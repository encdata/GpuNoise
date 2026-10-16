package dev.gpunoise;

import net.minecraft.world.level.levelgen.DensityFunction;

/** Registration point for other mods that expose custom density functions. */
public final class GpuDensityFunctionTranslators {
    private static final GpuDensityTranslatorRegistry<GpuDensityFunctionTranslator<?>> TRANSLATORS =
            new GpuDensityTranslatorRegistry<>();
    private static volatile boolean registrationsFrozen;

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

    private static synchronized <T extends DensityFunction> void add(
            Class<T> functionType, GpuDensityFunctionTranslator<? super T> translator,
            boolean includeSubtypes) {
        if (registrationsFrozen) {
            throw new IllegalStateException("GPU density translators are frozen because graph compilation has started");
        }
        TRANSLATORS.register(functionType, translator, includeSubtypes);
    }

    /** Returns whether an exact or uniquely applicable translator is registered. */
    public static synchronized boolean hasTranslator(Class<? extends DensityFunction> functionType) {
        return TRANSLATORS.find(functionType) != null;
    }

    static synchronized DensityFunction translate(DensityFunction function) {
        GpuDensityFunctionTranslator<?> translator = TRANSLATORS.find(function.getClass());
        if (translator == null) return null;
        DensityFunction translated = translateTyped(translator, function);
        if (translated == null) {
            // A direct-expression translator may intentionally have no
            // vanilla graph representation. The caller decides whether its
            // active Vulkan path supports that expression IR.
            return null;
        }
        if (translated == function) {
            throw new IllegalStateException("GPU density translator returned its input for "
                    + function.getClass().getName());
        }
        return translated;
    }

    static synchronized GpuDensityExpression translateToGpu(DensityFunction function) {
        GpuDensityFunctionTranslator<?> translator = TRANSLATORS.find(function.getClass());
        if (translator == null) return null;
        return translateExpressionTyped(translator, function);
    }

    static synchronized void freezeRegistrations() {
        registrationsFrozen = true;
    }

    @SuppressWarnings("unchecked")
    private static <T extends DensityFunction> DensityFunction translateTyped(
            GpuDensityFunctionTranslator<?> raw, DensityFunction function) {
        GpuDensityFunctionTranslator<T> translator = (GpuDensityFunctionTranslator<T>) raw;
        return translator.translate((T) function);
    }

    @SuppressWarnings("unchecked")
    private static <T extends DensityFunction> GpuDensityExpression translateExpressionTyped(
            GpuDensityFunctionTranslator<?> raw, DensityFunction function) {
        GpuDensityFunctionTranslator<T> translator = (GpuDensityFunctionTranslator<T>) raw;
        return translator.translateToGpu((T) function);
    }
}
