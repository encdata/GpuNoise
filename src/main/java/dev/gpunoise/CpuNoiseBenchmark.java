package dev.gpunoise;

/** CPU reference benchmark for the first matched noise workload. */
final class CpuNoiseBenchmark {
    private static final int SEED = 0x4A3B2C1D;

    private CpuNoiseBenchmark() {}

    static Result run(int sampleCount, int iterations) {
        long start = System.nanoTime();
        float checksum = 0.0f;
        for (int iteration = 0; iteration < iterations; iteration++) {
            for (int index = 0; index < sampleCount; index++) {
                int x = index & 1023;
                int z = index >>> 10;
                checksum += NoiseMath.valueNoise(x, z, SEED);
            }
        }
        return new Result(sampleCount, iterations, System.nanoTime() - start, checksum);
    }

    record Result(int sampleCount, int iterations, long elapsedNanos, float checksum) {}
}
