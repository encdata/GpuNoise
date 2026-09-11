package dev.gpunoise;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.ArrayList;
import java.util.List;

/** Real Vulkan provider for one live vanilla NormalNoise sampler. */
final class GpuNormalNoiseProvider implements AutoCloseable {
    private final MinecraftNormalNoiseData data;
    private final PersistentVulkanNormalNoiseEngine engine;
    private final ExecutorService executor;
    private final BlockingQueue<Pending> queue = new ArrayBlockingQueue<>(128);

    GpuNormalNoiseProvider(PersistentVulkanNormalNoiseEngine engine, MinecraftNormalNoiseData data) {
        this.data = Objects.requireNonNull(data, "data");
        this.engine = Objects.requireNonNull(engine, "engine");
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "GPU NormalNoise Provider");
            thread.setDaemon(true);
            return thread;
        });
        this.executor.execute(this::runLoop);
    }

    CompletionStage<float[]> submit(float[] x, float[] y, float[] z) {
        float[] copyX = x.clone(), copyY = y.clone(), copyZ = z.clone();
        CompletableFuture<float[]> result = new CompletableFuture<>();
        if (!queue.offer(new Pending(copyX, copyY, copyZ, result))) result.completeExceptionally(new IllegalStateException("GPU sampler queue full"));
        return result;
    }

    private void runLoop() {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                List<Pending> batch = new ArrayList<>();
                batch.add(queue.take());
                int count = batch.get(0).x.length;
                while (count < PersistentVulkanNormalNoiseEngine.CAPACITY && queue.peek() != null) {
                    Pending next = queue.peek();
                    if (count + next.x.length > PersistentVulkanNormalNoiseEngine.CAPACITY) break;
                    batch.add(queue.poll());
                    count += next.x.length;
                }
                float[] x = new float[count], y = new float[count], z = new float[count];
                int offset = 0;
                for (Pending item : batch) { System.arraycopy(item.x, 0, x, offset, item.x.length); System.arraycopy(item.y, 0, y, offset, item.y.length); System.arraycopy(item.z, 0, z, offset, item.z.length); offset += item.x.length; }
                try {
                    float[] output = engine.submit(data, x, y, z);
                    offset = 0;
                    for (Pending item : batch) { float[] part = new float[item.x.length]; System.arraycopy(output, offset, part, 0, part.length); item.result.complete(part); offset += part.length; }
                } catch (Throwable failure) { for (Pending item : batch) item.result.completeExceptionally(failure); }
            }
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }

    private record Pending(float[] x, float[] y, float[] z, CompletableFuture<float[]> result) {}

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
