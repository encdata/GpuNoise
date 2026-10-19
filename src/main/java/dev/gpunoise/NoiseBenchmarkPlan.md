# GPU terrain execution roadmap

## Objective and measurement rules

Move supported Minecraft and modded terrain generation from Java to Vulkan in
complete, parity-checked stages. Preserve source Java behavior and keep
unsupported graphs on the vanilla path. Speed matters, but it must come from
measured improvements on actual Vulkan hardware and must not weaken parity.

Use GitHub Actions with Mesa Lavapipe only for build, shader compilation,
Vulkan correctness, parity, lifecycle, and runtime regression coverage. CI must
not calculate, collect, or claim chunks-per-second or other throughput
benchmarks. Lavapipe is a CPU software driver; its results do not predict real
GPU speed. Any speed evaluation belongs on real Vulkan hardware outside CI.

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
  registers. Vulkan evaluates terrain router roots on the cell lattice and has
  a per-block interpolation shader. The final-density block trace currently
  fails parity, so that interpolation contract is not ready for authoritative
  terrain use. Minecraft retains aquifer orchestration, biome selection, and
  chunk writing on Java.
- Optional terrain profiling is available for separate real-hardware
  investigation; GitHub Actions does not enable or use it as a speed gate.
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
- Terrain scheduler pending queues are now partitioned by grid layout, with
  packed region coordinates inside each layout. Neighbor offsets are computed
  and distance-sorted once, then reused for candidate selection and dispatch;
  this removes per-neighbor `BatchKey` construction and per-dispatch region-list
  allocation/sorting. A worker-owned pending counter also replaces repeated
  full queue-count scans while the coalescing window is open. Equally occupied
  candidate batches now prefer the one with
  the oldest queued chunk, so Java map iteration order cannot arbitrarily pick
  which spatial region gets serviced first; a stable layout/region order breaks
  any remaining ties for reproducible scheduling.
- The C2ME + Chunky Actions scenario is a worldgen correctness test. It checks
  startup, shader execution, selected terrain profile loading, GPU cache use,
  CPU fallback policy, parity, and generated-world equality. Chunk counts only
  establish test completion; CI does not derive or report throughput. The
  production cell-grid path is the default; full-block output is a separate
  correctness diagnostic. Mesa Lavapipe is the required software Vulkan test
  device.

## Latest runtime evidence and implementation state

- A real modpack log from an RTX 3050 Laptop showed that the compact terrain
  graph had one root at max error 0.005861 against the 0.005
  tolerance, causing the previous global parity boolean to disable all terrain
  roots. The grid check also failed globally at 0.004871 due to that earlier
  result. Runtime validation now keeps a bitmask of roots that pass compact and
  grid parity independently; failed roots stay on Java while verified roots
  may use the GPU. The old log therefore records CPU-owned terrain despite
  graph installation and warmup messages. That run is useful parity evidence,
  not a controlled speed comparison.
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
- The first CI run after that pruning change stopped in the CPU-side smoke
  because an older assertion expected the unsupported root's placeholder node
  to remain in the lowered graph. The new invariant is that the GPU sibling's
  node and sampler counts match the graph compiled for that sibling alone; the
  smoke now checks that invariant directly.
- Terrain cache reads now reuse the already loaded root result, avoid a
  redundant root-mask/global-result lookup for final-density interpolation,
  and pass that result into initial-density, climate, aquifer, and general
  interpolation instead of re-reading the volatile cache field. They also
  skip repeated block-output checks unless diagnostic full-block mode is
  enabled. These changes reduce avoidable Java work without changing density
  values or transferring chunk writes to the GPU.
- Targeted run `39241099415` disproved final-density block parity even though
  graph and cell-grid root parity passed: all 98,304 traced block samples in
  chunk `(-17,-18)` differed bitwise, with 48 unsafe sign changes and maximum
  absolute error 0.287576. The first sign change was at `(-268,46,-287)`:
  Java `-0.0025274`, GPU `0.1482898`. Tectonic showed 44 sign changes and
  maximum error 0.262641; the same saved-world errors were reproduced with and
  without BYG. This localizes the remaining parity gap to the block stage. A
  likely cause is interpolating the composed final-density root instead of
  matching NoiseChunk's per-function interpolation; that hypothesis needs a
  direct implementation-level parity check before changing the GPU contract.
- Terrain profiling now reports Java fallback evaluations per router root in
  addition to whole-chunk fallback counts. This distinguishes intentional
  root-level CPU fallback in mixed modded graphs from GPU cache failures and
  makes partial GPU coverage visible in Actions correctness artifacts. It also
  reports successful GPU cache lookups per root as `gpu/java` counts, so a
  fallback count has a useful per-root denominator. Root labels are shared
  with parity and unsupported-root logs through `GpuTerrainRoots`, avoiding a
  second handwritten mapping in runtime code.

## Work passes

### Pass 1: establish reliable terrain correctness coverage

1. Keep CPU-reference and GPU worldgen fixtures deterministic with matching
   seeds, mods, and generation settings. Exercise production cell-grid mode;
   run full-block output as a separate parity diagnostic.
2. Record the active batch policy in correctness logs to make failures
   reproducible.
3. Require GPU cache consumption, zero unexpected CPU fallback, graph/grid
   parity, and shader parity-preserving math for accepted comparisons.

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
5. Evaluate scheduler policy changes on actual Vulkan hardware; Lavapipe is
   only a correctness gate and cannot select speed-oriented defaults.

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

### Pass 7: validate speed outside CI

1. Keep GitHub Actions and Lavapipe limited to build, shader, parity, and
   runtime correctness gates. Do not collect throughput metrics in CI.
2. Evaluate speed only outside CI on actual Vulkan devices. Record the exact
   device, driver, graph fingerprint, mod list, and power profile.
3. If a device or mod graph is slower, use the real-device profile to improve
   the implementation or choose Java for that graph/device; do not infer
   physical-GPU performance from Lavapipe.

## Immediate next actions

1. Resolve the saved-world terrain mismatch using the targeted final-density
   trace and preserve exact Java fallback where the GPU path differs.
2. Expand terrain-mod fixtures so bytecode translation and registered adapters
   are both validated through graph lowering, interpolation, and aquifer use.
3. Continue reducing CPU work and transfer volume in fused GPU stages without
   relying on Lavapipe timing to choose performance parameters.
4. Keep CI assertions focused on shader execution, parity, fallback policy,
   lifecycle, and generated-world correctness.

The overall goal is intentionally ongoing. A green Lavapipe run proves the
tested paths compile and execute; it does not prove support for arbitrary Java
terrain code or faster-than-CPU generation on discrete GPUs.
