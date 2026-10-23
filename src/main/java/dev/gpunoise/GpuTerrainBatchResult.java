package dev.gpunoise;

/** Both graph corner roots and GPU-materialized per-block roots for a batch. */
final class GpuTerrainBatchResult {
    private final GpuTerrainRootsResult corners;
    private final GpuTerrainBlockResult blocks;
    private final int[] surfaceHeights;

    GpuTerrainBatchResult(GpuTerrainRootsResult corners, GpuTerrainBlockResult blocks) {
        this(corners, blocks, null);
    }

    GpuTerrainBatchResult(GpuTerrainRootsResult corners, GpuTerrainBlockResult blocks,
                          int[] surfaceHeights) {
        this.corners = corners;
        this.blocks = blocks;
        this.surfaceHeights = surfaceHeights;
    }

    GpuTerrainBatchResult slice(int cornerOffset, int cornerCount, int blockOffset, int blockCount) {
        if (blocks.isEmpty()) return new GpuTerrainBatchResult(
                corners.slice(cornerOffset, cornerCount), blocks);
        int[] chunkSurfaceHeights = null;
        if (surfaceHeights != null) {
            int blocksPerChunk = blocks.blocksPerChunk();
            if (blockOffset % blocksPerChunk != 0 || blockCount % blocksPerChunk != 0) {
                throw new IllegalArgumentException("terrain slice is not aligned to chunk boundaries");
            }
            int firstChunk = blockOffset / blocksPerChunk;
            int chunkCount = blockCount / blocksPerChunk;
            chunkSurfaceHeights = java.util.Arrays.copyOfRange(surfaceHeights,
                    firstChunk * 16, (firstChunk + chunkCount) * 16);
        }
        return new GpuTerrainBatchResult(corners.slice(cornerOffset, cornerCount),
                blocks.slice(blockOffset, blockCount), chunkSurfaceHeights);
    }

    GpuTerrainRootsResult corners() { return corners; }
    GpuTerrainBlockResult blocks() { return blocks; }
    int[] surfaceHeights() { return surfaceHeights; }

    int roots() { return corners.roots(); }
}
