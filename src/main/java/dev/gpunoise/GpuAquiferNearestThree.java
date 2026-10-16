package dev.gpunoise;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Input contract and vanilla-order reference for aquifer center selection. */
final class GpuAquiferNearestThree {
    static final int CANDIDATES = 27;
    static final int RECORD_BYTES = 16 * (CANDIDATES + 1);
    static final int OUTPUT_INTS = 8;
    static final int MAX_QUERIES = 16_384;

    private GpuAquiferNearestThree() {}

    static ByteBuffer pack(int[][] queries, int[][][] centers) {
        if (queries.length != centers.length || queries.length > MAX_QUERIES)
            throw new IllegalArgumentException("invalid aquifer nearest-three batch");
        ByteBuffer packed = ByteBuffer.allocateDirect(queries.length * RECORD_BYTES)
                .order(ByteOrder.nativeOrder());
        for (int i = 0; i < queries.length; i++) {
            int[] query = queries[i];
            if (query.length != 3 || centers[i].length != CANDIDATES)
                throw new IllegalArgumentException("aquifer query must contain one point and 27 centers");
            packed.putInt(query[0]).putInt(query[1]).putInt(query[2]).putInt(0);
            for (int[] center : centers[i]) {
                if (center.length != 3) throw new IllegalArgumentException("aquifer center must be xyz");
                packed.putInt(center[0]).putInt(center[1]).putInt(center[2]).putInt(0);
            }
        }
        return packed.flip();
    }

    /** Returns nearest indices followed by their squared distances. */
    static int[] select(int[] query, int[][] centers) {
        if (query.length != 3 || centers.length != CANDIDATES)
            throw new IllegalArgumentException("aquifer query must contain one point and 27 centers");
        int[] indices = {-1, -1, -1};
        int[] distances = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE};
        for (int i = 0; i < CANDIDATES; i++) {
            int dx = centers[i][0] - query[0];
            int dy = centers[i][1] - query[1];
            int dz = centers[i][2] - query[2];
            int distance = dx * dx + dy * dy + dz * dz;
            if (distances[0] > distance) {
                indices[2] = indices[1]; distances[2] = distances[1];
                indices[1] = indices[0]; distances[1] = distances[0];
                indices[0] = i; distances[0] = distance;
            } else if (distances[1] > distance) {
                indices[2] = indices[1]; distances[2] = distances[1];
                indices[1] = i; distances[1] = distance;
            } else if (distances[2] >= distance) {
                indices[2] = i; distances[2] = distance;
            }
        }
        return new int[]{indices[0], indices[1], indices[2],
                distances[0], distances[1], distances[2], 0, 0};
    }
}
