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
    /** One prefetched area is already a useful dispatch and should not wait for another tile. */
    private static final int PREFETCH_TILE_CHUNKS = (GpuTerrainAreaRegistry.RADIUS * 2 + 1)
            * (GpuTerrainAreaRegistry.RADIUS * 2 + 1);
    /** Briefly collect adjacent tiles, then dispatch a complete prefetch tile even below target. */
    private static final long TILE_COALESCE_NANOS = TimeUnit.MILLISECONDS.toNanos(10L);
    /** Aim for a useful transfer/dispatch size when nearby requests are ready. */
    private static final int TARGET_CHUNKS = (int) Math.max(1L,
            Math.min(MAX_CHUNKS, Long.getLong("gpunoise.terrainBatchTargetChunks", 64L)));
    private static final int MAX_INPUT = PersistentVulkanNormalNoiseEngine.CAPACITY;
    // Give C2ME enough time to enqueue the rest of the local 10x10 area. The
    // deadline is still bounded, so an isolated request is dispatched
    // promptly instead of waiting for neighbors that may never arrive.
    private static final long BATCH_WINDOW_NANOS = TimeUnit.MILLISECONDS.toNanos(
            Math.max(1L, Math.min(250L, Long.getLong("gpunoise.terrainBatchWindowMs", 20L))));
    private final BlockingQueue<Pending> queue = new ArrayBlockingQueue<>(256);
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
    private final Map<BatchKey, ArrayDeque<Pending>> pendingByRegion = new HashMap<>();
    private final AtomicLong dispatchCount = new AtomicLong();
    private final AtomicLong totalChunksDispatched = new AtomicLong();
    private final AtomicLong totalSamplesDispatched = new AtomicLong();
    private final AtomicLong totalQueueWaitNanos = new AtomicLong();
    private final AtomicLong completedDispatches = new AtomicLong();
    private final AtomicLong totalDispatchLatencyNanos = new AtomicLong();
    private final LatencyHistogram chunkLatency = new LatencyHistogram();
    private final AtomicLong lastLatencyLogBucket = new AtomicLong();
    private final AtomicLong lastBatchLogBucket = new AtomicLong();

    GpuTerrainRootsBatchScheduler() {
        for (int i = 0; i < 8; i++) metadataPool.add(new DispatchMetadata());
        GpuNoise.LOGGER.info("GPU terrain batch window={}ms, target={}, chunk limit={}",
                TimeUnit.NANOSECONDS.toMillis(BATCH_WINDOW_NANOS), TARGET_CHUNKS, MAX_CHUNKS);
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
        if (closed || !queue.offer(new Pending(grid, future, System.nanoTime())))
            future.completeExceptionally(new IllegalStateException("GPU terrain roots queue is full"));
        return future;
    }

    private void run() {
        try {
            while (!closed) {
                Pending first = queue.poll();
                if (first != null) addPending(first);
                while (pendingByRegion.isEmpty()) {
                    addPending(queue.take());
                }
                long batchStarted = System.nanoTime();
                long deadline = batchStarted + BATCH_WINDOW_NANOS;
                long tileDeadline = batchStarted + TILE_COALESCE_NANOS;
                BatchCandidate batchCandidate = largestNearbyBatch();
                int compatibleCount = batchCandidate.chunks;
                while (System.nanoTime() < deadline && pendingCount() < MAX_CHUNKS
                        && compatibleCount < TARGET_CHUNKS) {
                    long now = System.nanoTime();
                    if (compatibleCount >= PREFETCH_TILE_CHUNKS && now >= tileDeadline) break;
                    long remaining = deadline - now;
                    if (compatibleCount >= PREFETCH_TILE_CHUNKS) {
                        remaining = Math.min(remaining, tileDeadline - now);
                    }
                    Pending next = remaining > 0
                            ? queue.poll(remaining, TimeUnit.NANOSECONDS)
                            : queue.poll();
                    if (next == null) break;
                    addPending(next);
                    batchCandidate = largestNearbyBatch();
                    compatibleCount = batchCandidate.chunks;
                }
                BatchKey batchKey = batchCandidate.key;
                ArrayDeque<Pending> region = pendingByRegion.get(batchKey);
                Pending seed = region.peekFirst();
                int samplesPerChunk = seed.grid.sampleCount();
                int maxChunks = maxChunksPerDispatch(seed.grid);
                List<Pending> batch = new ArrayList<>(maxChunks);
                int input = 0;
                long outputFloats = 0;
                // Prefer the selected aligned 5x5 bucket, then borrow from
                // adjacent buckets when it is underfilled.  Chunk grids are
                // self-describing, so this keeps one dispatch useful even
                // when C2ME's traversal crosses a 5x5 boundary between two
                // scheduler wakeups.
                for (long candidateRegion : nearbyRegions(batchKey.region, batchKey.layout)) {
                    BatchKey candidateKey = new BatchKey(candidateRegion, batchKey.layout);
                    ArrayDeque<Pending> candidateQueue = pendingByRegion.get(candidateKey);
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
                        batch.add(item);
                        input += item.grid.sampleCount();
                        outputFloats += itemOutputFloats;
                    }
                    if (candidateQueue.isEmpty()) pendingByRegion.remove(candidateKey);
                    if (batch.size() == maxChunks) break;
                }
                dispatchCount.incrementAndGet();
                totalChunksDispatched.addAndGet(batch.size());
                totalSamplesDispatched.addAndGet(input);
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
            Pending pending;
            for (ArrayDeque<Pending> region : pendingByRegion.values()) {
                while ((pending = region.pollFirst()) != null)
                    pending.future.completeExceptionally(new IllegalStateException("GPU terrain roots batcher stopped"));
            }
            pendingByRegion.clear();
            Pending item;
            while ((item = queue.poll()) != null)
                item.future.completeExceptionally(new IllegalStateException("GPU terrain roots batcher stopped"));
        }
    }

    private void addPending(Pending item) {
        BatchKey key = new BatchKey(regionKey(item.grid), GridLayout.of(item.grid));
        pendingByRegion.computeIfAbsent(key, ignored -> new ArrayDeque<>()).addLast(item);
    }

    private int pendingCount() {
        int count = 0;
        for (ArrayDeque<Pending> region : pendingByRegion.values()) {
            count += region.size();
        }
        return count;
    }

    private static int maxChunksPerDispatch(GpuTerrainGrid grid) {
        int maxChunks = Math.min(MAX_CHUNKS, MAX_INPUT / grid.sampleCount());
        if (GpuTerrainRuntime.terrainBlockOutputEnabled()) {
            long floatsPerChunk = (long) blocksPerChunk(grid)
                    * GpuTerrainRuntime.terrainBlockOutputRootCount();
            maxChunks = Math.min(maxChunks,
                    (int) (PersistentVulkanNormalNoiseEngine.INTERPOLATION_OUTPUT_FLOATS / floatsPerChunk));
        }
        if (maxChunks <= 0) throw new IllegalArgumentException("terrain grid exceeds GPU dispatch capacity");
        return maxChunks;
    }

    private BatchCandidate largestNearbyBatch() {
        BatchKey bestKey = null;
        int bestCount = 0;
        for (Map.Entry<BatchKey, ArrayDeque<Pending>> entry : pendingByRegion.entrySet()) {
            Pending seed = entry.getValue().peekFirst();
            if (seed == null) continue;
            int capacity = maxChunksPerDispatch(seed.grid);
            int centerX = (int) (entry.getKey().region >> 32);
            int centerZ = (int) entry.getKey().region;
            int count = 0;
            for (int dx = -1; dx <= 1 && count < capacity; dx++) {
                for (int dz = -1; dz <= 1 && count < capacity; dz++) {
                    long region = ((long) (centerX + dx) << 32) ^ ((centerZ + dz) & 0xffffffffL);
                    ArrayDeque<Pending> nearby = pendingByRegion.get(
                            new BatchKey(region, entry.getKey().layout));
                    if (nearby != null) count = Math.min(capacity, count + nearby.size());
                }
            }
            if (count > bestCount) {
                bestCount = count;
                bestKey = entry.getKey();
            }
        }
        if (bestKey == null) throw new IllegalStateException("terrain batch map is unexpectedly empty");
        return new BatchCandidate(bestKey, bestCount);
    }

    private List<Long> nearbyRegions(long center, GridLayout layout) {
        int centerX = (int) (center >> 32);
        int centerZ = (int) center;
        List<Long> regions = new ArrayList<>(9);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                long region = ((long) (centerX + dx) << 32) ^ ((centerZ + dz) & 0xffffffffL);
                if (pendingByRegion.containsKey(new BatchKey(region, layout))) {
                    regions.add(region);
                }
            }
        }
        regions.sort((a, b) -> {
            int aDistance = Math.abs((int) (a >> 32) - centerX)
                    + Math.abs((int) a.longValue() - centerZ);
            int bDistance = Math.abs((int) (b >> 32) - centerX)
                    + Math.abs((int) b.longValue() - centerZ);
            return Integer.compare(aDistance, bDistance);
        });
        return regions;
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
            outputMetadata.putInt(cornerOffset).putInt(blockOffset * outputRoots)
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
            GpuNoise.LOGGER.info("GPU terrain roots batching: avgChunksPerDispatch={}, avgSamplesPerDispatch={}, avgQueueWaitMsPerChunk={}, avgSubmitToCompleteMsPerDispatch={}",
                    (double) totalChunksDispatched.get() / dispatchCount.get(),
                    (double) totalSamplesDispatched.get() / dispatchCount.get(),
                    totalQueueWaitNanos.get() / (double) totalChunksDispatched.get() / 1_000_000.0,
                    totalDispatchLatencyNanos.get() / (double) completed / 1_000_000.0);
        }
        long latencyCount = completedChunks;
        long latencyBucket = latencyCount >>> 7;
        if (latencyBucket > 0L && lastLatencyLogBucket.getAndSet(latencyBucket) != latencyBucket) {
            GpuNoise.LOGGER.info("GPU terrain roots latency: completedChunks={}, p50BucketMs={}, p95BucketMs={}, p99BucketMs={}",
                    latencyCount, chunkLatency.percentileMillis(0.50),
                    chunkLatency.percentileMillis(0.95), chunkLatency.percentileMillis(0.99));
        }
    }

    /** Fixed-memory histogram so latency tracking does not allocate per chunk. */
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

    private record BatchKey(long region, GridLayout layout) {}

    private record BatchCandidate(BatchKey key, int chunks) {}

    private record GridLayout(int gridX, int gridY, int gridZ, int cellWidth, int cellHeight) {
        static GridLayout of(GpuTerrainGrid grid) {
            return new GridLayout(grid.gridX(), grid.gridY(), grid.gridZ(),
                    grid.cellWidth(), grid.cellHeight());
        }
    }

    private static final class DispatchMetadata {
        final java.nio.ByteBuffer grid = java.nio.ByteBuffer.allocateDirect(MAX_CHUNKS * 32)
                .order(java.nio.ByteOrder.nativeOrder());
        final java.nio.ByteBuffer output = java.nio.ByteBuffer.allocateDirect(MAX_CHUNKS * 32)
                .order(java.nio.ByteOrder.nativeOrder());
    }

}
