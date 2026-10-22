package dev.gpunoise;

/** Read-only chunk range into one shared, compact Vulkan ore-decision readback. */
final class GpuOreVeinBatchResult {
    private final byte[] stateCodes;
    private final int offset;
    private final int count;

    GpuOreVeinBatchResult(byte[] stateCodes, int offset, int count) {
        if (stateCodes == null || offset < 0 || count <= 0 || offset > stateCodes.length - count)
            throw new IllegalArgumentException("invalid ore-vein batch result range");
        this.stateCodes = stateCodes;
        this.offset = offset;
        this.count = count;
    }

    int count() { return count; }

    void copyTo(byte[] destination, int destinationOffset) {
        if (destination == null || destinationOffset < 0 || destinationOffset > destination.length - count)
            throw new IllegalArgumentException("invalid ore-vein result destination");
        System.arraycopy(stateCodes, offset, destination, destinationOffset, count);
    }
}
