package dev.gpunoise;

/** Read-only chunk range into one shared, three-bit Vulkan ore-decision readback. */
final class GpuOreVeinBatchResult {
    private final int[] packedCodes;
    private final int offset;
    private final int count;

    GpuOreVeinBatchResult(int[] packedCodes, int totalRows, int offset, int count) {
        if (packedCodes == null || totalRows <= 0 || offset < 0 || count <= 0
                || offset > totalRows - count
                || packedCodes.length != (totalRows + 9) / 10)
            throw new IllegalArgumentException("invalid ore-vein batch result range");
        this.packedCodes = packedCodes;
        this.offset = offset;
        this.count = count;
    }

    int count() { return count; }

    void copyTo(byte[] destination, int destinationOffset) {
        if (destination == null || destinationOffset < 0 || destinationOffset > destination.length - count)
            throw new IllegalArgumentException("invalid ore-vein result destination");
        for (int row = 0; row < count; row++) {
            int sourceRow = offset + row;
            destination[destinationOffset + row] = (byte)
                    ((packedCodes[sourceRow / 10] >>> ((sourceRow % 10) * 3)) & 0x7);
        }
    }
}
