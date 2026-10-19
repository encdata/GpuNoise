package dev.gpunoise;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.function.Supplier;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/** Shared ownership table for speculative terrain caches. */
final class GpuTerrainAreaRegistry {
    static final int RADIUS = 2;
    /** Bound retained cell grids and full-block output while chunks wait for fill. */
    private static final int MAX_LIVE_CACHES = (int) Math.max(32L,
            Math.min(512L, Long.getLong("gpunoise.terrainCacheBudget", 32L)));
    /** Bound CPU-heavy strict dependency construction independently of retained GPU caches. */
    private static final int MAX_CONCURRENT_CACHE_BUILDS = (int) Math.max(1L,
            Math.min(8L, Long.getLong("gpunoise.terrainCacheBuildConcurrency",
                    Math.max(1L, Math.min(4L, Runtime.getRuntime().availableProcessors() / 2L)))));
    private static final int MAX_PREFETCH_TILES = 4096;
    private static final ConcurrentHashMap<Long, GpuChunkDensityCache> CLAIMED = new ConcurrentHashMap<>();
    private static final Semaphore CACHE_SLOTS = new Semaphore(MAX_LIVE_CACHES);
    private static final Semaphore CACHE_BUILD_SLOTS = new Semaphore(MAX_CONCURRENT_CACHE_BUILDS, true);
    // Claims for different chunks can build and submit concurrently. The
    // lifecycle lock only prevents shutdown clearing the registry mid-claim;
    // its read side permits independent cache creation in parallel.
    private static final ReentrantReadWriteLock CACHE_LIFECYCLE = new ReentrantReadWriteLock();
    private static final LinkedHashMap<Long, Boolean> PREFETCHED_TILES = new LinkedHashMap<>();

    private GpuTerrainAreaRegistry() {}

    static int maxConcurrentCacheBuilds() { return MAX_CONCURRENT_CACHE_BUILDS; }

    /** Returns an already-claimed cache that covers a surface column and graph. */
    static GpuChunkDensityCache findSurfaceCache(int blockX, int blockZ, Object graphIdentity) {
        if (graphIdentity == null) return null;
        int chunkMinX = Math.floorDiv(blockX, 16) * 16;
        int chunkMinZ = Math.floorDiv(blockZ, 16) * 16;
        int[] candidateMinX = Math.floorMod(blockX, 16) == 0
                ? new int[]{chunkMinX, chunkMinX - 16} : new int[]{chunkMinX};
        int[] candidateMinZ = Math.floorMod(blockZ, 16) == 0
                ? new int[]{chunkMinZ, chunkMinZ - 16} : new int[]{chunkMinZ};
        Lock lifecycle = CACHE_LIFECYCLE.readLock();
        lifecycle.lock();
        try {
            for (int minX : candidateMinX) {
                for (int minZ : candidateMinZ) {
                    GpuChunkDensityCache cache = CLAIMED.get(key(minX >> 4, minZ >> 4));
                    if (cache != null && cache.terrainGraphIdentity() == graphIdentity
                            && cache.containsSurfaceColumn(blockX, blockZ)) {
                        return cache;
                    }
                }
            }
            return null;
        } finally {
            lifecycle.unlock();
        }
    }

    static GpuChunkDensityCache claim(int minX, int minZ, Supplier<GpuChunkDensityCache> creator) {
        long key = key(minX >> 4, minZ >> 4);
        Lock lifecycle = CACHE_LIFECYCLE.readLock();
        lifecycle.lock();
        try {
            GpuChunkDensityCache existing = CLAIMED.get(key);
            if (existing != null) return existing;
            acquireSlot();
            AtomicBoolean created = new AtomicBoolean();
            try {
                GpuChunkDensityCache result = CLAIMED.computeIfAbsent(key, ignored -> {
                    acquireBuildSlot();
                    created.set(true);
                    try {
                        return creator.get();
                    } finally {
                        CACHE_BUILD_SLOTS.release();
                    }
                });
                if (result == null) throw new IllegalStateException("terrain cache creator returned null");
                if (!created.get()) CACHE_SLOTS.release();
                return result;
            } catch (RuntimeException | Error failure) {
                CACHE_SLOTS.release();
                throw failure;
            }
        } finally {
            lifecycle.unlock();
        }
    }

    /** Speculative work never waits for memory; the requested chunk can claim the slot later. */
    static GpuChunkDensityCache tryClaim(int minX, int minZ, Supplier<GpuChunkDensityCache> creator) {
        long key = key(minX >> 4, minZ >> 4);
        Lock lifecycle = CACHE_LIFECYCLE.readLock();
        lifecycle.lock();
        try {
            GpuChunkDensityCache existing = CLAIMED.get(key);
            if (existing != null) return existing;
            if (!CACHE_SLOTS.tryAcquire()) return null;
            AtomicBoolean created = new AtomicBoolean();
            try {
                GpuChunkDensityCache result = CLAIMED.computeIfAbsent(key, ignored -> {
                    if (!CACHE_BUILD_SLOTS.tryAcquire()) return null;
                    created.set(true);
                    try {
                        return creator.get();
                    } finally {
                        CACHE_BUILD_SLOTS.release();
                    }
                });
                if (result == null) {
                    CACHE_SLOTS.release();
                    return null;
                }
                if (!created.get()) CACHE_SLOTS.release();
                return result;
            } catch (RuntimeException | Error failure) {
                CACHE_SLOTS.release();
                throw failure;
            }
        } finally {
            lifecycle.unlock();
        }
    }

    static void release(GpuChunkDensityCache cache) {
        if (cache == null) return;
        Lock lifecycle = CACHE_LIFECYCLE.readLock();
        lifecycle.lock();
        try {
            if (CLAIMED.remove(key(cache.minX() >> 4, cache.minZ() >> 4), cache)) {
                GpuChunkDensityCache.removePrefetched(cache);
                CACHE_SLOTS.release();
            }
        } finally {
            lifecycle.unlock();
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
        Lock lifecycle = CACHE_LIFECYCLE.writeLock();
        lifecycle.lock();
        try {
            int released = CLAIMED.size();
            CLAIMED.clear();
            CACHE_SLOTS.release(released);
            synchronized (GpuTerrainAreaRegistry.class) {
                PREFETCHED_TILES.clear();
            }
            GpuChunkDensityCache.clearPrefetched();
        } finally {
            lifecycle.unlock();
        }
    }

    private static void acquireSlot() {
        try {
            CACHE_SLOTS.acquire();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted waiting for GPU terrain cache capacity", interrupted);
        }
    }

    private static void acquireBuildSlot() {
        try {
            CACHE_BUILD_SLOTS.acquire();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted waiting for terrain cache build capacity", interrupted);
        }
    }

    private static long key(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) ^ (chunkZ & 0xffffffffL);
    }
}
