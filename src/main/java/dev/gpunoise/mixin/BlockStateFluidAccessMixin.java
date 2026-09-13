package dev.gpunoise.mixin;

import dev.gpunoise.GpuBlockStateFluidAccess;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "net.minecraft.world.level.block.state.BlockBehaviour$BlockStateBase")
interface BlockStateFluidAccessMixin extends GpuBlockStateFluidAccess {
    @Override
    @Accessor("fluidState")
    FluidState gpunoise$fluidState();
}
