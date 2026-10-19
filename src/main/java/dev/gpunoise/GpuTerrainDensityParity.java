package dev.gpunoise;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.DoubleAccumulator;

/** CI-only differential trace for the density values that actually reach terrain decisions. */
public final class GpuTerrainDensityParity {
    private static final boolean ENABLED = Boolean.getBoolean("gpunoise.verifyTerrainDensity");
    private static final Integer TARGET_CHUNK_X = Integer.getInteger("gpunoise.verifyTerrainDensityChunkX");
    private static final Integer TARGET_CHUNK_Z = Integer.getInteger("gpunoise.verifyTerrainDensityChunkZ");
    private static final AtomicLong CHUNKS = new AtomicLong();
    private static final AtomicLong SAMPLES = new AtomicLong();
    private static final AtomicLong EXACT_MISMATCHES = new AtomicLong();
    private static final AtomicLong SIGN_MISMATCHES = new AtomicLong();
    private static final AtomicLong UNSAFE_SIGN_MISMATCHES = new AtomicLong();
    private static final AtomicLong NEAR_ZERO_SAMPLES = new AtomicLong();
    private static final DoubleAccumulator MAX_ABS_ERROR = new DoubleAccumulator(Math::max, 0.0);
    private static final AtomicReference<String> FIRST_SIGN_MISMATCH = new AtomicReference<>();
    private static final AtomicReference<String> FIRST_NEAR_ZERO = new AtomicReference<>();
    private static final AtomicBoolean LOGGED_DISABLED = new AtomicBoolean();

    private GpuTerrainDensityParity() {}

    public static boolean enabled() {
        return ENABLED || (TARGET_CHUNK_X != null && TARGET_CHUNK_Z != null);
    }

    public static boolean shouldCompare(int x, int z) {
        if (!enabled()) return false;
        return (TARGET_CHUNK_X == null || TARGET_CHUNK_X == Math.floorDiv(x, 16))
                && (TARGET_CHUNK_Z == null || TARGET_CHUNK_Z == Math.floorDiv(z, 16));
    }

    public static void record(int x, int y, int z, double cpu, double gpu) {
        if (!shouldCompare(x, z)) return;
        SAMPLES.incrementAndGet();
        if (Double.doubleToLongBits(cpu) != Double.doubleToLongBits(gpu)) {
            EXACT_MISMATCHES.incrementAndGet();
            if (Double.isFinite(cpu) && Double.isFinite(gpu)) {
                MAX_ABS_ERROR.accumulate(Math.abs(cpu - gpu));
            }
        }
        if ((cpu > 0.0) != (gpu > 0.0)) {
            SIGN_MISMATCHES.incrementAndGet();
            FIRST_SIGN_MISMATCH.compareAndSet(null, describe(x, y, z, cpu, gpu));
            if (!GpuAquiferSubstance.nearDensityDecisionThreshold(gpu)) {
                UNSAFE_SIGN_MISMATCHES.incrementAndGet();
            }
        }
        if (Double.isFinite(cpu) && Double.isFinite(gpu)
                && (Math.abs(cpu) <= 0.01 || Math.abs(gpu) <= 0.01)) {
            NEAR_ZERO_SAMPLES.incrementAndGet();
            FIRST_NEAR_ZERO.compareAndSet(null, describe(x, y, z, cpu, gpu));
        }
    }

    public static void reportChunk(int chunkX, int chunkZ) {
        if (!enabled()) {
            if (LOGGED_DISABLED.compareAndSet(false, true)) {
                GpuNoise.LOGGER.info("GPU terrain density differential trace disabled");
            }
            return;
        }
        if ((TARGET_CHUNK_X != null && TARGET_CHUNK_X != chunkX)
                || (TARGET_CHUNK_Z != null && TARGET_CHUNK_Z != chunkZ)) return;
        long chunks = CHUNKS.incrementAndGet();
        GpuNoise.LOGGER.info(
                "GPU terrain density differential: chunks={}, lastChunk=({},{}), samples={}, "
                        + "exactMismatches={}, signMismatches={}, unsafeSignMismatches={}, "
                        + "nearZeroSamples={}, maxAbsError={}, "
                        + "firstSignMismatch={}, firstNearZero={}",
                chunks, chunkX, chunkZ, SAMPLES.get(), EXACT_MISMATCHES.get(), SIGN_MISMATCHES.get(),
                UNSAFE_SIGN_MISMATCHES.get(),
                NEAR_ZERO_SAMPLES.get(), MAX_ABS_ERROR.get(),
                valueOrNone(FIRST_SIGN_MISMATCH.get()), valueOrNone(FIRST_NEAR_ZERO.get()));
    }

    private static String describe(int x, int y, int z, double cpu, double gpu) {
        return "(" + x + "," + y + "," + z + "):cpu=" + cpu + ",gpu=" + gpu;
    }

    private static String valueOrNone(String value) {
        return value == null ? "none" : value;
    }
}
