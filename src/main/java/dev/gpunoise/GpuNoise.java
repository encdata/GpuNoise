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
    private boolean worldGraphSetupAttempted;
    /** Prevents the ServerStarting fallback from replaying a completed startup probe. */
    private boolean worldPreparationAttempted;

    public GpuNoise(IEventBus modEventBus, ModContainer modContainer) {
        LOGGER.info("GPU Noise mod constructor initialized.");
        // Defer Vulkan probing until ServerAboutToStart. That event still
        // precedes spawn generation, while an integrated client can render
        // its loading screen and show the terrain setup status.
        NeoForge.EVENT_BUS.addListener(this::onServerAboutToStart);
        NeoForge.EVENT_BUS.addListener(this::onServerStarting);
        NeoForge.EVENT_BUS.addListener(this::onServerStarted);
        NeoForge.EVENT_BUS.addListener(this::onServerStopping);
        modContainer.registerConfig(ModConfig.Type.COMMON, GpuNoiseConfig.SPEC);
    }

    private void onServerStopping(ServerStoppingEvent event) {
        GpuTerrainRuntime.stop();
        warmedDevice = null;
        worldGraphSetupAttempted = false;
        worldPreparationAttempted = false;
        GpuCompileStatus.clear();
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
        if (worldPreparationAttempted) return;
        worldPreparationAttempted = true;
        GpuCompileStatus.begin("Preparing world terrain");
        if (Boolean.getBoolean("gpunoise.vulkanSmoke")) {
            VulkanCiSmoke.prepareTranslatorIntegration();
        }
        if (Boolean.getBoolean("gpunoise.forceCpuTerrain")) {
            LOGGER.info("GPU terrain graph setup skipped for the forced-CPU reference run.");
            GpuCompileStatus.clear();
            return;
        }
        if (warmedDevice == null) {
            warmGpuBeforeWorldgen(GpuNoiseConfig.GPU_VENDOR.get());
        }
        if (event.getServer().overworld() == null) {
            // Preserve the later ServerStarting fallback if this lifecycle
            // event arrives before NeoForge has created the overworld.
            worldPreparationAttempted = false;
            GpuCompileStatus.clear();
            return;
        }
        if (warmedDevice != null) {
            installWorldGraph(event.getServer());
        }
    }

    private void onServerStarting(ServerStartingEvent event) {
        if (Boolean.getBoolean("gpunoise.forceCpuTerrain") || worldPreparationAttempted) return;
        // ServerAboutToStart normally sees a ready overworld. If it did not,
        // this is the fallback event; leave the attempt flag clear when the
        // level is still unavailable so another eligible lifecycle callback
        // can retry instead of permanently consuming the setup attempt.
        if (event.getServer().overworld() == null) {
            LOGGER.debug("Deferring GPU terrain setup: overworld is not available yet.");
            GpuCompileStatus.clear();
            return;
        }
        worldPreparationAttempted = true;
        GpuCompileStatus.begin("Preparing world terrain");
        if (warmedDevice == null) warmGpuBeforeWorldgen(GpuNoiseConfig.GPU_VENDOR.get());
        if (worldGraphSetupAttempted || warmedDevice == null) {
            GpuCompileStatus.clear();
            return;
        }
        installWorldGraph(event.getServer());
    }

    private void warmGpuBeforeWorldgen(GpuNoiseConfig.GpuVendorPreference preference) {
        if (warmedDevice != null) return;
        if (!GpuTerrainRuntime.c2meDfcAvailable()) {
            LOGGER.info("GPU Noise inactive: C2ME DFC is not installed; using vanilla world generation.");
            GpuCompileStatus.clear();
            return;
        }
        // Integrated servers already receive LWJGL from Minecraft. The slim
        // artifact uses those classes; standalone servers should use the
        // -all artifact. Do this before touching GpuProbe so missing Vulkan
        // bindings become a clean no-op instead of a server crash.
        if (!vulkanBindingsPresent()) {
            LOGGER.info("GPU Noise inactive: Vulkan/LWJGL bindings are not available; vanilla terrain remains unchanged.");
            GpuCompileStatus.clear();
            return;
        }
        GpuCompileStatus.update("Finding a Vulkan compute device");
        var candidates = GpuProbe.findPreferredDevices(preference);
        if (candidates.isEmpty()) {
            LOGGER.info("GPU Noise inactive: no supported server GPU detected; vanilla terrain remains unchanged.");
            GpuCompileStatus.clear();
            return;
        }
        java.util.ArrayList<GpuDevice> usableDevices = new java.util.ArrayList<>(candidates.size());
        for (GpuDevice candidate : candidates) {
            GpuCompileStatus.update("Checking Vulkan device: " + candidate.name());
            if (!VulkanComputeProbe.verify(candidate)) {
                LOGGER.warn("Vulkan compute setup failed on {}; trying the next supported device.", candidate.name());
                continue;
            }
            usableDevices.add(candidate);
        }
        if (usableDevices.isEmpty() || !GpuTerrainRuntime.start(usableDevices)) {
            LOGGER.info("GPU Noise inactive: no discovered GPU could create a Vulkan compute device; vanilla terrain remains unchanged.");
            GpuCompileStatus.clear();
            return;
        }

        GpuDevice device = GpuTerrainRuntime.selectedDevice();
        if (device == null) {
            LOGGER.error("GPU Vulkan device pool started without a primary device; disabling GPU terrain.");
            GpuTerrainRuntime.stop();
            GpuCompileStatus.clear();
            return;
        }
        warmedDevice = device;
        LOGGER.info("GPU Noise Vulkan runtime warmed during mod registration; primary device is {} ({}), {} Vulkan device(s) are available to the async terrain submission pool.",
                device.name(), device.vendorName(), GpuTerrainRuntime.activeDeviceCount());
    }

    private void installWorldGraph(MinecraftServer server) {
        if (worldGraphSetupAttempted) return;
        var overworld = server.overworld();
        if (overworld == null) {
            LOGGER.debug("Deferring GPU terrain setup: overworld disappeared before graph installation.");
            worldPreparationAttempted = false;
            return;
        }
        worldGraphSetupAttempted = true;
        GpuDevice device = warmedDevice;
        try {
            GpuCompileStatus.update("Translating the active terrain generator");
            if (!(overworld.getChunkSource().getGenerator()
                    instanceof NoiseBasedChunkGenerator)) {
                LOGGER.info("GPU terrain skipped: overworld generator is not NoiseBasedChunkGenerator.");
                return;
            }
            var router = overworld.getChunkSource().randomState().router();
            GpuDensityGraph installedGraph = null;
            boolean softwareVulkan = device.deviceType()
                    == org.lwjgl.vulkan.VK10.VK_PHYSICAL_DEVICE_TYPE_CPU;
            // The complete router is useful as an optional diagnostic, but it
            // is not the active terrain contract. Mods may add unrelated or
            // unsupported roots, and interpreting them on software Vulkan can
            // hold up server startup. The compact terrain graph below remains
            // responsible for deciding which GPU roots pass parity.
            if (softwareVulkan) {
                LOGGER.info("Skipping full-router compilation and parity diagnostics on the software Vulkan device; "
                        + "compact terrain-root and cell-grid checks remain enabled.");
            } else {
                try {
                    GpuCompileStatus.update("Compiling the terrain density graph");
                    GpuDensityGraph fullGraph = GpuDensityGraphLowerer.lower(GpuDensityGraphCompiler.compile(router));
                    GpuDensityGraphBuffer.Packed packedGraph = GpuDensityGraphBuffer.pack(fullGraph);
                    LOGGER.info("GPU density graph discovered: {} nodes, {} router roots, {} noise samplers; packed {} bytes for GPU lowering.",
                            fullGraph.nodes().size(), fullGraph.routerRootCount(), fullGraph.noises().size(),
                            packedGraph.nodes().capacity());
                    if (device.shaderFloat64()) {
                        VulkanBootstrapThread.callUnchecked(() -> {
                            GpuTerrainRuntime.verifyGraph(fullGraph, router);
                            return null;
                        });
                        VulkanBootstrapThread.callUnchecked(() -> {
                            GpuTerrainRuntime.verifyTerrainGraph(fullGraph, router);
                            return null;
                        });
                    } else {
                        LOGGER.info("Full-router parity diagnostics skipped: selected GPU lacks shaderFloat64.");
                    }
                } catch (RuntimeException diagnosticFailure) {
                    LOGGER.info("GPU full-router parity diagnostic skipped: {}", diagnosticFailure.getMessage());
                }
            }

            try {
                GpuCompileStatus.update("Compiling terrain and aquifer roots");
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
                        terrainGraph.nodes().size(), terrainGraph.routerRootCount());
                GpuCompileStatus.update("Checking GPU terrain parity");
                VulkanBootstrapThread.callUnchecked(() -> {
                    GpuTerrainRuntime.verifyCompactTerrainGraph(validationGraph, router);
                    return null;
                });
                GpuTerrainRuntime.installTerrainGraph(validationGraph, router);
                NoiseSettings terrainSettings = overworld.getChunkSource().getGenerator()
                        instanceof NoiseBasedChunkGenerator noiseGenerator
                        ? noiseGenerator.generatorSettings().value().noiseSettings()
                                .clampToHeightAccessor(overworld)
                        : null;
                if (terrainSettings != null) {
                    GpuCompileStatus.update("Checking terrain grid and world height");
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
                    if (terrainSettings != null) {
                        GpuDensityGraph parityGraph = activeTerrainGraph;
                        VulkanBootstrapThread.callUnchecked(() -> {
                            GpuTerrainRuntime.verifyTerrainGrid(parityGraph, router, terrainSettings);
                            return null;
                        });
                    }
                }
                if (verifiedRootMask != 0) {
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
        } finally {
            GpuCompileStatus.clear();
        }
        try {
            GpuCompileStatus.update("Checking Vulkan noise output");
            VulkanBootstrapThread.callUnchecked(() -> {
                VulkanNoiseParityProbe.run(device);
                return null;
            });
            LOGGER.info("GPU Noise selected {} ({}) using {} preference. Terrain uses global GPU graph batches; leaf noise hook is disabled.",
                    device.name(), device.vendorName(), GpuNoiseConfig.GPU_VENDOR.get());
        } finally {
            GpuCompileStatus.clear();
        }
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
