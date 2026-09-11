package dev.gpunoise;

/** Float reference for the exact operation order used by minecraft_noise.comp. */
final class MinecraftNoiseReference {
    private static final float INPUT_FACTOR = 1.0181269f;
    private static final int[][] GRADIENTS = {
        {1,1,0},{-1,1,0},{1,-1,0},{-1,-1,0},
        {1,0,1},{-1,0,1},{1,0,-1},{-1,0,-1},
        {0,1,1},{0,-1,1},{0,1,-1},{0,-1,-1},
        {1,1,0},{0,-1,1},{-1,1,0},{0,-1,-1}
    };

    private MinecraftNoiseReference() {}

    static float improved(MinecraftNoiseParameters.Improved noise, float x, float y, float z) {
        float px = x + noise.xo();
        float py = y + noise.yo();
        float pz = z + noise.zo();
        int gx = (int)Math.floor(px), gy = (int)Math.floor(py), gz = (int)Math.floor(pz);
        float dx = px - gx, dy = py - gy, dz = pz - gz;
        int pX = perm(noise, gx), qX = perm(noise, gx + 1);
        int p0 = perm(noise, pX + gy), p1 = perm(noise, pX + gy + 1);
        int q0 = perm(noise, qX + gy), q1 = perm(noise, qX + gy + 1);
        float n000 = grad(noise, p0 + gz, dx, dy, dz);
        float n100 = grad(noise, q0 + gz, dx - 1, dy, dz);
        float n010 = grad(noise, p1 + gz, dx, dy - 1, dz);
        float n110 = grad(noise, q1 + gz, dx - 1, dy - 1, dz);
        float n001 = grad(noise, p0 + gz + 1, dx, dy, dz - 1);
        float n101 = grad(noise, q0 + gz + 1, dx - 1, dy, dz - 1);
        float n011 = grad(noise, p1 + gz + 1, dx, dy - 1, dz - 1);
        float n111 = grad(noise, q1 + gz + 1, dx - 1, dy - 1, dz - 1);
        float sx = quintic(dx), sy = quintic(dy), sz = quintic(dz);
        return lerp(sz, lerp(sy, lerp(sx, n000, n100), lerp(sx, n010, n110)),
                lerp(sy, lerp(sx, n001, n101), lerp(sx, n011, n111)));
    }

    static float perlin(MinecraftNoiseParameters.Improved[] octaves, double[] amplitudes,
                        float x, float y, float z) {
        float result = 0.0f;
        float inputScale = 1.0f;
        float valueScale = 1.0f;
        for (int i = 0; i < octaves.length; i++) {
            if (octaves[i] != null && amplitudes[i] != 0.0) {
                result += (float)amplitudes[i] * improved(octaves[i], x * inputScale, y * inputScale, z * inputScale) * valueScale;
            }
            inputScale *= 2.0f;
            valueScale *= 0.5f;
        }
        return result;
    }

    static float normal(MinecraftNoiseParameters.Improved[] first, MinecraftNoiseParameters.Improved[] second,
                        double[] amplitudes, float x, float y, float z, float valueFactor) {
        return (perlin(first, amplitudes, x, y, z)
                + perlin(second, amplitudes, x * INPUT_FACTOR, y * INPUT_FACTOR, z * INPUT_FACTOR)) * valueFactor;
    }

    private static int perm(MinecraftNoiseParameters.Improved noise, int index) {
        return noise.permutationAt(index);
    }
    private static float grad(MinecraftNoiseParameters.Improved noise, int index, float x, float y, float z) {
        int[] g = GRADIENTS[perm(noise, index) & 15];
        return g[0] * x + g[1] * y + g[2] * z;
    }
    private static float quintic(float v) { return v * v * v * (v * (v * 6.0f - 15.0f) + 10.0f); }
    private static float lerp(float t, float a, float b) { return a + t * (b - a); }
}
