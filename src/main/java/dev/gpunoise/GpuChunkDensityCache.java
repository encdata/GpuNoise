package dev.gpunoise;

import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;

/** GPU cache containing the NoiseChunk cell-boundary grid for one chunk. */
public final class GpuChunkDensityCache {
    private static final boolean READ_STATS_ENABLED = Boolean.getBoolean("gpunoise.profileTerrain");
    private static final ConcurrentHashMap<Long, GpuChunkDensityCache> PREFETCHED = new ConcurrentHashMap<>();
    private final int minX, minZ, minY, cellWidth, cellHeight, cellsY, maxRelativeY, gridX, gridZ;
    private final boolean powerOfTwoCells;
    private final int cellWidthShift, cellHeightShift;
    private final float inverseCellWidth, inverseCellHeight;
    private final GpuTerrainGrid grid;
    private final CompletionStage<GpuTerrainBatchResult> result;
    private final GpuWorldgenDependency exact;
    // The completion stage publishes these fields before doFill is started.
    // They are immutable afterward; keeping them non-volatile avoids a pair
    // of volatile reads on every final-density lookup.
    private GpuTerrainRootsResult readyRoots;
    private GpuTerrainBlockResult readyBlocks;
    private final AtomicBoolean consumed = new AtomicBoolean();
    private final AtomicBoolean submitted = new AtomicBoolean();
    private final AtomicBoolean completed = new AtomicBoolean();
    private static final AtomicLong cornerReads = new AtomicLong();
    private static final AtomicLong interpolatedReads = new AtomicLong();
    private static final AtomicLong blockReads = new AtomicLong();
    private static final AtomicLong lastReadLogBucket = new AtomicLong(-1L);
    // doFill walks all block positions of a cell consecutively. Reuse the
    // eight immutable final-density corners while evaluating that run.
    private int cachedFinalGx = Integer.MIN_VALUE;
    private int cachedFinalGy;
    private int cachedFinalGz;
    private float cachedFinal000, cachedFinal100, cachedFinal010, cachedFinal110;
    private float cachedFinal001, cachedFinal101, cachedFinal011, cachedFinal111;
    // Terrain graph roots: final, initial, barrier, floodedness, spread, lava,
    // erosion, and depth.
    // All three are interpolated in one dispatch and kept in the same result
    // view so aquifer lookups do not trigger another GPU submission.
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
        int samples = width * (cellsY + 1) * depth;
        // Only upload/evaluate the NoiseChunk cell-boundary grid. The CPU
        // keeps Minecraft's interpolation and block writer, avoiding the
        // expensive full-block GPU output path.
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
        PREFETCHED.computeIfAbsent(chunkKey(minX >> 4, minZ >> 4),
                ignored -> new GpuChunkDensityCache(minX, minZ, settings, null));
    }

    public static void prefetchStrict(int minX, int minZ, NoiseSettings settings, PositionalRandomFactory aquiferRandom) {
        PREFETCHED.computeIfAbsent(chunkKey(minX >> 4, minZ >> 4),
                ignored -> createStrict(minX, minZ, settings, aquiferRandom));
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
                GpuTerrainAreaRegistry.claim(minX, minZ, () -> create(minX, minZ, settings));
            }
        }
    }

    int minX() { return minX; }
    int minZ() { return minZ; }

    public static GpuChunkDensityCache takePrefetched(int minX, int minZ) {
        return PREFETCHED.remove(chunkKey(minX >> 4, minZ >> 4));
    }

    public static void putPrefetched(int minX, int minZ, GpuChunkDensityCache cache) {
        PREFETCHED.put(chunkKey(minX >> 4, minZ >> 4), cache);
    }

    public CompletionStage<Void> ready() {
        // Publish the view before the coordinator's continuation is allowed
        // to enter NoiseChunk. This avoids a completion-order race where the
        // GPU future is complete but the volatile cache field is still null.
        if (exact != null) return exact.submit();
        return result.thenAccept(values -> {
            readyRoots = values.corners();
            readyBlocks = values.blocks();
        });
    }

    public boolean matchesChunk(int minX, int minZ) {
        return this.minX == minX && this.minZ == minZ;
    }

    public boolean markConsumed() {
        return consumed.compareAndSet(false, true);
    }

    public boolean isConsumed() {
        return consumed.get();
    }

    boolean markSubmitted() { return submitted.compareAndSet(false, true); }
    boolean markCompleted() { return completed.compareAndSet(false, true); }

    /** CPU trilinear interpolation over the same cell grid used by NoiseChunk. */
    public double density(DensityFunction.FunctionContext context) {
        return density(context.blockX(), context.blockY(), context.blockZ());
    }

    /** Allocation-free final-density lookup for NoiseChunk cell filling. */
    public double density(int x, int y, int z) {
        int rx = x - minX;
        int ry = y - minY;
        int rz = z - minZ;
        if ((rx | ry | rz) < 0 || rx > 16 || rz > 16 || ry > maxRelativeY) return Double.NaN;
        if (exact != null) return sampleExactRoot(rx, ry, rz, GpuTerrainRoots.FINAL_DENSITY);
        GpuTerrainRootsResult ready = readyRoots;
        if (ready == null) return Double.NaN;
        if (GpuTerrainRuntime.terrainBlockOutputEnabled()) {
            GpuTerrainBlockResult blocks = readyBlocks;
            if (blocks != null && !blocks.isEmpty()
                    && rx < 16 && ry < maxRelativeY && rz < 16) {
                if (READ_STATS_ENABLED) {
                    long direct = blockReads.incrementAndGet();
                    if ((direct & 0x3ffffL) == 0L) logReadStatistics();
                }
                return blocks.finalValueAtUnchecked(rx, ry, rz);
            }
        }
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
        return sampleRoot(rx, ry, rz, GpuTerrainRoots.FINAL_DENSITY);
    }

    private static void logReadStatistics() {
        long total = blockReads.get() + cornerReads.get() + interpolatedReads.get();
        long bucket = total >>> 24;
        if (lastReadLogBucket.getAndSet(bucket) == bucket) return;
        GpuNoise.LOGGER.info("GPU terrain density reads: blockReads={}, cornerReads={}, interpolatedReads={}",
                blockReads.get(), cornerReads.get(), interpolatedReads.get());
    }

    public double initialDensity(int x, int y, int z) {
        if (exact != null) return exact.valueAt(GpuTerrainRoots.INITIAL_DENSITY, x, y, z);
        double block = sampleBlockRoot(x - minX, y - minY, z - minZ, GpuTerrainRoots.INITIAL_DENSITY);
        return Double.isNaN(block) ? sampleRoot(x - minX, y - minY, z - minZ, GpuTerrainRoots.INITIAL_DENSITY) : block;
    }

    public boolean contains(int x, int y, int z) {
        int rx = x - minX, ry = y - minY, rz = z - minZ;
        if (exact != null) return exact.contains(x, y, z);
        return rx >= 0 && rz >= 0 && ry >= 0 && rx <= 16 && rz <= 16
                && ry <= maxRelativeY;
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

    /** Exact graph-root value at a NoiseChunk cell corner. */
    public double cornerDensity(int x, int y, int z) {
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
        if (exact != null) return exact.valueAt(root, x, y, z);
        double block = sampleBlockRoot(x - minX, y - minY, z - minZ, root);
        return Double.isNaN(block) ? sampleRoot(x - minX, y - minY, z - minZ, root) : block;
    }

    private double sampleBlockRoot(int rx, int ry, int rz, int root) {
        GpuTerrainBlockResult blocks = readyBlocks;
        // The compact block buffer currently materializes final density only.
        // Climate/initial roots continue through the corner/interpolation path.
        if (root != GpuTerrainRoots.FINAL_DENSITY
                || blocks == null || !blocks.contains(rx, ry, rz)) return Double.NaN;
        return blocks.valueAt(rx, ry, rz, root);
    }

    private double sampleRoot(int rx, int ry, int rz, int root) {
        GpuTerrainRootsResult values = readyRoots;
        // The upper and east/south boundaries are real NoiseChunk samples:
        // preliminary surface lookup evaluates at minY + height, and the
        // cell grid itself includes x/z = 16. Use the final cell with t=1
        // for those endpoints instead of falling back to CPU evaluation.
        if (values == null || root < 0 || root >= ROOTS || rx < 0 || rz < 0 || ry < 0
                || rx > 16 || rz > 16 || ry > cellsY * cellHeight) return Double.NaN;
        int gx, gy, gz;
        float tx, ty, tz;
        if (powerOfTwoCells) {
            gx = Math.min(rx >> cellWidthShift, gridX - 2);
            gy = Math.min(ry >> cellHeightShift, cellsY - 1);
            gz = Math.min(rz >> cellWidthShift, gridZ - 2);
            tx = (rx - (gx << cellWidthShift)) * inverseCellWidth;
            ty = (ry - (gy << cellHeightShift)) * inverseCellHeight;
            tz = (rz - (gz << cellWidthShift)) * inverseCellWidth;
        } else {
            gx = Math.min(rx / cellWidth, gridX - 2);
            gy = Math.min(ry / cellHeight, cellsY - 1);
            gz = Math.min(rz / cellWidth, gridZ - 2);
            tx = (float)(rx - gx * cellWidth) * inverseCellWidth;
            ty = (float)(ry - gy * cellHeight) * inverseCellHeight;
            tz = (float)(rz - gz * cellWidth) * inverseCellWidth;
        }
        float c000, c100, c010, c110, c001, c101, c011, c111;
        if (root == GpuTerrainRoots.FINAL_DENSITY
                && gx == cachedFinalGx && gy == cachedFinalGy && gz == cachedFinalGz) {
            c000 = cachedFinal000;
            c100 = cachedFinal100;
            c010 = cachedFinal010;
            c110 = cachedFinal110;
            c001 = cachedFinal001;
            c101 = cachedFinal101;
            c011 = cachedFinal011;
            c111 = cachedFinal111;
        } else {
            c000 = value(values, gx, gy, gz, root);
            c100 = value(values, gx + 1, gy, gz, root);
            c010 = value(values, gx, gy + 1, gz, root);
            c110 = value(values, gx + 1, gy + 1, gz, root);
            c001 = value(values, gx, gy, gz + 1, root);
            c101 = value(values, gx + 1, gy, gz + 1, root);
            c011 = value(values, gx, gy + 1, gz + 1, root);
            c111 = value(values, gx + 1, gy + 1, gz + 1, root);
            if (root == GpuTerrainRoots.FINAL_DENSITY) {
                cachedFinalGx = gx;
                cachedFinalGy = gy;
                cachedFinalGz = gz;
                cachedFinal000 = c000;
                cachedFinal100 = c100;
                cachedFinal010 = c010;
                cachedFinal110 = c110;
                cachedFinal001 = c001;
                cachedFinal101 = c101;
                cachedFinal011 = c011;
                cachedFinal111 = c111;
            }
        }
        return lerp3(c000, c100, c010, c110, c001, c101, c011, c111, tx, ty, tz);
    }

    private float value(GpuTerrainRootsResult values, int gx, int gy, int gz, int root) {
        return values.valueAtUnchecked((gy * gridX + gx) * gridZ + gz, root);
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
        float x00 = c000 + (c100 - c000) * tx;
        float x10 = c010 + (c110 - c010) * tx;
        float x01 = c001 + (c101 - c001) * tx;
        float x11 = c011 + (c111 - c011) * tx;
        return (x00 + (x10 - x00) * tz) + ((x01 + (x11 - x01) * tz)
                - (x00 + (x10 - x00) * tz)) * ty;
    }

    private static long chunkKey(int x, int z) { return ((long)x << 32) ^ (z & 0xffffffffL); }
}
