package dev.gpunoise.loader;

import java.io.InputStream;
import java.io.OutputStream;
import java.lang.module.ModuleFinder;
import java.net.URL;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.neoforgespi.ILaunchContext;
import net.neoforged.neoforgespi.locating.IDiscoveryPipeline;
import net.neoforged.neoforgespi.locating.IModFileCandidateLocator;
import net.neoforged.neoforgespi.locating.IncompatibleFileReporting;
import net.neoforged.neoforgespi.locating.ModFileDiscoveryAttributes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Registers GPU Noise on both sides and adds bundled LWJGL only to dedicated servers. */
public final class GpuNoiseDedicatedLwjglLocator implements IModFileCandidateLocator {
    private static final String SERVER_LIBRARY_DIRECTORY = "META-INF/gpunoise/server-libraries/";
    private static final String MOD_PAYLOAD = "META-INF/gpunoise/gpunoise-mod.jar";
    private static final String[] SERVER_LIBRARIES = {
            "lwjgl-core-gpunoise-server.jar"
    };
    private static final Logger LOGGER = LoggerFactory.getLogger("GpuNoiseLwjglLocator");

    @Override
    public void findCandidates(ILaunchContext context, IDiscoveryPipeline pipeline) {
        boolean dedicatedServer = FMLLoader.getDist() == Dist.DEDICATED_SERVER;
        boolean client = FMLLoader.getDist() == Dist.CLIENT;
        if (!dedicatedServer && !client) return;

        Path sourceJar = resolveSourceJar();
        if (sourceJar == null) {
            // Development runs have classes directories rather than a release jar.
            LOGGER.debug("Cannot locate packaged GPU Noise jar; skipping packaged mod registration");
            return;
        }

        // The containing artifact is only an early-loader shell. Register the
        // embedded mod jar, which is the sole jar containing worldgen and mixin
        // packages, so it cannot collide with this service-provider module.
        Path modDiscoveryJar = extractModForDiscovery(sourceJar);
        var modCandidate = pipeline.addPath(modDiscoveryJar,
                ModFileDiscoveryAttributes.DEFAULT.withLocator(this),
                IncompatibleFileReporting.ERROR);
        if (modCandidate.isPresent()) {
            LOGGER.info("Registered packaged GPU Noise mod {} for {} discovery", modDiscoveryJar,
                    dedicatedServer ? "dedicated-server" : "client");
        } else {
            LOGGER.warn("NeoForge did not create a {} mod candidate from {}",
                    dedicatedServer ? "dedicated-server" : "client", modDiscoveryJar);
        }

        // Minecraft already supplies LWJGL on clients. The client still needs
        // the GPU Noise candidate above because early service discovery can
        // consume the original mods-folder path before normal mod discovery.
        if (!dedicatedServer) {
            LOGGER.debug("Skipping embedded server LWJGL libraries on client; Minecraft supplies LWJGL");
            return;
        }

        boolean lwjglCoreAvailable = lwjglCoreAvailable();
        for (String libraryName : SERVER_LIBRARIES) {
            if (libraryName.equals("lwjgl-core-gpunoise-server.jar") && !FMLLoader.isProduction()) {
                // NeoGradle's development launch already puts Minecraft's
                // LWJGL core on the server module path. FMLLoader's module
                // graph is not available yet at candidate-discovery time, so
                // the boot-layer probe cannot see it; registering our bundled
                // copy creates two org.lwjgl providers when the game layer is
                // resolved. Production servers still receive the embedded
                // core when their launcher does not provide a named module.
                LOGGER.info("Using the LWJGL core supplied by the development server runtime");
                continue;
            }
            if (libraryName.equals("lwjgl-core-gpunoise-server.jar") && lwjglCoreAvailable) {
                LOGGER.info("Using the LWJGL core already present in the dedicated server runtime");
                continue;
            }
            Path libraryJar = extractEmbeddedLibrary(sourceJar, libraryName);
            if (libraryJar == null || !Files.isRegularFile(libraryJar)) {
                LOGGER.error("Could not extract embedded server library {} from {}", libraryName, sourceJar);
                continue;
            }
            if (libraryName.equals("lwjgl-core-gpunoise-server.jar")) {
                try {
                    if (ModuleFinder.of(libraryJar).find("org.lwjgl").isEmpty()) {
                        LOGGER.error("Bundled LWJGL core {} is not discoverable as the required JPMS module "
                                + "org.lwjgl; its versioned module descriptor may not be enabled by the jar manifest",
                                libraryJar);
                        continue;
                    }
                } catch (RuntimeException failure) {
                    LOGGER.error("Could not inspect bundled LWJGL core module descriptor at {}", libraryJar, failure);
                    continue;
                }
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

        Path nativeLibrary = extractPlatformNatives(modDiscoveryJar, sourceJar);
        if (nativeLibrary == null || !Files.isRegularFile(nativeLibrary)) {
            LOGGER.error("Could not prepare the dedicated-server LWJGL native library for this platform");
            return;
        }
        if (context.isLocated(nativeLibrary)) {
            LOGGER.debug("Dedicated-server native library is already registered: {}", nativeLibrary);
            return;
        }
        try {
            var added = pipeline.addPath(nativeLibrary,
                    ModFileDiscoveryAttributes.DEFAULT.withLocator(this),
                    IncompatibleFileReporting.ERROR);
            if (added.isPresent()) {
                LOGGER.info("Added platform-specific LWJGL natives to dedicated server runtime: {}", nativeLibrary);
            } else {
                LOGGER.error("NeoForge did not accept dedicated-server native library {}", nativeLibrary);
            }
        } catch (Exception failure) {
            LOGGER.error("Failed to register platform-specific dedicated-server LWJGL natives", failure);
        }
    }

    /** Builds the FML library from the already bundled mod natives for this runtime platform. */
    private static Path extractPlatformNatives(Path modPayload, Path sourceJar) {
        String nativePrefix = nativeResourcePrefix();
        if (nativePrefix == null) {
            LOGGER.error("No LWJGL natives are bundled for os.name={} os.arch={}",
                    System.getProperty("os.name"), System.getProperty("os.arch"));
            return null;
        }
        Path modsDirectory = sourceJar.getParent();
        Path gameDirectory = modsDirectory != null && modsDirectory.getParent() != null
                ? modsDirectory.getParent()
                : Paths.get(".").toAbsolutePath().normalize();
        Path cacheDirectory = gameDirectory.resolve(".gpunoise/bootstrap");
        Path temporary = null;
        try {
            Files.createDirectories(cacheDirectory);
            temporary = Files.createTempFile(cacheDirectory, "lwjgl-natives-", ".jar.tmp");
            var nativeDigest = MessageDigest.getInstance("SHA-256");
            Manifest manifest = new Manifest();
            Attributes attributes = manifest.getMainAttributes();
            attributes.putValue("Manifest-Version", "1.0");
            attributes.putValue("FMLModType", "LIBRARY");
            int copied = 0;
            try (JarFile mod = new JarFile(modPayload.toFile());
                 OutputStream fileOutput = Files.newOutputStream(temporary);
                 JarOutputStream output = new JarOutputStream(fileOutput, manifest)) {
                var entries = mod.entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    if (entry.isDirectory() || !entry.getName().startsWith(nativePrefix)) continue;
                    nativeDigest.update(entry.getName().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    nativeDigest.update((byte) 0);
                    nativeDigest.update(Long.toString(entry.getSize()).getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                    nativeDigest.update((byte) 0);
                    nativeDigest.update(Long.toString(entry.getCrc()).getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                    nativeDigest.update((byte) '\n');
                    JarEntry outputEntry = new JarEntry(entry.getName());
                    outputEntry.setTime(0L);
                    output.putNextEntry(outputEntry);
                    try (InputStream input = mod.getInputStream(entry)) {
                        input.transferTo(output);
                    }
                    output.closeEntry();
                    copied++;
                }
            }
            if (copied == 0) {
                LOGGER.error("Embedded mod {} contains no LWJGL resources under {}", modPayload, nativePrefix);
                Files.deleteIfExists(temporary);
                return null;
            }

            String digest = java.util.HexFormat.of().formatHex(nativeDigest.digest());
            String platform = nativePrefix.substring(0, nativePrefix.length() - 1).replace('/', '-');
            Path destination = cacheDirectory.resolve("lwjgl-natives-gpunoise-server-" + platform + "-"
                    + digest.substring(0, 16) + ".jar");
            long generatedBytes = Files.size(temporary);
            if (Files.isRegularFile(destination) && Files.size(destination) == generatedBytes) {
                Files.deleteIfExists(temporary);
                return destination;
            }

            try {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
            }
            return destination;
        } catch (Exception failure) {
            LOGGER.error("Failed to prepare platform-specific LWJGL native library", failure);
            return null;
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (Exception cleanupFailure) {
                    LOGGER.debug("Could not clean temporary LWJGL native archive {}", temporary, cleanupFailure);
                }
            }
        }
    }

    private static String nativeResourcePrefix() {
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String platform = osName.contains("win") ? "windows"
                : osName.contains("mac") || osName.contains("darwin") ? "macos"
                : osName.contains("linux") ? "linux" : null;
        String architecture = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        String lwjglArchitecture = architecture.equals("amd64") || architecture.equals("x86_64") ? "x64"
                : architecture.equals("aarch64") || architecture.equals("arm64") ? "arm64"
                : architecture.matches("i[3-6]86") || architecture.equals("x86") ? "x86" : null;
        return platform == null || lwjglArchitecture == null ? null : platform + "/" + lwjglArchitecture + "/";
    }

    private static boolean lwjglCoreAvailable() {
        if (ModuleLayer.boot().findModule("org.lwjgl").isPresent()) {
            return true;
        }
        // Only named modules satisfy a `requires org.lwjgl` edge. A class
        // visible to Class.forName or present on java.class.path belongs to an
        // unnamed module, so treating that as available breaks the mod layer.
        for (String property : new String[] {"jdk.module.path", "jdk.module.upgrade.path"}) {
            String value = System.getProperty(property, "");
            if (value.isBlank()) continue;
            try {
                Path[] paths = java.util.Arrays.stream(
                                value.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator)))
                        .filter(entry -> !entry.isBlank())
                        .map(Paths::get)
                        .toArray(Path[]::new);
                if (paths.length > 0 && ModuleFinder.of(paths).find("org.lwjgl").isPresent()) {
                    return true;
                }
            } catch (RuntimeException failure) {
                LOGGER.debug("Could not inspect named module path {}", property, failure);
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

    private static Path extractModForDiscovery(Path sourceJar) {
        try {
            byte[] bytes;
            try (InputStream input = GpuNoiseDedicatedLwjglLocator.class.getClassLoader()
                    .getResourceAsStream(MOD_PAYLOAD)) {
                if (input == null) {
                    throw new IllegalStateException("Missing embedded GPU Noise mod payload " + MOD_PAYLOAD);
                }
                bytes = input.readAllBytes();
            }
            String digest = sha256(bytes);
            Path modsDirectory = sourceJar.getParent();
            Path gameDirectory = modsDirectory != null && modsDirectory.getParent() != null
                    ? modsDirectory.getParent()
                    : Paths.get(".").toAbsolutePath().normalize();
            Path cacheDirectory = gameDirectory.resolve(".gpunoise/bootstrap");
            Files.createDirectories(cacheDirectory);
            Path destination = cacheDirectory.resolve("gpunoise-mod-" + digest.substring(0, 16) + ".jar");
            if (Files.isRegularFile(destination) && Files.size(destination) == bytes.length) return destination;

            Path temporary = Files.createTempFile(cacheDirectory, "gpunoise-mod-", ".jar.tmp");
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
            throw new IllegalStateException("Failed to extract GPU Noise mod payload for discovery", failure);
        }
    }

    private static Path resolveSourceJar() {
        try {
            URL location = GpuNoiseDedicatedLwjglLocator.class.getProtectionDomain().getCodeSource().getLocation();
            if (location == null) return null;
            String source = location.toExternalForm();
            int jarEnd = source.toLowerCase(Locale.ROOT).indexOf(".jar");
            if (jarEnd < 0) return null;
            return CodeSourcePathResolver.resolve(source.substring(0, jarEnd + 4));
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
