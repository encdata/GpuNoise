package dev.gpunoise;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/** Compact OCL-shaped preliminary-surface region assembled from grid-only GPU batches. */
final class GpuTerrainSurfaceRegion {
    static final int HALO_CHUNKS = 4;
    static final int SURFACE_COLUMNS_PER_CHUNK = 16;
    static final int GRID_METADATA_BYTES = 8 * Integer.BYTES;
    static final int SURFACE_METADATA_BYTES = GpuInterpolationBatch.METADATA_BYTES;

    private final int requestedStartChunkX;
    private final int requestedStartChunkZ;
    private final int requestedChunksX;
    private final int requestedChunksZ;
    private final int gridStartChunkX;
    private final int gridStartChunkZ;
    private final int gridChunksX;
    private final int gridChunksZ;
    private final int surfaceWidth;
    private final int surfaceDepth;
    private final int minY;
    private final int height;
    private final int cellWidth;
    private final int cellHeight;
    private final int cellsY;
    private final int samplesPerChunk;

    GpuTerrainSurfaceRegion(int requestedStartChunkX, int requestedStartChunkZ,
                            int requestedChunksX, int requestedChunksZ,
                            int minY, int height, int cellWidth, int cellHeight) {
        if (requestedChunksX <= 0 || requestedChunksZ <= 0 || height <= 0
                || cellWidth != 4 || cellHeight <= 0 || height % cellHeight != 0
                || 16 % cellWidth != 0) {
            throw new IllegalArgumentException("invalid OCL-compatible GPU surface region dimensions");
        }
        this.requestedStartChunkX = requestedStartChunkX;
        this.requestedStartChunkZ = requestedStartChunkZ;
        this.requestedChunksX = requestedChunksX;
        this.requestedChunksZ = requestedChunksZ;
        this.gridStartChunkX = requestedStartChunkX - HALO_CHUNKS;
        this.gridStartChunkZ = requestedStartChunkZ - HALO_CHUNKS;
        this.gridChunksX = Math.addExact(requestedChunksX, HALO_CHUNKS * 2);
        this.gridChunksZ = Math.addExact(requestedChunksZ, HALO_CHUNKS * 2);
        this.surfaceWidth = Math.addExact(36, Math.multiplyExact(4, requestedChunksX - 1));
        this.surfaceDepth = Math.addExact(36, Math.multiplyExact(4, requestedChunksZ - 1));
        this.minY = minY;
        this.height = height;
        this.cellWidth = cellWidth;
        this.cellHeight = cellHeight;
        this.cellsY = height / cellHeight;
        this.samplesPerChunk = Math.multiplyExact(Math.multiplyExact(5, cellsY + 1), 5);
        if (surfaceWidth != gridChunksX * 4 || surfaceDepth != gridChunksZ * 4) {
            throw new IllegalStateException("surface halo and chunk grid dimensions disagree");
        }
    }

    int requestedStartChunkX() { return requestedStartChunkX; }
    int requestedStartChunkZ() { return requestedStartChunkZ; }
    int gridStartChunkX() { return gridStartChunkX; }
    int gridStartChunkZ() { return gridStartChunkZ; }
    int gridChunksX() { return gridChunksX; }
    int gridChunksZ() { return gridChunksZ; }
    int chunkCount() { return Math.multiplyExact(gridChunksX, gridChunksZ); }
    int samplesPerChunk() { return samplesPerChunk; }
    int surfaceWidth() { return surfaceWidth; }
    int surfaceDepth() { return surfaceDepth; }
    int surfaceValueCount() { return Math.multiplyExact(surfaceWidth, surfaceDepth); }

    /** The engine accepts multiple bounded dispatches for tall or modded worlds. */
    List<ChunkRange> ranges(int maxSamplesPerDispatch, int maxSurfaceColumnsPerDispatch) {
        if (maxSamplesPerDispatch < samplesPerChunk
                || maxSurfaceColumnsPerDispatch < SURFACE_COLUMNS_PER_CHUNK) {
            throw new IllegalArgumentException("GPU surface dispatch capacity cannot hold one chunk grid");
        }
        int perDispatch = Math.min(maxSamplesPerDispatch / samplesPerChunk,
                maxSurfaceColumnsPerDispatch / SURFACE_COLUMNS_PER_CHUNK);
        int total = chunkCount();
        List<ChunkRange> ranges = new ArrayList<>((total + perDispatch - 1) / perDispatch);
        for (int first = 0; first < total; first += perDispatch) {
            ranges.add(new ChunkRange(first, Math.min(perDispatch, total - first)));
        }
        return List.copyOf(ranges);
    }

    ByteBuffer gridMetadata(ChunkRange range) {
        validateRange(range);
        ByteBuffer metadata = ByteBuffer.allocateDirect(Math.multiplyExact(range.chunkCount(), GRID_METADATA_BYTES))
                .order(ByteOrder.nativeOrder());
        for (int index = range.firstChunk(); index < range.endChunkExclusive(); index++) {
            int chunkX = gridStartChunkX + index % gridChunksX;
            int chunkZ = gridStartChunkZ + index / gridChunksX;
            metadata.putInt(chunkX * 16).putInt(minY).putInt(chunkZ * 16)
                    .putInt(5).putInt(cellsY + 1).putInt(5).putInt(cellWidth).putInt(cellHeight);
        }
        return metadata.flip();
    }

    /** Metadata word positions match terrain_surface_height.comp's per-chunk ABI. */
    ByteBuffer surfaceMetadata(ChunkRange range) {
        validateRange(range);
        ByteBuffer metadata = ByteBuffer.allocateDirect(Math.multiplyExact(range.chunkCount(), SURFACE_METADATA_BYTES))
                .order(ByteOrder.nativeOrder());
        for (int local = 0; local < range.chunkCount(); local++) {
            int global = range.firstChunk() + local;
            GpuInterpolationBatch.write(metadata, local,
                    Math.multiplyExact(local, samplesPerChunk), 0,
                    4, cellsY, 4, cellWidth, cellHeight,
                    (gridStartChunkX + global % gridChunksX) * 16, minY,
                    (gridStartChunkZ + global / gridChunksX) * 16,
                    1, 0);
        }
        metadata.position(0).limit(metadata.capacity());
        return metadata;
    }

    /** Combines chunk-major shader output into OCL's quart X-major region layout. */
    void stitch(ChunkRange range, int[] chunkMajorHeights, int[] regionHeights) {
        validateRange(range);
        if (chunkMajorHeights == null || chunkMajorHeights.length != range.chunkCount() * SURFACE_COLUMNS_PER_CHUNK
                || regionHeights == null || regionHeights.length != surfaceValueCount()) {
            throw new IllegalArgumentException("invalid GPU surface region stitch buffers");
        }
        for (int localChunk = 0; localChunk < range.chunkCount(); localChunk++) {
            int globalChunk = range.firstChunk() + localChunk;
            int chunkX = globalChunk % gridChunksX;
            int chunkZ = globalChunk / gridChunksX;
            int regionX = chunkX * 4;
            int regionZ = chunkZ * 4;
            for (int quartX = 0; quartX < 4; quartX++) {
                for (int quartZ = 0; quartZ < 4; quartZ++) {
                    int source = localChunk * SURFACE_COLUMNS_PER_CHUNK + quartZ * 4 + quartX;
                    int destination = (regionX + quartX) * surfaceDepth + regionZ + quartZ;
                    regionHeights[destination] = chunkMajorHeights[source];
                }
            }
        }
    }

    private void validateRange(ChunkRange range) {
        if (range == null || range.firstChunk() < 0 || range.chunkCount() <= 0
                || range.endChunkExclusive() > chunkCount()) {
            throw new IllegalArgumentException("invalid GPU surface chunk range");
        }
    }

    record ChunkRange(int firstChunk, int chunkCount) {
        int endChunkExclusive() { return Math.addExact(firstChunk, chunkCount); }
    }
}
