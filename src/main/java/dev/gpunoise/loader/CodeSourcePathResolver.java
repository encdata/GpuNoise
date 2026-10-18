package dev.gpunoise.loader;

import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;

/** Converts ModLauncher code-source URLs, including nested union URLs, to filesystem paths. */
final class CodeSourcePathResolver {
    private CodeSourcePathResolver() {}

    static Path resolve(String externalForm) {
        return Paths.get(toFileUri(externalForm)).toAbsolutePath().normalize();
    }

    static URI toFileUri(String externalForm) {
        if (externalForm == null || externalForm.isBlank()) {
            throw new IllegalArgumentException("Code-source URL is empty");
        }

        String location = externalForm;
        while (hasWrapperScheme(location)) {
            location = location.substring(location.indexOf(':') + 1);
        }

        URI uri = URI.create(location);
        if ("file".equalsIgnoreCase(uri.getScheme())) {
            String rawPath = uri.getRawPath();
            // ModLauncher can report a Windows drive path as union:/C:/... .
            // Treat that form as a file URI before Paths.get sees the invalid
            // Windows path /C:/..., and retain percent escapes for URI decoding.
            if (isSlashPrefixedWindowsDrive(rawPath)) {
                return URI.create("file://" + rawPath);
            }
            if (uri.getRawAuthority() == null && rawPath != null && rawPath.startsWith("/")) {
                return URI.create("file://" + rawPath);
            }
            return uri;
        }

        if (uri.getScheme() == null && isSlashPrefixedWindowsDrive(location)) {
            return URI.create("file://" + location);
        }
        if (uri.getScheme() == null) {
            return Paths.get(location).toAbsolutePath().normalize().toUri();
        }
        throw new IllegalArgumentException("Unsupported code-source URL scheme: " + uri.getScheme());
    }

    private static boolean hasWrapperScheme(String location) {
        int colon = location.indexOf(':');
        if (colon < 0) return false;
        String scheme = location.substring(0, colon).toLowerCase(Locale.ROOT);
        return scheme.equals("union") || scheme.equals("jar");
    }

    private static boolean isSlashPrefixedWindowsDrive(String path) {
        return path != null && path.length() >= 4 && path.charAt(0) == '/'
                && isAsciiLetter(path.charAt(1)) && path.charAt(2) == ':'
                && (path.charAt(3) == '/' || path.charAt(3) == '\\');
    }

    private static boolean isAsciiLetter(char value) {
        return (value >= 'A' && value <= 'Z') || (value >= 'a' && value <= 'z');
    }
}
