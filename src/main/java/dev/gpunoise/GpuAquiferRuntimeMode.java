package dev.gpunoise;

import net.neoforged.fml.ModList;

/** Detects an active optional aquifer algorithm whose decision contract differs from vanilla. */
public final class GpuAquiferRuntimeMode {
    private static final String C2ME_CONFIG =
            "com.ishland.c2me.opts.worldgen.vanilla.common.Config";

    private GpuAquiferRuntimeMode() {}

    /**
     * C2ME's optional aquifer rewrite changes nearest-center ordering and
     * fluid-tick decisions. Its module is optional, so inspect its runtime
     * config reflectively and conservatively use its GPU contract on failure.
     */
    public static boolean c2meAquiferRewriteEnabled() {
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
