# GPU Terrain Compiler and Runtime Design

## Objective

Move terrain generation from a collection of GPU-accelerated density samples toward a compiled, asynchronous terrain pipeline. The target is a sustained 1,000 chunks per second with low tail latency and no visible stutter, measured on documented hardware and workloads. That is a performance target, not a result this design claims today.

The GPU should produce the complete terrain payload that the CPU currently derives from Minecraft's terrain generation stages: density and interpolation, aquifer/fluid decisions, solid-versus-air decisions, surface material selection, and the block-state payload needed to commit the chunk. CPU work remains for orchestration, unsupported features, and Minecraft-owned chunk mutation unless profiling proves a safe alternative.

## Compatibility contract

There is no requirement that terrain mods provide custom GPU addons. The integration begins with the vanilla terrain pipeline and discovers the density and material behavior actually installed at runtime. Modded behavior is handled in three ways:

1. Compile a known behavior into the GPU intermediate representation (IR).
2. Execute an unsupported behavior through a CPU callback at a defined stage, then resume GPU work when the resulting data can be represented safely.
3. Mark the pipeline ineligible for GPU execution and use vanilla generation for that chunk or world.

Never silently substitute a different terrain function. Every fallback records the class, stage, and reason. Compatibility is per compiled pipeline, not a global promise that arbitrary Java can become a Vulkan shader.

## Pipeline boundaries

The compiler models a chunk as a sequence of typed stages with explicit inputs, outputs, dependencies, and eligibility conditions:

| Stage | Input | Output | Initial execution target |
|---|---|---|---|
| Density graph | World coordinates, noise samplers, settings | Density values | GPU |
| Density interpolation | Cell corner values and interpolation parameters | Per-block density | GPU |
| Aquifer decision | Density, fluid levels, barrier and positional noise | Fluid/solid decision | GPU when all required aquifer inputs are available |
| Material decision | Density and aquifer result | Air/solid/fluid classification | GPU |
| Surface rules | Biome, surface depth, noise and rule inputs | Block palette index | GPU for compiled rule subset; otherwise explicit CPU stage |
| Payload validation | Palette indices and chunk bounds | Validated chunk payload | CPU |
| Chunk commit | Chunk and payload | Minecraft chunk state | CPU-owned integration initially |

Stages are independently compilable, but the runtime schedules a dependency-complete region of stages as a unit to avoid repeated CPU/GPU synchronization. The long-term unit is one chunk batch, not one dispatch per density operation.

## Compiler architecture

### 1. Runtime discovery

At world or settings initialization, inspect the concrete configured terrain pipeline and record stable identities for density functions, noises, aquifer configuration, surface rules, biome inputs, and relevant settings. Do not infer compatibility only from class names. Use known vanilla implementations and explicit structural adapters where Minecraft exposes the required fields. Unknown wrappers remain opaque and trigger a named fallback.

Compilation is cached by a content-derived pipeline key. The key includes settings, registry identities, seed-dependent sampler configuration, and compiler/shader ABI version. World seed data must not leak across keys. Cache lifetime follows the world and its datapack/registry configuration.

### 2. Typed terrain IR

Use a typed, immutable IR that covers both scalar density expressions and chunk stages. Nodes carry source provenance (Java class and method, stage, and source object identity), value type, coordinate domain, dependencies, and numerical semantics. The IR must distinguish float and integer values, block and cell coordinates, biome/palette values, and side effects.

Initial scalar operations include coordinates, constants, normal-noise sampling, arithmetic, mappings, clamps, min/max, and known vanilla density wrappers. Stage operations include interpolation, aquifer queries, material classification, surface-rule selection, and palette writes. Each operation declares CPU evaluability, GPU lowering support, required extensions/features, and exact fallback behavior.

All constants and intermediate conversions follow the chosen Minecraft-compatible precision policy. The compiler must not constant-fold across a conversion or reassociate arithmetic unless it can prove equivalent results under the shader semantics. Seed, coordinate, and negative-coordinate test vectors are part of the compiler contract.

### 3. Java behavior capture

Known vanilla classes are translated using explicit versioned adapters that read their configured fields and construct IR. This is preferable to parsing method bytecode for the supported baseline because vanilla implementations are known and need precise numerical and random-state semantics.

For other installed classes, use a layered approach:

- First, recognize structurally equivalent known operations and lower those to IR.
- Second, allow a CPU callback at a stage boundary where inputs and outputs are bounded and serializable. Batch callback work; never make a callback per block if it would create a synchronization bottleneck.
- Otherwise, use the vanilla/mod implementation on CPU for the affected stage or whole chunk.

Do not execute arbitrary mod Java inside a Vulkan shader. A future bytecode-to-IR frontend can be evaluated for pure, bounded methods, but it must validate control flow, memory access, randomness, side effects, and numerical behavior before eligibility. Unsupported constructs must fail closed to CPU behavior.

### 4. Lowering and shader ABI

Lower the IR into a backend-neutral execution plan, then generate or select Vulkan compute kernels. A plan includes resource layouts, dispatch dimensions, scratch-buffer lifetimes, barriers, and stage dependencies. Keep the CPU evaluator as a reference implementation for IR validation and differential diagnostics.

Use a versioned shader ABI with explicit layouts for noise samplers, density nodes, aquifer parameters, biome/rule data, palette indices, and output payload. Validate buffer sizes and indices before dispatch. The runtime must split batches to respect device limits and bound temporary memory.

Shader generation must be deterministic for equivalent IR. Cache compiled pipelines by device and shader key. Use Mesa Lavapipe as a CPU Vulkan backend for correctness and CI coverage, while treating actual target-device results as the performance authority. Mesa is not a substitute for measuring the intended GPU.

## Runtime and stutter control

The runtime is asynchronous and bounded. It uses a fixed-capacity queue, a worker that owns Vulkan submission, staging-buffer pools, and explicit backpressure. Chunk requests cannot allocate unbounded GPU or CPU memory. If the queue is full or a stage is ineligible, generation follows the configured CPU fallback policy without blocking the render thread.

Batch nearby chunk work to amortize dispatch and transfer overhead. Track queue depth, CPU fallback time, GPU dispatch time, transfer time, and chunk commit time independently. Reuse buffers and compiled pipelines. Avoid per-chunk device-idle waits. Fence or timeline completion is consumed by a worker and handed back to Minecraft's scheduler only when the payload is ready.

No-stutter is measured using render-thread and server-thread latency percentiles, not only average chunks per second. A throughput win that causes long stalls is a failure.

## Correctness and compatibility gates

Before enabling a stage for a pipeline, require:

- Structural validation of all IR references, sampler indices, coordinate ranges, and output bounds.
- CPU-versus-GPU differential checks over deterministic seeds, positive and negative coordinates, chunk borders, and representative biome/settings cases.
- Exact block-state comparison for any stage whose output is expected to match vanilla exactly. If only approximate density is supported, keep the pipeline opt-in and document the difference.
- Safe fallback for unsupported functions, rules, device features, allocation failures, and shader compilation failures.
- No chunk commit before every required output is validated and complete.

Keep an opt-in diagnostic mode that identifies the first differing coordinate and stage, plus the source Java class/method captured in IR provenance.

## Performance gates

Report a result only when it includes Minecraft version, mod set, world settings, hardware, Vulkan driver/device, Mesa version where applicable, warmup, sample duration, chunk type, C2ME/Chunky configuration, and CPU/GPU stage timings. Include median, p95, and p99 chunk latency and render/server tick impact alongside CPS.

The 1,000 CPS target is accepted only if the benchmark completes with the intended terrain content, bounded memory, no correctness mismatches, and no material tail-latency regression. Lavapipe results validate the Vulkan code path and provide a reproducible baseline; they cannot establish that GPU execution beats CPU generation on hardware GPUs.

## Implementation passes

### Pass A: Establish the full vanilla stage inventory

Map the project's supported Minecraft version to the configured density graph, interpolation, aquifer, material, surface-rule, and chunk-commit call paths. Record each Java entry point, required inputs, output representation, thread ownership, and existing GPU/CPU implementation. Define parity fixtures before changing scheduling.

### Pass B: Complete compiler and plan representation

Extend the current density expression work into a stage IR and backend-neutral plan. Add versioned adapters for the full vanilla density-function family. Include provenance, precise numerical semantics, eligibility reporting, and safe fallback reasons. Keep this pass independent of a claim that every stage is already on the GPU.

### Pass C: Make density and interpolation a batched GPU stage

Compile the supported vanilla density graph and interpolation into a chunk-batch dispatch. Reuse scratch buffers and avoid per-cell or per-node dispatches. Compare against the CPU evaluator over seeded boundary-focused fixtures.

### Pass D: Integrate aquifer and block classification

Feed the existing aquifer kernels with all vanilla inputs and finish fluid/solid/air classification into compact palette or decision buffers. Remove CPU round trips between these kernels. Keep chunk mutation on the owning Minecraft thread until its safety and API contract are established.

### Pass E: Compile surface rules and write a complete payload

Lower the supported vanilla surface-rule subset into the stage IR and GPU plan. Add biome and rule data buffers with validation. Where a rule is opaque or mod-specific, run an explicit batched CPU callback or fall back for the affected chunk. Produce a complete validated chunk payload.

### Pass F: Integrate bounded asynchronous scheduling

Connect the completed payload pipeline to chunk generation with bounded queues, buffer pools, backpressure, and nonblocking completion. Prove thread ownership and cancellation behavior. Measure render and server latency while Chunky generates.

### Pass G: Expand mod compatibility and optimize

Expand the automatic bytecode frontend from verified real modpack fallback reports. Profile transfers, dispatch size, occupancy, and CPU preparation before optimizing. Run paired CPU/GPU benchmarks and tune batch sizes against actual target devices.

## Current position

The current source has more infrastructure than a density-only prototype, but its execution boundary is still narrower than the objective:

- `GpuDensityGraphCompiler.compileTerrain` builds 12 `NoiseRouter` roots. `GpuTerrainRootsBatchScheduler` batches cell grids, and `GpuChunkDensityCache` serves density and climate lookups from those results.
- `NoiseChunkGeneratorWriteMixin` asynchronously waits for the grid cache, then invokes Minecraft's `doFill`. The Minecraft chunk writer, heightmap updates, and section writes remain on Java. `AquiferGpuInputsMixin` can prepare batched aquifer substance decisions on Vulkan and return eligible results at `computeSubstance`; the CPU still prepares fluid statuses and exact state objects.
- Development `run` tasks default `gpunoise.fullBlockOutput` to false; dedicated-server correctness runs enable it. `noise_interpolation.comp` materializes final density, initial density, and the four aquifer input roots in one per-block dispatch. Startup samples compare every materialized root with CPU interpolation before the cache exposes it. The CI comparator reads saved Anvil palettes and checks every generated section block state and scheduled fluid tick between paired CPU/GPU worlds.
- Aquifer center selection returns four ranked centers. Vanilla consumes the first three; C2ME's optional rewrite uses a different scan order, fourth-center scheduling, and status-identity rules. The shader contract and Lavapipe fixtures cover both algorithms, but runtime aquifer dispatch currently falls back to C2ME's Java implementation because a few live C2ME cases still differ. C2ME's aquifer rewrite also consumes exact density values; until the GPU payload preserves its numeric precision, the final-density hook stays on Java when that rewrite is active. CI compares full saved worlds with C2ME present. Vanilla aquifer dispatch remains gated on full per-block density output and supported final-density/barrier roots; structure beardifier density is included, and each result must match the live density exactly.
- Dedicated-server parity runs keep C2ME installed but explicitly disable `vanillaWorldGenOptimizations.optimizeAquifer` in the generated `config/c2me.toml`. This selects the vanilla aquifer contract on both CPU and GPU reference runs, allowing the GPU aquifer path to be exercised without comparing different aquifer algorithms. C2ME's other enabled optimizations remain in the test environment.
- `GpuTerrainRuntime.chunkWriterAvailable()` currently returns false. Therefore the optimized chunk writer is not an active end-to-end GPU stage.
- Root and grid parity are supplemented by saved block-state and fluid-tick comparison for the selected seed and mod profiles. This does not yet validate surface-rule behavior under terrain mods or prove parity for every seed and configuration.

These observations make the next implementation boundary concrete: pass integrated aquifer parity and saved-world comparisons on dedicated servers, then extend the per-block payload beyond aquifer substance to surface rules and other final block choices. Surface-rule generation must join that payload before claiming complete terrain generation. No 1,000 CPS or no-stutter result is established.

The density compiler reads custom classes' `compute(FunctionContext):double` JVM bytecode directly and recursively inlines bounded same-class pure helpers. Final fields that reference another `DensityFunction` become graph dependencies and are recursively compiled by the graph compiler. The frontend accepts coordinate reads, immutable numeric instance fields and class/interface-hierarchy static-final primitive constants, direct `NormalNoise` samples with X/Y/Z axis scales, selected double `Math`/`Mth` operations (`abs`, `absMax`, `min`, `max`, `clamp`, `map`, `clampedMap`, `inverseLerp`, `clampedLerp`, `lerp2`, `lerp3`, `smoothstep`, `square`, `pow` with dynamic exponents (2 and 3 use multiplication mappings), `lerp`, `floor`, `ceil`, `sqrt`, `sin`, `cos`, `exp`, `log`, `atan2`, `signum`, and `tanh`), forward conditional branches, statically bounded loops (at most 64 iterations), bounded integer table/lookup switches, Java double and float remainder, Java 32-bit add/subtract/multiply/negate, bitwise AND/OR/XOR, signed/unsigned shifts, and byte/char/short narrowing, plus dynamic IEEE double division and integer divide/remainder by a compile-time nonzero divisor. `Math.floorDiv` and `Math.floorMod` lower with Java negative-coordinate rounding and remainder rules when their divisor is compile-time and nonzero. Local integer increments lower to wrapped 32-bit addition. Integer graph nodes implement Java overflow and the `MIN_VALUE / -1` edge case. Float arithmetic is represented as per-instruction float operations that round to 32 bits; integer/float/double conversions have explicit IR nodes. Branches lower to explicit comparison and select nodes; float/double compare nodes retain the JVM `FCMPL`/`FCMPG` and `DCMPL`/`DCMPG` NaN ordering. CPU reference evaluation uses Java arithmetic; GPU transcendental instructions use supported Vulkan GLSL float intrinsics for sin, cos, exp, log, atan2, pow, and tanh, then widen results into the double-valued expression IR; square root retains double precision. Their reduced precision remains subject to per-pipeline CPU/GPU parity checks and can make a pipeline ineligible when it exceeds tolerance. Dynamic Java power nodes cover negative bases, odd/even integer exponents, fractional-exponent NaN results, signed zero, and infinities in the shader helper. CI smoke coverage compares nested-helper output to Java at positive and negative coordinates and sends nested conditionals, noise-based branches, switch expressions, integer overflow, bitwise/shift hashing, narrowing, float rounding and casts, bounded loops, remainder, and scalar math through graph compilation, lowering, packing, and Lavapipe dispatch. Dynamic loops, loops with data-dependent exits or dynamic branches/switches in the loop body, switches over more than 64 cases, wide-integer arithmetic, integer division by a dynamic or zero divisor, object comparisons, unsupported conversions, and opaque external calls still reject the whole method. Rejections retain the vanilla function as the source fallback and include a concrete diagnostic. This does not yet translate full custom terrain generators, aquifers, or surface rules.

Lavapipe smoke dispatch additionally checks `Math.floorDiv` and `Math.floorMod`
at negative and positive block coordinates around 16-block boundaries, using
both positive and negative divisors. Dynamic divisors remain on the Java path.

## Immediate implementation sequence from the source inventory

1. **Prove and harden aquifer substance dispatch.** At the active aquifer decision boundary, gather density (including structure beardifier), barrier, fluid levels/types, four nearest-center distances and status-equality data, global fluid state, below-block lava status, and nearest state ID. Submit contiguous records as a batch. Consume only parity-verified results whose density exactly matches the live call; use the active Java implementation for unsupported inputs and `CPU_FALLBACK` records. CI must demonstrate nonempty batches and zero accepted-decision mismatches across the terrain-mod profiles.
2. **Unify the per-block input payload.** Extend the current interpolation output from final density to all roots needed by block decisions and aquifers. Keep field order and precision in a versioned Java/GLSL ABI. The batch planner must account for the enlarged output before enqueueing work, and must split batches without breaking completion ordering.
3. **Expand the block decision payload.** Resolve compact IDs to the exact registered `BlockState` objects on the CPU side, then have the GPU choose air/default solid/fluid/lava or a supplied exact state ID. Carry the scheduled-fluid-update bit. Preserve heightmap and post-processing semantics during commit.
4. **Move the fill loop to payload consumption.** Replace per-block density graph and aquifer callbacks with indexed payload reads. Keep structure beardifier and any opaque mod hook explicit. Validate every output before writing any section so a GPU failure can safely use vanilla without mixing partial writes.
5. **Add surface-rule compilation and final parity.** Include biome and surface-rule inputs in the plan, compile supported vanilla rules, and compare complete generated chunk states against CPU output. Unsupported mod rules select the explicit CPU stage or whole-chunk fallback.

Each item spans the compiler, scheduler, shader ABI, and worldgen integration; none should be treated as complete by merely enabling the current diagnostic full-block-output flag.
