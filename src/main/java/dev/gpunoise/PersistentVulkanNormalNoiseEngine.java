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
    private static final boolean GPU_TIMING_ENABLED = Boolean.getBoolean("gpunoise.profileTerrain");
    static final int CAPACITY = 262_144;
    static final int GRAPH_NODE_CAPACITY = 2048;
    /** Full per-block roots dominate output traffic; allow larger spatial batches without unbounded growth. */
    private static final int INTERPOLATION_OUTPUT_MIB = (int) Math.max(16L,
            Math.min(64L, Long.getLong("gpunoise.terrainOutputBufferMiB", 32L)));
    private static final int GRAPH_NODE_STORAGE_CAPACITY = GRAPH_NODE_CAPACITY * 2;
    private static final long AQUIFER_INPUT_BYTES = Math.max(
            GpuAquiferNearestThree.MAX_PACKED_BYTES
                    + (long) GpuAquiferFluidStatus.MAX_PREFILL_CENTERS * GpuAquiferFluidStatus.RECORD_BYTES,
            (long) GpuAquiferSubstance.MAX_QUERIES * GpuAquiferSubstance.RECORD_BYTES);
    private static final long AQUIFER_OUTPUT_BYTES = (Math.max(
            (long) GpuAquiferNearestThree.MAX_QUERIES * GpuAquiferNearestThree.OUTPUT_INTS,
            (long) GpuAquiferSubstance.MAX_QUERIES * GpuAquiferSubstance.OUTPUT_INTS)
            + (long) GpuAquiferFluidStatus.MAX_PREFILL_CENTERS * GpuAquiferFluidStatus.OUTPUT_INTS
            + (long) GpuAquiferNearestThree.MAX_QUERIES * 3L)
            * Integer.BYTES;
    private static final long BIOME_TREE_BUFFER_BYTES = 8L * 1024L * 1024L;
    // interpolationOutput stores the interleaved per-block terrain roots.
    static final int INTERPOLATION_OUTPUT_FLOATS = INTERPOLATION_OUTPUT_MIB * 1024 * 1024 / Float.BYTES;
    private static final int MAX_INTERPOLATORS = 16;
    private static final int MAX_GRAPH_OUTPUTS = 32;
    private static final int[] GRAPH_DESCRIPTOR_BINDINGS = {0, 1, 2, 3, 4, 5, 6, 7, 9, 10, 11, 12, 13, 14};
    static final int INTERPOLATED_INPUT_FLOATS = CAPACITY * MAX_INTERPOLATORS;
    private final VkInstance instance;
    private final VkPhysicalDevice physical;
    private final VkDevice device;
    private final VkQueue queue;
    private final int queueFamily;
    private final NativeBuffer coordinates, results, permutations, octaves, samplerMetadata;
    private final NativeBuffer graphCoordinates, graphNodes, graphSplinePoints, graphBlendedMetadata,
            graphRoots, graphMthSinTable, interpolatedInputs, interpolatorMap;
    private final NativeBuffer interpolationMetadata, interpolationOutput, aquiferPressureInputs,
            aquiferNearestInputs, aquiferNearestOutput, biomeTreeBuffer;
    private final long shader, descriptorLayout, pipelineLayout, pipeline, descriptorPool, descriptorSet, commandPool;
    private long graphShader, gridShader, graphPipeline, gridPipeline;
    private long blockGraphShader, blockGraphPipeline;
    private final long graphDescriptorLayout, graphPipelineLayout, graphDescriptorPool, graphDescriptorSet;
    private int graphRegisterVariant = -1;
    private boolean graphFastMathVariant;
    private GpuDensityGraph compiledGraphVariant;
    private int blockGraphRegisterVariant;
    private boolean blockGraphFastMathVariant;
    private GpuDensityGraph compiledBlockGraphVariant;
    private GpuDensityGraphBuffer.Packed compiledBlockGraphPacked;
    private GpuBiomeSearchTree uploadedBiomeTree;
    private final long interpolationShader, interpolationDescriptorLayout, interpolationPipelineLayout,
            interpolationPipeline, interpolationDescriptorPool, interpolationDescriptorSet;
    private final long aquiferPressureShader, aquiferPressureDescriptorLayout, aquiferPressurePipelineLayout,
            aquiferPressurePipeline, aquiferPressureDescriptorPool, aquiferPressureDescriptorSet;
    private final long aquiferNearestShader, aquiferNearestDescriptorLayout, aquiferNearestPipelineLayout,
            aquiferNearestPipeline, aquiferNearestDescriptorPool, aquiferNearestDescriptorSet;
    private final long aquiferSubstanceShader, aquiferSubstancePipelineLayout, aquiferSubstancePipeline;
    private final long oreVeinShader, oreVeinPipelineLayout, oreVeinPipeline;
    private final long aquiferFluidStatusShader, aquiferFluidStatusPipelineLayout, aquiferFluidStatusPipeline;
    private final long aquiferDecisionChainShader, aquiferDecisionChainPipelineLayout,
            aquiferDecisionChainPipeline;
    private final long biomeSearchShader, biomeSearchDescriptorLayout, biomeSearchPipelineLayout,
            biomeSearchPipeline, biomeSearchDescriptorPool, biomeSearchDescriptorSet;
    private final boolean ownsDevice;
    /** Vulkan requires host-side external synchronization for queue operations. */
    private final Object queueSubmitLock;
    private ByteBuffer persistentResults;
    private final Map<NativeBuffer, ByteBuffer> persistentMappings = new IdentityHashMap<>();
    private final Map<GpuDensityGraph, Map<Integer, GpuDensityGraphBuffer.Packed>> packedGraphs =
            new IdentityHashMap<>();
    // The startup parity pass uploads the full router graph, then terrain
    // generation uploads a reduced graph. A boolean cache flag incorrectly
    // treated those different layouts as interchangeable.
    private GpuDensityGraph graphResourcesGraph;
    private GpuDensityGraphBuffer.Packed graphResourcesPacked;
    private int[] uploadedGraphRoots;
    private int[] uploadedBlockGraphRoots;
    private GpuDensityGraph interpolatorMapGraph;
    private final long fence;
    private final VkCommandBuffer commandBuffer;
    /** Exactly one graph-roots dispatch owns this command buffer and mapped output at a time. */
    private CompletableFuture<float[]> inFlightGraphRoots;
    /** Full terrain dispatch shares the same slot and fence as graph-root work. */
    private CompletableFuture<GpuTerrainBatchResult> inFlightTerrain;
    /** Fused nearest-center and fluid-status prefill completes through the shared fence poller. */
    private CompletableFuture<GpuAquiferPrefillResult> inFlightAquiferPrefill;
    private int inFlightAquiferNearestCount;
    private int inFlightAquiferStatusCount;
    private boolean inFlightAquiferDecisionChain;
    private CompletableFuture<int[]> inFlightAquiferRows;
    private AquiferRowStage inFlightAquiferRowStage;
    private int inFlightAquiferRowCount;
    private enum AquiferRowStage {
        NEAREST(GpuAquiferNearestThree.OUTPUT_INTS),
        SUBSTANCE(GpuAquiferSubstance.OUTPUT_INTS),
        FLUID_STATUS(GpuAquiferFluidStatus.OUTPUT_INTS);

        private final int outputInts;
        AquiferRowStage(int outputInts) { this.outputInts = outputInts; }
    }
    private int inFlightSamples;
    private int inFlightRoots;
    private int inFlightTerrainBlockCount;
    private int inFlightTerrainOutputRoots;
    private int inFlightTerrainBlocksPerChunk;
    private int inFlightTerrainCornerRoots;
    private int inFlightTerrainCornerStride;
    private int[] inFlightTerrainRootIndexes;
    private long inFlightFenceSubmittedNanos;
    private long inFlightGraphPreparationNanos;
    private long inFlightTerrainPreparationNanos;
    private long completedGraphFenceSamples;
    private long totalGraphFenceNanos;
    private long totalGraphPreparationNanos;
    private long totalGraphReadbackNanos;
    private long totalGraphGpuCommandNanos;
    private long graphGpuTimestampSamples;
    private long completedTerrainBatchSamples;
    private long totalTerrainPreparationNanos;
    private long totalTerrainFenceNanos;
    private long totalTerrainReadbackNanos;
    private long totalTerrainGridGpuNanos;
    private long totalTerrainInterpolationGpuNanos;
    private long totalTerrainBlockGpuNanos;
    private long terrainGpuTimestampSamples;
    private long timestampQueryPool;
    private int timestampValidBits;
    private double timestampPeriodNanos;

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
            coordinates = buffer(CAPACITY * 16L, stack); // vec4 position; sampler id occupies unused w
            // Graph dispatches can return up to 16 router roots per sample.
            results = buffer(CAPACITY * (long) MAX_GRAPH_OUTPUTS * Float.BYTES, stack);
            graphCoordinates = hostVisibleBuffer(CAPACITY * 16L, stack); graphNodes = deviceLocalBuffer((long) GRAPH_NODE_STORAGE_CAPACITY * GpuDensityGraphBuffer.NODE_BYTES, stack); graphSplinePoints = deviceLocalBuffer((long) GRAPH_NODE_CAPACITY * 2L * 64L * 16L, stack); graphBlendedMetadata = deviceLocalBuffer((long) GRAPH_NODE_CAPACITY * 192L, stack); graphRoots = deviceLocalBuffer(128L, stack);
            graphMthSinTable = deviceLocalBuffer(65_536L * Float.BYTES, stack);
            interpolatedInputs = deviceLocalBuffer((long) INTERPOLATED_INPUT_FLOATS * Float.BYTES, stack);
            interpolatorMap = hostVisibleBuffer((1L + MAX_INTERPOLATORS + 256L) * Integer.BYTES, stack);
            interpolationMetadata = buffer(100L * GpuInterpolationBatch.METADATA_BYTES, stack);
            interpolationOutput = buffer((long) INTERPOLATION_OUTPUT_FLOATS * Float.BYTES, stack);
            aquiferPressureInputs = hostVisibleBuffer((long) CAPACITY * GpuAquiferPressure.RECORD_BYTES, stack);
            aquiferNearestInputs = hostVisibleBuffer(AQUIFER_INPUT_BYTES, stack);
            aquiferNearestOutput = buffer(AQUIFER_OUTPUT_BYTES, stack);
            biomeTreeBuffer = hostVisibleBuffer(BIOME_TREE_BUFFER_BYTES, stack);
            permutations = deviceLocalBuffer(8 * 1024 * 1024L, stack); octaves = deviceLocalBuffer(8 * 1024 * 1024L, stack); samplerMetadata = deviceLocalBuffer(64 * 1024L, stack);
            shader = shader(stack); descriptorLayout = descriptorLayout(stack); pipelineLayout = pipelineLayout(stack);
            pipeline = pipeline(stack); descriptorPool = descriptorPool(stack); descriptorSet = descriptorSet(stack);
            descriptors(stack); commandPool = commandPool(stack); commandBuffer = commandBuffer(stack); fence = fence(stack);
            timestampQueryPool = createTimestampQueryPool(stack);
            graphShader = graphShader(stack); graphDescriptorLayout = graphDescriptorLayout(stack);
            graphPipelineLayout = graphPipelineLayout(stack); graphPipeline = graphPipeline(stack);
            blockGraphShader = loadShader(stack, "/assets/gpunoise/shaders/density_graph.comp.spv", "block graph shader");
            blockGraphPipeline = graphPipeline(blockGraphShader, stack);
            blockGraphRegisterVariant = -1;
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
            oreVeinShader = loadShader(stack, "/assets/gpunoise/shaders/ore_vein.comp.spv", "ore vein shader");
            oreVeinPipelineLayout = aquiferSubstancePipelineLayout(stack);
            oreVeinPipeline = aquiferPipeline(oreVeinShader, oreVeinPipelineLayout, stack, "ore vein pipeline");
            aquiferFluidStatusShader = loadShader(stack, "/assets/gpunoise/shaders/aquifer_fluid_status.comp.spv", "aquifer fluid-status shader");
            aquiferFluidStatusPipelineLayout = aquiferFluidStatusPipelineLayout(stack);
            aquiferFluidStatusPipeline = aquiferPipeline(aquiferFluidStatusShader, aquiferFluidStatusPipelineLayout, stack, "aquifer fluid-status pipeline");
            aquiferDecisionChainShader = loadShader(stack, "/assets/gpunoise/shaders/aquifer_decision_chain.comp.spv", "aquifer decision-chain shader");
            aquiferDecisionChainPipelineLayout = aquiferDecisionChainPipelineLayout(stack);
            aquiferDecisionChainPipeline = aquiferPipeline(aquiferDecisionChainShader,
                    aquiferDecisionChainPipelineLayout, stack, "aquifer decision-chain pipeline");
            biomeSearchShader = loadShader(stack, "/assets/gpunoise/shaders/biome_multinoise.comp.spv", "multi-noise biome shader");
            biomeSearchDescriptorLayout = biomeSearchDescriptorLayout(stack);
            biomeSearchPipelineLayout = biomeSearchPipelineLayout(stack);
            biomeSearchPipeline = aquiferPipeline(biomeSearchShader, biomeSearchPipelineLayout, stack, "multi-noise biome pipeline");
            biomeSearchDescriptorPool = biomeSearchDescriptorPool(stack);
            biomeSearchDescriptorSet = biomeSearchDescriptorSet(stack);
            biomeSearchDescriptors(stack);
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
            coordinates = buffer(CAPACITY * 16L, stack);
            results = buffer(CAPACITY * (long) MAX_GRAPH_OUTPUTS * Float.BYTES, stack);
            graphCoordinates = hostVisibleBuffer(CAPACITY * 16L, stack);
            graphNodes = deviceLocalBuffer((long) GRAPH_NODE_STORAGE_CAPACITY * GpuDensityGraphBuffer.NODE_BYTES, stack);
            graphSplinePoints = deviceLocalBuffer((long) GRAPH_NODE_CAPACITY * 2L * 64L * 16L, stack);
            graphBlendedMetadata = deviceLocalBuffer((long) GRAPH_NODE_CAPACITY * 192L, stack);
            graphRoots = deviceLocalBuffer(128L, stack);
            graphMthSinTable = deviceLocalBuffer(65_536L * Float.BYTES, stack);
            interpolatedInputs = deviceLocalBuffer((long) INTERPOLATED_INPUT_FLOATS * Float.BYTES, stack);
            interpolatorMap = hostVisibleBuffer((1L + MAX_INTERPOLATORS + 256L) * Integer.BYTES, stack);
            interpolationMetadata = buffer(100L * GpuInterpolationBatch.METADATA_BYTES, stack);
            interpolationOutput = buffer((long) INTERPOLATION_OUTPUT_FLOATS * Float.BYTES, stack);
            aquiferPressureInputs = hostVisibleBuffer((long) CAPACITY * GpuAquiferPressure.RECORD_BYTES, stack);
            aquiferNearestInputs = hostVisibleBuffer(AQUIFER_INPUT_BYTES, stack);
            aquiferNearestOutput = buffer(AQUIFER_OUTPUT_BYTES, stack);
            biomeTreeBuffer = hostVisibleBuffer(BIOME_TREE_BUFFER_BYTES, stack);
            permutations = deviceLocalBuffer(8 * 1024 * 1024L, stack);
            octaves = deviceLocalBuffer(8 * 1024 * 1024L, stack);
            samplerMetadata = deviceLocalBuffer(64 * 1024L, stack);
            shader = shader(stack); descriptorLayout = descriptorLayout(stack); pipelineLayout = pipelineLayout(stack);
            pipeline = pipeline(stack); descriptorPool = descriptorPool(stack); descriptorSet = descriptorSet(stack);
            descriptors(stack); commandPool = commandPool(stack); commandBuffer = commandBuffer(stack); fence = fence(stack);
            timestampQueryPool = createTimestampQueryPool(stack);
            graphShader = graphShader(stack); graphDescriptorLayout = graphDescriptorLayout(stack);
            graphPipelineLayout = graphPipelineLayout(stack); graphPipeline = graphPipeline(stack);
            blockGraphShader = loadShader(stack, "/assets/gpunoise/shaders/density_graph.comp.spv", "block graph shader");
            blockGraphPipeline = graphPipeline(blockGraphShader, stack);
            blockGraphRegisterVariant = -1;
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
            oreVeinShader = loadShader(stack, "/assets/gpunoise/shaders/ore_vein.comp.spv", "ore vein shader");
            oreVeinPipelineLayout = aquiferSubstancePipelineLayout(stack);
            oreVeinPipeline = aquiferPipeline(oreVeinShader, oreVeinPipelineLayout, stack, "ore vein pipeline");
            aquiferFluidStatusShader = loadShader(stack, "/assets/gpunoise/shaders/aquifer_fluid_status.comp.spv", "aquifer fluid-status shader");
            aquiferFluidStatusPipelineLayout = aquiferFluidStatusPipelineLayout(stack);
            aquiferFluidStatusPipeline = aquiferPipeline(aquiferFluidStatusShader, aquiferFluidStatusPipelineLayout, stack, "aquifer fluid-status pipeline");
            aquiferDecisionChainShader = loadShader(stack, "/assets/gpunoise/shaders/aquifer_decision_chain.comp.spv", "aquifer decision-chain shader");
            aquiferDecisionChainPipelineLayout = aquiferDecisionChainPipelineLayout(stack);
            aquiferDecisionChainPipeline = aquiferPipeline(aquiferDecisionChainShader,
                    aquiferDecisionChainPipelineLayout, stack, "aquifer decision-chain pipeline");
            biomeSearchShader = loadShader(stack, "/assets/gpunoise/shaders/biome_multinoise.comp.spv", "multi-noise biome shader");
            biomeSearchDescriptorLayout = biomeSearchDescriptorLayout(stack);
            biomeSearchPipelineLayout = biomeSearchPipelineLayout(stack);
            biomeSearchPipeline = aquiferPipeline(biomeSearchShader, biomeSearchPipelineLayout, stack, "multi-noise biome pipeline");
            biomeSearchDescriptorPool = biomeSearchDescriptorPool(stack);
            biomeSearchDescriptorSet = biomeSearchDescriptorSet(stack);
            biomeSearchDescriptors(stack);
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
            if (!batch.addAll(data, xs, ys, zs)) throw new IllegalArgumentException("invalid GPU sample batch");
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
            writeGraphCoordinates(xs, ys, zs, xs.length);
            if (graphResourcesGraph != graph || graphResourcesPacked != packed) {
                put(graphNodes, packed.nodes(), stack);
                put(graphSplinePoints, packed.splinePoints(), stack);
                put(graphBlendedMetadata, packed.blendedMetadata(), stack);
            }
            uploadGraphRoots(new int[]{resultNode}, stack);
            if (graphResourcesGraph != graph || graphResourcesPacked != packed) {
                put(permutations, packed.permutations(), stack); put(octaves, packed.octaves(), stack); put(samplerMetadata, packed.samplerMetadata(), stack);
                graphResourcesGraph = graph; graphResourcesPacked = packed;
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
        int rootCount = graph.routerRootCount();
        if (packed.nodeCount() > GRAPH_NODE_CAPACITY || rootCount > 16) throw new IllegalArgumentException("density graph exceeds GPU capacity");
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ensureGraphVariant(graph, stack);
            writeGraphCoordinates(xs, ys, zs, sampleCount);
            if (graphResourcesGraph != graph || graphResourcesPacked != packed) {
                put(graphNodes, packed.nodes(), stack);
                put(graphSplinePoints, packed.splinePoints(), stack); put(graphBlendedMetadata, packed.blendedMetadata(), stack);
            }
            uploadGraphRoots(graph.routerRoots(), stack);
            if (graphResourcesGraph != graph || graphResourcesPacked != packed) {
                put(permutations, packed.permutations(), stack); put(octaves, packed.octaves(), stack); put(samplerMetadata, packed.samplerMetadata(), stack);
                graphResourcesGraph = graph; graphResourcesPacked = packed;
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
        int rootCount = graph.routerRootCount();
        if (packed.nodeCount() > GRAPH_NODE_CAPACITY || rootCount > 16) throw new IllegalArgumentException("density graph exceeds GPU capacity");
        long preparationStarted = GPU_TIMING_ENABLED ? System.nanoTime() : 0L;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ensureGraphVariant(graph, stack);
            writeGraphCoordinates(xs, ys, zs, sampleCount);
            if (graphResourcesGraph != graph || graphResourcesPacked != packed) {
                put(graphNodes, packed.nodes(), stack); put(graphSplinePoints, packed.splinePoints(), stack);
                put(graphBlendedMetadata, packed.blendedMetadata(), stack);
                put(permutations, packed.permutations(), stack); put(octaves, packed.octaves(), stack);
                put(samplerMetadata, packed.samplerMetadata(), stack); graphResourcesGraph = graph; graphResourcesPacked = packed;
            }
            uploadGraphRoots(graph.routerRoots(), stack);
            check(vkResetCommandBuffer(commandBuffer, 0), "reset async graph-roots command buffer");
            check(vkBeginCommandBuffer(commandBuffer, VkCommandBufferBeginInfo.calloc(stack).sType$Default()), "begin async graph-roots command buffer");
            vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, graphPipeline);
            vkCmdBindDescriptorSets(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, graphPipelineLayout, 0, stack.longs(graphDescriptorSet), null);
            ByteBuffer push = stack.malloc(16).order(ByteOrder.nativeOrder()).putInt(sampleCount).putInt(packed.nodeCount()).putInt(rootCount).putInt(0).flip();
            vkCmdPushConstants(commandBuffer, graphPipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, push);
            beginGpuTimestamp();
            vkCmdDispatch(commandBuffer, (sampleCount + 63) / 64, 1, 1);
            VkBufferMemoryBarrier.Buffer barrier = VkBufferMemoryBarrier.calloc(1, stack).sType$Default()
                    .srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT).dstAccessMask(VK_ACCESS_HOST_READ_BIT)
                    .buffer(results.buffer).size((long) sampleCount * rootCount * Float.BYTES);
            vkCmdPipelineBarrier(commandBuffer, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_HOST_BIT, 0, null, barrier, null);
            endGpuTimestamp();
            check(vkEndCommandBuffer(commandBuffer), "end async graph-roots command buffer");
            synchronized (queueSubmitLock) {
                check(vkQueueSubmit(queue, VkSubmitInfo.calloc(stack).sType$Default()
                        .pCommandBuffers(stack.pointers(commandBuffer.address())), fence), "submit async graph roots");
            }
            inFlightFenceSubmittedNanos = GPU_TIMING_ENABLED ? System.nanoTime() : 0L;
            inFlightGraphPreparationNanos = GPU_TIMING_ENABLED
                    ? inFlightFenceSubmittedNanos - preparationStarted : 0L;
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
        int rootCount = graph.routerRootCount();
        int resultStride = rootCount + graph.interpolatedMarkerCount();
        if (packed.nodeCount() > GRAPH_NODE_CAPACITY || rootCount > 16) throw new IllegalArgumentException("density graph exceeds GPU capacity");
        long preparationStarted = GPU_TIMING_ENABLED ? System.nanoTime() : 0L;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ensureGraphVariant(graph, stack);
            put(graphCoordinates, metadata, stack);
            uploadInterpolatorMap(graph, stack);
            if (graphResourcesGraph != graph || graphResourcesPacked != packed) {
                put(graphNodes, packed.nodes(), stack); put(graphSplinePoints, packed.splinePoints(), stack);
                put(graphBlendedMetadata, packed.blendedMetadata(), stack);
                put(permutations, packed.permutations(), stack); put(octaves, packed.octaves(), stack);
                put(samplerMetadata, packed.samplerMetadata(), stack); graphResourcesGraph = graph; graphResourcesPacked = packed;
            }
            uploadGraphRoots(graph.routerRoots(), stack);
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
                    .buffer(results.buffer).size((long) sampleCount * resultStride * Float.BYTES);
            vkCmdPipelineBarrier(commandBuffer, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_HOST_BIT, 0, null, barrier, null);
            check(vkEndCommandBuffer(commandBuffer), "end grid command buffer");
            synchronized (queueSubmitLock) {
                check(vkQueueSubmit(queue, VkSubmitInfo.calloc(stack).sType$Default()
                        .pCommandBuffers(stack.pointers(commandBuffer.address())), fence), "submit grid roots");
            }
            inFlightFenceSubmittedNanos = GPU_TIMING_ENABLED ? System.nanoTime() : 0L;
            inFlightGraphPreparationNanos = GPU_TIMING_ENABLED
                    ? inFlightFenceSubmittedNanos - preparationStarted : 0L;
            inFlightSamples = sampleCount; inFlightRoots = resultStride;
            inFlightGraphRoots = new CompletableFuture<>();
            return inFlightGraphRoots;
        }
    }

    synchronized void completeSignaledWork() {
        CompletableFuture<float[]> future = inFlightGraphRoots;
        CompletableFuture<GpuTerrainBatchResult> terrainFuture = inFlightTerrain;
        CompletableFuture<GpuAquiferPrefillResult> aquiferFuture = inFlightAquiferPrefill;
        CompletableFuture<int[]> aquiferRowsFuture = inFlightAquiferRows;
        if (future == null && terrainFuture == null && aquiferFuture == null && aquiferRowsFuture == null) return;
        int status = vkGetFenceStatus(device, fence);
        if (status == VK_NOT_READY) return;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            check(status, "poll terrain fence");
            long fenceDuration = GPU_TIMING_ENABLED
                    ? System.nanoTime() - inFlightFenceSubmittedNanos : 0L;
            long readbackStarted = GPU_TIMING_ENABLED ? System.nanoTime() : 0L;
            if (aquiferRowsFuture != null) {
                IntBuffer values = persistentMappings.get(aquiferNearestOutput)
                        .duplicate().order(ByteOrder.nativeOrder()).asIntBuffer();
                values.clear();
                int[] output;
                if (inFlightAquiferRowStage == AquiferRowStage.SUBSTANCE) {
                    int words = GpuAquiferSubstance.shaderOutputWordCount(inFlightAquiferRowCount);
                    int[] packed = new int[words];
                    values.limit(words);
                    values.get(packed);
                    output = unpackAquiferSubstanceRows(packed, inFlightAquiferRowCount);
                } else {
                    output = new int[Math.multiplyExact(inFlightAquiferRowCount,
                            inFlightAquiferRowStage.outputInts)];
                    values.limit(output.length);
                    values.get(output);
                }
                check(vkResetFences(device, fence), "reset async aquifer decision fence");
                inFlightAquiferRows = null;
                inFlightAquiferRowStage = null;
                inFlightAquiferRowCount = 0;
                aquiferRowsFuture.complete(output);
            } else if (aquiferFuture != null) {
                IntBuffer values = persistentMappings.get(aquiferNearestOutput)
                        .duplicate().order(ByteOrder.nativeOrder()).asIntBuffer();
                values.clear();
                int nearestInts = Math.multiplyExact(inFlightAquiferNearestCount,
                        GpuAquiferNearestThree.OUTPUT_INTS);
                int statusInts = Math.multiplyExact(inFlightAquiferStatusCount,
                        GpuAquiferFluidStatus.OUTPUT_INTS);
                int[] nearestRows = new int[nearestInts];
                int[] statusRows = new int[statusInts];
                int decisionInts = inFlightAquiferDecisionChain
                        ? Math.multiplyExact(inFlightAquiferNearestCount, 3) : 0;
                int[] decisionRows = new int[decisionInts];
                values.limit(nearestInts);
                values.get(nearestRows);
                values.position(nearestInts).limit(Math.addExact(nearestInts, statusInts));
                values.get(statusRows);
                if (decisionInts > 0) {
                    values.position(nearestInts + statusInts)
                            .limit(nearestInts + statusInts + decisionInts);
                    values.get(decisionRows);
                }
                check(vkResetFences(device, fence), "reset async combined aquifer fence");
                inFlightAquiferPrefill = null;
                inFlightAquiferDecisionChain = false;
                aquiferFuture.complete(new GpuAquiferPrefillResult(nearestRows, statusRows, decisionRows));
            } else if (terrainFuture != null) {
                long[] stageGpuNanos = GPU_TIMING_ENABLED ? readTerrainGpuTimestamps(stack) : null;
                float[] corners = readRootResults(inFlightSamples, inFlightTerrainCornerRoots,
                        inFlightTerrainCornerStride, stack);
                FloatBuffer blockValues = persistentMappings.get(interpolationOutput).duplicate()
                        .order(ByteOrder.nativeOrder()).asFloatBuffer();
                blockValues.clear();
                blockValues.limit(inFlightTerrainBlockCount * inFlightTerrainOutputRoots);
                float[] blocks = new float[inFlightTerrainBlockCount * inFlightTerrainOutputRoots];
                blockValues.get(blocks);
                if (GPU_TIMING_ENABLED) recordTerrainBatchDuration(inFlightTerrainPreparationNanos,
                        fenceDuration, stageGpuNanos, System.nanoTime() - readbackStarted);
                GpuTerrainBatchResult result = new GpuTerrainBatchResult(
                        new GpuTerrainRootsResult(corners, 0, corners.length, inFlightTerrainCornerRoots),
                        new GpuTerrainBlockResult(blocks, 0, inFlightTerrainBlockCount,
                                inFlightTerrainRootIndexes, 16, inFlightTerrainBlocksPerChunk / 256, 16));
                check(vkResetFences(device, fence), "reset async terrain fence");
                inFlightTerrain = null;
                terrainFuture.complete(result);
            } else {
                long gpuCommandNanos = GPU_TIMING_ENABLED ? readGpuTimestamp(stack) : -1L;
                float[] output = readResults(inFlightSamples, inFlightRoots, stack);
                if (GPU_TIMING_ENABLED) recordGraphFenceDuration(inFlightGraphPreparationNanos,
                        fenceDuration, gpuCommandNanos, System.nanoTime() - readbackStarted);
                check(vkResetFences(device, fence), "reset async graph-roots fence");
                inFlightGraphRoots = null;
                future.complete(output);
            }
        } catch (Throwable failure) {
            inFlightGraphRoots = null;
            inFlightTerrain = null;
            inFlightAquiferPrefill = null;
            inFlightAquiferRows = null;
            inFlightAquiferRowStage = null;
            inFlightAquiferRowCount = 0;
            if (future != null) future.completeExceptionally(failure);
            if (terrainFuture != null) terrainFuture.completeExceptionally(failure);
            if (aquiferFuture != null) aquiferFuture.completeExceptionally(failure);
            if (aquiferRowsFuture != null) aquiferRowsFuture.completeExceptionally(failure);
        }
    }

    /**
     * Tests completion without copying mapped output. The ring owns the
     * transition from fence polling to its bounded readback workers, so this
     * method must stay short and must not reset the fence.
     */
    synchronized boolean fenceReadyForReadback() {
        if (inFlightGraphRoots == null && inFlightTerrain == null
                && inFlightAquiferPrefill == null && inFlightAquiferRows == null) return false;
        return vkGetFenceStatus(device, fence) != VK_NOT_READY;
    }

    private void recordGraphFenceDuration(long preparationNanos, long fenceNanos,
                                          long gpuCommandNanos, long readbackNanos) {
        totalGraphPreparationNanos += preparationNanos;
        totalGraphFenceNanos += fenceNanos;
        totalGraphReadbackNanos += readbackNanos;
        if (gpuCommandNanos >= 0L) {
            totalGraphGpuCommandNanos += gpuCommandNanos;
            graphGpuTimestampSamples++;
        }
        long completed = ++completedGraphFenceSamples;
        if ((completed & 7L) == 0L) {
            double averageGpuCommandMs = graphGpuTimestampSamples == 0 ? -1.0
                    : totalGraphGpuCommandNanos / (double) graphGpuTimestampSamples / 1_000_000.0;
            GpuNoise.LOGGER.info("GPU terrain graph profile: completedDispatches={}, avgCpuPrepareMs={}, avgSubmitToFenceMs={}, avgReadbackMs={}, avgGpuCommandMs={}, gpuTimingSamples={}",
                    completed, totalGraphPreparationNanos / (double) completed / 1_000_000.0,
                    totalGraphFenceNanos / (double) completed / 1_000_000.0,
                    totalGraphReadbackNanos / (double) completed / 1_000_000.0,
                    averageGpuCommandMs, graphGpuTimestampSamples);
        }
    }

    private void recordTerrainBatchDuration(long preparationNanos, long fenceNanos,
                                            long[] stageGpuNanos, long readbackNanos) {
        totalTerrainPreparationNanos += preparationNanos;
        totalTerrainFenceNanos += fenceNanos;
        totalTerrainReadbackNanos += readbackNanos;
        if (stageGpuNanos.length == 3 && stageGpuNanos[0] >= 0L
                && stageGpuNanos[1] >= 0L && stageGpuNanos[2] >= 0L) {
            totalTerrainGridGpuNanos += stageGpuNanos[0];
            totalTerrainInterpolationGpuNanos += stageGpuNanos[1];
            totalTerrainBlockGpuNanos += stageGpuNanos[2];
            terrainGpuTimestampSamples++;
        }
        long completed = ++completedTerrainBatchSamples;
        if ((completed & 7L) == 0L) {
            double averageGridGpuMs = terrainGpuTimestampSamples == 0 ? -1.0
                    : totalTerrainGridGpuNanos / (double) terrainGpuTimestampSamples / 1_000_000.0;
            double averageInterpolationGpuMs = terrainGpuTimestampSamples == 0 ? -1.0
                    : totalTerrainInterpolationGpuNanos / (double) terrainGpuTimestampSamples / 1_000_000.0;
            double averageBlockGpuMs = terrainGpuTimestampSamples == 0 ? -1.0
                    : totalTerrainBlockGpuNanos / (double) terrainGpuTimestampSamples / 1_000_000.0;
            GpuNoise.LOGGER.info("GPU full terrain profile: batches={}, avgCpuPrepareMs={}, avgSubmitToFenceMs={}, avgReadbackMs={}, avgGridGpuMs={}, avgInterpolationGpuMs={}, avgBlockGpuMs={}, gpuTimingSamples={}",
                    completed, totalTerrainPreparationNanos / (double) completed / 1_000_000.0,
                    totalTerrainFenceNanos / (double) completed / 1_000_000.0,
                    totalTerrainReadbackNanos / (double) completed / 1_000_000.0,
                    averageGridGpuMs, averageInterpolationGpuMs, averageBlockGpuMs,
                    terrainGpuTimestampSamples);
        }
    }

    synchronized boolean graphRootsInFlight() { return inFlightGraphRoots != null; }
    synchronized boolean hasInFlightWork() {
        return inFlightGraphRoots != null || inFlightTerrain != null
                || inFlightAquiferPrefill != null || inFlightAquiferRows != null;
    }

    private float[] submit(GpuGlobalNoiseBatch.Packed packed, MemoryStack stack) throws Exception {
            graphResourcesGraph = null; graphResourcesPacked = null;
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
    synchronized CompletableFuture<GpuTerrainBatchResult> submitGraphGridInterpolated(ByteBuffer gridMetadata, int chunkCount,
                                                                                         int samplesPerChunk, ByteBuffer outputMetadata,
                                                                                         int blocksPerChunk) throws Exception {
        return submitGraphGridInterpolated(GpuTerrainRuntime.currentTerrainGraph(), gridMetadata,
                chunkCount, samplesPerChunk, outputMetadata, blocksPerChunk);
    }

    synchronized CompletableFuture<GpuTerrainBatchResult> submitGraphGridInterpolated(GpuDensityGraph graph,
                                                                                         ByteBuffer gridMetadata, int chunkCount,
                                                                                         int samplesPerChunk, ByteBuffer outputMetadata,
                                                                                         int blocksPerChunk) throws Exception {
        int[] outputRoots = new int[graph.routerRootCount()];
        for (int i = 0; i < outputRoots.length; i++) outputRoots[i] = i;
        return submitGraphGridInterpolated(graph, gridMetadata, chunkCount, samplesPerChunk,
                outputMetadata, blocksPerChunk, outputRoots);
    }

    synchronized CompletableFuture<GpuTerrainBatchResult> submitGraphGridInterpolated(GpuDensityGraph graph,
                                                                                         ByteBuffer gridMetadata, int chunkCount,
                                                                                         int samplesPerChunk, ByteBuffer outputMetadata,
                                                                                         int blocksPerChunk,
                                                                                         int[] outputRootIndexes) throws Exception {
        if (hasInFlightWork()) throw new IllegalStateException("Vulkan slot is still in flight");
        if (chunkCount <= 0 || samplesPerChunk <= 0 || blocksPerChunk <= 0
                || (long) chunkCount * samplesPerChunk > CAPACITY
                || gridMetadata.remaining() < chunkCount * 32L
                || outputMetadata.remaining() < chunkCount * GpuInterpolationBatch.METADATA_BYTES)
            throw new IllegalArgumentException("invalid grid interpolation batch");
        GpuInterpolationBatch.validateForDispatch(outputMetadata, chunkCount,
                samplesPerChunk, blocksPerChunk);
        if (graph == null) throw new IllegalArgumentException("terrain graph is not installed");
        GpuDensityGraphBuffer.Packed packed = packedGraph(graph, outputRootIndexes);
        int rootCount = graph.routerRootCount();
        int outputRoots = outputRootIndexes.length;
        if (outputRoots == 0) throw new IllegalArgumentException("grid interpolation needs at least one block output root");
        int[] allBlockRootRegisters = packed.blockRootRegisters();
        int[] blockRootRegisters = new int[outputRoots];
        for (int i = 0; i < outputRoots; i++) {
            int root = outputRootIndexes[i];
            if (root < 0 || root >= allBlockRootRegisters.length)
                throw new IllegalArgumentException("invalid terrain block output root " + root);
            blockRootRegisters[i] = allBlockRootRegisters[root];
        }
        int markerCount = graph.interpolatedMarkerCount();
        int cornerStride = rootCount + markerCount;
        int sampleCount = Math.multiplyExact(chunkCount, samplesPerChunk);
        int blockCount = Math.multiplyExact(chunkCount, blocksPerChunk);
        if (packed.nodeCount() > GRAPH_NODE_CAPACITY || rootCount > 16 || outputRoots > rootCount
                || markerCount > MAX_INTERPOLATORS || graph.registerCount() > 256
                || (long) sampleCount * cornerStride * Float.BYTES > results.size
                || (long) blockCount * markerCount > INTERPOLATED_INPUT_FLOATS
                || (long) blockCount * outputRoots * Float.BYTES > interpolationOutput.size)
            throw new IllegalArgumentException("grid interpolation batch exceeds GPU capacity");
        long preparationStarted = GPU_TIMING_ENABLED ? System.nanoTime() : 0L;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ensureGraphVariant(graph, stack);
            ensureBlockGraphVariant(graph, packed, stack);
            ByteBuffer grids = gridMetadata.duplicate().order(ByteOrder.nativeOrder());
            grids.limit(grids.position() + chunkCount * 32);
            put(graphCoordinates, grids, stack);
            if (graphResourcesGraph != graph || graphResourcesPacked != packed) {
                put(graphNodes, packed.nodes(), stack); put(graphSplinePoints, packed.splinePoints(), stack);
                put(graphBlendedMetadata, packed.blendedMetadata(), stack);
                put(permutations, packed.permutations(), stack); put(octaves, packed.octaves(), stack);
                put(samplerMetadata, packed.samplerMetadata(), stack); graphResourcesGraph = graph; graphResourcesPacked = packed;
            }
            uploadInterpolatorMap(graph, stack);
            uploadGraphRoots(graph.routerRoots(), blockRootRegisters, stack);
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
            beginTerrainGpuTimestamps();
            vkCmdDispatch(commandBuffer, (sampleCount + 63) / 64, 1, 1);
            writeGpuTimestamp(1, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT);
            VkBufferMemoryBarrier.Buffer graphBarrier = VkBufferMemoryBarrier.calloc(1, stack).sType$Default()
                    .srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT).dstAccessMask(VK_ACCESS_SHADER_READ_BIT)
                    .buffer(results.buffer).size((long) sampleCount * cornerStride * Float.BYTES);
            vkCmdPipelineBarrier(commandBuffer, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    0, null, graphBarrier, null);
            vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, interpolationPipeline);
            vkCmdBindDescriptorSets(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, interpolationPipelineLayout, 0,
                    stack.longs(interpolationDescriptorSet), null);
            ByteBuffer interpolationPush = stack.malloc(16).order(ByteOrder.nativeOrder())
                    .putInt(chunkCount).putInt(blocksPerChunk).putInt(cornerStride)
                    .putInt(markerCount | 0x8000_0000).flip();
            vkCmdPushConstants(commandBuffer, interpolationPipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, interpolationPush);
            writeGpuTimestamp(2, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
            vkCmdDispatch(commandBuffer, (blockCount + 63) / 64, 1, 1);
            writeGpuTimestamp(3, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT);
            VkBufferMemoryBarrier.Buffer interpolationBarrier = VkBufferMemoryBarrier.calloc(2, stack).sType$Default();
            interpolationBarrier.get(0).srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT).dstAccessMask(VK_ACCESS_SHADER_READ_BIT)
                    .buffer(interpolatedInputs.buffer).size(Math.max(4L, (long) blockCount * markerCount * Float.BYTES));
            interpolationBarrier.get(1).srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_SHADER_READ_BIT)
                    .dstAccessMask(VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT)
                    .buffer(graphCoordinates.buffer).size((long) blockCount * 16L);
            vkCmdPipelineBarrier(commandBuffer, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, null, interpolationBarrier, null);
            vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, blockGraphPipeline);
            vkCmdBindDescriptorSets(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, graphPipelineLayout, 0,
                    stack.longs(graphDescriptorSet), null);
            ByteBuffer blockPush = stack.malloc(16).order(ByteOrder.nativeOrder())
                    .putInt(blockCount).putInt(packed.blockNodeCount())
                    .putInt((packed.blockNodeOffset() << 5) | outputRoots)
                    .putInt(0x8000_0000 | markerCount).flip();
            vkCmdPushConstants(commandBuffer, graphPipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, blockPush);
            writeGpuTimestamp(4, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
            vkCmdDispatch(commandBuffer, (blockCount + 63) / 64, 1, 1);
            writeGpuTimestamp(5, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT);
            VkBufferMemoryBarrier.Buffer resultBarrier = VkBufferMemoryBarrier.calloc(1, stack).sType$Default()
                    .srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT).dstAccessMask(VK_ACCESS_HOST_READ_BIT)
                    .buffer(results.buffer).size((long) sampleCount * cornerStride * Float.BYTES);
            VkBufferMemoryBarrier.Buffer outputBarrier = VkBufferMemoryBarrier.calloc(1, stack).sType$Default()
                    .srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT).dstAccessMask(VK_ACCESS_HOST_READ_BIT)
                    .buffer(interpolationOutput.buffer).size((long) blockCount * outputRoots * Float.BYTES);
            VkBufferMemoryBarrier.Buffer coordinateBarrier = VkBufferMemoryBarrier.calloc(1, stack).sType$Default()
                    .srcAccessMask(VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT)
                    .dstAccessMask(VK_ACCESS_HOST_WRITE_BIT).buffer(graphCoordinates.buffer)
                    .size((long) blockCount * 16L);
            vkCmdPipelineBarrier(commandBuffer, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_HOST_BIT,
                    0, null, resultBarrier, null);
            VkBufferMemoryBarrier.Buffer finalBarriers = VkBufferMemoryBarrier.calloc(2, stack).sType$Default();
            finalBarriers.get(0).set(outputBarrier.get(0));
            finalBarriers.get(1).set(coordinateBarrier.get(0));
            vkCmdPipelineBarrier(commandBuffer, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_HOST_BIT,
                    0, null, finalBarriers, null);
            check(vkEndCommandBuffer(commandBuffer), "end grid interpolation command buffer");
            synchronized (queueSubmitLock) {
                check(vkQueueSubmit(queue, VkSubmitInfo.calloc(stack).sType$Default()
                        .pCommandBuffers(stack.pointers(commandBuffer.address())), fence), "submit grid interpolation");
            }
            inFlightFenceSubmittedNanos = GPU_TIMING_ENABLED ? System.nanoTime() : 0L;
            inFlightTerrainPreparationNanos = GPU_TIMING_ENABLED
                    ? inFlightFenceSubmittedNanos - preparationStarted : 0L;
            inFlightSamples = sampleCount;
            inFlightRoots = rootCount;
            inFlightTerrainBlockCount = blockCount;
            inFlightTerrainOutputRoots = outputRoots;
            inFlightTerrainBlocksPerChunk = blocksPerChunk;
            inFlightTerrainCornerRoots = rootCount;
            inFlightTerrainCornerStride = cornerStride;
            inFlightTerrainRootIndexes = outputRootIndexes.clone();
            inFlightTerrain = new CompletableFuture<>();
            return inFlightTerrain;
        }
    }

    synchronized GpuFusedResult submitGraphInterpolated(GpuDensityGraph graph, float[] xs, float[] ys, float[] zs,
                                                   int sampleCount, ByteBuffer metadata, int chunkCount, int blocksPerChunk) throws Exception {
        if (sampleCount < 0 || sampleCount > xs.length || sampleCount > ys.length || sampleCount > zs.length
                || sampleCount > CAPACITY)
            throw new IllegalArgumentException("invalid fused graph batch");
        GpuDensityGraphBuffer.Packed packed = packedGraph(graph);
        int rootCount = graph.routerRootCount();
        int blockCount = Math.multiplyExact(chunkCount, blocksPerChunk);
        int outputRoots = GpuTerrainRuntime.terrainOutputRootCount();
        if (packed.nodeCount() > GRAPH_NODE_CAPACITY || rootCount > 16 || outputRoots > rootCount
                || metadata.remaining() > interpolationMetadata.size
                || blockCount * outputRoots * 4L > interpolationOutput.size) throw new IllegalArgumentException("fused batch exceeds GPU capacity");
        try (MemoryStack stack = MemoryStack.stackPush()) {
            writeGraphCoordinates(xs, ys, zs, sampleCount);
            if (graphResourcesGraph != graph || graphResourcesPacked != packed) {
                put(graphNodes, packed.nodes(), stack); put(graphSplinePoints, packed.splinePoints(), stack);
                put(graphBlendedMetadata, packed.blendedMetadata(), stack);
                put(permutations, packed.permutations(), stack); put(octaves, packed.octaves(), stack);
                put(samplerMetadata, packed.samplerMetadata(), stack); graphResourcesGraph = graph; graphResourcesPacked = packed;
            }
            uploadGraphRoots(graph.routerRoots(), stack); put(interpolationMetadata, metadata, stack);
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
            // Evaluate graph roots once and materialize the compact per-block
            // terrain and material roots selected by the caller.
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
        float[] output = new float[samples * valuesPerSample];
        ByteBuffer view = persistentResults.duplicate().order(ByteOrder.nativeOrder());
        view.clear();
        view.limit(output.length * Float.BYTES);
        view.asFloatBuffer().get(output);
        return output;
    }

    /** Copies router roots to Java while leaving GPU-only marker corners out of the batch result. */
    private float[] readRootResults(int samples, int roots, int sourceStride, MemoryStack stack) {
        float[] output = new float[Math.multiplyExact(samples, roots)];
        FloatBuffer source = persistentResults.duplicate().order(ByteOrder.nativeOrder()).asFloatBuffer();
        source.clear();
        source.limit(Math.multiplyExact(samples, sourceStride));
        for (int sample = 0; sample < samples; sample++) {
            source.get(output, sample * roots, roots);
            source.position(source.position() + sourceStride - roots);
        }
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
            vkCmdDispatch(commandBuffer, (count + 63) / 64, 1, 1);
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
    synchronized CompletableFuture<int[]> submitAquiferNearestThreeAsync(ByteBuffer records, int count)
            throws Exception {
        return submitAquiferRowsAsync(records, count, AquiferRowStage.NEAREST);
    }

    synchronized CompletableFuture<int[]> submitAquiferSubstanceAsync(ByteBuffer records, int count)
            throws Exception {
        return submitAquiferRowsAsync(records, count, AquiferRowStage.SUBSTANCE);
    }

    synchronized CompletableFuture<int[]> submitAquiferFluidStatusAsync(ByteBuffer records, int count)
            throws Exception {
        return submitAquiferRowsAsync(records, count, AquiferRowStage.FLUID_STATUS);
    }

    private synchronized CompletableFuture<int[]> submitAquiferRowsAsync(
            ByteBuffer records, int count, AquiferRowStage stage) throws Exception {
        if (hasInFlightWork()) throw new IllegalStateException("Vulkan slot is still in flight");
        boolean valid = switch (stage) {
            case NEAREST -> GpuAquiferNearestThree.isValidPacked(records, count);
            case SUBSTANCE -> count > 0 && count <= GpuAquiferSubstance.MAX_QUERIES
                    && records.remaining() == count * GpuAquiferSubstance.RECORD_BYTES;
            case FLUID_STATUS -> count > 0 && count <= GpuAquiferFluidStatus.MAX_QUERIES
                    && records.remaining() == count * GpuAquiferFluidStatus.RECORD_BYTES;
        };
        if (!valid) throw new IllegalArgumentException("invalid aquifer " + stage + " batch");
        try (MemoryStack stack = MemoryStack.stackPush()) {
            put(aquiferNearestInputs, records, stack);
            check(vkResetCommandBuffer(commandBuffer, 0), "reset async aquifer " + stage + " command buffer");
            check(vkBeginCommandBuffer(commandBuffer, VkCommandBufferBeginInfo.calloc(stack).sType$Default()),
                    "begin async aquifer " + stage + " command buffer");
            long pipeline = switch (stage) {
                case NEAREST -> aquiferNearestPipeline;
                case SUBSTANCE -> aquiferSubstancePipeline;
                case FLUID_STATUS -> aquiferFluidStatusPipeline;
            };
            long pipelineLayout = switch (stage) {
                case NEAREST -> aquiferNearestPipelineLayout;
                case SUBSTANCE -> aquiferSubstancePipelineLayout;
                case FLUID_STATUS -> aquiferFluidStatusPipelineLayout;
            };
            vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
            vkCmdBindDescriptorSets(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE,
                    pipelineLayout, 0, stack.longs(aquiferNearestDescriptorSet), null);
            ByteBuffer push = stage == AquiferRowStage.FLUID_STATUS
                    ? stack.malloc(16).order(ByteOrder.nativeOrder()).putInt(count).putInt(0).putInt(0).putInt(0).flip()
                    : stack.malloc(4).order(ByteOrder.nativeOrder()).putInt(count).flip();
            vkCmdPushConstants(commandBuffer, pipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, push);
            int invocationsPerWorkgroup = stage == AquiferRowStage.SUBSTANCE ? 128 : 64;
            vkCmdDispatch(commandBuffer, Math.max(1, (count + invocationsPerWorkgroup - 1)
                    / invocationsPerWorkgroup), 1, 1);
            long outputBytes = stage == AquiferRowStage.SUBSTANCE
                    ? (long) GpuAquiferSubstance.shaderOutputWordCount(count) * Integer.BYTES
                    : (long) count * stage.outputInts * Integer.BYTES;
            VkBufferMemoryBarrier.Buffer barrier = VkBufferMemoryBarrier.calloc(1, stack).sType$Default()
                    .srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT).dstAccessMask(VK_ACCESS_HOST_READ_BIT)
                    .buffer(aquiferNearestOutput.buffer)
                    .size(outputBytes);
            vkCmdPipelineBarrier(commandBuffer, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK_PIPELINE_STAGE_HOST_BIT, 0, null, barrier, null);
            check(vkEndCommandBuffer(commandBuffer), "end async aquifer " + stage + " command buffer");
            synchronized (queueSubmitLock) {
                check(vkQueueSubmit(queue, VkSubmitInfo.calloc(stack).sType$Default()
                        .pCommandBuffers(stack.pointers(commandBuffer.address())), fence),
                        "submit async aquifer " + stage);
            }
        }
        inFlightAquiferRowCount = count;
        inFlightAquiferRowStage = stage;
        inFlightAquiferRows = new CompletableFuture<>();
        return inFlightAquiferRows;
    }

    /** Selects aquifer centers with Minecraft's exact integer tie ordering. */
    synchronized int[] submitAquiferNearestThree(ByteBuffer records, int count) throws Exception {
        if (hasInFlightWork()) throw new IllegalStateException("Vulkan slot is still in flight");
        if (!GpuAquiferNearestThree.isValidPacked(records, count))
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
            vkCmdDispatch(commandBuffer, (count + 63) / 64, 1, 1);
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
        if (hasInFlightWork()) throw new IllegalStateException("Vulkan slot is still in flight");
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
            vkCmdDispatch(commandBuffer, (count + 127) / 128, 1, 1);
            int outputWords = GpuAquiferSubstance.shaderOutputWordCount(count);
            VkBufferMemoryBarrier.Buffer barrier = VkBufferMemoryBarrier.calloc(1, stack).sType$Default()
                    .srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT).dstAccessMask(VK_ACCESS_HOST_READ_BIT)
                    .buffer(aquiferNearestOutput.buffer)
                    .size((long) outputWords * Integer.BYTES);
            vkCmdPipelineBarrier(commandBuffer, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK_PIPELINE_STAGE_HOST_BIT, 0, null, barrier, null);
            check(vkEndCommandBuffer(commandBuffer), "end aquifer substance command buffer");
            synchronized (queueSubmitLock) {
                check(vkQueueSubmit(queue, VkSubmitInfo.calloc(stack).sType$Default()
                        .pCommandBuffers(stack.pointers(commandBuffer.address())), fence), "submit aquifer substance");
            }
            check(vkWaitForFences(device, fence, true, Long.MAX_VALUE), "wait aquifer substance");
            check(vkResetFences(device, fence), "reset aquifer substance fence");
            IntBuffer values = persistentMappings.get(aquiferNearestOutput)
                    .duplicate().order(ByteOrder.nativeOrder()).asIntBuffer();
            values.clear();
            values.limit(outputWords);
            int[] packed = new int[outputWords];
            values.get(packed);
            return unpackAquiferSubstanceRows(packed, count);
        }
    }

    /** Runs OCL's per-block OreVeinifier classification on Vulkan. */
    synchronized int[] submitOreVeinDecisions(ByteBuffer records, int count) throws Exception {
        if (hasInFlightWork()) throw new IllegalStateException("Vulkan slot is still in flight");
        if (count <= 0 || count > CAPACITY
                || records.remaining() != count * GpuOreVeinDecisions.RECORD_BYTES)
            throw new IllegalArgumentException("invalid ore-vein decision batch");
        try (MemoryStack stack = MemoryStack.stackPush()) {
            put(aquiferNearestInputs, records, stack);
            check(vkResetCommandBuffer(commandBuffer, 0), "reset ore-vein command buffer");
            check(vkBeginCommandBuffer(commandBuffer, VkCommandBufferBeginInfo.calloc(stack).sType$Default()),
                    "begin ore-vein command buffer");
            vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, oreVeinPipeline);
            vkCmdBindDescriptorSets(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE,
                    oreVeinPipelineLayout, 0, stack.longs(aquiferNearestDescriptorSet), null);
            ByteBuffer push = stack.malloc(4).order(ByteOrder.nativeOrder()).putInt(count).flip();
            vkCmdPushConstants(commandBuffer, oreVeinPipelineLayout,
                    VK_SHADER_STAGE_COMPUTE_BIT, 0, push);
            vkCmdDispatch(commandBuffer, (count + 63) / 64, 1, 1);
            VkBufferMemoryBarrier.Buffer barrier = VkBufferMemoryBarrier.calloc(1, stack).sType$Default()
                    .srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT).dstAccessMask(VK_ACCESS_HOST_READ_BIT)
                    .buffer(aquiferNearestOutput.buffer).size((long) count * Integer.BYTES);
            vkCmdPipelineBarrier(commandBuffer, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK_PIPELINE_STAGE_HOST_BIT, 0, null, barrier, null);
            check(vkEndCommandBuffer(commandBuffer), "end ore-vein command buffer");
            synchronized (queueSubmitLock) {
                check(vkQueueSubmit(queue, VkSubmitInfo.calloc(stack).sType$Default()
                        .pCommandBuffers(stack.pointers(commandBuffer.address())), fence), "submit ore-vein decisions");
            }
            check(vkWaitForFences(device, fence, true, Long.MAX_VALUE), "wait ore-vein decisions");
            check(vkResetFences(device, fence), "reset ore-vein decision fence");
            IntBuffer values = persistentMappings.get(aquiferNearestOutput)
                    .duplicate().order(ByteOrder.nativeOrder()).asIntBuffer();
            values.clear();
            values.limit(count);
            int[] output = new int[count];
            values.get(output);
            return output;
        }
    }

    /** Runs OCL's compact multi-noise tree search for a packed terrain region. */
    synchronized int[] submitBiomeSearch(ByteBuffer targets, GpuBiomeSearchTree tree, int count) throws Exception {
        if (hasInFlightWork()) throw new IllegalStateException("Vulkan slot is still in flight");
        if (tree == null) throw new IllegalArgumentException("multi-noise biome tree is required");
        ByteBuffer nodes = tree.packedNodes();
        int nodeCount = tree.nodeCount();
        int maxDepth = tree.maxDepth();
        if (count <= 0 || count > CAPACITY || targets.remaining() != (long) count * 16L
                || nodes.remaining() > BIOME_TREE_BUFFER_BYTES
                || nodeCount < 2 || nodeCount * (long) GpuBiomeSearchTree.NODE_BYTES > nodes.remaining()
                || maxDepth < 1 || maxDepth >= 64) {
            throw new IllegalArgumentException("invalid multi-noise biome batch");
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            put(aquiferNearestInputs, targets, stack);
            if (uploadedBiomeTree != tree) {
                put(biomeTreeBuffer, nodes, stack);
                uploadedBiomeTree = tree;
            }
            check(vkResetCommandBuffer(commandBuffer, 0), "reset biome search command buffer");
            check(vkBeginCommandBuffer(commandBuffer, VkCommandBufferBeginInfo.calloc(stack).sType$Default()),
                    "begin biome search command buffer");
            vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, biomeSearchPipeline);
            vkCmdBindDescriptorSets(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE,
                    biomeSearchPipelineLayout, 0, stack.longs(biomeSearchDescriptorSet), null);
            ByteBuffer push = stack.malloc(12).order(ByteOrder.nativeOrder())
                    .putInt(count).putInt(nodeCount).putInt(maxDepth).flip();
            vkCmdPushConstants(commandBuffer, biomeSearchPipelineLayout,
                    VK_SHADER_STAGE_COMPUTE_BIT, 0, push);
            vkCmdDispatch(commandBuffer, (count + 63) / 64, 1, 1);
            VkBufferMemoryBarrier.Buffer barrier = VkBufferMemoryBarrier.calloc(1, stack).sType$Default()
                    .srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT).dstAccessMask(VK_ACCESS_HOST_READ_BIT)
                    .buffer(interpolationOutput.buffer).size((long) count * Integer.BYTES);
            vkCmdPipelineBarrier(commandBuffer, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK_PIPELINE_STAGE_HOST_BIT, 0, null, barrier, null);
            check(vkEndCommandBuffer(commandBuffer), "end biome search command buffer");
            synchronized (queueSubmitLock) {
                check(vkQueueSubmit(queue, VkSubmitInfo.calloc(stack).sType$Default()
                        .pCommandBuffers(stack.pointers(commandBuffer.address())), fence), "submit biome search");
            }
            check(vkWaitForFences(device, fence, true, Long.MAX_VALUE), "wait biome search");
            check(vkResetFences(device, fence), "reset biome search fence");
            IntBuffer values = persistentMappings.get(interpolationOutput)
                    .duplicate().order(ByteOrder.nativeOrder()).asIntBuffer();
            values.clear();
            values.limit(count);
            int[] output = new int[count];
            values.get(output);
            return output;
        }
    }

    private static int[] unpackAquiferSubstanceRows(int[] words, int rows) {
        if (words.length != GpuAquiferSubstance.shaderOutputWordCount(rows))
            throw new IllegalArgumentException("packed aquifer output word count does not match row count");
        int[] output = new int[rows];
        for (int row = 0; row < rows; row++) {
            output[row] = (words[row >>> 1] >>> ((row & 1) * 16)) & 0xffff;
        }
        return output;
    }

    /** Prefills the discrete per-center aquifer status using GPU terrain roots. */
    synchronized int[] submitAquiferFluidStatus(ByteBuffer records, int count) throws Exception {
        if (hasInFlightWork()) throw new IllegalStateException("Vulkan slot is still in flight");
        if (count <= 0 || count > GpuAquiferFluidStatus.MAX_QUERIES
                || records.remaining() != count * GpuAquiferFluidStatus.RECORD_BYTES)
            throw new IllegalArgumentException("invalid aquifer fluid-status batch");
        try (MemoryStack stack = MemoryStack.stackPush()) {
            put(aquiferNearestInputs, records, stack);
            check(vkResetCommandBuffer(commandBuffer, 0), "reset aquifer fluid-status command buffer");
            check(vkBeginCommandBuffer(commandBuffer, VkCommandBufferBeginInfo.calloc(stack).sType$Default()), "begin aquifer fluid-status command buffer");
            vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, aquiferFluidStatusPipeline);
            vkCmdBindDescriptorSets(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE,
                    aquiferFluidStatusPipelineLayout, 0, stack.longs(aquiferNearestDescriptorSet), null);
            ByteBuffer push = stack.malloc(16).order(ByteOrder.nativeOrder())
                    .putInt(count).putInt(0).putInt(0).putInt(0).flip();
            vkCmdPushConstants(commandBuffer, aquiferFluidStatusPipelineLayout,
                    VK_SHADER_STAGE_COMPUTE_BIT, 0, push);
            vkCmdDispatch(commandBuffer, (count + 63) / 64, 1, 1);
            VkBufferMemoryBarrier.Buffer barrier = VkBufferMemoryBarrier.calloc(1, stack).sType$Default()
                    .srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT).dstAccessMask(VK_ACCESS_HOST_READ_BIT)
                    .buffer(aquiferNearestOutput.buffer)
                    .size((long) count * GpuAquiferFluidStatus.OUTPUT_INTS * Integer.BYTES);
            vkCmdPipelineBarrier(commandBuffer, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK_PIPELINE_STAGE_HOST_BIT, 0, null, barrier, null);
            check(vkEndCommandBuffer(commandBuffer), "end aquifer fluid-status command buffer");
            synchronized (queueSubmitLock) {
                check(vkQueueSubmit(queue, VkSubmitInfo.calloc(stack).sType$Default()
                        .pCommandBuffers(stack.pointers(commandBuffer.address())), fence), "submit aquifer fluid-status");
            }
            check(vkWaitForFences(device, fence, true, Long.MAX_VALUE), "wait aquifer fluid-status");
            check(vkResetFences(device, fence), "reset aquifer fluid-status fence");
            IntBuffer values = persistentMappings.get(aquiferNearestOutput)
                    .duplicate().order(ByteOrder.nativeOrder()).asIntBuffer();
            values.clear();
            values.limit(count * GpuAquiferFluidStatus.OUTPUT_INTS);
            int[] output = new int[count * GpuAquiferFluidStatus.OUTPUT_INTS];
            values.get(output);
            return output;
        }
    }

    private static boolean validAquiferStatusPayload(ByteBuffer records, int count) {
        if (records == null || count <= 0) return false;
        long bytes = records.remaining();
        return bytes == (long) count * GpuAquiferFluidStatus.RECORD_BYTES
                || bytes == (long) count * GpuAquiferFluidStatus.COMPACT_PRECOMPUTED_RECORD_BYTES;
    }

    private static int aquiferStatusStride(ByteBuffer records, int count) {
        if (!validAquiferStatusPayload(records, count))
            throw new IllegalArgumentException("invalid aquifer status payload");
        return records.remaining() / count;
    }

    private void writeCompactAquiferStatusOutput(ByteBuffer records, int count, int outputBaseInt) {
        ByteBuffer mapped = persistentMappings.get(aquiferNearestOutput);
        if (mapped == null) throw new IllegalStateException("aquifer output is not host visible");
        IntBuffer output = mapped.duplicate().order(ByteOrder.nativeOrder()).asIntBuffer();
        IntBuffer input = records.duplicate().order(ByteOrder.nativeOrder()).asIntBuffer();
        int requiredInts = Math.addExact(outputBaseInt,
                Math.multiplyExact(count, GpuAquiferFluidStatus.OUTPUT_INTS));
        if (input.remaining() != count * 4 || requiredInts > output.capacity())
            throw new IllegalArgumentException("invalid compact aquifer status output range");
        for (int row = 0; row < count; row++) {
            input.get(); // Y is not part of the aquifer status output ABI.
            int level = input.get();
            int stateId = input.get();
            int kind = input.get();
            int base = outputBaseInt + row * GpuAquiferFluidStatus.OUTPUT_INTS;
            output.put(base, level);
            output.put(base + 1, 0);
            output.put(base + 2, 0);
            output.put(base + 3, stateId);
            output.put(base + 4, kind);
        }
    }

    /** Runs nearest-center ranking and center-status prefill without waiting on its fence. */
    synchronized CompletableFuture<GpuAquiferPrefillResult> submitAquiferPrefillAsync(
            ByteBuffer nearestRecords, int nearestCount, ByteBuffer statusRecords, int statusCount)
            throws Exception {
        return submitAquiferPrefillAsync(nearestRecords, nearestCount, statusRecords, statusCount, false);
    }

    /** Adds a GPU-resident substance decision pass before the single final readback. */
    synchronized CompletableFuture<GpuAquiferPrefillResult> submitAquiferPrefillAsync(
            ByteBuffer nearestRecords, int nearestCount, ByteBuffer statusRecords, int statusCount,
            boolean decisionChain)
            throws Exception {
        if (hasInFlightWork()) throw new IllegalStateException("Vulkan slot is still in flight");
        if (!GpuAquiferNearestThree.isValidPacked(nearestRecords, nearestCount)
                || statusCount <= 0 || statusCount > GpuAquiferFluidStatus.MAX_PREFILL_CENTERS
                || !validAquiferStatusPayload(statusRecords, statusCount))
            throw new IllegalArgumentException("invalid combined aquifer prefill batch");
        int statusStride = aquiferStatusStride(statusRecords, statusCount);
        boolean compactPrecomputed = statusStride
                == GpuAquiferFluidStatus.COMPACT_PRECOMPUTED_RECORD_BYTES;
        int nearestBytes = nearestRecords.remaining();
        int statusByteOffset = (nearestBytes + 3) & ~3;
        long combinedBytes = compactPrecomputed ? nearestBytes
                : (long) statusByteOffset + (long) statusCount * statusStride;
        if (combinedBytes > AQUIFER_INPUT_BYTES)
            throw new IllegalArgumentException("combined aquifer input exceeds GPU capacity");

        ByteBuffer input = persistentMappings.get(aquiferNearestInputs);
        if (input == null) input = ByteBuffer.allocateDirect(Math.toIntExact(combinedBytes))
                .order(ByteOrder.nativeOrder());
        else input.clear();
        input.put(nearestRecords.duplicate());
        if (!compactPrecomputed) {
            while (input.position() < statusByteOffset) input.put((byte) 0);
            input.put(statusRecords.duplicate());
        }
        input.flip();
        if (compactPrecomputed) writeCompactAquiferStatusOutput(statusRecords, statusCount,
                nearestCount * GpuAquiferNearestThree.OUTPUT_INTS);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            if (persistentMappings.get(aquiferNearestInputs) == null)
                put(aquiferNearestInputs, input, stack);
            check(vkResetCommandBuffer(commandBuffer, 0), "reset async combined aquifer command buffer");
            check(vkBeginCommandBuffer(commandBuffer, VkCommandBufferBeginInfo.calloc(stack).sType$Default()),
                    "begin async combined aquifer command buffer");
            vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, aquiferNearestPipeline);
            vkCmdBindDescriptorSets(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE,
                    aquiferNearestPipelineLayout, 0, stack.longs(aquiferNearestDescriptorSet), null);
            ByteBuffer nearestPush = stack.malloc(4).order(ByteOrder.nativeOrder()).putInt(nearestCount).flip();
            vkCmdPushConstants(commandBuffer, aquiferNearestPipelineLayout,
                    VK_SHADER_STAGE_COMPUTE_BIT, 0, nearestPush);
            vkCmdDispatch(commandBuffer, Math.max(1, (nearestCount + 63) / 64), 1, 1);
            if (!compactPrecomputed) {
                vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, aquiferFluidStatusPipeline);
                vkCmdBindDescriptorSets(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE,
                        aquiferFluidStatusPipelineLayout, 0, stack.longs(aquiferNearestDescriptorSet), null);
                ByteBuffer statusPush = stack.malloc(16).order(ByteOrder.nativeOrder())
                        .putInt(statusCount).putInt(statusByteOffset)
                        .putInt(nearestCount * GpuAquiferNearestThree.OUTPUT_INTS).putInt(0).flip();
                vkCmdPushConstants(commandBuffer, aquiferFluidStatusPipelineLayout,
                        VK_SHADER_STAGE_COMPUTE_BIT, 0, statusPush);
                vkCmdDispatch(commandBuffer, (statusCount + 63) / 64, 1, 1);
            }
            long nearestOutputBytes = (long) nearestCount
                    * GpuAquiferNearestThree.OUTPUT_INTS * Integer.BYTES;
            long statusOutputBytes = (long) statusCount
                    * GpuAquiferFluidStatus.OUTPUT_INTS * Integer.BYTES;
            if (decisionChain) {
                VkBufferMemoryBarrier.Buffer computeBarrier = VkBufferMemoryBarrier.calloc(1, stack)
                        .sType$Default().srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT)
                        .dstAccessMask(VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT)
                        .buffer(aquiferNearestOutput.buffer)
                        .offset(0).size(nearestOutputBytes + statusOutputBytes);
                int srcStages = VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT;
                if (compactPrecomputed) {
                    computeBarrier.srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_HOST_WRITE_BIT);
                    srcStages |= VK_PIPELINE_STAGE_HOST_BIT;
                }
                vkCmdPipelineBarrier(commandBuffer, srcStages,
                        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, null, computeBarrier, null);
                vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE,
                        aquiferDecisionChainPipeline);
                vkCmdBindDescriptorSets(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE,
                        aquiferDecisionChainPipelineLayout, 0,
                        stack.longs(aquiferNearestDescriptorSet), null);
                int statusBase = Math.multiplyExact(nearestCount,
                        GpuAquiferNearestThree.OUTPUT_INTS);
                int decisionBase = Math.addExact(statusBase, Math.multiplyExact(statusCount,
                        GpuAquiferFluidStatus.OUTPUT_INTS));
                ByteBuffer decisionPush = stack.malloc(16).order(ByteOrder.nativeOrder())
                        .putInt(nearestCount).putInt(statusCount).putInt(statusBase)
                        .putInt(decisionBase).flip();
                vkCmdPushConstants(commandBuffer, aquiferDecisionChainPipelineLayout,
                        VK_SHADER_STAGE_COMPUTE_BIT, 0, decisionPush);
                vkCmdDispatch(commandBuffer, (nearestCount + 63) / 64, 1, 1);
            }
            long outputBytes = ((long) nearestCount * GpuAquiferNearestThree.OUTPUT_INTS
                    + (long) statusCount * GpuAquiferFluidStatus.OUTPUT_INTS) * Integer.BYTES;
            if (decisionChain) outputBytes += (long) nearestCount * 3L * Integer.BYTES;
            VkBufferMemoryBarrier.Buffer barrier = VkBufferMemoryBarrier.calloc(1, stack).sType$Default()
                    .srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT).dstAccessMask(VK_ACCESS_HOST_READ_BIT)
                    .buffer(aquiferNearestOutput.buffer).size(outputBytes);
            vkCmdPipelineBarrier(commandBuffer, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK_PIPELINE_STAGE_HOST_BIT, 0, null, barrier, null);
            check(vkEndCommandBuffer(commandBuffer), "end async combined aquifer command buffer");
            synchronized (queueSubmitLock) {
                check(vkQueueSubmit(queue, VkSubmitInfo.calloc(stack).sType$Default()
                        .pCommandBuffers(stack.pointers(commandBuffer.address())), fence),
                        "submit async combined aquifer prefill");
            }
        }
        inFlightAquiferNearestCount = nearestCount;
        inFlightAquiferStatusCount = statusCount;
        inFlightAquiferDecisionChain = decisionChain;
        inFlightAquiferPrefill = new CompletableFuture<>();
        return inFlightAquiferPrefill;
    }

    /** Runs nearest-center ranking and C2ME center-status prefill in one submission and fence wait. */
    synchronized GpuAquiferPrefillResult submitAquiferPrefill(ByteBuffer nearestRecords, int nearestCount,
                                                               ByteBuffer statusRecords, int statusCount)
            throws Exception {
        if (hasInFlightWork()) throw new IllegalStateException("Vulkan slot is still in flight");
        if (!GpuAquiferNearestThree.isValidPacked(nearestRecords, nearestCount)
                || statusCount <= 0 || statusCount > GpuAquiferFluidStatus.MAX_PREFILL_CENTERS
                || !validAquiferStatusPayload(statusRecords, statusCount))
            throw new IllegalArgumentException("invalid combined aquifer prefill batch");
        int statusStride = aquiferStatusStride(statusRecords, statusCount);
        boolean compactPrecomputed = statusStride
                == GpuAquiferFluidStatus.COMPACT_PRECOMPUTED_RECORD_BYTES;
        int nearestBytes = nearestRecords.remaining();
        int statusByteOffset = (nearestBytes + 3) & ~3;
        long combinedBytes = compactPrecomputed ? nearestBytes
                : (long) statusByteOffset + (long) statusCount * statusStride;
        if (combinedBytes > AQUIFER_INPUT_BYTES)
            throw new IllegalArgumentException("combined aquifer input exceeds GPU capacity");

        ByteBuffer input = persistentMappings.get(aquiferNearestInputs);
        if (input == null) {
            input = ByteBuffer.allocateDirect(Math.toIntExact(combinedBytes))
                    .order(ByteOrder.nativeOrder());
        } else {
            input.clear();
        }
        input.put(nearestRecords.duplicate());
        if (!compactPrecomputed) {
            while (input.position() < statusByteOffset) input.put((byte) 0);
            input.put(statusRecords.duplicate());
        }
        input.flip();
        if (compactPrecomputed) writeCompactAquiferStatusOutput(statusRecords, statusCount,
                nearestCount * GpuAquiferNearestThree.OUTPUT_INTS);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            if (persistentMappings.get(aquiferNearestInputs) == null)
                put(aquiferNearestInputs, input, stack);
            check(vkResetCommandBuffer(commandBuffer, 0), "reset combined aquifer command buffer");
            check(vkBeginCommandBuffer(commandBuffer, VkCommandBufferBeginInfo.calloc(stack).sType$Default()),
                    "begin combined aquifer command buffer");

            vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, aquiferNearestPipeline);
            vkCmdBindDescriptorSets(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE,
                    aquiferNearestPipelineLayout, 0, stack.longs(aquiferNearestDescriptorSet), null);
            ByteBuffer nearestPush = stack.malloc(4).order(ByteOrder.nativeOrder()).putInt(nearestCount).flip();
            vkCmdPushConstants(commandBuffer, aquiferNearestPipelineLayout,
                    VK_SHADER_STAGE_COMPUTE_BIT, 0, nearestPush);
            vkCmdDispatch(commandBuffer, Math.max(1, (nearestCount + 63) / 64), 1, 1);

            if (!compactPrecomputed) {
                vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, aquiferFluidStatusPipeline);
                vkCmdBindDescriptorSets(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE,
                        aquiferFluidStatusPipelineLayout, 0, stack.longs(aquiferNearestDescriptorSet), null);
                ByteBuffer statusPush = stack.malloc(16).order(ByteOrder.nativeOrder())
                        .putInt(statusCount).putInt(statusByteOffset)
                        .putInt(nearestCount * GpuAquiferNearestThree.OUTPUT_INTS).putInt(0).flip();
                vkCmdPushConstants(commandBuffer, aquiferFluidStatusPipelineLayout,
                        VK_SHADER_STAGE_COMPUTE_BIT, 0, statusPush);
                vkCmdDispatch(commandBuffer, (statusCount + 63) / 64, 1, 1);
            }

            long outputBytes = ((long) nearestCount * GpuAquiferNearestThree.OUTPUT_INTS
                    + (long) statusCount * GpuAquiferFluidStatus.OUTPUT_INTS) * Integer.BYTES;
            VkBufferMemoryBarrier.Buffer barrier = VkBufferMemoryBarrier.calloc(1, stack).sType$Default()
                    .srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT).dstAccessMask(VK_ACCESS_HOST_READ_BIT)
                    .buffer(aquiferNearestOutput.buffer).size(outputBytes);
            vkCmdPipelineBarrier(commandBuffer, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK_PIPELINE_STAGE_HOST_BIT, 0, null, barrier, null);
            check(vkEndCommandBuffer(commandBuffer), "end combined aquifer command buffer");
            synchronized (queueSubmitLock) {
                check(vkQueueSubmit(queue, VkSubmitInfo.calloc(stack).sType$Default()
                        .pCommandBuffers(stack.pointers(commandBuffer.address())), fence), "submit combined aquifer prefill");
            }
            check(vkWaitForFences(device, fence, true, Long.MAX_VALUE), "wait combined aquifer prefill");
            check(vkResetFences(device, fence), "reset combined aquifer prefill fence");
            IntBuffer values = persistentMappings.get(aquiferNearestOutput)
                    .duplicate().order(ByteOrder.nativeOrder()).asIntBuffer();
            values.clear();
            int nearestInts = nearestCount * GpuAquiferNearestThree.OUTPUT_INTS;
            int statusInts = statusCount * GpuAquiferFluidStatus.OUTPUT_INTS;
            int[] nearestRows = new int[nearestInts];
            int[] statusRows = new int[statusInts];
            values.limit(nearestInts);
            values.get(nearestRows);
            values.position(nearestInts).limit(nearestInts + statusInts);
            values.get(statusRows);
            return new GpuAquiferPrefillResult(nearestRows, statusRows);
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
            FloatBuffer values = persistentMappings.get(interpolationOutput)
                    .duplicate().order(ByteOrder.nativeOrder()).asFloatBuffer();
            values.clear();
            values.limit(blockCount * outputStride);
            float[] output = new float[blockCount * outputStride];
            values.get(output);
            return output;
        }
    }

    private void uploadGraphRoots(int[] roots, MemoryStack stack) {
        uploadGraphRoots(roots, roots, stack);
    }

    private void uploadGraphRoots(int[] roots, int[] blockRoots, MemoryStack stack) {
        if (roots.length > 16 || blockRoots.length > 16)
            throw new IllegalArgumentException("GPU root tables are limited to 16 roots");
        if (uploadedGraphRoots != null && java.util.Arrays.equals(uploadedGraphRoots, roots)
                && uploadedBlockGraphRoots != null && java.util.Arrays.equals(uploadedBlockGraphRoots, blockRoots)) return;
        ByteBuffer bytes = stack.malloc(32 * Integer.BYTES).order(ByteOrder.nativeOrder());
        for (int i = 0; i < 16; i++) bytes.putInt(i < roots.length ? roots[i] : 0);
        for (int i = 0; i < 16; i++) bytes.putInt(i < blockRoots.length ? blockRoots[i] : 0);
        bytes.flip();
        put(graphRoots, bytes, stack);
        uploadedGraphRoots = roots.clone();
        uploadedBlockGraphRoots = blockRoots.clone();
    }

    private GpuDensityGraphBuffer.Packed packedGraph(GpuDensityGraph graph) {
        int allRoots = graph.routerRootCount() >= Integer.SIZE
                ? -1 : (1 << graph.routerRootCount()) - 1;
        return packedGraph(graph, allRoots);
    }

    private GpuDensityGraphBuffer.Packed packedGraph(GpuDensityGraph graph, int[] outputRootIndexes) {
        int rootMask = 0;
        for (int root : outputRootIndexes) {
            if (root < 0 || root >= graph.routerRootCount())
                throw new IllegalArgumentException("invalid terrain block output root " + root);
            rootMask |= 1 << root;
        }
        return packedGraph(graph, rootMask);
    }

    private GpuDensityGraphBuffer.Packed packedGraph(GpuDensityGraph graph, int blockRootMask) {
        Map<Integer, GpuDensityGraphBuffer.Packed> variants =
                packedGraphs.computeIfAbsent(graph, ignored -> new java.util.HashMap<>());
        return variants.computeIfAbsent(blockRootMask,
                mask -> GpuDensityGraphBuffer.pack(graph, mask));
    }

    private void uploadInterpolatorMap(GpuDensityGraph graph, MemoryStack stack) {
        if (interpolatorMapGraph == graph) return;
        int[] registers = graph.interpolatedMarkerRegisters();
        if (registers.length > MAX_INTERPOLATORS || graph.registerCount() > 256)
            throw new IllegalArgumentException("terrain graph exceeds interpolated marker capacity");
        ByteBuffer map = stack.malloc((1 + MAX_INTERPOLATORS + 256) * Integer.BYTES)
                .order(ByteOrder.nativeOrder());
        map.putInt(registers.length);
        int[] slots = new int[256];
        java.util.Arrays.fill(slots, -1);
        for (int i = 0; i < registers.length; i++) {
            int register = registers[i];
            if (register < 0 || register >= slots.length)
                throw new IllegalArgumentException("invalid interpolated marker register " + register);
            map.putInt(register);
            slots[register] = i;
        }
        for (int i = registers.length; i < MAX_INTERPOLATORS; i++) map.putInt(0);
        for (int slot : slots) map.putInt(slot < 0 ? 0xffff_ffff : slot);
        map.flip();
        put(interpolatorMap, map, stack);
        interpolatorMapGraph = graph;
    }

    private void mapPersistentBuffers(MemoryStack stack) {
        NativeBuffer[] buffers = {coordinates, results, permutations, octaves, samplerMetadata,
                graphCoordinates, graphNodes, graphSplinePoints, graphBlendedMetadata, graphRoots,
                interpolatorMap, interpolationMetadata, interpolationOutput, aquiferPressureInputs,
                aquiferNearestInputs, aquiferNearestOutput, biomeTreeBuffer};
        for (NativeBuffer buffer : buffers) {
            if (buffer.hostVisible) {
                persistentMappings.put(buffer, buffer.map(device, stack).order(ByteOrder.nativeOrder()));
            }
        }
        persistentResults = persistentMappings.get(results);
    }

    private void writeGraphCoordinates(float[] xs, float[] ys, float[] zs, int count) {
        ByteBuffer mapped = persistentMappings.get(graphCoordinates);
        if (mapped == null) throw new IllegalStateException("graph coordinate buffer is not persistently mapped");
        mapped.clear();
        for (int i = 0; i < count; i++) mapped.putFloat(xs[i]).putFloat(ys[i]).putFloat(zs[i]).putFloat(0.0f);
        mapped.flip();
    }

    private void put(NativeBuffer target, ByteBuffer source, MemoryStack stack) {
        int bytes = source.remaining();
        if (bytes == 0) return;
        ByteBuffer mapped = persistentMappings.get(target);
        if (mapped == null) {
            NativeBuffer staging = hostVisibleBuffer(bytes, stack);
            ByteBuffer stagingMap = staging.map(device, stack).order(ByteOrder.nativeOrder());
            stagingMap.put(source.duplicate());
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
    }
    private VkInstance createInstance(MemoryStack s) { VkApplicationInfo a=VkApplicationInfo.calloc(s).sType$Default().pApplicationName(s.UTF8("GPU Noise Persistent")).apiVersion(VK_API_VERSION_1_0); VkInstanceCreateInfo i=VkInstanceCreateInfo.calloc(s).sType$Default().pApplicationInfo(a); PointerBuffer h=s.mallocPointer(1); check(vkCreateInstance(i,null,h),"create instance"); return new VkInstance(h.get(0),i); }
    private VkPhysicalDevice findPhysical(VkInstance instance, GpuDevice expected, MemoryStack stack) {
        IntBuffer count = stack.ints(0);
        check(vkEnumeratePhysicalDevices(instance, count, null), "enumerate physical devices");
        if (expected.enumerationIndex() < 0 || expected.enumerationIndex() >= count.get(0)) {
            throw new IllegalStateException("selected Vulkan GPU disappeared from the device list");
        }
        PointerBuffer handles = stack.mallocPointer(count.get(0));
        check(vkEnumeratePhysicalDevices(instance, count, handles), "enumerate physical devices");
        VkPhysicalDevice physicalDevice = new VkPhysicalDevice(
                handles.get(expected.enumerationIndex()), instance);
        VkPhysicalDeviceProperties properties = VkPhysicalDeviceProperties.malloc();
        try {
            vkGetPhysicalDeviceProperties(physicalDevice, properties);
            if (properties.vendorID() != expected.vendorId()
                    || properties.deviceID() != expected.deviceId()
                    || !properties.deviceNameString().equals(expected.name())) {
                throw new IllegalStateException("selected Vulkan GPU identity changed between probe and initialization");
            }
            return physicalDevice;
        } finally {
            properties.free();
        }
    }
    private int findQueue(VkPhysicalDevice physicalDevice, MemoryStack stack) {
        int queueFamily = VulkanQueueSelection.findComputeFamily(physicalDevice, stack);
        if (queueFamily < 0) throw new IllegalStateException("no compute queue");
        return queueFamily;
    }
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
    private long graphDescriptorLayout(MemoryStack s){VkDescriptorSetLayoutBinding.Buffer b=VkDescriptorSetLayoutBinding.calloc(GRAPH_DESCRIPTOR_BINDINGS.length,s);for(int i=0;i<GRAPH_DESCRIPTOR_BINDINGS.length;i++)b.get(i).binding(GRAPH_DESCRIPTOR_BINDINGS[i]).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);VkDescriptorSetLayoutCreateInfo i=VkDescriptorSetLayoutCreateInfo.calloc(s).sType$Default().pBindings(b);LongBuffer h=s.longs(0);check(vkCreateDescriptorSetLayout(device,i,null,h),"graph layout");return h.get(0);}
    private long graphPipelineLayout(MemoryStack s){VkPushConstantRange.Buffer r=VkPushConstantRange.calloc(1,s).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT).size(16);VkPipelineLayoutCreateInfo i=VkPipelineLayoutCreateInfo.calloc(s).sType$Default().pSetLayouts(s.longs(graphDescriptorLayout)).pPushConstantRanges(r);LongBuffer h=s.longs(0);check(vkCreatePipelineLayout(device,i,null,h),"graph pipeline layout");return h.get(0);}
    private long graphPipeline(MemoryStack s){VkPipelineShaderStageCreateInfo st=VkPipelineShaderStageCreateInfo.calloc(s).sType$Default().stage(VK_SHADER_STAGE_COMPUTE_BIT).module(graphShader).pName(s.UTF8("main"));VkComputePipelineCreateInfo.Buffer i=VkComputePipelineCreateInfo.calloc(1,s).sType$Default().stage(st).layout(graphPipelineLayout);LongBuffer h=s.longs(0);check(vkCreateComputePipelines(device,VK_NULL_HANDLE,i,null,h),"graph pipeline");return h.get(0);}
    private long graphPipeline(long shaderModule, MemoryStack s){VkPipelineShaderStageCreateInfo st=VkPipelineShaderStageCreateInfo.calloc(s).sType$Default().stage(VK_SHADER_STAGE_COMPUTE_BIT).module(shaderModule).pName(s.UTF8("main"));VkComputePipelineCreateInfo.Buffer i=VkComputePipelineCreateInfo.calloc(1,s).sType$Default().stage(st).layout(graphPipelineLayout);LongBuffer h=s.longs(0);check(vkCreateComputePipelines(device,VK_NULL_HANDLE,i,null,h),"block graph pipeline");return h.get(0);}
    private long gridPipeline(MemoryStack s){VkPipelineShaderStageCreateInfo st=VkPipelineShaderStageCreateInfo.calloc(s).sType$Default().stage(VK_SHADER_STAGE_COMPUTE_BIT).module(gridShader).pName(s.UTF8("main"));VkComputePipelineCreateInfo.Buffer i=VkComputePipelineCreateInfo.calloc(1,s).sType$Default().stage(st).layout(graphPipelineLayout);LongBuffer h=s.longs(0);check(vkCreateComputePipelines(device,VK_NULL_HANDLE,i,null,h),"grid pipeline");return h.get(0);}
    private long compileRuntimeShader(String resource, String programName, Map<String, String> definitions,
                                      GpuDensityGraph graph, GpuDensityGraphBuffer.Packed packed,
                                      boolean blockProgram) {
        ByteBuffer spirv = GpuRuntimeShaderCompiler.compileResource(resource, programName, definitions,
                graph, packed, blockProgram);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkShaderModuleCreateInfo create = VkShaderModuleCreateInfo.calloc(stack)
                    .sType$Default().pCode(spirv);
            LongBuffer handle = stack.longs(0);
            check(vkCreateShaderModule(device, create, null, handle), programName + " module");
            return handle.get(0);
        } finally {
            MemoryUtil.memFree(spirv);
        }
    }

    private void ensureGraphVariant(GpuDensityGraph graph, MemoryStack stack) throws Exception {
        int required = graph.registerCount();
        int variant = required <= 32 ? 32 : required <= 64 ? 64 : required <= 128 ? 128 : 256;
        if (graph == compiledGraphVariant && variant == graphRegisterVariant
                && FAST_GRAPH_MATH == graphFastMathVariant) return;
        String source = "/assets/gpunoise/shaders/density_graph.comp";
        Map<String, String> defines = new java.util.HashMap<>();
        defines.put("REGISTER_COUNT", Integer.toString(variant));
        if (FAST_GRAPH_MATH) defines.put("FAST_MATH", "1");
        GpuDensityGraphBuffer.Packed packed = packedGraph(graph);
        long nextGraphShader = compileRuntimeShader(source, "density graph", defines, graph, packed, false);
        long nextGridShader;
        try {
            Map<String, String> gridDefines = new java.util.HashMap<>(defines);
            gridDefines.put("GRID_INPUT", "1");
            nextGridShader = compileRuntimeShader(source, "terrain grid", gridDefines, graph, packed, false);
        } catch (Throwable failure) {
            vkDestroyShaderModule(device, nextGraphShader, null);
            throw failure;
        }
        long nextGraphPipeline;
        long nextGridPipeline;
        try {
            nextGraphPipeline = graphPipeline(nextGraphShader, stack);
            nextGridPipeline = graphPipeline(nextGridShader, stack);
        } catch (Throwable failure) {
            vkDestroyShaderModule(device, nextGraphShader, null);
            vkDestroyShaderModule(device, nextGridShader, null);
            throw failure;
        }
        vkDestroyPipeline(device, graphPipeline, null);
        vkDestroyPipeline(device, gridPipeline, null);
        vkDestroyShaderModule(device, graphShader, null);
        vkDestroyShaderModule(device, gridShader, null);
        graphShader = nextGraphShader;
        gridShader = nextGridShader;
        graphPipeline = nextGraphPipeline;
        gridPipeline = nextGridPipeline;
        graphRegisterVariant = variant;
        graphFastMathVariant = FAST_GRAPH_MATH;
        compiledGraphVariant = graph;
        GpuNoise.LOGGER.info("GPU density shader variant: registers={}, fastMath={}", variant, FAST_GRAPH_MATH);
    }
    private void ensureBlockGraphVariant(GpuDensityGraph graph, GpuDensityGraphBuffer.Packed packed,
                                         MemoryStack stack) throws Exception {
        int required = packed.blockRegisterCount();
        int variant = required <= 32 ? 32 : required <= 64 ? 64 : required <= 128 ? 128 : 256;
        // A graph may have several block programs for different compact root
        // masks. They share the same router identity and register tier, but
        // their statically emitted instructions and register assignments can
        // differ. Reusing a pipeline across those packed programs pairs the
        // new root table with stale shader code and silently corrupts outputs.
        if (graph == compiledBlockGraphVariant && packed == compiledBlockGraphPacked
                && variant == blockGraphRegisterVariant
                && FAST_GRAPH_MATH == blockGraphFastMathVariant) return;
        long nextShader = compileRuntimeShader("/assets/gpunoise/shaders/density_graph.comp",
                "block density graph", FAST_GRAPH_MATH
                        ? Map.of("REGISTER_COUNT", Integer.toString(variant), "FAST_MATH", "1")
                        : Map.of("REGISTER_COUNT", Integer.toString(variant)), graph, packed, true);
        long nextPipeline;
        try {
            nextPipeline = graphPipeline(nextShader, stack);
        } catch (Throwable failure) {
            vkDestroyShaderModule(device, nextShader, null);
            throw failure;
        }
        vkDestroyPipeline(device, blockGraphPipeline, null);
        vkDestroyShaderModule(device, blockGraphShader, null);
        blockGraphShader = nextShader;
        blockGraphPipeline = nextPipeline;
        blockGraphRegisterVariant = variant;
        blockGraphFastMathVariant = FAST_GRAPH_MATH;
        compiledBlockGraphVariant = graph;
        compiledBlockGraphPacked = packed;
    }
    private long graphDescriptorPool(MemoryStack s){VkDescriptorPoolSize.Buffer z=VkDescriptorPoolSize.calloc(1,s).type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(GRAPH_DESCRIPTOR_BINDINGS.length);VkDescriptorPoolCreateInfo i=VkDescriptorPoolCreateInfo.calloc(s).sType$Default().maxSets(1).pPoolSizes(z);LongBuffer h=s.longs(0);check(vkCreateDescriptorPool(device,i,null,h),"graph pool");return h.get(0);}
    private long graphDescriptorSet(MemoryStack s){VkDescriptorSetAllocateInfo i=VkDescriptorSetAllocateInfo.calloc(s).sType$Default().descriptorPool(graphDescriptorPool).pSetLayouts(s.longs(graphDescriptorLayout));LongBuffer h=s.longs(0);check(vkAllocateDescriptorSets(device,i,h),"graph set");return h.get(0);}
    private void graphDescriptors(MemoryStack s){NativeBuffer[] bs={graphCoordinates,results,graphNodes,permutations,octaves,samplerMetadata,graphSplinePoints,graphBlendedMetadata,graphRoots,graphMthSinTable,interpolatedInputs,interpolatorMap,interpolationOutput,graphCoordinates};VkWriteDescriptorSet.Buffer w=VkWriteDescriptorSet.calloc(bs.length,s);for(int i=0;i<bs.length;i++){VkDescriptorBufferInfo.Buffer bi=VkDescriptorBufferInfo.calloc(1,s).buffer(bs[i].buffer).range(bs[i].size);w.get(i).sType$Default().dstSet(graphDescriptorSet).dstBinding(GRAPH_DESCRIPTOR_BINDINGS[i]).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).pBufferInfo(bi);}vkUpdateDescriptorSets(device,w,null);}
    private long interpolationShader(MemoryStack s)throws Exception{try(var in=getClass().getResourceAsStream("/assets/gpunoise/shaders/noise_interpolation.comp.spv")){byte[] b=in.readAllBytes();ByteBuffer c=MemoryUtil.memAlloc(b.length).put(b).flip();try{VkShaderModuleCreateInfo i=VkShaderModuleCreateInfo.calloc(s).sType$Default().pCode(c);LongBuffer h=s.longs(0);check(vkCreateShaderModule(device,i,null,h),"interpolation shader");return h.get(0);}finally{MemoryUtil.memFree(c);}}}
    private long interpolationDescriptorLayout(MemoryStack s){VkDescriptorSetLayoutBinding.Buffer b=VkDescriptorSetLayoutBinding.calloc(5,s);for(int i=0;i<5;i++)b.get(i).binding(i).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);VkDescriptorSetLayoutCreateInfo i=VkDescriptorSetLayoutCreateInfo.calloc(s).sType$Default().pBindings(b);LongBuffer h=s.longs(0);check(vkCreateDescriptorSetLayout(device,i,null,h),"interpolation layout");return h.get(0);}
    private long interpolationPipelineLayout(MemoryStack s){VkPushConstantRange.Buffer r=VkPushConstantRange.calloc(1,s).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT).size(16);VkPipelineLayoutCreateInfo i=VkPipelineLayoutCreateInfo.calloc(s).sType$Default().pSetLayouts(s.longs(interpolationDescriptorLayout)).pPushConstantRanges(r);LongBuffer h=s.longs(0);check(vkCreatePipelineLayout(device,i,null,h),"interpolation pipeline layout");return h.get(0);}
    private long interpolationPipeline(MemoryStack s){VkPipelineShaderStageCreateInfo st=VkPipelineShaderStageCreateInfo.calloc(s).sType$Default().stage(VK_SHADER_STAGE_COMPUTE_BIT).module(interpolationShader).pName(s.UTF8("main"));VkComputePipelineCreateInfo.Buffer i=VkComputePipelineCreateInfo.calloc(1,s).sType$Default().stage(st).layout(interpolationPipelineLayout);LongBuffer h=s.longs(0);check(vkCreateComputePipelines(device,VK_NULL_HANDLE,i,null,h),"interpolation pipeline");return h.get(0);}
    private long interpolationDescriptorPool(MemoryStack s){VkDescriptorPoolSize.Buffer z=VkDescriptorPoolSize.calloc(1,s).type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(5);VkDescriptorPoolCreateInfo i=VkDescriptorPoolCreateInfo.calloc(s).sType$Default().maxSets(1).pPoolSizes(z);LongBuffer h=s.longs(0);check(vkCreateDescriptorPool(device,i,null,h),"interpolation pool");return h.get(0);}
    private long interpolationDescriptorSet(MemoryStack s){VkDescriptorSetAllocateInfo i=VkDescriptorSetAllocateInfo.calloc(s).sType$Default().descriptorPool(interpolationDescriptorPool).pSetLayouts(s.longs(interpolationDescriptorLayout));LongBuffer h=s.longs(0);check(vkAllocateDescriptorSets(device,i,h),"interpolation set");return h.get(0);}
    private void interpolationDescriptors(MemoryStack s){NativeBuffer[] bs={results,interpolatedInputs,interpolationMetadata,graphCoordinates,interpolationOutput};VkWriteDescriptorSet.Buffer w=VkWriteDescriptorSet.calloc(bs.length,s);for(int i=0;i<bs.length;i++){VkDescriptorBufferInfo.Buffer bi=VkDescriptorBufferInfo.calloc(1,s).buffer(bs[i].buffer).range(bs[i].size);w.get(i).sType$Default().dstSet(interpolationDescriptorSet).dstBinding(i).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).pBufferInfo(bi);}vkUpdateDescriptorSets(device,w,null);}
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
    private long biomeSearchDescriptorLayout(MemoryStack s){VkDescriptorSetLayoutBinding.Buffer b=VkDescriptorSetLayoutBinding.calloc(3,s);for(int i=0;i<3;i++)b.get(i).binding(i).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);VkDescriptorSetLayoutCreateInfo i=VkDescriptorSetLayoutCreateInfo.calloc(s).sType$Default().pBindings(b);LongBuffer h=s.longs(0);check(vkCreateDescriptorSetLayout(device,i,null,h),"biome search layout");return h.get(0);}
    private long biomeSearchPipelineLayout(MemoryStack s){VkPushConstantRange.Buffer r=VkPushConstantRange.calloc(1,s).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT).size(12);VkPipelineLayoutCreateInfo i=VkPipelineLayoutCreateInfo.calloc(s).sType$Default().pSetLayouts(s.longs(biomeSearchDescriptorLayout)).pPushConstantRanges(r);LongBuffer h=s.longs(0);check(vkCreatePipelineLayout(device,i,null,h),"biome search pipeline layout");return h.get(0);}
    private long biomeSearchDescriptorPool(MemoryStack s){VkDescriptorPoolSize.Buffer z=VkDescriptorPoolSize.calloc(1,s).type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(3);VkDescriptorPoolCreateInfo i=VkDescriptorPoolCreateInfo.calloc(s).sType$Default().maxSets(1).pPoolSizes(z);LongBuffer h=s.longs(0);check(vkCreateDescriptorPool(device,i,null,h),"biome search pool");return h.get(0);}
    private long biomeSearchDescriptorSet(MemoryStack s){VkDescriptorSetAllocateInfo i=VkDescriptorSetAllocateInfo.calloc(s).sType$Default().descriptorPool(biomeSearchDescriptorPool).pSetLayouts(s.longs(biomeSearchDescriptorLayout));LongBuffer h=s.longs(0);check(vkAllocateDescriptorSets(device,i,h),"biome search set");return h.get(0);}
    private void biomeSearchDescriptors(MemoryStack s){NativeBuffer[] bs={aquiferNearestInputs,biomeTreeBuffer,interpolationOutput};VkWriteDescriptorSet.Buffer w=VkWriteDescriptorSet.calloc(bs.length,s);for(int i=0;i<bs.length;i++){VkDescriptorBufferInfo.Buffer bi=VkDescriptorBufferInfo.calloc(1,s).buffer(bs[i].buffer).range(bs[i].size);w.get(i).sType$Default().dstSet(biomeSearchDescriptorSet).dstBinding(i).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).pBufferInfo(bi);}vkUpdateDescriptorSets(device,w,null);}
    private void aquiferNearestDescriptors(MemoryStack s){NativeBuffer[] bs={aquiferNearestInputs,aquiferNearestOutput};VkWriteDescriptorSet.Buffer w=VkWriteDescriptorSet.calloc(2,s);for(int i=0;i<2;i++){VkDescriptorBufferInfo.Buffer bi=VkDescriptorBufferInfo.calloc(1,s).buffer(bs[i].buffer).range(bs[i].size);w.get(i).sType$Default().dstSet(aquiferNearestDescriptorSet).dstBinding(i).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).pBufferInfo(bi);}vkUpdateDescriptorSets(device,w,null);}
    private long aquiferSubstancePipelineLayout(MemoryStack s){VkPushConstantRange.Buffer r=VkPushConstantRange.calloc(1,s).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT).size(4);VkPipelineLayoutCreateInfo i=VkPipelineLayoutCreateInfo.calloc(s).sType$Default().pSetLayouts(s.longs(aquiferNearestDescriptorLayout)).pPushConstantRanges(r);LongBuffer h=s.longs(0);check(vkCreatePipelineLayout(device,i,null,h),"aquifer substance pipeline layout");return h.get(0);}
    private long aquiferFluidStatusPipelineLayout(MemoryStack s){VkPushConstantRange.Buffer r=VkPushConstantRange.calloc(1,s).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT).size(16);VkPipelineLayoutCreateInfo i=VkPipelineLayoutCreateInfo.calloc(s).sType$Default().pSetLayouts(s.longs(aquiferNearestDescriptorLayout)).pPushConstantRanges(r);LongBuffer h=s.longs(0);check(vkCreatePipelineLayout(device,i,null,h),"aquifer fluid-status pipeline layout");return h.get(0);}
    private long aquiferDecisionChainPipelineLayout(MemoryStack s){VkPushConstantRange.Buffer r=VkPushConstantRange.calloc(1,s).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT).size(16);VkPipelineLayoutCreateInfo i=VkPipelineLayoutCreateInfo.calloc(s).sType$Default().pSetLayouts(s.longs(aquiferNearestDescriptorLayout)).pPushConstantRanges(r);LongBuffer h=s.longs(0);check(vkCreatePipelineLayout(device,i,null,h),"aquifer decision-chain pipeline layout");return h.get(0);}
    private long aquiferSubstancePipeline(MemoryStack s){return aquiferPipeline(aquiferSubstanceShader,aquiferSubstancePipelineLayout,s,"aquifer substance pipeline");}
    private long aquiferPipeline(long module,long layout,MemoryStack s,String label){VkPipelineShaderStageCreateInfo st=VkPipelineShaderStageCreateInfo.calloc(s).sType$Default().stage(VK_SHADER_STAGE_COMPUTE_BIT).module(module).pName(s.UTF8("main"));VkComputePipelineCreateInfo.Buffer i=VkComputePipelineCreateInfo.calloc(1,s).sType$Default().stage(st).layout(layout);LongBuffer h=s.longs(0);check(vkCreateComputePipelines(device,VK_NULL_HANDLE,i,null,h),label);return h.get(0);}
    private long commandPool(MemoryStack s){VkCommandPoolCreateInfo i=VkCommandPoolCreateInfo.calloc(s).sType$Default().flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT).queueFamilyIndex(queueFamily);LongBuffer h=s.longs(0);check(vkCreateCommandPool(device,i,null,h),"command pool");return h.get(0);}
    private long createTimestampQueryPool(MemoryStack stack) {
        if (!GPU_TIMING_ENABLED) return 0L;
        IntBuffer count = stack.ints(0);
        vkGetPhysicalDeviceQueueFamilyProperties(physical, count, null);
        VkQueueFamilyProperties.Buffer families = VkQueueFamilyProperties.malloc(count.get(0), stack);
        vkGetPhysicalDeviceQueueFamilyProperties(physical, count, families);
        timestampValidBits = families.get(queueFamily).timestampValidBits();
        if (timestampValidBits <= 0) return 0L;
        VkPhysicalDeviceProperties properties = VkPhysicalDeviceProperties.calloc(stack);
        vkGetPhysicalDeviceProperties(physical, properties);
        timestampPeriodNanos = properties.limits().timestampPeriod();
        if (!(timestampPeriodNanos > 0.0) || !Double.isFinite(timestampPeriodNanos)) return 0L;
        VkQueryPoolCreateInfo create = VkQueryPoolCreateInfo.calloc(stack).sType$Default()
                .queryType(VK_QUERY_TYPE_TIMESTAMP).queryCount(6);
        LongBuffer handle = stack.longs(0);
        int result = vkCreateQueryPool(device, create, null, handle);
        return result == VK_SUCCESS ? handle.get(0) : 0L;
    }

    private void beginGpuTimestamp() {
        if (timestampQueryPool == 0L) return;
        vkCmdResetQueryPool(commandBuffer, timestampQueryPool, 0, 6);
        writeGpuTimestamp(0, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT);
    }

    private void endGpuTimestamp() {
        writeGpuTimestamp(1, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT);
    }

    private void beginTerrainGpuTimestamps() {
        if (timestampQueryPool == 0L) return;
        vkCmdResetQueryPool(commandBuffer, timestampQueryPool, 0, 6);
        writeGpuTimestamp(0, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
    }

    private void writeGpuTimestamp(int query, int stage) {
        if (timestampQueryPool != 0L)
            vkCmdWriteTimestamp(commandBuffer, stage, timestampQueryPool, query);
    }

    private long readGpuTimestamp(MemoryStack stack) {
        if (timestampQueryPool == 0L) return -1L;
        ByteBuffer results = stack.malloc(2 * Long.BYTES).order(ByteOrder.nativeOrder());
        int status = vkGetQueryPoolResults(device, timestampQueryPool, 0, 2, results,
                Long.BYTES, VK_QUERY_RESULT_64_BIT);
        if (status != VK_SUCCESS) return -1L;
        long elapsedTicks = results.getLong(Long.BYTES) - results.getLong(0);
        if (timestampValidBits < Long.SIZE) elapsedTicks &= (1L << timestampValidBits) - 1L;
        double elapsedNanos = elapsedTicks * timestampPeriodNanos;
        if (!Double.isFinite(elapsedNanos) || elapsedNanos < 0.0 || elapsedNanos > Long.MAX_VALUE) return -1L;
        return Math.round(elapsedNanos);
    }

    private long[] readTerrainGpuTimestamps(MemoryStack stack) {
        if (timestampQueryPool == 0L) return new long[]{-1L, -1L, -1L};
        ByteBuffer results = stack.malloc(6 * Long.BYTES).order(ByteOrder.nativeOrder());
        int status = vkGetQueryPoolResults(device, timestampQueryPool, 0, 6, results,
                Long.BYTES, VK_QUERY_RESULT_64_BIT);
        if (status != VK_SUCCESS) return new long[]{-1L, -1L, -1L};
        long[] elapsed = new long[3];
        for (int stage = 0; stage < elapsed.length; stage++) {
            long ticks = results.getLong((stage * 2 + 1) * Long.BYTES)
                    - results.getLong(stage * 2 * Long.BYTES);
            if (timestampValidBits < Long.SIZE) ticks &= (1L << timestampValidBits) - 1L;
            double nanos = ticks * timestampPeriodNanos;
            elapsed[stage] = !Double.isFinite(nanos) || nanos < 0.0 || nanos > Long.MAX_VALUE
                    ? -1L : Math.round(nanos);
        }
        return elapsed;
    }

    private VkCommandBuffer commandBuffer(MemoryStack s){VkCommandBufferAllocateInfo i=VkCommandBufferAllocateInfo.calloc(s).sType$Default().commandPool(commandPool).level(VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(1);PointerBuffer h=s.mallocPointer(1);check(vkAllocateCommandBuffers(device,i,h),"allocate command buffer");return new VkCommandBuffer(h.get(0),device);}
    private long fence(MemoryStack s){VkFenceCreateInfo i=VkFenceCreateInfo.calloc(s).sType$Default();LongBuffer h=s.longs(0);check(vkCreateFence(device,i,null,h),"create fence");return h.get(0);}
    private static void check(int r,String op){if(r!=VK_SUCCESS)throw new IllegalStateException(op+" failed: "+r);}
    private record NativeBuffer(long buffer,long memory,long size,boolean hostVisible){ByteBuffer map(VkDevice d,MemoryStack s){PointerBuffer p=s.mallocPointer(1);check(vkMapMemory(d,memory,0,size,0,p),"map");return MemoryUtil.memByteBuffer(p.get(0),(int)size);}void unmap(VkDevice d){if(hostVisible)vkUnmapMemory(d,memory);}void destroy(VkDevice d){vkDestroyBuffer(d,buffer,null);vkFreeMemory(d,memory,null);}}
    @Override public void close(){
        vkDeviceWaitIdle(device);
        CompletableFuture<float[]> unfinished = inFlightGraphRoots;
        inFlightGraphRoots = null;
        if (unfinished != null) unfinished.completeExceptionally(
                new IllegalStateException("GPU engine closed before graph completion was observed"));
        CompletableFuture<GpuTerrainBatchResult> unfinishedTerrain = inFlightTerrain;
        inFlightTerrain = null;
        if (unfinishedTerrain != null) unfinishedTerrain.completeExceptionally(
                new IllegalStateException("GPU engine closed before terrain completion was observed"));
        CompletableFuture<GpuAquiferPrefillResult> unfinishedAquifer = inFlightAquiferPrefill;
        inFlightAquiferPrefill = null;
        if (unfinishedAquifer != null) unfinishedAquifer.completeExceptionally(
                new IllegalStateException("GPU engine closed before aquifer prefill completion was observed"));
        CompletableFuture<int[]> unfinishedAquiferRows = inFlightAquiferRows;
        inFlightAquiferRows = null;
        inFlightAquiferRowStage = null;
        if (unfinishedAquiferRows != null) unfinishedAquiferRows.completeExceptionally(
                new IllegalStateException("GPU engine closed before aquifer decision completion was observed"));
        for (NativeBuffer buffer : persistentMappings.keySet()) buffer.unmap(device);
        vkDestroyFence(device, fence, null);
        if (timestampQueryPool != 0L) vkDestroyQueryPool(device, timestampQueryPool, null);
        vkDestroyCommandPool(device, commandPool, null);
        vkDestroyDescriptorPool(device, descriptorPool, null);
        vkDestroyPipeline(device, pipeline, null);
        vkDestroyPipelineLayout(device, pipelineLayout, null);
        vkDestroyDescriptorSetLayout(device, descriptorLayout, null);
        vkDestroyShaderModule(device, shader, null);
        vkDestroyPipeline(device, graphPipeline, null);
        vkDestroyPipeline(device, gridPipeline, null);
        vkDestroyPipeline(device, blockGraphPipeline, null);
        vkDestroyShaderModule(device, blockGraphShader, null);
        vkDestroyPipelineLayout(device, graphPipelineLayout, null);
        vkDestroyDescriptorSetLayout(device, graphDescriptorLayout, null);
        vkDestroyDescriptorPool(device, graphDescriptorPool, null);
        vkDestroyShaderModule(device, graphShader, null);
        vkDestroyPipeline(device, interpolationPipeline, null);
        vkDestroyPipelineLayout(device, interpolationPipelineLayout, null);
        vkDestroyDescriptorSetLayout(device, interpolationDescriptorLayout, null);
        vkDestroyDescriptorPool(device, interpolationDescriptorPool, null);
        vkDestroyShaderModule(device, interpolationShader, null);
        vkDestroyPipeline(device, aquiferPressurePipeline, null);
        vkDestroyPipelineLayout(device, aquiferPressurePipelineLayout, null);
        vkDestroyDescriptorSetLayout(device, aquiferPressureDescriptorLayout, null);
        vkDestroyDescriptorPool(device, aquiferPressureDescriptorPool, null);
        vkDestroyShaderModule(device, aquiferPressureShader, null);
        vkDestroyPipeline(device, aquiferNearestPipeline, null);
        vkDestroyPipelineLayout(device, aquiferNearestPipelineLayout, null);
        vkDestroyDescriptorSetLayout(device, aquiferNearestDescriptorLayout, null);
        vkDestroyDescriptorPool(device, aquiferNearestDescriptorPool, null);
        vkDestroyShaderModule(device, aquiferNearestShader, null);
        vkDestroyPipeline(device, aquiferSubstancePipeline, null);
        vkDestroyPipelineLayout(device, aquiferSubstancePipelineLayout, null);
        vkDestroyShaderModule(device, aquiferSubstanceShader, null);
        vkDestroyPipeline(device, oreVeinPipeline, null);
        vkDestroyPipelineLayout(device, oreVeinPipelineLayout, null);
        vkDestroyShaderModule(device, oreVeinShader, null);
        vkDestroyPipeline(device, aquiferFluidStatusPipeline, null);
        vkDestroyPipelineLayout(device, aquiferFluidStatusPipelineLayout, null);
        vkDestroyShaderModule(device, aquiferFluidStatusShader, null);
        vkDestroyPipeline(device, aquiferDecisionChainPipeline, null);
        vkDestroyPipelineLayout(device, aquiferDecisionChainPipelineLayout, null);
        vkDestroyShaderModule(device, aquiferDecisionChainShader, null);
        vkDestroyPipeline(device, biomeSearchPipeline, null);
        vkDestroyPipelineLayout(device, biomeSearchPipelineLayout, null);
        vkDestroyDescriptorSetLayout(device, biomeSearchDescriptorLayout, null);
        vkDestroyDescriptorPool(device, biomeSearchDescriptorPool, null);
        vkDestroyShaderModule(device, biomeSearchShader, null);
        coordinates.destroy(device);
        graphCoordinates.destroy(device);
        graphNodes.destroy(device);
        graphSplinePoints.destroy(device);
        graphBlendedMetadata.destroy(device);
        graphRoots.destroy(device);
        graphMthSinTable.destroy(device);
        interpolatedInputs.destroy(device);
        interpolatorMap.destroy(device);
        interpolationMetadata.destroy(device);
        interpolationOutput.destroy(device);
        aquiferPressureInputs.destroy(device);
        aquiferNearestInputs.destroy(device);
        aquiferNearestOutput.destroy(device);
        biomeTreeBuffer.destroy(device);
        results.destroy(device);
        permutations.destroy(device);
        octaves.destroy(device);
        samplerMetadata.destroy(device);
        if (ownsDevice) {
            vkDestroyDevice(device, null);
            vkDestroyInstance(instance, null);
        }
    }
}
