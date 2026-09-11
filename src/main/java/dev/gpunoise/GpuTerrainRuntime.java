package dev.gpunoise;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import net.minecraft.world.level.levelgen.DensityFunction;
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
    private static volatile boolean graphParity;
    private static final AtomicLong gpuCalls = new AtomicLong();
    private static final AtomicLong gpuSamples = new AtomicLong();
    private static final AtomicLong gpuNanos = new AtomicLong();

    private GpuTerrainRuntime() {}

    public static synchronized void start(GpuDevice selected) {
        try {
            engine = new PersistentVulkanNormalNoiseEngine(selected);
            submissionRing = new VulkanSubmissionRing(selected);
            globalQueue = new GpuGlobalSamplerQueue(submissionRing);
            device = selected;
        } catch (Exception failure) {
            GpuNoise.LOGGER.error("Persistent Vulkan terrain engine failed to initialize", failure);
            engine = null;
            if (submissionRing != null) submissionRing.close();
            submissionRing = null;
            device = null;
        }
    }
    public static boolean active() { return device != null; }

    static synchronized void installTerrainGraph(GpuDensityGraph graph) {
        if (engine == null) throw new IllegalStateException("GPU engine is not started");
        if (terrainScheduler != null) terrainScheduler.close();
        if (terrainProvider != null) terrainProvider.close();
        if (terrainRootsScheduler != null) terrainRootsScheduler.close();
        terrainRing = submissionRing;
        terrainGraph = graph;
        fusedChunkScheduler = new GpuFusedChunkBatchScheduler();
        terrainRootsScheduler = new GpuTerrainRootsBatchScheduler();
        terrainProvider = new GpuTerrainGraphProvider(terrainRing, graph);
        terrainScheduler = new GpuTerrainBatchScheduler(terrainProvider);
        chunkFillCoordinator = new GpuChunkFillCoordinator();
        GpuNoise.LOGGER.info("GPU chunk terrain provider ready: async exact slabs, batches up to {} chunks, all {} router roots per dispatch.",
                TerrainNoiseApi.MAX_CHUNKS_PER_BATCH, graph.routerRoots().length);
    }

    static java.util.concurrent.CompletionStage<TerrainNoiseApi.Result> submitTerrain(TerrainNoiseApi.Request request) {
        GpuTerrainBatchScheduler scheduler = terrainScheduler;
        if (scheduler == null) return java.util.concurrent.CompletableFuture.failedStage(
                new IllegalStateException("GPU terrain graph provider is not installed"));
        return scheduler.submit(request);
    }

    static java.util.concurrent.CompletionStage<float[]> submitTerrainRoots(int[] x, int[] y, int[] z) {
        GpuTerrainRootsBatchScheduler scheduler = terrainRootsScheduler;
        if (scheduler != null) return scheduler.submit(x, y, z);
        return submitTerrainRootsDirect(x, y, z);
    }

    static java.util.concurrent.CompletionStage<float[]> submitTerrainRootsDirect(int[] x, int[] y, int[] z) {
        VulkanSubmissionRing ring = terrainRing;
        GpuDensityGraph graph = terrainGraph;
        if (ring == null || graph == null) return java.util.concurrent.CompletableFuture.failedStage(
                new IllegalStateException("GPU terrain graph provider is not installed"));
        return ring.submitGraphRoots(graph, toFloat(x), toFloat(y), toFloat(z));
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
        return graph == null ? 1 : graph.routerRoots().length;
    }

    /** Number of roots currently materialized into the per-block terrain output. */
    static int terrainOutputRootCount() {
        // The hot path is final density. Initial density and aquifer inputs
        // remain vanilla-owned so the GPU readback stays within one root.
        return 1;
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

    private static float[] toFloat(int[] values) {
        float[] result = new float[values.length];
        for (int i = 0; i < values.length; i++) result[i] = values[i];
        return result;
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
        for (int i = 0; i < count; i++) {
            x[i] = (i % 32) * 8.0f;
            y[i] = -64.0f + (i / 32 % 16) * 24.0f;
            z[i] = (i / 512) * 8.0f;
        }
        DensityFunction[] cpu = {router.finalDensity(), router.initialDensityWithoutJaggedness(),
                router.fluidLevelFloodednessNoise()};
        try {
            float[] gpu = current.submitGraphRoots(graph, x, y, z);
            float worst = 0.0f;
            for (int root = 0; root < 3; root++) {
                float rootWorst = 0.0f;
                int worstIndex = -1;
                float worstGpu = 0.0f;
                double worstCpu = 0.0;
                for (int i = 0; i < count; i++) {
                    double expected = cpu[root].compute(new DensityFunction.SinglePointContext(
                            (int) x[i], (int) y[i], (int) z[i]));
                    float error = Math.abs(gpu[i * 3 + root] - (float) expected);
                    if (error > rootWorst) {
                        rootWorst = error;
                        worstIndex = i;
                        worstGpu = gpu[i * 3 + root];
                        worstCpu = expected;
                    }
                }
                worst = Math.max(worst, rootWorst);
                GpuNoise.LOGGER.info("GPU terrain root {} parity: maxError={}, at ({},{},{}), gpu={}, cpu={}",
                        root, rootWorst, (int) x[worstIndex], (int) y[worstIndex], (int) z[worstIndex],
                        worstGpu, worstCpu);
            }
            graphParity = worst < GRAPH_PARITY_EPSILON;
            GpuNoise.LOGGER.info("GPU terrain graph parity: {} samples, worstMaxError={}, parity={}",
                    count, worst, graphParity);
        } catch (Throwable failure) {
            graphParity = false;
            GpuNoise.LOGGER.warn("GPU terrain graph parity dispatch failed", failure);
        }
    }

    static void verifyGraph(GpuDensityGraph graph, NoiseRouter router) {
        PersistentVulkanNormalNoiseEngine current = engine;
        if (current == null) return;
        int count = 4096;
        float[] x = new float[count], y = new float[count], z = new float[count];
        for (int i = 0; i < count; i++) {
            x[i] = (i % 64) * 4.0f;
            y[i] = -64.0f + (i / 64 % 64) * 4.0f;
            z[i] = (i / 64) * 4.0f;
        }
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
            for (int root = 0; root < graph.routerRoots().length; root++) {
                float maxError = 0.0f;
                int maxIndex = -1;
                float maxGpu = 0.0f;
                double maxCpu = 0.0;
                for (int i = 0; i < count; i++) {
                    double expected = cpuRoots[root].compute(new DensityFunction.SinglePointContext(
                            (int) x[i], (int) y[i], (int) z[i]));
                    float gpu = allRoots[i * graph.routerRoots().length + root];
                    float error = Math.abs(gpu - (float) expected);
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
            graphParity = worst < GRAPH_PARITY_EPSILON;
            GpuNoise.LOGGER.info("GPU full-density graph test: {} samples, {} nodes, worstMaxError={}, epsilon={}, parity={}",
                    count, graph.nodes().size(), worst, GRAPH_PARITY_EPSILON, graphParity);
        } catch (Exception failure) {
            graphParity = false;
            GpuNoise.LOGGER.warn("GPU full-density graph dispatch failed; terrain remains CPU-owned", failure);
        }
    }


    /** Verifies the packed BlendedNoise records independently of router branches. */
    private static void verifyBlendedNoiseNodes(PersistentVulkanNormalNoiseEngine current,
                                                GpuDensityGraph graph, float[] x, float[] y, float[] z) throws Exception {
        int checked = 0;
        for (int node = 0; node < graph.nodes().size(); node++) {
            if (!graph.nodes().get(node).opcode().equals("BlendedNoise")) continue;
            float[] gpu = current.submitGraph(graph, x, y, z, node);
            DensityFunction cpuFunction = graph.sourceFunctions().get(node);
            float maxError = 0.0f;
            int maxIndex = -1;
            float maxGpu = 0.0f;
            float maxCpu = 0.0f;
            for (int i = 0; i < x.length; i++) {
                float cpu = (float) cpuFunction.compute(new DensityFunction.SinglePointContext(
                        (int) x[i], (int) y[i], (int) z[i]));
                float error = Math.abs(gpu[i] - cpu);
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




    public static boolean tryFill(NormalNoise noise, double xzScale, double yScale,
                           DensityFunction.ContextProvider contexts, double[] output) {
        GpuDevice selected = device;
        PersistentVulkanNormalNoiseEngine currentEngine = engine;
        GpuGlobalSamplerQueue currentQueue = globalQueue;
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

    public static void stop() {
        samplers.clear();
        if (terrainScheduler != null) terrainScheduler.close();
        if (chunkFillCoordinator != null) chunkFillCoordinator.close();
        if (fusedChunkScheduler != null) fusedChunkScheduler.close();
        if (terrainRootsScheduler != null) terrainRootsScheduler.close();
        if (terrainProvider != null) terrainProvider.close();
        terrainRing = null;
        terrainGraph = null;
        if (submissionRing != null) submissionRing.close();
        submissionRing = null;
        terrainScheduler = null;
        chunkFillCoordinator = null;
        fusedChunkScheduler = null;
        terrainRootsScheduler = null;
        terrainProvider = null;
        graphParity = false;
        if (globalQueue != null) globalQueue.close();
        globalQueue = null;
        if (engine != null) engine.close();
        engine = null;
        device = null;
    }
}
