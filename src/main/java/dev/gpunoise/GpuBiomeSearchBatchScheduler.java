package dev.gpunoise;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Coalesces independent chunk biome requests into bounded OCL-style GPU work batches. */
final class GpuBiomeSearchBatchScheduler implements AutoCloseable {
    private static final int MAX_PENDING = 256;
    private static final int MAX_BATCH_CHUNKS = 16;
    private static final int BATCH_CHUNKS_AXIS = 4;
    private static final long BATCH_WINDOW_NANOS = TimeUnit.MILLISECONDS.toNanos(1);
    private static final long REQUEST_TIMEOUT_SECONDS = Math.max(1L,
            Math.min(600L, Long.getLong("gpunoise.biomeRequestTimeoutSeconds", 120L)));

    private final VulkanSubmissionRing ring;
    private final ArrayBlockingQueue<Pending> queue = new ArrayBlockingQueue<>(MAX_PENDING);
    private final ArrayDeque<Pending> deferred = new ArrayDeque<>();
    private final Thread worker;
    private volatile boolean closed;

    GpuBiomeSearchBatchScheduler(VulkanSubmissionRing ring) {
        this.ring = ring;
        worker = new Thread(this::run, "GPU Noise biome batches");
        worker.setDaemon(true);
        worker.start();
    }

    CompletableFuture<int[]> submit(ByteBuffer targets, GpuBiomeSearchTree tree, int count,
                                   int chunkX, int chunkZ) {
        CompletableFuture<int[]> future = new CompletableFuture<>()
                .orTimeout(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (tree == null || count <= 0 || count > PersistentVulkanNormalNoiseEngine.CAPACITY
                || targets.remaining() != (long) count * 16L) {
            future.completeExceptionally(new IllegalArgumentException("invalid GPU biome request"));
            return future;
        }
        Pending request = new Pending(targets.duplicate(), tree, count,
                Math.floorDiv(chunkX, BATCH_CHUNKS_AXIS), Math.floorDiv(chunkZ, BATCH_CHUNKS_AXIS), future);
        if (closed || !queue.offer(request)) {
            future.completeExceptionally(new IllegalStateException("GPU biome batch queue is full or stopped"));
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
            ArrayList<Pending> batch = new ArrayList<>(MAX_BATCH_CHUNKS);
            batch.add(first);
            int maxRows = Math.max(1, PersistentVulkanNormalNoiseEngine.CAPACITY / first.count);
            int batchLimit = Math.min(MAX_BATCH_CHUNKS, maxRows);
            int rows = first.count;
            long deadline = System.nanoTime() + BATCH_WINDOW_NANOS;
            while (batch.size() < batchLimit && rows + first.count <= PersistentVulkanNormalNoiseEngine.CAPACITY) {
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
                if (next.tree == first.tree && next.count == first.count
                        && next.tileX == first.tileX && next.tileZ == first.tileZ
                        && next.targets.remaining() == (long) next.count * 16L) {
                    batch.add(next);
                    rows += next.count;
                } else {
                    deferred.addLast(next);
                }
            }
            execute(batch, rows);
        }
        Pending pending;
        while ((pending = queue.poll()) != null) {
            pending.future.completeExceptionally(new IllegalStateException("GPU biome batch scheduler stopped"));
        }
        while ((pending = deferred.pollFirst()) != null) {
            pending.future.completeExceptionally(new IllegalStateException("GPU biome batch scheduler stopped"));
        }
    }

    private void execute(List<Pending> batch, int rows) {
        try {
            ByteBuffer targets = ByteBuffer.allocateDirect(rows * 16).order(ByteOrder.nativeOrder());
            for (Pending request : batch) targets.put(request.targets.duplicate());
            targets.flip();
            int[] output = ring.submitBiomeSearch(targets, batch.get(0).tree, rows)
                    .get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (output.length != rows) throw new IllegalStateException("GPU biome output size mismatch");
            int offset = 0;
            for (Pending request : batch) {
                int end = offset + request.count;
                if (!request.future.isDone()) {
                    request.future.complete(java.util.Arrays.copyOfRange(output, offset, end));
                }
                offset = end;
            }
        } catch (Throwable failure) {
            for (Pending request : batch) request.future.completeExceptionally(failure);
        }
    }

    @Override
    public void close() {
        closed = true;
        worker.interrupt();
        try {
            worker.join(1000L);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private record Pending(ByteBuffer targets, GpuBiomeSearchTree tree, int count, int tileX, int tileZ,
                           CompletableFuture<int[]> future) {}
}
