package dev.gpunoise;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseChunk;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** CPU terrain writer driven by parity-admitted Vulkan aquifer decisions. */
public final class GpuTerrainDecisionWriter {
    private static final int TRACE_X = Integer.getInteger("gpunoise.traceAquiferBlockX", Integer.MIN_VALUE);
    private static final int TRACE_Y = Integer.getInteger("gpunoise.traceAquiferBlockY", Integer.MIN_VALUE);
    private static final int TRACE_Z = Integer.getInteger("gpunoise.traceAquiferBlockZ", Integer.MIN_VALUE);
    private static final int PREPARATION_THREADS = Math.max(1, Math.min(4,
            Integer.getInteger("gpunoise.terrainPreparationThreads",
                    Math.max(1, Runtime.getRuntime().availableProcessors() / 2))));
    private static final ThreadPoolExecutor PREPARATION_EXECUTOR = createPreparationExecutor();

    private GpuTerrainDecisionWriter() {}

    /** A fully prepared chunk that can be written as part of one aligned region. */
    public record ChunkFill(ChunkAccess chunk, NoiseChunk noiseChunk,
                            GpuAquiferDecisionTable decisions, int minCellY, int cellCountY,
                            int cellWidth, int cellHeight, BlockState defaultBlock,
                            GpuFillState fillState, GpuChunkDensityCache cache,
                            long fillStartNanos, AtomicBoolean cancellationRequested) {
        public ChunkFill {
            if (chunk == null || noiseChunk == null || decisions == null || defaultBlock == null
                    || fillState == null || cache == null || cancellationRequested == null
                    || cellCountY <= 0 || cellWidth <= 0 || cellHeight <= 0
                    || 16 % cellWidth != 0 || decisions.validCount() == 0) {
                throw new IllegalArgumentException("invalid GPU terrain chunk fill");
            }
            if (!decisions.coversChunk(chunk.getPos().getMinBlockX(), minCellY * cellHeight,
                    chunk.getPos().getMinBlockZ(), cellCountY * cellHeight)) {
                throw new IllegalArgumentException("aquifer decision table does not cover chunk fill");
            }
        }
    }

    /**
     * Writes a compatible aligned chunk region with all participating
     * sections acquired one vertical layer at a time, following OCL's region ownership strategy.
     * The request may contain a partial region when neighboring chunks are not
     * ready yet; each supplied chunk is still written exactly once.
     */
    public static List<ChunkAccess> writeRegion(List<ChunkFill> fills) {
        if (fills == null || fills.isEmpty() || fills.size() > 16) {
            throw new IllegalArgumentException("terrain write region must contain 1..16 chunks");
        }
        ChunkFill first = fills.get(0);
        int regionX = Math.floorDiv(first.chunk().getPos().x, 4);
        int regionZ = Math.floorDiv(first.chunk().getPos().z, 4);
        Object worldIdentity = first.chunk().getHeightAccessorForGeneration();
        Set<ChunkAccess> uniqueChunks = new HashSet<>(fills.size());
        ArrayList<ChunkAccess> written = new ArrayList<>(java.util.Collections.nCopies(fills.size(), null));
        ArrayList<ChunkAccess> heightmapsToPrime = new ArrayList<>(fills.size());
        boolean[] writeProtected = new boolean[fills.size()];
        PreparedChunk[] prepared = new PreparedChunk[fills.size()];
        for (int fillIndex = 0; fillIndex < fills.size(); fillIndex++) {
            ChunkFill fill = fills.get(fillIndex);
            if (Math.floorDiv(fill.chunk().getPos().x, 4) != regionX
                    || Math.floorDiv(fill.chunk().getPos().z, 4) != regionZ
                    || fill.chunk().getHeightAccessorForGeneration() != worldIdentity
                    || fill.minCellY() != first.minCellY()
                    || fill.cellCountY() != first.cellCountY()
                    || fill.cellWidth() != first.cellWidth()
                    || fill.cellHeight() != first.cellHeight()
                    || !fill.defaultBlock().equals(first.defaultBlock())
                    || !uniqueChunks.add(fill.chunk())) {
                throw new IllegalArgumentException("incompatible or duplicate chunk in terrain write region");
            }
            // Match OCL's write-protection rule: a chunk whose noise stage
            // already completed must never be rewritten by a duplicate or
            // late region batch.
            if (fill.chunk().getHighestGeneratedStatus().isOrAfter(ChunkStatus.NOISE)) {
                writeProtected[fillIndex] = true;
                written.set(fillIndex, fill.chunk());
                continue;
            }
        }
        // Resolve interpolation and Java material fallbacks before section
        // ownership. Each NoiseChunk and density cache belongs to one fill,
        // so these preparations can run independently while the final region
        // commit remains ordered and exclusively owned by this writer.
        @SuppressWarnings("unchecked")
        CompletableFuture<PreparedChunk>[] preparation = new CompletableFuture[fills.size()];
        PreparedChunk[] preparedInline = new PreparedChunk[fills.size()];
        for (int fillIndex = 0; fillIndex < fills.size(); fillIndex++) {
            if (writeProtected[fillIndex]) continue;
            ChunkFill fill = fills.get(fillIndex);
            int minY = fill.minCellY() * fill.cellHeight();
            int topSection = fill.chunk().getSectionIndex(
                    fill.cellCountY() * fill.cellHeight() - 1 + minY);
            int bottomSection = fill.chunk().getSectionIndex(minY);
            if (bottomSection < 0 || topSection >= fill.chunk().getSections().length
                    || bottomSection > topSection) {
                throw new IllegalArgumentException("terrain write range is outside chunk sections");
            }
            try {
                preparation[fillIndex] = CompletableFuture.supplyAsync(
                        () -> prepareFill(fill), PREPARATION_EXECUTOR);
                if (GpuTerrainProfiler.enabled()) {
                    GpuTerrainProfiler.recordTerrainPreparationSchedule(false,
                            PREPARATION_EXECUTOR.getActiveCount(), PREPARATION_EXECUTOR.getQueue().size());
                }
            } catch (RejectedExecutionException saturated) {
                // Keep the bounded queue from turning pressure into a failed
                // worldgen stage. The writer can prepare this one fill inline.
                if (GpuTerrainProfiler.enabled()) {
                    GpuTerrainProfiler.recordTerrainPreparationSchedule(true,
                            PREPARATION_EXECUTOR.getActiveCount(), PREPARATION_EXECUTOR.getQueue().size());
                }
                preparedInline[fillIndex] = prepareFill(fill);
            }
        }
        for (int fillIndex = 0; fillIndex < fills.size(); fillIndex++) {
            CompletableFuture<PreparedChunk> task = preparation[fillIndex];
            if (task != null) prepared[fillIndex] = task.join();
            else prepared[fillIndex] = preparedInline[fillIndex];
        }
        PreparedChunk[] byTile = new PreparedChunk[16];
        for (PreparedChunk output : prepared) {
            if (output == null) continue;
            int tileIndex = (Math.floorMod(output.chunk().getPos().z, 4) << 2)
                    | Math.floorMod(output.chunk().getPos().x, 4);
            byTile[tileIndex] = output;
        }
        writeRegionBlocks(byTile, regionX, regionZ,
                first.cellCountY() * first.cellHeight(), first.minCellY() * first.cellHeight());
        for (int fillIndex = 0; fillIndex < fills.size(); fillIndex++) {
            if (prepared[fillIndex] != null) {
                ChunkAccess chunk = fills.get(fillIndex).chunk();
                written.set(fillIndex, chunk);
                heightmapsToPrime.add(chunk);
            }
        }
        // OCL updates these once after the batched palette writes. Besides
        // avoiding two heightmap probes for every solid block, doing this
        // after releasing section ownership matches Minecraft's normal stage
        // boundary and keeps heightmap reads out of the locked write loop.
        if (!heightmapsToPrime.isEmpty()) {
            for (int i = 0; i < heightmapsToPrime.size(); i++) {
                Heightmap.primeHeightmaps(heightmapsToPrime.get(i),
                        EnumSet.of(Heightmap.Types.OCEAN_FLOOR_WG, Heightmap.Types.WORLD_SURFACE_WG));
            }
        }
        if (written.contains(null)) {
            throw new IllegalStateException("terrain region writer did not produce every chunk result");
        }
        return List.copyOf(written);
    }

    private static ThreadPoolExecutor createPreparationExecutor() {
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "GPU Noise terrain preparation");
            thread.setDaemon(true);
            return thread;
        };
        return new ThreadPoolExecutor(PREPARATION_THREADS, PREPARATION_THREADS,
                30L, TimeUnit.SECONDS, new ArrayBlockingQueue<>(64), factory,
                new ThreadPoolExecutor.AbortPolicy());
    }

    private static PreparedChunk prepareFill(ChunkFill fill) {
        GpuTerrainRuntime.bindFillCache(fill.cache());
        try {
            return prepareChunk(fill, new RegionPalette(fill.defaultBlock()));
        } finally {
            GpuTerrainRuntime.clearFillCache();
        }
    }

    /**
     * Preserves NoiseChunk's cell-interpolation lifecycle and uses resolved
     * Vulkan aquifer states directly. The writer acquires each touched section
     * layer and validates table coverage before its block pass. Rejected
     * rows continue through vanilla NoiseChunk interpolation and aquifer evaluation.
     */
    public static ChunkAccess write(ChunkAccess chunk, NoiseChunk noiseChunk,
                                    GpuAquiferDecisionTable decisions,
                                    int minCellY, int cellCountY,
                                    int cellWidth, int cellHeight,
                                    BlockState defaultBlock, GpuFillState fillState) {
        PreparedChunk prepared = prepareChunk(chunk, noiseChunk, decisions, minCellY,
                cellCountY, cellWidth, cellHeight, defaultBlock, fillState,
                new RegionPalette(defaultBlock));
        writePreparedChunk(prepared);
        Heightmap.primeHeightmaps(chunk,
                EnumSet.of(Heightmap.Types.OCEAN_FLOOR_WG, Heightmap.Types.WORLD_SURFACE_WG));
        return chunk;
    }

    private static PreparedChunk prepareChunk(ChunkFill fill, RegionPalette palette) {
        return prepareChunk(fill.chunk(), fill.noiseChunk(), fill.decisions(), fill.minCellY(),
                fill.cellCountY(), fill.cellWidth(), fill.cellHeight(), fill.defaultBlock(),
                fill.fillState(), palette);
    }

    private static PreparedChunk prepareChunk(ChunkAccess chunk, NoiseChunk noiseChunk,
                                    GpuAquiferDecisionTable decisions,
                                    int minCellY, int cellCountY,
                                    int cellWidth, int cellHeight,
                                    BlockState defaultBlock, GpuFillState fillState,
                                    RegionPalette palette) {
        if (decisions.validCount() == 0) {
            throw new IllegalArgumentException("GPU terrain writer requires at least one admitted decision");
        }
        long preparationStart = GpuTerrainProfiler.beginTerrainBlockPreparation();

        ChunkPos chunkPos = chunk.getPos();
        int startX = chunkPos.getMinBlockX();
        int startZ = chunkPos.getMinBlockZ();
        int cellsXZ = 16 / cellWidth;
        int minY = minCellY * cellHeight;
        int minDecisionY = decisions.minY();
        boolean debugVoid = fillState.debugVoidTerrain;
        BlockPaletteBuffer blockPaletteIds = new BlockPaletteBuffer(cellCountY * cellHeight * 256);
        BlockState air = Blocks.AIR.defaultBlockState();
        GpuNoiseChunkAccess noiseAccess = (GpuNoiseChunkAccess) (Object) noiseChunk;
        net.minecraft.world.level.levelgen.Aquifer aquifer = noiseChunk.aquifer();
        boolean[] parityColumns = null;
        if (GpuTerrainDensityParity.enabled()) {
            parityColumns = new boolean[256];
            for (int localZ = 0; localZ < 16; localZ++) {
                for (int localX = 0; localX < 16; localX++) {
                    parityColumns[(localZ << 4) | localX] = GpuTerrainDensityParity.shouldCompare(
                            startX + localX, startZ + localZ);
                }
            }
        }
        boolean profileDecisionUse = GpuTerrainProfiler.enabled();
        long blockDecisionSamples = 0L;
        long gpuDecisionHits = 0L;
        long missingDecisionRows = 0L;
        long parityBypasses = 0L;

        noiseChunk.initializeForFirstCellX();
        for (int cellX = 0; cellX < cellsXZ; cellX++) {
            noiseChunk.advanceCellX(cellX);
            for (int cellZ = 0; cellZ < cellsXZ; cellZ++) {
                for (int cellY = cellCountY - 1; cellY >= 0; cellY--) {
                    noiseChunk.selectCellYZ(cellY, cellZ);
                    for (int inCellY = cellHeight - 1; inCellY >= 0; inCellY--) {
                        int y = minY + cellY * cellHeight + inCellY;
                        double yProgress = (double) inCellY / cellHeight;
                        boolean yInterpolationReady = false;
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
                                int columnIndex = (localZ << 4) | localX;
                                if (profileDecisionUse) {
                                    blockDecisionSamples++;
                                    if (!decisions.isValid(decisionIndex)) missingDecisionRows++;
                                    else if (parityColumns != null && parityColumns[columnIndex]) parityBypasses++;
                                }
                                BlockState gpuState = null;
                                boolean exactGpuDecision = false;
                                // The table is tied to the exact density passed
                                // to NoiseChunk's aquifer rule. A valid null is
                                // meaningful: it is the aquifer's exact
                                // RETURN_DEFAULT result, including positive
                                // density rows, so it can bypass CPU
                                // interpolation just like a returned block.
                                // Leave parity-traced columns on vanilla so its
                                // Java comparison still observes the real path.
                                if (decisions.isValid(decisionIndex)
                                        && (parityColumns == null || !parityColumns[columnIndex])) {
                                    // This immutable table is built from this NoiseChunk's exact
                                    // GPU density plus Beardifier value, then indexed by block
                                    // coordinates. Recomputing those values here repeats structure
                                    // queries for every accepted block without adding a new parity
                                    // check; table publication follows successful chunk validation.
                                    gpuState = decisions.state(decisionIndex);
                                    exactGpuDecision = true;
                                    if (profileDecisionUse) gpuDecisionHits++;
                                }
                                boolean gpuStateResolved = exactGpuDecision;
                                BlockState state;
                                if (gpuStateResolved) {
                                    // The Vulkan row already contains an exact
                                    // density-matched aquifer state. Avoid
                                    // walking every NoiseChunk interpolator for
                                    // this block; initialize the interpolation
                                    // context lazily only if a later block needs
                                    // the full vanilla interpolation/aquifer path.
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
                                if (debugVoid) state = air;
                                int blockIndex = ((y - minY) << 8) | (localZ << 4) | localX;
                                int paletteId = palette.idFor(state);
                                blockPaletteIds.setStateId(blockIndex, paletteId);
                                if (paletteId != 0) {
                                    boolean scheduleFluidUpdate = gpuStateResolved
                                            ? decisions.schedulesFluidUpdate(decisionIndex)
                                            : aquifer.shouldScheduleFluidUpdate();
                                    if (scheduleFluidUpdate && !state.getFluidState().isEmpty()) {
                                        blockPaletteIds.markFluidPostProcessing(blockIndex);
                                    }
                                }
                            }
                        }
                    }
                }
            }
            noiseChunk.swapSlices();
        }
        noiseChunk.stopInterpolation();
        GpuTerrainProfiler.recordTerrainBlockPreparation(preparationStart);
        GpuTerrainProfiler.recordTerrainDecisionUse(blockDecisionSamples, gpuDecisionHits,
                missingDecisionRows, 0L, parityBypasses, 0L, 0L);
        return new PreparedChunk(chunk, minY, cellCountY * cellHeight, fillState,
                blockPaletteIds, palette);
    }

    /**
     * OCL-style region-major Y/Z/X write pass over compact per-chunk block
     * palettes. Missing neighbors in a partial batch are simply skipped.
     */
    private static void writeRegionBlocks(PreparedChunk[] byTile, int regionX, int regionZ,
                                          int verticalSize, int minY) {
        BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();
        ChunkAccess sectionOwner = null;
        for (PreparedChunk output : byTile) {
            if (output != null) {
                sectionOwner = output.chunk();
                break;
            }
        }
        if (sectionOwner == null) return;

        ChunkPos regionOrigin = new ChunkPos(regionX << 2, regionZ << 2);
        LevelChunkSection[] sections = new LevelChunkSection[16];
        int[][] sectionPaletteIds = new int[16][];
        boolean[] sectionDirectWrites = new boolean[16];
        long writeStart = GpuTerrainProfiler.beginPhase();
        int verticalEnd = minY + verticalSize;
        for (int sectionStartY = minY; sectionStartY < verticalEnd;) {
            int sectionIndex = sectionOwner.getSectionIndex(sectionStartY);
            int sectionEndY = Math.min(verticalEnd,
                    sectionStartY + (16 - Math.floorMod(sectionStartY, 16)));
            int acquiredCount = 0;
            try {
                for (int tile = 0; tile < byTile.length; tile++) {
                    PreparedChunk output = byTile[tile];
                    if (output == null) continue;
                    LevelChunkSection section = output.chunk().getSection(sectionIndex);
                    section.acquire();
                    sections[tile] = section;
                    acquiredCount++;
                    if (output.fillState().directWritesSafe) {
                        sectionPaletteIds[tile] = new int[output.palette().size()];
                        java.util.Arrays.fill(sectionPaletteIds[tile], -1);
                        sectionDirectWrites[tile] = true;
                    }
                }

                // Keep one section layer acquired at a time, but visit only
                // tiles that actually have output. Partial batches are common
                // while C2ME's neighboring region work is still arriving; a
                // full-region scan here would multiply the writer's block
                // traversal by up to sixteen for a single submitted chunk.
                for (int y = sectionStartY; y < sectionEndY; y++) {
                    int yOffset = y - minY;
                    for (int tileZ = 0; tileZ < 4; tileZ++) {
                        int chunkZ = regionOrigin.getMinBlockZ() + (tileZ << 4);
                        for (int tileX = 0; tileX < 4; tileX++) {
                            int tile = (tileZ << 2) | tileX;
                            PreparedChunk output = byTile[tile];
                            if (output == null) continue;
                            LevelChunkSection section = sections[tile];
                            int chunkX = regionOrigin.getMinBlockX() + (tileX << 4);
                            for (int localZ = 0; localZ < 16; localZ++) {
                                int worldZ = chunkZ + localZ;
                                for (int localX = 0; localX < 16; localX++) {
                                    int blockIndex = (yOffset << 8) | (localZ << 4) | localX;
                                    int paletteId = output.blockPaletteIds().stateId(blockIndex);
                                    if (paletteId == 0) continue;

                                    BlockState state = output.palette().state(paletteId);
                                    if (sectionDirectWrites[tile]) {
                                        int[] paletteMap = sectionPaletteIds[tile];
                                        int sectionPaletteId = paletteMap[paletteId];
                                        if (sectionPaletteId < 0) {
                                            sectionPaletteId = ((GpuLevelChunkSectionAccess) (Object) section)
                                                    .gpunoise$resolveGpuPaletteId(state);
                                            if (sectionPaletteId == -2) {
                                                // Palette growth can remap every old ID; drop this section's
                                                // cache and resolve the just-added state against the new data.
                                                java.util.Arrays.fill(paletteMap, -1);
                                                sectionPaletteId = ((GpuLevelChunkSectionAccess) (Object) section)
                                                        .gpunoise$resolveGpuPaletteId(state);
                                            }
                                            if (sectionPaletteId >= 0) paletteMap[paletteId] = sectionPaletteId;
                                            else sectionDirectWrites[tile] = false;
                                        }
                                        if (sectionDirectWrites[tile]) {
                                            ((GpuLevelChunkSectionAccess) (Object) section)
                                                    .gpunoise$writeGpuBlockOwned(
                                                            ((y & 15) << 8) | (localZ << 4) | localX,
                                                            state, sectionPaletteId);
                                        } else {
                                            section.setBlockState(localX, y & 15, localZ, state, false);
                                        }
                                    } else section.setBlockState(localX, y & 15, localZ, state, false);
                                    if (output.blockPaletteIds().needsFluidPostProcessing(blockIndex)) {
                                        mutable.set(chunkX + localX, y, worldZ);
                                        output.chunk().markPosForPostprocessing(mutable);
                                    }
                                }
                            }
                        }
                    }
                }
                for (LevelChunkSection section : sections) {
                    if (section != null) section.recalcBlockCounts();
                }
            } finally {
                for (int tile = byTile.length - 1; tile >= 0 && acquiredCount > 0; tile--) {
                    LevelChunkSection section = sections[tile];
                    if (section != null) {
                        section.release();
                        sections[tile] = null;
                        acquiredCount--;
                    }
                }
            }
            sectionStartY = sectionEndY;
        }
        GpuTerrainProfiler.recordBlockWrite(writeStart);
    }

    private static void writePreparedChunk(PreparedChunk output) {
        PreparedChunk[] single = new PreparedChunk[16];
        int tileIndex = (Math.floorMod(output.chunk().getPos().z, 4) << 2)
                | Math.floorMod(output.chunk().getPos().x, 4);
        single[tileIndex] = output;
        writeRegionBlocks(single, Math.floorDiv(output.chunk().getPos().x, 4),
                Math.floorDiv(output.chunk().getPos().z, 4), output.verticalSize(), output.minY());
    }

    private record PreparedChunk(ChunkAccess chunk, int minY, int verticalSize,
                                 GpuFillState fillState, BlockPaletteBuffer blockPaletteIds,
                                 RegionPalette palette) {}

    /** One OCL-style state mapping shared by all chunks in this CPU write batch. */
    private static final class RegionPalette {
        private final ArrayList<BlockState> states = new ArrayList<>();
        private final Map<BlockState, Integer> ids = new HashMap<>();
        private final BlockState air = Blocks.AIR.defaultBlockState();
        private final BlockState defaultBlock;
        private final BlockState water = Blocks.WATER.defaultBlockState();
        private final BlockState lava = Blocks.LAVA.defaultBlockState();
        private final int airId;
        private final int defaultBlockId;
        private final int waterId;
        private final int lavaId;
        private BlockState lastState;
        private int lastStateId;

        private RegionPalette(BlockState defaultBlock) {
            this.defaultBlock = defaultBlock;
            airId = register(air);
            defaultBlockId = register(defaultBlock);
            waterId = register(water);
            lavaId = register(lava);
            lastState = air;
            lastStateId = airId;
        }

        private int idFor(BlockState state) {
            if (state == lastState) return lastStateId;
            int knownId = state == air ? airId
                    : state == defaultBlock ? defaultBlockId
                    : state == water ? waterId
                    : state == lava ? lavaId
                    : -1;
            if (knownId >= 0) {
                lastState = state;
                lastStateId = knownId;
                return knownId;
            }
            Integer existing = ids.get(state);
            int id = existing == null ? register(state) : existing;
            lastState = state;
            lastStateId = id;
            return id;
        }

        private int register(BlockState state) {
            Integer existing = ids.get(state);
            if (existing != null) return existing;
            int id = states.size();
            states.add(state);
            ids.put(state, id);
            return id;
        }

        private BlockState state(int id) { return states.get(id); }
        private int size() { return states.size(); }
    }

    /** OCL-compatible compact IDs with automatic widening for large mod palettes. */
    private static final class BlockPaletteBuffer {
        private byte[] compactIds;
        private char[] extendedIds;
        private int[] wideIds;
        private java.util.BitSet fluidPostProcessing;

        private BlockPaletteBuffer(int size) {
            compactIds = new byte[size];
        }

        private void setStateId(int index, int stateId) {
            if (stateId < 0) throw new IllegalArgumentException("negative block palette ID");
            if (wideIds != null) {
                wideIds[index] = stateId;
            } else if (extendedIds != null) {
                if (stateId > Character.MAX_VALUE) {
                    widenToInt();
                    wideIds[index] = stateId;
                } else {
                    extendedIds[index] = (char) stateId;
                }
            } else if (stateId <= 0x7f) {
                compactIds[index] = (byte) ((compactIds[index] & 0x80) | stateId);
            } else {
                widenToChar();
                setStateId(index, stateId);
            }
        }

        private void markFluidPostProcessing(int index) {
            if (compactIds != null) {
                compactIds[index] |= (byte) 0x80;
            } else {
                if (fluidPostProcessing == null) fluidPostProcessing = new java.util.BitSet();
                fluidPostProcessing.set(index);
            }
        }

        private int stateId(int index) {
            if (wideIds != null) return wideIds[index];
            if (extendedIds != null) return extendedIds[index];
            return compactIds[index] & 0x7f;
        }

        private boolean needsFluidPostProcessing(int index) {
            if (compactIds != null) return (compactIds[index] & 0x80) != 0;
            return fluidPostProcessing != null && fluidPostProcessing.get(index);
        }

        private void widenToChar() {
            char[] widened = new char[compactIds.length];
            fluidPostProcessing = new java.util.BitSet();
            for (int i = 0; i < compactIds.length; i++) {
                int encoded = compactIds[i] & 0xff;
                widened[i] = (char) (encoded & 0x7f);
                if ((encoded & 0x80) != 0) fluidPostProcessing.set(i);
            }
            compactIds = null;
            extendedIds = widened;
        }

        private void widenToInt() {
            int[] widened = new int[extendedIds.length];
            for (int i = 0; i < extendedIds.length; i++) widened[i] = extendedIds[i];
            extendedIds = null;
            wideIds = widened;
        }
    }
}
