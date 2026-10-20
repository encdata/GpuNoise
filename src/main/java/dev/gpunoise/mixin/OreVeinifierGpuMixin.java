package dev.gpunoise.mixin;

import dev.gpunoise.GpuChunkDensityCache;
import dev.gpunoise.GpuOreVeinDecisions;
import dev.gpunoise.GpuOreVeinDecisionTable;
import dev.gpunoise.GpuTerrainRuntime;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.OreVeinifier;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.Objects;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Uses parity-admitted Vulkan ore decisions while preserving the rule's Java result elsewhere. */
@Mixin(OreVeinifier.class)
abstract class OreVeinifierGpuMixin {
    @org.spongepowered.asm.mixin.Unique
    private static final boolean gpunoise$verify = Boolean.getBoolean("gpunoise.verifyOreVeinGpu");
    @org.spongepowered.asm.mixin.Unique
    private static final AtomicBoolean gpunoise$mismatchLogged = new AtomicBoolean();
    @org.spongepowered.asm.mixin.Unique
    private static final AtomicBoolean gpunoise$useLogged = new AtomicBoolean();

    @Inject(method = "create", at = @At("RETURN"), cancellable = true)
    private static void gpunoise$useGpuOreVeinRows(
            DensityFunction toggle, DensityFunction ridged, DensityFunction gap,
            PositionalRandomFactory randomFactory,
            CallbackInfoReturnable<NoiseChunk.BlockStateFiller> callback) {
        NoiseChunk.BlockStateFiller javaFiller = callback.getReturnValue();
        if (javaFiller == null || !GpuTerrainRuntime.matchesTerrainOreRoots(toggle, ridged, gap)) return;
        callback.setReturnValue(context -> {
            GpuChunkDensityCache cache = GpuTerrainRuntime.activeFillCache();
            if (cache != null && cache.matchesOreVeinRandomFactory(randomFactory)) {
                GpuOreVeinDecisionTable decisions = cache.oreVeinDecisions();
                if (decisions != null) {
                    int result = decisions.stateIdAt(context.blockX(), context.blockY(), context.blockZ());
                    if (result != GpuOreVeinDecisionTable.MISSING
                            && result != GpuOreVeinDecisions.CPU_FALLBACK) {
                        if (gpunoise$useLogged.compareAndSet(false, true)) {
                            dev.gpunoise.GpuNoise.LOGGER.info(
                                    "GPU OreVeinifier decision applied at ({}, {}, {}): stateId={}",
                                    context.blockX(), context.blockY(), context.blockZ(), result);
                        }
                        net.minecraft.world.level.block.state.BlockState gpuState = result < 0
                                ? null : Block.stateById(result);
                        if (!gpunoise$verify) return gpuState;
                        net.minecraft.world.level.block.state.BlockState javaState = javaFiller.calculate(context);
                        if (Objects.equals(javaState, gpuState)) return gpuState;
                        if (gpunoise$mismatchLogged.compareAndSet(false, true)) {
                            dev.gpunoise.GpuNoise.LOGGER.error(
                                    "GPU OreVeinifier parity mismatch at ({}, {}, {}): Vulkan={}, Java={}; using Java",
                                    context.blockX(), context.blockY(), context.blockZ(), gpuState, javaState);
                        }
                        return javaState;
                    }
                }
            }
            return javaFiller.calculate(context);
        });
    }
}
