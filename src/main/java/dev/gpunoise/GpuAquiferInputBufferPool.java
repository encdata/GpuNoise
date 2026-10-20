package dev.gpunoise;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Bounded reuse for large direct aquifer input slabs held through async dispatch. */
public final class GpuAquiferInputBufferPool {
    private static final int MAX_PER_CAPACITY = 4;
    private static final int MAX_CAPACITIES = 16;
    private static final long MAX_POOLED_BYTES = 32L * 1024L * 1024L;
    private static final AtomicLong pooledBytes = new AtomicLong();
    private static final ConcurrentHashMap<Integer, Bucket> buckets = new ConcurrentHashMap<>();

    private GpuAquiferInputBufferPool() {}

    public static ByteBuffer acquire(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("aquifer input buffer capacity must be positive");
        Bucket bucket = buckets.get(capacity);
        ByteBuffer buffer = bucket == null ? null : bucket.acquire();
        if (buffer == null) buffer = ByteBuffer.allocateDirect(capacity);
        buffer.clear();
        buffer.order(ByteOrder.nativeOrder());
        return buffer;
    }

    public static synchronized void release(ByteBuffer input) {
        if (input == null || !input.isDirect() || input.capacity() <= 0) return;
        int capacity = input.capacity();
        Bucket bucket = buckets.get(capacity);
        if (bucket == null) {
            if (buckets.size() >= MAX_CAPACITIES) return;
            bucket = buckets.computeIfAbsent(capacity, ignored -> new Bucket(capacity));
        }
        ByteBuffer reusable = input.duplicate().order(ByteOrder.nativeOrder());
        reusable.clear();
        bucket.release(reusable);
    }

    private static final class Bucket {
        private final int capacity;
        private final ArrayDeque<ByteBuffer> buffers = new ArrayDeque<>();

        private Bucket(int capacity) { this.capacity = capacity; }

        synchronized ByteBuffer acquire() {
            ByteBuffer buffer = buffers.pollFirst();
            if (buffer != null) pooledBytes.addAndGet(-capacity);
            return buffer;
        }

        synchronized void release(ByteBuffer buffer) {
            if (buffers.size() >= MAX_PER_CAPACITY) return;
            while (true) {
                long current = pooledBytes.get();
                if (current + capacity > MAX_POOLED_BYTES) return;
                if (pooledBytes.compareAndSet(current, current + capacity)) break;
            }
            buffers.addFirst(buffer);
        }
    }
}
