package dev.gpunoise;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.nio.IntBuffer;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.Configuration;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;
import static org.lwjgl.vulkan.VK10.*;

/** Reusable Vulkan resources for repeated NormalNoise submissions. */
final class PersistentVulkanNormalNoiseEngine implements AutoCloseable {
    private static final boolean FAST_GRAPH_MATH = Boolean.getBoolean("gpunoise.fastMath");
    static final int CAPACITY = 262_144;
    static final int GRAPH_NODE_CAPACITY = 2048;
    // interpolationOutput stores the compact per-block final-density output.
    static final int INTERPOLATION_OUTPUT_FLOATS = CAPACITY * 16;
    private final VkInstance instance;
    private final VkPhysicalDevice physical;
    private final VkDevice device;
    private final VkQueue queue;
    private final int queueFamily;
    private final NativeBuffer coordinates, results, permutations, octaves, samplerMetadata;
    private final NativeBuffer graphCoordinates, graphNodes, graphSplinePoints, graphBlendedMetadata,
            graphBlendInputs, graphRoots, graphMthSinTable;
    private final NativeBuffer interpolationMetadata, interpolationOutput, aquiferPressureInputs,
            aquiferNearestInputs, aquiferNearestOutput;
    private final long shader, descriptorLayout, pipelineLayout, pipeline, descriptorPool, descriptorSet, commandPool;
    private long graphShader, gridShader, graphPipeline, gridPipeline;
    private final long graphDescriptorLayout, graphPipelineLayout, graphDescriptorPool, graphDescriptorSet;
    private int graphRegisterVariant = 256;
    private boolean graphFastMathVariant;
    private final long interpolationShader, interpolationDescriptorLayout, interpolationPipelineLayout,
            interpolationPipeline, interpolationDescriptorPool, interpolationDescriptorSet;
    private final long aquiferPressureShader, aquiferPressureDescriptorLayout, aquiferPressurePipelineLayout,
            aquiferPressurePipeline, aquiferPressureDescriptorPool, aquiferPressureDescriptorSet;
    private final long aquiferNearestShader, aquiferNearestDescriptorLayout, aquiferNearestPipelineLayout,
            aquiferNearestPipeline, aquiferNearestDescriptorPool, aquiferNearestDescriptorSet;
    private final long aquiferSubstanceShader, aquiferSubstancePipelineLayout, aquiferSubstancePipeline;
    private final boolean ownsDevice;
    /** Vulkan requires host-side external synchronization for queue operations. */
    private final Object queueSubmitLock;
    private ByteBuffer persistentResults;
    private final Map<NativeBuffer, ByteBuffer> persistentMappings = new IdentityHashMap<>();
    private final Map<GpuDensityGraph, GpuDensityGraphBuffer.Packed> packedGraphs = new IdentityHashMap<>();
    // The startup parity pass uploads the full router graph, then terrain
    // generation uploads a reduced graph. A boolean cache flag incorrectly
    // treated those different layouts as interchangeable.
    private GpuDensityGraph graphResourcesGraph;
    private final long fence;
    private final VkCommandBuffer commandBuffer;
    /** Exactly one graph-roots dispatch owns this command buffer and mapped output at a time. */
    private CompletableFuture<float[]> inFlightGraphRoots;
    private int inFlightSamples;
    private int inFlightRoots;
    private long inFlightFenceSubmittedNanos;
    private long inFlightGraphPreparationNanos;
    private long completedGraphFenceSamples;
    private long totalGraphFenceNanos;
    private long totalGraphPreparationNanos;
    private long totalGraphReadbackNanos;

    PersistentVulkanNormalNoiseEngine(GpuDevice expected) throws Exception {
        Configuration.STACK_SIZE.set(64 * 1_048_576);
        MemoryStack stack = MemoryStack.stackPush();
        try {
            instance = createInstance(stack);
            physical = findPhysical(instance, expected, stack);
            queueFamily = findQueue(physical, stack);
            VkDeviceQueueCreateInfo.Buffer qi = VkDeviceQueueCreateInfo.calloc(1, stack).sType$Default()
                    .queueFamilyIndex(queueFamily).pQueuePriorities(stack.floats(1.0f));
            VkPhysicalDeviceFeatures availableFeatures = VkPhysicalDeviceFeatures.calloc(stack);
            vkGetPhysicalDeviceFeatures(physical, availableFeatures);
            VkPhysicalDeviceFeatures enabledFeatures = VkPhysicalDeviceFeatures.calloc(stack)
                    .shaderFloat64(availableFeatures.shaderFloat64());
            VkDeviceCreateInfo di = VkDeviceCreateInfo.calloc(stack).sType$Default().pQueueCreateInfos(qi)
                    .pEnabledFeatures(enabledFeatures);
            PointerBuffer dh = stack.mallocPointer(1); check(vkCreateDevice(physical, di, null, dh), "create persistent device");
            device = new VkDevice(dh.get(0), physical, di);
            ownsDevice = true;
            queueSubmitLock = new Object();
            PointerBuffer qh = stack.mallocPointer(1); vkGetDeviceQueue(device, queueFamily, 0, qh); queue = new VkQueue(qh.get(0), device);
            coordinates = buffer(CAPACITY * 32L, stack); // 32-byte global-noise sample records
            // Graph dispatches can return up to 16 router roots per sample.
            results = buffer(CAPACITY * 16L * 4L, stack);
            graphCoordinates = hostVisibleBuffer(CAPACITY * 16L, stack); graphNodes = deviceLocalBuffer((long) GRAPH_NODE_CAPACITY * GpuDensityGraphBuffer.NODE_BYTES, stack); graphSplinePoints = deviceLocalBuffer((long) GRAPH_NODE_CAPACITY * 64L * 16L, stack); graphBlendedMetadata = deviceLocalBuffer((long) GRAPH_NODE_CAPACITY * 192L, stack); graphBlendInputs = hostVisibleBuffer(CAPACITY * 8L, stack); graphRoots = deviceLocalBuffer(64L, stack);
            graphMthSinTable = deviceLocalBuffer(65_536L * Float.BYTES, stack);
            interpolationMetadata = buffer(100L * GpuInterpolationBatch.METADATA_BYTES, stack); interpolationOutput = buffer((long) CAPACITY * 16L * Float.BYTES, stack);
            aquiferPressureInputs = hostVisibleBuffer((long) CAPACITY * GpuAquiferPressure.RECORD_BYTES, stack);
            aquiferNearestInputs = hostVisibleBuffer((long) GpuAquiferNearestThree.MAX_QUERIES
                    * GpuAquiferNearestThree.RECORD_BYTES, stack);
            aquiferNearestOutput = buffer((long) GpuAquiferNearestThree.MAX_QUERIES
                    * GpuAquiferNearestThree.OUTPUT_INTS * Integer.BYTES, stack);
            permutations = deviceLocalBuffer(8 * 1024 * 1024L, stack); octaves = deviceLocalBuffer(8 * 1024 * 1024L, stack); samplerMetadata = deviceLocalBuffer(64 * 1024L, stack);
            shader = shader(stack); descriptorLayout = descriptorLayout(stack); pipelineLayout = pipelineLayout(stack);
            pipeline = pipeline(stack); descriptorPool = descriptorPool(stack); descriptorSet = descriptorSet(stack);
            descriptors(stack); commandPool = commandPool(stack); commandBuffer = commandBuffer(stack); fence = fence(stack);
            graphShader = graphShader(stack); graphDescriptorLayout = graphDescriptorLayout(stack);
            graphPipelineLayout = graphPipelineLayout(stack); graphPipeline = graphPipeline(stack);
            gridShader = gridShader(stack); gridPipeline = gridPipeline(stack);
            graphDescriptorPool = graphDescriptorPool(stack); graphDescriptorSet = graphDescriptorSet(stack);
            graphDescriptors(stack);
            interpolationShader = interpolationShader(stack); interpolationDescriptorLayout = interpolationDescriptorLayout(stack);
            interpolationPipelineLayout = interpolationPipelineLayout(stack); interpolationPipeline = interpolationPipeline(stack);
            interpolationDescriptorPool = interpolationDescriptorPool(stack); interpolationDescriptorSet = interpolationDescriptorSet(stack);
            interpolationDescriptors(stack);
            aquiferPressureShader = loadShader(stack, "/assets/gpunoise/shaders/aquifer_pressure.comp.spv", "aquifer pressure shader");
            aquiferPressureDescriptorLayout = aquiferPressureDescriptorLayout(stack);
            aquiferPressurePipelineLayout = aquiferPressurePipelineLayout(stack);
            aquiferPressurePipeline = aquiferPressurePipeline(stack);
            aquiferPressureDescriptorPool = aquiferPressureDescriptorPool(stack);
            aquiferPressureDescriptorSet = aquiferPressureDescriptorSet(stack);
            aquiferPressureDescriptors(stack);
            aquiferNearestShader = loadShader(stack, "/assets/gpunoise/shaders/aquifer_nearest_three.comp.spv", "aquifer nearest-three shader");
            aquiferNearestDescriptorLayout = aquiferNearestDescriptorLayout(stack);
            aquiferNearestPipelineLayout = aquiferNearestPipelineLayout(stack);
            aquiferNearestPipeline = aquiferNearestPipeline(stack);
            aquiferNearestDescriptorPool = aquiferNearestDescriptorPool(stack);
            aquiferNearestDescriptorSet = aquiferNearestDescriptorSet(stack);
            aquiferNearestDescriptors(stack);
            aquiferSubstanceShader = loadShader(stack, "/assets/gpunoise/shaders/aquifer_substance.comp.spv", "aquifer substance shader");
            aquiferSubstancePipelineLayout = aquiferSubstancePipelineLayout(stack);
            aquiferSubstancePipeline = aquiferSubstancePipeline(stack);
            mapPersistentBuffers(stack);
            put(graphMthSinTable, GpuDensityGraphBuffer.mthSinTableBytes(), stack);
        } catch (Throwable failure) {
            stack.close();
            throw failure;
        }
        stack.close();
    }

    /** Creates an independent submission slot while sharing the parent Vulkan device. */
    PersistentVulkanNormalNoiseEngine(PersistentVulkanNormalNoiseEngine parent) throws Exception {
        instance = parent.instance;
        physical = parent.physical;
        device = parent.device;
        queueFamily = parent.queueFamily;
        queue = parent.queue;
        ownsDevice = false;
        queueSubmitLock = parent.queueSubmitLock;
        Configuration.STACK_SIZE.set(64 * 1_048_576);
        MemoryStack stack = MemoryStack.stackPush();
        try {
            coordinates = buffer(CAPACITY * 32L, stack);
            results = buffer(CAPACITY * 16L * 4L, stack);
            graphCoordinates = hostVisibleBuffer(CAPACITY * 16L, stack);
            graphNodes = deviceLocalBuffer((long) GRAPH_NODE_CAPACITY * GpuDensityGraphBuffer.NODE_BYTES, stack);
            graphSplinePoints = deviceLocalBuffer((long) GRAPH_NODE_CAPACITY * 64L * 16L, stack);
            graphBlendedMetadata = deviceLocalBuffer((long) GRAPH_NODE_CAPACITY * 192L, stack);
            graphBlendInputs = hostVisibleBuffer(CAPACITY * 8L, stack);
            graphRoots = deviceLocalBuffer(64L, stack);
            graphMthSinTable = deviceLocalBuffer(65_536L * Float.BYTES, stack);
            interpolationMetadata = buffer(100L * GpuInterpolationBatch.METADATA_BYTES, stack); interpolationOutput = buffer((long) CAPACITY * 16L * Float.BYTES, stack);
            aquiferPressureInputs = hostVisibleBuffer((long) CAPACITY * GpuAquiferPressure.RECORD_BYTES, stack);
            aquiferNearestInputs = hostVisibleBuffer((long) GpuAquiferNearestThree.MAX_QUERIES
                    * GpuAquiferNearestThree.RECORD_BYTES, stack);
            aquiferNearestOutput = buffer((long) GpuAquiferNearestThree.MAX_QUERIES
                    * GpuAquiferNearestThree.OUTPUT_INTS * Integer.BYTES, stack);
            permutations = deviceLocalBuffer(8 * 1024 * 1024L, stack);
            octaves = deviceLocalBuffer(8 * 1024 * 1024L, stack);
            samplerMetadata = deviceLocalBuffer(64 * 1024L, stack);
            shader = shader(stack); descriptorLayout = descriptorLayout(stack); pipelineLayout = pipelineLayout(stack);
            pipeline = pipeline(stack); descriptorPool = descriptorPool(stack); descriptorSet = descriptorSet(stack);
            descriptors(stack); commandPool = commandPool(stack); commandBuffer = commandBuffer(stack); fence = fence(stack);
            graphShader = graphShader(stack); graphDescriptorLayout = graphDescriptorLayout(stack);
            graphPipelineLayout = graphPipelineLayout(stack); graphPipeline = graphPipeline(stack);
            gridShader = gridShader(stack); gridPipeline = gridPipeline(stack);
            graphDescriptorPool = graphDescriptorPool(stack); graphDescriptorSet = graphDescriptorSet(stack);
            graphDescriptors(stack);
            interpolationShader = interpolationShader(stack); interpolationDescriptorLayout = interpolationDescriptorLayout(stack);
            interpolationPipelineLayout = interpolationPipelineLayout(stack); interpolationPipeline = interpolationPipeline(stack);
            interpolationDescriptorPool = interpolationDescriptorPool(stack); interpolationDescriptorSet = interpolationDescriptorSet(stack);
            interpolationDescriptors(stack);
            aquiferPressureShader = loadShader(stack, "/assets/gpunoise/shaders/aquifer_pressure.comp.spv", "aquifer pressure shader");
            aquiferPressureDescriptorLayout = aquiferPressureDescriptorLayout(stack);
            aquiferPressurePipelineLayout = aquiferPressurePipelineLayout(stack);
            aquiferPressurePipeline = aquiferPressurePipeline(stack);
            aquiferPressureDescriptorPool = aquiferPressureDescriptorPool(stack);
            aquiferPressureDescriptorSet = aquiferPressureDescriptorSet(stack);
            aquiferPressureDescriptors(stack);
            aquiferNearestShader = loadShader(stack, "/assets/gpunoise/shaders/aquifer_nearest_three.comp.spv", "aquifer nearest-three shader");
            aquiferNearestDescriptorLayout = aquiferNearestDescriptorLayout(stack);
            aquiferNearestPipelineLayout = aquiferNearestPipelineLayout(stack);
            aquiferNearestPipeline = aquiferNearestPipeline(stack);
            aquiferNearestDescriptorPool = aquiferNearestDescriptorPool(stack);
            aquiferNearestDescriptorSet = aquiferNearestDescriptorSet(stack);
            aquiferNearestDescriptors(stack);
            aquiferSubstanceShader = loadShader(stack, "/assets/gpunoise/shaders/aquifer_substance.comp.spv", "aquifer substance shader");
            aquiferSubstancePipelineLayout = aquiferSubstancePipelineLayout(stack);
            aquiferSubstancePipeline = aquiferSubstancePipeline(stack);
            mapPersistentBuffers(stack);
            put(graphMthSinTable, GpuDensityGraphBuffer.mthSinTableBytes(), stack);
        } catch (Throwable failure) {
            stack.close();
            throw failure;
        }
        stack.close();
    }

    synchronized float[] submit(MinecraftNormalNoiseData data, float[] xs, float[] ys, float[] zs) throws Exception {
        if (xs.length != ys.length || xs.length != zs.length || xs.length > CAPACITY) throw new IllegalArgumentException("invalid GPU sample batch");
        try (MemoryStack stack = MemoryStack.stackPush()) {
            GpuGlobalNoiseBatch batch = new GpuGlobalNoiseBatch();
            for (int i = 0; i < xs.length; i++) batch.add(new GpuGlobalNoiseBatch.Sample(data, xs[i], ys[i], zs[i]));
            return submit(batch.pack(), stack);
        }
    }

    synchronized float[] submit(GpuGlobalNoiseBatch.Packed packed) throws Exception {
        try (MemoryStack stack = MemoryStack.stackPush()) { return submit(packed, stack); }
    }

    synchronized float[] submitGraph(GpuDensityGraph graph, float[] xs, float[] ys, float[] zs, int resultNode) throws Exception {
        if (xs.length != ys.length || xs.length != zs.length || xs.length > CAPACITY) throw new IllegalArgumentException("invalid graph batch");
        GpuDensityGraphBuffer.Packed packed = packedGraph(graph);
        if (packed.nodeCount() > GRAPH_NODE_CAPACITY) throw new IllegalArgumentException("density graph exceeds GPU node capacity");
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer coords = stack.malloc(xs.length * 16).order(ByteOrder.nativeOrder());
            for (int i = 0; i < xs.length; i++) coords.putFloat(xs[i]).putFloat(ys[i]).putFloat(zs[i]).putFloat(0);
            coords.flip();
            put(graphCoordinates, coords, stack);
            if (graphResourcesGraph != graph) {
                put(graphNodes, packed.nodes(), stack);
                put(graphSplinePoints, packed.splinePoints(), stack);
                put(graphBlendedMetadata, packed.blendedMetadata(), stack);
            }
            ByteBuffer blendInputs = stack.malloc(xs.length * 8).order(ByteOrder.nativeOrder());
            for (int i = 0; i < xs.length; i++) blendInputs.putFloat(0.0f).putFloat(1.0f);
            blendInputs.flip();
            put(graphBlendInputs, blendInputs, stack);
            ByteBuffer roots = stack.malloc(4).order(ByteOrder.nativeOrder()).putInt(resultNode).flip();
            put(graphRoots, roots, stack);
            if (graphResourcesGraph != graph) {
                put(permutations, packed.permutations(), stack); put(octaves, packed.octaves(), stack); put(samplerMetadata, packed.samplerMetadata(), stack);
                graphResourcesGraph = graph;
            }
            check(vkResetCommandBuffer(commandBuffer, 0), "reset graph command buffer");
            check(vkBeginCommandBuffer(commandBuffer, VkCommandBufferBeginInfo.calloc(stack).sType$Default()), "begin graph command buffer");
            vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, graphPipeline);
            vkCmdBindDescriptorSets(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, graphPipelineLayout, 0, stack.longs(graphDescriptorSet), null);
            ByteBuffer push = stack.malloc(16).order(ByteOrder.nativeOrder()).putInt(xs.length).putInt(packed.nodeCount()).putInt(1).putInt(0).flip();
            vkCmdPushConstants(commandBuffer, graphPipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, push);
            vkCmdDispatch(commandBuffer, (xs.length + 63) / 64, 1, 1);
            VkBufferMemoryBarrier.Buffer barrier = VkBufferMemoryBarrier.calloc(1, stack).sType$Default().srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT).dstAccessMask(VK_ACCESS_HOST_READ_BIT).buffer(results.buffer).size(xs.length * 4L);
            vkCmdPipelineBarrier(commandBuffer, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_HOST_BIT, 0, null, barrier, null);
            check(vkEndCommandBuffer(commandBuffer), "end graph command buffer");
            synchronized (queueSubmitLock) { check(vkQueueSubmit(queue, VkSubmitInfo.calloc(stack).sType$Default().pCommandBuffers(stack.pointers(commandBuffer.address())), fence), "submit graph"); }
            check(vkWaitForFences(device, fence, true, Long.MAX_VALUE), "wait graph"); check(vkResetFences(device, fence), "reset graph fence");
            return readResults(xs.length, 1, stack);
        }
    }

    /** Computes all router roots in one dispatch; graph nodes are evaluated once per sample. */
    synchronized float[] submitGraphRoots(GpuDensityGraph graph, float[] xs, float[] ys, float[] zs) throws Exception {
        return submitGraphRoots(graph, xs, ys, zs, xs.length);
    }

    /** Evaluates a prefix of reusable coordinate arrays without copying it. */
    synchronized float[] submitGraphRoots(GpuDensityGraph graph, float[] xs, float[] ys, float[] zs, int sampleCount) throws Exception {
        if (xs.length != ys.length || xs.length != zs.length || sampleCount < 0 || sampleCount > xs.length || sampleCount > CAPACITY)
            throw new IllegalArgumentException("invalid graph batch");
        GpuDensityGraphBuffer.Packed packed = packedGraph(graph);
        int rootCount = graph.routerRoots().length;
        if (packed.nodeCount() > GRAPH_NODE_CAPACITY || rootCount > 16) throw new IllegalArgumentException("density graph exceeds GPU capacity");
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ensureGraphVariant(graph, stack);
            ByteBuffer coords = stack.malloc(sampleCount * 16).order(ByteOrder.nativeOrder());
            for (int i = 0; i < sampleCount; i++) coords.putFloat(xs[i]).putFloat(ys[i]).putFloat(zs[i]).putFloat(0);
            coords.flip();
            put(graphCoordinates, coords, stack);
            if (graphResourcesGraph != graph) {
                put(graphNodes, packed.nodes(), stack);
                put(graphSplinePoints, packed.splinePoints(), stack); put(graphBlendedMetadata, packed.blendedMetadata(), stack);
            }
            ByteBuffer blendInputs = stack.malloc(sampleCount * 8).order(ByteOrder.nativeOrder());
            for (int i = 0; i < sampleCount; i++) blendInputs.putFloat(0.0f).putFloat(1.0f);
            blendInputs.flip(); put(graphBlendInputs, blendInputs, stack);
            ByteBuffer roots = stack.malloc(rootCount * 4).order(ByteOrder.nativeOrder());
            for (int root : graph.routerRoots()) roots.putInt(root);
            roots.flip(); put(graphRoots, roots, stack);
            if (graphResourcesGraph != graph) {
                put(permutations, packed.permutations(), stack); put(octaves, packed.octaves(), stack); put(samplerMetadata, packed.samplerMetadata(), stack);
                graphResourcesGraph = graph;
            }
            check(vkResetCommandBuffer(commandBuffer, 0), "reset graph roots command buffer");
            check(vkBeginCommandBuffer(commandBuffer, VkCommandBufferBeginInfo.calloc(stack).sType$Default()), "begin graph roots command buffer");
            vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, graphPipeline);
            vkCmdBindDescriptorSets(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, graphPipelineLayout, 0, stack.longs(graphDescriptorSet), null);
            ByteBuffer push = stack.malloc(16).order(ByteOrder.nativeOrder()).putInt(sampleCount).putInt(packed.nodeCount()).putInt(rootCount).putInt(0).flip();
            vkCmdPushConstants(commandBuffer, graphPipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, push);
            vkCmdDispatch(commandBuffer, (sampleCount + 63) / 64, 1, 1);
            VkBufferMemoryBarrier.Buffer barrier = VkBufferMemoryBarrier.calloc(1, stack).sType$Default().srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT).dstAccessMask(VK_ACCESS_HOST_READ_BIT).buffer(results.buffer).size((long) sampleCount * rootCount * 4L);
            vkCmdPipelineBarrier(commandBuffer, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_HOST_BIT, 0, null, barrier, null);
            check(vkEndCommandBuffer(commandBuffer), "end graph roots command buffer");
            synchronized (queueSubmitLock) { check(vkQueueSubmit(queue, VkSubmitInfo.calloc(stack).sType$Default().pCommandBuffers(stack.pointers(commandBuffer.address())), fence), "submit graph roots"); }
            check(vkWaitForFences(device, fence, true, Long.MAX_VALUE), "wait graph roots"); check(vkResetFences(device, fence), "reset graph roots fence");
            return readResults(sampleCount, rootCount, stack);
        }
    }

    /**
     * Terrain hot path: record and submit, then return immediately. Completion
     * is observed through vkGetFenceStatus by VulkanSubmissionRing; there is
     * intentionally no vkWaitForFences here.
     */
    synchronized CompletableFuture<float[]> submitGraphRootsAsync(GpuDensityGraph graph, float[] xs, float[] ys,
                                                                    float[] zs, int sampleCount) throws Exception {
        if (inFlightGraphRoots != null) throw new IllegalStateException("graph-roots slot is still in flight");
        if (xs.length != ys.length || xs.length != zs.length || sampleCount < 0 || sampleCount > xs.length || sampleCount > CAPACITY)
            throw new IllegalArgumentException("invalid graph batch");
        GpuDensityGraphBuffer.Packed packed = packedGraph(graph);
        int rootCount = graph.routerRoots().length;
        if (packed.nodeCount() > GRAPH_NODE_CAPACITY || rootCount > 16) throw new IllegalArgumentException("density graph exceeds GPU capacity");
        long preparationStarted = System.nanoTime();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ensureGraphVariant(graph, stack);
            ByteBuffer coords = stack.malloc(sampleCount * 16).order(ByteOrder.nativeOrder());
            for (int i = 0; i < sampleCount; i++) coords.putFloat(xs[i]).putFloat(ys[i]).putFloat(zs[i]).putFloat(0);
            coords.flip(); put(graphCoordinates, coords, stack);
            if (graphResourcesGraph != graph) {
                put(graphNodes, packed.nodes(), stack); put(graphSplinePoints, packed.splinePoints(), stack);
                put(graphBlendedMetadata, packed.blendedMetadata(), stack);
                put(permutations, packed.permutations(), stack); put(octaves, packed.octaves(), stack);
                put(samplerMetadata, packed.samplerMetadata(), stack); graphResourcesGraph = graph;
            }
            ByteBuffer blendInputs = stack.malloc(sampleCount * 8).order(ByteOrder.nativeOrder());
            for (int i = 0; i < sampleCount; i++) blendInputs.putFloat(0.0f).putFloat(1.0f);
            blendInputs.flip(); put(graphBlendInputs, blendInputs, stack);
            ByteBuffer roots = stack.malloc(rootCount * 4).order(ByteOrder.nativeOrder());
            for (int root : graph.routerRoots()) roots.putInt(root);
            roots.flip(); put(graphRoots, roots, stack);
            check(vkResetCommandBuffer(commandBuffer, 0), "reset async graph-roots command buffer");
            check(vkBeginCommandBuffer(commandBuffer, VkCommandBufferBeginInfo.calloc(stack).sType$Default()), "begin async graph-roots command buffer");
            vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, graphPipeline);
            vkCmdBindDescriptorSets(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, graphPipelineLayout, 0, stack.longs(graphDescriptorSet), null);
            ByteBuffer push = stack.malloc(16).order(ByteOrder.nativeOrder()).putInt(sampleCount).putInt(packed.nodeCount()).putInt(rootCount).putInt(0).flip();
            vkCmdPushConstants(commandBuffer, graphPipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, push);
            vkCmdDispatch(commandBuffer, (sampleCount + 63) / 64, 1, 1);
            VkBufferMemoryBarrier.Buffer barrier = VkBufferMemoryBarrier.calloc(1, stack).sType$Default()
                    .srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT).dstAccessMask(VK_ACCESS_HOST_READ_BIT)
                    .buffer(results.buffer).size((long) sampleCount * rootCount * Float.BYTES);
            vkCmdPipelineBarrier(commandBuffer, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_HOST_BIT, 0, null, barrier, null);
            check(vkEndCommandBuffer(commandBuffer), "end async graph-roots command buffer");
            synchronized (queueSubmitLock) {
                check(vkQueueSubmit(queue, VkSubmitInfo.calloc(stack).sType$Default()
                        .pCommandBuffers(stack.pointers(commandBuffer.address())), fence), "submit async graph roots");
            }
            inFlightFenceSubmittedNanos = System.nanoTime();
            inFlightGraphPreparationNanos = inFlightFenceSubmittedNanos - preparationStarted;
            inFlightSamples = sampleCount;
            inFlightRoots = rootCount;
            inFlightGraphRoots = new CompletableFuture<>();
            return inFlightGraphRoots;
        }
    }

    synchronized CompletableFuture<float[]> submitGraphRootsGridAsync(ByteBuffer metadata, int chunkCount,
                                                                        int samplesPerChunk) throws Exception {
        return submitGraphRootsGridAsync(GpuTerrainRuntime.currentTerrainGraph(), metadata, chunkCount, samplesPerChunk);
    }

    synchronized CompletableFuture<float[]> submitGraphRootsGridAsync(GpuDensityGraph graph, ByteBuffer metadata,
                                                                        int chunkCount, int samplesPerChunk) throws Exception {
        if (inFlightGraphRoots != null) throw new IllegalStateException("graph-roots slot is still in flight");
        if (chunkCount <= 0 || samplesPerChunk <= 0 || (long) chunkCount * samplesPerChunk > CAPACITY
                || metadata.capacity() < chunkCount * 32L
                || (long) chunkCount * samplesPerChunk * 16L > results.size / Float.BYTES) {
            throw new IllegalArgumentException("invalid graph grid batch");
        }
        metadata = metadata.duplicate().clear();
        metadata.limit(chunkCount * 32);
        if (graph == null) throw new IllegalStateException("GPU terrain graph is not installed");
        GpuDensityGraphBuffer.Packed packed = packedGraph(graph);
        int rootCount = graph.routerRoots().length;
        if (packed.nodeCount() > GRAPH_NODE_CAPACITY || rootCount > 16) throw new IllegalArgumentException("density graph exceeds GPU capacity");
        long preparationStarted = System.nanoTime();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ensureGraphVariant(graph, stack);
            put(graphCoordinates, metadata, stack);
            if (graphResourcesGraph != graph) {
                put(graphNodes, packed.nodes(), stack); put(graphSplinePoints, packed.splinePoints(), stack);
                put(graphBlendedMetadata, packed.blendedMetadata(), stack);
                put(permutations, packed.permutations(), stack); put(octaves, packed.octaves(), stack);
                put(samplerMetadata, packed.samplerMetadata(), stack); graphResourcesGraph = graph;
            }
            ByteBuffer roots = stack.malloc(rootCount * 4).order(ByteOrder.nativeOrder());
            for (int root : graph.routerRoots()) roots.putInt(root);
            roots.flip(); put(graphRoots, roots, stack);
            check(vkResetCommandBuffer(commandBuffer, 0), "reset grid command buffer");
            check(vkBeginCommandBuffer(commandBuffer, VkCommandBufferBeginInfo.calloc(stack).sType$Default()), "begin grid command buffer");
            vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, gridPipeline);
            vkCmdBindDescriptorSets(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, graphPipelineLayout, 0, stack.longs(graphDescriptorSet), null);
            int sampleCount = Math.multiplyExact(chunkCount, samplesPerChunk);
            ByteBuffer push = stack.malloc(16).order(ByteOrder.nativeOrder())
                    .putInt(sampleCount).putInt(packed.nodeCount()).putInt(rootCount).putInt(samplesPerChunk).flip();
            vkCmdPushConstants(commandBuffer, graphPipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, push);
            vkCmdDispatch(commandBuffer, (sampleCount + 63) / 64, 1, 1);
            VkBufferMemoryBarrier.Buffer barrier = VkBufferMemoryBarrier.calloc(1, stack).sType$Default()
                    .srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT).dstAccessMask(VK_ACCESS_HOST_READ_BIT)
                    .buffer(results.buffer).size((long) sampleCount * rootCount * Float.BYTES);
            vkCmdPipelineBarrier(commandBuffer, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_HOST_BIT, 0, null, barrier, null);
            check(vkEndCommandBuffer(commandBuffer), "end grid command buffer");
            synchronized (queueSubmitLock) {
                check(vkQueueSubmit(queue, VkSubmitInfo.calloc(stack).sType$Default()
                        .pCommandBuffers(stack.pointers(commandBuffer.address())), fence), "submit grid roots");
            }
            inFlightFenceSubmittedNanos = System.nanoTime();
            inFlightGraphPreparationNanos = inFlightFenceSubmittedNanos - preparationStarted;
            inFlightSamples = sampleCount; inFlightRoots = rootCount;
            inFlightGraphRoots = new CompletableFuture<>();
            return inFlightGraphRoots;
        }
    }

    synchronized void pollGraphRootsCompletion() {
        CompletableFuture<float[]> future = inFlightGraphRoots;
        if (future == null) return;
        int status = vkGetFenceStatus(device, fence);
        if (status == VK_NOT_READY) return;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            check(status, "poll graph-roots fence");
            long fenceDuration = System.nanoTime() - inFlightFenceSubmittedNanos;
            long readbackStarted = System.nanoTime();
            float[] output = readResults(inFlightSamples, inFlightRoots, stack);
            recordGraphFenceDuration(inFlightGraphPreparationNanos, fenceDuration,
                    System.nanoTime() - readbackStarted);
            check(vkResetFences(device, fence), "reset async graph-roots fence");
            inFlightGraphRoots = null;
            future.complete(output);
        } catch (Throwable failure) {
            inFlightGraphRoots = null;
            future.completeExceptionally(failure);
        }
    }

    private void recordGraphFenceDuration(long preparationNanos, long fenceNanos, long readbackNanos) {
        totalGraphPreparationNanos += preparationNanos;
        totalGraphFenceNanos += fenceNanos;
        totalGraphReadbackNanos += readbackNanos;
        long completed = ++completedGraphFenceSamples;
        if ((completed & 7L) == 0L) {
            GpuNoise.LOGGER.info("GPU terrain graph profile: completedDispatches={}, avgCpuPrepareMs={}, avgSubmitToFenceMs={}, avgReadbackMs={}",
                    completed, totalGraphPreparationNanos / (double) completed / 1_000_000.0,
                    totalGraphFenceNanos / (double) completed / 1_000_000.0,
                    totalGraphReadbackNanos / (double) completed / 1_000_000.0);
        }
    }

    synchronized boolean graphRootsInFlight() { return inFlightGraphRoots != null; }

    private float[] submit(GpuGlobalNoiseBatch.Packed packed, MemoryStack stack) throws Exception {
            graphResourcesGraph = null;
            put(coordinates, packed.samples(), stack); put(permutations, packed.permutations(), stack);
            put(octaves, packed.octaves(), stack); put(samplerMetadata, packed.samplerMetadata(), stack);
            check(vkResetCommandBuffer(commandBuffer, 0), "reset persistent command buffer");
            check(vkBeginCommandBuffer(commandBuffer, VkCommandBufferBeginInfo.calloc(stack).sType$Default()), "begin persistent command buffer");
            vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
            vkCmdBindDescriptorSets(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, pipelineLayout, 0, stack.longs(descriptorSet), null);
            ByteBuffer push = stack.malloc(8).order(ByteOrder.nativeOrder()).putInt(packed.sampleCount()).putInt(packed.samplerCount()).flip();
            vkCmdPushConstants(commandBuffer, pipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, push);
            vkCmdDispatch(commandBuffer, (packed.sampleCount() + 63) / 64, 1, 1);
            VkBufferMemoryBarrier.Buffer barrier = VkBufferMemoryBarrier.calloc(1, stack).sType$Default()
                    .srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT).dstAccessMask(VK_ACCESS_HOST_READ_BIT).buffer(results.buffer).size(packed.sampleCount() * 4L);
            vkCmdPipelineBarrier(commandBuffer, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_HOST_BIT, 0, null, barrier, null);
            check(vkEndCommandBuffer(commandBuffer), "end persistent command buffer");
            VkSubmitInfo submit = VkSubmitInfo.calloc(stack).sType$Default().pCommandBuffers(stack.pointers(commandBuffer.address()));
            synchronized (queueSubmitLock) { check(vkQueueSubmit(queue, submit, fence), "submit persistent noise"); }
            check(vkWaitForFences(device, fence, true, Long.MAX_VALUE), "wait persistent noise");
            check(vkResetFences(device, fence), "reset persistent fence");
            return readResults(packed.sampleCount(), 1, stack);
        }

    /** Runs terrain graph roots and block interpolation in one Vulkan submission. */
    synchronized GpuFusedResult submitGraphInterpolated(GpuDensityGraph graph, float[] xs, float[] ys, float[] zs,
                                                   ByteBuffer metadata, int chunkCount, int blocksPerChunk) throws Exception {
        return submitGraphInterpolated(graph, xs, ys, zs, xs.length, metadata, chunkCount, blocksPerChunk);
    }

    /** Runs a grid graph dispatch directly into the persistent block output buffer. */
    synchronized GpuTerrainBatchResult submitGraphGridInterpolated(ByteBuffer gridMetadata, int chunkCount,
                                                                     int samplesPerChunk, ByteBuffer outputMetadata,
                                                                     int blocksPerChunk) throws Exception {
        if (chunkCount <= 0 || samplesPerChunk <= 0 || blocksPerChunk <= 0
                || (long) chunkCount * samplesPerChunk > CAPACITY
                || gridMetadata.remaining() < chunkCount * 32L
                || outputMetadata.remaining() < chunkCount * GpuInterpolationBatch.METADATA_BYTES)
            throw new IllegalArgumentException("invalid grid interpolation batch");
        GpuDensityGraph graph = GpuTerrainRuntime.currentTerrainGraph();
        GpuDensityGraphBuffer.Packed packed = packedGraph(graph);
        int rootCount = graph.routerRoots().length;
        int outputRoots = GpuTerrainRuntime.terrainBlockOutputRootCount();
        int sampleCount = Math.multiplyExact(chunkCount, samplesPerChunk);
        int blockCount = Math.multiplyExact(chunkCount, blocksPerChunk);
        if (packed.nodeCount() > GRAPH_NODE_CAPACITY || rootCount > 16 || outputRoots > rootCount
                || (long) sampleCount * rootCount * Float.BYTES > results.size
                || (long) blockCount * outputRoots * Float.BYTES > interpolationOutput.size)
            throw new IllegalArgumentException("grid interpolation batch exceeds GPU capacity");
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ensureGraphVariant(graph, stack);
            ByteBuffer grids = gridMetadata.duplicate().order(ByteOrder.nativeOrder());
            grids.limit(grids.position() + chunkCount * 32);
            put(graphCoordinates, grids, stack);
            if (graphResourcesGraph != graph) {
                put(graphNodes, packed.nodes(), stack); put(graphSplinePoints, packed.splinePoints(), stack);
                put(graphBlendedMetadata, packed.blendedMetadata(), stack);
                put(permutations, packed.permutations(), stack); put(octaves, packed.octaves(), stack);
                put(samplerMetadata, packed.samplerMetadata(), stack); graphResourcesGraph = graph;
            }
            ByteBuffer roots = stack.malloc(rootCount * 4).order(ByteOrder.nativeOrder());
            for (int root : graph.routerRoots()) roots.putInt(root);
            roots.flip(); put(graphRoots, roots, stack);
            ByteBuffer metadata = outputMetadata.duplicate().order(ByteOrder.nativeOrder());
            metadata.limit(metadata.position() + chunkCount * GpuInterpolationBatch.METADATA_BYTES);
            put(interpolationMetadata, metadata, stack);

            check(vkResetCommandBuffer(commandBuffer, 0), "reset grid interpolation command buffer");
            check(vkBeginCommandBuffer(commandBuffer, VkCommandBufferBeginInfo.calloc(stack).sType$Default()), "begin grid interpolation command buffer");
            vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, gridPipeline);
            vkCmdBindDescriptorSets(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, graphPipelineLayout, 0, stack.longs(graphDescriptorSet), null);
            ByteBuffer graphPush = stack.malloc(16).order(ByteOrder.nativeOrder())
                    .putInt(sampleCount).putInt(packed.nodeCount()).putInt(rootCount).putInt(samplesPerChunk).flip();
            vkCmdPushConstants(commandBuffer, graphPipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, graphPush);
            vkCmdDispatch(commandBuffer, (sampleCount + 63) / 64, 1, 1);
            VkBufferMemoryBarrier.Buffer graphBarrier = VkBufferMemoryBarrier.calloc(1, stack).sType$Default()
                    .srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT).dstAccessMask(VK_ACCESS_SHADER_READ_BIT)
                    .buffer(results.buffer).size((long) sampleCount * rootCount * Float.BYTES);
            vkCmdPipelineBarrier(commandBuffer, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    0, null, graphBarrier, null);
            vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, interpolationPipeline);
            vkCmdBindDescriptorSets(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, interpolationPipelineLayout, 0,
                    stack.longs(interpolationDescriptorSet), null);
            ByteBuffer interpolationPush = stack.malloc(16).order(ByteOrder.nativeOrder())
                    .putInt(chunkCount).putInt(blocksPerChunk).putInt(rootCount).putInt(outputRoots).flip();
            vkCmdPushConstants(commandBuffer, interpolationPipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, interpolationPush);
            vkCmdDispatch(commandBuffer, (blockCount + 63) / 64, 1, 1);
            VkBufferMemoryBarrier.Buffer resultBarrier = VkBufferMemoryBarrier.calloc(1, stack).sType$Default()
                    .srcAccessMask(VK_ACCESS_SHADER_READ_BIT).dstAccessMask(VK_ACCESS_HOST_READ_BIT)
                    .buffer(results.buffer).size((long) sampleCount * rootCount * Float.BYTES);
            VkBufferMemoryBarrier.Buffer outputBarrier = VkBufferMemoryBarrier.calloc(1, stack).sType$Default()
                    .srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT).dstAccessMask(VK_ACCESS_HOST_READ_BIT)
                    .buffer(interpolationOutput.buffer).size((long) blockCount * outputRoots * Float.BYTES);
            vkCmdPipelineBarrier(commandBuffer, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_HOST_BIT,
                    0, null, resultBarrier, null);
            vkCmdPipelineBarrier(commandBuffer, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_HOST_BIT,
                    0, null, outputBarrier, null);
            check(vkEndCommandBuffer(commandBuffer), "end grid interpolation command buffer");
            synchronized (queueSubmitLock) {
                check(vkQueueSubmit(queue, VkSubmitInfo.calloc(stack).sType$Default()
                        .pCommandBuffers(stack.pointers(commandBuffer.address())), fence), "submit grid interpolation");
            }
            check(vkWaitForFences(device, fence, true, Long.MAX_VALUE), "wait grid interpolation");
            check(vkResetFences(device, fence), "reset grid interpolation fence");
            float[] corners = readResults(sampleCount, rootCount, stack);
            interpolationOutput.invalidate(device, stack);
            FloatBuffer blockValues = persistentMappings.get(interpolationOutput).duplicate()
                    .order(ByteOrder.nativeOrder()).asFloatBuffer();
            blockValues.clear();
            blockValues.limit(blockCount * outputRoots);
            float[] blocks = new float[blockCount * outputRoots];
            blockValues.get(blocks);
            return new GpuTerrainBatchResult(new GpuTerrainRootsResult(corners, 0, corners.length, rootCount),
                    new GpuTerrainBlockResult(blocks, 0, blockCount, outputRoots,
                            16, blocksPerChunk / 256, 16));
        }
    }

    synchronized GpuFusedResult submitGraphInterpolated(GpuDensityGraph graph, float[] xs, float[] ys, float[] zs,
                                                   int sampleCount, ByteBuffer metadata, int chunkCount, int blocksPerChunk) throws Exception {
        if (sampleCount < 0 || sampleCount > xs.length || sampleCount > ys.length || sampleCount > zs.length
                || sampleCount > CAPACITY)
            throw new IllegalArgumentException("invalid fused graph batch");
        GpuDensityGraphBuffer.Packed packed = packedGraph(graph);
        int rootCount = graph.routerRoots().length;
        int blockCount = Math.multiplyExact(chunkCount, blocksPerChunk);
        int outputRoots = GpuTerrainRuntime.terrainOutputRootCount();
        if (packed.nodeCount() > GRAPH_NODE_CAPACITY || rootCount > 16 || outputRoots > rootCount
                || metadata.remaining() > interpolationMetadata.size
                || blockCount * outputRoots * 4L > interpolationOutput.size) throw new IllegalArgumentException("fused batch exceeds GPU capacity");
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer coords = stack.malloc(sampleCount * 16).order(ByteOrder.nativeOrder());
            for (int i = 0; i < sampleCount; i++) coords.putFloat(xs[i]).putFloat(ys[i]).putFloat(zs[i]).putFloat(0);
            coords.flip();
            put(graphCoordinates, coords, stack);
            if (graphResourcesGraph != graph) {
                put(graphNodes, packed.nodes(), stack); put(graphSplinePoints, packed.splinePoints(), stack);
                put(graphBlendedMetadata, packed.blendedMetadata(), stack);
                put(permutations, packed.permutations(), stack); put(octaves, packed.octaves(), stack);
                put(samplerMetadata, packed.samplerMetadata(), stack); graphResourcesGraph = graph;
            }
            ByteBuffer blendInputs = stack.malloc(sampleCount * 8).order(ByteOrder.nativeOrder());
            for (int i = 0; i < sampleCount; i++) blendInputs.putFloat(0.0f).putFloat(1.0f);
            blendInputs.flip(); put(graphBlendInputs, blendInputs, stack);
            ByteBuffer roots = stack.malloc(rootCount * 4).order(ByteOrder.nativeOrder());
            for (int root : graph.routerRoots()) roots.putInt(root);
            roots.flip(); put(graphRoots, roots, stack); put(interpolationMetadata, metadata, stack);
            check(vkResetCommandBuffer(commandBuffer, 0), "reset fused command buffer");
            check(vkBeginCommandBuffer(commandBuffer, VkCommandBufferBeginInfo.calloc(stack).sType$Default()), "begin fused command buffer");
            vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, graphPipeline);
            vkCmdBindDescriptorSets(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, graphPipelineLayout, 0, stack.longs(graphDescriptorSet), null);
            ByteBuffer graphPush = stack.malloc(16).order(ByteOrder.nativeOrder()).putInt(sampleCount).putInt(packed.nodeCount()).putInt(rootCount).putInt(0).flip();
            vkCmdPushConstants(commandBuffer, graphPipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, graphPush);
            vkCmdDispatch(commandBuffer, (sampleCount + 63) / 64, 1, 1);
            VkBufferMemoryBarrier.Buffer graphBarrier = VkBufferMemoryBarrier.calloc(1, stack).sType$Default()
                    .srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT).dstAccessMask(VK_ACCESS_SHADER_READ_BIT)
                    .buffer(results.buffer).size((long) sampleCount * rootCount * 4L);
            vkCmdPipelineBarrier(commandBuffer, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, null, graphBarrier, null);
            vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, interpolationPipeline);
            vkCmdBindDescriptorSets(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, interpolationPipelineLayout, 0, stack.longs(interpolationDescriptorSet), null);
            // Evaluate all graph roots once and materialize the roots consumed
            // by vanilla terrain density and aquifer code.
            ByteBuffer interpolationPush = stack.malloc(16).order(ByteOrder.nativeOrder()).putInt(chunkCount).putInt(blocksPerChunk).putInt(rootCount).putInt(outputRoots).flip();
            vkCmdPushConstants(commandBuffer, interpolationPipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, interpolationPush);
            vkCmdDispatch(commandBuffer, (blockCount + 63) / 64, 1, 1);
            VkBufferMemoryBarrier.Buffer graphReadbackBarrier = VkBufferMemoryBarrier.calloc(1, stack).sType$Default()
                    .srcAccessMask(VK_ACCESS_SHADER_READ_BIT).dstAccessMask(VK_ACCESS_HOST_READ_BIT)
                    .buffer(results.buffer).size((long) sampleCount * rootCount * 4L);
            VkBufferMemoryBarrier.Buffer outputBarrier = VkBufferMemoryBarrier.calloc(1, stack).sType$Default()
                    .srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT).dstAccessMask(VK_ACCESS_HOST_READ_BIT)
                    .buffer(interpolationOutput.buffer).size((long) blockCount * outputRoots * 4L);
            vkCmdPipelineBarrier(commandBuffer, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_HOST_BIT, 0, null,
                    graphReadbackBarrier, null);
            vkCmdPipelineBarrier(commandBuffer, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_HOST_BIT, 0, null,
                    outputBarrier, null);
            check(vkEndCommandBuffer(commandBuffer), "end fused command buffer");
            synchronized (queueSubmitLock) { check(vkQueueSubmit(queue, VkSubmitInfo.calloc(stack).sType$Default().pCommandBuffers(stack.pointers(commandBuffer.address())), fence), "submit fused graph"); }
            check(vkWaitForFences(device, fence, true, Long.MAX_VALUE), "wait fused graph");
            check(vkResetFences(device, fence), "reset fused graph fence");
            interpolationOutput.invalidate(device, stack);
            // The mapped buffer limit is expressed in bytes, while the
            // FloatBuffer limit is expressed in floats. Set the limit after
            // creating the typed view; otherwise a 4x-short view causes a
            // BufferUnderflowException on valid GPU output.
            FloatBuffer values = persistentMappings.get(interpolationOutput)
                    .duplicate().order(ByteOrder.nativeOrder()).asFloatBuffer();
            values.clear();
            values.limit(blockCount * outputRoots);
            float[] output = new float[blockCount * outputRoots];
            values.get(output);
            return new GpuFusedResult(output, 0, output.length, outputRoots);
        }
    }
    private float[] readResults(int samples, int valuesPerSample, MemoryStack stack) {
        results.invalidate(device, stack);
        float[] output = new float[samples * valuesPerSample];
        ByteBuffer view = persistentResults.duplicate().order(ByteOrder.nativeOrder());
        view.clear();
        view.limit(output.length * Float.BYTES);
        view.asFloatBuffer().get(output);
        return output;
    }

    /** Executes the batched corner-to-block interpolation shader. */
    synchronized float[] submitInterpolation(float[] corners, ByteBuffer metadata,
                                              int chunkCount, int blocksPerChunk) throws Exception {
        return submitInterpolation(corners, metadata, chunkCount, blocksPerChunk, 1, 1);
    }

    /** Runs the pure numerical aquifer pressure term over packed status pairs. */
    synchronized float[] submitAquiferPressure(ByteBuffer records, int count) throws Exception {
        if (count <= 0 || count > CAPACITY
                || records.remaining() != count * GpuAquiferPressure.RECORD_BYTES)
            throw new IllegalArgumentException("invalid aquifer pressure batch");
        try (MemoryStack stack = MemoryStack.stackPush()) {
            put(aquiferPressureInputs, records, stack);
            check(vkResetCommandBuffer(commandBuffer, 0), "reset aquifer pressure command buffer");
            check(vkBeginCommandBuffer(commandBuffer, VkCommandBufferBeginInfo.calloc(stack).sType$Default()), "begin aquifer pressure command buffer");
            vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, aquiferPressurePipeline);
            vkCmdBindDescriptorSets(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE,
                    aquiferPressurePipelineLayout, 0, stack.longs(aquiferPressureDescriptorSet), null);
            ByteBuffer push = stack.malloc(4).order(ByteOrder.nativeOrder()).putInt(count).flip();
            vkCmdPushConstants(commandBuffer, aquiferPressurePipelineLayout,
                    VK_SHADER_STAGE_COMPUTE_BIT, 0, push);
            vkCmdDispatch(commandBuffer, Math.max(1, (count + 63) / 64), 1, 1);
            VkBufferMemoryBarrier.Buffer barrier = VkBufferMemoryBarrier.calloc(1, stack).sType$Default()
                    .srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT).dstAccessMask(VK_ACCESS_HOST_READ_BIT)
                    .buffer(interpolationOutput.buffer).size((long) count * Float.BYTES);
            vkCmdPipelineBarrier(commandBuffer, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK_PIPELINE_STAGE_HOST_BIT, 0, null, barrier, null);
            check(vkEndCommandBuffer(commandBuffer), "end aquifer pressure command buffer");
            synchronized (queueSubmitLock) {
                check(vkQueueSubmit(queue, VkSubmitInfo.calloc(stack).sType$Default()
                        .pCommandBuffers(stack.pointers(commandBuffer.address())), fence), "submit aquifer pressure");
            }
            check(vkWaitForFences(device, fence, true, Long.MAX_VALUE), "wait aquifer pressure");
            check(vkResetFences(device, fence), "reset aquifer pressure fence");
            interpolationOutput.invalidate(device, stack);
            FloatBuffer values = persistentMappings.get(interpolationOutput)
                    .duplicate().order(ByteOrder.nativeOrder()).asFloatBuffer();
            values.clear();
            values.limit(count);
            float[] output = new float[count];
            values.get(output);
            return output;
        }
    }

    /** Selects aquifer centers with Minecraft's exact integer tie ordering. */
    synchronized int[] submitAquiferNearestThree(ByteBuffer records, int count) throws Exception {
        if (count <= 0 || count > GpuAquiferNearestThree.MAX_QUERIES
                || records.remaining() != count * GpuAquiferNearestThree.RECORD_BYTES)
            throw new IllegalArgumentException("invalid aquifer nearest-three batch");
        try (MemoryStack stack = MemoryStack.stackPush()) {
            put(aquiferNearestInputs, records, stack);
            check(vkResetCommandBuffer(commandBuffer, 0), "reset aquifer nearest-three command buffer");
            check(vkBeginCommandBuffer(commandBuffer, VkCommandBufferBeginInfo.calloc(stack).sType$Default()), "begin aquifer nearest-three command buffer");
            vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, aquiferNearestPipeline);
            vkCmdBindDescriptorSets(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE,
                    aquiferNearestPipelineLayout, 0, stack.longs(aquiferNearestDescriptorSet), null);
            ByteBuffer push = stack.malloc(4).order(ByteOrder.nativeOrder()).putInt(count).flip();
            vkCmdPushConstants(commandBuffer, aquiferNearestPipelineLayout,
                    VK_SHADER_STAGE_COMPUTE_BIT, 0, push);
            vkCmdDispatch(commandBuffer, Math.max(1, (count + 63) / 64), 1, 1);
            VkBufferMemoryBarrier.Buffer barrier = VkBufferMemoryBarrier.calloc(1, stack).sType$Default()
                    .srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT).dstAccessMask(VK_ACCESS_HOST_READ_BIT)
                    .buffer(aquiferNearestOutput.buffer)
                    .size((long) count * GpuAquiferNearestThree.OUTPUT_INTS * Integer.BYTES);
            vkCmdPipelineBarrier(commandBuffer, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK_PIPELINE_STAGE_HOST_BIT, 0, null, barrier, null);
            check(vkEndCommandBuffer(commandBuffer), "end aquifer nearest-three command buffer");
            synchronized (queueSubmitLock) {
                check(vkQueueSubmit(queue, VkSubmitInfo.calloc(stack).sType$Default()
                        .pCommandBuffers(stack.pointers(commandBuffer.address())), fence), "submit aquifer nearest-three");
            }
            check(vkWaitForFences(device, fence, true, Long.MAX_VALUE), "wait aquifer nearest-three");
            check(vkResetFences(device, fence), "reset aquifer nearest-three fence");
            aquiferNearestOutput.invalidate(device, stack);
            IntBuffer values = persistentMappings.get(aquiferNearestOutput)
                    .duplicate().order(ByteOrder.nativeOrder()).asIntBuffer();
            values.clear();
            values.limit(count * GpuAquiferNearestThree.OUTPUT_INTS);
            int[] output = new int[count * GpuAquiferNearestThree.OUTPUT_INTS];
            values.get(output);
            return output;
        }
    }

    /** Applies the vanilla nearest-status and pressure rules to packed records. */
    synchronized int[] submitAquiferSubstance(ByteBuffer records, int count) throws Exception {
        if (count <= 0 || count > GpuAquiferSubstance.MAX_QUERIES
                || records.remaining() != count * GpuAquiferSubstance.RECORD_BYTES)
            throw new IllegalArgumentException("invalid aquifer substance batch");
        try (MemoryStack stack = MemoryStack.stackPush()) {
            put(aquiferNearestInputs, records, stack);
            check(vkResetCommandBuffer(commandBuffer, 0), "reset aquifer substance command buffer");
            check(vkBeginCommandBuffer(commandBuffer, VkCommandBufferBeginInfo.calloc(stack).sType$Default()), "begin aquifer substance command buffer");
            vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, aquiferSubstancePipeline);
            vkCmdBindDescriptorSets(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE,
                    aquiferSubstancePipelineLayout, 0, stack.longs(aquiferNearestDescriptorSet), null);
            ByteBuffer push = stack.malloc(4).order(ByteOrder.nativeOrder()).putInt(count).flip();
            vkCmdPushConstants(commandBuffer, aquiferSubstancePipelineLayout,
                    VK_SHADER_STAGE_COMPUTE_BIT, 0, push);
            vkCmdDispatch(commandBuffer, (count + 63) / 64, 1, 1);
            VkBufferMemoryBarrier.Buffer barrier = VkBufferMemoryBarrier.calloc(1, stack).sType$Default()
                    .srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT).dstAccessMask(VK_ACCESS_HOST_READ_BIT)
                    .buffer(aquiferNearestOutput.buffer)
                    .size((long) count * GpuAquiferSubstance.OUTPUT_INTS * Integer.BYTES);
            vkCmdPipelineBarrier(commandBuffer, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK_PIPELINE_STAGE_HOST_BIT, 0, null, barrier, null);
            check(vkEndCommandBuffer(commandBuffer), "end aquifer substance command buffer");
            synchronized (queueSubmitLock) {
                check(vkQueueSubmit(queue, VkSubmitInfo.calloc(stack).sType$Default()
                        .pCommandBuffers(stack.pointers(commandBuffer.address())), fence), "submit aquifer substance");
            }
            check(vkWaitForFences(device, fence, true, Long.MAX_VALUE), "wait aquifer substance");
            check(vkResetFences(device, fence), "reset aquifer substance fence");
            aquiferNearestOutput.invalidate(device, stack);
            IntBuffer values = persistentMappings.get(aquiferNearestOutput)
                    .duplicate().order(ByteOrder.nativeOrder()).asIntBuffer();
            values.clear();
            values.limit(count * GpuAquiferSubstance.OUTPUT_INTS);
            int[] output = new int[count * GpuAquiferSubstance.OUTPUT_INTS];
            values.get(output);
            return output;
        }
    }

    /** Tests the production interleaved-root lattice contract as well as compact smoke inputs. */
    synchronized float[] submitInterpolation(float[] corners, ByteBuffer metadata,
                                              int chunkCount, int blocksPerChunk,
                                              int rootStride, int outputStride) throws Exception {
        int blockCount = Math.multiplyExact(chunkCount, blocksPerChunk);
        if (rootStride <= 0 || outputStride <= 0 || outputStride > rootStride
                || corners.length % rootStride != 0
                || corners.length * 4L > results.size || metadata.remaining() > interpolationMetadata.size
                || (long) blockCount * outputStride * 4L > interpolationOutput.size)
            throw new IllegalArgumentException("interpolation batch exceeds GPU capacity or has invalid root strides");
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer cornerBytes = stack.malloc(corners.length * Float.BYTES).order(ByteOrder.nativeOrder());
            cornerBytes.asFloatBuffer().put(corners); cornerBytes.position(0).limit(corners.length * Float.BYTES);
            put(results, cornerBytes, stack); put(interpolationMetadata, metadata, stack);
            check(vkResetCommandBuffer(commandBuffer, 0), "reset interpolation command buffer");
            check(vkBeginCommandBuffer(commandBuffer, VkCommandBufferBeginInfo.calloc(stack).sType$Default()), "begin interpolation command buffer");
            vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, interpolationPipeline);
            vkCmdBindDescriptorSets(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, interpolationPipelineLayout, 0, stack.longs(interpolationDescriptorSet), null);
            ByteBuffer push = stack.malloc(16).order(ByteOrder.nativeOrder())
                    .putInt(chunkCount).putInt(blocksPerChunk).putInt(rootStride).putInt(outputStride).flip();
            vkCmdPushConstants(commandBuffer, interpolationPipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, push);
            vkCmdDispatch(commandBuffer, (blockCount + 63) / 64, 1, 1);
            VkBufferMemoryBarrier.Buffer barrier = VkBufferMemoryBarrier.calloc(1, stack).sType$Default()
                    .srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT).dstAccessMask(VK_ACCESS_HOST_READ_BIT)
                    .buffer(interpolationOutput.buffer).size((long) blockCount * outputStride * 4L);
            vkCmdPipelineBarrier(commandBuffer, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_HOST_BIT, 0, null, barrier, null);
            check(vkEndCommandBuffer(commandBuffer), "end interpolation command buffer");
            synchronized (queueSubmitLock) { check(vkQueueSubmit(queue, VkSubmitInfo.calloc(stack).sType$Default().pCommandBuffers(stack.pointers(commandBuffer.address())), fence), "submit interpolation"); }
            check(vkWaitForFences(device, fence, true, Long.MAX_VALUE), "wait interpolation");
            check(vkResetFences(device, fence), "reset interpolation fence");
            interpolationOutput.invalidate(device, stack);
            FloatBuffer values = persistentMappings.get(interpolationOutput)
                    .duplicate().order(ByteOrder.nativeOrder()).asFloatBuffer();
            values.clear();
            values.limit(blockCount * outputStride);
            float[] output = new float[blockCount * outputStride];
            values.get(output);
            return output;
        }
    }

    private GpuDensityGraphBuffer.Packed packedGraph(GpuDensityGraph graph) {
        GpuDensityGraphBuffer.Packed packed = packedGraphs.get(graph);
        if (packed != null) return packed;
        packed = GpuDensityGraphBuffer.pack(graph);
        packedGraphs.put(graph, packed);
        return packed;
    }

    private void mapPersistentBuffers(MemoryStack stack) {
        NativeBuffer[] buffers = {coordinates, results, permutations, octaves, samplerMetadata,
                graphCoordinates, graphNodes, graphSplinePoints, graphBlendedMetadata, graphBlendInputs, graphRoots,
                interpolationMetadata, interpolationOutput, aquiferPressureInputs,
                aquiferNearestInputs, aquiferNearestOutput};
        for (NativeBuffer buffer : buffers) {
            if (buffer.hostVisible) {
                persistentMappings.put(buffer, buffer.map(device, stack).order(ByteOrder.nativeOrder()));
            }
        }
        persistentResults = persistentMappings.get(results);
    }

    private void put(NativeBuffer target, ByteBuffer source, MemoryStack stack) {
        ByteBuffer mapped = persistentMappings.get(target);
        if (mapped == null) {
            int bytes = source.remaining();
            NativeBuffer staging = hostVisibleBuffer(bytes, stack);
            ByteBuffer stagingMap = staging.map(device, stack).order(ByteOrder.nativeOrder());
            stagingMap.put(source.duplicate());
            staging.flush(device, stack);
            check(vkResetCommandBuffer(commandBuffer, 0), "reset upload command buffer");
            check(vkBeginCommandBuffer(commandBuffer, VkCommandBufferBeginInfo.calloc(stack).sType$Default()), "begin upload command buffer");
            VkBufferCopy.Buffer copy = VkBufferCopy.calloc(1, stack).srcOffset(0).dstOffset(0).size(bytes);
            vkCmdCopyBuffer(commandBuffer, staging.buffer, target.buffer, copy);
            check(vkEndCommandBuffer(commandBuffer), "end upload command buffer");
            synchronized (queueSubmitLock) {
                check(vkQueueSubmit(queue, VkSubmitInfo.calloc(stack).sType$Default()
                        .pCommandBuffers(stack.pointers(commandBuffer.address())), fence), "submit upload");
            }
            check(vkWaitForFences(device, fence, true, Long.MAX_VALUE), "wait upload");
            check(vkResetFences(device, fence), "reset upload fence");
            staging.unmap(device);
            staging.destroy(device);
            return;
        }
        mapped.clear();
        mapped.put(source.duplicate());
        target.flush(device, stack);
    }
    private VkInstance createInstance(MemoryStack s) { VkApplicationInfo a=VkApplicationInfo.calloc(s).sType$Default().pApplicationName(s.UTF8("GPU Noise Persistent")).apiVersion(VK_API_VERSION_1_0); VkInstanceCreateInfo i=VkInstanceCreateInfo.calloc(s).sType$Default().pApplicationInfo(a); PointerBuffer h=s.mallocPointer(1); check(vkCreateInstance(i,null,h),"create instance"); return new VkInstance(h.get(0),i); }
    private VkPhysicalDevice findPhysical(VkInstance i,GpuDevice e,MemoryStack s){IntBuffer n=s.ints(0);check(vkEnumeratePhysicalDevices(i,n,null),"enumerate");PointerBuffer h=s.mallocPointer(n.get(0));check(vkEnumeratePhysicalDevices(i,n,h),"enumerate");for(int k=0;k<n.get(0);k++){VkPhysicalDevice p=new VkPhysicalDevice(h.get(k),i);VkPhysicalDeviceProperties q=VkPhysicalDeviceProperties.malloc();try{vkGetPhysicalDeviceProperties(p,q);if(q.vendorID()==e.vendorId()&&q.deviceNameString().equals(e.name()))return p;}finally{q.free();}}throw new IllegalStateException("GPU disappeared");}
    private int findQueue(VkPhysicalDevice p,MemoryStack s){IntBuffer n=s.ints(0);vkGetPhysicalDeviceQueueFamilyProperties(p,n,null);VkQueueFamilyProperties.Buffer f=VkQueueFamilyProperties.malloc(n.get(0),s);vkGetPhysicalDeviceQueueFamilyProperties(p,n,f);for(int i=0;i<n.get(0);i++)if((f.get(i).queueFlags()&VK_QUEUE_COMPUTE_BIT)!=0)return i;throw new IllegalStateException("no compute queue");}
    private NativeBuffer buffer(long size,MemoryStack s){return createBuffer(size,VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT|VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,s);}
    private NativeBuffer hostVisibleBuffer(long size,MemoryStack s){return buffer(size,s);}
    private NativeBuffer deviceLocalBuffer(long size,MemoryStack s){
        return createBuffer(size,VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,s);
    }
    private NativeBuffer createBuffer(long size, int properties, MemoryStack stack) {
        VkBufferCreateInfo bufferInfo = VkBufferCreateInfo.calloc(stack).sType$Default()
                .size(size)
                .usage(VK_BUFFER_USAGE_STORAGE_BUFFER_BIT
                        | VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT)
                .sharingMode(VK_SHARING_MODE_EXCLUSIVE);
        LongBuffer bufferHandle = stack.longs(0);
        check(vkCreateBuffer(device, bufferInfo, null, bufferHandle), "create buffer");

        VkMemoryRequirements requirements = VkMemoryRequirements.malloc(stack);
        vkGetBufferMemoryRequirements(device, bufferHandle.get(0), requirements);
        VkPhysicalDeviceMemoryProperties memoryProperties = VkPhysicalDeviceMemoryProperties.malloc(stack);
        vkGetPhysicalDeviceMemoryProperties(physical, memoryProperties);
        int memoryType = -1;
        for (int index = 0; index < memoryProperties.memoryTypeCount(); index++) {
            int flags = memoryProperties.memoryTypes(index).propertyFlags();
            if ((requirements.memoryTypeBits() & (1 << index)) != 0 && (flags & properties) == properties) {
                memoryType = index;
                break;
            }
        }
        if (memoryType < 0) throw new IllegalStateException("no compatible Vulkan memory");

        int actualFlags = memoryProperties.memoryTypes(memoryType).propertyFlags();
        VkMemoryAllocateInfo allocateInfo = VkMemoryAllocateInfo.calloc(stack).sType$Default()
                .allocationSize(requirements.size()).memoryTypeIndex(memoryType);
        LongBuffer memoryHandle = stack.longs(0);
        check(vkAllocateMemory(device, allocateInfo, null, memoryHandle), "allocate");
        check(vkBindBufferMemory(device, bufferHandle.get(0), memoryHandle.get(0), 0), "bind");

        // Lavapipe and unified-memory GPUs often expose device-local storage
        // that is also coherent and host-visible. Map that storage once and
        // write it directly instead of allocating transient staging buffers.
        int coherentHostFlags = VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
        boolean persistentlyMappable = (actualFlags & coherentHostFlags) == coherentHostFlags;
        return new NativeBuffer(bufferHandle.get(0), memoryHandle.get(0), size, persistentlyMappable);
    }
    private long shader(MemoryStack s)throws Exception{try(var in=getClass().getResourceAsStream("/assets/gpunoise/shaders/minecraft_noise_batch.comp.spv")){byte[] b=in.readAllBytes();ByteBuffer c=MemoryUtil.memAlloc(b.length).put(b).flip();try{VkShaderModuleCreateInfo i=VkShaderModuleCreateInfo.calloc(s).sType$Default().pCode(c);LongBuffer h=s.longs(0);check(vkCreateShaderModule(device,i,null,h),"shader");return h.get(0);}finally{MemoryUtil.memFree(c);}}}
    private long descriptorLayout(MemoryStack s){VkDescriptorSetLayoutBinding.Buffer b=VkDescriptorSetLayoutBinding.calloc(5,s);for(int i=0;i<5;i++)b.get(i).binding(i).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);VkDescriptorSetLayoutCreateInfo i=VkDescriptorSetLayoutCreateInfo.calloc(s).sType$Default().pBindings(b);LongBuffer h=s.longs(0);check(vkCreateDescriptorSetLayout(device,i,null,h),"layout");return h.get(0);}
    private long pipelineLayout(MemoryStack s){VkPushConstantRange.Buffer r=VkPushConstantRange.calloc(1,s).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT).size(8);VkPipelineLayoutCreateInfo i=VkPipelineLayoutCreateInfo.calloc(s).sType$Default().pSetLayouts(s.longs(descriptorLayout)).pPushConstantRanges(r);LongBuffer h=s.longs(0);check(vkCreatePipelineLayout(device,i,null,h),"pipeline layout");return h.get(0);}
    private long pipeline(MemoryStack s){VkPipelineShaderStageCreateInfo st=VkPipelineShaderStageCreateInfo.calloc(s).sType$Default().stage(VK_SHADER_STAGE_COMPUTE_BIT).module(shader).pName(s.UTF8("main"));VkComputePipelineCreateInfo.Buffer i=VkComputePipelineCreateInfo.calloc(1,s).sType$Default().stage(st).layout(pipelineLayout);LongBuffer h=s.longs(0);check(vkCreateComputePipelines(device,VK_NULL_HANDLE,i,null,h),"pipeline");return h.get(0);}
    private long descriptorPool(MemoryStack s){VkDescriptorPoolSize.Buffer z=VkDescriptorPoolSize.calloc(1,s).type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(5);VkDescriptorPoolCreateInfo i=VkDescriptorPoolCreateInfo.calloc(s).sType$Default().maxSets(1).pPoolSizes(z);LongBuffer h=s.longs(0);check(vkCreateDescriptorPool(device,i,null,h),"pool");return h.get(0);}
    private long descriptorSet(MemoryStack s){VkDescriptorSetAllocateInfo i=VkDescriptorSetAllocateInfo.calloc(s).sType$Default().descriptorPool(descriptorPool).pSetLayouts(s.longs(descriptorLayout));LongBuffer h=s.longs(0);check(vkAllocateDescriptorSets(device,i,h),"set");return h.get(0);}
    private void descriptors(MemoryStack s){NativeBuffer[] bs={coordinates,results,permutations,octaves,samplerMetadata};VkWriteDescriptorSet.Buffer w=VkWriteDescriptorSet.calloc(5,s);for(int i=0;i<5;i++){VkDescriptorBufferInfo.Buffer bi=VkDescriptorBufferInfo.calloc(1,s).buffer(bs[i].buffer).range(bs[i].size);w.get(i).sType$Default().dstSet(descriptorSet).dstBinding(i).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).pBufferInfo(bi);}vkUpdateDescriptorSets(device,w,null);}
    private long graphShader(MemoryStack s)throws Exception{return loadShader(s,"/assets/gpunoise/shaders/density_graph.comp.spv","graph shader");}
    private long gridShader(MemoryStack s)throws Exception{return loadShader(s,"/assets/gpunoise/shaders/density_graph_grid.comp.spv","grid shader");}
    private long loadShader(MemoryStack s,String resource,String operation)throws Exception{try(var in=getClass().getResourceAsStream(resource)){if(in==null)throw new IllegalStateException("missing shader "+resource);byte[] b=in.readAllBytes();ByteBuffer c=MemoryUtil.memAlloc(b.length).put(b).flip();try{VkShaderModuleCreateInfo i=VkShaderModuleCreateInfo.calloc(s).sType$Default().pCode(c);LongBuffer h=s.longs(0);check(vkCreateShaderModule(device,i,null,h),operation);return h.get(0);}finally{MemoryUtil.memFree(c);}}}
    private long graphDescriptorLayout(MemoryStack s){VkDescriptorSetLayoutBinding.Buffer b=VkDescriptorSetLayoutBinding.calloc(11,s);for(int i=0;i<11;i++)b.get(i).binding(i).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);VkDescriptorSetLayoutCreateInfo i=VkDescriptorSetLayoutCreateInfo.calloc(s).sType$Default().pBindings(b);LongBuffer h=s.longs(0);check(vkCreateDescriptorSetLayout(device,i,null,h),"graph layout");return h.get(0);}
    private long graphPipelineLayout(MemoryStack s){VkPushConstantRange.Buffer r=VkPushConstantRange.calloc(1,s).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT).size(16);VkPipelineLayoutCreateInfo i=VkPipelineLayoutCreateInfo.calloc(s).sType$Default().pSetLayouts(s.longs(graphDescriptorLayout)).pPushConstantRanges(r);LongBuffer h=s.longs(0);check(vkCreatePipelineLayout(device,i,null,h),"graph pipeline layout");return h.get(0);}
    private long graphPipeline(MemoryStack s){VkPipelineShaderStageCreateInfo st=VkPipelineShaderStageCreateInfo.calloc(s).sType$Default().stage(VK_SHADER_STAGE_COMPUTE_BIT).module(graphShader).pName(s.UTF8("main"));VkComputePipelineCreateInfo.Buffer i=VkComputePipelineCreateInfo.calloc(1,s).sType$Default().stage(st).layout(graphPipelineLayout);LongBuffer h=s.longs(0);check(vkCreateComputePipelines(device,VK_NULL_HANDLE,i,null,h),"graph pipeline");return h.get(0);}
    private long gridPipeline(MemoryStack s){VkPipelineShaderStageCreateInfo st=VkPipelineShaderStageCreateInfo.calloc(s).sType$Default().stage(VK_SHADER_STAGE_COMPUTE_BIT).module(gridShader).pName(s.UTF8("main"));VkComputePipelineCreateInfo.Buffer i=VkComputePipelineCreateInfo.calloc(1,s).sType$Default().stage(st).layout(graphPipelineLayout);LongBuffer h=s.longs(0);check(vkCreateComputePipelines(device,VK_NULL_HANDLE,i,null,h),"grid pipeline");return h.get(0);}
    private void ensureGraphVariant(GpuDensityGraph graph, MemoryStack stack) throws Exception {
        int required = graph.registerCount();
        int variant = required <= 32 ? 32 : required <= 64 ? 64 : required <= 128 ? 128 : 256;
        if (variant == graphRegisterVariant && FAST_GRAPH_MATH == graphFastMathVariant) return;
        vkDestroyPipeline(device, graphPipeline, null);
        vkDestroyPipeline(device, gridPipeline, null);
        vkDestroyShaderModule(device, graphShader, null);
        vkDestroyShaderModule(device, gridShader, null);
        String fast = FAST_GRAPH_MATH ? "_fast" : "";
        graphShader = loadShader(stack, "/assets/gpunoise/shaders/density_graph" + fast
                + "_" + variant + ".comp.spv", "graph shader variant");
        gridShader = loadShader(stack, "/assets/gpunoise/shaders/density_graph_grid" + fast
                + "_" + variant + ".comp.spv", "grid shader variant");
        graphPipeline = graphPipeline(stack);
        gridPipeline = gridPipeline(stack);
        graphRegisterVariant = variant;
        graphFastMathVariant = FAST_GRAPH_MATH;
        GpuNoise.LOGGER.info("GPU density shader variant: registers={}, fastMath={}", variant, FAST_GRAPH_MATH);
    }
    private long graphDescriptorPool(MemoryStack s){VkDescriptorPoolSize.Buffer z=VkDescriptorPoolSize.calloc(1,s).type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(11);VkDescriptorPoolCreateInfo i=VkDescriptorPoolCreateInfo.calloc(s).sType$Default().maxSets(1).pPoolSizes(z);LongBuffer h=s.longs(0);check(vkCreateDescriptorPool(device,i,null,h),"graph pool");return h.get(0);}
    private long graphDescriptorSet(MemoryStack s){VkDescriptorSetAllocateInfo i=VkDescriptorSetAllocateInfo.calloc(s).sType$Default().descriptorPool(graphDescriptorPool).pSetLayouts(s.longs(graphDescriptorLayout));LongBuffer h=s.longs(0);check(vkAllocateDescriptorSets(device,i,h),"graph set");return h.get(0);}
    private void graphDescriptors(MemoryStack s){NativeBuffer[] bs={graphCoordinates,results,graphNodes,permutations,octaves,samplerMetadata,graphSplinePoints,graphBlendedMetadata,graphBlendInputs,graphRoots,graphMthSinTable};VkWriteDescriptorSet.Buffer w=VkWriteDescriptorSet.calloc(11,s);for(int i=0;i<11;i++){VkDescriptorBufferInfo.Buffer bi=VkDescriptorBufferInfo.calloc(1,s).buffer(bs[i].buffer).range(bs[i].size);w.get(i).sType$Default().dstSet(graphDescriptorSet).dstBinding(i).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).pBufferInfo(bi);}vkUpdateDescriptorSets(device,w,null);}
    private long interpolationShader(MemoryStack s)throws Exception{try(var in=getClass().getResourceAsStream("/assets/gpunoise/shaders/noise_interpolation.comp.spv")){byte[] b=in.readAllBytes();ByteBuffer c=MemoryUtil.memAlloc(b.length).put(b).flip();try{VkShaderModuleCreateInfo i=VkShaderModuleCreateInfo.calloc(s).sType$Default().pCode(c);LongBuffer h=s.longs(0);check(vkCreateShaderModule(device,i,null,h),"interpolation shader");return h.get(0);}finally{MemoryUtil.memFree(c);}}}
    private long interpolationDescriptorLayout(MemoryStack s){VkDescriptorSetLayoutBinding.Buffer b=VkDescriptorSetLayoutBinding.calloc(3,s);for(int i=0;i<3;i++)b.get(i).binding(i).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);VkDescriptorSetLayoutCreateInfo i=VkDescriptorSetLayoutCreateInfo.calloc(s).sType$Default().pBindings(b);LongBuffer h=s.longs(0);check(vkCreateDescriptorSetLayout(device,i,null,h),"interpolation layout");return h.get(0);}
    private long interpolationPipelineLayout(MemoryStack s){VkPushConstantRange.Buffer r=VkPushConstantRange.calloc(1,s).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT).size(16);VkPipelineLayoutCreateInfo i=VkPipelineLayoutCreateInfo.calloc(s).sType$Default().pSetLayouts(s.longs(interpolationDescriptorLayout)).pPushConstantRanges(r);LongBuffer h=s.longs(0);check(vkCreatePipelineLayout(device,i,null,h),"interpolation pipeline layout");return h.get(0);}
    private long interpolationPipeline(MemoryStack s){VkPipelineShaderStageCreateInfo st=VkPipelineShaderStageCreateInfo.calloc(s).sType$Default().stage(VK_SHADER_STAGE_COMPUTE_BIT).module(interpolationShader).pName(s.UTF8("main"));VkComputePipelineCreateInfo.Buffer i=VkComputePipelineCreateInfo.calloc(1,s).sType$Default().stage(st).layout(interpolationPipelineLayout);LongBuffer h=s.longs(0);check(vkCreateComputePipelines(device,VK_NULL_HANDLE,i,null,h),"interpolation pipeline");return h.get(0);}
    private long interpolationDescriptorPool(MemoryStack s){VkDescriptorPoolSize.Buffer z=VkDescriptorPoolSize.calloc(1,s).type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(3);VkDescriptorPoolCreateInfo i=VkDescriptorPoolCreateInfo.calloc(s).sType$Default().maxSets(1).pPoolSizes(z);LongBuffer h=s.longs(0);check(vkCreateDescriptorPool(device,i,null,h),"interpolation pool");return h.get(0);}
    private long interpolationDescriptorSet(MemoryStack s){VkDescriptorSetAllocateInfo i=VkDescriptorSetAllocateInfo.calloc(s).sType$Default().descriptorPool(interpolationDescriptorPool).pSetLayouts(s.longs(interpolationDescriptorLayout));LongBuffer h=s.longs(0);check(vkAllocateDescriptorSets(device,i,h),"interpolation set");return h.get(0);}
    private void interpolationDescriptors(MemoryStack s){NativeBuffer[] bs={results,interpolationOutput,interpolationMetadata};VkWriteDescriptorSet.Buffer w=VkWriteDescriptorSet.calloc(3,s);for(int i=0;i<3;i++){VkDescriptorBufferInfo.Buffer bi=VkDescriptorBufferInfo.calloc(1,s).buffer(bs[i].buffer).range(bs[i].size);w.get(i).sType$Default().dstSet(interpolationDescriptorSet).dstBinding(i).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).pBufferInfo(bi);}vkUpdateDescriptorSets(device,w,null);}
    private long aquiferPressureDescriptorLayout(MemoryStack s){VkDescriptorSetLayoutBinding.Buffer b=VkDescriptorSetLayoutBinding.calloc(2,s);for(int i=0;i<2;i++)b.get(i).binding(i).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);VkDescriptorSetLayoutCreateInfo i=VkDescriptorSetLayoutCreateInfo.calloc(s).sType$Default().pBindings(b);LongBuffer h=s.longs(0);check(vkCreateDescriptorSetLayout(device,i,null,h),"aquifer pressure layout");return h.get(0);}
    private long aquiferPressurePipelineLayout(MemoryStack s){VkPushConstantRange.Buffer r=VkPushConstantRange.calloc(1,s).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT).size(4);VkPipelineLayoutCreateInfo i=VkPipelineLayoutCreateInfo.calloc(s).sType$Default().pSetLayouts(s.longs(aquiferPressureDescriptorLayout)).pPushConstantRanges(r);LongBuffer h=s.longs(0);check(vkCreatePipelineLayout(device,i,null,h),"aquifer pressure pipeline layout");return h.get(0);}
    private long aquiferPressurePipeline(MemoryStack s){VkPipelineShaderStageCreateInfo st=VkPipelineShaderStageCreateInfo.calloc(s).sType$Default().stage(VK_SHADER_STAGE_COMPUTE_BIT).module(aquiferPressureShader).pName(s.UTF8("main"));VkComputePipelineCreateInfo.Buffer i=VkComputePipelineCreateInfo.calloc(1,s).sType$Default().stage(st).layout(aquiferPressurePipelineLayout);LongBuffer h=s.longs(0);check(vkCreateComputePipelines(device,VK_NULL_HANDLE,i,null,h),"aquifer pressure pipeline");return h.get(0);}
    private long aquiferPressureDescriptorPool(MemoryStack s){VkDescriptorPoolSize.Buffer z=VkDescriptorPoolSize.calloc(1,s).type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(2);VkDescriptorPoolCreateInfo i=VkDescriptorPoolCreateInfo.calloc(s).sType$Default().maxSets(1).pPoolSizes(z);LongBuffer h=s.longs(0);check(vkCreateDescriptorPool(device,i,null,h),"aquifer pressure pool");return h.get(0);}
    private long aquiferPressureDescriptorSet(MemoryStack s){VkDescriptorSetAllocateInfo i=VkDescriptorSetAllocateInfo.calloc(s).sType$Default().descriptorPool(aquiferPressureDescriptorPool).pSetLayouts(s.longs(aquiferPressureDescriptorLayout));LongBuffer h=s.longs(0);check(vkAllocateDescriptorSets(device,i,h),"aquifer pressure set");return h.get(0);}
    private void aquiferPressureDescriptors(MemoryStack s){NativeBuffer[] bs={aquiferPressureInputs,interpolationOutput};VkWriteDescriptorSet.Buffer w=VkWriteDescriptorSet.calloc(2,s);for(int i=0;i<2;i++){VkDescriptorBufferInfo.Buffer bi=VkDescriptorBufferInfo.calloc(1,s).buffer(bs[i].buffer).range(bs[i].size);w.get(i).sType$Default().dstSet(aquiferPressureDescriptorSet).dstBinding(i).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).pBufferInfo(bi);}vkUpdateDescriptorSets(device,w,null);}
    private long aquiferNearestDescriptorLayout(MemoryStack s){VkDescriptorSetLayoutBinding.Buffer b=VkDescriptorSetLayoutBinding.calloc(2,s);for(int i=0;i<2;i++)b.get(i).binding(i).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);VkDescriptorSetLayoutCreateInfo i=VkDescriptorSetLayoutCreateInfo.calloc(s).sType$Default().pBindings(b);LongBuffer h=s.longs(0);check(vkCreateDescriptorSetLayout(device,i,null,h),"aquifer nearest-three layout");return h.get(0);}
    private long aquiferNearestPipelineLayout(MemoryStack s){VkPushConstantRange.Buffer r=VkPushConstantRange.calloc(1,s).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT).size(4);VkPipelineLayoutCreateInfo i=VkPipelineLayoutCreateInfo.calloc(s).sType$Default().pSetLayouts(s.longs(aquiferNearestDescriptorLayout)).pPushConstantRanges(r);LongBuffer h=s.longs(0);check(vkCreatePipelineLayout(device,i,null,h),"aquifer nearest-three pipeline layout");return h.get(0);}
    private long aquiferNearestPipeline(MemoryStack s){VkPipelineShaderStageCreateInfo st=VkPipelineShaderStageCreateInfo.calloc(s).sType$Default().stage(VK_SHADER_STAGE_COMPUTE_BIT).module(aquiferNearestShader).pName(s.UTF8("main"));VkComputePipelineCreateInfo.Buffer i=VkComputePipelineCreateInfo.calloc(1,s).sType$Default().stage(st).layout(aquiferNearestPipelineLayout);LongBuffer h=s.longs(0);check(vkCreateComputePipelines(device,VK_NULL_HANDLE,i,null,h),"aquifer nearest-three pipeline");return h.get(0);}
    private long aquiferNearestDescriptorPool(MemoryStack s){VkDescriptorPoolSize.Buffer z=VkDescriptorPoolSize.calloc(1,s).type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(2);VkDescriptorPoolCreateInfo i=VkDescriptorPoolCreateInfo.calloc(s).sType$Default().maxSets(1).pPoolSizes(z);LongBuffer h=s.longs(0);check(vkCreateDescriptorPool(device,i,null,h),"aquifer nearest-three pool");return h.get(0);}
    private long aquiferNearestDescriptorSet(MemoryStack s){VkDescriptorSetAllocateInfo i=VkDescriptorSetAllocateInfo.calloc(s).sType$Default().descriptorPool(aquiferNearestDescriptorPool).pSetLayouts(s.longs(aquiferNearestDescriptorLayout));LongBuffer h=s.longs(0);check(vkAllocateDescriptorSets(device,i,h),"aquifer nearest-three set");return h.get(0);}
    private void aquiferNearestDescriptors(MemoryStack s){NativeBuffer[] bs={aquiferNearestInputs,aquiferNearestOutput};VkWriteDescriptorSet.Buffer w=VkWriteDescriptorSet.calloc(2,s);for(int i=0;i<2;i++){VkDescriptorBufferInfo.Buffer bi=VkDescriptorBufferInfo.calloc(1,s).buffer(bs[i].buffer).range(bs[i].size);w.get(i).sType$Default().dstSet(aquiferNearestDescriptorSet).dstBinding(i).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).pBufferInfo(bi);}vkUpdateDescriptorSets(device,w,null);}
    private long aquiferSubstancePipelineLayout(MemoryStack s){VkPushConstantRange.Buffer r=VkPushConstantRange.calloc(1,s).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT).size(4);VkPipelineLayoutCreateInfo i=VkPipelineLayoutCreateInfo.calloc(s).sType$Default().pSetLayouts(s.longs(aquiferNearestDescriptorLayout)).pPushConstantRanges(r);LongBuffer h=s.longs(0);check(vkCreatePipelineLayout(device,i,null,h),"aquifer substance pipeline layout");return h.get(0);}
    private long aquiferSubstancePipeline(MemoryStack s){VkPipelineShaderStageCreateInfo st=VkPipelineShaderStageCreateInfo.calloc(s).sType$Default().stage(VK_SHADER_STAGE_COMPUTE_BIT).module(aquiferSubstanceShader).pName(s.UTF8("main"));VkComputePipelineCreateInfo.Buffer i=VkComputePipelineCreateInfo.calloc(1,s).sType$Default().stage(st).layout(aquiferSubstancePipelineLayout);LongBuffer h=s.longs(0);check(vkCreateComputePipelines(device,VK_NULL_HANDLE,i,null,h),"aquifer substance pipeline");return h.get(0);}
    private long commandPool(MemoryStack s){VkCommandPoolCreateInfo i=VkCommandPoolCreateInfo.calloc(s).sType$Default().flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT).queueFamilyIndex(queueFamily);LongBuffer h=s.longs(0);check(vkCreateCommandPool(device,i,null,h),"command pool");return h.get(0);}
    private VkCommandBuffer commandBuffer(MemoryStack s){VkCommandBufferAllocateInfo i=VkCommandBufferAllocateInfo.calloc(s).sType$Default().commandPool(commandPool).level(VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(1);PointerBuffer h=s.mallocPointer(1);check(vkAllocateCommandBuffers(device,i,h),"allocate command buffer");return new VkCommandBuffer(h.get(0),device);}
    private long fence(MemoryStack s){VkFenceCreateInfo i=VkFenceCreateInfo.calloc(s).sType$Default();LongBuffer h=s.longs(0);check(vkCreateFence(device,i,null,h),"create fence");return h.get(0);}
    private static void check(int r,String op){if(r!=VK_SUCCESS)throw new IllegalStateException(op+" failed: "+r);}
    private record NativeBuffer(long buffer,long memory,long size,boolean hostVisible){ByteBuffer map(VkDevice d,MemoryStack s){PointerBuffer p=s.mallocPointer(1);check(vkMapMemory(d,memory,0,size,0,p),"map");return MemoryUtil.memByteBuffer(p.get(0),(int)size);}void flush(VkDevice d,MemoryStack s){VkMappedMemoryRange r=VkMappedMemoryRange.calloc(s).sType$Default().memory(memory).offset(0).size(size);check(vkFlushMappedMemoryRanges(d,r),"flush");}void invalidate(VkDevice d,MemoryStack s){VkMappedMemoryRange r=VkMappedMemoryRange.calloc(s).sType$Default().memory(memory).offset(0).size(size);check(vkInvalidateMappedMemoryRanges(d,r),"invalidate");}void unmap(VkDevice d){if(hostVisible)vkUnmapMemory(d,memory);}void destroy(VkDevice d){vkDestroyBuffer(d,buffer,null);vkFreeMemory(d,memory,null);}}
    @Override public void close(){vkDeviceWaitIdle(device);for(NativeBuffer buffer:persistentMappings.keySet())buffer.unmap(device);vkDestroyFence(device,fence,null);vkDestroyCommandPool(device,commandPool,null);vkDestroyDescriptorPool(device,descriptorPool,null);vkDestroyPipeline(device,pipeline,null);vkDestroyPipelineLayout(device,pipelineLayout,null);vkDestroyDescriptorSetLayout(device,descriptorLayout,null);vkDestroyShaderModule(device,shader,null);vkDestroyPipeline(device,graphPipeline,null);vkDestroyPipeline(device,gridPipeline,null);vkDestroyPipelineLayout(device,graphPipelineLayout,null);vkDestroyDescriptorSetLayout(device,graphDescriptorLayout,null);vkDestroyDescriptorPool(device,graphDescriptorPool,null);vkDestroyShaderModule(device,graphShader,null);vkDestroyShaderModule(device,gridShader,null);vkDestroyPipeline(device,interpolationPipeline,null);vkDestroyPipelineLayout(device,interpolationPipelineLayout,null);vkDestroyDescriptorSetLayout(device,interpolationDescriptorLayout,null);vkDestroyDescriptorPool(device,interpolationDescriptorPool,null);vkDestroyShaderModule(device,interpolationShader,null);vkDestroyPipeline(device,aquiferPressurePipeline,null);vkDestroyPipelineLayout(device,aquiferPressurePipelineLayout,null);vkDestroyDescriptorSetLayout(device,aquiferPressureDescriptorLayout,null);vkDestroyDescriptorPool(device,aquiferPressureDescriptorPool,null);vkDestroyShaderModule(device,aquiferPressureShader,null);vkDestroyPipeline(device,aquiferNearestPipeline,null);vkDestroyPipelineLayout(device,aquiferNearestPipelineLayout,null);vkDestroyDescriptorSetLayout(device,aquiferNearestDescriptorLayout,null);vkDestroyDescriptorPool(device,aquiferNearestDescriptorPool,null);vkDestroyShaderModule(device,aquiferNearestShader,null);vkDestroyPipeline(device,aquiferSubstancePipeline,null);vkDestroyPipelineLayout(device,aquiferSubstancePipelineLayout,null);vkDestroyShaderModule(device,aquiferSubstanceShader,null);coordinates.destroy(device);graphCoordinates.destroy(device);graphNodes.destroy(device);graphSplinePoints.destroy(device);graphBlendedMetadata.destroy(device);graphBlendInputs.destroy(device);graphRoots.destroy(device);graphMthSinTable.destroy(device);interpolationMetadata.destroy(device);interpolationOutput.destroy(device);aquiferPressureInputs.destroy(device);aquiferNearestInputs.destroy(device);aquiferNearestOutput.destroy(device);results.destroy(device);permutations.destroy(device);octaves.destroy(device);samplerMetadata.destroy(device);if(ownsDevice){vkDestroyDevice(device,null);vkDestroyInstance(instance,null);}}
}
