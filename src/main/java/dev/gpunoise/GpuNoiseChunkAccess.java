package dev.gpunoise;

public interface GpuNoiseChunkAccess {
    net.minecraft.world.level.block.state.BlockState gpunoise$vanillaInterpolatedState();

    GpuChunkDensityCache gpunoise$gpuCache();

    void gpunoise$setGpuCache(GpuChunkDensityCache cache);

    boolean gpunoise$matchesGpuCache(GpuChunkDensityCache cache);

    double gpunoise$withBeardifier(double cachedDensity, int x, int y, int z);

    GpuBeardifierInputs gpunoise$beardifierInputs();

    boolean gpunoise$hasZeroBeardifier();

    int gpunoise$preliminarySurfaceLevel(int x, int z);
}
