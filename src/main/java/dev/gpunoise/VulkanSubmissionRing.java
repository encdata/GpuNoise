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
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashSet;

/**
 * Safe in-flight submission ring. Each slot owns its Vulkan buffers, command
 * buffer, and fence through a separate persistent engine, so submissions never
 * overwrite resources still used by another dispatch.
 */
final class VulkanSubmissionRing implements AutoCloseable {
    private static final int MAX_SLOT_COUNT_PER_DEVICE = 2;
    private static final int MAX_DEVICE_COUNT = (int) Math.max(1L,
            Math.min(8L, Long.getLong("gpunoise.maxDevices", 2L)));
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
    /** Physical-device identity for each independently owned slot. */
    private final GpuDevice[] slotDevices;
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
    /** Startup parity borrows a specific device asynchronously until one of its slots is free. */
    private final BlockingQueue<ParityRequest<?>> pendingParity = new ArrayBlockingQueue<>(MAX_DEVICE_COUNT * 4);
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
    /** Round-robin tie break among physical devices with equal free capacity. */
    private int nextSlotIndex;
    private final long[] queueClassSamples = new long[QUEUE_CLASSES.length];
    private final long[] queueClassWaitNanos = new long[QUEUE_CLASSES.length];
    private final long[] queueClassMaxWaitNanos = new long[QUEUE_CLASSES.length];
    /** Next request class to inspect; prevents a busy producer class starving terrain. */
    private int nextQueueClass;
    private final ExecutorService dispatchWorkers;
    private final ExecutorService readbacks;
    private final ExecutorService submissions = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "GPU Vulkan submit");
        thread.setDaemon(true);
        return thread;
    });
    private final ScheduledExecutorService completion = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "GPU Vulkan completion");
        thread.setDaemon(true);
        return thread;
    });
    /** Dispatch continuations and mapped readbacks scale with active slots. */
    private ExecutorService createDispatchWorkers(int slotCount) {
        return Executors.newFixedThreadPool(slotCount, r -> {
        Thread thread = new Thread(r, "GPU Vulkan dispatch");
        thread.setDaemon(true);
        return thread;
        });
    }
    private ExecutorService createReadbacks(int slotCount) {
        return Executors.newFixedThreadPool(slotCount, r -> {
        Thread thread = new Thread(r, "GPU Vulkan readback");
        thread.setDaemon(true);
        return thread;
        });
    }
    VulkanSubmissionRing(GpuDevice device) throws Exception {
        this(List.of(device));
    }

    /**
     * Creates one bounded submission ring spanning preferred physical devices.
     * Each device owns its own Vulkan instance, logical device and queue; a slot
     * is never shared across physical devices.
     */
    VulkanSubmissionRing(List<GpuDevice> devices) throws Exception {
        if (devices == null || devices.isEmpty()) throw new IllegalArgumentException("no Vulkan devices supplied");
        List<PersistentVulkanNormalNoiseEngine> createdSlots = new ArrayList<>();
        List<GpuDevice> createdDevices = new ArrayList<>();
        Throwable lastFailure = null;
        int selectedDevices = 0;
        for (GpuDevice candidate : devices) {
            if (candidate == null) continue;
            // Mesa CPU Vulkan remains a valid fallback for CI and systems
            // without hardware Vulkan; do not mix it into a hardware pool.
            if (candidate.deviceType() == org.lwjgl.vulkan.VK10.VK_PHYSICAL_DEVICE_TYPE_CPU
                    && !createdSlots.isEmpty()) continue;
            if (selectedDevices >= MAX_DEVICE_COUNT) break;
            List<PersistentVulkanNormalNoiseEngine> deviceSlots = new ArrayList<>();
            try {
                PersistentVulkanNormalNoiseEngine primary = new PersistentVulkanNormalNoiseEngine(candidate);
                deviceSlots.add(primary);
                int requested = requestedSlotCount(candidate);
                for (int i = 1; i < requested; i++) {
                    try {
                        deviceSlots.add(new PersistentVulkanNormalNoiseEngine(primary));
                    } catch (Exception slotFailure) {
                        GpuNoise.LOGGER.warn("Could not create additional Vulkan slot on {}; keeping {} slot(s)",
                                candidate.name(), deviceSlots.size(), slotFailure);
                        break;
                    }
                }
                createdSlots.addAll(deviceSlots);
                for (int i = 0; i < deviceSlots.size(); i++) createdDevices.add(candidate);
                selectedDevices++;
                GpuNoise.LOGGER.info("GPU Vulkan device ring: {} slot(s) on {} ({})",
                        deviceSlots.size(), candidate.name(), candidate.vendorName());
            } catch (Exception failure) {
                lastFailure = failure;
                for (PersistentVulkanNormalNoiseEngine slot : deviceSlots) {
                    try { slot.close(); } catch (Throwable closeFailure) { failure.addSuppressed(closeFailure); }
                }
                GpuNoise.LOGGER.warn("Vulkan runtime initialization failed on {}; trying another device",
                        candidate.name(), failure);
            }
        }
        if (createdSlots.isEmpty()) {
            if (lastFailure instanceof Exception exception) throw exception;
            throw new IllegalStateException("no hardware Vulkan device could initialize");
        }
        slots = createdSlots.toArray(PersistentVulkanNormalNoiseEngine[]::new);
        slotDevices = createdDevices.toArray(GpuDevice[]::new);
        readbackScheduled = new AtomicBoolean[slots.length];
        for (int i = 0; i < slots.length; i++) readbackScheduled[i] = new AtomicBoolean();
        dispatchWorkers = createDispatchWorkers(slots.length);
        readbacks = createReadbacks(slots.length);
        GpuNoise.LOGGER.info("GPU Vulkan submission pool ready: devices={}, slots={}; set gpunoise.maxDevices and gpunoise.submissionSlots to tune resource use",
                selectedDevices, slots.length);
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
                if (value >= 1 && value <= MAX_SLOT_COUNT_PER_DEVICE) return value;
                GpuNoise.LOGGER.warn("Ignoring gpunoise.submissionSlots={}; expected 1..{}",
                        configured, MAX_SLOT_COUNT_PER_DEVICE);
            } catch (NumberFormatException invalid) {
                GpuNoise.LOGGER.warn("Ignoring invalid gpunoise.submissionSlots={}", configured);
            }
        }
        // Discrete GPUs usually have enough dedicated memory to keep two
        // terrain batches resident. Integrated and software Vulkan devices
        // stay single-slot by default to avoid competing with the CPU/server.
        return device.deviceType() == org.lwjgl.vulkan.VK10.VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU
                ? MAX_SLOT_COUNT_PER_DEVICE : 1;
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
            if (hasPendingRequests() && canStartQueuedWork()) scheduleDrain();
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
                || !pendingBlocking.isEmpty() || !pendingTerrainGrids.isEmpty() || !pendingParity.isEmpty();
    }

    private synchronized boolean canStartQueuedWork() {
        boolean hasGeneralWork = !pendingGraphRoots.isEmpty() || !pendingGraphGrids.isEmpty()
                || !pendingAquiferNearest.isEmpty() || !pendingAquiferSubstance.isEmpty()
                || !pendingAquiferFluidStatus.isEmpty() || !pendingAquiferPrefill.isEmpty()
                || !pendingBlocking.isEmpty() || !pendingTerrainGrids.isEmpty();
        for (int i = 0; i < slots.length; i++) {
            PersistentVulkanNormalNoiseEngine slot = slots[i];
            if (slot != null && !readbackScheduled[i].get()
                    && !slot.hasInFlightWork() && !synchronousSlots.contains(slot)
                    && (hasGeneralWork || hasPendingParityFor(slotDevices[i]))) return true;
        }
        return false;
    }

    private boolean hasPendingParityFor(GpuDevice device) {
        for (ParityRequest<?> request : pendingParity) {
            if (request.target.equals(device) && !request.future.isDone()) return true;
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
        ParityRequest<?> parity;
        while ((parity = pendingParity.poll()) != null) parity.future.completeExceptionally(failure);
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
    GpuDevice primaryDevice() { return slotDevices[0]; }
    int slotCount() { return slots.length; }
    List<GpuDevice> devices() { return List.copyOf(new LinkedHashSet<>(List.of(slotDevices))); }

    /** Direct startup-parity dispatch pinned to one physical device. */
    CompletableFuture<float[]> submitGraphRootsForDevice(GpuDevice target, GpuDensityGraph graph,
                                                          float[] x, float[] y, float[] z) {
        return submitParityOnDevice(target, "graph roots", slot ->
                slot.submitGraphRootsAsync(graph, x.clone(), y.clone(), z.clone(), x.length));
    }

    /** Direct terrain-grid shader parity dispatch pinned to one physical device. */
    CompletableFuture<float[]> submitGraphRootsGridForDevice(GpuDevice target, GpuDensityGraph graph,
                                                               ByteBuffer metadata, int chunkCount,
                                                               int samplesPerChunk) {
        return submitParityOnDevice(target, "terrain root grid", slot ->
                slot.submitGraphRootsGridAsync(graph, snapshot(metadata), chunkCount, samplesPerChunk));
    }

    /** Direct full grid, interpolation and block-root parity dispatch for one device. */
    CompletableFuture<GpuTerrainBatchResult> submitTerrainGridForDevice(
            GpuDevice target, GpuDensityGraph graph, ByteBuffer gridMetadata, int samplesPerChunk,
            ByteBuffer outputMetadata, int blocksPerChunk, int[] outputRootIndexes) {
        return submitParityOnDevice(target, "full terrain grid", slot ->
                slot.submitGraphGridInterpolated(graph, snapshot(gridMetadata), 1, samplesPerChunk,
                        snapshot(outputMetadata), blocksPerChunk, outputRootIndexes.clone()));
    }

    /** Runs pinned startup validation on a dispatch worker while reserving the slot. */
    private <T> CompletableFuture<T> submitParityOnDevice(
            GpuDevice target, String description, SlotOperation<CompletableFuture<T>> operation) {
        CompletableFuture<T> result = new CompletableFuture<>();
        if (target == null) return CompletableFuture.failedFuture(
                new IllegalArgumentException("Vulkan parity target is null"));
        boolean available = false;
        for (GpuDevice slotDevice : slotDevices) available |= slotDevice.equals(target);
        if (!available) return CompletableFuture.failedFuture(new IllegalArgumentException(
                "Vulkan parity target is not in the active pool: " + target.name()));
        if (!pendingParity.offer(new ParityRequest<>(target, description, operation, result))) {
            result.completeExceptionally(new RejectedExecutionException(
                    "Vulkan pinned parity queue is full for " + target.name()));
            return result;
        }
        scheduleDrain();
        return result;
    }

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
        return submitGraphRootsGrid(graph, metadata, chunkCount, samplesPerChunk, true);
    }

    /** Internal batch path; the caller keeps metadata stable until the future completes. */
    CompletableFuture<float[]> submitGraphRootsGridOwned(GpuDensityGraph graph, ByteBuffer metadata,
                                                           int chunkCount, int samplesPerChunk) {
        return submitGraphRootsGrid(graph, metadata, chunkCount, samplesPerChunk, false);
    }

    private CompletableFuture<float[]> submitGraphRootsGrid(GpuDensityGraph graph, ByteBuffer metadata,
                                                              int chunkCount, int samplesPerChunk,
                                                              boolean copyMetadata) {
        if (graph == null) return CompletableFuture.failedFuture(
                new IllegalStateException("GPU terrain graph is not installed"));
        CompletableFuture<float[]> future = new CompletableFuture<>();
        enqueue(pendingGraphGrids, () -> new GraphRootsGridRequest(graph,
                copyMetadata ? snapshot(metadata) : metadata, chunkCount,
                samplesPerChunk, future, System.nanoTime()), future, "terrain root grid");
        return future;
    }

    /** Starts at most one request per free slot, rotating classes to avoid producer starvation. */
    private synchronized void drainGraphRoots() {
        drainParityRequests();
        for (int dispatched = 0; dispatched < slots.length; dispatched++) {
            int index = selectLeastLoadedSlot();
            if (index < 0) return;
            PersistentVulkanNormalNoiseEngine slot = slots[index];
            for (int attempt = 0; attempt < QUEUE_CLASSES.length; attempt++) {
                QueueClass requestClass = QUEUE_CLASSES[nextQueueClass];
                nextQueueClass = (nextQueueClass + 1) % QUEUE_CLASSES.length;
                if (dispatchNext(slot, requestClass)) {
                    nextSlotIndex = (index + 1) % slots.length;
                    break;
                }
                if (attempt == QUEUE_CLASSES.length - 1) return;
            }
        }
    }

    /** Borrow pinned startup slots without blocking the bootstrap or submitter thread. */
    private void drainParityRequests() {
        int requestsToInspect = pendingParity.size();
        for (int requestIndex = 0; requestIndex < requestsToInspect; requestIndex++) {
            ParityRequest<?> request = pendingParity.poll();
            if (request == null) return;
            if (request.future.isDone()) continue;
            int slotIndex = selectIdleSlot(request.target);
            if (slotIndex < 0) {
                if (!pendingParity.offer(request)) request.future.completeExceptionally(
                        new RejectedExecutionException("Vulkan pinned parity queue overflow"));
                continue;
            }
            PersistentVulkanNormalNoiseEngine slot = slots[slotIndex];
            synchronousSlots.add(slot);
            dispatchParityRequest(request, slot);
        }
    }

    private int selectIdleSlot(GpuDevice target) {
        for (int offset = 0; offset < slots.length; offset++) {
            int index = (nextSlotIndex + offset) % slots.length;
            PersistentVulkanNormalNoiseEngine slot = slots[index];
            if (!slotDevices[index].equals(target) || readbackScheduled[index].get()
                    || slot.hasInFlightWork() || synchronousSlots.contains(slot)) continue;
            nextSlotIndex = (index + 1) % slots.length;
            return index;
        }
        return -1;
    }

    private <T> void dispatchParityRequest(ParityRequest<T> request,
                                            PersistentVulkanNormalNoiseEngine slot) {
        try {
            dispatchWorkers.execute(() -> {
                CompletableFuture<T> submitted;
                try {
                    submitted = request.operation.call(slot);
                    if (submitted == null) throw new IllegalStateException("parity submission returned no future");
                } catch (Throwable failure) {
                    releaseSynchronousSlot(slot);
                    request.future.completeExceptionally(new IllegalStateException(
                            "Vulkan " + request.description + " parity dispatch failed on "
                                    + request.target.name(), failure));
                    return;
                }
                releaseSynchronousSlot(slot);
                submitted.whenComplete((value, failure) -> {
                    Throwable reportedFailure = failure == null ? null
                            : new IllegalStateException("Vulkan " + request.description
                                    + " parity failed on " + request.target.name(), failure);
                    completeOnDispatchWorker(request.future, value, reportedFailure);
                });
            });
        } catch (RuntimeException stopped) {
            releaseSynchronousSlot(slot);
            request.future.completeExceptionally(stopped);
        }
    }

    /**
     * Mirrors C2ME OCL's available-permit selection: choose a device with the
     * largest fraction of idle submission slots, then rotate ties. This keeps
     * multi-GPU work balanced without pinning requests to a fixed first device.
     */
    private int selectLeastLoadedSlot() {
        int best = -1;
        double bestFreeRatio = -1.0;
        for (int offset = 0; offset < slots.length; offset++) {
            int index = (nextSlotIndex + offset) % slots.length;
            PersistentVulkanNormalNoiseEngine slot = slots[index];
            if (readbackScheduled[index].get() || slot.hasInFlightWork() || synchronousSlots.contains(slot)) continue;
            int deviceSlots = 0;
            int freeDeviceSlots = 0;
            GpuDevice candidateDevice = slotDevices[index];
            for (int other = 0; other < slots.length; other++) {
                if (!slotDevices[other].equals(candidateDevice)) continue;
                deviceSlots++;
                PersistentVulkanNormalNoiseEngine otherSlot = slots[other];
                if (!readbackScheduled[other].get() && !otherSlot.hasInFlightWork()
                        && !synchronousSlots.contains(otherSlot)) freeDeviceSlots++;
            }
            double freeRatio = deviceSlots == 0 ? 0.0 : (double) freeDeviceSlots / deviceSlots;
            if (freeRatio > bestFreeRatio) {
                best = index;
                bestFreeRatio = freeRatio;
            }
        }
        return best;
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
        return submitGraphGridInterpolated(graph, gridMetadata, chunkCount, samplesPerChunk,
                outputMetadata, blocksPerChunk, outputRootIndexes, true);
    }

    /** Internal batch path; metadata remains owned by its dispatch buffer until completion. */
    CompletableFuture<GpuTerrainBatchResult> submitGraphGridInterpolatedOwned(
            GpuDensityGraph graph, ByteBuffer gridMetadata, int chunkCount, int samplesPerChunk,
            ByteBuffer outputMetadata, int blocksPerChunk, int[] outputRootIndexes) {
        return submitGraphGridInterpolated(graph, gridMetadata, chunkCount, samplesPerChunk,
                outputMetadata, blocksPerChunk, outputRootIndexes, false);
    }

    private CompletableFuture<GpuTerrainBatchResult> submitGraphGridInterpolated(
            GpuDensityGraph graph, ByteBuffer gridMetadata, int chunkCount, int samplesPerChunk,
            ByteBuffer outputMetadata, int blocksPerChunk, int[] outputRootIndexes,
            boolean copyMetadata) {
        CompletableFuture<GpuTerrainBatchResult> future = new CompletableFuture<>();
        enqueue(pendingTerrainGrids, () -> new TerrainGridRequest(graph,
                copyMetadata ? snapshot(gridMetadata) : gridMetadata, chunkCount,
                samplesPerChunk, copyMetadata ? snapshot(outputMetadata) : outputMetadata,
                blocksPerChunk, outputRootIndexes.clone(), future,
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
        ParityRequest<?> parityRequest;
        while ((parityRequest = pendingParity.poll()) != null)
            parityRequest.future.completeExceptionally(stopped);
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
    private record ParityRequest<T>(GpuDevice target, String description,
                                    SlotOperation<CompletableFuture<T>> operation,
                                    CompletableFuture<T> future) {}
}
