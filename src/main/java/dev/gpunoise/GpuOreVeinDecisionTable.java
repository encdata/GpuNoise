package dev.gpunoise;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import net.minecraft.world.level.levelgen.PositionalRandomFactory;

/** Chunk-local result slab for Vulkan's per-block ore-vein decisions. */
public final class GpuOreVeinDecisionTable {
    public static final int MISSING = Integer.MIN_VALUE;
    private final int minX, minY, minZ, height;
    private final byte[] stateCodes;

    private GpuOreVeinDecisionTable(int minX, int minY, int minZ, int height, byte[] stateCodes) {
        int expected = Math.multiplyExact(height, 256);
        if (height <= 0 || stateCodes == null || stateCodes.length != expected)
            throw new IllegalArgumentException("invalid ore-vein result slab");
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.height = height;
        this.stateCodes = stateCodes;
    }

    static CompletionStage<GpuOreVeinDecisionTable> build(GpuChunkDensityCache cache,
                                                           PositionalRandomFactory randomFactory) {
        if (cache == null || randomFactory == null || !cache.hasOreVeinBlockRoots())
            return CompletableFuture.completedFuture(null);
        int minX = cache.minX(), minY = cache.minY(), minZ = cache.minZ(), height = cache.height();
        final int rows;
        try {
            rows = Math.multiplyExact(height, 256);
        } catch (ArithmeticException overflow) {
            GpuNoise.LOGGER.warn("Ore-vein Vulkan output exceeds addressable chunk size; OreVeinifier stays on Java");
            return CompletableFuture.completedFuture(null);
        }
        byte[] states = new byte[rows];
        // OreVeinifier's copper and iron veins are both hard-bounded to
        // [-60, 50]. Outside that union the vanilla result is always null,
        // regardless of density roots or positional randomness. Keep those
        // rows as NO_VEIN locally instead of uploading and reading them back.
        int firstY = Math.max(minY, -60);
        int endY = Math.min(Math.addExact(minY, height), 51);
        if (firstY >= endY) {
            return CompletableFuture.completedFuture(
                    new GpuOreVeinDecisionTable(minX, minY, minZ, height, states));
        }
        int firstRow = Math.multiplyExact(firstY - minY, 256);
        int activeRows = Math.multiplyExact(endY - firstY, 256);
        CompletableFuture<byte[]> batches = CompletableFuture.completedFuture(states);
        int maxRows = PersistentVulkanNormalNoiseEngine.CAPACITY;
        for (int offset = 0; offset < activeRows; offset += maxRows) {
            int batchOffset = firstRow + offset;
            int count = Math.min(maxRows, activeRows - offset);
            batches = batches.thenCompose(output -> CompletableFuture.supplyAsync(
                            () -> packRows(cache, randomFactory, minX, minY, minZ, batchOffset, count),
                            net.minecraft.Util.backgroundExecutor())
                    .thenCompose(records -> GpuTerrainRuntime.submitOreVeinDecisions(records, count))
                    .thenApply(batchStates -> {
                        if (batchStates == null || batchStates.length != count)
                            throw new IllegalStateException("Vulkan returned an invalid ore-vein result batch");
                        System.arraycopy(batchStates, 0, output, batchOffset, count);
                        return output;
                    }));
        }
        return batches.handle((completed, failure) -> {
            if (failure != null || completed == null) {
                GpuNoise.LOGGER.warn("Vulkan ore-vein decision batch failed; OreVeinifier stays on Java", failure);
                return null;
            }
            GpuNoise.LOGGER.info("GPU ore-vein decision preparation completed: {} of {} blocks dispatched to Vulkan",
                    activeRows, rows);
            return new GpuOreVeinDecisionTable(minX, minY, minZ, height, completed);
        });
    }

    private static java.nio.ByteBuffer packRows(GpuChunkDensityCache cache,
                                                 PositionalRandomFactory randomFactory,
                                                 int minX, int minY, int minZ,
                                                 int firstRow, int count) {
        java.nio.ByteBuffer records = GpuOreVeinDecisions.allocateBatch(count);
        for (int row = firstRow; row < firstRow + count; row++) {
            int localY = row >>> 8;
            int localZ = (row >>> 4) & 15;
            int localX = row & 15;
            int x = minX + localX, y = minY + localY, z = minZ + localZ;
            GpuOreVeinDecisions.put(records,
                    (float) cache.blockRootValue(GpuTerrainRoots.VEIN_TOGGLE, x, y, z),
                    (float) cache.blockRootValue(GpuTerrainRoots.VEIN_RIDGED, x, y, z),
                    (float) cache.blockRootValue(GpuTerrainRoots.VEIN_GAP, x, y, z),
                    x, y, z, randomFactory);
        }
        return records.flip();
    }

    public int stateIdAt(int x, int y, int z) {
        int localX = x - minX, localY = y - minY, localZ = z - minZ;
        if (localX < 0 || localX >= 16 || localY < 0 || localY >= height
                || localZ < 0 || localZ >= 16) return MISSING;
        int code = Byte.toUnsignedInt(stateCodes[localY * 256 + localZ * 16 + localX]);
        return GpuOreVeinDecisions.decodeCompactCode(code);
    }
}
