package dev.gpunoise;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Metadata for batched cell-grid to block-density interpolation. */
final class GpuInterpolationBatch {
    static final int METADATA_BYTES = 32;

    private GpuInterpolationBatch() {}

    static ByteBuffer metadata(int chunkCount, int cellsX, int cellsY, int cellsZ,
                               int blocksPerCell, int cornersPerChunk, int blocksPerChunk) {
        ByteBuffer result = ByteBuffer.allocateDirect(chunkCount * METADATA_BYTES)
                .order(ByteOrder.nativeOrder());
        for (int chunk = 0; chunk < chunkCount; chunk++) {
            result.putInt(chunk * cornersPerChunk);
            result.putInt(chunk * blocksPerChunk);
            result.putInt(cellsX).putInt(cellsY).putInt(cellsZ);
            result.putInt(blocksPerCell);
            result.putInt(0).putInt(0);
        }
        return (ByteBuffer) result.flip();
    }
}
