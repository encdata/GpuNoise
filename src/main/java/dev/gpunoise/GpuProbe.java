package dev.gpunoise;

import java.nio.IntBuffer;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.Configuration;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkApplicationInfo;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkInstanceCreateInfo;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;

/** Enumerates Vulkan devices without creating a compute workload. */
final class GpuProbe {
    private static final int NVIDIA_VENDOR_ID = 0x10DE;
    private static final int AMD_VENDOR_ID = 0x1002;
    private static final int INTEL_VENDOR_ID = 0x8086;

    private GpuProbe() {}

    static GpuDevice findPreferredDevice(GpuNoiseConfig.GpuVendorPreference preference) {
        try {
            return VulkanBootstrapThread.call(() -> findPreferredDeviceOnThread(preference));
        } catch (Throwable failure) {
            if (containsUnsatisfiedLinkError(failure)) {
                GpuNoise.LOGGER.warn("GPU Noise inactive: the system Vulkan loader/driver is unavailable ({}); install a Vulkan runtime and GPU driver.",
                        nativeFailureMessage(failure));
            } else {
                GpuNoise.LOGGER.warn("Vulkan probe failed; leaving vanilla terrain enabled.", failure);
            }
            return null;
        }
    }

    private static boolean containsUnsatisfiedLinkError(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof UnsatisfiedLinkError) return true;
        }
        return false;
    }

    private static String nativeFailureMessage(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof UnsatisfiedLinkError linkError) return linkError.getMessage();
        }
        return failure.getMessage();
    }

    private static GpuDevice findPreferredDeviceOnThread(GpuNoiseConfig.GpuVendorPreference preference) {
        try {
            // Set this before creating the first MemoryStack. Setting it after
            // stackPush() leaves Vulkan's extension query on the tiny default
            // stack and can fail with "Out of stack space" on worker threads.
            // VkInstance construction queries every loader/layer extension.
            // Hybrid laptops can expose enough layer metadata to exceed 1 MiB.
            // Some Windows hybrid-driver loaders expose a very large
            // extension table.  VkInstance builds its dispatch capabilities
            // on this stack, so leave ample room for the probe itself.
            Configuration.STACK_SIZE.set(64 * 1_048_576);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                int version = VK.getInstanceVersionSupported();
                if (version == 0) return null;
                GpuNoise.LOGGER.info("Vulkan loader detected: version {}.{}.{}",
                        VK10.VK_API_VERSION_MAJOR(version), VK10.VK_API_VERSION_MINOR(version), VK10.VK_API_VERSION_PATCH(version));

                VkApplicationInfo appInfo = VkApplicationInfo.calloc(stack).sType$Default()
                    .pApplicationName(stack.UTF8("GPU Noise")).applicationVersion(1)
                    .pEngineName(stack.UTF8("GPU Noise")).engineVersion(1).apiVersion(VK10.VK_API_VERSION_1_0);
                VkInstanceCreateInfo createInfo = VkInstanceCreateInfo.calloc(stack).sType$Default().pApplicationInfo(appInfo);
                PointerBuffer instanceHandle = stack.mallocPointer(1);
                int createResult = VK10.vkCreateInstance(createInfo, null, instanceHandle);
                if (createResult != VK10.VK_SUCCESS) {
                    GpuNoise.LOGGER.warn("Vulkan instance creation failed with result {}.", createResult);
                    return null;
                }

                VkInstance instance = new VkInstance(instanceHandle.get(0), createInfo);
                try {
                IntBuffer deviceCount = stack.ints(0);
                int enumerateResult = VK10.vkEnumeratePhysicalDevices(instance, deviceCount, null);
                if (enumerateResult != VK10.VK_SUCCESS || deviceCount.get(0) == 0) return null;

                PointerBuffer deviceHandles = stack.mallocPointer(deviceCount.get(0));
                if (VK10.vkEnumeratePhysicalDevices(instance, deviceCount, deviceHandles) != VK10.VK_SUCCESS) return null;

                GpuDevice selected = null;
                for (int index = 0; index < deviceCount.get(0); index++) {
                    VkPhysicalDevice physicalDevice = new VkPhysicalDevice(deviceHandles.get(index), instance);
                    VkPhysicalDeviceProperties properties = VkPhysicalDeviceProperties.malloc();
                    try {
                        VK10.vkGetPhysicalDeviceProperties(physicalDevice, properties);
                        GpuDevice device = new GpuDevice(properties.vendorID(), vendorName(properties.vendorID()),
                                properties.deviceNameString(), priority(properties.vendorID(), preference));
                        GpuNoise.LOGGER.info("Vulkan GPU found: {} ({})", device.name(), device.vendorName());
                    if (device.priority() >= 0 && (selected == null || device.priority() > selected.priority())) selected = device;
                    } finally {
                        properties.free();
                    }
                }
                return selected;
                } finally {
                    VK10.vkDestroyInstance(instance, null);
                }
            }
        } catch (Throwable failure) {
            GpuNoise.LOGGER.warn("Vulkan probe failed; leaving vanilla terrain enabled.", failure);
            return null;
        }
    }

    private static int priority(int vendorId, GpuNoiseConfig.GpuVendorPreference preference) {
        if (preference != GpuNoiseConfig.GpuVendorPreference.AUTO) return vendorMatches(vendorId, preference) ? 100 : -1;
        return switch (vendorId) {
            case NVIDIA_VENDOR_ID -> 30;
            case AMD_VENDOR_ID -> 20;
            case INTEL_VENDOR_ID -> 10;
            default -> 1;
        };
    }

    private static boolean vendorMatches(int vendorId, GpuNoiseConfig.GpuVendorPreference preference) {
        return switch (preference) {
            case NVIDIA -> vendorId == NVIDIA_VENDOR_ID;
            case AMD -> vendorId == AMD_VENDOR_ID;
            case INTEL -> vendorId == INTEL_VENDOR_ID;
            case AUTO -> true;
        };
    }

    private static String vendorName(int vendorId) {
        return switch (vendorId) {
            case NVIDIA_VENDOR_ID -> "NVIDIA";
            case AMD_VENDOR_ID -> "AMD";
            case INTEL_VENDOR_ID -> "Intel";
            default -> "Unknown vendor (0x%04X)".formatted(vendorId);
        };
    }
}
