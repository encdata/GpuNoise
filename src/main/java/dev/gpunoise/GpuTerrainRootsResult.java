package dev.gpunoise;

/** A view into one shared GPU roots readback, without copying per chunk. */
final class GpuTerrainRootsResult {
    private final float[] values;
    private final int offset;
    private final int length;
    private final int roots;
    private final int stride;
    private final int interpolatorCount;
    /** Maps logical router-root IDs to compact output positions; null means identity. */
    private final int[] rootIndexes;

    GpuTerrainRootsResult(float[] values, int offset, int length, int roots) {
        this(values, offset, length, roots, roots);
    }

    GpuTerrainRootsResult(float[] values, int offset, int length, int roots, int stride) {
        this(values, offset, length, roots, stride, stride - roots, null);
    }

    GpuTerrainRootsResult(float[] values, int offset, int length, int roots, int stride,
                          int interpolatorCount) {
        this(values, offset, length, roots, stride, interpolatorCount, null);
    }

    GpuTerrainRootsResult(float[] values, int offset, int length, int roots, int stride,
                          int interpolatorCount, int[] rootIndexes) {
        if (rootIndexes != null && rootIndexes.length != stride - interpolatorCount)
            throw new IllegalArgumentException("compact GPU root map does not match output stride");
        if (offset < 0 || length < 0 || roots <= 0 || stride <= 0 || interpolatorCount < 0
                || (rootIndexes == null && (roots + interpolatorCount > stride))
                || (rootIndexes != null && interpolatorCount > stride)
                || offset + length > values.length || length % stride != 0)
            throw new IllegalArgumentException("invalid GPU roots view");
        this.values = values;
        this.offset = offset;
        this.length = length;
        this.roots = roots;
        this.stride = stride;
        this.interpolatorCount = interpolatorCount;
        this.rootIndexes = rootIndexes == null ? null : rootIndexes.clone();
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
                samples * stride, roots, stride, interpolatorCount, rootIndexes);
    }
    float valueAt(int sample, int root) {
        if (sample < 0 || sample >= sampleCount() || root < 0 || root >= roots)
            throw new IndexOutOfBoundsException();
        int outputIndex = root;
        if (rootIndexes != null) {
            outputIndex = -1;
            for (int i = 0; i < rootIndexes.length; i++) {
                if (rootIndexes[i] == root) { outputIndex = i; break; }
            }
            if (outputIndex < 0) throw new IndexOutOfBoundsException("root was not included in compact output");
        }
        return values[offset + sample * stride + outputIndex];
    }

    /** Caller has already validated the sample and root coordinates. */
    float valueAtUnchecked(int sample, int root) {
        int outputIndex = root;
        if (rootIndexes != null) {
            outputIndex = -1;
            for (int i = 0; i < rootIndexes.length; i++) {
                if (rootIndexes[i] == root) { outputIndex = i; break; }
            }
            if (outputIndex < 0) return Float.NaN;
        }
        return values[offset + sample * stride + outputIndex];
    }
}
