package dev.gpunoise.loader;

import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.jar.JarFile;
import java.util.Locale;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.neoforgespi.ILaunchContext;
import net.neoforged.neoforgespi.locating.IDiscoveryPipeline;
import net.neoforged.neoforgespi.locating.IModFileCandidateLocator;
import net.neoforged.neoforgespi.locating.IncompatibleFileReporting;
import net.neoforged.neoforgespi.locating.ModFileDiscoveryAttributes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Adds the embedded LWJGL core to the game libraries on dedicated servers only. */
public final class GpuNoiseDedicatedLwjglLocator implements IModFileCandidateLocator {
    private static final String SERVER_LIBRARY_DIRECTORY = "META-INF/gpunoise/server-libraries/";
    private static final String[] SERVER_LIBRARIES = {
            "lwjgl-core-gpunoise-server.jar",
            "lwjgl-natives-gpunoise-server.jar"
    };
    private static final Logger LOGGER = LoggerFactory.getLogger("GpuNoiseLwjglLocator");

    @Override
    public void findCandidates(ILaunchContext context, IDiscoveryPipeline pipeline) {
        if (Boolean.getBoolean("gpunoise.artifactBackedRun")) {
            // NeoGradle CI provides a separate early locator from its library
            // source set; registering a copy here would create a second JPMS
            // module from the same release jar.
            LOGGER.debug("Skipping packaged mod re-registration in artifact-backed runServer");
            return;
        }
        if (FMLLoader.getDist() != Dist.DEDICATED_SERVER) {
            LOGGER.debug("Skipping embedded LWJGL core on client; Minecraft supplies its own copy");
            return;
        }

        Path sourceJar = resolveSourceJar();
        if (sourceJar == null) {
            // Development runs get LWJGL from localRunRuntime instead.
            LOGGER.debug("Cannot locate packaged GpuNoise jar; skipping server LWJGL extraction");
            return;
        }

        // Early service discovery has already seen sourceJar, which can make
        // the normal mods-folder path appear as a duplicate without adding it
        // as a mod. Register an identical bootstrap copy under a distinct path
        // so its @Mod lifecycle and server worldgen hooks enter mod discovery.
        Path modDiscoveryJar = copyForModDiscovery(sourceJar);
        var modCandidate = pipeline.addPath(modDiscoveryJar,
                ModFileDiscoveryAttributes.DEFAULT.withLocator(this),
                IncompatibleFileReporting.ERROR);
        if (modCandidate.isPresent()) {
            LOGGER.info("Registered packaged GPU Noise mod {} for dedicated-server discovery", modDiscoveryJar);
        } else {
            LOGGER.warn("NeoForge did not create a dedicated-server mod candidate from {}", modDiscoveryJar);
        }

        boolean lwjglCoreAvailable = lwjglCoreAvailable();
        for (String libraryName : SERVER_LIBRARIES) {
            if (libraryName.equals("lwjgl-core-gpunoise-server.jar") && lwjglCoreAvailable) {
                LOGGER.info("Using the LWJGL core already present in the dedicated server runtime");
                continue;
            }
            Path libraryJar = extractEmbeddedLibrary(sourceJar, libraryName);
            if (libraryJar == null || !Files.isRegularFile(libraryJar)) {
                LOGGER.error("Could not extract embedded server library {} from {}", libraryName, sourceJar);
                continue;
            }
            if (context.isLocated(libraryJar)) {
                LOGGER.debug("Server library is already registered: {}", libraryJar);
                continue;
            }

            try {
                var added = pipeline.addPath(libraryJar,
                        ModFileDiscoveryAttributes.DEFAULT.withLocator(this),
                        IncompatibleFileReporting.ERROR);
                if (added.isPresent()) {
                    LOGGER.info("Added bundled {} to dedicated server runtime", libraryName);
                } else {
                    LOGGER.error("NeoForge did not accept dedicated-server library {}", libraryJar);
                }
            } catch (Exception failure) {
                LOGGER.error("Failed to register dedicated-server library {}", libraryName, failure);
            }
        }
    }

    private static boolean lwjglCoreAvailable() {
        if (ModuleLayer.boot().modules().stream().anyMatch(module -> module.getPackages().contains("org.lwjgl"))) {
            return true;
        }
        try {
            Class.forName("org.lwjgl.Version", false, Thread.currentThread().getContextClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError ignored) {
            // The game module layer may not be active yet; inspect launch paths below.
        }

        for (String property : new String[] {"jdk.module.path", "jdk.module.upgrade.path", "java.class.path"}) {
            String value = System.getProperty(property, "");
            for (String entry : value.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))) {
                if (entry.isBlank()) continue;
                Path path;
                try {
                    path = Paths.get(entry);
                } catch (RuntimeException ignored) {
                    continue;
                }
                if (Files.isDirectory(path)) {
                    if (Files.isRegularFile(path.resolve("org/lwjgl/Version.class"))) return true;
                    continue;
                }
                if (!Files.isRegularFile(path)) continue;
                try (JarFile jar = new JarFile(path.toFile())) {
                    if (jar.getEntry("org/lwjgl/Version.class") != null
                            || jar.getEntry("classes/org/lwjgl/Version.class") != null) {
                        return true;
                    }
                } catch (Exception ignored) {
                    // Ignore non-jar classpath entries.
                }
            }
        }
        return false;
    }

    private static Path extractEmbeddedLibrary(Path sourceJar, String libraryName) {
        String resourcePath = SERVER_LIBRARY_DIRECTORY + libraryName;
        try (InputStream input = GpuNoiseDedicatedLwjglLocator.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (input == null) {
                LOGGER.error("Missing embedded server library resource {}", resourcePath);
                return null;
            }

            byte[] bytes = input.readAllBytes();
            String digest = sha256(bytes);
            Path modsDirectory = sourceJar.getParent();
            Path gameDirectory = modsDirectory != null && modsDirectory.getParent() != null
                    ? modsDirectory.getParent()
                    : Paths.get(".").toAbsolutePath().normalize();
            Path cacheDirectory = gameDirectory.resolve(".gpunoise/bootstrap");
            Files.createDirectories(cacheDirectory);
            String cacheName = libraryName.substring(0, libraryName.length() - ".jar".length());
            Path destination = cacheDirectory.resolve(cacheName + "-" + digest.substring(0, 16) + ".jar");
            if (Files.isRegularFile(destination) && Files.size(destination) == bytes.length) {
                return destination;
            }

            Path temporary = Files.createTempFile(cacheDirectory, "lwjgl-core-", ".jar.tmp");
            try {
                Files.write(temporary, bytes);
                try {
                    Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException ignored) {
                    Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
            return destination;
        } catch (Exception failure) {
            LOGGER.error("Failed to extract embedded server LWJGL library", failure);
            return null;
        }
    }

    private static Path copyForModDiscovery(Path sourceJar) {
        try {
            Path modsDirectory = sourceJar.getParent();
            Path gameDirectory = modsDirectory != null && modsDirectory.getParent() != null
                    ? modsDirectory.getParent()
                    : Paths.get(".").toAbsolutePath().normalize();
            Path cacheDirectory = gameDirectory.resolve(".gpunoise/bootstrap");
            Files.createDirectories(cacheDirectory);
            Path destination = cacheDirectory.resolve("gpunoise-dedicated-server.jar");
            Files.copy(sourceJar, destination, StandardCopyOption.REPLACE_EXISTING);
            return destination;
        } catch (Exception failure) {
            throw new IllegalStateException("Failed to stage GPU Noise for dedicated-server mod discovery", failure);
        }
    }

    private static Path resolveSourceJar() {
        try {
            URL location = GpuNoiseDedicatedLwjglLocator.class.getProtectionDomain().getCodeSource().getLocation();
            if (location == null) return null;
            String source = location.toExternalForm();
            int jarEnd = source.toLowerCase(Locale.ROOT).indexOf(".jar");
            if (jarEnd < 0) return null;
            String jarLocation = source.substring(0, jarEnd + 4);
            while (jarLocation.startsWith("union:") || jarLocation.startsWith("jar:")) {
                jarLocation = jarLocation.substring(jarLocation.indexOf(':') + 1);
            }
            if (jarLocation.startsWith("file:")) return Paths.get(URI.create(jarLocation));
            return Paths.get(jarLocation);
        } catch (Exception failure) {
            LOGGER.warn("Could not resolve GpuNoise source jar", failure);
            return null;
        }
    }

    private static String sha256(byte[] bytes) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder result = new StringBuilder(hash.length * 2);
        for (byte value : hash) result.append(String.format("%02x", value));
        return result.toString();
    }
}
