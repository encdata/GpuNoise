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
SERVER_CORE_LIBRARY = "META-INF/gpunoise/server-libraries/lwjgl-core-gpunoise-server.jar"
SERVER_NATIVES_LIBRARY = "META-INF/gpunoise/server-libraries/lwjgl-natives-gpunoise-server.jar"
MOD_PAYLOAD = "META-INF/gpunoise/gpunoise-mod.jar"
METADATA = "META-INF/jarjar/metadata.json"
LOCATOR_SERVICE = "META-INF/services/net.neoforged.neoforgespi.locating.IModFileCandidateLocator"
LOCATOR_CLASS = "dev/gpunoise/loader/GpuNoiseDedicatedLwjglLocator.class"


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
    with zipfile.ZipFile(path) as artifact:
        entries = set(artifact.namelist())
        required = {
            SERVER_CORE_LIBRARY, SERVER_NATIVES_LIBRARY, LOCATOR_SERVICE, LOCATOR_CLASS, MOD_PAYLOAD,
        }
        missing = sorted(required - entries)
        if missing:
            raise ValueError(f"Universal loader artifact is missing required entries: {missing}")

        duplicate_packages = sorted(
            name for name in entries
            if name.startswith("dev/gpunoise/") and not name.startswith("dev/gpunoise/loader/")
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
                "META-INF/neoforge.mods.toml", VULKAN_LIBRARY, METADATA,
                "dev/gpunoise/GpuNoise.class",
                "windows/x64/org/lwjgl/lwjgl.dll",
                "linux/x64/org/lwjgl/liblwjgl.so",
                "macos/x64/org/lwjgl/liblwjgl.dylib",
            }
            missing_mod = sorted(mod_required - mod_entries)
            if missing_mod:
                raise ValueError(f"Embedded GPU Noise mod payload is missing required entries: {missing_mod}")
            if LOCATOR_SERVICE in mod_entries or any(
                name.startswith("dev/gpunoise/loader/") for name in mod_entries
            ):
                raise ValueError("Embedded mod payload repeats the early locator module")

            metadata = json.loads(mod.read(METADATA))
        jars = metadata.get("jars")
        expected = [library_entry("lwjgl-vulkan")]
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

        with zipfile.ZipFile(io.BytesIO(artifact.read(SERVER_NATIVES_LIBRARY))) as server_natives:
            native_entries = set(server_natives.namelist())
            native_manifest = server_natives.read("META-INF/MANIFEST.MF").decode("utf-8", errors="replace")
            required_server_natives = {
                "linux/x64/org/lwjgl/liblwjgl.so",
                "windows/x64/org/lwjgl/lwjgl.dll",
                "macos/x64/org/lwjgl/liblwjgl.dylib",
            }
            if missing_natives := sorted(required_server_natives - native_entries):
                raise ValueError(f"Embedded dedicated-server library is missing native payloads: {missing_natives}")
            if "FMLModType: LIBRARY" not in native_manifest:
                raise ValueError("Embedded dedicated-server native jar is not marked as an FML library")

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
