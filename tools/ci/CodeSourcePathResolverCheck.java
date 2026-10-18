package dev.gpunoise.loader;

import java.net.URI;
import java.nio.file.Path;

/** Small JDK-only regression check for ModLauncher code-source URL formats. */
public final class CodeSourcePathResolverCheck {
    private CodeSourcePathResolverCheck() {}

    public static void main(String[] args) {
        String windowsUrl = "union:/C:/ci-fixture/Minecraft%20Instances/Test%20Instance/mods/mod.jar";
        String windowsUri = "file:///C:/ci-fixture/Minecraft%20Instances/Test%20Instance/mods/mod.jar";
        expect(windowsUri, windowsUrl);
        expect(windowsUri, "union:file:/C:/ci-fixture/Minecraft%20Instances/Test%20Instance/mods/mod.jar");

        String macUrl = "union:/Users/test-user/Library/Application%20Support/Launcher/profiles/"
                + "Test%20Instance/mods/mod.jar";
        expect("file:///Users/test-user/Library/Application%20Support/Launcher/profiles/"
                + "Test%20Instance/mods/mod.jar", macUrl);
        Path macPath = CodeSourcePathResolver.resolve(macUrl);
        if (!macPath.toString().contains("Application Support") || !macPath.toString().contains("Test Instance")
                || macPath.toString().contains("%20")) {
            throw new AssertionError("URL-escaped macOS path was not decoded: " + macPath);
        }
        expect("file:///opt/minecraft/mods/gpunoise.jar", "union:file:/opt/minecraft/mods/gpunoise.jar");
        expect("file:///opt/minecraft/mods/gpunoise.jar", "jar:file:/opt/minecraft/mods/gpunoise.jar");
        System.out.println("CODE_SOURCE_PATH_RESOLVER_OK");
    }

    private static void expect(String expected, String input) {
        URI actual = CodeSourcePathResolver.toFileUri(input);
        if (!expected.equals(actual.toASCIIString())) {
            throw new AssertionError("Expected " + expected + " for " + input + " but got " + actual);
        }
    }
}
