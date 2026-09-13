package dev.gpunoise;

import java.util.concurrent.CompletionStage;

/**
 * GPU provider for chunk cell-grid work. One request can contain up to 100
 * chunks; the graph is evaluated once for every sample and all router roots
 * are returned from the same Vulkan dispatch.
 */
final class GpuTerrainGraphProvider implements TerrainNoiseApi.Provider, AutoCloseable {
    private final VulkanSubmissionRing ring;
    private final GpuDensityGraph graph;
    GpuTerrainGraphProvider(VulkanSubmissionRing ring, GpuDensityGraph graph) {
        this.ring = ring;
        this.graph = graph;
    }

    @Override
    public CompletionStage<TerrainNoiseApi.Result> submit(TerrainNoiseApi.Request request) {
        return ring.submitGraphRoots(graph, toFloat(request.sampleX()), toFloat(request.sampleY()), toFloat(request.sampleZ()))
                .thenApply(roots -> result(request, roots));
    }

    private static TerrainNoiseApi.Result result(TerrainNoiseApi.Request request, float[] roots) {
        int rootCount = roots.length / (request.chunks().size() * request.samplesPerChunk());
        int samples = request.chunks().size() * request.samplesPerChunk();
        if (rootCount != GpuTerrainRoots.COUNT) {
            throw new IllegalArgumentException("compact GPU terrain provider requires "
                    + GpuTerrainRoots.COUNT + " terrain roots, got " + rootCount);
        }
        float[][] fields = new float[8][samples];
        for (int sample = 0; sample < samples; sample++) {
            int source = sample * rootCount;
            // The compact batch has only the graph values consumed by terrain
            // and aquifers. Climate remains Minecraft-owned in this provider.
            fields[0][sample] = roots[source + GpuTerrainRoots.FINAL_DENSITY];
            fields[1][sample] = roots[source + GpuTerrainRoots.FINAL_DENSITY];
            fields[2][sample] = roots[source + GpuTerrainRoots.FLOODEDNESS];
            fields[4][sample] = roots[source + GpuTerrainRoots.EROSION];
        }
        return TerrainNoiseApi.Result.owned(request, fields[0], fields[1], fields[2], fields[3],
                fields[4], fields[5], fields[6], fields[7]);
    }

    private static float[] toFloat(int[] values) {
        float[] result = new float[values.length];
        for (int i = 0; i < values.length; i++) result[i] = values[i];
        return result;
    }

    @Override
    public void close() {
        // The ring is shared with the global sampler queue and is owned by the runtime.
    }
}
