package dev.gpunoise;

import net.minecraft.world.level.levelgen.synth.PerlinNoise;

public interface BlendedNoiseAccess {
    PerlinNoise gpunoise$minLimitNoise();
    PerlinNoise gpunoise$maxLimitNoise();
    PerlinNoise gpunoise$mainNoise();
    double gpunoise$xzMultiplier();
    double gpunoise$yMultiplier();
    double gpunoise$xzFactor();
    double gpunoise$yFactor();
    double gpunoise$smearScaleMultiplier();
}
