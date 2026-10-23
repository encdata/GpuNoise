package dev.gpunoise;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.LongAdder;

/** Optional low-contention counters for locating the CPU portion of terrain generation. */
public final class GpuTerrainProfiler {
    private static final boolean ENABLED = Boolean.getBoolean("gpunoise.profileTerrain");
    private static final int REPORT_EVERY_FILLS = 128;
    private static final int LATENCY_BUCKETS = 63;
    private static final int HOT_PATH_SAMPLE_MASK = 63;
    public static final int AQUIFER_SCAN = 0;
    public static final int AQUIFER_NEAREST_STATUS = 1;
    public static final int AQUIFER_DECISION_DISPATCH = 2;
    public static final int AQUIFER_TABLE_RESOLVE = 3;
    private static final String[] AQUIFER_PHASE_NAMES = {
            "scanAndPack", "nearestAndStatus", "decisionDispatch", "tableResolve"
    };
    private static final ThreadLocal<Integer> hotPathSampleSequence = ThreadLocal.withInitial(() -> 0);
    private static final LongAdder fills = new LongAdder();
    private static final LongAdder fillNanos = new LongAdder();
    private static final LongAdder gpuFinalDensityCalls = new LongAdder();
    private static final LongAdder gpuInitialDensityCalls = new LongAdder();
    private static final LongAdder gpuClimateCalls = new LongAdder();
    private static final LongAdder graphRootDispatches = new LongAdder();
    private static final LongAdder graphRootSamples = new LongAdder();
    private static final LongAdder graphRootReturnedValues = new LongAdder();
    private static final LongAdder graphRootFullEquivalentValues = new LongAdder();
    private static final LongAdder aquiferCalls = new LongAdder();
    private static final LongAdder aquiferDecisionBuildCalls = new LongAdder();
    private static final LongAdder aquiferDecisionBuildNanos = new LongAdder();
    private static final LongAdder aquiferDecisionBuildSamples = new LongAdder();
    private static final LongAdder aquiferDecisionWaitCalls = new LongAdder();
    private static final LongAdder aquiferDecisionWaitNanos = new LongAdder();
    private static final LongAdder aquiferDecisionWaitSamples = new LongAdder();
    private static final LongAdder aquiferAsyncDecisionFallbacks = new LongAdder();
    private static final LongAdder aquiferDecisionQueueSamples = new LongAdder();
    private static final AtomicInteger peakAquiferDecisionWorkers = new AtomicInteger();
    private static final AtomicInteger peakAquiferDecisionQueue = new AtomicInteger();
    private static final LongAdder aquiferStatusPrefillCalls = new LongAdder();
    private static final LongAdder aquiferStatusPrefillNanos = new LongAdder();
    private static final LongAdder aquiferStatusPrefillSamples = new LongAdder();
    private static final LongAdder aquiferStatusPrefillCenters = new LongAdder();
    private static final LongAdder aquiferStatusPrefillJavaFallbacks = new LongAdder();
    private static final LongAdder aquiferNearestDispatches = new LongAdder();
    private static final LongAdder aquiferNearestRows = new LongAdder();
    private static final LongAdder aquiferNearestCoalescedDispatches = new LongAdder();
    private static final LongAdder aquiferNearestCoalescedRequests = new LongAdder();
    private static final LongAdder aquiferSubstanceDispatches = new LongAdder();
    private static final LongAdder aquiferSubstanceRows = new LongAdder();
    private static final LongAdder aquiferSubstanceCoalescedDispatches = new LongAdder();
    private static final LongAdder aquiferSubstanceCoalescedRequests = new LongAdder();
    private static final LongAdder oreVeinDispatches = new LongAdder();
    private static final LongAdder oreVeinRows = new LongAdder();
    private static final LongAdder oreVeinRequests = new LongAdder();
    private static final LongAdder oreVeinInputBytes = new LongAdder();
    private static final LongAdder oreVeinOutputBytes = new LongAdder();
    private static final LongAdder aquiferRegionStatusCacheHits = new LongAdder();
    private static final LongAdder aquiferRegionStatusCacheMisses = new LongAdder();
    private static final LongAdder aquiferRegionSurfaceCacheHits = new LongAdder();
    private static final LongAdder aquiferRegionSurfaceCacheMisses = new LongAdder();
    private static final LongAdder vanillaFluidPickerCacheHits = new LongAdder();
    private static final LongAdder vanillaFluidPickerCacheMisses = new LongAdder();
    private static final LongAdder[] aquiferPhaseNanos = createCounterArray(AQUIFER_PHASE_NAMES.length);
    private static final LongAdder[] aquiferPhaseSamples = createCounterArray(AQUIFER_PHASE_NAMES.length);
    private static final LongAdder blockWrites = new LongAdder();
    private static final LongAdder finalDensityNanos = new LongAdder();
    private static final LongAdder initialDensityNanos = new LongAdder();
    private static final LongAdder climateNanos = new LongAdder();
    private static final LongAdder aquiferNanos = new LongAdder();
    private static final LongAdder blockWriteNanos = new LongAdder();
    private static final LongAdder terrainBlockPreparationNanos = new LongAdder();
    private static final LongAdder finalDensitySamples = new LongAdder();
    private static final LongAdder initialDensitySamples = new LongAdder();
    private static final LongAdder climateSamples = new LongAdder();
    private static final LongAdder aquiferSamples = new LongAdder();
    private static final LongAdder blockWriteSamples = new LongAdder();
    private static final LongAdder terrainBlockPreparationCalls = new LongAdder();
    private static final LongAdder terrainBlockPreparationSamples = new LongAdder();
    private static final LongAdder terrainDecisionSamples = new LongAdder();
    private static final LongAdder terrainGpuDecisionHits = new LongAdder();
    private static final LongAdder terrainMissingDecisionRows = new LongAdder();
    private static final LongAdder terrainMissingDensityCache = new LongAdder();
    private static final LongAdder terrainParityBypasses = new LongAdder();
    private static final LongAdder terrainUnavailableDensity = new LongAdder();
    private static final LongAdder terrainDensityMismatches = new LongAdder();
    private static final LongAdder terrainCachePreparationCalls = new LongAdder();
    private static final LongAdder terrainCachePreparationNanos = new LongAdder();
    private static final LongAdder terrainCachePreparationSamples = new LongAdder();
    private static final AtomicInteger activeTerrainCachePreparations = new AtomicInteger();
    private static final AtomicInteger maxConcurrentTerrainCachePreparations = new AtomicInteger();
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

    /** Starts timing bounded CPU translation and input packing for one terrain cache. */
    public static long beginTerrainCachePreparation() {
        if (!ENABLED) return 0L;
        int active = activeTerrainCachePreparations.incrementAndGet();
        maxConcurrentTerrainCachePreparations.accumulateAndGet(active, Math::max);
        return System.nanoTime();
    }

    public static void recordTerrainCachePreparation(long start) {
        if (!ENABLED) return;
        terrainCachePreparationCalls.increment();
        if (start != 0L) {
            terrainCachePreparationNanos.add(Math.max(0L, System.nanoTime() - start));
            terrainCachePreparationSamples.increment();
        }
        activeTerrainCachePreparations.decrementAndGet();
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

    /** Tracks point-root transfer volume without reporting terrain throughput. */
    static void recordGraphRootReadback(int samples, int returnedRoots, int availableRoots) {
        if (!ENABLED || samples <= 0 || returnedRoots <= 0 || availableRoots < returnedRoots) return;
        graphRootDispatches.increment();
        graphRootSamples.add(samples);
        graphRootReturnedValues.add((long) samples * returnedRoots);
        graphRootFullEquivalentValues.add((long) samples * availableRoots);
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

    /** Measures chunk-thread blocking left after moving aquifer table preparation off-thread. */
    public static void recordAquiferDecisionWait(long start) {
        if (!ENABLED) return;
        aquiferDecisionWaitCalls.increment();
        if (start != 0L) {
            aquiferDecisionWaitNanos.add(Math.max(0L, System.nanoTime() - start));
            aquiferDecisionWaitSamples.increment();
        }
    }

    public static void recordAquiferAsyncDecisionFallback() {
        if (ENABLED) aquiferAsyncDecisionFallbacks.increment();
    }

    public static void recordAquiferDecisionQueue(int activeWorkers, int queuedBuilds) {
        if (!ENABLED) return;
        aquiferDecisionQueueSamples.increment();
        peakAquiferDecisionWorkers.accumulateAndGet(activeWorkers, Math::max);
        peakAquiferDecisionQueue.accumulateAndGet(queuedBuilds, Math::max);
    }

    /** Starts an unsampled timer for a once-per-chunk aquifer preparation phase. */
    public static long beginAquiferPhase() {
        return ENABLED ? System.nanoTime() : 0L;
    }

    public static void recordAquiferPhase(int phase, long start) {
        if (!ENABLED || start == 0L || phase < 0 || phase >= AQUIFER_PHASE_NAMES.length) return;
        aquiferPhaseNanos[phase].add(Math.max(0L, System.nanoTime() - start));
        aquiferPhaseSamples[phase].increment();
    }

    public static void recordAquiferRegionStatusCache(boolean hit) {
        if (!ENABLED) return;
        if (hit) aquiferRegionStatusCacheHits.increment();
        else aquiferRegionStatusCacheMisses.increment();
    }

    public static void recordAquiferRegionSurfaceCache(boolean hit) {
        if (!ENABLED) return;
        if (hit) aquiferRegionSurfaceCacheHits.increment();
        else aquiferRegionSurfaceCacheMisses.increment();
    }

    public static void recordVanillaFluidPickerCache(boolean hit) {
        if (!ENABLED) return;
        if (hit) vanillaFluidPickerCacheHits.increment();
        else vanillaFluidPickerCacheMisses.increment();
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

    /** Tracks aquifer kernel batch shape for tuning without CPS measurements. */
    public static void recordAquiferNearestBatch(int rows) {
        if (!ENABLED || rows <= 0) return;
        aquiferNearestDispatches.increment();
        aquiferNearestRows.add(rows);
    }

    public static void recordAquiferNearestCoalesced(int requests) {
        if (!ENABLED || requests <= 1) return;
        aquiferNearestCoalescedDispatches.increment();
        aquiferNearestCoalescedRequests.add(requests);
    }

    public static void recordAquiferSubstanceBatch(int rows) {
        if (!ENABLED || rows <= 0) return;
        aquiferSubstanceDispatches.increment();
        aquiferSubstanceRows.add(rows);
    }

    public static void recordAquiferSubstanceCoalesced(int requests) {
        if (!ENABLED || requests <= 1) return;
        aquiferSubstanceCoalescedDispatches.increment();
        aquiferSubstanceCoalescedRequests.add(requests);
    }

    public static void recordOreVeinBatch(int rows, int requests) {
        if (!ENABLED || rows <= 0) return;
        oreVeinDispatches.increment();
        oreVeinRows.add(rows);
        oreVeinRequests.add(Math.max(0, requests));
        oreVeinInputBytes.add((long) rows * GpuOreVeinDecisions.RECORD_BYTES
                + GpuOreVeinDecisions.PUSH_CONSTANT_BYTES);
        oreVeinOutputBytes.add((long) ((rows + 9) / 10) * Integer.BYTES);
    }

    public static void recordBlockWrite() {
        if (ENABLED) blockWrites.increment();
    }

    public static void recordBlockWrite(long start) {
        recordTimed(blockWrites, blockWriteNanos, blockWriteSamples, start);
    }

    /** Samples CPU preparation of the block palette and vanilla fallback rows. */
    public static long beginTerrainBlockPreparation() {
        return beginPhase();
    }

    public static void recordTerrainBlockPreparation(long start) {
        recordTimed(terrainBlockPreparationCalls, terrainBlockPreparationNanos,
                terrainBlockPreparationSamples, start);
    }

    /** Counts per-block aquifer decisions that avoid the Java NoiseChunk interpolation path. */
    public static void recordTerrainDecisionUse(long samples, long gpuHits, long missingRows,
                                                long missingCache, long parityBypasses,
                                                long unavailableDensity, long densityMismatches) {
        if (!ENABLED || samples <= 0L) return;
        terrainDecisionSamples.add(samples);
        terrainGpuDecisionHits.add(gpuHits);
        terrainMissingDecisionRows.add(missingRows);
        terrainMissingDensityCache.add(missingCache);
        terrainParityBypasses.add(parityBypasses);
        terrainUnavailableDensity.add(unavailableDensity);
        terrainDensityMismatches.add(densityMismatches);
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
        GpuNoise.LOGGER.info("GPU terrain profile: fills={}, avgFillMs={}, p50FillMs={}, p95FillMs={}, p99FillMs={}, finalDensityCalls={} sampled={} avgNs={}, initialDensityCalls={} sampled={} avgNs={}, climateCalls={} sampled={} avgNs={}, aquiferCalls={} sampled={} avgNs={}, blockPreparationCalls={} sampled={} avgNs={}, blockWrites={} sampled={} avgNs={}",
                completed, round(averageFillMs), percentileMillis(0.50, completed),
                percentileMillis(0.95, completed), percentileMillis(0.99, completed),
                gpuFinalDensityCalls.sum(), finalDensitySamples.sum(), averageNanos(finalDensityNanos, finalDensitySamples),
                gpuInitialDensityCalls.sum(), initialDensitySamples.sum(), averageNanos(initialDensityNanos, initialDensitySamples),
                gpuClimateCalls.sum(), climateSamples.sum(), averageNanos(climateNanos, climateSamples),
                aquiferCalls.sum(), aquiferSamples.sum(), averageNanos(aquiferNanos, aquiferSamples),
                terrainBlockPreparationCalls.sum(), terrainBlockPreparationSamples.sum(),
                averageNanos(terrainBlockPreparationNanos, terrainBlockPreparationSamples),
                blockWrites.sum(), blockWriteSamples.sum(), averageNanos(blockWriteNanos, blockWriteSamples));
        long returnedRootValues = graphRootReturnedValues.sum();
        long fullRootValues = graphRootFullEquivalentValues.sum();
        double rootReadbackReduction = fullRootValues == 0L ? 0.0
                : round(100.0 * (fullRootValues - returnedRootValues) / fullRootValues);
        GpuNoise.LOGGER.info("GPU graph-root readback: dispatches={}, samples={}, values={}, fullRootEquivalentValues={}, reductionPercent={}",
                graphRootDispatches.sum(), graphRootSamples.sum(), returnedRootValues,
                fullRootValues, rootReadbackReduction);
        GpuNoise.LOGGER.info("GPU terrain aquifer decision preparation: calls={}, sampled={} avgNs={}",
                aquiferDecisionBuildCalls.sum(), aquiferDecisionBuildSamples.sum(),
                averageNanos(aquiferDecisionBuildNanos, aquiferDecisionBuildSamples));
        GpuNoise.LOGGER.info("GPU terrain aquifer decision wait: calls={}, sampled={} avgNs={}, fallbacks={}",
                aquiferDecisionWaitCalls.sum(), aquiferDecisionWaitSamples.sum(),
                averageNanos(aquiferDecisionWaitNanos, aquiferDecisionWaitSamples),
                aquiferAsyncDecisionFallbacks.sum());
        GpuNoise.LOGGER.info("GPU terrain aquifer build queue: samples={}, peakWorkers={}, peakQueued={}",
                aquiferDecisionQueueSamples.sum(), peakAquiferDecisionWorkers.get(),
                peakAquiferDecisionQueue.get());
        GpuNoise.LOGGER.info("GPU terrain cache preparation: calls={}, sampled={} avgNs={}, maxConcurrent={}",
                terrainCachePreparationCalls.sum(), terrainCachePreparationSamples.sum(),
                averageNanos(terrainCachePreparationNanos, terrainCachePreparationSamples),
                maxConcurrentTerrainCachePreparations.get());
        long decisionSamples = terrainDecisionSamples.sum();
        GpuNoise.LOGGER.info("GPU terrain block-decision reuse: gpuApplied={}/{} ({}%), missingRows={}, missingDensityCache={}, parityBypasses={}, unavailableDensity={}, densityMismatches={}",
                terrainGpuDecisionHits.sum(), decisionSamples,
                String.format(java.util.Locale.ROOT, "%.2f",
                        decisionSamples == 0L ? 0.0 : 100.0 * terrainGpuDecisionHits.sum() / decisionSamples),
                terrainMissingDecisionRows.sum(), terrainMissingDensityCache.sum(),
                terrainParityBypasses.sum(), terrainUnavailableDensity.sum(),
                terrainDensityMismatches.sum());
        GpuNoise.LOGGER.info("GPU terrain aquifer status prefill: calls={}, centers={}, javaFallbacks={}, sampled={} avgNs={}",
                aquiferStatusPrefillCalls.sum(), aquiferStatusPrefillCenters.sum(),
                aquiferStatusPrefillJavaFallbacks.sum(), aquiferStatusPrefillSamples.sum(),
                averageNanos(aquiferStatusPrefillNanos, aquiferStatusPrefillSamples));
        GpuNoise.LOGGER.info("GPU terrain aquifer batches: nearestDispatches={}, nearestRows={}, avgNearestRows={}, nearestCoalescedDispatches={}, nearestCoalescedRequests={}, substanceDispatches={}, substanceRows={}, avgSubstanceRows={}, substanceCoalescedDispatches={}, substanceCoalescedRequests={}",
                aquiferNearestDispatches.sum(), aquiferNearestRows.sum(),
                averageRows(aquiferNearestRows, aquiferNearestDispatches),
                aquiferNearestCoalescedDispatches.sum(), aquiferNearestCoalescedRequests.sum(),
                aquiferSubstanceDispatches.sum(), aquiferSubstanceRows.sum(),
                averageRows(aquiferSubstanceRows, aquiferSubstanceDispatches),
                aquiferSubstanceCoalescedDispatches.sum(), aquiferSubstanceCoalescedRequests.sum());
        GpuNoise.LOGGER.info("GPU terrain ore-vein batches: dispatches={}, rows={}, avgRows={}, requests={}",
                oreVeinDispatches.sum(), oreVeinRows.sum(),
                averageRows(oreVeinRows, oreVeinDispatches), oreVeinRequests.sum());
        GpuNoise.LOGGER.info("GPU terrain ore-vein transfer: inputBytes={}, compactReadbackBytes={}, inputBytesPerRow={}",
                oreVeinInputBytes.sum(), oreVeinOutputBytes.sum(),
                oreVeinRows.sum() == 0L ? 0.0 : (double) oreVeinInputBytes.sum() / oreVeinRows.sum());
        GpuNoise.LOGGER.info("GPU terrain input staging slabs: allocated={}, reused={}, pooledBytes={}",
                GpuTerrainInputBufferPool.allocatedBufferCount(),
                GpuTerrainInputBufferPool.reusedBufferCount(),
                GpuTerrainInputBufferPool.pooledByteCount());
        GpuNoise.LOGGER.info("GPU terrain aquifer phase average ns: {}",
                formatAquiferPhases());
        GpuNoise.LOGGER.info("GPU terrain aquifer region-status cache: hits={}, misses={}",
                aquiferRegionStatusCacheHits.sum(), aquiferRegionStatusCacheMisses.sum());
        GpuNoise.LOGGER.info("GPU terrain aquifer region-surface cache: hits={}, misses={}",
                aquiferRegionSurfaceCacheHits.sum(), aquiferRegionSurfaceCacheMisses.sum());
        GpuNoise.LOGGER.info("GPU terrain vanilla fluid-picker row cache: hits={}, misses={}",
                vanillaFluidPickerCacheHits.sum(), vanillaFluidPickerCacheMisses.sum());
        GpuNoise.LOGGER.info("GPU terrain Java root fallback calls: {}", formatRootFallbacks());
        GpuNoise.LOGGER.info("GPU terrain per-root cache calls: {}", formatRootCalls());
    }

    private static LongAdder[] createRootFallbackCounters() {
        return createCounterArray(GpuTerrainRoots.COUNT);
    }

    private static LongAdder[] createCounterArray(int count) {
        LongAdder[] counters = new LongAdder[count];
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

    private static String formatAquiferPhases() {
        StringBuilder values = new StringBuilder();
        for (int phase = 0; phase < AQUIFER_PHASE_NAMES.length; phase++) {
            if (phase > 0) values.append(',');
            values.append(AQUIFER_PHASE_NAMES[phase]).append('=')
                    .append(averageNanos(aquiferPhaseNanos[phase], aquiferPhaseSamples[phase]))
                    .append("ns/").append(aquiferPhaseSamples[phase].sum());
        }
        return values.toString();
    }

    private static double averageNanos(LongAdder nanos, LongAdder samples) {
        long count = samples.sum();
        return count == 0L ? 0.0 : round(nanos.sum() / (double) count);
    }

    private static double averageRows(LongAdder rows, LongAdder dispatches) {
        long count = dispatches.sum();
        return count == 0L ? 0.0 : round(rows.sum() / (double) count);
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
