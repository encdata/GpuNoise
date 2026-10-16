package dev.gpunoise;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.function.Supplier;

/** Shared ownership table for speculative terrain caches. */
final class GpuTerrainAreaRegistry {
    static final int RADIUS = 2;
    private static final int MAX_ENTRIES = 4096;
    private static final int MAX_PREFETCH_TILES = 4096;
    private static final ConcurrentHashMap<Long, GpuChunkDensityCache> CLAIMED = new ConcurrentHashMap<>();
    private static final LinkedHashMap<Long, Boolean> PREFETCHED_TILES = new LinkedHashMap<>();

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

    static synchronized boolean claimPrefetchTile(int tileX, int tileZ) {
        long tileKey = key(tileX, tileZ);
        if (PREFETCHED_TILES.containsKey(tileKey)) return false;
        if (PREFETCHED_TILES.size() >= MAX_PREFETCH_TILES) {
            Iterator<Long> oldest = PREFETCHED_TILES.keySet().iterator();
            if (oldest.hasNext()) {
                oldest.next();
                oldest.remove();
            }
        }
        PREFETCHED_TILES.put(tileKey, Boolean.TRUE);
        return true;
    }

    static synchronized void clear() {
        CLAIMED.clear();
        PREFETCHED_TILES.clear();
    }

    private static long key(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) ^ (chunkZ & 0xffffffffL);
    }
}
