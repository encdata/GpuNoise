package dev.gpunoise;

/** Write-only palette access used by the exclusive GPU terrain fill. */
public interface GpuPalettedContainerAccess<T> {
    void gpunoise$setUnchecked(int index, T value);
}
