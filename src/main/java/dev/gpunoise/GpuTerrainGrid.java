package dev.gpunoise;

/** Regular cell-lattice description uploaded once per terrain chunk. */
record GpuTerrainGrid(int minX, int minY, int minZ, int gridX, int gridY, int gridZ,
                      int cellWidth, int cellHeight) {
    int sampleCount() {
        return gridX * gridY * gridZ;
    }
}
