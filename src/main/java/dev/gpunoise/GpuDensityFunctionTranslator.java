package dev.gpunoise;

import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * Describes an equivalent density graph for a custom mod density function.
 *
 * <p>The returned function must be composed from density operations supported
 * by GPU Noise. Its values must match the custom function for every position.
 * The original function is retained for CPU parity checks and fallback.</p>
 */
@FunctionalInterface
public interface GpuDensityFunctionTranslator<T extends DensityFunction> {
    DensityFunction translate(T function);
}
