package dev.gpunoise;

import net.minecraft.world.level.block.state.BlockState;

/** Fast path for the top-to-bottom heightmap writes performed by doFill. */
public interface GpuHeightmapAccess {
    boolean gpunoise$tryDescendingWrite(int x, int y, int z, BlockState state);
}
