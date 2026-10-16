package dev.gpunoise;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.core.Holder;
import net.minecraft.world.level.levelgen.DensityFunction.NoiseHolder;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import net.minecraft.world.level.levelgen.synth.BlendedNoise;
import dev.gpunoise.C2meCompiledDensityAccess;
import net.minecraft.util.CubicSpline;
import net.minecraft.world.level.levelgen.DensityFunctions;

/**
 * Converts the vanilla NoiseRouter record graph into a deterministic GPU IR.
 * Unsupported objects fail compilation; they are never silently approximated.
 */
final class GpuDensityGraphCompiler {
    private static final int MAX_COMPILATION_DEPTH = 512;
    private final Map<DensityFunction, Integer> indices = new IdentityHashMap<>();
    private final List<GpuDensityGraph.Node> nodes = new ArrayList<>();
    private final List<NormalNoise> noises = new ArrayList<>();
    private final Map<NormalNoise, Integer> noiseIndices = new IdentityHashMap<>();
    private final List<GpuDensityGraph.Spline> splines = new ArrayList<>();
    private final List<GpuDensityGraph.Blended> blended = new ArrayList<>();
    private final List<DensityFunction> sourceFunctions = new ArrayList<>();
    private boolean terrainGridMode;
    private int compilationDepth;

    private GpuDensityGraphCompiler() {}

    private record Checkpoint(int nodeCount, int noiseCount, int splineCount, int blendedCount,
                              int sourceCount, Map<DensityFunction, Integer> indices,
                              Map<NormalNoise, Integer> noiseIndices) {}

    private Checkpoint checkpoint() {
        return new Checkpoint(nodes.size(), noises.size(), splines.size(), blended.size(),
                sourceFunctions.size(), new IdentityHashMap<>(indices), new IdentityHashMap<>(noiseIndices));
    }

    private void rollback(Checkpoint checkpoint) {
        truncate(nodes, checkpoint.nodeCount());
        truncate(noises, checkpoint.noiseCount());
        truncate(splines, checkpoint.splineCount());
        truncate(blended, checkpoint.blendedCount());
        truncate(sourceFunctions, checkpoint.sourceCount());
        indices.clear();
        indices.putAll(checkpoint.indices());
        noiseIndices.clear();
        noiseIndices.putAll(checkpoint.noiseIndices());
        compilationDepth = 0;
    }

    private static void truncate(List<?> values, int size) {
        values.subList(size, values.size()).clear();
    }

    static GpuDensityGraph compile(NoiseRouter router) {
        GpuDensityFunctionTranslators.freezeRegistrations();
        GpuDensityGraphCompiler compiler = new GpuDensityGraphCompiler();
        int[] roots = {
            compiler.node(router.barrierNoise()), compiler.node(router.fluidLevelFloodednessNoise()),
            compiler.node(router.fluidLevelSpreadNoise()), compiler.node(router.lavaNoise()),
            compiler.node(router.temperature()), compiler.node(router.vegetation()),
            compiler.node(router.continents()), compiler.node(router.erosion()), compiler.node(router.depth()),
            compiler.node(router.ridges()), compiler.node(router.initialDensityWithoutJaggedness()),
            compiler.node(router.finalDensity()), compiler.node(router.veinToggle()),
            compiler.node(router.veinRidged()), compiler.node(router.veinGap())
        };
        return new GpuDensityGraph(compiler.nodes, roots, compiler.noises, compiler.splines, compiler.blended, compiler.sourceFunctions);
    }

    static GpuDensityGraph compileTerrain(NoiseRouter router) {
        // Keep every density input used by NoiseChunk and NoiseBasedAquifer
        // in the same GPU dispatch. The order is part of GpuChunkDensityCache.
        return compileTerrainRoots(new DensityFunction[]{router.finalDensity(),
                router.initialDensityWithoutJaggedness(), router.barrierNoise(),
                router.fluidLevelFloodednessNoise(), router.fluidLevelSpreadNoise(), router.lavaNoise(),
                router.erosion(), router.depth(), router.temperature(), router.vegetation(),
                router.continents(), router.ridges()});
    }

    /** Compiles terrain roots transactionally so one modded root cannot poison its siblings. */
    static GpuDensityGraph compileTerrainRoots(DensityFunction[] terrainRoots) {
        if (terrainRoots == null || terrainRoots.length == 0 || terrainRoots.length > Integer.SIZE - 1) {
            throw new IllegalArgumentException("terrain root list must contain between 1 and 31 functions");
        }
        for (int root = 0; root < terrainRoots.length; root++) {
            if (terrainRoots[root] == null) {
                throw new IllegalArgumentException("terrain root " + root + " is null");
            }
        }
        GpuDensityFunctionTranslators.freezeRegistrations();
        GpuDensityGraphCompiler compiler = new GpuDensityGraphCompiler();
        compiler.terrainGridMode = true;
        int[] roots = new int[terrainRoots.length];
        String[] rejections = new String[terrainRoots.length];
        int supportedMask = 0;
        for (int root = 0; root < terrainRoots.length; root++) {
            DensityFunction function = terrainRoots[root];
            Checkpoint checkpoint = compiler.checkpoint();
            try {
                roots[root] = compiler.node(function);
                supportedMask |= 1 << root;
            } catch (RuntimeException unsupported) {
                compiler.rollback(checkpoint);
                rejections[root] = unsupported.getMessage() == null
                        ? unsupported.getClass().getSimpleName() : unsupported.getMessage();
                roots[root] = compiler.nodes.size();
                compiler.nodes.add(new GpuDensityGraph.Node("Constant", new int[0], new double[]{0.0},
                        "UnsupportedTerrainRoot"));
                compiler.sourceFunctions.add(function);
            }
        }
        if (supportedMask == 0) {
            throw new IllegalStateException("GPU terrain compiler could not translate any terrain roots: "
                    + Arrays.toString(rejections));
        }
        return new GpuDensityGraph(compiler.nodes, roots, compiler.noises, compiler.splines,
                compiler.blended, compiler.sourceFunctions, supportedMask, rejections);
    }

    /**
     * Compiles one custom density root through the same graph path used by
     * NoiseRouter compilation. This is also the stable seam for validating
     * Java-to-GPU translators without constructing a synthetic 15-root router.
     */
    static GpuDensityGraph compileDensityFunction(DensityFunction function, boolean terrainGridMode) {
        GpuDensityFunctionTranslators.freezeRegistrations();
        GpuDensityGraphCompiler compiler = new GpuDensityGraphCompiler();
        compiler.terrainGridMode = terrainGridMode;
        int root = compiler.node(function);
        return new GpuDensityGraph(compiler.nodes, new int[]{root}, compiler.noises,
                compiler.splines, compiler.blended, compiler.sourceFunctions);
    }

    private int node(DensityFunction function) {
        if (++compilationDepth > MAX_COMPILATION_DEPTH) {
            compilationDepth--;
            throw unsupported(function, "density dependency depth exceeds " + MAX_COMPILATION_DEPTH);
        }
        try {
            return compileNode(function);
        } finally {
            compilationDepth--;
        }
    }

    private int compileNode(DensityFunction function) {
        if (function == null) throw new IllegalArgumentException("NoiseRouter contains null density function");
        Integer existing = indices.get(function);
        if (existing != null) {
            if (nodes.get(existing) == null) {
                throw unsupported(function, "recursive density graph");
            }
            return existing;
        }
        int index = nodes.size();
        indices.put(function, index);
        // Reserve the index before walking children; this also detects recursive graphs.
        nodes.add(null);
        sourceFunctions.add(function);

        // C2ME DFC replaces the vanilla record graph with a bytecode wrapper,
        // but intentionally retains the original graph for blending and
        // fallback semantics. Compile that graph instead of rejecting the
        // wrapper as a non-record density function.
        if (function instanceof C2meCompiledDensityAccess compiled) {
            DensityFunction vanilla = compiled.gpunoise$vanillaFallback();
            if (vanilla == null) throw unsupported(function, "C2ME wrapper has no vanilla fallback");
            sourceFunctions.set(index, vanilla);
            int child = node(vanilla);
            nodes.set(index, new GpuDensityGraph.Node("Marker", new int[]{child}, new double[0],
                    "C2MECompiledDensityFunction"));
            return index;
        }

        // The remaining lowering rules describe Minecraft's concrete
        // DensityFunctions implementations.  A mod may implement
        // SimpleFunction (or even a record) with the same shape/name, but
        // reflection cannot prove that its compute() semantics match the
        // shader opcode.  Keep those functions on the vanilla path rather
        // than producing a plausible-looking but incorrect GPU graph.
        String functionClassName = function.getClass().getName();
        boolean customFunction = !functionClassName.startsWith("net.minecraft.");
        boolean tectonicFunction = functionClassName.startsWith(
                "dev.worldgen.tectonic.worldgen.densityfunction.");
        if (customFunction) {
            GpuDensityExpression expression = GpuDensityFunctionTranslators.translateToGpu(function);
            String bytecodeRejection = null;
            if (expression == null) {
                GpuDensityBytecodeCompiler.Result bytecode = GpuDensityBytecodeCompiler.compile(function);
                expression = bytecode.expression();
                bytecodeRejection = bytecode.rejection();
            }
            if (expression != null
                    && (!terrainGridMode || expression.unsupportedOperation(
                            GpuDensityExpression.Backend.TERRAIN_GRID) == null)) {
                int[] samplerRemap = new int[expression.noises().size()];
                for (int sampler = 0; sampler < samplerRemap.length; sampler++) {
                    NormalNoise noise = expression.noises().get(sampler);
                    samplerRemap[sampler] = noiseIndices.computeIfAbsent(noise, key -> {
                        noises.add(key);
                        return noises.size() - 1;
                    });
                }
                int[] expressionNodeRemap = new int[expression.nodes().size()];
                for (int localIndex = 0; localIndex < expression.nodes().size(); localIndex++) {
                    GpuDensityExpression.Node expressionNode = expression.nodes().get(localIndex);
                    int[] children = expressionNode.children();
                    for (int child = 0; child < children.length; child++) {
                        children[child] = expressionNodeRemap[children[child]];
                    }
                    double[] constants = expressionNode.constants();
                    if (expressionNode.opcode().equals("Noise")) {
                        int localSampler = (int) constants[0];
                        if (localSampler < 0 || localSampler >= samplerRemap.length) {
                            throw unsupported(function, "GPU expression references an invalid noise sampler");
                        }
                        constants[0] = samplerRemap[localSampler];
                    }
                    String opcode = expressionNode.opcode();
                    String payload = expressionNode.payload();
                    if (opcode.equals("DensityRef")) {
                        int dependencyIndex = (int) constants[0];
                        if (dependencyIndex < 0 || dependencyIndex >= expression.densityReferences().size()
                                || constants[0] != dependencyIndex) {
                            throw unsupported(function, "bytecode expression references an invalid density dependency");
                        }
                        int dependency = node(expression.densityReferences().get(dependencyIndex));
                        nodes.add(new GpuDensityGraph.Node("Marker", new int[]{dependency},
                                new double[0], "GpuDensityBytecodeDependency"));
                        sourceFunctions.add(function);
                        expressionNodeRemap[localIndex] = nodes.size() - 1;
                        continue;
                    }
                    if (opcode.equals("GpuMath")) {
                        payload = "EXPR_" + payload;
                    }
                    nodes.add(new GpuDensityGraph.Node(opcode, children, constants, payload));
                    sourceFunctions.add(function);
                    expressionNodeRemap[localIndex] = nodes.size() - 1;
                }
                nodes.set(index, new GpuDensityGraph.Node("Marker",
                        new int[]{expressionNodeRemap[expression.root()]}, new double[0],
                        "GpuDensityExpression"));
                return index;
            }
            DensityFunction translated = GpuDensityFunctionTranslators.translate(function);
            if (translated != null) {
                // Keep the original function in sourceFunctions for parity and
                // fallback. The marker forwards the GPU value from the adapter's
                // equivalent built-in density graph. Explicit adapters precede
                // built-in compatibility lowering so mods can define exact semantics.
                nodes.set(index, new GpuDensityGraph.Node("Marker", new int[]{node(translated)},
                        new double[0], "GpuDensityFunctionTranslator"));
                return index;
            }
            if (expression != null && terrainGridMode) {
                String unsupportedNode = expression.unsupportedOperation(
                        GpuDensityExpression.Backend.TERRAIN_GRID);
                throw unsupported(function, "direct GPU expression node " + unsupportedNode
                        + " is not supported by the terrain-grid shader; provide a vanilla-equivalent "
                        + "translate() graph for the grid path");
            }
            if (!tectonicFunction) {
                throw unsupported(function, "automatic Java bytecode compilation rejected this implementation: "
                        + (bytecodeRejection == null ? "no registered GPU translator" : bytecodeRejection));
            }
        }

        // Some NeoForge/Minecraft transformations expose Constant as a normal
        // class even though the source declares it as a record. Its value is
        // exactly its min/max value, so it can be lowered without reflection.
        String simpleName = function.getClass().getSimpleName();
        if (tectonicFunction && simpleName.equals("ConfigNoise")) {
            if (!function.getClass().isRecord()) throw unsupported(function, "ConfigNoise is not a record");
            try {
                RecordComponent[] components = function.getClass().getRecordComponents();
                Object noiseValue = recordValue(components, function, "noise");
                Object shiftXValue = recordValue(components, function, "shiftX");
                Object shiftZValue = recordValue(components, function, "shiftZ");
                Object scaleValue = recordValue(components, function, "scale");
                Object multiplierValue = recordValue(components, function, "multiplier");
                Object offsetValue = recordValue(components, function, "offset");
                Object smootherValue = recordValue(components, function, "smootherScaling");
                if (!(noiseValue instanceof NoiseHolder noiseHolder)
                        || noiseHolder.noise() == null
                        || !(shiftXValue instanceof DensityFunction shiftX)
                        || !(shiftZValue instanceof DensityFunction shiftZ)
                        || !(scaleValue instanceof Number scale)
                        || !(multiplierValue instanceof Number multiplier)
                        || !(offsetValue instanceof Number offset)
                        || !(smootherValue instanceof Boolean smootherScaling)) {
                    throw unsupported(function, "ConfigNoise has invalid record fields");
                }
                int noiseId = noiseIndices.computeIfAbsent(noiseHolder.noise(), key -> {
                    noises.add(key);
                    return noises.size() - 1;
                });
                nodes.set(index, new GpuDensityGraph.Node("TectonicConfigNoise",
                        new int[]{node(shiftX), node(shiftZ)},
                        new double[]{noiseId, scale.doubleValue(), multiplier.doubleValue(), offset.doubleValue()},
                        smootherScaling.toString()));
                return index;
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("Cannot inspect Tectonic ConfigNoise", failure);
            }
        }
        if (tectonicFunction && simpleName.equals("Invert")) {
            if (!function.getClass().isRecord()) throw unsupported(function, "Invert is not a record");
            try {
                Object inputValue = recordValue(function.getClass().getRecordComponents(), function, "input");
                if (!(inputValue instanceof DensityFunction input)) {
                    throw unsupported(function, "Invert input is not a density function");
                }
                nodes.set(index, new GpuDensityGraph.Node("TectonicInvert",
                        new int[]{node(input)}, new double[0], null));
                return index;
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("Cannot inspect Tectonic Invert", failure);
            }
        }
        if (function instanceof BlendedNoise blendedNoise) {
            BlendedNoiseAccess accessor = (BlendedNoiseAccess) (Object) blendedNoise;
            int blendedId = blended.size();
            blended.add(new GpuDensityGraph.Blended(
                    MinecraftPerlinData.read(accessor.gpunoise$minLimitNoise()),
                    MinecraftPerlinData.read(accessor.gpunoise$maxLimitNoise()),
                    MinecraftPerlinData.read(accessor.gpunoise$mainNoise()),
                    accessor.gpunoise$xzMultiplier(), accessor.gpunoise$yMultiplier(),
                    accessor.gpunoise$xzFactor(), accessor.gpunoise$yFactor(),
                    accessor.gpunoise$smearScaleMultiplier()));
            nodes.set(index, new GpuDensityGraph.Node("BlendedNoise", new int[0], new double[]{blendedId}, null));
            return index;
        }
        if (simpleName.equals("HolderHolder")) {
            if (!function.getClass().isRecord()) throw unsupported(function, "holder wrapper is not a record");
            try {
                var accessor = function.getClass().getRecordComponents()[0].getAccessor();
                if (!accessor.trySetAccessible()) throw unsupported(function, "holder wrapper is inaccessible");
                Object value = accessor.invoke(function);
                if (!(value instanceof Holder<?> holder) || !holder.isBound() || !(holder.value() instanceof DensityFunction child)) {
                    throw unsupported(function, "unbound density holder");
                }
                int childNode = node(child);
                nodes.set(index, new GpuDensityGraph.Node("Marker", new int[]{childNode}, new double[0], "HolderHolder"));
                return index;
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("Cannot inspect holder wrapper " + function.getClass().getName(), failure);
            }
        }
        if (simpleName.equals("YClampedGradient")) {
            if (!function.getClass().isRecord()) throw unsupported(function, "gradient is not a record");
            try {
                RecordComponent[] components = function.getClass().getRecordComponents();
                double[] values = new double[4];
                for (int i = 0; i < components.length; i++) {
                    var accessor = components[i].getAccessor();
                    if (!accessor.trySetAccessible()) throw unsupported(function, "gradient is inaccessible");
                    Object value = accessor.invoke(function);
                    if (!(value instanceof Number number)) throw unsupported(function, "gradient field is not numeric");
                    values[i] = number.doubleValue();
                }
                nodes.set(index, new GpuDensityGraph.Node("YClampedGradient", new int[0], values, null));
                return index;
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("Cannot inspect gradient " + function.getClass().getName(), failure);
            }
        }
        if (simpleName.equals("BlendDensity")) {
            if (!function.getClass().isRecord()) throw unsupported(function, "blend density is not a record");
            try {
                var accessor = function.getClass().getRecordComponents()[0].getAccessor();
                if (!accessor.trySetAccessible()) throw unsupported(function, "blend density is inaccessible");
                Object value = accessor.invoke(function);
                if (!(value instanceof DensityFunction child)) throw unsupported(function, "blend density input is not a density function");
                nodes.set(index, new GpuDensityGraph.Node("BlendDensity", new int[]{node(child)}, new double[0], null));
                return index;
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("Cannot inspect blend density " + function.getClass().getName(), failure);
            }
        }
        if (simpleName.equals("WeirdScaledSampler")) {
            if (!function.getClass().isRecord()) throw unsupported(function, "weird sampler is not a record");
            try {
                RecordComponent[] components = function.getClass().getRecordComponents();
                DensityFunction input = (DensityFunction) components[0].getAccessor().invoke(function);
                NoiseHolder holder = (NoiseHolder) components[1].getAccessor().invoke(function);
                Object mapper = components[2].getAccessor().invoke(function);
                if (holder.noise() == null) throw unsupported(function, "unresolved noise holder");
                int noiseId = noiseIndices.computeIfAbsent(holder.noise(), key -> { noises.add(key); return noises.size() - 1; });
                nodes.set(index, new GpuDensityGraph.Node("WeirdScaledSampler", new int[]{node(input)}, new double[]{noiseId}, mapper.toString()));
                return index;
            } catch (ReflectiveOperationException | ClassCastException failure) {
                throw new IllegalStateException("Cannot inspect weird sampler " + function.getClass().getName(), failure);
            }
        }
        if (simpleName.equals("Constant") || simpleName.equals("BlendOffset") || simpleName.equals("BlendAlpha")) {
            // These singleton functions have no children and a position-
            // independent value. Preserve that value exactly in the IR.
            nodes.set(index, new GpuDensityGraph.Node("Constant", new int[0], new double[]{function.minValue()}, null));
            return index;
        }
        if (simpleName.equals("Spline")) {
            if (!function.getClass().isRecord()) throw unsupported(function, "spline wrapper is not a record");
            try {
                var splineAccessor = function.getClass().getRecordComponents()[0].getAccessor();
                splineAccessor.trySetAccessible();
                Object spline = splineAccessor.invoke(function);
                if (spline instanceof CubicSpline.Constant<?, ?> constant) {
                    nodes.set(index, new GpuDensityGraph.Node("Constant", new int[0], new double[]{constant.value()}, null));
                    return index;
                }
                if (!(spline instanceof CubicSpline.Multipoint<?, ?> points)) throw unsupported(function, "unknown cubic spline implementation");
                return compileSpline(index, function, points);
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("Cannot inspect spline " + function.getClass().getName(), failure);
            }
        }
        if (!function.getClass().isRecord()) {
            throw unsupported(function, "not a record density function");
        }
        RecordComponent[] components = function.getClass().getRecordComponents();
        List<Integer> children = new ArrayList<>();
        List<Double> constants = new ArrayList<>();
        String payload = null;
        for (RecordComponent component : components) {
            Object value;
            try {
                var accessor = component.getAccessor();
                if (!accessor.trySetAccessible()) {
                    throw unsupported(function, "accessor is not accessible: " + component.getName());
                }
                value = accessor.invoke(function);
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("Cannot inspect density function " + function.getClass().getName(), failure);
            }
            if (value instanceof DensityFunction child) {
                children.add(node(child));
            } else if (value instanceof NoiseHolder noiseHolder) {
                NormalNoise noise = noiseHolder.noise();
                if (noise == null) throw unsupported(function, "noise holder has no resolved NormalNoise");
                // Store the sampler index in the node constants. The Vulkan graph
                // serializer will upload each unique NormalNoise once per batch.
                constants.add(noiseIndices.computeIfAbsent(noise, key -> { noises.add(key); return noises.size() - 1; }).doubleValue());
            } else if (value instanceof Holder<?> holder && holder.isBound() && holder.value() instanceof DensityFunction child) {
                // NoiseRouter entries are commonly wrapped in HolderHolder. The holder
                // is graph metadata, not a runtime operation, so lower its value directly.
                children.add(node(child));
            } else if (value instanceof Number number) {
                constants.add(number.doubleValue());
            } else if (value instanceof String string) {
                payload = string;
            } else if (value instanceof Enum<?> enumValue) {
                // Operation modes are part of the graph semantics (ADD vs MUL,
                // ABS vs SQUARE, marker/cache kind, etc.). Preserve them in the IR.
                payload = enumValue.name();
            } else if (value != null) {
                // NoiseHolder and spline objects need dedicated serializers rather than a silent approximation.
                throw unsupported(function, component.getName() + " has unsupported value " + value.getClass().getName());
            }
        }
        nodes.set(index, new GpuDensityGraph.Node(function.getClass().getSimpleName(),
                children.stream().mapToInt(Integer::intValue).toArray(),
                constants.stream().mapToDouble(Double::doubleValue).toArray(), payload));
        return index;
    }

    private int compileSpline(int index, DensityFunction owner, CubicSpline.Multipoint<?, ?> points) {
        // Nested spline nodes need their own CPU reference during parity tests.
        // Storing the outer owner here makes a valid nested spline look like a
        // large GPU mismatch even though the serialized spline is correct.
        @SuppressWarnings({"rawtypes", "unchecked"})
        DensityFunction splineReference = DensityFunctions.spline((CubicSpline) points);
        sourceFunctions.set(index, splineReference);
        Object coordinate = points.coordinate();
        if (!(coordinate instanceof DensityFunctions.Spline.Coordinate coordinateRecord)) {
            throw unsupported(owner, "unsupported spline coordinate");
        }
        if (!coordinateRecord.function().isBound()) throw unsupported(owner, "unbound spline coordinate");
        int coordinateNode = node(coordinateRecord.function().value());
        float[] locations = points.locations();
        float[] derivatives = points.derivatives();
        if (locations.length == 0 || locations.length != derivatives.length || locations.length > 64) {
            throw unsupported(owner, "spline point count must be 1..64");
        }
        List<? extends CubicSpline<?, ?>> values = points.values();
        if (values.size() != locations.length) throw unsupported(owner, "spline value count does not match locations");
        int[] valueNodes = new int[values.size()];
        for (int i = 0; i < values.size(); i++) {
            CubicSpline<?, ?> value = values.get(i);
            if (value instanceof CubicSpline.Constant<?, ?> constant) {
                valueNodes[i] = node(DensityFunctions.constant(constant.value()));
            } else if (value instanceof CubicSpline.Multipoint<?, ?> nested) {
                int nestedIndex = nodes.size();
                nodes.add(null);
                sourceFunctions.add(owner);
                valueNodes[i] = compileSpline(nestedIndex, owner, nested);
            } else {
                throw unsupported(owner, "unknown nested spline implementation");
            }
        }
        int splineId = splines.size();
        splines.add(new GpuDensityGraph.Spline(coordinateNode, locations, derivatives, valueNodes));
        // Spline control-point values are graph dependencies too. They are not
        // part of the coordinate expression, but the GPU evaluator reads them
        // by node index; declaring them here makes the lowerer place every
        // value node before this spline node.
        int[] dependencies = new int[1 + valueNodes.length];
        dependencies[0] = coordinateNode;
        System.arraycopy(valueNodes, 0, dependencies, 1, valueNodes.length);
        nodes.set(index, new GpuDensityGraph.Node("Spline", dependencies, new double[]{splineId, locations.length}, null));
        return index;
    }

    private static IllegalStateException unsupported(DensityFunction function, String reason) {
        return new IllegalStateException("GPU density compiler cannot compile " + function.getClass().getName() + ": " + reason);
    }

    private static Object recordValue(RecordComponent[] components, Object owner, String name)
            throws ReflectiveOperationException {
        for (RecordComponent component : components) {
            if (component.getName().equals(name)) {
                var accessor = component.getAccessor();
                if (!accessor.trySetAccessible()) throw new IllegalAccessException(name);
                return accessor.invoke(owner);
            }
        }
        throw new NoSuchFieldException(name);
    }
}
