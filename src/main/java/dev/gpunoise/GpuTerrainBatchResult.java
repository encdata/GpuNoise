package dev.gpunoise;

/** Both graph corner roots and GPU-materialized per-block roots for a batch. */
final class GpuTerrainBatchResult {
    private final GpuTerrainRootsResult corners;
    private final GpuTerrainBlockResult blocks;

    GpuTerrainBatchResult(GpuTerrainRootsResult corners, GpuTerrainBlockResult blocks) {
        this.corners = corners;
        this.blocks = blocks;
    }

    GpuTerrainBatchResult slice(int cornerOffset, int cornerCount, int blockOffset, int blockCount) {
        if (blocks.isEmpty()) return new GpuTerrainBatchResult(
                corners.slice(cornerOffset, cornerCount), blocks);
        return new GpuTerrainBatchResult(corners.slice(cornerOffset, cornerCount),
                blocks.slice(blockOffset, blockCount));
    }

    GpuTerrainRootsResult corners() { return corners; }
    GpuTerrainBlockResult blocks() { return blocks; }

    int roots() { return corners.roots(); }
}
