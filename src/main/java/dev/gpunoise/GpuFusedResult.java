package dev.gpunoise;

/** Results from one fused graph + interpolation submission. */
record GpuFusedResult(float[] blocks, float[] corners, int roots) {}
