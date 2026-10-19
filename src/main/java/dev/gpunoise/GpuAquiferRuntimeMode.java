package dev.gpunoise;

import net.neoforged.fml.ModList;

/** Detects an active optional aquifer algorithm whose decision contract differs from vanilla. */
public final class GpuAquiferRuntimeMode {
    private static final String C2ME_CONFIG =
            "com.ishland.c2me.opts.worldgen.vanilla.common.Config";
    private static volatile Boolean c2meAquiferRewrite;
    private static final boolean EXACT_AQUIFER_INPUTS = Boolean.getBoolean("gpunoise.exactAquiferInputs");

    private GpuAquiferRuntimeMode() {}

    /**
     * C2ME's optional aquifer rewrite changes nearest-center ordering and
     * fluid-tick decisions. Its module is optional, so inspect its runtime
     * config reflectively and conservatively use its GPU contract on failure.
     */
    public static boolean c2meAquiferRewriteEnabled() {
        Boolean cached = c2meAquiferRewrite;
        if (cached != null) return cached;
        synchronized (GpuAquiferRuntimeMode.class) {
            cached = c2meAquiferRewrite;
            if (cached != null) return cached;
            c2meAquiferRewrite = cached = detectC2meAquiferRewrite();
        }
        return cached;
    }

    /** Keeps discrete aquifer inputs on Java when exact parity is requested. */
    public static boolean exactAquiferInputsEnabled() {
        return EXACT_AQUIFER_INPUTS;
    }

    private static boolean detectC2meAquiferRewrite() {
        if (!ModList.get().isLoaded("c2me")) return false;
        try {
            ClassLoader loader = GpuAquiferRuntimeMode.class.getClassLoader();
            Class<?> config = Class.forName(C2ME_CONFIG, true, loader);
            return config.getField("optimizeAquifer").getBoolean(null);
        } catch (ReflectiveOperationException | LinkageError unavailable) {
            return true;
        }
    }
}
