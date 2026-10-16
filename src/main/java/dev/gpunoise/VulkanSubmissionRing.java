package dev.gpunoise;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.nio.ByteBuffer;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Safe in-flight submission ring. Each slot owns its Vulkan buffers, command
 * buffer, and fence through a separate persistent engine, so submissions never
 * overwrite resources still used by another dispatch.
 */
final class VulkanSubmissionRing implements AutoCloseable {
    // Each independent engine owns large persistent Vulkan buffers. Four
    // engines used several gigabytes of committed JVM/direct memory on a
    // normal 6 GB VRAM laptop and could freeze the desktop. Keep one engine
    // until slots share one device allocation with buffer offsets.
    private static final int SLOT_COUNT = 1;
    private final PersistentVulkanNormalNoiseEngine[] slots;
    private final AtomicInteger next = new AtomicInteger();
    /** Requests wait here, not on a worldgen thread, while the shared slot is busy. */
    private final ConcurrentLinkedQueue<GraphRootsRequest> pendingGraphRoots = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<GraphRootsGridRequest> pendingGraphGrids = new ConcurrentLinkedQueue<>();
    private final ScheduledExecutorService completion = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "GPU Vulkan completion");
        thread.setDaemon(true);
        return thread;
    });

    VulkanSubmissionRing(GpuDevice device) throws Exception {
        slots = new PersistentVulkanNormalNoiseEngine[SLOT_COUNT];
        int created = 0;
        try {
            slots[0] = new PersistentVulkanNormalNoiseEngine(device);
            created = 1;
            for (; created < slots.length; created++) slots[created] = new PersistentVulkanNormalNoiseEngine(slots[0]);
        } catch (Exception failure) {
            for (int i = 0; i < created; i++) slots[i].close();
            throw failure;
        }
        // Polling a fence is non-blocking. This executor only completes
        // futures; it never waits for a terrain submission.
        completion.scheduleWithFixedDelay(() -> {
            for (PersistentVulkanNormalNoiseEngine slot : slots) {
                if (slot != null) slot.pollGraphRootsCompletion();
            }
            drainGraphRoots();
        }, 0, 1, TimeUnit.MILLISECONDS);
    }

    PersistentVulkanNormalNoiseEngine primary() { return slots[0]; }

    CompletableFuture<float[]> submitGraphRoots(GpuDensityGraph graph, float[] x, float[] y, float[] z) {
        return submitGraphRoots(graph, x, y, z, x.length);
    }

    CompletableFuture<float[]> submitGraphRoots(GpuDensityGraph graph, float[] x, float[] y, float[] z, int sampleCount) {
        CompletableFuture<float[]> future = new CompletableFuture<>();
        pendingGraphRoots.add(new GraphRootsRequest(graph, x, y, z, sampleCount, future));
        drainGraphRoots();
        return future;
    }

    CompletableFuture<float[]> submitGraphRootsGrid(ByteBuffer metadata, int chunkCount, int samplesPerChunk) {
        return submitGraphRootsGrid(GpuTerrainRuntime.currentTerrainGraph(), metadata, chunkCount, samplesPerChunk);
    }

    CompletableFuture<float[]> submitGraphRootsGrid(GpuDensityGraph graph, ByteBuffer metadata,
                                                      int chunkCount, int samplesPerChunk) {
        if (graph == null) return CompletableFuture.failedFuture(
                new IllegalStateException("GPU terrain graph is not installed"));
        CompletableFuture<float[]> future = new CompletableFuture<>();
        pendingGraphGrids.add(new GraphRootsGridRequest(graph, metadata, chunkCount, samplesPerChunk, future));
        drainGraphRoots();
        return future;
    }

    /** Starts at most one request per lightweight slot; never rejects work just because GPU is busy. */
    private synchronized void drainGraphRoots() {
        for (PersistentVulkanNormalNoiseEngine slot : slots) {
            if (slot == null || slot.graphRootsInFlight()) continue;
            GraphRootsRequest request = pendingGraphRoots.poll();
            if (request == null) {
                GraphRootsGridRequest grid = pendingGraphGrids.poll();
                if (grid == null) return;
                try {
                    slot.submitGraphRootsGridAsync(grid.graph, grid.metadata, grid.chunkCount, grid.samplesPerChunk)
                            .whenComplete((values, failure) -> {
                                if (failure != null) grid.future.completeExceptionally(failure);
                                else grid.future.complete(values);
                            });
                } catch (Throwable failure) {
                    grid.future.completeExceptionally(new IllegalStateException("GPU ring grid dispatch failed", failure));
                }
                continue;
            }
            try {
                slot.submitGraphRootsAsync(request.graph, request.x, request.y, request.z, request.sampleCount)
                        .whenComplete((values, failure) -> {
                            if (failure != null) request.future.completeExceptionally(failure);
                            else request.future.complete(values);
                        });
            } catch (Throwable failure) {
                request.future.completeExceptionally(new IllegalStateException("GPU ring graph dispatch failed", failure));
            }
        }
    }

    CompletableFuture<float[]> submitNoise(GpuGlobalNoiseBatch.Packed batch) {
        PersistentVulkanNormalNoiseEngine slot = slots[Math.floorMod(next.getAndIncrement(), slots.length)];
        return CompletableFuture.supplyAsync(() -> {
            try {
                return slot.submit(batch);
            } catch (Exception failure) {
                throw new IllegalStateException("GPU ring noise dispatch failed", failure);
            }
        }, completion);
    }

    CompletableFuture<float[]> submitInterpolation(float[] corners, ByteBuffer metadata,
                                                    int chunkCount, int blocksPerChunk) {
        PersistentVulkanNormalNoiseEngine slot = slots[Math.floorMod(next.getAndIncrement(), slots.length)];
        return CompletableFuture.supplyAsync(() -> {
            try {
                return slot.submitInterpolation(corners, metadata, chunkCount, blocksPerChunk);
            } catch (Exception failure) {
                throw new IllegalStateException("GPU ring interpolation dispatch failed", failure);
            }
        }, completion);
    }

    CompletableFuture<GpuFusedResult> submitGraphInterpolated(GpuDensityGraph graph, float[] x, float[] y, float[] z,
                                                        ByteBuffer metadata, int chunkCount, int blocksPerChunk) {
        return submitGraphInterpolated(graph, x, y, z, x.length, metadata, chunkCount, blocksPerChunk);
    }

    CompletableFuture<GpuTerrainBatchResult> submitGraphGridInterpolated(ByteBuffer gridMetadata, int chunkCount,
                                                                           int samplesPerChunk, ByteBuffer outputMetadata,
                                                                           int blocksPerChunk) {
        PersistentVulkanNormalNoiseEngine slot = slots[Math.floorMod(next.getAndIncrement(), slots.length)];
        return CompletableFuture.supplyAsync(() -> {
            try {
                return slot.submitGraphGridInterpolated(gridMetadata, chunkCount, samplesPerChunk,
                        outputMetadata, blocksPerChunk);
            } catch (Exception failure) {
                throw new IllegalStateException("GPU grid terrain output dispatch failed", failure);
            }
        }, completion);
    }

    CompletableFuture<GpuFusedResult> submitGraphInterpolated(GpuDensityGraph graph, float[] x, float[] y, float[] z,
                                                        int sampleCount, ByteBuffer metadata, int chunkCount, int blocksPerChunk) {
        PersistentVulkanNormalNoiseEngine slot = slots[Math.floorMod(next.getAndIncrement(), slots.length)];
        return CompletableFuture.supplyAsync(() -> {
            try {
                return slot.submitGraphInterpolated(graph, x, y, z, sampleCount, metadata, chunkCount, blocksPerChunk);
            } catch (Exception failure) {
                throw new IllegalStateException("GPU fused terrain dispatch failed", failure);
            }
        }, completion);
    }

    @Override public void close() {
        completion.shutdown();
        try {
            if (!completion.awaitTermination(30, TimeUnit.SECONDS)) completion.shutdownNow();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        for (int i = slots.length - 1; i >= 0; i--) {
            if (slots[i] != null) slots[i].close();
        }
    }

    private record GraphRootsRequest(GpuDensityGraph graph, float[] x, float[] y, float[] z,
                                     int sampleCount, CompletableFuture<float[]> future) {}
    private record GraphRootsGridRequest(GpuDensityGraph graph, ByteBuffer metadata, int chunkCount, int samplesPerChunk,
                                         CompletableFuture<float[]> future) {}
}
