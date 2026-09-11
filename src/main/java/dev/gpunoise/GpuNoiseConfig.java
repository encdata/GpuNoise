package dev.gpunoise;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Server configuration for Vulkan device selection. */
public final class GpuNoiseConfig {
    enum GpuVendorPreference { AUTO, NVIDIA, AMD, INTEL }

    static final ModConfigSpec SPEC;
    static final ModConfigSpec.EnumValue<GpuVendorPreference> GPU_VENDOR;
    static final ModConfigSpec.IntValue MIN_GPU_SAMPLES;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        builder.push("vulkan");
        GPU_VENDOR = builder.comment("AUTO prefers NVIDIA, then AMD, then Intel. Select a vendor to force it when available.")
                .defineEnum("gpuVendor", GpuVendorPreference.AUTO);
        MIN_GPU_SAMPLES = builder.comment("Minimum samples in one vanilla fillArray call before dispatching to Vulkan. Higher values avoid GPU dispatch/readback overhead on small terrain subgraphs.")
                .defineInRange("minimumGpuSamples", 8192, 1, 262144);
        builder.pop();
        SPEC = builder.build();
    }

    private GpuNoiseConfig() {}
}
