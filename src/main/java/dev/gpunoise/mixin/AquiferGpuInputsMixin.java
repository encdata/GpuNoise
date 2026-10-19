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
import dev.gpunoise.GpuAquiferFluidStatus;
import dev.gpunoise.GpuAquiferDecisionData.AquiferQueries;
import dev.gpunoise.GpuAquiferDecisionData.FluidStatusCache;
import dev.gpunoise.GpuAquiferDecisionData.NeighborhoodCache;
import dev.gpunoise.GpuAquiferDecisionData.PendingDecisions;
import dev.gpunoise.GpuAquiferDecisionTable;
import dev.gpunoise.GpuWorldgenDependency;
import dev.gpunoise.GpuTerrainProfiler;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
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
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
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
    private static final AtomicBoolean gpunoise$c2meCutoffWarning = new AtomicBoolean();

    @org.spongepowered.asm.mixin.Unique
    private static final int gpunoise$aquiferDecisionConcurrency = (int) Math.max(1L,
            Math.min(8L, Long.getLong("gpunoise.aquiferDecisionConcurrency", 3L)));

    @org.spongepowered.asm.mixin.Unique
    private static final Semaphore gpunoise$aquiferDecisionSlots =
            new Semaphore(gpunoise$aquiferDecisionConcurrency, true);

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

    @Invoker("computeSubstance")
    protected abstract BlockState gpunoise$invokeVanillaComputeSubstance(
            DensityFunction.FunctionContext context, double density);

    @Override
    public void gpunoise$prepareGpuDecisions(GpuChunkDensityCache cache, NoiseChunk noiseChunk) {
        if (gpunoise$decisions != null) return;
        if (!(noiseChunk instanceof dev.gpunoise.GpuNoiseChunkAccess noiseAccess)) {
            gpunoise$logAquiferPreflightSkip("NoiseChunk GPU access bridge is missing");
            return;
        }
        if (!noiseAccess.gpunoise$matchesGpuCache(cache)) {
            gpunoise$logAquiferPreflightSkip("GPU density cache does not match NoiseChunk coordinates");
            return;
        }
        if (!cache.hasFullBlockOutput()) {
            gpunoise$logAquiferPreflightSkip("full block density output is unavailable");
            return;
        }
        if (!cache.supportsRoot(GpuTerrainRoots.FINAL_DENSITY)) {
            gpunoise$logAquiferPreflightSkip("finalDensity root is unsupported");
            return;
        }
        if (!cache.supportsRoot(GpuTerrainRoots.BARRIER)) {
            gpunoise$logAquiferPreflightSkip("aquifer barrier root is unsupported");
            return;
        }
        boolean acquired = false;
        long decisionBuildStart = GpuTerrainProfiler.beginPhase();
        try {
            gpunoise$aquiferDecisionSlots.acquire();
            acquired = true;
            gpunoise$buildGpuDecisionTable(cache, noiseAccess, positionalRandomFactory);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            gpunoise$logAquiferPreflightSkip("interrupted while waiting for the shared Vulkan aquifer decision slot");
        } catch (RuntimeException failure) {
            gpunoise$decisions = null;
            if (gpunoise$aquiferBatchWarning.compareAndSet(false, true)) {
                dev.gpunoise.GpuNoise.LOGGER.warn(
                        "GPU aquifer decision batch unavailable; using vanilla aquifer decisions.", failure);
            }
        } finally {
            if (acquired) gpunoise$aquiferDecisionSlots.release();
            GpuTerrainProfiler.recordAquiferDecisionBuild(decisionBuildStart);
        }
    }

    @org.spongepowered.asm.mixin.Unique
    private static void gpunoise$logAquiferPreflightSkip(String reason) {
        if (gpunoise$aquiferPreflightLogged.compareAndSet(false, true)) {
            dev.gpunoise.GpuNoise.LOGGER.info("GPU aquifer decision preflight skipped: {}", reason);
        }
    }

    @org.spongepowered.asm.mixin.Unique
    private Integer gpunoise$c2meSamplingCutoff() {
        try {
            ClassLoader loader = getClass().getClassLoader();
            Class<?> accessor = Class.forName(
                    "com.ishland.c2me.base.mixin.access.IAquiferSamplerImpl", false, loader);
            if (!accessor.isInstance(this)) return null;
            Object value = accessor.getMethod("getSamplingYLowPassCutoff").invoke(this);
            return value instanceof Integer cutoff ? cutoff : null;
        } catch (ReflectiveOperationException | LinkageError unavailable) {
            if (gpunoise$c2meCutoffWarning.compareAndSet(false, true)) {
                dev.gpunoise.GpuNoise.LOGGER.warn(
                        "C2ME aquifer cutoff accessor is unavailable; its GPU fluid-decision batch will stay disabled",
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
        AquiferQueries queries = new AquiferQueries(decisionCapacity);
        GpuAquiferSubstance.BatchWriter substanceWriter =
                new GpuAquiferSubstance.BatchWriter(decisionCapacity);
        PendingDecisions pending = new PendingDecisions(decisionCapacity, gpunoise$verifyAquifer);
        NeighborhoodCache neighborhoods = new NeighborhoodCache(Math.max(16, (height + 11) / 12 * 4));
        FluidStatusCache fluidStatuses = new FluidStatusCache(Math.max(16, (height + 11) / 12 * 4));
        // C2ME's aquifer pressure decisions consume the Java barrier value.
        // Keep the continuous terrain roots on GPU while supplying this
        // discrete decision boundary from the same Java function as OCL.
        boolean exactAquiferInputs = c2meAquifer || GpuAquiferRuntimeMode.exactAquiferInputsEnabled();
        int directGlobalFluidDecisions = 0;
        int positiveDensityDecisions = 0;

        for (int y = minY; y < minY + height; y++) {
            for (int z = minZ; z < minZ + 16; z++) {
                for (int x = minX; x < minX + 16; x++) {
                    double cachedDensity = cache.finalDensity(x, y, z);
                    double density = noiseAccess.gpunoise$withBeardifier(cachedDensity, x, y, z);
                    if (!Double.isFinite(density)) continue;
                    int index = (y - minY) * 256 + (z - minZ) * 16 + (x - minX);
                    // Only the optional differential verifier needs a context
                    // retained for each queued decision. Exact Java barrier
                    // mode creates one only for non-lava aquifer queries.
                    DensityFunction.FunctionContext context = gpunoise$verifyAquifer
                            ? new DensityFunction.SinglePointContext(x, y, z) : null;
                    if (density > 0.0) {
                        // Vanilla's positive-density decision is the base
                        // solid/air material choice. Batch it through the same
                        // Vulkan decision kernel as aquifer results so the
                        // complete per-block material classification has one
                        // parity gate and one result table.
                        GpuAquiferSubstance.Input diagnosticInput = gpunoise$verifyAquifer
                                ? new GpuAquiferSubstance.Input(
                                density, 0.0, y, 0, 0, 0, 0, 0, 0, 0, 0,
                                GpuAquiferSubstance.OTHER, GpuAquiferSubstance.OTHER,
                                GpuAquiferSubstance.OTHER, GpuAquiferSubstance.OTHER,
                                0, 0, false, false, c2meAquifer, 0) : null;
                        substanceWriter.addPositiveDensity(density, y, c2meAquifer);
                        pending.add(index, null, context, density, diagnosticInput);
                        positiveDensityDecisions++;
                        continue;
                    }
                    Aquifer.FluidStatus global = globalFluidPicker.computeFluid(x, y, z);
                    BlockState globalState = global.at(y);
                    if (c2meAquifer && y > c2meSamplingCutoff) {
                        // C2ME OCL bypasses local aquifer pressure above its
                        // low-pass cutoff and returns the global picker state.
                        substanceWriter.addDirectGlobalFluid(density, y, true);
                        pending.add(index, globalState, context, density, null);
                        directGlobalFluidDecisions++;
                        continue;
                    }
                    if (globalState.is(Blocks.LAVA)) {
                        GpuAquiferSubstance.Input diagnosticInput = gpunoise$verifyAquifer
                                ? new GpuAquiferSubstance.Input(density, 0.0, y,
                                0, 0, 0, 0, 0, 0,
                                GpuAquiferSubstance.OTHER, GpuAquiferSubstance.OTHER,
                                GpuAquiferSubstance.OTHER, 0, true, false, 0) : null;
                        substanceWriter.addDirectGlobalFluid(density, y, c2meAquifer);
                        pending.add(index, globalState, context, density, diagnosticInput);
                        directGlobalFluidDecisions++;
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
                    double barrier = exactAquiferInputs
                            ? barrierNoise.compute(context)
                            : cache.aquiferBarrier(x, y, z);
                    if (!Double.isFinite(barrier)) continue;
                    boolean belowIsLava = globalFluidPicker.computeFluid(x, y - 1, z)
                            .at(y - 1).is(Blocks.LAVA);
                    queries.add(index, x, y, z, density, barrier, belowIsLava, centers);
                }
            }
        }

        // C2ME OCL precomputes each random aquifer center's FluidStatus before
        // resolving block decisions. Mirror that bulk prefill: evaluate the
        // 13 surface probes, global fluid samples, and terrain roots together
        // on Vulkan, then retain the original Java statuses for custom fluids.
        if (c2meAquifer && queries.size() > 0) {
            gpunoise$prefillC2meFluidStatuses(cache, queries, fluidStatuses);
        }

        List<CompletableFuture<int[]>> nearestBatches = new ArrayList<>();
        for (int offset = 0; offset < queries.size(); offset += GpuAquiferNearestThree.MAX_QUERIES) {
            int count = Math.min(GpuAquiferNearestThree.MAX_QUERIES, queries.size() - offset);
            nearestBatches.add(GpuTerrainRuntime.submitAquiferNearestThree(
                    GpuAquiferNearestThree.pack(queries, offset, count), count).toCompletableFuture());
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
                Aquifer.FluidStatus status0 = gpunoise$getCachedAquiferStatus(fluidStatuses, queryCenters[first]);
                Aquifer.FluidStatus status1 = gpunoise$getCachedAquiferStatus(fluidStatuses, queryCenters[second]);
                Aquifer.FluidStatus status2 = gpunoise$getCachedAquiferStatus(fluidStatuses, queryCenters[third]);
                Aquifer.FluidStatus status3 = gpunoise$getCachedAquiferStatus(fluidStatuses, queryCenters[fourth]);
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
                        0, false, queryBelowIsLava, c2meAquifer, 0) : null;
                substanceWriter.addAquifer(queryDensity, queryBarrier, queryY,
                        level0, level1, level2, level3,
                        distance0, distance1, distance2, distance3,
                        kind0, kind1, kind2, kind3, statusMask,
                        queryBelowIsLava, c2meAquifer);
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
        if (substanceWriter.size() != pending.size()) {
            throw new IllegalStateException("aquifer GPU rows lost their ordered chunk metadata");
        }

        List<CompletableFuture<int[]>> substanceBatches = new ArrayList<>();
        for (java.nio.ByteBuffer records : substanceWriter.finish()) {
            int count = records.remaining() / GpuAquiferSubstance.RECORD_BYTES;
            substanceBatches.add(GpuTerrainRuntime.submitAquiferSubstance(records, count).toCompletableFuture());
        }
        CompletableFuture.allOf(substanceBatches.toArray(CompletableFuture[]::new)).join();

        int outputOffset = 0;
        int acceptedDecisions = 0;
        int vanillaParitySamples = 0;
        int vanillaParityMismatches = 0;
        String substanceFirstMismatch = null;
        for (CompletableFuture<int[]> batch : substanceBatches) {
            int[] outputs = batch.join();
            for (int row = 0; row < outputs.length / GpuAquiferSubstance.OUTPUT_INTS; row++) {
                int query = outputOffset++;
                int packedOutput = outputs[row];
                if (!GpuAquiferSubstance.isValidPackedOutput(packedOutput)) continue;
                int action = GpuAquiferSubstance.outputAction(packedOutput);
                if (action == GpuAquiferSubstance.CPU_FALLBACK) continue;
                if (action != GpuAquiferSubstance.RETURN_DEFAULT
                        && action != GpuAquiferSubstance.RETURN_STATE) continue;
                boolean scheduleFluidUpdate = GpuAquiferSubstance.outputSchedulesFluidUpdate(packedOutput);
                int sourceIndex = pending.index(query);
                BlockState state = action == GpuAquiferSubstance.RETURN_STATE
                        ? pending.selectedState(query) : null;
                if (gpunoise$verifyAquifer) {
                    DensityFunction.FunctionContext context = pending.context(query);
                    double density = pending.density(query);
                    BlockState vanilla = gpunoise$invokeVanillaComputeSubstance(context, density);
                    boolean vanillaSchedule = shouldScheduleFluidUpdate;
                    vanillaParitySamples++;
                    if (gpunoise$isTraceAquiferBlock(context.blockX(), context.blockY(), context.blockZ())) {
                        dev.gpunoise.GpuNoise.LOGGER.info(
                                "GPU_AQUIFER_TRACE_PARITY block=({}, {}, {}) density={} "
                                        + "vanillaState={} gpuState={} vanillaSchedule={} gpuSchedule={} action={}",
                                context.blockX(), context.blockY(), context.blockZ(), density,
                                vanilla, state, vanillaSchedule, scheduleFluidUpdate, action);
                    }
                    if (!Objects.equals(vanilla, state) || vanillaSchedule != scheduleFluidUpdate) {
                        vanillaParityMismatches++;
                        if (substanceFirstMismatch == null) {
                            substanceFirstMismatch = "query=(" + context.blockX() + ","
                                    + context.blockY() + "," + context.blockZ()
                                    + "), density=" + density + ", vanillaState=" + vanilla
                                    + ", gpuState=" + state + ", vanillaSchedule=" + vanillaSchedule
                                    + ", gpuSchedule=" + scheduleFluidUpdate
                                    + ", gpuAction=" + action + ", gpuInput=" + pending.gpuInput(query);
                        }
                        continue;
                    }
                }
                decisions.set(sourceIndex, state, pending.density(query), scheduleFluidUpdate);
                acceptedDecisions++;
            }
        }
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
                    vanillaParitySamples, vanillaParityMismatches,
                    substanceFirstMismatch == null ? "none" : substanceFirstMismatch);
        }
        long cumulativeQueries = gpunoise$terrainDecisionQueryCount.addAndGet(substanceWriter.size());
        long cumulativePositive = gpunoise$terrainDecisionPositiveCount.addAndGet(positiveDensityDecisions);
        if (cumulativePositive > 0
                && gpunoise$terrainDecisionBatchLogged.compareAndSet(false, true)) {
            dev.gpunoise.GpuNoise.LOGGER.info(
                    "GPU terrain material decisions: queries={}, positiveDensityQueries={}, "
                            + "aquiferQueries={}, centerStatusLookups={}, centerStatusEvaluations={}, "
                            + "centerStatusCacheHits={}, acceptedDecisions={}, cpuFallback={}, "
                            + "directGlobalFluidQueries={}, vanillaParitySamples={}, vanillaParityMismatches={}",
                    cumulativeQueries, cumulativePositive, cumulativeQueries - cumulativePositive,
                    queries.size() * 4L, fluidStatuses.size(),
                    Math.max(0L, queries.size() * 4L - fluidStatuses.size()),
                    acceptedDecisions, substanceWriter.size() - acceptedDecisions,
                    directGlobalFluidDecisions,
                    vanillaParitySamples, vanillaParityMismatches);
        }
    }

    @org.spongepowered.asm.mixin.Unique
    private void gpunoise$prefillC2meFluidStatuses(GpuChunkDensityCache cache,
                                                    AquiferQueries queries,
                                                    FluidStatusCache statusCache) {
        long prefillStart = GpuTerrainProfiler.beginPhase();
        int javaFallbacks = 0;
        int[][] offsets = {
                {0, 0}, {-2, -1}, {-1, -1}, {0, -1}, {1, -1}, {-3, 0},
                {-2, 0}, {-1, 0}, {1, 0}, {-2, 1}, {-1, 1}, {0, 1}, {1, 1}
        };
        Map<Long, Integer> indexes = new HashMap<>();
        Map<Integer, Aquifer.FluidStatus> fluidLevelSampler = new HashMap<>();
        List<int[]> centers = new ArrayList<>();
        for (int query = 0; query < queries.size(); query++) {
            for (int[] center : queries.centers(query)) {
                long key = BlockPos.asLong(center[0], center[1], center[2]);
                if (!indexes.containsKey(key)) {
                    indexes.put(key, centers.size());
                    centers.add(center);
                }
            }
        }
        GpuAquiferFluidStatus.Input[] inputs = new GpuAquiferFluidStatus.Input[centers.size()];
        Aquifer.FluidStatus[][] sampledStatuses = new Aquifer.FluidStatus[centers.size()][];
        for (int index = 0; index < centers.size(); index++) {
            int[] center = centers.get(index);
            int centerX = center[0], centerY = center[1], centerZ = center[2];
            Aquifer.FluidStatus defaultStatus = gpunoise$sampleGlobalFluidLevel(
                    fluidLevelSampler, cache, centerY);
            float[] surfaces = new float[offsets.length];
            int[] globalLevels = new int[offsets.length];
            int[] globalKinds = new int[offsets.length];
            Aquifer.FluidStatus[] samples = new Aquifer.FluidStatus[offsets.length];
            for (int sample = 0; sample < offsets.length; sample++) {
                int surfaceX = Math.floorDiv(centerX + (offsets[sample][0] << 4), 4) * 4;
                int surfaceZ = Math.floorDiv(centerZ + (offsets[sample][1] << 4), 4) * 4;
                surfaces[sample] = noiseChunk instanceof GpuNoiseChunkAccess access
                        ? access.gpunoise$preliminarySurfaceLevel(surfaceX, surfaceZ)
                        : Float.NaN;
                int surfaceY = (int) Math.floor(surfaces[sample]) + 8;
                Aquifer.FluidStatus sampled = gpunoise$sampleGlobalFluidLevel(
                        fluidLevelSampler, cache, surfaceY);
                samples[sample] = sampled;
                globalLevels[sample] = gpunoise$fluidLevel(sampled);
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
            inputs[index] = new GpuAquiferFluidStatus.Input(centerY,
                    gpunoise$fluidLevel(defaultStatus), gpunoise$fluidKind(
                    ((AquiferFluidStatusAccessor) (Object) defaultStatus).gpunoise$fluidType()),
                    (float) cache.aquiferFloodedness(centerX, centerY, centerZ),
                    (float) cache.aquiferSpread(spread.x(), spread.y(), spread.z()),
                    (float) cache.aquiferLava(lava.x(), lava.y(), lava.z()),
                    (float) cache.aquiferErosion(centerX, centerY, centerZ),
                    (float) cache.aquiferDepth(centerX, centerY, centerZ),
                    surfaces, globalLevels, globalKinds);
            sampledStatuses[index] = samples;
        }
        int[] outputs = GpuTerrainRuntime.submitAquiferFluidStatus(
                GpuAquiferFluidStatus.pack(inputs), inputs.length).toCompletableFuture().join();
        for (int index = 0; index < centers.size(); index++) {
            int[] center = centers.get(index);
            long key = BlockPos.asLong(center[0], center[1], center[2]);
            int base = index * GpuAquiferFluidStatus.OUTPUT_INTS;
            int level = outputs[base];
            int source = outputs[base + 1];
            int flags = outputs[base + 2];
            Aquifer.FluidStatus status;
            if ((flags & GpuAquiferFluidStatus.FLAG_CPU_FALLBACK) != 0
                    || source < 0 || source > GpuAquiferFluidStatus.SURFACE_SAMPLES) {
                status = gpunoise$getAquiferStatus(key);
                javaFallbacks++;
            } else if ((flags & GpuAquiferFluidStatus.FLAG_LAVA_OVERRIDE) != 0) {
                status = new Aquifer.FluidStatus(level, Blocks.LAVA.defaultBlockState());
            } else if (source == 0) {
                Aquifer.FluidStatus defaultStatus = gpunoise$sampleGlobalFluidLevel(
                        fluidLevelSampler, cache, center[1]);
                status = new Aquifer.FluidStatus(level,
                        ((AquiferFluidStatusAccessor) (Object) defaultStatus).gpunoise$fluidType());
            } else {
                Aquifer.FluidStatus sampled = sampledStatuses[index][source - 1];
                status = new Aquifer.FluidStatus(level,
                        ((AquiferFluidStatusAccessor) (Object) sampled).gpunoise$fluidType());
            }
            statusCache.put(key, status);
        }
        GpuTerrainProfiler.recordAquiferStatusPrefill(prefillStart, centers.size(), javaFallbacks);
    }

    @org.spongepowered.asm.mixin.Unique
    private Aquifer.FluidStatus gpunoise$sampleGlobalFluidLevel(
            Map<Integer, Aquifer.FluidStatus> sampler, GpuChunkDensityCache cache, int y) {
        int clampedY = Math.max(cache.minY(), Math.min(cache.minY() + cache.height() - 1, y));
        return sampler.computeIfAbsent(clampedY,
                sampleY -> globalFluidPicker.computeFluid(0, sampleY, 0));
    }

    @org.spongepowered.asm.mixin.Unique
    private Aquifer.FluidStatus gpunoise$getCachedAquiferStatus(FluidStatusCache cache, int[] center) {
        long position = BlockPos.asLong(center[0], center[1], center[2]);
        Aquifer.FluidStatus status = cache.get(position);
        if (status == null) {
            status = gpunoise$getAquiferStatus(position);
            cache.put(position, status);
        }
        return status;
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
        // FluidStatus preparation is part of C2ME's rewritten aquifer
        // algorithm. Feed it the same Java values as the CPU reference until
        // the GPU roots can satisfy exact decision parity at this boundary.
        if (GpuAquiferRuntimeMode.c2meAquiferRewriteEnabled()
                || GpuAquiferRuntimeMode.exactAquiferInputsEnabled()) {
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
