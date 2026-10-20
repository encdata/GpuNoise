package dev.gpunoise;

import com.mojang.datafixers.util.Pair;
import dev.gpunoise.mixin.ClimateParameterListAccess;
import dev.gpunoise.mixin.ClimateRTreeAccess;
import dev.gpunoise.mixin.ClimateRTreeLeafAccess;
import dev.gpunoise.mixin.ClimateRTreeNodeAccess;
import dev.gpunoise.mixin.ClimateRTreeSubTreeAccess;
import dev.gpunoise.mixin.MultiNoiseBiomeSourceAccess;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** Compact, immutable serialization of Minecraft's live multi-noise search tree. */
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
            Object root = ((ClimateRTreeAccess) (Object)
                    ((ClimateParameterListAccess) (Object) entries).gpunoise$index()).gpunoise$root();
            TreeBuilder builder = new TreeBuilder(biomeIds);
            builder.append(root, 1);
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
        private final Map<Holder<?>, Integer> biomeIds;
        private final List<PackedNode> nodes = new ArrayList<>();
        private final IdentityHashMap<Object, Integer> indices = new IdentityHashMap<>();
        private int maxDepth;

        private TreeBuilder(Map<Holder<?>, Integer> biomeIds) {
            this.biomeIds = biomeIds;
            nodes.add(null); // OCL's tree uses one-based node offsets.
        }

        private void append(Object source, int depth) {
            if (source == null) throw new IllegalArgumentException("null climate tree node");
            if (indices.containsKey(source)) throw new IllegalArgumentException("climate tree is not a tree");
            maxDepth = Math.max(maxDepth, depth);
            PackedNode node = new PackedNode(bounds(source));
            int index = nodes.size();
            indices.put(source, index);
            nodes.add(node);
            if (source instanceof ClimateRTreeSubTreeAccess subtree) {
                Object rawChildren = subtree.gpunoise$children();
                if (!(rawChildren instanceof Object[] children) || children.length == 0 || children.length > 7)
                    throw new IllegalArgumentException("invalid climate branch fanout");
                node.branch = true;
                node.children = new int[children.length];
                for (Object child : children) append(child, depth + 1);
                for (int i = 0; i < children.length; i++) node.children[i] = indices.get(children[i]);
            } else if (source instanceof ClimateRTreeLeafAccess leaf) {
                Object rawValue = leaf.gpunoise$value();
                if (!(rawValue instanceof Holder<?> holder))
                    throw new IllegalArgumentException("climate leaf is not a biome holder");
                Integer id = biomeIds.get(holder);
                if (id == null) throw new IllegalArgumentException("climate leaf biome is absent from mapping");
                node.biomeId = id;
            } else {
                throw new IllegalArgumentException("unknown climate tree node " + source.getClass().getName());
            }
        }

        private short[] bounds(Object source) {
            Climate.Parameter[] space = ((ClimateRTreeNodeAccess) source).gpunoise$parameterSpace();
            if (space == null || space.length != DIMENSIONS) throw new IllegalArgumentException("invalid climate bounds");
            short[] result = new short[DIMENSIONS * 2];
            for (int i = 0; i < DIMENSIONS; i++) {
                result[i] = quantize(space[i].min());
                result[DIMENSIONS + i] = quantize(space[i].max());
            }
            return result;
        }

        private static short quantize(long coordinate) {
            float climate = Climate.unquantizeCoord(coordinate);
            int scaled = (int) (climate * 10000.0f);
            return (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, scaled));
        }
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
