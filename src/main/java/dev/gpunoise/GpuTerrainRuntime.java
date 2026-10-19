package dev.gpunoise;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;

import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.DensityFunction;

/** Runtime bridge used by the vanilla DensityFunctions.Noise fill hook. */
public final class GpuTerrainRuntime {
    private static final float GRAPH_PARITY_EPSILON = 5.0E-3f;
    private static volatile GpuDevice device;
    private static volatile PersistentVulkanNormalNoiseEngine engine;
    private static volatile VulkanSubmissionRing submissionRing;
    private static final Map<NormalNoise, MinecraftNormalNoiseData> samplers = new ConcurrentHashMap<>();
    private static volatile GpuGlobalSamplerQueue globalQueue;
    private static volatile GpuTerrainBatchScheduler terrainScheduler;
    private static volatile GpuChunkFillCoordinator chunkFillCoordinator;
    private static volatile GpuFusedChunkBatchScheduler fusedChunkScheduler;
    private static volatile GpuTerrainRootsBatchScheduler terrainRootsScheduler;
    private static volatile GpuTerrainGraphProvider terrainProvider;
    private static volatile VulkanSubmissionRing terrainRing;
    private static volatile GpuDensityGraph terrainGraph;
    /** The exact router used to build and parity-check terrainGraph. */
    private static volatile NoiseRouter terrainRouter;
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

    public static synchronized boolean start(GpuDevice selected) {
        if (selected == null) return false;
        try {
            submissionRing = VulkanBootstrapThread.call(() -> new VulkanSubmissionRing(selected));
            // The ring owns the one terrain VkInstance/VkDevice/VkQueue.
            // Startup parity uses its primary slot instead of allocating a
            // second complete engine and a second set of persistent buffers.
            engine = submissionRing.primary();
            device = selected;
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
    public static boolean active() { return device != null; }

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

    /**
     * The strict writer is intentionally held behind the exact-coordinate
     * dependency provider.  A NoiseChunk cache is not sufficient: aquifers
     * read preliminary-surface columns outside the chunk and use two scaled
     * coordinate domains.  Enabling it before those dependencies are ready
     * corrupts generation or fails chunk futures.
     */
    public static boolean gpuOnlyWorldgenAvailable() {
        // The GPU fill continuation is designed around C2ME's concurrent
        // chunk-generation pipeline.  Vanilla's scheduler has lower
        // parallelism and the serialized Vulkan submission path can be slower
        // than vanilla CPU generation, so preserve the vanilla path when
        // C2ME is not installed.
        return c2meDfcAvailable() && terrainInputsAvailable() && terrainRootsScheduler != null;
    }

    /** Prevents an overworld graph from being used by another dimension or generator. */
    public static boolean matchesTerrainRouter(RandomState randomState) {
        NoiseRouter installed = terrainRouter;
        return installed != null && randomState != null && randomState.router() == installed;
    }

    /**
     * Starts exactly one cell-lattice request for a chunk.  This is the only
     * submission point for authoritative fillFromNoise work: callers receive
     * a future and never evaluate the CPU density graph while it is pending.
     */
    public static java.util.concurrent.CompletableFuture<GpuChunkDensityCache> prepareChunk(
            Blender blender, RandomState randomState, StructureManager structures, ChunkAccess chunk,
            int minCellY, int cellCountY, NoiseSettings settings) {
        if (!gpuOnlyWorldgenAvailable() || !matchesTerrainRouter(randomState) || cellCountY <= 0) {
            cpuFallbackChunks.incrementAndGet();
            return java.util.concurrent.CompletableFuture.failedFuture(
                    new IllegalStateException("GPU terrain pipeline is unavailable"));
        }
        int minX = chunk.getPos().getMinBlockX();
        int minZ = chunk.getPos().getMinBlockZ();
        GpuChunkDensityCache.prefetch5x5ForChunk(minX, minZ, settings);
        GpuChunkDensityCache cache = GpuTerrainAreaRegistry.claim(minX, minZ,
                () -> GpuChunkDensityCache.create(minX, minZ, settings));
        if (cache.markSubmitted()) gpuChunksSubmitted.incrementAndGet();
        GpuChunkDensityCache preparedCache = cache;
        return preparedCache.ready().thenApply(ignored -> {
            if (preparedCache.markCompleted()) gpuChunksCompleted.incrementAndGet();
            logChunkStatistics(false);
            return preparedCache;
        }).whenComplete((ignored, failure) -> {
            if (failure != null) releaseGpuCache(preparedCache);
        }).toCompletableFuture();
    }

    public static java.util.concurrent.CompletableFuture<GpuChunkDensityCache> prepareChunkForBiomes(
            RandomState randomState, ChunkAccess chunk, NoiseSettings settings) {
        if (!gpuOnlyWorldgenAvailable() || !matchesTerrainRouter(randomState)) {
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
        if (terrainScheduler != null) terrainScheduler.close();
        if (terrainProvider != null) terrainProvider.close();
        if (terrainRootsScheduler != null) terrainRootsScheduler.close();
        terrainRing = submissionRing;
        terrainGraph = graph;
        terrainRouter = router;
        verifiedTerrainBlockRootMask = 0;
        interpolatedTerrainBlockRootMask = 0;
        terrainBlockOutputVerificationComplete = false;
        GpuNoise.LOGGER.info("GPU terrain graph: nodes={}, registers={}, block pass skips {} marker-only nodes",
                graph.nodes().size(), graph.registerCount(), skippedBlockNodes);
        terrainRootsScheduler = new GpuTerrainRootsBatchScheduler();
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

    /** Prime the exact terrain-grid dispatch path before the first worldgen chunk. */
    static void warmTerrainGpu(NoiseSettings settings) {
        GpuTerrainRootsBatchScheduler scheduler = terrainRootsScheduler;
        if (scheduler == null) return;
        int cellWidth = settings.getCellWidth();
        int cellHeight = settings.getCellHeight();
        GpuTerrainGrid grid = new GpuTerrainGrid(
                0, settings.minY(), 0,
                16 / cellWidth + 1,
                settings.height() / cellHeight + 1,
                16 / cellWidth + 1,
                cellWidth, cellHeight);
        try {
            scheduler.submit(grid).toCompletableFuture().join();
            GpuNoise.LOGGER.info("GPU terrain grid path warmed before worldgen.");
        } catch (RuntimeException failure) {
            GpuNoise.LOGGER.warn("GPU terrain grid warmup failed; continuing with normal runtime dispatches.", failure);
        }
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

    static java.util.concurrent.CompletionStage<GpuTerrainBatchResult> submitTerrainGridInterpolated(
            java.nio.ByteBuffer gridMetadata, int chunkCount, int samplesPerChunk,
            java.nio.ByteBuffer outputMetadata, int blocksPerChunk) {
        VulkanSubmissionRing ring = terrainRing;
        if (ring == null || terrainGraph == null) return java.util.concurrent.CompletableFuture.failedStage(
                new IllegalStateException("GPU terrain graph provider is not installed"));
        if (!terrainBlockOutputEnabled()) {
            return ring.submitGraphRootsGrid(gridMetadata, chunkCount, samplesPerChunk)
                    .thenApply(values -> new GpuTerrainBatchResult(
                            new GpuTerrainRootsResult(values, 0, values.length,
                                    terrainGraph.routerRootCount(),
                                    terrainGraph.routerRootCount()
                                            + terrainGraph.interpolatedMarkerCount(),
                                    terrainGraph.interpolatedMarkerCount()),
                            new GpuTerrainBlockResult(new float[0], 0, 0, 1, 16, 0, 16)));
        }
        return ring.submitGraphGridInterpolated(gridMetadata, chunkCount, samplesPerChunk,
                outputMetadata, blocksPerChunk, terrainBlockOutputRootIndexes());
    }

    static boolean terrainBlockOutputEnabled() {
        if (!FULL_BLOCK_OUTPUT) return false;
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
        // final, initial, barrier, floodedness, spread, lava, erosion, depth,
        // temperature, vegetation, continents, ridges, preliminary surface
        return GpuTerrainRoots.COUNT;
    }

    /** Number of parity-verified roots in compact per-block GPU output. */
    static int terrainBlockOutputRootCount() {
        return Integer.bitCount(terrainBlockOutputRootMask());
    }

    /** Stable root ordering for compact block readback, derived only from parity-verified roots. */
    static int[] terrainBlockOutputRootIndexes() {
        GpuDensityGraph graph = terrainGraph;
        if (graph == null) {
            int[] roots = new int[GpuTerrainRoots.COUNT];
            for (int i = 0; i < roots.length; i++) roots[i] = i;
            return roots;
        }
        int mask = terrainBlockOutputRootMask();
        int[] roots = new int[Integer.bitCount(mask)];
        int output = 0;
        for (int root = 0; root < GpuTerrainRoots.COUNT; root++) {
            if ((mask & (1 << root)) != 0) roots[output++] = root;
        }
        return roots;
    }

    private static int terrainBlockOutputRootMask() {
        GpuDensityGraph graph = terrainGraph;
        if (graph == null) return (1 << GpuTerrainRoots.COUNT) - 1;
        int rootLimit = Math.min(GpuTerrainRoots.COUNT, graph.routerRootCount());
        int verified = terrainBlockOutputVerificationComplete
                ? verifiedTerrainBlockRootMask
                    | (EXPERIMENTAL_INTERPOLATED_BLOCK_OUTPUT ? interpolatedTerrainBlockRootMask : 0)
                : supportedTerrainRootMask();
        return verified & ((1 << rootLimit) - 1);
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
            float[] gpu = current.submitGraphRoots(graph, x, y, z);
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
                router.vegetation(), router.continents(), router.ridges()};
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
            float[] gpu = current.submitGraphRoots(graph, x, y, z);
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
        GpuTerrainRootsBatchScheduler scheduler = terrainRootsScheduler;
        if (scheduler == null) {
            verifiedTerrainRootMask = 0;
            verifiedTerrainBlockRootMask = 0;
            interpolatedTerrainBlockRootMask = 0;
            terrainBlockOutputVerificationComplete = true;
            return;
        }
        int cellWidth = settings.getCellWidth();
        int cellHeight = settings.getCellHeight();
        GpuTerrainGrid grid = new GpuTerrainGrid(0, settings.minY(), 0,
                16 / cellWidth + 1, settings.height() / cellHeight + 1,
                16 / cellWidth + 1, cellWidth, cellHeight);
        try {
            GpuTerrainBatchResult batch = scheduler.submit(grid).toCompletableFuture().join();
            GpuTerrainRootsResult values = batch.corners();
            DensityFunction[] cpu = {
                    router.finalDensity(), router.initialDensityWithoutJaggedness(),
                    router.barrierNoise(), router.fluidLevelFloodednessNoise(),
                    router.fluidLevelSpreadNoise(), router.lavaNoise(), router.erosion(),
                    router.depth(), router.temperature(), router.vegetation(),
                    router.continents(), router.ridges()
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
                            float actual = values.valueAt(sample, root);
                            float expected = (float) cpu[root].compute(
                                    new DensityFunction.SinglePointContext(x, y, z));
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
            int verifiedBlockRoots = verifyTerrainBlockOutputs(graph, cpu, batch.blocks(), settings);
            verifiedTerrainBlockRootMask = verifiedBlockRoots & verifiedTerrainRootMask;
            interpolatedTerrainBlockRootMask = findInterpolatedBlockOutputRoots(graph, batch.blocks())
                    & verifiedTerrainRootMask;
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
            verifyBlendedNoiseNodes(current, graph, x, y, z);
            float worst = 0.0f;
            float[] allRoots = current.submitGraphRoots(graph, x, y, z);
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
    private static void verifyBlendedNoiseNodes(PersistentVulkanNormalNoiseEngine current,
                                                GpuDensityGraph graph, float[] x, float[] y, float[] z) throws Exception {
        int checked = 0;
        for (int node = 0; node < graph.nodes().size(); node++) {
            if (!graph.nodes().get(node).opcode().equals("BlendedNoise")) continue;
            // The lowered graph stores physical register numbers separately
            // from its instruction indices. The shader's single-root test
            // reads a register, so passing the instruction index compares the
            // wrong value after register allocation.
            int resultRegister = graph.nodes().get(node).register();
            float[] gpu = current.submitGraph(graph, x, y, z, resultRegister);
            DensityFunction cpuFunction = graph.sourceFunctions().get(node);
            float maxError = 0.0f;
            int maxIndex = -1;
            float maxGpu = 0.0f;
            float maxCpu = 0.0f;
            for (int i = 0; i < x.length; i++) {
                float cpu = (float) cpuFunction.compute(new DensityFunction.SinglePointContext(
                        (int) x[i], (int) y[i], (int) z[i]));
                float error = GpuTerrainParity.error(gpu[i], cpu);
                if (error > maxError) {
                    maxError = error;
                    maxIndex = i;
                    maxGpu = gpu[i];
                    maxCpu = cpu;
                }
            }
            GpuNoise.LOGGER.info("GPU BlendedNoise parity: node={} maxError={} parity={}",
                    node, maxError, maxError < 1.0E-3f);
            if (maxIndex >= 0) GpuNoise.LOGGER.info(
                    "GPU BlendedNoise detail: at ({},{},{}): gpu={}, cpu={}",
                    (int) x[maxIndex], (int) y[maxIndex], (int) z[maxIndex], maxGpu, maxCpu);
            checked++;
        }
        GpuNoise.LOGGER.info("GPU BlendedNoise parity test: {} nodes checked", checked);
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
        if (terrainScheduler != null) terrainScheduler.close();
        if (chunkFillCoordinator != null) chunkFillCoordinator.close();
        if (fusedChunkScheduler != null) fusedChunkScheduler.close();
        if (terrainRootsScheduler != null) terrainRootsScheduler.close();
        if (terrainProvider != null) terrainProvider.close();
        VulkanSubmissionRing ring = submissionRing;
        terrainRing = null;
        terrainGraph = null;
        submissionRing = null;
        terrainScheduler = null;
        chunkFillCoordinator = null;
        fusedChunkScheduler = null;
        terrainRootsScheduler = null;
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
