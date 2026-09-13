package dev.gpunoise.mixin;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;
import java.util.LinkedHashSet;

public final class GpuNoiseMixinPlugin implements IMixinConfigPlugin {
    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        // Apply the normal Minecraft accessors as well as the optional C2ME
        // integrations. The config is optional, so missing C2ME targets are
        // allowed on installations that do not have C2ME installed.
        // The optional C2ME target is resolved by Mixin after plugin loading;
        // probing it here can use a different loader and incorrectly skip
        // the bridge when C2ME is present.
        if (mixinClassName.endsWith("C2meCompiledDensityFunctionMixin")) {
            return findClass(targetClassName) != null;
        }
        return true;
    }

    private static Class<?> findClass(String name) {
        Set<ClassLoader> loaders = new LinkedHashSet<>();
        addLoader(loaders, GpuNoiseMixinPlugin.class.getClassLoader());
        addLoader(loaders, Thread.currentThread().getContextClassLoader());
        addLoader(loaders, ClassLoader.getSystemClassLoader());
        for (ClassLoader loader : loaders) {
            try {
                return Class.forName(name, false, loader);
            } catch (ClassNotFoundException | LinkageError ignored) {
                // Try the next loader; NeoForge and C2ME can use separate
                // transforming loaders during early server startup.
            }
        }
        return null;
    }

    private static void addLoader(Set<ClassLoader> loaders, ClassLoader loader) {
        while (loader != null && loaders.add(loader)) loader = loader.getParent();
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}
