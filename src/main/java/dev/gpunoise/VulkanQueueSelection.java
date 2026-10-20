package dev.gpunoise;

import java.nio.IntBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkQueueFamilyProperties;

/** Selects the same compute queue family during probing, smoke checks, and runtime setup. */
final class VulkanQueueSelection {
    private VulkanQueueSelection() {}

    static int findComputeFamily(VkPhysicalDevice device, MemoryStack stack) {
        IntBuffer count = stack.ints(0);
        VK10.vkGetPhysicalDeviceQueueFamilyProperties(device, count, null);
        if (count.get(0) == 0) return -1;
        VkQueueFamilyProperties.Buffer families = VkQueueFamilyProperties.malloc(count.get(0), stack);
        VK10.vkGetPhysicalDeviceQueueFamilyProperties(device, count, families);

        int fallback = -1;
        for (int index = 0; index < count.get(0); index++) {
            VkQueueFamilyProperties family = families.get(index);
            if (family.queueCount() == 0
                    || (family.queueFlags() & VK10.VK_QUEUE_COMPUTE_BIT) == 0) continue;
            if ((family.queueFlags() & VK10.VK_QUEUE_GRAPHICS_BIT) == 0) return index;
            fallback = index;
        }
        return fallback;
    }
}
