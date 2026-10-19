package dev.gpunoise;

import net.minecraft.world.level.block.state.BlockState;

/** Primitive-backed chunk table published only after every GPU decision row is ready. */
public final class GpuAquiferDecisionTable {
    private static final byte VALID = 1;
    private static final byte SCHEDULE_FLUID_UPDATE = 2;

    private final int minX, minY, minZ, height;
    private final BlockState[] states;
    private final long[] densityBits;
    private final byte[] flags;

    public GpuAquiferDecisionTable(int minX, int minY, int minZ, int height) {
        if (height <= 0) throw new IllegalArgumentException("aquifer decision table height must be positive");
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.height = height;
        int size = Math.multiplyExact(height, 256);
        this.states = new BlockState[size];
        this.densityBits = new long[size];
        this.flags = new byte[size];
    }

    public int indexOfMatch(int x, int y, int z, double density) {
        int localX = x - minX;
        int localY = y - minY;
        int localZ = z - minZ;
        if (localX < 0 || localX >= 16 || localY < 0 || localY >= height
                || localZ < 0 || localZ >= 16) return -1;
        int index = localY * 256 + localZ * 16 + localX;
        return (flags[index] & VALID) != 0
                && densityBits[index] == Double.doubleToLongBits(density) ? index : -1;
    }

    public void set(int index, BlockState state, double density, boolean scheduleFluidUpdate) {
        if (index < 0 || index >= flags.length) throw new IndexOutOfBoundsException(index);
        states[index] = state;
        densityBits[index] = Double.doubleToLongBits(density);
        flags[index] = (byte) (VALID | (scheduleFluidUpdate ? SCHEDULE_FLUID_UPDATE : 0));
    }

    public BlockState state(int index) { return states[index]; }
    public boolean schedulesFluidUpdate(int index) { return (flags[index] & SCHEDULE_FLUID_UPDATE) != 0; }
    int height() { return height; }
    int size() { return flags.length; }
}
