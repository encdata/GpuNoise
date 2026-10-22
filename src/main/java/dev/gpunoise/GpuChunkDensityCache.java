package dev.gpunoise;

import java.util.concurrent.CompletionStage;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;

/** GPU cache containing the NoiseChunk cell-boundary grid for one chunk. */
public final class GpuChunkDensityCache {
    private static final boolean READ_STATS_ENABLED = Boolean.getBoolean("gpunoise.profileTerrain");
    private static final boolean FULL_BLOCK_OUTPUT = Boolean.parseBoolean(
            System.getProperty("gpunoise.fullBlockOutput", "true"));
    private static final AtomicBoolean BLOCK_OUTPUT_LOGGED = new AtomicBoolean();
    private static final ConcurrentHashMap<Long, GpuChunkDensityCache> PREFETCHED = new ConcurrentHashMap<>();
    private final int minX, minZ, minY, cellWidth, cellHeight, cellsY, maxRelativeY, gridX, gridZ;
    private final boolean powerOfTwoCells;
    private final int cellWidthShift, cellHeightShift;
    private final float inverseCellWidth, inverseCellHeight;
    private final GpuTerrainGrid grid;
    private final CompletionStage<GpuTerrainBatchResult> result;
    private final GpuWorldgenDependency exact;
    private final Object terrainGraphIdentity;
    /** Graph support is immutable for the lifetime of the graph-bound cache. */
    private final int supportedRootMask;
    // The completion stage publishes these fields before doFill is started.
    // They are immutable afterward; keeping them non-volatile avoids a pair
    // of volatile reads on every final-density lookup.
    private GpuTerrainRootsResult readyRoots;
    private GpuTerrainBlockResult readyBlocks;
    /** Root-to-slab views are resolved once, off the per-block terrain hot path. */
    private GpuTerrainBlockResult.RootView[] readyBlockRoots;
    private GpuTerrainBlockResult.RootView fullFinalDensityBlockRoot;
    private GpuTerrainBlockResult.RootView fullInitialDensityBlockRoot;
    private volatile GpuOreVeinDecisionTable oreVeinDecisions;
    private CompletableFuture<GpuOreVeinDecisionTable> oreVeinDecisionBuild;
    private volatile PositionalRandomFactory oreVeinRandomFactory;
    private final AtomicBoolean consumed = new AtomicBoolean();
    // A cache is consumed by one NoiseChunk fill task. After the first
    // observation, keep the hot path entirely non-atomic.
    private boolean consumptionObserved;
    private final AtomicBoolean submitted = new AtomicBoolean();
    private final AtomicBoolean completed = new AtomicBoolean();
    private static final AtomicLong cornerReads = new AtomicLong();
    private static final AtomicLong interpolatedReads = new AtomicLong();
    private static final AtomicLong blockReads = new AtomicLong();
    private static final AtomicLong lastReadLogBucket = new AtomicLong(-1L);
    // The compact cell grid still carries every compiled router root. Full
    // volume outputs are restricted to parity-verified material, aquifer, and
    // ore roots; unsupported or unverified roots stay on the Java path.
    private static final int ROOTS = GpuTerrainRoots.COUNT;

    private GpuChunkDensityCache(int minX, int minZ, NoiseSettings settings, GpuWorldgenDependency exact) {
        this.minX = minX;
        this.minZ = minZ;
        this.minY = settings.minY();
        this.cellWidth = settings.getCellWidth();
        this.cellHeight = settings.getCellHeight();
        this.powerOfTwoCells = Integer.bitCount(cellWidth) == 1 && Integer.bitCount(cellHeight) == 1;
        this.cellWidthShift = powerOfTwoCells ? Integer.numberOfTrailingZeros(cellWidth) : 0;
        this.cellHeightShift = powerOfTwoCells ? Integer.numberOfTrailingZeros(cellHeight) : 0;
        this.inverseCellWidth = 1.0f / cellWidth;
        this.inverseCellHeight = 1.0f / cellHeight;
        this.cellsY = settings.height() / cellHeight;
        this.maxRelativeY = cellsY * cellHeight;
        int width = 16 / cellWidth + 1;
        int depth = 16 / cellWidth + 1;
        this.gridX = width;
        this.gridZ = depth;
        this.grid = new GpuTerrainGrid(minX, minY, minZ, width, cellsY + 1, depth, cellWidth, cellHeight);
        this.exact = exact;
        this.terrainGraphIdentity = GpuTerrainRuntime.aquiferCacheGraphIdentity();
        this.supportedRootMask = GpuTerrainRuntime.supportedTerrainRootMask();
        int samples = width * (cellsY + 1) * depth;
        // The GPU evaluates the cell lattice and materializes final-density
        // values for every block. Minecraft retains aquifer orchestration and
        // the authoritative chunk writer.
        this.result = exact == null ? GpuTerrainRuntime.submitTerrainGrid(grid) : null;
    }

    public static GpuChunkDensityCache create(int minX, int minZ, NoiseSettings settings) {
        return new GpuChunkDensityCache(minX, minZ, settings, null);
    }

    public static GpuChunkDensityCache createStrict(int minX, int minZ, NoiseSettings settings,
                                                     PositionalRandomFactory aquiferRandom) {
        GpuWorldgenDependency.Builder builder = GpuWorldgenDependency.strictBuilderForChunk(minX, minZ,
                settings.minY(), settings.height(), settings.getCellWidth(), settings.getCellHeight());
        GpuWorldgenDependency.addAquiferCandidates(builder, aquiferRandom, minX, minZ, settings.minY(), settings.height());
        return new GpuChunkDensityCache(minX, minZ, settings, builder.build());
    }

    public static void prefetch(int minX, int minZ, NoiseSettings settings) {
        prefetchOwned(minX, minZ, () -> new GpuChunkDensityCache(minX, minZ, settings, null));
    }

    public static void prefetchStrict(int minX, int minZ, NoiseSettings settings, PositionalRandomFactory aquiferRandom) {
        prefetchOwned(minX, minZ, () -> createStrict(minX, minZ, settings, aquiferRandom));
    }

    private static void prefetchOwned(int minX, int minZ, java.util.function.Supplier<GpuChunkDensityCache> creator) {
        long key = chunkKey(minX >> 4, minZ >> 4);
        if (PREFETCHED.containsKey(key)) return;
        GpuChunkDensityCache cache = GpuTerrainAreaRegistry.claim(minX, minZ, creator);
        PREFETCHED.putIfAbsent(key, cache);
    }

    /**
     * Claims a complete 5x5 chunk neighborhood for the grid batcher.  The
     * requested center cache is owned by the caller; the surrounding caches
     * stay in PREFETCHED until their own fill starts.  PREFETCHED also acts as
     * the in-flight flag, so overlapping neighborhoods do not submit duplicate
     * GPU grids.
     */
    public static void prefetch5x5(int centerMinX, int centerMinZ, NoiseSettings settings) {
        for (int dz = -GpuTerrainAreaRegistry.RADIUS; dz <= GpuTerrainAreaRegistry.RADIUS; dz++) {
            for (int dx = -GpuTerrainAreaRegistry.RADIUS; dx <= GpuTerrainAreaRegistry.RADIUS; dx++) {
                if (dx == 0 && dz == 0) continue;
                int minX = centerMinX + dx * 16;
                int minZ = centerMinZ + dz * 16;
                GpuChunkDensityCache cache = GpuTerrainAreaRegistry.tryClaim(
                        minX, minZ, () -> create(minX, minZ, settings));
                if (cache != null) putPrefetched(minX, minZ, cache);
            }
        }
    }

    /** Enqueues one aligned 5x5 terrain tile the first time any tile chunk is requested. */
    public static void prefetch5x5ForChunk(int minX, int minZ, NoiseSettings settings) {
        int chunksPerTile = GpuTerrainAreaRegistry.RADIUS * 2 + 1;
        int chunkX = minX >> 4;
        int chunkZ = minZ >> 4;
        int tileX = Math.floorDiv(chunkX, chunksPerTile);
        int tileZ = Math.floorDiv(chunkZ, chunksPerTile);
        if (!GpuTerrainAreaRegistry.claimPrefetchTile(tileX, tileZ)) return;
        int centerChunkX = tileX * chunksPerTile + GpuTerrainAreaRegistry.RADIUS;
        int centerChunkZ = tileZ * chunksPerTile + GpuTerrainAreaRegistry.RADIUS;
        prefetch5x5(centerChunkX << 4, centerChunkZ << 4, settings);
    }

    public int minX() { return minX; }
    public int minZ() { return minZ; }
    public int minY() { return minY; }
    public int height() { return maxRelativeY; }
    public Object terrainGraphIdentity() { return terrainGraphIdentity; }

    boolean containsSurfaceColumn(int x, int z) {
        int rx = x - minX, rz = z - minZ;
        return rx >= 0 && rz >= 0 && rx <= 16 && rz <= 16;
    }

    /**
     * Resolves NoiseChunk's preliminary surface scan from this completed GPU
     * full-block GPU output. Returns Integer.MIN_VALUE when the exact column
     * is unavailable, the dispatch is incomplete, or a threshold-ambiguous
     * sample needs the Java scanner.
     */
    public int preliminarySurfaceLevelFromBlocks(int x, int z) {
        if (!supportsRoot(GpuTerrainRoots.INITIAL_DENSITY) || !containsSurfaceColumn(x, z)) {
            return Integer.MIN_VALUE;
        }
        GpuTerrainBlockResult blocks = completedBlocks();
        GpuTerrainBlockResult.RootView initialDensity = fullInitialDensityBlockRoot;
        if (blocks == null || initialDensity == null) {
            return Integer.MIN_VALUE;
        }
        int rx = x - minX, rz = z - minZ;
        if (rx >= 16 || rz >= 16) return Integer.MIN_VALUE;
        for (int y = minY + maxRelativeY - 1; y >= minY; y--) {
            int ry = y - minY;
            double density = initialDensity.valueAtUnchecked(rx, ry, rz);
            if (!Double.isFinite(density)
                    || Math.abs(density - 0.390625) <= GpuTerrainRuntime.GRAPH_PARITY_EPSILON) {
                return Integer.MIN_VALUE;
            }
            if (density > 0.390625) return y + 1;
        }
        return minY;
    }

    private GpuTerrainBlockResult completedBlocks() {
        if (exact != null || result == null) return null;
        try {
            CompletableFuture<GpuTerrainBatchResult> future = result.toCompletableFuture();
            if (!future.isDone() || future.isCompletedExceptionally() || future.isCancelled()) return null;
            GpuTerrainBatchResult completedResult = future.getNow(null);
            return completedResult == null ? null : completedResult.blocks();
        } catch (CancellationException | CompletionException unavailable) {
            return null;
        }
    }

    public static GpuChunkDensityCache takePrefetched(int minX, int minZ) {
        return PREFETCHED.remove(chunkKey(minX >> 4, minZ >> 4));
    }

    public static void putPrefetched(int minX, int minZ, GpuChunkDensityCache cache) {
        PREFETCHED.put(chunkKey(minX >> 4, minZ >> 4), cache);
    }

    static void clearPrefetched() {
        PREFETCHED.clear();
    }

    static void removePrefetched(GpuChunkDensityCache cache) {
        PREFETCHED.remove(chunkKey(cache.minX() >> 4, cache.minZ() >> 4), cache);
    }

    public CompletionStage<Void> ready() {
        // Publish the view before the coordinator's continuation is allowed
        // to enter NoiseChunk. This avoids a completion-order race where the
        // GPU future is complete but the volatile cache field is still null.
        if (exact != null) return exact.submit();
        return result.thenAccept(values -> {
            readyRoots = values.corners();
            readyBlocks = values.blocks();
            readyBlockRoots = createBlockRootViews(readyBlocks);
            fullFinalDensityBlockRoot = fullBlockRoot(GpuTerrainRoots.FINAL_DENSITY);
            fullInitialDensityBlockRoot = fullBlockRoot(GpuTerrainRoots.INITIAL_DENSITY);
            if (GpuTerrainRuntime.terrainBlockOutputEnabled()) validateBlockOutput();
        });
    }

    private static GpuTerrainBlockResult.RootView[] createBlockRootViews(GpuTerrainBlockResult blocks) {
        if (blocks == null || blocks.isEmpty()) return null;
        GpuTerrainBlockResult.RootView[] roots = new GpuTerrainBlockResult.RootView[ROOTS];
        for (int root = 0; root < roots.length; root++) roots[root] = blocks.rootView(root);
        return roots;
    }

    private GpuTerrainBlockResult.RootView fullBlockRoot(int root) {
        GpuTerrainBlockResult.RootView[] roots = readyBlockRoots;
        if (roots == null) return null;
        GpuTerrainBlockResult.RootView view = roots[root];
        return view != null && view.covers(16, maxRelativeY, 16) ? view : null;
    }

    boolean hasOreVeinBlockRoots() {
        return FULL_BLOCK_OUTPUT && exact == null
                && supportsRoot(GpuTerrainRoots.VEIN_TOGGLE)
                && supportsRoot(GpuTerrainRoots.VEIN_RIDGED)
                && supportsRoot(GpuTerrainRoots.VEIN_GAP)
                && fullBlockRoot(GpuTerrainRoots.VEIN_TOGGLE) != null
                && fullBlockRoot(GpuTerrainRoots.VEIN_RIDGED) != null
                && fullBlockRoot(GpuTerrainRoots.VEIN_GAP) != null;
    }

    double blockRootValue(int root, int x, int y, int z) {
        if (!supportsRoot(root) || exact != null) return Double.NaN;
        int localX = x - minX, localY = y - minY, localZ = z - minZ;
        if (localX < 0 || localX >= 16 || localY < 0 || localY >= maxRelativeY
                || localZ < 0 || localZ >= 16) return Double.NaN;
        GpuTerrainBlockResult.RootView view = fullBlockRoot(root);
        return view == null ? Double.NaN : view.valueAtUnchecked(localX, localY, localZ);
    }

    void packOreVeinRows(java.nio.ByteBuffer destination, PositionalRandomFactory randomFactory,
                         int firstRow, int count) {
        if (destination == null || randomFactory == null || firstRow < 0 || count <= 0
                || (long) firstRow + count > (long) maxRelativeY * 256
                || destination.remaining() < (long) count * GpuOreVeinDecisions.RECORD_BYTES
                || !hasOreVeinBlockRoots())
            throw new IllegalArgumentException("invalid ore-vein block-root packing range");
        GpuTerrainBlockResult.RootView toggle = fullBlockRoot(GpuTerrainRoots.VEIN_TOGGLE);
        GpuTerrainBlockResult.RootView ridged = fullBlockRoot(GpuTerrainRoots.VEIN_RIDGED);
        GpuTerrainBlockResult.RootView gap = fullBlockRoot(GpuTerrainRoots.VEIN_GAP);
        for (int row = firstRow; row < firstRow + count; row++) {
            int localY = row >>> 8;
            int localZ = (row >>> 4) & 15;
            int localX = row & 15;
            int x = minX + localX;
            int y = minY + localY;
            int z = minZ + localZ;
            GpuOreVeinDecisions.put(destination,
                    toggle.valueAtUnchecked(localX, localY, localZ),
                    ridged.valueAtUnchecked(localX, localY, localZ),
                    gap.valueAtUnchecked(localX, localY, localZ), x, y, z, randomFactory);
        }
    }

    public synchronized CompletionStage<GpuOreVeinDecisionTable> prepareOreVeinDecisions(
            PositionalRandomFactory randomFactory) {
        if (oreVeinDecisionBuild != null) return oreVeinDecisionBuild;
        oreVeinRandomFactory = randomFactory;
        try {
            oreVeinDecisionBuild = GpuOreVeinDecisionTable.build(this, randomFactory)
                    .thenApply(table -> {
                        oreVeinDecisions = table;
                        return table;
                    }).toCompletableFuture();
        } catch (RuntimeException failure) {
            oreVeinDecisionBuild = CompletableFuture.failedFuture(failure);
        }
        return oreVeinDecisionBuild;
    }

    public GpuOreVeinDecisionTable oreVeinDecisions() { return oreVeinDecisions; }
    public boolean matchesOreVeinRandomFactory(PositionalRandomFactory randomFactory) {
        return oreVeinRandomFactory == randomFactory;
    }

    public boolean matchesChunk(int minX, int minZ) {
        return this.minX == minX && this.minZ == minZ;
    }

    public boolean markConsumed() {
        if (consumptionObserved) return false;
        if (!consumed.compareAndSet(false, true)) {
            consumptionObserved = true;
            return false;
        }
        consumptionObserved = true;
        return true;
    }

    boolean markSubmitted() { return submitted.compareAndSet(false, true); }
    boolean markCompleted() { return completed.compareAndSet(false, true); }

    /** Exact GPU block result or exact cell-corner sample, with Java fallback outside those points. */
    public double density(DensityFunction.FunctionContext context) {
        if (!supportsRoot(GpuTerrainRoots.FINAL_DENSITY)) return Double.NaN;
        int rx = context.blockX() - minX;
        int ry = context.blockY() - minY;
        int rz = context.blockZ() - minZ;
        if ((rx | ry | rz) < 0 || rx > 16 || rz > 16 || ry > maxRelativeY) return Double.NaN;
        if (exact != null) return sampleExactRoot(rx, ry, rz, GpuTerrainRoots.FINAL_DENSITY);
        if (GpuTerrainRuntime.terrainBlockOutputEnabled()) {
            GpuTerrainBlockResult.RootView blocks = fullFinalDensityBlockRoot;
            if (blocks != null && rx < 16 && ry < maxRelativeY && rz < 16) {
                if (READ_STATS_ENABLED) {
                    long direct = blockReads.incrementAndGet();
                    if ((direct & 0x3ffffL) == 0L) logReadStatistics();
                }
                return blocks.valueAtUnchecked(rx, ry, rz);
            }
        }
        GpuTerrainRootsResult ready = readyRoots;
        if (ready == null) return Double.NaN;
        boolean aligned = powerOfTwoCells
                ? ((rx & (cellWidth - 1)) | (ry & (cellHeight - 1)) | (rz & (cellWidth - 1))) == 0
                : rx % cellWidth == 0 && ry % cellHeight == 0 && rz % cellWidth == 0;
        if (rx <= 16 && rz <= 16 && ry <= maxRelativeY && aligned) {
            int gx = rx / cellWidth;
            int gy = ry / cellHeight;
            int gz = rz / cellWidth;
            if (gx < gridX && gy <= cellsY && gz < gridZ) {
                if (READ_STATS_ENABLED) {
                    long corners = cornerReads.incrementAndGet();
                    if ((corners & 0x3ffffL) == 0L) logReadStatistics();
                }
                return ready.valueAt((gy * gridX + gx) * gridZ + gz, GpuTerrainRoots.FINAL_DENSITY);
            }
        }
        if (READ_STATS_ENABLED) {
            long interpolated = interpolatedReads.incrementAndGet();
            if ((interpolated & 0x3ffffL) == 0L) logReadStatistics();
        }
        return sampleRoot(ready, rx, ry, rz, GpuTerrainRoots.FINAL_DENSITY);
    }

    /** Hot path used by NoiseChunk's final-density graph redirect. */
    public double finalDensity(int x, int y, int z) {
        if (!supportsRoot(GpuTerrainRoots.FINAL_DENSITY)) return Double.NaN;
        int rx = x - minX;
        int ry = y - minY;
        int rz = z - minZ;
        if ((rx | ry | rz) < 0 || rx > 16 || rz > 16 || ry > maxRelativeY) return Double.NaN;
        if (exact != null) return sampleExactRoot(rx, ry, rz, GpuTerrainRoots.FINAL_DENSITY);
        if (FULL_BLOCK_OUTPUT) {
            GpuTerrainBlockResult.RootView blocks = fullFinalDensityBlockRoot;
            if (blocks != null && rx < 16 && ry < maxRelativeY && rz < 16) {
                if (READ_STATS_ENABLED) {
                    long direct = blockReads.incrementAndGet();
                    if ((direct & 0x3ffffL) == 0L) logReadStatistics();
                }
                return blocks.valueAtUnchecked(rx, ry, rz);
            }
        }
        GpuTerrainRootsResult ready = readyRoots;
        if (ready == null) return Double.NaN;
        boolean aligned = powerOfTwoCells
                ? ((rx & (cellWidth - 1)) | (ry & (cellHeight - 1)) | (rz & (cellWidth - 1))) == 0
                : rx % cellWidth == 0 && ry % cellHeight == 0 && rz % cellWidth == 0;
        if (aligned) {
            int gx = rx / cellWidth;
            int gy = ry / cellHeight;
            int gz = rz / cellWidth;
            if (gx < gridX && gy <= cellsY && gz < gridZ) {
                if (READ_STATS_ENABLED) {
                    long corners = cornerReads.incrementAndGet();
                    if ((corners & 0x3ffffL) == 0L) logReadStatistics();
                }
                return ready.valueAt((gy * gridX + gx) * gridZ + gz, GpuTerrainRoots.FINAL_DENSITY);
            }
        }
        if (READ_STATS_ENABLED) {
            long interpolated = interpolatedReads.incrementAndGet();
            if ((interpolated & 0x3ffffL) == 0L) logReadStatistics();
        }
        return sampleRoot(ready, rx, ry, rz, GpuTerrainRoots.FINAL_DENSITY);
    }

    private static void logReadStatistics() {
        long total = blockReads.get() + cornerReads.get() + interpolatedReads.get();
        long bucket = total >>> 24;
        if (lastReadLogBucket.getAndSet(bucket) == bucket) return;
        GpuNoise.LOGGER.info("GPU terrain density reads: blockReads={}, cornerReads={}, interpolatedReads={}",
                blockReads.get(), cornerReads.get(), interpolatedReads.get());
    }

    public double initialDensity(int x, int y, int z) {
        if (!supportsRoot(GpuTerrainRoots.INITIAL_DENSITY)) return Double.NaN;
        if (exact != null) return exact.valueAt(GpuTerrainRoots.INITIAL_DENSITY, x, y, z);
        int rx = x - minX, ry = y - minY, rz = z - minZ;
        GpuTerrainBlockResult.RootView fullBlocks = fullInitialDensityBlockRoot;
        if (fullBlocks != null && rx >= 0 && rx < 16 && ry >= 0 && ry < maxRelativeY
                && rz >= 0 && rz < 16) {
            return fullBlocks.valueAtUnchecked(rx, ry, rz);
        }
        double blockValue = sampleBlockRoot(rx, ry, rz, GpuTerrainRoots.INITIAL_DENSITY);
        if (!Double.isNaN(blockValue)) return blockValue;
        return sampleRoot(readyRoots, rx, ry, rz, GpuTerrainRoots.INITIAL_DENSITY);
    }

    public boolean contains(int x, int y, int z) {
        int rx = x - minX, ry = y - minY, rz = z - minZ;
        if (exact != null) return exact.contains(x, y, z);
        return rx >= 0 && rz >= 0 && ry >= 0 && rx <= 16 && rz <= 16
                && ry <= maxRelativeY;
    }
    public boolean supportsRoot(int root) {
        return root >= 0 && root < ROOTS && (supportedRootMask & (1 << root)) != 0;
    }
    public boolean hasFullBlockOutput() {
        GpuTerrainBlockResult blocks = readyBlocks;
        return FULL_BLOCK_OUTPUT && blocks != null && !blocks.isEmpty();
    }

    /** Returns a prebound exact-root reader for full-volume CPU consumers such as the aquifer scan. */
    public FinalDensityBlockView finalDensityBlockView() {
        if (!supportsRoot(GpuTerrainRoots.FINAL_DENSITY) || exact != null || !FULL_BLOCK_OUTPUT) return null;
        GpuTerrainBlockResult blocks = readyBlocks;
        if (blocks == null || blocks.isEmpty()) return null;
        GpuTerrainBlockResult.RootView root = blocks.rootView(GpuTerrainRoots.FINAL_DENSITY);
        return root == null || !root.covers(16, maxRelativeY, 16)
                ? null : new FinalDensityBlockView(root);
    }

    /** Narrow, public read contract for callers outside the cache package. */
    public static final class FinalDensityBlockView {
        private final GpuTerrainBlockResult.RootView root;

        private FinalDensityBlockView(GpuTerrainBlockResult.RootView root) {
            this.root = root;
        }

        public double valueAtLocalUnchecked(int x, int y, int z) {
            return root.valueAtUnchecked(x, y, z);
        }

        public void recordReads(long count) {
            if (!READ_STATS_ENABLED || count <= 0L) return;
            long direct = blockReads.addAndGet(count);
            if ((direct & 0x3ffffL) < count) logReadStatistics();
        }
    }
    public double aquiferFloodedness(int x, int y, int z) {
        return rootDensity(x, y, z, GpuTerrainRoots.FLOODEDNESS);
    }

    public double aquiferBarrier(int x, int y, int z) { return rootDensity(x, y, z, GpuTerrainRoots.BARRIER); }
    public double aquiferSpread(int x, int y, int z) { return rootDensity(x, y, z, GpuTerrainRoots.SPREAD); }
    public double aquiferLava(int x, int y, int z) { return rootDensity(x, y, z, GpuTerrainRoots.LAVA); }
    public double aquiferErosion(int x, int y, int z) { return rootDensity(x, y, z, GpuTerrainRoots.EROSION); }
    public double aquiferDepth(int x, int y, int z) { return rootDensity(x, y, z, GpuTerrainRoots.DEPTH); }
    public double climate(int x, int y, int z, int root) { return rootDensity(x, y, z, root); }

    public boolean supportsBiomeClimateRoots() {
        return supportsRoot(GpuTerrainRoots.TEMPERATURE)
                && supportsRoot(GpuTerrainRoots.VEGETATION)
                && supportsRoot(GpuTerrainRoots.CONTINENTS)
                && supportsRoot(GpuTerrainRoots.EROSION)
                && supportsRoot(GpuTerrainRoots.DEPTH)
                && supportsRoot(GpuTerrainRoots.RIDGES);
    }

    /** Uses the exact candidate coordinates already declared by a strict chunk dependency. */
    public CompletionStage<int[]> selectNearestAquiferCenters(PositionalRandomFactory factory,
                                                               int[][] blockQueries) {
        if (exact == null) return CompletableFuture.failedStage(new IllegalStateException(
                "nearest aquifer GPU selection requires a strict terrain dependency"));
        return exact.selectNearestAquiferCenters(factory, blockQueries);
    }

    /** Exact graph-root value at a NoiseChunk cell corner. */
    public double cornerDensity(int x, int y, int z) {
        if (!supportsRoot(GpuTerrainRoots.FINAL_DENSITY)) return Double.NaN;
        int rx = x - minX, ry = y - minY, rz = z - minZ;
        if (exact != null) return exact.valueAt(GpuTerrainRoots.FINAL_DENSITY, x, y, z);
        GpuTerrainRootsResult corners = readyRoots;
        if (corners == null || rx < 0 || rz < 0 || ry < 0
                || rx % cellWidth != 0 || rz % cellWidth != 0 || ry % cellHeight != 0) return Double.NaN;
        int gx = rx / cellWidth, gy = ry / cellHeight, gz = rz / cellWidth;
        if (gx >= gridX || gz >= gridZ || gy > cellsY) return Double.NaN;
        return corners.valueAt((gy * gridX + gx) * gridZ + gz, GpuTerrainRoots.FINAL_DENSITY);
    }

    private double rootDensity(int x, int y, int z, int root) {
        if (!supportsRoot(root)) return Double.NaN;
        if (exact != null) return exact.valueAt(root, x, y, z);
        double blockValue = sampleBlockRoot(x - minX, y - minY, z - minZ, root);
        if (!Double.isNaN(blockValue)) return blockValue;
        return sampleRoot(readyRoots, x - minX, y - minY, z - minZ, root);
    }

    private double sampleBlockRoot(int rx, int ry, int rz, int root) {
        GpuTerrainBlockResult.RootView[] roots = readyBlockRoots;
        if (roots == null || root < 0 || root >= roots.length) return Double.NaN;
        GpuTerrainBlockResult.RootView view = roots[root];
        if (view == null || !view.contains(rx, ry, rz)) return Double.NaN;
        return view.valueAtUnchecked(rx, ry, rz);
    }

    private double sampleRoot(int rx, int ry, int rz, int root) {
        if (!supportsRoot(root)) return Double.NaN;
        return sampleRoot(readyRoots, rx, ry, rz, root);
    }

    /** Reads an exact corner sample; non-corner values require verified per-block GPU output. */
    private double sampleRoot(GpuTerrainRootsResult values, int rx, int ry, int rz, int root) {
        // Do not approximate arbitrary root evaluation by interpolating the
        // final root's corners. NoiseChunk interpolates marked subexpressions
        // and then evaluates the rest of the graph at each block, which is
        // generally not equivalent for nonlinear roots. The GPU block pass
        // serves verified per-block roots above; otherwise let the caller use
        // its original Java function except at exact, parity-checked corners.
        if (values == null || root < 0 || root >= ROOTS || rx < 0 || rz < 0 || ry < 0
                || rx > 16 || rz > 16 || ry > cellsY * cellHeight) return Double.NaN;
        boolean aligned = powerOfTwoCells
                ? ((rx & (cellWidth - 1)) | (ry & (cellHeight - 1)) | (rz & (cellWidth - 1))) == 0
                : rx % cellWidth == 0 && ry % cellHeight == 0 && rz % cellWidth == 0;
        if (!aligned) return Double.NaN;
        int gx = powerOfTwoCells ? rx >> cellWidthShift : rx / cellWidth;
        int gy = powerOfTwoCells ? ry >> cellHeightShift : ry / cellHeight;
        int gz = powerOfTwoCells ? rz >> cellWidthShift : rz / cellWidth;
        if (gx >= gridX || gy > cellsY || gz >= gridZ || root >= values.roots()) return Double.NaN;
        return values.valueAt((gy * gridX + gx) * gridZ + gz, root);
    }

    /** Checks that the GPU returned the complete block volume and every selected root. */
    private void validateBlockOutput() {
        GpuTerrainBlockResult blocks = readyBlocks;
        if (blocks == null || blocks.isEmpty()) {
            throw new IllegalStateException("GPU block output was enabled but returned no values");
        }
        int rootCount = GpuTerrainRuntime.terrainBlockOutputRootCount();
        int[] rootIndexes = GpuTerrainRuntime.terrainBlockOutputRootIndexes();
        boolean missingRoot = blocks.rootCount() != rootIndexes.length;
        for (int root : rootIndexes) missingRoot |= blocks.outputIndexForRoot(root) < 0;
        if (missingRoot || blocks.rootCount() != rootCount
                || !blocks.contains(15, maxRelativeY - 1, 15)) {
            throw new IllegalStateException("GPU block output is incomplete: roots=" + blocks.rootCount()
                    + ", expectedRoots=" + rootCount + ", maxY=" + maxRelativeY);
        }
        if (BLOCK_OUTPUT_LOGGED.compareAndSet(false, true)) {
            GpuDensityGraph graph = GpuTerrainRuntime.currentTerrainGraph();
            int interpolatedMarkers = graph == null ? 0 : graph.interpolatedMarkerCount();
            GpuNoise.LOGGER.info("GPU per-block terrain output materialized: blocks={}x{}x16, roots={}, interpolatedMarkers={}",
                    16, maxRelativeY, blocks.rootCount(), interpolatedMarkers);
        }
    }

    private double sampleExactRoot(int rx, int ry, int rz, int root) {
        if (rx < 0 || rz < 0 || ry < 0 || rx >= 16 || rz >= 16 || ry >= cellsY * cellHeight) return Double.NaN;
        int gx = Math.min(rx / cellWidth, gridX - 2), gy = Math.min(ry / cellHeight, cellsY - 1), gz = Math.min(rz / cellWidth, gridZ - 2);
        float tx = (float) (rx - gx * cellWidth) / cellWidth, ty = (float) (ry - gy * cellHeight) / cellHeight,
                tz = (float) (rz - gz * cellWidth) / cellWidth;
        return lerp3(exactValue(gx, gy, gz, root), exactValue(gx + 1, gy, gz, root),
                exactValue(gx, gy + 1, gz, root), exactValue(gx + 1, gy + 1, gz, root),
                exactValue(gx, gy, gz + 1, root), exactValue(gx + 1, gy, gz + 1, root),
                exactValue(gx, gy + 1, gz + 1, root), exactValue(gx + 1, gy + 1, gz + 1, root), tx, ty, tz);
    }

    private float exactValue(int gx, int gy, int gz, int root) {
        return (float) exact.valueAt(root, minX + gx * cellWidth, minY + gy * cellHeight, minZ + gz * cellWidth);
    }

    private static double lerp3(float c000, float c100, float c010, float c110,
                                float c001, float c101, float c011, float c111,
                                float tx, float ty, float tz) {
        // Match Mth.lerp3(x, y, z, ...): interpolate Y first, then X,
        // then Z.  The previous implementation interpolated Z and Y in
        // the opposite order, which transposed the vertical/depth axes and
        // produced the characteristic striped terrain.
        float y00 = c000 + (c010 - c000) * ty;
        float y10 = c100 + (c110 - c100) * ty;
        float y01 = c001 + (c011 - c001) * ty;
        float y11 = c101 + (c111 - c101) * ty;
        float x0 = y00 + (y10 - y00) * tx;
        float x1 = y01 + (y11 - y01) * tx;
        return x0 + (x1 - x0) * tz;
    }

    private static long chunkKey(int x, int z) { return ((long)x << 32) ^ (z & 0xffffffffL); }
}
