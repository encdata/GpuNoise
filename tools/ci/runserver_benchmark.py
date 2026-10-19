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
SERVER_START_TIMEOUT = 300
CHUNKY_RUN_TIMEOUT = 3600
SHUTDOWN_TIMEOUT = 90
GPU_NOISE_MOD_LIST = re.compile(r"(?m)^\s*GPU Noise [^\r\n]*\(gpunoise\)\s*$")
BYEPREGEN_MOD_LIST = re.compile(r"(?m)^\s*ByePregen [^\r\n]*\(byepregen\)\s*$", re.IGNORECASE)
CHUNKY_RADIUS_BLOCKS = int(os.environ.get("GPUNOISE_CHUNKY_RADIUS_BLOCKS", "250"))
MIN_CHUNKY_RADIUS_BLOCKS = 250
if CHUNKY_RADIUS_BLOCKS < MIN_CHUNKY_RADIUS_BLOCKS:
    raise ValueError("GPUNOISE_CHUNKY_RADIUS_BLOCKS must be at least 250 blocks")
WORLDGEN_CHECK_REPETITIONS = 1
BATCH_WINDOW_MS = int(os.environ.get("GPUNOISE_TERRAIN_BATCH_WINDOW_MS", "50"))
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
VERIFY_AQUIFER_GPU = os.environ.get("GPUNOISE_CI_VERIFY_AQUIFER", "false").lower()
if VERIFY_AQUIFER_GPU not in {"true", "false"}:
    raise ValueError("GPUNOISE_CI_VERIFY_AQUIFER must be true or false")
VERIFY_AQUIFER_GPU = VERIFY_AQUIFER_GPU == "true"
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
C2ME_AQUIFER_OVERRIDE = "Setting vanillaWorldGenOptimizations.optimizeAquifer to false (defined in system property)"


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


def start_chunky_with_rcon() -> None:
    commands = (
        "chunky cancel",
        "chunky world minecraft:overworld",
        "chunky center 0 0",
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
                return
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
    # C2ME's aquifer rewrite has a different nearest-center and fluid-tick
    # contract from vanilla. GPU Noise's aquifer kernel implements the vanilla
    # contract, so pin C2ME to its vanilla-compatible mode in both CPU and GPU
    # runs. This keeps the CPU world as the reference while actually exercising
    # GPU density and aquifer decisions instead of silently benchmarking the
    # exact-Java C2ME fallback.
    config_dir = run_dir / "config"
    config_dir.mkdir(parents=True, exist_ok=True)
    (config_dir / "c2me.toml").write_text(
        "version = 3\n\n[vanillaWorldGenOptimizations]\noptimizeAquifer = false\n",
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
    print("CI_C2ME_CONFIG optimizeAquifer=false (vanilla aquifer parity mode)", flush=True)


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
        # Benchmark the production cell-grid mode by default. Full-block
        # materialization is an explicit diagnostic because it adds output
        # transfer work that the normal runtime configuration does not use.
        if FULL_BLOCK_OUTPUT:
            command.append("-Dgpunoise.fullBlockOutput=true")
        if VERIFY_AQUIFER_GPU:
            command.append("-Dgpunoise.verifyAquiferGpu=true")
        command.append(f"-Dgpunoise.terrainBatchWindowMs={BATCH_WINDOW_MS}")
        command.append(f"-Dgpunoise.terrainBatchTargetChunks={BATCH_TARGET_CHUNKS}")
        command.append(f"-Dgpunoise.terrainBatchRegionRadius={BATCH_REGION_RADIUS}")
    print(f"Starting {mode} dedicated Chunky worldgen check (full_block_output={FULL_BLOCK_OUTPUT})", flush=True)
    process_env = os.environ.copy()
    jvm_overrides = [
        "-Dc2me.base.config.override.vanillaWorldGenOptimizations.optimizeAquifer=false"
    ]
    if not force_cpu:
        jvm_overrides.append("-Dgpunoise.exactAquiferInputs=true")
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
            if not chunky_start_requested and SERVER_READY.search(line):
                server_log = log_path.read_text(encoding="utf-8", errors="replace")
                if C2ME_AQUIFER_OVERRIDE not in server_log:
                    raise RuntimeError(
                        f"{mode} server did not confirm C2ME's vanilla aquifer override; "
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
                start_chunky_with_rcon()
                chunky_start_requested = True
            if TASK_STARTED.search(line):
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
            c2me_java_fallback = (
                "GPU aquifer decision preflight skipped: C2ME aquifer rewrite is active; "
                "keeping its fluid decisions on the exact Java path" in run_log
            )
            if not gpu_counts:
                raise RuntimeError("GPU server completed without reporting terrain cache state")
            if ("GPU aquifer inputs use exact Java roots; Vulkan center selection and substance decisions "
                    "remain enabled" not in run_log):
                raise RuntimeError("GPU aquifer decisions did not use the exact Java input roots required for world parity")
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
                if not c2me_java_fallback:
                    raise RuntimeError("GPU benchmark completed without any GPU terrain cache being consumed")
                print("CI_WORLDGEN_C2ME_EXACT_JAVA_FALLBACK cache_reads=0; continuing to saved-world parity",
                      flush=True)
            material_batches = re.findall(
                r"GPU terrain material decisions: queries=(\d+), positiveDensityQueries=(\d+), "
                r"aquiferQueries=(\d+), acceptedDecisions=(\d+), cpuFallback=(\d+)", run_log)
            if not material_batches and not c2me_java_fallback:
                skip_reasons = re.findall(r"GPU aquifer decision preflight skipped: ([^\r\n]+)", run_log)
                reason_detail = "; ".join(dict.fromkeys(skip_reasons)) or "no preflight skip reason was logged"
                raise RuntimeError(
                    "GPU benchmark did not execute the chunk-batched terrain material decision shader: "
                    + reason_detail
                )
            if material_batches and any(int(queries) == 0 or int(positive) == 0
                                        for queries, positive, _aquifer, _accepted, _fallback in material_batches):
                raise RuntimeError(
                    "GPU terrain material dispatch did not classify positive-density solid/air decisions")
            if VERIFY_AQUIFER_GPU and material_batches:
                nearest_results = re.findall(
                    r"GPU aquifer nearest parity: samples=(\d+), mismatches=(\d+)",
                    log_path.read_text(encoding="utf-8"))
                if not nearest_results or any(int(samples) == 0 or int(mismatches) != 0
                                              for samples, mismatches in nearest_results):
                    raise RuntimeError(
                        "GPU aquifer four-center selection did not pass runtime parity for every prepared chunk")
                parity_results = re.findall(
                    r"GPU aquifer substance parity: samples=(\d+), mismatches=(\d+)",
                    log_path.read_text(encoding="utf-8"))
                if not parity_results or any(int(samples) == 0 or int(mismatches) != 0
                                             for samples, mismatches in parity_results):
                    raise RuntimeError(
                        "GPU aquifer substance decisions did not pass runtime parity for every prepared chunk")
            fallback_chunks = max(counts[3] for counts in gpu_counts)
            if fallback_chunks != 0:
                raise RuntimeError(
                    f"GPU benchmark used CPU terrain fallback for {fallback_chunks} chunks"
                )
            if "Byepregen palette compatibility detected; using normal block writes" not in run_log:
                raise RuntimeError(
                    "GPU worldgen did not activate the Byepregen palette compatibility path"
                )
            if "GPU terrain grid parity:" not in run_log:
                raise RuntimeError("GPU terrain grid parity check did not run")
            if terrain_profile == "tectonic-overworld-stack" and "GPU terrain root 'finalDensity' remains on vanilla Java" in run_log:
                raise RuntimeError("Tectonic finalDensity did not compile through the generic GPU density frontend")
            if FULL_BLOCK_OUTPUT and "GPU per-block terrain output materialized:" not in run_log:
                raise RuntimeError("GPU per-block terrain output validation did not run")
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

        if run_vulkan_smoke and "GPU_NOISE_VULKAN_SMOKE_PASSED" not in log_path.read_text(encoding="utf-8"):
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
            choices = ", ".join(("without-terrain-mods", *TERRAIN_TEST_PROFILES))
            raise ValueError(f"usage: runserver_benchmark.py [--prepare-mods | --profile PROFILE]; profiles: {choices}")
        profile = sys.argv[2]
        if profile not in ("without-terrain-mods", *TERRAIN_TEST_PROFILES):
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
        f"test_cases={1 + len(TERRAIN_TEST_PROFILES)} selected_profile={profile or 'all'} "
        f"repetitions={WORLDGEN_CHECK_REPETITIONS} "
        f"terrain_mods={len(TERRAIN_MODS)} modded_profiles={len(TERRAIN_TEST_PROFILES)} "
        f"batch_window_ms={BATCH_WINDOW_MS} "
        f"batch_target_chunks={BATCH_TARGET_CHUNKS} "
        f"batch_region_radius={BATCH_REGION_RADIUS} full_block_output={FULL_BLOCK_OUTPUT}",
        flush=True,
    )
    completed_cases: list[str] = []
    all_cases: tuple[tuple[str, str | None], ...] = (
        (("without-terrain-mods", None),)
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
