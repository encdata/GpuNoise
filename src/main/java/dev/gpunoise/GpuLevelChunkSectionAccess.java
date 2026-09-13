package dev.gpunoise;

import net.minecraft.world.level.block.state.BlockState;

/** Counter updates for the exclusive direct-palette terrain writer. */
public interface GpuLevelChunkSectionAccess {
    boolean gpunoise$writeGpuBlock(int index, BlockState state);
}
