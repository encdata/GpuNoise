package dev.gpunoise;

import java.util.Objects;
import java.util.function.Function;
import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * Describes an equivalent GPU implementation for a custom mod density function.
 *
 * <p>{@link #translateToGpu(DensityFunction)} may describe custom math directly
 * with {@link GpuDensityExpression}; otherwise {@link #translate(DensityFunction)}
 * can return a graph composed from supported Minecraft density operations.
 * Either result must match the custom function for every position. The original
 * function is retained for CPU parity checks and fallback.</p>
 */
@FunctionalInterface
public interface GpuDensityFunctionTranslator<T extends DensityFunction> {
    DensityFunction translate(T function);

    /** Creates a direct-expression-only adapter with no vanilla graph fallback. */
    static <T extends DensityFunction> GpuDensityFunctionTranslator<T> expressionOnly(
            Function<? super T, GpuDensityExpression> expressionTranslator) {
        Objects.requireNonNull(expressionTranslator, "expressionTranslator");
        return new GpuDensityFunctionTranslator<>() {
            @Override
            public DensityFunction translate(T function) {
                return null;
            }

            @Override
            public GpuDensityExpression translateToGpu(T function) {
                return expressionTranslator.apply(function);
            }
        };
    }

    /**
     * Optionally lowers a custom function directly into the GPU expression IR.
     * Return {@code null} to use {@link #translate(DensityFunction)}. Direct
     * expressions can represent math that has no equivalent vanilla
     * DensityFunction node. The original function remains the CPU reference
     * evaluator for graph validation and fallback.
     */
    default GpuDensityExpression translateToGpu(T function) {
        return null;
    }
}
