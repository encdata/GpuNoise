package dev.gpunoise.mixin;

import dev.gpunoise.GpuChunkDensityCache;
import dev.gpunoise.GpuNoiseChunkAccess;
import dev.gpunoise.GpuTerrainRuntime;
import dev.gpunoise.GpuTerrainRoots;
import dev.gpunoise.GpuAquiferCoordinatePlanner;
import dev.gpunoise.GpuWorldgenDependency;
import dev.gpunoise.GpuTerrainProfiler;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Feeds the GPU floodedness root into vanilla's aquifer surface calculation. */
// C2ME also rewrites computeSurfaceLevel. Apply this small value substitution
// before its aquifer mixin so both transformations can coexist.
@Mixin(value = Aquifer.NoiseBasedAquifer.class, priority = 1200)
abstract class AquiferGpuInputsMixin implements GpuAquiferCoordinatePlanner {
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

    @Override
    public void gpunoise$addDependencyCoordinates(GpuWorldgenDependency.Builder builder) {
        int gridHeight = aquiferLocationCache.length / (gridSizeX * gridSizeZ);
        for (int localY = 0; localY < gridHeight; localY++) {
            int gridY = minGridY + localY;
            for (int localZ = 0; localZ < gridSizeZ; localZ++) {
                int gridZ = minGridZ + localZ;
                for (int localX = 0; localX < gridSizeX; localX++) {
                    int gridX = minGridX + localX;
                    RandomSource random = positionalRandomFactory.at(gridX, gridY, gridZ);
                    int x = gridX * 16 + random.nextInt(10);
                    int y = gridY * 12 + random.nextInt(9);
                    int z = gridZ * 16 + random.nextInt(10);
                    builder.add(x, y, z);
                    builder.addSpreadFromBlock(x, y, z);
                    builder.addLavaFromBlock(x, y, z);
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
                    GpuTerrainProfiler.recordAquifer();
                    GpuTerrainRuntime.markGpuCacheUsed(cache);
                    return gpu;
                }
            }
        }
        return vanillaFunction.compute(context);
    }
}
