# GPU terrain generation roadmap

## Target

Move the complete noise terrain chunk path onto Vulkan: evaluate density and
aquifer inputs, interpolate cell values, choose solid/air/fluid results, and
apply terrain surface rules. Keep Minecraft's chunk objects, palettes, and
heightmaps coherent when the GPU results are committed. Discover and compile
supported custom terrain functions automatically; an explicit adapter remains
an optional escape hatch for behavior outside the automatic compiler.

The target is semantic equivalence first, then throughput. A claimed target of
1,000 chunks per second is a benchmark goal, not a promise for every GPU,
modpack, world seed, or terrain configuration.

## Backend shape

Keep game-object access on the worldgen side and make the GPU boundary a data
contract:

1. A Minecraft/mod front end walks a density or surface-rule graph.
2. The compiler produces a versioned, typed GPU IR with explicit inputs,
   outputs, samplers, and capability requirements.
3. Lowering validates graph bounds, cycles, register pressure, and fallback
   coverage before dispatch.
4. The Vulkan backend compiles or selects a shader variant and runs bounded
   chunk tiles through persistent buffers.
5. A result validator compares GPU output against Minecraft's Java behavior
   before the GPU path is enabled for that graph.
6. The chunk writer commits the resulting states and updates the same counters,
   heightmaps, and fluid data that vanilla expects.

The automatic bytecode frontend accepts only complete, bounded, side-effect
free Java behavior that it can represent in the GPU IR. Java functions may
call opaque methods, access mutable objects, or have side effects, so there is
no safe general conversion of every Java method into a Vulkan shader. Those
functions retain vanilla CPU behavior and a precise compiler diagnostic.
Explicit IR adapters can extend coverage, but other mods are not required to
register with GPU Noise.

## Passes

### 1. Dedicated-server correctness gate

- Start dedicated NeoForge servers with the pinned C2ME and Chunky versions.
- Check startup, graph parity, GPU cache consumption, CPU fallback behavior,
  terrain generation completion, crashes, and shutdown.
- Use Mesa Lavapipe to validate shader compilation and Vulkan behavior. CI does
  not collect or compare CPS and does not represent physical-GPU performance.
- Preserve server logs, crash reports, versions, and benchmark configuration
  with each CI artifact.
- Compare saved CPU/GPU Anvil block-state palettes and scheduled fluid ticks
  for the same seed and mod profile after both servers stop. Report the first
  differing world coordinate and states. Keep this as correctness evidence,
  not a throughput measurement.

### 2. Versioned graph compiler and automatic custom density translation

- Treat the current graph serializer as an initial IR version, then make node
  types and their parameters explicit instead of relying on class names and
  positional float arrays.
- Compile supported custom `compute(FunctionContext):double` bytecode directly
  into the built-in operation catalog, with source-level diagnostics and
  per-backend capability checks. Optional adapters may lower opaque mod
  behavior to an equivalent graph when bytecode analysis cannot prove it.
- Add a typed expression builder for custom noise algorithms that cannot be
  represented by vanilla `DensityFunctions`, including explicit sampler
  inputs and coordinate transforms. Minecraft `NormalNoise` samplers are now
  explicit expression inputs; custom sampler algorithms and resource types
  remain future work.
- The first direct expression IR now has typed X/Y/Z inputs, finite constants,
  explicit `NormalNoise` inputs, binary arithmetic, clamp, and the shader's
  absolute, square, cube, half-negative, quarter-negative, and squeeze mappings,
  comparison/select operations for bytecode branches, with finite constant
  subgraphs folded, identical pure nodes shared, and
  unreachable nodes and samplers removed before lowering. Graph lowering also
  drops transparent adapter/delegate markers and statically selects constant
  `RangeChoice`/select branches before visiting their dead subgraphs; it keeps
  interpolation markers because they are runtime data inputs. This follows the
  useful compiler-pass pattern seen in C2ME's OpenCL path while preserving
  GPU Noise's existing register and interpolation ABI. Graph-shape checks
  validate the compact IR.
  before packing. A Java IR evaluator now provides an independent numerical
  reference; it does not yet prove an adapter matches its original Java
  function. The point-sample Vulkan shader understands its coordinate and
  arithmetic opcodes. Division follows Java IEEE behavior, including zero
  divisors. Terrain-grid compilation lowers constants, exact X/Y/Z coordinate nodes,
  `NormalNoise`, clamp, mapping, arithmetic, comparisons, and selections into
  shader operations. Dynamic double division now uses the shader’s IEEE
  floating-point path; integer division still requires a constant nonzero
  divisor. Differential checks against each
  adapter's original Java function remain necessary for each newly supported
  operation before broad modpack terrain graphs can be enabled by default.
- Custom density compilation inspects `compute(FunctionContext)` JVM
  bytecode after explicit translators. It recursively inlines bounded pure
  same-class helpers and static helpers in dependency classes, carries final
  `DensityFunction` fields as graph dependencies, snapshots final instance
  arrays at constant indices, and reads final static primitive values.
  Bounded forward branches become compare/select nodes; bounded integer
  table/lookup switches lower through the same IR. The compiler recognizes
  recognizes `floor`, `ceil`, `sqrt`, `sin`, `cos`, `exp`, `log`, and the
  `Mth.map`, `clampedMap`, `inverseLerp`, `clampedLerp`, `lerp2`, and
  `lerp3`, plus `absMax`, `smoothstep`, `atan2`, `pow`, `signum`, and `tanh`.
  Rejections now include the custom class, method descriptor, bytecode index,
  and unsupported call or instruction so mod authors can target adapters at
  actual compatibility gaps.
  It also lowers double and float `Math.min`/`Math.max`/`Math.abs`, preserves
  float `Mth.square` rounding, and handles `Mth.clamp` with dynamic float or
  double bounds through compare/select nodes. Minecraft `Mth` float sine and
  cosine, float/double floor and ceiling to saturated Java integers, integer
  absolute value, and wrapping integer square overloads are lowered explicitly.
  `Mth.sin` and `Mth.cos` use dedicated float-rounded IR mappings, distinct
  from double `Math` operations; `Mth.sqrt(float)` has its own float-rounded
  mapping. Float `Mth.lerp`, `clampedLerp`, `inverseLerp`, `map`, and
  `clampedMap` lower each JVM float arithmetic step with explicit rounding.
  `Mth.frac(float)`, float/double `positiveModulo`, and `wrapDegrees` lower
  with Java remainder and their original float rounding points, covering
  common angular and periodic terrain transforms.
  `Mth.triangleWave(float,float)` lowers as explicit rounded JVM float
  operations and has an isolated dynamic-period parity fixture.
  Float/double `rotLerp` and float degree-difference, rotation-clamp, and
  approach helpers compose the same explicit wrap, compare, select, and
  per-operation float IR used by their standalone primitives.
  Double 2D/3D `Mth.length` and `lengthSquared`, plus float 3D
  `lengthSquared`, lower to fused graph arithmetic and retain sequential JVM
  rounding for the float overload.
  `lerpInt` and `lerpDiscrete` preserve their wrapped integer subtraction,
  float multiplication and floor, and positive-delta selection semantics.
  The terrain roots queue's batch window is now configurable from 1–250 ms;
  CI correctness runs use 50 ms and verify that the selected batch
  configuration reaches the dedicated server. Batch fill and dispatch latency
  remain available for local runtime profiling; CI does not collect CPS.
  Retained terrain caches now have a bounded live budget (default 32, tunable
  with `gpunoise.terrainCacheBudget`); speculative tile prefetch skips work
  when the budget is full, while requested chunks apply backpressure until a
  cache is released. Independent chunk caches can prepare concurrently, with
  CPU-heavy strict dependency construction capped separately (default is half
  the reported processor count, clamped to 1–4; tunable up to eight with
  `gpunoise.terrainCacheBuildConcurrency`). Speculative cache builds never wait
  for a build slot. The roots batcher refreshes its spatial candidate scan at
  bounded intervals while collecting arrivals and makes the final candidate
  choice from a fresh scan before dispatch. The roots queue defaults to 512 pending grids rather than
  allowing a large producer burst to accumulate thousands of requests. These
  limits protect full-block output from exhausting the heap under parallel
  chunk generation; tune them against the target server's memory and profiling
  data. Per-block result packing now includes only roots that passed graph and
  runtime parity checks. Unsupported or unverified roots are read from the
  corner grid or use their Java fallback, while the compact root map preserves
  router-root identity. This reduces readback and raises the dispatch capacity
  when a modded graph supports only part of the router. The single Vulkan
  device slot also bounds aquifer decision-table producers to three by default
  (tunable with `gpunoise.aquiferDecisionConcurrency`,
  capped at eight). This allows CPU preparation to overlap without letting all
  worldgen workers allocate chunk-sized decision and candidate buffers while
  waiting for the same slot. Full per-block output's mapped Vulkan buffer now defaults
  to 32 MiB (tunable from 16–64 MiB with `gpunoise.terrainOutputBufferMiB`),
  raising the dispatch ceiling above the previous three full-height chunks;
  the effective capacity is logged at startup.
  An opt-in `gpunoise.fastMath` shader variant rounds generic density add,
  subtract, multiply, and divide through FP32 while keeping the default
  FP64 path intact. CI runs the full Vulkan parity fixtures and terrain-grid
  parity gate on FP32 before enabling it for terrain work.
  `Math.signum(float)` and
  `Math.signum(double)` retain their respective input precision.
  Minecraft's indexed sine table is generated once and uploaded to persistent
  graph storage, preserving vanilla's angle quantization without per-dispatch
  table transfers.
  GPU min/max nodes follow Java NaN propagation and signed-zero ordering rather
  than GLSL's implementation-defined NaN selection behavior.
  Java double division and double/float remainder are represented explicitly;
  integer division and remainder require a compile-time nonzero divisor. Java
  32-bit arithmetic, bitwise AND/OR/XOR, signed/unsigned shifts, and
  byte/short/char narrowing preserve integer semantics in dedicated IR nodes. Float arithmetic
  uses a dedicated per-instruction IR that rounds at each JVM float operation;
  int/float/double casts have explicit nodes. Transcendental GPU operations are
  validated against the Java CPU reference with the configured parity
  tolerance.
  `FCMPL`/`FCMPG` and `DCMPL`/`DCMPG` NaN ordering is retained. Loops with
  statically known exits unroll up to 64 iterations; dynamic loops and
  dynamic branches inside loop bodies remain CPU fallbacks. No mod Java is
  executed from a shader. Mesa smoke fixtures dispatch transcendental math,
  power and sign operations, Minecraft mapping functions, interpolation,
  float overloads, and dynamic clamps independently so a numerical mismatch
  identifies its operation group instead of failing an opaque composite graph.
  Shader `atan2` handles Java's signed-zero, zero-pair, infinity, and NaN cases
  before using the GPU intrinsic for ordinary finite inputs.
  The Mesa smoke path also checks
  nested ternaries, a noise-dependent floating branch, and a switch expression
  through lowering, packing, and dispatch against Java results. This grows the
  automatic compiler while preserving the vanilla implementation as oracle.
- Retain the original Java function as the reference evaluator. Compare outputs
  over boundary, negative-coordinate, high-altitude, and random samples.
- Startup terrain-root parity uses a deterministic 8x8x8 coordinate set spanning
  negative and positive X/Z, zero boundaries, underground values, and high Y;
  the full graph check uses a wider 16x16x16 volume. Shared comparison logic
  treats mismatched NaN and infinity classifications as infinite error, so
  non-finite results cannot disappear from a max-error reduction. Final-density
  verification also requires identical positive-versus-nonpositive decisions,
  even when the numeric difference is within tolerance. Lavapipe smoke
  assertions cover these rules and the startup sample range.
- Allow unsupported nodes to form explicit CPU islands in a dependency graph
  when their GPU consumers can accept CPU-produced inputs. Never silently
  replace an unknown function with an approximation.

### 3. GPU cell-grid evaluation and interpolation

- Evaluate all required terrain and aquifer roots in one chunk-grid dispatch.
- Keep the lattice layout identical to `NoiseChunk`; verify axis order,
  inclusive boundary samples, cell dimensions, and output stride.
- Move cell-to-block interpolation to Vulkan and validate every generated
  block-density value against the Java interpolation path.
- Batch independent chunks in bounded tiles, reuse mapped allocations, and
  avoid per-chunk or per-function buffer creation.

### 4. GPU block and fluid decisions

- Port the density-to-substance decision with exact handling for solid blocks,
  air, water, lava, aquifer barriers, pressure, and lava scheduling.
- Prepare aquifer random-cell metadata on the CPU where Minecraft object or
  random-state access is required, then pass immutable numeric records to the
  shader. Validate random sequences and neighborhood boundaries exactly.
- Start from `Aquifer.NoiseBasedAquifer.computeSubstance` for the pinned game
  version. Preserve its order: positive density returns the caller's default
  solid; lava from the global picker wins for non-positive density; otherwise
  choose four nearest aquifer centers, evaluate their fluid statuses and
  similarities, test barrier pressure, and return the selected status or air.
  Keep the flowing-fluid update bit alongside the result; it affects later
  chunk fluid scheduling even when the chosen block state is unchanged.
- Use integer world coordinates and exact floor division for aquifer grid
  coordinates. The four nearest random centers are sampled from the 2x3x2
  neighborhood using 16x12x16 grid spacing and bounded offsets (10, 9, 10).
  Stable tie handling matters because the third-nearest selection feeds both
  similarity and pressure decisions.
- Port `similarity(d1,d2) = 1 - abs(d2-d1)/25` and vanilla pressure as its own
  validated shader operation. Pressure includes the water/lava interface
  special case, fluid-level midpoint and distance terms, a lazily evaluated
  barrier-noise value, and the final `2 * (barrier + adjustment)` result.
  Do not approximate this with density thresholds or sampled aquifer noise.
- The first standalone pressure kernel is now wired to the persistent Vulkan
  engine and exercised by the Lavapipe smoke task against a Java reference.
  Its 32-byte records carry Y, two levels, two fluid kinds, and the already
  evaluated barrier value. The current shader uses 32-bit floats for this
  component; it is a validated kernel boundary, not yet eligible to decide
  blocks near a pressure threshold until precision behavior is compared with
  vanilla's double calculation and a safe threshold fallback is implemented.
- A second standalone kernel selects the four nearest centers from the exact
  12 candidates. Its packed query carries xyz plus 12 center xyz records;
  output carries selected indices and squared integer distances. Selection
  preserves vanilla's strict first/second comparisons and inclusive third
  comparison. One shared positional-random helper now builds centers for both
  this kernel's future inputs and the existing strict dependency planner, so
  candidate coordinates use one implementation. Mesa smoke checks ordinary
  and equal-distance cases against the Java reference. Strict dependencies
  can validate that all 12 centers were declared, then submit bounded batches
  through the Vulkan ring after pending graph-root work completes. The smoke
  also overlaps a graph-grid request with a nearest-center request to verify
  command-buffer serialization.
- A block-substance shader now folds nearest statuses into vanilla's
  solid/null, global-lava, closest-fluid, water-over-lava, pairwise pressure,
  and fluid-update decisions. Its records carry registry state IDs and
  pressure inputs. Near-zero results are marked for exact CPU resolution
  because the shader uses float inputs while vanilla computes pressure in
  double precision. The chunk writer does not consume this output yet.
  Full-chunk substance batches now pack directly from the shared pending list,
  removing the per-dispatch temporary reference array before the direct-buffer
  upload. The Vulkan record layout is checked against array packing in smoke.
- Keep fluid picking, preliminary surface sampling, surface level selection,
  and fluid type selection as explicit inputs/operations. These consult
  `NoiseChunk`, density roots, and world settings; the shader must not infer
  them from one generic aquifer vector. Begin with CPU-prepared status records
  for the exact chunk, then move individual pure numeric operations only when
  their input contract is complete.
- Define the first packed output as a material/fluid registry key plus flags
  for null/default-solid, explicit air, water/lava, and scheduled fluid update.
  A null substance is not the same value as an explicit air block. Resolve
  registry objects and write sections on the CPU until whole-section and
  heightmap parity is established.
- Emit compact material/fluid IDs rather than returning one float per block.
- Differentially compare complete chunk sections and heightmaps before
  enabling GPU writes. Keep CPU section writes as the first safe commit path;
  optimize palette updates only after state parity passes.

### 5. GPU surface material selection

- Compile supported `SurfaceRules` conditions and sequences into a separate
  rule IR. Include biome, depth, water, noise, y, and stone-depth inputs.
- Add explicit adapters for modded surface conditions and state suppliers.
- Return block-state registry IDs plus required properties from the shader;
  validate IDs against the world's registry before writing sections.
- Handle unsupported rule nodes with a measured CPU surface pass while keeping
  the density, interpolation, and block classification outputs on the GPU.

### 6. Chunk commit and worldgen integration

- Integrate with C2ME's staged chunk flow without blocking worldgen worker
  threads on Vulkan fences.
- Keep structure placement, carvers, and feature placement ordered according
  to Minecraft's chunk status pipeline. Add further GPU stages only where
  their data and random semantics can be represented exactly.
- Test cancellation, server shutdown, cache release, overlapping chunk batches,
  dimensions, blending, and modded generator fallback.

### 7. Modpack coverage and performance tuning

- Publish a stable adapter API and small examples for custom density functions,
  noise samplers, and surface rules.
- Add compatibility fixtures for popular terrain mods and combinations, not
  only isolated functions. A graph is eligible only when all roots required by
  the active GPU stages pass validation or have explicit fallback islands.
- Profile shader occupancy, graph register pressure, transfer volume, queue
  latency, and CPU chunk-commit cost on several physical GPU vendors.
- Tune batch size, dispatch scheduling, memory pools, and shader variants from
  dedicated-server profiling on physical GPUs; keep correctness CI independent
  from speed claims. CPU fallbacks and parity failures remain visible in tests.

## Current position

The mod currently has a graph evaluator for density and aquifer roots, a cell
grid path, GPU final-density interpolation for every block, and an adapter that
translates supported custom density functions into GPU graphs. The aquifer
substance kernel is now connected to `NoiseBasedAquifer.computeSubstance` for
eligible full-block caches: Java prepares fluid statuses and exact state
objects, Vulkan chooses the substance outcome, and Java retains the chunk
writer. Structure beardifier values are included in precomputation, and a
prepared result is used only when its density exactly matches the live Java
call. Unsupported cases continue through vanilla.

The aquifer substance pipeline follows all three ordered pressure checks from
the pinned `NoiseBasedAquifer.computeSubstance` implementation: nearest /
second, nearest / third, then second / third. Mesa smoke exercises each pair,
pressure ties, global lava, water over lava, and CPU fallback thresholds. This
validates the kernel reference cases, while dedicated-server profiles must
still prove that the integrated batches execute with nonzero samples and zero
vanilla parity mismatches. Failure logs now include the first mismatching query
and expected/GPU decisions to make any discrepancy actionable.

Substance and nearest-center batches use the terrain engine's 262,144-record
capacity. The nearest-center input/output buffers account for its wider records.
Each stage can process a 147,456-block, 576-high modded chunk in one shader
dispatch and one fence/readback round trip instead of splitting the stage into
multiple submissions. Nearest-center inputs now store each block's point and a
neighborhood index once, then upload each shared 12-center neighborhood once;
the per-block query record shrinks from 208 bytes to 16 bytes for the common
shared-neighborhood case. Mesa smoke sends and parity-checks full-height batches
for both kernels and asserts that shared centers are compacted. This reduces
transfer volume and avoidable synchronization without treating software Vulkan
timing as evidence of hardware speed.

Aquifer substance outputs use a chunk-local byte state palette, with action
and fluid-tick bits in the same 16-bit decision. Vulkan packs two decisions
into each 32-bit transfer word; Java expands the decisions and resolves palette
slots before the CPU writer applies them. If one batch contains more than 256
output states, only rows whose state cannot be mapped request exact Java
evaluation. This ports OCL's compact device/host state boundary while
preserving the Minecraft-owned chunk writer and an explicit overflow path.

The submission ring also coalesces compatible, already-queued aquifer
nearest-center, combined nearest/status prefill, and substance batches from
multiple chunks into capacity-bounded Vulkan dispatches. Nearest query indices
are rebased when payloads merge; output is sliced back to each chunk, and
substance palettes stay chunk-local. Combined staging slabs return to the pool
only after readback. The profiler reports actual dispatch sizes and how many
chunk requests were joined, so dedicated-server traces can show whether region
concurrency is feeding the device often enough to make this useful.

### Aquifer CPU/GPU synchronization boundary

The C2ME aquifer path currently has two GPU submissions per chunk decision
batch. The first Vulkan command buffer dispatches nearest-center selection and
fluid-status prefill, then performs one final readback. Java maps statuses to
Minecraft block states, resolves center references, and packs substance rows;
the second Vulkan command buffer evaluates those rows and reads back compact
decisions. The standalone vanilla aquifer path skips fluid-status prefill but
still reads nearest-center rows before Java packs the substance inputs. This
means input staging and Minecraft state resolution, rather than shader
execution alone, determine whether the stages can be fused.

The next synchronization reduction is a GPU-resident decision chain: upload
query metadata, center/status inputs, and chunk-local state mappings once;
dispatch nearest-center ranking; make its output visible to subsequent compute
stages with a shader-write to shader-read barrier; resolve center statuses and
evaluate substance decisions without mapping intermediate buffers to Java;
then read back only compact final decisions and any rows explicitly marked for
vanilla fallback. The status contract must carry exact block-state IDs as well
as fluid kinds and levels, because `OTHER_FLUID` cannot preserve custom fluid
identity or status equality. Cached statuses and shared-center indices must
refer to stable rows in the same upload. Any unsupported fluid picker,
uncertain float boundary, state-palette overflow, or failed parity check must
mark only affected rows for exact vanilla evaluation.

The fluid-status ABI now includes exact default, neighbor, and lava state IDs
in both its input records and five-word output rows. Java cache reconstruction
uses the returned state ID, and the Mesa smoke fixture includes a custom state
ID so the new field is parity-checked before the decision-chain shader consumes
it.

Nearest-query records now also carry packed density and barrier floats plus
below-lava and C2ME mode bits. Their center records reserve a stable status-row
index, and coalescing preserves all of that metadata. Fluid-status records can
carry an exact precomputed cache row; the shader copies its level, state ID, and
kind through without recomputing terrain roots. The next integration step is
to stage every neighborhood status row, populate those indices, and consume
nearest/status outputs in a final decision dispatch before the sole readback.

Do not enable this chain until the CPU reference and Lavapipe fixtures compare
all four ranked centers, selected states, status-equality cases, fluid update
flags, custom-fluid IDs, palette overflow, and fallback rows. The current
two-submission path remains the correctness reference while the chained ABI
and shader are introduced. This work reduces one intermediate device-to-host
readback and one host-to-device upload; it does not change Minecraft's CPU
chunk writer.

The terrain roots batcher now reuses a bounded pool of direct metadata buffers
until each asynchronous Vulkan submission completes. The scheduler uses the
same 4x4 aligned chunk regions as C2ME-OCL, with a 2x2 mode and bounded
adjacent-region search available for smaller or sparse workloads. The selected
region is further capped by the actual grid-buffer capacity. This is a runtime
scheduling configuration, not a CI performance measurement. Dedicated-server
profiles can report CPU preparation/upload/command-recording time,
submit-to-fence time, readback time, batch fill, and per-chunk latency.
Lavapipe remains a CPU Vulkan backend and cannot establish discrete-GPU speed.

The optional fused chunk scheduler now coalesces requests for at most one
millisecond, retains incompatible FIFO requests without racing queue producers,
validates each request against input/output capacities before enqueueing, and
returns its host slab even when asynchronous result handling fails. Its
periodic batch-fill and latency counters will show whether this path is
dispatch-bound or request-arrival-bound; CI results are still pending.

C2ME OpenCL specializes an optimized density AST into generated source and
combines it with area-aligned batches and reusable buffers. GPU Noise now uses
the same compile-at-world-graph strategy for Vulkan: supported custom Java
density methods lower automatically into the validated IR, and runtime
shaderc emits a graph-specific GLSL evaluator with one static instruction per
IR node. The per-sample dynamic opcode switch and graph-node fetch are removed.
Mesa Lavapipe compiles and executes this generated path in worldgen CI. The
chunk writer remains Minecraft-owned; Vulkan produces parity-checked density
and interpolation data for the existing CPU terrain stages.

## Large-coordinate precision

Terrain-grid and block-evaluation noise now retain integer world coordinates
as doubles through octave scaling, Minecraft's period wrapping, and Improved
Noise interpolation. A Lavapipe smoke fixture compares custom transformed
`NormalNoise` graphs against Java at and beyond 2^24, including the world
border. The shader retains the previous float fast path within a bounded
near-spawn range, then switches to double precision at larger coordinates.
Direct point-sample requests still receive float coordinate arrays and cannot
represent every integer in that range; terrain generation uses the
exact-coordinate grid path. The precision fallback's throughput must be
measured on physical GPUs rather than inferred from Lavapipe.
