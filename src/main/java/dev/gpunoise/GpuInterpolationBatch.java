package dev.gpunoise;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Metadata for batched cell-grid to block-density interpolation. */
final class GpuInterpolationBatch {
    static final int METADATA_BYTES = 48;

    private GpuInterpolationBatch() {}

    static ByteBuffer metadata(int chunkCount, int cellsX, int cellsY, int cellsZ,
                               int blocksPerCellX, int blocksPerCellY,
                               int cornersPerChunk, int blocksPerChunk) {
        ByteBuffer result = ByteBuffer.allocateDirect(chunkCount * METADATA_BYTES)
                .order(ByteOrder.nativeOrder());
        for (int chunk = 0; chunk < chunkCount; chunk++) {
            result.putInt(chunk * cornersPerChunk);
            result.putInt(chunk * blocksPerChunk);
            result.putInt(cellsX).putInt(cellsY).putInt(cellsZ);
            result.putInt(blocksPerCellX).putInt(blocksPerCellY);
            result.putInt(0).putInt(0).putInt(0).putInt(0);
        }
        return (ByteBuffer) result.flip();
    }

    static ByteBuffer metadata(int chunkCount, int cellsX, int cellsY, int cellsZ,
                               int blocksPerCellX, int blocksPerCellY,
                               int cornersPerChunk, int blocksPerChunk,
                               int minX, int minY, int minZ) {
        ByteBuffer result = metadata(chunkCount, cellsX, cellsY, cellsZ,
                blocksPerCellX, blocksPerCellY, cornersPerChunk, blocksPerChunk);
        for (int chunk = 0; chunk < chunkCount; chunk++) {
            int originOffset = chunk * METADATA_BYTES + 32;
            result.putInt(originOffset, minX).putInt(originOffset + 4, minY)
                    .putInt(originOffset + 8, minZ);
        }
        return result;
    }
}
