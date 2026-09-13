package dev.gpunoise;

/** Internal bridge for the package-private NoiseChunk interpolator updates. */
public interface GpuNoiseInterpolatorAccess {
    void gpunoise$updateForY(double y);

    void gpunoise$updateForX(double x);

    void gpunoise$updateForZ(double z);
}
