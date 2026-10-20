package dev.gpunoise.mixin;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.core.Holder;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.SharedConstants;
import net.neoforged.fml.ModList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import dev.gpunoise.GpuChunkDensityCache;
import dev.gpunoise.GpuFillState;
import dev.gpunoise.GpuLevelChunkSectionAccess;
import dev.gpunoise.GpuHeightmapAccess;
import dev.gpunoise.GpuTerrainRuntime;
import dev.gpunoise.GpuTerrainProfiler;
import dev.gpunoise.GpuAquiferDecisionAccess;
import dev.gpunoise.GpuAquiferDecisionTable;

/**
 * Noisium-style worldgen write optimization. Noise generation owns the section
 * exclusively, so the ThreadingDetector lock is unnecessary here. The section
 * still performs vanilla counter updates and palette resizing.
 */
@Mixin(NoiseBasedChunkGenerator.class)
abstract class NoiseChunkGeneratorWriteMixin {
    @Shadow public abstract Holder<NoiseGeneratorSettings> generatorSettings();

    /*
     * NoiseBasedChunkGenerator is shared by all concurrent C2ME fills.  This
     * state must therefore follow the fill thread, not the generator object;
     * instance fields let one chunk's write affect another chunk's fallback.
     */
    @org.spongepowered.asm.mixin.Unique
    private static final ThreadLocal<GpuFillState> gpunoise$fillState = new ThreadLocal<>();

    @org.spongepowered.asm.mixin.Unique
    private static final AtomicBoolean gpunoise$loggedPaletteCompatibility = new AtomicBoolean();

    @org.spongepowered.asm.mixin.Unique
    private static final AtomicBoolean gpunoise$loggedDecisionWriter = new AtomicBoolean();

    @org.spongepowered.asm.mixin.Unique
    private static final AtomicBoolean gpunoise$loggedDecisionWriterFallback = new AtomicBoolean();

    @WrapMethod(method = "fillFromNoise")
    private CompletableFuture<ChunkAccess> gpunoise$asyncBatchFill(
            Blender blender, RandomState randomState, StructureManager structures, ChunkAccess chunk,
            Operation<CompletableFuture<ChunkAccess>> original) {
        if (!GpuTerrainRuntime.gpuOnlyWorldgenAvailable()
                || !GpuTerrainRuntime.matchesTerrainRouter(randomState)
                // Match C2ME OCL's batching eligibility: its GPU region path
                // only runs where the Blender reports no old-chunk data.
                // BlendDensity's GPU lowering encodes the empty-blender
                // identity values, so blended chunks must keep vanilla's
                // exact per-context blending behavior.
                || blender != Blender.empty()) {
            return original.call(blender, randomState, structures, chunk);
        }
        NoiseSettings settings = generatorSettings().value().noiseSettings()
                .clampToHeightAccessor(chunk.getHeightAccessorForGeneration());
        int minCellY = Mth.floorDiv(settings.minY(), settings.getCellHeight());
        int cellCountY = Mth.floorDiv(settings.height(), settings.getCellHeight());
        if (cellCountY <= 0) {
            return original.call(blender, randomState, structures, chunk);
        }
        // prepareChunk returns immediately.  doFill is chained only after the
        // lattice result is installed, so no worldgen thread calls join/get.
        AtomicBoolean cancellationRequested = new AtomicBoolean();
        CompletableFuture<ChunkAccess> gpuFill = GpuTerrainRuntime.submitChunkPipeline(() -> {
            CompletionStage<GpuChunkDensityCache> prepared = GpuTerrainRuntime.prepareChunk(
                    blender, randomState, structures, chunk, minCellY, cellCountY, settings);
            // Only preparation failures are safe to retry with vanilla. Once
            // doFill starts it may have written sections, so retrying after a
            // fill exception could mix two terrain writers into one chunk.
            return prepared.handle((cache, failure) -> {
                if (cancellationRequested.get()) {
                    GpuTerrainRuntime.releaseGpuCache(cache);
                    return CompletableFuture.<ChunkAccess>failedFuture(
                            new CancellationException("chunk generation cancelled before CPU fill"));
                }
                if (failure != null) {
                    GpuTerrainRuntime.markCpuFallbackChunk(
                            chunk.getPos().x, chunk.getPos().z, failure);
                    return original.call(blender, randomState, structures, chunk);
                }
                return CompletableFuture.supplyAsync(
                        () -> gpunoise$startGpuFill(blender, randomState, structures, chunk,
                                minCellY, cellCountY, cache, cancellationRequested, original),
                        net.minecraft.Util.backgroundExecutor()).thenCompose(stage -> stage);
            }).thenCompose(stage -> stage);
        }, interrupted -> cancellationRequested.set(true));
        return gpuFill;
    }

    private CompletableFuture<ChunkAccess> gpunoise$startGpuFill(
            Blender blender, RandomState randomState, StructureManager structures,
            ChunkAccess chunk, int minCellY, int cellCountY, GpuChunkDensityCache cache,
            AtomicBoolean cancellationRequested,
            Operation<CompletableFuture<ChunkAccess>> original) {
        if (cancellationRequested.get()) {
            GpuTerrainRuntime.releaseGpuCache(cache);
            return CompletableFuture.failedFuture(
                    new CancellationException("chunk generation cancelled before NoiseChunk setup"));
        }
        NoiseChunk preparedNoiseChunk = null;
        RuntimeException setupFailure = null;
        try {
            // NoiseChunk construction starts its asynchronous aquifer batch.
            // Bind only for construction; do not occupy a worker while Vulkan
            // evaluates the batch and completes its readback future.
            GpuTerrainRuntime.bindFillCache(cache);
            preparedNoiseChunk = chunk.getOrCreateNoiseChunk(existing ->
                    gpunoise$createNoiseChunk(existing, structures, blender, randomState));
        } catch (RuntimeException failure) {
            setupFailure = failure;
        } finally {
            GpuTerrainRuntime.clearFillCache();
        }
        if (setupFailure == null && preparedNoiseChunk == null) {
            setupFailure = new IllegalStateException("ChunkAccess returned a null NoiseChunk");
        }
        if (setupFailure != null) {
            GpuTerrainRuntime.releaseGpuCache(cache);
            GpuTerrainRuntime.markCpuFallbackChunk(chunk.getPos().x, chunk.getPos().z, setupFailure);
            return original.call(blender, randomState, structures, chunk);
        }
        NoiseChunk noiseChunk = preparedNoiseChunk;

        CompletableFuture<Void> decisionBuild = null;
        Aquifer aquifer = noiseChunk.aquifer();
        if (aquifer instanceof GpuAquiferDecisionAccess access) {
            // A NoiseChunk may have been created by an earlier generation
            // stage before this fill cache existed. Explicitly attach/start
            // its decision build so the async continuation cannot race the
            // first interpolation call and silently miss the GPU batch.
            access.gpunoise$beginGpuDecisions(cache, noiseChunk);
            decisionBuild = access.gpunoise$decisionBuild();
        }
        // OCL applies OreVeinifier after aquifer resolution for every block.
        // Submit the same per-block classifier while aquifer rows are in
        // flight; an unavailable root or failed dispatch leaves its filler on
        // the original Java implementation.
        CompletableFuture<Void> oreVeinBuild = cache.prepareOreVeinDecisions(randomState.oreRandom())
                .handle((table, failure) -> {
                    if (failure != null) {
                        dev.gpunoise.GpuNoise.LOGGER.warn(
                                "GPU ore-vein preparation failed; OreVeinifier stays on Java", failure);
                    }
                    return null;
                }).toCompletableFuture();
        CompletableFuture<Void> allDecisionsReady = CompletableFuture.allOf(
                decisionBuild == null ? CompletableFuture.completedFuture(null) : decisionBuild,
                oreVeinBuild);

        long waitStart = allDecisionsReady.isDone() ? 0L : GpuTerrainProfiler.beginPhase();
        return allDecisionsReady.handleAsync((ignored, failure) -> {
            GpuTerrainProfiler.recordAquiferDecisionWait(waitStart);
            if (cancellationRequested.get()) {
                GpuTerrainRuntime.releaseGpuCache(cache);
                throw new CancellationException("chunk generation cancelled before terrain writes");
            }
            return gpunoise$writeGpuFilled(blender, randomState, structures, chunk,
                    minCellY, cellCountY, cache, noiseChunk,
                    gpunoise$getPublishedDecisionTable(noiseChunk));
        }, net.minecraft.Util.backgroundExecutor()).whenComplete((result, failure) -> {
            if (failure != null) GpuTerrainRuntime.releaseGpuCache(cache);
        });
    }

    private ChunkAccess gpunoise$writeGpuFilled(Blender blender, RandomState randomState,
                                                StructureManager structures, ChunkAccess chunk,
                                                int minCellY, int cellCountY,
                                                GpuChunkDensityCache cache, NoiseChunk noiseChunk,
                                                GpuAquiferDecisionTable decisions) {
        int minY = generatorSettings().value().noiseSettings().minY();
        int cellHeight = generatorSettings().value().noiseSettings().getCellHeight();
        int top = chunk.getSectionIndex(cellCountY * cellHeight - 1 + minY);
        int bottom = chunk.getSectionIndex(minY);
        // ChunkAccess already owns this array. Reusing it avoids allocating a
        // temporary section array and avoids a getSection() call for every
        // section on every chunk fill.
        LevelChunkSection[] sections = chunk.getSections();
        for (int sectionIndex = top; sectionIndex >= bottom; sectionIndex--) {
            sections[sectionIndex].acquire();
        }
        try {
            GpuFillState state = new GpuFillState();
            // Byepregen replaces the normal PalettedContainer resize path
            // with its own raw-ID palette handling. Our direct section writer
            // calls PalettedContainer.set directly, which can reach a
            // SingleValuePalette with no resize callback and crash while a
            // new chunk is upgraded. Keep GPU density generation enabled,
            // but let the normal chunk writer handle block states there.
            boolean byepregenLoaded = ModList.get().isLoaded("byepregen");
            state.directWritesSafe = !byepregenLoaded;
            if (byepregenLoaded && gpunoise$loggedPaletteCompatibility.compareAndSet(false, true)) {
                dev.gpunoise.GpuNoise.LOGGER.info(
                        "Byepregen palette compatibility detected; using normal block writes while GPU density generation remains active.");
            }
            for (int sectionIndex = top; sectionIndex >= bottom; sectionIndex--) {
                if (!sections[sectionIndex].hasOnlyAir()) {
                    state.directWritesSafe = false;
                    break;
                }
            }
            gpunoise$fillState.set(state);
            GpuTerrainRuntime.bindFillCache(cache);
            state.debugVoidTerrain = SharedConstants.debugVoidTerrain(chunk.getPos());
            state.debugVoidTerrainCached = true;
            long fillStart = GpuTerrainProfiler.beginFill();
            try {
                if (decisions != null && decisions.validCount() > 0
                        && decisions.coversChunk(chunk.getPos().getMinBlockX(), minCellY * cellHeight,
                                chunk.getPos().getMinBlockZ(), cellCountY * cellHeight)) {
                    if (gpunoise$loggedDecisionWriter.compareAndSet(false, true)) {
                        dev.gpunoise.GpuNoise.LOGGER.info(
                                "GPU terrain material decisions are available; using the hybrid decision-driven CPU fill writer.");
                    }
                    return dev.gpunoise.GpuTerrainDecisionWriter.write(chunk, noiseChunk, decisions,
                            minCellY, cellCountY,
                            generatorSettings().value().noiseSettings().getCellWidth(),
                            cellHeight, generatorSettings().value().defaultBlock(), state);
                }
                if (gpunoise$loggedDecisionWriterFallback.compareAndSet(false, true)) {
                    dev.gpunoise.GpuNoise.LOGGER.info(
                            "GPU terrain material table is incomplete or unavailable; using vanilla block-state fill for this chunk.");
                }
                return gpunoise$doFill(blender, structures, randomState, chunk, minCellY, cellCountY);
            } finally {
                dev.gpunoise.GpuTerrainDensityParity.reportChunk(
                        chunk.getPos().x, chunk.getPos().z);
                GpuTerrainProfiler.endFill(fillStart);
                state.debugVoidTerrainCached = false;
                GpuTerrainRuntime.clearFillCache();
            }
        } finally {
            gpunoise$fillState.remove();
            GpuTerrainRuntime.releaseGpuCache(cache);
            for (int sectionIndex = top; sectionIndex >= bottom; sectionIndex--) {
                sections[sectionIndex].release();
            }
        }
    }

    @org.spongepowered.asm.mixin.gen.Invoker("doFill")
    protected abstract ChunkAccess gpunoise$doFill(Blender blender, StructureManager structures, RandomState randomState,
                                                  ChunkAccess chunk, int minCellY, int cellCountY);

    @org.spongepowered.asm.mixin.gen.Invoker("createNoiseChunk")
    protected abstract NoiseChunk gpunoise$createNoiseChunk(ChunkAccess chunk, StructureManager structures,
                                                            Blender blender, RandomState randomState);

    @org.spongepowered.asm.mixin.Unique
    private GpuAquiferDecisionTable gpunoise$getPublishedDecisionTable(NoiseChunk noiseChunk) {
        Aquifer aquifer = noiseChunk.aquifer();
        if (!(aquifer instanceof GpuAquiferDecisionAccess access)) return null;
        GpuAquiferDecisionTable decisions = access.gpunoise$decisionTable();
        if (decisions == null) return null;
        if (decisions.validCount() < decisions.capacity()
                && gpunoise$loggedDecisionWriterFallback.compareAndSet(false, true)) {
            dev.gpunoise.GpuNoise.LOGGER.info(
                    "GPU terrain material table has {}/{} GPU decisions; using vanilla per-block material decisions for rejected rows.",
                    decisions.validCount(), decisions.capacity());
        }
        return decisions;
    }

    @Redirect(
            method = "doFill",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/chunk/LevelChunkSection;setBlockState(IIILnet/minecraft/world/level/block/state/BlockState;Z)Lnet/minecraft/world/level/block/state/BlockState;"
            )
    )
    private BlockState gpunoise$writeWithoutSectionLock(
                                                  LevelChunkSection section, int x, int y, int z, BlockState state, boolean ignored) {
        GpuFillState fillState = gpunoise$fillState.get();
        if (fillState == null || !fillState.directWritesSafe
                || GpuTerrainRuntime.activeFillCache() == null) {
            return section.setBlockState(x, y, z, state, ignored);
        }
        long writeStart = GpuTerrainProfiler.beginPhase();
        fillState.lastWrittenBlockHasFluid = ((GpuLevelChunkSectionAccess) (Object) section)
                .gpunoise$writeGpuBlock((y << 4 | z) << 4 | x, state);
        fillState.lastWrittenBlockFluidCached = true;
        GpuTerrainProfiler.recordBlockWrite(writeStart);
        return state;
    }

    @Redirect(
            method = "doFill",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/levelgen/Heightmap;update(IIILnet/minecraft/world/level/block/state/BlockState;)Z"
            )
    )
    private boolean gpunoise$updateHeightmapDescending(
            net.minecraft.world.level.levelgen.Heightmap heightmap,
            int x, int y, int z, BlockState state) {
        GpuFillState fillState = gpunoise$fillState.get();
        if (fillState == null || !fillState.directWritesSafe
                || GpuTerrainRuntime.activeFillCache() == null) {
            return heightmap.update(x, y, z, state);
        }
        if (heightmap instanceof GpuHeightmapAccess fast
                && fast.gpunoise$tryDescendingWrite(x, y, z, state)) {
            return true;
        }
        return heightmap.update(x, y, z, state);
    }

    @Redirect(
            method = "doFill",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/material/FluidState;isEmpty()Z"
            )
    )
    private boolean gpunoise$reuseWrittenFluidState(net.minecraft.world.level.material.FluidState fluidState) {
        GpuFillState fillState = gpunoise$fillState.get();
        if (fillState == null || !fillState.directWritesSafe
                || GpuTerrainRuntime.activeFillCache() == null) {
            return fluidState.isEmpty();
        }
        if (fillState.lastWrittenBlockFluidCached) {
            fillState.lastWrittenBlockFluidCached = false;
            return !fillState.lastWrittenBlockHasFluid;
        }
        return fluidState.isEmpty();
    }

    /**
     * This is a per-fill constant in the normal terrain path. Vanilla asks
     * for it once per generated block, including a repeated ChunkPos lookup.
     * Keep the original call for any non-GPU invocation of doFill.
     */
    @Redirect(
            method = "doFill",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/SharedConstants;debugVoidTerrain(Lnet/minecraft/world/level/ChunkPos;)Z"
            )
    )
    private boolean gpunoise$cachedDebugVoidTerrain(net.minecraft.world.level.ChunkPos pos) {
        GpuFillState fillState = gpunoise$fillState.get();
        if (fillState != null && GpuTerrainRuntime.activeFillCache() != null
                && fillState.debugVoidTerrainCached) {
            return fillState.debugVoidTerrain;
        }
        return SharedConstants.debugVoidTerrain(pos);
    }

    /**
     * 1.21.1's preliminary-surface hook is intentionally a no-op. Returning
     * the state directly avoids a virtual/private helper call for every block.
     */
    @Redirect(
            method = "doFill",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/levelgen/NoiseBasedChunkGenerator;debugPreliminarySurfaceLevel(Lnet/minecraft/world/level/levelgen/NoiseChunk;IIILnet/minecraft/world/level/block/state/BlockState;)Lnet/minecraft/world/level/block/state/BlockState;"
            )
    )
    private BlockState gpunoise$skipNoOpPreliminarySurfaceLevel(
            NoiseBasedChunkGenerator ignored,
            net.minecraft.world.level.levelgen.NoiseChunk noiseChunk,
            int x, int y, int z, BlockState state) {
        return state;
    }
}
