package dev.gpunoise.mixin;

import dev.gpunoise.GpuChunkDensityCache;
import dev.gpunoise.GpuNoiseChunkAccess;
import dev.gpunoise.GpuNoiseConfig;
import dev.gpunoise.GpuTerrainRuntime;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseSettings;
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
    @Shadow @Final int firstNoiseX;
    @Shadow @Final int firstNoiseZ;
    @Unique private GpuChunkDensityCache gpunoise$cache;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void gpunoise$installGpuCache(CallbackInfo callback) {
        if (!GpuTerrainRuntime.chunkWriterAvailable()) return;
        int chunkMinX = firstNoiseX << 2;
        int chunkMinZ = firstNoiseZ << 2;
        GpuChunkDensityCache cache = GpuChunkDensityCache.takePrefetched(chunkMinX, chunkMinZ);
        if (cache == null) cache = GpuChunkDensityCache.create(chunkMinX, chunkMinZ, noiseSettings);
        gpunoise$cache = cache;
    }

    @Override
    public GpuChunkDensityCache gpunoise$gpuCache() {
        return gpunoise$cache;
    }

    @Redirect(method = "<init>", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/levelgen/DensityFunctions;add(Lnet/minecraft/world/level/levelgen/DensityFunction;Lnet/minecraft/world/level/levelgen/DensityFunction;)Lnet/minecraft/world/level/levelgen/DensityFunction;"))
    private DensityFunction gpunoise$wrapFinalDensity(DensityFunction finalDensity, DensityFunction beardifier) {
        return new GpuFinalDensity(finalDensity, beardifier, (NoiseChunk) (Object) this);
    }

    @Redirect(method = "computePreliminarySurfaceLevel", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/levelgen/DensityFunction;compute(Lnet/minecraft/world/level/levelgen/DensityFunction$FunctionContext;)D"))
    private double gpunoise$useGpuInitialDensity(DensityFunction vanillaFunction, DensityFunction.FunctionContext context) {
        GpuChunkDensityCache cache = gpunoise$cache;
        if (cache != null) {
            double gpu = cache.initialDensity(context.blockX(), context.blockY(), context.blockZ());
            if (!Double.isNaN(gpu)) return gpu;
        }
        return vanillaFunction.compute(context);
    }

    @Unique
    private static final class GpuFinalDensity implements DensityFunction.SimpleFunction {
        private final DensityFunction finalDensity;
        private final DensityFunction beardifier;
        private final NoiseChunk owner;

        private GpuFinalDensity(DensityFunction finalDensity, DensityFunction beardifier, NoiseChunk owner) {
            this.finalDensity = finalDensity;
            this.beardifier = beardifier;
            this.owner = owner;
        }

        @Override
        public double compute(DensityFunction.FunctionContext context) {
            if (context == owner) {
                GpuChunkDensityCache cache = ((GpuNoiseChunkAccess) (Object) owner).gpunoise$gpuCache();
                if (cache != null) {
                    double gpu = cache.density(context);
                    if (!Double.isNaN(gpu)) return gpu + beardifier.compute(context);
                }
            }
            return finalDensity.compute(context) + beardifier.compute(context);
        }

        @Override
        public DensityFunction mapAll(DensityFunction.Visitor visitor) {
            return visitor.apply(new GpuFinalDensity(finalDensity.mapAll(visitor), beardifier.mapAll(visitor), owner));
        }

        @Override public double minValue() { return finalDensity.minValue() + beardifier.minValue(); }
        @Override public double maxValue() { return finalDensity.maxValue() + beardifier.maxValue(); }
        @Override public net.minecraft.util.KeyDispatchDataCodec<? extends DensityFunction> codec() {
            return DensityFunctions.constant(0.0).codec();
        }
    }
}
