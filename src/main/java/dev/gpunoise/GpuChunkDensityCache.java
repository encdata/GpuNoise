package dev.gpunoise;

import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseSettings;

/** GPU cache containing the NoiseChunk cell-boundary grid for one chunk. */
public final class GpuChunkDensityCache {
    private static final ConcurrentHashMap<Long, GpuChunkDensityCache> PREFETCHED = new ConcurrentHashMap<>();
    private final int minX, minZ, minY, cellWidth, cellHeight, cellsY, gridX, gridZ;
    private final CompletionStage<GpuFusedResult> result;
    private volatile float[] readyValues;
    private volatile float[] readyCorners;
    private static final int ROOTS = 1;

    private GpuChunkDensityCache(int minX, int minZ, NoiseSettings settings) {
        this.minX = minX;
        this.minZ = minZ;
        this.minY = settings.minY();
        this.cellWidth = settings.getCellWidth();
        this.cellHeight = settings.getCellHeight();
        this.cellsY = settings.height() / cellHeight;
        int width = 16 / cellWidth + 1;
        int depth = 16 / cellWidth + 1;
        this.gridX = width;
        this.gridZ = depth;
        int samples = width * (cellsY + 1) * depth;
        int[] x = new int[samples], y = new int[samples], z = new int[samples];
        int index = 0;
        for (int localY = 0; localY <= cellsY; localY++) {
            for (int localX = 0; localX < width; localX++) {
                for (int localZ = 0; localZ < depth; localZ++) {
                    x[index] = minX + localX * cellWidth;
                    y[index] = minY + localY * cellHeight;
                    z[index] = minZ + localZ * cellWidth;
                    index++;
                }
            }
        }
        int blocksPerChunk = 16 * settings.height() * 16;
        java.nio.ByteBuffer metadata = GpuInterpolationBatch.metadata(1, width - 1, cellsY, depth - 1,
                cellWidth, samples, blocksPerChunk);
        this.result = GpuTerrainRuntime.submitFused(x, y, z, metadata, 1, blocksPerChunk);
        this.result.whenComplete((values, failure) -> {
            if (failure == null) {
                readyValues = values.blocks();
            }
        });

    }

    public static GpuChunkDensityCache create(int minX, int minZ, NoiseSettings settings) {
        return new GpuChunkDensityCache(minX, minZ, settings);
    }

    public static void prefetch(int minX, int minZ, NoiseSettings settings) {
        PREFETCHED.computeIfAbsent(chunkKey(minX >> 4, minZ >> 4),
                ignored -> new GpuChunkDensityCache(minX, minZ, settings));
    }

    public static GpuChunkDensityCache takePrefetched(int minX, int minZ) {
        return PREFETCHED.remove(chunkKey(minX >> 4, minZ >> 4));
    }

    public static void putPrefetched(int minX, int minZ, GpuChunkDensityCache cache) {
        PREFETCHED.put(chunkKey(minX >> 4, minZ >> 4), cache);
    }

    public CompletionStage<Void> ready() {
        return result.thenApply(ignored -> null);
    }

    /** CPU trilinear interpolation over the same cell grid used by NoiseChunk. */
    public double density(DensityFunction.FunctionContext context) {
        int rx = context.blockX() - minX;
        int ry = context.blockY() - minY;
        int rz = context.blockZ() - minZ;
        if ((rx | ry | rz) < 0 || rx >= 16 || rz >= 16 || ry >= cellsY * cellHeight) return Double.NaN;
        float[] ready = readyValues;
        if (ready == null) return Double.NaN;
        return ready[((ry * 16 + rz) * 16 + rx) * ROOTS];
    }

    public double initialDensity(int x, int y, int z) {
        return rootDensity(x, y, z, 1);
    }
    public double aquiferFloodedness(int x, int y, int z) {
        return rootDensity(x, y, z, 2);
    }

    /** Exact graph-root value at a NoiseChunk cell corner. */
    public double cornerDensity(int x, int y, int z) {
        int rx = x - minX, ry = y - minY, rz = z - minZ;
        float[] corners = readyCorners;
        if (corners == null || rx < 0 || rz < 0 || ry < 0
                || rx % cellWidth != 0 || rz % cellWidth != 0 || ry % cellHeight != 0) return Double.NaN;
        int gx = rx / cellWidth, gy = ry / cellHeight, gz = rz / cellWidth;
        if (gx >= gridX || gz >= gridZ || gy > cellsY) return Double.NaN;
        return corners[((gy * gridX + gx) * gridZ + gz) * ROOTS];
    }

    private double rootDensity(int x, int y, int z, int root) {
        int rx = x - minX, ry = y - minY, rz = z - minZ;
        float[] ready = readyValues;
        if (root != 0 || ready == null || rx < 0 || ry < 0 || rz < 0
                || rx >= 16 || rz >= 16 || ry >= cellsY * cellHeight) return Double.NaN;
        return ready[((ry * 16 + rz) * 16 + rx) * ROOTS + root];
    }

    private static long chunkKey(int x, int z) { return ((long)x << 32) ^ (z & 0xffffffffL); }
}
