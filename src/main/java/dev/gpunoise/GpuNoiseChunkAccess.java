package dev.gpunoise;

public interface GpuNoiseChunkAccess {
    GpuChunkDensityCache gpunoise$gpuCache();

    void gpunoise$setGpuCache(GpuChunkDensityCache cache);

    boolean gpunoise$matchesGpuCache(GpuChunkDensityCache cache);

    boolean gpunoise$fillGpuFinalDensityCell(double[] values);
}
