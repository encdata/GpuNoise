package dev.gpunoise;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.atomic.AtomicLong;

/** Bounded reuse for direct worldgen input slabs held through asynchronous Vulkan dispatch. */
public final class GpuTerrainInputBufferPool {
    private static final int CAPACITY_ALIGNMENT = 4096;
    private static final int MAX_PER_CAPACITY = 4;
    private static final int MAX_CAPACITIES = 128;
    private static final long MAX_POOLED_BYTES = 32L * 1024L * 1024L;
    private static final AtomicLong pooledBytes = new AtomicLong();
    private static final LongAdder allocatedBuffers = new LongAdder();
    private static final LongAdder reusedBuffers = new LongAdder();
    private static final ConcurrentHashMap<Integer, Bucket> buckets = new ConcurrentHashMap<>();

    private GpuTerrainInputBufferPool() {}

    public static ByteBuffer acquire(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("aquifer input buffer capacity must be positive");
        long alignedCapacity = ((long) capacity + CAPACITY_ALIGNMENT - 1L)
                & -((long) CAPACITY_ALIGNMENT);
        if (alignedCapacity > Integer.MAX_VALUE)
            throw new IllegalArgumentException("aquifer input buffer capacity exceeds direct-buffer limit");
        int pooledCapacity = (int) alignedCapacity;
        Bucket bucket = buckets.get(pooledCapacity);
        ByteBuffer buffer = bucket == null ? null : bucket.acquire();
        if (buffer == null) {
            buffer = ByteBuffer.allocateDirect(pooledCapacity);
            allocatedBuffers.increment();
        } else {
            reusedBuffers.increment();
        }
        buffer.clear();
        buffer.order(ByteOrder.nativeOrder());
        return buffer;
    }

    static long allocatedBufferCount() { return allocatedBuffers.sum(); }
    static long reusedBufferCount() { return reusedBuffers.sum(); }
    static long pooledByteCount() { return pooledBytes.get(); }

    public static void release(ByteBuffer input) {
        if (input == null || !input.isDirect() || input.capacity() <= 0) return;
        int capacity = input.capacity();
        Bucket bucket = buckets.get(capacity);
        if (bucket == null) {
            synchronized (buckets) {
                bucket = buckets.get(capacity);
                if (bucket == null) {
                    if (buckets.size() >= MAX_CAPACITIES) return;
                    bucket = new Bucket(capacity);
                    buckets.put(capacity, bucket);
                }
            }
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
