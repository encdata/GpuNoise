package dev.gpunoise;

import net.minecraft.world.level.chunk.ChunkAccess;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Batches CPU section writes for prepared chunks in the same OCL-sized region. */
final class GpuTerrainRegionWriter implements AutoCloseable {
    private static final int MAX_PENDING = 256;
    private static final int REGION_CHUNKS = 4;
    private static final int MAX_BATCH = REGION_CHUNKS * REGION_CHUNKS;
    private static final long BATCH_WINDOW_NANOS = TimeUnit.MILLISECONDS.toNanos(
            Math.max(1L, Math.min(100L, Long.getLong("gpunoise.terrainWriteBatchWindowMs", 12L))));

    private final ArrayBlockingQueue<Pending> queue = new ArrayBlockingQueue<>(MAX_PENDING);
    private final ArrayDeque<Pending> deferred = new ArrayDeque<>();
    private final Thread worker;
    private volatile boolean closed;

    GpuTerrainRegionWriter() {
        worker = new Thread(this::run, "GPU Noise region writer");
        worker.setDaemon(true);
        worker.start();
    }

    CompletableFuture<ChunkAccess> submit(GpuTerrainDecisionWriter.ChunkFill fill) {
        CompletableFuture<ChunkAccess> future = new CompletableFuture<>();
        Pending pending = new Pending(fill, future);
        if (closed) {
            future.completeExceptionally(new IllegalStateException(
                    "GPU terrain region writer is stopped"));
        } else if (!queue.offer(pending)) {
            // Preserve the vanilla completion contract under queue pressure:
            // a bounded-queue miss writes this chunk immediately instead of
            // failing a generation future or retaining more region payloads.
            try {
                future.complete(GpuTerrainDecisionWriter.writeRegion(List.of(fill)).get(0));
            } catch (Throwable failure) {
                future.completeExceptionally(failure);
            }
        }
        return future;
    }

    private void run() {
        while (!closed) {
            Pending first;
            try {
                first = deferred.isEmpty() ? queue.take() : deferred.removeFirst();
            } catch (InterruptedException interrupted) {
                if (closed) break;
                continue;
            }
            if (first.future.isDone()) continue;
            ArrayList<Pending> batch = new ArrayList<>(MAX_BATCH);
            batch.add(first);
            long deadline = System.nanoTime() + BATCH_WINDOW_NANOS;
            while (batch.size() < MAX_BATCH) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0L) break;
                Pending next;
                try {
                    next = queue.poll(remaining, TimeUnit.NANOSECONDS);
                } catch (InterruptedException interrupted) {
                    if (closed) break;
                    continue;
                }
                if (next == null) break;
                if (next.future.isDone()) continue;
                if (compatible(first.fill, next.fill)) batch.add(next);
                else deferred.addLast(next);
            }
            ArrayList<Pending> ready = new ArrayList<>(batch.size());
            for (Pending pending : batch) {
                if (pending.fill.cancellationRequested().get()) {
                    pending.future.completeExceptionally(new java.util.concurrent.CancellationException(
                            "terrain region write cancelled before dispatch"));
                } else if (!pending.future.isDone()) {
                    ready.add(pending);
                }
            }
            if (!ready.isEmpty()) execute(ready);
        }
        failQueued("GPU terrain region writer stopped");
    }

    private static boolean compatible(GpuTerrainDecisionWriter.ChunkFill first,
                                      GpuTerrainDecisionWriter.ChunkFill next) {
        return first.cache().terrainGraphIdentity() == next.cache().terrainGraphIdentity()
                && first.chunk().getHeightAccessorForGeneration() == next.chunk().getHeightAccessorForGeneration()
                && Math.floorDiv(first.chunk().getPos().x, REGION_CHUNKS)
                == Math.floorDiv(next.chunk().getPos().x, REGION_CHUNKS)
                && Math.floorDiv(first.chunk().getPos().z, REGION_CHUNKS)
                == Math.floorDiv(next.chunk().getPos().z, REGION_CHUNKS)
                && first.minCellY() == next.minCellY()
                && first.cellCountY() == next.cellCountY()
                && first.cellWidth() == next.cellWidth()
                && first.cellHeight() == next.cellHeight()
                && first.defaultBlock().equals(next.defaultBlock());
    }

    private static void execute(List<Pending> batch) {
        ArrayList<GpuTerrainDecisionWriter.ChunkFill> fills = new ArrayList<>(batch.size());
        for (Pending pending : batch) fills.add(pending.fill);
        try {
            List<ChunkAccess> written = GpuTerrainDecisionWriter.writeRegion(fills);
            if (written.size() != batch.size()) throw new IllegalStateException("region writer output size mismatch");
            for (int i = 0; i < batch.size(); i++) batch.get(i).future.complete(written.get(i));
        } catch (Throwable failure) {
            for (Pending pending : batch) pending.future.completeExceptionally(failure);
        }
    }

    private void failQueued(String message) {
        Pending pending;
        while ((pending = queue.poll()) != null) {
            pending.future.completeExceptionally(new IllegalStateException(message));
        }
        while ((pending = deferred.pollFirst()) != null) {
            pending.future.completeExceptionally(new IllegalStateException(message));
        }
    }

    @Override public void close() {
        closed = true;
        worker.interrupt();
        try {
            worker.join(1000L);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private record Pending(GpuTerrainDecisionWriter.ChunkFill fill,
                           CompletableFuture<ChunkAccess> future) {}
}
