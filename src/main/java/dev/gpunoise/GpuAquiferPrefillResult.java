package dev.gpunoise;

/** Results from the co-submitted nearest-center and C2ME fluid-status passes. */
public record GpuAquiferPrefillResult(int[] nearestRows, int[] fluidStatusRows) {}
