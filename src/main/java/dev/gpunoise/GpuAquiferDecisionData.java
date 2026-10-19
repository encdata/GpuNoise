package dev.gpunoise;

import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.block.state.BlockState;

/** Data holders shared by the aquifer Mixin without loading nested Mixin-package classes. */
public final class GpuAquiferDecisionData {
    private GpuAquiferDecisionData() {}

    /** Open-addressed cache for aquifer center groups, avoiding a key object per block. */
    public static final class NeighborhoodCache {
        private int[] xs;
        private int[] ys;
        private int[] zs;
        private int[][][] values;
        private int size;

        public NeighborhoodCache(int expectedEntries) {
            if (expectedEntries < 0) throw new IllegalArgumentException("negative neighborhood capacity");
            int capacity = 16;
            long required = Math.max(16L, (long) expectedEntries * 2L);
            while (capacity < required && capacity < (1 << 29)) capacity <<= 1;
            allocate(capacity);
        }

        public int[][] get(int x, int y, int z) {
            int mask = values.length - 1;
            int slot = hash(x, y, z) & mask;
            while (values[slot] != null) {
                if (xs[slot] == x && ys[slot] == y && zs[slot] == z) return values[slot];
                slot = (slot + 1) & mask;
            }
            return null;
        }

        public void put(int x, int y, int z, int[][] centers) {
            if (centers == null) throw new NullPointerException("centers");
            if ((long) (size + 1) * 10L >= (long) values.length * 7L) grow();
            insert(x, y, z, centers);
        }

        private void insert(int x, int y, int z, int[][] centers) {
            int mask = values.length - 1;
            int slot = hash(x, y, z) & mask;
            while (values[slot] != null) {
                if (xs[slot] == x && ys[slot] == y && zs[slot] == z) {
                    values[slot] = centers;
                    return;
                }
                slot = (slot + 1) & mask;
            }
            xs[slot] = x;
            ys[slot] = y;
            zs[slot] = z;
            values[slot] = centers;
            size++;
        }

        private void grow() {
            if (values.length >= (1 << 29)) throw new IllegalStateException("aquifer neighborhood cache is full");
            int[] oldX = xs, oldY = ys, oldZ = zs;
            int[][][] oldValues = values;
            allocate(values.length << 1);
            size = 0;
            for (int i = 0; i < oldValues.length; i++) {
                if (oldValues[i] != null) insert(oldX[i], oldY[i], oldZ[i], oldValues[i]);
            }
        }

        private void allocate(int capacity) {
            xs = new int[capacity];
            ys = new int[capacity];
            zs = new int[capacity];
            values = new int[capacity][][];
        }

        private static int hash(int x, int y, int z) {
            int h = x * 0x9E3779B9;
            h = Integer.rotateLeft(h ^ y * 0x85EBCA6B, 13);
            h = Integer.rotateLeft(h ^ z * 0xC2B2AE35, 15);
            h ^= h >>> 16;
            h *= 0x7FEB352D;
            h ^= h >>> 15;
            return h;
        }
    }

    public static final class AquiferQuery {
        public final int index, x, y, z;
        public final double density, barrier;
        public final boolean belowIsLava;
        public final int[][] centers;
        public AquiferQuery(int index, int x, int y, int z, double density,
                            double barrier, boolean belowIsLava, int[][] centers) {
            this.index = index; this.x = x; this.y = y; this.z = z;
            this.density = density; this.barrier = barrier;
            this.belowIsLava = belowIsLava; this.centers = centers;
        }
    }

    /** Columnar staging for a chunk's aquifer work; avoids one query object per block. */
    public static final class AquiferQueries {
        private final int[] indices, xs, ys, zs;
        private final double[] densities, barriers;
        private final byte[] belowLava;
        private final int[][][] centers;
        private int size;

        public AquiferQueries(int capacity) {
            if (capacity < 0) throw new IllegalArgumentException("negative aquifer query capacity");
            indices = new int[capacity]; xs = new int[capacity];
            ys = new int[capacity]; zs = new int[capacity];
            densities = new double[capacity]; barriers = new double[capacity];
            belowLava = new byte[capacity];
            centers = new int[capacity][][];
        }

        public void add(int index, int x, int y, int z, double density,
                        double barrier, boolean isBelowLava, int[][] neighborhood) {
            if (size == indices.length) throw new IllegalStateException("aquifer queries exceeded chunk capacity");
            indices[size] = index; xs[size] = x; ys[size] = y; zs[size] = z;
            densities[size] = density; barriers[size] = barrier;
            belowLava[size] = (byte) (isBelowLava ? 1 : 0);
            centers[size] = neighborhood;
            size++;
        }

        public int size() { return size; }
        public int index(int row) { return indices[row]; }
        public int x(int row) { return xs[row]; }
        public int y(int row) { return ys[row]; }
        public int z(int row) { return zs[row]; }
        public double density(int row) { return densities[row]; }
        public double barrier(int row) { return barriers[row]; }
        public boolean belowIsLava(int row) { return belowLava[row] != 0; }
        public int[][] centers(int row) { return centers[row]; }
    }

    /** Ordered primitive/reference columns for GPU rows; avoids one holder per block. */
    public static final class PendingDecisions {
        private final int[] indices;
        private final BlockState[] selectedStates;
        private final double[] densities;
        private final DensityFunction.FunctionContext[] contexts;
        private final GpuAquiferSubstance.Input[] gpuInputs;
        private int size;

        public PendingDecisions(int capacity, boolean diagnostics) {
            indices = new int[capacity];
            selectedStates = new BlockState[capacity];
            densities = new double[capacity];
            contexts = diagnostics ? new DensityFunction.FunctionContext[capacity] : null;
            gpuInputs = diagnostics ? new GpuAquiferSubstance.Input[capacity] : null;
        }

        public void add(int index, BlockState selectedState,
                        DensityFunction.FunctionContext context, double density,
                        GpuAquiferSubstance.Input gpuInput) {
            if (size == indices.length) throw new IllegalStateException("aquifer pending rows exceeded chunk capacity");
            indices[size] = index;
            selectedStates[size] = selectedState;
            densities[size] = density;
            if (contexts != null) {
                contexts[size] = context;
                gpuInputs[size] = gpuInput;
            }
            size++;
        }

        public int size() { return size; }
        public int index(int row) { return indices[row]; }
        public BlockState selectedState(int row) { return selectedStates[row]; }
        public double density(int row) { return densities[row]; }
        public DensityFunction.FunctionContext context(int row) {
            return contexts == null ? null : contexts[row];
        }
        public GpuAquiferSubstance.Input gpuInput(int row) {
            return gpuInputs == null ? null : gpuInputs[row];
        }
    }

}
