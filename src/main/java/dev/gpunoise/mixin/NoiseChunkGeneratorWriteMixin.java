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
import java.util.concurrent.CompletableFuture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import dev.gpunoise.GpuChunkDensityCache;
import dev.gpunoise.GpuPalettedContainerAccess;
import dev.gpunoise.GpuTerrainRuntime;

/**
 * Noisium-style worldgen write optimization. Noise generation owns the section
 * exclusively, so the ThreadingDetector lock is unnecessary here. The section
 * still performs vanilla counter updates and palette resizing.
 */
@Mixin(NoiseBasedChunkGenerator.class)
abstract class NoiseChunkGeneratorWriteMixin {
    @Shadow public abstract Holder<NoiseGeneratorSettings> generatorSettings();

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
        LevelChunkSection[] sections = new LevelChunkSection[top - bottom + 1];
        int sectionCount = 0;
        for (int sectionIndex = top; sectionIndex >= bottom; sectionIndex--) {
            LevelChunkSection section = chunk.getSection(sectionIndex);
            section.acquire();
            sections[sectionCount++] = section;
        }
        try {
            GpuTerrainRuntime.bindFillCache(cache);
            try {
                return gpunoise$doFill(blender, structures, randomState, chunk, minCellY, cellCountY);
            } finally {
                GpuTerrainRuntime.clearFillCache();
            }
        } finally {
            GpuTerrainRuntime.releaseGpuCache(cache);
            for (int i = 0; i < sectionCount; i++) {
                // The GPU-owned fill uses unchecked palette writes below, so
                // rebuild the three section counters once instead of doing
                // fluid/random-tick bookkeeping for every generated block.
                sections[i].recalcBlockCounts();
                sections[i].release();
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
        @SuppressWarnings("unchecked")
        GpuPalettedContainerAccess<BlockState> states =
                (GpuPalettedContainerAccess<BlockState>) (Object) section.getStates();
        states.gpunoise$setUnchecked((y << 4 | z) << 4 | x, state);
        return state;
    }
}
