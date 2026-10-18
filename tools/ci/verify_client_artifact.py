#!/usr/bin/env python3
"""Prove NeoForge discovers the release jar from a normal client mods folder."""

from __future__ import annotations

import os
import queue
import re
import shutil
import signal
import subprocess
import sys
import threading
import time
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
MOD_LIST_ENTRY = re.compile(r"^\s*GPU Noise [^\r\n]*\(gpunoise\)\s*$", re.IGNORECASE)
MOD_CONSTRUCTOR_ENTRY = "GPU Noise inactive: C2ME DFC is not installed"
ANSI_ESCAPE = re.compile(r"\x1b\[[0-9;]*[A-Za-z]")
MODULE_RESOLUTION_ERROR = re.compile(
    r"ModuleLayerHandler|ResolutionException|Error while resolving modules|"
    r"contains package .* module .* exports package",
    re.IGNORECASE,
)
STARTUP_TIMEOUT_SECONDS = 240
SHUTDOWN_TIMEOUT_SECONDS = 20


def tail(path: Path, count: int = 100) -> str:
    try:
        return "\n".join(path.read_text(encoding="utf-8", errors="replace").splitlines()[-count:])
    except OSError as error:
        return f"Could not read client log {path}: {error}"


def stop_process_group(process: subprocess.Popen[str]) -> None:
    try:
        os.killpg(process.pid, signal.SIGTERM)
    except ProcessLookupError:
        return
    try:
        process.wait(timeout=SHUTDOWN_TIMEOUT_SECONDS)
    except subprocess.TimeoutExpired:
        try:
            os.killpg(process.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
        process.wait(timeout=5)


def verify(artifact: Path) -> None:
    if not artifact.is_file():
        raise FileNotFoundError(f"Release artifact is missing: {artifact}")

    run_dir = ROOT / "run" / "client-artifact"
    shutil.rmtree(run_dir, ignore_errors=True)
    mods_dir = run_dir / "mods"
    mods_dir.mkdir(parents=True)
    shutil.copy2(artifact, mods_dir / artifact.name)
    (run_dir / "options.txt").write_text("fullscreen:false\n", encoding="utf-8")

    log_path = ROOT / "build" / "reports" / "ci" / "client-artifact-discovery.log"
    log_path.parent.mkdir(parents=True, exist_ok=True)
    command = [
        "./gradlew", "--no-daemon", "runClient",
        "-PgpunoiseClientFromArtifact=true",
        "-PgpunoiseRunDir=run/client-artifact",
    ]
    print(
        "Starting isolated NeoForge client with only the exact release artifact in its mods folder: "
        + " ".join(command),
        flush=True,
    )
    process = subprocess.Popen(
        command,
        cwd=ROOT,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        bufsize=1,
        start_new_session=True,
    )
    assert process.stdout is not None
    events: queue.Queue[str | None] = queue.Queue()

    def collect_output() -> None:
        with log_path.open("w", encoding="utf-8") as log_file:
            for line in process.stdout:
                print(line, end="", flush=True)
                log_file.write(line)
                log_file.flush()
                events.put(line)
        events.put(None)

    reader = threading.Thread(target=collect_output, name="client-artifact-log", daemon=True)
    reader.start()
    deadline = time.monotonic() + STARTUP_TIMEOUT_SECONDS
    found_mod = False
    constructed_mod = False
    resolution_error = False
    finished_output = False

    try:
        while time.monotonic() < deadline and not finished_output:
            try:
                line = events.get(timeout=min(0.5, max(0.0, deadline - time.monotonic())))
            except queue.Empty:
                if process.poll() is not None and not reader.is_alive():
                    break
                continue
            if line is None:
                finished_output = True
                break
            clean_line = ANSI_ESCAPE.sub("", line)
            if MODULE_RESOLUTION_ERROR.search(clean_line):
                resolution_error = True
            if MOD_LIST_ENTRY.search(clean_line):
                found_mod = True
            if MOD_CONSTRUCTOR_ENTRY in clean_line:
                constructed_mod = True
            if found_mod and constructed_mod:
                break

        if found_mod and constructed_mod:
            print(
                "CLIENT_ARTIFACT_LOAD_OK: NeoForge discovered the jar from mods/ and ran GPU Noise's @Mod constructor.",
                flush=True,
            )
            return

        if process.poll() is None:
            raise TimeoutError(
                f"NeoForge client did not fully load GPU Noise within {STARTUP_TIMEOUT_SECONDS}s "
                f"(mod-list entry={found_mod}, constructor marker={constructed_mod}). "
                f"Module resolution failure observed={resolution_error}. Recent log:\n{tail(log_path)}"
            )
        raise RuntimeError(
            f"NeoForge client exited with status {process.returncode} before GPU Noise loaded "
            f"(mod-list entry={found_mod}, constructor marker={constructed_mod}). "
            f"Module resolution failure observed={resolution_error}. Recent log:\n{tail(log_path)}"
        )
    finally:
        stop_process_group(process)
        reader.join(timeout=5)


def main() -> int:
    artifacts = sorted((ROOT / "build" / "libs").glob("*-all.jar"))
    if len(artifacts) != 1:
        raise SystemExit(f"Expected exactly one build/libs/*-all.jar artifact, found {artifacts}")
    try:
        verify(artifacts[0])
    except Exception as error:
        print(f"CLIENT_ARTIFACT_DISCOVERY_FAILED: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
