package dev.gpunoise.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.world.level.levelgen.DensityFunction;
import dev.gpunoise.GpuTerrainRuntime;

@Mixin(targets = "net.minecraft.world.level.levelgen.DensityFunctions$Noise")
abstract class NoiseDensityMixin {
    @Shadow private DensityFunction.NoiseHolder noise;
    @Shadow private double xzScale;
    @Shadow private double yScale;

    @Inject(method = "fillArray", at = @At("HEAD"), cancellable = true)
    private void gpunoise$fillArray(double[] values, DensityFunction.ContextProvider contexts, CallbackInfo callback) {
        if (GpuTerrainRuntime.tryFill(noise.noise(), xzScale, yScale, contexts, values)) callback.cancel();
    }
}
