# C2ME OCL terrain pipeline port map

This document records the implementation details observed in the local
`C2ME-fabric/c2me-opts-accel-opencl` source and maps them to GPU Noise. It is a
working port guide: the target backend is Vulkan, and Minecraft chunk objects
remain owned by the worldgen thread for their final state updates.

## OCL generation path

`OpenCLCGen.compile` compiles the router roots, final density, noise kernels,
and biome search into one world-specific generated program. The world context
compiles that program asynchronously for each available device. Device/queue
selection favors the device with the most available permits, and generation
waits asynchronously for a queue instead of blocking the worldgen worker.

`CLServerBatchedBiomeNoiseContext.execute` handles an aligned 4x4 region as a
unit. It packs Java-owned inputs and cache data into one working buffer, then
orders interpolator/cache prefill, aquifer prefill, full-volume noise
generation, and biome search on one queue with event dependencies. The noise
kernel covers every block in the region and writes compact block-state IDs to
one result buffer. A single asynchronous readback precedes the CPU commit.

The commit walks vertical section layers across region tiles, updates each
chunk's palette container, recalculates section counts, and populates
heightmaps. OCL also precomputes surface-height estimates for an expanded area
and passes those values into `ChunkNoiseSampler`'s cache.

## GPU Noise mapping

| OCL stage or tactic | GPU Noise state |
| --- | --- |
| Generated, world-specialized density program | Density graph compiler and persistent Vulkan engine compile graph roots. Custom Java bytecode has bounded automatic translation and diagnostics. |
| Aligned region generation | `GpuTerrainRootsBatchScheduler` batches up to a 4x4 area. `GpuTerrainDecisionWriter.writeRegion` groups CPU writes by the same region. |
| Full-volume noise evaluation and cell interpolation | One Vulkan terrain-grid command buffer evaluates the graph lattice, materializes interpolated block roots, and returns selected corner and block values in one readback. `GpuTerrainRootsBatchScheduler` batches the chunks. |
| Aquifer prefill and block decisions | Aquifer inputs are prepared per chunk on Java, then nearest/status and substance decisions are submitted to Vulkan. The resulting decision table stores registry state IDs and fluid-update flags. |
| One ordered queue/event chain and compact final readback | Grid evaluation and cell interpolation share one command buffer/readback. Aquifer inputs and decisions are a later Vulkan transaction, with CPU query/status preparation between the graph result and that decision chain. This is the largest remaining CPU/GPU boundary. |
| Compact block-state map | `GpuTerrainDecisionWriter.BlockPaletteBuffer` stores compact IDs and widens for large mod palettes. |
| Region section commit | `GpuTerrainDecisionWriter.writeRegionBlocks` performs section-layer ownership, writes in region Y/Z/X order, recalculates counts, and primes heightmaps. |
| Surface-height prefill | GPU Noise scans raw initial-density grid corners at cell-height boundaries in the same Vulkan command buffer, matching `NoiseChunk.preliminarySurfaceLevel`, and reads back 16 heights per chunk at quart coordinates when the horizontal cell width is four blocks. OCL precomputes a larger expanded area for neighboring chunks and places it in `ChunkNoiseSampler`'s surface-height cache; that neighborhood remains to port. |
| Async program compile and device permit selection | Vulkan engine initializes asynchronously and `VulkanSubmissionRing` coalesces queued work. Physical device selection and concurrent queue utilization still need comparison against OCL's per-device permit model. |

## Next implementation boundary

The next large stage is a region-level Vulkan terrain transaction. It should
consume the already interpolated block roots and region inputs, perform
aquifer/material decisions on device, and return compact state IDs plus
postprocessing data for the existing section commit. Expand the new Vulkan
surface scan to OCL's neighboring area so vanilla surface lookups reuse it.
The transaction must retain
per-root/per-row admission so unsupported custom functions, blending, or
parity failures use the existing Java path. Input packing,
per-stage diagnostics, shader compilation, dispatch, and readback should be
profiled as one transaction so the cost of CPU/GPU round trips is visible.

The current code is not yet equivalent to OCL's single full-region kernel:
the decision table is assembled separately from root evaluation, and any
unadmitted decision still enters `NoiseChunk`'s CPU interpolation and aquifer
logic in `GpuTerrainDecisionWriter.prepareChunk`. The unused
`assets/gpunoise/shaders/terrain_classification.comp` is only a simple
threshold classifier; it does not implement Minecraft's density interpolation
or aquifer semantics and must not be enabled as a substitute.

GitHub Actions with Mesa Lavapipe validates compilation, shader behavior,
parity, and runtime correctness. Its CPS is software-renderer output and is
not comparable to a physical GPU benchmark.
