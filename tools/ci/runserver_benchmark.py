#!/usr/bin/env python3
"""Run paired NeoForge + C2ME + Chunky terrain benchmarks in GitHub Actions."""

from __future__ import annotations

import hashlib
import os
import queue
import re
import shutil
import signal
import socket
import statistics
import struct
import subprocess
import sys
import threading
import time
import urllib.request
from pathlib import Path


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
}
RCON_PASSWORD = "gpunoise-ci-only"
SERVER_START_TIMEOUT = 300
CHUNKY_RUN_TIMEOUT = 3600
SHUTDOWN_TIMEOUT = 90
CHUNKY_RADIUS_BLOCKS = int(os.environ.get("GPUNOISE_CHUNKY_RADIUS_BLOCKS", "250"))
MIN_CHUNKY_RADIUS_BLOCKS = 250
if CHUNKY_RADIUS_BLOCKS < MIN_CHUNKY_RADIUS_BLOCKS:
    raise ValueError("GPUNOISE_CHUNKY_RADIUS_BLOCKS must be at least 250 blocks")
BENCHMARK_REPETITIONS = 3
BATCH_WINDOW_MS = int(os.environ.get("GPUNOISE_TERRAIN_BATCH_WINDOW_MS", "50"))
if not 1 <= BATCH_WINDOW_MS <= 250:
    raise ValueError("GPUNOISE_TERRAIN_BATCH_WINDOW_MS must be between 1 and 250")
BATCH_TARGET_CHUNKS = int(os.environ.get("GPUNOISE_TERRAIN_BATCH_TARGET_CHUNKS", "96"))
if not 1 <= BATCH_TARGET_CHUNKS <= 100:
    raise ValueError("GPUNOISE_TERRAIN_BATCH_TARGET_CHUNKS must be between 1 and 100")
BATCH_REGION_RADIUS = int(os.environ.get("GPUNOISE_TERRAIN_BATCH_REGION_RADIUS", "2"))
if not 1 <= BATCH_REGION_RADIUS <= 4:
    raise ValueError("GPUNOISE_TERRAIN_BATCH_REGION_RADIUS must be between 1 and 4")
FULL_BLOCK_OUTPUT = os.environ.get("GPUNOISE_BENCHMARK_FULL_BLOCK_OUTPUT", "false").lower()
if FULL_BLOCK_OUTPUT not in {"true", "false"}:
    raise ValueError("GPUNOISE_BENCHMARK_FULL_BLOCK_OUTPUT must be true or false")
FULL_BLOCK_OUTPUT = FULL_BLOCK_OUTPUT == "true"
TASK_DONE = re.compile(
    r"Task finished for .*?Processed:\s*([\d,]+) chunks \(([^)]+)%\), "
    r"Total time:\s*(\d+):(\d+):(\d+)"
)
TASK_STARTED = re.compile(r"\[Chunky\] Task started in minecraft:overworld")
SERVER_CRASH = re.compile(
    r"Encountered an unexpected exception|Failed to start the minecraft server|server thread.*FATAL",
    re.IGNORECASE,
)
GPU_COUNTS = re.compile(
    r"GPU terrain: submitted=(\d+), completed=(\d+), consumed=(\d+), fallback=(\d+)"
)
GPU_BATCH_WINDOW = re.compile(
    r"GPU terrain batch window=(\d+)ms, target=(\d+), chunk limit=(\d+), "
    r"searchRadiusRegions=(\d+)"
)
GPU_BATCHING = re.compile(
    r"GPU terrain roots batching: avgChunksPerDispatch=([\d.]+), "
    r"avgSamplesPerDispatch=([\d.]+),"
)
GPU_BATCH_SIZES = re.compile(
    r"GPU terrain roots batching: .*batchSizes=lt16:(\d+),16to31:(\d+),"
    r"32to63:(\d+),64to95:(\d+),96plus:(\d+)"
)
GPU_GRAPH_PROFILE = re.compile(
    r"GPU terrain graph profile: completedDispatches=(\d+), "
    r"avgCpuPrepareMs=([\d.]+), avgSubmitToFenceMs=([\d.]+), "
    r"avgReadbackMs=([\d.]+)(?:, avgGpuCommandMs=([-\d.]+), gpuTimingSamples=(\d+))?"
)
GPU_SHADER_VARIANT = re.compile(r"GPU density shader variant: registers=(\d+), fastMath=(true|false)")
GPU_CHUNK_LATENCY = re.compile(
    r"GPU terrain roots latency: completedChunks=(\d+), "
    r"p50BucketMs=([\d>]+), p95BucketMs=([\d>]+), p99BucketMs=([\d>]+)"
)
GPU_HOT_PATH_PROFILE = re.compile(
    r"GPU terrain profile: fills=(\d+), observedCps=([\d.]+), avgFillMs=([\d.]+), "
    r"p50FillMs=([\d.]+), p95FillMs=([\d.]+), p99FillMs=([\d.]+), "
    r"finalDensityCalls=(\d+) sampled=(\d+) avgNs=([\d.]+), "
    r"initialDensityCalls=(\d+) sampled=(\d+) avgNs=([\d.]+), "
    r"climateCalls=(\d+) sampled=(\d+) avgNs=([\d.]+), "
    r"aquiferCalls=(\d+) sampled=(\d+) avgNs=([\d.]+), "
    r"blockWrites=(\d+) sampled=(\d+) avgNs=([\d.]+)"
)
GPU_ROOT_FALLBACKS = re.compile(r"GPU terrain Java root fallback calls: ([A-Za-z]+=[\d]+(?:,[A-Za-z]+=[\d]+)*)")
GPU_ROOT_CACHE_CALLS = re.compile(
    r"GPU terrain per-root cache calls: ([A-Za-z]+=[\d]+/[\d]+(?:,[A-Za-z]+=[\d]+/[\d]+)*)"
)


def fetch_mods(destination: Path) -> None:
    destination.mkdir(parents=True, exist_ok=True)
    for filename, (url, expected_hash) in MODS.items():
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


def prepare_run_directory(run_dir: Path, downloaded_mods: Path) -> None:
    mods_dir = run_dir / "mods"
    mods_dir.mkdir(parents=True, exist_ok=True)
    for filename in MODS:
        shutil.copy2(downloaded_mods / filename, mods_dir / filename)
    # runServer's dev source set is not listed in the mod discovery output on
    # this NeoGradle version. Put the exact artifact being released into the
    # isolated benchmark instance so the workload exercises GPU Noise itself.
    project_jars = sorted((ROOT / "build" / "libs").glob("*-all.jar"))
    if len(project_jars) != 1:
        raise RuntimeError(
            f"Expected exactly one *-all.jar mod artifact in build/libs, found {len(project_jars)}"
        )
    shutil.copy2(project_jars[0], mods_dir / project_jars[0].name)
    (run_dir / "eula.txt").write_text("eula=true\n", encoding="utf-8")
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


def run_benchmark(mode: str, downloaded_mods: Path, force_cpu: bool,
                  repetition: int) -> tuple[int, float]:
    run_name = f"ci-{mode}-r{repetition}"
    run_dir = ROOT / "run" / run_name
    shutil.rmtree(run_dir, ignore_errors=True)
    prepare_run_directory(run_dir, downloaded_mods)
    log_path = ROOT / "build" / "reports" / "ci" / f"runserver-{mode}-r{repetition}.log"
    log_path.parent.mkdir(parents=True, exist_ok=True)
    command = [
        "./gradlew", "--no-daemon", "runServer",
        f"-PgpunoiseRunDir=run/{run_name}",
        "-PgpunoiseRunFromArtifact=true",
        # Chunky radius is measured in blocks; keep the default benchmark at
        # a 250-block radius for a quick paired CPU/GPU comparison.
        f"-Dgpunoise.chunkyRadius={CHUNKY_RADIUS_BLOCKS}",
        "-Dgpunoise.chunkyCenterX=0",
        "-Dgpunoise.chunkyCenterZ=0",
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
        command.append(f"-Dgpunoise.terrainBatchWindowMs={BATCH_WINDOW_MS}")
        command.append(f"-Dgpunoise.terrainBatchTargetChunks={BATCH_TARGET_CHUNKS}")
        command.append(f"-Dgpunoise.terrainBatchRegionRadius={BATCH_REGION_RADIUS}")
        command.append("-Dgpunoise.profileTerrain=true")
    print(f"Starting {mode} Chunky benchmark (full_block_output={FULL_BLOCK_OUTPUT}): {' '.join(command)}", flush=True)
    process = subprocess.Popen(
        command, cwd=ROOT, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
        text=True, bufsize=1, start_new_session=True,
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
    chunky_elapsed: float | None = None
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
            if TASK_STARTED.search(line):
                chunky_started = observed_at
            if SERVER_CRASH.search(line):
                raise RuntimeError(
                    f"{mode} server reported a fatal error before Chunky completed:\n"
                    f"{crash_context(log_path)}"
                )
            match = TASK_DONE.search(line)
            if match:
                completed = match
                if chunky_started is not None:
                    # Chunky's summary rounds its duration to whole seconds.
                    # Time the streamed start and finish records at sub-second
                    # precision so small CI benchmark areas remain useful.
                    chunky_elapsed = observed_at - chunky_started
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
        reported_elapsed = (
            int(completed.group(3)) * 3600
            + int(completed.group(4)) * 60
            + int(completed.group(5))
        )
        elapsed_seconds = chunky_elapsed if chunky_elapsed is not None else float(reported_elapsed)
        if percent < 99.99 or count <= 0 or elapsed_seconds <= 0:
            raise RuntimeError(f"Chunky reported an incomplete or unmeasurable run: {completed.group(0)}")

        if not force_cpu:
            if not gpu_counts or max(counts[2] for counts in gpu_counts) <= 0:
                raise RuntimeError("GPU benchmark completed without any GPU terrain cache being consumed")
            fallback_chunks = max(counts[3] for counts in gpu_counts)
            if fallback_chunks != 0:
                raise RuntimeError(
                    f"GPU benchmark used CPU terrain fallback for {fallback_chunks} chunks"
                )
            run_log = log_path.read_text(encoding="utf-8")
            if "GPU terrain grid parity:" not in run_log:
                raise RuntimeError("GPU terrain grid parity check did not run")
            latency_samples = GPU_CHUNK_LATENCY.findall(run_log)
            if not latency_samples or max(int(sample[0]) for sample in latency_samples) < 128:
                raise RuntimeError("GPU per-chunk latency percentiles were not recorded")
            latest_latency = latency_samples[-1]
            print(
                f"CI_GPU_CHUNK_LATENCY mode={mode} samples={latest_latency[0]} "
                f"p50_bucket_ms={latest_latency[1]} p95_bucket_ms={latest_latency[2]} "
                f"p99_bucket_ms={latest_latency[3]}", flush=True,
            )
            batch_samples = GPU_BATCHING.findall(run_log)
            if not batch_samples:
                raise RuntimeError("GPU average chunks per dispatch were not recorded")
            print(
                f"CI_GPU_BATCHING mode={mode} avg_chunks_per_dispatch={batch_samples[-1][0]} "
                f"avg_samples_per_dispatch={batch_samples[-1][1]}", flush=True,
            )
            batch_size_samples = GPU_BATCH_SIZES.findall(run_log)
            if not batch_size_samples:
                raise RuntimeError("GPU dispatch batch-size distribution was not recorded")
            latest_sizes = batch_size_samples[-1]
            print(
                f"CI_GPU_BATCH_SIZES mode={mode} lt16={latest_sizes[0]} "
                f"16to31={latest_sizes[1]} 32to63={latest_sizes[2]} "
                f"64to95={latest_sizes[3]} 96plus={latest_sizes[4]}", flush=True,
            )
            profile_samples = GPU_GRAPH_PROFILE.findall(run_log)
            if not profile_samples:
                raise RuntimeError("GPU terrain CPU preparation, submit-to-fence, and readback profile was not recorded")
            latest_profile = profile_samples[-1]
            print(
                f"CI_GPU_PROFILE mode={mode} dispatches={latest_profile[0]} "
                f"avg_cpu_prepare_ms={latest_profile[1]} "
                f"avg_submit_to_fence_ms={latest_profile[2]} "
                f"avg_readback_ms={latest_profile[3]} "
                f"avg_gpu_command_ms={latest_profile[4] or 'unavailable'} "
                f"gpu_timing_samples={latest_profile[5] or '0'}", flush=True,
            )
            hot_path_profiles = GPU_HOT_PATH_PROFILE.findall(run_log)
            if not hot_path_profiles:
                raise RuntimeError("GPU terrain hot-path timings were not recorded")
            latest_hot_path = hot_path_profiles[-1]
            print(
                f"CI_GPU_HOT_PATH_PROFILE mode={mode} fills={latest_hot_path[0]} "
                f"observed_cps={latest_hot_path[1]} avg_fill_ms={latest_hot_path[2]} "
                f"final_density_calls={latest_hot_path[6]} sampled={latest_hot_path[7]} avg_ns={latest_hot_path[8]} "
                f"initial_density_calls={latest_hot_path[9]} sampled={latest_hot_path[10]} avg_ns={latest_hot_path[11]} "
                f"climate_calls={latest_hot_path[12]} sampled={latest_hot_path[13]} avg_ns={latest_hot_path[14]} "
                f"aquifer_calls={latest_hot_path[15]} sampled={latest_hot_path[16]} avg_ns={latest_hot_path[17]} "
                f"block_writes={latest_hot_path[18]} sampled={latest_hot_path[19]} avg_ns={latest_hot_path[20]}",
                flush=True,
            )
            root_fallback_samples = GPU_ROOT_FALLBACKS.findall(run_log)
            if not root_fallback_samples:
                raise RuntimeError("GPU terrain per-root Java fallback counts were not recorded")
            print(f"CI_GPU_ROOT_FALLBACKS mode={mode} calls={root_fallback_samples[-1]}", flush=True)
            root_cache_samples = GPU_ROOT_CACHE_CALLS.findall(run_log)
            if not root_cache_samples:
                raise RuntimeError("GPU terrain per-root cache call counts were not recorded")
            print(f"CI_GPU_ROOT_CACHE_CALLS mode={mode} gpu_over_java={root_cache_samples[-1]}", flush=True)
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
            # Keep the paired throughput comparison on the parity-preserving
            # shader. FP32 math remains an explicit experiment: the compiler
            # smoke suite includes large accumulations that exceed Java parity.
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

        cps = count / elapsed_seconds
        print(
            f"CI_WORLDGEN_BENCHMARK mode={mode} chunks={count} "
            f"elapsed_seconds={elapsed_seconds:.3f} chunks_per_second={cps:.3f}",
            flush=True,
        )
        return count, cps
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
    if sys.argv[1:] and not prepare_only:
        raise ValueError("usage: runserver_benchmark.py [--prepare-mods]")
    downloaded_mods = ROOT / "build" / "ci-mods"
    fetch_mods(downloaded_mods)
    if prepare_only:
        print(f"CI_BENCHMARK_MODS_READY path={downloaded_mods} files={len(MODS)}", flush=True)
        return 0
    print(
        f"CI_BENCHMARK_CONFIG radius_blocks={CHUNKY_RADIUS_BLOCKS} "
        f"repetitions={BENCHMARK_REPETITIONS} batch_window_ms={BATCH_WINDOW_MS} "
        f"batch_target_chunks={BATCH_TARGET_CHUNKS} "
        f"batch_region_radius={BATCH_REGION_RADIUS} full_block_output={FULL_BLOCK_OUTPUT}",
        flush=True,
    )
    measurements: dict[str, list[tuple[int, float]]] = {"cpu": [], "gpu": []}
    for repetition in range(1, BENCHMARK_REPETITIONS + 1):
        order = ("cpu", "gpu") if repetition % 2 else ("gpu", "cpu")
        for mode in order:
            measurements[mode].append(run_benchmark(
                mode, downloaded_mods, force_cpu=mode == "cpu", repetition=repetition))
        cpu_count = measurements["cpu"][-1][0]
        gpu_count = measurements["gpu"][-1][0]
        if cpu_count != gpu_count:
            raise RuntimeError(f"Benchmark areas differ in repetition {repetition}: "
                               f"CPU={cpu_count} chunks, GPU={gpu_count} chunks")
        pair_cpu_cps = measurements["cpu"][-1][1]
        pair_gpu_cps = measurements["gpu"][-1][1]
        print(
            f"CI_WORLDGEN_REPETITION repetition={repetition} chunks={cpu_count} "
            f"cpu_cps={pair_cpu_cps:.3f} lavapipe_cps={pair_gpu_cps:.3f} "
            f"ratio={pair_gpu_cps / pair_cpu_cps if pair_cpu_cps else 0.0:.3f}",
            flush=True,
        )
    cpu_cps = statistics.median(cps for _, cps in measurements["cpu"])
    gpu_cps = statistics.median(cps for _, cps in measurements["gpu"])
    chunk_counts = {count for runs in measurements.values() for count, _ in runs}
    if len(chunk_counts) != 1:
        raise RuntimeError(f"Benchmark areas differed across repetitions: {sorted(chunk_counts)}")
    count = chunk_counts.pop()
    paired_ratios = [gpu_run[1] / cpu_run[1] if cpu_run[1] else 0.0
                     for cpu_run, gpu_run in zip(measurements["cpu"], measurements["gpu"])]
    ratio = statistics.median(paired_ratios)
    cpu_values = [cps for _, cps in measurements["cpu"]]
    gpu_values = [cps for _, cps in measurements["gpu"]]
    print(
        f"CI_WORLDGEN_COMPARISON repetitions={BENCHMARK_REPETITIONS} chunks={count} "
        f"cpu_median_cps={cpu_cps:.3f} lavapipe_median_cps={gpu_cps:.3f} "
        f"median_paired_ratio={ratio:.3f} "
        f"cpu_range_cps={min(cpu_values):.3f}..{max(cpu_values):.3f} "
        f"lavapipe_range_cps={min(gpu_values):.3f}..{max(gpu_values):.3f}",
        flush=True,
    )
    print("Lavapipe results validate correctness and overhead only; they do not represent a discrete GPU.")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as error:
        print(f"CI worldgen benchmark failed: {error}", file=sys.stderr, flush=True)
        raise SystemExit(1)
