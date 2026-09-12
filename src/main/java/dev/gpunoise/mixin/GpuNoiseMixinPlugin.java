package dev.gpunoise.mixin;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

public final class GpuNoiseMixinPlugin implements IMixinConfigPlugin {
    private static final String C2ME_BATCH_MIXIN = "dev.gpunoise.mixin.C2mePriorityBatchMixin";
    private static final String C2ME_COMPILED_MIXIN = "dev.gpunoise.mixin.C2meCompiledDensityFunctionMixin";

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
        return true;
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
