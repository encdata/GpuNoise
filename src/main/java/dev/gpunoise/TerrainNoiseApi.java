package dev.gpunoise;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Integration boundary between Minecraft terrain generation and a GPU noise provider.
 * The provider owns Vulkan resources; callers only exchange immutable batches.
 */
public final class TerrainNoiseApi {
    public static final int MAX_CHUNKS_PER_BATCH = 100;

    private static volatile Provider provider;

    private TerrainNoiseApi() {}

    /** Installs the real server-side provider. Installing twice is an error. */
    public static synchronized void install(Provider newProvider) {
        Objects.requireNonNull(newProvider, "newProvider");
        if (provider != null) throw new IllegalStateException("GPU terrain provider already installed");
        provider = newProvider;
    }

    public static boolean isAvailable() {
        return provider != null;
    }

    /** Submits work to the installed GPU provider; it never fabricates CPU results. */
    public static CompletionStage<Result> submit(Request request) {
        Provider current = provider;
        if (current == null) {
            return CompletableFuture.failedStage(new IllegalStateException("No GPU terrain provider is installed"));
        }
        return current.submit(Objects.requireNonNull(request, "request"));
    }

    public interface Provider {
        CompletionStage<Result> submit(Request request);
    }

    public record Chunk(int x, int z) {}

    /**
     * A flattened sample batch. Every field uses sampleCount entries per chunk,
     * in the same chunk order as {@link #chunks()}.
     */
    public static final class Request {
        private final long worldSeed;
        private final List<Chunk> chunks;
        private final int samplesPerChunk;
        private final int outputSamplesPerChunk;
        private final int[] sampleX;
        private final int[] sampleY;
        private final int[] sampleZ;

        public Request(long worldSeed, List<Chunk> chunks, int samplesPerChunk,
                       int[] sampleX, int[] sampleY, int[] sampleZ) {
            this(worldSeed, chunks, samplesPerChunk, samplesPerChunk, sampleX, sampleY, sampleZ);
        }

        /** Internal transform request where GPU input and output grids differ. */
        Request(long worldSeed, List<Chunk> chunks, int inputSamplesPerChunk, int outputSamplesPerChunk,
                int[] sampleX, int[] sampleY, int[] sampleZ) {
            this.worldSeed = worldSeed;
            this.chunks = List.copyOf(chunks);
            this.samplesPerChunk = inputSamplesPerChunk;
            this.outputSamplesPerChunk = outputSamplesPerChunk;
            this.sampleX = sampleX.clone();
            this.sampleY = sampleY.clone();
            this.sampleZ = sampleZ.clone();
            if (this.chunks.isEmpty() || this.chunks.size() > MAX_CHUNKS_PER_BATCH) {
                throw new IllegalArgumentException("batch must contain 1-100 chunks");
            }
            if (inputSamplesPerChunk <= 0 || outputSamplesPerChunk <= 0) throw new IllegalArgumentException("sample counts must be positive");
            int expected = Math.multiplyExact(this.chunks.size(), inputSamplesPerChunk);
            if (this.sampleX.length != expected || this.sampleY.length != expected || this.sampleZ.length != expected) {
                throw new IllegalArgumentException("sample arrays must contain chunks * samplesPerChunk entries");
            }
        }

        public long worldSeed() { return worldSeed; }
        public List<Chunk> chunks() { return chunks; }
        public int samplesPerChunk() { return samplesPerChunk; }
        int outputSamplesPerChunk() { return outputSamplesPerChunk; }
        public int[] sampleX() { return sampleX.clone(); }
        public int[] sampleY() { return sampleY.clone(); }
        public int[] sampleZ() { return sampleZ.clone(); }
        int[] sampleXRaw() { return sampleX; }
        int[] sampleYRaw() { return sampleY; }
        int[] sampleZRaw() { return sampleZ; }
    }

    /** GPU outputs required by terrain, caves, aquifers, and climate routing. */
    public static final class Result {
        private final Request request;
        private final float[] terrainDensity;
        private final float[] caveDensity;
        private final float[] aquiferPressure;
        private final float[] continentalness;
        private final float[] erosion;
        private final float[] weirdness;
        private final float[] temperature;
        private final float[] humidity;
        private final int offset;
        private final int length;

        public Result(Request request, float[] terrainDensity, float[] caveDensity,
                      float[] aquiferPressure, float[] continentalness, float[] erosion,
                      float[] weirdness, float[] temperature, float[] humidity) {
            this.request = Objects.requireNonNull(request, "request");
            int count = request.chunks().size() * request.outputSamplesPerChunk();
            this.terrainDensity = copy("terrainDensity", terrainDensity, count);
            this.caveDensity = copy("caveDensity", caveDensity, count);
            this.aquiferPressure = copy("aquiferPressure", aquiferPressure, count);
            this.continentalness = copy("continentalness", continentalness, count);
            this.erosion = copy("erosion", erosion, count);
            this.weirdness = copy("weirdness", weirdness, count);
            this.temperature = copy("temperature", temperature, count);
            this.humidity = copy("humidity", humidity, count);
            this.offset = 0;
            this.length = count;
        }

        static Result owned(Request request, float[] terrainDensity, float[] caveDensity,
                            float[] aquiferPressure, float[] continentalness, float[] erosion,
                            float[] weirdness, float[] temperature, float[] humidity) {
            return new Result(request, terrainDensity, caveDensity, aquiferPressure,
                    continentalness, erosion, weirdness, temperature, humidity, false);
        }

        private Result(Request request, float[] terrainDensity, float[] caveDensity,
                       float[] aquiferPressure, float[] continentalness, float[] erosion,
                       float[] weirdness, float[] temperature, float[] humidity, boolean copy) {
            this.request = Objects.requireNonNull(request, "request");
            int count = request.chunks().size() * request.outputSamplesPerChunk();
            this.terrainDensity = copy ? copy("terrainDensity", terrainDensity, count) : owned("terrainDensity", terrainDensity, count);
            this.caveDensity = copy ? copy("caveDensity", caveDensity, count) : owned("caveDensity", caveDensity, count);
            this.aquiferPressure = copy ? copy("aquiferPressure", aquiferPressure, count) : owned("aquiferPressure", aquiferPressure, count);
            this.continentalness = copy ? copy("continentalness", continentalness, count) : owned("continentalness", continentalness, count);
            this.erosion = copy ? copy("erosion", erosion, count) : owned("erosion", erosion, count);
            this.weirdness = copy ? copy("weirdness", weirdness, count) : owned("weirdness", weirdness, count);
            this.temperature = copy ? copy("temperature", temperature, count) : owned("temperature", temperature, count);
            this.humidity = copy ? copy("humidity", humidity, count) : owned("humidity", humidity, count);
            this.offset = 0;
            this.length = count;
        }

        private Result(Request request, Result source, int offset, int length) {
            this.request = Objects.requireNonNull(request, "request");
            if (offset < 0 || length < 0 || offset > source.length - length
                    || length != Math.multiplyExact(request.chunks().size(), request.outputSamplesPerChunk())) {
                throw new IllegalArgumentException("invalid terrain result view");
            }
            this.terrainDensity = source.terrainDensity;
            this.caveDensity = source.caveDensity;
            this.aquiferPressure = source.aquiferPressure;
            this.continentalness = source.continentalness;
            this.erosion = source.erosion;
            this.weirdness = source.weirdness;
            this.temperature = source.temperature;
            this.humidity = source.humidity;
            this.offset = source.offset + offset;
            this.length = length;
        }

        Result slice(Request request, int relativeOffset, int count) {
            if (relativeOffset < 0 || count < 0 || relativeOffset > length - count)
                throw new IndexOutOfBoundsException("terrain result slice outside batch");
            return new Result(request, this, relativeOffset, count);
        }

        private static float[] owned(String name, float[] values, int expected) {
            Objects.requireNonNull(values, name);
            if (values.length != expected) throw new IllegalArgumentException(name + " has incorrect length");
            return values;
        }

        private static float[] copy(String name, float[] values, int expected) {
            Objects.requireNonNull(values, name);
            if (values.length != expected) throw new IllegalArgumentException(name + " has incorrect length");
            return Arrays.copyOf(values, values.length);
        }

        public Request request() { return request; }
        public float[] terrainDensity() { return copyRange(terrainDensity); }
        float terrainDensityAt(int index) { return terrainDensity[offset + checkedIndex(index)]; }
        public float[] caveDensity() { return copyRange(caveDensity); }
        public float[] aquiferPressure() { return copyRange(aquiferPressure); }
        public float[] continentalness() { return copyRange(continentalness); }
        public float[] erosion() { return copyRange(erosion); }
        public float[] weirdness() { return copyRange(weirdness); }
        public float[] temperature() { return copyRange(temperature); }
        public float[] humidity() { return copyRange(humidity); }
        private int checkedIndex(int index) {
            if (index < 0 || index >= length) throw new IndexOutOfBoundsException(index);
            return index;
        }

        private float[] copyRange(float[] values) {
            return Arrays.copyOfRange(values, offset, offset + length);
        }
    }
}
