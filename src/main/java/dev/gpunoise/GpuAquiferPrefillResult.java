package dev.gpunoise;

/** Results from co-submitted nearest-center, fluid-status, and optional decision passes. */
public record GpuAquiferPrefillResult(int[] nearestRows, int[] fluidStatusRows, int[] decisionRows) {
    public GpuAquiferPrefillResult(int[] nearestRows, int[] fluidStatusRows) {
        this(nearestRows, fluidStatusRows, new int[0]);
    }
}
