package dev.gpunoise;

/**
 * A zero-copy view into one completed fused batch output.
 *
 * The backing array belongs to the completed batch. Each chunk keeps only its
 * range, so splitting a 100-chunk result does not allocate/copy one array per
 * chunk.
 */
final class GpuFusedResult {
    private final float[] batchOutput;
    private final int offset;
    private final int length;
    private final int roots;

    GpuFusedResult(float[] batchOutput, int offset, int length, int roots) {
        if (batchOutput == null || offset < 0 || length < 0 || offset > batchOutput.length - length) {
            throw new IllegalArgumentException("invalid fused result view");
        }
        this.batchOutput = batchOutput;
        this.offset = offset;
        this.length = length;
        this.roots = roots;
    }

    /** Reads one value relative to this chunk's output range. */
    float valueAt(int index) {
        if (index < 0 || index >= length) throw new IndexOutOfBoundsException(index);
        return batchOutput[offset + index];
    }

    int length() { return length; }
    int roots() { return roots; }

    /** The shared completed-batch array; callers must use offset/length. */
    float[] batchOutput() { return batchOutput; }
    int offset() { return offset; }
}
