package dev.gpunoise;

import java.util.ArrayDeque;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
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
        return submit(operation, ignored -> {});
    }

    /**
     * Submits work with an operation-owned cancellation hook. The hook owns
     * cancellation semantics because cancelling an arbitrary dependent
     * CompletableFuture can complete its wrapper while the underlying GPU or
     * CPU work keeps running, which would release this gate slot too early.
     */
    <T> CompletableFuture<T> submit(Supplier<? extends CompletionStage<T>> operation,
                                    Consumer<Boolean> cancellation) {
        if (operation == null) throw new NullPointerException("operation");
        if (cancellation == null) throw new NullPointerException("cancellation");
        Task<T> task = new Task<>(operation, cancellation);
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

        boolean cancelOperation;
        synchronized (this) {
            task.stageReady = true;
            cancelOperation = (task.cancellationRequested || task.result.isCancelled())
                    && !task.cancellationHookInvoked;
            if (cancelOperation) task.cancellationHookInvoked = true;
        }
        if (cancelOperation) invokeCancellation(task);

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
        boolean invokeCancellation = false;
        boolean releaseReservedSlot = false;
        synchronized (this) {
            if (task.finished) return;
            if (task.started) {
                task.cancellationRequested = true;
                invokeCancellation = task.stageReady && !task.cancellationHookInvoked;
                if (invokeCancellation) task.cancellationHookInvoked = true;
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
        if (invokeCancellation) invokeCancellation(task);
        if (releaseReservedSlot) finish(task);
    }

    private static void invokeCancellation(Task<?> task) {
        try {
            task.cancellation.accept(true);
        } catch (Throwable ignored) {
            // Cancellation is best effort; the operation's stage still owns
            // the slot until its actual completion is observed.
        }
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
        boolean stageReady;
        boolean cancellationRequested;
        boolean cancellationHookInvoked;
        final Consumer<Boolean> cancellation;

        Task(Supplier<? extends CompletionStage<T>> operation, Consumer<Boolean> cancellation) {
            this.operation = operation;
            this.cancellation = cancellation;
        }
    }
}
