package dev.gpunoise;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
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
    public static final int MAX_BATCH_CHUNKS = 16;
    public static final int BATCH_MAGIC = 0x42445244;
    private static final int BATCH_HEADER_WORDS = 4;
    private static final int BATCH_GROUP_WORDS = 4;

    private final int[] pieces;
    private final int[] junctions;
    private final float[] kernel;
    private final int[] affectedBounds;

    public GpuBeardifierInputs(List<Beardifier.Rigid> sourcePieces, List<JigsawJunction> sourceJunctions,
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

    /** Wraps independent chunk payloads so one dispatch can evaluate up to a 4x4 region. */
    static ByteBuffer packBatch(List<ByteBuffer> chunks, int[] sampleCounts) {
        if (chunks == null || sampleCounts == null || chunks.isEmpty()
                || chunks.size() != sampleCounts.length || chunks.size() > MAX_BATCH_CHUNKS)
            throw new IllegalArgumentException("invalid GPU Beardifier chunk batch");
        int totalSamples = 0;
        long totalBytes = (long) (BATCH_HEADER_WORDS + chunks.size() * BATCH_GROUP_WORDS) * Integer.BYTES;
        for (int i = 0; i < chunks.size(); i++) {
            ByteBuffer chunk = chunks.get(i);
            int samples = sampleCounts[i];
            if (!isValidPacked(chunk, samples))
                throw new IllegalArgumentException("invalid chunk payload in GPU Beardifier batch");
            totalSamples = Math.addExact(totalSamples, samples);
            totalBytes += chunk.remaining();
        }
        if (totalSamples > MAX_SAMPLES || totalBytes > Integer.MAX_VALUE)
            throw new IllegalArgumentException("GPU Beardifier batch exceeds input capacity");

        ByteBuffer batch = GpuTerrainInputBufferPool.acquire((int) totalBytes).order(ByteOrder.nativeOrder());
        batch.putInt(totalSamples).putInt(chunks.size()).putInt(BATCH_MAGIC)
                .putInt(BATCH_HEADER_WORDS);
        int payloadWordOffset = BATCH_HEADER_WORDS + chunks.size() * BATCH_GROUP_WORDS;
        int sampleStart = 0;
        for (int i = 0; i < chunks.size(); i++) {
            ByteBuffer chunk = chunks.get(i).duplicate();
            int payloadWords = chunk.remaining() / Integer.BYTES;
            batch.putInt(sampleStart).putInt(sampleCounts[i]).putInt(payloadWordOffset).putInt(payloadWords);
            sampleStart += sampleCounts[i];
            payloadWordOffset += payloadWords;
        }
        for (ByteBuffer chunk : chunks) batch.put(chunk.duplicate());
        return batch.flip();
    }

    static boolean isValidBatch(ByteBuffer records, int totalSamples) {
        if (records == null || totalSamples <= 0 || totalSamples > MAX_SAMPLES
                || records.remaining() < (long) BATCH_HEADER_WORDS * Integer.BYTES) return false;
        ByteBuffer words = records.duplicate().order(ByteOrder.nativeOrder());
        int base = words.position();
        int groupCount = words.getInt(base + Integer.BYTES);
        if (words.getInt(base) != totalSamples || words.getInt(base + 2 * Integer.BYTES) != BATCH_MAGIC
                || groupCount <= 0 || groupCount > MAX_BATCH_CHUNKS
                || words.getInt(base + 3 * Integer.BYTES) != BATCH_HEADER_WORDS) return false;
        long expectedPayloadWord = BATCH_HEADER_WORDS + (long) groupCount * BATCH_GROUP_WORDS;
        int expectedSample = 0;
        for (int group = 0; group < groupCount; group++) {
            int descriptor = base + (BATCH_HEADER_WORDS + group * BATCH_GROUP_WORDS) * Integer.BYTES;
            int sampleStart = words.getInt(descriptor);
            int samples = words.getInt(descriptor + Integer.BYTES);
            int payloadWord = words.getInt(descriptor + 2 * Integer.BYTES);
            int payloadWords = words.getInt(descriptor + 3 * Integer.BYTES);
            long payloadEnd = (long) payloadWord + payloadWords;
            long payloadByteOffset = (long) payloadWord * Integer.BYTES;
            long payloadByteLength = (long) payloadWords * Integer.BYTES;
            if (sampleStart != expectedSample || samples <= 0 || payloadWord != expectedPayloadWord
                    || payloadEnd * Integer.BYTES > records.remaining()) return false;
            ByteBuffer chunk = records.duplicate().order(ByteOrder.nativeOrder());
            int start = Math.toIntExact(payloadByteOffset);
            int end = Math.toIntExact(payloadByteOffset + payloadByteLength);
            chunk.position(base + start).limit(base + end);
            chunk = chunk.slice().order(ByteOrder.nativeOrder());
            if (!isValidPacked(chunk, samples)) return false;
            expectedSample = Math.addExact(expectedSample, samples);
            expectedPayloadWord = payloadEnd;
        }
        return expectedSample == totalSamples
                && expectedPayloadWord * Integer.BYTES == records.remaining();
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
