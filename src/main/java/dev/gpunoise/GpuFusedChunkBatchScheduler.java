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

/** Coalesces per-chunk fused terrain requests before they reach Vulkan. */
final class GpuFusedChunkBatchScheduler implements AutoCloseable {
    private static final int MAX_INPUT_SAMPLES = PersistentVulkanNormalNoiseEngine.CAPACITY;
    private static final int MAX_OUTPUT_SAMPLES = PersistentVulkanNormalNoiseEngine.INTERPOLATION_OUTPUT_FLOATS;
    private static final int MAX_CHUNKS_PER_BATCH = 100;
    private final BlockingQueue<Pending> queue = new ArrayBlockingQueue<>(256);
    // There is currently one safe Vulkan engine. Keep exactly one matching
    // host-side slab: it prevents per-dispatch allocation without allowing
    // submissions to overwrite buffers still owned by that engine.
    private final BlockingQueue<BatchBuffers> buffers = new ArrayBlockingQueue<>(1);
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "GPU fused chunk batcher");
        t.setDaemon(true);
        return t;
    });
    private volatile boolean closed;

    GpuFusedChunkBatchScheduler() {
        buffers.add(new BatchBuffers());
        worker.execute(this::run);
    }

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
                long output = (long) first.blocksPerChunk * GpuTerrainRuntime.terrainOutputRootCount();
                while (batch.size() < MAX_CHUNKS_PER_BATCH) {
                    Pending next = queue.peek();
                    if (next == null || next.blocksPerChunk != first.blocksPerChunk
                            || (long) input + next.x.length > MAX_INPUT_SAMPLES
                            || output + (long) next.blocksPerChunk * GpuTerrainRuntime.terrainOutputRootCount() > MAX_OUTPUT_SAMPLES) break;
                    queue.poll();
                    batch.add(next);
                    input += next.x.length;
                    output += (long) next.blocksPerChunk * GpuTerrainRuntime.terrainOutputRootCount();
                }
                dispatch(batch, input);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally {
            Pending item;
            while ((item = queue.poll()) != null)
                item.result.completeExceptionally(new IllegalStateException("GPU fused chunk batcher stopped"));
        }
    }

    private void dispatch(List<Pending> batch, int inputCount) {
        BatchBuffers buffers = null;
        try {
            buffers = this.buffers.take();
            ByteBuffer metadata = buffers.metadata;
            metadata.clear();
            int inputOffset = 0, outputOffset = 0;
            for (int i = 0; i < batch.size(); i++) {
                Pending item = batch.get(i);
                for (int sample = 0; sample < item.x.length; sample++) {
                    buffers.x[inputOffset + sample] = item.x[sample];
                    buffers.y[inputOffset + sample] = item.y[sample];
                    buffers.z[inputOffset + sample] = item.z[sample];
                }
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
            BatchBuffers submitted = buffers;
            GpuTerrainRuntime.submitFusedDirect(submitted.x, submitted.y, submitted.z, inputCount,
                            metadata, batch.size(), batch.get(0).blocksPerChunk)
                    .whenComplete((values, failure) -> {
                        int blockOffset = 0;
                        for (Pending item : batch) {
                            int count = item.blocksPerChunk;
                            int roots = values == null ? 0 : values.roots();
                            int perItemOutput = count * roots;
                            if (failure != null) item.result.completeExceptionally(failure);
                            else {
                                item.result.complete(new GpuFusedResult(
                                        values.batchOutput(), blockOffset, perItemOutput, roots));
                            }
                            blockOffset += perItemOutput;
                        }
                        this.buffers.offer(submitted);
                    });
        } catch (Throwable failure) {
            for (Pending item : batch) item.result.completeExceptionally(failure);
            if (buffers != null) this.buffers.offer(buffers);
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

    private static final class BatchBuffers {
        final float[] x = new float[MAX_INPUT_SAMPLES];
        final float[] y = new float[MAX_INPUT_SAMPLES];
        final float[] z = new float[MAX_INPUT_SAMPLES];
        final ByteBuffer metadata = ByteBuffer.allocateDirect(MAX_CHUNKS_PER_BATCH * GpuInterpolationBatch.METADATA_BYTES)
                .order(ByteOrder.nativeOrder());
    }
}
