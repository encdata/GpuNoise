package dev.gpunoise.mixin;

import dev.gpunoise.GpuChunkDensityCache;
import dev.gpunoise.GpuAquiferDecisionAccess;
import dev.gpunoise.GpuNoiseChunkAccess;
import dev.gpunoise.GpuTerrainRuntime;
import dev.gpunoise.GpuTerrainRoots;
import dev.gpunoise.GpuAquiferCoordinatePlanner;
import dev.gpunoise.GpuAquiferNearestThree;
import dev.gpunoise.GpuAquiferRuntimeMode;
import dev.gpunoise.GpuAquiferSubstance;
import dev.gpunoise.GpuAquiferInputBufferPool;
import dev.gpunoise.GpuAquiferFluidStatus;
import dev.gpunoise.GpuAquiferPrefillResult;
import dev.gpunoise.GpuAquiferStatusRegionCache;
import dev.gpunoise.GpuAquiferDecisionData.AquiferQueries;
import dev.gpunoise.GpuAquiferDecisionData.FluidStatusCache;
import dev.gpunoise.GpuAquiferDecisionData.NeighborhoodCache;
import dev.gpunoise.GpuAquiferDecisionData.PendingDecisions;
import dev.gpunoise.GpuAquiferDecisionTable;
import dev.gpunoise.GpuWorldgenDependency;
import dev.gpunoise.GpuTerrainProfiler;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.gen.Invoker;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.Objects;
import java.lang.reflect.Field;

/** Feeds the GPU floodedness root into vanilla's aquifer surface calculation. */
// C2ME also rewrites computeSurfaceLevel. Apply this small value substitution
// before its aquifer mixin so both transformations can coexist.
@Mixin(value = Aquifer.NoiseBasedAquifer.class, priority = 1200)
abstract class AquiferGpuInputsMixin implements GpuAquiferCoordinatePlanner, GpuAquiferDecisionAccess {
    @Shadow @Final private NoiseChunk noiseChunk;
    @Shadow @Final protected DensityFunction barrierNoise;
    @Shadow @Final private DensityFunction fluidLevelFloodednessNoise;
    @Shadow @Final private DensityFunction fluidLevelSpreadNoise;
    @Shadow @Final protected DensityFunction lavaNoise;
    @Shadow @Final private DensityFunction erosion;
    @Shadow @Final private DensityFunction depth;
    @Shadow @Final private PositionalRandomFactory positionalRandomFactory;
    @Shadow @Final protected int minGridX;
    @Shadow @Final protected int minGridY;
    @Shadow @Final protected int minGridZ;
    @Shadow @Final protected int gridSizeX;
    @Shadow @Final protected int gridSizeZ;
    @Shadow @Final private long[] aquiferLocationCache;
    @Shadow @Final private Aquifer.FluidPicker globalFluidPicker;
    @Shadow private boolean shouldScheduleFluidUpdate;

    @org.spongepowered.asm.mixin.Unique
    private volatile GpuAquiferDecisionTable gpunoise$decisions;

    @org.spongepowered.asm.mixin.Unique
    private volatile CompletableFuture<Void> gpunoise$decisionBuild;

    @Override
    public GpuAquiferDecisionTable gpunoise$decisionTable() {
        return gpunoise$decisions;
    }

    @Override
    public CompletableFuture<Void> gpunoise$decisionBuild() {
        return gpunoise$decisionBuild;
    }

    @org.spongepowered.asm.mixin.Unique
    private volatile boolean gpunoise$decisionBuildFallback;

    @org.spongepowered.asm.mixin.Unique
    private static final AtomicBoolean gpunoise$aquiferBatchWarning = new AtomicBoolean();

    @org.spongepowered.asm.mixin.Unique
    private static final AtomicBoolean gpunoise$terrainDecisionBatchLogged = new AtomicBoolean();

    @org.spongepowered.asm.mixin.Unique
    private static final AtomicLong gpunoise$terrainDecisionQueryCount = new AtomicLong();

    @org.spongepowered.asm.mixin.Unique
    private static final AtomicLong gpunoise$terrainDecisionPositiveCount = new AtomicLong();

    @org.spongepowered.asm.mixin.Unique
    private static final AtomicBoolean gpunoise$aquiferPreflightLogged = new AtomicBoolean();

    @org.spongepowered.asm.mixin.Unique
    private static final AtomicBoolean gpunoise$c2meCutoffWarning = new AtomicBoolean();

    @org.spongepowered.asm.mixin.Unique
    private static volatile Field gpunoise$c2meCutoffField;

    @org.spongepowered.asm.mixin.Unique
    private static final AtomicBoolean gpunoise$exactAquiferInputsLogged = new AtomicBoolean();

    @org.spongepowered.asm.mixin.Unique
    private static final AtomicBoolean gpunoise$gpuAquiferInputsLogged = new AtomicBoolean();

    @org.spongepowered.asm.mixin.Unique
    private static final int gpunoise$aquiferDecisionConcurrency = (int) Math.max(1L,
            Math.min(8L, Long.getLong("gpunoise.aquiferDecisionConcurrency", 3L)));

    @org.spongepowered.asm.mixin.Unique
    private static final int gpunoise$aquiferDecisionQueueCapacity = (int) Math.max(16L,
            Math.min(1024L, Long.getLong("gpunoise.aquiferDecisionQueueCapacity", 128L)));

    @org.spongepowered.asm.mixin.Unique
    // The persistent Vulkan slot already owns buffers for its full row
    // capacity. Keep each normal chunk's nearest-center work in one dispatch
    // instead of fragmenting it into small 16k submissions.
    private static final int gpunoise$nearestQueriesPerDispatch = GpuAquiferNearestThree.MAX_QUERIES;

    @org.spongepowered.asm.mixin.Unique
    private static final int gpunoise$substanceRowsPerDispatch = GpuAquiferSubstance.MAX_QUERIES;

    @org.spongepowered.asm.mixin.Unique
    private static final ThreadPoolExecutor gpunoise$aquiferDecisionExecutor = new ThreadPoolExecutor(
            gpunoise$aquiferDecisionConcurrency, gpunoise$aquiferDecisionConcurrency,
            0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(gpunoise$aquiferDecisionQueueCapacity), r -> {
                Thread thread = new Thread(r, "GPU aquifer decision build");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());

    @org.spongepowered.asm.mixin.Unique
    private static final boolean gpunoise$verifyAquifer = Boolean.getBoolean("gpunoise.verifyAquiferGpu");

    @org.spongepowered.asm.mixin.Unique
    private static final int gpunoise$traceAquiferX = Integer.getInteger(
            "gpunoise.traceAquiferBlockX", Integer.MIN_VALUE);

    @org.spongepowered.asm.mixin.Unique
    private static final int gpunoise$traceAquiferY = Integer.getInteger(
            "gpunoise.traceAquiferBlockY", Integer.MIN_VALUE);

    @org.spongepowered.asm.mixin.Unique
    private static final int gpunoise$traceAquiferZ = Integer.getInteger(
            "gpunoise.traceAquiferBlockZ", Integer.MIN_VALUE);

    @Invoker("getAquiferStatus")
    protected abstract Aquifer.FluidStatus gpunoise$getAquiferStatus(long position);

    private void gpunoise$prepareGpuDecisions(GpuChunkDensityCache cache, NoiseChunk noiseChunk) {
        if (gpunoise$decisions != null) return;
        dev.gpunoise.GpuNoiseChunkAccess noiseAccess = gpunoise$validatedNoiseAccess(cache, noiseChunk);
        if (noiseAccess == null) return;
        long decisionBuildStart = GpuTerrainProfiler.beginPhase();
        try {
            gpunoise$buildGpuDecisionTable(cache, noiseAccess, positionalRandomFactory);
        } catch (RuntimeException failure) {
            gpunoise$decisions = null;
            if (gpunoise$aquiferBatchWarning.compareAndSet(false, true)) {
                dev.gpunoise.GpuNoise.LOGGER.warn(
                        "GPU aquifer decision batch unavailable; using vanilla aquifer decisions.", failure);
            }
        } finally {
            GpuTerrainProfiler.recordAquiferDecisionBuild(decisionBuildStart);
        }
    }

    @Override
    public void gpunoise$beginGpuDecisions(GpuChunkDensityCache cache, NoiseChunk noiseChunk) {
        if (gpunoise$decisions != null || cache == null || noiseChunk == null) return;
        if (gpunoise$validatedNoiseAccess(cache, noiseChunk) == null) return;
        synchronized (this) {
            if (gpunoise$decisions != null || gpunoise$decisionBuild != null) return;
            try {
                CompletableFuture<Void> build = CompletableFuture.runAsync(
                        () -> gpunoise$prepareGpuDecisions(cache, noiseChunk), gpunoise$aquiferDecisionExecutor);
                build.whenComplete((ignored, failure) -> {
                    if (failure != null || gpunoise$decisions == null) {
                        gpunoise$decisionBuildFallback = true;
                        GpuTerrainProfiler.recordAquiferAsyncDecisionFallback();
                    }
                });
                gpunoise$decisionBuild = build;
                GpuTerrainProfiler.recordAquiferDecisionQueue(
                        gpunoise$aquiferDecisionExecutor.getActiveCount(),
                        gpunoise$aquiferDecisionExecutor.getQueue().size());
            } catch (RuntimeException rejected) {
                GpuTerrainProfiler.recordAquiferAsyncDecisionFallback();
                if (gpunoise$aquiferBatchWarning.compareAndSet(false, true)) {
                    dev.gpunoise.GpuNoise.LOGGER.warn(
                            "GPU aquifer decision executor rejected a chunk; using vanilla aquifer decisions.", rejected);
                }
            }
        }
    }

    @org.spongepowered.asm.mixin.Unique
    private dev.gpunoise.GpuNoiseChunkAccess gpunoise$validatedNoiseAccess(
            GpuChunkDensityCache cache, NoiseChunk noiseChunk) {
        if (cache == null || noiseChunk == null
                || !(noiseChunk instanceof dev.gpunoise.GpuNoiseChunkAccess noiseAccess)) {
            gpunoise$logAquiferPreflightSkip("NoiseChunk GPU access bridge is missing");
            return null;
        }
        if (!noiseAccess.gpunoise$matchesGpuCache(cache)) {
            gpunoise$logAquiferPreflightSkip("GPU density cache does not match NoiseChunk coordinates");
            return null;
        }
        if (!cache.hasFullBlockOutput()) {
            gpunoise$logAquiferPreflightSkip("full block density output is unavailable");
            return null;
        }
        if (!cache.supportsRoot(GpuTerrainRoots.FINAL_DENSITY)) {
            gpunoise$logAquiferPreflightSkip("finalDensity root is unsupported");
            return null;
        }
        if (!cache.supportsRoot(GpuTerrainRoots.BARRIER)) {
            gpunoise$logAquiferPreflightSkip("aquifer barrier root is unsupported");
            return null;
        }
        return noiseAccess;
    }

    @org.spongepowered.asm.mixin.Unique
    private static void gpunoise$logAquiferPreflightSkip(String reason) {
        if (gpunoise$aquiferPreflightLogged.compareAndSet(false, true)) {
            dev.gpunoise.GpuNoise.LOGGER.info("GPU aquifer decision preflight skipped: {}", reason);
        }
    }

    @org.spongepowered.asm.mixin.Unique
    private Integer gpunoise$c2meSamplingCutoff() {
        if (!GpuAquiferRuntimeMode.c2meAquiferRewriteEnabled()) return null;
        try {
            Field cutoffField = gpunoise$c2meCutoffField;
            if (cutoffField == null) {
                synchronized (AquiferGpuInputsMixin.class) {
                    cutoffField = gpunoise$c2meCutoffField;
                    if (cutoffField == null) {
                        cutoffField = getClass().getDeclaredField("maxY");
                        if (!cutoffField.trySetAccessible()) {
                            throw new IllegalAccessException("NoiseBasedAquifer.maxY is inaccessible");
                        }
                        gpunoise$c2meCutoffField = cutoffField;
                    }
                }
            }
            Object value = cutoffField.get(this);
            return value instanceof Integer cutoff ? cutoff : null;
        } catch (ReflectiveOperationException | LinkageError unavailable) {
            if (gpunoise$c2meCutoffWarning.compareAndSet(false, true)) {
                dev.gpunoise.GpuNoise.LOGGER.warn(
                        "C2ME NoiseBasedAquifer.maxY cutoff is unavailable; keeping C2ME fluid decisions on Java",
                        unavailable);
            }
            return null;
        }
    }

    @Inject(method = "computeSubstance", at = @At("HEAD"), cancellable = true)
    private void gpunoise$usePreparedGpuAquiferDecision(
            DensityFunction.FunctionContext context, double density,
            CallbackInfoReturnable<BlockState> cir) {
        GpuAquiferDecisionTable decisions = gpunoise$decisions;
        if (decisions == null) {
            CompletableFuture<Void> build = gpunoise$decisionBuild;
            if (build != null && !gpunoise$decisionBuildFallback) {
                long waitStart = build.isDone() ? 0L : GpuTerrainProfiler.beginPhase();
                try {
                    build.join();
                } catch (CompletionException | CancellationException failure) {
                    if (gpunoise$aquiferBatchWarning.compareAndSet(false, true)) {
                        dev.gpunoise.GpuNoise.LOGGER.warn(
                                "Asynchronous GPU aquifer decision build failed; using vanilla decisions.", failure);
                    }
                } finally {
                    GpuTerrainProfiler.recordAquiferDecisionWait(waitStart);
                }
                decisions = gpunoise$decisions;
                if (decisions == null) gpunoise$decisionBuildFallback = true;
            }
        }
        if (decisions == null) {
            if (gpunoise$isTraceAquiferBlock(context.blockX(), context.blockY(), context.blockZ())) {
                dev.gpunoise.GpuNoise.LOGGER.info(
                        "GPU_AQUIFER_TRACE_VANILLA block=({}, {}, {}) reason=no-decision-table density={}",
                        context.blockX(), context.blockY(), context.blockZ(), density);
            }
            return;
        }
        int index = decisions.indexOfMatch(context.blockX(), context.blockY(), context.blockZ(), density);
        if (index < 0) {
            if (gpunoise$isTraceAquiferBlock(context.blockX(), context.blockY(), context.blockZ())) {
                dev.gpunoise.GpuNoise.LOGGER.info(
                        "GPU_AQUIFER_TRACE_VANILLA block=({}, {}, {}) reason=no-matching-decision density={}",
                        context.blockX(), context.blockY(), context.blockZ(), density);
            }
            return;
        }
        shouldScheduleFluidUpdate = decisions.schedulesFluidUpdate(index);
        if (gpunoise$isTraceAquiferBlock(context.blockX(), context.blockY(), context.blockZ())) {
            dev.gpunoise.GpuNoise.LOGGER.info(
                    "GPU_AQUIFER_TRACE_APPLIED block=({}, {}, {}) index={} state={} scheduleFluidUpdate={}",
                    context.blockX(), context.blockY(), context.blockZ(), index,
                    decisions.state(index), shouldScheduleFluidUpdate);
        }
        cir.setReturnValue(decisions.state(index));
    }

    @org.spongepowered.asm.mixin.Unique
    private void gpunoise$buildGpuDecisionTable(GpuChunkDensityCache cache,
                                                 dev.gpunoise.GpuNoiseChunkAccess noiseAccess,
                                                 PositionalRandomFactory randomFactory) {
        boolean c2meAquifer = GpuAquiferRuntimeMode.c2meAquiferRewriteEnabled();
        int c2meSamplingCutoff = Integer.MAX_VALUE;
        if (c2meAquifer) {
            Integer cutoff = gpunoise$c2meSamplingCutoff();
            if (cutoff == null) {
                gpunoise$logAquiferPreflightSkip(
                        "C2ME aquifer cutoff accessor is unavailable; keeping its fluid decisions on Java");
                return;
            }
            c2meSamplingCutoff = cutoff;
        }
        final int minX = cache.minX(), minY = cache.minY(), minZ = cache.minZ();
        final int height = cache.height();
        int decisionCapacity = Math.multiplyExact(height, 256);
        GpuAquiferDecisionTable decisions = new GpuAquiferDecisionTable(minX, minY, minZ, height);
        GpuChunkDensityCache.FinalDensityBlockView finalDensityBlockView = cache.finalDensityBlockView();
        AquiferQueries queries = new AquiferQueries(decisionCapacity);
        GpuAquiferSubstance.BatchWriter substanceWriter =
                new GpuAquiferSubstance.BatchWriter(decisionCapacity,
                        Math.min(decisionCapacity, gpunoise$substanceRowsPerDispatch));
        List<CompletableFuture<int[]>> substanceBatches = new ArrayList<>();
        PendingDecisions pending = new PendingDecisions(decisionCapacity, gpunoise$verifyAquifer);
        int submittedSubstanceRows = 0;
        NeighborhoodCache neighborhoods = new NeighborhoodCache(Math.max(16, (height + 11) / 12 * 4));
        FluidStatusCache fluidStatuses = new FluidStatusCache(Math.max(16, (height + 11) / 12 * 4));
        Map<Integer, Aquifer.FluidStatus> c2meGlobalFluidLevels = c2meAquifer ? new HashMap<>() : null;
        // Non-C2ME nearest-center selection is independent of the later
        // center-status and substance passes. Dispatch full records as soon
        // as they are packed so Vulkan can process them while this thread
        // continues scanning the remaining block inputs.
        List<CompletableFuture<int[]>> nearestBatches = new ArrayList<>();
        int submittedNearestQueries = 0;
        // C2ME OCL evaluates these density roots on its device. Use the same
        // strict GPU root cache here; exact Java evaluation remains an explicit
        // diagnostic mode and the fallback for missing cache coordinates.
        boolean exactAquiferInputs = GpuAquiferRuntimeMode.exactAquiferInputsEnabled();
        int directGlobalFluidDecisions = 0;
        int positiveDensityDecisions = 0;

        long inputScanStart = GpuTerrainProfiler.beginAquiferPhase();
        for (int y = minY; y < minY + height; y++) {
            for (int z = minZ; z < minZ + 16; z++) {
                for (int x = minX; x < minX + 16; x++) {
                    int localX = x - minX, localY = y - minY, localZ = z - minZ;
                    double cachedDensity = finalDensityBlockView != null
                            ? finalDensityBlockView.valueAtLocalUnchecked(localX, localY, localZ)
                            : cache.finalDensity(x, y, z);
                    double density = noiseAccess.gpunoise$withBeardifier(cachedDensity, x, y, z);
                    if (!Double.isFinite(density)) continue;
                    int index = (y - minY) * 256 + (z - minZ) * 16 + (x - minX);
                    if (density > 0.0) {
                        // Positive density never asks the aquifer for a block
                        // state. OreVeinifier and any mod material rules still
                        // require the vanilla rule list, so sending a null
                        // aquifer result through a separate Vulkan dispatch
                        // only adds records and readback with no writer benefit.
                        positiveDensityDecisions++;
                        continue;
                    }
                    // Only queued rows need a context for optional differential
                    // verification or exact Java barrier sampling.
                    DensityFunction.FunctionContext context = gpunoise$verifyAquifer
                            ? new DensityFunction.SinglePointContext(x, y, z) : null;
                    Aquifer.FluidStatus global = c2meAquifer
                            ? gpunoise$sampleGlobalFluidLevel(c2meGlobalFluidLevels, cache, y)
                            : globalFluidPicker.computeFluid(x, y, z);
                    BlockState globalState = global.at(y);
                    if (c2meAquifer && y > c2meSamplingCutoff) {
                        // C2ME OCL bypasses local aquifer pressure above its
                        // low-pass cutoff and returns the global picker state.
                        int globalStateId = Block.getId(globalState);
                        GpuAquiferSubstance.Input diagnosticInput = gpunoise$verifyAquifer
                                ? new GpuAquiferSubstance.Input(density, 0.0, y,
                                0, 0, 0, 0, 0, 0,
                                GpuAquiferSubstance.OTHER, GpuAquiferSubstance.OTHER,
                                GpuAquiferSubstance.OTHER, globalStateId,
                                true, false, globalStateId) : null;
                        substanceWriter.addDirectGlobalFluid(density, y, true,
                                globalStateId);
                        pending.add(index, globalState, context, density, diagnosticInput);
                        directGlobalFluidDecisions++;
                        submittedSubstanceRows = gpunoise$dispatchReadySubstanceRows(
                                pending, substanceWriter, substanceBatches, submittedSubstanceRows);
                        continue;
                    }
                    if (globalState.is(Blocks.LAVA)) {
                        GpuAquiferSubstance.Input diagnosticInput = gpunoise$verifyAquifer
                                ? new GpuAquiferSubstance.Input(density, 0.0, y,
                                0, 0, 0, 0, 0, 0,
                                GpuAquiferSubstance.OTHER, GpuAquiferSubstance.OTHER,
                                GpuAquiferSubstance.OTHER, Block.getId(globalState),
                                true, false, 0) : null;
                        substanceWriter.addDirectGlobalFluid(density, y, c2meAquifer,
                                Block.getId(globalState));
                        pending.add(index, globalState, context, density, diagnosticInput);
                        directGlobalFluidDecisions++;
                        submittedSubstanceRows = gpunoise$dispatchReadySubstanceRows(
                                pending, substanceWriter, substanceBatches, submittedSubstanceRows);
                        continue;
                    }

                    int gridX = Math.floorDiv(x - 5, 16);
                    int gridY = Math.floorDiv(y + 1, 12);
                    int gridZ = Math.floorDiv(z - 5, 16);
                    int[][] centers = neighborhoods.get(gridX, gridY, gridZ);
                    if (centers == null) {
                        centers = c2meAquifer
                                ? GpuAquiferNearestThree.centersForC2meQuery(randomFactory, x, y, z)
                                : GpuAquiferNearestThree.centersForQuery(randomFactory, x, y, z);
                        neighborhoods.put(gridX, gridY, gridZ, centers);
                    }
                    if (context == null && exactAquiferInputs) {
                        context = new DensityFunction.SinglePointContext(x, y, z);
                    }
                    long barrierStart = GpuTerrainProfiler.beginPhase();
                    double barrier = exactAquiferInputs ? barrierNoise.compute(context)
                            : cache.aquiferBarrier(x, y, z);
                    if (!exactAquiferInputs && Double.isFinite(barrier)) {
                        GpuTerrainProfiler.recordAquifer(GpuTerrainRoots.BARRIER, barrierStart);
                        GpuTerrainRuntime.markGpuCacheUsed(cache);
                        if (gpunoise$gpuAquiferInputsLogged.compareAndSet(false, true)) {
                            dev.gpunoise.GpuNoise.LOGGER.info(
                                    "GPU aquifer inputs use Vulkan cache roots; parity verifier compares GPU math with the Java double-precision decision reference");
                        }
                    }
                    if (!Double.isFinite(barrier)) {
                        if (context == null) context = new DensityFunction.SinglePointContext(x, y, z);
                        barrier = barrierNoise.compute(context);
                        if (!exactAquiferInputs)
                            GpuTerrainProfiler.recordJavaRootFallback(GpuTerrainRoots.BARRIER);
                    }
                    if (!Double.isFinite(barrier)) continue;
                    Aquifer.FluidStatus belowGlobal = c2meAquifer
                            ? gpunoise$sampleGlobalFluidLevel(c2meGlobalFluidLevels, cache, y - 1)
                            : globalFluidPicker.computeFluid(x, y - 1, z);
                    boolean belowIsLava = belowGlobal.at(y - 1).is(Blocks.LAVA);
                    queries.add(index, x, y, z, density, barrier, belowIsLava, centers);
                    if (!c2meAquifer
                            && queries.size() - submittedNearestQueries
                                    >= gpunoise$nearestQueriesPerDispatch) {
                        int count = queries.size() - submittedNearestQueries;
                        java.nio.ByteBuffer records = GpuAquiferNearestThree.packForAsync(
                                queries, submittedNearestQueries, count);
                        nearestBatches.add(gpunoise$submitNearestBatch(records, count));
                        submittedNearestQueries += count;
                    }
                }
            }
        }
        if (finalDensityBlockView != null) finalDensityBlockView.recordReads(decisionCapacity);
        GpuTerrainProfiler.recordAquiferPhase(GpuTerrainProfiler.AQUIFER_SCAN, inputScanStart);

        // C2ME OCL precomputes each random aquifer center's FluidStatus before
        // resolving block decisions. Mirror that bulk prefill: evaluate the
        // 13 surface probes, global fluid samples, and terrain roots together
        // on Vulkan, then retain the original Java statuses for custom fluids.
        long nearestStatusStart = GpuTerrainProfiler.beginAquiferPhase();
        boolean combineAquiferPasses = c2meAquifer && queries.size() > 0
                && queries.size() <= GpuAquiferNearestThree.MAX_QUERIES;
        if (combineAquiferPasses) {
            java.nio.ByteBuffer nearestRecords = GpuAquiferNearestThree.pack(queries, 0, queries.size());
            int[] rows = gpunoise$prefillC2meFluidStatuses(cache, queries, fluidStatuses,
                    c2meGlobalFluidLevels, nearestRecords, queries.size());
            nearestBatches.add(CompletableFuture.completedFuture(rows));
        } else {
            if (c2meAquifer && queries.size() > 0) {
                gpunoise$prefillC2meFluidStatuses(cache, queries, fluidStatuses,
                        c2meGlobalFluidLevels, null, 0);
            }
            for (int offset = submittedNearestQueries; offset < queries.size();
                    offset += gpunoise$nearestQueriesPerDispatch) {
                int count = Math.min(gpunoise$nearestQueriesPerDispatch, queries.size() - offset);
                java.nio.ByteBuffer records = GpuAquiferNearestThree.packForAsync(queries, offset, count);
                nearestBatches.add(gpunoise$submitNearestBatch(records, count));
            }
        }
        CompletableFuture.allOf(nearestBatches.toArray(CompletableFuture[]::new)).join();

        int nearestParitySamples = 0;
        int nearestParityMismatches = 0;
        String nearestFirstMismatch = null;
        int queryOffset = 0;
        for (CompletableFuture<int[]> batch : nearestBatches) {
            int[] selectedRows = batch.join();
            for (int row = 0; row < selectedRows.length / GpuAquiferNearestThree.OUTPUT_INTS; row++) {
                int query = queryOffset++;
                int queryX = queries.x(query), queryY = queries.y(query), queryZ = queries.z(query);
                int[][] queryCenters = queries.centers(query);
                double queryDensity = queries.density(query), queryBarrier = queries.barrier(query);
                boolean queryBelowIsLava = queries.belowIsLava(query);
                int base = row * GpuAquiferNearestThree.OUTPUT_INTS;
                int first = selectedRows[base], second = selectedRows[base + 1];
                int third = selectedRows[base + 2], fourth = selectedRows[base + 3];
                int distance0 = selectedRows[base + 4], distance1 = selectedRows[base + 5];
                int distance2 = selectedRows[base + 6], distance3 = selectedRows[base + 7];
                if (gpunoise$verifyAquifer) {
                    int[] expectedNearest = GpuAquiferNearestThree.select(
                            new int[]{queryX, queryY, queryZ}, queryCenters);
                    nearestParitySamples++;
                    if (selectedRows[base] != expectedNearest[0]
                            || selectedRows[base + 1] != expectedNearest[1]
                            || selectedRows[base + 2] != expectedNearest[2]
                            || selectedRows[base + 3] != expectedNearest[3]
                            || distance0 != expectedNearest[4] || distance1 != expectedNearest[5]
                            || distance2 != expectedNearest[6] || distance3 != expectedNearest[7]) {
                        nearestParityMismatches++;
                        if (nearestFirstMismatch == null) {
                            nearestFirstMismatch = "query=(" + queryX + "," + queryY + "," + queryZ
                                    + "), expected=" + java.util.Arrays.toString(expectedNearest)
                                    + ", gpu=[" + first + "," + second + "," + third + "," + fourth + ","
                                    + distance0 + "," + distance1 + "," + distance2 + "," + distance3 + "]";
                        }
                        continue;
                    }
                }
                if (first < 0 || first >= GpuAquiferNearestThree.CANDIDATES
                        || second < 0 || second >= GpuAquiferNearestThree.CANDIDATES
                        || third < 0 || third >= GpuAquiferNearestThree.CANDIDATES
                        || fourth < 0 || fourth >= GpuAquiferNearestThree.CANDIDATES
                        || first == second || first == third || first == fourth
                        || second == third || second == fourth || third == fourth
                        || distance0 < 0 || distance1 < 0 || distance2 < 0 || distance3 < 0) continue;
                Aquifer.FluidStatus status0 = gpunoise$getCachedAquiferStatus(cache, fluidStatuses, queryCenters[first]);
                Aquifer.FluidStatus status1 = gpunoise$getCachedAquiferStatus(cache, fluidStatuses, queryCenters[second]);
                Aquifer.FluidStatus status2 = gpunoise$getCachedAquiferStatus(cache, fluidStatuses, queryCenters[third]);
                Aquifer.FluidStatus status3 = gpunoise$getCachedAquiferStatus(cache, fluidStatuses, queryCenters[fourth]);
                BlockState nearestState = status0.at(queryY);
                int level0 = gpunoise$fluidLevel(status0);
                int level1 = gpunoise$fluidLevel(status1);
                int level2 = gpunoise$fluidLevel(status2);
                int level3 = gpunoise$fluidLevel(status3);
                int kind0 = gpunoise$fluidKind(status0.at(queryY));
                int kind1 = gpunoise$fluidKind(status1.at(queryY));
                int kind2 = gpunoise$fluidKind(status2.at(queryY));
                int kind3 = gpunoise$fluidKind(status3.at(queryY));
                int statusMask = gpunoise$statusEqualityMask(status0, status1, status2, status3);
                GpuAquiferSubstance.Input diagnosticInput = gpunoise$verifyAquifer
                        ? new GpuAquiferSubstance.Input(
                        queryDensity, queryBarrier, queryY,
                        level0, level1, level2, level3,
                        distance0, distance1, distance2, distance3,
                        kind0, kind1, kind2, kind3, statusMask,
                        0, false, queryBelowIsLava, c2meAquifer, Block.getId(nearestState)) : null;
                substanceWriter.addAquifer(queryDensity, queryBarrier, queryY,
                        level0, level1, level2, level3,
                        distance0, distance1, distance2, distance3,
                        kind0, kind1, kind2, kind3, statusMask,
                        queryBelowIsLava, c2meAquifer, Block.getId(nearestState));
                pending.add(queries.index(query), nearestState,
                        gpunoise$verifyAquifer
                                ? new DensityFunction.SinglePointContext(queryX, queryY, queryZ) : null,
                        queryDensity, diagnosticInput);
            }
        }
        if (gpunoise$verifyAquifer) {
            nearestParityMismatches += queries.size() - nearestParitySamples;
            if (queries.size() != nearestParitySamples && nearestFirstMismatch == null) {
                nearestFirstMismatch = "GPU returned fewer query rows: expected=" + queries.size()
                        + ", sampled=" + nearestParitySamples;
            }
        }
        GpuTerrainProfiler.recordAquiferPhase(GpuTerrainProfiler.AQUIFER_NEAREST_STATUS,
                nearestStatusStart);
        if (substanceWriter.size() != pending.size()) {
            throw new IllegalStateException("aquifer GPU rows lost their ordered chunk metadata");
        }

        long decisionDispatchStart = GpuTerrainProfiler.beginAquiferPhase();
        for (java.nio.ByteBuffer records : substanceWriter.finish()) {
            int count = records.remaining() / GpuAquiferSubstance.RECORD_BYTES;
            substanceBatches.add(gpunoise$submitSubstanceBatch(records, count));
        }
        CompletableFuture.allOf(substanceBatches.toArray(CompletableFuture[]::new)).join();
        GpuTerrainProfiler.recordAquiferPhase(GpuTerrainProfiler.AQUIFER_DECISION_DISPATCH,
                decisionDispatchStart);

        int outputOffset = 0;
        int acceptedDecisions = 0;
        int decisionReferenceSamples = 0;
        int decisionReferenceMismatches = 0;
        String substanceFirstMismatch = null;
        long tableResolveStart = GpuTerrainProfiler.beginAquiferPhase();
        for (CompletableFuture<int[]> batch : substanceBatches) {
            int[] outputs = batch.join();
            for (int row = 0; row < outputs.length / GpuAquiferSubstance.OUTPUT_INTS; row++) {
                int query = outputOffset++;
                int outputBase = row * GpuAquiferSubstance.OUTPUT_INTS;
                int packedOutput = outputs[outputBase];
                if (!GpuAquiferSubstance.isValidPackedOutput(packedOutput)) continue;
                int action = GpuAquiferSubstance.outputAction(packedOutput);
                if (action == GpuAquiferSubstance.CPU_FALLBACK) continue;
                if (action != GpuAquiferSubstance.RETURN_DEFAULT
                        && action != GpuAquiferSubstance.RETURN_STATE) continue;
                boolean scheduleFluidUpdate = GpuAquiferSubstance.outputSchedulesFluidUpdate(packedOutput);
                int sourceIndex = pending.index(query);
                BlockState state = action == GpuAquiferSubstance.RETURN_STATE
                        ? Block.stateById(substanceWriter.stateIdForOutputSlot(
                                GpuAquiferSubstance.outputStateId(packedOutput))) : null;
                if (action == GpuAquiferSubstance.RETURN_STATE && state == null) continue;
                if (gpunoise$verifyAquifer) {
                    DensityFunction.FunctionContext context = pending.context(query);
                    int reference = GpuAquiferSubstance.evaluatePacked(pending.gpuInput(query));
                    int referenceAction = GpuAquiferSubstance.outputAction(reference);
                    BlockState referenceState = referenceAction == GpuAquiferSubstance.RETURN_STATE
                            ? Block.stateById(GpuAquiferSubstance.outputStateId(reference)) : null;
                    boolean referenceSchedule = GpuAquiferSubstance.outputSchedulesFluidUpdate(reference);
                    decisionReferenceSamples++;
                    if (gpunoise$isTraceAquiferBlock(context.blockX(), context.blockY(), context.blockZ())) {
                        dev.gpunoise.GpuNoise.LOGGER.info(
                                "GPU_AQUIFER_TRACE_PARITY block=({}, {}, {}) density={} "
                                        + "referenceState={} gpuState={} referenceSchedule={} gpuSchedule={} "
                                        + "referenceAction={} gpuAction={}",
                                context.blockX(), context.blockY(), context.blockZ(), pending.density(query),
                                referenceState, state, referenceSchedule, scheduleFluidUpdate,
                                referenceAction, action);
                    }
                    if (referenceAction == GpuAquiferSubstance.CPU_FALLBACK
                            || !Objects.equals(referenceState, state)
                            || referenceSchedule != scheduleFluidUpdate) {
                        decisionReferenceMismatches++;
                        if (substanceFirstMismatch == null) {
                            substanceFirstMismatch = "query=(" + context.blockX() + ","
                                    + context.blockY() + "," + context.blockZ()
                                    + "), density=" + pending.density(query) + ", referenceState=" + referenceState
                                    + ", gpuState=" + state + ", referenceSchedule=" + referenceSchedule
                                    + ", gpuSchedule=" + scheduleFluidUpdate
                                    + ", referenceAction=" + referenceAction + ", gpuAction=" + action
                                    + ", gpuInput=" + pending.gpuInput(query);
                        }
                        continue;
                    }
                }
                decisions.set(sourceIndex, state, pending.density(query), scheduleFluidUpdate);
                acceptedDecisions++;
            }
        }
        GpuTerrainProfiler.recordAquiferPhase(GpuTerrainProfiler.AQUIFER_TABLE_RESOLVE,
                tableResolveStart);
        gpunoise$decisions = decisions;
        if (gpunoise$verifyAquifer) {
            dev.gpunoise.GpuNoise.LOGGER.info(
                    "GPU aquifer nearest parity: samples={}, mismatches={}, firstMismatch={}",
                    nearestParitySamples, nearestParityMismatches,
                    nearestFirstMismatch == null ? "none" : nearestFirstMismatch);
        }
        if (gpunoise$verifyAquifer) {
            dev.gpunoise.GpuNoise.LOGGER.info(
                    "GPU aquifer substance parity: samples={}, mismatches={}, firstMismatch={}",
                    decisionReferenceSamples, decisionReferenceMismatches,
                    substanceFirstMismatch == null ? "none" : substanceFirstMismatch);
        }
        long cumulativeGpuRows = gpunoise$terrainDecisionQueryCount.addAndGet(substanceWriter.size());
        long cumulativePositive = gpunoise$terrainDecisionPositiveCount.addAndGet(positiveDensityDecisions);
        if (cumulativePositive > 0
                && gpunoise$terrainDecisionBatchLogged.compareAndSet(false, true)) {
            dev.gpunoise.GpuNoise.LOGGER.info(
                    "GPU terrain material decisions: skippedPositiveDensityQueries={}, submittedAquiferRows={}, "
                            + "centerStatusReferences={}, uniqueCenterStatuses={}, "
                            + "acceptedDecisions={}, cpuFallback={}, "
                            + "directGlobalFluidQueries={}, javaReferenceSamples={}, javaReferenceMismatches={}",
                    cumulativePositive, cumulativeGpuRows,
                    queries.size() * 4L, fluidStatuses.size(),
                    acceptedDecisions, substanceWriter.size() - acceptedDecisions,
                    directGlobalFluidDecisions,
                    decisionReferenceSamples, decisionReferenceMismatches);
        }
    }

    @org.spongepowered.asm.mixin.Unique
    private int gpunoise$dispatchReadySubstanceRows(PendingDecisions pending,
                                                     GpuAquiferSubstance.BatchWriter writer,
                                                     List<CompletableFuture<int[]>> batches,
                                                     int submittedRows) {
        if (pending.size() - submittedRows < gpunoise$substanceRowsPerDispatch) return submittedRows;
        for (java.nio.ByteBuffer records : writer.drain()) {
            int count = records.remaining() / GpuAquiferSubstance.RECORD_BYTES;
            batches.add(gpunoise$submitSubstanceBatch(records, count));
        }
        return pending.size();
    }

    @org.spongepowered.asm.mixin.Unique
    private static CompletableFuture<int[]> gpunoise$submitNearestBatch(
            java.nio.ByteBuffer records, int count) {
        CompletableFuture<int[]> future = GpuTerrainRuntime.submitAquiferNearestThree(records, count)
                .toCompletableFuture();
        future.whenComplete((rows, failure) -> {
            GpuAquiferInputBufferPool.release(records);
        });
        return future;
    }

    @org.spongepowered.asm.mixin.Unique
    private static CompletableFuture<int[]> gpunoise$submitSubstanceBatch(
            java.nio.ByteBuffer records, int count) {
        CompletableFuture<int[]> future = GpuTerrainRuntime.submitAquiferSubstance(records, count)
                .toCompletableFuture();
        future.whenComplete((rows, failure) -> {
            GpuAquiferInputBufferPool.release(records);
        });
        return future;
    }

    @org.spongepowered.asm.mixin.Unique
    private int[] gpunoise$prefillC2meFluidStatuses(GpuChunkDensityCache cache,
                                                    AquiferQueries queries,
                                                    FluidStatusCache statusCache,
                                                    Map<Integer, Aquifer.FluidStatus> fluidLevelSampler,
                                                    java.nio.ByteBuffer nearestRecords,
                                                    int nearestCount) {
        long prefillStart = GpuTerrainProfiler.beginPhase();
        int javaFallbacks = 0;
        int[][] offsets = {
                {0, 0}, {-2, -1}, {-1, -1}, {0, -1}, {1, -1}, {-3, 0},
                {-2, 0}, {-1, 0}, {1, 0}, {-2, 1}, {-1, 1}, {0, 1}, {1, 1}
        };
        int expectedCenters = Math.min(GpuAquiferFluidStatus.MAX_PREFILL_CENTERS,
                Math.max(16, queries.size() / 32));
        Set<Long> seenCenters = new HashSet<>(expectedCenters);
        List<int[]> centers = new ArrayList<>(expectedCenters);
        List<GpuAquiferStatusRegionCache.Reservation> owners = new ArrayList<>(expectedCenters);
        List<Map.Entry<Long, GpuAquiferStatusRegionCache.Reservation>> waiters =
                new ArrayList<>(expectedCenters);
        Object graphIdentity = cache.terrainGraphIdentity();
        try {
            for (int query = 0; query < queries.size(); query++) {
                for (int[] center : queries.centers(query)) {
                    long key = BlockPos.asLong(center[0], center[1], center[2]);
                    if (!seenCenters.add(key)) continue;
                    Aquifer.FluidStatus cached = gpunoise$getSharedAquiferStatus(graphIdentity, key);
                    if (cached != null) {
                        GpuTerrainProfiler.recordAquiferRegionStatusCache(true);
                        statusCache.put(key, cached);
                        continue;
                    }
                    GpuAquiferStatusRegionCache.Reservation reservation =
                            GpuAquiferStatusRegionCache.reserve(graphIdentity, globalFluidPicker, key);
                    boolean owner = reservation.isOwner();
                    GpuTerrainProfiler.recordAquiferRegionStatusCache(!owner);
                    if (owner) {
                        centers.add(center);
                        owners.add(reservation);
                    } else {
                        waiters.add(new java.util.AbstractMap.SimpleImmutableEntry<>(key, reservation));
                    }
                }
            }
        } catch (RuntimeException | Error failure) {
            for (GpuAquiferStatusRegionCache.Reservation owner : owners) owner.fail(failure);
            throw failure;
        }
        GpuAquiferFluidStatus.BatchWriter statusWriter = null;
        try {
            statusWriter = centers.isEmpty()
                    ? null : new GpuAquiferFluidStatus.BatchWriter(centers.size());
            float[] surfaces = new float[GpuAquiferFluidStatus.SURFACE_SAMPLES];
            int[] globalLevels = new int[GpuAquiferFluidStatus.GLOBAL_LEVEL_SAMPLES];
            int[] globalKinds = new int[GpuAquiferFluidStatus.GLOBAL_LEVEL_SAMPLES];
            int[] globalStateIds = new int[GpuAquiferFluidStatus.GLOBAL_LEVEL_SAMPLES];
            for (int index = 0; index < centers.size(); index++) {
                int[] center = centers.get(index);
                int centerX = center[0], centerY = center[1], centerZ = center[2];
                Aquifer.FluidStatus defaultStatus = gpunoise$sampleGlobalFluidLevel(
                        fluidLevelSampler, cache, centerY);
                BlockState defaultFluidType =
                        ((AquiferFluidStatusAccessor) (Object) defaultStatus).gpunoise$fluidType();
                for (int sample = 0; sample < offsets.length; sample++) {
                    int surfaceX = Math.floorDiv(centerX + (offsets[sample][0] << 4), 4) * 4;
                    int surfaceZ = Math.floorDiv(centerZ + (offsets[sample][1] << 4), 4) * 4;
                    surfaces[sample] = gpunoise$sharedSurfaceLevel(
                            graphIdentity, surfaceX, surfaceZ);
                    int surfaceY = (int) Math.floor(surfaces[sample]) + 8;
                    Aquifer.FluidStatus sampled = gpunoise$sampleGlobalFluidLevel(
                            fluidLevelSampler, cache, surfaceY);
                    globalLevels[sample] = gpunoise$fluidLevel(sampled);
                    BlockState sampledType =
                            ((AquiferFluidStatusAccessor) (Object) sampled).gpunoise$fluidType();
                    globalStateIds[sample] = Block.getId(sampledType);
                    BlockState sampledState = sampled.at(surfaceY);
                    globalKinds[sample] = sampledState.isAir() ? GpuAquiferFluidStatus.AIR
                            : sampledState.is(Blocks.WATER) ? GpuAquiferFluidStatus.WATER
                            : sampledState.is(Blocks.LAVA) ? GpuAquiferFluidStatus.LAVA
                            : GpuAquiferFluidStatus.OTHER_FLUID;
                }
                GpuWorldgenDependency.Coordinate spread = GpuWorldgenDependency.spreadCoordinate(
                        centerX, centerY, centerZ);
                GpuWorldgenDependency.Coordinate lava = GpuWorldgenDependency.lavaCoordinate(
                        centerX, centerY, centerZ);
                statusWriter.add(centerY,
                        gpunoise$fluidLevel(defaultStatus), gpunoise$fluidKind(defaultFluidType),
                        Block.getId(defaultFluidType), Block.getId(Blocks.LAVA.defaultBlockState()),
                        (float) cache.aquiferFloodedness(centerX, centerY, centerZ),
                        (float) cache.aquiferSpread(spread.x(), spread.y(), spread.z()),
                        (float) cache.aquiferLava(lava.x(), lava.y(), lava.z()),
                        (float) cache.aquiferErosion(centerX, centerY, centerZ),
                        (float) cache.aquiferDepth(centerX, centerY, centerZ),
                        surfaces, globalLevels, globalKinds, globalStateIds);
            }
        } catch (RuntimeException | Error failure) {
            if (statusWriter != null) statusWriter.close();
            for (GpuAquiferStatusRegionCache.Reservation owner : owners) owner.fail(failure);
            throw failure;
        }
        java.nio.ByteBuffer statusRecords = statusWriter == null ? null : statusWriter.finish();
        GpuAquiferPrefillResult combined;
        int[] nearestRows;
        int[] outputs;
        try {
            combined = nearestRecords != null && statusRecords != null
                    && centers.size() <= GpuAquiferFluidStatus.MAX_PREFILL_CENTERS
                    ? GpuTerrainRuntime.submitAquiferPrefill(nearestRecords, nearestCount,
                            statusRecords, centers.size()).toCompletableFuture().join()
                    : null;
            nearestRows = combined == null && nearestRecords != null
                    ? gpunoise$submitNearestBatch(nearestRecords, nearestCount).join()
                    : combined == null ? null : combined.nearestRows();
            outputs = statusRecords == null ? new int[0]
                    : combined == null
                    ? GpuTerrainRuntime.submitAquiferFluidStatus(statusRecords, centers.size())
                            .toCompletableFuture().join()
                    : combined.fluidStatusRows();
        } catch (RuntimeException | Error failure) {
            for (GpuAquiferStatusRegionCache.Reservation owner : owners) owner.fail(failure);
            throw failure;
        } finally {
            if (statusRecords != null) GpuAquiferInputBufferPool.release(statusRecords);
        }
        try {
            for (int index = 0; index < centers.size(); index++) {
                int[] center = centers.get(index);
                long key = BlockPos.asLong(center[0], center[1], center[2]);
                int base = index * GpuAquiferFluidStatus.OUTPUT_INTS;
                int level = outputs[base];
                int source = outputs[base + 1];
                int flags = outputs[base + 2];
                int fluidStateId = outputs[base + 3];
                Aquifer.FluidStatus status;
                if ((flags & GpuAquiferFluidStatus.FLAG_CPU_FALLBACK) != 0
                        || source < 0 || source > GpuAquiferFluidStatus.SURFACE_SAMPLES) {
                    status = gpunoise$getAquiferStatus(key);
                    javaFallbacks++;
                } else if (source == 0) {
                    BlockState fluidType = Block.stateById(fluidStateId);
                    if (fluidType == null) {
                        status = gpunoise$getAquiferStatus(key);
                        javaFallbacks++;
                    } else {
                        status = new Aquifer.FluidStatus(level, fluidType);
                    }
                } else {
                    BlockState fluidType = Block.stateById(fluidStateId);
                    if (fluidType == null) {
                        status = gpunoise$getAquiferStatus(key);
                        javaFallbacks++;
                    } else {
                        status = new Aquifer.FluidStatus(level, fluidType);
                    }
                }
                statusCache.put(key, status);
                owners.get(index).complete(status);
            }
            for (Map.Entry<Long, GpuAquiferStatusRegionCache.Reservation> waiter : waiters) {
                Aquifer.FluidStatus status = gpunoise$verifySharedAquiferStatus(
                        graphIdentity, waiter.getKey(), waiter.getValue().join());
                statusCache.put(waiter.getKey(), status);
            }
        } catch (RuntimeException | Error failure) {
            for (GpuAquiferStatusRegionCache.Reservation owner : owners) owner.fail(failure);
            throw failure;
        }
        GpuTerrainProfiler.recordAquiferStatusPrefill(prefillStart, centers.size(), javaFallbacks);
        return nearestRows;
    }

    @org.spongepowered.asm.mixin.Unique
    private Aquifer.FluidStatus gpunoise$sampleGlobalFluidLevel(
            Map<Integer, Aquifer.FluidStatus> sampler, GpuChunkDensityCache cache, int y) {
        int clampedY = Math.max(cache.minY(), Math.min(cache.minY() + cache.height() - 1, y));
        return sampler.computeIfAbsent(clampedY,
                sampleY -> globalFluidPicker.computeFluid(0, sampleY, 0));
    }

    @org.spongepowered.asm.mixin.Unique
    private Aquifer.FluidStatus gpunoise$getCachedAquiferStatus(GpuChunkDensityCache terrainCache,
                                                                 FluidStatusCache cache, int[] center) {
        long position = BlockPos.asLong(center[0], center[1], center[2]);
        Aquifer.FluidStatus status = cache.get(position);
        if (status == null) {
            Object graphIdentity = terrainCache.terrainGraphIdentity();
            status = gpunoise$getSharedAquiferStatus(graphIdentity, position);
            GpuTerrainProfiler.recordAquiferRegionStatusCache(status != null);
            if (status == null) {
                status = gpunoise$getAquiferStatus(position);
                GpuAquiferStatusRegionCache.put(graphIdentity, globalFluidPicker, position, status);
            }
            cache.put(position, status);
        }
        return status;
    }

    @org.spongepowered.asm.mixin.Unique
    private Aquifer.FluidStatus gpunoise$getSharedAquiferStatus(Object graphIdentity, long position) {
        Aquifer.FluidStatus cached = GpuAquiferStatusRegionCache.get(
                graphIdentity, globalFluidPicker, position);
        return cached == null ? null : gpunoise$verifySharedAquiferStatus(graphIdentity, position, cached);
    }

    @org.spongepowered.asm.mixin.Unique
    private Aquifer.FluidStatus gpunoise$verifySharedAquiferStatus(Object graphIdentity,
                                                                    long position,
                                                                    Aquifer.FluidStatus cached) {
        if (!gpunoise$verifyAquifer) return cached;
        Aquifer.FluidStatus reference = gpunoise$getAquiferStatus(position);
        if (cached.equals(reference)) return cached;
        if (GpuAquiferStatusRegionCache.disable(graphIdentity, globalFluidPicker)) {
            dev.gpunoise.GpuNoise.LOGGER.warn(
                    "Shared GPU aquifer status cache failed parity at center {}; disabling cache for this terrain graph and fluid picker. Cached={}, Java={}",
                    BlockPos.of(position), cached, reference);
        }
        return reference;
    }

    @org.spongepowered.asm.mixin.Unique
    private float gpunoise$sharedSurfaceLevel(Object graphIdentity, int blockX, int blockZ) {
        int cached = GpuAquiferStatusRegionCache.getSurface(
                graphIdentity, globalFluidPicker, blockX, blockZ);
        GpuTerrainProfiler.recordAquiferRegionSurfaceCache(cached != Integer.MIN_VALUE);
        if (cached != Integer.MIN_VALUE) {
            if (!gpunoise$verifyAquifer || !(noiseChunk instanceof GpuNoiseChunkAccess access)) return cached;
            int reference = access.gpunoise$preliminarySurfaceLevel(blockX, blockZ);
            if (cached == reference) return cached;
            if (GpuAquiferStatusRegionCache.disableSurface(graphIdentity, globalFluidPicker)) {
                dev.gpunoise.GpuNoise.LOGGER.warn(
                        "Shared GPU aquifer surface cache failed parity at ({}, {}); disabling it for this terrain graph and fluid picker. Cached={}, Java={}",
                        blockX, blockZ, cached, reference);
            }
            return reference;
        }
        if (!(noiseChunk instanceof GpuNoiseChunkAccess access)) return Float.NaN;
        if (!GpuAquiferStatusRegionCache.isSurfaceDisabled(graphIdentity, globalFluidPicker)) {
            GpuChunkDensityCache terrain = GpuTerrainRuntime.terrainCacheForSurface(
                    blockX, blockZ, graphIdentity);
            if (terrain != null) {
                int gpuSurface = terrain.preliminarySurfaceLevelFromBlocks(blockX, blockZ);
                if (gpuSurface != Integer.MIN_VALUE) {
                    if (gpunoise$verifyAquifer) {
                        int reference = access.gpunoise$preliminarySurfaceLevel(blockX, blockZ);
                        if (gpuSurface != reference) {
                            if (GpuAquiferStatusRegionCache.disableSurface(graphIdentity, globalFluidPicker)) {
                                dev.gpunoise.GpuNoise.LOGGER.warn(
                                        "Shared GPU aquifer surface grid failed parity at ({}, {}); disabling it for this terrain graph and fluid picker. GPU={}, Java={}",
                                        blockX, blockZ, gpuSurface, reference);
                            }
                            return reference;
                        }
                    }
                    GpuAquiferStatusRegionCache.putSurface(
                            graphIdentity, globalFluidPicker, blockX, blockZ, gpuSurface);
                    return gpuSurface;
                }
            }
        }
        int surface = access.gpunoise$preliminarySurfaceLevel(blockX, blockZ);
        GpuAquiferStatusRegionCache.putSurface(graphIdentity, globalFluidPicker, blockX, blockZ, surface);
        return surface;
    }

    @org.spongepowered.asm.mixin.Unique
    private static boolean gpunoise$isTraceAquiferBlock(int x, int y, int z) {
        return gpunoise$traceAquiferX != Integer.MIN_VALUE
                && gpunoise$traceAquiferX == x
                && gpunoise$traceAquiferY == y
                && gpunoise$traceAquiferZ == z;
    }

    @org.spongepowered.asm.mixin.Unique
    private static int gpunoise$fluidKind(BlockState state) {
        return state.is(Blocks.WATER) ? GpuAquiferSubstance.WATER
                : state.is(Blocks.LAVA) ? GpuAquiferSubstance.LAVA : GpuAquiferSubstance.OTHER;
    }

    @org.spongepowered.asm.mixin.Unique
    private static int gpunoise$fluidLevel(Aquifer.FluidStatus status) {
        return ((AquiferFluidStatusAccessor) (Object) status).gpunoise$fluidLevel();
    }

    @org.spongepowered.asm.mixin.Unique
    private static int gpunoise$statusEqualityMask(Aquifer.FluidStatus status0,
                                                    Aquifer.FluidStatus status1,
                                                    Aquifer.FluidStatus status2,
                                                    Aquifer.FluidStatus status3) {
        return (status0.equals(status1) ? 1 : 0)
                | (status0.equals(status2) ? 1 << 1 : 0)
                | (status0.equals(status3) ? 1 << 2 : 0)
                | (status1.equals(status2) ? 1 << 3 : 0)
                | (status1.equals(status3) ? 1 << 4 : 0)
                | (status2.equals(status3) ? 1 << 5 : 0);
    }

    @Override
    public void gpunoise$addDependencyCoordinates(GpuWorldgenDependency.Builder builder) {
        int gridHeight = aquiferLocationCache.length / (gridSizeX * gridSizeZ);
        for (int localY = 0; localY < gridHeight; localY++) {
            int gridY = minGridY + localY;
            for (int localZ = 0; localZ < gridSizeZ; localZ++) {
                int gridZ = minGridZ + localZ;
                for (int localX = 0; localX < gridSizeX; localX++) {
                    int gridX = minGridX + localX;
                    int[] center = GpuAquiferNearestThree.centerAt(positionalRandomFactory,
                            gridX, gridY, gridZ);
                    builder.add(center[0], center[1], center[2]);
                    builder.addSpreadFromBlock(center[0], center[1], center[2]);
                    builder.addLavaFromBlock(center[0], center[1], center[2]);
                }
            }
        }
    }

    @Redirect(method = {"computeSurfaceLevel", "computeRandomizedFluidSurfaceLevel", "calculatePressure"}, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/levelgen/DensityFunction;compute(Lnet/minecraft/world/level/levelgen/DensityFunction$FunctionContext;)D"))
    private double gpunoise$useGpuAquiferInputs(DensityFunction vanillaFunction, DensityFunction.FunctionContext context) {
        // Exact mode bypasses cached GPU roots; production decisions read the
        // same parity-admitted roots used by the terrain shader.
        if (GpuAquiferRuntimeMode.exactAquiferInputsEnabled()) {
            if (GpuAquiferRuntimeMode.exactAquiferInputsEnabled()
                    && gpunoise$exactAquiferInputsLogged.compareAndSet(false, true)) {
                dev.gpunoise.GpuNoise.LOGGER.info(
                        "GPU aquifer inputs use exact Java roots; Vulkan center selection and substance decisions remain enabled");
            }
            return vanillaFunction.compute(context);
        }
        if (noiseChunk instanceof GpuNoiseChunkAccess gpuNoiseChunk) {
            GpuChunkDensityCache cache = gpuNoiseChunk.gpunoise$gpuCache();
            if (cache == null) {
                GpuChunkDensityCache active = GpuTerrainRuntime.activeFillCache();
                if (active != null && gpuNoiseChunk.gpunoise$matchesGpuCache(active)
                        && active.matchesChunk(context.blockX() & ~15, context.blockZ() & ~15)) {
                    gpuNoiseChunk.gpunoise$setGpuCache(active);
                    cache = active;
                }
            }
            if (cache != null) {
                int root = vanillaFunction == barrierNoise ? GpuTerrainRoots.BARRIER
                        : vanillaFunction == fluidLevelFloodednessNoise ? GpuTerrainRoots.FLOODEDNESS
                        : vanillaFunction == fluidLevelSpreadNoise ? GpuTerrainRoots.SPREAD
                        : vanillaFunction == lavaNoise ? GpuTerrainRoots.LAVA
                        : vanillaFunction == erosion ? GpuTerrainRoots.EROSION
                        : vanillaFunction == depth ? GpuTerrainRoots.DEPTH : -1;
                long lookupStart = GpuTerrainProfiler.beginPhase();
                double gpu = switch (root) {
                    case GpuTerrainRoots.BARRIER -> cache.aquiferBarrier(context.blockX(), context.blockY(), context.blockZ());
                    case GpuTerrainRoots.FLOODEDNESS -> cache.aquiferFloodedness(context.blockX(), context.blockY(), context.blockZ());
                    case GpuTerrainRoots.SPREAD -> cache.aquiferSpread(context.blockX(), context.blockY(), context.blockZ());
                    case GpuTerrainRoots.LAVA -> cache.aquiferLava(context.blockX(), context.blockY(), context.blockZ());
                    case GpuTerrainRoots.EROSION -> cache.aquiferErosion(context.blockX(), context.blockY(), context.blockZ());
                    case GpuTerrainRoots.DEPTH -> cache.aquiferDepth(context.blockX(), context.blockY(), context.blockZ());
                    default -> Double.NaN;
                };
                if (!Double.isNaN(gpu)) {
                    GpuTerrainProfiler.recordAquifer(root, lookupStart);
                    GpuTerrainRuntime.markGpuCacheUsed(cache);
                    return gpu;
                }
                GpuTerrainProfiler.recordJavaRootFallback(root);
            }
        }
        return vanillaFunction.compute(context);
    }
}
