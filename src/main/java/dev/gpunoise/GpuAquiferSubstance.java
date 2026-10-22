package dev.gpunoise;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Packed contract and double-precision reference for vanilla aquifer results. */
public final class GpuAquiferSubstance {
    /** 18 words: aquifer inputs, flags, and an 8-bit chunk-local state palette slot. */
    public static final int RECORD_BYTES = 72;
    /** One logical result word per input row; Vulkan stores two decisions per uint. */
    public static final int OUTPUT_INTS = 1;
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
    private static final int OUTPUT_ACTION_MASK = 0x3;
    private static final int OUTPUT_SCHEDULE_FLUID_UPDATE = 1 << 2;
    private static final int OUTPUT_STATE_SHIFT = 3;
    private static final int MAX_STATE_ID = 0x1fff_ffff;
    private static final int MAX_OUTPUT_STATES = 256;
    static final int FLAG_STATE_MAPPING_OVERFLOW = 8;
    // Matches the router parity gate while float64 classification is not yet
    // selected as a device capability variant.
    static final float PRECISION_EPSILON = 5.0e-3f;
    // Vanilla: similarity(square(10), square(12)) = 1 - (144 - 100) / 25.
    static final double FLOWING_UPDATE_SIMILARITY = 1.0 - (12 * 12 - 10 * 10) / 25.0;

    private GpuAquiferSubstance() {}

    static int packOutput(int action, boolean scheduleFluidUpdate) {
        return packOutput(action, 0, scheduleFluidUpdate);
    }

    static int packOutput(int action, int stateId, boolean scheduleFluidUpdate) {
        if (action < RETURN_DEFAULT || action > CPU_FALLBACK)
            throw new IllegalArgumentException("invalid aquifer action " + action);
        if (stateId < 0 || stateId > MAX_STATE_ID)
            throw new IllegalArgumentException("block-state ID cannot fit aquifer output: " + stateId);
        return (stateId << OUTPUT_STATE_SHIFT) | action
                | (scheduleFluidUpdate ? OUTPUT_SCHEDULE_FLUID_UPDATE : 0);
    }

    public static int outputAction(int packed) { return packed & OUTPUT_ACTION_MASK; }

    public static int outputStateId(int packed) { return packed >>> OUTPUT_STATE_SHIFT; }

    public static boolean outputSchedulesFluidUpdate(int packed) {
        return (packed & OUTPUT_SCHEDULE_FLUID_UPDATE) != 0;
    }

    public static boolean isValidPackedOutput(int packed) {
        int action = outputAction(packed);
        return action <= CPU_FALLBACK;
    }

    static int packGpuOutput(int action, int stateSlot, boolean scheduleFluidUpdate) {
        if (action < RETURN_DEFAULT || action > CPU_FALLBACK)
            throw new IllegalArgumentException("invalid aquifer action " + action);
        if (stateSlot < 0 || stateSlot >= MAX_OUTPUT_STATES)
            throw new IllegalArgumentException("aquifer palette slot does not fit one byte");
        return (stateSlot << OUTPUT_STATE_SHIFT) | action
                | (scheduleFluidUpdate ? OUTPUT_SCHEDULE_FLUID_UPDATE : 0);
    }

    static int shaderOutputWordCount(int rows) { return (rows + 1) >>> 1; }

    /** Returns true when GPU rounding can change the density sign decision. */
    public static boolean nearDensityDecisionThreshold(double density) {
        return Double.isFinite(density) && Math.abs(density) <= PRECISION_EPSILON;
    }

    public static ByteBuffer pack(Input[] inputs) {
        return packBatch(inputs).records();
    }

    public static PackedBatch packBatch(Input[] inputs) {
        if (inputs == null || inputs.length == 0 || inputs.length > MAX_QUERIES)
            throw new IllegalArgumentException("invalid aquifer substance batch");
        ByteBuffer packed = allocateBatch(inputs.length);
        StatePalette palette = new StatePalette();
        for (Input input : inputs) putInput(packed, input, palette);
        return new PackedBatch(packed.flip(), palette.snapshot());
    }

    /** Packs a range directly from the caller's list without a temporary array copy. */
    static ByteBuffer pack(List<Input> inputs, int offset, int count) {
        if (inputs == null || count <= 0 || count > MAX_QUERIES
                || offset < 0 || offset > inputs.size() - count)
            throw new IllegalArgumentException("invalid aquifer substance batch range");
        ByteBuffer packed = allocateBatch(count);
        StatePalette palette = new StatePalette();
        for (int index = offset; index < offset + count; index++) putInput(packed, inputs.get(index), palette);
        return packed.flip();
    }

    private static ByteBuffer allocateBatch(int count) {
        return ByteBuffer.allocateDirect(Math.multiplyExact(count, RECORD_BYTES))
                .order(ByteOrder.nativeOrder());
    }

    private static void putInput(ByteBuffer packed, Input input, StatePalette palette) {
        int flags = (input.directGlobalFluid ? 1 : 0) | (input.belowIsLava ? 2 : 0)
                | (input.c2meAquifer ? 4 : 0);
        int slot = palette.slot(input.directGlobalFluid ? input.globalStateId : input.nearestStateId);
        if (slot < 0) {
            flags |= FLAG_STATE_MAPPING_OVERFLOW;
            slot = 0;
        }
        packed.putFloat((float) input.density).putFloat((float) input.barrier).putInt(input.y)
                .putInt(input.level0).putInt(input.level1).putInt(input.level2).putInt(input.level3)
                .putInt(input.distance0).putInt(input.distance1).putInt(input.distance2).putInt(input.distance3)
                .putInt(input.kind0).putInt(input.kind1).putInt(input.kind2).putInt(input.kind3)
                .putInt(input.statusEqualityMask).putInt(flags).putInt(slot);
    }

    public record PackedBatch(ByteBuffer records, int[] stateIds) {
        public PackedBatch { stateIds = stateIds.clone(); }
        @Override public int[] stateIds() { return stateIds.clone(); }
        public int stateIdForSlot(int slot) {
            return slot < 0 || slot >= stateIds.length ? -1 : stateIds[slot];
        }
        public int slotForStateId(int stateId) {
            for (int slot = 0; slot < stateIds.length; slot++)
                if (stateIds[slot] == stateId) return slot;
            return -1;
        }
    }

    private static final class StatePalette {
        private final int[] keys = new int[MAX_OUTPUT_STATES * 2];
        private final int[] keySlots = new int[MAX_OUTPUT_STATES * 2];
        private final int[] stateIds = new int[MAX_OUTPUT_STATES];
        private int size;

        StatePalette() { Arrays.fill(keys, -1); }

        int slot(int stateId) {
            if (stateId < 0) return -1;
            int mask = keys.length - 1;
            int bucket = (stateId * 0x9e3779b9) >>> 23;
            while (keys[bucket] != -1) {
                if (keys[bucket] == stateId) return keySlots[bucket];
                bucket = (bucket + 1) & mask;
            }
            if (size == stateIds.length) return -1;
            int slot = size++;
            keys[bucket] = stateId;
            keySlots[bucket] = slot;
            stateIds[slot] = stateId;
            return slot;
        }

        int stateId(int slot) { return slot < 0 || slot >= size ? -1 : stateIds[slot]; }
        int[] snapshot() { return Arrays.copyOf(stateIds, size); }
    }

    /** Incremental direct-buffer staging for chunk-sized aquifer decisions. */
    public static final class BatchWriter {
        private final int expectedRows;
        private final int rowsPerBuffer;
        private final List<ByteBuffer> completed = new ArrayList<>();
        private final StatePalette palette = new StatePalette();
        private ByteBuffer current;
        private int size;
        private boolean finished;

        public BatchWriter(int expectedRows) {
            this(expectedRows, MAX_QUERIES);
        }

        public BatchWriter(int expectedRows, int rowsPerBuffer) {
            if (expectedRows < 0) throw new IllegalArgumentException("negative aquifer batch capacity");
            if (rowsPerBuffer <= 0 || rowsPerBuffer > MAX_QUERIES)
                throw new IllegalArgumentException("invalid aquifer rows per buffer");
            this.expectedRows = expectedRows;
            this.rowsPerBuffer = rowsPerBuffer;
        }

        public int size() { return size; }
        public int outputPaletteSize() { return palette.size; }
        public int stateIdForOutputSlot(int slot) { return palette.stateId(slot); }
        public void add(Input input) {
            if (input == null) throw new NullPointerException("input");
            write(input.density, input.barrier, input.y, input.level0, input.level1,
                    input.level2, input.level3, input.distance0, input.distance1,
                    input.distance2, input.distance3, input.kind0, input.kind1,
                    input.kind2, input.kind3, input.statusEqualityMask,
                    input.directGlobalFluid, input.belowIsLava, input.c2meAquifer,
                    input.directGlobalFluid ? input.globalStateId : input.nearestStateId);
        }

        public void addPositiveDensity(double density, int y, boolean c2meAquifer) {
            write(density, 0.0, y, 0, 0, 0, 0, 0, 0, 0, 0,
                    OTHER, OTHER, OTHER, OTHER, 0, false, false, c2meAquifer, 0);
        }

        public void addDirectGlobalFluid(double density, int y, boolean c2meAquifer, int stateId) {
            write(density, 0.0, y, 0, 0, 0, 0, 0, 0, 0, 0,
                    OTHER, OTHER, OTHER, OTHER, 0, true, false, c2meAquifer, stateId);
        }

        public void addAquifer(double density, double barrier, int y,
                        int level0, int level1, int level2, int level3,
                        int distance0, int distance1, int distance2, int distance3,
                        int kind0, int kind1, int kind2, int kind3,
                        int statusEqualityMask, boolean belowIsLava, boolean c2meAquifer,
                        int selectedStateId) {
            write(density, barrier, y, level0, level1, level2, level3,
                    distance0, distance1, distance2, distance3, kind0, kind1,
                    kind2, kind3, statusEqualityMask, false, belowIsLava, c2meAquifer,
                    selectedStateId);
        }

        public List<ByteBuffer> finish() {
            if (finished) throw new IllegalStateException("aquifer batch writer is already finished");
            List<ByteBuffer> batches = drain();
            finished = true;
            return batches;
        }

        /** Publishes all staged rows so far while keeping the writer appendable. */
        public List<ByteBuffer> drain() {
            if (finished) throw new IllegalStateException("aquifer batch writer is already finished");
            if (current != null && current.position() > 0) {
                current.flip();
                completed.add(current);
                current = null;
            }
            List<ByteBuffer> batches = List.copyOf(completed);
            completed.clear();
            return batches;
        }

        private void write(double density, double barrier, int y,
                           int level0, int level1, int level2, int level3,
                           int distance0, int distance1, int distance2, int distance3,
                           int kind0, int kind1, int kind2, int kind3,
                           int statusEqualityMask, boolean directGlobalFluid,
                           boolean belowIsLava, boolean c2meAquifer, int selectedStateId) {
            if (finished) throw new IllegalStateException("aquifer batch writer is already finished");
            if (size >= expectedRows) throw new IllegalStateException("aquifer batch exceeded its chunk capacity");
            if (current == null || !current.hasRemaining()) {
                if (current != null) {
                    current.flip();
                    completed.add(current);
                }
                // Keep slab capacities stable so drained buffers can be reused
                // across chunks even when the final slab is only partly full.
                current = GpuTerrainInputBufferPool.acquire(
                        Math.multiplyExact(rowsPerBuffer, RECORD_BYTES));
            }
            int flags = (directGlobalFluid ? 1 : 0) | (belowIsLava ? 2 : 0)
                    | (c2meAquifer ? 4 : 0);
            int slot = palette.slot(selectedStateId);
            if (slot < 0) {
                flags |= FLAG_STATE_MAPPING_OVERFLOW;
                slot = 0;
            }
            current.putFloat((float) density).putFloat((float) barrier).putInt(y)
                    .putInt(level0).putInt(level1).putInt(level2).putInt(level3)
                    .putInt(distance0).putInt(distance1).putInt(distance2).putInt(distance3)
                    .putInt(kind0).putInt(kind1).putInt(kind2).putInt(kind3)
                    .putInt(statusEqualityMask).putInt(flags).putInt(slot);
            size++;
        }
    }

    /** Reference words are action, state ID, and schedule-fluid-update. */
    public static int[] evaluate(Input input) {
        int packed = evaluatePacked(input);
        return new int[]{outputAction(packed), outputStateId(packed),
                outputSchedulesFluidUpdate(packed) ? 1 : 0};
    }

    /** Allocation-free Java reference for the runtime differential verifier. */
    public static int evaluatePacked(Input input) {
        if (!Double.isFinite(input.density)) return packOutput(CPU_FALLBACK, 0, false);
        if (nearDensityDecisionThreshold(input.density)) return packOutput(CPU_FALLBACK, 0, false);
        if (input.density > 0.0) return packOutput(RETURN_DEFAULT, 0, false);
        if (input.directGlobalFluid) return packOutput(RETURN_STATE, input.globalStateId, false);
        if (!Double.isFinite(input.barrier))
            return packOutput(CPU_FALLBACK, 0, false);

        double similarity01 = similarity(input.distance0, input.distance1);
        double similarity02 = similarity(input.distance0, input.distance2);
        double similarity12 = similarity(input.distance1, input.distance2);
        if (similarity01 <= 0.0) {
            return packOutput(RETURN_STATE, input.nearestStateId,
                    similarity01 >= FLOWING_UPDATE_SIMILARITY
                            && (!input.c2meAquifer || !sameStatus(input, 0, 1)));
        }
        if (input.kind0 == WATER && input.belowIsLava)
            return packOutput(RETURN_STATE, input.nearestStateId, true);

        double contribution01 = similarity01 * pressure(input.y, input.level0, input.level1,
                input.kind0, input.kind1, input.barrier);
        double firstResult = input.density + contribution01;
        if (nearThreshold(firstResult)) return packOutput(CPU_FALLBACK, 0, false);
        if (firstResult > 0.0) return packOutput(RETURN_DEFAULT, 0, false);

        if (similarity02 > 0.0) {
            double contribution02 = similarity01 * similarity02
                    * pressure(input.y, input.level0, input.level2,
                            input.kind0, input.kind2, input.barrier);
            double secondResult = input.density + contribution02;
            if (nearThreshold(secondResult)) return packOutput(CPU_FALLBACK, 0, false);
            if (secondResult > 0.0) return packOutput(RETURN_DEFAULT, 0, false);
        }

        if (similarity12 > 0.0) {
            double contribution12 = similarity01 * similarity12
                    * pressure(input.y, input.level1, input.level2,
                            input.kind1, input.kind2, input.barrier);
            double thirdResult = input.density + contribution12;
            if (nearThreshold(thirdResult)) return packOutput(CPU_FALLBACK, 0, false);
            if (thirdResult > 0.0) return packOutput(RETURN_DEFAULT, 0, false);
        }
        if (!input.c2meAquifer) return packOutput(RETURN_STATE, input.nearestStateId, true);

        boolean mayFlow01 = !sameStatus(input, 0, 1);
        boolean mayFlow12 = similarity12 >= FLOWING_UPDATE_SIMILARITY && !sameStatus(input, 1, 2);
        boolean mayFlow02 = similarity02 >= FLOWING_UPDATE_SIMILARITY && !sameStatus(input, 0, 2);
        boolean schedule = mayFlow01 || mayFlow12 || mayFlow02
                || (similarity02 >= FLOWING_UPDATE_SIMILARITY
                && similarity(input.distance0, input.distance3) >= FLOWING_UPDATE_SIMILARITY
                && !sameStatus(input, 0, 3));
        return packOutput(RETURN_STATE, input.nearestStateId, schedule);
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
                 int statusEqualityMask, int globalStateId, boolean directGlobalFluid,
                 boolean belowIsLava, boolean c2meAquifer, int nearestStateId) {
        public Input(double density, double barrier, int y,
                     int level0, int level1, int level2,
                     int distance0, int distance1, int distance2,
                     int kind0, int kind1, int kind2,
                     int globalStateId, boolean directGlobalFluid, boolean belowIsLava,
                     int nearestStateId) {
            this(density, barrier, y, level0, level1, level2, 0,
                    distance0, distance1, distance2, 0,
                    kind0, kind1, kind2, OTHER, 0, globalStateId,
                    directGlobalFluid, belowIsLava, false, nearestStateId);
        }
    }
}
