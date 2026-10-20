package dev.gpunoise;

import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkAccess;

import java.util.ArrayDeque;
import java.util.IdentityHashMap;
import java.util.Map;

/** Short-lived biome IDs prepared for ChunkAccess.fillBiomesFromNoise. */
public final class GpuBiomeRegionResults {
    private static final int MAX_ENTRIES = 128;
    private static final Map<ChunkAccess, GpuBiomeRegionResults> RESULTS = new IdentityHashMap<>();
    private static final ArrayDeque<ChunkAccess> INSERTION_ORDER = new ArrayDeque<>();

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

    static synchronized void publish(ChunkAccess chunk, int minBiomeY, int height,
                                    Holder<Biome>[] values) {
        if (height <= 0 || values.length != 16 * height)
            throw new IllegalArgumentException("invalid prepared biome region");
        RESULTS.remove(chunk);
        INSERTION_ORDER.remove(chunk);
        RESULTS.put(chunk, new GpuBiomeRegionResults(
                chunk.getPos().x * 4, minBiomeY, chunk.getPos().z * 4, height, values));
        INSERTION_ORDER.addLast(chunk);
        while (RESULTS.size() > MAX_ENTRIES) {
            ChunkAccess oldest = INSERTION_ORDER.pollFirst();
            if (oldest == null) break;
            RESULTS.remove(oldest);
        }
    }

    public static synchronized GpuBiomeRegionResults take(ChunkAccess chunk) {
        INSERTION_ORDER.remove(chunk);
        return RESULTS.remove(chunk);
    }

    static synchronized void clear() {
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
}
