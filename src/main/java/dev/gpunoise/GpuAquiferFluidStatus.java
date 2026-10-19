package dev.gpunoise;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/** Packed C2ME OCL aquifer fluid-status prefill and conservative CPU reference. */
public final class GpuAquiferFluidStatus {
    public static final int SURFACE_SAMPLES = 13;
    /** One global fluid sample per C2ME OCL surface offset. */
    public static final int GLOBAL_LEVEL_SAMPLES = SURFACE_SAMPLES;
    public static final int RECORD_BYTES = 192;
    public static final int OUTPUT_INTS = 3;
    public static final int MAX_QUERIES = PersistentVulkanNormalNoiseEngine.CAPACITY;
    public static final int AIR = 0;
    public static final int WATER = 1;
    public static final int LAVA = 2;
    public static final int OTHER_FLUID = 3;
    public static final int FLAG_CPU_FALLBACK = 1;
    public static final int FLAG_LAVA_OVERRIDE = 2;
    public static final int LAVA_DISABLED_LEVEL = -32512;
    static final float DECISION_EPSILON = 5.0e-3f;

    private GpuAquiferFluidStatus() {}

    public static ByteBuffer pack(Input[] inputs) {
        if (inputs == null || inputs.length == 0 || inputs.length > MAX_QUERIES)
            throw new IllegalArgumentException("invalid aquifer fluid-status batch");
        ByteBuffer packed = ByteBuffer.allocateDirect(Math.multiplyExact(inputs.length, RECORD_BYTES))
                .order(ByteOrder.nativeOrder());
        for (Input input : inputs) put(packed, input);
        return packed.flip();
    }

    static List<ByteBuffer> pack(List<Input> inputs) {
        if (inputs == null) throw new NullPointerException("inputs");
        List<ByteBuffer> batches = new ArrayList<>();
        for (int offset = 0; offset < inputs.size(); offset += MAX_QUERIES) {
            int count = Math.min(MAX_QUERIES, inputs.size() - offset);
            ByteBuffer packed = ByteBuffer.allocateDirect(Math.multiplyExact(count, RECORD_BYTES))
                    .order(ByteOrder.nativeOrder());
            for (int index = offset; index < offset + count; index++) put(packed, inputs.get(index));
            batches.add(packed.flip());
        }
        return List.copyOf(batches);
    }

    private static void put(ByteBuffer packed, Input input) {
        if (input == null) throw new NullPointerException("aquifer fluid-status input");
        if (input.surfaceHeights == null || input.surfaceHeights.length != SURFACE_SAMPLES
                || input.globalLevels == null || input.globalLevels.length != GLOBAL_LEVEL_SAMPLES
                || input.globalKinds == null || input.globalKinds.length != GLOBAL_LEVEL_SAMPLES)
            throw new IllegalArgumentException("invalid aquifer fluid-status sample arrays");
        packed.putInt(input.y).putInt(input.defaultLevel).putInt(input.defaultKind).putInt(0)
                .putFloat(input.floodedness).putFloat(input.spread).putFloat(input.lava)
                .putFloat(input.erosion).putFloat(input.depth);
        for (float value : input.surfaceHeights) packed.putFloat(value);
        for (int value : input.globalLevels) packed.putInt(value);
        for (int value : input.globalKinds) packed.putInt(value);
    }

    /** Returns [fluid level, global-status source slot, flags]. */
    public static int[] evaluate(Input input) {
        if (!finite(input)) return result(0, 0, FLAG_CPU_FALLBACK);
        if (Math.abs(input.erosion + 0.225f) <= DECISION_EPSILON
                || Math.abs(input.depth - 0.9f) <= DECISION_EPSILON
                || Math.abs(Math.abs(input.lava) - 0.3f) <= DECISION_EPSILON)
            return result(0, 0, FLAG_CPU_FALLBACK);

        int surfaceEstimate = Integer.MAX_VALUE;
        int maxY = input.y + 12;
        int minY = input.y - 12;
        boolean centerHasFluid = false;
        for (int neighbor = 0; neighbor < SURFACE_SAMPLES; neighbor++) {
            int surface = (int) Math.floor(input.surfaceHeights[neighbor]);
            int o = surface + 8;
            boolean center = neighbor == 0;
            if (center && minY > o) return result(input.defaultLevel, 0, 0);
            boolean aboveSurface = maxY > o;
            if (aboveSurface || center) {
                int kindAtSurface = input.globalKinds[neighbor];
                if (kindAtSurface != AIR) {
                    if (center) centerHasFluid = true;
                    if (aboveSurface) return result(input.globalLevels[neighbor], neighbor + 1, 0);
                }
            }
            surfaceEstimate = Math.min(surfaceEstimate, surface);
        }

        float density, levelDensity;
        if (input.erosion < -0.225f && input.depth > 0.9f) {
            density = -1.0f;
            levelDensity = -1.0f;
        } else {
            int delta = surfaceEstimate + 8 - input.y;
            float fade = centerHasFluid
                    ? clamp(1.0f - (float) delta / 64.0f, 0.0f, 1.0f) : 0.0f;
            float floodedness = clamp(input.floodedness, -1.0f, 1.0f);
            density = floodedness + 0.8f + (fade - 1.0f) * 1.2f;
            levelDensity = floodedness + 0.3f + (fade - 1.0f) * 1.1f;
        }
        if (Math.abs(density) <= DECISION_EPSILON
                || Math.abs(levelDensity) <= DECISION_EPSILON)
            return result(0, 0, FLAG_CPU_FALLBACK);

        int fluidLevel;
        if (levelDensity > 0.0f) {
            fluidLevel = input.defaultLevel;
        } else if (density > 0.0f) {
            double scaledSpread = input.spread * 10.0;
            double multipleBoundary = scaledSpread / 3.0;
            if (Math.abs(multipleBoundary - Math.rint(multipleBoundary)) <= DECISION_EPSILON)
                return result(0, 0, FLAG_CPU_FALLBACK);
            int base = Math.floorDiv(input.y, 40) * 40 + 20;
            int spreadOffset = (int) Math.floor(multipleBoundary) * 3;
            fluidLevel = Math.min(surfaceEstimate, base + spreadOffset);
        } else {
            fluidLevel = LAVA_DISABLED_LEVEL;
        }

        if (fluidLevel <= -10 && fluidLevel != LAVA_DISABLED_LEVEL
                && input.defaultKind != LAVA && Math.abs(input.lava) > 0.3f) {
            return result(fluidLevel, 0, FLAG_LAVA_OVERRIDE);
        }
        return result(fluidLevel, 0, 0);
    }

    private static boolean finite(Input input) {
        if (input.surfaceHeights == null || input.surfaceHeights.length != SURFACE_SAMPLES
                || input.globalLevels == null || input.globalLevels.length != GLOBAL_LEVEL_SAMPLES
                || input.globalKinds == null || input.globalKinds.length != GLOBAL_LEVEL_SAMPLES
                || !Float.isFinite(input.floodedness) || !Float.isFinite(input.spread)
                || !Float.isFinite(input.lava) || !Float.isFinite(input.erosion)
                || !Float.isFinite(input.depth)) return false;
        for (float value : input.surfaceHeights) if (!Float.isFinite(value)) return false;
        return true;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int[] result(int level, int source, int flags) {
        return new int[]{level, source, flags};
    }

    public record Input(int y, int defaultLevel, int defaultKind,
                        float floodedness, float spread, float lava, float erosion, float depth,
                        float[] surfaceHeights, int[] globalLevels, int[] globalKinds) {}
}
