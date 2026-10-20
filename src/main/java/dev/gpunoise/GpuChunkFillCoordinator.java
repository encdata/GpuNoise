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
import java.util.concurrent.locks.LockSupport;

import net.minecraft.Util;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;

public final class GpuChunkFillCoordinator implements AutoCloseable {
    // A cell-grid chunk uses roughly 2,425 terrain samples. The Vulkan engine
    // supports 262,144 samples, so the coordinator can safely prepare a large
    // batch instead of starving the GPU with eight-chunk submissions.
    private static final int MAX_CHUNKS = 100;
    private final BlockingQueue<Pending> pending = new ArrayBlockingQueue<>(256);
    // Reuse Minecraft's worldgen pool. A private pool oversubscribes the CPU
    // because vanilla already schedules neighboring chunk steps there.
    private final ExecutorService fillExecutor = Util.backgroundExecutor();
    // Cache graph translation/input packing is separately capped by the cache
    // registry. Give those bounded tasks their own workers so builds waiting
    // for a cache permit cannot occupy all Minecraft fill workers.
    private final ExecutorService cachePreparationExecutor = Executors.newFixedThreadPool(
            GpuTerrainAreaRegistry.maxConcurrentCacheBuilds(), r -> {
                Thread t = new Thread(r, "GPU terrain cache preparation");
                t.setDaemon(true);
                return t;
            });
    private final ExecutorService collector = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "GPU Chunk Fill Coordinator");
        t.setDaemon(true);
        return t;
    });

    GpuChunkFillCoordinator() { collector.execute(this::runLoop); }

    CompletionStage<ChunkAccess> submit(Work work) {
        CompletableFuture<ChunkAccess> result = new CompletableFuture<>();
        if (!pending.offer(new Pending(Objects.requireNonNull(work), result)))
            result.completeExceptionally(new IllegalStateException("GPU chunk fill queue is full"));
        return result;
    }

    private void runLoop() {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                List<Pending> batch = new ArrayList<>(MAX_CHUNKS);
                batch.add(pending.take());
                LockSupport.parkNanos(2_000_000L);
                while (batch.size() < MAX_CHUNKS) {
                    Pending next = pending.poll();
                    if (next == null) break;
                    batch.add(next);
                }
                prepare(batch);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void prepare(List<Pending> batch) {
        for (Pending item : batch) {
            try {
                CompletableFuture.runAsync(() -> prepareCache(item), cachePreparationExecutor)
                        .whenComplete((ignored, failure) -> {
                            if (failure != null) {
                                item.result.completeExceptionally(failure);
                            } else {
                                startReadyFill(item);
                            }
                        });
            } catch (RuntimeException rejected) {
                item.result.completeExceptionally(rejected);
            }
        }

        // The collector must not build strict Java inputs serially: those
        // builders can be substantial for modded density graphs, and serial
        // preparation delays every later terrain submission in the batch.
        // Cache claims have their own bounded build semaphore, so this fans
        // out preparation without creating unbounded simultaneous builders.
        // Each item continues independently when its own cache is prepared.
    }

    private void prepareCache(Pending item) {
        if (item.result.isDone()) return;
        long preparationStart = GpuTerrainProfiler.beginTerrainCachePreparation();
        try {
            int x = item.work.chunk().getPos().getMinBlockX();
            int z = item.work.chunk().getPos().getMinBlockZ();
            if (GpuTerrainRuntime.gpuOnlyWorldgenAvailable())
                GpuChunkDensityCache.prefetchStrict(x, z, item.work.settings(), item.work.aquiferRandom());
            else GpuChunkDensityCache.prefetch(x, z, item.work.settings());
            item.cache = GpuChunkDensityCache.takePrefetched(x, z);
            if (item.cache == null) {
                throw new IllegalStateException("GPU terrain cache was not available after preparation");
            }
            if (item.result.isCancelled()) {
                GpuTerrainRuntime.releaseGpuCache(item.cache);
                item.cache = null;
            }
        } catch (Throwable error) {
            GpuTerrainRuntime.releaseGpuCache(item.cache);
            item.cache = null;
            item.result.completeExceptionally(error);
        } finally {
            GpuTerrainProfiler.recordTerrainCachePreparation(preparationStart);
        }
    }

    private void startReadyFill(Pending item) {
        // Do not gate fills on the slowest GPU request. Each cache owns its
        // completion future, so its chunk starts as soon as its own slab is
        // ready, even while other cache preparations or dispatches continue.
        GpuChunkDensityCache cache = item.cache;
        if (cache == null) {
            if (item.result.isDone()) return;
            item.result.completeExceptionally(new IllegalStateException(
                    "GPU terrain cache preparation failed without a reported cause"));
            return;
        }
        if (item.result.isDone()) {
            GpuTerrainRuntime.releaseGpuCache(cache);
            return;
        }
        cache.ready().whenComplete((ignored, readinessFailure) -> {
            if (item.result.isCancelled()) {
                GpuTerrainRuntime.releaseGpuCache(cache);
                return;
            }
            if (readinessFailure != null) {
                GpuTerrainRuntime.releaseGpuCache(cache);
                item.result.completeExceptionally(readinessFailure);
                return;
            }
            try {
                CompletableFuture.supplyAsync(() -> {
                            GpuTerrainRuntime.enterGpuDensityScope();
                            try {
                                return item.work.fill(cache);
                            } finally {
                                GpuTerrainRuntime.leaveGpuDensityScope();
                            }
                        }, fillExecutor)
                        .whenComplete((chunk, error) -> {
                            GpuTerrainRuntime.releaseGpuCache(cache);
                            if (error != null) item.result.completeExceptionally(error);
                            else item.result.complete(chunk);
                        });
            } catch (RuntimeException rejected) {
                GpuTerrainRuntime.releaseGpuCache(cache);
                item.result.completeExceptionally(rejected);
            }
        });
    }

    public interface Work {
        ChunkAccess chunk();
        NoiseSettings settings();
        PositionalRandomFactory aquiferRandom();
        ChunkAccess fill(GpuChunkDensityCache cache);
    }

    private static final class Pending {
        final Work work;
        final CompletableFuture<ChunkAccess> result;
        GpuChunkDensityCache cache;
        Pending(Work work, CompletableFuture<ChunkAccess> result) { this.work = work; this.result = result; }
    }

    @Override public void close() {
        collector.shutdownNow();
        cachePreparationExecutor.shutdownNow();
        // fillExecutor is owned by Minecraft's Util background executor.
    }
}
