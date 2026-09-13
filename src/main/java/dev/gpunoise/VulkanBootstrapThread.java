package dev.gpunoise;

import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;

/** Runs Vulkan bootstrap code on a fresh thread with a newly sized LWJGL stack. */
final class VulkanBootstrapThread {
    private VulkanBootstrapThread() {}

    static <T> T call(Callable<T> operation) throws Exception {
        AtomicReference<T> value = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread thread = new Thread(() -> {
            try {
                value.set(operation.call());
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        }, "GPU Vulkan bootstrap");
        thread.start();
        try {
            thread.join();
        } catch (InterruptedException interrupted) {
            thread.interrupt();
            Thread.currentThread().interrupt();
            throw interrupted;
        }
        Throwable throwable = failure.get();
        if (throwable != null) {
            if (throwable instanceof Exception exception) throw exception;
            if (throwable instanceof Error error) throw error;
            throw new RuntimeException(throwable);
        }
        return value.get();
    }
}
