package dev.gpunoise;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Metadata for batched cell-grid to block-density interpolation. */
final class GpuInterpolationBatch {
    static final int METADATA_INTS = 12;
    static final int METADATA_BYTES = METADATA_INTS * Integer.BYTES;
    private static final int CORNER_OFFSET_WORD = 0;
    private static final int BLOCK_OFFSET_WORD = 1;
    private static final int CELLS_X_WORD = 2;
    private static final int CELLS_Y_WORD = 3;
    private static final int CELLS_Z_WORD = 4;
    private static final int BLOCKS_PER_CELL_X_WORD = 5;
    private static final int BLOCKS_PER_CELL_Y_WORD = 6;
    private static final int ORIGIN_X_WORD = 8;
    private static final int ORIGIN_Y_WORD = 9;
    private static final int ORIGIN_Z_WORD = 10;
    private static final int WORDS_PER_RECORD = METADATA_INTS;

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
        validateDestination(target, chunk);
        int baseWord = Math.multiplyExact(chunk, WORDS_PER_RECORD);
        putWord(target, baseWord + CORNER_OFFSET_WORD, cornerOffset);
        putWord(target, baseWord + BLOCK_OFFSET_WORD, blockOffset);
        putWord(target, baseWord + CELLS_X_WORD, cellsX);
        putWord(target, baseWord + CELLS_Y_WORD, cellsY);
        putWord(target, baseWord + CELLS_Z_WORD, cellsZ);
        putWord(target, baseWord + BLOCKS_PER_CELL_X_WORD, blocksPerCellX);
        putWord(target, baseWord + BLOCKS_PER_CELL_Y_WORD, blocksPerCellY);
        putWord(target, baseWord + 7, 0);
        putWord(target, baseWord + ORIGIN_X_WORD, originX);
        putWord(target, baseWord + ORIGIN_Y_WORD, originY);
        putWord(target, baseWord + ORIGIN_Z_WORD, originZ);
        putWord(target, baseWord + 11, 0);
    }

    static void setOrigin(ByteBuffer target, int chunk, int x, int y, int z) {
        validateDestination(target, chunk);
        int baseWord = Math.multiplyExact(chunk, WORDS_PER_RECORD);
        putWord(target, baseWord + ORIGIN_X_WORD, x);
        putWord(target, baseWord + ORIGIN_Y_WORD, y);
        putWord(target, baseWord + ORIGIN_Z_WORD, z);
    }

    private static void putWord(ByteBuffer target, int word, int value) {
        target.putInt(Math.multiplyExact(word, Integer.BYTES), value);
    }

    private static void validateDestination(ByteBuffer target, int chunk) {
        if (chunk < 0 || target == null
                || target.limit() < (long) (chunk + 1) * METADATA_BYTES) {
            throw new IllegalArgumentException("invalid interpolation metadata destination");
        }
    }
}
