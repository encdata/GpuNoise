package dev.gpunoise;

/** Per-block terrain roots materialized by the GPU interpolation stage. */
final class GpuTerrainBlockResult {
    private final float[] values;
    private final int offset;
    private final int blocks;
    private final int roots;
    private final int blocksX, blocksY, blocksZ;

    GpuTerrainBlockResult(float[] values, int offset, int blocks, int roots,
                          int blocksX, int blocksY, int blocksZ) {
        if (values == null || offset < 0 || blocks < 0 || roots <= 0
                || offset > values.length - blocks * roots)
            throw new IllegalArgumentException("invalid GPU terrain block result");
        this.values = values;
        this.offset = offset;
        this.blocks = blocks;
        this.roots = roots;
        this.blocksX = blocksX;
        this.blocksY = blocksY;
        this.blocksZ = blocksZ;
    }

    GpuTerrainBlockResult slice(int blockOffset, int blockCount) {
        if (blockOffset < 0 || blockCount < 0 || blockOffset + blockCount > blocks)
            throw new IndexOutOfBoundsException();
        return new GpuTerrainBlockResult(values, offset + blockOffset * roots, blockCount,
                roots, blocksX, blocksY, blocksZ);
    }

    boolean contains(int x, int y, int z) {
        return x >= 0 && y >= 0 && z >= 0 && x < blocksX && y < blocksY && z < blocksZ;
    }

    boolean isEmpty() {
        return blocks == 0;
    }

    float valueAt(int x, int y, int z, int root) {
        if (!contains(x, y, z) || root < 0 || root >= roots) throw new IndexOutOfBoundsException();
        return valueAtUnchecked(x, y, z, root);
    }

    float valueAtUnchecked(int x, int y, int z, int root) {
        int index = (y * blocksZ + z) * blocksX + x;
        return values[offset + index * roots + root];
    }

    float finalValueAtUnchecked(int x, int y, int z) {
        int index = (y * blocksZ + z) * blocksX + x;
        return values[offset + index * roots + GpuTerrainRoots.FINAL_DENSITY];
    }
}
