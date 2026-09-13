package dev.gpunoise.mixin;

import dev.gpunoise.GpuChunkDensityCache;
import dev.gpunoise.GpuTerrainRuntime;
import net.minecraft.core.Holder;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import java.util.concurrent.CompletableFuture;

/** Prepares the same GPU cache for C2ME's biome stage before fillFromNoise. */
@Mixin(NoiseBasedChunkGenerator.class)
abstract class NoiseChunkGeneratorBiomeMixin {
    @Shadow public abstract Holder<NoiseGeneratorSettings> generatorSettings();

    @WrapMethod(method = "createBiomes")
    private CompletableFuture<ChunkAccess> gpunoise$prepareBiomeCache(
            RandomState randomState, Blender blender, StructureManager structures, ChunkAccess chunk,
            Operation<CompletableFuture<ChunkAccess>> original) {
        if (!GpuTerrainRuntime.gpuOnlyWorldgenAvailable()) {
            return original.call(randomState, blender, structures, chunk);
        }
        NoiseSettings settings = generatorSettings().value().noiseSettings()
                .clampToHeightAccessor(chunk.getHeightAccessorForGeneration());
        return GpuTerrainRuntime.prepareChunkForBiomes(chunk, settings).thenComposeAsync(cache -> {
            GpuTerrainRuntime.bindFillCache(cache);
            try {
                return original.call(randomState, blender, structures, chunk)
                        .whenComplete((ignored, failure) -> GpuTerrainRuntime.clearFillCache());
            } catch (Throwable failure) {
                GpuTerrainRuntime.clearFillCache();
                throw failure;
            }
        }, net.minecraft.Util.backgroundExecutor());
    }
}
