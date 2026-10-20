package dev.gpunoise;

import net.minecraft.world.level.levelgen.NoiseChunk;

/** Runtime hook for preparing one aquifer's chunk-wide decision table. */
public interface GpuAquiferDecisionAccess {
    void gpunoise$beginGpuDecisions(GpuChunkDensityCache cache, NoiseChunk noiseChunk);

    GpuAquiferDecisionTable gpunoise$decisionTable();

    java.util.concurrent.CompletableFuture<Void> gpunoise$decisionBuild();
}
