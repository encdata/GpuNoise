package dev.gpunoise;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.function.Supplier;

/** Shared ownership table for speculative terrain caches. */
final class GpuTerrainAreaRegistry {
    static final int RADIUS = 2;
    /** Bound retained cell grids and full-block output while chunks wait for fill. */
    private static final int MAX_LIVE_CACHES = (int) Math.max(32L,
            Math.min(512L, Long.getLong("gpunoise.terrainCacheBudget", 128L)));
    private static final int MAX_PREFETCH_TILES = 4096;
    private static final ConcurrentHashMap<Long, GpuChunkDensityCache> CLAIMED = new ConcurrentHashMap<>();
    private static final Semaphore CACHE_SLOTS = new Semaphore(MAX_LIVE_CACHES);
    private static final Object CLAIM_LOCK = new Object();
    private static final LinkedHashMap<Long, Boolean> PREFETCHED_TILES = new LinkedHashMap<>();

    private GpuTerrainAreaRegistry() {}

    static GpuChunkDensityCache claim(int minX, int minZ, Supplier<GpuChunkDensityCache> creator) {
        long key = key(minX >> 4, minZ >> 4);
        GpuChunkDensityCache existing = CLAIMED.get(key);
        if (existing != null) return existing;
        acquireSlot();
        synchronized (CLAIM_LOCK) {
            existing = CLAIMED.get(key);
            if (existing != null) {
                CACHE_SLOTS.release();
                return existing;
            }
            try {
                GpuChunkDensityCache created = creator.get();
                CLAIMED.put(key, created);
                return created;
            } catch (RuntimeException | Error failure) {
                CACHE_SLOTS.release();
                throw failure;
            }
        }
    }

    /** Speculative work never waits for memory; the requested chunk can claim the slot later. */
    static GpuChunkDensityCache tryClaim(int minX, int minZ, Supplier<GpuChunkDensityCache> creator) {
        long key = key(minX >> 4, minZ >> 4);
        GpuChunkDensityCache existing = CLAIMED.get(key);
        if (existing != null) return existing;
        if (!CACHE_SLOTS.tryAcquire()) return null;
        synchronized (CLAIM_LOCK) {
            existing = CLAIMED.get(key);
            if (existing != null) {
                CACHE_SLOTS.release();
                return existing;
            }
            try {
                GpuChunkDensityCache created = creator.get();
                CLAIMED.put(key, created);
                return created;
            } catch (RuntimeException | Error failure) {
                CACHE_SLOTS.release();
                throw failure;
            }
        }
    }

    static void release(GpuChunkDensityCache cache) {
        if (cache == null) return;
        synchronized (CLAIM_LOCK) {
            if (CLAIMED.remove(key(cache.minX() >> 4, cache.minZ() >> 4), cache)) {
                GpuChunkDensityCache.removePrefetched(cache);
                CACHE_SLOTS.release();
            }
        }
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

    static void clear() {
        synchronized (CLAIM_LOCK) {
            int released = CLAIMED.size();
            CLAIMED.clear();
            CACHE_SLOTS.release(released);
        }
        synchronized (GpuTerrainAreaRegistry.class) {
            PREFETCHED_TILES.clear();
        }
        GpuChunkDensityCache.clearPrefetched();
    }

    private static void acquireSlot() {
        try {
            CACHE_SLOTS.acquire();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted waiting for GPU terrain cache capacity", interrupted);
        }
    }

    private static long key(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) ^ (chunkZ & 0xffffffffL);
    }
}
