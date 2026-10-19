package dev.gpunoise;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;

/** Packed global submission format: samples may use different live NormalNoise samplers. */
final class GpuGlobalNoiseBatch {
    static final int MAX_SAMPLES = 262_144;
    record Packed(ByteBuffer samples, ByteBuffer results, ByteBuffer permutations, ByteBuffer octaves,
                  ByteBuffer samplerMetadata, int sampleCount, int samplerCount) {}

    // Primitive columns avoid one Sample object per point and let producers append
    // whole vanilla fillArray requests with bulk copies.
    private float[] x = new float[256];
    private float[] y = new float[256];
    private float[] z = new float[256];
    private int[] samplerIds = new int[256];
    private final IdentityHashMap<MinecraftNormalNoiseData, Integer> samplerIdsByIdentity = new IdentityHashMap<>();
    private final List<MinecraftNormalNoiseData> samplers = new ArrayList<>();
    private int size;

    int size() { return size; }

    boolean addAll(MinecraftNormalNoiseData sampler, float[] xs, float[] ys, float[] zs) {
        if (sampler == null || xs == null || ys == null || zs == null
                || xs.length != ys.length || xs.length != zs.length
                || (long) size + xs.length > MAX_SAMPLES) return false;
        ensureCapacity(size + xs.length);
        Integer samplerId = samplerIdsByIdentity.get(sampler);
        if (samplerId == null) {
            samplerId = samplers.size();
            samplerIdsByIdentity.put(sampler, samplerId);
            samplers.add(sampler);
        }
        System.arraycopy(xs, 0, x, size, xs.length);
        System.arraycopy(ys, 0, y, size, ys.length);
        System.arraycopy(zs, 0, z, size, zs.length);
        java.util.Arrays.fill(samplerIds, size, size + xs.length, samplerId);
        size += xs.length;
        return true;
    }

    private void ensureCapacity(int required) {
        if (required <= x.length) return;
        int capacity = Math.min(MAX_SAMPLES, Math.max(required, x.length + (x.length >> 1)));
        x = java.util.Arrays.copyOf(x, capacity);
        y = java.util.Arrays.copyOf(y, capacity);
        z = java.util.Arrays.copyOf(z, capacity);
        samplerIds = java.util.Arrays.copyOf(samplerIds, capacity);
    }

    Packed pack() {
        int permutationBytes = 0, octaveBytes = 0;
        int[] permutationBase = new int[samplers.size()];
        int[] octaveBase = new int[samplers.size()];
        for (int i = 0; i < samplers.size(); i++) {
            MinecraftNormalNoiseData data = samplers.get(i);
            permutationBase[i] = permutationBytes / Integer.BYTES;
            octaveBase[i] = octaveBytes / MinecraftNoiseParameters.OCTAVE_BYTES;
            permutationBytes += (data.first().length + data.second().length)
                    * MinecraftNoiseParameters.PERMUTATION_SIZE * Integer.BYTES;
            octaveBytes += (data.first().length + data.second().length) * MinecraftNoiseParameters.OCTAVE_BYTES;
        }
        // std430 array stride for { vec4 position; uint samplerId; } is 32 bytes.
        ByteBuffer coordinates = ByteBuffer.allocateDirect(size * 32).order(ByteOrder.nativeOrder());
        ByteBuffer results = ByteBuffer.allocateDirect(size * Float.BYTES).order(ByteOrder.nativeOrder());
        ByteBuffer permutations = ByteBuffer.allocateDirect(permutationBytes).order(ByteOrder.nativeOrder());
        ByteBuffer octaves = ByteBuffer.allocateDirect(octaveBytes).order(ByteOrder.nativeOrder());
        ByteBuffer metadata = ByteBuffer.allocateDirect(samplers.size() * 16).order(ByteOrder.nativeOrder());
        for (int i = 0; i < samplers.size(); i++) {
            MinecraftNormalNoiseData data = samplers.get(i);
            permutations.put(MinecraftNoiseParameters.permutationBuffer(data.first(), data.second()));
            octaves.put(MinecraftNoiseParameters.octaveBuffer(data.first(), data.second(), data.amplitudes()));
            metadata.putInt(octaveBase[i]).putInt(data.first().length)
                    .putFloat((float) data.valueFactor()).putInt(permutationBase[i]);
        }
        for (int i = 0; i < size; i++) {
            coordinates.putFloat(x[i]).putFloat(y[i]).putFloat(z[i]).putFloat(0)
                    .putInt(samplerIds[i]).putInt(0).putInt(0).putInt(0);
        }
        return new Packed(flip(coordinates), flip(results), flip(permutations), flip(octaves),
                flip(metadata), size, samplers.size());
    }

    private static ByteBuffer flip(ByteBuffer buffer) { return (ByteBuffer) buffer.flip(); }
}
