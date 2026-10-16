package dev.gpunoise;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import net.minecraft.world.level.levelgen.DensityFunction;

/** Registration point for other mods that expose custom density functions. */
public final class GpuDensityFunctionTranslators {
    private static final ConcurrentMap<Class<?>, Registration<?>> TRANSLATORS =
            new ConcurrentHashMap<>();

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
        if (functionType == null || translator == null) throw new NullPointerException();
        Registration<T> registration = new Registration<>(translator, includeSubtypes);
        Registration<?> previous = TRANSLATORS.putIfAbsent(functionType, registration);
        if (previous != null) {
            throw new IllegalStateException("A GPU density translator is already registered for "
                    + functionType.getName());
        }
    }

    /** Returns whether an exact or uniquely applicable translator is registered. */
    public static boolean hasTranslator(Class<? extends DensityFunction> functionType) {
        return resolve(functionType) != null;
    }

    static DensityFunction translate(DensityFunction function) {
        Registration<?> registration = resolve(function.getClass());
        if (registration == null) return null;
        DensityFunction translated = translateTyped(registration.translator, function);
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

    private static Registration<?> resolve(Class<?> functionType) {
        if (functionType == null) return null;
        Registration<?> exact = TRANSLATORS.get(functionType);
        if (exact != null) return exact;

        Map<Class<?>, Integer> distances = typeDistances(functionType);
        int bestDistance = Integer.MAX_VALUE;
        List<Class<?>> bestTypes = new ArrayList<>();
        for (Map.Entry<Class<?>, Registration<?>> entry : TRANSLATORS.entrySet()) {
            if (!entry.getValue().includeSubtypes) continue;
            Integer distance = distances.get(entry.getKey());
            if (distance == null) continue;
            if (distance < bestDistance) {
                bestDistance = distance;
                bestTypes.clear();
                bestTypes.add(entry.getKey());
            } else if (distance == bestDistance) {
                bestTypes.add(entry.getKey());
            }
        }
        if (bestTypes.isEmpty()) return null;
        if (bestTypes.size() > 1) {
            bestTypes.sort((left, right) -> left.getName().compareTo(right.getName()));
            throw new IllegalStateException("Ambiguous GPU density translators for "
                    + functionType.getName() + ": "
                    + bestTypes.stream().map(Class::getName).toList());
        }
        return TRANSLATORS.get(bestTypes.get(0));
    }

    private static Map<Class<?>, Integer> typeDistances(Class<?> concreteType) {
        Map<Class<?>, Integer> distances = new HashMap<>();
        ArrayDeque<Class<?>> queue = new ArrayDeque<>();
        distances.put(concreteType, 0);
        queue.add(concreteType);
        while (!queue.isEmpty()) {
            Class<?> current = queue.removeFirst();
            int nextDistance = distances.get(current) + 1;
            Class<?> parent = current.getSuperclass();
            if (parent != null && distances.putIfAbsent(parent, nextDistance) == null) queue.addLast(parent);
            for (Class<?> implemented : current.getInterfaces()) {
                if (distances.putIfAbsent(implemented, nextDistance) == null) queue.addLast(implemented);
            }
        }
        return distances;
    }

    @SuppressWarnings("unchecked")
    private static <T extends DensityFunction> DensityFunction translateTyped(
            GpuDensityFunctionTranslator<?> raw, DensityFunction function) {
        GpuDensityFunctionTranslator<T> translator = (GpuDensityFunctionTranslator<T>) raw;
        return translator.translate((T) function);
    }

    private record Registration<T extends DensityFunction>(
            GpuDensityFunctionTranslator<? super T> translator, boolean includeSubtypes) {}
}
