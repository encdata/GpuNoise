# Automatic custom density translation and optional adapters

Other mods do not need to call this API. GPU Noise first translates supported
custom `compute(FunctionContext):double` implementations automatically from
their Java bytecode, recursively inlining bounded pure helpers and capturing
final configuration fields. It rejects side effects and unsupported calls and
keeps the original Java function as the parity reference and fallback. This
mirrors the graph-to-source compilation strategy used by C2ME OCL, with Vulkan
GLSL generated from GPU Noise's validated IR.

The adapter API below is an optional escape hatch for behavior that cannot be
derived from bytecode or when a mod already provides an equivalent vanilla
graph. It is not the normal compatibility path.

Register adapters during mod initialization, before world terrain graphs are
compiled. The registry freezes on the first graph compilation. An exact class
registration takes precedence over a subtype registration; among subtype
matches the closest supertype wins, and equally close unrelated matches are
reported as ambiguous.

## Direct expression example

This example turns a custom sampler function into an absolute noise ridge
scaled by a mod-defined value:

~~~
GpuDensityFunctionTranslators.registerForSubtypes(MyNoiseFunction.class,
        new GpuDensityFunctionTranslator<MyNoiseFunction>() {
            @Override
            public DensityFunction translate(MyNoiseFunction function) {
                // Optional equivalent vanilla graph for shader paths that do
                // not support every direct expression operation.
                return function.vanillaEquivalent();
            }

            @Override
            public GpuDensityExpression translateToGpu(MyNoiseFunction function) {
                GpuDensityExpression.Builder expression =
                        new GpuDensityExpression.Builder();
                int sample = expression.noise(
                        function.noiseSampler(),
                        function.horizontalScale(),
                        function.verticalScale());
                int ridge = expression.map(GpuDensityExpression.Mapping.ABS, sample);
                int amplitude = expression.constant(function.amplitude());
                int result = expression.apply(
                        GpuDensityExpression.Operation.MULTIPLY, ridge, amplitude);
                return expression.build(result);
            }
        });
~~~

translateToGpu may return null to request the vanilla translate result.
translate may return null for a direct-only adapter, but graph compilation
will reject it when the selected shader path cannot represent the expression.
The original DensityFunction remains the Java reference and CPU fallback.
For a direct-only adapter, use
`GpuDensityFunctionTranslator.expressionOnly(function -> ...)` to avoid
implementing a translate method that returns null.

## Current operation coverage

After build, nodeCount(), samplerCount(), and rootNode() report the compacted
live IR shape. Use these counts in adapter diagnostics; backend-specific graph
capacity checks still happen during graph compilation.

The point-sample shader supports coordinates, finite constants, NormalNoise,
double arithmetic, mapped scalar math, float arithmetic with per-operation
32-bit rounding, Java 32-bit integer arithmetic, numeric casts, and
compare/select nodes. The bytecode frontend also recognizes inherited class
and unambiguous default-interface `compute(FunctionContext)` implementations,
bounded pure instance helpers in the density class hierarchy and nested
immutable record configurations, bounded forward branches and switches, static
loops of at most 64 iterations, static helper methods, `Math` scalar methods,
and `Mth` mapping helpers. Dynamic loops,
wide integers, opaque calls, and other unrepresented constructs fall back with
a compiler diagnostic. The Java expression evaluator is a reference for the
compiled expression; it does not prove that an explicit adapter matches its
original function. Adapters must compare representative negative, boundary,
high-altitude, and ordinary terrain coordinates.

Noise samples and serialized constants still follow the current GPU path's
float-backed ABI. Double and integer operations use double values internally;
float operations explicitly round after each bytecode operation. Compare
results with tolerances appropriate to the sampler and GPU math used.

Terrain-grid compilation supports exact coordinate nodes, double arithmetic
(including dynamic division), integer arithmetic and bitwise/shift operations, float operations, compare/select, and
Java remainder. Integer division and remainder still require a compile-time
nonzero divisor so the shader can preserve Java overflow and zero-divisor
behavior. Unsupported expression operations report a diagnostic and stay on
the CPU path instead of being approximated.
Adapters can query
`expression.unsupportedOperation(GpuDensityExpression.Backend.TERRAIN_GRID)`
before returning a direct expression and choose their fallback deliberately.

NormalNoise inputs use the exact sampler object and explicit horizontal and
vertical coordinate scales. Custom sampler implementations, resource-backed
samplers outside NormalNoise, and custom surface rules need later IR
operations and shader capabilities.
