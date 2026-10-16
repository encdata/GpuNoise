package dev.gpunoise;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.LongAdder;

/** Optional low-contention counters for locating the CPU portion of terrain generation. */
public final class GpuTerrainProfiler {
    private static final boolean ENABLED = Boolean.getBoolean("gpunoise.profileTerrain");
    private static final int REPORT_EVERY_FILLS = 128;
    private static final int LATENCY_BUCKETS = 63;
    private static final LongAdder fills = new LongAdder();
    private static final LongAdder fillNanos = new LongAdder();
    private static final LongAdder gpuFinalDensityCalls = new LongAdder();
    private static final LongAdder gpuInitialDensityCalls = new LongAdder();
    private static final LongAdder gpuClimateCalls = new LongAdder();
    private static final LongAdder aquiferCalls = new LongAdder();
    private static final LongAdder blockWrites = new LongAdder();
    private static final LongAdder finalDensityNanos = new LongAdder();
    private static final LongAdder initialDensityNanos = new LongAdder();
    private static final LongAdder climateNanos = new LongAdder();
    private static final LongAdder aquiferNanos = new LongAdder();
    private static final LongAdder blockWriteNanos = new LongAdder();
    private static final AtomicLong firstFillStartNanos = new AtomicLong();
    private static final AtomicLong latestFillEndNanos = new AtomicLong();
    private static final AtomicLongArray fillLatencyHistogram = new AtomicLongArray(LATENCY_BUCKETS);

    private GpuTerrainProfiler() {}

    public static boolean enabled() { return ENABLED; }

    public static long beginFill() {
        if (!ENABLED) return 0L;
        long start = System.nanoTime();
        firstFillStartNanos.compareAndSet(0L, start);
        return start;
    }

    public static void endFill(long start) {
        if (!ENABLED || start == 0L) return;
        long end = System.nanoTime();
        long elapsed = Math.max(0L, end - start);
        long completed = fills.sum() + 1L;
        fillNanos.add(elapsed);
        fillLatencyHistogram.incrementAndGet(latencyBucket(elapsed));
        latestFillEndNanos.accumulateAndGet(end, Math::max);
        fills.increment();
        if (completed % REPORT_EVERY_FILLS == 0L) logSnapshot(completed);
    }

    public static void recordGpuFinalDensity() {
        if (ENABLED) gpuFinalDensityCalls.increment();
    }

    public static long beginPhase() {
        return ENABLED ? System.nanoTime() : 0L;
    }

    public static void recordGpuFinalDensity(long start) {
        recordTimed(gpuFinalDensityCalls, finalDensityNanos, start);
    }

    public static void recordGpuInitialDensity() {
        if (ENABLED) gpuInitialDensityCalls.increment();
    }

    public static void recordGpuInitialDensity(long start) {
        recordTimed(gpuInitialDensityCalls, initialDensityNanos, start);
    }

    public static void recordGpuClimate() {
        if (ENABLED) gpuClimateCalls.increment();
    }

    public static void recordGpuClimate(long start) {
        recordTimed(gpuClimateCalls, climateNanos, start);
    }

    public static void recordAquifer() {
        if (ENABLED) aquiferCalls.increment();
    }

    public static void recordAquifer(long start) {
        recordTimed(aquiferCalls, aquiferNanos, start);
    }

    public static void recordBlockWrite() {
        if (ENABLED) blockWrites.increment();
    }

    public static void recordBlockWrite(long start) {
        recordTimed(blockWrites, blockWriteNanos, start);
    }

    private static void recordTimed(LongAdder calls, LongAdder nanos, long start) {
        if (!ENABLED || start == 0L) return;
        nanos.add(Math.max(0L, System.nanoTime() - start));
        calls.increment();
    }

    private static void logSnapshot(long completed) {
        long totalNanos = fillNanos.sum();
        long wallNanos = Math.max(1L, latestFillEndNanos.get() - firstFillStartNanos.get());
        double averageFillMs = totalNanos / 1_000_000.0 / completed;
        double fillsPerSecond = completed * 1_000_000_000.0 / wallNanos;
        GpuNoise.LOGGER.info("GPU terrain profile: fills={}, observedCps={}, avgFillMs={}, p50FillMs={}, p95FillMs={}, p99FillMs={}, finalDensityCalls={} avgNs={}, initialDensityCalls={} avgNs={}, climateCalls={} avgNs={}, aquiferCalls={} avgNs={}, blockWrites={} avgNs={}",
                completed, round(fillsPerSecond), round(averageFillMs), percentileMillis(0.50, completed),
                percentileMillis(0.95, completed), percentileMillis(0.99, completed),
                gpuFinalDensityCalls.sum(), averageNanos(finalDensityNanos, gpuFinalDensityCalls),
                gpuInitialDensityCalls.sum(), averageNanos(initialDensityNanos, gpuInitialDensityCalls),
                gpuClimateCalls.sum(), averageNanos(climateNanos, gpuClimateCalls),
                aquiferCalls.sum(), averageNanos(aquiferNanos, aquiferCalls),
                blockWrites.sum(), averageNanos(blockWriteNanos, blockWrites));
    }

    private static double averageNanos(LongAdder nanos, LongAdder calls) {
        long count = calls.sum();
        return count == 0L ? 0.0 : round(nanos.sum() / (double) count);
    }

    private static double percentileMillis(double percentile, long sampleCount) {
        long target = Math.max(1L, (long) Math.ceil(sampleCount * percentile));
        long seen = 0L;
        for (int bucket = 0; bucket < LATENCY_BUCKETS; bucket++) {
            seen += fillLatencyHistogram.get(bucket);
            if (seen >= target) return (1L << bucket) / 1_000_000.0;
        }
        return (1L << (LATENCY_BUCKETS - 1)) / 1_000_000.0;
    }

    private static int latencyBucket(long nanos) {
        return Math.min(LATENCY_BUCKETS - 1,
                63 - Long.numberOfLeadingZeros(Math.max(1L, nanos)));
    }

    private static double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }
}
