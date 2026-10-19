package dev.gpunoise;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/** Packed contract and double-precision reference for vanilla aquifer results. */
public final class GpuAquiferSubstance {
    public static final int RECORD_BYTES = 80;
    public static final int OUTPUT_INTS = 4;
    /**
     * Substance evaluation is a compact one-dispatch pass over the full block
     * volume. Keep center selection's smaller limit independent: its records
     * are wider and can be split without forcing additional substance submits.
     */
    public static final int MAX_QUERIES = PersistentVulkanNormalNoiseEngine.CAPACITY;
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

    /** Returns true when GPU rounding can change the density sign decision. */
    public static boolean nearDensityDecisionThreshold(double density) {
        return Double.isFinite(density) && Math.abs(density) <= PRECISION_EPSILON;
    }

    public static ByteBuffer pack(Input[] inputs) {
        if (inputs == null || inputs.length == 0 || inputs.length > MAX_QUERIES)
            throw new IllegalArgumentException("invalid aquifer substance batch");
        ByteBuffer packed = allocateBatch(inputs.length);
        for (Input input : inputs) putInput(packed, input);
        return packed.flip();
    }

    /** Packs a range directly from the caller's list without a temporary array copy. */
    static ByteBuffer pack(List<Input> inputs, int offset, int count) {
        if (inputs == null || count <= 0 || count > MAX_QUERIES
                || offset < 0 || offset > inputs.size() - count)
            throw new IllegalArgumentException("invalid aquifer substance batch range");
        ByteBuffer packed = allocateBatch(count);
        for (int index = offset; index < offset + count; index++) putInput(packed, inputs.get(index));
        return packed.flip();
    }

    private static ByteBuffer allocateBatch(int count) {
        return ByteBuffer.allocateDirect(Math.multiplyExact(count, RECORD_BYTES))
                .order(ByteOrder.nativeOrder());
    }

    private static void putInput(ByteBuffer packed, Input input) {
        int flags = (input.globalIsLava ? 1 : 0) | (input.belowIsLava ? 2 : 0)
                | (input.c2meAquifer ? 4 : 0);
        packed.putFloat((float) input.density).putFloat((float) input.barrier).putInt(input.y)
                .putInt(input.level0).putInt(input.level1).putInt(input.level2).putInt(input.level3)
                .putInt(input.distance0).putInt(input.distance1).putInt(input.distance2).putInt(input.distance3)
                .putInt(input.kind0).putInt(input.kind1).putInt(input.kind2).putInt(input.kind3)
                .putInt(input.statusEqualityMask).putInt(input.globalStateId).putInt(flags)
                .putInt(input.nearestStateId).putInt(0);
    }

    /** Incremental direct-buffer staging for chunk-sized aquifer decisions. */
    public static final class BatchWriter {
        private final int expectedRows;
        private final List<ByteBuffer> completed = new ArrayList<>();
        private ByteBuffer current;
        private int size;
        private boolean finished;

        public BatchWriter(int expectedRows) {
            if (expectedRows < 0) throw new IllegalArgumentException("negative aquifer batch capacity");
            this.expectedRows = expectedRows;
        }

        public int size() { return size; }

        public void add(Input input) {
            if (input == null) throw new NullPointerException("input");
            write(input.density, input.barrier, input.y, input.level0, input.level1,
                    input.level2, input.level3, input.distance0, input.distance1,
                    input.distance2, input.distance3, input.kind0, input.kind1,
                    input.kind2, input.kind3, input.statusEqualityMask,
                    input.globalStateId, input.globalIsLava, input.belowIsLava,
                    input.c2meAquifer, input.nearestStateId);
        }

        public void addPositiveDensity(double density, int y, boolean c2meAquifer) {
            write(density, 0.0, y, 0, 0, 0, 0, 0, 0, 0, 0,
                    OTHER, OTHER, OTHER, OTHER, 0, 0, false, false, c2meAquifer, 0);
        }

        public void addGlobalLava(double density, int y, int stateId, boolean c2meAquifer) {
            write(density, 0.0, y, 0, 0, 0, 0, 0, 0, 0, 0,
                    OTHER, OTHER, OTHER, OTHER, 0, stateId, true, false, c2meAquifer, 0);
        }

        public void addAquifer(double density, double barrier, int y,
                        int level0, int level1, int level2, int level3,
                        int distance0, int distance1, int distance2, int distance3,
                        int kind0, int kind1, int kind2, int kind3,
                        int statusEqualityMask, boolean belowIsLava, boolean c2meAquifer) {
            write(density, barrier, y, level0, level1, level2, level3,
                    distance0, distance1, distance2, distance3, kind0, kind1,
                    kind2, kind3, statusEqualityMask, 0, false, belowIsLava,
                    c2meAquifer, 0);
        }

        public List<ByteBuffer> finish() {
            if (finished) throw new IllegalStateException("aquifer batch writer is already finished");
            finished = true;
            if (current != null && current.position() > 0) {
                current.flip();
                completed.add(current);
                current = null;
            }
            return List.copyOf(completed);
        }

        private void write(double density, double barrier, int y,
                           int level0, int level1, int level2, int level3,
                           int distance0, int distance1, int distance2, int distance3,
                           int kind0, int kind1, int kind2, int kind3,
                           int statusEqualityMask, int globalStateId,
                           boolean globalIsLava, boolean belowIsLava,
                           boolean c2meAquifer, int nearestStateId) {
            if (finished) throw new IllegalStateException("aquifer batch writer is already finished");
            if (size >= expectedRows) throw new IllegalStateException("aquifer batch exceeded its chunk capacity");
            if (current == null || !current.hasRemaining()) {
                if (current != null) {
                    current.flip();
                    completed.add(current);
                }
                current = allocateBatch(Math.min(expectedRows - size, MAX_QUERIES));
            }
            int flags = (globalIsLava ? 1 : 0) | (belowIsLava ? 2 : 0)
                    | (c2meAquifer ? 4 : 0);
            current.putFloat((float) density).putFloat((float) barrier).putInt(y)
                    .putInt(level0).putInt(level1).putInt(level2).putInt(level3)
                    .putInt(distance0).putInt(distance1).putInt(distance2).putInt(distance3)
                    .putInt(kind0).putInt(kind1).putInt(kind2).putInt(kind3)
                    .putInt(statusEqualityMask).putInt(globalStateId).putInt(flags)
                    .putInt(nearestStateId).putInt(0);
            size++;
        }
    }

    /** Output words are action, state ID, schedule-fluid-update, reserved. */
    public static int[] evaluate(Input input) {
        if (!Double.isFinite(input.density)) return output(CPU_FALLBACK, 0, false);
        if (nearDensityDecisionThreshold(input.density)) return output(CPU_FALLBACK, 0, false);
        if (input.density > 0.0) return output(RETURN_DEFAULT, 0, false);
        if (input.globalIsLava) return output(RETURN_STATE, input.globalStateId, false);
        if (!Double.isFinite(input.barrier))
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
            double contribution12 = similarity01 * similarity12
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
