package dev.gpunoise;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;

/** Packed per-block inputs and Java reference for OCL's OreVeinifier stage. */
public final class GpuOreVeinDecisions {
    public static final int RECORD_BYTES = 52;
    public static final int NO_VEIN = -1;
    public static final int CPU_FALLBACK = -2;
    static final int COMPACT_NO_VEIN = 0;
    static final int COMPACT_CPU_FALLBACK = 1;
    private static final int[] COMPACT_STATE_IDS = {
            NO_VEIN,
            CPU_FALLBACK,
            Block.getId(Blocks.COPPER_ORE.defaultBlockState()),
            Block.getId(Blocks.RAW_COPPER_BLOCK.defaultBlockState()),
            Block.getId(Blocks.GRANITE.defaultBlockState()),
            Block.getId(Blocks.DEEPSLATE_IRON_ORE.defaultBlockState()),
            Block.getId(Blocks.RAW_IRON_BLOCK.defaultBlockState()),
            Block.getId(Blocks.TUFF.defaultBlockState())
    };
    private static final float DECISION_EPSILON = GpuTerrainRuntime.GRAPH_PARITY_EPSILON;

    private GpuOreVeinDecisions() {}

    static int decodeCompactCode(int code) {
        if (code < 0 || code >= COMPACT_STATE_IDS.length)
            throw new IllegalArgumentException("invalid compact ore-vein result " + code);
        return COMPACT_STATE_IDS[code];
    }

    /** Packs one block row using the same positional random stream as OreVeinifier. */
    public static ByteBuffer pack(float veinToggle, float veinRidged, float veinGap,
                                  int x, int y, int z, PositionalRandomFactory randomFactory) {
        if (randomFactory == null) throw new NullPointerException("randomFactory");
        ByteBuffer result = ByteBuffer.allocateDirect(RECORD_BYTES).order(ByteOrder.nativeOrder());
        put(result, veinToggle, veinRidged, veinGap, x, y, z, randomFactory);
        return result.flip();
    }

    static ByteBuffer allocateBatch(int rows) {
        if (rows <= 0 || (long) rows * RECORD_BYTES > Integer.MAX_VALUE)
            throw new IllegalArgumentException("invalid ore-vein batch size");
        return ByteBuffer.allocateDirect(rows * RECORD_BYTES).order(ByteOrder.nativeOrder());
    }

    static void put(ByteBuffer target, float veinToggle, float veinRidged, float veinGap,
                    int x, int y, int z, PositionalRandomFactory randomFactory) {
        if (target == null || target.remaining() < RECORD_BYTES || randomFactory == null)
            throw new IllegalArgumentException("invalid ore-vein row destination");
        RandomSource random = randomFactory.at(x, y, z);
        target.putFloat(veinToggle).putFloat(veinRidged).putFloat(veinGap).putInt(y)
                .putFloat(random.nextFloat()).putFloat(random.nextFloat()).putFloat(random.nextFloat())
                .putInt(Block.getId(Blocks.COPPER_ORE.defaultBlockState()))
                .putInt(Block.getId(Blocks.RAW_COPPER_BLOCK.defaultBlockState()))
                .putInt(Block.getId(Blocks.GRANITE.defaultBlockState()))
                .putInt(Block.getId(Blocks.DEEPSLATE_IRON_ORE.defaultBlockState()))
                .putInt(Block.getId(Blocks.RAW_IRON_BLOCK.defaultBlockState()))
                .putInt(Block.getId(Blocks.TUFF.defaultBlockState()));
    }

    /** CPU reference used for Vulkan parity checks and threshold fallback. */
    public static int evaluate(float toggle, float ridged, float gap, int y,
                               float roll0, float roll1, float roll2,
                               int copperOre, int copperRaw, int copperFiller,
                               int ironOre, int ironRaw, int ironFiller) {
        if (!Float.isFinite(toggle) || !Float.isFinite(ridged) || !Float.isFinite(gap)
                || !Float.isFinite(roll0) || !Float.isFinite(roll1) || !Float.isFinite(roll2))
            return CPU_FALLBACK;
        if (near(toggle, 0.0f) || near(ridged, 0.0f)) return CPU_FALLBACK;
        boolean copper = toggle > 0.0f;
        int minY = copper ? 0 : -60;
        int maxY = copper ? 50 : -8;
        int lowerEdge = y - minY;
        int upperEdge = maxY - y;
        if (lowerEdge < 0 || upperEdge < 0) return NO_VEIN;

        float edge = Math.min(lowerEdge, upperEdge);
        float edgeFade = clampedMap(edge, 0.0f, 20.0f, -0.2f, 0.0f);
        float richness = Math.abs(toggle);
        float veinThreshold = richness + edgeFade;
        if (near(veinThreshold, 0.4f)) return CPU_FALLBACK;
        if (veinThreshold < 0.4f || roll0 > 0.7f) return NO_VEIN;
        if (ridged >= 0.0f) return NO_VEIN;

        float oreChance = clampedMap(richness, 0.4f, 0.6f, 0.1f, 0.3f);
        if (near(roll1, oreChance) || near(gap, -0.3f)) return CPU_FALLBACK;
        if (roll1 < oreChance && gap > -0.3f) {
            if (near(roll2, 0.02f)) return CPU_FALLBACK;
            if (roll2 < 0.02f) return copper ? copperRaw : ironRaw;
            return copper ? copperOre : ironOre;
        }
        return copper ? copperFiller : ironFiller;
    }

    public static int evaluate(ByteBuffer record) {
        if (record == null || record.remaining() != RECORD_BYTES)
            throw new IllegalArgumentException("invalid ore-vein record");
        ByteBuffer row = record.duplicate().order(ByteOrder.nativeOrder());
        return evaluate(row.getFloat(), row.getFloat(), row.getFloat(), row.getInt(),
                row.getFloat(), row.getFloat(), row.getFloat(),
                row.getInt(), row.getInt(), row.getInt(), row.getInt(), row.getInt(), row.getInt());
    }

    public static ByteBuffer packBatch(ByteBuffer[] records) {
        if (records == null || records.length == 0
                || (long) records.length * RECORD_BYTES > Integer.MAX_VALUE)
            throw new IllegalArgumentException("invalid ore-vein batch");
        ByteBuffer result = ByteBuffer.allocateDirect(records.length * RECORD_BYTES)
                .order(ByteOrder.nativeOrder());
        for (ByteBuffer record : records) {
            if (record == null || record.remaining() != RECORD_BYTES)
                throw new IllegalArgumentException("invalid ore-vein row");
            result.put(record.duplicate());
        }
        return result.flip();
    }

    private static float clampedMap(float value, float inMin, float inMax, float outMin, float outMax) {
        float t = Math.max(0.0f, Math.min(1.0f, (value - inMin) / (inMax - inMin)));
        return outMin + t * (outMax - outMin);
    }

    private static boolean near(float first, float second) {
        return Math.abs(first - second) <= DECISION_EPSILON;
    }
}
