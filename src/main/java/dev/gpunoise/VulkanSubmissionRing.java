package dev.gpunoise;

import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Safe in-flight submission ring. Each slot owns its Vulkan buffers, command
 * buffer, and fence through a separate persistent engine, so submissions never
 * overwrite resources still used by another dispatch.
 */
final class VulkanSubmissionRing implements AutoCloseable {
    private static final int MAX_SLOT_COUNT = 2;
    /** Bound retained request payloads across every queue class, including in-flight work. */
    private static final int MAX_PENDING_SUBMISSIONS = (int) Math.max(8L,
            Math.min(256L, Long.getLong("gpunoise.submissionQueueCapacity", 32L)));
    private static final QueueClass[] QUEUE_CLASSES = QueueClass.values();
    // Terrain callers block on their futures after submitting. Poll more
    // frequently while a graph dispatch is in flight, then back off while idle
    // so low handoff latency does not become constant CPU wakeup overhead.
    private static final long COMPLETION_POLL_MICROS = 250L;
    private static final long IDLE_POLL_MILLIS = 1L;
    private final PersistentVulkanNormalNoiseEngine[] slots;
    /** A slot stays unavailable from signaled-fence detection through host readback. */
    private final AtomicBoolean[] readbackScheduled;
    private final AtomicBoolean drainScheduled = new AtomicBoolean();
    /** Requests wait here, not on a worldgen thread, while the shared slot is busy. */
    private final BlockingQueue<GraphRootsRequest> pendingGraphRoots = new ArrayBlockingQueue<>(MAX_PENDING_SUBMISSIONS);
    private final BlockingQueue<GraphRootsGridRequest> pendingGraphGrids = new ArrayBlockingQueue<>(MAX_PENDING_SUBMISSIONS);
    private final BlockingQueue<TerrainGridRequest> pendingTerrainGrids = new ArrayBlockingQueue<>(MAX_PENDING_SUBMISSIONS);
    private final BlockingQueue<AquiferNearestRequest> pendingAquiferNearest = new ArrayBlockingQueue<>(MAX_PENDING_SUBMISSIONS);
    private final BlockingQueue<AquiferSubstanceRequest> pendingAquiferSubstance = new ArrayBlockingQueue<>(MAX_PENDING_SUBMISSIONS);
    private final BlockingQueue<AquiferFluidStatusRequest> pendingAquiferFluidStatus = new ArrayBlockingQueue<>(MAX_PENDING_SUBMISSIONS);
    private final BlockingQueue<AquiferPrefillRequest> pendingAquiferPrefill = new ArrayBlockingQueue<>(MAX_PENDING_SUBMISSIONS);
    /** Synchronous dispatch requests run on bounded slot workers. */
    private final BlockingQueue<BlockingRequest<?>> pendingBlocking = new ArrayBlockingQueue<>(MAX_PENDING_SUBMISSIONS);
    /** A permit belongs to a request until its result future completes. */
    private final Semaphore submissionPermits = new Semaphore(MAX_PENDING_SUBMISSIONS);
    private final AtomicLong submissionSaturationEvents = new AtomicLong();
    /** Synchronous Vulkan APIs run off the completion thread and reserve one slot each. */
    private final Set<PersistentVulkanNormalNoiseEngine> synchronousSlots =
            Collections.newSetFromMap(new IdentityHashMap<>());
    /** Ring queue latency stays separate from terrain batch collection and GPU fence time. */
    private long queueDispatchSamples;
    private long totalQueueWaitNanos;
    private long maxQueueWaitNanos;
    private final long[] queueClassSamples = new long[QUEUE_CLASSES.length];
    private final long[] queueClassWaitNanos = new long[QUEUE_CLASSES.length];
    private final long[] queueClassMaxWaitNanos = new long[QUEUE_CLASSES.length];
    /** Next request class to inspect; prevents a busy producer class starving terrain. */
    private int nextQueueClass;
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
    /** Large mapped-output copies must not hold up fence polling for other slots. */
    private final ExecutorService readbacks = Executors.newFixedThreadPool(MAX_SLOT_COUNT, r -> {
        Thread thread = new Thread(r, "GPU Vulkan readback");
        thread.setDaemon(true);
        return thread;
    });
    /** Command recording/submission may take CPU time; keep it off the fence poller. */
    private final ExecutorService submissions = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "GPU Vulkan submit");
        thread.setDaemon(true);
        return thread;
    });

    VulkanSubmissionRing(GpuDevice device) throws Exception {
        int requestedSlots = requestedSlotCount(device);
        slots = new PersistentVulkanNormalNoiseEngine[requestedSlots];
        readbackScheduled = new AtomicBoolean[requestedSlots];
        for (int i = 0; i < requestedSlots; i++) readbackScheduled[i] = new AtomicBoolean();
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
            for (int i = 0; i < slots.length; i++) {
                PersistentVulkanNormalNoiseEngine slot = slots[i];
                if (slot == null) continue;
                if (readbackScheduled[i].get()) {
                    graphInFlight = true;
                    continue;
                }
                if (slot.fenceReadyForReadback()) {
                    if (readbackScheduled[i].compareAndSet(false, true)) {
                        graphInFlight = true;
                        scheduleReadback(i, slot);
                    }
                } else {
                    graphInFlight |= slot.hasInFlightWork();
                }
            }
            // Submission setup is handled on its own executor. Fence polling
            // must stay responsive even when command recording takes time.
            scheduleDrain();
            for (int i = 0; i < slots.length; i++) {
                PersistentVulkanNormalNoiseEngine slot = slots[i];
                if (slot != null && !readbackScheduled[i].get()) graphInFlight |= slot.hasInFlightWork();
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

    private void scheduleReadback(int index, PersistentVulkanNormalNoiseEngine slot) {
        try {
            readbacks.execute(() -> {
                try {
                    slot.completeSignaledWork();
                } catch (Throwable failure) {
                    GpuNoise.LOGGER.error("GPU Vulkan result readback failed for slot {}", index, failure);
                } finally {
                    readbackScheduled[index].set(false);
                    scheduleDrain();
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException stopped) {
            readbackScheduled[index].set(false);
            GpuNoise.LOGGER.error("GPU Vulkan readback worker rejected completed slot {}", index, stopped);
        }
    }

    /** Keep command recording and queue submission off terrain producer threads. */
    private void scheduleDrain() {
        if (!drainScheduled.compareAndSet(false, true)) return;
        try {
            submissions.execute(() -> {
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
        for (int i = 0; i < slots.length; i++) {
            PersistentVulkanNormalNoiseEngine slot = slots[i];
            if (slot != null && !readbackScheduled[i].get()
                    && !slot.hasInFlightWork() && !synchronousSlots.contains(slot)) return true;
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

    /** Keep worldgen continuations and chunk-result fanout off the fence-poll thread. */
    private <T> void completeOnDispatchWorker(CompletableFuture<T> future, T value, Throwable failure) {
        try {
            dispatchWorkers.execute(() -> {
                if (failure != null) future.completeExceptionally(failure);
                else future.complete(value);
            });
        } catch (RuntimeException stopped) {
            if (failure != null) stopped.addSuppressed(failure);
            future.completeExceptionally(stopped);
        }
    }

    private void recordQueueWait(long enqueuedNanos, QueueClass requestClass) {
        if (!GpuTerrainProfiler.enabled() || enqueuedNanos == 0L) return;
        long waitNanos = Math.max(0L, System.nanoTime() - enqueuedNanos);
        totalQueueWaitNanos += waitNanos;
        maxQueueWaitNanos = Math.max(maxQueueWaitNanos, waitNanos);
        int classIndex = requestClass.ordinal();
        queueClassSamples[classIndex]++;
        queueClassWaitNanos[classIndex] += waitNanos;
        queueClassMaxWaitNanos[classIndex] = Math.max(queueClassMaxWaitNanos[classIndex], waitNanos);
        long samples = ++queueDispatchSamples;
        if ((samples & 127L) == 0L) {
            GpuNoise.LOGGER.info("GPU Vulkan submission queue: dispatches={}, avgQueueWaitMs={}, maxQueueWaitMs={}, slots={}",
                    samples, totalQueueWaitNanos / (double) samples / 1_000_000.0,
                    maxQueueWaitNanos / 1_000_000.0, slots.length);
            for (int i = 0; i < QUEUE_CLASSES.length; i++) {
                long classSamples = queueClassSamples[i];
                if (classSamples == 0L) continue;
                GpuNoise.LOGGER.info("GPU Vulkan queue class {}: dispatches={}, avgQueueWaitMs={}, maxQueueWaitMs={}",
                        QUEUE_CLASSES[i], classSamples,
                        queueClassWaitNanos[i] / (double) classSamples / 1_000_000.0,
                        queueClassMaxWaitNanos[i] / 1_000_000.0);
            }
        }
    }

    PersistentVulkanNormalNoiseEngine primary() { return slots[0]; }

    CompletableFuture<float[]> submitGraphRoots(GpuDensityGraph graph, float[] x, float[] y, float[] z) {
        return submitGraphRoots(graph, x, y, z, x.length);
    }

    CompletableFuture<float[]> submitGraphRoots(GpuDensityGraph graph, float[] x, float[] y, float[] z, int sampleCount) {
        CompletableFuture<float[]> future = new CompletableFuture<>();
        enqueue(pendingGraphRoots, () -> new GraphRootsRequest(graph, x.clone(), y.clone(), z.clone(), sampleCount,
                future, System.nanoTime()), future, "graph roots");
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
        enqueue(pendingGraphGrids, () -> new GraphRootsGridRequest(graph, snapshot(metadata), chunkCount,
                samplesPerChunk, future, System.nanoTime()), future, "terrain root grid");
        return future;
    }

    /** Starts at most one request per free slot, rotating classes to avoid producer starvation. */
    private synchronized void drainGraphRoots() {
        for (int i = 0; i < slots.length; i++) {
            PersistentVulkanNormalNoiseEngine slot = slots[i];
            if (slot == null || readbackScheduled[i].get()
                    || slot.hasInFlightWork() || synchronousSlots.contains(slot)) continue;
            for (int attempt = 0; attempt < QUEUE_CLASSES.length; attempt++) {
                QueueClass requestClass = QUEUE_CLASSES[nextQueueClass];
                nextQueueClass = (nextQueueClass + 1) % QUEUE_CLASSES.length;
                if (dispatchNext(slot, requestClass)) break;
            }
        }
    }

    /**
     * Queue classes are scalar roots, root grids, full terrain, synchronous
     * compatibility calls, and aquifer batches. Rotating even when a class is
     * empty keeps the policy simple and bounds how long a continuously busy
     * producer can delay any other class to a handful of slot completions.
     */
    private boolean dispatchNext(PersistentVulkanNormalNoiseEngine slot, QueueClass requestClass) {
        switch (requestClass) {
            case GRAPH_ROOTS -> {
                GraphRootsRequest request = pendingGraphRoots.poll();
                if (request == null || request.future.isDone()) return request != null;
                recordQueueWait(request.enqueuedNanos, requestClass);
                try {
                    slot.submitGraphRootsAsync(request.graph, request.x, request.y, request.z, request.sampleCount)
                            .whenComplete((values, failure) -> completeOnDispatchWorker(request.future, values, failure));
                } catch (Throwable failure) {
                    request.future.completeExceptionally(new IllegalStateException("GPU ring graph dispatch failed", failure));
                }
            }
            case GRAPH_GRIDS -> {
                GraphRootsGridRequest grid = pendingGraphGrids.poll();
                if (grid == null || grid.future.isDone()) return grid != null;
                recordQueueWait(grid.enqueuedNanos, requestClass);
                try {
                    slot.submitGraphRootsGridAsync(grid.graph, grid.metadata, grid.chunkCount, grid.samplesPerChunk)
                            .whenComplete((values, failure) -> completeOnDispatchWorker(grid.future, values, failure));
                } catch (Throwable failure) {
                    grid.future.completeExceptionally(new IllegalStateException("GPU ring grid dispatch failed", failure));
                }
            }
            case FULL_TERRAIN -> {
                TerrainGridRequest terrain = pendingTerrainGrids.poll();
                if (terrain == null || terrain.future.isDone()) return terrain != null;
                recordQueueWait(terrain.enqueuedNanos, requestClass);
                try {
                    slot.submitGraphGridInterpolated(terrain.graph, terrain.gridMetadata,
                                    terrain.chunkCount, terrain.samplesPerChunk,
                                    terrain.outputMetadata, terrain.blocksPerChunk, terrain.outputRootIndexes)
                            .whenComplete((result, failure) -> completeOnDispatchWorker(terrain.future, result, failure));
                } catch (Throwable failure) {
                    terrain.future.completeExceptionally(new IllegalStateException(
                            "GPU ring full terrain submission failed", failure));
                }
            }
            case BLOCKING -> {
                BlockingRequest<?> blocking = pendingBlocking.poll();
                if (blocking == null || blocking.future.isDone()) return blocking != null;
                recordQueueWait(blocking.enqueuedNanos, requestClass);
                runBlocking(blocking, slot);
            }
            case AQUIFER -> {
                AquiferPrefillRequest prefill = pendingAquiferPrefill.poll();
                AquiferNearestRequest nearest = prefill == null ? pendingAquiferNearest.poll() : null;
                AquiferSubstanceRequest substance = prefill == null && nearest == null
                        ? pendingAquiferSubstance.poll() : null;
                AquiferFluidStatusRequest fluidStatus = prefill == null && nearest == null && substance == null
                        ? pendingAquiferFluidStatus.poll() : null;
                if (prefill == null && nearest == null && substance == null && fluidStatus == null) return false;
                if ((prefill != null && prefill.future.isDone())
                        || (nearest != null && nearest.future.isDone())
                        || (substance != null && substance.future.isDone())
                        || (fluidStatus != null && fluidStatus.future.isDone())) return true;
                long enqueued = prefill != null ? prefill.enqueuedNanos
                        : nearest != null ? nearest.enqueuedNanos
                        : substance != null ? substance.enqueuedNanos : fluidStatus.enqueuedNanos;
                recordQueueWait(enqueued, requestClass);
                dispatchAquifer(slot, prefill, nearest, substance, fluidStatus);
            }
        }
        return true;
    }

    private enum QueueClass { GRAPH_ROOTS, GRAPH_GRIDS, FULL_TERRAIN, BLOCKING, AQUIFER }

    private void dispatchAquifer(PersistentVulkanNormalNoiseEngine slot, AquiferPrefillRequest prefill,
                                 AquiferNearestRequest nearest, AquiferSubstanceRequest substance,
                                 AquiferFluidStatusRequest fluidStatus) {
        if (prefill != null) {
            dispatchAquiferAsync(slot, "combined aquifer prefill", prefill.future,
                    engine -> engine.submitAquiferPrefillAsync(prefill.nearestRecords,
                            prefill.nearestCount, prefill.statusRecords, prefill.statusCount));
            return;
        }
        if (nearest != null) {
            dispatchAquiferAsync(slot, "aquifer nearest-three", nearest.future,
                    engine -> engine.submitAquiferNearestThreeAsync(nearest.records, nearest.count));
            return;
        }
        if (substance != null) {
            dispatchAquiferAsync(slot, "aquifer substance", substance.future,
                    engine -> engine.submitAquiferSubstanceAsync(substance.records, substance.count));
            return;
        }
        if (fluidStatus != null) {
            dispatchAquiferAsync(slot, "aquifer fluid-status", fluidStatus.future,
                    engine -> engine.submitAquiferFluidStatusAsync(fluidStatus.records, fluidStatus.count));
        }
    }

    private <T> void dispatchAquiferAsync(PersistentVulkanNormalNoiseEngine slot, String description,
                                          CompletableFuture<T> result,
                                          SlotOperation<CompletableFuture<T>> submission) {
        try {
            submission.call(slot).whenComplete((value, failure) ->
                    completeOnDispatchWorker(result, value, failure));
        } catch (Throwable failure) {
            result.completeExceptionally(new IllegalStateException(
                    "GPU ring " + description + " submission failed", failure));
        }
    }

    private <T> CompletableFuture<T> submitBlocking(String description, SlotOperation<T> operation) {
        CompletableFuture<T> future = new CompletableFuture<>();
        enqueue(pendingBlocking, () -> new BlockingRequest<>(description, operation, future, System.nanoTime()),
                future, description);
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
        return submitBlocking("interpolation", slot -> slot.submitInterpolation(corners.clone(), snapshot(metadata),
                chunkCount, blocksPerChunk, rootStride, outputStride));
    }

    CompletableFuture<float[]> submitAquiferPressure(ByteBuffer records, int count) {
        ByteBuffer input = records.duplicate();
        return submitBlocking("aquifer pressure", slot -> slot.submitAquiferPressure(input, count));
    }

    CompletableFuture<int[]> submitAquiferNearestThree(ByteBuffer records, int count) {
        CompletableFuture<int[]> future = new CompletableFuture<>();
        enqueue(pendingAquiferNearest,
                () -> new AquiferNearestRequest(records.duplicate(), count, future, System.nanoTime()),
                future, "aquifer nearest-three");
        return future;
    }

    CompletableFuture<int[]> submitAquiferSubstance(ByteBuffer records, int count) {
        CompletableFuture<int[]> future = new CompletableFuture<>();
        enqueue(pendingAquiferSubstance,
                () -> new AquiferSubstanceRequest(records.duplicate(), count, future, System.nanoTime()),
                future, "aquifer substance");
        return future;
    }

    CompletableFuture<int[]> submitAquiferFluidStatus(ByteBuffer records, int count) {
        CompletableFuture<int[]> future = new CompletableFuture<>();
        enqueue(pendingAquiferFluidStatus,
                () -> new AquiferFluidStatusRequest(records.duplicate(), count, future, System.nanoTime()),
                future, "aquifer fluid-status");
        return future;
    }

    CompletableFuture<GpuAquiferPrefillResult> submitAquiferPrefill(
            ByteBuffer nearestRecords, int nearestCount, ByteBuffer statusRecords, int statusCount) {
        CompletableFuture<GpuAquiferPrefillResult> future = new CompletableFuture<>();
        enqueue(pendingAquiferPrefill, () -> new AquiferPrefillRequest(nearestRecords.duplicate(), nearestCount,
                statusRecords.duplicate(), statusCount, future, System.nanoTime()), future, "aquifer prefill");
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
        CompletableFuture<GpuTerrainBatchResult> future = new CompletableFuture<>();
        enqueue(pendingTerrainGrids, () -> new TerrainGridRequest(graph, snapshot(gridMetadata), chunkCount,
                samplesPerChunk, snapshot(outputMetadata), blocksPerChunk, outputRootIndexes.clone(), future,
                System.nanoTime()),
                future, "full terrain grid");
        return future;
    }

    /**
     * Enqueues only while the shared pending-plus-in-flight budget has room.
     * Payloads are created after acquiring a permit, so a burst cannot allocate
     * arbitrarily many cloned sample arrays while waiting for a Vulkan slot.
     */
    private <T, R> void enqueue(BlockingQueue<T> queue, Supplier<T> requestFactory,
                               CompletableFuture<R> future, String description) {
        if (!submissionPermits.tryAcquire()) {
            long rejected = submissionSaturationEvents.incrementAndGet();
            if (rejected == 1L || (rejected & 127L) == 0L) {
                GpuNoise.LOGGER.warn("GPU Vulkan submission backlog is full ({} outstanding); {} request rejected, callers may use Java fallback (total rejections={})",
                        MAX_PENDING_SUBMISSIONS, description, rejected);
            }
            future.completeExceptionally(new RejectedExecutionException(
                    "GPU Vulkan submission backlog is full: " + description));
            return;
        }
        future.whenComplete((ignored, failure) -> submissionPermits.release());
        try {
            if (!queue.offer(requestFactory.get())) {
                future.completeExceptionally(new RejectedExecutionException(
                        "GPU Vulkan " + description + " queue is full"));
                return;
            }
            scheduleDrain();
        } catch (Throwable failure) {
            future.completeExceptionally(failure);
        }
    }

    CompletableFuture<GpuFusedResult> submitGraphInterpolated(GpuDensityGraph graph, float[] x, float[] y, float[] z,
                                                        int sampleCount, ByteBuffer metadata, int chunkCount, int blocksPerChunk) {
        return submitBlocking("fused terrain", slot ->
                slot.submitGraphInterpolated(graph, x.clone(), y.clone(), z.clone(), sampleCount,
                        snapshot(metadata), chunkCount, blocksPerChunk));
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
        submissions.shutdown();
        try {
            if (!submissions.awaitTermination(30, TimeUnit.SECONDS)) submissions.shutdownNow();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            submissions.shutdownNow();
        }
        readbacks.shutdown();
        try {
            if (!readbacks.awaitTermination(30, TimeUnit.SECONDS)) readbacks.shutdownNow();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            readbacks.shutdownNow();
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
                                     int sampleCount, CompletableFuture<float[]> future, long enqueuedNanos) {}
    private record GraphRootsGridRequest(GpuDensityGraph graph, ByteBuffer metadata, int chunkCount, int samplesPerChunk,
                                         CompletableFuture<float[]> future, long enqueuedNanos) {}
    private record TerrainGridRequest(GpuDensityGraph graph, ByteBuffer gridMetadata, int chunkCount,
                                      int samplesPerChunk, ByteBuffer outputMetadata, int blocksPerChunk,
                                      int[] outputRootIndexes, CompletableFuture<GpuTerrainBatchResult> future,
                                      long enqueuedNanos) {}
    private record AquiferNearestRequest(ByteBuffer records, int count, CompletableFuture<int[]> future,
                                        long enqueuedNanos) {}
    private record AquiferSubstanceRequest(ByteBuffer records, int count, CompletableFuture<int[]> future,
                                           long enqueuedNanos) {}
    private record AquiferFluidStatusRequest(ByteBuffer records, int count, CompletableFuture<int[]> future,
                                             long enqueuedNanos) {}
    private record AquiferPrefillRequest(ByteBuffer nearestRecords, int nearestCount,
                                         ByteBuffer statusRecords, int statusCount,
                                         CompletableFuture<GpuAquiferPrefillResult> future, long enqueuedNanos) {}
    @FunctionalInterface
    private interface SlotOperation<T> {
        T call(PersistentVulkanNormalNoiseEngine slot) throws Exception;
    }
    private record BlockingRequest<T>(String description, SlotOperation<T> operation,
                                      CompletableFuture<T> future, long enqueuedNanos) {}
}
