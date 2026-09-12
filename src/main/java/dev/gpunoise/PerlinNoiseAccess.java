package dev.gpunoise;

import it.unimi.dsi.fastutil.doubles.DoubleList;
import net.minecraft.world.level.levelgen.synth.ImprovedNoise;

public interface PerlinNoiseAccess {
    ImprovedNoise[] gpunoise$noiseLevels();
    DoubleList gpunoise$amplitudes();
    double gpunoise$lowestFreqInputFactor();
    double gpunoise$lowestFreqValueFactor();
}
