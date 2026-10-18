#!/usr/bin/env python3
"""Validate the dedicated-server-safe Vulkan JarJar artifact before upload."""

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


def verify(path: Path) -> None:
    if not path.is_file():
        raise FileNotFoundError(f"Vulkan artifact does not exist: {path}")
    with zipfile.ZipFile(path) as artifact:
        entries = set(artifact.namelist())
        required = {"META-INF/neoforge.mods.toml", VULKAN_LIBRARY, METADATA}
        missing = sorted(required - entries)
        if missing:
            raise ValueError(f"Vulkan artifact is missing required entries: {missing}")
        if CORE_LIBRARY in entries:
            raise ValueError(
                "Vulkan-only artifact must not package LWJGL core, which Minecraft supplies"
            )

        metadata = json.loads(artifact.read(METADATA))
        jars = metadata.get("jars")
        expected = {
            "identifier": {"group": "org.lwjgl", "artifact": "lwjgl-vulkan"},
            "version": {
                "range": f"[{LWJGL_VERSION},3.3.4)",
                "artifactVersion": LWJGL_VERSION,
            },
            "path": VULKAN_LIBRARY,
            "isObfuscated": False,
        }
        if jars != [expected]:
            raise ValueError(f"Unexpected Vulkan JarJar metadata: {jars!r}")

    digest = hashlib.sha256(path.read_bytes()).hexdigest()
    print(f"CI_VULKAN_ARTIFACT path={path} bytes={path.stat().st_size} sha256={digest}")


def main() -> int:
    arguments = [Path(argument) for argument in sys.argv[1:]]
    if len(arguments) != 1:
        raise SystemExit("usage: verify_vulkan_artifact.py path/to/*-all-vulkan.jar")
    verify(arguments[0])
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
