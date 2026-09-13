package dev.gpunoise;

import java.util.ArrayDeque;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

/** Bounds complete GPU-cache-plus-CPU-fill operations without blocking workers. */
final class GpuChunkPipelineGate {
    private final int maxInFlight;
    private int inFlight;
    private final ArrayDeque<Runnable> waiting = new ArrayDeque<>();

    GpuChunkPipelineGate(int maxInFlight) {
        this.maxInFlight = maxInFlight;
    }

    <T> CompletableFuture<T> submit(Supplier<? extends CompletionStage<T>> operation) {
        CompletableFuture<T> result = new CompletableFuture<>();
        Runnable starter = () -> start(operation, result);
        boolean startNow;
        synchronized (this) {
            startNow = inFlight < maxInFlight;
            if (startNow) inFlight++;
            else waiting.addLast(starter);
        }
        if (startNow) starter.run();
        return result;
    }

    private <T> void start(Supplier<? extends CompletionStage<T>> operation, CompletableFuture<T> result) {
        try {
            CompletionStage<T> stage = operation.get();
            if (stage == null) throw new NullPointerException("GPU pipeline operation returned null");
            stage.whenComplete((value, failure) -> {
                if (failure != null) result.completeExceptionally(failure);
                else result.complete(value);
                finish();
            });
        } catch (Throwable failure) {
            result.completeExceptionally(failure);
            finish();
        }
    }

    private void finish() {
        Runnable next = null;
        synchronized (this) {
            inFlight--;
            if (!waiting.isEmpty()) {
                next = waiting.removeFirst();
                inFlight++;
            }
        }
        if (next != null) next.run();
    }
}
