package dev.gpunoise;

import java.util.Arrays;

/** Co-submitted aquifer outputs, optionally represented as ranges in a shared batch readback. */
public final class GpuAquiferPrefillResult {
    private static final int[] EMPTY = new int[0];
    private final int[] nearestStorage;
    private final int nearestOffset;
    private final int nearestLength;
    private final int[] fluidStatusStorage;
    private final int fluidStatusOffset;
    private final int fluidStatusLength;
    private final int[] decisionStorage;
    private final int decisionOffset;
    private final int decisionLength;

    public GpuAquiferPrefillResult(int[] nearestRows, int[] fluidStatusRows, int[] decisionRows) {
        this(nearestRows, 0, nearestRows == null ? 0 : nearestRows.length,
                fluidStatusRows, 0, fluidStatusRows == null ? 0 : fluidStatusRows.length,
                decisionRows, 0, decisionRows == null ? 0 : decisionRows.length);
    }

    public GpuAquiferPrefillResult(int[] nearestRows, int[] fluidStatusRows) {
        this(nearestRows, fluidStatusRows, new int[0]);
    }

    private GpuAquiferPrefillResult(int[] nearestRows, int nearestOffset, int nearestLength,
                                    int[] fluidStatusRows, int fluidStatusOffset, int fluidStatusLength,
                                    int[] decisionRows, int decisionOffset, int decisionLength) {
        validateRange(nearestRows, nearestOffset, nearestLength, "nearest-center");
        validateRange(fluidStatusRows, fluidStatusOffset, fluidStatusLength, "fluid-status");
        validateRange(decisionRows, decisionOffset, decisionLength, "decision-chain");
        this.nearestStorage = nearestRows;
        this.nearestOffset = nearestOffset;
        this.nearestLength = nearestLength;
        this.fluidStatusStorage = fluidStatusRows;
        this.fluidStatusOffset = fluidStatusOffset;
        this.fluidStatusLength = fluidStatusLength;
        this.decisionStorage = decisionRows;
        this.decisionOffset = decisionOffset;
        this.decisionLength = decisionLength;
    }

    static GpuAquiferPrefillResult slice(GpuAquiferPrefillResult source,
                                         int nearestOffset, int nearestLength,
                                         int fluidStatusOffset, int fluidStatusLength,
                                         int decisionOffset, int decisionLength) {
        if (source == null) throw new NullPointerException("source");
        return new GpuAquiferPrefillResult(source.nearestStorage, nearestOffset, nearestLength,
                source.fluidStatusStorage, fluidStatusOffset, fluidStatusLength,
                source.decisionStorage, decisionOffset, decisionLength);
    }

    GpuAquiferPrefillResult withoutFluidStatusRows() {
        return new GpuAquiferPrefillResult(nearestStorage, nearestOffset, nearestLength,
                EMPTY, 0, 0, decisionStorage, decisionOffset, decisionLength);
    }

    /** Returns an owned array for sliced results; whole-batch results retain record-style access. */
    public int[] nearestRows() { return copyRange(nearestStorage, nearestOffset, nearestLength); }
    public int[] fluidStatusRows() { return copyRange(fluidStatusStorage, fluidStatusOffset, fluidStatusLength); }
    public int[] decisionRows() { return copyRange(decisionStorage, decisionOffset, decisionLength); }

    public GpuAquiferRowsView nearestView() {
        int stride = GpuAquiferNearestThree.OUTPUT_INTS;
        if (nearestLength % stride != 0) throw new IllegalStateException("nearest-center range is not row-aligned");
        return new GpuAquiferRowsView(nearestStorage, nearestOffset, nearestLength / stride, stride);
    }

    public int decisionValue(int row, int component) {
        int stride = 3;
        if (row < 0 || row >= decisionLength / stride || component < 0 || component >= stride)
            throw new IndexOutOfBoundsException();
        return decisionStorage[decisionOffset + row * stride + component];
    }

    public int nearestValueCount() { return nearestLength; }
    public int fluidStatusValueCount() { return fluidStatusLength; }
    public int decisionValueCount() { return decisionLength; }

    public int fluidStatusValue(int row, int component) {
        int stride = GpuAquiferFluidStatus.OUTPUT_INTS;
        if (row < 0 || row >= fluidStatusLength / stride || component < 0 || component >= stride)
            throw new IndexOutOfBoundsException();
        int index = row * stride + component;
        return fluidStatusStorage[fluidStatusOffset + index];
    }

    private static int[] copyRange(int[] source, int offset, int length) {
        if (offset == 0 && length == source.length) return source;
        return Arrays.copyOfRange(source, offset, offset + length);
    }

    private static void validateRange(int[] values, int offset, int length, String label) {
        if (values == null || offset < 0 || length < 0 || offset > values.length - length)
            throw new IllegalArgumentException("invalid aquifer " + label + " result range");
    }
}
