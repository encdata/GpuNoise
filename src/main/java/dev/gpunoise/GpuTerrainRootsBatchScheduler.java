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

/** Batches the small NoiseChunk corner grids into one graph dispatch. */
final class GpuTerrainRootsBatchScheduler implements AutoCloseable {
    /** Spatial batch footprint requested for terrain generation. */
    private static final int BATCH_SIZE_CHUNKS = 5;
    private static final int MAX_CHUNKS = BATCH_SIZE_CHUNKS * BATCH_SIZE_CHUNKS;
    private static final int MAX_INPUT = PersistentVulkanNormalNoiseEngine.CAPACITY;
    private static final long BATCH_WINDOW_NANOS = 10_000_000L;
    private final BlockingQueue<Pending> queue = new ArrayBlockingQueue<>(256);
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "GPU terrain roots batcher");
        t.setDaemon(true);
        return t;
    });
    private volatile boolean closed;
    /** Requests are held by C2ME-style 4x4 spatial area until dispatch. */
    private final Map<Long, ArrayDeque<Pending>> pendingByRegion = new HashMap<>();
    private final AtomicLong dispatchCount = new AtomicLong();
    private final AtomicLong totalChunksDispatched = new AtomicLong();
    private final AtomicLong totalSamplesDispatched = new AtomicLong();

    GpuTerrainRootsBatchScheduler() {
        worker.execute(this::run);
    }

    CompletionStage<GpuTerrainBatchResult> submit(GpuTerrainGrid grid) {
        CompletableFuture<GpuTerrainBatchResult> future = new CompletableFuture<>();
        if (grid.sampleCount() > MAX_INPUT) {
            future.completeExceptionally(new IllegalArgumentException("invalid GPU terrain roots batch"));
            return future;
        }
        if (closed || !queue.offer(new Pending(grid, future)))
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
                long deadline = System.nanoTime() + BATCH_WINDOW_NANOS;
                while (System.nanoTime() < deadline && pendingByRegion.size() < MAX_CHUNKS * 4) {
                    long remaining = deadline - System.nanoTime();
                    Pending next = remaining > 0
                            ? queue.poll(remaining, TimeUnit.NANOSECONDS)
                            : queue.poll();
                    if (next == null) break;
                    addPending(next);
                }
                long batchRegion = largestRegion();
                ArrayDeque<Pending> region = pendingByRegion.get(batchRegion);
                Pending seed = region.peekFirst();
                int samplesPerChunk = seed.grid.sampleCount();
                List<Pending> batch = new ArrayList<>(MAX_CHUNKS);
                int input = 0;
                // Prefer the selected aligned 5x5 bucket, then borrow from
                // adjacent buckets when it is underfilled.  Chunk grids are
                // self-describing, so this keeps one dispatch useful even
                // when C2ME's traversal crosses a 5x5 boundary between two
                // scheduler wakeups.
                for (long candidateRegion : nearbyRegions(batchRegion)) {
                    ArrayDeque<Pending> candidate = pendingByRegion.get(candidateRegion);
                    if (candidate == null) continue;
                    while (!candidate.isEmpty() && batch.size() < MAX_CHUNKS) {
                        Pending item = candidate.peekFirst();
                        if (item.grid.sampleCount() != samplesPerChunk
                                || (long) input + item.grid.sampleCount() > MAX_INPUT) break;
                        candidate.removeFirst();
                        batch.add(item);
                        input += item.grid.sampleCount();
                    }
                    if (candidate.isEmpty()) pendingByRegion.remove(candidateRegion);
                    if (batch.size() == MAX_CHUNKS) break;
                }
                dispatchCount.incrementAndGet();
                totalChunksDispatched.addAndGet(batch.size());
                totalSamplesDispatched.addAndGet(input);
                if ((dispatchCount.get() & 63L) == 0L) {
                    GpuNoise.LOGGER.info("GPU terrain roots batching: avgChunksPerDispatch={}, avgSamplesPerDispatch={}",
                            (double) totalChunksDispatched.get() / dispatchCount.get(),
                            (double) totalSamplesDispatched.get() / dispatchCount.get());
                }
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
        pendingByRegion.computeIfAbsent(regionKey(item.grid), ignored -> new ArrayDeque<>()).addLast(item);
    }

    private long largestRegion() {
        long bestRegion = 0L;
        int bestCount = -1;
        for (Map.Entry<Long, ArrayDeque<Pending>> entry : pendingByRegion.entrySet()) {
            if (entry.getValue().size() > bestCount) {
                bestCount = entry.getValue().size();
                bestRegion = entry.getKey();
            }
        }
        return bestRegion;
    }

    private List<Long> nearbyRegions(long center) {
        int centerX = (int) (center >> 32);
        int centerZ = (int) center;
        List<Long> regions = new ArrayList<>(pendingByRegion.size());
        for (long region : pendingByRegion.keySet()) {
            int x = (int) (region >> 32);
            int z = (int) region;
            if (Math.abs(x - centerX) <= 1 && Math.abs(z - centerZ) <= 1) {
                regions.add(region);
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
        java.nio.ByteBuffer metadata = java.nio.ByteBuffer.allocateDirect(batch.size() * 32)
                .order(java.nio.ByteOrder.nativeOrder());
        int blocksPerChunk = GpuTerrainRuntime.terrainBlockOutputEnabled()
                ? blocksPerChunk(batch.get(0).grid) : 0;
        java.nio.ByteBuffer outputMetadata = java.nio.ByteBuffer.allocateDirect(
                GpuTerrainRuntime.terrainBlockOutputEnabled() ? batch.size() * 32 : 0)
                .order(java.nio.ByteOrder.nativeOrder());
        int cornerOffset = 0;
        int blockOffset = 0;
        int outputRoots = GpuTerrainRuntime.terrainBlockOutputRootCount();
        for (Pending item : batch) {
            GpuTerrainGrid g = item.grid;
            metadata.putInt(g.minX()).putInt(g.minY()).putInt(g.minZ())
                    .putInt(g.gridX()).putInt(g.gridY()).putInt(g.gridZ())
                    .putInt(g.cellWidth()).putInt(g.cellHeight());
            if (!GpuTerrainRuntime.terrainBlockOutputEnabled()) continue;
            int cellsX = g.gridX() - 1;
            int cellsY = g.gridY() - 1;
            int cellsZ = g.gridZ() - 1;
            outputMetadata.putInt(cornerOffset).putInt(blockOffset * outputRoots)
                    .putInt(cellsX).putInt(cellsY).putInt(cellsZ)
                    .putInt(g.cellWidth()).putInt(0).putInt(0);
            cornerOffset += g.sampleCount();
            blockOffset += blocksPerChunk;
        }
        metadata.flip();
        outputMetadata.flip();
        GpuTerrainRuntime.submitTerrainGridInterpolated(metadata, batch.size(), samplesPerChunk,
                outputMetadata, blocksPerChunk).whenComplete((values, failure) -> {
            int cornerOffset0 = 0;
            int blockOffset0 = 0;
            int roots = failure == null && values != null && inputCount != 0 ? values.roots() : 0;
            for (Pending item : batch) {
                if (failure != null) item.future.completeExceptionally(failure);
                else {
                    item.future.complete(values.slice(cornerOffset0, item.grid.sampleCount(), blockOffset0, blocksPerChunk));
                }
                cornerOffset0 += item.grid.sampleCount();
                blockOffset0 += blocksPerChunk;
            }
        });
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

    private record Pending(GpuTerrainGrid grid, CompletableFuture<GpuTerrainBatchResult> future) {}

}
