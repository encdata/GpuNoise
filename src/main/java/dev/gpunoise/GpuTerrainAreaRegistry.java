package dev.gpunoise;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/** Shared ownership table for speculative terrain caches. */
final class GpuTerrainAreaRegistry {
    static final int RADIUS = 2;
    private static final int MAX_ENTRIES = 4096;
    private static final ConcurrentHashMap<Long, GpuChunkDensityCache> CLAIMED = new ConcurrentHashMap<>();

    private GpuTerrainAreaRegistry() {}

    static GpuChunkDensityCache claim(int minX, int minZ, Supplier<GpuChunkDensityCache> creator) {
        long key = key(minX >> 4, minZ >> 4);
        GpuChunkDensityCache existing = CLAIMED.get(key);
        if (existing != null) return existing;
        if (CLAIMED.size() >= MAX_ENTRIES) return creator.get();
        return CLAIMED.computeIfAbsent(key, ignored -> creator.get());
    }

    static void release(GpuChunkDensityCache cache) {
        if (cache != null) CLAIMED.remove(key(cache.minX() >> 4, cache.minZ() >> 4), cache);
    }

    static void clear() {
        CLAIMED.clear();
    }

    private static long key(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) ^ (chunkZ & 0xffffffffL);
    }
}
