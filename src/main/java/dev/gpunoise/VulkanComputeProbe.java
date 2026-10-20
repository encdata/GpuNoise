package dev.gpunoise;

import java.nio.IntBuffer;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.Configuration;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkApplicationInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkDeviceCreateInfo;
import org.lwjgl.vulkan.VkDeviceQueueCreateInfo;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkInstanceCreateInfo;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures;

/** Creates and destroys a headless compute device to verify real GPU availability. */
final class VulkanComputeProbe {
    private VulkanComputeProbe() {}

    static boolean verify(GpuDevice expected) {
        try {
            return VulkanBootstrapThread.call(() -> verifyOnThread(expected));
        } catch (Throwable failure) {
            GpuNoise.LOGGER.warn("Vulkan compute probe failed; leaving vanilla terrain enabled.", failure);
            return false;
        }
    }

    private static boolean verifyOnThread(GpuDevice expected) {
        Configuration.STACK_SIZE.set(64 * 1_048_576);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkApplicationInfo appInfo = VkApplicationInfo.calloc(stack).sType$Default()
                    .pApplicationName(stack.UTF8("GPU Noise Compute Probe"))
                    .applicationVersion(1).pEngineName(stack.UTF8("GPU Noise")).engineVersion(1)
                    .apiVersion(VK10.VK_API_VERSION_1_0);
            VkInstanceCreateInfo instanceInfo = VkInstanceCreateInfo.calloc(stack).sType$Default().pApplicationInfo(appInfo);
            PointerBuffer instanceHandle = stack.mallocPointer(1);
            if (VK10.vkCreateInstance(instanceInfo, null, instanceHandle) != VK10.VK_SUCCESS) return false;

            VkInstance instance = new VkInstance(instanceHandle.get(0), instanceInfo);
            try {
                VkPhysicalDevice physicalDevice = findDevice(instance, expected, stack);
                if (physicalDevice == null) return false;
                int queueFamily = VulkanQueueSelection.findComputeFamily(physicalDevice, stack);
                if (queueFamily < 0) {
                    GpuNoise.LOGGER.warn("Selected Vulkan GPU has no compute queue.");
                    return false;
                }
                VkPhysicalDeviceFeatures features = VkPhysicalDeviceFeatures.malloc(stack);
                VK10.vkGetPhysicalDeviceFeatures(physicalDevice, features);
                if (!features.shaderFloat64()) {
                    GpuNoise.LOGGER.info("Selected Vulkan GPU has compute but no shaderFloat64 support; exact blended graph path is unavailable.");
                }

                VkDeviceQueueCreateInfo.Buffer queueInfo = VkDeviceQueueCreateInfo.calloc(1, stack)
                        .sType$Default().queueFamilyIndex(queueFamily).pQueuePriorities(stack.floats(1.0f));
                VkDeviceCreateInfo deviceInfo = VkDeviceCreateInfo.calloc(stack).sType$Default().pQueueCreateInfos(queueInfo);
                PointerBuffer deviceHandle = stack.mallocPointer(1);
                int deviceResult = VK10.vkCreateDevice(physicalDevice, deviceInfo, null, deviceHandle);
                if (deviceResult != VK10.VK_SUCCESS) {
                    GpuNoise.LOGGER.warn("Vulkan compute-device creation failed with result {}.", deviceResult);
                    return false;
                }

                VkDevice device = new VkDevice(deviceHandle.get(0), physicalDevice, deviceInfo);
                try {
                    PointerBuffer queueHandle = stack.mallocPointer(1);
                    VK10.vkGetDeviceQueue(device, queueFamily, 0, queueHandle);
                    if (queueHandle.get(0) == 0L) return false;
                    GpuNoise.LOGGER.info("Vulkan compute queue is ready on {} (queue family {}).", expected.name(), queueFamily);
                    return true;
                } finally {
                    VK10.vkDestroyDevice(device, null);
                }
            } finally {
                VK10.vkDestroyInstance(instance, null);
            }
        } catch (Throwable failure) {
            GpuNoise.LOGGER.warn("Vulkan compute probe failed; leaving vanilla terrain enabled.", failure);
            return false;
        }
    }

    static boolean supportsFloat64(GpuDevice expected) {
        try {
            return VulkanBootstrapThread.call(() -> supportsFloat64OnThread(expected));
        } catch (Throwable failure) {
            GpuNoise.LOGGER.warn("Vulkan FP64 capability probe failed.", failure);
            return false;
        }
    }

    private static boolean supportsFloat64OnThread(GpuDevice expected) {
        // Device-extension/capability enumeration can allocate substantially
        // more than LWJGL's default per-thread stack on hybrid Windows
        // loaders. Keep this probe consistent with the device probe above.
        Configuration.STACK_SIZE.set(64 * 1_048_576);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkApplicationInfo appInfo = VkApplicationInfo.calloc(stack).sType$Default()
                    .pApplicationName(stack.UTF8("GPU Noise FP64 Probe")).applicationVersion(1)
                    .pEngineName(stack.UTF8("GPU Noise")).engineVersion(1).apiVersion(VK10.VK_API_VERSION_1_0);
            VkInstanceCreateInfo instanceInfo = VkInstanceCreateInfo.calloc(stack).sType$Default().pApplicationInfo(appInfo);
            PointerBuffer handle = stack.mallocPointer(1);
            if (VK10.vkCreateInstance(instanceInfo, null, handle) != VK10.VK_SUCCESS) return false;
            VkInstance instance = new VkInstance(handle.get(0), instanceInfo);
            try {
                VkPhysicalDevice physical = findDevice(instance, expected, stack);
                if (physical == null) return false;
                VkPhysicalDeviceFeatures features = VkPhysicalDeviceFeatures.malloc(stack);
                VK10.vkGetPhysicalDeviceFeatures(physical, features);
                return features.shaderFloat64();
            } finally {
                VK10.vkDestroyInstance(instance, null);
            }
        } catch (Throwable failure) {
            GpuNoise.LOGGER.warn("Vulkan FP64 capability probe failed.", failure);
            return false;
        }
    }

    private static VkPhysicalDevice findDevice(VkInstance instance, GpuDevice expected, MemoryStack stack) {
        IntBuffer count = stack.ints(0);
        if (VK10.vkEnumeratePhysicalDevices(instance, count, null) != VK10.VK_SUCCESS) return null;
        PointerBuffer handles = stack.mallocPointer(count.get(0));
        if (VK10.vkEnumeratePhysicalDevices(instance, count, handles) != VK10.VK_SUCCESS) return null;
        for (int i = 0; i < count.get(0); i++) {
            if (i != expected.enumerationIndex()) continue;
            VkPhysicalDevice candidate = new VkPhysicalDevice(handles.get(i), instance);
            VkPhysicalDeviceProperties properties = VkPhysicalDeviceProperties.malloc();
            try {
                VK10.vkGetPhysicalDeviceProperties(candidate, properties);
                if (properties.vendorID() == expected.vendorId()
                        && properties.deviceID() == expected.deviceId()
                        && properties.deviceNameString().equals(expected.name())) return candidate;
            } finally {
                properties.free();
            }
        }
        return null;
    }

}
