package dev.gpunoise.mixin;

import dev.gpunoise.GpuChunkDensityCache;
import dev.gpunoise.GpuBeardifierInputs;
import dev.gpunoise.GpuAquiferDecisionAccess;
import dev.gpunoise.GpuNoiseChunkAccess;
import dev.gpunoise.GpuTerrainRuntime;
import dev.gpunoise.GpuTerrainDensityParity;
import dev.gpunoise.GpuTerrainRoots;
import dev.gpunoise.GpuAquiferCoordinatePlanner;
import dev.gpunoise.GpuAquiferNearestThree;
import dev.gpunoise.GpuAquiferRuntimeMode;
import dev.gpunoise.GpuAquiferSubstance;
import dev.gpunoise.GpuTerrainInputBufferPool;
import dev.gpunoise.GpuAquiferFluidStatus;
import dev.gpunoise.GpuAquiferPrefillResult;
import dev.gpunoise.GpuAquiferRowsView;
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
        final int minX = cache.minX(), minY = cache.minY(), minZ = cache.minZ();
        final int height = cache.height();
        int decisionCapacity = Math.multiplyExact(height, 256);
        GpuAquiferDecisionTable decisions = new GpuAquiferDecisionTable(minX, minY, minZ, height);
        GpuChunkDensityCache.FinalDensityBlockView finalDensityBlockView = cache.finalDensityBlockView();
        dev.gpunoise.GpuBeardifierOverlay gpuBeardifierOverlays = null;
        int beardifierParitySamples = 0;
        int beardifierParityMismatches = 0;
        String beardifierFirstMismatch = null;
        if (!noiseAccess.gpunoise$hasZeroBeardifier() && finalDensityBlockView != null) {
            GpuBeardifierInputs beardifierInputs = noiseAccess.gpunoise$beardifierInputs();
            if (beardifierInputs != null) {
                java.nio.ByteBuffer records = beardifierInputs.pack(minX, minY, minZ, height);
                try {
                    gpuBeardifierOverlays = GpuTerrainRuntime.submitBeardifier(records, decisionCapacity)
                            .toCompletableFuture().join();
                    if (gpunoise$verifyAquifer) {
                        for (int y = 0; y < height; y++) {
                            for (int z = 0; z < 16; z++) {
                                for (int x = 0; x < 16; x++) {
                                    int blockX = minX + x, blockY = minY + y, blockZ = minZ + z;
                                    if (!GpuTerrainDensityParity.shouldCompare(blockX, blockZ)) continue;
                                    double base = finalDensityBlockView.valueAtLocalUnchecked(x, y, z);
                                    double javaDensity = noiseAccess.gpunoise$withBeardifier(
                                            base, blockX, blockY, blockZ);
                                    double gpuDensity = base + gpuBeardifierOverlays.get(
                                            y * 256 + z * 16 + x);
                                    beardifierParitySamples++;
                                    if (!Double.isFinite(javaDensity)
                                            || Math.abs(javaDensity - gpuDensity)
                                            > GpuTerrainRuntime.GRAPH_PARITY_EPSILON) {
                                        beardifierParityMismatches++;
                                        if (beardifierFirstMismatch == null) {
                                            beardifierFirstMismatch = "block=(" + blockX + "," + blockY + ","
                                                    + blockZ + "), java=" + javaDensity + ", gpu=" + gpuDensity;
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (beardifierParityMismatches == 0) {
                        noiseAccess.gpunoise$setGpuBeardifierOverlay(minX, minY, minZ, height,
                                gpuBeardifierOverlays);
                    } else {
                        dev.gpunoise.GpuNoise.LOGGER.warn(
                                "GPU Beardifier parity failed; using Java structure density for this chunk: "
                                        + "samples={}, mismatches={}, firstMismatch={}",
                                beardifierParitySamples, beardifierParityMismatches, beardifierFirstMismatch);
                        gpuBeardifierOverlays = null;
                    }
                    dev.gpunoise.GpuNoise.LOGGER.debug(
                            "GPU Beardifier evaluated {} block-density samples from {} pieces and {} junctions",
                            decisionCapacity, beardifierInputs.pieceCount(), beardifierInputs.junctionCount());
                } finally {
                    dev.gpunoise.GpuTerrainInputBufferPool.release(records);
                }
            }
        }
        AquiferQueries queries = new AquiferQueries(decisionCapacity);
        GpuAquiferSubstance.BatchWriter substanceWriter =
                new GpuAquiferSubstance.BatchWriter(decisionCapacity,
                        Math.min(decisionCapacity, gpunoise$substanceRowsPerDispatch));
        List<CompletableFuture<GpuAquiferRowsView>> substanceBatches = new ArrayList<>();
        PendingDecisions pending = new PendingDecisions(decisionCapacity, gpunoise$verifyAquifer);
        int submittedSubstanceRows = 0;
        NeighborhoodCache neighborhoods = new NeighborhoodCache(Math.max(16, (height + 11) / 12 * 4));
        FluidStatusCache fluidStatuses = new FluidStatusCache(Math.max(16, (height + 11) / 12 * 4));
        Map<Integer, Aquifer.FluidStatus> c2meGlobalFluidLevels = c2meAquifer ? new HashMap<>() : null;
        // Keep nearest queries queued until center status inputs are ready,
        // allowing one command buffer to execute the complete aquifer chain.
        List<CompletableFuture<GpuAquiferRowsView>> nearestBatches = new ArrayList<>();
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
                    int index = (y - minY) * 256 + (z - minZ) * 16 + (x - minX);
                    double cachedDensity = finalDensityBlockView != null
                            ? finalDensityBlockView.valueAtLocalUnchecked(localX, localY, localZ)
                            : cache.finalDensity(x, y, z);
                    double density = gpuBeardifierOverlays != null
                            ? cachedDensity + gpuBeardifierOverlays.get(index)
                            : noiseAccess.gpunoise$withBeardifier(cachedDensity, x, y, z);
                    if (!Double.isFinite(density)) continue;
                    if (density > 0.0) {
                        if (GpuAquiferSubstance.nearDensityDecisionThreshold(density)) {
                            // The graph's float result may have a different
                            // sign from vanilla's double result at this
                            // boundary. Keep NoiseChunk's Java calculation
                            // authoritative for these samples.
                            continue;
                        }
                        // Vanilla NoiseBasedAquifer returns null immediately
                        // for positive density. Publish that exact result in
                        // the same table as dispatched substance decisions so
                        // the writer can skip NoiseChunk interpolation without
                        // another GPU dispatch or readback.
                        decisions.set(index, null, density, false);
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
                    queries.add(index, x, y, z, density, barrier, belowIsLava, c2meAquifer, centers);
                }
            }
        }
        if (finalDensityBlockView != null) finalDensityBlockView.recordReads(decisionCapacity);
        GpuTerrainProfiler.recordAquiferPhase(GpuTerrainProfiler.AQUIFER_SCAN, inputScanStart);
        if (gpunoise$verifyAquifer && !noiseAccess.gpunoise$hasZeroBeardifier()
                && finalDensityBlockView != null
                && noiseAccess.gpunoise$beardifierInputs() != null) {
            dev.gpunoise.GpuNoise.LOGGER.info(
                    "GPU Beardifier parity: samples={}, mismatches={}, firstMismatch={}",
                    beardifierParitySamples, beardifierParityMismatches,
                    beardifierFirstMismatch == null ? "none" : beardifierFirstMismatch);
        }

        // C2ME OCL precomputes random-center FluidStatus values before resolving
        // decisions. C2ME statuses are evaluated in bulk on Vulkan; vanilla
        // statuses are snapshotted once from the original method.
        long nearestStatusStart = GpuTerrainProfiler.beginAquiferPhase();
        boolean combineAquiferPasses = queries.size() > 0
                && queries.size() <= GpuAquiferNearestThree.MAX_QUERIES;
        GpuAquiferPrefillResult chainedAquifer = null;
        if (combineAquiferPasses) {
            chainedAquifer = gpunoise$stageAquiferStatuses(cache, queries, fluidStatuses,
                    c2meGlobalFluidLevels, c2meAquifer, queries.size(), true);
            nearestBatches.add(CompletableFuture.completedFuture(chainedAquifer.nearestView()));
        } else {
            if (c2meAquifer && queries.size() > 0) {
                gpunoise$stageAquiferStatuses(cache, queries, fluidStatuses,
                        c2meGlobalFluidLevels, c2meAquifer, 0, false);
            }
            for (int offset = 0; offset < queries.size();
                    offset += gpunoise$nearestQueriesPerDispatch) {
                int count = Math.min(gpunoise$nearestQueriesPerDispatch, queries.size() - offset);
                java.nio.ByteBuffer records = GpuAquiferNearestThree.packForAsync(queries, offset, count);
                nearestBatches.add(gpunoise$submitNearestBatch(records, count).thenApply(rows ->
                        GpuAquiferRowsView.whole(rows, GpuAquiferNearestThree.OUTPUT_INTS)));
            }
        }
        CompletableFuture.allOf(nearestBatches.toArray(CompletableFuture[]::new)).join();
        int directPendingRows = pending.size();
        boolean decisionChainActive = chainedAquifer != null
                && chainedAquifer.decisionValueCount() == queries.size() * 3;
        boolean[] decisionChainEligible = decisionChainActive ? new boolean[queries.size()] : null;

        int nearestParitySamples = 0;
        int nearestParityMismatches = 0;
        String nearestFirstMismatch = null;
        int queryOffset = 0;
        for (CompletableFuture<GpuAquiferRowsView> batch : nearestBatches) {
            GpuAquiferRowsView selectedRows = batch.join();
            for (int row = 0; row < selectedRows.rows(); row++) {
                int query = queryOffset++;
                int queryX = queries.x(query), queryY = queries.y(query), queryZ = queries.z(query);
                int[][] queryCenters = queries.centers(query);
                double queryDensity = queries.density(query), queryBarrier = queries.barrier(query);
                boolean queryBelowIsLava = queries.belowIsLava(query);
                int first = selectedRows.get(row, 0), second = selectedRows.get(row, 1);
                int third = selectedRows.get(row, 2), fourth = selectedRows.get(row, 3);
                int distance0 = selectedRows.get(row, 4), distance1 = selectedRows.get(row, 5);
                int distance2 = selectedRows.get(row, 6), distance3 = selectedRows.get(row, 7);
                if (gpunoise$verifyAquifer) {
                    int[] expectedNearest = GpuAquiferNearestThree.select(
                            new int[]{queryX, queryY, queryZ}, queryCenters);
                    nearestParitySamples++;
                    if (first != expectedNearest[0]
                            || second != expectedNearest[1]
                            || third != expectedNearest[2]
                            || fourth != expectedNearest[3]
                            || distance0 != expectedNearest[4] || distance1 != expectedNearest[5]
                            || distance2 != expectedNearest[6] || distance3 != expectedNearest[7]) {
                        nearestParityMismatches++;
                        if (nearestFirstMismatch == null) {
                            nearestFirstMismatch = "query=(" + queryX + "," + queryY + "," + queryZ
                                    + "), expected=" + java.util.Arrays.toString(expectedNearest)
                                    + ", gpu=[" + first + "," + second + "," + third + "," + fourth + ","
                                    + distance0 + "," + distance1 + "," + distance2 + "," + distance3 + "]";
                        }
                        pending.add(queries.index(query), null,
                                new DensityFunction.SinglePointContext(queryX, queryY, queryZ),
                                queryDensity, null);
                        continue;
                    }
                }
                if (first < 0 || first >= GpuAquiferNearestThree.CANDIDATES
                        || second < 0 || second >= GpuAquiferNearestThree.CANDIDATES
                        || third < 0 || third >= GpuAquiferNearestThree.CANDIDATES
                        || fourth < 0 || fourth >= GpuAquiferNearestThree.CANDIDATES
                        || first == second || first == third || first == fourth
                        || second == third || second == fourth || third == fourth
                        || distance0 < 0 || distance1 < 0 || distance2 < 0 || distance3 < 0) {
                    pending.add(queries.index(query), null,
                            gpunoise$verifyAquifer
                                    ? new DensityFunction.SinglePointContext(queryX, queryY, queryZ) : null,
                            queryDensity, null);
                    continue;
                }
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
                if (!decisionChainActive) {
                    substanceWriter.addAquifer(queryDensity, queryBarrier, queryY,
                            level0, level1, level2, level3,
                            distance0, distance1, distance2, distance3,
                            kind0, kind1, kind2, kind3, statusMask,
                            queryBelowIsLava, c2meAquifer, Block.getId(nearestState));
                }
                pending.add(queries.index(query), nearestState,
                        gpunoise$verifyAquifer
                                ? new DensityFunction.SinglePointContext(queryX, queryY, queryZ) : null,
                        queryDensity, diagnosticInput);
                if (decisionChainActive) decisionChainEligible[query] = true;
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
        if (decisionChainActive
                ? pending.size() != directPendingRows + queries.size()
                : substanceWriter.size() != pending.size()) {
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
        for (CompletableFuture<GpuAquiferRowsView> batch : substanceBatches) {
            GpuAquiferRowsView outputs = batch.join();
            for (int row = 0; row < outputs.rows(); row++) {
                int query = outputOffset++;
                int packedOutput = outputs.get(row, 0);
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
        if (decisionChainActive) {
            if (outputOffset != directPendingRows) {
                throw new IllegalStateException("aquifer direct-fluid rows lost their ordered decision metadata");
            }
            for (int query = 0; query < queries.size(); query++) {
                if (!decisionChainEligible[query]) continue;
                int action = chainedAquifer.decisionValue(query, 0);
                if (action == GpuAquiferSubstance.CPU_FALLBACK) continue;
                if (action != GpuAquiferSubstance.RETURN_DEFAULT
                        && action != GpuAquiferSubstance.RETURN_STATE) continue;
                boolean scheduleFluidUpdate = chainedAquifer.decisionValue(query, 1) != 0;
                int pendingRow = directPendingRows + query;
                BlockState state = action == GpuAquiferSubstance.RETURN_STATE
                        ? Block.stateById(chainedAquifer.decisionValue(query, 2)) : null;
                if (action == GpuAquiferSubstance.RETURN_STATE && state == null) continue;
                if (gpunoise$verifyAquifer) {
                    DensityFunction.FunctionContext context = pending.context(pendingRow);
                    int reference = GpuAquiferSubstance.evaluatePacked(pending.gpuInput(pendingRow));
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
                                context.blockX(), context.blockY(), context.blockZ(), pending.density(pendingRow),
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
                                    + "), density=" + pending.density(pendingRow)
                                    + ", referenceState=" + referenceState + ", gpuState=" + state
                                    + ", referenceSchedule=" + referenceSchedule
                                    + ", gpuSchedule=" + scheduleFluidUpdate
                                    + ", referenceAction=" + referenceAction + ", gpuAction=" + action
                                    + ", gpuInput=" + pending.gpuInput(pendingRow);
                        }
                        continue;
                    }
                }
                decisions.set(pending.index(pendingRow), state,
                        pending.density(pendingRow), scheduleFluidUpdate);
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
        int submittedDecisionRows = substanceWriter.size()
                + (decisionChainActive ? queries.size() : 0);
        long cumulativeGpuRows = gpunoise$terrainDecisionQueryCount.addAndGet(submittedDecisionRows);
        long cumulativePositive = gpunoise$terrainDecisionPositiveCount.addAndGet(positiveDensityDecisions);
        if (cumulativePositive > 0
                && gpunoise$terrainDecisionBatchLogged.compareAndSet(false, true)) {
            dev.gpunoise.GpuNoise.LOGGER.info(
                    "GPU terrain material decisions: positiveDensityDefaultRows={}, submittedAquiferRows={}, "
                            + "centerStatusReferences={}, uniqueCenterStatuses={}, "
                            + "acceptedAquiferDecisions={}, cpuFallback={}, "
                            + "directGlobalFluidQueries={}, javaReferenceSamples={}, javaReferenceMismatches={}",
                    cumulativePositive, cumulativeGpuRows,
                    queries.size() * 4L, fluidStatuses.size(),
                    acceptedDecisions, submittedDecisionRows - acceptedDecisions,
                    directGlobalFluidDecisions,
                    decisionReferenceSamples, decisionReferenceMismatches);
        }
    }

    @org.spongepowered.asm.mixin.Unique
    private int gpunoise$dispatchReadySubstanceRows(PendingDecisions pending,
                                                     GpuAquiferSubstance.BatchWriter writer,
                                                     List<CompletableFuture<GpuAquiferRowsView>> batches,
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
            GpuTerrainInputBufferPool.release(records);
        });
        return future;
    }

    @org.spongepowered.asm.mixin.Unique
    private static CompletableFuture<GpuAquiferRowsView> gpunoise$submitSubstanceBatch(
            java.nio.ByteBuffer records, int count) {
        CompletableFuture<GpuAquiferRowsView> future = GpuTerrainRuntime.submitAquiferSubstance(records, count)
                .toCompletableFuture();
        future.whenComplete((rows, failure) -> {
            GpuTerrainInputBufferPool.release(records);
        });
        return future;
    }

    @org.spongepowered.asm.mixin.Unique
    private GpuAquiferPrefillResult gpunoise$stageAquiferStatuses(GpuChunkDensityCache cache,
                                                                       AquiferQueries queries,
                                                                       FluidStatusCache statusCache,
                                                                       Map<Integer, Aquifer.FluidStatus> fluidLevelSampler,
                                                                       boolean c2meStatusOnGpu,
                                                                       int nearestCount,
                                                                       boolean decisionChain) {
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
        List<GpuAquiferStatusRegionCache.Reservation> reservations = new ArrayList<>(expectedCenters);
        List<GpuAquiferStatusRegionCache.Reservation> owners = new ArrayList<>(expectedCenters);
        List<Aquifer.FluidStatus> precomputedStatuses = new ArrayList<>(expectedCenters);
        Map<Long, Integer> statusRows = new HashMap<>(expectedCenters);
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
                        statusRows.put(key, centers.size());
                        centers.add(center);
                        reservations.add(null);
                        precomputedStatuses.add(cached);
                        continue;
                    }
                    GpuAquiferStatusRegionCache.Reservation reservation =
                            GpuAquiferStatusRegionCache.reserve(graphIdentity, globalFluidPicker, key);
                    boolean owner = reservation.isOwner();
                    GpuTerrainProfiler.recordAquiferRegionStatusCache(!owner);
                    if (owner) {
                        statusRows.put(key, centers.size());
                        centers.add(center);
                        reservations.add(reservation);
                        precomputedStatuses.add(null);
                        owners.add(reservation);
                    } else {
                        statusRows.put(key, centers.size());
                        centers.add(center);
                        reservations.add(reservation);
                        precomputedStatuses.add(null);
                        waiters.add(new java.util.AbstractMap.SimpleImmutableEntry<>(key, reservation));
                    }
                }
            }
        } catch (RuntimeException | Error failure) {
            for (GpuAquiferStatusRegionCache.Reservation owner : owners) owner.fail(failure);
            throw failure;
        }
        try {
            for (int row = 0; row < reservations.size(); row++) {
                GpuAquiferStatusRegionCache.Reservation reservation = reservations.get(row);
                if (reservation != null && !reservation.isOwner()) {
                    Aquifer.FluidStatus status = reservation.join();
                    precomputedStatuses.set(row, status);
                    statusCache.put(BlockPos.asLong(centers.get(row)[0], centers.get(row)[1], centers.get(row)[2]),
                            status);
                }
            }
        } catch (RuntimeException | Error failure) {
            for (GpuAquiferStatusRegionCache.Reservation owner : owners) owner.fail(failure);
            throw failure;
        }
        java.nio.ByteBuffer nearestRecords = nearestCount <= 0 ? null
                : GpuAquiferNearestThree.pack(queries, 0, nearestCount, center ->
                statusRows.getOrDefault(BlockPos.asLong(center[0], center[1], center[2]), -1));
        GpuAquiferFluidStatus.BatchWriter statusWriter = null;
        try {
            statusWriter = centers.isEmpty()
                    ? null : new GpuAquiferFluidStatus.BatchWriter(centers.size(), !c2meStatusOnGpu);
            float[] surfaces = new float[GpuAquiferFluidStatus.SURFACE_SAMPLES];
            int[] globalLevels = new int[GpuAquiferFluidStatus.GLOBAL_LEVEL_SAMPLES];
            int[] globalKinds = new int[GpuAquiferFluidStatus.GLOBAL_LEVEL_SAMPLES];
            int[] globalStateIds = new int[GpuAquiferFluidStatus.GLOBAL_LEVEL_SAMPLES];
            for (int index = 0; index < centers.size(); index++) {
                int[] center = centers.get(index);
                int centerX = center[0], centerY = center[1], centerZ = center[2];
                Aquifer.FluidStatus precomputed = precomputedStatuses.get(index);
                if (precomputed == null && !c2meStatusOnGpu) {
                    long key = BlockPos.asLong(centerX, centerY, centerZ);
                    precomputed = gpunoise$getAquiferStatus(key);
                    precomputedStatuses.set(index, precomputed);
                }
                if (precomputed != null) {
                    BlockState fluidType =
                            ((AquiferFluidStatusAccessor) (Object) precomputed).gpunoise$fluidType();
                    int stateId = Block.getId(fluidType);
                    int level = gpunoise$fluidLevel(precomputed);
                    int kind = gpunoise$fluidKind(fluidType);
                    statusWriter.add(centerY, level, kind, stateId, 0,
                            0.0f, 0.0f, 0.0f, 0.0f, 0.0f,
                            surfaces, globalLevels, globalKinds, globalStateIds,
                            true, level, stateId, kind);
                    continue;
                }
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
                            statusRecords, centers.size(), decisionChain).toCompletableFuture().join()
                    : null;
            nearestRows = combined == null && nearestRecords != null
                    ? gpunoise$submitNearestBatch(nearestRecords, nearestCount).join()
                    : null;
            outputs = statusRecords == null ? new int[0]
                    : combined == null
                    ? GpuTerrainRuntime.submitAquiferFluidStatus(statusRecords, centers.size())
                            .toCompletableFuture().join()
                    : null;
        } catch (RuntimeException | Error failure) {
            for (GpuAquiferStatusRegionCache.Reservation owner : owners) owner.fail(failure);
            throw failure;
        } finally {
            if (statusRecords != null) GpuTerrainInputBufferPool.release(statusRecords);
        }
        try {
            for (int index = 0; index < centers.size(); index++) {
                int[] center = centers.get(index);
                long key = BlockPos.asLong(center[0], center[1], center[2]);
                int base = index * GpuAquiferFluidStatus.OUTPUT_INTS;
                int level = combined == null ? outputs[base] : combined.fluidStatusValue(index, 0);
                int source = combined == null ? outputs[base + 1] : combined.fluidStatusValue(index, 1);
                int flags = combined == null ? outputs[base + 2] : combined.fluidStatusValue(index, 2);
                int fluidStateId = combined == null ? outputs[base + 3] : combined.fluidStatusValue(index, 3);
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
                GpuAquiferStatusRegionCache.Reservation reservation = reservations.get(index);
                if (reservation != null && reservation.isOwner()) reservation.complete(status);
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
        return combined != null ? combined.withoutFluidStatusRows() : new GpuAquiferPrefillResult(
                nearestRows == null ? new int[0] : nearestRows, new int[0]);
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
