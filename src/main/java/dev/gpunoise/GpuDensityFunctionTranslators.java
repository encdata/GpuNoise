package dev.gpunoise;

import net.minecraft.world.level.levelgen.DensityFunction;

/** Registration point for other mods that expose custom density functions. */
public final class GpuDensityFunctionTranslators {
    private static final GpuDensityTranslatorRegistry<GpuDensityFunctionTranslator<?>> TRANSLATORS =
            new GpuDensityTranslatorRegistry<>();

    private GpuDensityFunctionTranslators() {}

    /**
     * Registers an exact-class translator. Call during mod initialization,
     * before the server's terrain graph is compiled. Duplicate registrations
     * fail so load order cannot silently change terrain output.
     */
    public static <T extends DensityFunction> void register(
            Class<T> functionType, GpuDensityFunctionTranslator<? super T> translator) {
        add(functionType, translator, false);
    }

    /**
     * Registers a translator for a mod API type and its implementations.
     * Exact-class registrations always take precedence. When multiple
     * registered supertypes match, the closest type wins; equally close,
     * unrelated matches fail with an explicit ambiguity error.
     */
    public static <T extends DensityFunction> void registerForSubtypes(
            Class<T> functionType, GpuDensityFunctionTranslator<? super T> translator) {
        add(functionType, translator, true);
    }

    private static <T extends DensityFunction> void add(
            Class<T> functionType, GpuDensityFunctionTranslator<? super T> translator,
            boolean includeSubtypes) {
        TRANSLATORS.register(functionType, translator, includeSubtypes);
    }

    /** Returns whether an exact or uniquely applicable translator is registered. */
    public static boolean hasTranslator(Class<? extends DensityFunction> functionType) {
        return TRANSLATORS.find(functionType) != null;
    }

    static DensityFunction translate(DensityFunction function) {
        GpuDensityFunctionTranslator<?> translator = TRANSLATORS.find(function.getClass());
        if (translator == null) return null;
        DensityFunction translated = translateTyped(translator, function);
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

    @SuppressWarnings("unchecked")
    private static <T extends DensityFunction> DensityFunction translateTyped(
            GpuDensityFunctionTranslator<?> raw, DensityFunction function) {
        GpuDensityFunctionTranslator<T> translator = (GpuDensityFunctionTranslator<T>) raw;
        return translator.translate((T) function);
    }
}
