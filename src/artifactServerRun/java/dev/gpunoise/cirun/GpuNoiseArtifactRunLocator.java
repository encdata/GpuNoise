package dev.gpunoise.cirun;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.net.URLClassLoader;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.neoforgespi.ILaunchContext;
import net.neoforged.neoforgespi.locating.IDiscoveryPipeline;
import net.neoforged.neoforgespi.locating.IModFileCandidateLocator;
import net.neoforged.neoforgespi.locating.IncompatibleFileReporting;
import net.neoforged.neoforgespi.locating.ModFileDiscoveryAttributes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Registers the distributable jar once in isolated NeoGradle client/server runs. */
public final class GpuNoiseArtifactRunLocator implements IModFileCandidateLocator {
    private static final Logger LOGGER = LoggerFactory.getLogger("GpuNoiseArtifactRunLocator");

    @Override
    public void findCandidates(ILaunchContext context, IDiscoveryPipeline pipeline) {
        boolean serverRun = Boolean.getBoolean("gpunoise.artifactBackedRun")
                && FMLLoader.getDist() == Dist.DEDICATED_SERVER;
        boolean clientRun = Boolean.getBoolean("gpunoise.clientArtifactRun")
                && FMLLoader.getDist() == Dist.CLIENT;
        if (!serverRun && !clientRun) {
            return;
        }

        String configuredPath = System.getProperty("gpunoise.releaseArtifactPath", "");
        if (configuredPath.isBlank()) {
            throw new IllegalStateException("Artifact-backed run has no gpunoise.releaseArtifactPath");
        }

        Path artifact = Paths.get(configuredPath).toAbsolutePath().normalize();
        if (!Files.isRegularFile(artifact)) {
            throw new IllegalStateException("GPU Noise release artifact does not exist: " + artifact);
        }

        if (clientRun) {
            invokePackagedClientLocator(artifact, context, pipeline);
            return;
        }

        var candidate = pipeline.addPath(artifact,
                ModFileDiscoveryAttributes.DEFAULT.withLocator(this),
                IncompatibleFileReporting.ERROR);
        if (candidate.isPresent()) {
            LOGGER.info("Registered GPU Noise release artifact once for dedicated-server discovery: {}", artifact);
        } else {
            throw new IllegalStateException("NeoForge did not create a mod candidate from " + artifact);
        }
    }

    private static void invokePackagedClientLocator(Path artifact, ILaunchContext context,
            IDiscoveryPipeline pipeline) {
        try (var artifactLoader = new URLClassLoader(
                new java.net.URL[] {artifact.toUri().toURL()}, GpuNoiseArtifactRunLocator.class.getClassLoader())) {
            Class<?> locatorClass = Class.forName(
                    "dev.gpunoise.loader.GpuNoiseDedicatedLwjglLocator", true, artifactLoader);
            var locator = (IModFileCandidateLocator) locatorClass.getConstructor().newInstance();
            locator.findCandidates(context, pipeline);
            LOGGER.info("Invoked packaged GPU Noise client locator for release artifact: {}", artifact);
        } catch (ReflectiveOperationException | java.io.IOException failure) {
            throw new IllegalStateException("Could not invoke packaged GPU Noise client locator", failure);
        }
    }
}
