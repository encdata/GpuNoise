package dev.gpunoise;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import net.minecraft.world.level.levelgen.DensityFunction;

/** Registration point for other mods that expose custom density functions. */
public final class GpuDensityFunctionTranslators {
    private static final ConcurrentMap<Class<?>, GpuDensityFunctionTranslator<?>> TRANSLATORS =
            new ConcurrentHashMap<>();

    private GpuDensityFunctionTranslators() {}

    /**
     * Registers an exact-class translator. Call during mod initialization,
     * before the server's terrain graph is compiled. Duplicate registrations
     * fail so load order cannot silently change terrain output.
     */
    public static <T extends DensityFunction> void register(
            Class<T> functionType, GpuDensityFunctionTranslator<? super T> translator) {
        if (functionType == null || translator == null) throw new NullPointerException();
        GpuDensityFunctionTranslator<?> previous = TRANSLATORS.putIfAbsent(functionType, translator);
        if (previous != null) {
            throw new IllegalStateException("A GPU density translator is already registered for "
                    + functionType.getName());
        }
    }

    /** Returns whether a translator is registered for this exact function class. */
    public static boolean hasTranslator(Class<? extends DensityFunction> functionType) {
        return TRANSLATORS.containsKey(functionType);
    }

    static DensityFunction translate(DensityFunction function) {
        GpuDensityFunctionTranslator<?> raw = TRANSLATORS.get(function.getClass());
        if (raw == null) return null;
        return translateTyped(raw, function);
    }

    @SuppressWarnings("unchecked")
    private static <T extends DensityFunction> DensityFunction translateTyped(
            GpuDensityFunctionTranslator<?> raw, DensityFunction function) {
        GpuDensityFunctionTranslator<T> translator = (GpuDensityFunctionTranslator<T>) raw;
        DensityFunction translated = translator.translate((T) function);
        if (translated == null) {
            throw new IllegalStateException("GPU density translator returned null for "
                    + function.getClass().getName());
        }
        if (translated == function) {
            throw new IllegalStateException("GPU density translator returned its input for "
                    + function.getClass().getName());
        }
        return translated;
    }
}
