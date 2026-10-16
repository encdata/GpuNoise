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
import net.minecraft.SharedConstants;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
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

    @WrapMethod(method = "fillFromNoise")
    private CompletableFuture<ChunkAccess> gpunoise$asyncBatchFill(
            Blender blender, RandomState randomState, StructureManager structures, ChunkAccess chunk,
            Operation<CompletableFuture<ChunkAccess>> original) {
        if (!GpuTerrainRuntime.gpuOnlyWorldgenAvailable()
                || !GpuTerrainRuntime.matchesTerrainRouter(randomState)) {
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
        CompletableFuture<ChunkAccess> gpuFill = GpuTerrainRuntime.submitChunkPipeline(() -> {
            CompletionStage<GpuChunkDensityCache> prepared = GpuTerrainRuntime.prepareChunk(
                    blender, randomState, structures, chunk, minCellY, cellCountY, settings);
            // Only preparation failures are safe to retry with vanilla. Once
            // doFill starts it may have written sections, so retrying after a
            // fill exception could mix two terrain writers into one chunk.
            return prepared.handle((cache, failure) -> {
                if (failure != null) {
                    GpuTerrainRuntime.markCpuFallbackChunk(
                            chunk.getPos().x, chunk.getPos().z, failure);
                    return original.call(blender, randomState, structures, chunk);
                }
                return CompletableFuture.supplyAsync(
                        () -> gpunoise$fillWithGpuReady(blender, randomState, structures, chunk,
                                minCellY, cellCountY, cache), net.minecraft.Util.backgroundExecutor());
            }).thenCompose(stage -> stage);
        });
        return gpuFill;
    }

    private ChunkAccess gpunoise$fillWithGpuReady(Blender blender, RandomState randomState, StructureManager structures,
                                                  ChunkAccess chunk, int minCellY, int cellCountY,
                                                  GpuChunkDensityCache cache) {
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
            state.directWritesSafe = true;
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
                return gpunoise$doFill(blender, structures, randomState, chunk, minCellY, cellCountY);
            } finally {
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
        fillState.lastWrittenBlockHasFluid = ((GpuLevelChunkSectionAccess) (Object) section)
                .gpunoise$writeGpuBlock((y << 4 | z) << 4 | x, state);
        fillState.lastWrittenBlockFluidCached = true;
        GpuTerrainProfiler.recordBlockWrite();
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
