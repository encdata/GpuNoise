package dev.gpunoise;

import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseChunk;

/** Climate sampler view that reads prepared GPU terrain roots and preserves the Java fallback. */
public final class GpuClimateFunction implements DensityFunction.SimpleFunction {
    private final NoiseChunk owner;
    private final GpuChunkDensityCache initialCache;
    private final int root;
    private final DensityFunction fallback;

    public GpuClimateFunction(NoiseChunk owner, GpuChunkDensityCache cache, int root,
                              DensityFunction fallback) {
        this.owner = owner;
        this.initialCache = cache;
        this.root = root;
        this.fallback = fallback;
    }

    @Override
    public double compute(DensityFunction.FunctionContext context) {
        GpuChunkDensityCache cache = initialCache;
        if (cache == null) {
            GpuNoiseChunkAccess access = (GpuNoiseChunkAccess) (Object) owner;
            cache = access.gpunoise$gpuCache();
            if (cache == null) {
                GpuChunkDensityCache candidate = GpuTerrainRuntime.activeFillCache();
                int minX = context.blockX() & ~15;
                int minZ = context.blockZ() & ~15;
                if (candidate != null && access.gpunoise$matchesGpuCache(candidate)
                        && candidate.matchesChunk(minX, minZ)) {
                    access.gpunoise$setGpuCache(candidate);
                    cache = candidate;
                }
            }
        }
        if (cache != null) {
            long lookupStart = GpuTerrainProfiler.beginPhase();
            double value = cache.climate(context.blockX(), context.blockY(), context.blockZ(), root);
            if (!Double.isNaN(value)) {
                GpuTerrainProfiler.recordGpuClimate(root, lookupStart);
                GpuTerrainRuntime.markGpuCacheUsed(cache);
                return value;
            }
            GpuTerrainProfiler.recordJavaRootFallback(root);
        }
        return fallback.compute(context);
    }

    @Override
    public DensityFunction mapAll(DensityFunction.Visitor visitor) {
        return visitor.apply(new GpuClimateFunction(owner, initialCache, root, fallback.mapAll(visitor)));
    }

    @Override public double minValue() { return fallback.minValue(); }
    @Override public double maxValue() { return fallback.maxValue(); }
    @Override public KeyDispatchDataCodec<? extends DensityFunction> codec() {
        return DensityFunctions.constant(0.0).codec();
    }
}
