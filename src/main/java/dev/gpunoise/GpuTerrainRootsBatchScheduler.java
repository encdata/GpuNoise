package dev.gpunoise;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

/** Batches the small NoiseChunk corner grids into one graph dispatch. */
final class GpuTerrainRootsBatchScheduler implements AutoCloseable {
    /** Spatial batch footprint requested for terrain generation. */
    private static final int BATCH_SIZE_CHUNKS = 10;
    private static final int MAX_CHUNKS = 100;
    /** Search a bounded 50x50 chunk area so neighboring prefetch tiles can coalesce. */
    private static final int SEARCH_RADIUS_REGIONS = (int) Math.max(1L,
            Math.min(4L, Long.getLong("gpunoise.terrainBatchRegionRadius", 2L)));
    /** Amortize CPU/GPU submission overhead while keeping a hard dispatch bound. */
    private static final int TARGET_CHUNKS = (int) Math.max(1L,
            Math.min(MAX_CHUNKS, Long.getLong("gpunoise.terrainBatchTargetChunks", 96L)));
    /** Bound producer bursts without converting temporary overload into CPU fallback. */
    private static final int PENDING_QUEUE_CAPACITY = (int) Math.max(256L,
            Math.min(16_384L, Long.getLong("gpunoise.terrainQueueCapacity", 512L)));
    private static final int MAX_INPUT = PersistentVulkanNormalNoiseEngine.CAPACITY;
    // Give C2ME enough time to enqueue the rest of the local 10x10 area. The
    // deadline is still bounded, so an isolated request is dispatched
    // promptly instead of waiting for neighbors that may never arrive.
    private static final long BATCH_WINDOW_NANOS = TimeUnit.MILLISECONDS.toNanos(
            Math.max(1L, Math.min(250L, Long.getLong("gpunoise.terrainBatchWindowMs", 20L))));
    private final BlockingQueue<Pending> queue = new ArrayBlockingQueue<>(PENDING_QUEUE_CAPACITY);
    // Grid metadata is small and the Vulkan submission is asynchronous. Keep a
    // bounded pool alive until each submission has consumed its metadata rather
    // than allocating native buffers for every dispatch or reusing one too soon.
    private final BlockingQueue<DispatchMetadata> metadataPool = new ArrayBlockingQueue<>(8);
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "GPU terrain roots batcher");
        t.setDaemon(true);
        return t;
    });
    private volatile boolean closed;
    /** Isolates compatible grid layouts in each spatial region for dispatch. */
    // Separate layouts before spatial lookup so candidate scans do not create
    // temporary BatchKey records for every neighboring bucket.
    private final Map<GridLayout, Map<Long, ArrayDeque<Pending>>> pendingByLayout = new HashMap<>();
    private static final Offset[] NEIGHBOR_OFFSETS = createNeighborOffsets();
    /** Number of requests currently owned by the worker's spatial queues. */
    private int pendingChunks;
    private final AtomicLong dispatchCount = new AtomicLong();
    private final AtomicLong totalChunksDispatched = new AtomicLong();
    private final AtomicLong totalSamplesDispatched = new AtomicLong();
    private final AtomicLong totalQueueWaitNanos = new AtomicLong();
    private final AtomicLong completedDispatches = new AtomicLong();
    private final AtomicLong totalDispatchLatencyNanos = new AtomicLong();
    private final AtomicLong queueBackpressureEvents = new AtomicLong();
    private final BatchSizeHistogram batchSizes = new BatchSizeHistogram();
    private final LatencyHistogram chunkLatency = new LatencyHistogram();
    private final AtomicLong lastLatencyLogBucket = new AtomicLong();
    private final AtomicLong lastBatchLogBucket = new AtomicLong();

    GpuTerrainRootsBatchScheduler() {
        for (int i = 0; i < 8; i++) metadataPool.add(new DispatchMetadata());
        GpuNoise.LOGGER.info("GPU terrain batch window={}ms, target={}, chunk limit={}, searchRadiusRegions={}, queueCapacity={}, outputBufferMiB={}",
                TimeUnit.NANOSECONDS.toMillis(BATCH_WINDOW_NANOS), TARGET_CHUNKS, MAX_CHUNKS,
                SEARCH_RADIUS_REGIONS, PENDING_QUEUE_CAPACITY,
                (long) PersistentVulkanNormalNoiseEngine.INTERPOLATION_OUTPUT_FLOATS * Float.BYTES / (1024L * 1024L));
        worker.execute(this::run);
    }

    CompletionStage<GpuTerrainBatchResult> submit(GpuTerrainGrid grid) {
        CompletableFuture<GpuTerrainBatchResult> future = new CompletableFuture<>();
        if (grid.sampleCount() > MAX_INPUT) {
            future.completeExceptionally(new IllegalArgumentException("invalid GPU terrain roots batch"));
            return future;
        }
        if (GpuTerrainRuntime.terrainBlockOutputEnabled()
                && (long) blocksPerChunk(grid) * GpuTerrainRuntime.terrainBlockOutputRootCount()
                > PersistentVulkanNormalNoiseEngine.INTERPOLATION_OUTPUT_FLOATS) {
            future.completeExceptionally(new IllegalArgumentException(
                    "one chunk exceeds GPU terrain block-output capacity"));
            return future;
        }
        if (GpuTerrainRuntime.terrainBlockOutputEnabled()) {
            GpuDensityGraph graph = GpuTerrainRuntime.currentTerrainGraph();
            int markerCount = graph == null ? 0 : graph.interpolatedMarkerCount();
            if ((long) blocksPerChunk(grid) * markerCount
                    > PersistentVulkanNormalNoiseEngine.INTERPOLATED_INPUT_FLOATS) {
                future.completeExceptionally(new IllegalArgumentException(
                        "one chunk exceeds GPU interpolated-marker capacity"));
                return future;
            }
        }
        Pending pending = new Pending(grid, future, System.nanoTime());
        while (!closed) {
            try {
                if (queue.offer(pending, 50L, TimeUnit.MILLISECONDS)) return future;
                queueBackpressureEvents.incrementAndGet();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                future.completeExceptionally(new IllegalStateException(
                        "interrupted while waiting for GPU terrain queue capacity", interrupted));
                return future;
            }
        }
        future.completeExceptionally(new IllegalStateException("GPU terrain roots batcher is closed"));
        return future;
    }

    private void run() {
        try {
            while (!closed) {
                Pending first = queue.poll();
                if (first != null) addPending(first);
                while (pendingByLayout.isEmpty()) {
                    addPending(queue.take());
                }
                long batchStarted = System.nanoTime();
                long deadline = batchStarted + BATCH_WINDOW_NANOS;
                BatchCandidate batchCandidate = largestNearbyBatch();
                int compatibleCount = batchCandidate.chunks;
                while (System.nanoTime() < deadline && pendingChunks < MAX_CHUNKS
                        && compatibleCount < Math.min(TARGET_CHUNKS, batchCandidate.capacity)) {
                    long now = System.nanoTime();
                    long remaining = deadline - now;
                    Pending next = remaining > 0
                            ? queue.poll(remaining, TimeUnit.NANOSECONDS)
                            : queue.poll();
                    if (next == null) break;
                    addPending(next);
                    batchCandidate = largestNearbyBatch();
                    compatibleCount = batchCandidate.chunks;
                }
                Map<Long, ArrayDeque<Pending>> region = pendingByLayout.get(batchCandidate.layout);
                ArrayDeque<Pending> seedQueue = region.get(batchCandidate.region);
                Pending seed = seedQueue.peekFirst();
                int samplesPerChunk = seed.grid.sampleCount();
                int maxChunks = batchCandidate.capacity;
                List<Pending> batch = new ArrayList<>(maxChunks);
                int input = 0;
                long outputFloats = 0;
                // Prefer regions closest to the fullest compatible candidate,
                // then borrow across the bounded 50x50 search area. Chunk grids
                // are self-describing, so unrelated chunk coordinates can
                // share a dispatch without changing per-chunk result slicing.
                for (Offset offset : NEIGHBOR_OFFSETS) {
                    long candidateRegion = offsetRegion(batchCandidate.region, offset.dx, offset.dz);
                    ArrayDeque<Pending> candidateQueue = region.get(candidateRegion);
                    if (candidateQueue == null) continue;
                    while (!candidateQueue.isEmpty() && batch.size() < maxChunks) {
                        Pending item = candidateQueue.peekFirst();
                        if ((long) input + item.grid.sampleCount() > MAX_INPUT) break;
                        long itemOutputFloats = (long) blocksPerChunk(item.grid)
                                * GpuTerrainRuntime.terrainBlockOutputRootCount();
                        if (GpuTerrainRuntime.terrainBlockOutputEnabled()
                                && outputFloats + itemOutputFloats
                                        > PersistentVulkanNormalNoiseEngine.INTERPOLATION_OUTPUT_FLOATS) break;
                        candidateQueue.removeFirst();
                        pendingChunks--;
                        batch.add(item);
                        input += item.grid.sampleCount();
                        outputFloats += itemOutputFloats;
                    }
                    if (candidateQueue.isEmpty()) region.remove(candidateRegion);
                    if (batch.size() == maxChunks) break;
                }
                if (region.isEmpty()) pendingByLayout.remove(batchCandidate.layout);
                dispatchCount.incrementAndGet();
                totalChunksDispatched.addAndGet(batch.size());
                totalSamplesDispatched.addAndGet(input);
                batchSizes.record(batch.size());
                long queueWaitNanos = 0L;
                for (Pending item : batch) queueWaitNanos += System.nanoTime() - item.enqueuedNanos;
                totalQueueWaitNanos.addAndGet(queueWaitNanos);
                dispatch(batch, samplesPerChunk, input);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (Throwable failure) {
            GpuNoise.LOGGER.error("GPU terrain roots batcher failed", failure);
        } finally {
            closed = true;
            Pending pending;
            for (Map<Long, ArrayDeque<Pending>> regions : pendingByLayout.values()) {
                for (ArrayDeque<Pending> region : regions.values()) {
                    while ((pending = region.pollFirst()) != null)
                        pending.future.completeExceptionally(new IllegalStateException("GPU terrain roots batcher stopped"));
                }
            }
            pendingByLayout.clear();
            Pending item;
            while ((item = queue.poll()) != null)
                item.future.completeExceptionally(new IllegalStateException("GPU terrain roots batcher stopped"));
        }
    }

    private void addPending(Pending item) {
        GridLayout layout = GridLayout.of(item.grid);
        Map<Long, ArrayDeque<Pending>> regions = pendingByLayout.computeIfAbsent(layout,
                ignored -> new HashMap<>());
        regions.computeIfAbsent(regionKey(item.grid), ignored -> new ArrayDeque<>()).addLast(item);
        pendingChunks++;
    }

    private static int maxChunksPerDispatch(GpuTerrainGrid grid) {
        int maxChunks = Math.min(MAX_CHUNKS, MAX_INPUT / grid.sampleCount());
        if (GpuTerrainRuntime.terrainBlockOutputEnabled()) {
            long floatsPerChunk = (long) blocksPerChunk(grid)
                    * GpuTerrainRuntime.terrainBlockOutputRootCount();
            maxChunks = Math.min(maxChunks,
                    (int) (PersistentVulkanNormalNoiseEngine.INTERPOLATION_OUTPUT_FLOATS / floatsPerChunk));
            GpuDensityGraph graph = GpuTerrainRuntime.currentTerrainGraph();
            int markerCount = graph == null ? 0 : graph.interpolatedMarkerCount();
            if (markerCount > 0) {
                long markersPerChunk = (long) blocksPerChunk(grid) * markerCount;
                maxChunks = Math.min(maxChunks,
                        (int) (PersistentVulkanNormalNoiseEngine.INTERPOLATED_INPUT_FLOATS / markersPerChunk));
            }
        }
        if (maxChunks <= 0) throw new IllegalArgumentException("terrain grid exceeds GPU dispatch capacity");
        return maxChunks;
    }

    private BatchCandidate largestNearbyBatch() {
        GridLayout bestLayout = null;
        long bestRegion = 0L;
        int bestCount = 0;
        int bestCapacity = 0;
        long bestOldestEnqueuedNanos = Long.MAX_VALUE;
        for (Map.Entry<GridLayout, Map<Long, ArrayDeque<Pending>>> layoutEntry : pendingByLayout.entrySet()) {
            Map<Long, ArrayDeque<Pending>> regions = layoutEntry.getValue();
            for (Map.Entry<Long, ArrayDeque<Pending>> entry : regions.entrySet()) {
                Pending seed = entry.getValue().peekFirst();
                if (seed == null) continue;
                int capacity = maxChunksPerDispatch(seed.grid);
                int count = 0;
                long oldestEnqueuedNanos = Long.MAX_VALUE;
                for (Offset offset : NEIGHBOR_OFFSETS) {
                    if (count >= capacity) break;
                    ArrayDeque<Pending> nearby = regions.get(offsetRegion(entry.getKey(), offset.dx, offset.dz));
                    if (nearby != null) {
                        Pending oldest = nearby.peekFirst();
                        if (oldest != null) oldestEnqueuedNanos = Math.min(
                                oldestEnqueuedNanos, oldest.enqueuedNanos);
                        count = Math.min(capacity, count + nearby.size());
                    }
                }
                // Occupancy remains the primary goal; among equally full
                // batches, dispatch the one whose oldest request has waited
                // longest. HashMap iteration order must not decide fairness.
                if (preferCandidate(count, oldestEnqueuedNanos, layoutEntry.getKey(), entry.getKey(),
                        bestCount, bestOldestEnqueuedNanos, bestLayout, bestRegion)) {
                    bestLayout = layoutEntry.getKey();
                    bestRegion = entry.getKey();
                    bestCount = count;
                    bestCapacity = capacity;
                    bestOldestEnqueuedNanos = oldestEnqueuedNanos;
                }
            }
        }
        if (bestLayout == null) throw new IllegalStateException("terrain batch map is unexpectedly empty");
        return new BatchCandidate(bestLayout, bestRegion, bestCount, bestCapacity);
    }

    private static boolean preferCandidate(int count, long oldestEnqueuedNanos,
                                           GridLayout layout, long region,
                                           int bestCount, long bestOldestEnqueuedNanos,
                                           GridLayout bestLayout, long bestRegion) {
        if (count != bestCount) return count > bestCount;
        if (oldestEnqueuedNanos != bestOldestEnqueuedNanos)
            return oldestEnqueuedNanos < bestOldestEnqueuedNanos;
        if (bestLayout == null) return true;
        int layoutOrder = layout.compareTo(bestLayout);
        return layoutOrder < 0 || (layoutOrder == 0 && Long.compare(region, bestRegion) < 0);
    }

    private static Offset[] createNeighborOffsets() {
        int diameter = SEARCH_RADIUS_REGIONS * 2 + 1;
        Offset[] offsets = new Offset[diameter * diameter];
        int index = 0;
        for (int dx = -SEARCH_RADIUS_REGIONS; dx <= SEARCH_RADIUS_REGIONS; dx++) {
            for (int dz = -SEARCH_RADIUS_REGIONS; dz <= SEARCH_RADIUS_REGIONS; dz++) {
                offsets[index++] = new Offset(dx, dz);
            }
        }
        java.util.Arrays.sort(offsets, java.util.Comparator
                .comparingInt((Offset offset) -> Math.abs(offset.dx) + Math.abs(offset.dz))
                .thenComparingInt(offset -> offset.dx).thenComparingInt(offset -> offset.dz));
        return offsets;
    }

    private static long offsetRegion(long region, int dx, int dz) {
        int regionX = (int) (region >> 32) + dx;
        int regionZ = (int) region + dz;
        return ((long) regionX << 32) ^ (regionZ & 0xffffffffL);
    }

    private void dispatch(List<Pending> batch, int samplesPerChunk, int inputCount) {
        DispatchMetadata buffers;
        try {
            buffers = metadataPool.take();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            IllegalStateException failure = new IllegalStateException("interrupted waiting for terrain metadata buffers", interrupted);
            for (Pending item : batch) item.future.completeExceptionally(failure);
            return;
        }
        java.nio.ByteBuffer metadata = buffers.grid;
        java.nio.ByteBuffer outputMetadata = buffers.output;
        metadata.clear();
        outputMetadata.clear();
        boolean blockOutput = GpuTerrainRuntime.terrainBlockOutputEnabled();
        int blocksPerChunk = blockOutput ? blocksPerChunk(batch.get(0).grid) : 0;
        int cornerOffset = 0;
        int blockOffset = 0;
        int outputRoots = GpuTerrainRuntime.terrainBlockOutputRootCount();
        for (Pending item : batch) {
            GpuTerrainGrid g = item.grid;
            metadata.putInt(g.minX()).putInt(g.minY()).putInt(g.minZ())
                    .putInt(g.gridX()).putInt(g.gridY()).putInt(g.gridZ())
                    .putInt(g.cellWidth()).putInt(g.cellHeight());
            if (!blockOutput) continue;
            int cellsX = g.gridX() - 1;
            int cellsY = g.gridY() - 1;
            int cellsZ = g.gridZ() - 1;
            outputMetadata.putInt(cornerOffset).putInt(blockOffset)
                    .putInt(cellsX).putInt(cellsY).putInt(cellsZ)
                    .putInt(g.cellWidth()).putInt(g.cellHeight()).putInt(0);
            cornerOffset += g.sampleCount();
            blockOffset += blocksPerChunk;
        }
        metadata.flip();
        if (!blockOutput) outputMetadata.limit(0);
        outputMetadata.flip();
        long submittedAt = System.nanoTime();
        try {
            GpuTerrainRuntime.submitTerrainGridInterpolated(metadata, batch.size(), samplesPerChunk,
                    outputMetadata, blocksPerChunk).whenComplete((values, failure) -> {
                try {
                    long completedAt = System.nanoTime();
                    int cornerOffset0 = 0;
                    int blockOffset0 = 0;
                    for (Pending item : batch) {
                        chunkLatency.record(completedAt - item.enqueuedNanos);
                        if (failure != null) item.future.completeExceptionally(failure);
                        else {
                            item.future.complete(values.slice(cornerOffset0, item.grid.sampleCount(), blockOffset0, blocksPerChunk));
                        }
                        cornerOffset0 += item.grid.sampleCount();
                        blockOffset0 += blocksPerChunk;
                    }
                } catch (Throwable completionFailure) {
                    for (Pending item : batch) item.future.completeExceptionally(completionFailure);
                } finally {
                    metadataPool.offer(buffers);
                    recordDispatchLatency(submittedAt);
                }
            });
        } catch (Throwable submissionFailure) {
            metadataPool.offer(buffers);
            for (Pending item : batch) item.future.completeExceptionally(submissionFailure);
            recordDispatchLatency(submittedAt);
        }
    }

    private void recordDispatchLatency(long submittedAt) {
        totalDispatchLatencyNanos.addAndGet(System.nanoTime() - submittedAt);
        long completed = completedDispatches.incrementAndGet();
        long completedChunks = chunkLatency.count();
        long batchBucket = completedChunks >>> 8;
        if (batchBucket > 0L && lastBatchLogBucket.getAndSet(batchBucket) != batchBucket) {
            GpuNoise.LOGGER.info("GPU terrain roots batching: avgChunksPerDispatch={}, avgSamplesPerDispatch={}, avgQueueWaitMsPerChunk={}, avgSubmitToCompleteMsPerDispatch={}, queueBackpressureEvents={}, batchSizes={}",
                    (double) totalChunksDispatched.get() / dispatchCount.get(),
                    (double) totalSamplesDispatched.get() / dispatchCount.get(),
                    totalQueueWaitNanos.get() / (double) totalChunksDispatched.get() / 1_000_000.0,
                    totalDispatchLatencyNanos.get() / (double) completed / 1_000_000.0,
                    queueBackpressureEvents.get(),
                    batchSizes.describe());
        }
        long latencyCount = completedChunks;
        long latencyBucket = latencyCount >>> 7;
        if (latencyBucket > 0L && lastLatencyLogBucket.getAndSet(latencyBucket) != latencyBucket) {
            GpuNoise.LOGGER.info("GPU terrain roots latency: completedChunks={}, p50BucketMs={}, p95BucketMs={}, p99BucketMs={}",
                    latencyCount, chunkLatency.percentileMillis(0.50),
                    chunkLatency.percentileMillis(0.95), chunkLatency.percentileMillis(0.99));
        }
    }

    /** Fixed-memory histogram of chunks per Vulkan dispatch. */
    private static final class BatchSizeHistogram {
        private final AtomicLongArray buckets = new AtomicLongArray(5);

        void record(int chunks) {
            int bucket = chunks < 16 ? 0 : chunks < 32 ? 1 : chunks < 64 ? 2 : chunks < 96 ? 3 : 4;
            buckets.incrementAndGet(bucket);
        }

        String describe() {
            return "lt16:" + buckets.get(0) + ",16to31:" + buckets.get(1)
                    + ",32to63:" + buckets.get(2) + ",64to95:" + buckets.get(3)
                    + ",96plus:" + buckets.get(4);
        }
    }

    /** Fixed-memory per-chunk latency histogram; no objects are allocated per observation. */
    private static final class LatencyHistogram {
        private static final long[] UPPER_BOUNDS_NANOS = {
                1_000_000L, 2_000_000L, 5_000_000L, 10_000_000L, 20_000_000L,
                50_000_000L, 100_000_000L, 250_000_000L, 500_000_000L,
                1_000_000_000L, 2_500_000_000L, 5_000_000_000L, 10_000_000_000L
        };
        private final AtomicLongArray buckets = new AtomicLongArray(UPPER_BOUNDS_NANOS.length + 1);
        private final AtomicLong count = new AtomicLong();

        void record(long elapsedNanos) {
            int bucket = 0;
            while (bucket < UPPER_BOUNDS_NANOS.length && elapsedNanos > UPPER_BOUNDS_NANOS[bucket]) bucket++;
            buckets.incrementAndGet(bucket);
            count.incrementAndGet();
        }

        long count() { return count.get(); }

        String percentileMillis(double percentile) {
            long observations = count.get();
            if (observations == 0L) return "n/a";
            long target = (long) Math.ceil(observations * percentile);
            long seen = 0L;
            for (int bucket = 0; bucket < buckets.length(); bucket++) {
                seen += buckets.get(bucket);
                if (seen >= target) {
                    return bucket == UPPER_BOUNDS_NANOS.length
                            ? ">10000" : Long.toString(UPPER_BOUNDS_NANOS[bucket] / 1_000_000L);
                }
            }
            return ">10000";
        }
    }

    private static int blocksPerChunk(GpuTerrainGrid grid) {
        return Math.multiplyExact(16 * 16,
                Math.multiplyExact(grid.gridY() - 1, grid.cellHeight()));
    }

    private static long regionKey(GpuTerrainGrid grid) {
        int chunkX = grid.minX() >> 4;
        int chunkZ = grid.minZ() >> 4;
        int regionX = Math.floorDiv(chunkX, BATCH_SIZE_CHUNKS);
        int regionZ = Math.floorDiv(chunkZ, BATCH_SIZE_CHUNKS);
        return ((long) regionX << 32) ^ (regionZ & 0xffffffffL);
    }

    @Override public void close() {
        closed = true;
        worker.shutdownNow();
    }

    private record Pending(GpuTerrainGrid grid, CompletableFuture<GpuTerrainBatchResult> future,
                           long enqueuedNanos) {}

    private record Offset(int dx, int dz) {}

    private record BatchCandidate(GridLayout layout, long region, int chunks, int capacity) {}

    private record GridLayout(int gridX, int gridY, int gridZ, int cellWidth, int cellHeight)
            implements Comparable<GridLayout> {
        static GridLayout of(GpuTerrainGrid grid) {
            return new GridLayout(grid.gridX(), grid.gridY(), grid.gridZ(),
                    grid.cellWidth(), grid.cellHeight());
        }

        @Override public int compareTo(GridLayout other) {
            int order = Integer.compare(gridX, other.gridX);
            if (order == 0) order = Integer.compare(gridY, other.gridY);
            if (order == 0) order = Integer.compare(gridZ, other.gridZ);
            if (order == 0) order = Integer.compare(cellWidth, other.cellWidth);
            if (order == 0) order = Integer.compare(cellHeight, other.cellHeight);
            return order;
        }
    }

    private static final class DispatchMetadata {
        final java.nio.ByteBuffer grid = java.nio.ByteBuffer.allocateDirect(MAX_CHUNKS * 32)
                .order(java.nio.ByteOrder.nativeOrder());
        final java.nio.ByteBuffer output = java.nio.ByteBuffer.allocateDirect(MAX_CHUNKS * 32)
                .order(java.nio.ByteOrder.nativeOrder());
    }

}
