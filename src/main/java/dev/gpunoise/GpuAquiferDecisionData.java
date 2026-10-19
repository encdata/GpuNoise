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

    public static final class PendingDecision {
        public final int index;
        public final BlockState nearestState;
        public final DensityFunction.FunctionContext context;
        public final double density;
        public PendingDecision(int index, BlockState nearestState,
                               DensityFunction.FunctionContext context, double density) {
            this.index = index; this.nearestState = nearestState;
            this.context = context; this.density = density;
        }
    }

    public static final class Decision {
        public final BlockState state;
        public final boolean scheduleFluidUpdate;
        public Decision(BlockState state, boolean scheduleFluidUpdate) {
            this.state = state; this.scheduleFluidUpdate = scheduleFluidUpdate;
        }
    }
}
