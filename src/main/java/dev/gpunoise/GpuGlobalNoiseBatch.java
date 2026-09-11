package dev.gpunoise;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** Packed global submission format: samples may use different live NormalNoise samplers. */
final class GpuGlobalNoiseBatch {
    static final int MAX_SAMPLES = 262_144;

    record Sample(MinecraftNormalNoiseData sampler, float x, float y, float z) {}
    record Packed(ByteBuffer samples, ByteBuffer results, ByteBuffer permutations, ByteBuffer octaves, ByteBuffer samplerMetadata, int sampleCount, int samplerCount) {}

    private final List<Sample> samples = new ArrayList<>();
    private final Map<MinecraftNormalNoiseData, Integer> samplers = new IdentityHashMap<>();

    boolean add(Sample sample) {
        if (samples.size() >= MAX_SAMPLES) return false;
        samples.add(sample);
        samplers.computeIfAbsent(sample.sampler(), ignored -> samplers.size());
        return true;
    }

    int size() { return samples.size(); }

    Packed pack() {
        List<MinecraftNormalNoiseData> unique = new ArrayList<>(samplers.size());
        for (Sample sample : samples) if (!unique.contains(sample.sampler())) unique.add(sample.sampler());
        int permutationBytes = 0, octaveBytes = 0;
        int[] permutationBase = new int[unique.size()];
        int[] octaveBase = new int[unique.size()];
        for (int i = 0; i < unique.size(); i++) {
            MinecraftNormalNoiseData data = unique.get(i);
            permutationBase[i] = permutationBytes / 4;
            octaveBase[i] = octaveBytes / MinecraftNoiseParameters.OCTAVE_BYTES;
            permutationBytes += (data.first().length + data.second().length) * MinecraftNoiseParameters.PERMUTATION_SIZE * 4;
            octaveBytes += (data.first().length + data.second().length) * MinecraftNoiseParameters.OCTAVE_BYTES;
        }
        // std430 array stride for { vec4 position; uint samplerId; } is 32 bytes.
        ByteBuffer coordinates = ByteBuffer.allocateDirect(samples.size() * 32).order(ByteOrder.nativeOrder());
        ByteBuffer results = ByteBuffer.allocateDirect(samples.size() * 4).order(ByteOrder.nativeOrder());
        ByteBuffer permutations = ByteBuffer.allocateDirect(permutationBytes).order(ByteOrder.nativeOrder());
        ByteBuffer octaves = ByteBuffer.allocateDirect(octaveBytes).order(ByteOrder.nativeOrder());
        ByteBuffer metadata = ByteBuffer.allocateDirect(unique.size() * 16).order(ByteOrder.nativeOrder());
        for (int i = 0; i < unique.size(); i++) {
            MinecraftNormalNoiseData data = unique.get(i);
            ByteBuffer p = MinecraftNoiseParameters.permutationBuffer(data.first(), data.second());
            ByteBuffer o = MinecraftNoiseParameters.octaveBuffer(data.first(), data.second(), data.amplitudes());
            permutations.put(p); octaves.put(o);
            metadata.putInt(octaveBase[i]).putInt(data.first().length).putFloat((float)data.valueFactor()).putInt(permutationBase[i]);
        }
        for (Sample sample : samples) {
            int sampler = samplers.get(sample.sampler());
            coordinates.putFloat(sample.x()).putFloat(sample.y()).putFloat(sample.z()).putFloat(0).putInt(sampler)
                    .putInt(0).putInt(0).putInt(0);
        }
        return new Packed(flip(coordinates), flip(results), flip(permutations), flip(octaves), flip(metadata), samples.size(), unique.size());
    }

    private static ByteBuffer flip(ByteBuffer buffer) { return (ByteBuffer)buffer.flip(); }
}
