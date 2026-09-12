package dev.gpunoise;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
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
    private final Map<DensityFunction, Integer> indices = new IdentityHashMap<>();
    private final List<GpuDensityGraph.Node> nodes = new ArrayList<>();
    private final List<NormalNoise> noises = new ArrayList<>();
    private final Map<NormalNoise, Integer> noiseIndices = new IdentityHashMap<>();
    private final List<GpuDensityGraph.Spline> splines = new ArrayList<>();
    private final List<GpuDensityGraph.Blended> blended = new ArrayList<>();
    private final List<DensityFunction> sourceFunctions = new ArrayList<>();

    private GpuDensityGraphCompiler() {}

    static GpuDensityGraph compile(NoiseRouter router) {
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
        GpuDensityGraphCompiler compiler = new GpuDensityGraphCompiler();
        int[] roots = {compiler.node(router.finalDensity()),
                compiler.node(router.initialDensityWithoutJaggedness()),
                compiler.node(router.fluidLevelFloodednessNoise())};
        return new GpuDensityGraph(compiler.nodes, roots, compiler.noises, compiler.splines,
                compiler.blended, compiler.sourceFunctions);
    }

    private int node(DensityFunction function) {
        if (function == null) throw new IllegalArgumentException("NoiseRouter contains null density function");
        Integer existing = indices.get(function);
        if (existing != null) return existing;
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

        // Some NeoForge/Minecraft transformations expose Constant as a normal
        // class even though the source declares it as a record. Its value is
        // exactly its min/max value, so it can be lowered without reflection.
        String simpleName = function.getClass().getSimpleName();
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
}
