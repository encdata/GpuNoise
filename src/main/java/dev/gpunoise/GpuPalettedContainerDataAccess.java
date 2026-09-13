package dev.gpunoise;

import net.minecraft.util.BitStorage;
import net.minecraft.world.level.chunk.Palette;

/** Access to the immutable data record used by PalettedContainer's hot write path. */
public interface GpuPalettedContainerDataAccess<T> {
    BitStorage gpunoise$storage();

    Palette<T> gpunoise$palette();
}
