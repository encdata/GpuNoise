package dev.gpunoise.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import net.minecraft.world.level.levelgen.synth.ImprovedNoise;

@Mixin(ImprovedNoise.class)
public interface ImprovedNoiseAccessor {
    @Accessor("p") byte[] gpunoise$permutation();
}
