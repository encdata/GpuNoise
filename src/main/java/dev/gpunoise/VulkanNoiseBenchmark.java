package dev.gpunoise;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.ByteOrder;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.Configuration;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.LegacyRandomSource;

import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.EXTDebugUtils.*;

/** Phase 1 benchmark: identical deterministic noise on CPU and Vulkan GPU. */
final class VulkanNoiseBenchmark {
    private static final int SAMPLE_COUNT = 65_536;
    private static final int ITERATIONS = 100;
    private static final int SEED = 0x4A3B2C1D;
    private static VkDebugUtilsMessengerCallbackEXT validationCallback;

    private VulkanNoiseBenchmark() {}

    static void run(GpuDevice expected) {
        Configuration.STACK_SIZE.set(1_048_576);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            CpuNoiseBenchmark.Result cpu = CpuNoiseBenchmark.run(SAMPLE_COUNT, ITERATIONS);
            GpuNoise.LOGGER.info("CPU noise: {} samples x {} in {} ms; checksum/pass={}", SAMPLE_COUNT, ITERATIONS,
                    cpu.elapsedNanos() / 1_000_000.0, cpu.checksum() / ITERATIONS);

            long gpuStart = System.nanoTime();
            float[] gpuResults = dispatch(expected, stack);
            long gpuNanos = System.nanoTime() - gpuStart;
            float gpuChecksum = 0.0f;
            boolean matches = true;
            for (int i = 0; i < SAMPLE_COUNT; i++) {
                gpuChecksum += gpuResults[i];
                float expectedValue = NoiseMath.valueNoise(i & 1023, i >>> 10, SEED);
                if (Math.abs(expectedValue - gpuResults[i]) > 0.000001f) matches = false;
            }
            GpuNoise.LOGGER.info("GPU noise: {} samples x {} in {} ms (setup + dispatch); checksum/pass={}; outputsMatch={}",
                    SAMPLE_COUNT, ITERATIONS, gpuNanos / 1_000_000.0, gpuChecksum, matches);
            runImprovedNoiseParity(expected, stack);
            runMinecraftParameterParity(expected, stack);
        } catch (Throwable failure) {
            GpuNoise.LOGGER.warn("Noise benchmark failed; vanilla terrain remains enabled.", failure);
        }
    }

    private static void runImprovedNoiseParity(GpuDevice expected, MemoryStack stack) throws Exception {
        float[] output = dispatch(expected, stack, "/assets/gpunoise/shaders/improved_noise_parity.comp.spv");
        boolean matches = true;
        float maxError = 0.0f;
        float checksum = 0.0f;
        for (int i = 0; i < SAMPLE_COUNT; i++) {
            float expectedValue = improvedNoiseReference(i & 1023, i >>> 10);
            float error = Math.abs(expectedValue - output[i]);
            checksum += output[i];
            maxError = Math.max(maxError, error);
            if (error > 0.00001f) matches = false;
        }
        GpuNoise.LOGGER.info("ImprovedNoise parity: {} samples; maxError={}; checksum={}; matches={}",
                SAMPLE_COUNT, maxError, checksum, matches);
        if (!matches) throw new IllegalStateException("GPU ImprovedNoise parity failed");
    }

    private static void runMinecraftParameterParity(GpuDevice expected, MemoryStack stack) throws Exception {
        RandomSource random = new LegacyRandomSource(0x1234ABCDL);
        MinecraftNoiseParameters.Improved first = MinecraftNoiseParameters.improved(random);
        MinecraftNoiseParameters.Improved second = MinecraftNoiseParameters.improved(random);
        MinecraftNoiseParameters.Improved[] firstOctave = {first};
        MinecraftNoiseParameters.Improved[] secondOctave = {second};
        double[] amplitudes = {1.0};
        float[] gpu = dispatchMinecraft(expected, stack, firstOctave, secondOctave, amplitudes,
                parityX(), parityY(), parityZ());
        boolean matches = true;
        float maxError = 0.0f;
        for (int i = 0; i < SAMPLE_COUNT; i++) {
            float x = (i & 1023) * 0.03125f;
            float z = (i >>> 10) * 0.027f;
            float expectedValue = MinecraftNoiseReference.normal(firstOctave, secondOctave, amplitudes, x, 0.0f, z, 1.0f);
            float error = Math.abs(expectedValue - gpu[i]);
            maxError = Math.max(maxError, error);
            if (error > 0.00001f) matches = false;
        }
        GpuNoise.LOGGER.info("Minecraft parameter parity: {} samples; maxError={}; matches={}", SAMPLE_COUNT, maxError, matches);
        if (!matches) throw new IllegalStateException("Minecraft parameter parity failed");
    }

    static float[] evaluateNormalNoise(GpuDevice expected, MinecraftNormalNoiseData data,
                                        float[] sampleX, float[] sampleY, float[] sampleZ) throws Exception {
        if (sampleX.length != sampleY.length || sampleX.length != sampleZ.length) throw new IllegalArgumentException("sample arrays differ");
        Configuration.STACK_SIZE.set(1_048_576);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            return dispatchMinecraft(expected, stack, data.first(), data.second(), data.amplitudes(), sampleX, sampleY, sampleZ);
        }
    }

    private static float[] parityX() { float[] values = new float[SAMPLE_COUNT]; for (int i = 0; i < values.length; i++) values[i] = (i & 1023) * 0.03125f; return values; }
    private static float[] parityY() { return new float[SAMPLE_COUNT]; }
    private static float[] parityZ() { float[] values = new float[SAMPLE_COUNT]; for (int i = 0; i < values.length; i++) values[i] = (i >>> 10) * 0.027f; return values; }

    private static float[] dispatchMinecraft(GpuDevice expected, MemoryStack stack,
                                             MinecraftNoiseParameters.Improved[] first,
                                             MinecraftNoiseParameters.Improved[] second,
                                             double[] amplitudes, float[] sampleX, float[] sampleY, float[] sampleZ) throws Exception {
        int sampleCount = sampleX.length;
        VkInstance instance = createInstance(stack);
        VkPhysicalDevice physical = findPhysical(instance, expected, stack);
        int queueFamily = findComputeQueueFamily(physical, stack);
        VkDeviceQueueCreateInfo.Buffer queueInfo = VkDeviceQueueCreateInfo.calloc(1, stack)
                .sType$Default().queueFamilyIndex(queueFamily).pQueuePriorities(stack.floats(1.0f));
        VkDeviceCreateInfo deviceInfo = VkDeviceCreateInfo.calloc(stack).sType$Default().pQueueCreateInfos(queueInfo);
        PointerBuffer deviceHandle = stack.mallocPointer(1);
        check(vkCreateDevice(physical, deviceInfo, null, deviceHandle), "create Minecraft noise device");
        VkDevice device = new VkDevice(deviceHandle.get(0), physical, deviceInfo);
        PointerBuffer queueHandle = stack.mallocPointer(1);
        vkGetDeviceQueue(device, queueFamily, 0, queueHandle);
        VkQueue queue = new VkQueue(queueHandle.get(0), device);

        NativeBuffer coordinates = createBuffer(physical, device, sampleCount * 16L, stack);
        NativeBuffer results = createBuffer(physical, device, sampleCount * 4L, stack);
        ByteBuffer coordinatesData = coordinates.map(device, stack).order(ByteOrder.nativeOrder());
        for (int i = 0; i < sampleCount; i++) {
            coordinatesData.putFloat(sampleX[i]).putFloat(sampleY[i]).putFloat(sampleZ[i]).putFloat(0.0f);
        }
        coordinates.flush(device);
        coordinates.unmap(device);

        ByteBuffer permutationData = MinecraftNoiseParameters.permutationBuffer(first[0], second[0]);
        ByteBuffer octaveData = MinecraftNoiseParameters.octaveBuffer(first, second, amplitudes);
        NativeBuffer permutations = createBuffer(physical, device, permutationData.remaining(), stack);
        NativeBuffer octaves = createBuffer(physical, device, octaveData.remaining(), stack);
        ByteBuffer mappedPermutations = permutations.map(device, stack).order(ByteOrder.nativeOrder());
        mappedPermutations.put(permutationData.duplicate());
        permutations.flush(device); permutations.unmap(device);
        ByteBuffer mappedOctaves = octaves.map(device, stack).order(ByteOrder.nativeOrder());
        mappedOctaves.put(octaveData.duplicate());
        octaves.flush(device); octaves.unmap(device);

        long shader = createShader(device, stack, "/assets/gpunoise/shaders/minecraft_noise.comp.spv");
        long descriptorLayout = createMinecraftDescriptorLayout(device, stack);
        long pipelineLayout = createMinecraftPipelineLayout(device, descriptorLayout, stack);
        long pipeline = createPipeline(device, shader, pipelineLayout, stack);
        long descriptorPool = createMinecraftDescriptorPool(device, stack);
        long descriptorSet = allocateDescriptorSet(device, descriptorPool, descriptorLayout, stack);
        writeMinecraftDescriptors(device, descriptorSet, coordinates, results, permutations, octaves, stack);
        long commandPool = createCommandPool(device, queueFamily, stack);
        VkCommandBuffer commandBuffer = allocateCommandBuffer(device, commandPool, stack);
        check(vkBeginCommandBuffer(commandBuffer, VkCommandBufferBeginInfo.calloc(stack).sType$Default()), "begin Minecraft command buffer");
        vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
        vkCmdBindDescriptorSets(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, pipelineLayout, 0, stack.longs(descriptorSet), null);
        ByteBuffer push = stack.malloc(16).order(ByteOrder.nativeOrder()).putInt(sampleCount).putInt(0).putInt(1).putFloat(1.0f).flip();
        vkCmdPushConstants(commandBuffer, pipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, push);
        vkCmdDispatch(commandBuffer, (sampleCount + 63) / 64, 1, 1);
        VkBufferMemoryBarrier.Buffer barrier = VkBufferMemoryBarrier.calloc(1, stack).sType$Default()
                .srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT).dstAccessMask(VK_ACCESS_HOST_READ_BIT)
                .buffer(results.buffer).offset(0).size(results.size);
        vkCmdPipelineBarrier(commandBuffer, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_HOST_BIT, 0, null, barrier, null);
        check(vkEndCommandBuffer(commandBuffer), "end Minecraft command buffer");
        VkSubmitInfo submit = VkSubmitInfo.calloc(stack).sType$Default().pCommandBuffers(stack.pointers(commandBuffer.address()));
        check(vkQueueSubmit(queue, submit, VK_NULL_HANDLE), "submit Minecraft noise");
        check(vkQueueWaitIdle(queue), "wait Minecraft noise");
        ByteBuffer resultData = results.map(device, stack).order(ByteOrder.nativeOrder());
        results.invalidate(device, stack);
        float[] output = new float[sampleCount];
        resultData.asFloatBuffer().get(output);
        results.unmap(device);
        vkDestroyCommandPool(device, commandPool, null); vkDestroyDescriptorPool(device, descriptorPool, null);
        vkDestroyPipeline(device, pipeline, null); vkDestroyPipelineLayout(device, pipelineLayout, null);
        vkDestroyDescriptorSetLayout(device, descriptorLayout, null); vkDestroyShaderModule(device, shader, null);
        coordinates.destroy(device); results.destroy(device); permutations.destroy(device); octaves.destroy(device);
        vkDestroyDevice(device, null); vkDestroyInstance(instance, null);
        return output;
    }

    private static long createMinecraftDescriptorLayout(VkDevice device, MemoryStack stack) {
        VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(4, stack);
        for (int i = 0; i < 4; i++) bindings.get(i).binding(i).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
        LongBufferWrapper handle = new LongBufferWrapper(stack);
        check(vkCreateDescriptorSetLayout(device, VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(bindings), null, handle.buffer), "create Minecraft descriptor layout");
        return handle.value();
    }

    private static long createMinecraftPipelineLayout(VkDevice device, long descriptorLayout, MemoryStack stack) {
        VkPushConstantRange.Buffer constants = VkPushConstantRange.calloc(1, stack).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(16);
        LongBufferWrapper handle = new LongBufferWrapper(stack);
        check(vkCreatePipelineLayout(device, VkPipelineLayoutCreateInfo.calloc(stack).sType$Default().pSetLayouts(stack.longs(descriptorLayout)).pPushConstantRanges(constants), null, handle.buffer), "create Minecraft pipeline layout");
        return handle.value();
    }

    private static long createMinecraftDescriptorPool(VkDevice device, MemoryStack stack) {
        VkDescriptorPoolSize.Buffer size = VkDescriptorPoolSize.calloc(1, stack).type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(4);
        LongBufferWrapper handle = new LongBufferWrapper(stack);
        check(vkCreateDescriptorPool(device, VkDescriptorPoolCreateInfo.calloc(stack).sType$Default().maxSets(1).pPoolSizes(size), null, handle.buffer), "create Minecraft descriptor pool");
        return handle.value();
    }

    private static void writeMinecraftDescriptors(VkDevice device, long set, NativeBuffer coordinates, NativeBuffer results,
                                                  NativeBuffer permutations, NativeBuffer octaves, MemoryStack stack) {
        VkDescriptorBufferInfo.Buffer a = VkDescriptorBufferInfo.calloc(1, stack).buffer(coordinates.buffer).range(coordinates.size);
        VkDescriptorBufferInfo.Buffer b = VkDescriptorBufferInfo.calloc(1, stack).buffer(results.buffer).range(results.size);
        VkDescriptorBufferInfo.Buffer c = VkDescriptorBufferInfo.calloc(1, stack).buffer(permutations.buffer).range(permutations.size);
        VkDescriptorBufferInfo.Buffer d = VkDescriptorBufferInfo.calloc(1, stack).buffer(octaves.buffer).range(octaves.size);
        VkDescriptorBufferInfo.Buffer[] infos = {a, b, c, d};
        VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(4, stack);
        for (int i = 0; i < 4; i++) writes.get(i).sType$Default().dstSet(set).dstBinding(i).dstArrayElement(0)
                .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).pBufferInfo(infos[i]);
        vkUpdateDescriptorSets(device, writes, null);
    }

    private static float improvedNoiseReference(int x, int z) {
        float px = x * 0.03125f + 17.25f;
        float pz = z * 0.027f + 7.75f;
        int gx = (int)Math.floor(px), gz = (int)Math.floor(pz);
        float dx = px - gx, dz = pz - gz;
        float sx = quintic(dx), sz = quintic(dz);
        float n00 = gradient(gx, gz, dx, 0.0f, dz);
        float n10 = gradient(gx + 1, gz, dx - 1.0f, 0.0f, dz);
        float n01 = gradient(gx, gz + 1, dx, 0.0f, dz - 1.0f);
        float n11 = gradient(gx + 1, gz + 1, dx - 1.0f, 0.0f, dz - 1.0f);
        return lerp(sz, lerp(sx, n00, n10), lerp(sx, n01, n11));
    }

    private static float gradient(int x, int z, float dx, float dy, float dz) {
        int hash = (x & 255);
        hash = (hash + 0) & 255;
        hash = (hash + z) & 255;
        int[][] gradients = {{1,1,0},{-1,1,0},{1,-1,0},{-1,-1,0},{1,0,1},{-1,0,1},{1,0,-1},{-1,0,-1},{0,1,1},{0,-1,1},{0,1,-1},{0,-1,-1},{1,1,0},{0,-1,1},{-1,1,0},{0,-1,-1}};
        int[] g = gradients[hash & 15];
        return g[0] * dx + g[1] * dy + g[2] * dz;
    }

    private static float quintic(float v) { return v * v * v * (v * (v * 6.0f - 15.0f) + 10.0f); }
    private static float lerp(float t, float a, float b) { return a + t * (b - a); }

    private static float[] dispatch(GpuDevice expected, MemoryStack stack) throws Exception {
        return dispatch(expected, stack, "/assets/gpunoise/shaders/noise_benchmark.comp.spv");
    }

    private static float[] dispatch(GpuDevice expected, MemoryStack stack, String shaderResource) throws Exception {
        VkInstance instance = createInstance(stack);
        VkPhysicalDevice physical = findPhysical(instance, expected, stack);
        int queueFamily = findComputeQueueFamily(physical, stack);

        VkDeviceQueueCreateInfo.Buffer queueInfo = VkDeviceQueueCreateInfo.calloc(1, stack)
                .sType$Default().queueFamilyIndex(queueFamily).pQueuePriorities(stack.floats(1.0f));
        VkDeviceCreateInfo deviceInfo = VkDeviceCreateInfo.calloc(stack).sType$Default().pQueueCreateInfos(queueInfo);
        PointerBuffer deviceHandle = stack.mallocPointer(1);
        check(vkCreateDevice(physical, deviceInfo, null, deviceHandle), "create device");
        VkDevice device = new VkDevice(deviceHandle.get(0), physical, deviceInfo);
        PointerBuffer queueHandle = stack.mallocPointer(1);
        vkGetDeviceQueue(device, queueFamily, 0, queueHandle);
        VkQueue queue = new VkQueue(queueHandle.get(0), device);

        NativeBuffer coordinates = createBuffer(physical, device, SAMPLE_COUNT * 8L, stack);
        NativeBuffer results = createBuffer(physical, device, SAMPLE_COUNT * 4L, stack);
        ByteBuffer coordinateData = coordinates.map(device, stack).order(ByteOrder.nativeOrder());
        for (int i = 0; i < SAMPLE_COUNT; i++) coordinateData.putInt(i & 1023).putInt(i >>> 10);
        coordinates.flush(device);
        coordinates.unmap(device);

        long shader = createShader(device, stack, shaderResource);
        long descriptorLayout = createDescriptorLayout(device, stack);
        long pipelineLayout = createPipelineLayout(device, descriptorLayout, stack);
        long pipeline = createPipeline(device, shader, pipelineLayout, stack);
        long descriptorPool = createDescriptorPool(device, stack);
        long descriptorSet = allocateDescriptorSet(device, descriptorPool, descriptorLayout, stack);
        writeDescriptors(device, descriptorSet, coordinates, results, stack);
        long commandPool = createCommandPool(device, queueFamily, stack);
        VkCommandBuffer commandBuffer = allocateCommandBuffer(device, commandPool, stack);

        VkCommandBufferBeginInfo begin = VkCommandBufferBeginInfo.calloc(stack).sType$Default();
        check(vkBeginCommandBuffer(commandBuffer, begin), "begin command buffer");
        vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
        vkCmdBindDescriptorSets(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, pipelineLayout, 0, stack.longs(descriptorSet), null);
        ByteBuffer pushData = stack.malloc(8).order(ByteOrder.nativeOrder())
                .putInt(SEED).putInt(SAMPLE_COUNT).flip();
        vkCmdPushConstants(commandBuffer, pipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, pushData);
        for (int iteration = 0; iteration < ITERATIONS; iteration++) {
            vkCmdDispatch(commandBuffer, (SAMPLE_COUNT + 63) / 64, 1, 1);
        }
        VkBufferMemoryBarrier.Buffer readbackBarrier = VkBufferMemoryBarrier.calloc(1, stack)
                .sType$Default().srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT).dstAccessMask(VK_ACCESS_HOST_READ_BIT)
                .buffer(results.buffer).offset(0).size(results.size);
        vkCmdPipelineBarrier(commandBuffer, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_HOST_BIT,
                0, null, readbackBarrier, null);
        check(vkEndCommandBuffer(commandBuffer), "end command buffer");
        VkSubmitInfo submit = VkSubmitInfo.calloc(stack).sType$Default().pCommandBuffers(stack.pointers(commandBuffer.address()));
        check(vkQueueSubmit(queue, submit, VK_NULL_HANDLE), "submit compute work");
        check(vkQueueWaitIdle(queue), "wait for compute work");

        ByteBuffer resultData = results.map(device, stack).order(ByteOrder.nativeOrder());
        results.invalidate(device, stack);
        FloatBuffer floats = resultData.asFloatBuffer();
        float[] output = new float[SAMPLE_COUNT];
        floats.get(output);
        results.unmap(device);

        vkDestroyCommandPool(device, commandPool, null);
        vkDestroyDescriptorPool(device, descriptorPool, null);
        vkDestroyPipeline(device, pipeline, null);
        vkDestroyPipelineLayout(device, pipelineLayout, null);
        vkDestroyDescriptorSetLayout(device, descriptorLayout, null);
        vkDestroyShaderModule(device, shader, null);
        coordinates.destroy(device);
        results.destroy(device);
        vkDestroyDevice(device, null);
        vkDestroyInstance(instance, null);
        return output;
    }

    private static VkInstance createInstance(MemoryStack stack) {
        boolean validation = Boolean.getBoolean("gpunoise.validation");
        VkApplicationInfo app = VkApplicationInfo.calloc(stack).sType$Default().pApplicationName(stack.UTF8("GPU Noise Benchmark"))
                .applicationVersion(1).pEngineName(stack.UTF8("GPU Noise")).engineVersion(1).apiVersion(VK_API_VERSION_1_0);
        VkInstanceCreateInfo info = VkInstanceCreateInfo.calloc(stack).sType$Default().pApplicationInfo(app)
                ;
        if (validation) {
            validationCallback = VkDebugUtilsMessengerCallbackEXT.create((severity, types, callbackData, userData) -> {
                VkDebugUtilsMessengerCallbackDataEXT data = VkDebugUtilsMessengerCallbackDataEXT.create(callbackData);
                GpuNoise.LOGGER.warn("Vulkan validation: {}", data.pMessageString());
                return VK_FALSE;
            });
            VkDebugUtilsMessengerCreateInfoEXT debug = VkDebugUtilsMessengerCreateInfoEXT.calloc(stack)
                    .sType$Default()
                    .messageSeverity(VK_DEBUG_UTILS_MESSAGE_SEVERITY_VERBOSE_BIT_EXT
                            | VK_DEBUG_UTILS_MESSAGE_SEVERITY_INFO_BIT_EXT
                            | VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT
                            | VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT)
                    .messageType(VK_DEBUG_UTILS_MESSAGE_TYPE_GENERAL_BIT_EXT
                            | VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT
                            | VK_DEBUG_UTILS_MESSAGE_TYPE_PERFORMANCE_BIT_EXT)
                    .pfnUserCallback(validationCallback);
            info.ppEnabledLayerNames(stack.pointers(stack.UTF8("VK_LAYER_KHRONOS_validation")))
                    .ppEnabledExtensionNames(stack.pointers(stack.UTF8("VK_EXT_debug_utils")))
                    .pNext(debug.address());
        }
        PointerBuffer handle = stack.mallocPointer(1);
        check(vkCreateInstance(info, null, handle), "create instance");
        return new VkInstance(handle.get(0), info);
    }

    private static VkPhysicalDevice findPhysical(VkInstance instance, GpuDevice expected, MemoryStack stack) {
        IntBuffer count = stack.ints(0);
        check(vkEnumeratePhysicalDevices(instance, count, null), "enumerate devices");
        PointerBuffer handles = stack.mallocPointer(count.get(0));
        check(vkEnumeratePhysicalDevices(instance, count, handles), "enumerate device handles");
        for (int i = 0; i < count.get(0); i++) {
            VkPhysicalDevice candidate = new VkPhysicalDevice(handles.get(i), instance);
            VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.malloc();
            try {
                vkGetPhysicalDeviceProperties(candidate, props);
                if (props.vendorID() == expected.vendorId() && props.deviceNameString().equals(expected.name())) return candidate;
            } finally { props.free(); }
        }
        throw new IllegalStateException("Selected Vulkan device disappeared");
    }

    private static int findComputeQueueFamily(VkPhysicalDevice device, MemoryStack stack) {
        IntBuffer count = stack.ints(0);
        vkGetPhysicalDeviceQueueFamilyProperties(device, count, null);
        VkQueueFamilyProperties.Buffer families = VkQueueFamilyProperties.malloc(count.get(0), stack);
        vkGetPhysicalDeviceQueueFamilyProperties(device, count, families);
        for (int i = 0; i < count.get(0); i++) if ((families.get(i).queueFlags() & VK_QUEUE_COMPUTE_BIT) != 0) return i;
        throw new IllegalStateException("No compute queue");
    }

    private static long createShader(VkDevice device, MemoryStack stack, String resource) throws Exception {
        byte[] bytes;
        try (var input = VulkanNoiseBenchmark.class.getResourceAsStream(resource)) {
            if (input == null) throw new IllegalStateException("Missing benchmark shader");
            bytes = input.readAllBytes();
        }
        ByteBuffer code = MemoryUtil.memAlloc(bytes.length).put(bytes).flip();
        try {
            VkShaderModuleCreateInfo info = VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(code);
            java.nio.LongBuffer handle = stack.longs(0);
            check(vkCreateShaderModule(device, info, null, handle), "create shader");
            return handle.get(0);
        } finally { MemoryUtil.memFree(code); }
    }

    private static long createDescriptorLayout(VkDevice device, MemoryStack stack) {
        VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(2, stack);
        for (int i = 0; i < 2; i++) bindings.get(i).binding(i).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
        VkDescriptorSetLayoutCreateInfo info = VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(bindings);
        LongBufferWrapper handle = new LongBufferWrapper(stack); check(vkCreateDescriptorSetLayout(device, info, null, handle.buffer), "create descriptor layout"); return handle.value();
    }

    private static long createPipelineLayout(VkDevice device, long descriptorLayout, MemoryStack stack) {
        VkPushConstantRange.Buffer pushConstants = VkPushConstantRange.calloc(1, stack)
                .stageFlags(VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(8);
        VkPipelineLayoutCreateInfo info = VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                .pSetLayouts(stack.longs(descriptorLayout)).pPushConstantRanges(pushConstants);
        LongBufferWrapper handle = new LongBufferWrapper(stack); check(vkCreatePipelineLayout(device, info, null, handle.buffer), "create pipeline layout"); return handle.value();
    }

    private static long createPipeline(VkDevice device, long shader, long layout, MemoryStack stack) {
        VkPipelineShaderStageCreateInfo stage = VkPipelineShaderStageCreateInfo.calloc(stack).sType$Default().stage(VK_SHADER_STAGE_COMPUTE_BIT).module(shader).pName(stack.UTF8("main"));
        VkComputePipelineCreateInfo.Buffer info = VkComputePipelineCreateInfo.calloc(1, stack).sType$Default().stage(stage).layout(layout);
        LongBufferWrapper handle = new LongBufferWrapper(stack); check(vkCreateComputePipelines(device, VK_NULL_HANDLE, info, null, handle.buffer), "create compute pipeline"); return handle.value();
    }

    private static long createDescriptorPool(VkDevice device, MemoryStack stack) {
        VkDescriptorPoolSize.Buffer size = VkDescriptorPoolSize.calloc(1, stack).type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(2);
        VkDescriptorPoolCreateInfo info = VkDescriptorPoolCreateInfo.calloc(stack).sType$Default().maxSets(1).pPoolSizes(size);
        LongBufferWrapper handle = new LongBufferWrapper(stack); check(vkCreateDescriptorPool(device, info, null, handle.buffer), "create descriptor pool"); return handle.value();
    }

    private static long allocateDescriptorSet(VkDevice device, long pool, long layout, MemoryStack stack) {
        VkDescriptorSetAllocateInfo info = VkDescriptorSetAllocateInfo.calloc(stack).sType$Default().descriptorPool(pool).pSetLayouts(stack.longs(layout));
        java.nio.LongBuffer handle = stack.longs(0); check(vkAllocateDescriptorSets(device, info, handle), "allocate descriptor set"); return handle.get(0);
    }

    private static void writeDescriptors(VkDevice device, long set, NativeBuffer coordinates, NativeBuffer results, MemoryStack stack) {
        VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(2, stack);
        VkDescriptorBufferInfo.Buffer coordinateInfo = VkDescriptorBufferInfo.calloc(1, stack).buffer(coordinates.buffer).offset(0).range(coordinates.size);
        VkDescriptorBufferInfo.Buffer resultInfo = VkDescriptorBufferInfo.calloc(1, stack).buffer(results.buffer).offset(0).range(results.size);
        writes.get(0).sType$Default().dstSet(set).dstBinding(0).dstArrayElement(0)
                .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).pBufferInfo(coordinateInfo);
        writes.get(1).sType$Default().dstSet(set).dstBinding(1).dstArrayElement(0)
                .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).pBufferInfo(resultInfo);
        vkUpdateDescriptorSets(device, writes, null);
    }

    private static long createCommandPool(VkDevice device, int family, MemoryStack stack) {
        VkCommandPoolCreateInfo info = VkCommandPoolCreateInfo.calloc(stack).sType$Default().queueFamilyIndex(family);
        LongBufferWrapper handle = new LongBufferWrapper(stack); check(vkCreateCommandPool(device, info, null, handle.buffer), "create command pool"); return handle.value();
    }

    private static VkCommandBuffer allocateCommandBuffer(VkDevice device, long pool, MemoryStack stack) {
        VkCommandBufferAllocateInfo info = VkCommandBufferAllocateInfo.calloc(stack).sType$Default().commandPool(pool).level(VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(1);
        PointerBuffer handle = stack.mallocPointer(1); check(vkAllocateCommandBuffers(device, info, handle)); return new VkCommandBuffer(handle.get(0), device);
    }

    private static NativeBuffer createBuffer(VkPhysicalDevice physical, VkDevice device, long size, MemoryStack stack) {
        VkBufferCreateInfo info = VkBufferCreateInfo.calloc(stack).sType$Default().size(size)
                .usage(VK_BUFFER_USAGE_STORAGE_BUFFER_BIT)
                .sharingMode(VK_SHARING_MODE_EXCLUSIVE);
        LongBufferWrapper buffer = new LongBufferWrapper(stack); check(vkCreateBuffer(device, info, null, buffer.buffer), "create buffer");
        VkMemoryRequirements requirements = VkMemoryRequirements.malloc(stack); vkGetBufferMemoryRequirements(device, buffer.value(), requirements);
        VkPhysicalDeviceMemoryProperties memory = VkPhysicalDeviceMemoryProperties.malloc(stack); vkGetPhysicalDeviceMemoryProperties(physical, memory);
        int type = -1; for (int i = 0; i < memory.memoryTypeCount(); i++) if ((requirements.memoryTypeBits() & (1 << i)) != 0 && (memory.memoryTypes(i).propertyFlags() & (VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT)) != 0) { type = i; break; }
        if (type < 0) throw new IllegalStateException("No host-visible memory");
        VkMemoryAllocateInfo allocation = VkMemoryAllocateInfo.calloc(stack).sType$Default().allocationSize(requirements.size()).memoryTypeIndex(type);
        LongBufferWrapper memoryHandle = new LongBufferWrapper(stack); check(vkAllocateMemory(device, allocation, null, memoryHandle.buffer), "allocate buffer memory");
        check(vkBindBufferMemory(device, buffer.value(), memoryHandle.value(), 0), "bind buffer memory");
        return new NativeBuffer(buffer.value(), memoryHandle.value(), size);
    }

    private static void check(int result) { check(result, "Vulkan call"); }
    private static void check(int result, String operation) { if (result != VK_SUCCESS) throw new IllegalStateException(operation + " failed: " + result); }
    private record NativeBuffer(long buffer, long memory, long size) {
        ByteBuffer map(VkDevice device, MemoryStack stack) { PointerBuffer pointer = stack.mallocPointer(1); check(vkMapMemory(device, memory, 0, size, 0, pointer), "map memory"); return MemoryUtil.memByteBuffer(pointer.get(0), (int) size); }
        void flush(VkDevice device) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkMappedMemoryRange range = VkMappedMemoryRange.calloc(stack).sType$Default().memory(memory).offset(0).size(size);
                check(vkFlushMappedMemoryRanges(device, range), "flush mapped memory");
            }
        }
        void invalidate(VkDevice device, MemoryStack stack) {
            VkMappedMemoryRange range = VkMappedMemoryRange.calloc(stack).sType$Default().memory(memory).offset(0).size(size);
            check(vkInvalidateMappedMemoryRanges(device, range), "invalidate mapped memory");
        }
        void unmap(VkDevice device) { vkUnmapMemory(device, memory); }
        void destroy(VkDevice device) { vkDestroyBuffer(device, buffer, null); vkFreeMemory(device, memory, null); }
    }
    private static final class LongBufferWrapper { final java.nio.LongBuffer buffer; LongBufferWrapper(MemoryStack stack) { buffer = stack.longs(0); } long value() { return buffer.get(0); } }
}
