# GPU Noise

GPU Noise translates Minecraft terrain density graphs into a compact
intermediate representation, compiles the matching compute shaders to SPIR-V,
and evaluates terrain samples through Vulkan. Mesa Lavapipe is used in CI to
compile the shaders and exercise the Vulkan path without a physical GPU.

## Custom density functions

Other mods can register an adapter for a custom `DensityFunction` before the
server terrain graph is compiled. The adapter returns an equivalent graph
composed from Minecraft density functions that GPU Noise already supports:

```java
GpuDensityFunctionTranslators.register(MyTerrainDensity.class,
        MyTerrainDensity::asGpuDensityFunction);
```

`asGpuDensityFunction` should build the same math as the custom function from
supported `DensityFunctions` operations. GPU Noise checks the translated graph
against the original function during terrain graph parity checks. The original
function remains available for parity and CPU fallback. Registration is exact
class based. If a mod defines an interface or base class for a family of
custom functions, register it once for all implementations:

```java
GpuDensityFunctionTranslators.registerForSubtypes(MyDensityApi.class,
        MyDensityApi::asGpuDensityFunction);
```

For math that has no equivalent `DensityFunctions` node, an adapter can build
the GPU expression directly. Its inputs must come from the expression builder;
captured noise samplers and finite configuration values are packed with the
graph and checked against the original Java function during parity validation.

```java
GpuDensityFunctionTranslators.register(MyWarp.class,
        GpuDensityFunctionTranslator.expressionOnly(function -> {
            GpuDensityExpression.Builder expression = new GpuDensityExpression.Builder();
            int sample = expression.noise(function.noise(), 0.25, 0.5);
            int scaled = expression.apply(GpuDensityExpression.Operation.MULTIPLY,
                    sample, expression.constant(function.amplitude()));
            return expression.build(scaled);
        }));
```

An exact-class adapter takes precedence. Otherwise GPU Noise chooses the closest
registered supertype and reports an error if two unrelated translators match
equally well. This lets terrain mods cover implementation wrappers without
silently depending on mod load order.

Adapters describe equivalent density math. GPU Noise does not execute arbitrary
mod bytecode on Vulkan. If a function has no adapter or uses an unsupported
operation, the terrain graph fails closed and vanilla CPU terrain generation
remains enabled.

## Current GPU terrain scope

The current path evaluates terrain density and aquifer input roots on the GPU
at Minecraft's cell grid, then interpolates final density to block resolution
on the GPU. The full-block path also batches aquifer fluid decisions on
Vulkan when the required density and barrier data are available. Minecraft
retains fluid-status lookup, the chunk writer, surface rules, carvers,
structures, and features. Unsupported or mismatched aquifer decisions return
to vanilla Java. This does not make full chunk generation GPU-only.

## Mod jar

Use the single `gpunoise-*-all.jar` artifact from GitHub Actions for both
client/integrated-server and dedicated-server installs. It bundles the Vulkan
Java bindings and native payloads. The early NeoForge locator uses Minecraft's
LWJGL core on clients and adds the bundled LWJGL core plus native resources as
game libraries on dedicated servers. Both use the version pinned for Minecraft
1.21.1.

## CI correctness checks

GitHub Actions builds and validates the single mod jar, compiles GLSL with
`glslc`, runs a Vulkan smoke dispatch on Mesa Lavapipe, and starts fresh
same-seed NeoForge dedicated servers with C2ME and Chunky. It checks server
startup, terrain graph parity, GPU cache consumption, CPU fallback behavior,
and clean Chunky completion. CI does not collect or compare CPS; Lavapipe is
used for correctness checks, not as a speed measurement. Actions uploads the
mod jar and pinned C2ME/Chunky jars before the worldgen checks start.
The default Chunky radius is 250 blocks. Manual workflow runs can select the
radius, batch window, target size, and spatial search radius for controlled
follow-up measurements.
