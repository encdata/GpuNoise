package dev.gpunoise;

import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import java.lang.reflect.Field;
import org.lwjgl.system.MemoryStack;

/** Runs Vulkan bootstrap code on a fresh thread with a newly sized LWJGL stack. */
final class VulkanBootstrapThread {
    private VulkanBootstrapThread() {}

    static <T> T call(Callable<T> operation) throws Exception {
        AtomicReference<T> value = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread thread = new Thread(() -> {
            try {
                installLargeStack();
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

    static <T> T callUnchecked(Callable<T> operation) {
        try {
            return call(operation);
        } catch (RuntimeException | Error failure) {
            throw failure;
        } catch (Exception failure) {
            throw new RuntimeException(failure);
        }
    }

    /**
     * LWJGL freezes its default stack size when MemoryStack is initialized.
     * Minecraft can initialize that class before a mod reaches server-start,
     * so changing Configuration.STACK_SIZE later does not resize the TLS
     * stack. Install an explicit stack for this short-lived bootstrap thread.
     */
    @SuppressWarnings("unchecked")
    private static void installLargeStack() throws ReflectiveOperationException {
        MemoryStack large = MemoryStack.create(64 * 1_048_576);
        Field tlsField = MemoryStack.class.getDeclaredField("TLS");
        if (!tlsField.trySetAccessible()) {
            throw new IllegalStateException("LWJGL MemoryStack TLS is inaccessible");
        }
        ((ThreadLocal<MemoryStack>) tlsField.get(null)).set(large);
    }
}
