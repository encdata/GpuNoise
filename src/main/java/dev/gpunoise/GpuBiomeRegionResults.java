package dev.gpunoise;

import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/** Short-lived biome IDs prepared for ChunkAccess.fillBiomesFromNoise. */
public final class GpuBiomeRegionResults {
    private static final int MAX_ENTRIES = 128;
    private static final ConcurrentHashMap<Long, GpuBiomeRegionResults> RESULTS = new ConcurrentHashMap<>();
    private static final ConcurrentLinkedQueue<Long> INSERTION_ORDER = new ConcurrentLinkedQueue<>();

    private final int minBiomeX;
    private final int minBiomeY;
    private final int minBiomeZ;
    private final int height;
    private final Holder<Biome>[] biomes;

    private GpuBiomeRegionResults(int minBiomeX, int minBiomeY, int minBiomeZ, int height,
                                  Holder<Biome>[] biomes) {
        this.minBiomeX = minBiomeX;
        this.minBiomeY = minBiomeY;
        this.minBiomeZ = minBiomeZ;
        this.height = height;
        this.biomes = biomes;
    }

    static void publish(int chunkX, int chunkZ, int minBiomeY, int height, Holder<Biome>[] values) {
        if (height <= 0 || values.length != 16 * height)
            throw new IllegalArgumentException("invalid prepared biome region");
        long key = key(chunkX, chunkZ);
        RESULTS.put(key, new GpuBiomeRegionResults(chunkX * 4, minBiomeY, chunkZ * 4, height, values));
        INSERTION_ORDER.add(key);
        while (RESULTS.size() > MAX_ENTRIES) {
            Long oldest = INSERTION_ORDER.poll();
            if (oldest == null) break;
            RESULTS.remove(oldest);
        }
    }

    public static GpuBiomeRegionResults take(int chunkX, int chunkZ) {
        return RESULTS.remove(key(chunkX, chunkZ));
    }

    static void clear() {
        RESULTS.clear();
        INSERTION_ORDER.clear();
    }

    public Holder<Biome> biomeAt(int biomeX, int biomeY, int biomeZ) {
        int x = biomeX - minBiomeX;
        int y = biomeY - minBiomeY;
        int z = biomeZ - minBiomeZ;
        if (x < 0 || x >= 4 || y < 0 || y >= height || z < 0 || z >= 4) return null;
        return biomes[(y * 4 + z) * 4 + x];
    }

    private static long key(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) ^ (chunkZ & 0xffff_ffffL);
    }
}
