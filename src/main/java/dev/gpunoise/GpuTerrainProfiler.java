package dev.gpunoise;

import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.LongAdder;

/** Optional low-contention counters for locating the CPU portion of terrain generation. */
public final class GpuTerrainProfiler {
    private static final boolean ENABLED = Boolean.getBoolean("gpunoise.profileTerrain");
    private static final int REPORT_EVERY_FILLS = 128;
    private static final int LATENCY_BUCKETS = 63;
    private static final int HOT_PATH_SAMPLE_MASK = 63;
    private static final ThreadLocal<Integer> hotPathSampleSequence = ThreadLocal.withInitial(() -> 0);
    private static final LongAdder fills = new LongAdder();
    private static final LongAdder fillNanos = new LongAdder();
    private static final LongAdder gpuFinalDensityCalls = new LongAdder();
    private static final LongAdder gpuInitialDensityCalls = new LongAdder();
    private static final LongAdder gpuClimateCalls = new LongAdder();
    private static final LongAdder aquiferCalls = new LongAdder();
    private static final LongAdder aquiferDecisionBuildCalls = new LongAdder();
    private static final LongAdder aquiferDecisionBuildNanos = new LongAdder();
    private static final LongAdder aquiferDecisionBuildSamples = new LongAdder();
    private static final LongAdder aquiferStatusPrefillCalls = new LongAdder();
    private static final LongAdder aquiferStatusPrefillNanos = new LongAdder();
    private static final LongAdder aquiferStatusPrefillSamples = new LongAdder();
    private static final LongAdder aquiferStatusPrefillCenters = new LongAdder();
    private static final LongAdder aquiferStatusPrefillJavaFallbacks = new LongAdder();
    private static final LongAdder blockWrites = new LongAdder();
    private static final LongAdder finalDensityNanos = new LongAdder();
    private static final LongAdder initialDensityNanos = new LongAdder();
    private static final LongAdder climateNanos = new LongAdder();
    private static final LongAdder aquiferNanos = new LongAdder();
    private static final LongAdder blockWriteNanos = new LongAdder();
    private static final LongAdder finalDensitySamples = new LongAdder();
    private static final LongAdder initialDensitySamples = new LongAdder();
    private static final LongAdder climateSamples = new LongAdder();
    private static final LongAdder aquiferSamples = new LongAdder();
    private static final LongAdder blockWriteSamples = new LongAdder();
    private static final LongAdder[] gpuRootCalls = createRootFallbackCounters();
    private static final LongAdder[] javaRootFallbackCalls = createRootFallbackCounters();
    private static final AtomicLongArray fillLatencyHistogram = new AtomicLongArray(LATENCY_BUCKETS);

    private GpuTerrainProfiler() {}

    public static boolean enabled() { return ENABLED; }

    public static long beginFill() {
        if (!ENABLED) return 0L;
        long start = System.nanoTime();
        return start;
    }

    public static void endFill(long start) {
        if (!ENABLED || start == 0L) return;
        long elapsed = Math.max(0L, System.nanoTime() - start);
        long completed = fills.sum() + 1L;
        fillNanos.add(elapsed);
        fillLatencyHistogram.incrementAndGet(latencyBucket(elapsed));
        fills.increment();
        if (completed % REPORT_EVERY_FILLS == 0L) logSnapshot(completed);
    }

    public static void recordGpuFinalDensity() {
        if (ENABLED) {
            gpuFinalDensityCalls.increment();
            gpuRootCalls[GpuTerrainRoots.FINAL_DENSITY].increment();
        }
    }

    /** Counts Java root evaluations after a chunk cache could not supply that root. */
    public static void recordJavaRootFallback(int root) {
        if (ENABLED && root >= 0 && root < javaRootFallbackCalls.length) {
            javaRootFallbackCalls[root].increment();
        }
    }

    public static long beginPhase() {
        if (!ENABLED) return 0L;
        int sequence = hotPathSampleSequence.get();
        hotPathSampleSequence.set(sequence + 1);
        return (sequence & HOT_PATH_SAMPLE_MASK) == 0 ? System.nanoTime() : 0L;
    }

    public static void recordGpuFinalDensity(long start) {
        recordGpuRoot(GpuTerrainRoots.FINAL_DENSITY);
        recordTimed(gpuFinalDensityCalls, finalDensityNanos, finalDensitySamples, start);
    }

    public static void recordGpuInitialDensity() {
        if (ENABLED) {
            gpuInitialDensityCalls.increment();
            gpuRootCalls[GpuTerrainRoots.INITIAL_DENSITY].increment();
        }
    }

    public static void recordGpuInitialDensity(long start) {
        recordGpuRoot(GpuTerrainRoots.INITIAL_DENSITY);
        recordTimed(gpuInitialDensityCalls, initialDensityNanos, initialDensitySamples, start);
    }

    public static void recordGpuClimate() {
        if (ENABLED) gpuClimateCalls.increment();
    }

    public static void recordGpuClimate(int root, long start) {
        recordGpuRoot(root);
        recordTimed(gpuClimateCalls, climateNanos, climateSamples, start);
    }

    public static void recordAquifer() {
        if (ENABLED) aquiferCalls.increment();
    }

    public static void recordAquifer(int root, long start) {
        recordGpuRoot(root);
        recordTimed(aquiferCalls, aquiferNanos, aquiferSamples, start);
    }

    /** Records the sampled cost of assembling and submitting one chunk's aquifer decision table. */
    public static void recordAquiferDecisionBuild(long start) {
        if (!ENABLED) return;
        aquiferDecisionBuildCalls.increment();
        if (start != 0L) {
            aquiferDecisionBuildNanos.add(Math.max(0L, System.nanoTime() - start));
            aquiferDecisionBuildSamples.increment();
        }
    }

    /** Records the C2ME-style batched status kernel and its conservative Java fallbacks. */
    public static void recordAquiferStatusPrefill(long start, int centers, int javaFallbacks) {
        if (!ENABLED) return;
        aquiferStatusPrefillCalls.increment();
        aquiferStatusPrefillCenters.add(Math.max(0, centers));
        aquiferStatusPrefillJavaFallbacks.add(Math.max(0, javaFallbacks));
        if (start != 0L) {
            aquiferStatusPrefillNanos.add(Math.max(0L, System.nanoTime() - start));
            aquiferStatusPrefillSamples.increment();
        }
    }

    public static void recordBlockWrite() {
        if (ENABLED) blockWrites.increment();
    }

    public static void recordBlockWrite(long start) {
        recordTimed(blockWrites, blockWriteNanos, blockWriteSamples, start);
    }

    private static void recordTimed(LongAdder calls, LongAdder nanos, LongAdder samples, long start) {
        if (!ENABLED) return;
        calls.increment();
        if (start != 0L) {
            nanos.add(Math.max(0L, System.nanoTime() - start));
            samples.increment();
        }
    }

    private static void recordGpuRoot(int root) {
        if (ENABLED && root >= 0 && root < gpuRootCalls.length) gpuRootCalls[root].increment();
    }

    private static void logSnapshot(long completed) {
        long totalNanos = fillNanos.sum();
        double averageFillMs = totalNanos / 1_000_000.0 / completed;
        GpuNoise.LOGGER.info("GPU terrain profile: fills={}, avgFillMs={}, p50FillMs={}, p95FillMs={}, p99FillMs={}, finalDensityCalls={} sampled={} avgNs={}, initialDensityCalls={} sampled={} avgNs={}, climateCalls={} sampled={} avgNs={}, aquiferCalls={} sampled={} avgNs={}, blockWrites={} sampled={} avgNs={}",
                completed, round(averageFillMs), percentileMillis(0.50, completed),
                percentileMillis(0.95, completed), percentileMillis(0.99, completed),
                gpuFinalDensityCalls.sum(), finalDensitySamples.sum(), averageNanos(finalDensityNanos, finalDensitySamples),
                gpuInitialDensityCalls.sum(), initialDensitySamples.sum(), averageNanos(initialDensityNanos, initialDensitySamples),
                gpuClimateCalls.sum(), climateSamples.sum(), averageNanos(climateNanos, climateSamples),
                aquiferCalls.sum(), aquiferSamples.sum(), averageNanos(aquiferNanos, aquiferSamples),
                blockWrites.sum(), blockWriteSamples.sum(), averageNanos(blockWriteNanos, blockWriteSamples));
        GpuNoise.LOGGER.info("GPU terrain aquifer decision preparation: calls={}, sampled={} avgNs={}",
                aquiferDecisionBuildCalls.sum(), aquiferDecisionBuildSamples.sum(),
                averageNanos(aquiferDecisionBuildNanos, aquiferDecisionBuildSamples));
        GpuNoise.LOGGER.info("GPU terrain aquifer status prefill: calls={}, centers={}, javaFallbacks={}, sampled={} avgNs={}",
                aquiferStatusPrefillCalls.sum(), aquiferStatusPrefillCenters.sum(),
                aquiferStatusPrefillJavaFallbacks.sum(), aquiferStatusPrefillSamples.sum(),
                averageNanos(aquiferStatusPrefillNanos, aquiferStatusPrefillSamples));
        GpuNoise.LOGGER.info("GPU terrain Java root fallback calls: {}", formatRootFallbacks());
        GpuNoise.LOGGER.info("GPU terrain per-root cache calls: {}", formatRootCalls());
    }

    private static LongAdder[] createRootFallbackCounters() {
        LongAdder[] counters = new LongAdder[GpuTerrainRoots.COUNT];
        for (int root = 0; root < counters.length; root++) counters[root] = new LongAdder();
        return counters;
    }

    private static String formatRootFallbacks() {
        StringBuilder counts = new StringBuilder();
        for (int root = 0; root < javaRootFallbackCalls.length; root++) {
            if (root > 0) counts.append(',');
            counts.append(GpuTerrainRoots.name(root)).append('=').append(javaRootFallbackCalls[root].sum());
        }
        return counts.toString();
    }

    private static String formatRootCalls() {
        StringBuilder counts = new StringBuilder();
        for (int root = 0; root < gpuRootCalls.length; root++) {
            if (root > 0) counts.append(',');
            counts.append(GpuTerrainRoots.name(root)).append('=')
                    .append(gpuRootCalls[root].sum()).append('/')
                    .append(javaRootFallbackCalls[root].sum());
        }
        return counts.toString();
    }

    private static double averageNanos(LongAdder nanos, LongAdder samples) {
        long count = samples.sum();
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
