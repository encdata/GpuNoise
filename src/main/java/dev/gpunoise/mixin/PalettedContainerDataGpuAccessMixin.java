package dev.gpunoise.mixin;

import dev.gpunoise.GpuPalettedContainerDataAccess;
import net.minecraft.util.BitStorage;
import net.minecraft.world.level.chunk.Palette;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "net.minecraft.world.level.chunk.PalettedContainer$Data")
interface PalettedContainerDataGpuAccessMixin<T> extends GpuPalettedContainerDataAccess<T> {
    @Override
    @Accessor("storage")
    BitStorage gpunoise$storage();

    @Override
    @Accessor("palette")
    Palette<T> gpunoise$palette();
}
