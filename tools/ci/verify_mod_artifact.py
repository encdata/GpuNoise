#!/usr/bin/env python3
"""Validate the single client/server mod artifact before upload."""

from __future__ import annotations

import hashlib
import io
import json
import sys
import zipfile
from pathlib import Path


LWJGL_VERSION = "3.3.3"
VULKAN_LIBRARY = f"META-INF/jarjar/lwjgl-vulkan-{LWJGL_VERSION}.jar"
SHADERC_LIBRARY = f"META-INF/jarjar/lwjgl-shaderc-{LWJGL_VERSION}.jar"
SERVER_CORE_LIBRARY = "META-INF/gpunoise/server-libraries/lwjgl-core-gpunoise-server.jar"
MOD_PAYLOAD = "META-INF/gpunoise/gpunoise-mod.jar"
METADATA = "META-INF/jarjar/metadata.json"
LOCATOR_SERVICE = "META-INF/services/net.neoforged.neoforgespi.locating.IModFileCandidateLocator"
LOCATOR_CLASS = "dev/gpunoise/loader/GpuNoiseDedicatedLwjglLocator.class"
UNUSED_GRAPH_SHADER_VARIANTS = {
    f"assets/gpunoise/shaders/density_graph_{registers}.comp.spv"
    for registers in (32, 64, 128)
} | {
    f"assets/gpunoise/shaders/density_graph_grid_{registers}.comp.spv"
    for registers in (32, 64, 128, 256)
}
ACTIVE_GRAPH_SHADERS = {
    "assets/gpunoise/shaders/density_graph.comp.spv",
    "assets/gpunoise/shaders/density_graph_grid.comp.spv",
}
MAX_ARTIFACT_BYTES = 20 * 1024 * 1024


def library_entry(artifact: str) -> dict[str, object]:
    return {
        "identifier": {"group": "org.lwjgl", "artifact": artifact},
        "version": {
            "range": f"[{LWJGL_VERSION},3.3.4)",
            "artifactVersion": LWJGL_VERSION,
        },
        "path": f"META-INF/jarjar/{artifact}-{LWJGL_VERSION}.jar",
        "isObfuscated": False,
    }


def verify(path: Path) -> None:
    if not path.is_file():
        raise FileNotFoundError(f"Vulkan artifact does not exist: {path}")
    if path.stat().st_size > MAX_ARTIFACT_BYTES:
        raise ValueError(
            f"Universal loader artifact is {path.stat().st_size} bytes; the deduplicated artifact must stay below "
            f"{MAX_ARTIFACT_BYTES} bytes"
        )
    with zipfile.ZipFile(path) as artifact:
        entries = set(artifact.namelist())
        duplicate_outer_payload = sorted(
            name for name in entries
            if name in {VULKAN_LIBRARY, SHADERC_LIBRARY}
            or name.startswith(("windows/", "linux/", "macos/"))
        )
        if duplicate_outer_payload:
            raise ValueError(
                "Loader shell repeats libraries or platform natives already bundled in the embedded mod payload: "
                f"{duplicate_outer_payload[:12]}"
            )
        required = {
            SERVER_CORE_LIBRARY, LOCATOR_SERVICE, LOCATOR_CLASS, MOD_PAYLOAD,
        }
        missing = sorted(required - entries)
        if missing:
            raise ValueError(f"Universal loader artifact is missing required entries: {missing}")
        if any(name.startswith("META-INF/gpunoise/server-libraries/lwjgl-natives-") for name in entries):
            raise ValueError("Platform natives are duplicated in the loader shell instead of derived from the mod payload")

        duplicate_packages = sorted(
            name for name in entries
            if name.endswith(".class")
            and name.startswith("dev/gpunoise/")
            and not name.startswith("dev/gpunoise/loader/")
        )
        if "META-INF/neoforge.mods.toml" in entries or duplicate_packages:
            raise ValueError(
                "Loader shell contains mod metadata or non-loader classes; "
                f"metadata={('META-INF/neoforge.mods.toml' in entries)}, "
                f"sample duplicate entries={duplicate_packages[:12]}"
            )

        with zipfile.ZipFile(io.BytesIO(artifact.read(MOD_PAYLOAD))) as mod:
            mod_entries = set(mod.namelist())
            mod_required = {
                "META-INF/neoforge.mods.toml", VULKAN_LIBRARY, SHADERC_LIBRARY, METADATA,
                "dev/gpunoise/GpuNoise.class",
                "dev/gpunoise/GpuRuntimeShaderCompiler.class",
                "dev/gpunoise/GpuDensityGraphShaderGenerator.class",
                *ACTIVE_GRAPH_SHADERS,
                "linux/x64/org/lwjgl/shaderc/libshaderc.so",
                "windows/x64/org/lwjgl/shaderc/shaderc.dll",
                "macos/x64/org/lwjgl/shaderc/libshaderc.dylib",
                "windows/x64/org/lwjgl/lwjgl.dll",
                "linux/x64/org/lwjgl/liblwjgl.so",
                "macos/x64/org/lwjgl/liblwjgl.dylib",
            }
            missing_mod = sorted(mod_required - mod_entries)
            if missing_mod:
                raise ValueError(f"Embedded GPU Noise mod payload is missing required entries: {missing_mod}")
            unused_graph_shaders = sorted(UNUSED_GRAPH_SHADER_VARIANTS & mod_entries)
            if "assets/gpunoise/shaders/density_graph_256.comp.spv" in mod_entries:
                unused_graph_shaders.append("assets/gpunoise/shaders/density_graph_256.comp.spv (duplicate)")
            if unused_graph_shaders:
                raise ValueError(
                    "Embedded mod payload includes unused precompiled register variants: "
                    f"{unused_graph_shaders}"
                )
            if LOCATOR_SERVICE in mod_entries or any(
                name.startswith("dev/gpunoise/loader/") for name in mod_entries
            ):
                raise ValueError("Embedded mod payload repeats the early locator module")

            nested_mixin_classes = sorted(
                name for name in mod_entries
                if name.startswith("dev/gpunoise/mixin/")
                and name.endswith(".class")
                and "$" in name.rsplit("/", 1)[-1]
            )
            if nested_mixin_classes:
                raise ValueError(
                    "Mixin packages cannot contain referenced nested helper classes; "
                    f"move these classes to the regular mod package: {nested_mixin_classes[:12]}"
                )

            metadata = json.loads(mod.read(METADATA))
        jars = metadata.get("jars")
        expected = [library_entry("lwjgl-vulkan"), library_entry("lwjgl-shaderc")]
        if jars != expected:
            raise ValueError(f"Unexpected cross-platform JarJar metadata: {jars!r}")
        if any(entry.get("identifier", {}).get("artifact") == "lwjgl" for entry in jars):
            raise ValueError("LWJGL core must be loaded conditionally, not through Jar-in-Jar")

        with zipfile.ZipFile(io.BytesIO(artifact.read(SERVER_CORE_LIBRARY))) as server_core:
            core_entries = set(server_core.namelist())
            manifest = server_core.read("META-INF/MANIFEST.MF").decode("utf-8", errors="replace")
            if "org/lwjgl/Version.class" not in core_entries:
                raise ValueError("Embedded dedicated-server library is missing LWJGL core classes")
            if "FMLModType: LIBRARY" not in manifest:
                raise ValueError("Embedded dedicated-server LWJGL jar is not marked as an FML library")

        locator = artifact.read(LOCATOR_SERVICE).decode("utf-8", errors="replace").strip()
        if locator != "dev.gpunoise.loader.GpuNoiseDedicatedLwjglLocator":
            raise ValueError(f"Unexpected early library locator service entry: {locator!r}")

    digest = hashlib.sha256(path.read_bytes()).hexdigest()
    print(f"CI_MOD_ARTIFACT path={path} bytes={path.stat().st_size} sha256={digest}")


def main() -> int:
    arguments = [Path(argument) for argument in sys.argv[1:]]
    if len(arguments) != 1:
        raise SystemExit("usage: verify_mod_artifact.py path/to/*-all.jar")
    verify(arguments[0])
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
