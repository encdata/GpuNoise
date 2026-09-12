package dev.gpunoise;

import net.minecraft.world.level.levelgen.synth.ImprovedNoise;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import net.minecraft.world.level.levelgen.synth.PerlinNoise;
import dev.gpunoise.ImprovedNoiseAccess;
import dev.gpunoise.NormalNoiseAccess;
import dev.gpunoise.PerlinNoiseAccess;

/** Extracts the live vanilla NormalNoise state without approximating or reseeding it. */
record MinecraftNormalNoiseData(
        MinecraftNoiseParameters.Improved[] first,
        MinecraftNoiseParameters.Improved[] second,
        double[] amplitudes,
        double valueFactor,
        double firstInputFactor, double firstValueFactor,
        double secondInputFactor, double secondValueFactor
) {
    static MinecraftNormalNoiseData read(NormalNoise noise) {
        NormalNoiseAccess normal = (NormalNoiseAccess)(Object)noise;
        PerlinData first = readPerlin(normal.gpunoise$first());
        PerlinData second = readPerlin(normal.gpunoise$second());
        if (first.amplitudes.length != second.amplitudes.length) {
            throw new IllegalStateException("NormalNoise octave arrays differ");
        }
        return new MinecraftNormalNoiseData(first.noises, second.noises, first.amplitudes, normal.gpunoise$valueFactor(),
                first.inputFactor, first.valueFactor, second.inputFactor, second.valueFactor);
    }

    private static PerlinData readPerlin(PerlinNoise perlin) {
        PerlinNoiseAccess accessor = (PerlinNoiseAccess)(Object)perlin;
        ImprovedNoise[] levels = accessor.gpunoise$noiseLevels();
        double[] amplitudes = new double[accessor.gpunoise$amplitudes().size()];
        MinecraftNoiseParameters.Improved[] noises = new MinecraftNoiseParameters.Improved[levels.length];
        for (int i = 0; i < amplitudes.length; i++) amplitudes[i] = accessor.gpunoise$amplitudes().getDouble(i);
        for (int i = 0; i < levels.length; i++) {
            ImprovedNoise level = levels[i];
            if (level == null) continue;
            byte[] permutation = ((ImprovedNoiseAccess)(Object)level).gpunoise$permutation();
            int[] values = new int[permutation.length];
            for (int j = 0; j < permutation.length; j++) values[j] = permutation[j] & 255;
            noises[i] = new MinecraftNoiseParameters.Improved((float)level.xo, (float)level.yo, (float)level.zo, values);
        }
        return new PerlinData(noises, amplitudes, accessor.gpunoise$lowestFreqInputFactor(), accessor.gpunoise$lowestFreqValueFactor());
    }

    private record PerlinData(MinecraftNoiseParameters.Improved[] noises, double[] amplitudes,
                              double inputFactor, double valueFactor) {}
}
