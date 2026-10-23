#!/usr/bin/env python3
"""Run NeoForge + C2ME + Byepregen + Chunky worldgen correctness checks in GitHub Actions."""

from __future__ import annotations

import hashlib
import os
import queue
import re
import shutil
import signal
import socket
import struct
import subprocess
import sys
import threading
import time
import urllib.request
from pathlib import Path
from terrain_mods import TERRAIN_LIBRARIES, TERRAIN_MODS, TERRAIN_TEST_PROFILES
from compare_worldgen_chunks import compare_worlds


ROOT = Path(__file__).resolve().parents[2]
MODS = {
    "c2me-neoforge-mc1.21.1-0.4.0-alpha.0.123.jar": (
        "https://cdn.modrinth.com/data/COlSi5iR/versions/avwWYPbG/"
        "c2me-neoforge-mc1.21.1-0.4.0-alpha.0.123.jar",
        "c7c485d856fa0118694ae8484e85a714db92ce7886195dbc355257d59d4254da"
        "2ce537ac18da8e05c9786c3a98f70cc5ffdfd089ad23b6822f854d72f3caa62d",
    ),
    "Chunky-NeoForge-1.4.23.jar": (
        "https://cdn.modrinth.com/data/fALzjamp/versions/LuFhm4eU/"
        "Chunky-NeoForge-1.4.23.jar",
        "2db769dd723f243a21e1881e7c9f825e9c193da6f2bed454b70cb6fa9e51c57f"
        "63fdcf017c0657bbd26f7bba30815413e27c74d3c7be0783390a96ee9baa4bf7",
    ),
    "byepregen-1.21.1-1.1.2.4.jar": (
        "https://www.cursemaven.com/curse/maven/bye-pregen-1567378/8858794/"
        "bye-pregen-1567378-8858794.jar",
        "63439774148dbc99a7ab6083417431137ebf9dead0d9b873236b6a31411970d4"
        "834822da0df2f6494f0473c479e5dcedf68e35e3eae5c20020c3986e1cd53275",
    ),
}
RCON_PASSWORD = "gpunoise-ci-only"
SERVER_START_TIMEOUT = int(os.environ.get("GPUNOISE_SERVER_START_TIMEOUT", "900"))
if SERVER_START_TIMEOUT < 300:
    raise ValueError("GPUNOISE_SERVER_START_TIMEOUT must be at least 300 seconds")
CHUNKY_RUN_TIMEOUT = 3600
# VulkanSubmissionRing closes four worker pools sequentially, allowing up to
# 120 seconds before it force-stops unfinished work. Leave a small margin so
# a successful server stop is not reported as a failed benchmark.
SHUTDOWN_TIMEOUT = 150
GPU_NOISE_MOD_LIST = re.compile(r"(?m)^\s*GPU Noise [^\r\n]*\(gpunoise\)\s*$")
BYEPREGEN_MOD_LIST = re.compile(r"(?m)^\s*ByePregen [^\r\n]*\(byepregen\)\s*$", re.IGNORECASE)
CHUNKY_RADIUS_BLOCKS = int(os.environ.get("GPUNOISE_CHUNKY_RADIUS_BLOCKS", "250"))
MIN_CHUNKY_RADIUS_BLOCKS = 250
if CHUNKY_RADIUS_BLOCKS < MIN_CHUNKY_RADIUS_BLOCKS:
    raise ValueError("GPUNOISE_CHUNKY_RADIUS_BLOCKS must be at least 250 blocks")
WORLDGEN_CHECK_REPETITIONS = 1
BATCH_WINDOW_MS = int(os.environ.get("GPUNOISE_TERRAIN_BATCH_WINDOW_MS", "8"))
if not 1 <= BATCH_WINDOW_MS <= 250:
    raise ValueError("GPUNOISE_TERRAIN_BATCH_WINDOW_MS must be between 1 and 250")
BATCH_TARGET_CHUNKS = int(os.environ.get("GPUNOISE_TERRAIN_BATCH_TARGET_CHUNKS", "96"))
if not 1 <= BATCH_TARGET_CHUNKS <= 100:
    raise ValueError("GPUNOISE_TERRAIN_BATCH_TARGET_CHUNKS must be between 1 and 100")
BATCH_REGION_RADIUS = int(os.environ.get("GPUNOISE_TERRAIN_BATCH_REGION_RADIUS", "2"))
if not 1 <= BATCH_REGION_RADIUS <= 4:
    raise ValueError("GPUNOISE_TERRAIN_BATCH_REGION_RADIUS must be between 1 and 4")
FULL_BLOCK_OUTPUT = os.environ.get("GPUNOISE_CI_FULL_BLOCK_OUTPUT", "true").lower()
if FULL_BLOCK_OUTPUT not in {"true", "false"}:
    raise ValueError("GPUNOISE_BENCHMARK_FULL_BLOCK_OUTPUT must be true or false")
FULL_BLOCK_OUTPUT = FULL_BLOCK_OUTPUT == "true"
DENSITY_PARITY_CHUNK_X = int(os.environ.get("GPUNOISE_DENSITY_PARITY_CHUNK_X", "-17"))
DENSITY_PARITY_CHUNK_Z = int(os.environ.get("GPUNOISE_DENSITY_PARITY_CHUNK_Z", "-18"))
# Chunky's center command takes block coordinates. Keep the configured parity
# chunk inside the small correctness radius instead of pregenerating a much
# larger area just to reach the known failing coordinates.
DENSITY_PARITY_CENTER_X = DENSITY_PARITY_CHUNK_X * 16 + 8
DENSITY_PARITY_CENTER_Z = DENSITY_PARITY_CHUNK_Z * 16 + 8
VERIFY_AQUIFER_GPU = os.environ.get("GPUNOISE_CI_VERIFY_AQUIFER", "false").lower()
if VERIFY_AQUIFER_GPU not in {"true", "false"}:
    raise ValueError("GPUNOISE_CI_VERIFY_AQUIFER must be true or false")
VERIFY_AQUIFER_GPU = VERIFY_AQUIFER_GPU == "true"
TRACE_AQUIFER_BLOCK = os.environ.get("GPUNOISE_CI_TRACE_AQUIFER_BLOCK", "").strip()
if TRACE_AQUIFER_BLOCK:
    try:
        TRACE_AQUIFER_BLOCK_COORDS = tuple(int(value.strip()) for value in TRACE_AQUIFER_BLOCK.split(","))
    except ValueError as error:
        raise ValueError("GPUNOISE_CI_TRACE_AQUIFER_BLOCK must be x,y,z") from error
    if len(TRACE_AQUIFER_BLOCK_COORDS) != 3:
        raise ValueError("GPUNOISE_CI_TRACE_AQUIFER_BLOCK must be x,y,z")
else:
    TRACE_AQUIFER_BLOCK_COORDS = ()
TASK_DONE = re.compile(
    r"Task finished for .*?Processed:\s*([\d,]+) chunks \(([^)]+)%\),"
)
TASK_STARTED = re.compile(r"\[Chunky\] Task started in minecraft:overworld")
SERVER_READY = re.compile(r"Done \([^)]*\)! For help, type")
SERVER_CRASH = re.compile(
    r"Encountered an unexpected exception|Failed to start the minecraft server|server thread.*FATAL",
    re.IGNORECASE,
)
PALETTE_RESIZE_CRASH = re.compile(r"SingleValuePalette\.idFor|this\.resizeHandler is null")
GPU_COUNTS = re.compile(
    r"GPU terrain: submitted=(\d+), completed=(\d+), consumed=(\d+), fallback=(\d+)"
)
GPU_BATCH_WINDOW = re.compile(
    r"GPU terrain batch window=(\d+)ms, target=(\d+), chunk limit=(\d+), "
    r"searchRadiusRegions=(\d+)"
)
GPU_SHADER_VARIANT = re.compile(r"GPU density shader variant: registers=(\d+), fastMath=(true|false)")
VALUE_NOISE_PARITY = re.compile(
    r"Vulkan value-noise parity: samples=65536, mismatches=0, maxError=0\.0, .*matches=true"
)
C2ME_AQUIFER_OVERRIDE = {
    False: "Setting vanillaWorldGenOptimizations.optimizeAquifer to false (defined in system property)",
    True: "Setting vanillaWorldGenOptimizations.optimizeAquifer to true (defined in system property)",
}


def fetch_mods(destination: Path) -> None:
    destination.mkdir(parents=True, exist_ok=True)
    for filename, (url, expected_hash) in (MODS | TERRAIN_MODS | TERRAIN_LIBRARIES).items():
        target = destination / filename
        if not target.exists():
            print(f"Downloading test mod {filename}", flush=True)
            urllib.request.urlretrieve(url, target)
        actual_hash = hashlib.sha512(target.read_bytes()).hexdigest()
        if actual_hash != expected_hash:
            raise RuntimeError(f"SHA-512 mismatch for {filename}")


def read_exact(connection: socket.socket, count: int) -> bytes:
    data = bytearray()
    while len(data) < count:
        chunk = connection.recv(count - len(data))
        if not chunk:
            raise ConnectionError("RCON server closed the connection")
        data.extend(chunk)
    return bytes(data)


def rcon_packet(connection: socket.socket, request_id: int, packet_type: int, body: str) -> tuple[int, int, str]:
    payload = body.encode("utf-8") + b"\0\0"
    connection.sendall(struct.pack("<iii", len(payload) + 8, request_id, packet_type) + payload)
    size = struct.unpack("<i", read_exact(connection, 4))[0]
    response = read_exact(connection, size)
    response_id, response_type = struct.unpack("<ii", response[:8])
    return response_id, response_type, response[8:-2].decode("utf-8", errors="replace")


def start_chunky_with_rcon() -> float:
    commands = (
        "chunky cancel",
        "chunky world minecraft:overworld",
        f"chunky center {DENSITY_PARITY_CENTER_X} {DENSITY_PARITY_CENTER_Z}",
        f"chunky radius {CHUNKY_RADIUS_BLOCKS}",
        "chunky start",
        "chunky confirm",
    )
    deadline = time.monotonic() + 30
    last_error: Exception | None = None
    while time.monotonic() < deadline:
        try:
            with socket.create_connection(("127.0.0.1", 25575), timeout=5) as connection:
                connection.settimeout(5)
                response_id, _, _ = rcon_packet(connection, 1, 3, RCON_PASSWORD)
                if response_id == -1:
                    raise RuntimeError("RCON authentication failed")
                for request_id, command in enumerate(commands, start=2):
                    response_id, _, response = rcon_packet(connection, request_id, 2, command)
                    if response_id != request_id:
                        raise ConnectionError(f"RCON returned response id {response_id} for {command!r}")
                    print(f"CI_CHUNKY_RCON command={command!r} response={response!r}", flush=True)
                    if command == "chunky start":
                        if "Task started" not in response:
                            raise RuntimeError(f"Chunky did not confirm task start: {response!r}")
                        # Chunky confirms its task over RCON but does not
                        # reliably mirror that line into the server log. Use
                        # the successful response as the run-time clock origin.
                        return time.monotonic()
        except (OSError, ConnectionError) as error:
            last_error = error
            time.sleep(0.5)
    raise TimeoutError(f"RCON did not become ready to start Chunky within 30s: {last_error}")


def stop_server_with_rcon() -> None:
    with socket.create_connection(("127.0.0.1", 25575), timeout=5) as connection:
        connection.settimeout(5)
        response_id, _, _ = rcon_packet(connection, 1, 3, RCON_PASSWORD)
        if response_id == -1:
            raise RuntimeError("RCON authentication failed")
        rcon_packet(connection, 2, 2, "stop")


def crash_context(log_path: Path, max_lines: int = 45) -> str:
    try:
        lines = log_path.read_text(encoding="utf-8", errors="replace").splitlines()
    except OSError as error:
        return f"Unable to read server log {log_path}: {error}"
    return "\n".join(lines[-max_lines:]) or "Server log is empty."


def prepare_run_directory(run_dir: Path, downloaded_mods: Path,
                          terrain_profile: str | None) -> None:
    mods_dir = run_dir / "mods"
    mods_dir.mkdir(parents=True, exist_ok=True)
    profile_mods = set(TERRAIN_TEST_PROFILES[terrain_profile]) if terrain_profile in TERRAIN_TEST_PROFILES else set()
    selected_mods = MODS | ({name: TERRAIN_MODS[name] for name in profile_mods} | TERRAIN_LIBRARIES
                            if profile_mods else {})
    for filename in selected_mods:
        shutil.copy2(downloaded_mods / filename, mods_dir / filename)
    (run_dir / "eula.txt").write_text("eula=true\n", encoding="utf-8")
    c2me_aquifer_rewrite = terrain_profile == "c2me-aquifer-rewrite"
    # Exercise both C2ME's native aquifer path and the vanilla-compatible
    # profile. Both CPU and GPU worlds receive the same explicit setting.
    config_dir = run_dir / "config"
    config_dir.mkdir(parents=True, exist_ok=True)
    (config_dir / "c2me.toml").write_text(
        "version = 3\n\n[vanillaWorldGenOptimizations]\n"
        f"optimizeAquifer = {str(c2me_aquifer_rewrite).lower()}\n",
        encoding="utf-8",
    )
    properties = {
        "level-name": "world",
        "level-seed": "7136249183512570431",
        "online-mode": "false",
        "view-distance": "2",
        "simulation-distance": "2",
        "enable-rcon": "true",
        "rcon.port": "25575",
        "rcon.password": RCON_PASSWORD,
        "max-tick-time": "600000",
    }
    (run_dir / "server.properties").write_text(
        "".join(f"{key}={value}\n" for key, value in properties.items()), encoding="utf-8"
    )
    mod_names = ", ".join(sorted(path.name for path in mods_dir.glob("*.jar")))
    print(f"Prepared benchmark mods: {mod_names}", flush=True)
    print(f"CI_C2ME_CONFIG optimizeAquifer={str(c2me_aquifer_rewrite).lower()}", flush=True)


def run_worldgen_check(mode: str, downloaded_mods: Path, force_cpu: bool,
                       repetition: int, terrain_profile: str | None) -> int:
    case_name = terrain_profile or "without-terrain-mods"
    run_name = f"ci-{case_name}-{mode}-r{repetition}"
    run_dir = ROOT / "run" / run_name
    shutil.rmtree(run_dir, ignore_errors=True)
    prepare_run_directory(run_dir, downloaded_mods, terrain_profile)
    log_path = ROOT / "build" / "reports" / "ci" / f"runserver-{case_name}-{mode}-r{repetition}.log"
    log_path.parent.mkdir(parents=True, exist_ok=True)
    command = [
        "./gradlew", "--no-daemon", "runServer",
        f"-PgpunoiseRunDir=run/{run_name}",
        "-PgpunoiseRunFromArtifact=true",
        f"-Dgpunoise.forceCpuTerrain={str(force_cpu).lower()}",
    ]
    run_vulkan_smoke = repetition == 1
    if run_vulkan_smoke:
        # Run registry-dependent compiler fixtures after NeoForge has fully
        # booted. A standalone JavaExec has no LoadingModList or mod lifecycle.
        command.append("-Dgpunoise.vulkanSmoke=true")
    if not force_cpu:
        # Collect sampled phase timings to locate CPU/GPU synchronization and
        # aquifer staging costs during correctness runs. These diagnostics do
        # not calculate or assert a chunks-per-second result.
        command.append("-Dgpunoise.profileTerrain=true")
        command.append("-Dgpunoise.verifyBiomeGpu=true")
        # Benchmark the production cell-grid mode by default. Full-block
        # materialization is an explicit diagnostic because it adds output
        # transfer work that the normal runtime configuration does not use.
        if FULL_BLOCK_OUTPUT:
            command.append("-Dgpunoise.fullBlockOutput=true")
            # Exercise marker-dependent GPU block densities against the live
            # NoiseChunk reference and the saved CPU/GPU chunk comparison.
            command.append("-Dgpunoise.experimentalInterpolatedBlockOutput=true")
        if VERIFY_AQUIFER_GPU:
            command.append("-Dgpunoise.verifyAquiferGpu=true")
        if FULL_BLOCK_OUTPUT:
            command.append("-Dgpunoise.verifyOreVeinGpu=true")
        command.append(f"-Dgpunoise.terrainBatchWindowMs={BATCH_WINDOW_MS}")
        command.append(f"-Dgpunoise.terrainBatchTargetChunks={BATCH_TARGET_CHUNKS}")
        command.append(f"-Dgpunoise.terrainBatchRegionRadius={BATCH_REGION_RADIUS}")
    print(f"Starting {mode} dedicated Chunky worldgen check (full_block_output={FULL_BLOCK_OUTPUT})", flush=True)
    process_env = os.environ.copy()
    c2me_aquifer_rewrite = terrain_profile == "c2me-aquifer-rewrite"
    jvm_overrides = [
        "-Dc2me.base.config.override.vanillaWorldGenOptimizations.optimizeAquifer="
        + str(c2me_aquifer_rewrite).lower(),
        # Five Mesa jobs run concurrently; graph compilation and parity on a
        # software Vulkan device can exceed the runtime's 120 second default.
        "-Dgpunoise.startupParityTimeoutSeconds=600",
    ]
    if not force_cpu:
        if TRACE_AQUIFER_BLOCK_COORDS:
            trace_x, trace_y, trace_z = TRACE_AQUIFER_BLOCK_COORDS
            jvm_overrides.extend((
                f"-Dgpunoise.traceAquiferBlockX={trace_x}",
                f"-Dgpunoise.traceAquiferBlockY={trace_y}",
                f"-Dgpunoise.traceAquiferBlockZ={trace_z}",
            ))
        jvm_overrides.extend((
            "-Dgpunoise.verifyTerrainDensityChunkX=" + str(DENSITY_PARITY_CHUNK_X),
            "-Dgpunoise.verifyTerrainDensityChunkZ=" + str(DENSITY_PARITY_CHUNK_Z),
        ))
    process_env["JAVA_TOOL_OPTIONS"] = " ".join(
        value for value in (process_env.get("JAVA_TOOL_OPTIONS", ""), *jvm_overrides) if value
    )
    process = subprocess.Popen(
        command, cwd=ROOT, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
        text=True, bufsize=1, start_new_session=True, env=process_env,
    )
    lines: queue.Queue[tuple[str, float] | None] = queue.Queue()

    def collect_output() -> None:
        assert process.stdout is not None
        with log_path.open("w", encoding="utf-8") as log_file:
            for line in process.stdout:
                # Chunky prints a throughput estimate in its progress line;
                # CI checks correctness only, so do not retain or publish it.
                line = re.sub(r", Rate: [0-9]+(?:\.[0-9]+)? cps", "", line)
                observed_at = time.monotonic()
                print(line, end="", flush=True)
                log_file.write(line)
                log_file.flush()
                lines.put((line, observed_at))
        lines.put(None)

    reader = threading.Thread(target=collect_output, name=f"{mode}-server-log", daemon=True)
    reader.start()
    started = time.monotonic()
    chunky_started: float | None = None
    chunky_start_requested = False
    server_ready_seen = False
    completed: re.Match[str] | None = None
    gpu_counts: list[tuple[int, int, int, int]] = []
    try:
        while True:
            now = time.monotonic()
            if chunky_started is None:
                if now - started >= SERVER_START_TIMEOUT:
                    raise TimeoutError(f"{mode} server did not start Chunky within {SERVER_START_TIMEOUT}s")
                remaining = SERVER_START_TIMEOUT - (now - started)
            else:
                if now - chunky_started >= CHUNKY_RUN_TIMEOUT:
                    raise TimeoutError(f"{mode} Chunky pregen did not finish within {CHUNKY_RUN_TIMEOUT}s")
                remaining = CHUNKY_RUN_TIMEOUT - (now - chunky_started)
            try:
                event = lines.get(timeout=min(0.5, remaining))
            except queue.Empty:
                event = ("", time.monotonic())
            if event is None:
                break
            line, observed_at = event
            if not server_ready_seen and SERVER_READY.search(line):
                server_log = log_path.read_text(encoding="utf-8", errors="replace")
                if C2ME_AQUIFER_OVERRIDE[c2me_aquifer_rewrite] not in server_log:
                    raise RuntimeError(
                        f"{mode} server did not confirm C2ME optimizeAquifer={c2me_aquifer_rewrite}; "
                        "GPU and CPU worldgen would use an unverified aquifer contract"
                    )
                if not GPU_NOISE_MOD_LIST.search(server_log):
                    raise RuntimeError(
                        f"{mode} dedicated server reached ready state without GPU Noise in NeoForge's mod list"
                    )
                if not BYEPREGEN_MOD_LIST.search(server_log):
                    raise RuntimeError(
                        f"{mode} dedicated server reached ready state without Byepregen in NeoForge's mod list"
                    )
                if terrain_profile in TERRAIN_TEST_PROFILES:
                    missing_mods = [filename for filename in TERRAIN_TEST_PROFILES[terrain_profile]
                                    if filename not in server_log]
                    if missing_mods:
                        raise RuntimeError(
                            f"terrain profile {terrain_profile} reached ready state without discovering: "
                            + ", ".join(missing_mods)
                        )
                server_ready_seen = True
            # The optional Vulkan smoke runs synchronously on the server thread
            # after the ready line. RCON connections can be accepted while it
            # runs, but command responses block until it finishes. Wait for its
            # completion marker before starting Chunky so the RCON handshake
            # does not expire against a busy server thread.
            smoke_ready = not run_vulkan_smoke or "GPU_NOISE_VULKAN_SMOKE_PASSED" in line
            if server_ready_seen and not chunky_start_requested and smoke_ready:
                chunky_started = start_chunky_with_rcon()
                chunky_start_requested = True
            if chunky_started is None and TASK_STARTED.search(line):
                chunky_started = observed_at
            if SERVER_CRASH.search(line):
                raise RuntimeError(
                    f"{mode} server reported a fatal error before Chunky completed:\n"
                    f"{crash_context(log_path)}"
                )
            if PALETTE_RESIZE_CRASH.search(line):
                raise RuntimeError(
                    f"{mode} server hit the Byepregen palette resize crash:\n"
                    f"{crash_context(log_path)}"
                )
            match = TASK_DONE.search(line)
            if match:
                completed = match
                break
            counts = GPU_COUNTS.search(line)
            if counts:
                gpu_counts.append(tuple(int(value) for value in counts.groups()))
            if process.poll() is not None:
                break

        if completed is None:
            if process.poll() is not None:
                reader.join(timeout=5)
                raise RuntimeError(
                    f"{mode} runServer exited with status {process.returncode} before Chunky completed:\n"
                    f"{crash_context(log_path)}"
                )
            raise TimeoutError(f"{mode} server stopped before Chunky completed")

        count = int(completed.group(1).replace(",", ""))
        percent = float(completed.group(2).rstrip("%"))
        if percent < 99.99 or count <= 0:
            raise RuntimeError(f"Chunky reported an incomplete run: {completed.group(0)}")

        if not force_cpu:
            run_log = log_path.read_text(encoding="utf-8")
            if not VALUE_NOISE_PARITY.search(run_log):
                raise RuntimeError("Vulkan deterministic value-noise parity probe did not pass")
            for parity_marker in (
                    "ImprovedNoise parity: 65536 samples;",
                    "Minecraft parameter parity: 65536 samples;"):
                parity_line = next((line for line in run_log.splitlines()
                                    if parity_marker in line), "")
                if "matches=true" not in parity_line:
                    raise RuntimeError(f"Vulkan startup parity check did not pass: {parity_marker}")
            if VERIFY_AQUIFER_GPU:
                surface_fallbacks = [
                    line for line in run_log.splitlines()
                    if "GPU preliminary aquifer surface decision mismatch" in line
                ]
                unsafe_surface_results = [
                    line for line in surface_fallbacks
                    if "using Java for this sample." not in line
                ]
                if unsafe_surface_results:
                    raise RuntimeError(
                        "GPU aquifer surface mismatch did not explicitly fall back to Java: "
                        + unsafe_surface_results[0]
                    )
                if surface_fallbacks:
                    print(f"CI_GPU_AQUIFER_SURFACE_JAVA_FALLBACKS count={len(surface_fallbacks)}", flush=True)
            if terrain_profile == "c2me-aquifer-rewrite" and (
                    "GPU aquifer surface scan sampled initialDensity" not in run_log):
                raise RuntimeError(
                    "C2ME aquifer surface estimation did not consume a GPU initialDensity sample"
                )
            if not gpu_counts:
                raise RuntimeError("GPU server completed without reporting terrain cache state")
            if ("GPU aquifer inputs use Vulkan cache roots; parity verifier compares GPU math with the Java "
                    "double-precision decision reference" not in run_log):
                raise RuntimeError("GPU aquifer decisions did not use the Vulkan terrain roots required for the C2ME path")
            density_differentials = re.findall(
                r"GPU terrain density differential: chunks=(\d+), lastChunk=\((-?\d+),(-?\d+)\), "
                r"samples=(\d+), exactMismatches=(\d+), signMismatches=(\d+), "
                r"unsafeSignMismatches=(\d+),",
                run_log)
            if not any(int(chunks) > 0
                       and int(chunk_x) == DENSITY_PARITY_CHUNK_X
                       and int(chunk_z) == DENSITY_PARITY_CHUNK_Z
                       and int(samples) > 0
                       for chunks, chunk_x, chunk_z, samples, _exact, _sign, _unsafe in density_differentials):
                raise RuntimeError(
                    "GPU final-density differential did not sample the configured parity chunk "
                    f"({DENSITY_PARITY_CHUNK_X},{DENSITY_PARITY_CHUNK_Z})")
            if any(int(unsafe) != 0 for *_, unsafe in density_differentials):
                raise RuntimeError(
                    "GPU final-density sign parity failed outside the Java fallback threshold in the "
                    f"configured chunk ({DENSITY_PARITY_CHUNK_X},{DENSITY_PARITY_CHUNK_Z})")
            if max(counts[2] for counts in gpu_counts) <= 0:
                raise RuntimeError("GPU benchmark completed without any GPU terrain cache being consumed")
            material_batches = re.findall(
                r"GPU terrain material decisions: positiveDensityDefaultRows=(\d+), "
                r"submittedAquiferRows=(\d+), .*?acceptedAquiferDecisions=(\d+), cpuFallback=(\d+)", run_log)
            if not material_batches:
                skip_reasons = re.findall(r"GPU aquifer decision preflight skipped: ([^\r\n]+)", run_log)
                reason_detail = "; ".join(dict.fromkeys(skip_reasons)) or "no preflight skip reason was logged"
                raise RuntimeError(
                    "GPU benchmark did not execute the chunk-batched terrain material decision shader: "
                    + reason_detail
                )
            if material_batches and (sum(int(row[0]) for row in material_batches) == 0
                                     or sum(int(row[1]) for row in material_batches) == 0):
                raise RuntimeError(
                    "GPU terrain material path did not scan positive-density blocks and submit aquifer rows")
            if (c2me_aquifer_rewrite
                    and sum(int(row[2]) for row in material_batches) == 0):
                raise RuntimeError(
                    "C2ME aquifer rewrite completed without applying a GPU decision row; "
                    "the optimized C2ME path is still running entirely on Java")
            if VERIFY_AQUIFER_GPU and material_batches:
                nearest_results = re.findall(
                    r"GPU aquifer nearest parity: samples=(\d+), mismatches=(\d+)",
                    log_path.read_text(encoding="utf-8"))
                if (not nearest_results
                        or sum(int(samples) for samples, _ in nearest_results) == 0
                        or any(int(mismatches) != 0 for _, mismatches in nearest_results)):
                    raise RuntimeError(
                        "GPU aquifer four-center selection did not produce passing runtime parity samples")
                parity_results = re.findall(
                    r"GPU aquifer substance parity: samples=(\d+), mismatches=(\d+)",
                    log_path.read_text(encoding="utf-8"))
                if (not parity_results
                        or sum(int(samples) for samples, _ in parity_results) == 0
                        or any(int(mismatches) != 0 for _, mismatches in parity_results)):
                    raise RuntimeError(
                        "GPU aquifer substance decisions did not produce passing runtime parity samples")
            if TRACE_AQUIFER_BLOCK_COORDS:
                trace_x, trace_y, trace_z = TRACE_AQUIFER_BLOCK_COORDS
                trace_block = f"block=({trace_x}, {trace_y}, {trace_z})"
                trace_lines = [line for line in run_log.splitlines()
                               if "GPU_AQUIFER_TRACE_PARITY" in line and trace_block in line]
                trace_applied = any("GPU_AQUIFER_TRACE_APPLIED" in line and trace_block in line
                                    for line in run_log.splitlines())
                trace_vanilla = any("GPU_AQUIFER_TRACE_VANILLA" in line and trace_block in line
                                    for line in run_log.splitlines())
                if not trace_applied and not trace_vanilla:
                    raise RuntimeError(
                        f"GPU aquifer trace did not observe runtime decision lookup at {trace_block}")
                if trace_applied and not trace_lines:
                    raise RuntimeError(
                        f"GPU aquifer trace applied a decision without its Java decision-reference sample at {trace_block}")
                if trace_lines and not any(
                        ("referenceSchedule=true" in line and "gpuSchedule=true" in line)
                        or ("referenceSchedule=false" in line and "gpuSchedule=false" in line)
                        for line in trace_lines):
                    raise RuntimeError(
                        f"GPU aquifer trace schedule differs from the Java decision reference at {trace_block}: {trace_lines[-1]}")
            fallback_chunks = max(counts[3] for counts in gpu_counts)
            if fallback_chunks != 0:
                raise RuntimeError(
                    f"GPU benchmark used CPU terrain fallback for {fallback_chunks} chunks"
                )
            if "GPU terrain custom DensityFunction translations passing root parity:" not in run_log:
                raise RuntimeError(
                    "GPU terrain startup did not report which custom DensityFunction translations passed root parity"
                )
            if "Byepregen palette compatibility detected; using normal block writes" not in run_log:
                raise RuntimeError(
                    "GPU worldgen did not activate the Byepregen palette compatibility path"
                )
            if "GPU terrain grid parity:" not in run_log:
                raise RuntimeError("GPU terrain grid parity check did not run")
            for shader_program in ("density graph", "terrain grid"):
                if f"Runtime Vulkan shader compiled: program={shader_program}," not in run_log:
                    raise RuntimeError(
                        f"registry-time Vulkan shader compilation did not run for {shader_program}")
            if FULL_BLOCK_OUTPUT and "Runtime Vulkan shader compiled: program=block density graph," not in run_log:
                raise RuntimeError("registry-time block density shader compilation did not run")
            if terrain_profile == "tectonic-overworld-stack" and "GPU terrain root 'finalDensity' remains on vanilla Java" in run_log:
                raise RuntimeError("Tectonic finalDensity did not compile through the generic GPU density frontend")
            if FULL_BLOCK_OUTPUT and "GPU per-block terrain output materialized:" not in run_log:
                raise RuntimeError("GPU per-block terrain output validation did not run")
            if FULL_BLOCK_OUTPUT:
                block_roots = re.findall(
                    r"GPU terrain per-block roots: exact=(\[[^\]]*\]), "
                    r"interpolation-dependent=(\[[^\]]*\]), enabled=(\[[^\]]*\]), runtimeProbe=(true|false)",
                    run_log)
                if (not block_roots or block_roots[-1][3] != "true"
                        or "finalDensity" not in block_roots[-1][1] + block_roots[-1][2]):
                    raise RuntimeError(
                        "GPU worldgen did not exercise interpolation-dependent finalDensity block output")
                if "GPU ore-vein decision preparation completed:" not in run_log:
                    raise RuntimeError("GPU terrain did not submit per-block ore-vein decisions to Vulkan")
                if "GPU OreVeinifier decision applied at" not in run_log:
                    raise RuntimeError("GPU terrain did not consume a Vulkan ore-vein block decision")
                if "GPU OreVeinifier parity mismatch" in run_log:
                    raise RuntimeError("Vulkan OreVeinifier decision differed from Minecraft's Java rule")
            if "GPU multi-noise biome table applied to chunk" not in run_log:
                raise RuntimeError("GPU multi-noise biome IDs were not applied during worldgen")
            batch_window = GPU_BATCH_WINDOW.search(run_log)
            if (batch_window is None or int(batch_window.group(1)) != BATCH_WINDOW_MS
                    or int(batch_window.group(2)) != BATCH_TARGET_CHUNKS
                    or int(batch_window.group(4)) != BATCH_REGION_RADIUS):
                observed = "missing" if batch_window is None else f"{batch_window.group(1)}ms"
                raise RuntimeError(
                    f"GPU batch policy was window={observed}, target="
                    f"{batch_window.group(2) if batch_window else 'missing'}; expected "
                    f"window={BATCH_WINDOW_MS}ms, target={BATCH_TARGET_CHUNKS}, "
                    f"searchRadiusRegions={BATCH_REGION_RADIUS}"
                )
            shader_variants = GPU_SHADER_VARIANT.findall(run_log)
            # Keep correctness checks on the parity-preserving shader. FP32
            # math remains an explicit experiment because some compiler
            # fixtures exceed Java parity.
            expected_fast_math = "false"
            if not shader_variants or shader_variants[-1][1] != expected_fast_math:
                observed = "missing" if not shader_variants else shader_variants[-1][1]
                raise RuntimeError(f"GPU shader fastMath mode was {observed}; expected {expected_fast_math}")

        if run_vulkan_smoke:
            startup_log = log_path.read_text(encoding="utf-8")
            if "GPU_NOISE_VULKAN_SMOKE_PASSED" not in startup_log:
                raise RuntimeError("Vulkan server lifecycle smoke did not report success")

        try:
            stop_server_with_rcon()
        except (OSError, ConnectionError, RuntimeError) as error:
            print(f"RCON stop failed after completed benchmark: {error}", flush=True)
            os.killpg(process.pid, signal.SIGTERM)
        return_code = process.wait(timeout=SHUTDOWN_TIMEOUT)
        if return_code != 0:
            raise RuntimeError(f"{mode} runServer exited with status {return_code}")

        if force_cpu:
            cpu_run_log = log_path.read_text(encoding="utf-8")
            if "GPU terrain graph setup skipped for the forced-CPU reference run." not in cpu_run_log:
                raise RuntimeError("CPU reference run attempted to install a GPU terrain graph")
            cpu_world = ROOT / "run" / run_name / "world"
            snapshot_chunks, snapshot_sections, snapshot_blocks = compare_worlds(cpu_world, cpu_world)
            print(f"CI_WORLDGEN_CPU_SNAPSHOT_PARSED chunks={snapshot_chunks} "
                  f"sections={snapshot_sections} blocks={snapshot_blocks}", flush=True)

        print(f"CI_WORLDGEN_CHECK_PASSED mode={mode} chunks={count}", flush=True)
        return count
    except BaseException:
        if process.poll() is None:
            try:
                stop_server_with_rcon()
                process.wait(timeout=SHUTDOWN_TIMEOUT)
            except Exception:
                os.killpg(process.pid, signal.SIGTERM)
                try:
                    process.wait(timeout=15)
                except subprocess.TimeoutExpired:
                    os.killpg(process.pid, signal.SIGKILL)
                    process.wait()
        raise
    finally:
        reader.join(timeout=5)


def main() -> int:
    prepare_only = sys.argv[1:] == ["--prepare-mods"]
    profile = None
    if sys.argv[1:] and not prepare_only:
        if len(sys.argv) != 3 or sys.argv[1] != "--profile":
            choices = ", ".join(("without-terrain-mods", "c2me-aquifer-rewrite", *TERRAIN_TEST_PROFILES))
            raise ValueError(f"usage: runserver_benchmark.py [--prepare-mods | --profile PROFILE]; profiles: {choices}")
        profile = sys.argv[2]
        if profile not in ("without-terrain-mods", "c2me-aquifer-rewrite", *TERRAIN_TEST_PROFILES):
            raise ValueError(f"unknown worldgen profile {profile!r}")
    downloaded_mods = ROOT / "build" / "ci-mods"
    fetch_mods(downloaded_mods)
    if prepare_only:
        print(f"CI_BENCHMARK_MODS_READY path={downloaded_mods} files="
              f"{len(MODS) + len(TERRAIN_MODS) + len(TERRAIN_LIBRARIES)} "
              f"terrainMods={len(TERRAIN_MODS)}", flush=True)
        return 0
    print(
        f"CI_WORLDGEN_CONFIG radius_blocks={CHUNKY_RADIUS_BLOCKS} "
        f"test_cases={2 + len(TERRAIN_TEST_PROFILES)} selected_profile={profile or 'all'} "
        f"repetitions={WORLDGEN_CHECK_REPETITIONS} "
        f"terrain_mods={len(TERRAIN_MODS)} modded_profiles={len(TERRAIN_TEST_PROFILES)} "
        f"batch_window_ms={BATCH_WINDOW_MS} "
        f"batch_target_chunks={BATCH_TARGET_CHUNKS} "
        f"batch_region_radius={BATCH_REGION_RADIUS} full_block_output={FULL_BLOCK_OUTPUT} "
        f"server_start_timeout_seconds={SERVER_START_TIMEOUT}",
        flush=True,
    )
    completed_cases: list[str] = []
    all_cases: tuple[tuple[str, str | None], ...] = (
        (("without-terrain-mods", None),)
        + (("c2me-aquifer-rewrite", "c2me-aquifer-rewrite"),)
        + tuple((profile, profile) for profile in TERRAIN_TEST_PROFILES)
    )
    cases = tuple(case for case in all_cases if profile is None or case[0] == profile)
    for case_name, terrain_profile in cases:
        counts: dict[str, int] = {}
        for mode in ("cpu", "gpu"):
            counts[mode] = run_worldgen_check(
                mode, downloaded_mods, force_cpu=mode == "cpu", repetition=1,
                terrain_profile=terrain_profile)
        if counts["cpu"] != counts["gpu"]:
            raise RuntimeError(f"{case_name} CPU/GPU Chunky areas differ: "
                               f"CPU={counts['cpu']} chunks, GPU={counts['gpu']} chunks")
        cpu_world = ROOT / "run" / f"ci-{case_name}-cpu-r1" / "world"
        gpu_world = ROOT / "run" / f"ci-{case_name}-gpu-r1" / "world"
        compared_chunks, compared_sections, compared_blocks = compare_worlds(cpu_world, gpu_world)
        print(f"CI_WORLDGEN_BLOCK_PARITY_PASSED case={case_name} chunks={compared_chunks} "
              f"sections={compared_sections} blocks={compared_blocks}", flush=True)
        completed_cases.append(case_name)
        print(f"CI_WORLDGEN_CASE_PASSED case={case_name} chunks={counts['gpu']} "
              "cpu_gpu_correctness_paths=true", flush=True)
    print(
        f"CI_WORLDGEN_CHECKS_PASSED cases={','.join(completed_cases)} "
        f"cpu_fallback_and_gpu_paths=true profiles={len(completed_cases)}",
        flush=True,
    )
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as error:
        print(f"CI worldgen benchmark failed: {error}", file=sys.stderr, flush=True)
        raise SystemExit(1)
