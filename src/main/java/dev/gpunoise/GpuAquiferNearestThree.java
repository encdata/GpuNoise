package dev.gpunoise;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.function.ToIntFunction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import dev.gpunoise.GpuAquiferDecisionData.AquiferQuery;
import dev.gpunoise.GpuAquiferDecisionData.AquiferQueries;

/** Input contract and reference selection for vanilla and optimized aquifer kernels. */
public final class GpuAquiferNearestThree {
    // Vanilla visits grid X offsets 0..1, Y offsets -1..1, Z offsets 0..1.
    public static final int CANDIDATES = 12;
    public static final int HEADER_BYTES = 2 * Integer.BYTES;
    /** xyz + center-group index + density/barrier float bits + flags. */
    public static final int QUERY_RECORD_WORDS = 7;
    public static final int QUERY_RECORD_BYTES = QUERY_RECORD_WORDS * Integer.BYTES;
    public static final int CENTER_RECORD_BYTES = 4 * Integer.BYTES * CANDIDATES;
    /** Maximum-size query plus one unique center neighborhood per query. */
    public static final int RECORD_BYTES = QUERY_RECORD_BYTES + CENTER_RECORD_BYTES;
    // Four ranked center indices and their squared distances. The fourth rank
    // is used by optimized aquifer implementations for fluid-tick decisions.
    public static final int OUTPUT_INTS = 8;
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
                    .putInt(queryCenterIndices[i]).putFloat(0.0f).putFloat(0.0f).putInt(0);
        }
        for (int[][] neighborhood : uniqueCenters) {
            for (int[] center : neighborhood) {
                packed.putInt(center[0]).putInt(center[1]).putInt(center[2]).putInt(-1);
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
        int[] queryCenterIndices = new int[count];
        for (int index = offset; index < offset + count; index++) {
            AquiferQuery query = queries.get(index);
            if (query == null || query.centers == null || query.centers.length != CANDIDATES)
                throw new IllegalArgumentException("aquifer query must contain xyz and 12 centers");
            Integer group = centerIndices.get(query.centers);
            if (group == null) {
                for (int[] center : query.centers) {
                    if (center == null || center.length != 3)
                        throw new IllegalArgumentException("aquifer center must be xyz");
                }
                group = uniqueCenters.size();
                centerIndices.put(query.centers, group);
                uniqueCenters.add(query.centers);
            }
            queryCenterIndices[index - offset] = group;
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
                    .putInt(queryCenterIndices[index - offset])
                    .putFloat((float) query.density).putFloat((float) query.barrier)
                    .putInt((query.belowIsLava ? 1 : 0) | (query.c2meAquifer ? 2 : 0));
        }
        for (int[][] neighborhood : uniqueCenters) {
            for (int[] center : neighborhood) {
                packed.putInt(center[0]).putInt(center[1]).putInt(center[2]).putInt(-1);
            }
        }
        return packed.flip();
    }

    /** Packs columnar chunk staging directly, without materializing per-query objects. */
    public static ByteBuffer pack(AquiferQueries queries, int offset, int count) {
        return pack(queries, offset, count, false, null);
    }

    /** Packs query seeds and stable status-row indices for the GPU-resident decision chain. */
    public static ByteBuffer pack(AquiferQueries queries, int offset, int count,
                                  ToIntFunction<int[]> centerStatusRows) {
        if (centerStatusRows == null) throw new NullPointerException("centerStatusRows");
        return pack(queries, offset, count, false, centerStatusRows);
    }

    /** Packs a reusable GPU submission slab; release it after its async future completes. */
    public static ByteBuffer packForAsync(AquiferQueries queries, int offset, int count) {
        return pack(queries, offset, count, true, null);
    }

    private static ByteBuffer pack(AquiferQueries queries, int offset, int count, boolean pooled,
                                   ToIntFunction<int[]> centerStatusRows) {
        if (queries == null || count <= 0 || count > MAX_QUERIES
                || offset < 0 || offset > queries.size() - count)
            throw new IllegalArgumentException("invalid aquifer nearest-four batch range");
        IdentityHashMap<int[][], Integer> centerIndices = new IdentityHashMap<>();
        List<int[][]> uniqueCenters = new ArrayList<>();
        int[] queryCenterIndices = new int[count];
        for (int i = 0; i < count; i++) {
            int[][] neighborhood = queries.centers(offset + i);
            if (neighborhood == null || neighborhood.length != CANDIDATES)
                throw new IllegalArgumentException("aquifer query must contain 12 centers");
            Integer group = centerIndices.get(neighborhood);
            if (group == null) {
                for (int[] center : neighborhood) {
                    if (center == null || center.length != 3)
                        throw new IllegalArgumentException("aquifer center must be xyz");
                }
                group = uniqueCenters.size();
                centerIndices.put(neighborhood, group);
                uniqueCenters.add(neighborhood);
            }
            queryCenterIndices[i] = group;
        }
        long byteCount = HEADER_BYTES + (long) count * QUERY_RECORD_BYTES
                + (long) uniqueCenters.size() * CENTER_RECORD_BYTES;
        if (byteCount > MAX_PACKED_BYTES || byteCount > Integer.MAX_VALUE)
            throw new IllegalArgumentException("aquifer nearest-center payload exceeds GPU capacity");
        ByteBuffer packed = (pooled ? GpuAquiferInputBufferPool.acquire((int) byteCount)
                : ByteBuffer.allocateDirect((int) byteCount)).order(ByteOrder.nativeOrder());
        packed.putInt(count).putInt(uniqueCenters.size());
        for (int i = 0; i < count; i++) {
            int row = offset + i;
            packed.putInt(queries.x(row)).putInt(queries.y(row)).putInt(queries.z(row))
                    .putInt(queryCenterIndices[i]).putFloat((float) queries.density(row))
                    .putFloat((float) queries.barrier(row)).putInt(
                            (queries.belowIsLava(row) ? 1 : 0) | (queries.c2meAquifer(row) ? 2 : 0));
        }
        for (int[][] neighborhood : uniqueCenters) {
            for (int[] center : neighborhood) {
                int statusRow = centerStatusRows == null ? -1 : centerStatusRows.applyAsInt(center);
                if (statusRow < -1) throw new IllegalArgumentException("invalid aquifer center status row");
                packed.putInt(center[0]).putInt(center[1]).putInt(center[2]).putInt(statusRow);
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
        return centersForQuery(factory, query[0], query[1], query[2]);
    }

    /** Coordinate overload for hot paths that already have primitive positions. */
    public static int[][] centersForQuery(PositionalRandomFactory factory, int x, int y, int z) {
        int baseX = Math.floorDiv(x - 5, 16);
        int baseY = Math.floorDiv(y + 1, 12);
        int baseZ = Math.floorDiv(z - 5, 16);
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
        return centersForC2meQuery(factory, query[0], query[1], query[2]);
    }

    /** Coordinate overload for hot paths that already have primitive positions. */
    public static int[][] centersForC2meQuery(PositionalRandomFactory factory, int x, int y, int z) {
        int baseX = Math.floorDiv(x - 5, 16);
        int baseY = Math.floorDiv(y + 1, 12);
        int baseZ = Math.floorDiv(z - 5, 16);
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
        return new int[]{indices[0], indices[1], indices[2], indices[3],
                distances[0], distances[1], distances[2], distances[3]};
    }
}
