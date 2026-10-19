package dev.gpunoise;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

/**
 * Groups single-chunk terrain requests into batches of at most 100 chunks.
 * A real Vulkan provider must be supplied; this class never computes replacement values.
 */
final class GpuTerrainBatchScheduler implements AutoCloseable {
    private static final long COALESCE_NANOS = 1_000_000L;
    // The terrain graph evaluates hundreds of nodes per sample. Keeping these
    // dispatches bounded prevents a large speculative prefetch from starving
    // the worldgen executor or triggering a driver watchdog timeout.
    private static final long MAX_TERRAIN_DISPATCH_SAMPLES = PersistentVulkanNormalNoiseEngine.CAPACITY;
    private final TerrainNoiseApi.Provider provider;
    private final ExecutorService executor;
    // A chunk is currently represented by 12 exact vertical slabs. Leave
    // enough room for several coordinator batches without rejecting futures.
    private final BlockingQueue<Pending> pending = new ArrayBlockingQueue<>(1024);

    GpuTerrainBatchScheduler(TerrainNoiseApi.Provider provider) {
        this.provider = Objects.requireNonNull(provider, "provider");
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "GPU Noise Batch Scheduler");
            thread.setDaemon(true);
            return thread;
        });
        this.executor.execute(this::runLoop);
    }

    CompletionStage<TerrainNoiseApi.Result> submit(TerrainNoiseApi.Request request) {
        Objects.requireNonNull(request, "request");
        if (request.chunks().size() > TerrainNoiseApi.MAX_CHUNKS_PER_BATCH) {
            return CompletableFuture.failedStage(new IllegalArgumentException("request exceeds 100 chunks"));
        }
        long samples = (long) request.chunks().size() * request.samplesPerChunk();
        if (samples > MAX_TERRAIN_DISPATCH_SAMPLES) {
            return CompletableFuture.failedStage(new IllegalArgumentException("request exceeds terrain dispatch capacity"));
        }
        CompletableFuture<TerrainNoiseApi.Result> future = new CompletableFuture<>();
        if (!pending.offer(new Pending(request, future))) {
            future.completeExceptionally(new IllegalStateException("GPU terrain queue is full"));
        }
        return future;
    }

    private void runLoop() {
        Pending deferred = null;
        try {
            while (!Thread.currentThread().isInterrupted()) {
                Pending first;
                if (deferred != null) {
                    first = deferred;
                    deferred = null;
                } else {
                    first = pending.take();
                }
                List<Pending> batch = new ArrayList<>();
                batch.add(first);
                int chunkCount = first.request.chunks().size();
                long sampleCount = (long) chunkCount * first.request.samplesPerChunk();
                LockSupport.parkNanos(COALESCE_NANOS);
                while (chunkCount < TerrainNoiseApi.MAX_CHUNKS_PER_BATCH) {
                    Pending next = pending.poll();
                    if (next == null) break;
                    int nextCount = next.request.chunks().size();
                    long nextSamples = (long) nextCount * next.request.samplesPerChunk();
                    if (chunkCount + nextCount > TerrainNoiseApi.MAX_CHUNKS_PER_BATCH
                            || sampleCount + nextSamples > MAX_TERRAIN_DISPATCH_SAMPLES) {
                        deferred = next;
                        break;
                    }
                    batch.add(next);
                    chunkCount += nextCount;
                    sampleCount += nextSamples;
                }
                dispatch(batch);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            if (deferred != null) deferred.future.completeExceptionally(
                    new IllegalStateException("GPU terrain scheduler stopped before dispatch", interrupted));
        } catch (Throwable failure) {
            if (deferred != null) deferred.future.completeExceptionally(failure);
            Pending item;
            while ((item = pending.poll()) != null) item.future.completeExceptionally(failure);
        }
    }

    private void dispatch(List<Pending> batch) {
        Pending first = batch.get(0);
        int samplesPerChunk = first.request.samplesPerChunk();
        int outputSamplesPerChunk = first.request.outputSamplesPerChunk();
        long seed = first.request.worldSeed();
        int totalChunks = 0;
        for (Pending item : batch) {
            if (item.request.worldSeed() != seed || item.request.samplesPerChunk() != samplesPerChunk
                    || item.request.outputSamplesPerChunk() != outputSamplesPerChunk) {
                item.future.completeExceptionally(new IllegalArgumentException("cannot combine different seed/sample layouts"));
            } else {
                totalChunks += item.request.chunks().size();
            }
        }
        if (totalChunks == 0) return;

        List<TerrainNoiseApi.Chunk> chunks = new ArrayList<>(totalChunks);
        int[] x = new int[totalChunks * samplesPerChunk];
        int[] y = new int[x.length];
        int[] z = new int[x.length];
        int chunkOffset = 0;
        for (Pending item : batch) {
            if (item.future.isDone()) continue;
            chunks.addAll(item.request.chunks());
            int sampleOffset = chunkOffset * samplesPerChunk;
            System.arraycopy(item.request.sampleXRaw(), 0, x, sampleOffset, item.request.sampleXRaw().length);
            System.arraycopy(item.request.sampleYRaw(), 0, y, sampleOffset, item.request.sampleYRaw().length);
            System.arraycopy(item.request.sampleZRaw(), 0, z, sampleOffset, item.request.sampleZRaw().length);
            chunkOffset += item.request.chunks().size();
        }
        TerrainNoiseApi.Request merged = new TerrainNoiseApi.Request(seed, chunks, samplesPerChunk, x, y, z);
        try {
            provider.submit(merged).whenComplete((result, failure) -> {
                if (failure != null) {
                    for (Pending item : batch) if (!item.future.isDone()) item.future.completeExceptionally(failure);
                    return;
                }
            int offset = 0;
            for (Pending item : batch) {
                if (item.future.isDone()) continue;
                int count = item.request.chunks().size() * outputSamplesPerChunk;
                item.future.complete(slice(result, offset, count, item.request));
                offset += count;
            }
            });
        } catch (Throwable failure) {
            for (Pending item : batch) if (!item.future.isDone()) item.future.completeExceptionally(failure);
        }
    }

    private static TerrainNoiseApi.Result slice(TerrainNoiseApi.Result source, int offset, int count, TerrainNoiseApi.Request request) {
        return TerrainNoiseApi.Result.owned(request,
                slice(source.terrainDensityAtArray(), offset, count), slice(source.caveDensityRaw(), offset, count),
                slice(source.aquiferPressureRaw(), offset, count), slice(source.continentalnessRaw(), offset, count),
                slice(source.erosionRaw(), offset, count), slice(source.weirdnessRaw(), offset, count),
                slice(source.temperatureRaw(), offset, count), slice(source.humidityRaw(), offset, count));
    }

    private static float[] slice(float[] values, int offset, int count) {
        float[] result = new float[count];
        System.arraycopy(values, offset, result, 0, count);
        return result;
    }

    private record Pending(TerrainNoiseApi.Request request, CompletableFuture<TerrainNoiseApi.Result> future) {}

    /** Creates a single-chunk request for the scheduler's batch collector. */
    static TerrainNoiseApi.Request singleChunk(long seed, int chunkX, int chunkZ, int samplesPerChunk,
                                               int[] sampleX, int[] sampleY, int[] sampleZ) {
        return new TerrainNoiseApi.Request(seed, List.of(new TerrainNoiseApi.Chunk(chunkX, chunkZ)),
                samplesPerChunk, sampleX, sampleY, sampleZ);
    }

    @Override
    public void close() {
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) executor.shutdownNow();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }
}
