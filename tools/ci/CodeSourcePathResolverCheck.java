package dev.gpunoise.loader;

import java.net.URI;
import java.nio.file.Path;

/** Small JDK-only regression check for ModLauncher code-source URL formats. */
public final class CodeSourcePathResolverCheck {
    private CodeSourcePathResolverCheck() {}

    public static void main(String[] args) {
        expect("file:///C:/Minecraft%20servers%20fast/GrandBone%20Bonetasy/mods/gpunoise.jar",
                "union:/C:/Minecraft%20servers%20fast/GrandBone%20Bonetasy/mods/gpunoise.jar");
        expect("file:///C:/Minecraft%20servers%20fast/GrandBone%20Bonetasy/mods/gpunoise.jar",
                "union:file:/C:/Minecraft%20servers%20fast/GrandBone%20Bonetasy/mods/gpunoise.jar");
        expect("file:///Users/jamesgreen/Library/Application%20Support/ModrinthApp/profiles/NeoForge%201.21.1/mods/gpunoise.jar",
                "union:/Users/jamesgreen/Library/Application%20Support/ModrinthApp/profiles/NeoForge%201.21.1/mods/gpunoise.jar");
        Path macPath = CodeSourcePathResolver.resolve(
                "union:/Users/jamesgreen/Library/Application%20Support/ModrinthApp/profiles/NeoForge%201.21.1/mods/gpunoise.jar");
        if (!macPath.toString().contains("Application Support")
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
