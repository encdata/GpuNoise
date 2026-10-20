package dev.gpunoise;

/** Per-block terrain roots materialized by the GPU interpolation stage. */
final class GpuTerrainBlockResult {
    private final float[] values;
    private final int offset;
    private final int blocks;
    private final int roots;
    private final int[] rootIndexes;
    private final int[] outputIndexesByRoot;
    private final int blocksX, blocksY, blocksZ;

    GpuTerrainBlockResult(float[] values, int offset, int blocks, int roots,
                          int blocksX, int blocksY, int blocksZ) {
        this(values, offset, blocks, sequentialRoots(roots), blocksX, blocksY, blocksZ);
    }

    GpuTerrainBlockResult(float[] values, int offset, int blocks, int[] rootIndexes,
                          int blocksX, int blocksY, int blocksZ) {
        int roots = rootIndexes == null ? 0 : rootIndexes.length;
        if (values == null || offset < 0 || blocks < 0 || roots <= 0
                || offset > values.length - blocks * roots)
            throw new IllegalArgumentException("invalid GPU terrain block result");
        this.values = values;
        this.offset = offset;
        this.blocks = blocks;
        this.roots = roots;
        this.rootIndexes = rootIndexes.clone();
        this.outputIndexesByRoot = new int[GpuTerrainRoots.COUNT];
        java.util.Arrays.fill(this.outputIndexesByRoot, -1);
        for (int output = 0; output < this.rootIndexes.length; output++) {
            int root = this.rootIndexes[output];
            if (root < 0 || root >= this.outputIndexesByRoot.length || this.outputIndexesByRoot[root] >= 0)
                throw new IllegalArgumentException("invalid or duplicate terrain block root " + root);
            this.outputIndexesByRoot[root] = output;
        }
        this.blocksX = blocksX;
        this.blocksY = blocksY;
        this.blocksZ = blocksZ;
    }

    GpuTerrainBlockResult slice(int blockOffset, int blockCount) {
        if (blockOffset < 0 || blockCount < 0 || blockOffset + blockCount > blocks)
            throw new IndexOutOfBoundsException();
        return new GpuTerrainBlockResult(values, offset + blockOffset * roots, blockCount,
                rootIndexes, blocksX, blocksY, blocksZ);
    }

    boolean contains(int x, int y, int z) {
        return x >= 0 && y >= 0 && z >= 0 && x < blocksX && y < blocksY && z < blocksZ;
    }

    boolean isEmpty() {
        return blocks == 0;
    }

    int rootCount() {
        return roots;
    }

    int rootIndexAt(int outputIndex) {
        return rootIndexes[outputIndex];
    }

    int outputIndexForRoot(int root) {
        return root < 0 || root >= outputIndexesByRoot.length ? -1 : outputIndexesByRoot[root];
    }

    RootView rootView(int root) {
        int outputRoot = outputIndexForRoot(root);
        return outputRoot < 0 ? null : new RootView(values, offset, roots, outputRoot,
                blocksX, blocksY, blocksZ);
    }

    float valueAt(int x, int y, int z, int root) {
        if (!contains(x, y, z) || outputIndexForRoot(root) < 0) throw new IndexOutOfBoundsException();
        return valueAtUnchecked(x, y, z, root);
    }

    float valueAtUnchecked(int x, int y, int z, int root) {
        int outputRoot = outputIndexForRoot(root);
        if (outputRoot < 0) return Float.NaN;
        int index = (y * blocksZ + z) * blocksX + x;
        return values[offset + index * roots + outputRoot];
    }

    float finalValueAtUnchecked(int x, int y, int z) {
        int outputRoot = outputIndexForRoot(GpuTerrainRoots.FINAL_DENSITY);
        if (outputRoot < 0) return Float.NaN;
        int index = (y * blocksZ + z) * blocksX + x;
        return values[offset + index * roots + outputRoot];
    }

    private static int[] sequentialRoots(int count) {
        int[] roots = new int[count];
        for (int i = 0; i < count; i++) roots[i] = i;
        return roots;
    }

    /** Prebound read access for hot loops that scan a complete root volume. */
    static final class RootView {
        private final float[] values;
        private final int offset, roots, outputRoot, blocksX, blocksY, blocksZ;

        private RootView(float[] values, int offset, int roots, int outputRoot,
                         int blocksX, int blocksY, int blocksZ) {
            this.values = values;
            this.offset = offset;
            this.roots = roots;
            this.outputRoot = outputRoot;
            this.blocksX = blocksX;
            this.blocksY = blocksY;
            this.blocksZ = blocksZ;
        }

        boolean contains(int x, int y, int z) {
            return x >= 0 && y >= 0 && z >= 0
                    && x < blocksX && y < blocksY && z < blocksZ;
        }

        float valueAtUnchecked(int x, int y, int z) {
            int block = (y * blocksZ + z) * blocksX + x;
            return values[offset + block * roots + outputRoot];
        }
    }
}
