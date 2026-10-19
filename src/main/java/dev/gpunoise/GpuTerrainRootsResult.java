package dev.gpunoise;

/** A view into one shared GPU roots readback, without copying per chunk. */
final class GpuTerrainRootsResult {
    private final float[] values;
    private final int offset;
    private final int length;
    private final int roots;
    private final int stride;
    private final int interpolatorCount;

    GpuTerrainRootsResult(float[] values, int offset, int length, int roots) {
        this(values, offset, length, roots, roots);
    }

    GpuTerrainRootsResult(float[] values, int offset, int length, int roots, int stride) {
        this(values, offset, length, roots, stride, stride - roots);
    }

    GpuTerrainRootsResult(float[] values, int offset, int length, int roots, int stride,
                          int interpolatorCount) {
        if (offset < 0 || length < 0 || roots <= 0 || stride < roots || interpolatorCount < 0
                || roots + interpolatorCount > stride || offset + length > values.length
                || length % stride != 0) throw new IllegalArgumentException("invalid GPU roots view");
        this.values = values;
        this.offset = offset;
        this.length = length;
        this.roots = roots;
        this.stride = stride;
        this.interpolatorCount = interpolatorCount;
    }

    int sampleCount() { return length / stride; }
    int roots() { return roots; }
    int stride() { return stride; }
    int interpolatorCount() { return interpolatorCount; }
    float interpolatedValueAt(int sample, int marker) {
        if (sample < 0 || sample >= sampleCount() || marker < 0 || marker >= interpolatorCount)
            throw new IndexOutOfBoundsException();
        return values[offset + sample * stride + roots + marker];
    }
    GpuTerrainRootsResult slice(int sampleOffset, int samples) {
        if (sampleOffset < 0 || samples < 0 || sampleOffset + samples > sampleCount())
            throw new IndexOutOfBoundsException();
        return new GpuTerrainRootsResult(values, offset + sampleOffset * stride,
                samples * stride, roots, stride, interpolatorCount);
    }
    float valueAt(int sample, int root) {
        if (sample < 0 || sample >= sampleCount() || root < 0 || root >= roots)
            throw new IndexOutOfBoundsException();
        return values[offset + sample * stride + root];
    }

    /** Caller has already validated the sample and root coordinates. */
    float valueAtUnchecked(int sample, int root) {
        return values[offset + sample * stride + root];
    }
}
