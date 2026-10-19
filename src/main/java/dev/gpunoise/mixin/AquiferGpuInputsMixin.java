package dev.gpunoise.mixin;

import dev.gpunoise.GpuChunkDensityCache;
import dev.gpunoise.GpuAquiferDecisionAccess;
import dev.gpunoise.GpuNoiseChunkAccess;
import dev.gpunoise.GpuTerrainRuntime;
import dev.gpunoise.GpuTerrainRoots;
import dev.gpunoise.GpuAquiferCoordinatePlanner;
import dev.gpunoise.GpuAquiferNearestThree;
import dev.gpunoise.GpuAquiferSubstance;
import dev.gpunoise.GpuAquiferDecisionData.AquiferQuery;
import dev.gpunoise.GpuAquiferDecisionData.Decision;
import dev.gpunoise.GpuAquiferDecisionData.GridNeighborhood;
import dev.gpunoise.GpuAquiferDecisionData.PendingDecision;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
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
    private volatile Decision[] gpunoise$decisions;

    @org.spongepowered.asm.mixin.Unique
    private int gpunoise$decisionMinX, gpunoise$decisionMinY, gpunoise$decisionMinZ;

    @org.spongepowered.asm.mixin.Unique
    private static final AtomicBoolean gpunoise$aquiferBatchWarning = new AtomicBoolean();

    @org.spongepowered.asm.mixin.Unique
    private static final AtomicBoolean gpunoise$aquiferBatchLogged = new AtomicBoolean();

    @org.spongepowered.asm.mixin.Unique
    private static final AtomicBoolean gpunoise$aquiferPreflightLogged = new AtomicBoolean();

    @org.spongepowered.asm.mixin.Unique
    private static final boolean gpunoise$verifyAquifer = Boolean.getBoolean("gpunoise.verifyAquiferGpu");

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
        try {
            gpunoise$buildGpuDecisionTable(cache, noiseAccess, positionalRandomFactory);
        } catch (RuntimeException failure) {
            gpunoise$decisions = null;
            if (gpunoise$aquiferBatchWarning.compareAndSet(false, true)) {
                dev.gpunoise.GpuNoise.LOGGER.warn(
                        "GPU aquifer decision batch unavailable; using vanilla aquifer decisions.", failure);
            }
        }
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
        Decision[] decisions = gpunoise$decisions;
        if (decisions == null) return;
        int x = context.blockX() - gpunoise$decisionMinX;
        int y = context.blockY() - gpunoise$decisionMinY;
        int z = context.blockZ() - gpunoise$decisionMinZ;
        int height = decisions.length / 256;
        if (x < 0 || x >= 16 || z < 0 || z >= 16 || y < 0 || y >= height) return;
        Decision decision = decisions[y * 256 + z * 16 + x];
        if (decision == null || Double.doubleToLongBits(decision.density) != Double.doubleToLongBits(density)) return;
        shouldScheduleFluidUpdate = decision.scheduleFluidUpdate;
        cir.setReturnValue(decision.state);
    }

    @org.spongepowered.asm.mixin.Unique
    private void gpunoise$buildGpuDecisionTable(GpuChunkDensityCache cache,
                                                 dev.gpunoise.GpuNoiseChunkAccess noiseAccess,
                                                 PositionalRandomFactory randomFactory) {
        final int minX = cache.minX(), minY = cache.minY(), minZ = cache.minZ();
        final int height = cache.height();
        Decision[] decisions = new Decision[Math.multiplyExact(height, 256)];
        List<AquiferQuery> queries = new ArrayList<>();
        Map<GridNeighborhood, int[][]> neighborhoods = new HashMap<>();
        int directLavaDecisions = 0;

        for (int y = minY; y < minY + height; y++) {
            for (int z = minZ; z < minZ + 16; z++) {
                for (int x = minX; x < minX + 16; x++) {
                    double cachedDensity = cache.finalDensity(x, y, z);
                    double density = noiseAccess.gpunoise$withBeardifier(cachedDensity, x, y, z);
                    if (!Double.isFinite(density) || density > 0.0) continue;
                    int index = (y - minY) * 256 + (z - minZ) * 16 + (x - minX);
                    Aquifer.FluidStatus global = globalFluidPicker.computeFluid(x, y, z);
                    BlockState globalState = global.at(y);
                    if (globalState.is(Blocks.LAVA)) {
                        decisions[index] = new Decision(globalState, false, density);
                        directLavaDecisions++;
                        continue;
                    }

                    GridNeighborhood key = new GridNeighborhood(Math.floorDiv(x - 5, 16),
                            Math.floorDiv(y + 1, 12), Math.floorDiv(z - 5, 16));
                    int[][] centers = neighborhoods.get(key);
                    if (centers == null) {
                        centers = GpuAquiferNearestThree.centersForQuery(randomFactory, new int[]{x, y, z});
                        neighborhoods.put(key, centers);
                    }
                    double barrier = cache.aquiferBarrier(x, y, z);
                    if (!Double.isFinite(barrier)) continue;
                    boolean belowIsLava = globalFluidPicker.computeFluid(x, y - 1, z)
                            .at(y - 1).is(Blocks.LAVA);
                    queries.add(new AquiferQuery(index, x, y, z, density, barrier,
                            belowIsLava, centers));
                }
            }
        }

        List<CompletableFuture<int[]>> nearestBatches = new ArrayList<>();
        for (int offset = 0; offset < queries.size(); offset += GpuAquiferNearestThree.MAX_QUERIES) {
            int count = Math.min(GpuAquiferNearestThree.MAX_QUERIES, queries.size() - offset);
            int[][] points = new int[count][3];
            int[][][] candidates = new int[count][][];
            for (int i = 0; i < count; i++) {
                AquiferQuery query = queries.get(offset + i);
                points[i][0] = query.x;
                points[i][1] = query.y;
                points[i][2] = query.z;
                candidates[i] = query.centers;
            }
            nearestBatches.add(GpuTerrainRuntime.submitAquiferNearestThree(
                    GpuAquiferNearestThree.pack(points, candidates), count).toCompletableFuture());
        }
        CompletableFuture.allOf(nearestBatches.toArray(CompletableFuture[]::new)).join();

        List<GpuAquiferSubstance.Input> inputs = new ArrayList<>();
        List<PendingDecision> pending = new ArrayList<>();
        int nearestParitySamples = 0;
        int nearestParityMismatches = 0;
        String nearestFirstMismatch = null;
        int queryOffset = 0;
        for (CompletableFuture<int[]> batch : nearestBatches) {
            int[] selectedRows = batch.join();
            for (int row = 0; row < selectedRows.length / GpuAquiferNearestThree.OUTPUT_INTS; row++) {
                AquiferQuery query = queries.get(queryOffset++);
                int base = row * GpuAquiferNearestThree.OUTPUT_INTS;
                int first = selectedRows[base], second = selectedRows[base + 1], third = selectedRows[base + 2];
                int distance0 = selectedRows[base + 3], distance1 = selectedRows[base + 4];
                int distance2 = selectedRows[base + 5];
                if (gpunoise$verifyAquifer) {
                    int[] expectedNearest = GpuAquiferNearestThree.select(
                            new int[]{query.x, query.y, query.z}, query.centers);
                    nearestParitySamples++;
                    if (selectedRows[base] != expectedNearest[0]
                            || selectedRows[base + 1] != expectedNearest[1]
                            || selectedRows[base + 2] != expectedNearest[2]
                            || distance0 != expectedNearest[3] || distance1 != expectedNearest[4]
                            || distance2 != expectedNearest[5]) {
                        nearestParityMismatches++;
                        if (nearestFirstMismatch == null) {
                            nearestFirstMismatch = "query=(" + query.x + "," + query.y + "," + query.z
                                    + "), expected=" + java.util.Arrays.toString(expectedNearest)
                                    + ", gpu=[" + first + "," + second + "," + third + ","
                                    + distance0 + "," + distance1 + "," + distance2 + "]";
                        }
                        continue;
                    }
                }
                if (first < 0 || first >= GpuAquiferNearestThree.CANDIDATES
                        || second < 0 || second >= GpuAquiferNearestThree.CANDIDATES
                        || third < 0 || third >= GpuAquiferNearestThree.CANDIDATES
                        || first == second || first == third || second == third
                        || distance0 < 0 || distance1 < 0 || distance2 < 0) continue;
                Aquifer.FluidStatus status0 = gpunoise$getAquiferStatus(BlockPos.asLong(
                        query.centers[first][0], query.centers[first][1], query.centers[first][2]));
                Aquifer.FluidStatus status1 = gpunoise$getAquiferStatus(BlockPos.asLong(
                        query.centers[second][0], query.centers[second][1], query.centers[second][2]));
                Aquifer.FluidStatus status2 = gpunoise$getAquiferStatus(BlockPos.asLong(
                        query.centers[third][0], query.centers[third][1], query.centers[third][2]));
                BlockState nearestState = status0.at(query.y);
                inputs.add(new GpuAquiferSubstance.Input(query.density, query.barrier, query.y,
                        gpunoise$fluidLevel(status0), gpunoise$fluidLevel(status1),
                        gpunoise$fluidLevel(status2), distance0, distance1, distance2,
                        gpunoise$fluidKind(status0.at(query.y)), gpunoise$fluidKind(status1.at(query.y)),
                        gpunoise$fluidKind(status2.at(query.y)), 0, false, query.belowIsLava, 0));
                pending.add(new PendingDecision(query.index, nearestState,
                        new DensityFunction.SinglePointContext(query.x, query.y, query.z), query.density));
            }
        }
        if (gpunoise$verifyAquifer) {
            nearestParityMismatches += queries.size() - nearestParitySamples;
            if (queries.size() != nearestParitySamples && nearestFirstMismatch == null) {
                nearestFirstMismatch = "GPU returned fewer query rows: expected=" + queries.size()
                        + ", sampled=" + nearestParitySamples;
            }
        }

        List<CompletableFuture<int[]>> substanceBatches = new ArrayList<>();
        for (int offset = 0; offset < inputs.size(); offset += GpuAquiferSubstance.MAX_QUERIES) {
            int count = Math.min(GpuAquiferSubstance.MAX_QUERIES, inputs.size() - offset);
            GpuAquiferSubstance.Input[] batch = inputs.subList(offset, offset + count)
                    .toArray(GpuAquiferSubstance.Input[]::new);
            substanceBatches.add(GpuTerrainRuntime.submitAquiferSubstance(
                    GpuAquiferSubstance.pack(batch), batch.length).toCompletableFuture());
        }
        CompletableFuture.allOf(substanceBatches.toArray(CompletableFuture[]::new)).join();

        int outputOffset = 0;
        int acceptedDecisions = directLavaDecisions;
        int vanillaParitySamples = 0;
        int vanillaParityMismatches = 0;
        String substanceFirstMismatch = null;
        for (CompletableFuture<int[]> batch : substanceBatches) {
            int[] outputs = batch.join();
            for (int row = 0; row < outputs.length / GpuAquiferSubstance.OUTPUT_INTS; row++) {
                int query = outputOffset++;
                int base = row * GpuAquiferSubstance.OUTPUT_INTS;
                int action = outputs[base];
                if (action == GpuAquiferSubstance.CPU_FALLBACK) continue;
                if ((action != GpuAquiferSubstance.RETURN_DEFAULT
                        && action != GpuAquiferSubstance.RETURN_STATE)
                        || outputs[base + 1] != 0 || (outputs[base + 2] & ~1) != 0) continue;
                PendingDecision source = pending.get(query);
                BlockState state = action == GpuAquiferSubstance.RETURN_STATE
                        ? source.nearestState : null;
                if (gpunoise$verifyAquifer) {
                    BlockState vanilla = gpunoise$invokeVanillaComputeSubstance(source.context, source.density);
                    boolean vanillaSchedule = shouldScheduleFluidUpdate;
                    vanillaParitySamples++;
                    if (!Objects.equals(vanilla, state) || vanillaSchedule != (outputs[base + 2] != 0)) {
                        vanillaParityMismatches++;
                        if (substanceFirstMismatch == null) {
                            substanceFirstMismatch = "query=(" + source.context.blockX() + ","
                                    + source.context.blockY() + "," + source.context.blockZ()
                                    + "), density=" + source.density + ", vanillaState=" + vanilla
                                    + ", gpuState=" + state + ", vanillaSchedule=" + vanillaSchedule
                                    + ", gpuSchedule=" + (outputs[base + 2] != 0)
                                    + ", gpuAction=" + action;
                        }
                        continue;
                    }
                }
                decisions[source.index] = new Decision(state, outputs[base + 2] != 0, source.density);
                acceptedDecisions++;
            }
        }
        gpunoise$decisionMinX = minX;
        gpunoise$decisionMinY = minY;
        gpunoise$decisionMinZ = minZ;
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
        if (!inputs.isEmpty() && gpunoise$aquiferBatchLogged.compareAndSet(false, true)) {
            dev.gpunoise.GpuNoise.LOGGER.info(
                    "GPU aquifer substance batch: queries={}, acceptedDecisions={}, cpuFallback={}, "
                            + "vanillaParitySamples={}, vanillaParityMismatches={}",
                    inputs.size(), acceptedDecisions, inputs.size() - acceptedDecisions + directLavaDecisions,
                    vanillaParitySamples, vanillaParityMismatches);
        }
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
