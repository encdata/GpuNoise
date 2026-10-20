package dev.gpunoise;

import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import net.minecraft.world.level.levelgen.Aquifer;

/** Shares deterministic fluid-center results between nearby chunk aquifers. */
public final class GpuAquiferStatusRegionCache {
    private static final int MAX_ENTRIES = (int) Math.max(256L,
            Math.min(65_536L, Long.getLong("gpunoise.aquiferStatusCacheEntries", 16_384L)));
    private static final int MAX_SURFACE_ENTRIES = (int) Math.max(256L,
            Math.min(65_536L, Long.getLong("gpunoise.aquiferSurfaceCacheEntries", 32_768L)));
    private static final int SEGMENT_COUNT = 16;
    private static final ShardedLruCache<Key, Aquifer.FluidStatus> ENTRIES =
            new ShardedLruCache<>(MAX_ENTRIES, SEGMENT_COUNT);
    private static final ShardedLruCache<SurfaceKey, Integer> SURFACES =
            new ShardedLruCache<>(MAX_SURFACE_ENTRIES, SEGMENT_COUNT);
    private static final ConcurrentHashMap<Key, CompletableFuture<Aquifer.FluidStatus>> IN_FLIGHT =
            new ConcurrentHashMap<>();
    private static final Set<Scope> DISABLED_SCOPES = ConcurrentHashMap.newKeySet();
    private static final Set<Scope> DISABLED_SURFACE_SCOPES = ConcurrentHashMap.newKeySet();

    private GpuAquiferStatusRegionCache() {}

    public static Aquifer.FluidStatus get(Object graphIdentity, Object fluidPickerIdentity,
                                          long centerPosition) {
        if (graphIdentity == null || fluidPickerIdentity == null) return null;
        if (DISABLED_SCOPES.contains(new Scope(graphIdentity, fluidPickerIdentity))) return null;
        return ENTRIES.get(new Key(graphIdentity, fluidPickerIdentity, centerPosition));
    }

    public static void put(Object graphIdentity, Object fluidPickerIdentity,
                           long centerPosition, Aquifer.FluidStatus status) {
        if (graphIdentity == null || fluidPickerIdentity == null || status == null) return;
        if (DISABLED_SCOPES.contains(new Scope(graphIdentity, fluidPickerIdentity))) return;
        ENTRIES.put(new Key(graphIdentity, fluidPickerIdentity, centerPosition), status);
    }

    /** Claims a center prefill or joins a neighboring chunk's in-flight result. */
    public static Reservation reserve(Object graphIdentity, Object fluidPickerIdentity,
                                      long centerPosition) {
        if (graphIdentity == null || fluidPickerIdentity == null)
            throw new IllegalArgumentException("aquifer status reservations need stable graph and picker identities");
        Scope scope = new Scope(graphIdentity, fluidPickerIdentity);
        Key key = new Key(graphIdentity, fluidPickerIdentity, centerPosition);
        if (DISABLED_SCOPES.contains(scope)) {
            return new Reservation(key, scope, new CompletableFuture<>(), true, false);
        }
        CompletableFuture<Aquifer.FluidStatus> existing = IN_FLIGHT.get(key);
        if (existing != null) return new Reservation(key, scope, existing, false, true);
        if (IN_FLIGHT.size() >= MAX_ENTRIES)
            return new Reservation(key, scope, new CompletableFuture<>(), true, false);
        AtomicBoolean owner = new AtomicBoolean();
        AtomicReference<Aquifer.FluidStatus> cachedStatus = new AtomicReference<>();
        CompletableFuture<Aquifer.FluidStatus> future = IN_FLIGHT.computeIfAbsent(key, candidate -> {
            Aquifer.FluidStatus cached = ENTRIES.get(candidate);
            if (cached != null) {
                cachedStatus.set(cached);
                return null;
            }
            owner.set(true);
            return new CompletableFuture<>();
        });
        if (future == null) return new Reservation(key, scope,
                CompletableFuture.completedFuture(cachedStatus.get()), false, false);
        return new Reservation(key, scope, future, owner.get(), true);
    }

    /** One-shot ownership or join handle for a single center-status calculation. */
    public static final class Reservation {
        private final Key key;
        private final Scope scope;
        private final CompletableFuture<Aquifer.FluidStatus> future;
        private final boolean owner;
        private final boolean tracked;
        private final AtomicBoolean completed = new AtomicBoolean();

        private Reservation(Key key, Scope scope, CompletableFuture<Aquifer.FluidStatus> future,
                            boolean owner, boolean tracked) {
            this.key = key;
            this.scope = scope;
            this.future = future;
            this.owner = owner;
            this.tracked = tracked;
        }

        public boolean isOwner() { return owner; }

        public Aquifer.FluidStatus join() { return future.join(); }

        public void complete(Aquifer.FluidStatus status) {
            if (!owner) throw new IllegalStateException("only the aquifer reservation owner may complete it");
            if (status == null) throw new NullPointerException("aquifer fluid status");
            if (completed.get()) return;
            if (tracked && !DISABLED_SCOPES.contains(scope)) ENTRIES.put(key, status);
            if (!completed.compareAndSet(false, true)) return;
            future.complete(status);
            if (tracked) IN_FLIGHT.remove(key, future);
        }

        public void fail(Throwable failure) {
            if (!owner) return;
            if (failure == null) failure = new IllegalStateException("aquifer center prefill failed");
            if (!completed.compareAndSet(false, true)) return;
            future.completeExceptionally(failure);
            if (tracked) IN_FLIGHT.remove(key, future);
        }
    }

    /** Returns the cached preliminary surface level or Integer.MIN_VALUE on a miss. */
    public static int getSurface(Object graphIdentity, Object fluidPickerIdentity,
                                 int blockX, int blockZ) {
        if (graphIdentity == null || fluidPickerIdentity == null) return Integer.MIN_VALUE;
        if (DISABLED_SURFACE_SCOPES.contains(new Scope(graphIdentity, fluidPickerIdentity)))
            return Integer.MIN_VALUE;
        Integer value = SURFACES.get(new SurfaceKey(graphIdentity, fluidPickerIdentity, blockX, blockZ));
        return value == null ? Integer.MIN_VALUE : value;
    }

    public static boolean isSurfaceDisabled(Object graphIdentity, Object fluidPickerIdentity) {
        return graphIdentity == null || fluidPickerIdentity == null
                || DISABLED_SURFACE_SCOPES.contains(new Scope(graphIdentity, fluidPickerIdentity));
    }

    public static void putSurface(Object graphIdentity, Object fluidPickerIdentity,
                                  int blockX, int blockZ, int surfaceLevel) {
        if (graphIdentity == null || fluidPickerIdentity == null) return;
        if (DISABLED_SURFACE_SCOPES.contains(new Scope(graphIdentity, fluidPickerIdentity))) return;
        SURFACES.put(new SurfaceKey(graphIdentity, fluidPickerIdentity, blockX, blockZ), surfaceLevel);
    }

    public static void clear() {
        IN_FLIGHT.forEach((key, future) -> future.completeExceptionally(
                new IllegalStateException("aquifer status cache was cleared")));
        IN_FLIGHT.clear();
        ENTRIES.clear();
        SURFACES.clear();
        DISABLED_SCOPES.clear();
        DISABLED_SURFACE_SCOPES.clear();
    }

    /** Permanently disables reuse for a graph/picker pair after a parity mismatch. */
    public static boolean disable(Object graphIdentity, Object fluidPickerIdentity) {
        if (graphIdentity == null || fluidPickerIdentity == null) return false;
        Scope scope = new Scope(graphIdentity, fluidPickerIdentity);
        boolean changed = DISABLED_SCOPES.add(scope);
        ENTRIES.removeIf(key -> key.graphIdentity == graphIdentity
                && key.fluidPickerIdentity == fluidPickerIdentity);
        IN_FLIGHT.forEach((key, future) -> {
            if (key.graphIdentity == graphIdentity && key.fluidPickerIdentity == fluidPickerIdentity
                    && IN_FLIGHT.remove(key, future)) {
                future.completeExceptionally(new IllegalStateException(
                        "aquifer status sharing disabled after a parity mismatch"));
            }
        });
        return changed;
    }

    /** Disables surface reuse for a graph/picker pair after a sampled parity mismatch. */
    public static boolean disableSurface(Object graphIdentity, Object fluidPickerIdentity) {
        if (graphIdentity == null || fluidPickerIdentity == null) return false;
        Scope scope = new Scope(graphIdentity, fluidPickerIdentity);
        boolean changed = DISABLED_SURFACE_SCOPES.add(scope);
        SURFACES.removeIf(key -> key.graphIdentity == graphIdentity
                && key.fluidPickerIdentity == fluidPickerIdentity);
        return changed;
    }

    /** Bounded access-order cache split across independent locks for parallel chunk workers. */
    private static final class ShardedLruCache<K, V> {
        private final List<Segment<K, V>> segments;
        private final int mask;

        private ShardedLruCache(int maximumEntries, int segmentCount) {
            if (Integer.bitCount(segmentCount) != 1) {
                throw new IllegalArgumentException("segment count must be a power of two");
            }
            this.mask = segmentCount - 1;
            int perSegmentLimit = Math.max(1, (maximumEntries + segmentCount - 1) / segmentCount);
            this.segments = new ArrayList<>(segmentCount);
            for (int index = 0; index < segmentCount; index++) {
                segments.add(new Segment<>(perSegmentLimit));
            }
        }

        private Segment<K, V> segment(K key) {
            int hash = key.hashCode();
            hash ^= hash >>> 16;
            return segments.get(hash & mask);
        }

        private V get(K key) { return segment(key).get(key); }

        private void put(K key, V value) { segment(key).put(key, value); }

        private void clear() { segments.forEach(Segment::clear); }

        private void removeIf(Predicate<K> predicate) {
            segments.forEach(segment -> segment.removeIf(predicate));
        }
    }

    private static final class Segment<K, V> {
        private final int maximumEntries;
        private final LinkedHashMap<K, V> entries = new LinkedHashMap<>(256, .75f, true);

        private Segment(int maximumEntries) { this.maximumEntries = maximumEntries; }

        private synchronized V get(K key) { return entries.get(key); }

        private synchronized void put(K key, V value) {
            entries.put(key, value);
            while (entries.size() > maximumEntries) {
                entries.remove(entries.keySet().iterator().next());
            }
        }

        private synchronized void clear() { entries.clear(); }

        private synchronized void removeIf(Predicate<K> predicate) {
            entries.keySet().removeIf(predicate);
        }
    }

    private static final class Key {
        private final Object graphIdentity;
        private final Object fluidPickerIdentity;
        private final long centerPosition;
        private final int hash;

        private Key(Object graphIdentity, Object fluidPickerIdentity, long centerPosition) {
            this.graphIdentity = graphIdentity;
            this.fluidPickerIdentity = fluidPickerIdentity;
            this.centerPosition = centerPosition;
            this.hash = 31 * (31 * System.identityHashCode(graphIdentity)
                    + System.identityHashCode(fluidPickerIdentity)) + Long.hashCode(centerPosition);
        }

        @Override public int hashCode() { return hash; }

        @Override public boolean equals(Object other) {
            return other instanceof Key key && graphIdentity == key.graphIdentity
                    && fluidPickerIdentity == key.fluidPickerIdentity
                    && centerPosition == key.centerPosition;
        }
    }

    private static final class Scope {
        private final Object graphIdentity;
        private final Object fluidPickerIdentity;
        private final int hash;

        private Scope(Object graphIdentity, Object fluidPickerIdentity) {
            this.graphIdentity = graphIdentity;
            this.fluidPickerIdentity = fluidPickerIdentity;
            this.hash = 31 * System.identityHashCode(graphIdentity)
                    + System.identityHashCode(fluidPickerIdentity);
        }

        @Override public int hashCode() { return hash; }

        @Override public boolean equals(Object other) {
            return other instanceof Scope scope && graphIdentity == scope.graphIdentity
                    && fluidPickerIdentity == scope.fluidPickerIdentity;
        }
    }

    private static final class SurfaceKey {
        private final Object graphIdentity;
        private final Object fluidPickerIdentity;
        private final int blockX;
        private final int blockZ;
        private final int hash;

        private SurfaceKey(Object graphIdentity, Object fluidPickerIdentity, int blockX, int blockZ) {
            this.graphIdentity = graphIdentity;
            this.fluidPickerIdentity = fluidPickerIdentity;
            this.blockX = blockX;
            this.blockZ = blockZ;
            this.hash = 31 * (31 * (31 * System.identityHashCode(graphIdentity)
                    + System.identityHashCode(fluidPickerIdentity)) + blockX) + blockZ;
        }

        @Override public int hashCode() { return hash; }

        @Override public boolean equals(Object other) {
            return other instanceof SurfaceKey key && graphIdentity == key.graphIdentity
                    && fluidPickerIdentity == key.fluidPickerIdentity
                    && blockX == key.blockX && blockZ == key.blockZ;
        }
    }
}
