package dev.gpunoise.mixin;

import dev.gpunoise.GpuChunkDensityCache;
import dev.gpunoise.GpuNoiseChunkAccess;
import dev.gpunoise.GpuNoiseConfig;
import dev.gpunoise.GpuTerrainRuntime;
import dev.gpunoise.GpuTerrainRoots;
import dev.gpunoise.GpuTerrainProfiler;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.biome.Climate;
import java.util.List;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Replaces only the final terrain-density evaluation with the GPU slab. */
@Mixin(NoiseChunk.class)
abstract class NoiseChunkGpuDensityMixin implements GpuNoiseChunkAccess {
    @Shadow @Final private NoiseSettings noiseSettings;
    @Shadow @Final private DensityFunctions.BeardifierOrMarker beardifier;
    @Shadow @Final int firstNoiseX;
    @Shadow @Final int firstNoiseZ;
    @Unique private GpuChunkDensityCache gpunoise$cache;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void gpunoise$installGpuCache(CallbackInfo callback) {
        GpuChunkDensityCache cache = GpuTerrainRuntime.activeFillCache();
        if (cache != null && cache.matchesChunk(firstNoiseX << 2, firstNoiseZ << 2)) {
            gpunoise$cache = cache;
        }
    }

    @Inject(method = "cachedClimateSampler", at = @At("HEAD"), cancellable = true)
    private void gpunoise$useGpuClimate(NoiseRouter router, List<Climate.ParameterPoint> points,
                                        org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Climate.Sampler> callback) {
        GpuChunkDensityCache cache = gpunoise$getActiveCache();
        if (cache == null) return;
        callback.setReturnValue(new Climate.Sampler(
                new GpuClimateFunction((NoiseChunk) (Object) this, cache, GpuTerrainRoots.TEMPERATURE, router.temperature()),
                new GpuClimateFunction((NoiseChunk) (Object) this, cache, GpuTerrainRoots.VEGETATION, router.vegetation()),
                new GpuClimateFunction((NoiseChunk) (Object) this, cache, GpuTerrainRoots.CONTINENTS, router.continents()),
                new GpuClimateFunction((NoiseChunk) (Object) this, cache, GpuTerrainRoots.EROSION, router.erosion()),
                new GpuClimateFunction((NoiseChunk) (Object) this, cache, GpuTerrainRoots.DEPTH, router.depth()),
                new GpuClimateFunction((NoiseChunk) (Object) this, cache, GpuTerrainRoots.RIDGES, router.ridges()), points));
    }

    @Override
    public GpuChunkDensityCache gpunoise$gpuCache() {
        return gpunoise$cache;
    }

    @Override
    public void gpunoise$setGpuCache(GpuChunkDensityCache cache) {
        this.gpunoise$cache = cache;
    }

    @Override
    public boolean gpunoise$matchesGpuCache(GpuChunkDensityCache cache) {
        return cache != null && cache.matchesChunk(firstNoiseX << 2, firstNoiseZ << 2);
    }

    @Unique
    private GpuChunkDensityCache gpunoise$getActiveCache() {
        GpuChunkDensityCache cache = this.gpunoise$cache;
        if (cache == null) {
            cache = GpuTerrainRuntime.activeFillCache();
            if (cache != null && cache.matchesChunk(firstNoiseX << 2, firstNoiseZ << 2)) {
                this.gpunoise$cache = cache;
            } else {
                cache = null;
            }
        }
        return cache;
    }

    /**
     * C2ME's compiled router path reaches the block-state lambda directly,
     * bypassing the DensityFunction wrapper above.  This redirect is the
     * authoritative final-density handoff for that path.
     */
    @Redirect(method = "lambda$new$0", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/levelgen/DensityFunction;compute(Lnet/minecraft/world/level/levelgen/DensityFunction$FunctionContext;)D"))
    private double gpunoise$useGpuFinalDensity(DensityFunction vanillaFunction,
                                                DensityFunction.FunctionContext context) {
        GpuNoiseChunkAccess access = (GpuNoiseChunkAccess) (Object) this;
        GpuChunkDensityCache cache = access.gpunoise$gpuCache();
        int minX = context.blockX() & ~15;
        int minZ = context.blockZ() & ~15;
        GpuChunkDensityCache active = GpuTerrainRuntime.activeFillCache();
        if (active != null && access.gpunoise$matchesGpuCache(active)
                && active.matchesChunk(minX, minZ)) {
            access.gpunoise$setGpuCache(active);
            cache = active;
        }
        if (cache != null && cache.matchesChunk(minX, minZ)) {
            double gpu = cache.density(context);
            if (!Double.isNaN(gpu)) {
                GpuTerrainProfiler.recordGpuFinalDensity();
                GpuTerrainRuntime.markGpuCacheUsed(cache);
                return gpu + beardifier.compute(context);
            }
            if (cache.contains(context.blockX(), context.blockY(), context.blockZ())) {
                throw new IllegalStateException("Authoritative GPU density cache returned NaN at "
                        + context.blockX() + "," + context.blockY() + "," + context.blockZ());
            }
        }
        return vanillaFunction.compute(context);
    }

    @Redirect(method = "computePreliminarySurfaceLevel", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/levelgen/DensityFunction;compute(Lnet/minecraft/world/level/levelgen/DensityFunction$FunctionContext;)D"))
    private double gpunoise$useGpuInitialDensity(DensityFunction vanillaFunction, DensityFunction.FunctionContext context) {
        GpuChunkDensityCache cache = gpunoise$getActiveCache();
        if (cache != null) {
            double gpu = cache.initialDensity(context.blockX(), context.blockY(), context.blockZ());
            if (!Double.isNaN(gpu)) {
                GpuTerrainProfiler.recordGpuInitialDensity();
                GpuTerrainRuntime.markGpuCacheUsed(cache);
                return gpu;
            }
        }
        return vanillaFunction.compute(context);
    }

    @Unique
    private static final class GpuClimateFunction implements DensityFunction.SimpleFunction {
        private final NoiseChunk owner;
        private final GpuChunkDensityCache cache;
        private final int root;
        private final DensityFunction fallback;

        private GpuClimateFunction(NoiseChunk owner, GpuChunkDensityCache cache, int root, DensityFunction fallback) {
            this.owner = owner;
            this.cache = cache;
            this.root = root;
            this.fallback = fallback;
        }

        @Override
        public double compute(DensityFunction.FunctionContext context) {
            GpuChunkDensityCache activeCache = cache;
            if (activeCache == null) {
                GpuNoiseChunkAccess access = (GpuNoiseChunkAccess) (Object) owner;
                activeCache = access.gpunoise$gpuCache();
                if (activeCache == null) {
                    GpuChunkDensityCache candidate = GpuTerrainRuntime.activeFillCache();
                    int minX = context.blockX() & ~15;
                    int minZ = context.blockZ() & ~15;
                    if (candidate != null && access.gpunoise$matchesGpuCache(candidate)
                            && candidate.matchesChunk(minX, minZ)) {
                        access.gpunoise$setGpuCache(candidate);
                        activeCache = candidate;
                    }
                }
            }
            if (activeCache != null) {
                double value = activeCache.climate(context.blockX(), context.blockY(), context.blockZ(), root);
                if (!Double.isNaN(value)) {
                    GpuTerrainProfiler.recordGpuClimate();
                    GpuTerrainRuntime.markGpuCacheUsed(activeCache);
                    return value;
                }
            }
            return fallback.compute(context);
        }

        @Override
        public DensityFunction mapAll(DensityFunction.Visitor visitor) {
            return visitor.apply(new GpuClimateFunction(owner, cache, root, fallback.mapAll(visitor)));
        }

        @Override public double minValue() { return fallback.minValue(); }
        @Override public double maxValue() { return fallback.maxValue(); }
        @Override public net.minecraft.util.KeyDispatchDataCodec<? extends DensityFunction> codec() {
            return DensityFunctions.constant(0.0).codec();
        }
    }
}
