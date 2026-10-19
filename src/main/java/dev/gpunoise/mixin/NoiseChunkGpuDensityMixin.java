package dev.gpunoise.mixin;

import dev.gpunoise.GpuChunkDensityCache;
import dev.gpunoise.GpuNoiseChunkAccess;
import dev.gpunoise.GpuAquiferDecisionAccess;
import dev.gpunoise.GpuNoiseInterpolatorAccess;
import dev.gpunoise.GpuNoiseConfig;
import dev.gpunoise.GpuTerrainRuntime;
import dev.gpunoise.GpuTerrainRoots;
import dev.gpunoise.GpuTerrainProfiler;
import dev.gpunoise.GpuClimateFunction;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.Aquifer;
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
import org.spongepowered.asm.mixin.Overwrite;

/** Replaces only the final terrain-density evaluation with the GPU slab. */
@Mixin(NoiseChunk.class)
abstract class NoiseChunkGpuDensityMixin implements GpuNoiseChunkAccess {
    @Shadow @Final private NoiseSettings noiseSettings;
    @Shadow @Final private DensityFunctions.BeardifierOrMarker beardifier;
    @Shadow @Final private List<NoiseChunk.NoiseInterpolator> interpolators;
    @Shadow @Final private Aquifer aquifer;
    @Shadow private int cellStartBlockX;
    @Shadow int cellStartBlockY;
    @Shadow private int cellStartBlockZ;
    @Shadow int inCellX;
    @Shadow int inCellY;
    @Shadow int inCellZ;
    @Shadow long interpolationCounter;
    @Shadow @Final int firstNoiseX;
    @Shadow @Final int firstNoiseZ;
    @Unique private GpuChunkDensityCache gpunoise$cache;
    @Unique private boolean gpunoise$zeroBeardifier;
    @Unique private boolean gpunoise$gpuUseMarked;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void gpunoise$installGpuCache(CallbackInfo callback) {
        // The vanilla marker is an enum whose compute() is unconditionally
        // zero. Avoid invoking it for every block while preserving real
        // structure beardifier calculations.
        gpunoise$zeroBeardifier = beardifier.getClass().isEnum()
                && beardifier.getClass().getName().endsWith("DensityFunctions$BeardifierMarker");
        GpuChunkDensityCache cache = GpuTerrainRuntime.activeFillCache();
        if (cache != null && cache.matchesChunk(firstNoiseX << 2, firstNoiseZ << 2)) {
            gpunoise$cache = cache;
        }
    }

    @Inject(method = "initializeForFirstCellX", at = @At("TAIL"))
    private void gpunoise$prepareAquiferBatch(CallbackInfo callback) {
        GpuChunkDensityCache cache = gpunoise$getActiveCache();
        if (cache != null && aquifer instanceof GpuAquiferDecisionAccess access) {
            access.gpunoise$prepareGpuDecisions(cache, (NoiseChunk) (Object) this);
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

    @Override
    public double gpunoise$withBeardifier(double cachedDensity, int x, int y, int z) {
        if (gpunoise$zeroBeardifier) return cachedDensity;
        return cachedDensity + beardifier.compute(new DensityFunction.SinglePointContext(x, y, z));
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

    @Unique
    private void gpunoise$markGpuUse(GpuChunkDensityCache cache) {
        if (!gpunoise$gpuUseMarked) {
            gpunoise$gpuUseMarked = true;
            GpuTerrainRuntime.markGpuCacheUsed(cache);
        }
    }

    /**
     * NoiseChunk's vanilla implementation uses List.forEach with a lambda for
     * every block-coordinate update. The list is immutable after construction,
     * so indexed traversal avoids allocating/dispatching an iterator on the
     * hottest part of the interpolation loop.
     */
    @Overwrite
    public void updateForY(int cellEndBlockY, double y) {
        this.inCellY = cellEndBlockY - this.cellStartBlockY;
        for (int i = 0, size = this.interpolators.size(); i < size; i++) {
            ((GpuNoiseInterpolatorAccess) (Object) this.interpolators.get(i)).gpunoise$updateForY(y);
        }
    }

    @Overwrite
    public void updateForX(int cellEndBlockX, double x) {
        this.inCellX = cellEndBlockX - this.cellStartBlockX;
        for (int i = 0, size = this.interpolators.size(); i < size; i++) {
            ((GpuNoiseInterpolatorAccess) (Object) this.interpolators.get(i)).gpunoise$updateForX(x);
        }
    }

    @Overwrite
    public void updateForZ(int cellEndBlockZ, double z) {
        this.inCellZ = cellEndBlockZ - this.cellStartBlockZ;
        this.interpolationCounter++;
        for (int i = 0, size = this.interpolators.size(); i < size; i++) {
            ((GpuNoiseInterpolatorAccess) (Object) this.interpolators.get(i)).gpunoise$updateForZ(z);
        }
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
        // C2ME's aquifer rewrite makes block/fluid decisions from this exact
        // value. The terrain graph ABI currently stores float results, so a
        // tiny GPU rounding difference can change a thresholded fluid block.
        // Preserve C2ME's Java density semantics until its aquifer decision
        // stage can consume a precision-matched payload; other GPU terrain
        // roots remain available for climate and batched lookup work.
        if (dev.gpunoise.GpuAquiferRuntimeMode.c2meAquiferRewriteEnabled()) {
            return vanillaFunction.compute(context);
        }
        GpuChunkDensityCache cache = this.gpunoise$cache;
        if (cache == null) {
            cache = gpunoise$getActiveCache();
        }
        // The cache is attached only after validating this NoiseChunk's
        // coordinates. density() still checks its own bounds for preliminary
        // and boundary queries, so revalidating the chunk on every hot-path
        // final-density call is unnecessary.
        if (cache != null) {
            long lookupStart = GpuTerrainProfiler.beginPhase();
            double gpu = cache.finalDensity(context.blockX(), context.blockY(), context.blockZ());
            if (!Double.isNaN(gpu)) {
                GpuTerrainProfiler.recordGpuFinalDensity(lookupStart);
                gpunoise$markGpuUse(cache);
                return gpunoise$zeroBeardifier ? gpu : gpu + beardifier.compute(context);
            }
            GpuTerrainProfiler.recordJavaRootFallback(GpuTerrainRoots.FINAL_DENSITY);
            if (cache.supportsRoot(GpuTerrainRoots.FINAL_DENSITY)
                    && cache.contains(context.blockX(), context.blockY(), context.blockZ())) {
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
            long lookupStart = GpuTerrainProfiler.beginPhase();
            double gpu = cache.initialDensity(context.blockX(), context.blockY(), context.blockZ());
            if (!Double.isNaN(gpu)) {
                GpuTerrainProfiler.recordGpuInitialDensity(lookupStart);
                gpunoise$markGpuUse(cache);
                return gpu;
            }
            GpuTerrainProfiler.recordJavaRootFallback(GpuTerrainRoots.INITIAL_DENSITY);
        }
        return vanillaFunction.compute(context);
    }

}
