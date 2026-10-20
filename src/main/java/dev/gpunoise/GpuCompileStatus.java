package dev.gpunoise;

import java.util.concurrent.atomic.AtomicReference;

/** Thread-safe status shared by world setup and the optional client loading overlay. */
public final class GpuCompileStatus {
    public record Snapshot(String message, long startedAtMillis) {}

    private static final AtomicReference<Snapshot> CURRENT = new AtomicReference<>();

    private GpuCompileStatus() {}

    public static void begin(String message) {
        CURRENT.set(new Snapshot(message, System.currentTimeMillis()));
    }

    public static void update(String message) {
        CURRENT.updateAndGet(current -> current == null ? null
                : new Snapshot(message, current.startedAtMillis()));
    }

    public static void clear() {
        CURRENT.set(null);
    }

    public static Snapshot current() {
        return CURRENT.get();
    }
}
