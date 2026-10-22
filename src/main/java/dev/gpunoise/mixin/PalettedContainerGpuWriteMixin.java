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
    public void gpunoise$setGpuOwned(int index, T value, int paletteId) {
        Object current = gpunoise$data();
        if (!(current instanceof GpuPalettedContainerDataAccess<?>)) {
            this.gpunoise$setUnchecked(index, value);
            return;
        }
        ((GpuPalettedContainerDataAccess<T>) current).gpunoise$storage().set(index, paletteId);
    }

    @Override
    @SuppressWarnings("unchecked")
    public int gpunoise$resolveGpuPaletteId(T value) {
        Object current = gpunoise$data();
        if (!(current instanceof GpuPalettedContainerDataAccess<?>)) return -1;
        int paletteId = ((GpuPalettedContainerDataAccess<T>) current).gpunoise$palette().idFor(value);
        return current == gpunoise$data() ? paletteId : -2;
    }
}
