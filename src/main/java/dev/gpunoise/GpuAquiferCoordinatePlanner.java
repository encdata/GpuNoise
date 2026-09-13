package dev.gpunoise;

/** Implemented on vanilla NoiseBasedAquifer by the mixin. */
public interface GpuAquiferCoordinatePlanner {
    void gpunoise$addDependencyCoordinates(GpuWorldgenDependency.Builder builder);
}
