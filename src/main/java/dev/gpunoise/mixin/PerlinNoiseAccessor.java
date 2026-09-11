package dev.gpunoise.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import net.minecraft.world.level.levelgen.synth.ImprovedNoise;
import net.minecraft.world.level.levelgen.synth.PerlinNoise;
import it.unimi.dsi.fastutil.doubles.DoubleList;

@Mixin(PerlinNoise.class)
public interface PerlinNoiseAccessor {
    @Accessor("noiseLevels") ImprovedNoise[] gpunoise$noiseLevels();
    @Accessor("amplitudes") DoubleList gpunoise$amplitudes();
    @Accessor("lowestFreqInputFactor") double gpunoise$lowestFreqInputFactor();
    @Accessor("lowestFreqValueFactor") double gpunoise$lowestFreqValueFactor();
}
