package dev.gpunoise;

import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.block.state.BlockState;

/** Data holders shared by the aquifer Mixin without loading nested Mixin-package classes. */
public final class GpuAquiferDecisionData {
    private GpuAquiferDecisionData() {}

    public static final class GridNeighborhood {
        public final int x, y, z;
        public GridNeighborhood(int x, int y, int z) { this.x = x; this.y = y; this.z = z; }
        @Override public int hashCode() { return (x * 31 + y) * 31 + z; }
        @Override public boolean equals(Object other) {
            return other instanceof GridNeighborhood key && x == key.x && y == key.y && z == key.z;
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

    public static final class Decision {
        public final BlockState state;
        public final boolean scheduleFluidUpdate;
        public final double density;
        public Decision(BlockState state, boolean scheduleFluidUpdate, double density) {
            this.state = state; this.scheduleFluidUpdate = scheduleFluidUpdate;
            this.density = density;
        }
    }
}
