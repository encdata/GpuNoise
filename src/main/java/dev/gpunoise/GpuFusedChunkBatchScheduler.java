package dev.gpunoise;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

/** Coalesces per-chunk fused terrain requests before they reach Vulkan. */
final class GpuFusedChunkBatchScheduler implements AutoCloseable {
    private static final int MAX_INPUT_SAMPLES = PersistentVulkanNormalNoiseEngine.CAPACITY;
    private static final int MAX_OUTPUT_SAMPLES = PersistentVulkanNormalNoiseEngine.CAPACITY;
    private final BlockingQueue<Pending> queue = new ArrayBlockingQueue<>(256);
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "GPU fused chunk batcher");
        t.setDaemon(true);
        return t;
    });
    private volatile boolean closed;

    GpuFusedChunkBatchScheduler() { worker.execute(this::run); }

    CompletionStage<GpuFusedResult> submit(int[] x, int[] y, int[] z, ByteBuffer metadata,
                                    int blocksPerChunk) {
        CompletableFuture<GpuFusedResult> result = new CompletableFuture<>();
        if (closed || !queue.offer(new Pending(x, y, z, metadata, blocksPerChunk, result))) {
            result.completeExceptionally(new IllegalStateException("GPU fused chunk queue is full"));
        }
        return result;
    }

    private void run() {
        try {
            while (!closed) {
                List<Pending> batch = new ArrayList<>();
                Pending first = queue.take();
                batch.add(first);
                int input = first.x.length;
                int output = first.blocksPerChunk * GpuTerrainRuntime.terrainOutputRootCount();
                // A short coalescing window catches chunks submitted by the
                // parallel generator without adding a full tick of latency.
                LockSupport.parkNanos(1_000_000L);
                while (batch.size() < 32) {
                    Pending next = queue.peek();
                    if (next == null || next.blocksPerChunk != first.blocksPerChunk
                            || input + next.x.length > MAX_INPUT_SAMPLES
                            || output + next.blocksPerChunk * GpuTerrainRuntime.terrainOutputRootCount() > MAX_OUTPUT_SAMPLES) break;
                    queue.poll();
                    batch.add(next);
                    input += next.x.length;
                    output += next.blocksPerChunk * GpuTerrainRuntime.terrainOutputRootCount();
                }
                dispatch(batch, input, output);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally {
            Pending item;
            while ((item = queue.poll()) != null)
                item.result.completeExceptionally(new IllegalStateException("GPU fused chunk batcher stopped"));
        }
    }

    private void dispatch(List<Pending> batch, int inputCount, int outputCount) {
        try {
            int[] x = new int[inputCount], y = new int[inputCount], z = new int[inputCount];
            ByteBuffer metadata = ByteBuffer.allocateDirect(batch.size() * GpuInterpolationBatch.METADATA_BYTES)
                    .order(ByteOrder.nativeOrder());
            int inputOffset = 0, outputOffset = 0;
            for (int i = 0; i < batch.size(); i++) {
                Pending item = batch.get(i);
                System.arraycopy(item.x, 0, x, inputOffset, item.x.length);
                System.arraycopy(item.y, 0, y, inputOffset, item.y.length);
                System.arraycopy(item.z, 0, z, inputOffset, item.z.length);
                ByteBuffer source = item.metadata.duplicate().order(ByteOrder.nativeOrder());
                source.rewind();
                // The source metadata describes one chunk. Rewrite only the
                // offsets; dimensions remain valid per chunk.
                metadata.putInt(inputOffset + source.getInt());
                metadata.putInt(outputOffset * GpuTerrainRuntime.terrainOutputRootCount() + source.getInt());
                for (int field = 2; field < 8; field++) metadata.putInt(source.getInt());
                inputOffset += item.x.length;
                outputOffset += item.blocksPerChunk;
            }
            metadata.flip();
            GpuTerrainRuntime.submitFusedDirect(x, y, z, metadata, batch.size(), batch.get(0).blocksPerChunk)
                    .whenComplete((values, failure) -> {
                        int resultInputOffset = 0, blockOffset = 0;
                        for (Pending item : batch) {
                            int count = item.blocksPerChunk;
                            int roots = values == null ? 0 : values.roots();
                            int perItemOutput = count * roots;
                            if (failure != null) item.result.completeExceptionally(failure);
                            else {
                                item.result.complete(new GpuFusedResult(
                                        java.util.Arrays.copyOfRange(values.blocks(), blockOffset, blockOffset + perItemOutput),
                                        null,
                                        roots));
                            }
                            resultInputOffset += item.x.length;
                            blockOffset += perItemOutput;
                        }
                    });
        } catch (Throwable failure) {
            for (Pending item : batch) item.result.completeExceptionally(failure);
        }
    }

    @Override public void close() {
        closed = true;
        worker.shutdownNow();
        try { worker.awaitTermination(5, TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }

    private record Pending(int[] x, int[] y, int[] z, ByteBuffer metadata,
                           int blocksPerChunk, CompletableFuture<GpuFusedResult> result) {}
}
