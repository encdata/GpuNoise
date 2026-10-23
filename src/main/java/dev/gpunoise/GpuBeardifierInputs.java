package dev.gpunoise;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.structure.TerrainAdjustment;
import net.minecraft.world.level.levelgen.structure.pools.JigsawJunction;

/** Immutable structure-weight inputs shared by all block samples in one chunk. */
public final class GpuBeardifierInputs {
    public static final int HEADER_WORDS = 13;
    public static final int PIECE_WORDS = 8;
    public static final int JUNCTION_WORDS = 3;
    public static final int SAMPLE_WORDS = 3;
    public static final int KERNEL_VALUES = 24 * 24 * 24;
    public static final int MAX_SAMPLES = PersistentVulkanNormalNoiseEngine.CAPACITY;

    private final int[] pieces;
    private final int[] junctions;
    private final float[] kernel;
    private final int[] affectedBounds;

    GpuBeardifierInputs(List<Beardifier.Rigid> sourcePieces, List<JigsawJunction> sourceJunctions,
                        float[] structureKernel) {
        if (structureKernel == null || structureKernel.length != KERNEL_VALUES)
            throw new IllegalArgumentException("incomplete Beardifier inputs");
        this.pieces = new int[Math.multiplyExact(sourcePieces.size(), PIECE_WORDS)];
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (int i = 0; i < sourcePieces.size(); i++) {
            Beardifier.Rigid piece = sourcePieces.get(i);
            BoundingBox box = piece.box();
            int base = i * PIECE_WORDS;
            pieces[base] = box.minX();
            pieces[base + 1] = box.minY();
            pieces[base + 2] = box.minZ();
            pieces[base + 3] = box.maxX();
            pieces[base + 4] = box.maxY();
            pieces[base + 5] = box.maxZ();
            pieces[base + 6] = piece.groundLevelDelta();
            pieces[base + 7] = adjustmentId(piece.terrainAdjustment());
            minX = Math.min(minX, box.minX() - 12);
            minY = Math.min(minY, Math.min(box.minY() + piece.groundLevelDelta() - 12,
                    box.minY() - 12));
            minZ = Math.min(minZ, box.minZ() - 12);
            maxX = Math.max(maxX, box.maxX() + 12);
            maxY = Math.max(maxY, Math.max(box.maxY() + piece.groundLevelDelta() + 12,
                    box.maxY() + 12));
            maxZ = Math.max(maxZ, box.maxZ() + 12);
        }
        this.junctions = new int[Math.multiplyExact(sourceJunctions.size(), JUNCTION_WORDS)];
        for (int i = 0; i < sourceJunctions.size(); i++) {
            JigsawJunction junction = sourceJunctions.get(i);
            int base = i * JUNCTION_WORDS;
            junctions[base] = junction.getSourceX();
            junctions[base + 1] = junction.getSourceGroundY();
            junctions[base + 2] = junction.getSourceZ();
            minX = Math.min(minX, junction.getSourceX() - 12);
            minY = Math.min(minY, junction.getSourceGroundY() - 12);
            minZ = Math.min(minZ, junction.getSourceZ() - 12);
            maxX = Math.max(maxX, junction.getSourceX() + 12);
            maxY = Math.max(maxY, junction.getSourceGroundY() + 12);
            maxZ = Math.max(maxZ, junction.getSourceZ() + 12);
        }
        this.kernel = structureKernel.clone();
        if (pieces.length == 0 && junctions.length == 0) {
            minX = minY = minZ = 1;
            maxX = maxY = maxZ = 0;
        }
        this.affectedBounds = new int[]{minX, minY, minZ, maxX, maxY, maxZ};
    }

    private static int adjustmentId(TerrainAdjustment adjustment) {
        return switch (adjustment) {
            case NONE -> 0;
            case BURY -> 1;
            case BEARD_THIN -> 2;
            case BEARD_BOX -> 3;
            case ENCAPSULATE -> 4;
        };
    }

    public int pieceCount() { return pieces.length / PIECE_WORDS; }
    public int junctionCount() { return junctions.length / JUNCTION_WORDS; }
    public boolean hasContributions() { return pieces.length != 0 || junctions.length != 0; }

    static boolean isValidPacked(ByteBuffer records, int count) {
        if (records == null || count <= 0 || count > MAX_SAMPLES
                || records.remaining() < HEADER_WORDS * Integer.BYTES) return false;
        ByteBuffer words = records.duplicate().order(ByteOrder.nativeOrder());
        int base = words.position();
        if (words.getInt(base) != count) return false;
        int pieceCount = words.getInt(base + 4);
        int junctionCount = words.getInt(base + 8);
        if (pieceCount < 0 || junctionCount < 0) return false;
        long pieceBase = HEADER_WORDS;
        long junctionBase = pieceBase + (long) pieceCount * PIECE_WORDS;
        long kernelBase = junctionBase + (long) junctionCount * JUNCTION_WORDS;
        long sampleBase = kernelBase + KERNEL_VALUES;
        long total = sampleBase + (long) count * SAMPLE_WORDS;
        return total * Integer.BYTES == records.remaining()
                && words.getInt(base + 9 * Integer.BYTES) == pieceBase
                && words.getInt(base + 10 * Integer.BYTES) == junctionBase
                && words.getInt(base + 11 * Integer.BYTES) == kernelBase
                && words.getInt(base + 12 * Integer.BYTES) == sampleBase;
    }

    /** Packs block coordinates followed by bounded structure inputs for one Vulkan dispatch. */
    public ByteBuffer pack(int minX, int minY, int minZ, int height) {
        int sampleCount = Math.multiplyExact(height, 256);
        if (height <= 0 || sampleCount > MAX_SAMPLES)
            throw new IllegalArgumentException("invalid Beardifier density volume");
        int pieceBase = HEADER_WORDS;
        int junctionBase = Math.addExact(pieceBase, pieces.length);
        int kernelBase = Math.addExact(junctionBase, junctions.length);
        int sampleBase = Math.addExact(kernelBase, kernel.length);
        int totalWords = Math.addExact(sampleBase, Math.multiplyExact(sampleCount, SAMPLE_WORDS));
        ByteBuffer packed = GpuTerrainInputBufferPool.acquire(Math.multiplyExact(totalWords, Integer.BYTES))
                .order(ByteOrder.nativeOrder());
        packed.putInt(sampleCount).putInt(pieceCount()).putInt(junctionCount());
        for (int bound : affectedBounds) packed.putInt(bound);
        packed.putInt(pieceBase).putInt(junctionBase).putInt(kernelBase).putInt(sampleBase);
        for (int word : pieces) packed.putInt(word);
        for (int word : junctions) packed.putInt(word);
        for (float value : kernel) packed.putFloat(value);
        for (int y = 0; y < height; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    packed.putInt(minX + x).putInt(minY + y).putInt(minZ + z);
                }
            }
        }
        return packed.flip();
    }
}
