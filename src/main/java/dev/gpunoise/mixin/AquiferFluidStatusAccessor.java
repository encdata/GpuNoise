package dev.gpunoise.mixin;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Aquifer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Read-only view of vanilla aquifer status data used to prepare GPU batches. */
@Mixin(Aquifer.FluidStatus.class)
interface AquiferFluidStatusAccessor {
    @Accessor("fluidLevel")
    int gpunoise$fluidLevel();

    @Accessor("fluidType")
    BlockState gpunoise$fluidType();
}
