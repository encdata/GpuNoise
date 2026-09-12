package dev.gpunoise;

import dev.gpunoise.ImprovedNoiseAccess;
import dev.gpunoise.PerlinNoiseAccess;
import net.minecraft.world.level.levelgen.synth.ImprovedNoise;
import net.minecraft.world.level.levelgen.synth.PerlinNoise;

/** Exact live state of one vanilla PerlinNoise, including null octave slots. */
record MinecraftPerlinData(MinecraftNoiseParameters.Improved[] noises, double[] amplitudes,
                           double lowestFreqInputFactor, double lowestFreqValueFactor) {
    static MinecraftPerlinData read(PerlinNoise perlin) {
        PerlinNoiseAccess accessor = (PerlinNoiseAccess) (Object) perlin;
        ImprovedNoise[] levels = accessor.gpunoise$noiseLevels();
        double[] amplitudes = new double[accessor.gpunoise$amplitudes().size()];
        MinecraftNoiseParameters.Improved[] noises = new MinecraftNoiseParameters.Improved[levels.length];
        for (int i = 0; i < amplitudes.length; i++) amplitudes[i] = accessor.gpunoise$amplitudes().getDouble(i);
        for (int i = 0; i < levels.length; i++) {
            ImprovedNoise level = levels[i];
            if (level == null) continue;
            byte[] permutation = ((ImprovedNoiseAccess) (Object) level).gpunoise$permutation();
            int[] values = new int[permutation.length];
            for (int j = 0; j < permutation.length; j++) values[j] = permutation[j] & 255;
            noises[i] = new MinecraftNoiseParameters.Improved((float) level.xo, (float) level.yo, (float) level.zo, values);
        }
        return new MinecraftPerlinData(noises, amplitudes, accessor.gpunoise$lowestFreqInputFactor(), accessor.gpunoise$lowestFreqValueFactor());
    }
}
