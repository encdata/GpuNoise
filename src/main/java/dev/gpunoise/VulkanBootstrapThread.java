package dev.gpunoise;

import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.lang.reflect.Field;
import org.lwjgl.system.Configuration;
import org.lwjgl.system.MemoryStack;

/** Runs Vulkan bootstrap code on a fresh thread with a newly sized LWJGL stack. */
final class VulkanBootstrapThread {
    private VulkanBootstrapThread() {}

    static <T> T call(Callable<T> operation) throws Exception {
        AtomicReference<T> value = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread thread = new Thread(() -> {
            try {
                configureNativeLibraryPath();
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

    /**
     * Mod launchers and server panels can provide a narrower native search
     * path than the login shell that installed Vulkan.  LWJGL's Vulkan
     * binding is initialized after this method, so make the same system paths
     * visible to its loader before VK's static initializer runs.
     */
    private static void configureNativeLibraryPath() {
        LinkedHashSet<String> paths = new LinkedHashSet<>();
        addPathList(paths, System.getenv("LD_LIBRARY_PATH"));
        addPathList(paths, System.getProperty("java.library.path", ""));
        if (isLinux()) {
            addPath(paths, "/usr/lib64");
            addPath(paths, "/usr/lib");
            addPath(paths, "/lib64");
            addPath(paths, "/lib");
            addPath(paths, "/usr/lib/x86_64-linux-gnu");
            addPath(paths, "/lib/x86_64-linux-gnu");
        }
        if (paths.isEmpty()) return;
        String existing = Configuration.LIBRARY_PATH.get();
        addPathList(paths, existing);
        Configuration.LIBRARY_PATH.set(String.join(File.pathSeparator, paths));
    }

    private static void addPathList(LinkedHashSet<String> paths, String value) {
        if (value == null || value.isBlank()) return;
        for (String path : value.split(java.util.regex.Pattern.quote(File.pathSeparator))) addPath(paths, path);
    }

    private static void addPath(LinkedHashSet<String> paths, String path) {
        if (path != null && !path.isBlank() && Files.isDirectory(Path.of(path))) paths.add(path);
    }

    private static boolean isLinux() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("linux");
    }
}
