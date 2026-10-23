package dev.gpunoise;

public interface GpuNoiseChunkAccess {
    net.minecraft.world.level.block.state.BlockState gpunoise$vanillaInterpolatedState();

    GpuChunkDensityCache gpunoise$gpuCache();

    void gpunoise$setGpuCache(GpuChunkDensityCache cache);

    boolean gpunoise$matchesGpuCache(GpuChunkDensityCache cache);

    double gpunoise$withBeardifier(double cachedDensity, int x, int y, int z);

    GpuBeardifierInputs gpunoise$beardifierInputs();

    boolean gpunoise$hasZeroBeardifier();

    void gpunoise$setGpuBeardifierOverlay(int minX, int minY, int minZ, int height,
                                          GpuBeardifierOverlay values);

    int gpunoise$preliminarySurfaceLevel(int x, int z);
}
