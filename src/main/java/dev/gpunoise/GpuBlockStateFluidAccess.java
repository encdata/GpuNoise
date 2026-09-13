package dev.gpunoise;

import net.minecraft.world.level.material.FluidState;

/** Direct access to the FluidState already cached by Minecraft BlockState. */
public interface GpuBlockStateFluidAccess {
    FluidState gpunoise$fluidState();
}
