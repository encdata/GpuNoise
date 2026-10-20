package dev.gpunoise.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "net.minecraft.world.level.biome.Climate$RTree$SubTree")
public interface ClimateRTreeSubTreeAccess {
    @Accessor("children")
    Object gpunoise$children();
}
