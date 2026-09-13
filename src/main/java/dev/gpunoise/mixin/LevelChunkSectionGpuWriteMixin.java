package dev.gpunoise.mixin;

import dev.gpunoise.GpuLevelChunkSectionAccess;
import dev.gpunoise.GpuPalettedContainerAccess;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(LevelChunkSection.class)
abstract class LevelChunkSectionGpuWriteMixin implements GpuLevelChunkSectionAccess {
    @Shadow private short nonEmptyBlockCount;
    @Shadow private short tickingBlockCount;
    @Shadow private short tickingFluidCount;
    @Shadow @Final private PalettedContainer<BlockState> states;

    @Override
    @SuppressWarnings("unchecked")
    public void gpunoise$writeGpuBlock(int index, BlockState state) {
        this.nonEmptyBlockCount++;
        if (state.isRandomlyTicking()) this.tickingBlockCount++;
        if (!state.getFluidState().isEmpty()) this.tickingFluidCount++;
        ((GpuPalettedContainerAccess<BlockState>) (Object) this.states)
                .gpunoise$setUnchecked(index, state);
    }
}
