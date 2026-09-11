package dev.gpunoise;

import java.util.ArrayList;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

/** One server-wide queue. Requests from different vanilla samplers share one dispatch. */
final class GpuGlobalSamplerQueue implements AutoCloseable {
    private static final int MAX_PENDING = 512;
    /**
     * Chunk generation normally submits several independent fills back-to-back.
     * Let those calls arrive before dispatching; this is deliberately short so
     * the queue does not add a visible generation stall when only one request is
     * available.
     */
    // Generation's fillArray calls are synchronous. A millisecond here is
    // visible on every isolated call, while 100 us still catches requests
    // arriving concurrently from neighboring chunk tasks.
    private static final long COALESCE_NANOS = 100_000L;
    private final VulkanSubmissionRing ring;
    private final ArrayBlockingQueue<Pending> queue = new ArrayBlockingQueue<>(MAX_PENDING);
    private final ExecutorService worker;
    private volatile boolean closed;

    GpuGlobalSamplerQueue(VulkanSubmissionRing ring) {
        this.ring = ring;
        this.worker = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "GPU Noise global batch");
            thread.setDaemon(true);
            return thread;
        });
        worker.execute(this::run);
    }

    CompletableFuture<float[]> submit(MinecraftNormalNoiseData sampler, float[] x, float[] y, float[] z) {
        if (x.length != y.length || x.length != z.length || x.length > PersistentVulkanNormalNoiseEngine.CAPACITY) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("invalid GPU sample request"));
        }
        Pending pending = new Pending(sampler, x, y, z);
        if (!queue.offer(pending)) pending.future.completeExceptionally(new IllegalStateException("GPU noise queue is full"));
        return pending.future;
    }

    private void run() {
        while (!closed) {
            ArrayList<Pending> requests = new ArrayList<>();
            try {
                Pending first = queue.take();
                requests.add(first);
                int total = first.x.length;

                Pending next;
                // Give chunk-generation threads a real coalescing window. A yield
                // is not a timing primitive and routinely dispatched single fills.
                LockSupport.parkNanos(COALESCE_NANOS);
                while ((next = queue.poll()) != null) {
                    if (total + next.x.length > PersistentVulkanNormalNoiseEngine.CAPACITY) {
                        if (!queue.offer(next)) {
                            next.future.completeExceptionally(new IllegalStateException("GPU noise queue is full"));
                        }
                        break;
                    }
                    requests.add(next);
                    total += next.x.length;
                }
                GpuGlobalNoiseBatch batch = new GpuGlobalNoiseBatch();
                int[] offsets = new int[requests.size()];
                for (int r = 0; r < requests.size(); r++) {
                    Pending request = requests.get(r);
                    offsets[r] = batch.size();
                    for (int i = 0; i < request.x.length; i++) {
                        batch.add(new GpuGlobalNoiseBatch.Sample(request.sampler, request.x[i], request.y[i], request.z[i]));
                    }
                }
                ring.submitNoise(batch.pack()).whenComplete((output, failure) -> {
                    if (failure != null) {
                        for (Pending request : requests) request.future.completeExceptionally(failure);
                        return;
                    }
                    for (int r = 0; r < requests.size(); r++) {
                        Pending request = requests.get(r);
                        float[] values = new float[request.x.length];
                        System.arraycopy(output, offsets[r], values, 0, values.length);
                        request.future.complete(values);
                    }
                });
            } catch (InterruptedException interrupted) {
                if (closed) return;
                Thread.currentThread().interrupt();
                return;
            } catch (Throwable failure) {
                // Never turn a GPU submission failure into an accidental CPU result.
                // Complete the requests already removed from the queue as well as
                // requests still waiting in it.
                for (Pending request : requests) request.future.completeExceptionally(failure);
                Pending request;
                while ((request = queue.poll()) != null) request.future.completeExceptionally(failure);
            }
        }
    }

    @Override public void close() {
        closed = true;
        worker.shutdownNow();
        try { worker.awaitTermination(5, TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        Pending request;
        while ((request = queue.poll()) != null) request.future.completeExceptionally(new IllegalStateException("GPU noise queue closed"));
    }

    private static final class Pending {
        final MinecraftNormalNoiseData sampler;
        final float[] x, y, z;
        final CompletableFuture<float[]> future = new CompletableFuture<>();
        Pending(MinecraftNormalNoiseData sampler, float[] x, float[] y, float[] z) {
            this.sampler = sampler; this.x = x; this.y = y; this.z = z;
        }
    }
}
