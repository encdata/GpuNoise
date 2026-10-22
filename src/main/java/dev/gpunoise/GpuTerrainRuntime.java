package dev.gpunoise;

import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.core.QuartPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Runtime bridge used by the vanilla DensityFunctions.Noise fill hook. */
public final class GpuTerrainRuntime {
    public static final float GRAPH_PARITY_EPSILON = 5.0E-3f;
    private static final long STARTUP_PARITY_TIMEOUT_SECONDS = Math.max(1L,
            Long.getLong("gpunoise.startupParityTimeoutSeconds", 120L));
    private static volatile GpuDevice device;
    private static volatile PersistentVulkanNormalNoiseEngine engine;
    private static volatile VulkanSubmissionRing submissionRing;
    private static final Map<NormalNoise, MinecraftNormalNoiseData> samplers = new ConcurrentHashMap<>();
    private static volatile GpuGlobalSamplerQueue globalQueue;
    private static volatile GpuTerrainBatchScheduler terrainScheduler;
    private static volatile GpuChunkFillCoordinator chunkFillCoordinator;
    private static volatile GpuFusedChunkBatchScheduler fusedChunkScheduler;
    private static volatile GpuTerrainRootsBatchScheduler terrainRootsScheduler;
    private static volatile GpuTerrainRegionWriter terrainRegionWriter;
    private static volatile GpuBiomeSearchBatchScheduler biomeSearchScheduler;
    private static volatile GpuTerrainGraphProvider terrainProvider;
    private static volatile VulkanSubmissionRing terrainRing;
    private static volatile GpuDensityGraph terrainGraph;
    /** The exact router used to build and parity-check terrainGraph. */
    private static volatile NoiseRouter terrainRouter;
    private static volatile GpuBiomeSearchTree biomeSearchTree;
    /** GPU-interpolated block densities; disable with gpunoise.fullBlockOutput=false. */
    private static final boolean FULL_BLOCK_OUTPUT = Boolean.parseBoolean(
            System.getProperty("gpunoise.fullBlockOutput", "true"));
    /** CI probe for block roots whose Java reference requires NoiseChunk interpolation state. */
    private static final boolean EXPERIMENTAL_INTERPOLATED_BLOCK_OUTPUT = Boolean.getBoolean(
            "gpunoise.experimentalInterpolatedBlockOutput");
    private static final GpuChunkPipelineGate chunkPipelineGate = new GpuChunkPipelineGate(
            Math.max(16, Runtime.getRuntime().availableProcessors() * 2));
    /** Roots verified against both the compact graph and real cell-grid layout. */
    private static volatile int verifiedTerrainRootMask;
    /** Roots additionally checked as exact per-block values without NoiseChunk markers. */
    private static volatile int verifiedTerrainBlockRootMask;
    /** Marker-dependent roots included only by the explicit runtime-parity probe. */
    private static volatile int interpolatedTerrainBlockRootMask;
    private static volatile boolean terrainBlockOutputVerificationComplete;
    /** Immutable root ordering shared by every chunk after startup verification. */
    private static volatile TerrainBlockOutputLayout terrainBlockOutputLayout;
    private static final ThreadLocal<Boolean> GPU_DENSITY_REQUIRED =
            ThreadLocal.withInitial(() -> Boolean.FALSE);
    private static final ThreadLocal<GpuChunkDensityCache> ACTIVE_FILL_CACHE =
            new ThreadLocal<>();
    private static final AtomicLong gpuCalls = new AtomicLong();
    private static final AtomicLong gpuSamples = new AtomicLong();
    private static final AtomicLong gpuNanos = new AtomicLong();
    private static final AtomicLong gpuChunksSubmitted = new AtomicLong();
    private static final AtomicLong gpuChunksCompleted = new AtomicLong();
    private static final AtomicLong gpuChunksConsumed = new AtomicLong();
    private static final AtomicLong cpuFallbackChunks = new AtomicLong();
    private static final AtomicLong lastChunkStatisticsBucket = new AtomicLong(-1L);
    private GpuTerrainRuntime() {}

    private record TerrainBlockOutputLayout(int mask, int[] rootIndexes) {}

    public static synchronized boolean start(GpuDevice selected) {
        if (selected == null) return false;
        return start(java.util.List.of(selected));
    }

    /** Starts a shared bounded submission pool over the preferred Vulkan devices. */
    public static synchronized boolean start(java.util.List<GpuDevice> candidates) {
        if (candidates == null || candidates.isEmpty()) return false;
        try {
            submissionRing = VulkanBootstrapThread.call(() -> new VulkanSubmissionRing(candidates));
            // Startup parity shares the primary slot; terrain dispatches fan
            // out across every available engine in the device pool.
            engine = submissionRing.primary();
            device = submissionRing.primaryDevice();
            return true;
        } catch (Exception failure) {
            GpuNoise.LOGGER.error("Persistent Vulkan terrain engine failed to initialize", failure);
            engine = null;
            if (submissionRing != null) submissionRing.close();
            submissionRing = null;
            device = null;
            return false;
        }
    }
    static GpuDevice selectedDevice() { return device; }
    static int activeSubmissionSlotCount() {
        VulkanSubmissionRing ring = submissionRing;
        return ring == null ? 1 : ring.slotCount();
    }
    static int activeDeviceCount() {
        VulkanSubmissionRing ring = submissionRing;
        return ring == null ? 0 : ring.devices().size();
    }
    public static boolean active() { return device != null; }

    /** Stable identity for bounded caches whose values depend on this terrain router. */
    public static Object aquiferCacheGraphIdentity() { return terrainRouter; }

    /** Looks up an already-claimed neighboring terrain cache without claiming new work. */
    public static GpuChunkDensityCache terrainCacheForSurface(int blockX, int blockZ, Object graphIdentity) {
        return GpuTerrainAreaRegistry.findSurfaceCache(blockX, blockZ, graphIdentity);
    }

    /** The biome-stage cache wrapper is only safe with C2ME's staged flow. */
    public static boolean c2meDfcAvailable() {
        return classAvailable("com.ishland.c2me.opts.dfc.common.gen.jvm.AbstractCompiledDensityFunction");
    }

    private static boolean classAvailable(String name) {
        Set<ClassLoader> loaders = new LinkedHashSet<>();
        addLoaderChain(loaders, GpuTerrainRuntime.class.getClassLoader());
        addLoaderChain(loaders, Thread.currentThread().getContextClassLoader());
        addLoaderChain(loaders, ClassLoader.getSystemClassLoader());
        for (ClassLoader loader : loaders) {
            try {
                Class.forName(name, false, loader);
                return true;
            } catch (ClassNotFoundException | LinkageError ignored) {
                // NeoForge may expose optional mods through another loader.
            }
        }
        return false;
    }

    private static void addLoaderChain(Set<ClassLoader> loaders, ClassLoader loader) {
        while (loader != null && loaders.add(loader)) loader = loader.getParent();
    }

    /** Enables cell-grid inputs when at least one root passes compact and grid parity. */
    public static boolean terrainInputsAvailable() {
        return !Boolean.getBoolean("gpunoise.forceCpuTerrain")
                && device != null && terrainGraph != null && verifiedTerrainRootMask != 0;
    }

    /** Whether the installed terrain graph can supply this router root on the GPU. */
    public static boolean supportsTerrainRoot(int root) {
        GpuDensityGraph graph = terrainGraph;
        return graph != null && graph.supportsRouterRoot(root) && root >= 0
                && (verifiedTerrainRootMask & (1 << root)) != 0;
    }

    /** Immutable, parity-verified root snapshot for per-chunk hot-path caches. */
    static int supportedTerrainRootMask() {
        GpuDensityGraph graph = terrainGraph;
        return graph == null ? 0 : graph.supportedRouterRootMask() & verifiedTerrainRootMask;
    }

    /**
     * The old DensityFunctions.Noise hook submits one Vulkan job per leaf
     * noise node, then immediately waits for it.  That is the opposite of the
     * fused terrain design and made C2ME generation slower than CPU-only.
     * Once a full terrain graph has been installed, reserve Vulkan for the
     * graph batchers instead of intercepting individual vanilla leaf nodes.
     */
    public static boolean shouldUseLeafNoiseOffload() {
        return terrainGraph == null;
    }

    public static boolean gpuDensityRequired() {
        return GPU_DENSITY_REQUIRED.get();
    }

    public static void bindFillCache(GpuChunkDensityCache cache) {
        if (ACTIVE_FILL_CACHE.get() != null) {
            throw new IllegalStateException("Nested GPU fill cache binding");
        }
        ACTIVE_FILL_CACHE.set(cache);
    }

    public static GpuChunkDensityCache activeFillCache() {
        return ACTIVE_FILL_CACHE.get();
    }

    public static void clearFillCache() {
        ACTIVE_FILL_CACHE.remove();
    }

    public static <T> java.util.concurrent.CompletableFuture<T> submitChunkPipeline(
            java.util.function.Supplier<? extends java.util.concurrent.CompletionStage<T>> operation) {
        return chunkPipelineGate.submit(operation);
    }

    public static <T> java.util.concurrent.CompletableFuture<T> submitChunkPipeline(
            java.util.function.Supplier<? extends java.util.concurrent.CompletionStage<T>> operation,
            java.util.function.Consumer<Boolean> cancellation) {
        return chunkPipelineGate.submit(operation, cancellation);
    }

    /**
     * The strict writer is intentionally held behind the exact-coordinate
     * dependency provider.  A NoiseChunk cache is not sufficient: aquifers
     * read preliminary-surface columns outside the chunk and use two scaled
     * coordinate domains.  Enabling it before those dependencies are ready
     * corrupts generation or fails chunk futures.
     */
    public static boolean gpuOnlyWorldgenAvailable() {
        // The GPU fill continuation is designed around C2ME's concurrent
        // chunk-generation pipeline. Vanilla's scheduler exposes less
        // concurrent generation work, so preserve its normal path when C2ME
        // is absent instead of adding cache and staging overhead to it.
        return c2meDfcAvailable() && terrainInputsAvailable() && terrainRootsScheduler != null;
    }

    /** Prevents an overworld graph from being used by another dimension or generator. */
    public static boolean matchesTerrainRouter(RandomState randomState) {
        NoiseRouter installed = terrainRouter;
        return installed != null && randomState != null && randomState.router() == installed;
    }

    public static boolean matchesTerrainOreRoots(DensityFunction toggle,
                                                  DensityFunction ridged,
                                                  DensityFunction gap) {
        NoiseRouter installed = terrainRouter;
        return installed != null && installed.veinToggle() == toggle
                && installed.veinRidged() == ridged && installed.veinGap() == gap;
    }

    /**
     * Starts exactly one cell-lattice request for a chunk.  This is the only
     * submission point for authoritative fillFromNoise work: callers receive
     * a future and never evaluate the CPU density graph while it is pending.
     */
    public static java.util.concurrent.CompletableFuture<GpuChunkDensityCache> prepareChunk(
            Blender blender, RandomState randomState, StructureManager structures, ChunkAccess chunk,
            int minCellY, int cellCountY, NoiseSettings settings) {
        if (!gpuOnlyWorldgenAvailable() || !matchesTerrainRouter(randomState)
                || blender != Blender.empty() || cellCountY <= 0) {
            cpuFallbackChunks.incrementAndGet();
            return java.util.concurrent.CompletableFuture.failedFuture(
                    new IllegalStateException("GPU terrain pipeline is unavailable"));
        }
        int minX = chunk.getPos().getMinBlockX();
        int minZ = chunk.getPos().getMinBlockZ();
        net.minecraft.world.level.levelgen.PositionalRandomFactory oreRandom = randomState.oreRandom();
        GpuChunkDensityCache.prefetch5x5ForChunk(minX, minZ, settings);
        GpuChunkDensityCache cache = GpuTerrainAreaRegistry.claim(minX, minZ,
                () -> GpuChunkDensityCache.create(minX, minZ, settings));
        if (cache.markSubmitted()) gpuChunksSubmitted.incrementAndGet();
        GpuChunkDensityCache preparedCache = cache;
        return preparedCache.ready().thenApply(ignored -> {
            // Start the per-block vein pass as soon as the GPU root slab is
            // published. NoiseChunk construction and its aquifer setup can
            // then overlap the queued ore classification.
            try {
                preparedCache.prepareOreVeinDecisions(oreRandom);
            } catch (RuntimeException failure) {
                GpuNoise.LOGGER.warn("GPU ore-vein preparation could not be queued; OreVeinifier stays on Java", failure);
            }
            if (preparedCache.markCompleted()) gpuChunksCompleted.incrementAndGet();
            logChunkStatistics(false);
            return preparedCache;
        }).whenComplete((ignored, failure) -> {
            if (failure != null) releaseGpuCache(preparedCache);
        }).toCompletableFuture();
    }

    public static java.util.concurrent.CompletableFuture<GpuChunkDensityCache> prepareChunkForBiomes(
            Blender blender, RandomState randomState, ChunkAccess chunk, NoiseSettings settings) {
        if (!gpuOnlyWorldgenAvailable() || !matchesTerrainRouter(randomState)
                || blender != Blender.empty()) {
            return java.util.concurrent.CompletableFuture.failedFuture(
                    new IllegalStateException("GPU terrain pipeline is unavailable"));
        }
        int minX = chunk.getPos().getMinBlockX();
        int minZ = chunk.getPos().getMinBlockZ();
        GpuChunkDensityCache.prefetch5x5ForChunk(minX, minZ, settings);
        GpuChunkDensityCache cache = GpuTerrainAreaRegistry.claim(minX, minZ,
                () -> GpuChunkDensityCache.create(minX, minZ, settings));
        if (cache.markSubmitted()) gpuChunksSubmitted.incrementAndGet();
        return cache.ready().thenApply(ignored -> {
            if (cache.markCompleted()) gpuChunksCompleted.incrementAndGet();
            logChunkStatistics(false);
            return cache;
        }).toCompletableFuture();
    }

    public static void installBiomeSearchTree(MultiNoiseBiomeSource source) {
        GpuBiomeSearchTree candidate = GpuBiomeSearchTree.compile(source);
        biomeSearchTree = candidate;
        if (candidate == null) {
            GpuNoise.LOGGER.info("GPU multi-noise biome search unavailable for the active biome source.");
        } else {
            GpuNoise.LOGGER.info("GPU multi-noise biome tree ready: {} nodes, {} biome mappings, depth {}.",
                    candidate.nodeCount(), candidate.biomeCount(), candidate.maxDepth());
        }
    }

    /** Prepares a chunk's quart biome IDs from the GPU climate roots and OCL-style tree search. */
    public static java.util.concurrent.CompletableFuture<Void> prepareGpuBiomeRegion(
            GpuChunkDensityCache cache, RandomState randomState, ChunkAccess chunk) {
        GpuBiomeSearchTree tree = biomeSearchTree;
        VulkanSubmissionRing ring = terrainRing;
        if (tree == null || ring == null || cache == null || !cache.supportsBiomeClimateRoots()
                || chunk.isUpgrading()) {
            return CompletableFuture.completedFuture(null);
        }
        int height = chunk.getHeightAccessorForGeneration().getSectionsCount() * 4;
        if (height <= 0 || (long) height * 16L > PersistentVulkanNormalNoiseEngine.CAPACITY) {
            return CompletableFuture.completedFuture(null);
        }
        int minBiomeX = QuartPos.fromBlock(chunk.getPos().getMinBlockX());
        int minBiomeY = QuartPos.fromBlock(chunk.getMinBuildHeight());
        int minBiomeZ = QuartPos.fromBlock(chunk.getPos().getMinBlockZ());
        int sampleCount = height * 16;
        float[] xs = new float[sampleCount];
        float[] ys = new float[sampleCount];
        float[] zs = new float[sampleCount];
        int sample = 0;
        for (int y = 0; y < height; y++) {
            int blockY = QuartPos.toBlock(minBiomeY + y);
            for (int z = 0; z < 4; z++) {
                int blockZ = QuartPos.toBlock(minBiomeZ + z);
                for (int x = 0; x < 4; x++) {
                    int blockX = QuartPos.toBlock(minBiomeX + x);
                    // The graph's float coordinate ABI is exact only inside this
                    // range. Preserve vanilla biome selection beyond it.
                    if (Math.abs((long) blockX) >= (1L << 24) || Math.abs((long) blockY) >= (1L << 24)
                            || Math.abs((long) blockZ) >= (1L << 24)) {
                        return CompletableFuture.completedFuture(null);
                    }
                    xs[sample] = blockX;
                    ys[sample] = blockY;
                    zs[sample] = blockZ;
                    sample++;
                }
            }
        }
        GpuBiomeSearchBatchScheduler scheduler = biomeSearchScheduler;
        if (scheduler == null) return CompletableFuture.completedFuture(null);
        int[] climateRootIds = {GpuTerrainRoots.TEMPERATURE, GpuTerrainRoots.VEGETATION,
                GpuTerrainRoots.CONTINENTS, GpuTerrainRoots.EROSION,
                GpuTerrainRoots.DEPTH, GpuTerrainRoots.RIDGES};
        GpuDensityGraph graph = terrainGraph;
        if (graph == null) return CompletableFuture.completedFuture(null);
        for (int root : climateRootIds) {
            if (!graph.supportsRouterRoot(root)) return CompletableFuture.completedFuture(null);
        }
        return submitTerrainRootsDirect(xs, ys, zs, sampleCount, climateRootIds).thenCompose(climateRoots -> {
            for (int root : climateRootIds) {
                if (root >= climateRoots.roots()) return CompletableFuture.completedFuture(null);
            }
            ByteBuffer targets = ByteBuffer.allocateDirect(sampleCount * 16).order(ByteOrder.nativeOrder());
            boolean verifyGpu = Boolean.getBoolean("gpunoise.verifyBiomeGpu");
            NoiseRouter javaRouter = verifyGpu ? randomState.router() : null;
            long[] javaTargets = verifyGpu ? new long[sampleCount * 6] : null;
            for (int i = 0; i < sampleCount; i++) {
                float temperature = climateRoots.valueAt(i, GpuTerrainRoots.TEMPERATURE);
                float vegetation = climateRoots.valueAt(i, GpuTerrainRoots.VEGETATION);
                float continents = climateRoots.valueAt(i, GpuTerrainRoots.CONTINENTS);
                float erosion = climateRoots.valueAt(i, GpuTerrainRoots.EROSION);
                float depth = climateRoots.valueAt(i, GpuTerrainRoots.DEPTH);
                float ridges = climateRoots.valueAt(i, GpuTerrainRoots.RIDGES);
                if (!Float.isFinite(temperature) || !Float.isFinite(vegetation)
                        || !Float.isFinite(continents) || !Float.isFinite(erosion)
                        || !Float.isFinite(depth) || !Float.isFinite(ridges)) {
                    return CompletableFuture.completedFuture(null);
                }
                if (verifyGpu) {
                    DensityFunction.FunctionContext context = new DensityFunction.SinglePointContext(
                            (int) xs[i], (int) ys[i], (int) zs[i]);
                    int targetOffset = i * 6;
                    javaTargets[targetOffset] = net.minecraft.world.level.biome.Climate.quantizeCoord(
                            (float) javaRouter.temperature().compute(context));
                    javaTargets[targetOffset + 1] = net.minecraft.world.level.biome.Climate.quantizeCoord(
                            (float) javaRouter.vegetation().compute(context));
                    javaTargets[targetOffset + 2] = net.minecraft.world.level.biome.Climate.quantizeCoord(
                            (float) javaRouter.continents().compute(context));
                    javaTargets[targetOffset + 3] = net.minecraft.world.level.biome.Climate.quantizeCoord(
                            (float) javaRouter.erosion().compute(context));
                    javaTargets[targetOffset + 4] = net.minecraft.world.level.biome.Climate.quantizeCoord(
                            (float) javaRouter.depth().compute(context));
                    javaTargets[targetOffset + 5] = net.minecraft.world.level.biome.Climate.quantizeCoord(
                            (float) javaRouter.ridges().compute(context));
                }
                GpuBiomeSearchTree.putTarget(targets, temperature, vegetation, continents,
                        erosion, depth, ridges);
            }
            targets.flip();
            ByteBuffer parityTargets = targets.asReadOnlyBuffer().order(ByteOrder.nativeOrder());
            return scheduler.submit(targets, tree, sampleCount,
                    chunk.getPos().x, chunk.getPos().z).thenAccept(ids -> {
                @SuppressWarnings("unchecked")
                Holder<Biome>[] mapped = (Holder<Biome>[]) new Holder<?>[ids.length];
                ByteBuffer verify = parityTargets.duplicate().order(ByteOrder.nativeOrder());
                for (int i = 0; i < ids.length; i++) {
                    if (verifyGpu) {
                        short[] target = new short[7];
                        for (int dim = 0; dim < 7; dim++) target[dim] = verify.getShort();
                        verify.getShort();
                        int expected = tree.search(target);
                        boolean vanillaMatches = tree.matchesVanilla(javaTargets, i * 6, ids[i]);
                        if (ids[i] != expected || !vanillaMatches) {
                            GpuNoise.LOGGER.error("GPU biome search parity mismatch at row {}: Vulkan={}, Java={}; "
                                    + "vanillaTreeMatches={}; discarding this chunk's GPU biome table.",
                                    i, ids[i], expected, vanillaMatches);
                            return;
                        }
                    }
                    mapped[i] = tree.biome(ids[i]);
                    if (mapped[i] == null) return;
                }
                GpuBiomeRegionResults.publish(chunk, minBiomeY, height, mapped);
            });
        }).exceptionally(failure -> {
            GpuNoise.LOGGER.debug("GPU multi-noise biome batch unavailable; biome search remains Java: {}",
                    failure.toString());
            return null;
        }).toCompletableFuture();
    }

    public static void markGpuCacheUsed(GpuChunkDensityCache cache) {
        // GPU values are read for almost every generated block, but the
        // statistic is per cache. The cache keeps a plain fast flag and only
        // performs the atomic transition on the first observer.
        if (cache != null && cache.markConsumed()) {
            gpuChunksConsumed.incrementAndGet();
            logChunkStatistics(false);
        }
    }

    public static void releaseGpuCache(GpuChunkDensityCache cache) {
        GpuTerrainAreaRegistry.release(cache);
    }

    public static void markCpuFallbackChunk(int chunkX, int chunkZ, Throwable failure) {
        long count = cpuFallbackChunks.incrementAndGet();
        if (count <= 5 || (count & 63L) == 0L) {
            Throwable cause = failure;
            while ((cause instanceof java.util.concurrent.CompletionException
                    || cause instanceof java.util.concurrent.ExecutionException) && cause.getCause() != null) {
                cause = cause.getCause();
            }
            GpuNoise.LOGGER.warn("GPU terrain preparation failed for chunk {},{}; using CPU fallback (fallbacks={}): {}",
                    chunkX, chunkZ, count, cause.getMessage(), cause);
        }
    }

    private static void logChunkStatistics(boolean force) {
        long completed = gpuChunksCompleted.get();
        long bucket = completed >>> 6;
        boolean milestone = (completed & 63L) == 0L;
        boolean newMilestone = milestone && lastChunkStatisticsBucket.getAndSet(bucket) != bucket;
        if (force || newMilestone) {
            GpuNoise.LOGGER.info("GPU terrain: submitted={}, completed={}, consumed={}, fallback={}",
                    gpuChunksSubmitted.get(), completed, gpuChunksConsumed.get(), cpuFallbackChunks.get());
        }
    }

    private static long chunkKey(int x, int z) { return ((long) x << 32) ^ (z & 0xffffffffL); }

    static void enterGpuDensityScope() {
        GPU_DENSITY_REQUIRED.set(Boolean.TRUE);
    }

    static void leaveGpuDensityScope() {
        GPU_DENSITY_REQUIRED.set(Boolean.FALSE);
    }

    static synchronized void installTerrainGraph(GpuDensityGraph graph, NoiseRouter router) {
        if (engine == null) throw new IllegalStateException("GPU engine is not started");
        if (graph == null || router == null) throw new IllegalArgumentException("terrain graph and router are required");
        int routerRootCount = graph.routerRootCount();
        if (routerRootCount > 16) {
            throw new IllegalArgumentException("GPU terrain graph has " + routerRootCount
                    + " roots; the Vulkan root table supports at most 16");
        }
        // Finish graph-dependent validation before closing the currently
        // installed scheduler. Candidate graph inspection can fail for
        // unsupported custom nodes or malformed spline dependencies.
        int skippedBlockNodes = GpuDensityGraphBuffer.blockSkippedNodeCount(graph);
        if (terrainRouter != router) {
            GpuTerrainAreaRegistry.clear();
            GpuAquiferStatusRegionCache.clear();
        }
        if (terrainScheduler != null) terrainScheduler.close();
        if (terrainProvider != null) terrainProvider.close();
        if (terrainRootsScheduler != null) terrainRootsScheduler.close();
        if (terrainRegionWriter != null) terrainRegionWriter.close();
        if (biomeSearchScheduler != null) biomeSearchScheduler.close();
        terrainRing = submissionRing;
        terrainGraph = graph;
        terrainRouter = router;
        terrainBlockOutputLayout = null;
        verifiedTerrainBlockRootMask = 0;
        interpolatedTerrainBlockRootMask = 0;
        terrainBlockOutputVerificationComplete = false;
        GpuNoise.LOGGER.info("GPU terrain graph: nodes={}, registers={}, block pass skips {} marker-only nodes",
                graph.nodes().size(), graph.registerCount(), skippedBlockNodes);
        terrainRootsScheduler = new GpuTerrainRootsBatchScheduler();
        terrainRegionWriter = new GpuTerrainRegionWriter();
        biomeSearchScheduler = new GpuBiomeSearchBatchScheduler(terrainRing);
        terrainProvider = null;
        terrainScheduler = null;
        chunkFillCoordinator = null;
        GpuNoise.LOGGER.info("GPU chunk terrain provider ready: async exact slabs, batches up to {} chunks, {} router roots per cell-grid dispatch; block output is parity-filtered.",
                TerrainNoiseApi.MAX_CHUNKS_PER_BATCH, routerRootCount);
    }

    static java.util.concurrent.CompletionStage<TerrainNoiseApi.Result> submitTerrain(TerrainNoiseApi.Request request) {
        GpuTerrainBatchScheduler scheduler = terrainScheduler;
        if (scheduler == null) return java.util.concurrent.CompletableFuture.failedStage(
                new IllegalStateException("GPU terrain graph provider is not installed"));
        return scheduler.submit(request);
    }

    static java.util.concurrent.CompletionStage<GpuTerrainRootsResult> submitTerrainRoots(float[] x, float[] y, float[] z) {
        return submitTerrainRootsDirect(x, y, z);
    }

    static java.util.concurrent.CompletionStage<GpuTerrainBatchResult> submitTerrainGrid(GpuTerrainGrid grid) {
        GpuTerrainRootsBatchScheduler scheduler = terrainRootsScheduler;
        if (scheduler != null) return scheduler.submit(grid);
        return java.util.concurrent.CompletableFuture.failedStage(
                new IllegalStateException("GPU terrain roots scheduler is not installed"));
    }

    public static java.util.concurrent.CompletionStage<net.minecraft.world.level.chunk.ChunkAccess> submitTerrainRegionWrite(
            GpuTerrainDecisionWriter.ChunkFill fill) {
        GpuTerrainRegionWriter writer = terrainRegionWriter;
        if (writer == null) {
            try {
                return CompletableFuture.completedFuture(
                        GpuTerrainDecisionWriter.writeRegion(java.util.List.of(fill)).get(0));
            } catch (RuntimeException failure) {
                return CompletableFuture.failedFuture(failure);
            }
        }
        return writer.submit(fill);
    }

    static java.util.concurrent.CompletionStage<GpuTerrainRootsResult> submitTerrainGridDirect(
            java.nio.ByteBuffer metadata, int chunkCount, int samplesPerChunk) {
        VulkanSubmissionRing ring = terrainRing;
        GpuDensityGraph graph = terrainGraph;
        if (ring == null || graph == null) return java.util.concurrent.CompletableFuture.failedStage(
                new IllegalStateException("GPU terrain graph provider is not installed"));
        return ring.submitGraphRootsGrid(metadata, chunkCount, samplesPerChunk)
                .thenApply(values -> {
                    int roots = graph.routerRootCount();
                    int markers = graph.interpolatedMarkerCount();
                    return new GpuTerrainRootsResult(values, 0, values.length, roots, roots + markers, markers);
                });
    }

    public static java.util.concurrent.CompletionStage<int[]> submitAquiferNearestThree(
            java.nio.ByteBuffer records, int queryCount) {
        VulkanSubmissionRing ring = terrainRing;
        if (ring == null) return java.util.concurrent.CompletableFuture.failedStage(
                new IllegalStateException("GPU terrain graph provider is not installed"));
        return ring.submitAquiferNearestThree(records, queryCount);
    }

    public static java.util.concurrent.CompletionStage<int[]> submitAquiferSubstance(
            java.nio.ByteBuffer records, int queryCount) {
        VulkanSubmissionRing ring = terrainRing;
        if (ring == null) return java.util.concurrent.CompletableFuture.failedStage(
                new IllegalStateException("GPU terrain graph provider is not installed"));
        return ring.submitAquiferSubstance(records, queryCount);
    }

    static java.util.concurrent.CompletionStage<GpuOreVeinBatchResult> submitOreVeinDecisions(
            java.nio.ByteBuffer records, int blockCount) {
        VulkanSubmissionRing ring = submissionRing;
        if (ring == null) return java.util.concurrent.CompletableFuture.failedStage(
                new IllegalStateException("Vulkan submission ring is not installed"));
        return ring.submitOreVeinDecisions(records, blockCount);
    }

    public static java.util.concurrent.CompletionStage<int[]> submitAquiferFluidStatus(
            java.nio.ByteBuffer records, int queryCount) {
        VulkanSubmissionRing ring = terrainRing;
        if (ring == null) return java.util.concurrent.CompletableFuture.failedStage(
                new IllegalStateException("GPU terrain graph provider is not installed"));
        return ring.submitAquiferFluidStatus(records, queryCount);
    }

    public static java.util.concurrent.CompletionStage<GpuAquiferPrefillResult> submitAquiferPrefill(
            java.nio.ByteBuffer nearestRecords, int nearestCount,
            java.nio.ByteBuffer statusRecords, int statusCount) {
        return submitAquiferPrefill(nearestRecords, nearestCount, statusRecords, statusCount, false);
    }

    public static java.util.concurrent.CompletionStage<GpuAquiferPrefillResult> submitAquiferPrefill(
            java.nio.ByteBuffer nearestRecords, int nearestCount,
            java.nio.ByteBuffer statusRecords, int statusCount, boolean decisionChain) {
        VulkanSubmissionRing ring = terrainRing;
        if (ring == null) return java.util.concurrent.CompletableFuture.failedStage(
                new IllegalStateException("GPU terrain graph provider is not installed"));
        return ring.submitAquiferPrefill(nearestRecords, nearestCount, statusRecords, statusCount,
                decisionChain);
    }

    static java.util.concurrent.CompletionStage<GpuTerrainBatchResult> submitTerrainGridInterpolated(
            java.nio.ByteBuffer gridMetadata, int chunkCount, int samplesPerChunk,
            java.nio.ByteBuffer outputMetadata, int blocksPerChunk) {
        return submitTerrainGridInterpolated(gridMetadata, chunkCount, samplesPerChunk,
                outputMetadata, blocksPerChunk, false);
    }

    /** Batch scheduler owns both pooled metadata buffers until this stage completes. */
    static java.util.concurrent.CompletionStage<GpuTerrainBatchResult> submitTerrainGridInterpolatedOwned(
            java.nio.ByteBuffer gridMetadata, int chunkCount, int samplesPerChunk,
            java.nio.ByteBuffer outputMetadata, int blocksPerChunk) {
        return submitTerrainGridInterpolated(gridMetadata, chunkCount, samplesPerChunk,
                outputMetadata, blocksPerChunk, true);
    }

    private static java.util.concurrent.CompletionStage<GpuTerrainBatchResult> submitTerrainGridInterpolated(
            java.nio.ByteBuffer gridMetadata, int chunkCount, int samplesPerChunk,
            java.nio.ByteBuffer outputMetadata, int blocksPerChunk, boolean ownedMetadata) {
        VulkanSubmissionRing ring = terrainRing;
        GpuDensityGraph graph = terrainGraph;
        if (ring == null || graph == null) return java.util.concurrent.CompletableFuture.failedStage(
                new IllegalStateException("GPU terrain graph provider is not installed"));
        if (!terrainBlockOutputEnabled()) {
            java.util.concurrent.CompletableFuture<float[]> roots = ownedMetadata
                    ? ring.submitGraphRootsGridOwned(graph, gridMetadata, chunkCount, samplesPerChunk)
                    : ring.submitGraphRootsGrid(graph, gridMetadata, chunkCount, samplesPerChunk);
            return roots
                    .thenApply(values -> new GpuTerrainBatchResult(
                            new GpuTerrainRootsResult(values, 0, values.length,
                                    graph.routerRootCount(), graph.routerRootCount()
                                            + graph.interpolatedMarkerCount(),
                                    graph.interpolatedMarkerCount()),
                            new GpuTerrainBlockResult(new float[0], 0, 0, 1, 16, 0, 16)));
        }
        return ownedMetadata
                ? ring.submitGraphGridInterpolatedOwned(graph, gridMetadata, chunkCount, samplesPerChunk,
                        outputMetadata, blocksPerChunk, terrainBlockOutputRootIndexes(),
                        terrainCornerOutputRootIndexes())
                : ring.submitGraphGridInterpolated(graph, gridMetadata, chunkCount, samplesPerChunk,
                        outputMetadata, blocksPerChunk, terrainBlockOutputRootIndexes());
    }

    static boolean terrainBlockOutputEnabled() {
        if (!FULL_BLOCK_OUTPUT || Boolean.getBoolean("gpunoise.forceCpuTerrain")) return false;
        if (!terrainBlockOutputVerificationComplete) {
            // Startup verification needs one full block-result batch before
            // it can decide which roots are safe to keep on the block path.
            return supportsTerrainRoot(GpuTerrainRoots.FINAL_DENSITY);
        }
        return terrainBlockOutputRootMask() != 0;
    }

    static GpuDensityGraph currentTerrainGraph() {
        return terrainGraph;
    }

    static java.util.concurrent.CompletionStage<GpuTerrainRootsResult> submitTerrainRootsDirect(float[] x, float[] y, float[] z) {
        return submitTerrainRootsDirect(x, y, z, x.length);
    }

    static java.util.concurrent.CompletionStage<GpuTerrainRootsResult> submitTerrainRootsDirect(float[] x, float[] y, float[] z,
                                                                                                  int sampleCount) {
        VulkanSubmissionRing ring = terrainRing;
        GpuDensityGraph graph = terrainGraph;
        if (ring == null || graph == null) return java.util.concurrent.CompletableFuture.failedStage(
                new IllegalStateException("GPU terrain graph provider is not installed"));
        return ring.submitGraphRoots(graph, x, y, z, sampleCount)
                .thenApply(values -> new GpuTerrainRootsResult(values, 0, values.length,
                        graph.routerRootCount()));
    }

    /** Compact climate-only readback for biome search; root IDs remain addressable in the result view. */
    static java.util.concurrent.CompletionStage<GpuTerrainRootsResult> submitTerrainRootsDirect(
            float[] x, float[] y, float[] z, int sampleCount, int[] outputRootIndexes) {
        VulkanSubmissionRing ring = terrainRing;
        GpuDensityGraph graph = terrainGraph;
        if (ring == null || graph == null) return java.util.concurrent.CompletableFuture.failedStage(
                new IllegalStateException("GPU terrain graph provider is not installed"));
        int[] rootIndexes = outputRootIndexes.clone();
        return ring.submitGraphRoots(graph, x, y, z, sampleCount, rootIndexes)
                .thenApply(values -> new GpuTerrainRootsResult(values, 0, values.length,
                        graph.routerRootCount(), rootIndexes.length, 0, rootIndexes));
    }

    static java.util.concurrent.CompletionStage<float[]> submitInterpolation(float[] corners, java.nio.ByteBuffer metadata,
                                                                               int chunkCount, int blocksPerChunk) {
        VulkanSubmissionRing ring = submissionRing;
        if (ring == null) return java.util.concurrent.CompletableFuture.failedStage(
                new IllegalStateException("GPU submission ring is not installed"));
        return ring.submitInterpolation(corners, metadata, chunkCount, blocksPerChunk);
    }

    static java.util.concurrent.CompletionStage<GpuFusedResult> submitFused(int[] x, int[] y, int[] z,
                                                                      java.nio.ByteBuffer metadata,
                                                                      int chunkCount, int blocksPerChunk) {
        GpuFusedChunkBatchScheduler scheduler = fusedChunkScheduler;
        if (chunkCount == 1 && scheduler != null)
            return scheduler.submit(x, y, z, metadata, blocksPerChunk);
        return submitFusedDirect(x, y, z, metadata, chunkCount, blocksPerChunk);
    }

    static int terrainRootCount() {
        GpuDensityGraph graph = terrainGraph;
        return graph == null ? 1 : graph.routerRootCount();
    }

    /** Number of roots currently materialized into the per-block terrain output. */
    static int terrainOutputRootCount() {
        return GpuTerrainRoots.COUNT;
    }

    /** Number of parity-verified roots in compact per-block GPU output. */
    static int terrainBlockOutputRootCount() {
        if (terrainBlockOutputVerificationComplete)
            return verifiedTerrainBlockOutputLayout().rootIndexes().length;
        return Integer.bitCount(terrainBlockOutputRootMask());
    }

    /**
     * Roots with dense per-block consumers stay in the full-volume result:
     * final/initial density drive material and surface decisions, aquifer
     * roots feed the batched center/status and block decision passes, climate
     * roots feed biome sampling, and vein roots drive OreVeinifier. Every root
     * still needs its own block parity verification before runtime use.
     */
    private static int terrainBlockOutputEligibleRootMask() {
        return (1 << GpuTerrainRoots.FINAL_DENSITY)
                | (1 << GpuTerrainRoots.INITIAL_DENSITY)
                | (1 << GpuTerrainRoots.BARRIER)
                | (1 << GpuTerrainRoots.FLOODEDNESS)
                | (1 << GpuTerrainRoots.SPREAD)
                | (1 << GpuTerrainRoots.LAVA)
                | (1 << GpuTerrainRoots.EROSION)
                | (1 << GpuTerrainRoots.DEPTH)
                | (1 << GpuTerrainRoots.TEMPERATURE)
                | (1 << GpuTerrainRoots.VEGETATION)
                | (1 << GpuTerrainRoots.CONTINENTS)
                | (1 << GpuTerrainRoots.RIDGES)
                | (1 << GpuTerrainRoots.VEIN_TOGGLE)
                | (1 << GpuTerrainRoots.VEIN_RIDGED)
                | (1 << GpuTerrainRoots.VEIN_GAP);
    }

    /** Stable root ordering for compact block readback, derived only from parity-verified roots. */
    static int[] terrainBlockOutputRootIndexes() {
        GpuDensityGraph graph = terrainGraph;
        if (graph == null) {
            int[] roots = new int[GpuTerrainRoots.COUNT];
            for (int i = 0; i < roots.length; i++) roots[i] = i;
            return roots;
        }
        if (terrainBlockOutputVerificationComplete)
            return verifiedTerrainBlockOutputLayout().rootIndexes();
        int mask = terrainBlockOutputRootMask();
        int[] roots = new int[Integer.bitCount(mask)];
        int output = 0;
        for (int root = 0; root < GpuTerrainRoots.COUNT; root++) {
            if ((mask & (1 << root)) != 0) roots[output++] = root;
        }
        return roots;
    }

    /** Only verified graph roots need to cross the host-visible corner-result boundary. */
    private static int[] terrainCornerOutputRootIndexes() {
        GpuDensityGraph graph = terrainGraph;
        if (graph == null) return new int[] { GpuTerrainRoots.FINAL_DENSITY };
        int rootLimit = Math.min(GpuTerrainRoots.COUNT, graph.routerRootCount());
        int mask = supportedTerrainRootMask() & ((1 << rootLimit) - 1);
        if (mask == 0) return allRouterRootIndexes(graph.routerRootCount());
        int[] roots = new int[Integer.bitCount(mask)];
        int output = 0;
        for (int root = 0; root < rootLimit; root++) {
            if ((mask & (1 << root)) != 0) roots[output++] = root;
        }
        return roots;
    }

    private static int[] allRouterRootIndexes(int rootCount) {
        int[] roots = new int[rootCount];
        for (int root = 0; root < rootCount; root++) roots[root] = root;
        return roots;
    }

    private static int terrainBlockOutputRootMask() {
        GpuDensityGraph graph = terrainGraph;
        if (graph == null) return (1 << GpuTerrainRoots.COUNT) - 1;
        if (terrainBlockOutputVerificationComplete) return verifiedTerrainBlockOutputLayout().mask();
        int rootLimit = Math.min(GpuTerrainRoots.COUNT, graph.routerRootCount());
        int verified = supportedTerrainRootMask();
        return verified & terrainBlockOutputEligibleRootMask() & ((1 << rootLimit) - 1);
    }

    private static TerrainBlockOutputLayout verifiedTerrainBlockOutputLayout() {
        TerrainBlockOutputLayout layout = terrainBlockOutputLayout;
        if (layout != null) return layout;
        synchronized (GpuTerrainRuntime.class) {
            layout = terrainBlockOutputLayout;
            if (layout == null) {
                GpuDensityGraph graph = terrainGraph;
                int rootLimit = graph == null ? GpuTerrainRoots.COUNT
                        : Math.min(GpuTerrainRoots.COUNT, graph.routerRootCount());
                int verified = verifiedTerrainBlockRootMask
                        | (EXPERIMENTAL_INTERPOLATED_BLOCK_OUTPUT
                        ? interpolatedTerrainBlockRootMask : 0);
                int mask = verified & terrainBlockOutputEligibleRootMask()
                        & ((1 << rootLimit) - 1);
                int[] indexes = new int[Integer.bitCount(mask)];
                int output = 0;
                for (int root = 0; root < rootLimit; root++) {
                    if ((mask & (1 << root)) != 0) indexes[output++] = root;
                }
                layout = new TerrainBlockOutputLayout(mask, indexes);
                terrainBlockOutputLayout = layout;
            }
        }
        return layout;
    }

    static java.util.concurrent.CompletionStage<GpuFusedResult> submitFusedDirect(int[] x, int[] y, int[] z,
                                                                            java.nio.ByteBuffer metadata,
                                                                            int chunkCount, int blocksPerChunk) {
        VulkanSubmissionRing ring = submissionRing;
        GpuDensityGraph graph = terrainGraph;
        if (ring == null || graph == null) return java.util.concurrent.CompletableFuture.failedStage(
                new IllegalStateException("GPU fused terrain path is not installed"));
        return ring.submitGraphInterpolated(graph, toFloat(x), toFloat(y), toFloat(z), metadata,
                chunkCount, blocksPerChunk);
    }

    static java.util.concurrent.CompletionStage<GpuFusedResult> submitFusedDirect(float[] x, float[] y, float[] z,
                                                                                   int sampleCount,
                                                                                   java.nio.ByteBuffer metadata,
                                                                                   int chunkCount, int blocksPerChunk) {
        VulkanSubmissionRing ring = submissionRing;
        GpuDensityGraph graph = terrainGraph;
        if (ring == null || graph == null) return java.util.concurrent.CompletableFuture.failedStage(
                new IllegalStateException("GPU fused terrain path is not installed"));
        return ring.submitGraphInterpolated(graph, x, y, z, sampleCount, metadata, chunkCount, blocksPerChunk);
    }

    private static float[] toFloat(int[] values) {
        float[] result = new float[values.length];
        for (int i = 0; i < values.length; i++) result[i] = values[i];
        return result;
    }

    private static GpuGlobalSamplerQueue globalSamplerQueue() {
        GpuGlobalSamplerQueue current = globalQueue;
        if (current != null) return current;
        synchronized (GpuTerrainRuntime.class) {
            current = globalQueue;
            if (current == null && submissionRing != null) {
                current = new GpuGlobalSamplerQueue(submissionRing);
                globalQueue = current;
            }
            return current;
        }
    }

    public static java.util.concurrent.CompletionStage<net.minecraft.world.level.chunk.ChunkAccess> submitChunkFill(
            GpuChunkFillCoordinator.Work work) {
        GpuChunkFillCoordinator coordinator = chunkFillCoordinator;
        if (coordinator == null) return java.util.concurrent.CompletableFuture.failedStage(
                new IllegalStateException("GPU chunk fill coordinator is not installed"));
        return coordinator.submit(work);
    }

    public static boolean chunkWriterAvailable() {
        // The experimental fillFromNoise replacement is disabled by default.
        // Keep the GPU graph/noise services available without replacing
        // Minecraft's stable chunk-generation pipeline.
        return false;
    }

    static void verifyTerrainGraph(GpuDensityGraph graph, NoiseRouter router) {
        PersistentVulkanNormalNoiseEngine current = engine;
        if (current == null) return;
        int count = 512;
        float[] x = new float[count], y = new float[count], z = new float[count];
        GpuTerrainParity.fillStartupSamples(x, y, z);
        DensityFunction[] cpu = {router.barrierNoise(), router.fluidLevelFloodednessNoise(),
                router.fluidLevelSpreadNoise(), router.lavaNoise(), router.temperature(), router.vegetation(),
                router.continents(), router.erosion(), router.depth(), router.ridges(),
                router.initialDensityWithoutJaggedness(), router.finalDensity(), router.veinToggle(),
                router.veinRidged(), router.veinGap()};
        try {
            float[] gpu = submitGraphRootsForParity(graph, x, y, z);
            float worst = 0.0f;
            for (int root = 0; root < cpu.length; root++) {
                float rootWorst = 0.0f;
                int worstIndex = -1;
                float worstGpu = 0.0f;
                double worstCpu = 0.0;
                for (int i = 0; i < count; i++) {
                    double expected = cpu[root].compute(new DensityFunction.SinglePointContext(
                            (int) x[i], (int) y[i], (int) z[i]));
                    float error = root == 11
                            ? GpuTerrainParity.decisionError(gpu[i * cpu.length + root], (float) expected)
                            : GpuTerrainParity.error(gpu[i * cpu.length + root], (float) expected);
                    if (error > rootWorst) {
                        rootWorst = error;
                        worstIndex = i;
                        worstGpu = gpu[i * cpu.length + root];
                        worstCpu = expected;
                    }
                }
                worst = Math.max(worst, rootWorst);
                GpuNoise.LOGGER.info("GPU terrain root {} parity: maxError={}, at ({},{},{}), gpu={}, cpu={}",
                        root, rootWorst, (int) x[worstIndex], (int) y[worstIndex], (int) z[worstIndex],
                        worstGpu, worstCpu);
            }
            boolean parity = worst < GRAPH_PARITY_EPSILON;
            GpuNoise.LOGGER.info("GPU terrain graph parity: {} samples, worstMaxError={}, parity={}",
                    count, worst, parity);
        } catch (Throwable failure) {
            GpuNoise.LOGGER.warn("GPU terrain graph parity dispatch failed", failure);
        }
    }

    /** Verifies the smaller graph that is actually dispatched for chunks. */
    static void verifyCompactTerrainGraph(GpuDensityGraph graph, NoiseRouter router) {
        PersistentVulkanNormalNoiseEngine current = engine;
        if (current == null) return;
        DensityFunction[] cpu = {router.finalDensity(), router.initialDensityWithoutJaggedness(),
                router.barrierNoise(), router.fluidLevelFloodednessNoise(), router.fluidLevelSpreadNoise(),
                router.lavaNoise(), router.erosion(), router.depth(), router.temperature(),
                router.vegetation(), router.continents(), router.ridges(), router.veinToggle(),
                router.veinRidged(), router.veinGap()};
        if (graph.routerRootCount() != cpu.length) {
            verifiedTerrainRootMask = 0;
            GpuNoise.LOGGER.warn("GPU compact terrain graph has {} roots; expected {}", graph.routerRootCount(), cpu.length);
            return;
        }
        int count = 512;
        float[] x = new float[count], y = new float[count], z = new float[count];
        GpuTerrainParity.fillStartupSamples(x, y, z);
        int verifiedRoots = 0;
        try {
            float[] gpu = submitGraphRootsForParity(graph, x, y, z);
            for (int root = 0; root < cpu.length; root++) {
                if (!graph.supportsRouterRoot(root)) continue;
                float rootWorst = 0.0f;
                for (int i = 0; i < count; i++) {
                    double expected = cpu[root].compute(new DensityFunction.SinglePointContext((int) x[i], (int) y[i], (int) z[i]));
                    float error = root == GpuTerrainRoots.FINAL_DENSITY
                            ? GpuTerrainParity.decisionError(gpu[i * cpu.length + root], (float) expected)
                            : GpuTerrainParity.error(gpu[i * cpu.length + root], (float) expected);
                    rootWorst = Math.max(rootWorst, error);
                }
                if (rootWorst < GRAPH_PARITY_EPSILON) verifiedRoots |= 1 << root;
                GpuNoise.LOGGER.info("GPU compact terrain root '{}' parity: maxError={}, parity={}",
                        GpuTerrainRoots.name(root), rootWorst, rootWorst < GRAPH_PARITY_EPSILON);
            }
            verifiedTerrainRootMask = verifiedRoots;
            GpuNoise.LOGGER.info("GPU compact terrain graph parity: {} samples, {}/{} roots verified, "
                            + "verifiedMask=0x{}",
                    count, Integer.bitCount(verifiedRoots), cpu.length, Integer.toHexString(verifiedRoots));
        } catch (Throwable failure) {
            verifiedTerrainRootMask = 0;
            GpuNoise.LOGGER.warn("GPU compact terrain graph parity dispatch failed", failure);
        }
    }

    /** Verifies the metadata/indexing path used by real chunk cell grids. */
    static void verifyTerrainGrid(GpuDensityGraph graph, NoiseRouter router, NoiseSettings settings) {
        int cellWidth = settings.getCellWidth();
        int cellHeight = settings.getCellHeight();
        GpuTerrainGrid grid = new GpuTerrainGrid(0, settings.minY(), 0,
                16 / cellWidth + 1, settings.height() / cellHeight + 1,
                16 / cellWidth + 1, cellWidth, cellHeight);
        try {
            // Validate the actual grid metadata directly. The spatial batcher
            // is exercised by worldgen. Software Vulkan verifies only the
            // final-density block root: this is the dense input required by
            // the batched aquifer material pass, while avoiding the larger
            // multi-root readback that previously made Mesa startup time out.
            VulkanSubmissionRing ring = submissionRing;
            if (ring == null) throw new IllegalStateException("Vulkan submission pool is not installed");
            java.nio.ByteBuffer metadata = java.nio.ByteBuffer.allocateDirect(32)
                    .order(java.nio.ByteOrder.nativeOrder());
            metadata.putInt(grid.minX()).putInt(grid.minY()).putInt(grid.minZ())
                    .putInt(grid.gridX()).putInt(grid.gridY()).putInt(grid.gridZ())
                    .putInt(grid.cellWidth()).putInt(grid.cellHeight()).flip();
            int[] blockRoots = terrainBlockOutputRootIndexes();
            int[] softwareBlockRoots = graph.supportsRouterRoot(GpuTerrainRoots.FINAL_DENSITY)
                    ? new int[]{GpuTerrainRoots.FINAL_DENSITY} : new int[0];
            int blocksPerChunk = Math.multiplyExact(256, settings.height());
            java.nio.ByteBuffer outputMetadata = GpuInterpolationBatch.metadata(1, grid.gridX() - 1,
                    grid.gridY() - 1, grid.gridZ() - 1, grid.cellWidth(), grid.cellHeight(),
                    grid.sampleCount(), blocksPerChunk, grid.minX(), grid.minY(), grid.minZ());
            List<CompletableFuture<GpuTerrainBatchResult>> terrainChecks = new ArrayList<>();
            for (GpuDevice candidate : ring.devices()) {
                boolean softwareDevice = candidate.deviceType()
                        == org.lwjgl.vulkan.VK10.VK_PHYSICAL_DEVICE_TYPE_CPU;
                int[] candidateBlockRoots = softwareDevice ? softwareBlockRoots : blockRoots;
                boolean includeBlockOutputs = terrainBlockOutputEnabled() && candidateBlockRoots.length > 0;
                if (includeBlockOutputs) {
                    terrainChecks.add(ring.submitTerrainGridForDevice(candidate, graph, metadata,
                                    grid.sampleCount(), outputMetadata, blocksPerChunk, candidateBlockRoots)
                            .orTimeout(STARTUP_PARITY_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS));
                } else {
                    int markers = graph.interpolatedMarkerCount();
                    terrainChecks.add(ring.submitGraphRootsGridForDevice(candidate, graph, metadata,
                                    1, grid.sampleCount())
                            .orTimeout(STARTUP_PARITY_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
                            .thenApply(roots -> new GpuTerrainBatchResult(
                                    new GpuTerrainRootsResult(roots, 0, roots.length, graph.routerRootCount(),
                                            graph.routerRootCount() + markers, markers),
                                    new GpuTerrainBlockResult(new float[0], 0, 0, 1, 16, 0, 16))));
                }
            }
            CompletableFuture.allOf(terrainChecks.toArray(CompletableFuture[]::new)).join();
            List<GpuTerrainBatchResult> deviceTerrainResults = terrainChecks.stream()
                    .map(CompletableFuture::join).toList();
            DensityFunction[] cpu = {
                    router.finalDensity(), router.initialDensityWithoutJaggedness(),
                    router.barrierNoise(), router.fluidLevelFloodednessNoise(),
                    router.fluidLevelSpreadNoise(), router.lavaNoise(), router.erosion(),
                    router.depth(), router.temperature(), router.vegetation(),
                    router.continents(), router.ridges(), router.veinToggle(),
                    router.veinRidged(), router.veinGap()
            };
            float[] rootWorst = new float[cpu.length];
            int[] worstX = new int[cpu.length], worstY = new int[cpu.length], worstZ = new int[cpu.length];
            int supportedRoots = Integer.bitCount(graph.supportedRouterRootMask());
            for (int gy = 0; gy < grid.gridY(); gy++) {
                for (int gx = 0; gx < grid.gridX(); gx++) {
                    for (int gz = 0; gz < grid.gridZ(); gz++) {
                        int sample = (gy * grid.gridX() + gx) * grid.gridZ() + gz;
                        int x = grid.minX() + gx * cellWidth;
                        int y = grid.minY() + gy * cellHeight;
                        int z = grid.minZ() + gz * cellWidth;
                        for (int root = 0; root < cpu.length; root++) {
                            if (!graph.supportsRouterRoot(root)) continue;
                            float expected = (float) cpu[root].compute(
                                    new DensityFunction.SinglePointContext(x, y, z));
                            for (GpuTerrainBatchResult deviceResult : deviceTerrainResults) {
                                float actual = deviceResult.corners().valueAt(sample, root);
                                float error = root == GpuTerrainRoots.FINAL_DENSITY
                                        ? GpuTerrainParity.decisionError(actual, expected)
                                        : GpuTerrainParity.error(actual, expected);
                                if (error > rootWorst[root]) {
                                    rootWorst[root] = error;
                                    worstX[root] = x;
                                    worstY[root] = y;
                                    worstZ[root] = z;
                                }
                            }
                        }
                    }
                }
            }
            int gridVerifiedRoots = 0;
            for (int root = 0; root < cpu.length; root++) {
                boolean passes = graph.supportsRouterRoot(root)
                        && rootWorst[root] < GRAPH_PARITY_EPSILON;
                if (passes) gridVerifiedRoots |= 1 << root;
                if (graph.supportsRouterRoot(root)) {
                    GpuNoise.LOGGER.info("GPU terrain grid root '{}' parity: maxError={}, at ({},{},{}), parity={}",
                            GpuTerrainRoots.name(root), rootWorst[root], worstX[root], worstY[root], worstZ[root], passes);
                }
            }
            verifiedTerrainRootMask &= gridVerifiedRoots;
            int verifiedBlockRoots = (1 << GpuTerrainRoots.COUNT) - 1;
            int verifiedInterpolatedRoots = (1 << GpuTerrainRoots.COUNT) - 1;
            int blockVerifiedDeviceCount = 0;
            for (int deviceIndex = 0; deviceIndex < deviceTerrainResults.size(); deviceIndex++) {
                GpuTerrainBatchResult deviceResult = deviceTerrainResults.get(deviceIndex);
                if (deviceResult.blocks().isEmpty()) continue;
                blockVerifiedDeviceCount++;
                verifiedBlockRoots &= verifyTerrainBlockOutputs(graph, cpu, deviceResult.blocks(), settings);
                verifiedInterpolatedRoots &= findInterpolatedBlockOutputRoots(graph, deviceResult.blocks());
            }
            if (blockVerifiedDeviceCount == 0) {
                GpuNoise.LOGGER.info("No device produced per-block startup output; per-block output remains disabled.");
            }
            verifiedTerrainBlockRootMask = verifiedBlockRoots & verifiedTerrainRootMask;
            interpolatedTerrainBlockRootMask = verifiedInterpolatedRoots & verifiedTerrainRootMask;
            terrainBlockOutputLayout = null;
            terrainBlockOutputVerificationComplete = true;
            GpuNoise.LOGGER.info("GPU terrain grid parity: {} samples, {}/{} roots verified, verifiedMask=0x{}",
                    grid.sampleCount(), Integer.bitCount(verifiedTerrainRootMask), supportedRoots,
                    Integer.toHexString(verifiedTerrainRootMask));
            GpuNoise.LOGGER.info("GPU terrain block parity: {}/{} roots verified without interpolation-marker dependencies, verifiedMask=0x{}",
                    Integer.bitCount(verifiedTerrainBlockRootMask), graph.routerRootCount(),
                    Integer.toHexString(verifiedTerrainBlockRootMask));
            GpuNoise.LOGGER.info("GPU terrain per-block roots: exact={}, interpolation-dependent={}, enabled={}, runtimeProbe={}",
                    rootNames(verifiedTerrainBlockRootMask), rootNames(interpolatedTerrainBlockRootMask),
                    rootNames(terrainBlockOutputRootMask()), EXPERIMENTAL_INTERPOLATED_BLOCK_OUTPUT);
        } catch (Throwable failure) {
            verifiedTerrainRootMask = 0;
            verifiedTerrainBlockRootMask = 0;
            interpolatedTerrainBlockRootMask = 0;
            terrainBlockOutputLayout = null;
            terrainBlockOutputVerificationComplete = true;
            GpuNoise.LOGGER.warn("GPU terrain grid parity dispatch failed; terrain remains CPU-owned.", failure);
        }
    }

    /**
     * Checks GPU block outputs against Java at every block in the startup
     * chunk, but only for roots independent of NoiseChunk interpolation
     * markers. Those markers must be sampled by NoiseChunk's cell state and
     * cannot be validated with a plain SinglePointContext.
     */
    private static int verifyTerrainBlockOutputs(GpuDensityGraph graph, DensityFunction[] cpu,
                                                  GpuTerrainBlockResult blocks, NoiseSettings settings) {
        if (blocks == null || blocks.isEmpty()) return 0;
        boolean[] markerDependent = graph.routerRootsWithInterpolatedMarkers();
        int verified = 0;
        int blockHeight = settings.height();
        int minY = settings.minY();
        for (int root = 0; root < cpu.length; root++) {
            if (!graph.supportsRouterRoot(root) || markerDependent[root]
                    || blocks.outputIndexForRoot(root) < 0) continue;
            float worst = 0.0f;
            int worstX = 0, worstY = 0, worstZ = 0;
            for (int y = 0; y < blockHeight; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        float actual = blocks.valueAt(x, y, z, root);
                        float expected = (float) cpu[root].compute(
                                new DensityFunction.SinglePointContext(x, minY + y, z));
                        float error = root == GpuTerrainRoots.FINAL_DENSITY
                                ? GpuTerrainParity.decisionError(actual, expected)
                                : GpuTerrainParity.error(actual, expected);
                        if (error > worst) {
                            worst = error;
                            worstX = x;
                            worstY = minY + y;
                            worstZ = z;
                        }
                    }
                }
            }
            boolean passes = worst < GRAPH_PARITY_EPSILON;
            if (passes) verified |= 1 << root;
            GpuNoise.LOGGER.info("GPU terrain block root '{}' exact Java parity: maxError={}, at ({},{},{}), parity={}",
                    GpuTerrainRoots.name(root), worst, worstX, worstY, worstZ, passes);
        }
        return verified;
    }

    private static int findInterpolatedBlockOutputRoots(GpuDensityGraph graph, GpuTerrainBlockResult blocks) {
        if (blocks == null || blocks.isEmpty()) return 0;
        boolean[] markerDependent = graph.routerRootsWithInterpolatedMarkers();
        int candidates = 0;
        for (int root = 0; root < markerDependent.length; root++) {
            if (graph.supportsRouterRoot(root) && markerDependent[root]
                    && blocks.outputIndexForRoot(root) >= 0) candidates |= 1 << root;
        }
        return candidates;
    }

    private static String rootNames(int mask) {
        StringJoiner names = new StringJoiner(",", "[", "]");
        for (int root = 0; root < GpuTerrainRoots.COUNT; root++) {
            if ((mask & (1 << root)) != 0) names.add(GpuTerrainRoots.name(root));
        }
        return names.toString();
    }

    static void verifyGraph(GpuDensityGraph graph, NoiseRouter router) {
        PersistentVulkanNormalNoiseEngine current = engine;
        if (current == null) return;
        int count = 4096;
        float[] x = new float[count], y = new float[count], z = new float[count];
        fillVolumeParitySamples(x, y, z);
        DensityFunction[] cpuRoots = {
                router.barrierNoise(), router.fluidLevelFloodednessNoise(), router.fluidLevelSpreadNoise(),
                router.lavaNoise(), router.temperature(), router.vegetation(), router.continents(),
                router.erosion(), router.depth(), router.ridges(), router.initialDensityWithoutJaggedness(),
                router.finalDensity(), router.veinToggle(), router.veinRidged(), router.veinGap()
        };
        String[] names = {
                "barrier", "fluidFloodedness", "fluidSpread", "lava", "temperature", "vegetation",
                "continents", "erosion", "depth", "ridges", "initialDensity", "finalDensity",
                "veinToggle", "veinRidged", "veinGap"
        };
        try {
            verifyBlendedNoiseNodes(graph, x, y, z);
            float worst = 0.0f;
            float[] allRoots = submitGraphRootsForParity(graph, x, y, z);
            for (int root = 0; root < graph.routerRootCount(); root++) {
                float maxError = 0.0f;
                int maxIndex = -1;
                float maxGpu = 0.0f;
                double maxCpu = 0.0;
                for (int i = 0; i < count; i++) {
                    double expected = cpuRoots[root].compute(new DensityFunction.SinglePointContext(
                            (int) x[i], (int) y[i], (int) z[i]));
                    float gpu = allRoots[i * graph.routerRootCount() + root];
                    float error = root == 11
                            ? GpuTerrainParity.decisionError(gpu, (float) expected)
                            : GpuTerrainParity.error(gpu, (float) expected);
                    if (error > maxError) {
                        maxError = error;
                        maxIndex = i;
                        maxGpu = gpu;
                        maxCpu = expected;
                    }
                }
                worst = Math.max(worst, maxError);
                GpuNoise.LOGGER.info("GPU graph parity: {} maxError={} parity={}",
                        names[root], maxError, maxError < GRAPH_PARITY_EPSILON);
                if (maxError >= GRAPH_PARITY_EPSILON) {
                    GpuNoise.LOGGER.info("GPU graph parity detail: {} at ({},{},{}): gpu={}, cpu={}",
                            names[root], (int) x[maxIndex], (int) y[maxIndex], (int) z[maxIndex], maxGpu, maxCpu);
                }
            }
            boolean parity = worst < GRAPH_PARITY_EPSILON;
            GpuNoise.LOGGER.info("GPU full-density graph test: {} samples, {} nodes, worstMaxError={}, epsilon={}, parity={}",
                    count, graph.nodes().size(), worst, GRAPH_PARITY_EPSILON, parity);
        } catch (Exception failure) {
            GpuNoise.LOGGER.warn("GPU full-density graph dispatch failed; terrain remains CPU-owned", failure);
        }
    }


    /** Verifies the packed BlendedNoise records independently of router branches. */
    private static void verifyBlendedNoiseNodes(GpuDensityGraph graph, float[] x, float[] y, float[] z) {
        List<Integer> blendedNodes = new ArrayList<>();
        for (int node = 0; node < graph.nodes().size(); node++) {
            if (graph.nodes().get(node).opcode().equals("BlendedNoise")) blendedNodes.add(node);
        }
        for (int start = 0; start < blendedNodes.size(); start += 16) {
            int count = Math.min(16, blendedNodes.size() - start);
            int[] rootRegisters = new int[count];
            for (int root = 0; root < count; root++) {
                int node = blendedNodes.get(start + root);
                rootRegisters[root] = graph.nodes().get(node).register();
            }
            GpuDensityGraph registerGraph = new GpuDensityGraph(graph.nodes(), rootRegisters,
                    graph.noises(), graph.splines(), graph.blended(), graph.sourceFunctions(),
                    graph.registerCount(), (1 << count) - 1, new String[count]);
            float[] gpu = submitGraphRootsForParity(registerGraph, x, y, z);
            for (int root = 0; root < count; root++) {
                int node = blendedNodes.get(start + root);
                DensityFunction cpuFunction = graph.sourceFunctions().get(node);
                float maxError = 0.0f;
                int maxIndex = -1;
                float maxGpu = 0.0f;
                float maxCpu = 0.0f;
                for (int sample = 0; sample < x.length; sample++) {
                    float actual = gpu[sample * count + root];
                    float expected = (float) cpuFunction.compute(new DensityFunction.SinglePointContext(
                            (int) x[sample], (int) y[sample], (int) z[sample]));
                    float error = GpuTerrainParity.error(actual, expected);
                    if (error > maxError) {
                        maxError = error;
                        maxIndex = sample;
                        maxGpu = actual;
                        maxCpu = expected;
                    }
                }
                GpuNoise.LOGGER.info("GPU BlendedNoise parity: node={} maxError={} parity={}",
                        node, maxError, maxError < 1.0E-3f);
                if (maxIndex >= 0) GpuNoise.LOGGER.info(
                        "GPU BlendedNoise detail: at ({},{},{}): gpu={}, cpu={}",
                        (int) x[maxIndex], (int) y[maxIndex], (int) z[maxIndex], maxGpu, maxCpu);
            }
        }
        GpuNoise.LOGGER.info("GPU BlendedNoise parity test: {} nodes checked", blendedNodes.size());
    }

    /** Uses the ring's async completion path so parity work never holds an engine monitor while waiting. */
    private static float[] submitGraphRootsForParity(GpuDensityGraph graph, float[] x, float[] y, float[] z) {
        VulkanSubmissionRing ring = submissionRing;
        if (ring == null) throw new IllegalStateException("Vulkan submission ring is not installed");
        List<GpuDevice> candidates = ring.devices();
        List<CompletableFuture<float[]>> checks = candidates.stream()
                .map(candidate -> ring.submitGraphRootsForDevice(candidate, graph, x, y, z)
                        .orTimeout(STARTUP_PARITY_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS))
                .toList();
        CompletableFuture.allOf(checks.toArray(CompletableFuture[]::new)).join();
        float[] reference = null;
        for (int deviceIndex = 0; deviceIndex < candidates.size(); deviceIndex++) {
            GpuDevice candidate = candidates.get(deviceIndex);
            float[] actual = checks.get(deviceIndex).join();
            if (reference == null) {
                reference = actual;
                continue;
            }
            if (actual.length != reference.length) {
                throw new IllegalStateException("Vulkan devices returned different startup parity output sizes");
            }
            float worst = 0.0f;
            int worstIndex = -1;
            for (int i = 0; i < actual.length; i++) {
                int root = i % Math.max(1, graph.routerRootCount());
                float error = graph.routerRootCount() == GpuTerrainRoots.COUNT
                        && root == GpuTerrainRoots.FINAL_DENSITY
                        ? GpuTerrainParity.decisionError(actual[i], reference[i])
                        : GpuTerrainParity.error(actual[i], reference[i]);
                if (error > worst) {
                    worst = error;
                    worstIndex = i;
                }
            }
            if (worst >= 1.0E-4f) {
                throw new IllegalStateException("Vulkan device parity mismatch on " + candidate.name()
                        + ": maxError=" + worst + " at output " + worstIndex);
            }
            GpuNoise.LOGGER.info("GPU cross-device graph parity: device={}, samples={}, maxError={}, parity=true",
                    candidate.name(), actual.length / Math.max(1, graph.routerRootCount()), worst);
        }
        if (reference == null) throw new IllegalStateException("Vulkan device pool has no parity target");
        return reference;
    }

    private static void fillVolumeParitySamples(float[] x, float[] y, float[] z) {
        if (x.length != 4096 || y.length != 4096 || z.length != 4096) {
            throw new IllegalArgumentException("full graph terrain parity requires exactly 4096 samples");
        }
        int sample = 0;
        for (int iy = 0; iy < 16; iy++) {
            float worldY = -192.0f + iy * 32.0f;
            for (int ix = 0; ix < 16; ix++) {
                float worldX = -512.0f + ix * 64.0f;
                for (int iz = 0; iz < 16; iz++) {
                    x[sample] = worldX;
                    y[sample] = worldY;
                    z[sample] = -512.0f + iz * 64.0f;
                    sample++;
                }
            }
        }
    }




    public static boolean tryFill(NormalNoise noise, double xzScale, double yScale,
                           DensityFunction.ContextProvider contexts, double[] output) {
        GpuDevice selected = device;
        PersistentVulkanNormalNoiseEngine currentEngine = engine;
        GpuGlobalSamplerQueue currentQueue = globalSamplerQueue();
        if (selected == null || currentEngine == null || currentQueue == null || noise == null) return false;
        if (output.length < GpuNoiseConfig.MIN_GPU_SAMPLES.get()) return false;
        float[] x = new float[output.length];
        float[] y = new float[output.length];
        float[] z = new float[output.length];
        for (int i = 0; i < output.length; i++) {
            DensityFunction.FunctionContext context = contexts.forIndex(i);
            x[i] = (float)(context.blockX() * xzScale);
            y[i] = (float)(context.blockY() * yScale);
            z[i] = (float)(context.blockZ() * xzScale);
        }
        MinecraftNormalNoiseData sampler = samplers.computeIfAbsent(noise, MinecraftNormalNoiseData::read);
        long start = System.nanoTime();
        float[] values = currentQueue.submit(sampler, x, y, z).join();
        long call = gpuCalls.incrementAndGet();
        gpuSamples.addAndGet(output.length);
        gpuNanos.addAndGet(System.nanoTime() - start);
        if (call % 1000 == 0) {
            GpuNoise.LOGGER.info("GPU terrain fills: {} calls, {} samples, average wait {:.2f} ms/fill",
                    call, gpuSamples.get(), gpuNanos.get() / 1_000_000.0 / call);
        }
        for (int i = 0; i < output.length; i++) output[i] = values[i];
        return true;
    }

    public static synchronized void stop() {
        samplers.clear();
        GpuTerrainAreaRegistry.clear();
        GpuAquiferStatusRegionCache.clear();
        if (terrainScheduler != null) terrainScheduler.close();
        if (chunkFillCoordinator != null) chunkFillCoordinator.close();
        if (fusedChunkScheduler != null) fusedChunkScheduler.close();
        if (terrainRootsScheduler != null) terrainRootsScheduler.close();
        if (terrainRegionWriter != null) terrainRegionWriter.close();
        if (biomeSearchScheduler != null) biomeSearchScheduler.close();
        if (terrainProvider != null) terrainProvider.close();
        VulkanSubmissionRing ring = submissionRing;
        terrainRing = null;
        terrainGraph = null;
        terrainRouter = null;
        terrainBlockOutputLayout = null;
        biomeSearchTree = null;
        GpuBiomeRegionResults.clear();
        submissionRing = null;
        terrainScheduler = null;
        chunkFillCoordinator = null;
        fusedChunkScheduler = null;
        terrainRootsScheduler = null;
        terrainRegionWriter = null;
        biomeSearchScheduler = null;
        terrainProvider = null;
        verifiedTerrainRootMask = 0;
        verifiedTerrainBlockRootMask = 0;
        interpolatedTerrainBlockRootMask = 0;
        terrainBlockOutputVerificationComplete = false;
        if (globalQueue != null) globalQueue.close();
        globalQueue = null;
        // The ring owns its primary engine. Close it through exactly one
        // owner; double-destroying Vulkan handles can poison the next run.
        if (ring != null) ring.close();
        else if (engine != null) engine.close();
        engine = null;
        device = null;
    }
}
