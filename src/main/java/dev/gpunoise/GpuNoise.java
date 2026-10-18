package dev.gpunoise;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseSettings;
import java.util.LinkedHashSet;
import java.util.Set;

/** Entry point for GPU Noise. The first milestone is a safe, CPU-backed noise boundary. */
@Mod(GpuNoise.MOD_ID)
public final class GpuNoise {
    public static final String MOD_ID = "gpunoise";
    public static final Logger LOGGER = LogUtils.getLogger();
    private GpuDevice warmedDevice;

    public GpuNoise(IEventBus modEventBus, ModContainer modContainer) {
        // Deliberately server-side only: no client/rendering hooks are registered.
        // Probe and allocate the Vulkan runtime while mods are still being
        // registered.  Waiting for ServerStarting is too late: NeoForge may
        // already have begun preparing the spawn region by then.
        // Config values are not loaded until after mod construction. Use the
        // safe automatic preference for this early probe; the configured
        // preference is applied by the pre-worldgen retry if needed.
        warmGpuBeforeWorldgen(GpuNoiseConfig.GpuVendorPreference.AUTO);
        NeoForge.EVENT_BUS.addListener(this::onServerAboutToStart);
        NeoForge.EVENT_BUS.addListener(this::onServerStarting);
        NeoForge.EVENT_BUS.addListener(this::onServerStarted);
        NeoForge.EVENT_BUS.addListener(this::onServerStopping);
        modContainer.registerConfig(ModConfig.Type.COMMON, GpuNoiseConfig.SPEC);
    }

    private void onServerStopping(ServerStoppingEvent event) {
        GpuTerrainRuntime.stop();
        warmedDevice = null;
    }

    private void onServerStarted(ServerStartedEvent event) {
        if (Boolean.getBoolean("gpunoise.vulkanSmoke")) {
            LOGGER.info("Running Vulkan CI smoke inside the initialized NeoForge server");
            try {
                VulkanCiSmoke.main(new String[0]);
                LOGGER.info("GPU_NOISE_VULKAN_SMOKE_PASSED");
            } catch (Exception exception) {
                throw new IllegalStateException("Vulkan CI smoke failed", exception);
            }
        }
    }

    private void onServerAboutToStart(ServerAboutToStartEvent event) {
        if (warmedDevice == null) {
            warmGpuBeforeWorldgen(GpuNoiseConfig.GPU_VENDOR.get());
        }
        if (warmedDevice != null && event.getServer().overworld() != null) {
            installWorldGraph(event.getServer());
        }
    }

    private void onServerStarting(ServerStartingEvent event) {
        if (warmedDevice == null) warmGpuBeforeWorldgen(GpuNoiseConfig.GPU_VENDOR.get());
        if (warmedDevice == null || event.getServer().overworld() == null
                || GpuTerrainRuntime.terrainInputsAvailable()) return;
        installWorldGraph(event.getServer());
    }

    private void warmGpuBeforeWorldgen(GpuNoiseConfig.GpuVendorPreference preference) {
        if (warmedDevice != null) return;
        if (!GpuTerrainRuntime.c2meDfcAvailable()) {
            LOGGER.info("GPU Noise inactive: C2ME DFC is not installed; using vanilla world generation.");
            return;
        }
        // Integrated servers already receive LWJGL from Minecraft. The slim
        // artifact uses those classes; standalone servers should use the
        // -all artifact. Do this before touching GpuProbe so missing Vulkan
        // bindings become a clean no-op instead of a server crash.
        if (!vulkanBindingsPresent()) {
            LOGGER.info("GPU Noise inactive: Vulkan/LWJGL bindings are not available; vanilla terrain remains unchanged.");
            return;
        }
        GpuDevice device = GpuProbe.findPreferredDevice(preference);
        if (device == null) {
            LOGGER.info("GPU Noise inactive: no supported server GPU detected; vanilla terrain remains unchanged.");
            return;
        }

        if (!VulkanComputeProbe.verify(device)) {
            LOGGER.info("GPU Noise inactive: selected GPU cannot create a Vulkan compute queue; vanilla terrain remains unchanged.");
            return;
        }

        GpuTerrainRuntime.start(device);
        warmedDevice = device;
        LOGGER.info("GPU Noise Vulkan runtime warmed during mod registration; selected {} ({}).",
                device.name(), device.vendorName());
    }

    private void installWorldGraph(MinecraftServer server) {
        GpuDevice device = warmedDevice;
        try {
            if (!(server.overworld().getChunkSource().getGenerator()
                    instanceof NoiseBasedChunkGenerator)) {
                LOGGER.info("GPU terrain skipped: overworld generator is not NoiseBasedChunkGenerator.");
                return;
            }
            var router = server.overworld().getChunkSource().randomState().router();
            GpuDensityGraph installedGraph = null;
            // The complete router is useful for a startup parity diagnostic,
            // but it is not the active terrain contract. Mods may add custom
            // functions to unrelated roots (veins, aquifer helpers, etc.).
            // Do not disable supported terrain roots merely because that
            // diagnostic graph cannot represent an unrelated custom branch.
            try {
                GpuDensityGraph fullGraph = GpuDensityGraphLowerer.lower(GpuDensityGraphCompiler.compile(router));
                GpuDensityGraphBuffer.Packed packedGraph = GpuDensityGraphBuffer.pack(fullGraph);
                LOGGER.info("GPU density graph discovered: {} nodes, {} router roots, {} noise samplers; packed {} bytes for GPU lowering.",
                        fullGraph.nodes().size(), fullGraph.routerRoots().length, fullGraph.noises().size(), packedGraph.nodes().capacity());
                if (VulkanComputeProbe.supportsFloat64(device)) {
                    VulkanBootstrapThread.callUnchecked(() -> {
                        GpuTerrainRuntime.verifyGraph(fullGraph, router);
                        return null;
                    });
                } else LOGGER.info("GPU density graph verification skipped: selected GPU lacks shaderFloat64.");
                VulkanBootstrapThread.callUnchecked(() -> {
                    GpuTerrainRuntime.verifyTerrainGraph(fullGraph, router);
                    return null;
                });
            } catch (RuntimeException diagnosticFailure) {
                LOGGER.info("GPU full-router parity diagnostic skipped: {}", diagnosticFailure.getMessage());
            }

            try {
                GpuDensityGraph sourceTerrainGraph = GpuDensityGraphCompiler.compileTerrain(router);
                GpuDensityGraph terrainGraph = GpuDensityGraphLowerer.lower(sourceTerrainGraph);
                final GpuDensityGraph validationGraph = terrainGraph;
                String[] rootRejections = terrainGraph.unsupportedRouterRootReasons();
                for (int root = 0; root < rootRejections.length; root++) {
                    if (rootRejections[root] != null) {
                        LOGGER.warn("GPU terrain root '{}' remains on vanilla Java: {}",
                                GpuTerrainRoots.name(root), rootRejections[root]);
                    }
                }
                LOGGER.info("GPU terrain runtime graph: {} nodes, {} required terrain/aquifer roots.",
                        terrainGraph.nodes().size(), terrainGraph.routerRoots().length);
                VulkanBootstrapThread.callUnchecked(() -> {
                    GpuTerrainRuntime.verifyCompactTerrainGraph(validationGraph, router);
                    return null;
                });
                GpuTerrainRuntime.installTerrainGraph(validationGraph, router);
                NoiseSettings terrainSettings = server.overworld().getChunkSource().getGenerator()
                        instanceof NoiseBasedChunkGenerator noiseGenerator
                        ? noiseGenerator.generatorSettings().value().noiseSettings()
                                .clampToHeightAccessor(server.overworld())
                        : null;
                if (terrainSettings != null) {
                    VulkanBootstrapThread.callUnchecked(() -> {
                        GpuTerrainRuntime.verifyTerrainGrid(validationGraph, router, terrainSettings);
                        return null;
                    });
                }
                int verifiedRootMask = GpuTerrainRuntime.supportedTerrainRootMask();
                GpuDensityGraph activeTerrainGraph = validationGraph;
                if (verifiedRootMask != 0
                        && verifiedRootMask != validationGraph.supportedRouterRootMask()) {
                    activeTerrainGraph = GpuDensityGraphLowerer.lowerVerifiedRoots(
                            sourceTerrainGraph, verifiedRootMask);
                    LOGGER.info("GPU terrain parity pruning: nodes {} -> {}, verifiedRoots=0x{}.",
                            validationGraph.nodes().size(), activeTerrainGraph.nodes().size(),
                            Integer.toHexString(verifiedRootMask));
                    GpuTerrainRuntime.installTerrainGraph(activeTerrainGraph, router);
                }
                if (verifiedRootMask != 0) {
                    if (terrainSettings != null) GpuTerrainRuntime.warmTerrainGpu(terrainSettings);
                    installedGraph = activeTerrainGraph;
                    LOGGER.info("GPU terrain roots enabled: {}/{} passed runtime parity checks; mask=0x{}.",
                            Integer.bitCount(verifiedRootMask), GpuTerrainRoots.COUNT,
                            Integer.toHexString(verifiedRootMask));
                } else {
                    LOGGER.warn("GPU terrain graph compiled, but no roots passed parity; terrain remains CPU-owned.");
                }
            } catch (RuntimeException terrainFailure) {
                LOGGER.warn("GPU terrain graph unavailable; vanilla terrain remains enabled: {}", terrainFailure.getMessage());
            }
            if (installedGraph != null) {
                LOGGER.info("GPU terrain graph installed with {} terrain/aquifer roots.", GpuTerrainRoots.COUNT);
            }
        } catch (RuntimeException unsupported) {
            LOGGER.warn("GPU density graph batch is not enabled yet", unsupported);
        }
        VulkanBootstrapThread.callUnchecked(() -> {
            VulkanNoiseBenchmark.run(device);
            return null;
        });

        LOGGER.info("GPU Noise selected {} ({}) using {} preference. Terrain uses global GPU graph batches; leaf noise hook is disabled.",
                device.name(), device.vendorName(), GpuNoiseConfig.GPU_VENDOR.get());
    }

    private static boolean vulkanBindingsPresent() {
        // Integrated and dedicated servers can expose LWJGL through different
        // NeoForge/module class loaders, so do not inspect only the mod loader.
        Set<ClassLoader> loaders = new LinkedHashSet<>();
        addLoaderChain(loaders, GpuNoise.class.getClassLoader());
        addLoaderChain(loaders, Thread.currentThread().getContextClassLoader());
        addLoaderChain(loaders, ClassLoader.getSystemClassLoader());
        try {
            addLoaderChain(loaders, ModuleLayer.boot().findLoader("org.lwjgl"));
        } catch (IllegalArgumentException ignored) {
            // NeoForge normally puts LWJGL in its own module layer.
        }

        for (ClassLoader loader : loaders) {
            try {
                Class.forName("org.lwjgl.system.Configuration", false, loader);
                Class.forName("org.lwjgl.vulkan.VK", false, loader);
                Class.forName("org.lwjgl.vulkan.VK10", false, loader);
                LOGGER.info("Vulkan bindings found through {}.", loader);
                return true;
            } catch (ClassNotFoundException | LinkageError ignored) {
                // Try the next class loader.
            }
        }
        return false;
    }

    private static void addLoaderChain(Set<ClassLoader> loaders, ClassLoader loader) {
        while (loader != null) {
            loaders.add(loader);
            loader = loader.getParent();
        }
    }
}
