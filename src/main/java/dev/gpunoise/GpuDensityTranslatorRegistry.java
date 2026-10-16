package dev.gpunoise;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Deterministic exact and subtype matching shared by custom GPU adapters. */
final class GpuDensityTranslatorRegistry<T> {
    private final ConcurrentMap<Class<?>, Registration<T>> registrations = new ConcurrentHashMap<>();

    void register(Class<?> type, T translator, boolean includeSubtypes) {
        if (type == null || translator == null) throw new NullPointerException();
        Registration<T> previous = registrations.putIfAbsent(type,
                new Registration<>(translator, includeSubtypes));
        if (previous != null) {
            throw new IllegalStateException("A GPU density translator is already registered for "
                    + type.getName());
        }
    }

    T find(Class<?> concreteType) {
        if (concreteType == null) return null;
        Registration<T> exact = registrations.get(concreteType);
        if (exact != null) return exact.translator;

        Map<Class<?>, Integer> distances = typeDistances(concreteType);
        int bestDistance = Integer.MAX_VALUE;
        List<Class<?>> bestTypes = new ArrayList<>();
        for (Map.Entry<Class<?>, Registration<T>> entry : registrations.entrySet()) {
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
                    + concreteType.getName() + ": "
                    + bestTypes.stream().map(Class::getName).toList());
        }
        return registrations.get(bestTypes.get(0)).translator;
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

    private record Registration<T>(T translator, boolean includeSubtypes) {}
}
