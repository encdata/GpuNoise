package dev.gpunoise.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Climate.ParameterList.class)
public interface ClimateParameterListAccess {
    @Accessor("index")
    Object gpunoise$index();
}
