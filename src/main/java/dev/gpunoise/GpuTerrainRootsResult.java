package dev.gpunoise;

/** A view into one shared GPU roots readback, without copying per chunk. */
final class GpuTerrainRootsResult {
    private final float[] values;
    private final int offset;
    private final int length;
    private final int roots;

    GpuTerrainRootsResult(float[] values, int offset, int length, int roots) {
        if (offset < 0 || length < 0 || roots <= 0 || offset + length > values.length
                || length % roots != 0) throw new IllegalArgumentException("invalid GPU roots view");
        this.values = values;
        this.offset = offset;
        this.length = length;
        this.roots = roots;
    }

    int sampleCount() { return length / roots; }
    int roots() { return roots; }
    GpuTerrainRootsResult slice(int sampleOffset, int samples) {
        if (sampleOffset < 0 || samples < 0 || sampleOffset + samples > sampleCount())
            throw new IndexOutOfBoundsException();
        return new GpuTerrainRootsResult(values, offset + sampleOffset * roots, samples * roots, roots);
    }
    float valueAt(int sample, int root) {
        if (sample < 0 || sample >= sampleCount() || root < 0 || root >= roots)
            throw new IndexOutOfBoundsException();
        return values[offset + sample * roots + root];
    }
}
