package dev.gpunoise;

/** Immutable row view into an aquifer result slab. */
public final class GpuAquiferRowsView {
    private final int[] values;
    private final int offset;
    private final int rows;
    private final int stride;

    GpuAquiferRowsView(int[] values, int offset, int rows, int stride) {
        if (values == null || offset < 0 || rows < 0 || stride <= 0
                || (long) offset + (long) rows * stride > values.length)
            throw new IllegalArgumentException("invalid aquifer row view");
        this.values = values;
        this.offset = offset;
        this.rows = rows;
        this.stride = stride;
    }

    public static GpuAquiferRowsView whole(int[] values, int stride) {
        if (values == null || stride <= 0 || values.length % stride != 0)
            throw new IllegalArgumentException("invalid aquifer result rows");
        return new GpuAquiferRowsView(values, 0, values.length / stride, stride);
    }

    public int rows() { return rows; }

    public int get(int row, int column) {
        if (row < 0 || row >= rows || column < 0 || column >= stride)
            throw new IndexOutOfBoundsException();
        return values[offset + row * stride + column];
    }
}
