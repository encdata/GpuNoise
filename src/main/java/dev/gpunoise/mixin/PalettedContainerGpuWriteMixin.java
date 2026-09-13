package dev.gpunoise.mixin;

import dev.gpunoise.GpuPalettedContainerAccess;
import net.minecraft.world.level.chunk.PalettedContainer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Exposes PalettedContainer's private write-only method to the exclusive fill. */
@Mixin(PalettedContainer.class)
abstract class PalettedContainerGpuWriteMixin<T> implements GpuPalettedContainerAccess<T> {
    @Override
    @Invoker("set")
    public abstract void gpunoise$setUnchecked(int index, T value);

    @Override
    public void gpunoise$setGpuCached(int index, T value) {
        // Keep the vanilla palette-resize path until the Data accessor is
        // proven stable across the NeoForge runtime transformer.
        this.gpunoise$setUnchecked(index, value);
    }
}
