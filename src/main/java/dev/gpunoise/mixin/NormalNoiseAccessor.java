package dev.gpunoise.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import net.minecraft.world.level.levelgen.synth.PerlinNoise;
import dev.gpunoise.NormalNoiseAccess;

@Mixin(NormalNoise.class)
public interface NormalNoiseAccessor extends NormalNoiseAccess {
    @Accessor("first") PerlinNoise gpunoise$first();
    @Accessor("second") PerlinNoise gpunoise$second();
    @Accessor("valueFactor") double gpunoise$valueFactor();
}
