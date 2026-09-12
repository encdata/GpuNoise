package dev.gpunoise.mixin;

import java.util.function.Supplier;

import dev.gpunoise.C2meCompiledDensityAccess;
import net.minecraft.world.level.levelgen.DensityFunction;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Exposes C2ME DFC's retained vanilla graph without depending on C2ME at
 * compile time. The mixin is conditionally applied by GpuNoiseMixinPlugin.
 */
@Mixin(targets = "com.ishland.c2me.opts.dfc.common.gen.jvm.AbstractCompiledDensityFunction", remap = false)
abstract class C2meCompiledDensityFunctionMixin implements C2meCompiledDensityAccess {
    @Shadow @Final protected Supplier<DensityFunction> blendingFallback;

    @Override
    public DensityFunction gpunoise$vanillaFallback() {
        return blendingFallback == null ? null : blendingFallback.get();
    }
}
