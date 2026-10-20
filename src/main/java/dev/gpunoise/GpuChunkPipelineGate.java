package dev.gpunoise;

import java.util.ArrayDeque;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Future;
import java.util.function.Supplier;

/** Bounds complete GPU-cache-plus-CPU-fill operations without blocking workers. */
final class GpuChunkPipelineGate {
    private final int maxInFlight;
    private int inFlight;
    private final ArrayDeque<Task<?>> waiting = new ArrayDeque<>();

    GpuChunkPipelineGate(int maxInFlight) {
        if (maxInFlight <= 0) throw new IllegalArgumentException("maxInFlight must be positive");
        this.maxInFlight = maxInFlight;
    }

    <T> CompletableFuture<T> submit(Supplier<? extends CompletionStage<T>> operation) {
        if (operation == null) throw new NullPointerException("operation");
        Task<T> task = new Task<>(operation);
        task.result.whenComplete((ignored, failure) -> {
            if (task.result.isCancelled()) cancel(task);
        });

        boolean startNow;
        synchronized (this) {
            startNow = inFlight < maxInFlight;
            if (startNow) {
                inFlight++;
                task.slotReserved = true;
            } else {
                waiting.addLast(task);
            }
        }
        if (startNow) start(task);
        return task.result;
    }

    private <T> void start(Task<T> task) {
        Task<?> next = null;
        boolean cancelledBeforeStart = false;
        synchronized (this) {
            if (task.finished) return;
            task.started = true;
            if (task.result.isCancelled()) {
                // Cancellation can race with promotion out of the waiting
                // queue. Release the reserved slot through the normal path.
                next = finishLocked(task);
                cancelledBeforeStart = true;
            }
        }
        if (cancelledBeforeStart) {
            if (next != null) start(next);
            return;
        }

        final CompletionStage<T> stage;
        try {
            stage = task.operation.get();
            if (stage == null) throw new NullPointerException("GPU pipeline operation returned null");
        } catch (Throwable failure) {
            task.result.completeExceptionally(failure);
            finish(task);
            return;
        }

        Future<?> cancellable = null;
        try {
            cancellable = stage.toCompletableFuture();
        } catch (RuntimeException ignored) {
            // Some CompletionStage implementations do not expose a Future.
        }
        boolean cancelStage;
        synchronized (this) {
            task.activeStage = cancellable;
            cancelStage = task.result.isCancelled();
        }
        if (cancelStage && cancellable != null) cancellable.cancel(true);

        try {
            stage.whenComplete((value, failure) -> {
                if (failure != null) task.result.completeExceptionally(failure);
                else task.result.complete(value);
                finish(task);
            });
        } catch (Throwable failure) {
            task.result.completeExceptionally(failure);
            finish(task);
        }
    }

    private void cancel(Task<?> task) {
        Future<?> active = null;
        boolean releaseReservedSlot = false;
        synchronized (this) {
            if (task.finished) return;
            if (task.started) {
                active = task.activeStage;
            } else if (task.slotReserved) {
                // A promoted task may be cancelled before its starter runs.
                // Mark it finished now; the queued starter will become a no-op.
                task.started = true;
                releaseReservedSlot = true;
            } else {
                waiting.remove(task);
                task.finished = true;
            }
        }
        if (active != null) active.cancel(true);
        if (releaseReservedSlot) finish(task);
    }

    private void finish(Task<?> task) {
        Task<?> next;
        synchronized (this) {
            next = finishLocked(task);
        }
        if (next != null) start(next);
    }

    /** Updates slot ownership while holding this gate's monitor. */
    private Task<?> finishLocked(Task<?> task) {
        if (task.finished) return null;
        task.finished = true;
        task.activeStage = null;
        if (task.slotReserved) {
            task.slotReserved = false;
            inFlight--;
        }
        while (!waiting.isEmpty()) {
            Task<?> next = waiting.removeFirst();
            if (next.finished || next.result.isCancelled()) {
                next.finished = true;
                continue;
            }
            next.slotReserved = true;
            inFlight++;
            return next;
        }
        return null;
    }

    private static final class Task<T> {
        final Supplier<? extends CompletionStage<T>> operation;
        final CompletableFuture<T> result = new CompletableFuture<>();
        boolean slotReserved;
        boolean started;
        boolean finished;
        Future<?> activeStage;

        Task(Supplier<? extends CompletionStage<T>> operation) {
            this.operation = operation;
        }
    }
}
