package dev.gpunoise.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.gpunoise.GpuBiomeRegionResults;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Mixin;
import java.util.concurrent.atomic.AtomicBoolean;

/** Supplies precomputed GPU biome IDs while leaving section palette writes in Minecraft. */
@Mixin(ChunkAccess.class)
abstract class ChunkAccessBiomeFillMixin {
    @org.spongepowered.asm.mixin.Unique
    private static final AtomicBoolean gpunoise$loggedGpuBiome = new AtomicBoolean();

    @WrapMethod(method = "fillBiomesFromNoise")
    private void gpunoise$fillPreparedGpuBiomes(BiomeResolver resolver, Climate.Sampler sampler,
                                                Operation<Void> original) {
        ChunkAccess chunk = (ChunkAccess) (Object) this;
        GpuBiomeRegionResults results = chunk.isUpgrading()
                ? null : GpuBiomeRegionResults.take(chunk.getPos().x, chunk.getPos().z);
        if (results == null) {
            original.call(resolver, sampler);
            return;
        }
        if (gpunoise$loggedGpuBiome.compareAndSet(false, true)) {
            dev.gpunoise.GpuNoise.LOGGER.info("GPU multi-noise biome table applied to chunk {}.", chunk.getPos());
        }
        BiomeResolver gpuResolver = (biomeX, biomeY, biomeZ, climateSampler) -> {
            Holder<Biome> prepared = results.biomeAt(biomeX, biomeY, biomeZ);
            return prepared != null ? prepared
                    : resolver.getNoiseBiome(biomeX, biomeY, biomeZ, climateSampler);
        };
        original.call(gpuResolver, sampler);
    }
}
