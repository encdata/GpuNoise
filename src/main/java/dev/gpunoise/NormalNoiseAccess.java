package dev.gpunoise;

import net.minecraft.world.level.levelgen.synth.PerlinNoise;

public interface NormalNoiseAccess {
    PerlinNoise gpunoise$first();
    PerlinNoise gpunoise$second();
    double gpunoise$valueFactor();
}
