package dev.gpunoise;

public interface GpuNoiseChunkAccess {
    GpuChunkDensityCache gpunoise$gpuCache();

    void gpunoise$setGpuCache(GpuChunkDensityCache cache);

    boolean gpunoise$matchesGpuCache(GpuChunkDensityCache cache);

    double gpunoise$withBeardifier(double cachedDensity, int x, int y, int z);
}
