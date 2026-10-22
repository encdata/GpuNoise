package dev.gpunoise;

import com.mojang.datafixers.util.Pair;
import dev.gpunoise.mixin.MultiNoiseBiomeSourceAccess;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Compact, immutable serialization of a GPU-searchable multi-noise parameter tree. */
public final class GpuBiomeSearchTree {
    public static final int NODE_BYTES = 48;
    public static final int BRANCH = 1;
    private static final int DIMENSIONS = 7;
    /** Must stay within PersistentVulkanNormalNoiseEngine's persistent tree upload buffer. */
    private static final long MAX_PACKED_BYTES = 8L * 1024L * 1024L;

    private final int[] words;
    private final ByteBuffer packed;
    private final int nodeCount;
    private final Holder<Biome>[] biomes;
    private final int maxDepth;
    private final MultiNoiseBiomeSource source;

    private GpuBiomeSearchTree(int[] words, int nodeCount, Holder<Biome>[] biomes, int maxDepth,
                               MultiNoiseBiomeSource source) {
        this.words = words;
        this.nodeCount = nodeCount;
        this.biomes = biomes;
        this.maxDepth = maxDepth;
        this.source = source;
        ByteBuffer data = ByteBuffer.allocateDirect(words.length * Integer.BYTES).order(ByteOrder.nativeOrder());
        for (int word : words) data.putInt(word);
        this.packed = data.flip().asReadOnlyBuffer().order(ByteOrder.nativeOrder());
    }

    /** Returns null for non-multi-noise sources or an incomplete/unsupported tree. */
    @SuppressWarnings("unchecked")
    public static GpuBiomeSearchTree compile(MultiNoiseBiomeSource source) {
        if (source == null) return null;
        try {
            Climate.ParameterList<Holder<Biome>> entries =
                    ((MultiNoiseBiomeSourceAccess) source).gpunoise$parameters();
            List<Pair<Climate.ParameterPoint, Holder<Biome>>> values = entries.values();
            if (values.isEmpty()) return null;
            Map<Holder<?>, Integer> biomeIds = new HashMap<>();
            List<Holder<Biome>> biomeList = new ArrayList<>();
            for (Pair<Climate.ParameterPoint, Holder<Biome>> entry : values) {
                if (!biomeIds.containsKey(entry.getSecond())) {
                    biomeIds.put(entry.getSecond(), biomeList.size());
                    biomeList.add(entry.getSecond());
                }
            }
            TreeBuilder builder = new TreeBuilder();
            for (Pair<Climate.ParameterPoint, Holder<Biome>> entry : values) {
                builder.add(entry.getFirst(), biomeIds.get(entry.getSecond()));
            }
            builder.build();
            if (builder.nodes.size() < 2 || builder.nodes.size() > 1_000_000) return null;
            int nodeCount = builder.nodes.size();
            long childWordCount = 0L;
            for (PackedNode node : builder.nodes) {
                if (node != null) childWordCount += node.children.length;
            }
            int nodeWords = NODE_BYTES / Integer.BYTES;
            long packedWordCount = (long) nodeCount * nodeWords + childWordCount;
            if (packedWordCount * Integer.BYTES > MAX_PACKED_BYTES) {
                GpuNoise.LOGGER.info("GPU multi-noise tree for {} needs {} bytes; device upload limit is {} bytes, keeping Java biome search.",
                        source.getClass().getName(), packedWordCount * Integer.BYTES, MAX_PACKED_BYTES);
                return null;
            }
            int[] packed = new int[(int) packedWordCount];
            int nextChildWord = nodeCount * nodeWords;
            for (int i = 1; i < builder.nodes.size(); i++) {
                PackedNode node = builder.nodes.get(i);
                int base = i * nodeWords;
                packed[base] = node.branch ? BRANCH : 0;
                packed[base + 1] = node.branch ? nextChildWord : 0;
                packed[base + 2] = node.children.length;
                packed[base + 3] = node.biomeId;
                for (int dim = 0; dim < DIMENSIONS; dim++) {
                    packed[base + 4 + dim] = (node.min[dim] & 0xffff)
                            | ((node.max[dim] & 0xffff) << 16);
                }
                for (int child : node.children) packed[nextChildWord++] = child;
            }
            return new GpuBiomeSearchTree(packed, nodeCount,
                    biomeList.toArray(Holder[]::new), builder.maxDepth, source);
        } catch (RuntimeException | LinkageError unsupported) {
            GpuNoise.LOGGER.warn("GPU multi-noise tree serialization rejected {}: {}",
                    source.getClass().getName(), unsupported.toString());
            return null;
        }
    }

    public int nodeCount() { return nodeCount; }
    public int biomeCount() { return biomes.length; }
    public int maxDepth() { return maxDepth; }
    public Holder<Biome> biome(int id) { return id >= 0 && id < biomes.length ? biomes[id] : null; }

    public boolean matchesVanilla(long[] target, int offset, int biomeId) {
        if (target == null || offset < 0 || offset + 6 > target.length) return false;
        Holder<Biome> gpuBiome = biome(biomeId);
        if (gpuBiome == null) return false;
        Climate.TargetPoint point = new Climate.TargetPoint(target[offset], target[offset + 1],
                target[offset + 2], target[offset + 3], target[offset + 4], target[offset + 5]);
        return gpuBiome.equals(source.getNoiseBiome(point));
    }

    public ByteBuffer packedNodes() {
        return packed.duplicate().order(ByteOrder.nativeOrder());
    }

    /** Packs OCL's signed-short climate ABI (six values and a zero offset). */
    public static ByteBuffer packTarget(double temperature, double humidity, double continents,
                                        double erosion, double depth, double weirdness) {
        ByteBuffer result = ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder());
        putTarget(result, temperature, humidity, continents, erosion, depth, weirdness);
        return result.flip();
    }

    static void putTarget(ByteBuffer target, double temperature, double humidity, double continents,
                          double erosion, double depth, double weirdness) {
        if (target.remaining() < 16) throw new IllegalArgumentException("target buffer is too small");
        putClimate(target, temperature);
        putClimate(target, humidity);
        putClimate(target, continents);
        putClimate(target, erosion);
        putClimate(target, depth);
        putClimate(target, weirdness);
        target.putShort((short) 0).putShort((short) 0);
    }

    private static void putClimate(ByteBuffer target, double value) {
        target.putShort(quantizeClimate(value));
    }

    static short quantizeClimate(double value) {
        if (!Double.isFinite(value)) throw new IllegalArgumentException("non-finite climate target");
        int scaled = (int) ((float) value * 10000.0f);
        return (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, scaled));
    }

    /** CPU implementation of the GPU traversal, used by parity probes. */
    public int search(short[] target) {
        if (target == null || target.length != DIMENSIONS) throw new IllegalArgumentException("seven climate values required");
        int rootBase = NODE_BYTES / Integer.BYTES;
        if (words[rootBase] != BRANCH) {
            return words[rootBase + 3];
        }
        Search search = new Search(target);
        search.visit(1, 1);
        return search.biomeId;
    }

    private final class Search {
        private final short[] target;
        private long best = Long.MAX_VALUE;
        private int biomeId = -1;

        private Search(short[] target) { this.target = target; }

        private void visit(int nodeIndex, int depth) {
            if (depth > maxDepth + 1 || nodeIndex <= 0 || nodeIndex >= nodeCount()) return;
            int base = nodeIndex * (NODE_BYTES / Integer.BYTES);
            long distance = distance(base);
            if (distance >= best) return;
            if (words[base] != BRANCH) {
                best = distance;
                biomeId = words[base + 3];
                return;
            }
            int start = words[base + 1];
            int count = words[base + 2];
            for (int i = 0; i < count; i++) visit(words[start + i], depth + 1);
        }

        private long distance(int base) {
            long sum = 0L;
            for (int dim = 0; dim < DIMENSIONS; dim++) {
                int packed = words[base + 4 + dim];
                int min = (short) packed;
                int max = (short) (packed >>> 16);
                int delta = target[dim] < min ? min - target[dim]
                        : target[dim] > max ? target[dim] - max : 0;
                sum += (long) delta * delta;
            }
            return sum;
        }
    }

    private static final class TreeBuilder {
        private final List<TreeEntry> entries = new ArrayList<>();
        private final List<PackedNode> nodes = new ArrayList<>();
        private int maxDepth;

        private TreeBuilder() {
            nodes.add(null); // OCL's tree uses one-based node offsets.
        }

        private void add(Climate.ParameterPoint point, int biomeId) {
            List<Climate.Parameter> parameters = List.of(point.temperature(), point.humidity(),
                    point.continentalness(), point.erosion(), point.depth(), point.weirdness());
            short[] bounds = new short[DIMENSIONS * 2];
            for (int dim = 0; dim < parameters.size(); dim++) {
                bounds[dim] = quantize(parameters.get(dim).min());
                bounds[DIMENSIONS + dim] = quantize(parameters.get(dim).max());
            }
            bounds[DIMENSIONS - 1] = quantize(point.offset());
            bounds[DIMENSIONS * 2 - 1] = bounds[DIMENSIONS - 1];
            entries.add(new TreeEntry(bounds, biomeId));
        }

        private void build() {
            if (entries.isEmpty()) throw new IllegalArgumentException("empty climate parameter list");
            append(new ArrayList<>(entries), 1);
        }

        private int append(List<TreeEntry> subset, int depth) {
            maxDepth = Math.max(maxDepth, depth);
            PackedNode node = new PackedNode(unionBounds(subset));
            int index = nodes.size();
            nodes.add(node);
            if (subset.size() == 1) {
                node.biomeId = subset.get(0).biomeId;
            } else {
                int splitDim = widestDimension(subset);
                subset.sort(Comparator.comparingInt(entry -> center(entry.bounds, splitDim)));
                int middle = subset.size() / 2;
                List<TreeEntry> left = new ArrayList<>(subset.subList(0, middle));
                List<TreeEntry> right = new ArrayList<>(subset.subList(middle, subset.size()));
                node.branch = true;
                node.children = new int[]{append(left, depth + 1), append(right, depth + 1)};
            }
            return index;
        }

        private static short[] unionBounds(List<TreeEntry> entries) {
            short[] result = new short[DIMENSIONS * 2];
            java.util.Arrays.fill(result, 0, DIMENSIONS, Short.MAX_VALUE);
            java.util.Arrays.fill(result, DIMENSIONS, DIMENSIONS * 2, Short.MIN_VALUE);
            for (TreeEntry entry : entries) {
                for (int dim = 0; dim < DIMENSIONS; dim++) {
                    result[dim] = (short) Math.min(result[dim], entry.bounds[dim]);
                    result[DIMENSIONS + dim] = (short) Math.max(result[DIMENSIONS + dim], entry.bounds[DIMENSIONS + dim]);
                }
            }
            return result;
        }

        private static int widestDimension(List<TreeEntry> entries) {
            int best = 0;
            int spread = -1;
            for (int dim = 0; dim < DIMENSIONS; dim++) {
                int min = Short.MAX_VALUE;
                int max = Short.MIN_VALUE;
                for (TreeEntry entry : entries) {
                    min = Math.min(min, center(entry.bounds, dim));
                    max = Math.max(max, center(entry.bounds, dim));
                }
                if (max - min > spread) { best = dim; spread = max - min; }
            }
            return best;
        }

        private static int center(short[] bounds, int dim) {
            return ((int) bounds[dim] + bounds[DIMENSIONS + dim]) / 2;
        }

        private static short quantize(long coordinate) {
            float climate = Climate.unquantizeCoord(coordinate);
            int scaled = (int) (climate * 10000.0f);
            return (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, scaled));
        }
    }

    private static final class TreeEntry {
        private final short[] bounds;
        private final int biomeId;
        private TreeEntry(short[] bounds, int biomeId) { this.bounds = bounds; this.biomeId = biomeId; }
    }

    private static final class PackedNode {
        private final short[] min;
        private final short[] max;
        private int[] children = new int[0];
        private int biomeId = -1;
        private boolean branch;

        private PackedNode(short[] bounds) {
            this.min = new short[DIMENSIONS];
            this.max = new short[DIMENSIONS];
            System.arraycopy(bounds, 0, min, 0, DIMENSIONS);
            System.arraycopy(bounds, DIMENSIONS, max, 0, DIMENSIONS);
        }
    }
}
