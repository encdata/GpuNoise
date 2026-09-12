package dev.gpunoise.mixin;

import net.minecraft.world.level.levelgen.synth.BlendedNoise;
import net.minecraft.world.level.levelgen.synth.PerlinNoise;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import dev.gpunoise.BlendedNoiseAccess;

@Mixin(BlendedNoise.class)
public interface BlendedNoiseAccessor extends BlendedNoiseAccess {
    @Accessor("minLimitNoise") PerlinNoise gpunoise$minLimitNoise();
    @Accessor("maxLimitNoise") PerlinNoise gpunoise$maxLimitNoise();
    @Accessor("mainNoise") PerlinNoise gpunoise$mainNoise();
    @Accessor("xzMultiplier") double gpunoise$xzMultiplier();
    @Accessor("yMultiplier") double gpunoise$yMultiplier();
    @Accessor("xzFactor") double gpunoise$xzFactor();
    @Accessor("yFactor") double gpunoise$yFactor();
    @Accessor("smearScaleMultiplier") double gpunoise$smearScaleMultiplier();
}
