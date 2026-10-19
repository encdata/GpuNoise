package dev.gpunoise;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Packed contract and double-precision reference for vanilla aquifer results. */
public final class GpuAquiferSubstance {
    public static final int RECORD_BYTES = 80;
    public static final int OUTPUT_INTS = 4;
    public static final int MAX_QUERIES = GpuAquiferNearestThree.MAX_QUERIES;
    public static final int OTHER = 0;
    public static final int WATER = GpuAquiferPressure.WATER;
    public static final int LAVA = GpuAquiferPressure.LAVA;
    public static final int RETURN_DEFAULT = 0;
    public static final int RETURN_STATE = 1;
    public static final int CPU_FALLBACK = 2;
    // Matches the router parity gate while float64 classification is not yet
    // selected as a device capability variant.
    static final float PRECISION_EPSILON = 5.0e-3f;
    // Vanilla: similarity(square(10), square(12)) = 1 - (144 - 100) / 25.
    static final double FLOWING_UPDATE_SIMILARITY = 1.0 - (12 * 12 - 10 * 10) / 25.0;

    private GpuAquiferSubstance() {}

    public static ByteBuffer pack(Input[] inputs) {
        if (inputs.length == 0 || inputs.length > MAX_QUERIES)
            throw new IllegalArgumentException("invalid aquifer substance batch");
        ByteBuffer packed = ByteBuffer.allocateDirect(inputs.length * RECORD_BYTES)
                .order(ByteOrder.nativeOrder());
        for (Input input : inputs) {
            int flags = (input.globalIsLava ? 1 : 0) | (input.belowIsLava ? 2 : 0)
                    | (input.c2meAquifer ? 4 : 0);
            packed.putFloat((float) input.density).putFloat((float) input.barrier).putInt(input.y)
                    .putInt(input.level0).putInt(input.level1).putInt(input.level2).putInt(input.level3)
                    .putInt(input.distance0).putInt(input.distance1).putInt(input.distance2).putInt(input.distance3)
                    .putInt(input.kind0).putInt(input.kind1).putInt(input.kind2).putInt(input.kind3)
                    .putInt(input.statusEqualityMask).putInt(input.globalStateId).putInt(flags)
                    .putInt(input.nearestStateId).putInt(0);
        }
        return packed.flip();
    }

    /** Output words are action, state ID, schedule-fluid-update, reserved. */
    public static int[] evaluate(Input input) {
        if (!Double.isFinite(input.density)) return output(CPU_FALLBACK, 0, false);
        if (input.density > 0.0) return output(RETURN_DEFAULT, 0, false);
        if (input.globalIsLava) return output(RETURN_STATE, input.globalStateId, false);
        if (!Double.isFinite(input.barrier) || Math.abs(input.density) <= PRECISION_EPSILON)
            return output(CPU_FALLBACK, 0, false);

        double similarity01 = similarity(input.distance0, input.distance1);
        double similarity02 = similarity(input.distance0, input.distance2);
        double similarity12 = similarity(input.distance1, input.distance2);
        if (similarity01 <= 0.0) {
            return output(RETURN_STATE, input.nearestStateId,
                    similarity01 >= FLOWING_UPDATE_SIMILARITY
                            && (!input.c2meAquifer || !sameStatus(input, 0, 1)));
        }
        if (input.kind0 == WATER && input.belowIsLava)
            return output(RETURN_STATE, input.nearestStateId, true);

        double contribution01 = similarity01 * pressure(input.y, input.level0, input.level1,
                input.kind0, input.kind1, input.barrier);
        double firstResult = input.density + contribution01;
        if (nearThreshold(firstResult)) return output(CPU_FALLBACK, 0, false);
        if (firstResult > 0.0) return output(RETURN_DEFAULT, 0, false);

        if (similarity02 > 0.0) {
            double contribution02 = similarity01 * similarity02
                    * pressure(input.y, input.level0, input.level2,
                            input.kind0, input.kind2, input.barrier);
            double secondResult = input.density + contribution02;
            if (nearThreshold(secondResult)) return output(CPU_FALLBACK, 0, false);
            if (secondResult > 0.0) return output(RETURN_DEFAULT, 0, false);
        }

        if (similarity12 > 0.0) {
            double contribution12 = (input.c2meAquifer ? similarity01 * similarity12 : similarity12)
                    * pressure(input.y, input.level1, input.level2,
                            input.kind1, input.kind2, input.barrier);
            double thirdResult = input.density + contribution12;
            if (nearThreshold(thirdResult)) return output(CPU_FALLBACK, 0, false);
            if (thirdResult > 0.0) return output(RETURN_DEFAULT, 0, false);
        }
        if (!input.c2meAquifer) return output(RETURN_STATE, input.nearestStateId, true);

        boolean mayFlow01 = !sameStatus(input, 0, 1);
        boolean mayFlow12 = similarity12 >= FLOWING_UPDATE_SIMILARITY && !sameStatus(input, 1, 2);
        boolean mayFlow02 = similarity02 >= FLOWING_UPDATE_SIMILARITY && !sameStatus(input, 0, 2);
        boolean schedule = mayFlow01 || mayFlow12 || mayFlow02
                || (similarity02 >= FLOWING_UPDATE_SIMILARITY
                && similarity(input.distance0, input.distance3) >= FLOWING_UPDATE_SIMILARITY
                && !sameStatus(input, 0, 3));
        return output(RETURN_STATE, input.nearestStateId, schedule);
    }

    static double similarity(int firstDistance, int secondDistance) {
        return 1.0 - Math.abs(secondDistance - firstDistance) / 25.0;
    }

    private static double pressure(int y, int levelA, int levelB,
                                   int kindA, int kindB, double barrier) {
        if ((kindA == LAVA && kindB == WATER) || (kindA == WATER && kindB == LAVA)) return 2.0;
        int levelDistance = Math.abs(levelA - levelB);
        if (levelDistance == 0) return 0.0;
        double midpointDelta = y + 0.5 - 0.5 * (levelA + levelB);
        double adjustment = levelDistance * 0.5 - Math.abs(midpointDelta);
        if (midpointDelta > 0.0) adjustment = adjustment > 0.0 ? adjustment / 1.5 : adjustment / 2.5;
        else {
            double shifted = 3.0 + adjustment;
            adjustment = shifted > 0.0 ? shifted / 3.0 : shifted / 10.0;
        }
        double barrierValue = adjustment < -2.0 || adjustment > 2.0 ? 0.0 : barrier;
        return 2.0 * (barrierValue + adjustment);
    }

    private static boolean nearThreshold(double value) {
        return Math.abs(value) <= PRECISION_EPSILON;
    }

    private static int[] output(int action, int stateId, boolean schedule) {
        return new int[]{action, stateId, schedule ? 1 : 0, 0};
    }

    private static boolean sameStatus(Input input, int first, int second) {
        int bit = switch (first * 4 + second) {
            case 1, 4 -> 0;
            case 2, 8 -> 1;
            case 3, 12 -> 2;
            case 6, 9 -> 3;
            case 7, 13 -> 4;
            case 11, 14 -> 5;
            default -> throw new IllegalArgumentException("invalid aquifer status pair");
        };
        return (input.statusEqualityMask & (1 << bit)) != 0;
    }

    public record Input(double density, double barrier, int y,
                 int level0, int level1, int level2, int level3,
                 int distance0, int distance1, int distance2, int distance3,
                 int kind0, int kind1, int kind2, int kind3,
                 int statusEqualityMask, int globalStateId, boolean globalIsLava,
                 boolean belowIsLava, boolean c2meAquifer, int nearestStateId) {
        public Input(double density, double barrier, int y,
                     int level0, int level1, int level2,
                     int distance0, int distance1, int distance2,
                     int kind0, int kind1, int kind2,
                     int globalStateId, boolean globalIsLava, boolean belowIsLava,
                     int nearestStateId) {
            this(density, barrier, y, level0, level1, level2, 0,
                    distance0, distance1, distance2, 0,
                    kind0, kind1, kind2, OTHER, 0, globalStateId,
                    globalIsLava, belowIsLava, false, nearestStateId);
        }
    }
}
