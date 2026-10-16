package dev.gpunoise;

/** Numeric reference for the pressure term used by NoiseBasedAquifer. */
final class GpuAquiferPressure {
    static final int WATER = 1;
    static final int LAVA = 2;
    static final int RECORD_BYTES = 32;

    private GpuAquiferPressure() {}

    static float calculate(int y, int levelA, int levelB, int kindA, int kindB, float barrier) {
        if ((kindA == LAVA && kindB == WATER) || (kindA == WATER && kindB == LAVA)) return 2.0f;

        int levelDistance = Math.abs(levelA - levelB);
        if (levelDistance == 0) return 0.0f;

        double midpointDelta = (y + 0.5) - 0.5 * (levelA + levelB);
        double halfDistance = levelDistance * 0.5;
        double adjustment = halfDistance - Math.abs(midpointDelta);
        if (midpointDelta > 0.0) {
            adjustment = adjustment > 0.0 ? adjustment / 1.5 : adjustment / 2.5;
        } else {
            double shifted = 3.0 + adjustment;
            adjustment = shifted > 0.0 ? shifted / 3.0 : shifted / 10.0;
        }

        double barrierValue = adjustment < -2.0 || adjustment > 2.0 ? 0.0 : barrier;
        return (float) (2.0 * (barrierValue + adjustment));
    }
}
