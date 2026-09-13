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
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import dev.gpunoise.GpuChunkDensityCache;
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

    @org.spongepowered.asm.mixin.Unique
    private boolean gpunoise$debugVoidTerrain;

    @org.spongepowered.asm.mixin.Unique
    private boolean gpunoise$debugVoidTerrainCached;

    @org.spongepowered.asm.mixin.Unique
    private boolean gpunoise$lastWrittenBlockHasFluid;

    @org.spongepowered.asm.mixin.Unique
    private boolean gpunoise$lastWrittenBlockFluidCached;

    @Inject(method = "fillFromNoise", at = @At("HEAD"), cancellable = true)
    private void gpunoise$asyncBatchFill(Blender blender, RandomState randomState, StructureManager structures,
                                         ChunkAccess chunk,
                                         CallbackInfoReturnable<CompletableFuture<ChunkAccess>> callback) {
        if (!GpuTerrainRuntime.gpuOnlyWorldgenAvailable()) return;
        NoiseSettings settings = generatorSettings().value().noiseSettings()
                .clampToHeightAccessor(chunk.getHeightAccessorForGeneration());
        int minCellY = Mth.floorDiv(settings.minY(), settings.getCellHeight());
        int cellCountY = Mth.floorDiv(settings.height(), settings.getCellHeight());
        if (cellCountY <= 0) {
            callback.setReturnValue(CompletableFuture.completedFuture(chunk));
            return;
        }
        // prepareChunk returns immediately.  doFill is chained only after the
        // lattice result is installed, so no worldgen thread calls join/get.
        callback.setReturnValue(GpuTerrainRuntime.submitChunkPipeline(() ->
                GpuTerrainRuntime.prepareChunk(blender, randomState, structures, chunk,
                        minCellY, cellCountY, settings)
                .thenApplyAsync(cache -> gpunoise$fillWithGpuReady(blender, randomState, structures, chunk,
                        minCellY, cellCountY, cache), net.minecraft.Util.backgroundExecutor())));
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
            GpuTerrainRuntime.bindFillCache(cache);
            gpunoise$debugVoidTerrain = SharedConstants.debugVoidTerrain(chunk.getPos());
            gpunoise$debugVoidTerrainCached = true;
            long fillStart = GpuTerrainProfiler.beginFill();
            try {
                return gpunoise$doFill(blender, structures, randomState, chunk, minCellY, cellCountY);
            } finally {
                GpuTerrainProfiler.endFill(fillStart);
                gpunoise$debugVoidTerrainCached = false;
                GpuTerrainRuntime.clearFillCache();
            }
        } finally {
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
        gpunoise$lastWrittenBlockHasFluid = ((GpuLevelChunkSectionAccess) (Object) section)
                .gpunoise$writeGpuBlock((y << 4 | z) << 4 | x, state);
        gpunoise$lastWrittenBlockFluidCached = true;
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
        if (gpunoise$lastWrittenBlockFluidCached) {
            gpunoise$lastWrittenBlockFluidCached = false;
            return !gpunoise$lastWrittenBlockHasFluid;
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
        if (gpunoise$debugVoidTerrainCached) {
            return gpunoise$debugVoidTerrain;
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
