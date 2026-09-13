package dev.gpunoise;

import java.util.concurrent.atomic.AtomicLong;

/** Optional counters for locating the CPU portion of terrain generation. */
public final class GpuTerrainProfiler {
    private static final boolean ENABLED = Boolean.getBoolean("gpunoise.profileTerrain");
    private static final AtomicLong fills = new AtomicLong();
    private static final AtomicLong fillNanos = new AtomicLong();
    private static final AtomicLong gpuDensityCalls = new AtomicLong();
    private static final AtomicLong aquiferCalls = new AtomicLong();
    private static final AtomicLong blockWrites = new AtomicLong();

    private GpuTerrainProfiler() {}

    public static boolean enabled() { return ENABLED; }

    public static long beginFill() {
        return ENABLED ? System.nanoTime() : 0L;
    }

    public static void endFill(long start) {
        if (!ENABLED) return;
        long count = fills.incrementAndGet();
        fillNanos.addAndGet(System.nanoTime() - start);
        if ((count & 127L) == 0L) {
            GpuNoise.LOGGER.info("GPU terrain profile: fills={}, avgFillMs={}, gpuDensityCalls={}, aquiferCalls={}, blockWrites={}",
                    count, fillNanos.get() / 1_000_000.0 / count,
                    gpuDensityCalls.get(), aquiferCalls.get(), blockWrites.get());
        }
    }

    public static void recordGpuDensity() {
        if (ENABLED) gpuDensityCalls.incrementAndGet();
    }

    public static void recordAquifer() {
        if (ENABLED) aquiferCalls.incrementAndGet();
    }

    public static void recordBlockWrite() {
        if (ENABLED) blockWrites.incrementAndGet();
    }
}
