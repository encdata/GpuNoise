package dev.gpunoise;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Map;
import java.util.List;
import net.minecraft.world.level.levelgen.synth.NormalNoise;

/** Fixed-stride GPU representation of the lowered density graph. */
final class GpuDensityGraphBuffer {
    static final int NODE_BYTES = 48;
    record Packed(ByteBuffer nodes, ByteBuffer permutations, ByteBuffer octaves,
                  ByteBuffer samplerMetadata, ByteBuffer splinePoints, ByteBuffer blendedMetadata,
                  int nodeCount, int samplerCount, int blendedCount) {}

    private static final Map<String, Integer> OPCODES = Map.ofEntries(
            Map.entry("Constant", 1), Map.entry("Noise", 2), Map.entry("Ap2", 3),
            Map.entry("MulOrAdd", 4), Map.entry("Clamp", 5), Map.entry("Mapped", 6),
            Map.entry("Marker", 7), Map.entry("Shift", 8), Map.entry("ShiftA", 9),
            Map.entry("ShiftB", 10), Map.entry("ShiftedNoise", 11), Map.entry("Spline", 12),
            Map.entry("BlendedNoise", 13), Map.entry("YClampedGradient", 14), Map.entry("BlendDensity", 15), Map.entry("RangeChoice", 16), Map.entry("WeirdScaledSampler", 17)
    );

    private GpuDensityGraphBuffer() {}

    static Packed pack(GpuDensityGraph graph) {
        ByteBuffer output = ByteBuffer.allocateDirect(Math.multiplyExact(graph.nodes().size(), NODE_BYTES))
                .order(ByteOrder.nativeOrder());
        for (GpuDensityGraph.Node node : graph.nodes()) {
            Integer opcode = OPCODES.get(node.opcode());
            if (opcode == null) throw new IllegalArgumentException("No GPU opcode for " + node.opcode());
            int[] children = node.children();
            double[] constants = node.constants();
            output.putInt(opcode).putInt(children.length > 0 ? children[0] : -1)
                    .putInt(children.length > 1 ? children[1] : -1)
                    .putInt(children.length > 2 ? children[2] : -1);
            for (int i = 0; i < 4; i++) output.putFloat(i < constants.length ? (float) constants[i] : 0.0f);
            output.putInt(operation(node.payload())).putInt(children.length).putInt(constants.length).putInt(0);
        }
        ByteBuffer splinePoints = ByteBuffer.allocateDirect(graph.splines().size() * 64 * 16).order(ByteOrder.nativeOrder());
        for (int splineIndex = 0; splineIndex < graph.splines().size(); splineIndex++) {
            GpuDensityGraph.Spline spline = graph.splines().get(splineIndex);
            int[] valueNodes = spline.valueNodes(); float[] locations = spline.locations(); float[] derivatives = spline.derivatives();
            for (int i = 0; i < locations.length; i++) splinePoints.putFloat(locations[i]).putFloat(derivatives[i]).putInt(valueNodes[i]).putInt(0);
            splinePoints.position((splineIndex + 1) * 64 * 16);
        }
        List<NormalNoise> samplers = graph.noises();
        int permutationBytes = 0;
        int octaveBytes = 0;
        for (NormalNoise noise : samplers) {
            MinecraftNormalNoiseData data = MinecraftNormalNoiseData.read(noise);
            permutationBytes += (data.first().length + data.second().length) * MinecraftNoiseParameters.PERMUTATION_SIZE * 4;
            octaveBytes += (data.first().length + data.second().length) * MinecraftNoiseParameters.OCTAVE_BYTES;
        }
        for (GpuDensityGraph.Blended value : graph.blended()) {
            octaveBytes += perlinBytes(value.minLimit());
            octaveBytes += perlinBytes(value.maxLimit());
            octaveBytes += perlinBytes(value.main());
        }
        for (GpuDensityGraph.Blended value : graph.blended()) {
            permutationBytes += perlinPermutationBytes(value.minLimit());
            permutationBytes += perlinPermutationBytes(value.maxLimit());
            permutationBytes += perlinPermutationBytes(value.main());
        }
        ByteBuffer permutations = ByteBuffer.allocateDirect(permutationBytes).order(ByteOrder.nativeOrder());
        ByteBuffer octaves = ByteBuffer.allocateDirect(octaveBytes).order(ByteOrder.nativeOrder());
        ByteBuffer metadata = ByteBuffer.allocateDirect(samplers.size() * 32).order(ByteOrder.nativeOrder());
        int permutationBase = 0;
        int octaveBase = 0;
        for (NormalNoise noise : samplers) {
            MinecraftNormalNoiseData data = MinecraftNormalNoiseData.read(noise);
            permutations.put(MinecraftNoiseParameters.permutationBuffer(data.first(), data.second()));
            octaves.put(MinecraftNoiseParameters.octaveBuffer(data.first(), data.second(), data.amplitudes(),
                    data.firstInputFactor(), data.firstValueFactor(), data.secondInputFactor(), data.secondValueFactor()));
            metadata.putInt(octaveBase).putInt(data.first().length).putFloat((float) data.valueFactor()).putInt(permutationBase)
                    .putFloat((float) data.firstInputFactor()).putFloat((float) data.firstValueFactor())
                    .putFloat((float) data.secondInputFactor()).putFloat((float) data.secondValueFactor());
            permutationBase += (data.first().length + data.second().length) * MinecraftNoiseParameters.PERMUTATION_SIZE;
            octaveBase += data.first().length + data.second().length;
        }
        // std430: three PerlinInfo records (96), 32-byte alignment gap before
        // dvec4 scales, dvec4 (32), smear double (8), and 24 bytes padding.
        // The resulting array stride is 192 bytes.
        ByteBuffer blendedMetadata = ByteBuffer.allocateDirect(graph.blended().size() * 192).order(ByteOrder.nativeOrder());
        int blendedOctaveBase = octaveBase;
        int blendedPermutationBase = permutationBase;
        for (GpuDensityGraph.Blended value : graph.blended()) {
            MinecraftPerlinData[] perlin = {value.minLimit(), value.maxLimit(), value.main()};
            for (MinecraftPerlinData data : perlin) {
                blendedMetadata.putInt(blendedOctaveBase).putInt(data.noises().length)
                        .putFloat((float) data.lowestFreqInputFactor()).putFloat((float) data.lowestFreqValueFactor())
                        .putInt(blendedPermutationBase).putInt(0).putInt(0).putInt(0);
                putBlendedPerlin(permutations, octaves, data, blendedPermutationBase, blendedOctaveBase);
                blendedOctaveBase += data.noises().length;
                blendedPermutationBase += data.noises().length * MinecraftNoiseParameters.PERMUTATION_SIZE;
            }
            for (int i = 0; i < 8; i++) blendedMetadata.putInt(0); // align dvec4 to offset 128
            blendedMetadata.putDouble(value.xzMultiplier()).putDouble(value.yMultiplier())
                    .putDouble(value.xzFactor()).putDouble(value.yFactor())
                    .putDouble(value.smearScaleMultiplier());
            for (int i = 0; i < 6; i++) blendedMetadata.putInt(0); // pad record to 192 bytes
        }
        return new Packed((ByteBuffer) output.flip(), (ByteBuffer) permutations.flip(),
                (ByteBuffer) octaves.flip(), (ByteBuffer) metadata.flip(), (ByteBuffer) splinePoints.flip(),
                (ByteBuffer) blendedMetadata.flip(), graph.nodes().size(), samplers.size(), graph.blended().size());
    }

    private static int perlinPermutationBytes(MinecraftPerlinData data) {
        return data.noises().length * MinecraftNoiseParameters.PERMUTATION_SIZE * 4;
    }

    private static int perlinBytes(MinecraftPerlinData data) {
        return data.noises().length * MinecraftNoiseParameters.OCTAVE_BYTES;
    }

    private static void putPerlin(ByteBuffer permutations, ByteBuffer octaves, MinecraftPerlinData data,
                                  int permutationBase, int octaveBase) {
        MinecraftNoiseParameters.Improved[] levels = data.noises();
        double[] amplitudes = data.amplitudes();
        for (int i = 0; i < levels.length; i++) {
            MinecraftNoiseParameters.Improved level = levels[i];
            for (int p = 0; p < MinecraftNoiseParameters.PERMUTATION_SIZE; p++)
                permutations.putInt(level == null ? p : level.permutationAt(p));
            float input = (float) Math.scalb(data.lowestFreqInputFactor(), i);
            float value = (float) Math.scalb(data.lowestFreqValueFactor(), -i);
            if (level == null) octaves.putFloat(0).putFloat(0).putFloat(0).putFloat(0);
            else octaves.putFloat(level.xo()).putFloat(level.yo()).putFloat(level.zo()).putFloat((float) amplitudes[i]);
            octaves.putFloat(input).putFloat(value).putInt(permutationBase + i * MinecraftNoiseParameters.PERMUTATION_SIZE)
                    .putInt(level == null || amplitudes[i] == 0.0 ? 0 : 1);
        }
    }

    /** Packs the octave order used by BlendedNoise.getOctaveNoise(i). */
    private static void putBlendedPerlin(ByteBuffer permutations, ByteBuffer octaves, MinecraftPerlinData data,
                                         int permutationBase, int octaveBase) {
        MinecraftNoiseParameters.Improved[] levels = data.noises();
        double[] amplitudes = data.amplitudes();
        for (int i = 0; i < levels.length; i++) {
            int source = levels.length - 1 - i;
            MinecraftNoiseParameters.Improved level = levels[source];
            for (int p = 0; p < MinecraftNoiseParameters.PERMUTATION_SIZE; p++)
                permutations.putInt(level == null ? p : level.permutationAt(p));
            // BlendedNoise applies the octave frequency in its outer loop.
            // Do not apply it again in the packed Perlin level; doing so
            // squared the frequency and broke the full terrain graph.
            float input = 1.0f;
            float value = (float) Math.scalb(1.0, i);
            if (level == null) octaves.putFloat(0).putFloat(0).putFloat(0).putFloat(0);
            else octaves.putFloat(level.xo()).putFloat(level.yo()).putFloat(level.zo()).putFloat((float) amplitudes[source]);
            octaves.putFloat(input).putFloat(value).putInt(permutationBase + i * MinecraftNoiseParameters.PERMUTATION_SIZE)
                    .putInt(level == null ? 0 : 1);
        }
    }

    private static int operation(String payload) {
        if (payload == null) return 0;
        return switch (payload) {
            case "ADD" -> 1;
            case "MUL" -> 2;
            case "MIN" -> 3;
            case "MAX" -> 4;
            case "ABS" -> 5;
            case "SQUARE" -> 6;
            case "CUBE" -> 7;
            case "HALF_NEGATIVE" -> 8;
            case "QUARTER_NEGATIVE" -> 9;
            case "SQUEEZE" -> 10;
            case "TYPE1" -> 0;
            case "TYPE2" -> 1;
            default -> Math.abs(payload.hashCode());
        };
    }
}
