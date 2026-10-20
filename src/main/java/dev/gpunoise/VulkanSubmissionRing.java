package dev.gpunoise;

import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Safe in-flight submission ring. Each slot owns its Vulkan buffers, command
 * buffer, and fence through a separate persistent engine, so submissions never
 * overwrite resources still used by another dispatch.
 */
final class VulkanSubmissionRing implements AutoCloseable {
    private static final int MAX_SLOT_COUNT = 2;
    // Terrain callers block on their futures after submitting. Poll more
    // frequently while a graph dispatch is in flight, then back off while idle
    // so low handoff latency does not become constant CPU wakeup overhead.
    private static final long COMPLETION_POLL_MICROS = 250L;
    private static final long IDLE_POLL_MILLIS = 1L;
    private final PersistentVulkanNormalNoiseEngine[] slots;
    private final AtomicBoolean drainScheduled = new AtomicBoolean();
    /** Requests wait here, not on a worldgen thread, while the shared slot is busy. */
    private final ConcurrentLinkedQueue<GraphRootsRequest> pendingGraphRoots = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<GraphRootsGridRequest> pendingGraphGrids = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<TerrainGridRequest> pendingTerrainGrids = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<AquiferNearestRequest> pendingAquiferNearest = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<AquiferSubstanceRequest> pendingAquiferSubstance = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<AquiferFluidStatusRequest> pendingAquiferFluidStatus = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<AquiferPrefillRequest> pendingAquiferPrefill = new ConcurrentLinkedQueue<>();
    /** Synchronous dispatch requests run on bounded slot workers. */
    private final ConcurrentLinkedQueue<BlockingRequest<?>> pendingBlocking = new ConcurrentLinkedQueue<>();
    /** Synchronous Vulkan APIs run off the completion thread and reserve one slot each. */
    private final Set<PersistentVulkanNormalNoiseEngine> synchronousSlots =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private final ScheduledExecutorService completion = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "GPU Vulkan completion");
        thread.setDaemon(true);
        return thread;
    });
    private final ExecutorService dispatchWorkers = Executors.newFixedThreadPool(MAX_SLOT_COUNT, r -> {
        Thread thread = new Thread(r, "GPU Vulkan dispatch");
        thread.setDaemon(true);
        return thread;
    });

    VulkanSubmissionRing(GpuDevice device) throws Exception {
        int requestedSlots = requestedSlotCount(device);
        slots = new PersistentVulkanNormalNoiseEngine[requestedSlots];
        int created = 0;
        try {
            slots[0] = new PersistentVulkanNormalNoiseEngine(device);
            created = 1;
            for (; created < slots.length; created++) slots[created] = new PersistentVulkanNormalNoiseEngine(slots[0]);
        } catch (Exception failure) {
            for (int i = 0; i < created; i++) slots[i].close();
            throw failure;
        }
        GpuNoise.LOGGER.info("GPU Vulkan submission slots: {} (requested {}); set gpunoise.submissionSlots=1..{} to tune resource use",
                created, requestedSlots, MAX_SLOT_COUNT);
        // Fence checks are non-blocking. Polling is frequent only while useful;
        // the executor never waits synchronously for a terrain submission.
        completion.execute(this::pollAndDrain);
    }

    /** Keep independent command/fence and scratch ownership bounded per physical device. */
    private static int requestedSlotCount(GpuDevice device) {
        String configured = System.getProperty("gpunoise.submissionSlots");
        if (configured != null) {
            try {
                int value = Integer.parseInt(configured.trim());
                if (value >= 1 && value <= MAX_SLOT_COUNT) return value;
                GpuNoise.LOGGER.warn("Ignoring gpunoise.submissionSlots={}; expected 1..{}",
                        configured, MAX_SLOT_COUNT);
            } catch (NumberFormatException invalid) {
                GpuNoise.LOGGER.warn("Ignoring invalid gpunoise.submissionSlots={}", configured);
            }
        }
        // Discrete GPUs usually have enough dedicated memory to keep two
        // terrain batches resident. Integrated and software Vulkan devices
        // stay single-slot by default to avoid competing with the CPU/server.
        return device.deviceType() == org.lwjgl.vulkan.VK10.VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU ? 2 : 1;
    }

    private void pollAndDrain() {
        if (completion.isShutdown()) return;
        long delay = TimeUnit.MILLISECONDS.toMicros(IDLE_POLL_MILLIS);
        try {
            boolean graphInFlight = false;
            for (PersistentVulkanNormalNoiseEngine slot : slots) {
                if (slot == null) continue;
                slot.pollGraphRootsCompletion();
                graphInFlight |= slot.hasInFlightWork();
            }
            drainGraphRoots();
            // drainGraphRoots may have started the next queued submission.
            for (PersistentVulkanNormalNoiseEngine slot : slots) {
                if (slot != null) graphInFlight |= slot.hasInFlightWork();
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

    /** Keep command recording and queue submission off terrain producer threads. */
    private void scheduleDrain() {
        if (!drainScheduled.compareAndSet(false, true)) return;
        try {
            completion.execute(() -> {
                try {
                    drainGraphRoots();
                } finally {
                    drainScheduled.set(false);
                    // Close the enqueue/clear race without busy-spinning while
                    // a fence or synchronous dispatch still owns the slot.
                    if (hasPendingRequests() && canStartQueuedWork()) scheduleDrain();
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException stopped) {
            drainScheduled.set(false);
            failPending(stopped);
        }
    }

    private boolean hasPendingRequests() {
        return !pendingGraphRoots.isEmpty() || !pendingGraphGrids.isEmpty()
                || !pendingAquiferNearest.isEmpty() || !pendingAquiferSubstance.isEmpty()
                || !pendingAquiferFluidStatus.isEmpty() || !pendingAquiferPrefill.isEmpty()
                || !pendingBlocking.isEmpty() || !pendingTerrainGrids.isEmpty();
    }

    private synchronized boolean canStartQueuedWork() {
        for (PersistentVulkanNormalNoiseEngine slot : slots) {
            if (slot != null && !slot.hasInFlightWork() && !synchronousSlots.contains(slot)) return true;
        }
        return false;
    }

    private void failPending(Throwable failure) {
        GraphRootsRequest graph;
        while ((graph = pendingGraphRoots.poll()) != null) graph.future.completeExceptionally(failure);
        GraphRootsGridRequest grid;
        while ((grid = pendingGraphGrids.poll()) != null) grid.future.completeExceptionally(failure);
        TerrainGridRequest terrain;
        while ((terrain = pendingTerrainGrids.poll()) != null) terrain.future.completeExceptionally(failure);
        AquiferNearestRequest nearest;
        while ((nearest = pendingAquiferNearest.poll()) != null) nearest.future.completeExceptionally(failure);
        AquiferSubstanceRequest substance;
        while ((substance = pendingAquiferSubstance.poll()) != null) substance.future.completeExceptionally(failure);
        AquiferFluidStatusRequest fluid;
        while ((fluid = pendingAquiferFluidStatus.poll()) != null) fluid.future.completeExceptionally(failure);
        AquiferPrefillRequest prefill;
        while ((prefill = pendingAquiferPrefill.poll()) != null) prefill.future.completeExceptionally(failure);
        BlockingRequest<?> blocking;
        while ((blocking = pendingBlocking.poll()) != null) blocking.future.completeExceptionally(failure);
    }

    PersistentVulkanNormalNoiseEngine primary() { return slots[0]; }

    CompletableFuture<float[]> submitGraphRoots(GpuDensityGraph graph, float[] x, float[] y, float[] z) {
        return submitGraphRoots(graph, x, y, z, x.length);
    }

    CompletableFuture<float[]> submitGraphRoots(GpuDensityGraph graph, float[] x, float[] y, float[] z, int sampleCount) {
        CompletableFuture<float[]> future = new CompletableFuture<>();
        pendingGraphRoots.add(new GraphRootsRequest(graph, x.clone(), y.clone(), z.clone(), sampleCount, future));
        scheduleDrain();
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
        pendingGraphGrids.add(new GraphRootsGridRequest(graph, snapshot(metadata), chunkCount, samplesPerChunk, future));
        scheduleDrain();
        return future;
    }

    /** Starts at most one request per free slot; queue pressure never causes CPU fallback. */
    private synchronized void drainGraphRoots() {
        for (PersistentVulkanNormalNoiseEngine slot : slots) {
            if (slot == null || slot.hasInFlightWork() || synchronousSlots.contains(slot)) continue;
            GraphRootsRequest request = pendingGraphRoots.poll();
            if (request == null) {
                GraphRootsGridRequest grid = pendingGraphGrids.poll();
                if (grid == null) {
                    TerrainGridRequest terrain = pendingTerrainGrids.poll();
                    if (terrain != null) {
                        try {
                            slot.submitGraphGridInterpolated(terrain.graph, terrain.gridMetadata,
                                            terrain.chunkCount, terrain.samplesPerChunk,
                                            terrain.outputMetadata, terrain.blocksPerChunk, terrain.outputRootIndexes)
                                    .whenComplete((result, failure) -> {
                                        if (failure != null) terrain.future.completeExceptionally(failure);
                                        else terrain.future.complete(result);
                                    });
                        } catch (Throwable failure) {
                            terrain.future.completeExceptionally(new IllegalStateException(
                                    "GPU ring full terrain submission failed", failure));
                        }
                        continue;
                    }
                    // Full terrain dispatches are the producer's main unit of
                    // work. Do not let a long queue of auxiliary aquifer
                    // lookups starve them after graph-grid work is drained.
                    BlockingRequest<?> blocking = pendingBlocking.poll();
                    if (blocking != null) {
                        runBlocking(blocking, slot);
                        continue;
                    }
                    AquiferPrefillRequest prefill = pendingAquiferPrefill.poll();
                    AquiferNearestRequest nearest = prefill == null ? pendingAquiferNearest.poll() : null;
                    AquiferSubstanceRequest substance = prefill == null && nearest == null
                            ? pendingAquiferSubstance.poll() : null;
                    AquiferFluidStatusRequest fluidStatus = prefill == null && nearest == null && substance == null
                            ? pendingAquiferFluidStatus.poll() : null;
                    if (prefill == null && nearest == null && substance == null && fluidStatus == null) break;
                    synchronousSlots.add(slot);
                    try {
                        dispatchWorkers.execute(() -> {
                            try {
                                if (prefill != null) {
                                    prefill.future.complete(slot.submitAquiferPrefill(
                                            prefill.nearestRecords, prefill.nearestCount,
                                            prefill.statusRecords, prefill.statusCount));
                                } else if (nearest != null) {
                                    nearest.future.complete(slot.submitAquiferNearestThree(
                                            nearest.records, nearest.count));
                                } else {
                                    if (substance != null) {
                                        substance.future.complete(slot.submitAquiferSubstance(
                                                substance.records, substance.count));
                                    } else {
                                        fluidStatus.future.complete(slot.submitAquiferFluidStatus(
                                                fluidStatus.records, fluidStatus.count));
                                    }
                                }
                            } catch (Throwable failure) {
                                if (prefill != null) prefill.future.completeExceptionally(new IllegalStateException(
                                        "GPU ring combined aquifer prefill dispatch failed", failure));
                                else if (nearest != null) nearest.future.completeExceptionally(new IllegalStateException(
                                        "GPU ring aquifer nearest-three dispatch failed", failure));
                                else if (substance != null) substance.future.completeExceptionally(new IllegalStateException(
                                        "GPU ring aquifer substance dispatch failed", failure));
                                else fluidStatus.future.completeExceptionally(new IllegalStateException(
                                        "GPU ring aquifer fluid-status dispatch failed", failure));
                            } finally {
                                releaseSynchronousSlot(slot);
                            }
                        });
                    } catch (RuntimeException stopped) {
                        synchronousSlots.remove(slot);
                        if (prefill != null) prefill.future.completeExceptionally(stopped);
                        else if (nearest != null) nearest.future.completeExceptionally(stopped);
                        else if (substance != null) substance.future.completeExceptionally(stopped);
                        else fluidStatus.future.completeExceptionally(stopped);
                    }
                    continue;
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

    private <T> CompletableFuture<T> submitBlocking(String description, SlotOperation<T> operation) {
        CompletableFuture<T> future = new CompletableFuture<>();
        pendingBlocking.add(new BlockingRequest<>(description, operation, future));
        scheduleDrain();
        return future;
    }

    private <T> void runBlocking(BlockingRequest<T> request, PersistentVulkanNormalNoiseEngine slot) {
        synchronousSlots.add(slot);
        try {
            dispatchWorkers.execute(() -> {
                try {
                    request.future.complete(request.operation.call(slot));
                } catch (Throwable failure) {
                    request.future.completeExceptionally(new IllegalStateException(
                            "GPU ring " + request.description + " dispatch failed", failure));
                } finally {
                    releaseSynchronousSlot(slot);
                }
            });
        } catch (RuntimeException stopped) {
            synchronousSlots.remove(slot);
            request.future.completeExceptionally(stopped);
        }
    }

    private void releaseSynchronousSlot(PersistentVulkanNormalNoiseEngine slot) {
        synchronized (this) { synchronousSlots.remove(slot); }
        scheduleDrain();
    }

    CompletableFuture<float[]> submitNoise(GpuGlobalNoiseBatch.Packed batch) {
        return submitBlocking("noise", slot -> slot.submit(batch));
    }

    CompletableFuture<float[]> submitInterpolation(float[] corners, ByteBuffer metadata,
                                                    int chunkCount, int blocksPerChunk) {
        return submitInterpolation(corners, metadata, chunkCount, blocksPerChunk, 1, 1);
    }

    CompletableFuture<float[]> submitInterpolation(float[] corners, ByteBuffer metadata,
                                                    int chunkCount, int blocksPerChunk,
                                                    int rootStride, int outputStride) {
        ByteBuffer inputMetadata = metadata.duplicate();
        float[] inputCorners = corners.clone();
        return submitBlocking("interpolation", slot -> slot.submitInterpolation(inputCorners, inputMetadata,
                chunkCount, blocksPerChunk, rootStride, outputStride));
    }

    CompletableFuture<float[]> submitAquiferPressure(ByteBuffer records, int count) {
        ByteBuffer input = records.duplicate();
        return submitBlocking("aquifer pressure", slot -> slot.submitAquiferPressure(input, count));
    }

    CompletableFuture<int[]> submitAquiferNearestThree(ByteBuffer records, int count) {
        CompletableFuture<int[]> future = new CompletableFuture<>();
        pendingAquiferNearest.add(new AquiferNearestRequest(records.duplicate(), count, future));
        scheduleDrain();
        return future;
    }

    CompletableFuture<int[]> submitAquiferSubstance(ByteBuffer records, int count) {
        CompletableFuture<int[]> future = new CompletableFuture<>();
        pendingAquiferSubstance.add(new AquiferSubstanceRequest(records.duplicate(), count, future));
        scheduleDrain();
        return future;
    }

    CompletableFuture<int[]> submitAquiferFluidStatus(ByteBuffer records, int count) {
        CompletableFuture<int[]> future = new CompletableFuture<>();
        pendingAquiferFluidStatus.add(new AquiferFluidStatusRequest(records.duplicate(), count, future));
        scheduleDrain();
        return future;
    }

    CompletableFuture<GpuAquiferPrefillResult> submitAquiferPrefill(
            ByteBuffer nearestRecords, int nearestCount, ByteBuffer statusRecords, int statusCount) {
        CompletableFuture<GpuAquiferPrefillResult> future = new CompletableFuture<>();
        pendingAquiferPrefill.add(new AquiferPrefillRequest(nearestRecords.duplicate(), nearestCount,
                statusRecords.duplicate(), statusCount, future));
        scheduleDrain();
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
        ByteBuffer inputGridMetadata = snapshot(gridMetadata);
        ByteBuffer inputOutputMetadata = snapshot(outputMetadata);
        int[] roots = outputRootIndexes.clone();
        CompletableFuture<GpuTerrainBatchResult> future = new CompletableFuture<>();
        pendingTerrainGrids.add(new TerrainGridRequest(graph, inputGridMetadata, chunkCount,
                samplesPerChunk, inputOutputMetadata, blocksPerChunk, roots, future));
        scheduleDrain();
        return future;
    }

    CompletableFuture<GpuFusedResult> submitGraphInterpolated(GpuDensityGraph graph, float[] x, float[] y, float[] z,
                                                        int sampleCount, ByteBuffer metadata, int chunkCount, int blocksPerChunk) {
        ByteBuffer inputMetadata = metadata.duplicate();
        float[] inputX = x.clone(), inputY = y.clone(), inputZ = z.clone();
        return submitBlocking("fused terrain", slot ->
                slot.submitGraphInterpolated(graph, inputX, inputY, inputZ, sampleCount, inputMetadata, chunkCount, blocksPerChunk));
    }

    /** Own the small metadata payload until the queued dispatch has consumed it. */
    private static ByteBuffer snapshot(ByteBuffer source) {
        ByteBuffer input = source.duplicate();
        ByteBuffer copy = ByteBuffer.allocateDirect(input.remaining()).order(source.order());
        copy.put(input).flip();
        return copy;
    }

    @Override public void close() {
        completion.shutdown();
        try {
            if (!completion.awaitTermination(30, TimeUnit.SECONDS)) completion.shutdownNow();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        dispatchWorkers.shutdown();
        try {
            if (!dispatchWorkers.awaitTermination(30, TimeUnit.SECONDS)) dispatchWorkers.shutdownNow();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            dispatchWorkers.shutdownNow();
        }
        IllegalStateException stopped = new IllegalStateException("GPU submission ring stopped");
        GraphRootsRequest graphRequest;
        while ((graphRequest = pendingGraphRoots.poll()) != null)
            graphRequest.future.completeExceptionally(stopped);
        GraphRootsGridRequest gridRequest;
        while ((gridRequest = pendingGraphGrids.poll()) != null)
            gridRequest.future.completeExceptionally(stopped);
        TerrainGridRequest terrainRequest;
        while ((terrainRequest = pendingTerrainGrids.poll()) != null)
            terrainRequest.future.completeExceptionally(stopped);
        AquiferNearestRequest request;
        while ((request = pendingAquiferNearest.poll()) != null)
            request.future.completeExceptionally(stopped);
        AquiferSubstanceRequest substanceRequest;
        while ((substanceRequest = pendingAquiferSubstance.poll()) != null)
            substanceRequest.future.completeExceptionally(stopped);
        AquiferFluidStatusRequest fluidStatusRequest;
        while ((fluidStatusRequest = pendingAquiferFluidStatus.poll()) != null)
            fluidStatusRequest.future.completeExceptionally(stopped);
        AquiferPrefillRequest prefillRequest;
        while ((prefillRequest = pendingAquiferPrefill.poll()) != null)
            prefillRequest.future.completeExceptionally(stopped);
        BlockingRequest<?> blockingRequest;
        while ((blockingRequest = pendingBlocking.poll()) != null)
            blockingRequest.future.completeExceptionally(stopped);
        for (int i = slots.length - 1; i >= 0; i--) {
            if (slots[i] != null) slots[i].close();
        }
    }

    private record GraphRootsRequest(GpuDensityGraph graph, float[] x, float[] y, float[] z,
                                     int sampleCount, CompletableFuture<float[]> future) {}
    private record GraphRootsGridRequest(GpuDensityGraph graph, ByteBuffer metadata, int chunkCount, int samplesPerChunk,
                                         CompletableFuture<float[]> future) {}
    private record TerrainGridRequest(GpuDensityGraph graph, ByteBuffer gridMetadata, int chunkCount,
                                      int samplesPerChunk, ByteBuffer outputMetadata, int blocksPerChunk,
                                      int[] outputRootIndexes, CompletableFuture<GpuTerrainBatchResult> future) {}
    private record AquiferNearestRequest(ByteBuffer records, int count, CompletableFuture<int[]> future) {}
    private record AquiferSubstanceRequest(ByteBuffer records, int count, CompletableFuture<int[]> future) {}
    private record AquiferFluidStatusRequest(ByteBuffer records, int count, CompletableFuture<int[]> future) {}
    private record AquiferPrefillRequest(ByteBuffer nearestRecords, int nearestCount,
                                         ByteBuffer statusRecords, int statusCount,
                                         CompletableFuture<GpuAquiferPrefillResult> future) {}
    @FunctionalInterface
    private interface SlotOperation<T> {
        T call(PersistentVulkanNormalNoiseEngine slot) throws Exception;
    }
    private record BlockingRequest<T>(String description, SlotOperation<T> operation,
                                      CompletableFuture<T> future) {}
}
