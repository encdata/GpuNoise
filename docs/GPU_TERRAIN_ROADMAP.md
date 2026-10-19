# GPU terrain generation roadmap

## Target

Move the complete noise terrain chunk path onto Vulkan: evaluate density and
aquifer inputs, interpolate cell values, choose solid/air/fluid results, and
apply terrain surface rules. Keep Minecraft's chunk objects, palettes, and
heightmaps coherent when the GPU results are committed. Support custom terrain
functions from other mods through explicit, checked adapters.

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

An adapter must describe behavior and data, not pass arbitrary Java bytecode
to the GPU. Java functions may call arbitrary methods, access mutable objects,
or have side effects, so there is no safe general conversion of every Java
method into a Vulkan shader. The supported extension surface should let mods
describe their custom math in the IR and provide a reference evaluator for
parity checks.

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

### 2. Versioned graph compiler and custom density API

- Treat the current graph serializer as an initial IR version, then make node
  types and their parameters explicit instead of relying on class names and
  positional float arrays.
- Add per-function translator registration, a built-in operation catalog,
  compile diagnostics, and capability checks. The first adapter API can lower
  custom functions to supported Minecraft density operations. Registration is
  now frozen when graph compilation begins so worldgen workers see a stable
  adapter set. Translator
  registration now supports exact classes and mod API supertypes; exact matches
  win, the closest supertype wins, and equally specific conflicts fail loudly.
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
  unreachable nodes and samplers removed before lowering. Graph-shape checks
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
- Custom density compilation now also inspects `compute(FunctionContext)` JVM
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
multiple submissions. Mesa smoke sends and parity-checks full-height batches
for both kernels. This removes avoidable per-dispatch synchronization without
treating software Vulkan timing as evidence of hardware speed.

The terrain roots batcher now reuses a bounded pool of direct metadata buffers
until each asynchronous Vulkan submission completes. This removes per-dispatch
native metadata allocation without letting an in-flight submission observe
overwritten records. To amortize host-to-device submission overhead, the
current experiment targets 96 compatible chunks (within a 100-chunk ceiling)
and collects for at most 50 ms; the target is reduced to the selected grid's
actual buffer capacity so impossible batch sizes do not consume the full
window. It no longer dispatches early after a single 25-chunk prefetch tile.
This is a runtime scheduling configuration, not a CI performance measurement.
Dedicated-server profiles can report CPU preparation/upload/command-recording
time, submit-to-fence time, readback time, batch fill, and per-chunk latency.
Lavapipe remains a CPU Vulkan backend and cannot establish discrete-GPU speed.

The optional fused chunk scheduler now coalesces requests for at most one
millisecond, retains incompatible FIFO requests without racing queue producers,
validates each request against input/output capacities before enqueueing, and
returns its host slab even when asynchronous result handling fails. Its
periodic batch-fill and latency counters will show whether this path is
dispatch-bound or request-arrival-bound; CI results are still pending.

## Known precision boundary

The current point-sample and terrain-grid shaders pass world coordinates into
`NormalNoise` as 32-bit floats. A Lavapipe differential sample at coordinates
near 2^24 diverged from Java's double-coordinate noise result by about 0.018,
even though the Java bytecode branch lowering was correct. The branch smoke now
uses ordinary-sized world coordinates so it can validate branch semantics
independently. Large-coordinate noise parity remains an open correctness item:
either carry coordinates and octave transforms in double precision through
noise wrapping, or detect an unsafe range and keep that chunk on the Java path.
Do not treat ordinary-coordinate smoke success as proof of parity near the
world border.
