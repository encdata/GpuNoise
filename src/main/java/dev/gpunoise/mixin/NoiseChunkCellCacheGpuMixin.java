package dev.gpunoise.mixin;

import dev.gpunoise.GpuNoiseChunkAccess;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Replaces the actual final CacheAllInCell fill call in selectCellYZ. */
@Mixin(NoiseChunk.class)
abstract class NoiseChunkCellCacheGpuMixin {
    @Redirect(method = "selectCellYZ", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/levelgen/DensityFunction;fillArray([DLnet/minecraft/world/level/levelgen/DensityFunction$ContextProvider;)V"))
    private void gpunoise$fillFinalCellFromGpu(DensityFunction function, double[] values,
                                                DensityFunction.ContextProvider contextProvider) {
        if (contextProvider instanceof GpuNoiseChunkAccess access
                && access.gpunoise$fillGpuFinalDensityCell(values)) {
            return;
        }
        function.fillArray(values, contextProvider);
    }
}
