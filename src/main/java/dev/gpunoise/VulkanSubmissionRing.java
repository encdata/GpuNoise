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
import java.util.concurrent.atomic.AtomicInteger;
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
    /** Match the bounded direct-buffer pool budget so more chunks can share a dispatch. */
    private static final long MAX_COALESCED_INPUT_BYTES = 32L * 1024L * 1024L;
    private static final QueueClass[] QUEUE_CLASSES = QueueClass.values();
    // Terrain callers block on their futures after submitting. Poll more
    // frequently while a graph dispatch is in flight, then back off while idle
    // so low handoff latency does not become constant CPU wakeup overhead.
    private static final long COMPLETION_POLL_MICROS = 250L;
    private static final long IDLE_POLL_MILLIS = 1L;
    private static final boolean DISPATCH_PROFILE_ENABLED = Boolean.getBoolean("gpunoise.profileTerrain");
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
    private final BlockingQueue<BeardifierRequest> pendingBeardifier = new ArrayBlockingQueue<>(MAX_PENDING_SUBMISSIONS);
    private final BlockingQueue<OreVeinRequest> pendingOreVein = new ArrayBlockingQueue<>(MAX_PENDING_SUBMISSIONS);
    /** Blocking compatibility requests run on bounded slot workers. */
    private final BlockingQueue<BlockingRequest<?>> pendingBlocking = new ArrayBlockingQueue<>(MAX_PENDING_SUBMISSIONS);
    /** Startup parity borrows a specific device asynchronously until one of its slots is free. */
    private final BlockingQueue<ParityRequest<?>> pendingParity = new ArrayBlockingQueue<>(MAX_DEVICE_COUNT * 4);
    /** A permit belongs to a request until its result future completes. */
    private final Semaphore submissionPermits = new Semaphore(MAX_PENDING_SUBMISSIONS);
    private final AtomicLong submissionSaturationEvents = new AtomicLong();
    /** Measures overlapping host-side command preparation, never worldgen throughput. */
    private final AtomicInteger activeDispatchPreparations = new AtomicInteger();
    private final AtomicInteger maxConcurrentDispatchPreparations = new AtomicInteger();
    /** Prevents concurrent dispatch workers from selecting the same idle slot. */
    private final Set<PersistentVulkanNormalNoiseEngine> reservedSlots =
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
                                candidate.diagnosticIdentity(), deviceSlots.size(), slotFailure);
                        break;
                    }
                }
                createdSlots.addAll(deviceSlots);
                for (int i = 0; i < deviceSlots.size(); i++) createdDevices.add(candidate);
                selectedDevices++;
                GpuNoise.LOGGER.info("GPU Vulkan device ring: {} slot(s) on {}",
                        deviceSlots.size(), candidate.diagnosticIdentity());
            } catch (Exception failure) {
                lastFailure = failure;
                for (PersistentVulkanNormalNoiseEngine slot : deviceSlots) {
                    try { slot.close(); } catch (Throwable closeFailure) { failure.addSuppressed(closeFailure); }
                }
                GpuNoise.LOGGER.warn("Vulkan runtime initialization failed on {}; trying another device",
                        candidate.diagnosticIdentity(), failure);
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
                || !pendingBeardifier.isEmpty()
                || !pendingOreVein.isEmpty()
                || !pendingBlocking.isEmpty() || !pendingTerrainGrids.isEmpty() || !pendingParity.isEmpty();
    }

    private synchronized boolean canStartQueuedWork() {
        boolean hasGeneralWork = !pendingGraphRoots.isEmpty() || !pendingGraphGrids.isEmpty()
                || !pendingAquiferNearest.isEmpty() || !pendingAquiferSubstance.isEmpty()
                || !pendingAquiferFluidStatus.isEmpty() || !pendingAquiferPrefill.isEmpty()
                || !pendingBeardifier.isEmpty()
                || !pendingOreVein.isEmpty()
                || !pendingBlocking.isEmpty() || !pendingTerrainGrids.isEmpty();
        for (int i = 0; i < slots.length; i++) {
            PersistentVulkanNormalNoiseEngine slot = slots[i];
            if (slot != null && !readbackScheduled[i].get()
                    && !slot.hasInFlightWork() && !reservedSlots.contains(slot)
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
        BeardifierRequest beardifier;
        while ((beardifier = pendingBeardifier.poll()) != null) beardifier.future.completeExceptionally(failure);
        OreVeinRequest oreVein;
        while ((oreVein = pendingOreVein.poll()) != null) oreVein.future.completeExceptionally(failure);
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
            GpuNoise.LOGGER.info("GPU Vulkan submission queue: dispatches={}, avgQueueWaitMs={}, maxQueueWaitMs={}, slots={}, maxConcurrentPreparation={}",
                    samples, totalQueueWaitNanos / (double) samples / 1_000_000.0,
                    maxQueueWaitNanos / 1_000_000.0, slots.length,
                    maxConcurrentDispatchPreparations.get());
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
                null, future, System.nanoTime()), future, "graph roots");
        return future;
    }

    CompletableFuture<float[]> submitGraphRoots(GpuDensityGraph graph, float[] x, float[] y, float[] z,
                                                  int[] outputRootIndexes) {
        return submitGraphRoots(graph, x, y, z, x.length, outputRootIndexes);
    }

    CompletableFuture<float[]> submitGraphRoots(GpuDensityGraph graph, float[] x, float[] y, float[] z,
                                                  int sampleCount, int[] outputRootIndexes) {
        CompletableFuture<float[]> future = new CompletableFuture<>();
        if (outputRootIndexes == null || outputRootIndexes.length == 0 || outputRootIndexes.length > 16
                || sampleCount < 0 || sampleCount > x.length || x.length != y.length || x.length != z.length) {
            future.completeExceptionally(new IllegalArgumentException("invalid selected graph roots request"));
            return future;
        }
        enqueue(pendingGraphRoots, () -> new GraphRootsRequest(graph, x.clone(), y.clone(), z.clone(), sampleCount,
                outputRootIndexes.clone(), future, System.nanoTime()), future, "selected graph roots");
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

    /** Reserve free slots centrally, then record independent submissions in parallel. */
    private void drainGraphRoots() {
        drainParityRequests();
        for (int dispatched = 0; dispatched < slots.length; dispatched++) {
            ReservedDispatch dispatch;
            synchronized (this) {
                int index = selectLeastLoadedSlot();
                if (index < 0) return;
                PersistentVulkanNormalNoiseEngine slot = slots[index];
                dispatch = takeNextDispatch(slot);
                if (dispatch == null) return;
                // This reservation closes the gap between central selection
                // and the worker entering the synchronized engine method.
                reservedSlots.add(slot);
                nextSlotIndex = (index + 1) % slots.length;
            }
            try {
                dispatchWorkers.execute(() -> executeReservedDispatch(dispatch));
            } catch (RejectedExecutionException stopped) {
                releaseReservedSlot(dispatch.slot);
                failDispatch(dispatch, stopped);
            }
        }
    }

    /** Select and remove a request while holding the ring monitor. */
    private ReservedDispatch takeNextDispatch(PersistentVulkanNormalNoiseEngine slot) {
        for (int attempt = 0; attempt < QUEUE_CLASSES.length; attempt++) {
            QueueClass requestClass = QUEUE_CLASSES[nextQueueClass];
            nextQueueClass = (nextQueueClass + 1) % QUEUE_CLASSES.length;
            DispatchRequest request = switch (requestClass) {
                case GRAPH_ROOTS -> pollLive(pendingGraphRoots);
                case GRAPH_GRIDS -> pollLive(pendingGraphGrids);
                case FULL_TERRAIN -> pollLive(pendingTerrainGrids);
                case BLOCKING -> pollLive(pendingBlocking);
                case AQUIFER -> pollAquiferRequest();
                case ORE_VEIN -> pollLive(pendingOreVein);
            };
            if (request == null) continue;
            recordQueueWait(enqueuedNanos(request), requestClass);
            return new ReservedDispatch(slot, requestClass, request);
        }
        return null;
    }

    private static <T extends DispatchRequest> T pollLive(BlockingQueue<T> queue) {
        T request;
        while ((request = queue.poll()) != null) {
            if (!request.future().isDone()) return request;
        }
        return null;
    }

    private DispatchRequest pollAquiferRequest() {
        DispatchRequest request = pollLive(pendingAquiferPrefill);
        if (request == null) request = pollLive(pendingAquiferNearest);
        if (request == null) request = pollLive(pendingAquiferSubstance);
        if (request == null) request = pollLive(pendingAquiferFluidStatus);
        if (request == null) request = pollLive(pendingBeardifier);
        return request;
    }

    private static CompletableFuture<?> requestFuture(DispatchRequest request) {
        return request.future();
    }

    private static long enqueuedNanos(DispatchRequest request) {
        return request.enqueuedNanos();
    }

    private void executeReservedDispatch(ReservedDispatch dispatch) {
        boolean profile = DISPATCH_PROFILE_ENABLED && !requestFuture(dispatch.request).isDone();
        if (profile) {
            int active = activeDispatchPreparations.incrementAndGet();
            maxConcurrentDispatchPreparations.accumulateAndGet(active, Math::max);
        }
        try {
            if (requestFuture(dispatch.request).isDone()) return;
            PersistentVulkanNormalNoiseEngine slot = dispatch.slot;
            DispatchRequest request = dispatch.request;
            switch (dispatch.requestClass) {
                case GRAPH_ROOTS -> {
                    GraphRootsRequest value = (GraphRootsRequest) request;
                    executeGraphRootsBatch(slot, value);
                }
                case GRAPH_GRIDS -> {
                    GraphRootsGridRequest value = (GraphRootsGridRequest) request;
                    slot.submitGraphRootsGridAsync(value.graph, value.metadata, value.chunkCount, value.samplesPerChunk)
                            .whenComplete((result, failure) -> completeOnDispatchWorker(value.future, result, failure));
                }
                case FULL_TERRAIN -> {
                    TerrainGridRequest value = (TerrainGridRequest) request;
                    slot.submitGraphGridInterpolated(value.graph, value.gridMetadata,
                                    value.chunkCount, value.samplesPerChunk, value.outputMetadata,
                                    value.blocksPerChunk, value.outputRootIndexes,
                                    value.cornerRootIndexes)
                            .whenComplete((result, failure) -> completeOnDispatchWorker(value.future, result, failure));
                }
                case BLOCKING -> runBlockingReserved((BlockingRequest<?>) request, slot);
                case AQUIFER -> executeAquiferReserved(slot, request);
                case ORE_VEIN -> executeOreVeinBatch(slot, (OreVeinRequest) request);
            }
        } catch (Throwable failure) {
            failDispatch(dispatch, failure);
        } finally {
            if (profile) activeDispatchPreparations.decrementAndGet();
            releaseReservedSlot(dispatch.slot);
        }
    }

    /** Coalesces queued point-root requests for one graph into a single Vulkan dispatch. */
    private void executeGraphRootsBatch(PersistentVulkanNormalNoiseEngine slot,
                                        GraphRootsRequest first) {
        List<GraphRootsRequest> requests = new ArrayList<>();
        requests.add(first);
        int totalSamples = first.sampleCount;
        if (totalSamples > 0 && totalSamples <= PersistentVulkanNormalNoiseEngine.CAPACITY
                && first.sampleCount <= first.x.length
                && first.sampleCount <= first.y.length
                && first.sampleCount <= first.z.length) {
            synchronized (this) {
                while (totalSamples < PersistentVulkanNormalNoiseEngine.CAPACITY) {
                    GraphRootsRequest next = pendingGraphRoots.peek();
                    if (next == null || next.future.isDone() || next.graph != first.graph
                            || !java.util.Arrays.equals(next.outputRootIndexes, first.outputRootIndexes)
                            || next.sampleCount <= 0
                            || next.sampleCount > PersistentVulkanNormalNoiseEngine.CAPACITY - totalSamples
                            || next.sampleCount > next.x.length
                            || next.sampleCount > next.y.length
                            || next.sampleCount > next.z.length) break;
                    next = pendingGraphRoots.poll();
                    if (next == null) break;
                    requests.add(next);
                    totalSamples += next.sampleCount;
                    recordQueueWait(next.enqueuedNanos, QueueClass.GRAPH_ROOTS);
                }
            }
        }
        if (requests.size() == 1) {
            try {
                submitGraphRootsAsync(slot, first)
                        .whenComplete((result, failure) ->
                                completeOnDispatchWorker(first.future, result, failure));
            } catch (Throwable failure) {
                first.future.completeExceptionally(new IllegalStateException(
                        "GPU ring graph-roots submission failed", failure));
            }
            return;
        }

        float[] x = new float[totalSamples];
        float[] y = new float[totalSamples];
        float[] z = new float[totalSamples];
        int offset = 0;
        for (GraphRootsRequest request : requests) {
            System.arraycopy(request.x, 0, x, offset, request.sampleCount);
            System.arraycopy(request.y, 0, y, offset, request.sampleCount);
            System.arraycopy(request.z, 0, z, offset, request.sampleCount);
            offset += request.sampleCount;
        }
        int batchSamples = totalSamples;
        try {
            slot.submitGraphRootsAsync(first.graph, x, y, z, batchSamples).whenComplete((result, failure) -> {
                if (failure != null) {
                    for (GraphRootsRequest request : requests)
                        completeOnDispatchWorker(request.future, null, failure);
                    return;
                }
                int roots = first.outputRootIndexes == null
                        ? first.graph.routerRootCount() : first.outputRootIndexes.length;
                int expected = Math.multiplyExact(batchSamples, roots);
                if (result == null || result.length != expected) {
                    IllegalStateException invalid = new IllegalStateException(
                            "Vulkan graph-roots batch returned "
                                    + (result == null ? "null" : result.length)
                                    + " values for " + batchSamples + " samples and " + roots + " roots");
                    for (GraphRootsRequest request : requests)
                        completeOnDispatchWorker(request.future, null, invalid);
                    return;
                }
                int sampleOffset = 0;
                for (GraphRootsRequest request : requests) {
                    int valueOffset = Math.multiplyExact(sampleOffset, roots);
                    int valueEnd = Math.addExact(valueOffset,
                            Math.multiplyExact(request.sampleCount, roots));
                    completeOnDispatchWorker(request.future,
                            java.util.Arrays.copyOfRange(result, valueOffset, valueEnd), null);
                    sampleOffset += request.sampleCount;
                }
            });
        } catch (Throwable failure) {
            for (GraphRootsRequest request : requests)
                request.future.completeExceptionally(new IllegalStateException(
                        "GPU ring graph-roots batch submission failed", failure));
        }
    }

    private static CompletableFuture<float[]> submitGraphRootsAsync(PersistentVulkanNormalNoiseEngine slot,
                                                                      GraphRootsRequest request) throws Exception {
        if (request.outputRootIndexes == null)
            return slot.submitGraphRootsAsync(request.graph, request.x, request.y, request.z, request.sampleCount);
        return slot.submitGraphRootsAsync(request.graph, request.x, request.y, request.z,
                request.sampleCount, request.outputRootIndexes);
    }

    private static CompletableFuture<float[]> submitGraphRootsAsync(PersistentVulkanNormalNoiseEngine slot,
                                                                      GraphRootsRequest request, float[] x,
                                                                      float[] y, float[] z, int sampleCount)
            throws Exception {
        if (request.outputRootIndexes == null)
            return slot.submitGraphRootsAsync(request.graph, x, y, z, sampleCount);
        return slot.submitGraphRootsAsync(request.graph, x, y, z, sampleCount, request.outputRootIndexes);
    }

    @SuppressWarnings("unchecked")
    private <T> void runBlockingReserved(BlockingRequest<?> raw, PersistentVulkanNormalNoiseEngine slot)
            throws Exception {
        BlockingRequest<T> request = (BlockingRequest<T>) raw;
        request.future.complete(request.operation.call(slot));
    }

    /** Coalesces up to sixteen independent chunk structure inputs into one device pass. */
    private void executeBeardifierBatch(PersistentVulkanNormalNoiseEngine slot, BeardifierRequest first) {
        List<BeardifierRequest> requests = new ArrayList<>();
        requests.add(first);
        int totalSamples = first.count;
        long totalBytes = first.records.remaining();
        synchronized (this) {
            while (requests.size() < GpuBeardifierInputs.MAX_BATCH_CHUNKS
                    && totalSamples < GpuBeardifierInputs.MAX_SAMPLES) {
                BeardifierRequest next = pendingBeardifier.peek();
                if (next == null || next.future.isDone()
                        || next.count <= 0 || next.count > GpuBeardifierInputs.MAX_SAMPLES - totalSamples
                        || totalBytes + next.records.remaining() > MAX_COALESCED_INPUT_BYTES) break;
                next = pendingBeardifier.poll();
                if (next == null) break;
                requests.add(next);
                totalSamples += next.count;
                totalBytes += next.records.remaining();
                recordQueueWait(next.enqueuedNanos, QueueClass.AQUIFER);
            }
        }

        ByteBuffer combined = null;
        try {
            List<ByteBuffer> payloads = new ArrayList<>(requests.size());
            int[] sampleCounts = new int[requests.size()];
            for (int i = 0; i < requests.size(); i++) {
                payloads.add(requests.get(i).records);
                sampleCounts[i] = requests.get(i).count;
            }
            combined = GpuBeardifierInputs.packBatch(payloads, sampleCounts);
            float[] result = slot.submitBeardifier(combined, totalSamples);
            if (result == null || result.length != totalSamples)
                throw new IllegalStateException("Vulkan Beardifier batch returned "
                        + (result == null ? "null" : result.length) + " values for " + totalSamples + " samples");
            int offset = 0;
            for (BeardifierRequest request : requests) {
                int end = Math.addExact(offset, request.count);
                completeOnDispatchWorker(request.future,
                        new GpuBeardifierOverlay(result, offset, request.count), null);
                offset = end;
            }
        } catch (Throwable failure) {
            for (BeardifierRequest request : requests)
                request.future.completeExceptionally(new IllegalStateException(
                        "GPU ring Beardifier batch submission failed", failure));
        } finally {
            if (combined != null) GpuTerrainInputBufferPool.release(combined);
        }
    }

    private void executeAquiferReserved(PersistentVulkanNormalNoiseEngine slot, DispatchRequest request) {
        if (request instanceof AquiferPrefillRequest value) {
            executeAquiferPrefillBatch(slot, value);
        } else if (request instanceof BeardifierRequest value) {
            executeBeardifierBatch(slot, value);
        } else if (request instanceof AquiferNearestRequest value) {
            executeAquiferNearestBatch(slot, value);
        } else if (request instanceof AquiferSubstanceRequest value) {
            executeAquiferSubstanceBatch(slot, value);
        } else if (request instanceof AquiferFluidStatusRequest value) {
            dispatchAquiferAsync(slot, "aquifer fluid-status", value.future,
                    engine -> engine.submitAquiferFluidStatusAsync(value.records, value.count));
        } else {
            throw new IllegalArgumentException("unknown aquifer request type " + request.getClass().getName());
        }
    }

    /** Coalesces chunk-local ore rows into one bounded device dispatch. */
    private void executeOreVeinBatch(PersistentVulkanNormalNoiseEngine slot, OreVeinRequest first) {
        List<OreVeinRequest> requests = new ArrayList<>();
        requests.add(first);
        int totalRows = first.count;
        int totalBytes = first.records.remaining();
        int maxRows = PersistentVulkanNormalNoiseEngine.CAPACITY;
        synchronized (this) {
            while (totalRows < maxRows) {
                OreVeinRequest next = pendingOreVein.peek();
                if (next == null || next.future.isDone()
                        || next.count <= 0 || next.count > maxRows - totalRows
                        || next.records.remaining() != next.count * GpuOreVeinDecisions.RECORD_BYTES) break;
                next = pendingOreVein.poll();
                if (next == null) break;
                requests.add(next);
                totalRows += next.count;
                totalBytes += next.records.remaining();
                recordQueueWait(next.enqueuedNanos, QueueClass.ORE_VEIN);
            }
        }

        boolean merged = requests.size() > 1;
        int dispatchedRows = totalRows;
        ByteBuffer combined = null;
        try {
            combined = merged ? GpuTerrainInputBufferPool.acquire(totalBytes)
                    : first.records.duplicate();
            if (merged) {
                for (OreVeinRequest request : requests) combined.put(request.records.duplicate());
                combined.flip();
            }
            slot.submitOreVeinDecisionsAsync(combined, dispatchedRows).whenComplete((packedRows, failure) -> {
                if (failure != null) {
                    for (OreVeinRequest request : requests)
                        completeOnDispatchWorker(request.future, null, new IllegalStateException(
                                "GPU ring ore-vein batch submission failed", failure));
                    return;
                }
                int expectedWords = (dispatchedRows + 9) / 10;
                if (packedRows == null || packedRows.length != expectedWords) {
                    IllegalStateException invalid = new IllegalStateException("Vulkan ore-vein batch returned "
                            + (packedRows == null ? "null" : packedRows.length) + " packed words for "
                            + dispatchedRows + " inputs (expected " + expectedWords + ")");
                    for (OreVeinRequest request : requests)
                        completeOnDispatchWorker(request.future, null, invalid);
                    return;
                }
                GpuTerrainProfiler.recordOreVeinBatch(dispatchedRows, requests.size());
                int offset = 0;
                for (OreVeinRequest request : requests) {
                    completeOnDispatchWorker(request.future,
                            new GpuOreVeinBatchResult(packedRows, dispatchedRows, offset, request.count), null);
                    offset += request.count;
                }
            });
        } catch (Throwable failure) {
            for (OreVeinRequest request : requests) {
                completeOnDispatchWorker(request.future, null, new IllegalStateException(
                        "GPU ring ore-vein batch submission failed", failure));
            }
        } finally {
            if (merged && combined != null) GpuTerrainInputBufferPool.release(combined);
        }
    }

    /** Merges compatible nearest-center payloads while preserving each query's neighborhood index. */
    private void executeAquiferNearestBatch(PersistentVulkanNormalNoiseEngine slot,
                                             AquiferNearestRequest first) {
        List<AquiferNearestRequest> requests = new ArrayList<>();
        requests.add(first);
        int totalRows = first.count;
        int totalGroups = GpuAquiferNearestThree.isValidPacked(first.records, first.count)
                ? first.records.duplicate().order(java.nio.ByteOrder.nativeOrder())
                        .getInt(first.records.position() + Integer.BYTES)
                : -1;
        long totalBytes = first.records.remaining();
        if (totalGroups > 0 && totalBytes <= MAX_COALESCED_INPUT_BYTES) {
            synchronized (this) {
                while (totalRows < GpuAquiferNearestThree.MAX_QUERIES) {
                    AquiferNearestRequest next = pendingAquiferNearest.peek();
                    if (next == null || next.future.isDone()
                            || !GpuAquiferNearestThree.isValidPacked(next.records, next.count)
                            || next.count > GpuAquiferNearestThree.MAX_QUERIES - totalRows) break;
                    ByteBuffer nextRecords = next.records.duplicate().order(java.nio.ByteOrder.nativeOrder());
                    int nextGroups = nextRecords.getInt(next.records.position() + Integer.BYTES);
                    long nextRows = (long) totalRows + next.count;
                    long nextGroupCount = (long) totalGroups + nextGroups;
                    long nextBytes = GpuAquiferNearestThree.HEADER_BYTES
                            + nextRows * GpuAquiferNearestThree.QUERY_RECORD_BYTES
                            + nextGroupCount * GpuAquiferNearestThree.CENTER_RECORD_BYTES;
                    if (nextBytes > MAX_COALESCED_INPUT_BYTES) break;
                    next = pendingAquiferNearest.poll();
                    if (next == null) break;
                    requests.add(next);
                    totalRows += next.count;
                    totalGroups += nextGroups;
                    recordQueueWait(next.enqueuedNanos, QueueClass.AQUIFER);
                }
            }
        }
        if (requests.size() == 1) {
            try {
                slot.submitAquiferNearestThreeAsync(first.records, first.count).whenComplete((rows, failure) -> {
                    if (failure == null) GpuTerrainProfiler.recordAquiferNearestBatch(first.count);
                    completeOnDispatchWorker(first.future, rows, failure);
                });
            } catch (Throwable failure) {
                first.future.completeExceptionally(new IllegalStateException(
                        "GPU ring aquifer nearest-three submission failed", failure));
            }
            return;
        }

        int batchRowCount = totalRows;
        List<NearestPayload> payloads = new ArrayList<>(requests.size());
        for (AquiferNearestRequest request : requests)
            payloads.add(new NearestPayload(request.records, request.count, 0));
        ByteBuffer combined;
        try {
            combined = combineNearestPayloads(payloads);
        } catch (Throwable failure) {
            for (AquiferNearestRequest request : requests)
                completeOnDispatchWorker(request.future, null, new IllegalStateException(
                        "GPU ring nearest-center batch packing failed", failure));
            return;
        }
        try {
            slot.submitAquiferNearestThreeAsync(combined, batchRowCount).whenComplete((rows, failure) -> {
                GpuTerrainInputBufferPool.release(combined);
                if (failure != null) {
                    for (AquiferNearestRequest request : requests)
                        completeOnDispatchWorker(request.future, null, failure);
                    return;
                }
                if (rows == null || rows.length != batchRowCount * GpuAquiferNearestThree.OUTPUT_INTS) {
                    IllegalStateException invalid = new IllegalStateException(
                            "Vulkan nearest-center batch returned "
                                    + (rows == null ? "null" : rows.length)
                                    + " values for " + batchRowCount + " queries");
                    for (AquiferNearestRequest request : requests)
                        completeOnDispatchWorker(request.future, null, invalid);
                    return;
                }
                GpuTerrainProfiler.recordAquiferNearestBatch(batchRowCount);
                GpuTerrainProfiler.recordAquiferNearestCoalesced(requests.size());
                int offset = 0;
                for (AquiferNearestRequest request : requests) {
                    int valueCount = request.count * GpuAquiferNearestThree.OUTPUT_INTS;
                    int[] chunkRows = java.util.Arrays.copyOfRange(rows, offset, offset + valueCount);
                    completeOnDispatchWorker(request.future, chunkRows, null);
                    offset += valueCount;
                }
            });
        } catch (Throwable failure) {
            GpuTerrainInputBufferPool.release(combined);
            for (AquiferNearestRequest request : requests)
                completeOnDispatchWorker(request.future, null, new IllegalStateException(
                        "GPU ring nearest-center batch submission failed", failure));
        }
    }

    private static ByteBuffer combineNearestPayloads(List<NearestPayload> payloads) {
        long rowCount = 0;
        long groupCount = 0;
        for (NearestPayload payload : payloads) {
            if (!GpuAquiferNearestThree.isValidPacked(payload.records, payload.count))
                throw new IllegalArgumentException("invalid nearest-center payload in coalesced batch");
            ByteBuffer source = payload.records.duplicate().order(java.nio.ByteOrder.nativeOrder());
            int groups = source.getInt(payload.records.position() + Integer.BYTES);
            rowCount += payload.count;
            groupCount += groups;
        }
        if (rowCount <= 0 || rowCount > GpuAquiferNearestThree.MAX_QUERIES
                || groupCount <= 0 || groupCount > rowCount)
            throw new IllegalArgumentException("combined nearest-center rows exceed GPU limits");
        long bytes = GpuAquiferNearestThree.HEADER_BYTES
                + rowCount * GpuAquiferNearestThree.QUERY_RECORD_BYTES
                + groupCount * GpuAquiferNearestThree.CENTER_RECORD_BYTES;
        if (bytes > MAX_COALESCED_INPUT_BYTES || bytes > Integer.MAX_VALUE)
            throw new IllegalArgumentException("combined nearest-center input exceeds bounded staging budget");

        ByteBuffer combined = GpuTerrainInputBufferPool.acquire((int) bytes);
        try {
            combined.putInt((int) rowCount).putInt((int) groupCount);
            int groupOffset = 0;
            for (NearestPayload payload : payloads) {
                ByteBuffer source = payload.records.duplicate().order(java.nio.ByteOrder.nativeOrder());
                int sourcePosition = source.position();
                int groups = source.getInt(sourcePosition + Integer.BYTES);
                for (int row = 0; row < payload.count; row++) {
                    int queryOffset = sourcePosition + GpuAquiferNearestThree.HEADER_BYTES
                            + row * GpuAquiferNearestThree.QUERY_RECORD_BYTES;
                    combined.putInt(source.getInt(queryOffset))
                            .putInt(source.getInt(queryOffset + Integer.BYTES))
                            .putInt(source.getInt(queryOffset + Integer.BYTES * 2))
                            .putInt(source.getInt(queryOffset + Integer.BYTES * 3) + groupOffset)
                            .putInt(source.getInt(queryOffset + Integer.BYTES * 4))
                            .putInt(source.getInt(queryOffset + Integer.BYTES * 5))
                            .putInt(source.getInt(queryOffset + Integer.BYTES * 6));
                }
                int centersOffset = sourcePosition + GpuAquiferNearestThree.HEADER_BYTES
                        + payload.count * GpuAquiferNearestThree.QUERY_RECORD_BYTES;
                source.position(centersOffset);
                source.limit(centersOffset + groups * GpuAquiferNearestThree.CENTER_RECORD_BYTES);
                while (source.remaining() >= GpuAquiferNearestThree.CENTER_RECORD_BYTES) {
                    for (int center = 0; center < GpuAquiferNearestThree.CANDIDATES; center++) {
                        combined.putInt(source.getInt()).putInt(source.getInt()).putInt(source.getInt());
                        int statusRow = source.getInt();
                        combined.putInt(statusRow < 0 ? -1 : statusRow + payload.statusRowOffset);
                    }
                }
                groupOffset += groups;
            }
            return combined.flip();
        } catch (RuntimeException | Error failure) {
            GpuTerrainInputBufferPool.release(combined);
            throw failure;
        }
    }

    /** Coalesces C2ME-style nearest-center and status-prefill work across chunks. */
    private void executeAquiferPrefillBatch(PersistentVulkanNormalNoiseEngine slot,
                                             AquiferPrefillRequest first) {
        List<AquiferPrefillRequest> requests = new ArrayList<>();
        requests.add(first);
        int nearestRows = first.nearestCount;
        int statusRows = first.statusCount;
        int statusStride = aquiferStatusStride(first);
        long nearestGroups = validPrefill(first)
                ? nearestGroupCount(first.nearestRecords, first.nearestCount) : -1;
        if (nearestGroups > 0 && coalescedPrefillBytes(nearestRows, nearestGroups, statusRows, statusStride)
                <= MAX_COALESCED_INPUT_BYTES) {
            synchronized (this) {
                while (nearestRows < GpuAquiferNearestThree.MAX_QUERIES
                        && statusRows < GpuAquiferFluidStatus.MAX_PREFILL_CENTERS) {
                    AquiferPrefillRequest next = pendingAquiferPrefill.peek();
                    if (next == null || next.future.isDone() || !validPrefill(next)
                            || aquiferStatusStride(next) != statusStride
                            || next.decisionChain != first.decisionChain
                            || next.nearestCount > GpuAquiferNearestThree.MAX_QUERIES - nearestRows
                            || next.statusCount > GpuAquiferFluidStatus.MAX_PREFILL_CENTERS - statusRows)
                        break;
                    long nextGroups = nearestGroups
                            + nearestGroupCount(next.nearestRecords, next.nearestCount);
                    long bytes = coalescedPrefillBytes(nearestRows + next.nearestCount,
                            nextGroups, statusRows + next.statusCount, statusStride);
                    if (bytes > MAX_COALESCED_INPUT_BYTES) break;
                    next = pendingAquiferPrefill.poll();
                    if (next == null) break;
                    requests.add(next);
                    nearestRows += next.nearestCount;
                    statusRows += next.statusCount;
                    nearestGroups = nextGroups;
                    recordQueueWait(next.enqueuedNanos, QueueClass.AQUIFER);
                }
            }
        }
        if (requests.size() == 1) {
            try {
                slot.submitAquiferPrefillAsync(first.nearestRecords, first.nearestCount,
                        first.statusRecords, first.statusCount, first.decisionChain)
                        .whenComplete((rows, failure) -> {
                    if (failure == null) GpuTerrainProfiler.recordAquiferNearestBatch(first.nearestCount);
                    completeOnDispatchWorker(first.future, rows, failure);
                });
            } catch (Throwable failure) {
                first.future.completeExceptionally(new IllegalStateException(
                        "GPU ring combined aquifer prefill submission failed", failure));
            }
            return;
        }

        int batchNearestRows = nearestRows;
        int batchStatusRows = statusRows;
        List<NearestPayload> nearestPayloads = new ArrayList<>(requests.size());
        int statusRowOffset = 0;
        for (AquiferPrefillRequest request : requests) {
            nearestPayloads.add(new NearestPayload(
                    request.nearestRecords, request.nearestCount, statusRowOffset));
            statusRowOffset += request.statusCount;
        }
        ByteBuffer mergedNearest;
        try {
            mergedNearest = combineNearestPayloads(nearestPayloads);
        } catch (Throwable failure) {
            for (AquiferPrefillRequest request : requests)
                completeOnDispatchWorker(request.future, null, new IllegalStateException(
                        "GPU ring combined aquifer prefill packing failed", failure));
            return;
        }
        ByteBuffer mergedStatus;
        try {
            mergedStatus = GpuTerrainInputBufferPool.acquire(
                    Math.multiplyExact(batchStatusRows, statusStride));
        } catch (Throwable failure) {
            GpuTerrainInputBufferPool.release(mergedNearest);
            for (AquiferPrefillRequest request : requests)
                completeOnDispatchWorker(request.future, null, new IllegalStateException(
                        "GPU ring combined aquifer status packing failed", failure));
            return;
        }
        try {
            for (AquiferPrefillRequest request : requests)
                mergedStatus.put(request.statusRecords.duplicate());
            mergedStatus.flip();
            slot.submitAquiferPrefillAsync(mergedNearest, batchNearestRows,
                    mergedStatus, batchStatusRows, first.decisionChain).whenComplete((rows, failure) -> {
                GpuTerrainInputBufferPool.release(mergedNearest);
                GpuTerrainInputBufferPool.release(mergedStatus);
                if (failure != null) {
                    for (AquiferPrefillRequest request : requests)
                        completeOnDispatchWorker(request.future, null, failure);
                    return;
                }
                if (rows == null || rows.nearestValueCount()
                        != batchNearestRows * GpuAquiferNearestThree.OUTPUT_INTS
                        || rows.fluidStatusValueCount()
                        != batchStatusRows * GpuAquiferFluidStatus.OUTPUT_INTS
                        || rows.decisionValueCount()
                        != (first.decisionChain ? batchNearestRows * 3 : 0)) {
                    IllegalStateException invalid = new IllegalStateException(
                            "Vulkan combined aquifer prefill returned a mismatched row count");
                    for (AquiferPrefillRequest request : requests)
                        completeOnDispatchWorker(request.future, null, invalid);
                    return;
                }
                GpuTerrainProfiler.recordAquiferNearestBatch(batchNearestRows);
                GpuTerrainProfiler.recordAquiferNearestCoalesced(requests.size());
                int nearestOffset = 0;
                int statusOffset = 0;
                for (AquiferPrefillRequest request : requests) {
                    int nearestLength = request.nearestCount
                            * GpuAquiferNearestThree.OUTPUT_INTS;
                    int statusLength = request.statusCount
                            * GpuAquiferFluidStatus.OUTPUT_INTS;
                    int decisionStart = first.decisionChain
                            ? nearestOffset / GpuAquiferNearestThree.OUTPUT_INTS * 3 : 0;
                    int decisionLength = first.decisionChain ? request.nearestCount * 3 : 0;
                    GpuAquiferPrefillResult chunkResult = GpuAquiferPrefillResult.slice(rows,
                            nearestOffset, nearestLength, statusOffset, statusLength,
                            decisionStart, decisionLength);
                    completeOnDispatchWorker(request.future, chunkResult, null);
                    nearestOffset += nearestLength;
                    statusOffset += statusLength;
                }
            });
        } catch (Throwable failure) {
            GpuTerrainInputBufferPool.release(mergedNearest);
            GpuTerrainInputBufferPool.release(mergedStatus);
            for (AquiferPrefillRequest request : requests)
                completeOnDispatchWorker(request.future, null, new IllegalStateException(
                        "GPU ring combined aquifer prefill submission failed", failure));
        }
    }

    private static boolean validPrefill(AquiferPrefillRequest request) {
        return GpuAquiferNearestThree.isValidPacked(request.nearestRecords, request.nearestCount)
                && request.statusCount > 0
                && request.statusCount <= GpuAquiferFluidStatus.MAX_PREFILL_CENTERS
                && (request.statusRecords.remaining()
                        == request.statusCount * GpuAquiferFluidStatus.RECORD_BYTES
                    || request.statusRecords.remaining()
                        == request.statusCount * GpuAquiferFluidStatus.COMPACT_PRECOMPUTED_RECORD_BYTES);
    }

    private static int aquiferStatusStride(AquiferPrefillRequest request) {
        return request.statusCount <= 0 ? 0 : request.statusRecords.remaining() / request.statusCount;
    }

    private static int nearestGroupCount(ByteBuffer records, int count) {
        return records.duplicate().order(java.nio.ByteOrder.nativeOrder())
                .getInt(records.position() + Integer.BYTES);
    }

    private static long coalescedPrefillBytes(long nearestRows, long nearestGroups, long statusRows,
                                              int statusStride) {
        long nearestBytes = GpuAquiferNearestThree.HEADER_BYTES
                + nearestRows * GpuAquiferNearestThree.QUERY_RECORD_BYTES
                + nearestGroups * GpuAquiferNearestThree.CENTER_RECORD_BYTES;
        long statusOffset = (nearestBytes + 3L) & ~3L;
        return statusOffset + statusRows * statusStride;
    }

    /** Coalesces already-queued chunk decisions into one OCL-style area dispatch. */
    private void executeAquiferSubstanceBatch(PersistentVulkanNormalNoiseEngine slot,
                                               AquiferSubstanceRequest first) {
        List<AquiferSubstanceRequest> requests = new ArrayList<>();
        requests.add(first);
        int totalRows = first.count;
        if (totalRows > 0 && totalRows <= GpuAquiferSubstance.MAX_QUERIES
                && first.records.remaining() == totalRows * GpuAquiferSubstance.RECORD_BYTES) {
            synchronized (this) {
                while (totalRows < GpuAquiferSubstance.MAX_QUERIES) {
                    AquiferSubstanceRequest next = pendingAquiferSubstance.peek();
                    if (next == null || next.future.isDone()
                            || next.count <= 0
                            || next.count > GpuAquiferSubstance.MAX_QUERIES - totalRows
                            || next.records.remaining() != next.count * GpuAquiferSubstance.RECORD_BYTES) break;
                    next = pendingAquiferSubstance.poll();
                    if (next == null) break;
                    requests.add(next);
                    totalRows += next.count;
                    recordQueueWait(next.enqueuedNanos, QueueClass.AQUIFER);
                }
            }
        }
        if (requests.size() == 1) {
            try {
                slot.submitAquiferSubstanceAsync(first.records, first.count).whenComplete((rows, failure) -> {
                    if (failure == null) GpuTerrainProfiler.recordAquiferSubstanceBatch(first.count);
                    completeOnDispatchWorker(first.future, rows == null ? null
                            : GpuAquiferRowsView.whole(rows, GpuAquiferSubstance.OUTPUT_INTS), failure);
                });
            } catch (Throwable failure) {
                first.future.completeExceptionally(new IllegalStateException(
                        "GPU ring aquifer substance submission failed", failure));
            }
            return;
        }
        int batchRowCount = totalRows;

        ByteBuffer combined = GpuTerrainInputBufferPool.acquire(
                Math.multiplyExact(batchRowCount, GpuAquiferSubstance.RECORD_BYTES));
        try {
            for (AquiferSubstanceRequest request : requests) {
                ByteBuffer records = request.records.duplicate();
                combined.put(records);
            }
            combined.flip();
            slot.submitAquiferSubstanceAsync(combined, batchRowCount).whenComplete((rows, failure) -> {
                GpuTerrainInputBufferPool.release(combined);
                if (failure != null) {
                    for (AquiferSubstanceRequest request : requests)
                        completeOnDispatchWorker(request.future, null, failure);
                    return;
                }
                if (rows == null || rows.length != batchRowCount) {
                    IllegalStateException invalid = new IllegalStateException(
                            "Vulkan aquifer substance batch returned "
                                    + (rows == null ? "null" : rows.length)
                                    + " rows for " + batchRowCount + " inputs");
                    for (AquiferSubstanceRequest request : requests)
                        completeOnDispatchWorker(request.future, null, invalid);
                    return;
                }
                GpuTerrainProfiler.recordAquiferSubstanceBatch(batchRowCount);
                GpuTerrainProfiler.recordAquiferSubstanceCoalesced(requests.size());
                int offset = 0;
                for (AquiferSubstanceRequest request : requests) {
                    completeOnDispatchWorker(request.future, new GpuAquiferRowsView(rows, offset,
                            request.count, GpuAquiferSubstance.OUTPUT_INTS), null);
                    offset += request.count;
                }
            });
        } catch (Throwable failure) {
            GpuTerrainInputBufferPool.release(combined);
            for (AquiferSubstanceRequest request : requests)
                completeOnDispatchWorker(request.future, null, new IllegalStateException(
                        "GPU ring aquifer substance batch submission failed", failure));
        }
    }

    private static void failDispatch(ReservedDispatch dispatch, Throwable failure) {
        CompletableFuture<?> future = requestFuture(dispatch.request);
        if (future != null) future.completeExceptionally(new IllegalStateException(
                "GPU ring " + dispatch.requestClass + " dispatch failed", failure));
    }

    /** Borrow pinned startup slots without blocking the bootstrap or submitter thread. */
    private synchronized void drainParityRequests() {
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
            reservedSlots.add(slot);
            dispatchParityRequest(request, slot);
        }
    }

    private int selectIdleSlot(GpuDevice target) {
        for (int offset = 0; offset < slots.length; offset++) {
            int index = (nextSlotIndex + offset) % slots.length;
            PersistentVulkanNormalNoiseEngine slot = slots[index];
            if (!slotDevices[index].equals(target) || readbackScheduled[index].get()
                    || slot.hasInFlightWork() || reservedSlots.contains(slot)) continue;
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
                    releaseReservedSlot(slot);
                    request.future.completeExceptionally(new IllegalStateException(
                            "Vulkan " + request.description + " parity dispatch failed on "
                                    + request.target.name(), failure));
                    return;
                }
                releaseReservedSlot(slot);
                submitted.whenComplete((value, failure) -> {
                    Throwable reportedFailure = failure == null ? null
                            : new IllegalStateException("Vulkan " + request.description
                                    + " parity failed on " + request.target.name(), failure);
                    completeOnDispatchWorker(request.future, value, reportedFailure);
                });
            });
        } catch (RuntimeException stopped) {
            releaseReservedSlot(slot);
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
            if (readbackScheduled[index].get() || slot.hasInFlightWork() || reservedSlots.contains(slot)) continue;
            int deviceSlots = 0;
            int freeDeviceSlots = 0;
            GpuDevice candidateDevice = slotDevices[index];
            for (int other = 0; other < slots.length; other++) {
                if (!slotDevices[other].equals(candidateDevice)) continue;
                deviceSlots++;
                PersistentVulkanNormalNoiseEngine otherSlot = slots[other];
                if (!readbackScheduled[other].get() && !otherSlot.hasInFlightWork()
                        && !reservedSlots.contains(otherSlot)) freeDeviceSlots++;
            }
            double freeRatio = deviceSlots == 0 ? 0.0 : (double) freeDeviceSlots / deviceSlots;
            if (freeRatio > bestFreeRatio) {
                best = index;
                bestFreeRatio = freeRatio;
            }
        }
        return best;
    }

    private enum QueueClass { GRAPH_ROOTS, GRAPH_GRIDS, FULL_TERRAIN, BLOCKING, AQUIFER, ORE_VEIN }

    private sealed interface DispatchRequest permits GraphRootsRequest, GraphRootsGridRequest,
            TerrainGridRequest, BlockingRequest, AquiferNearestRequest, AquiferSubstanceRequest,
            AquiferFluidStatusRequest, AquiferPrefillRequest, BeardifierRequest, OreVeinRequest {
        CompletableFuture<?> future();
        long enqueuedNanos();
    }

    private record ReservedDispatch(PersistentVulkanNormalNoiseEngine slot,
                                    QueueClass requestClass, DispatchRequest request) {}

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

    private void releaseReservedSlot(PersistentVulkanNormalNoiseEngine slot) {
        synchronized (this) { reservedSlots.remove(slot); }
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

    CompletableFuture<GpuAquiferRowsView> submitAquiferSubstance(ByteBuffer records, int count) {
        CompletableFuture<GpuAquiferRowsView> future = new CompletableFuture<>();
        enqueue(pendingAquiferSubstance,
                () -> new AquiferSubstanceRequest(records.duplicate(), count, future, System.nanoTime()),
                future, "aquifer substance");
        return future;
    }

    CompletableFuture<GpuOreVeinBatchResult> submitOreVeinDecisions(ByteBuffer records, int count) {
        CompletableFuture<GpuOreVeinBatchResult> future = new CompletableFuture<>();
        if (records == null || count <= 0 || count > PersistentVulkanNormalNoiseEngine.CAPACITY
                || records.remaining() != (long) count * GpuOreVeinDecisions.RECORD_BYTES) {
            future.completeExceptionally(new IllegalArgumentException("invalid ore-vein decision request"));
            return future;
        }
        enqueue(pendingOreVein, () -> new OreVeinRequest(records.duplicate(), count,
                future, System.nanoTime()), future, "ore-vein decisions");
        return future;
    }

    CompletableFuture<int[]> submitBiomeSearch(ByteBuffer targets, GpuBiomeSearchTree tree, int count) {
        if (tree == null) return CompletableFuture.failedFuture(
                new IllegalArgumentException("multi-noise biome tree is required"));
        ByteBuffer queryData = targets.duplicate();
        return submitBlocking("multi-noise biome search", slot -> slot.submitBiomeSearch(
                queryData, tree, count));
    }

    CompletableFuture<int[]> submitAquiferFluidStatus(ByteBuffer records, int count) {
        CompletableFuture<int[]> future = new CompletableFuture<>();
        enqueue(pendingAquiferFluidStatus,
                () -> new AquiferFluidStatusRequest(records.duplicate(), count, future, System.nanoTime()),
                future, "aquifer fluid-status");
        return future;
    }

    CompletableFuture<GpuBeardifierOverlay> submitBeardifier(ByteBuffer records, int count) {
        CompletableFuture<GpuBeardifierOverlay> future = new CompletableFuture<>();
        if (!GpuBeardifierInputs.isValidPacked(records, count)) {
            future.completeExceptionally(new IllegalArgumentException("invalid GPU Beardifier chunk payload"));
            return future;
        }
        enqueue(pendingBeardifier, () -> new BeardifierRequest(records.duplicate(), count,
                future, System.nanoTime()), future, "GPU Beardifier");
        return future;
    }

    CompletableFuture<float[]> submitBeardifierBatch(List<ByteBuffer> chunks, int[] sampleCounts) {
        if (chunks == null || sampleCounts == null) return CompletableFuture.failedFuture(
                new IllegalArgumentException("GPU Beardifier batch inputs are required"));
        int totalSamples = 0;
        for (int count : sampleCounts) totalSamples = Math.addExact(totalSamples, count);
        ByteBuffer batch = GpuBeardifierInputs.packBatch(chunks, sampleCounts);
        int batchSampleCount = totalSamples;
        CompletableFuture<float[]> future = submitBlocking("GPU Beardifier test batch",
                slot -> slot.submitBeardifier(batch, batchSampleCount));
        future.whenComplete((ignored, failure) -> GpuTerrainInputBufferPool.release(batch));
        return future;
    }

    CompletableFuture<GpuAquiferPrefillResult> submitAquiferPrefill(
            ByteBuffer nearestRecords, int nearestCount, ByteBuffer statusRecords, int statusCount) {
        return submitAquiferPrefill(nearestRecords, nearestCount, statusRecords, statusCount, false);
    }

    CompletableFuture<GpuAquiferPrefillResult> submitAquiferPrefill(
            ByteBuffer nearestRecords, int nearestCount, ByteBuffer statusRecords, int statusCount,
            boolean decisionChain) {
        CompletableFuture<GpuAquiferPrefillResult> future = new CompletableFuture<>();
        enqueue(pendingAquiferPrefill, () -> new AquiferPrefillRequest(nearestRecords.duplicate(), nearestCount,
                statusRecords.duplicate(), statusCount, decisionChain, future, System.nanoTime()),
                future, "aquifer prefill");
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
        int[] cornerRootIndexes = new int[graph.routerRootCount()];
        for (int i = 0; i < cornerRootIndexes.length; i++) cornerRootIndexes[i] = i;
        return submitGraphGridInterpolatedOwned(graph, gridMetadata, chunkCount, samplesPerChunk,
                outputMetadata, blocksPerChunk, outputRootIndexes, cornerRootIndexes);
    }

    /** Compact the host corner result to roots already admitted by terrain parity. */
    CompletableFuture<GpuTerrainBatchResult> submitGraphGridInterpolatedOwned(
            GpuDensityGraph graph, ByteBuffer gridMetadata, int chunkCount, int samplesPerChunk,
            ByteBuffer outputMetadata, int blocksPerChunk, int[] outputRootIndexes,
            int[] cornerRootIndexes) {
        return submitGraphGridInterpolated(graph, gridMetadata, chunkCount, samplesPerChunk,
                outputMetadata, blocksPerChunk, outputRootIndexes, cornerRootIndexes, false);
    }

    private CompletableFuture<GpuTerrainBatchResult> submitGraphGridInterpolated(
            GpuDensityGraph graph, ByteBuffer gridMetadata, int chunkCount, int samplesPerChunk,
            ByteBuffer outputMetadata, int blocksPerChunk, int[] outputRootIndexes,
            boolean copyMetadata) {
        CompletableFuture<GpuTerrainBatchResult> future = new CompletableFuture<>();
        enqueue(pendingTerrainGrids, () -> new TerrainGridRequest(graph,
                copyMetadata ? snapshot(gridMetadata) : gridMetadata, chunkCount,
                samplesPerChunk, copyMetadata ? snapshot(outputMetadata) : outputMetadata,
                blocksPerChunk, outputRootIndexes.clone(),
                allRouterRoots(graph), future,
                System.nanoTime()),
                future, "full terrain grid");
        return future;
    }

    private CompletableFuture<GpuTerrainBatchResult> submitGraphGridInterpolated(
            GpuDensityGraph graph, ByteBuffer gridMetadata, int chunkCount, int samplesPerChunk,
            ByteBuffer outputMetadata, int blocksPerChunk, int[] outputRootIndexes,
            int[] cornerRootIndexes, boolean copyMetadata) {
        CompletableFuture<GpuTerrainBatchResult> future = new CompletableFuture<>();
        enqueue(pendingTerrainGrids, () -> new TerrainGridRequest(graph,
                copyMetadata ? snapshot(gridMetadata) : gridMetadata, chunkCount,
                samplesPerChunk, copyMetadata ? snapshot(outputMetadata) : outputMetadata,
                blocksPerChunk, outputRootIndexes.clone(), cornerRootIndexes.clone(), future,
                System.nanoTime()), future, "full terrain grid");
        return future;
    }

    private static int[] allRouterRoots(GpuDensityGraph graph) {
        int[] roots = new int[graph.routerRootCount()];
        for (int i = 0; i < roots.length; i++) roots[i] = i;
        return roots;
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
        BeardifierRequest beardifierRequest;
        while ((beardifierRequest = pendingBeardifier.poll()) != null)
            beardifierRequest.future.completeExceptionally(stopped);
        OreVeinRequest oreVeinRequest;
        while ((oreVeinRequest = pendingOreVein.poll()) != null)
            oreVeinRequest.future.completeExceptionally(stopped);
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
                                     int sampleCount, int[] outputRootIndexes,
                                     CompletableFuture<float[]> future, long enqueuedNanos)
            implements DispatchRequest {}
    private record GraphRootsGridRequest(GpuDensityGraph graph, ByteBuffer metadata, int chunkCount, int samplesPerChunk,
                                         CompletableFuture<float[]> future, long enqueuedNanos)
            implements DispatchRequest {}
    private record TerrainGridRequest(GpuDensityGraph graph, ByteBuffer gridMetadata, int chunkCount,
                                      int samplesPerChunk, ByteBuffer outputMetadata, int blocksPerChunk,
                                      int[] outputRootIndexes, int[] cornerRootIndexes,
                                      CompletableFuture<GpuTerrainBatchResult> future,
                                      long enqueuedNanos) implements DispatchRequest {}
    private record AquiferNearestRequest(ByteBuffer records, int count, CompletableFuture<int[]> future,
                                        long enqueuedNanos) implements DispatchRequest {}
    private record AquiferSubstanceRequest(ByteBuffer records, int count,
                                          CompletableFuture<GpuAquiferRowsView> future,
                                           long enqueuedNanos) implements DispatchRequest {}
    private record AquiferFluidStatusRequest(ByteBuffer records, int count, CompletableFuture<int[]> future,
                                             long enqueuedNanos) implements DispatchRequest {}
    private record AquiferPrefillRequest(ByteBuffer nearestRecords, int nearestCount,
                                         ByteBuffer statusRecords, int statusCount,
                                         boolean decisionChain,
                                         CompletableFuture<GpuAquiferPrefillResult> future, long enqueuedNanos)
            implements DispatchRequest {}
    private record BeardifierRequest(ByteBuffer records, int count, CompletableFuture<GpuBeardifierOverlay> future,
                                     long enqueuedNanos) implements DispatchRequest {}
    private record OreVeinRequest(ByteBuffer records, int count,
                                  CompletableFuture<GpuOreVeinBatchResult> future,
                                  long enqueuedNanos) implements DispatchRequest {}
    private record NearestPayload(ByteBuffer records, int count, int statusRowOffset) {}
    @FunctionalInterface
    private interface SlotOperation<T> {
        T call(PersistentVulkanNormalNoiseEngine slot) throws Exception;
    }
    private record BlockingRequest<T>(String description, SlotOperation<T> operation,
                                      CompletableFuture<T> future, long enqueuedNanos)
            implements DispatchRequest {}
    private record ParityRequest<T>(GpuDevice target, String description,
                                    SlotOperation<CompletableFuture<T>> operation,
                                    CompletableFuture<T> future) {}
}
