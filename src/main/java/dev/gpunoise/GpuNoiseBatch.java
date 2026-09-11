package dev.gpunoise;

import java.util.Arrays;

/** Immutable input batch for one GPU submission. Terrain integration will fill its sample arrays. */
final class GpuNoiseBatch {
    static final int MAX_CHUNKS = 100;

    private final long worldSeed;
    private final int[] chunkX = new int[MAX_CHUNKS];
    private final int[] chunkZ = new int[MAX_CHUNKS];
    private int size;

    GpuNoiseBatch(long worldSeed) {
        this.worldSeed = worldSeed;
    }

    boolean add(int x, int z) {
        if (size == MAX_CHUNKS) return false;
        chunkX[size] = x;
        chunkZ[size] = z;
        size++;
        return true;
    }

    long worldSeed() { return worldSeed; }
    int size() { return size; }
    int chunkX(int index) { return chunkX[index]; }
    int chunkZ(int index) { return chunkZ[index]; }

    int[] copyChunkX() { return Arrays.copyOf(chunkX, size); }
    int[] copyChunkZ() { return Arrays.copyOf(chunkZ, size); }
}
