package dev.gpunoise.mixin;

import dev.gpunoise.GpuPalettedContainerAccess;
import dev.gpunoise.GpuPalettedContainerDataAccess;
import net.minecraft.world.level.chunk.PalettedContainer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Exposes PalettedContainer's private write-only method to the exclusive fill. */
@Mixin(PalettedContainer.class)
abstract class PalettedContainerGpuWriteMixin<T> implements GpuPalettedContainerAccess<T> {
    @Accessor("data")
    protected abstract Object gpunoise$data();

    @Override
    @Invoker("set")
    public abstract void gpunoise$setUnchecked(int index, T value);

    @Override
    public void gpunoise$setGpuCached(int index, T value) {
        this.gpunoise$setUnchecked(index, value);
    }

    @Override
    @SuppressWarnings("unchecked")
    public void gpunoise$setGpuOwned(int index, T value) {
        Object current = gpunoise$data();
        if (!(current instanceof GpuPalettedContainerDataAccess<?>)) {
            this.gpunoise$setUnchecked(index, value);
            return;
        }
        GpuPalettedContainerDataAccess<T> data = (GpuPalettedContainerDataAccess<T>) current;
        int paletteId = data.gpunoise$palette().idFor(value);
        // idFor may resize the palette and replace PalettedContainer.data.
        // Always fetch the current storage after that callback has completed.
        Object resized = gpunoise$data();
        if (!(resized instanceof GpuPalettedContainerDataAccess<?>)) {
            this.gpunoise$setUnchecked(index, value);
            return;
        }
        ((GpuPalettedContainerDataAccess<T>) resized).gpunoise$storage().set(index, paletteId);
    }
}
