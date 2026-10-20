package dev.gpunoise.mixin;

import net.minecraft.world.level.biome.Climate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Climate.RTree.class)
public interface ClimateRTreeAccess {
    @Accessor("root")
    Object gpunoise$root();
}
