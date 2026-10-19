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
    // Terrain callers block on their futures after submitting. Poll more
    // frequently while a graph dispatch is in flight, then back off while idle
    // so low handoff latency does not become constant CPU wakeup overhead.
    private static final long COMPLETION_POLL_MICROS = 250L;
    private static final long IDLE_POLL_MILLIS = 1L;
    private final PersistentVulkanNormalNoiseEngine[] slots;
    private final AtomicInteger next = new AtomicInteger();
    /** Requests wait here, not on a worldgen thread, while the shared slot is busy. */
    private final ConcurrentLinkedQueue<GraphRootsRequest> pendingGraphRoots = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<GraphRootsGridRequest> pendingGraphGrids = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<AquiferNearestRequest> pendingAquiferNearest = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<AquiferSubstanceRequest> pendingAquiferSubstance = new ConcurrentLinkedQueue<>();
    /** The auxiliary kernel shares the slot command buffer with async graph work. */
    private boolean aquiferKernelInFlight;
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
        // Fence checks are non-blocking. Polling is frequent only while useful;
        // the executor never waits synchronously for a terrain submission.
        completion.execute(this::pollAndDrain);
    }

    private void pollAndDrain() {
        if (completion.isShutdown()) return;
        long delay = TimeUnit.MILLISECONDS.toMicros(IDLE_POLL_MILLIS);
        try {
            boolean graphInFlight = false;
            for (PersistentVulkanNormalNoiseEngine slot : slots) {
                if (slot == null) continue;
                slot.pollGraphRootsCompletion();
                graphInFlight |= slot.graphRootsInFlight();
            }
            drainGraphRoots();
            // drainGraphRoots may have started the next queued submission.
            for (PersistentVulkanNormalNoiseEngine slot : slots) {
                if (slot != null) graphInFlight |= slot.graphRootsInFlight();
            }
            if (graphInFlight) delay = COMPLETION_POLL_MICROS;
        } catch (Throwable failure) {
            GpuNoise.LOGGER.error("GPU Vulkan completion poll failed; polling will resume", failure);
        } finally {
            try {
                if (!completion.isShutdown()) completion.schedule(this::pollAndDrain,
                        delay, TimeUnit.MICROSECONDS);
            } catch (java.util.concurrent.RejectedExecutionException ignored) {
                // Shutdown raced the next scheduled poll.
            }
        }
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
            if (slot == null || slot.graphRootsInFlight() || aquiferKernelInFlight) continue;
            GraphRootsRequest request = pendingGraphRoots.poll();
            if (request == null) {
                GraphRootsGridRequest grid = pendingGraphGrids.poll();
                if (grid == null) {
                    AquiferNearestRequest nearest = pendingAquiferNearest.poll();
                    AquiferSubstanceRequest substance = nearest == null
                            ? pendingAquiferSubstance.poll() : null;
                    if (nearest == null && substance == null) return;
                    aquiferKernelInFlight = true;
                    try {
                        completion.execute(() -> {
                            try {
                                if (nearest != null) {
                                    nearest.future.complete(slot.submitAquiferNearestThree(
                                            nearest.records, nearest.count));
                                } else {
                                    substance.future.complete(slot.submitAquiferSubstance(
                                            substance.records, substance.count));
                                }
                            } catch (Throwable failure) {
                                if (nearest != null) nearest.future.completeExceptionally(new IllegalStateException(
                                        "GPU ring aquifer nearest-three dispatch failed", failure));
                                else substance.future.completeExceptionally(new IllegalStateException(
                                        "GPU ring aquifer substance dispatch failed", failure));
                            } finally {
                                synchronized (VulkanSubmissionRing.this) {
                                    aquiferKernelInFlight = false;
                                    drainGraphRoots();
                                }
                            }
                        });
                    } catch (RuntimeException stopped) {
                        aquiferKernelInFlight = false;
                        if (nearest != null) nearest.future.completeExceptionally(stopped);
                        else substance.future.completeExceptionally(stopped);
                    }
                    return;
                }
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
        return submitInterpolation(corners, metadata, chunkCount, blocksPerChunk, 1, 1);
    }

    CompletableFuture<float[]> submitInterpolation(float[] corners, ByteBuffer metadata,
                                                    int chunkCount, int blocksPerChunk,
                                                    int rootStride, int outputStride) {
        PersistentVulkanNormalNoiseEngine slot = slots[Math.floorMod(next.getAndIncrement(), slots.length)];
        return CompletableFuture.supplyAsync(() -> {
            try {
                return slot.submitInterpolation(corners, metadata, chunkCount, blocksPerChunk,
                        rootStride, outputStride);
            } catch (Exception failure) {
                throw new IllegalStateException("GPU ring interpolation dispatch failed", failure);
            }
        }, completion);
    }

    CompletableFuture<float[]> submitAquiferPressure(ByteBuffer records, int count) {
        PersistentVulkanNormalNoiseEngine slot = slots[Math.floorMod(next.getAndIncrement(), slots.length)];
        ByteBuffer input = records.duplicate();
        return CompletableFuture.supplyAsync(() -> {
            try {
                return slot.submitAquiferPressure(input, count);
            } catch (Exception failure) {
                throw new IllegalStateException("GPU aquifer pressure dispatch failed", failure);
            }
        }, completion);
    }

    CompletableFuture<int[]> submitAquiferNearestThree(ByteBuffer records, int count) {
        CompletableFuture<int[]> future = new CompletableFuture<>();
        pendingAquiferNearest.add(new AquiferNearestRequest(records.duplicate(), count, future));
        drainGraphRoots();
        return future;
    }

    CompletableFuture<int[]> submitAquiferSubstance(ByteBuffer records, int count) {
        CompletableFuture<int[]> future = new CompletableFuture<>();
        pendingAquiferSubstance.add(new AquiferSubstanceRequest(records.duplicate(), count, future));
        drainGraphRoots();
        return future;
    }

    CompletableFuture<GpuFusedResult> submitGraphInterpolated(GpuDensityGraph graph, float[] x, float[] y, float[] z,
                                                        ByteBuffer metadata, int chunkCount, int blocksPerChunk) {
        return submitGraphInterpolated(graph, x, y, z, x.length, metadata, chunkCount, blocksPerChunk);
    }

    CompletableFuture<GpuTerrainBatchResult> submitGraphGridInterpolated(ByteBuffer gridMetadata, int chunkCount,
                                                                           int samplesPerChunk, ByteBuffer outputMetadata,
                                                                           int blocksPerChunk) {
        return submitGraphGridInterpolated(GpuTerrainRuntime.currentTerrainGraph(), gridMetadata,
                chunkCount, samplesPerChunk, outputMetadata, blocksPerChunk);
    }

    CompletableFuture<GpuTerrainBatchResult> submitGraphGridInterpolated(GpuDensityGraph graph,
                                                                           ByteBuffer gridMetadata,
                                                                           int chunkCount, int samplesPerChunk,
                                                                           ByteBuffer outputMetadata,
                                                                           int blocksPerChunk) {
        int[] outputRoots = new int[graph.routerRootCount()];
        for (int i = 0; i < outputRoots.length; i++) outputRoots[i] = i;
        return submitGraphGridInterpolated(graph, gridMetadata, chunkCount, samplesPerChunk,
                outputMetadata, blocksPerChunk, outputRoots);
    }

    CompletableFuture<GpuTerrainBatchResult> submitGraphGridInterpolated(ByteBuffer gridMetadata,
                                                                           int chunkCount,
                                                                           int samplesPerChunk,
                                                                           ByteBuffer outputMetadata,
                                                                           int blocksPerChunk,
                                                                           int[] outputRootIndexes) {
        return submitGraphGridInterpolated(GpuTerrainRuntime.currentTerrainGraph(), gridMetadata,
                chunkCount, samplesPerChunk, outputMetadata, blocksPerChunk, outputRootIndexes);
    }

    CompletableFuture<GpuTerrainBatchResult> submitGraphGridInterpolated(GpuDensityGraph graph,
                                                                           ByteBuffer gridMetadata,
                                                                           int chunkCount, int samplesPerChunk,
                                                                           ByteBuffer outputMetadata,
                                                                           int blocksPerChunk,
                                                                           int[] outputRootIndexes) {
        PersistentVulkanNormalNoiseEngine slot = slots[Math.floorMod(next.getAndIncrement(), slots.length)];
        return CompletableFuture.supplyAsync(() -> {
            try {
                return slot.submitGraphGridInterpolated(graph, gridMetadata, chunkCount,
                        samplesPerChunk, outputMetadata, blocksPerChunk, outputRootIndexes);
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
        IllegalStateException stopped = new IllegalStateException("GPU submission ring stopped");
        GraphRootsRequest graphRequest;
        while ((graphRequest = pendingGraphRoots.poll()) != null)
            graphRequest.future.completeExceptionally(stopped);
        GraphRootsGridRequest gridRequest;
        while ((gridRequest = pendingGraphGrids.poll()) != null)
            gridRequest.future.completeExceptionally(stopped);
        AquiferNearestRequest request;
        while ((request = pendingAquiferNearest.poll()) != null)
            request.future.completeExceptionally(stopped);
        AquiferSubstanceRequest substanceRequest;
        while ((substanceRequest = pendingAquiferSubstance.poll()) != null)
            substanceRequest.future.completeExceptionally(stopped);
        for (int i = slots.length - 1; i >= 0; i--) {
            if (slots[i] != null) slots[i].close();
        }
    }

    private record GraphRootsRequest(GpuDensityGraph graph, float[] x, float[] y, float[] z,
                                     int sampleCount, CompletableFuture<float[]> future) {}
    private record GraphRootsGridRequest(GpuDensityGraph graph, ByteBuffer metadata, int chunkCount, int samplesPerChunk,
                                         CompletableFuture<float[]> future) {}
    private record AquiferNearestRequest(ByteBuffer records, int count, CompletableFuture<int[]> future) {}
    private record AquiferSubstanceRequest(ByteBuffer records, int count, CompletableFuture<int[]> future) {}
}
