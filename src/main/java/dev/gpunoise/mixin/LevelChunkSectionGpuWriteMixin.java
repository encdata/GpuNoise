package dev.gpunoise.mixin;

import dev.gpunoise.GpuLevelChunkSectionAccess;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(LevelChunkSection.class)
abstract class LevelChunkSectionGpuWriteMixin implements GpuLevelChunkSectionAccess {
    @Shadow private short nonEmptyBlockCount;
    @Shadow private short tickingBlockCount;
    @Shadow private short tickingFluidCount;

    @Override
    public void gpunoise$recordGpuBlockWrite(BlockState state) {
        this.nonEmptyBlockCount++;
        if (state.isRandomlyTicking()) this.tickingBlockCount++;
        if (!state.getFluidState().isEmpty()) this.tickingFluidCount++;
    }
}
