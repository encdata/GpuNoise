package dev.gpunoise;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Metadata for batched cell-grid to block-density interpolation. */
final class GpuInterpolationBatch {
    static final int METADATA_INTS = 12;
    static final int METADATA_BYTES = METADATA_INTS * Integer.BYTES;
    private static final int ORIGIN_X_WORD = 8;
    private static final int ORIGIN_Y_WORD = 9;
    private static final int ORIGIN_Z_WORD = 10;

    private GpuInterpolationBatch() {}

    static ByteBuffer metadata(int chunkCount, int cellsX, int cellsY, int cellsZ,
                               int blocksPerCellX, int blocksPerCellY,
                               int cornersPerChunk, int blocksPerChunk) {
        ByteBuffer result = ByteBuffer.allocateDirect(chunkCount * METADATA_BYTES)
                .order(ByteOrder.nativeOrder());
        for (int chunk = 0; chunk < chunkCount; chunk++) {
            write(result, chunk, chunk * cornersPerChunk, chunk * blocksPerChunk,
                    cellsX, cellsY, cellsZ, blocksPerCellX, blocksPerCellY, 0, 0, 0);
        }
        return (ByteBuffer) result.position(0).limit(result.capacity());
    }

    static ByteBuffer metadata(int chunkCount, int cellsX, int cellsY, int cellsZ,
                               int blocksPerCellX, int blocksPerCellY,
                               int cornersPerChunk, int blocksPerChunk,
                               int minX, int minY, int minZ) {
        ByteBuffer result = metadata(chunkCount, cellsX, cellsY, cellsZ,
                blocksPerCellX, blocksPerCellY, cornersPerChunk, blocksPerChunk);
        for (int chunk = 0; chunk < chunkCount; chunk++) {
            setOrigin(result, chunk, minX, minY, minZ);
        }
        return result;
    }

    /** Writes the shared std430 record consumed by noise_interpolation.comp. */
    static void write(ByteBuffer target, int chunk, int cornerOffset, int blockOffset,
                      int cellsX, int cellsY, int cellsZ,
                      int blocksPerCellX, int blocksPerCellY,
                      int originX, int originY, int originZ) {
        if (chunk < 0 || target == null || target.capacity() < (long) (chunk + 1) * METADATA_BYTES) {
            throw new IllegalArgumentException("invalid interpolation metadata destination");
        }
        int base = chunk * METADATA_BYTES;
        target.putInt(base, cornerOffset).putInt(base + 4, blockOffset)
                .putInt(base + 8, cellsX).putInt(base + 12, cellsY).putInt(base + 16, cellsZ)
                .putInt(base + 20, blocksPerCellX).putInt(base + 24, blocksPerCellY)
                .putInt(base + 28, 0).putInt(base + 32, originX).putInt(base + 36, originY)
                .putInt(base + 40, originZ).putInt(base + 44, 0);
    }

    static void setOrigin(ByteBuffer target, int chunk, int x, int y, int z) {
        if (chunk < 0 || target == null || target.capacity() < (long) (chunk + 1) * METADATA_BYTES) {
            throw new IllegalArgumentException("invalid interpolation metadata destination");
        }
        int base = chunk * METADATA_BYTES;
        target.putInt(base + ORIGIN_X_WORD * Integer.BYTES, x)
                .putInt(base + ORIGIN_Y_WORD * Integer.BYTES, y)
                .putInt(base + ORIGIN_Z_WORD * Integer.BYTES, z);
    }
}
