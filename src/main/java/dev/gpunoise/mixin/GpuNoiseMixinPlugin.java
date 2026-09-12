package dev.gpunoise.mixin;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

public final class GpuNoiseMixinPlugin implements IMixinConfigPlugin {
    private static final String C2ME_BATCH_MIXIN = "dev.gpunoise.mixin.C2mePriorityBatchMixin";

    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (!C2ME_BATCH_MIXIN.equals(mixinClassName) && !mixinClassName.endsWith("C2mePriorityBatchMixin")) return true;
        String target = "com.ishland.c2me.base.common.scheduler.SchedulingManager";
        ClassLoader[] loaders = {
                Thread.currentThread().getContextClassLoader(),
                GpuNoiseMixinPlugin.class.getClassLoader(),
                ClassLoader.getSystemClassLoader()
        };
        for (ClassLoader loader : loaders) {
            if (loader == null) continue;
            try {
                Class.forName(target, false, loader);
                return true;
            } catch (ClassNotFoundException | LinkageError ignored) {
                // Try the next NeoForge/module classloader.
            }
        }
        return false;
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
