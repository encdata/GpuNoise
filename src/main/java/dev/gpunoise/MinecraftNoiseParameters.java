package dev.gpunoise;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

import net.minecraft.util.RandomSource;

/** CPU-side serialized parameters for the GPU ImprovedNoise/Perlin shader. */
final class MinecraftNoiseParameters {
    static final int PERMUTATION_SIZE = 256;
    static final int OCTAVE_BYTES = 32;

    private MinecraftNoiseParameters() {}

    /** Reproduces ImprovedNoise's constructor, including offsets and shuffle order. */
    static Improved improved(RandomSource random) {
        float xo = (float)(random.nextDouble() * 256.0);
        float yo = (float)(random.nextDouble() * 256.0);
        float zo = (float)(random.nextDouble() * 256.0);
        int[] permutation = new int[PERMUTATION_SIZE];
        for (int i = 0; i < PERMUTATION_SIZE; i++) permutation[i] = i;
        for (int i = 0; i < PERMUTATION_SIZE; i++) {
            int swap = i + random.nextInt(PERMUTATION_SIZE - i);
            int value = permutation[i];
            permutation[i] = permutation[swap];
            permutation[swap] = value;
        }
        return new Improved(xo, yo, zo, permutation);
    }

    static ByteBuffer permutationBuffer(Improved... noises) {
        ByteBuffer result = ByteBuffer.allocateDirect(noises.length * PERMUTATION_SIZE * 4).order(ByteOrder.nativeOrder());
        for (Improved noise : noises) for (int value : noise.permutation()) result.putInt(value);
        return result.flip();
    }

    static ByteBuffer permutationBuffer(Improved[]... levels) {
        int total = 0;
        for (Improved[] array : levels) total += array.length;
        ByteBuffer result = ByteBuffer.allocateDirect(total * PERMUTATION_SIZE * 4).order(ByteOrder.nativeOrder());
        for (Improved[] array : levels) {
            for (Improved noise : array) {
                for (int i = 0; i < PERMUTATION_SIZE; i++) result.putInt(noise == null ? i : noise.permutationAt(i));
            }
        }
        return result.flip();
    }

    /** Matches the std430 Octave struct in minecraft_noise.comp. */
    static ByteBuffer octaveBuffer(Improved[] first, Improved[] second, double[] amplitudes) {
        return octaveBuffer(first, second, amplitudes, 1.0, 1.0, 1.0, 1.0);
    }

    static ByteBuffer octaveBuffer(Improved[] first, Improved[] second, double[] amplitudes,
                                   double firstInput, double firstValue, double secondInput, double secondValue) {
        if (first.length != second.length || first.length != amplitudes.length) throw new IllegalArgumentException("octave arrays differ");
        ByteBuffer result = ByteBuffer.allocateDirect(first.length * 2 * OCTAVE_BYTES).order(ByteOrder.nativeOrder());
        putOctaves(result, first, amplitudes, 0, firstInput, firstValue);
        putOctaves(result, second, amplitudes, first.length * PERMUTATION_SIZE, secondInput, secondValue);
        return result.flip();
    }

    private static void putOctaves(ByteBuffer result, Improved[] noises, double[] amplitudes, int permutationOffset,
                                   double inputFactor, double valueFactor) {
        for (int i = 0; i < noises.length; i++) {
            Improved noise = noises[i];
            if (noise == null) result.putFloat(0).putFloat(0).putFloat(0).putFloat(0);
            else result.putFloat(noise.xo()).putFloat(noise.yo()).putFloat(noise.zo()).putFloat((float)amplitudes[i]);
            result.putFloat((float)Math.scalb(inputFactor, i)).putFloat((float)Math.scalb(valueFactor, -i));
            result.putInt(permutationOffset + i * PERMUTATION_SIZE).putInt(noise == null || amplitudes[i] == 0.0 ? 0 : 1);
        }
    }

    record Improved(float xo, float yo, float zo, int[] permutation) {
        Improved {
            permutation = permutation.clone();
            if (permutation.length != PERMUTATION_SIZE) throw new IllegalArgumentException("permutation must have 256 entries");
        }

        @Override public int[] permutation() { return Arrays.copyOf(permutation, permutation.length); }
        int permutationAt(int index) { return permutation[index & 255]; }
    }
}
