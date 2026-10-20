package dev.gpunoise.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "net.minecraft.world.level.biome.Climate$RTree$Leaf")
public interface ClimateRTreeLeafAccess {
    @Accessor("value")
    Object gpunoise$value();
}
