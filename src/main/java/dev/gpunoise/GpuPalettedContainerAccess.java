package dev.gpunoise;

/** Write-only palette access used by the exclusive GPU terrain fill. */
public interface GpuPalettedContainerAccess<T> {
    void gpunoise$setUnchecked(int index, T value);

    /** Fast write for a fresh, exclusively section-locked palette. */
    void gpunoise$setGpuCached(int index, T value);

    /** Direct packed-storage write; valid only while the owner holds the section lock. */
    void gpunoise$setGpuOwned(int index, T value);
}
