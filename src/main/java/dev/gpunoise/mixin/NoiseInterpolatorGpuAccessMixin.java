package dev.gpunoise.mixin;

import dev.gpunoise.GpuNoiseInterpolatorAccess;
import net.minecraft.world.level.levelgen.NoiseChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Exposes the existing interpolation operations without changing their math. */
@Mixin(NoiseChunk.NoiseInterpolator.class)
abstract class NoiseInterpolatorGpuAccessMixin implements GpuNoiseInterpolatorAccess {
    @Override
    @Invoker("updateForY")
    public abstract void gpunoise$updateForY(double y);

    @Override
    @Invoker("updateForX")
    public abstract void gpunoise$updateForX(double x);

    @Override
    @Invoker("updateForZ")
    public abstract void gpunoise$updateForZ(double z);
}
