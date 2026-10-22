package dev.gpunoise.mixin;

import dev.gpunoise.GpuLevelChunkSectionAccess;
import dev.gpunoise.GpuPalettedContainerAccess;
import dev.gpunoise.GpuBlockStateFluidAccess;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
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
    public boolean gpunoise$writeGpuBlock(int index, BlockState state) {
        return gpunoise$writeGpuBlock(index, state, false);
    }

    @Override
    @SuppressWarnings("unchecked")
    public boolean gpunoise$writeGpuBlockOwned(int index, BlockState state, int paletteId) {
        return gpunoise$writeGpuBlock(index, state, true, paletteId);
    }

    @SuppressWarnings("unchecked")
    private boolean gpunoise$writeGpuBlock(int index, BlockState state, boolean owned) {
        return gpunoise$writeGpuBlock(index, state, owned, -1);
    }

    private boolean gpunoise$writeGpuBlock(int index, BlockState state, boolean owned, int paletteId) {
        this.nonEmptyBlockCount++;
        if (state.isRandomlyTicking()) this.tickingBlockCount++;
        FluidState fluidState = ((GpuBlockStateFluidAccess) (Object) state).gpunoise$fluidState();
        boolean hasFluid = fluidState.getType() != Fluids.EMPTY;
        if (hasFluid) this.tickingFluidCount++;
        GpuPalettedContainerAccess<BlockState> palette =
                (GpuPalettedContainerAccess<BlockState>) (Object) this.states;
        if (owned) palette.gpunoise$setGpuOwned(index, state, paletteId);
        else palette.gpunoise$setGpuCached(index, state);
        return hasFluid;
    }

    @Override
    @SuppressWarnings("unchecked")
    public int gpunoise$resolveGpuPaletteId(BlockState state) {
        return ((GpuPalettedContainerAccess<BlockState>) (Object) this.states)
                .gpunoise$resolveGpuPaletteId(state);
    }
}
