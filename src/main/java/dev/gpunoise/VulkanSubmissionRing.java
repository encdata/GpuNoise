package dev.gpunoise;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.nio.ByteBuffer;

/**
 * Safe in-flight submission ring. Each slot owns its Vulkan buffers, command
 * buffer, and fence through a separate persistent engine, so submissions never
 * overwrite resources still used by another dispatch.
 */
final class VulkanSubmissionRing implements AutoCloseable {
    // Keep the independent-resource ring at four slots. Increasing this by
    // creating more engines multiplies LWJGL stack/direct-memory use and can
    // exhaust the JVM before a shared suballocated ring is implemented.
    private static final int SLOT_COUNT = 4;
    private final PersistentVulkanNormalNoiseEngine[] slots;
    private final AtomicInteger next = new AtomicInteger();
    private final ExecutorService completion = Executors.newFixedThreadPool(SLOT_COUNT, r -> {
        Thread thread = new Thread(r, "GPU Vulkan completion");
        thread.setDaemon(true);
        return thread;
    });

    VulkanSubmissionRing(GpuDevice device) throws Exception {
        slots = new PersistentVulkanNormalNoiseEngine[SLOT_COUNT];
        int created = 0;
        try {
            slots[0] = new PersistentVulkanNormalNoiseEngine(device);
            created = 1;
            for (; created < slots.length; created++) slots[created] = new PersistentVulkanNormalNoiseEngine(slots[0]);
        } catch (Exception failure) {
            for (int i = 0; i < created; i++) slots[i].close();
            throw failure;
        }
    }

    CompletableFuture<float[]> submitGraphRoots(GpuDensityGraph graph, float[] x, float[] y, float[] z) {
        PersistentVulkanNormalNoiseEngine slot = slots[Math.floorMod(next.getAndIncrement(), slots.length)];
        return CompletableFuture.supplyAsync(() -> {
            try {
                return slot.submitGraphRoots(graph, x, y, z);
            } catch (Exception failure) {
                throw new IllegalStateException("GPU ring graph dispatch failed", failure);
            }
        }, completion);
    }

    CompletableFuture<float[]> submitNoise(GpuGlobalNoiseBatch.Packed batch) {
        PersistentVulkanNormalNoiseEngine slot = slots[Math.floorMod(next.getAndIncrement(), slots.length)];
        return CompletableFuture.supplyAsync(() -> {
            try {
                return slot.submit(batch);
            } catch (Exception failure) {
                throw new IllegalStateException("GPU ring noise dispatch failed", failure);
            }
        }, completion);
    }

    CompletableFuture<float[]> submitInterpolation(float[] corners, ByteBuffer metadata,
                                                    int chunkCount, int blocksPerChunk) {
        PersistentVulkanNormalNoiseEngine slot = slots[Math.floorMod(next.getAndIncrement(), slots.length)];
        return CompletableFuture.supplyAsync(() -> {
            try {
                return slot.submitInterpolation(corners, metadata, chunkCount, blocksPerChunk);
            } catch (Exception failure) {
                throw new IllegalStateException("GPU ring interpolation dispatch failed", failure);
            }
        }, completion);
    }

    CompletableFuture<GpuFusedResult> submitGraphInterpolated(GpuDensityGraph graph, float[] x, float[] y, float[] z,
                                                        ByteBuffer metadata, int chunkCount, int blocksPerChunk) {
        PersistentVulkanNormalNoiseEngine slot = slots[Math.floorMod(next.getAndIncrement(), slots.length)];
        return CompletableFuture.supplyAsync(() -> {
            try {
                return slot.submitGraphInterpolated(graph, x, y, z, metadata, chunkCount, blocksPerChunk);
            } catch (Exception failure) {
                throw new IllegalStateException("GPU fused terrain dispatch failed", failure);
            }
        }, completion);
    }

    @Override public void close() {
        completion.shutdown();
        try {
            if (!completion.awaitTermination(30, TimeUnit.SECONDS)) completion.shutdownNow();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        for (int i = slots.length - 1; i >= 0; i--) {
            if (slots[i] != null) slots[i].close();
        }
    }
}
