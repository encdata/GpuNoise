package dev.gpunoise;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseChunk;

/** CPU terrain writer driven by parity-admitted Vulkan aquifer decisions. */
public final class GpuTerrainDecisionWriter {
    private static final int TRACE_X = Integer.getInteger("gpunoise.traceAquiferBlockX", Integer.MIN_VALUE);
    private static final int TRACE_Y = Integer.getInteger("gpunoise.traceAquiferBlockY", Integer.MIN_VALUE);
    private static final int TRACE_Z = Integer.getInteger("gpunoise.traceAquiferBlockZ", Integer.MIN_VALUE);

    private GpuTerrainDecisionWriter() {}

    /**
     * Preserves NoiseChunk's cell-interpolation lifecycle and uses resolved
     * Vulkan aquifer states directly. The caller must acquire all touched
     * sections and validate table coverage before this method writes. Rejected
     * rows and null aquifer results continue through vanilla material rules.
     */
    public static ChunkAccess write(ChunkAccess chunk, NoiseChunk noiseChunk,
                                    GpuAquiferDecisionTable decisions,
                                    int minCellY, int cellCountY,
                                    int cellWidth, int cellHeight,
                                    BlockState defaultBlock, GpuFillState fillState) {
        if (decisions.validCount() == 0) {
            throw new IllegalArgumentException("GPU terrain writer requires at least one admitted decision");
        }

        Heightmap oceanFloor = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR_WG);
        Heightmap worldSurface = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);
        ChunkPos chunkPos = chunk.getPos();
        int startX = chunkPos.getMinBlockX();
        int startZ = chunkPos.getMinBlockZ();
        int cellsXZ = 16 / cellWidth;
        int minY = minCellY * cellHeight;
        int minDecisionY = decisions.minY();
        int sectionsCount = chunk.getSectionsCount();
        boolean debugVoid = fillState.debugVoidTerrain;
        BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();
        GpuNoiseChunkAccess noiseAccess = (GpuNoiseChunkAccess) (Object) noiseChunk;
        GpuChunkDensityCache densityCache = noiseAccess.gpunoise$gpuCache();
        net.minecraft.world.level.levelgen.Aquifer aquifer = noiseChunk.aquifer();

        noiseChunk.initializeForFirstCellX();
        for (int cellX = 0; cellX < cellsXZ; cellX++) {
            noiseChunk.advanceCellX(cellX);
            for (int cellZ = 0; cellZ < cellsXZ; cellZ++) {
                int sectionIndex = sectionsCount - 1;
                LevelChunkSection section = chunk.getSection(sectionIndex);
                for (int cellY = cellCountY - 1; cellY >= 0; cellY--) {
                    noiseChunk.selectCellYZ(cellY, cellZ);
                    for (int inCellY = cellHeight - 1; inCellY >= 0; inCellY--) {
                        int y = minY + cellY * cellHeight + inCellY;
                        double yProgress = (double) inCellY / cellHeight;
                        boolean yInterpolationReady = false;
                        int nextSectionIndex = chunk.getSectionIndex(y);
                        if (nextSectionIndex != sectionIndex) {
                            sectionIndex = nextSectionIndex;
                            section = chunk.getSection(sectionIndex);
                        }

                        for (int inCellX = 0; inCellX < cellWidth; inCellX++) {
                            int x = startX + cellX * cellWidth + inCellX;
                            int localX = x & 15;
                            double xProgress = (double) inCellX / cellWidth;
                            boolean xInterpolationReady = false;
                            for (int inCellZ = 0; inCellZ < cellWidth; inCellZ++) {
                                int z = startZ + cellZ * cellWidth + inCellZ;
                                int localZ = z & 15;

                                int decisionIndex = (y - minDecisionY) * 256
                                        + (z - startZ) * 16 + (x - startX);
                                BlockState gpuState = null;
                                boolean exactGpuDecision = false;
                                // The table is tied to the exact density passed
                                // to NoiseChunk's aquifer rule. Revalidate that
                                // input while replaying interpolation, and
                                // leave parity-traced columns on vanilla so its
                                // Java comparison still observes the real path.
                                if (densityCache != null && decisions.isValid(decisionIndex)
                                        && !GpuTerrainDensityParity.shouldCompare(x, z)) {
                                    BlockState candidate = decisions.state(decisionIndex);
                                    if (candidate != null) {
                                        double cachedDensity = densityCache.finalDensity(x, y, z);
                                        if (!Double.isNaN(cachedDensity)) {
                                            double density = noiseAccess.gpunoise$withBeardifier(
                                                    cachedDensity, x, y, z);
                                            if (decisions.matchesDensity(decisionIndex, density)) {
                                                gpuState = candidate;
                                                exactGpuDecision = true;
                                            }
                                        }
                                    }
                                }
                                // A null aquifer result must continue through
                                // Minecraft's material-rule list (including
                                // OreVeinifier) before using the default block.
                                boolean gpuStateResolved = exactGpuDecision;
                                BlockState state;
                                if (gpuStateResolved) {
                                    // The Vulkan row already contains an exact
                                    // density-matched aquifer state. Avoid
                                    // walking every NoiseChunk interpolator for
                                    // this block; initialize the interpolation
                                    // context lazily only if a later block needs
                                    // the full vanilla material-rule chain.
                                    state = gpuState;
                                } else {
                                    if (!yInterpolationReady) {
                                        noiseChunk.updateForY(y, yProgress);
                                        yInterpolationReady = true;
                                    }
                                    if (!xInterpolationReady) {
                                        noiseChunk.updateForX(x, xProgress);
                                        xInterpolationReady = true;
                                    }
                                    noiseChunk.updateForZ(z, (double) inCellZ / cellWidth);
                                    state = noiseAccess.gpunoise$vanillaInterpolatedState();
                                }
                                if (x == TRACE_X && y == TRACE_Y && z == TRACE_Z) {
                                    if (gpuStateResolved) {
                                        GpuNoise.LOGGER.info(
                                                "GPU_AQUIFER_TRACE_APPLIED block=({}, {}, {}) index={} state={} scheduleFluidUpdate={}",
                                                x, y, z, decisionIndex, gpuState,
                                                decisions.schedulesFluidUpdate(decisionIndex));
                                    } else {
                                        GpuNoise.LOGGER.info(
                                                "GPU_AQUIFER_TRACE_VANILLA block=({}, {}, {}) reason=decision-not-admitted-or-density-mismatch",
                                                x, y, z);
                                    }
                                }
                                if (state == null) state = defaultBlock;
                                if (state == Blocks.AIR.defaultBlockState() || debugVoid) continue;

                                if (fillState.directWritesSafe) {
                                    long writeStart = GpuTerrainProfiler.beginPhase();
                                    ((GpuLevelChunkSectionAccess) (Object) section)
                                            .gpunoise$writeGpuBlock((inCellY << 8) | (localZ << 4) | localX,
                                                    state);
                                    GpuTerrainProfiler.recordBlockWrite(writeStart);
                                } else {
                                    section.setBlockState(localX, inCellY, localZ, state, false);
                                }
                                updateHeightmap(oceanFloor, localX, y, localZ, state,
                                        fillState.directWritesSafe);
                                updateHeightmap(worldSurface, localX, y, localZ, state,
                                        fillState.directWritesSafe);
                                boolean scheduleFluidUpdate = gpuStateResolved
                                        ? decisions.schedulesFluidUpdate(decisionIndex)
                                        : aquifer.shouldScheduleFluidUpdate();
                                if (scheduleFluidUpdate
                                        && !state.getFluidState().isEmpty()) {
                                    mutable.set(x, y, z);
                                    chunk.markPosForPostprocessing(mutable);
                                }
                            }
                        }
                    }
                }
            }
            noiseChunk.swapSlices();
        }
        noiseChunk.stopInterpolation();
        return chunk;
    }

    private static void updateHeightmap(Heightmap heightmap, int x, int y, int z,
                                        BlockState state, boolean directWritesSafe) {
        if (directWritesSafe && heightmap instanceof GpuHeightmapAccess fast
                && fast.gpunoise$tryDescendingWrite(x, y, z, state)) {
            return;
        }
        heightmap.update(x, y, z, state);
    }
}
