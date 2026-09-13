package dev.gpunoise;

import java.util.concurrent.atomic.AtomicLong;

/** Optional counters for locating the CPU portion of terrain generation. */
public final class GpuTerrainProfiler {
    private static final boolean ENABLED = Boolean.getBoolean("gpunoise.profileTerrain");
    private static final AtomicLong fills = new AtomicLong();
    private static final AtomicLong fillNanos = new AtomicLong();
    private static final AtomicLong gpuFinalDensityCalls = new AtomicLong();
    private static final AtomicLong finalDensityWrapperCalls = new AtomicLong();
    private static final AtomicLong finalDensityWrappersCreated = new AtomicLong();
    private static final AtomicLong gpuInitialDensityCalls = new AtomicLong();
    private static final AtomicLong gpuClimateCalls = new AtomicLong();
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
            GpuNoise.LOGGER.info("GPU terrain profile: fills={}, avgFillMs={}, finalWrappersCreated={}, finalWrapperCalls={}, finalDensityCalls={}, initialDensityCalls={}, climateCalls={}, aquiferCalls={}, blockWrites={}",
                    count, fillNanos.get() / 1_000_000.0 / count,
                    finalDensityWrappersCreated.get(), finalDensityWrapperCalls.get(), gpuFinalDensityCalls.get(), gpuInitialDensityCalls.get(), gpuClimateCalls.get(),
                    aquiferCalls.get(), blockWrites.get());
        }
    }

    public static void recordGpuFinalDensity() {
        if (ENABLED) gpuFinalDensityCalls.incrementAndGet();
    }

    public static void recordFinalDensityWrapper() {
        if (ENABLED) finalDensityWrapperCalls.incrementAndGet();
    }

    public static void recordFinalDensityWrapperCreated() {
        if (ENABLED) finalDensityWrappersCreated.incrementAndGet();
    }

    public static void recordGpuInitialDensity() {
        if (ENABLED) gpuInitialDensityCalls.incrementAndGet();
    }

    public static void recordGpuClimate() {
        if (ENABLED) gpuClimateCalls.incrementAndGet();
    }

    public static void recordAquifer() {
        if (ENABLED) aquiferCalls.incrementAndGet();
    }

    public static void recordBlockWrite() {
        if (ENABLED) blockWrites.incrementAndGet();
    }
}
