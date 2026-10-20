package dev.gpunoise;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.nio.ByteBuffer;
import java.util.Arrays;

import net.minecraft.world.level.levelgen.PositionalRandomFactory;

/** Exact GPU coordinate dependencies for one chunk-generation task. */
public final class GpuWorldgenDependency {
    private final List<Page> pages;
    private final CoordinateIndex index;
    private volatile CompletableFuture<Void> submitted;

    private GpuWorldgenDependency(List<Page> pages, CoordinateIndex index) {
        this.pages = List.copyOf(pages);
        this.index = index;
    }

    /** Submits every page once. Callers share the same completion future. */
    public CompletionStage<Void> submit() {
        CompletableFuture<Void> existing = submitted;
        if (existing != null) return existing;
        synchronized (this) {
            if (submitted != null) return submitted;
            CompletableFuture<?>[] futures = new CompletableFuture<?>[pages.size()];
            for (int i = 0; i < pages.size(); i++) futures[i] = pages.get(i).submit().toCompletableFuture();
            submitted = CompletableFuture.allOf(futures);
            return submitted;
        }
    }

    /** Returns NaN until completed or when the exact coordinate was not declared. */
    public double valueAt(int root, int x, int y, int z) {
        int sample = index.find(x, y, z);
        if (sample < 0) return Double.NaN;
        GpuTerrainRootsResult roots = pages.get(index.page(sample)).result;
        if (roots == null || root < 0 || root >= roots.roots()) return Double.NaN;
        return roots.valueAt(index.pageSample(sample), root);
    }

    public boolean contains(int x, int y, int z) { return index.find(x, y, z) >= 0; }
    public int samples() { return index.size(); }
    public int pages() { return pages.size(); }

    /**
     * Validates candidate coordinates against this strict dependency and sends
     * bounded nearest-center batches to Vulkan. Each output row contains four
     * center indices, their squared distances, and four reserved words.
     */
    public CompletionStage<int[]> selectNearestAquiferCenters(PositionalRandomFactory factory,
                                                               int[][] blockQueries) {
        if (factory == null || blockQueries == null)
            return CompletableFuture.failedStage(new IllegalArgumentException("missing aquifer query input"));
        final int outputLength;
        try {
            outputLength = Math.multiplyExact(blockQueries.length, GpuAquiferNearestThree.OUTPUT_INTS);
        } catch (ArithmeticException overflow) {
            return CompletableFuture.failedStage(overflow);
        }
        int[] output = new int[outputLength];
        List<NearestAquiferBatch> batches = new ArrayList<>();
        for (int offset = 0; offset < blockQueries.length; offset += GpuAquiferNearestThree.MAX_QUERIES) {
            int batchCount = Math.min(GpuAquiferNearestThree.MAX_QUERIES, blockQueries.length - offset);
            int[][] queries = Arrays.copyOfRange(blockQueries, offset, offset + batchCount);
            int[][][] centers = new int[batchCount][][];
            boolean c2meAquifer = GpuAquiferRuntimeMode.c2meAquiferRewriteEnabled();
            for (int i = 0; i < batchCount; i++) {
                centers[i] = c2meAquifer
                        ? GpuAquiferNearestThree.centersForC2meQuery(factory, queries[i])
                        : GpuAquiferNearestThree.centersForQuery(factory, queries[i]);
                for (int[] center : centers[i]) {
                    if (!contains(center[0], center[1], center[2]))
                        return CompletableFuture.failedStage(new IllegalStateException(
                                "aquifer candidate coordinate is absent from strict GPU dependencies"));
                }
            }
            batches.add(new NearestAquiferBatch(offset, batchCount,
                    GpuAquiferNearestThree.pack(queries, centers)));
        }
        CompletableFuture<?>[] futures = new CompletableFuture<?>[batches.size()];
        for (int i = 0; i < batches.size(); i++) {
            NearestAquiferBatch batch = batches.get(i);
            futures[i] = GpuTerrainRuntime.submitAquiferNearestThree(batch.records, batch.count)
                    .thenAccept(values -> System.arraycopy(values, 0, output,
                            batch.offset * GpuAquiferNearestThree.OUTPUT_INTS, values.length))
                    .toCompletableFuture();
        }
        return CompletableFuture.allOf(futures).thenApply(ignored -> output);
    }

    /** Declares cell and preliminary-surface coordinates used by a chunk. */
    public static Builder forChunk(int minX, int minZ, int minY, int height, int cellWidth, int cellHeight) {
        Builder builder = new Builder();
        int maxY = minY + height;
        for (int y = minY; y <= maxY; y += cellHeight)
            for (int x = minX; x <= minX + 16; x += cellWidth)
                for (int z = minZ; z <= minZ + 16; z += cellWidth) builder.add(x, y, z);
        // NoiseBasedAquifer's random cells extend outside the chunk before
        // its 13 preliminary-surface offsets are applied.
        for (int y = minY; y <= maxY; y += cellHeight)
            for (int x = minX - 64; x <= minX + 56; x += 4)
                for (int z = minZ - 32; z <= minZ + 56; z += 4) builder.add(x, y, z);
        return builder;
    }

    /**
     * Full strict-terrain request. Direct block positions cover barrier and
     * ore roots; transformed positions cover spread/lava; climate receives
     * its native quart coordinates rather than block coordinates.
     */
    public static GpuWorldgenDependency strictForChunk(int minX, int minZ, int minY, int height,
                                                        int cellWidth, int cellHeight) {
        Builder builder = forChunk(minX, minZ, minY, height, cellWidth, cellHeight);
        builder.addBlocks(minX, minY, minZ, 16, height, 16);
        int maxY = minY + height;
        for (int y = minY; y < maxY; y++) {
            for (int x = minX; x < minX + 16; x++) {
                for (int z = minZ; z < minZ + 16; z++) {
                    builder.addSpreadFromBlock(x, y, z);
                    builder.addLavaFromBlock(x, y, z);
                }
            }
        }
        int minQuartX = Math.floorDiv(minX, 4);
        int minQuartY = Math.floorDiv(minY, 4);
        int minQuartZ = Math.floorDiv(minZ, 4);
        int maxQuartY = Math.floorDiv(maxY - 1, 4);
        for (int y = minQuartY; y <= maxQuartY; y++)
            for (int x = minQuartX; x <= minQuartX + 4; x++)
                for (int z = minQuartZ; z <= minQuartZ + 4; z++) builder.add(x, y, z);
        return builder.build();
    }

    /** Starts a strict request which an aquifer planner can enrich before submit. */
    public static Builder strictBuilderForChunk(int minX, int minZ, int minY, int height,
                                                int cellWidth, int cellHeight) {
        Builder builder = forChunk(minX, minZ, minY, height, cellWidth, cellHeight);
        builder.addBlocks(minX, minY, minZ, 16, height, 16);
        int maxY = minY + height;
        for (int y = minY; y < maxY; y++)
            for (int x = minX; x < minX + 16; x++)
                for (int z = minZ; z < minZ + 16; z++) {
                    builder.addSpreadFromBlock(x, y, z);
                    builder.addLavaFromBlock(x, y, z);
                }
        int minQuartX = Math.floorDiv(minX, 4), minQuartY = Math.floorDiv(minY, 4), minQuartZ = Math.floorDiv(minZ, 4);
        int maxQuartY = Math.floorDiv(maxY - 1, 4);
        for (int y = minQuartY; y <= maxQuartY; y++)
            for (int x = minQuartX; x <= minQuartX + 4; x++)
                for (int z = minQuartZ; z <= minQuartZ + 4; z++) builder.add(x, y, z);
        return builder;
    }

    /** Adds the exact random aquifer locations vanilla will cache for this chunk. */
    public static void addAquiferCandidates(Builder builder, PositionalRandomFactory factory,
                                            int minX, int minZ, int minY, int height) {
        int minGridX = Math.floorDiv(minX, 16) - 1;
        int maxGridX = Math.floorDiv(minX + 15, 16) + 1;
        int minGridY = Math.floorDiv(minY, 12) - 1;
        int maxGridY = Math.floorDiv(minY + height, 12) + 1;
        int minGridZ = Math.floorDiv(minZ, 16) - 1;
        int maxGridZ = Math.floorDiv(minZ + 15, 16) + 1;
        for (int gridY = minGridY; gridY <= maxGridY; gridY++) {
            for (int gridZ = minGridZ; gridZ <= maxGridZ; gridZ++) {
                for (int gridX = minGridX; gridX <= maxGridX; gridX++) {
                    int[] center = GpuAquiferNearestThree.centerAt(factory, gridX, gridY, gridZ);
                    builder.add(center[0], center[1], center[2]);
                    builder.addSpreadFromBlock(center[0], center[1], center[2]);
                    builder.addLavaFromBlock(center[0], center[1], center[2]);
                }
            }
        }
    }

    public static Coordinate spreadCoordinate(int x, int y, int z) {
        return new Coordinate(Math.floorDiv(x, 16), Math.floorDiv(y, 40), Math.floorDiv(z, 16));
    }

    public static Coordinate lavaCoordinate(int x, int y, int z) {
        return new Coordinate(Math.floorDiv(x, 64), Math.floorDiv(y, 40), Math.floorDiv(z, 64));
    }

    public static final class Builder {
        private final List<PageBuilder> pages = new ArrayList<>();
        private final CoordinateIndex index = new CoordinateIndex();
        private boolean built;

        public Builder add(int x, int y, int z) {
            if (built) throw new IllegalStateException("GPU worldgen dependency builder is already built");
            if (index.find(x, y, z) >= 0) return this;
            PageBuilder page = pages.isEmpty() || pages.getLast().size() == PersistentVulkanNormalNoiseEngine.CAPACITY
                    ? addPage() : pages.getLast();
            int pageIndex = pages.size() - 1;
            int pageSample = page.add(x, y, z);
            index.add(x, y, z, pageIndex, pageSample);
            return this;
        }

        /** Adds a full block-resolution box for barrier and vein roots. */
        public Builder addBlocks(int minX, int minY, int minZ, int width, int height, int depth) {
            for (int y = minY; y < minY + height; y++)
                for (int x = minX; x < minX + width; x++)
                    for (int z = minZ; z < minZ + depth; z++) add(x, y, z);
            return this;
        }

        public Builder addSpreadFromBlock(int x, int y, int z) {
            Coordinate point = spreadCoordinate(x, y, z);
            return add(point.x, point.y, point.z);
        }

        public Builder addLavaFromBlock(int x, int y, int z) {
            Coordinate point = lavaCoordinate(x, y, z);
            return add(point.x, point.y, point.z);
        }

        public GpuWorldgenDependency build() {
            if (built) throw new IllegalStateException("GPU worldgen dependency builder is already built");
            if (pages.isEmpty()) throw new IllegalStateException("GPU worldgen dependency has no samples");
            List<Page> result = new ArrayList<>(pages.size());
            for (PageBuilder page : pages) result.add(page.build());
            built = true;
            return new GpuWorldgenDependency(result, index);
        }

        private PageBuilder addPage() {
            PageBuilder page = new PageBuilder();
            pages.add(page);
            return page;
        }
    }

    private static final class PageBuilder {
        private int[] x = new int[64];
        private int[] y = new int[64];
        private int[] z = new int[64];
        private int size;

        int add(int blockX, int blockY, int blockZ) {
            if (size == x.length) {
                int capacity = Math.min(PersistentVulkanNormalNoiseEngine.CAPACITY,
                        Math.max(size + 1, size + (size >> 1)));
                x = Arrays.copyOf(x, capacity);
                y = Arrays.copyOf(y, capacity);
                z = Arrays.copyOf(z, capacity);
            }
            x[size] = blockX;
            y[size] = blockY;
            z[size] = blockZ;
            return size++;
        }

        int size() { return size; }

        Page build() {
            float[] sampleX = new float[size], sampleY = new float[size], sampleZ = new float[size];
            for (int i = 0; i < size; i++) {
                sampleX[i] = x[i];
                sampleY[i] = y[i];
                sampleZ[i] = z[i];
            }
            return new Page(sampleX, sampleY, sampleZ);
        }
    }

    /** Primitive tuple index for high-volume per-chunk coordinate dependencies. */
    private static final class CoordinateIndex {
        private static final int INITIAL_TABLE_CAPACITY = 256;
        private int[] slots = new int[INITIAL_TABLE_CAPACITY];
        private int[] x = new int[128];
        private int[] y = new int[128];
        private int[] z = new int[128];
        private int[] pages = new int[128];
        private int[] pageSamples = new int[128];
        private int size;

        int find(int blockX, int blockY, int blockZ) {
            int slot = hash(blockX, blockY, blockZ) & (slots.length - 1);
            while (true) {
                int entry = slots[slot] - 1;
                if (entry < 0) return -1;
                if (x[entry] == blockX && y[entry] == blockY && z[entry] == blockZ) return entry;
                slot = (slot + 1) & (slots.length - 1);
            }
        }

        void add(int blockX, int blockY, int blockZ, int page, int sample) {
            if ((size + 1) * 10 >= slots.length * 7) resizeTable();
            if (size == x.length) resizeEntries();
            int slot = hash(blockX, blockY, blockZ) & (slots.length - 1);
            while (slots[slot] != 0) slot = (slot + 1) & (slots.length - 1);
            x[size] = blockX;
            y[size] = blockY;
            z[size] = blockZ;
            pages[size] = page;
            pageSamples[size] = sample;
            slots[slot] = ++size;
        }

        int page(int entry) { return pages[entry]; }
        int pageSample(int entry) { return pageSamples[entry]; }
        int size() { return size; }

        private void resizeEntries() {
            int capacity = Math.max(size + 1, size + (size >> 1));
            x = Arrays.copyOf(x, capacity);
            y = Arrays.copyOf(y, capacity);
            z = Arrays.copyOf(z, capacity);
            pages = Arrays.copyOf(pages, capacity);
            pageSamples = Arrays.copyOf(pageSamples, capacity);
        }

        private void resizeTable() {
            int[] previous = slots;
            if (previous.length >= 1 << 30) throw new IllegalStateException("GPU dependency index capacity exceeded");
            slots = new int[previous.length << 1];
            int mask = slots.length - 1;
            for (int encoded : previous) {
                if (encoded == 0) continue;
                int entry = encoded - 1;
                int slot = hash(x[entry], y[entry], z[entry]) & mask;
                while (slots[slot] != 0) slot = (slot + 1) & mask;
                slots[slot] = encoded;
            }
        }

        private static int hash(int x, int y, int z) {
            int value = x * 0x9E3779B9;
            value = Integer.rotateLeft(value ^ y * 0x85EBCA6B, 13) * 0xC2B2AE35;
            value = Integer.rotateLeft(value ^ z * 0x27D4EB2F, 15) * 0x165667B1;
            value ^= value >>> 16;
            value *= 0x7FEB352D;
            value ^= value >>> 15;
            value *= 0x846CA68B;
            return value ^ (value >>> 16);
        }
    }

    private static final class Page {
        final float[] x, y, z;
        volatile GpuTerrainRootsResult result;
        volatile CompletableFuture<Void> submitted;
        Page(float[] x, float[] y, float[] z) { this.x = x; this.y = y; this.z = z; }
        CompletionStage<Void> submit() {
            CompletableFuture<Void> existing = submitted;
            if (existing != null) return existing;
            synchronized (this) {
                if (submitted != null) return submitted;
                submitted = GpuTerrainRuntime.submitTerrainRoots(x, y, z)
                        .thenAccept(roots -> result = roots).toCompletableFuture();
                return submitted;
            }
        }
    }

    private record NearestAquiferBatch(int offset, int count, ByteBuffer records) { }
    public record Coordinate(int x, int y, int z) { }
}
