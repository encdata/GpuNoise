package dev.gpunoise;

/** Comparison rules shared by startup and terrain-grid differential checks. */
final class GpuTerrainParity {
    private GpuTerrainParity() {}

    /**
     * Returns a finite absolute error for matching values and infinity for a
     * mismatch in NaN or infinity classification. A NaN must never disappear
     * from a max-error reduction and accidentally make a graph look verified.
     */
    static float error(float actual, float expected) {
        if (Float.isNaN(actual) || Float.isNaN(expected)) {
            return Float.isNaN(actual) && Float.isNaN(expected) ? 0.0f : Float.POSITIVE_INFINITY;
        }
        if (actual == expected) return 0.0f;
        if (!Float.isFinite(actual) || !Float.isFinite(expected)) return Float.POSITIVE_INFINITY;
        return Math.abs(actual - expected);
    }

    /** Fills a deterministic 8x8x8 set spanning negative, boundary and positive coordinates. */
    static void fillStartupSamples(float[] x, float[] y, float[] z) {
        if (x.length != 512 || y.length != 512 || z.length != 512) {
            throw new IllegalArgumentException("startup terrain parity requires exactly 512 samples");
        }
        int[] horizontal = {-256, -128, -32, -1, 0, 1, 32, 256};
        int[] vertical = {-128, -64, -1, 0, 1, 64, 128, 256};
        int sample = 0;
        for (int iy = 0; iy < vertical.length; iy++) {
            for (int ix = 0; ix < horizontal.length; ix++) {
                for (int iz = 0; iz < horizontal.length; iz++) {
                    x[sample] = horizontal[ix];
                    y[sample] = vertical[iy];
                    z[sample] = horizontal[iz];
                    sample++;
                }
            }
        }
    }
}
