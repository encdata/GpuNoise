package dev.gpunoise;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/** Bounded compact cache for asynchronously prefetched OCL-sized surface regions. */
public final class GpuTerrainSurfaceRegionCache {
    private static final int MAX_IN_FLIGHT = 32;
    private static final int MAX_COMPLETED = 32;
    private static final ConcurrentHashMap<Key, CompletableFuture<int[]>> IN_FLIGHT = new ConcurrentHashMap<>();
    private static final java.util.Set<Object> DISABLED = ConcurrentHashMap.newKeySet();
    private static final LinkedHashMap<Key, int[]> COMPLETED = new LinkedHashMap<>(32, 0.75f, true);

    private GpuTerrainSurfaceRegionCache() {}

    static void prefetch(Object graphIdentity, GpuTerrainSurfaceRegion region) {
        if (graphIdentity == null || region == null || DISABLED.contains(graphIdentity)) return;
        Key key = new Key(graphIdentity, region.requestedStartChunkX(), region.requestedStartChunkZ());
        synchronized (COMPLETED) {
            if (COMPLETED.containsKey(key)) return;
        }
        if (IN_FLIGHT.containsKey(key) || IN_FLIGHT.size() >= MAX_IN_FLIGHT) return;
        CompletableFuture<int[]> future;
        try {
            future = GpuTerrainRuntime.submitTerrainSurfaceRegion(region).toCompletableFuture();
        } catch (RuntimeException failure) {
            return;
        }
        CompletableFuture<int[]> previous = IN_FLIGHT.putIfAbsent(key, future);
        if (previous != null) return;
        future.whenComplete((values, failure) -> {
            IN_FLIGHT.remove(key, future);
            if (failure != null || values == null || values.length != region.surfaceValueCount()
                    || DISABLED.contains(graphIdentity)) return;
            synchronized (COMPLETED) {
                COMPLETED.put(key, values);
                while (COMPLETED.size() > MAX_COMPLETED) {
                    var oldest = COMPLETED.keySet().iterator();
                    if (!oldest.hasNext()) break;
                    oldest.next();
                    oldest.remove();
                }
            }
        });
    }

    /** Returns Integer.MIN_VALUE on an absent, unready, or ambiguous sample. */
    public static int get(Object graphIdentity, int blockX, int blockZ) {
        if (graphIdentity == null || DISABLED.contains(graphIdentity)
                || Math.floorMod(blockX, 4) != 0 || Math.floorMod(blockZ, 4) != 0) {
            return Integer.MIN_VALUE;
        }
        int chunkX = Math.floorDiv(blockX, 16);
        int chunkZ = Math.floorDiv(blockZ, 16);
        int regionChunkX = Math.floorDiv(chunkX, 4) * 4;
        int regionChunkZ = Math.floorDiv(chunkZ, 4) * 4;
        Key key = new Key(graphIdentity, regionChunkX, regionChunkZ);
        int originX = (regionChunkX - GpuTerrainSurfaceRegion.HALO_CHUNKS) * 16;
        int originZ = (regionChunkZ - GpuTerrainSurfaceRegion.HALO_CHUNKS) * 16;
        int quartX = Math.floorDiv(blockX - originX, 4);
        int quartZ = Math.floorDiv(blockZ - originZ, 4);
        if (quartX < 0 || quartX >= 48 || quartZ < 0 || quartZ >= 48) return Integer.MIN_VALUE;
        synchronized (COMPLETED) {
            int[] values = COMPLETED.get(key);
            return values == null ? Integer.MIN_VALUE : values[quartX * 48 + quartZ];
        }
    }

    public static void disable(Object graphIdentity) {
        if (graphIdentity == null) return;
        DISABLED.add(graphIdentity);
        synchronized (COMPLETED) {
            COMPLETED.entrySet().removeIf(entry -> entry.getKey().graphIdentity == graphIdentity);
        }
    }

    static void clear() {
        IN_FLIGHT.clear();
        DISABLED.clear();
        synchronized (COMPLETED) { COMPLETED.clear(); }
    }

    private static final class Key {
        private final Object graphIdentity;
        private final int chunkX, chunkZ;
        private final int hash;

        private Key(Object graphIdentity, int chunkX, int chunkZ) {
            this.graphIdentity = graphIdentity;
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.hash = 31 * (31 * System.identityHashCode(graphIdentity) + chunkX) + chunkZ;
        }

        @Override public int hashCode() { return hash; }

        @Override public boolean equals(Object other) {
            return other instanceof Key key && key.graphIdentity == graphIdentity
                    && key.chunkX == chunkX && key.chunkZ == chunkZ;
        }
    }
}
