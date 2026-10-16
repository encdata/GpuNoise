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

### 1. Stable benchmark and correctness gate

- Keep paired CPU and GPU runs on the same Minecraft version, seed, world
  settings, mod set, Chunky area, and C2ME configuration.
- Record generated chunks, exact elapsed time, CPS, GPU submissions, consumed
  caches, CPU fallbacks, and parity results.
- Separate Mesa correctness runs from hardware performance runs. Lavapipe
  validates shader behavior and Vulkan plumbing; it is not a GPU speed result.
- Repeat runs and report medians plus spread so JVM warmup and task order do not
  decide the result.
- Preserve server logs, crash reports, versions, and benchmark configuration
  with each CI artifact.

### 2. Versioned graph compiler and custom density API

- Treat the current graph serializer as an initial IR version, then make node
  types and their parameters explicit instead of relying on class names and
  positional float arrays.
- Add per-function translator registration, a built-in operation catalog,
  compile diagnostics, and capability checks. The first adapter API can lower
  custom functions to supported Minecraft density operations.
- Add a typed expression builder for custom noise algorithms that cannot be
  represented by vanilla `DensityFunctions`, including explicit sampler
  inputs and coordinate transforms.
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
  choose the nearest three aquifer centers, evaluate their fluid statuses and
  similarities, test barrier pressure, and return the selected status or air.
  Keep the flowing-fluid update bit alongside the result; it affects later
  chunk fluid scheduling even when the chosen block state is unchanged.
- Use integer world coordinates and exact floor division for aquifer grid
  coordinates. The three nearest random centers are sampled from the 2x3x2
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
- A second standalone kernel selects the nearest three centers from the exact
  12 candidates. Its packed query carries xyz plus 12 center xyz records;
  output carries selected indices and squared integer distances. Selection
  preserves vanilla's strict first/second comparisons and inclusive third
  comparison. One shared positional-random helper now builds centers for both
  this kernel's future inputs and the existing strict dependency planner, so
  candidate coordinates use one implementation. Mesa smoke checks ordinary
  and equal-distance cases against the Java reference.
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
  measured end-to-end chunks per second. Track both median throughput and
  tail latency; CPU fallbacks and parity failures remain visible in results.

## Current position

The mod currently has a graph evaluator for density and aquifer roots, a cell
grid path, optional per-block density interpolation, and a new adapter that
translates custom density functions into supported Minecraft density graphs.
The GPU still does not choose final solid, air, fluid, or surface block states.
Those remain the next major terrain-generation passes.
Aquifer pressure and nearest-three candidate selection now have packed input
records, persistent Vulkan buffers and pipelines, and differential Mesa smoke
cases. Neither kernel is connected to `NoiseBasedAquifer.computeSubstance`;
fluid status construction, final substance output, and chunk state writes
remain CPU-owned.

The first paired C2ME/Chunky CI run completed 289 chunks on each path: CPU
36.125 CPS and Mesa Lavapipe 26.273 CPS (0.727x CPU), with 319 GPU caches
consumed and no fallbacks. This is a coarse historical baseline from commit
`b50cba6`; Chunky reported only whole-second task durations. Later multi-cell
interpolation checks exposed axis and lattice-order bugs, so this measurement
does not validate the current shader.

The corrected shader passed the multi-cell Vulkan smoke and two precise paired
289-chunk runs. The latest measured 49.829 CPU CPS and 39.603 Lavapipe CPS
(0.795x CPU), with 318 GPU caches consumed and no fallbacks. The prior run
measured 32.468 CPU CPS and 20.943 Lavapipe CPS (0.645x CPU). This spread shows
the small area is too noisy for tuning. CI has now completed a 1,089-chunk
paired run: CPU 34.903 CPS, Lavapipe 22.927 CPS (0.657x CPU), 1,214 caches
consumed, and no fallbacks. The multi-cell shader smoke passed on the same
commit. Lavapipe validates Vulkan behavior and overhead; physical-GPU
performance still needs separate measurements. CI times Chunky's streamed
task start and finish at sub-second precision.

The first adaptive-capacity run completed 1,089 chunks at 42.533 CPU CPS and
32.510 Lavapipe CPS (0.764x CPU), with zero fallbacks. Despite raising the
dispatch ceiling to 100 chunks and bounding it by output-buffer capacity, the
scheduler averaged only 1.82 chunks per dispatch. Queue arrival timing, rather
than Vulkan buffer capacity, is currently preventing large batches. A follow-up
60 ms coalescing window raised the average to 2.72 chunks but measured 22.783
Lavapipe CPS, so the longer wait was removed. The current pass prefetches each
aligned 5x5 chunk tile once, then batches those ready grids with a 20 ms window;
tile bookkeeping is bounded to 4,096 entries.

The first tile-prefetch CI run passed shader smoke and completed the 1,089
chunk pair at 60.842 CPU CPS and 45.190 Lavapipe CPS (0.743x CPU). It consumed
1,207 GPU caches with no fallbacks and averaged 15.125 chunks per dispatch,
compared with 1.82 before prefetch. A repeated run on the aquifer pressure
kernel commit measured 34.683 CPU CPS and 21.608 Lavapipe CPS (0.623x CPU),
with 1,202 caches consumed, zero CPU fallbacks, and 14.6875 average chunks per
dispatch. That run passed the pressure-kernel smoke. Runner variation is
large, and the standalone kernel is not part of the chunk hot path, so these
measurements do not establish a performance change from the kernel. Current
throughput measures density/interpolation under Mesa Lavapipe, not GPU-selected
block states or physical GPU performance.
