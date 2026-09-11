package dev.gpunoise;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.LockSupport;

/** Batches the small NoiseChunk corner grids into one graph dispatch. */
final class GpuTerrainRootsBatchScheduler implements AutoCloseable {
    private static final int MAX_CHUNKS = 32;
    private static final int MAX_INPUT = PersistentVulkanNormalNoiseEngine.CAPACITY;
    private final BlockingQueue<Pending> queue = new ArrayBlockingQueue<>(256);
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "GPU terrain roots batcher");
        t.setDaemon(true);
        return t;
    });
    private volatile boolean closed;

    GpuTerrainRootsBatchScheduler() { worker.execute(this::run); }

    CompletionStage<float[]> submit(int[] x, int[] y, int[] z) {
        CompletableFuture<float[]> future = new CompletableFuture<>();
        if (closed || !queue.offer(new Pending(x, y, z, future)))
            future.completeExceptionally(new IllegalStateException("GPU terrain roots queue is full"));
        return future;
    }

    private void run() {
        try {
            while (!closed) {
                List<Pending> batch = new ArrayList<>(MAX_CHUNKS);
                Pending first = queue.take();
                batch.add(first);
                int input = first.x.length;
                LockSupport.parkNanos(1_000_000L);
                while (batch.size() < MAX_CHUNKS) {
                    Pending next = queue.peek();
                    if (next == null || input + next.x.length > MAX_INPUT) break;
                    queue.poll();
                    batch.add(next);
                    input += next.x.length;
                }
                dispatch(batch, input);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally {
            Pending item;
            while ((item = queue.poll()) != null)
                item.future.completeExceptionally(new IllegalStateException("GPU terrain roots batcher stopped"));
        }
    }

    private void dispatch(List<Pending> batch, int inputCount) {
        int[] x = new int[inputCount], y = new int[inputCount], z = new int[inputCount];
        int offset = 0;
        for (Pending item : batch) {
            System.arraycopy(item.x, 0, x, offset, item.x.length);
            System.arraycopy(item.y, 0, y, offset, item.y.length);
            System.arraycopy(item.z, 0, z, offset, item.z.length);
            offset += item.x.length;
        }
        GpuTerrainRuntime.submitTerrainRootsDirect(x, y, z).whenComplete((values, failure) -> {
            int sampleOffset = 0;
            int roots = failure == null && values != null && inputCount != 0 ? values.length / inputCount : 0;
            for (Pending item : batch) {
                if (failure != null) item.future.completeExceptionally(failure);
                else {
                    int count = item.x.length * roots;
                    item.future.complete(java.util.Arrays.copyOfRange(values, sampleOffset * roots,
                            sampleOffset * roots + count));
                }
                sampleOffset += item.x.length;
            }
        });
    }

    @Override public void close() {
        closed = true;
        worker.shutdownNow();
    }

    private record Pending(int[] x, int[] y, int[] z, CompletableFuture<float[]> future) {}
}
