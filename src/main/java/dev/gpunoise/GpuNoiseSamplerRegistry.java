package dev.gpunoise;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.world.level.levelgen.synth.NormalNoise;

/** Caches live vanilla samplers and assigns stable GPU table indices. */
final class GpuNoiseSamplerRegistry {
    private final Map<NormalNoise, Integer> ids = new IdentityHashMap<>();
    private final List<MinecraftNormalNoiseData> values = new ArrayList<>();

    synchronized int register(NormalNoise noise) {
        Integer existing = ids.get(noise);
        if (existing != null) return existing;
        int id = values.size();
        ids.put(noise, id);
        values.add(MinecraftNormalNoiseData.read(noise));
        return id;
    }

    synchronized MinecraftNormalNoiseData get(int id) {
        return values.get(id);
    }

    synchronized int size() { return values.size(); }
}
