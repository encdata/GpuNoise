# GPU terrain execution and performance roadmap

## Objective and measurement rules

Move supported Minecraft and modded terrain density computation from Java to
Vulkan, preserve the source Java result within the defined parity tolerance,
and make the supported terrain workloads faster than the CPU path. The longer
term throughput target is 1,000 generated chunks per second on suitable
hardware. Treat that as a hardware- and modpack-specific goal, not a result
that can be inferred from shader microbenchmarks.

Use Mesa Lavapipe in GitHub Actions for shader compilation, Vulkan execution,
parity, lifecycle, and regression coverage. Lavapipe executes on the CPU; its
throughput measures correctness and software-driver overhead, not discrete-GPU
performance. Prove the GPU speed target on physical Vulkan devices separately.

Every throughput comparison must use fresh world directories, identical
Minecraft/mod versions, world seed, Chunky radius, C2ME settings, warmup policy,
and machine load. Run CPU and GPU modes in alternating order, report each
repetition plus median and range, and keep graph parity and fallback counts
beside CPS. A faster CPS number is invalid if it used CPU fallback or generated
different terrain. Include queue wait, batch occupancy, submit-to-completion,
and p50/p95/p99 chunk latency so batching gains cannot hide stutter.

## Current implementation

- `GpuDensityGraphCompiler` lowers vanilla router graphs and accepts explicit
  translators registered by other mods.
- `GpuDensityBytecodeCompiler` translates a conservative, side-effect-free
  subset of custom `compute(FunctionContext): double` bytecode into the shared
  graph IR. Supported methods include bounded statically-unrolled loops,
  helper calls, inherited/default methods, record and final-field helper
  objects, arithmetic, comparisons, and branch selection. Helper bytecode is
  still inlined and each field load must be final. Unsupported behavior must
  remain on the Java path with a recorded reason; never approximate silently.
- Terrain roots now compile transactionally as independent outputs. A custom
  function that cannot be translated marks only the roots that depend on it
  for vanilla fallback; supported sibling roots retain their GPU graph and
  parity checks. This preserves correct Java behavior while expanding useful
  GPU coverage in mixed terrain modpacks.
- The graph lowerer removes unreachable nodes, orders dependencies, and reuses
  registers. Vulkan evaluates the terrain router roots on the cell lattice.
  Java still owns interpolation, aquifer orchestration, biome selection, and
  chunk writing; full block output remains a diagnostic option.
- Terrain dispatch profiling now reports GPU timestamp-query command duration
  separately from CPU preparation, submit-to-fence wall time, and readback.
  Devices with no timestamp-capable compute queue report the GPU duration as
  unavailable rather than treating host polling time as shader execution.
- Optional terrain profiling now times successful cache lookups for final and
  initial density, climate roots, aquifer roots, and direct section writes.
  Each report includes call totals and average nanoseconds, alongside whole
  fill latency percentiles, to show whether hot-path lookup and write overhead
  offsets the GPU's grid-evaluation work.
- Hot-path nanosecond timing is sampled once per 64 lookups per worker thread;
  successful operations are still counted on every call. Sampling prevents
  diagnostic timing overhead from being added to every density evaluation.
- Bytecode translation now permits dynamic forward conditionals and switches
  inside statically bounded loops. The current structured interpreter unrolls
  those loops and expresses each branch as select nodes; its existing bytecode
  and branch expansion budgets still reject graphs whose expansion is too
  large. CPU evaluation remains the fallback for unbounded loops and other
  unsupported control flow.
- Each chunk cache snapshots the installed graph's immutable root-support mask
  once at construction. Per-block and per-aquifer lookups then test a local
  integer bit instead of repeatedly loading the global volatile graph reference
  on the terrain hot path. The cache is created only after graph installation;
  server shutdown clears the graph and all prefetched caches together.
- The C2ME + Chunky CI scenario is configured for a 1,600-block radius, or
  100 chunks in each direction (40,401 chunks per square pregen), in three
  paired repetitions. It checks Vulkan smoke/parity and captures dispatch and
  chunk latency metrics. The paired CPS benchmark uses the production cell-grid
  output mode by default. Full-block GPU materialization can be selected with
  `GPUNOISE_BENCHMARK_FULL_BLOCK_OUTPUT=true` for diagnostic runs, but its
  additional output transfer must not be mixed into production throughput
  comparisons. CI builds and tests use Mesa Lavapipe.
- Two completed CI runs isolate the batch target change at the same 50 ms
  collection window. Target 32 measured median CPU 43.915 CPS and Lavapipe
  30.336 CPS (paired ratio 0.691), with 14.93 chunks per dispatch. Target 64
  measured median CPU 56.438 CPS and Lavapipe 40.488 CPS (paired ratio
  0.715), with 16.50 chunks per dispatch. Runner throughput varied, so paired
  ratios are the more useful comparison: occupancy and relative throughput
  improved modestly. Both runs still had p95 chunk latency in the 2.5-second
  histogram bucket. The 64-target run had p50 in the 1-second bucket. These
  results do not meet the throughput or tail-latency goals, and Lavapipe is not
  a discrete-GPU performance measurement. Those runs forced diagnostic
  full-block output, so they are useful for comparing the scheduler but are
  not the production cell-grid throughput baseline.
- The subsequent complete-tile early-dispatch and spatial/layout queue
  partition changes also completed a paired diagnostic run: median CPU
  throughput was 41.724 CPS and Lavapipe was 30.508 CPS (paired ratio 0.733).
  The three repetitions averaged 14.39, 14.93, and 15.22 chunks per dispatch;
  p95 remained in the 2.5-second bucket, and queue wait was about 35-39 ms per
  chunk. These results used full-block diagnostic output. They show that the
  early-tile and layout partition changes did not yet fill 64-chunk batches or
  remove the latency tail.
- The first corrected production cell-grid run (commit `18a30d5`, radius 256
  blocks, target 64) completed 1,089 chunks per repetition at median CPU 33.509
  CPS and Lavapipe 21.780 CPS (paired ratio 0.642). Dispatches averaged
  14.39-14.65 chunks. Chunk latency buckets were p50 2.5 seconds, p95 5
  seconds, and p99 5 seconds. GPU cache use and zero CPU fallback gates passed.
  At that point the scheduler still dispatched a single prefetched tile early;
  a follow-up removed the early dispatch and raised the target to 96, measured
  below. New CI runs use a 1,600-block radius (100 chunks per direction) to
  measure sustained pregen, with a one-hour Chunky timeout.
- The 96-target/no-early-tile production run (commit `f2e980a`, radius 256
  blocks) completed at median CPU 44.632 CPS and Lavapipe 31.941 CPS (paired
  ratio 0.718). Average dispatch fill was 18.23-19.60 chunks, still far below
  the target. CPU preparation took 0.29-0.49 ms per dispatch, while
  submit-to-fence took 269-289 ms and result readback 1.06-1.87 ms. The fence
  interval includes up to 1 ms of polling and cannot be interpreted as pure
  shader execution time. Chunk latency buckets improved to p50 1 second and
  p95/p99 2.5 seconds, but remain too high for the no-stutter target. This
  points the next pass toward dispatch occupancy and the compute/device path,
  rather than CPU-side buffer preparation. The next scheduler pass searches a
  50x50 chunk region (radius two 10x10 buckets) for compatible queued chunks,
  keeping the 50 ms deadline and 96-chunk target unchanged. This addresses the
  measured ~44 ms enqueue wait with only 18-20 chunks per dispatch: the queue
  had time to collect, but the previous 30x30 search region could not find
  enough nearby work.
- The capacity-aware batch pass (commit `8f3fa5c`, still at a 256-block radius
  and the 30x30 search region) built and completed all CI gates. It measured
  median CPU 33.404 CPS and Lavapipe 23.222 CPS (ratio 0.681), with 15.84-17.62
  chunks per dispatch and 371-395 ms average submit-to-fence. Its p50 chunk
  latency was 2.5 seconds and p95/p99 reached 5 seconds. This runner result is
  slower than the earlier 96-target run; the capacity check did not increase
  occupancy, and runner variation prevents attributing the CPS change to that
  code. The larger region search is the next isolated scheduling change.
- Five subsequent successful 100-chunk-radius Actions runs each generated
  40,401 chunks per CPU/GPU pair over three repetitions. Across the radius-one
  and radius-two scheduler revisions, CPU medians ranged from 41.923 to 45.615
  CPS and Lavapipe medians ranged from 28.611 to 32.059 CPS (paired ratios
  0.656-0.747). The radius-two run on `f0ef1c7` measured 44.617 CPU CPS and
  29.735 Lavapipe CPS (0.666 ratio); the later capacity-aware run on `7fa38d6`
  measured 43.876 and 31.983 CPS (0.727). The bytecode compiler run on
  `2ce1da2` measured 45.272 and 29.896 CPS (0.661). All used Mesa/Lavapipe,
  so these numbers establish long-run software-Vulkan correctness and relative
  overhead only; they do not establish physical-GPU throughput. The measured
  CPU preparation remained around 0.42-0.54 ms per dispatch while
  submit-to-fence spanned roughly 315-372 ms, with 1.5-1.9 ms readback.
  Fence time includes the polling interval and Lavapipe software execution.
  Batch occupancy and p95/p99 latency still need to improve, and the next
  benchmark should run the same revision on actual GPU hardware.

## Latest runtime evidence and implementation state

- A real modpack log from an RTX 3050 Laptop showed Chunksmith rates around
  25-40 CPS while generating a 32,768-block radius with C2ME, Voxy, and many
  terrain and content mods. It is not a controlled CPU/GPU comparison. The
  compact terrain graph had one root at max error 0.005861 against the 0.005
  tolerance, causing the previous global parity boolean to disable all terrain
  roots. The grid check also failed globally at 0.004871 due to that earlier
  result. Runtime validation now keeps a bitmask of roots that pass compact and
  grid parity independently; failed roots stay on Java while verified roots
  may use the GPU. The old log therefore records CPU-owned terrain despite
  graph installation and warmup messages. Its separate one-shot 65,536-sample
  x100 noise microbenchmark was 23.68 ms CPU versus 123.65 ms GPU including
  setup and dispatch, and must not be read as full-terrain throughput.
- The latest Vulkan Actions run built successfully but its smoke failed on a
  signed integer remainder (`x=-7`: Java expected -7.5, Vulkan returned 9.5).
  This isolated the bug to negative integer remainder in the GLSL path, before
  the bounded dynamic-switch loop could run. The shader now computes Java
  remainder from a truncation-toward-zero quotient instead of relying on GLSL
  `%` lowering. The Actions smoke also covers that signed case before retrying
  the combined bounded-switch-loop fixture; dynamic switches in loops remain
  unverified until that passes.
- Lowering now omits root subgraphs that the compiler cannot translate, and
  startup re-lowers the source graph after parity removes failing roots. The
  router output layout stays fixed, while unsupported slots point at an
  unread placeholder. This targets wasted per-sample shader instructions from
  terrain roots that already fall back to Java, especially in custom modpack
  graphs with only partial Vulkan coverage.
- Terrain cache reads now reuse the already loaded root result, avoid a
  redundant root-mask/global-result lookup for final-density interpolation,
  and skip repeated block-output checks unless diagnostic full-block mode is
  enabled. The next paired run must quantify this hot-path change after
  Vulkan parity is restored.

## Work passes

### Pass 1: establish a reliable terrain baseline

1. Keep the paired CPU/GPU C2ME + Chunky workload deterministic and large
   enough to expose steady-state throughput, startup cost, and tail latency.
   Measure the normal production cell-grid mode; run full-block output as a
   separate diagnostic because it changes GPU readback volume.
2. Record the active batch window and target in each log and benchmark result.
3. Require GPU cache consumption, zero unexpected CPU fallback, graph/grid
   parity, and shader parity-preserving math for accepted comparisons.
4. Report dispatch occupancy, input samples per dispatch, queue wait,
   submit-to-completion, and latency percentiles with every CPS result.
5. Run an explicit batch-target sweep (for example 16, 32, 64) on the same
   runner and workload before choosing defaults. Use the smallest wait that
   keeps the best sustained throughput without worsening p95/p99 materially.

### Pass 2: remove avoidable queue and dispatch cost

1. Batch only requests with compatible grid dimensions, cell spacing, output
   mode, and graph. Prefer spatially adjacent work and cap work by actual input
   and output buffer capacity.
2. Keep a bounded coalescing deadline. Dispatch a complete prefetched tile
   promptly when neighboring work is absent; continue collecting when other
   compatible work is already arriving.
3. Make backpressure explicit. Bound pending requests and metadata buffers,
   record time spent in scheduler queues separately from GPU execution, and
   complete every future on shutdown or submission failure.
4. Avoid allocating and sorting spatial candidates on each arrival. Keep
   candidate selection bounded by the local neighborhood and make it aware of
   grid layout.
5. Compare measured occupancy and latency before increasing target sizes.
   Larger batches amortize transfers and submissions, but they can increase
   fill delay, memory use, readback size, and GPU watchdog risk.

### Pass 3: make the Java-to-GPU compiler useful to terrain mods

1. Define and publish the translator API contract: exact and subtype matching,
   deterministic registration, graph dependencies, sampler configuration,
   supported operations, parity expectations, and failure behavior.
2. Expand the bytecode frontend by semantic families, not class-name guesses:
   numeric conversions, integer overflow, floating-point edge cases, helper
   dispatch, arrays/record and final-field helper objects, static
   configuration, and supported control flow.
3. Add mod-shaped fixtures for common terrain patterns and report accepted
   graph nodes or a precise rejection reason for every fixture.
4. Support helper calls only when their bytecode and object reads can be
   represented completely. Reject recursion, unbounded loops, mutable state,
   side effects, reflection, unsupported virtual dispatch, and unsafe calls.
5. Preserve exact Java behavior for branches, NaN/infinity, signed zero,
   integer wraparound, noise seeds, coordinate scaling, and sampler caches.
   For every supported construct, compare GPU output with the original Java
   implementation over representative and adversarial coordinates.
6. Treat translator registrations and bytecode translation as graph build
   inputs; freeze registrations before compilation and never expose a partially
   translated graph to world generation.

### Pass 4: reduce per-sample shader execution cost

1. Profile the current iterative node evaluator by opcode, sampler, graph
   register count, and device. The terrain graph currently has hundreds of
   nodes, so shader interpretation may dominate dispatch overhead.
2. Compare the generic node interpreter with a graph-specialized execution
   path that emits straight-line SPIR-V or specializes stable graph topology.
   Keep the generic interpreter as a correctness fallback for changing modpack
   graphs.
3. Keep register allocation and node liveness in the compiler IR. Inspect
   generated SPIR-V and vendor compiler reports for local-memory spills,
   register pressure, divergent branches, and excessive storage-buffer reads.
4. Specialize common noise operations and constant parameters only when parity
   holds and measured hardware results improve. Preserve a precise path for
   Java-sensitive operations; fast math stays opt-in until its error bounds
   match the terrain contract.
5. Cache compiled pipelines by graph fingerprint, device, shader variant, and
   compiler version. Bound cache memory and invalidate on graph/device changes.

### Pass 5: overlap work without exhausting memory

1. The current Vulkan submission ring uses one persistent engine slot to limit
   memory. Measure whether its single in-flight dispatch leaves the device
   idle or merely protects buffers from reuse.
2. If overlap is limiting, split per-flight command buffers, fences, and
   coordinate/readback regions while sharing immutable graph/noise buffers.
   Never create duplicate full-size engines as a shortcut.
3. Use timeline semaphores or fences for completion, persistent mapped staging
   allocations where supported, and explicit non-coherent flush/invalidate
   ranges. Do not make worldgen workers block on fence waits.
4. Add a bounded multi-flight policy based on available memory and device
   capabilities. Keep Lavapipe on a low-memory configuration and test device
   loss, cancellation, and shutdown with submissions in flight.

### Pass 6: broaden terrain coverage and validate real modpacks

1. Build compatibility fixtures from multiple terrain mods and combinations
   of custom density implementations; test both registered translators and
   bytecode translation.
2. Keep unsupported nodes on the authoritative Java path and make mixed graphs
   explicit. Never route an entire terrain router to GPU if one required root
   is not supported and parity-checked.
3. Measure generation, aquifer and climate reads, cache consumption, CPU
   fallbacks, queueing, memory, and chunk latency across different dimensions,
   world seeds, vertical ranges, and C2ME concurrency settings.
4. Retain Minecraft's chunk writer unless the project scope explicitly changes;
   GPU density work must not mutate chunk state from an unsafe thread.

### Pass 7: prove the throughput target

1. Keep Lavapipe as the required correctness/build gate.
2. Add optional GPU-runner benchmarks for representative discrete and
   integrated Vulkan devices. Label results with exact device, driver, graph
   fingerprint, mod list, and power mode.
3. Require repeated paired CPU/GPU results above 1,000 CPS for a named workload
   and hardware profile before claiming that target. Also require the GPU path
   to beat its CPU baseline and meet a stated p95/p99 latency budget.
4. If one device or mod graph is slower, use profiling and a measured policy
   to choose Java or Vulkan per graph/device; do not claim universal speedup.

## Immediate next actions

1. Read the corrected production cell-grid benchmark and fullest-cluster
   candidate-selection results from GitHub Actions.
2. Compare production-mode paired ratios, chunks per dispatch, queue wait,
   submit-to-completion, and p95/p99. Keep full-block diagnostic results
   separate from production-mode results.
3. If occupancy remains near 16 chunks, improve cross-request coalescing before
   increasing the target again. If the wait/latency tail worsens, lower the
   target or shorten the adaptive fill window.
4. Enforce zero CPU fallback in the GPU benchmark so a partial CPU result
   cannot inflate the reported throughput.
5. After the scheduler result is stable, profile the graph interpreter versus
   shader execution and choose the next shader specialization experiment.

The overall goal is intentionally ongoing. A green Lavapipe run proves the
tested paths compile and execute; it does not prove support for arbitrary Java
terrain code, faster-than-CPU generation on discrete GPUs, or 1,000 CPS.
