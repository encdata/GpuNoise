package dev.gpunoise;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import dev.gpunoise.GpuAquiferDecisionData.AquiferQuery;

/** Input contract and reference selection for vanilla and optimized aquifer kernels. */
public final class GpuAquiferNearestThree {
    // Vanilla visits grid X offsets 0..1, Y offsets -1..1, Z offsets 0..1.
    public static final int CANDIDATES = 12;
    public static final int HEADER_BYTES = 2 * Integer.BYTES;
    public static final int QUERY_RECORD_BYTES = 4 * Integer.BYTES;
    public static final int CENTER_RECORD_BYTES = 4 * Integer.BYTES * CANDIDATES;
    /** Maximum-size query plus one unique center neighborhood per query. */
    public static final int RECORD_BYTES = QUERY_RECORD_BYTES + CENTER_RECORD_BYTES;
    // Four ranked center indices, four squared distances, and four ABI words.
    // Vanilla consumes the first three; optimized aquifer implementations can
    // use the fourth for fluid-tick decisions.
    public static final int OUTPUT_INTS = 12;
    /** One dispatch can rank centers for a modded 576-block chunk. */
    public static final int MAX_QUERIES = PersistentVulkanNormalNoiseEngine.CAPACITY;
    public static final long MAX_PACKED_BYTES = HEADER_BYTES
            + (long) MAX_QUERIES * RECORD_BYTES;

    private GpuAquiferNearestThree() {}

    public static ByteBuffer pack(int[][] queries, int[][][] centers) {
        if (queries.length == 0 || queries.length != centers.length || queries.length > MAX_QUERIES)
            throw new IllegalArgumentException("invalid aquifer nearest-four batch");
        IdentityHashMap<int[][], Integer> centerIndices = new IdentityHashMap<>();
        List<int[][]> uniqueCenters = new ArrayList<>();
        int[] queryCenterIndices = new int[queries.length];
        for (int i = 0; i < queries.length; i++) {
            int[] query = queries[i];
            if (query == null || query.length != 3 || centers[i] == null
                    || centers[i].length != CANDIDATES)
                throw new IllegalArgumentException("aquifer query must contain one point and 12 centers");
            for (int[] center : centers[i]) {
                if (center == null || center.length != 3)
                    throw new IllegalArgumentException("aquifer center must be xyz");
            }
            Integer group = centerIndices.get(centers[i]);
            if (group == null) {
                group = uniqueCenters.size();
                uniqueCenters.add(centers[i]);
                centerIndices.put(centers[i], group);
            }
            queryCenterIndices[i] = group;
        }
        long byteCount = HEADER_BYTES + (long) queries.length * QUERY_RECORD_BYTES
                + (long) uniqueCenters.size() * CENTER_RECORD_BYTES;
        if (byteCount > MAX_PACKED_BYTES || byteCount > Integer.MAX_VALUE)
            throw new IllegalArgumentException("aquifer nearest-center payload exceeds GPU capacity");
        ByteBuffer packed = ByteBuffer.allocateDirect((int) byteCount).order(ByteOrder.nativeOrder());
        packed.putInt(queries.length).putInt(uniqueCenters.size());
        for (int i = 0; i < queries.length; i++) {
            packed.putInt(queries[i][0]).putInt(queries[i][1]).putInt(queries[i][2])
                    .putInt(queryCenterIndices[i]);
        }
        for (int[][] neighborhood : uniqueCenters) {
            for (int[] center : neighborhood) {
                packed.putInt(center[0]).putInt(center[1]).putInt(center[2]).putInt(0);
            }
        }
        return packed.flip();
    }

    /** Packs queued chunk queries without constructing per-query coordinate and candidate arrays. */
    public static ByteBuffer pack(List<AquiferQuery> queries, int offset, int count) {
        if (queries == null || count <= 0 || count > MAX_QUERIES
                || offset < 0 || offset > queries.size() - count)
            throw new IllegalArgumentException("invalid aquifer nearest-four batch range");
        IdentityHashMap<int[][], Integer> centerIndices = new IdentityHashMap<>();
        List<int[][]> uniqueCenters = new ArrayList<>();
        for (int index = offset; index < offset + count; index++) {
            AquiferQuery query = queries.get(index);
            if (query == null || query.centers == null || query.centers.length != CANDIDATES)
                throw new IllegalArgumentException("aquifer query must contain xyz and 12 centers");
            for (int[] center : query.centers) {
                if (center == null || center.length != 3)
                    throw new IllegalArgumentException("aquifer center must be xyz");
            }
            if (!centerIndices.containsKey(query.centers)) {
                centerIndices.put(query.centers, uniqueCenters.size());
                uniqueCenters.add(query.centers);
            }
        }
        long byteCount = HEADER_BYTES + (long) count * QUERY_RECORD_BYTES
                + (long) uniqueCenters.size() * CENTER_RECORD_BYTES;
        if (byteCount > MAX_PACKED_BYTES || byteCount > Integer.MAX_VALUE)
            throw new IllegalArgumentException("aquifer nearest-center payload exceeds GPU capacity");
        ByteBuffer packed = ByteBuffer.allocateDirect((int) byteCount).order(ByteOrder.nativeOrder());
        packed.putInt(count).putInt(uniqueCenters.size());
        for (int index = offset; index < offset + count; index++) {
            AquiferQuery query = queries.get(index);
            packed.putInt(query.x).putInt(query.y).putInt(query.z)
                    .putInt(centerIndices.get(query.centers));
        }
        for (int[][] neighborhood : uniqueCenters) {
            for (int[] center : neighborhood) {
                packed.putInt(center[0]).putInt(center[1]).putInt(center[2]).putInt(0);
            }
        }
        return packed.flip();
    }

    static boolean isValidPacked(ByteBuffer records, int count) {
        if (records == null || count <= 0 || count > MAX_QUERIES
                || records.remaining() < HEADER_BYTES + (long) count * QUERY_RECORD_BYTES) return false;
        ByteBuffer view = records.duplicate().order(ByteOrder.nativeOrder());
        int position = view.position();
        if (view.getInt(position) != count) return false;
        int groups = view.getInt(position + Integer.BYTES);
        if (groups <= 0 || groups > count) return false;
        long expected = HEADER_BYTES + (long) count * QUERY_RECORD_BYTES
                + (long) groups * CENTER_RECORD_BYTES;
        return expected == records.remaining() && expected <= MAX_PACKED_BYTES;
    }

    static ByteBuffer pack(PositionalRandomFactory factory, int[][] queries) {
        int[][][] centers = new int[queries.length][CANDIDATES][];
        for (int i = 0; i < queries.length; i++) centers[i] = centersForQuery(factory, queries[i]);
        return pack(queries, centers);
    }

    /** Recreates the 2x3x2 positional-random neighborhood used by vanilla. */
    public static int[][] centersForQuery(PositionalRandomFactory factory, int[] query) {
        if (query.length != 3) throw new IllegalArgumentException("aquifer query must be xyz");
        int baseX = Math.floorDiv(query[0] - 5, 16);
        int baseY = Math.floorDiv(query[1] + 1, 12);
        int baseZ = Math.floorDiv(query[2] - 5, 16);
        int[][] centers = new int[CANDIDATES][3];
        int index = 0;
        for (int offsetX = 0; offsetX <= 1; offsetX++) {
            for (int offsetY = -1; offsetY <= 1; offsetY++) {
                for (int offsetZ = 0; offsetZ <= 1; offsetZ++) {
                    centers[index++] = centerAt(factory,
                            baseX + offsetX, baseY + offsetY, baseZ + offsetZ);
                }
            }
        }
        return centers;
    }

    /** Same neighborhood in C2ME's aquifer scan order (Y, Z, then X). */
    public static int[][] centersForC2meQuery(PositionalRandomFactory factory, int[] query) {
        if (query.length != 3) throw new IllegalArgumentException("aquifer query must be xyz");
        int baseX = Math.floorDiv(query[0] - 5, 16);
        int baseY = Math.floorDiv(query[1] + 1, 12);
        int baseZ = Math.floorDiv(query[2] - 5, 16);
        int[][] centers = new int[CANDIDATES][3];
        int index = 0;
        for (int offsetY = -1; offsetY <= 1; offsetY++) {
            for (int offsetZ = 0; offsetZ <= 1; offsetZ++) {
                for (int offsetX = 0; offsetX <= 1; offsetX++) {
                    centers[index++] = centerAt(factory,
                            baseX + offsetX, baseY + offsetY, baseZ + offsetZ);
                }
            }
        }
        return centers;
    }

    public static int[] centerAt(PositionalRandomFactory factory, int gridX, int gridY, int gridZ) {
        RandomSource random = factory.at(gridX, gridY, gridZ);
        return new int[]{gridX * 16 + random.nextInt(10),
                gridY * 12 + random.nextInt(9), gridZ * 16 + random.nextInt(10)};
    }

    /** Returns nearest indices followed by their squared distances. */
    public static int[] select(int[] query, int[][] centers) {
        if (query.length != 3 || centers.length != CANDIDATES)
            throw new IllegalArgumentException("aquifer query must contain one point and 12 centers");
        int[] indices = {-1, -1, -1, -1};
        int[] distances = {Integer.MAX_VALUE, Integer.MAX_VALUE,
                Integer.MAX_VALUE, Integer.MAX_VALUE};
        for (int i = 0; i < CANDIDATES; i++) {
            int dx = centers[i][0] - query[0];
            int dy = centers[i][1] - query[1];
            int dz = centers[i][2] - query[2];
            int distance = dx * dx + dy * dy + dz * dz;
            // Vanilla's NoiseBasedAquifer uses inclusive comparisons at all
            // three consumed ranks. Preserve that ordering while retaining a
            // fourth rank for optimized aquifer fluid-tick decisions.
            if (distances[0] >= distance) {
                indices[3] = indices[2]; distances[3] = distances[2];
                indices[2] = indices[1]; distances[2] = distances[1];
                indices[1] = indices[0]; distances[1] = distances[0];
                indices[0] = i; distances[0] = distance;
            } else if (distances[1] >= distance) {
                indices[3] = indices[2]; distances[3] = distances[2];
                indices[2] = indices[1]; distances[2] = distances[1];
                indices[1] = i; distances[1] = distance;
            } else if (distances[2] >= distance) {
                indices[3] = indices[2]; distances[3] = distances[2];
                indices[2] = i; distances[2] = distance;
            } else if (distances[3] >= distance) {
                indices[3] = i; distances[3] = distance;
            }
        }
        return new int[]{indices[0], indices[1], indices[2],
                indices[3], distances[0], distances[1], distances[2], distances[3],
                0, 0, 0, 0};
    }
}
