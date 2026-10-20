package dev.gpunoise.mixin;

import net.minecraft.world.level.biome.Climate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "net.minecraft.world.level.biome.Climate$RTree$Node")
public interface ClimateRTreeNodeAccess {
    @Accessor("parameterSpace")
    Climate.Parameter[] gpunoise$parameterSpace();
}
