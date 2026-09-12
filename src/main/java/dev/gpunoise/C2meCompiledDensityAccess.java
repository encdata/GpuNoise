package dev.gpunoise;

import net.minecraft.world.level.levelgen.DensityFunction;

/** Optional bridge to the vanilla graph retained by C2ME's compiled wrapper. */
public interface C2meCompiledDensityAccess {
    DensityFunction gpunoise$vanillaFallback();
}
