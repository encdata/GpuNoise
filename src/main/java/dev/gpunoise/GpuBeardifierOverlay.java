package dev.gpunoise;

/** Read-only slice of a completed Beardifier dispatch shared by its chunk requests. */
public final class GpuBeardifierOverlay {
    private final float[] values;
    private final int offset;
    private final int length;

    GpuBeardifierOverlay(float[] values, int offset, int length) {
        if (values == null || offset < 0 || length <= 0 || offset > values.length - length)
            throw new IllegalArgumentException("invalid GPU Beardifier overlay slice");
        this.values = values;
        this.offset = offset;
        this.length = length;
    }

    public int length() { return length; }

    public float get(int index) {
        if (index < 0 || index >= length) throw new IndexOutOfBoundsException(index);
        return values[offset + index];
    }
}
