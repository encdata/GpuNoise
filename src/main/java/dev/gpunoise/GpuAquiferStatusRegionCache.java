package dev.gpunoise;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.world.level.levelgen.Aquifer;

/** Shares deterministic fluid-center results between nearby chunk aquifers. */
public final class GpuAquiferStatusRegionCache {
    private static final int MAX_ENTRIES = (int) Math.max(256L,
            Math.min(65_536L, Long.getLong("gpunoise.aquiferStatusCacheEntries", 16_384L)));
    private static final LinkedHashMap<Key, Aquifer.FluidStatus> ENTRIES =
            new LinkedHashMap<>(1024, .75f, true);
    private static final Set<Scope> DISABLED_SCOPES = new HashSet<>();

    private GpuAquiferStatusRegionCache() {}

    public static synchronized Aquifer.FluidStatus get(Object graphIdentity, Object fluidPickerIdentity,
                                                        long centerPosition) {
        if (graphIdentity == null || fluidPickerIdentity == null) return null;
        if (DISABLED_SCOPES.contains(new Scope(graphIdentity, fluidPickerIdentity))) return null;
        return ENTRIES.get(new Key(graphIdentity, fluidPickerIdentity, centerPosition));
    }

    public static synchronized void put(Object graphIdentity, Object fluidPickerIdentity,
                                         long centerPosition, Aquifer.FluidStatus status) {
        if (graphIdentity == null || fluidPickerIdentity == null || status == null) return;
        if (DISABLED_SCOPES.contains(new Scope(graphIdentity, fluidPickerIdentity))) return;
        ENTRIES.put(new Key(graphIdentity, fluidPickerIdentity, centerPosition), status);
        while (ENTRIES.size() > MAX_ENTRIES) {
            Iterator<Map.Entry<Key, Aquifer.FluidStatus>> entries = ENTRIES.entrySet().iterator();
            entries.next();
            entries.remove();
        }
    }

    public static synchronized void clear() {
        ENTRIES.clear();
        DISABLED_SCOPES.clear();
    }

    /** Permanently disables reuse for a graph/picker pair after a parity mismatch. */
    public static synchronized boolean disable(Object graphIdentity, Object fluidPickerIdentity) {
        if (graphIdentity == null || fluidPickerIdentity == null) return false;
        Scope scope = new Scope(graphIdentity, fluidPickerIdentity);
        boolean changed = DISABLED_SCOPES.add(scope);
        Iterator<Key> keys = ENTRIES.keySet().iterator();
        while (keys.hasNext()) {
            Key key = keys.next();
            if (key.graphIdentity == graphIdentity && key.fluidPickerIdentity == fluidPickerIdentity) keys.remove();
        }
        return changed;
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
}
