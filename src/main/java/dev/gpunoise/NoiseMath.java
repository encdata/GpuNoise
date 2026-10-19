package dev.gpunoise;

/** Deterministic integer value noise used by the Vulkan parity probe. */
final class NoiseMath {
    private NoiseMath() {}

    static float valueNoise(int x, int z, int seed) {
        int hash = seed ^ (x * 0x9E3779B9) ^ (z * 0x85EBCA6B);
        hash ^= hash >>> 16;
        hash *= 0x7FEB352D;
        hash ^= hash >>> 15;
        hash *= 0x846CA68B;
        hash ^= hash >>> 16;
        return (hash & 0x00FFFFFF) * (1.0f / 16_777_216.0f);
    }
}
