package dev.gpunoise;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
        float[][] fields = new float[8][samples];
        for (int sample = 0; sample < samples; sample++) {
            int source = sample * rootCount;
            if (rootCount == 1) {
                fields[0][sample] = roots[source];
            } else if (rootCount == 2) {
                fields[0][sample] = roots[source];
                fields[2][sample] = roots[source + 1];
            } else {
                fields[0][sample] = roots[source + 11];
                fields[1][sample] = roots[source + 10];
                fields[2][sample] = roots[source + 1];
                fields[3][sample] = roots[source + 6];
                fields[4][sample] = roots[source + 7];
                fields[5][sample] = roots[source + 9];
                fields[6][sample] = roots[source + 4];
                fields[7][sample] = roots[source + 5];
            }
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
