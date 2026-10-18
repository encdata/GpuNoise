#!/usr/bin/env python3
"""Validate the single client/server mod artifact before upload."""

from __future__ import annotations

import hashlib
import json
import sys
import zipfile
from pathlib import Path


LWJGL_VERSION = "3.3.3"
VULKAN_LIBRARY = f"META-INF/jarjar/lwjgl-vulkan-{LWJGL_VERSION}.jar"
CORE_LIBRARY = f"META-INF/jarjar/lwjgl-{LWJGL_VERSION}.jar"
METADATA = "META-INF/jarjar/metadata.json"


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
            "META-INF/neoforge.mods.toml", CORE_LIBRARY, VULKAN_LIBRARY, METADATA,
            "windows/x64/org/lwjgl/lwjgl.dll",
            "linux/x64/org/lwjgl/liblwjgl.so",
            "macos/x64/org/lwjgl/liblwjgl.dylib",
        }
        missing = sorted(required - entries)
        if missing:
            raise ValueError(f"Universal mod artifact is missing required entries: {missing}")

        metadata = json.loads(artifact.read(METADATA))
        jars = metadata.get("jars")
        expected = [library_entry("lwjgl"), library_entry("lwjgl-vulkan")]
        if jars != expected:
            raise ValueError(f"Unexpected cross-platform JarJar metadata: {jars!r}")

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
