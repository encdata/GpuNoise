package dev.gpunoise;

/** Per-worker state used while NoiseBasedChunkGenerator performs a GPU fill. */
public final class GpuFillState {
    public boolean directWritesSafe;
    public boolean debugVoidTerrain;
    public boolean debugVoidTerrainCached;
    public boolean lastWrittenBlockHasFluid;
    public boolean lastWrittenBlockFluidCached;
}
