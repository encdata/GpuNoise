package dev.gpunoise;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import java.util.concurrent.ThreadLocalRandom;

/** Entry point for GPU Noise. The first milestone is a safe, CPU-backed noise boundary. */
@Mod(GpuNoise.MOD_ID)
public final class GpuNoise {
    public static final String MOD_ID = "gpunoise";
    public static final Logger LOGGER = LogUtils.getLogger();

    public GpuNoise(IEventBus modEventBus, ModContainer modContainer) {
        // Deliberately server-side only: no client/rendering hooks are registered.
        NeoForge.EVENT_BUS.addListener(this::onServerStarting);
        NeoForge.EVENT_BUS.addListener(this::onServerStarted);
        modContainer.registerConfig(ModConfig.Type.COMMON, GpuNoiseConfig.SPEC);
    }

    private void onServerStarted(ServerStartedEvent event) {
        if (!Boolean.getBoolean("gpunoise.chunkyBenchmark")) return;
        var server = event.getServer();
        server.execute(() -> {
            var random = ThreadLocalRandom.current();
            int centerX = Math.floorDiv(random.nextInt(-1_800_000, 1_800_001), 16) * 16;
            int centerZ = Math.floorDiv(random.nextInt(-1_800_000, 1_800_001), 16) * 16;
            var source = server.createCommandSourceStack().withPermission(4);
            server.getCommands().performPrefixedCommand(source, "chunky world minecraft:overworld");
            server.getCommands().performPrefixedCommand(source, "chunky center " + centerX + " " + centerZ);
            // 800 blocks gives a 101x101 chunk square: approximately 10,000
            // chunks while keeping the benchmark finite and reproducible.
            server.getCommands().performPrefixedCommand(source, "chunky radius 800");
            server.getCommands().performPrefixedCommand(source, "chunky start");
            LOGGER.info("Started development Chunky benchmark: approximately 10,201 chunks centered at {},{}.", centerX, centerZ);
        });
    }

    private void onServerStarting(ServerStartingEvent event) {
        // Integrated servers already receive LWJGL from Minecraft. The slim
        // artifact uses those classes; standalone servers should use the
        // -all artifact. Do this before touching GpuProbe so missing Vulkan
        // bindings become a clean no-op instead of a server crash.
        if (!vulkanBindingsPresent()) {
            LOGGER.info("GPU Noise inactive: Vulkan/LWJGL bindings are not available; vanilla terrain remains unchanged.");
            return;
        }
        GpuDevice device = GpuProbe.findPreferredDevice(GpuNoiseConfig.GPU_VENDOR.get());
        if (device == null) {
            LOGGER.info("GPU Noise inactive: no supported server GPU detected; vanilla terrain remains unchanged.");
            return;
        }

        if (!VulkanComputeProbe.verify(device)) {
            LOGGER.info("GPU Noise inactive: selected GPU cannot create a Vulkan compute queue; vanilla terrain remains unchanged.");
            return;
        }

        GpuTerrainRuntime.start(device);
        try {
            var router = event.getServer().overworld().getChunkSource().randomState().router();
            int fullNodes = -1;
            try {
                GpuDensityGraph graph = GpuDensityGraphLowerer.lower(GpuDensityGraphCompiler.compile(router));
                GpuDensityGraphBuffer.Packed packedGraph = GpuDensityGraphBuffer.pack(graph);
                fullNodes = graph.nodes().size();
                LOGGER.info("GPU density graph discovered: {} nodes, {} router roots, {} noise samplers; packed {} bytes for GPU lowering.",
                        graph.nodes().size(), graph.routerRoots().length, graph.noises().size(), packedGraph.nodes().capacity());
                if (VulkanComputeProbe.supportsFloat64(device)) GpuTerrainRuntime.verifyGraph(graph, router);
                else LOGGER.info("GPU density graph verification skipped: selected GPU lacks shaderFloat64.");
            } catch (RuntimeException fullGraphFailure) {
                LOGGER.warn("Full router parity graph unavailable; continuing with reduced terrain graph.", fullGraphFailure);
            }
            GpuDensityGraph terrainGraph = GpuDensityGraphLowerer.lower(GpuDensityGraphCompiler.compileTerrain(
                    router));
            LOGGER.info("GPU terrain graph reduced to {} nodes from {} full-graph nodes.",
                    terrainGraph.nodes().size(), fullNodes);
            GpuTerrainRuntime.installTerrainGraph(terrainGraph);
            GpuTerrainRuntime.verifyTerrainGraph(terrainGraph, router);
            LOGGER.warn("GPU chunk writer enabled without parity gating; parity remains diagnostic only.");
        } catch (RuntimeException unsupported) {
            LOGGER.warn("GPU density graph batch is not enabled yet", unsupported);
        }
        VulkanNoiseBenchmark.run(device);

        LOGGER.info("GPU Noise selected {} ({}) using {} preference. Vanilla noise fill hook is GPU-enabled.",
                device.name(), device.vendorName(), GpuNoiseConfig.GPU_VENDOR.get());
    }

    private static boolean vulkanBindingsPresent() {
        try {
            ClassLoader loader = GpuNoise.class.getClassLoader();
            Class.forName("org.lwjgl.system.Configuration", false, loader);
            Class.forName("org.lwjgl.vulkan.VK", false, loader);
            Class.forName("org.lwjgl.vulkan.VK10", false, loader);
            return true;
        } catch (ClassNotFoundException | LinkageError missing) {
            return false;
        }
    }
}
