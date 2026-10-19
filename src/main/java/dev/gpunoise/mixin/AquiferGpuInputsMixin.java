package dev.gpunoise.mixin;

import dev.gpunoise.GpuChunkDensityCache;
import dev.gpunoise.GpuAquiferDecisionAccess;
import dev.gpunoise.GpuNoiseChunkAccess;
import dev.gpunoise.GpuTerrainRuntime;
import dev.gpunoise.GpuTerrainRoots;
import dev.gpunoise.GpuAquiferCoordinatePlanner;
import dev.gpunoise.GpuAquiferNearestThree;
import dev.gpunoise.GpuAquiferSubstance;
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
    private volatile GpuDecision[] gpunoise$decisions;

    @org.spongepowered.asm.mixin.Unique
    private int gpunoise$decisionMinX, gpunoise$decisionMinY, gpunoise$decisionMinZ;

    @org.spongepowered.asm.mixin.Unique
    private static final AtomicBoolean gpunoise$aquiferBatchWarning = new AtomicBoolean();

    @org.spongepowered.asm.mixin.Unique
    private static final AtomicBoolean gpunoise$aquiferBatchLogged = new AtomicBoolean();

    @org.spongepowered.asm.mixin.Unique
    private static final boolean gpunoise$verifyAquifer = Boolean.getBoolean("gpunoise.verifyAquiferGpu");

    @Invoker("getAquiferStatus")
    protected abstract Aquifer.FluidStatus gpunoise$getAquiferStatus(long position);

    @Invoker("computeSubstance")
    protected abstract BlockState gpunoise$invokeVanillaComputeSubstance(
            DensityFunction.FunctionContext context, double density);

    @Override
    public void gpunoise$prepareGpuDecisions(GpuChunkDensityCache cache, NoiseChunk noiseChunk) {
        if (gpunoise$decisions != null
                || !(noiseChunk instanceof dev.gpunoise.GpuNoiseChunkAccess noiseAccess)
                || !noiseAccess.gpunoise$hasZeroBeardifier()
                || !noiseAccess.gpunoise$matchesGpuCache(cache)
                || !cache.hasFullBlockOutput()
                || !cache.supportsRoot(GpuTerrainRoots.FINAL_DENSITY)
                || !cache.supportsRoot(GpuTerrainRoots.BARRIER)) return;
        try {
            gpunoise$buildGpuDecisionTable(cache, positionalRandomFactory);
        } catch (RuntimeException failure) {
            gpunoise$decisions = null;
            if (gpunoise$aquiferBatchWarning.compareAndSet(false, true)) {
                dev.gpunoise.GpuNoise.LOGGER.warn(
                        "GPU aquifer decision batch unavailable; using vanilla aquifer decisions.", failure);
            }
        }
    }

    @Inject(method = "computeSubstance", at = @At("HEAD"), cancellable = true)
    private void gpunoise$usePreparedGpuAquiferDecision(
            DensityFunction.FunctionContext context, double density,
            CallbackInfoReturnable<BlockState> cir) {
        GpuDecision[] decisions = gpunoise$decisions;
        if (decisions == null) return;
        int x = context.blockX() - gpunoise$decisionMinX;
        int y = context.blockY() - gpunoise$decisionMinY;
        int z = context.blockZ() - gpunoise$decisionMinZ;
        int height = decisions.length / 256;
        if (x < 0 || x >= 16 || z < 0 || z >= 16 || y < 0 || y >= height) return;
        GpuDecision decision = decisions[y * 256 + z * 16 + x];
        if (decision == null) return;
        shouldScheduleFluidUpdate = decision.scheduleFluidUpdate;
        cir.setReturnValue(decision.state);
    }

    @org.spongepowered.asm.mixin.Unique
    private void gpunoise$buildGpuDecisionTable(GpuChunkDensityCache cache,
                                                 PositionalRandomFactory randomFactory) {
        final int minX = cache.minX(), minY = cache.minY(), minZ = cache.minZ();
        final int height = cache.height();
        GpuDecision[] decisions = new GpuDecision[Math.multiplyExact(height, 256)];
        List<GpuAquiferSubstance.Input> inputs = new ArrayList<>();
        List<PendingDecision> pending = new ArrayList<>();
        Map<GridNeighborhood, int[][]> neighborhoods = new HashMap<>();
        int directLavaDecisions = 0;

        for (int y = minY; y < minY + height; y++) {
            for (int z = minZ; z < minZ + 16; z++) {
                for (int x = minX; x < minX + 16; x++) {
                    double density = cache.finalDensity(x, y, z);
                    if (!Double.isFinite(density) || density > 0.0) continue;
                    int index = (y - minY) * 256 + (z - minZ) * 16 + (x - minX);
                    Aquifer.FluidStatus global = globalFluidPicker.computeFluid(x, y, z);
                    BlockState globalState = global.at(y);
                    if (globalState.is(Blocks.LAVA)) {
                        decisions[index] = new GpuDecision(globalState, false);
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
                    int[] selected = GpuAquiferNearestThree.select(new int[]{x, y, z}, centers);
                    Aquifer.FluidStatus status0 = gpunoise$getAquiferStatus(
                            BlockPos.asLong(centers[selected[0]][0], centers[selected[0]][1], centers[selected[0]][2]));
                    Aquifer.FluidStatus status1 = gpunoise$getAquiferStatus(
                            BlockPos.asLong(centers[selected[1]][0], centers[selected[1]][1], centers[selected[1]][2]));
                    Aquifer.FluidStatus status2 = gpunoise$getAquiferStatus(
                            BlockPos.asLong(centers[selected[2]][0], centers[selected[2]][1], centers[selected[2]][2]));
                    double barrier = cache.aquiferBarrier(x, y, z);
                    if (!Double.isFinite(barrier)) continue;
                    BlockState nearestState = status0.at(y);
                    boolean belowIsLava = globalFluidPicker.computeFluid(x, y - 1, z)
                            .at(y - 1).is(Blocks.LAVA);
                    inputs.add(new GpuAquiferSubstance.Input(density, barrier, y,
                            gpunoise$fluidLevel(status0), gpunoise$fluidLevel(status1),
                            gpunoise$fluidLevel(status2),
                            selected[3], selected[4], selected[5],
                            gpunoise$fluidKind(status0.at(y)), gpunoise$fluidKind(status1.at(y)),
                            gpunoise$fluidKind(status2.at(y)), 0, false, belowIsLava, 0));
                    pending.add(new PendingDecision(index, nearestState,
                            new DensityFunction.SinglePointContext(x, y, z), density));
                }
            }
        }

        List<CompletableFuture<int[]>> batches = new ArrayList<>();
        for (int offset = 0; offset < inputs.size(); offset += GpuAquiferSubstance.MAX_QUERIES) {
            int count = Math.min(GpuAquiferSubstance.MAX_QUERIES, inputs.size() - offset);
            GpuAquiferSubstance.Input[] batch = inputs.subList(offset, offset + count)
                    .toArray(GpuAquiferSubstance.Input[]::new);
            batches.add(GpuTerrainRuntime.submitAquiferSubstance(
                    GpuAquiferSubstance.pack(batch), batch.length).toCompletableFuture());
        }
        CompletableFuture.allOf(batches.toArray(CompletableFuture[]::new)).join();

        int outputOffset = 0;
        int acceptedDecisions = directLavaDecisions;
        int vanillaParitySamples = 0;
        int vanillaParityMismatches = 0;
        for (CompletableFuture<int[]> batch : batches) {
            int[] outputs = batch.join();
            for (int row = 0; row < outputs.length / GpuAquiferSubstance.OUTPUT_INTS; row++) {
                int query = outputOffset++;
                GpuAquiferSubstance.Input input = inputs.get(query);
                int[] expected = GpuAquiferSubstance.evaluate(input);
                int base = row * GpuAquiferSubstance.OUTPUT_INTS;
                if (outputs[base] != expected[0] || outputs[base + 1] != expected[1]
                        || outputs[base + 2] != expected[2]
                        || expected[0] == GpuAquiferSubstance.CPU_FALLBACK) continue;
                PendingDecision source = pending.get(query);
                BlockState state = expected[0] == GpuAquiferSubstance.RETURN_STATE
                        ? source.nearestState : null;
                if (gpunoise$verifyAquifer) {
                    BlockState vanilla = gpunoise$invokeVanillaComputeSubstance(source.context, source.density);
                    boolean vanillaSchedule = shouldScheduleFluidUpdate;
                    vanillaParitySamples++;
                    if (!Objects.equals(vanilla, state) || vanillaSchedule != (expected[2] != 0)) {
                        vanillaParityMismatches++;
                        continue;
                    }
                }
                decisions[source.index] = new GpuDecision(state, expected[2] != 0);
                acceptedDecisions++;
            }
        }
        gpunoise$decisionMinX = minX;
        gpunoise$decisionMinY = minY;
        gpunoise$decisionMinZ = minZ;
        gpunoise$decisions = decisions;
        if (gpunoise$verifyAquifer) {
            dev.gpunoise.GpuNoise.LOGGER.info(
                    "GPU aquifer substance parity: samples={}, mismatches={}",
                    vanillaParitySamples, vanillaParityMismatches);
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

    @org.spongepowered.asm.mixin.Unique
    private record GridNeighborhood(int x, int y, int z) {}

    @org.spongepowered.asm.mixin.Unique
    private record PendingDecision(int index, BlockState nearestState,
                                   DensityFunction.FunctionContext context, double density) {}

    @org.spongepowered.asm.mixin.Unique
    private record GpuDecision(BlockState state, boolean scheduleFluidUpdate) {}

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
