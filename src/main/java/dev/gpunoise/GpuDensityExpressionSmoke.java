package dev.gpunoise;

import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.synth.NormalNoise;

/** CPU-side public-API checks used by the GitHub Actions Vulkan smoke entry point. */
final class GpuDensityExpressionSmoke {
    private GpuDensityExpressionSmoke() {}

    record VulkanBranchFixture(GpuDensityGraph graph, DensityFunction javaFunction) {}

    static GpuDensityGraph conditionalGraphForVulkanSmoke() {
        return GpuDensityGraphLowerer.lower(GpuDensityGraphCompiler.compileDensityFunction(
                new BranchedDensity(), true));
    }

    static VulkanBranchFixture floatingConditionalFixture() {
        NormalNoise noise = NormalNoise.create(RandomSource.create(911L), -2, 1.0, 0.5);
        DensityFunction function = new FloatingBranchedDensity(noise, 0.125, 0.25, 0.0);
        GpuDensityGraph graph = GpuDensityGraphLowerer.lower(
                GpuDensityGraphCompiler.compileDensityFunction(function, true));
        return new VulkanBranchFixture(graph, function);
    }

    static GpuDensityGraph switchGraphForVulkanSmoke() {
        return GpuDensityGraphLowerer.lower(GpuDensityGraphCompiler.compileDensityFunction(
                new SwitchDensity(), true));
    }

    static GpuDensityGraph remainderGraphForVulkanSmoke() {
        return GpuDensityGraphLowerer.lower(GpuDensityGraphCompiler.compileDensityFunction(
                new RemainderDensity(), true));
    }

    static VulkanBranchFixture scalarMathFixture() {
        DensityFunction function = new ScalarMathDensity();
        return new VulkanBranchFixture(GpuDensityGraphLowerer.lower(
                GpuDensityGraphCompiler.compileDensityFunction(function, true)), function);
    }

    static VulkanBranchFixture dynamicPowerFixture() {
        DensityFunction function = new DynamicPowerDensity();
        return new VulkanBranchFixture(GpuDensityGraphLowerer.lower(
                GpuDensityGraphCompiler.compileDensityFunction(function, true)), function);
    }

    static VulkanBranchFixture integerMathFixture() {
        DensityFunction function = new IntegerMathDensity();
        return new VulkanBranchFixture(GpuDensityGraphLowerer.lower(
                GpuDensityGraphCompiler.compileDensityFunction(function, true)), function);
    }

    static VulkanBranchFixture integerBitwiseFixture() {
        DensityFunction function = new IntegerBitwiseDensity();
        return new VulkanBranchFixture(GpuDensityGraphLowerer.lower(
                GpuDensityGraphCompiler.compileDensityFunction(function, true)), function);
    }

    static VulkanBranchFixture integerDivisionOverflowFixture() {
        DensityFunction function = new IntegerDivisionOverflowDensity();
        return new VulkanBranchFixture(GpuDensityGraphLowerer.lower(
                GpuDensityGraphCompiler.compileDensityFunction(function, true)), function);
    }

    static VulkanBranchFixture floorDivisionFixture() {
        DensityFunction function = new FloorDivisionDensity();
        return new VulkanBranchFixture(GpuDensityGraphLowerer.lower(
                GpuDensityGraphCompiler.compileDensityFunction(function, true)), function);
    }

    static VulkanBranchFixture narrowingFixture() {
        DensityFunction function = new NarrowingDensity();
        return new VulkanBranchFixture(GpuDensityGraphLowerer.lower(
                GpuDensityGraphCompiler.compileDensityFunction(function, true)), function);
    }

    static VulkanBranchFixture floatArithmeticFixture() {
        DensityFunction function = new FloatArithmeticDensity();
        return new VulkanBranchFixture(GpuDensityGraphLowerer.lower(
                GpuDensityGraphCompiler.compileDensityFunction(function, true)), function);
    }

    static VulkanBranchFixture floatCompareFixture() {
        DensityFunction function = new FloatCompareDensity();
        return new VulkanBranchFixture(GpuDensityGraphLowerer.lower(
                GpuDensityGraphCompiler.compileDensityFunction(function, true)), function);
    }

    static VulkanBranchFixture boundedLoopFixture() {
        DensityFunction function = new BoundedLoopDensity();
        return new VulkanBranchFixture(GpuDensityGraphLowerer.lower(
                GpuDensityGraphCompiler.compileDensityFunction(function, true)), function);
    }

    static void verify() {
        requireOpcode("EXPR_ADD", 1);
        requireOpcode("EXPR_SUBTRACT", 2);
        requireOpcode("EXPR_MULTIPLY", 3);
        requireOpcode("EXPR_DIVIDE", 4);
        requireOpcode("DIVIDE", 4);
        requireOpcode("EXPR_MIN", 5);
        requireOpcode("EXPR_MAX", 6);
        requireOpcode("EXPR_ATAN2", 8);
        requireOpcode("EXPR_POW", 9);
        requireOpcode("ADD", 1);
        requireOpcode("MUL", 2);
        requireOpcode("MIN", 3);
        requireOpcode("MAX", 4);
        requireOpcode("IAND", 10);
        requireOpcode("IOR", 11);
        requireOpcode("IXOR", 12);
        requireOpcode("ISHL", 13);
        requireOpcode("ISHR", 14);
        requireOpcode("IUSHR", 15);

        GpuDensityExpression.Builder builder = new GpuDensityExpression.Builder();
        int x = builder.coordinate(GpuDensityExpression.Coordinate.X);
        int y = builder.coordinate(GpuDensityExpression.Coordinate.Y);
        int product = builder.apply(GpuDensityExpression.Operation.MULTIPLY, x, y);
        int magnitude = builder.map(GpuDensityExpression.Mapping.ABS, product);
        int offset = builder.constant(3.0);
        int shifted = builder.apply(GpuDensityExpression.Operation.ADD, magnitude, offset);
        GpuDensityExpression expression = builder.build(builder.clamp(shifted, 0.0, 20.0));
        requireClose(expression.evaluate(7.0, -4.0, 100.0), 20.0, "clamped expression");
        requireClose(expression.evaluate(-1.0, 3.0, 0.0), 6.0, "negative-coordinate expression");

        GpuDensityExpression.Builder divideBuilder = new GpuDensityExpression.Builder();
        int numerator = divideBuilder.coordinate(GpuDensityExpression.Coordinate.X);
        int zero = divideBuilder.constant(0.0);
        int quotient = divideBuilder.apply(GpuDensityExpression.Operation.DIVIDE, numerator, zero);
        if (!Double.isInfinite(divideBuilder.build(quotient).evaluate(1.0, 0.0, 0.0))) {
            throw new AssertionError("GPU expression division must preserve Java IEEE behavior");
        }

        GpuDensityExpression.Builder mappingBuilder = new GpuDensityExpression.Builder();
        int negative = mappingBuilder.constant(-2.0);
        int halfNegative = mappingBuilder.map(GpuDensityExpression.Mapping.HALF_NEGATIVE, negative);
        requireClose(mappingBuilder.build(halfNegative).evaluate(0.0, 0.0, 0.0), -1.0,
                "half-negative mapping");
        GpuDensityExpression.Builder squeezeBuilder = new GpuDensityExpression.Builder();
        int aboveRange = squeezeBuilder.constant(2.0);
        int squeezed = squeezeBuilder.map(GpuDensityExpression.Mapping.SQUEEZE, aboveRange);
        requireClose(squeezeBuilder.build(squeezed).evaluate(0.0, 0.0, 0.0), 11.0 / 24.0,
                "squeeze mapping");

        GpuDensityExpression.Builder foldBuilder = new GpuDensityExpression.Builder();
        int foldedRoot = foldBuilder.apply(GpuDensityExpression.Operation.MULTIPLY,
                foldBuilder.constant(6.0), foldBuilder.constant(7.0));
        GpuDensityExpression folded = foldBuilder.build(foldedRoot);
        if (!folded.nodes().get(folded.root()).opcode().equals("Constant")) {
            throw new AssertionError("constant-only arithmetic should fold into a constant node");
        }
        if (folded.unsupportedOperation(GpuDensityExpression.Backend.TERRAIN_GRID) != null) {
            throw new AssertionError("constant expressions should be legal in the terrain-grid IR");
        }
        requireClose(folded.evaluate(0.0, 0.0, 0.0), 42.0, "constant-folded expression");

        GpuDensityExpression.Builder nanCompareBuilder = new GpuDensityExpression.Builder();
        int zeroCoordinate = nanCompareBuilder.coordinate(GpuDensityExpression.Coordinate.X);
        int zeroConstant = nanCompareBuilder.constant(0.0);
        int nanValue = nanCompareBuilder.apply(GpuDensityExpression.Operation.DIVIDE,
                zeroCoordinate, zeroConstant);
        int dcmplLess = nanCompareBuilder.compare("DCMPL_LT", nanValue, zeroConstant);
        int dcmplGreaterEqual = nanCompareBuilder.compare("DCMPL_GE", nanValue, zeroConstant);
        int dcmpgLess = nanCompareBuilder.compare("DCMPG_LT", nanValue, zeroConstant);
        int dcmpgGreaterEqual = nanCompareBuilder.compare("DCMPG_GE", nanValue, zeroConstant);
        requireClose(nanCompareBuilder.build(dcmplLess).evaluate(0.0, 0.0, 0.0), 1.0,
                "DCMPL NaN ordering for less-than");
        requireClose(nanCompareBuilder.build(dcmplGreaterEqual).evaluate(0.0, 0.0, 0.0), 0.0,
                "DCMPL NaN ordering for greater-or-equal");
        requireClose(nanCompareBuilder.build(dcmpgLess).evaluate(0.0, 0.0, 0.0), 0.0,
                "DCMPG NaN ordering for less-than");
        requireClose(nanCompareBuilder.build(dcmpgGreaterEqual).evaluate(0.0, 0.0, 0.0), 1.0,
                "DCMPG NaN ordering for greater-or-equal");

        GpuDensityExpression.Builder reuseBuilder = new GpuDensityExpression.Builder();
        int sharedX = reuseBuilder.coordinate(GpuDensityExpression.Coordinate.X);
        int sharedScale = reuseBuilder.constant(4.0);
        int firstBranch = reuseBuilder.apply(GpuDensityExpression.Operation.MULTIPLY, sharedX, sharedScale);
        int secondBranch = reuseBuilder.apply(GpuDensityExpression.Operation.MULTIPLY, sharedX, sharedScale);
        if (firstBranch != secondBranch) {
            throw new AssertionError("identical pure expression nodes should share one IR value");
        }

        GpuDensityExpression.Builder compactBuilder = new GpuDensityExpression.Builder();
        int deadConstant = compactBuilder.constant(9.0);
        compactBuilder.map(GpuDensityExpression.Mapping.SQUARE, deadConstant);
        int liveCoordinate = compactBuilder.coordinate(GpuDensityExpression.Coordinate.Z);
        GpuDensityExpression compacted = compactBuilder.build(liveCoordinate);
        if (compacted.nodes().size() != 1 || compacted.root() != 0) {
            throw new AssertionError("expression build should remove unreachable nodes and remap its root");
        }
        requireClose(compacted.evaluate(1.0, 2.0, 3.0), 3.0, "compacted coordinate expression");

        GpuDensityExpression.Builder coordinateBuilder = new GpuDensityExpression.Builder();
        GpuDensityExpression coordinateOnly = coordinateBuilder.build(
                coordinateBuilder.coordinate(GpuDensityExpression.Coordinate.X));
        if (coordinateOnly.unsupportedOperation(GpuDensityExpression.Backend.TERRAIN_GRID) == null) {
            throw new AssertionError("terrain-grid capability checks must reject coordinate IR opcodes");
        }
        if (!"GpuCoordinate".equals(coordinateOnly.unsupportedOperation(
                GpuDensityExpression.Backend.TERRAIN_GRID))) {
            throw new AssertionError("terrain-grid diagnostics should name the unsupported expression node");
        }
        if (coordinateOnly.unsupportedOperation(GpuDensityExpression.Backend.POINT_SAMPLE) != null) {
            throw new AssertionError("point-sample shader should support coordinate expression nodes");
        }

        GpuDensityExpression.Builder subtractionBuilder = new GpuDensityExpression.Builder();
        int minuend = subtractionBuilder.coordinate(GpuDensityExpression.Coordinate.X);
        int subtrahend = subtractionBuilder.coordinate(GpuDensityExpression.Coordinate.Y);
        int difference = subtractionBuilder.apply(
                GpuDensityExpression.Operation.SUBTRACT, minuend, subtrahend);
        GpuDensityExpression subtraction = subtractionBuilder.build(difference);
        requireClose(subtraction.evaluate(8.0, 3.0, 0.0), 5.0, "positive subtraction");
        requireClose(subtraction.evaluate(2.0, 7.0, 0.0), -5.0, "negative subtraction");
        double[][] ieeeSamples = {
            {0.0, 0.0}, {-0.0, 0.0}, {0.0, -0.0}, {-0.0, -0.0},
            {Double.POSITIVE_INFINITY, 1.0},
            {Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY}
        };
        for (double[] sample : ieeeSamples) {
            double direct = sample[0] - sample[1];
            double lowered = sample[0] + sample[1] * -1.0;
            if (Double.doubleToLongBits(direct) != Double.doubleToLongBits(lowered)) {
                throw new AssertionError("grid subtraction lowering changed IEEE result for "
                        + sample[0] + " - " + sample[1]);
            }
        }

        NormalNoise usedSampler = NormalNoise.create(RandomSource.create(17L), -2, 1.0, 0.5);
        NormalNoise deadSampler = NormalNoise.create(RandomSource.create(29L), -1, 1.0);
        GpuDensityExpression.Builder noiseBuilder = new GpuDensityExpression.Builder();
        int sampled = noiseBuilder.noise(usedSampler, 0.125, 0.25);
        int sameSample = noiseBuilder.noise(usedSampler, 0.125, 0.25);
        if (sampled != sameSample) {
            throw new AssertionError("identical noise samples should be commoned in the expression IR");
        }
        noiseBuilder.noise(deadSampler, 0.5, 0.5);
        int gain = noiseBuilder.constant(2.0);
        int amplified = noiseBuilder.apply(GpuDensityExpression.Operation.MULTIPLY, sampled, gain);
        int bias = noiseBuilder.constant(0.25);
        int noiseResult = noiseBuilder.apply(GpuDensityExpression.Operation.SUBTRACT, amplified, bias);
        GpuDensityExpression noiseExpression = noiseBuilder.build(noiseResult);
        if (noiseExpression.samplerCount() != 1) {
            throw new AssertionError("building an expression should prune unused noise samplers");
        }
        if (noiseExpression.unsupportedOperation(GpuDensityExpression.Backend.TERRAIN_GRID) != null) {
            throw new AssertionError("NormalNoise arithmetic and subtraction should lower on the terrain grid");
        }
        double expectedNoise = (double) (float) usedSampler.getValue(
                (float) (3.0 * 0.125), (float) (-16.0 * 0.25), (float) (-8.0 * 0.125));
        requireClose(noiseExpression.evaluate(3.0, -16.0, -8.0),
                expectedNoise * 2.0 - 0.25, "sampler-backed expression");

        GpuDensityExpression.Builder floatBuilder = new GpuDensityExpression.Builder();
        int roundedConstant = floatBuilder.constant(1.00000006);
        requireClose(floatBuilder.build(roundedConstant).evaluate(0.0, 0.0, 0.0),
                (double) (float) 1.00000006, "packed-float constant");

        verifyBytecodeFrontend();
    }

    private static void verifyBytecodeFrontend() {
        NormalNoise noise = NormalNoise.create(RandomSource.create(811L), -2, 1.0, 0.5);
        BytecodeDensity density = new BytecodeDensity(noise, DensityFunctions.constant(0.125),
                0.017, 0.03125, 0.4, 1.7);
        GpuDensityBytecodeCompiler.Result compiled = GpuDensityBytecodeCompiler.compile(density);
        if (compiled.expression() == null) {
            throw new AssertionError("straight-line density bytecode should compile: " + compiled.rejection());
        }
        if (compiled.expression().densityReferences().size() != 1) {
            throw new AssertionError("nested DensityFunction.compute should become a graph dependency");
        }
        if (compiled.expression().unsupportedOperation(GpuDensityExpression.Backend.TERRAIN_GRID) != null) {
            throw new AssertionError("compiled custom density expression should be valid for terrain-grid lowering");
        }
        int[][] points = {{-193, -64, -117}, {-1, 0, 1}, {37, 91, -248}, {256, 319, 128}};
        for (int[] point : points) {
            DensityFunction.SinglePointContext context = new DensityFunction.SinglePointContext(
                    point[0], point[1], point[2]);
            double expected = density.compute(context);
            double actual = compiled.expression().evaluate(point[0], point[1], point[2]);
            if (Math.abs(expected - actual) > 0.005) {
                throw new AssertionError("bytecode density parity failed at " + point[0] + ","
                        + point[1] + "," + point[2] + ": CPU=" + expected + ", IR=" + actual);
            }
        }

        GpuDensityGraph compiledGraph = GpuDensityGraphCompiler.compileDensityFunction(density, true);
        GpuDensityGraph loweredGraph = GpuDensityGraphLowerer.lower(compiledGraph);
        if (loweredGraph.routerRoots().length != 1 || loweredGraph.registerCount() <= 0) {
            throw new AssertionError("custom density did not survive graph lowering as one GPU root");
        }
        boolean hasDependencyMarker = false;
        boolean hasExpressionMath = false;
        for (GpuDensityGraph.Node node : loweredGraph.nodes()) {
            hasDependencyMarker |= "GpuDensityBytecodeDependency".equals(node.payload());
            hasExpressionMath |= "GpuMath".equals(node.opcode()) || "Ap2".equals(node.opcode());
        }
        if (!hasDependencyMarker || !hasExpressionMath) {
            throw new AssertionError("bytecode graph lowering lost its nested density dependency or math nodes");
        }
        GpuDensityGraphBuffer.Packed packed = GpuDensityGraphBuffer.pack(loweredGraph);
        if (packed.nodeCount() != loweredGraph.nodes().size() || packed.nodes().remaining()
                != packed.nodeCount() * GpuDensityGraphBuffer.NODE_BYTES) {
            throw new AssertionError("lowered custom density graph did not produce a complete GPU node buffer");
        }

        BranchedDensity branchedDensity = new BranchedDensity();
        GpuDensityBytecodeCompiler.Result branched = GpuDensityBytecodeCompiler.compile(branchedDensity);
        if (branched.expression() == null) {
            throw new AssertionError("coordinate-conditioned density bytecode should compile: " + branched.rejection());
        }
        int[][] branchPoints = {{-19, 7, -3}, {0, 0, 0}, {23, -7, 3}};
        for (int[] point : branchPoints) {
            double actual = branched.expression().evaluate(point[0], point[1], point[2]);
            double expected = branchedDensity.compute(new DensityFunction.SinglePointContext(
                    point[0], point[1], point[2]));
            requireClose(actual, expected, "conditional bytecode at x=" + point[0]);
        }
        GpuDensityGraph branchedGraph = conditionalGraphForVulkanSmoke();
        boolean hasComparison = false, hasSelect = false;
        for (GpuDensityGraph.Node node : branchedGraph.nodes()) {
            hasComparison |= node.opcode().equals("GpuCompare");
            hasSelect |= node.opcode().equals("GpuSelect");
        }
        if (!hasComparison || !hasSelect) {
            throw new AssertionError("conditional Java bytecode was not preserved through Vulkan graph lowering");
        }
        GpuDensityGraphBuffer.Packed conditionalPacked = GpuDensityGraphBuffer.pack(branchedGraph);
        if (conditionalPacked.nodeCount() != branchedGraph.nodes().size()) {
            throw new AssertionError("conditional graph did not pack all compare/select instructions");
        }

        SwitchDensity switchDensity = new SwitchDensity();
        GpuDensityBytecodeCompiler.Result switched = GpuDensityBytecodeCompiler.compile(switchDensity);
        if (switched.expression() == null) {
            throw new AssertionError("bounded integer switch bytecode should compile: " + switched.rejection());
        }
        for (int xValue : new int[]{-7, -1, 0, 1, 2, 100}) {
            double expected = switchDensity.compute(new DensityFunction.SinglePointContext(xValue, 0, 0));
            requireClose(switched.expression().evaluate(xValue, 0, 0), expected,
                    "switch bytecode at x=" + xValue);
        }
        GpuDensityGraphBuffer.pack(switchGraphForVulkanSmoke());

        RemainderDensity remainderDensity = new RemainderDensity();
        GpuDensityBytecodeCompiler.Result remainder = GpuDensityBytecodeCompiler.compile(remainderDensity);
        if (remainder.expression() == null) {
            throw new AssertionError("constant-divisor integer remainder should compile: " + remainder.rejection());
        }
        for (int xValue : new int[]{-33, -17, -16, -1, 0, 1, 15, 16, 17, 31, 32}) {
            double expected = remainderDensity.compute(new DensityFunction.SinglePointContext(xValue, 0, 0));
            requireClose(remainder.expression().evaluate(xValue, 0, 0), expected,
                    "Java integer remainder at x=" + xValue);
        }
        GpuDensityGraphBuffer.pack(remainderGraphForVulkanSmoke());
        GpuDensityBytecodeCompiler.Result loop = GpuDensityBytecodeCompiler.compile(new LoopingDensity());
        if (loop.expression() != null || loop.rejection() == null
                || !(loop.rejection().contains("statically bounded") || loop.rejection().contains("loop"))) {
            throw new AssertionError("backward bytecode loops must remain on the CPU path with a clear diagnostic");
        }

        BoundedLoopDensity boundedLoop = new BoundedLoopDensity();
        GpuDensityBytecodeCompiler.Result boundedLoopResult = GpuDensityBytecodeCompiler.compile(boundedLoop);
        if (boundedLoopResult.expression() == null) {
            throw new AssertionError("constant-bounded terrain loop should unroll: " + boundedLoopResult.rejection());
        }
        for (int xValue : new int[]{-30_000_001, -17, 0, 7, 1_234_567, 30_000_001}) {
            double expected = boundedLoop.compute(new DensityFunction.SinglePointContext(xValue, 0, 0));
            requireClose(boundedLoopResult.expression().evaluate(xValue, 0, 0), expected,
                    "bounded loop bytecode at x=" + xValue);
        }
        GpuDensityGraphBuffer.pack(boundedLoopFixture().graph());

        ScalarMathDensity scalarMath = new ScalarMathDensity();
        GpuDensityBytecodeCompiler.Result scalarMathResult = GpuDensityBytecodeCompiler.compile(scalarMath);
        if (scalarMathResult.expression() == null) {
            throw new AssertionError("common scalar Math bytecode should compile: " + scalarMathResult.rejection());
        }
        for (int[] point : new int[][]{{-81, -17, -5}, {-1, 0, 1}, {17, 9, 31}}) {
            DensityFunction.SinglePointContext context = new DensityFunction.SinglePointContext(
                    point[0], point[1], point[2]);
            requireClose(scalarMathResult.expression().evaluate(point[0], point[1], point[2]),
                    scalarMath.compute(context), "scalar Math bytecode at x=" + point[0]);
        }
        GpuDensityGraph scalarMathGraph = scalarMathFixture().graph();
        boolean hasGridCoordinate = scalarMathGraph.nodes().stream()
                .anyMatch(node -> node.opcode().equals("GpuCoordinate"));
        boolean hasDirectDoubleDivision = scalarMathGraph.nodes().stream()
                .anyMatch(node -> node.opcode().equals("GpuMath")
                        && "EXPR_DIVIDE".equals(node.payload()));
        if (!hasGridCoordinate || !hasDirectDoubleDivision) {
            throw new AssertionError("terrain-grid bytecode lowering must preserve coordinate and division ops");
        }
        GpuDensityGraphBuffer.pack(scalarMathGraph);

        DynamicPowerDensity dynamicPower = new DynamicPowerDensity();
        GpuDensityBytecodeCompiler.Result dynamicPowerResult = GpuDensityBytecodeCompiler.compile(dynamicPower);
        if (dynamicPowerResult.expression() == null) {
            throw new AssertionError("dynamic Java Math.pow should compile: " + dynamicPowerResult.rejection());
        }
        for (int exponentCoordinate : new int[]{-6, -1, 0, 1, 4, 6}) {
            double expected = dynamicPower.compute(
                    new DensityFunction.SinglePointContext(-2, exponentCoordinate, 0));
            double actual = dynamicPowerResult.expression().evaluate(-2, exponentCoordinate, 0);
            if (Double.isNaN(expected) ? !Double.isNaN(actual) : Math.abs(actual - expected) > 1.0e-12) {
                throw new AssertionError("dynamic Java power at exponent coordinate " + exponentCoordinate
                        + " expected " + expected + ", got " + actual);
            }
        }
        if (!Double.isNaN(dynamicPowerResult.expression().evaluate(-2, 1, 0))) {
            throw new AssertionError("negative base with fractional dynamic exponent must produce NaN");
        }
        GpuDensityGraphBuffer.pack(dynamicPowerFixture().graph());

        StaticConstantDensity staticConstants = new StaticConstantDensity();
        GpuDensityBytecodeCompiler.Result staticConstantResult =
                GpuDensityBytecodeCompiler.compile(staticConstants);
        if (staticConstantResult.expression() == null) {
            throw new AssertionError("static final terrain constants should compile: "
                    + staticConstantResult.rejection());
        }
        for (int xValue : new int[]{-128, -1, 0, 37, 1024}) {
            double expected = staticConstants.compute(
                    new DensityFunction.SinglePointContext(xValue, 0, 0));
            requireClose(staticConstantResult.expression().evaluate(xValue, 0, 0), expected,
                    "static final terrain constant at x=" + xValue);
        }
        GpuDensityGraphBuffer.pack(GpuDensityGraphLowerer.lower(
                GpuDensityGraphCompiler.compileDensityFunction(staticConstants, true)));

        IntegerMathDensity integerMath = new IntegerMathDensity();
        GpuDensityBytecodeCompiler.Result integerMathResult = GpuDensityBytecodeCompiler.compile(integerMath);
        if (integerMathResult.expression() == null) {
            throw new AssertionError("Java int arithmetic should compile: " + integerMathResult.rejection());
        }
        for (int xValue : new int[]{Integer.MIN_VALUE, -30_000_001, -17, -1, 0, 19,
                30_000_001, Integer.MAX_VALUE}) {
            double expected = integerMath.compute(new DensityFunction.SinglePointContext(xValue, 0, 0));
            requireClose(integerMathResult.expression().evaluate(xValue, 0, 0), expected,
                    "Java int arithmetic at x=" + xValue);
        }
        GpuDensityGraphBuffer.pack(integerMathFixture().graph());

        IntegerBitwiseDensity bitwise = new IntegerBitwiseDensity();
        GpuDensityBytecodeCompiler.Result bitwiseResult = GpuDensityBytecodeCompiler.compile(bitwise);
        if (bitwiseResult.expression() == null) {
            throw new AssertionError("Java integer bitwise/shift operations should compile: "
                    + bitwiseResult.rejection());
        }
        for (int xValue : new int[]{-30_000_001, -257, -1, 0, 1, 19, 257, 30_000_001}) {
            double expected = bitwise.compute(new DensityFunction.SinglePointContext(xValue, 0, 0));
            requireClose(bitwiseResult.expression().evaluate(xValue, 0, 0), expected,
                    "Java integer bitwise/shift operations at x=" + xValue);
        }
        GpuDensityGraphBuffer.pack(integerBitwiseFixture().graph());
        IntegerDivisionOverflowDensity divisionOverflow = new IntegerDivisionOverflowDensity();
        GpuDensityBytecodeCompiler.Result divisionOverflowResult =
                GpuDensityBytecodeCompiler.compile(divisionOverflow);
        if (divisionOverflowResult.expression() == null) {
            throw new AssertionError("Java MIN_VALUE / -1 integer division should compile: "
                    + divisionOverflowResult.rejection());
        }
        requireClose(divisionOverflowResult.expression().evaluate(0, 0, 0), Integer.MIN_VALUE,
                "Java integer division MIN_VALUE / -1");
        if (GpuDensityBytecodeCompiler.compile(new DynamicIntegerDivisionDensity()).expression() != null) {
            throw new AssertionError("integer division by a dynamic divisor must remain on the Java path");
        }
        GpuDensityGraphBuffer.pack(integerDivisionOverflowFixture().graph());
        FloorDivisionDensity floorDivision = new FloorDivisionDensity();
        GpuDensityBytecodeCompiler.Result floorDivisionResult = GpuDensityBytecodeCompiler.compile(floorDivision);
        if (floorDivisionResult.expression() == null) {
            throw new AssertionError("Math.floorDiv/floorMod should compile: " + floorDivisionResult.rejection());
        }
        for (int xValue : new int[]{-33, -32, -17, -16, -15, -1, 0, 1, 15, 16, 17, 32, 33}) {
            double expected = floorDivision.compute(new DensityFunction.SinglePointContext(xValue, 0, 0));
            requireClose(floorDivisionResult.expression().evaluate(xValue, 0, 0), expected,
                    "Java floorDiv/floorMod at x=" + xValue);
        }
        if (GpuDensityBytecodeCompiler.compile(new DynamicFloorDivisionDensity()).expression() != null) {
            throw new AssertionError("floorDiv/floorMod by a dynamic divisor must remain on the Java path");
        }
        GpuDensityGraphBuffer.pack(floorDivisionFixture().graph());
        GpuDensityBytecodeCompiler.Result narrowing = GpuDensityBytecodeCompiler.compile(new NarrowingDensity());
        if (narrowing.expression() == null) {
            throw new AssertionError("Java byte/short/char narrowing should compile: " + narrowing.rejection());
        }
        NarrowingDensity narrowingDensity = new NarrowingDensity();
        for (int xValue : new int[]{-257, -129, -1, 0, 127, 128, 255, 65_537}) {
            double expected = narrowingDensity.compute(new DensityFunction.SinglePointContext(xValue, 0, 0));
            requireClose(narrowing.expression().evaluate(xValue, 0, 0), expected,
                    "Java narrowing conversions at x=" + xValue);
        }
        FloatArithmeticDensity floatArithmetic = new FloatArithmeticDensity();
        GpuDensityBytecodeCompiler.Result floatResult = GpuDensityBytecodeCompiler.compile(floatArithmetic);
        if (floatResult.expression() == null) {
            throw new AssertionError("float arithmetic should preserve explicit JVM rounding: "
                    + floatResult.rejection());
        }
        for (int xValue : new int[]{-30_000_001, -257, -1, 0, 7, 1_234_567, 30_000_001}) {
            double expected = floatArithmetic.compute(new DensityFunction.SinglePointContext(xValue, 0, 0));
            requireClose(floatResult.expression().evaluate(xValue, 0, 0), expected,
                    "JVM float arithmetic at x=" + xValue);
        }
        GpuDensityGraphBuffer.pack(floatArithmeticFixture().graph());
        FloatCompareDensity floatCompare = new FloatCompareDensity();
        GpuDensityBytecodeCompiler.Result floatCompareResult = GpuDensityBytecodeCompiler.compile(floatCompare);
        if (floatCompareResult.expression() == null) {
            throw new AssertionError("float compare and branch bytecode should compile: "
                    + floatCompareResult.rejection());
        }
        for (int xValue : new int[]{-7, -1, 0, 1, 7}) {
            double expected = floatCompare.compute(new DensityFunction.SinglePointContext(xValue, 0, 0));
            requireClose(floatCompareResult.expression().evaluate(xValue, 0, 0), expected,
                    "JVM float comparison at x=" + xValue);
        }
        GpuDensityGraphBuffer.pack(floatCompareFixture().graph());

        VulkanBranchFixture floatingFixture = floatingConditionalFixture();
        GpuDensityBytecodeCompiler.Result floatingCompiled = GpuDensityBytecodeCompiler.compile(
                floatingFixture.javaFunction());
        if (floatingCompiled.expression() == null) {
            throw new AssertionError("floating conditional bytecode should compile: " + floatingCompiled.rejection());
        }
        int[][] floatingPoints = {{-83, -64, 127}, {-1, 0, 1}, {53, 91, -248}, {256, 319, 128}};
        for (int[] point : floatingPoints) {
            DensityFunction.SinglePointContext context = new DensityFunction.SinglePointContext(
                    point[0], point[1], point[2]);
            double expected = floatingFixture.javaFunction().compute(context);
            double actual = floatingCompiled.expression().evaluate(point[0], point[1], point[2]);
            if (Math.abs(expected - actual) > 0.005) {
                throw new AssertionError("floating branch parity failed at " + point[0] + ","
                        + point[1] + "," + point[2] + ": CPU=" + expected + ", IR=" + actual);
            }
        }
    }

    private static final class BytecodeDensity implements DensityFunction.SimpleFunction {
        private final NormalNoise noise;
        private final DensityFunction dependency;
        private final double horizontalScale;
        private final double verticalScale;
        private final double offset;
        private final double gain;

        private BytecodeDensity(NormalNoise noise, DensityFunction dependency,
                                double horizontalScale, double verticalScale,
                                double offset, double gain) {
            this.noise = noise;
            this.dependency = dependency;
            this.horizontalScale = horizontalScale;
            this.verticalScale = verticalScale;
            this.offset = offset;
            this.gain = gain;
        }

        @Override
        public double compute(FunctionContext context) {
            return saturate(curve(sample(context)) + dependency.compute(context));
        }

        private double sample(FunctionContext context) {
            return noise.getValue(context.blockX() * horizontalScale,
                    context.blockY() * verticalScale, context.blockZ() * horizontalScale);
        }

        private double curve(double sampled) {
            double blended = net.minecraft.util.Mth.lerp(0.25, sampled, sampled + offset);
            return (net.minecraft.util.Mth.square(blended) + Math.pow(0.75, 3.0)) * gain;
        }

        private double saturate(double value) {
            return net.minecraft.util.Mth.clamp(Math.max(-1.0, Math.min(1.0, value)), -1.0, 1.0);
        }

        @Override public DensityFunction mapAll(Visitor visitor) { return visitor.apply(this); }
        @Override public double minValue() { return -1.0; }
        @Override public double maxValue() { return 1.0; }
        @Override public net.minecraft.util.KeyDispatchDataCodec<? extends DensityFunction> codec() {
            return DensityFunctions.constant(0.0).codec();
        }
    }

    private static final class BranchedDensity implements DensityFunction.SimpleFunction {
        @Override
        public double compute(FunctionContext context) {
            return context.blockX() < 0 ? -1.0 : context.blockX() == 0 ? 0.0 : 1.0;
        }

        @Override public DensityFunction mapAll(Visitor visitor) { return visitor.apply(this); }
        @Override public double minValue() { return -1.0; }
        @Override public double maxValue() { return 1.0; }
        @Override public net.minecraft.util.KeyDispatchDataCodec<? extends DensityFunction> codec() {
            return DensityFunctions.constant(0.0).codec();
        }
    }

    private static final class LoopingDensity implements DensityFunction.SimpleFunction {
        @Override
        public double compute(FunctionContext context) {
            while (context.blockX() < -1) { }
            return 1.0;
        }

        @Override public DensityFunction mapAll(Visitor visitor) { return visitor.apply(this); }
        @Override public double minValue() { return 1.0; }
        @Override public double maxValue() { return 1.0; }
        @Override public net.minecraft.util.KeyDispatchDataCodec<? extends DensityFunction> codec() {
            return DensityFunctions.constant(1.0).codec();
        }
    }

    private static final class BoundedLoopDensity implements DensityFunction.SimpleFunction {
        @Override public double compute(FunctionContext context) {
            double sum = 0.0;
            for (int octave = 0; octave < 6; octave++) {
                sum += (context.blockX() + octave) * 0.125;
            }
            return sum;
        }
        @Override public DensityFunction mapAll(Visitor visitor) { return visitor.apply(this); }
        @Override public double minValue() { return -4_000_000.0; }
        @Override public double maxValue() { return 4_000_000.0; }
        @Override public net.minecraft.util.KeyDispatchDataCodec<? extends DensityFunction> codec() {
            return DensityFunctions.constant(0.0).codec();
        }
    }

    private static final class FloatingBranchedDensity implements DensityFunction.SimpleFunction {
        private final NormalNoise noise;
        private final double horizontalScale;
        private final double verticalScale;
        private final double threshold;

        private FloatingBranchedDensity(NormalNoise noise, double horizontalScale,
                                        double verticalScale, double threshold) {
            this.noise = noise;
            this.horizontalScale = horizontalScale;
            this.verticalScale = verticalScale;
            this.threshold = threshold;
        }

        @Override
        public double compute(FunctionContext context) {
            double sampled = noise.getValue(context.blockX() * horizontalScale,
                    context.blockY() * verticalScale, context.blockZ() * horizontalScale);
            return sampled > threshold ? sampled + 0.25 : sampled - 0.25;
        }

        @Override public DensityFunction mapAll(Visitor visitor) { return visitor.apply(this); }
        @Override public double minValue() { return -2.0; }
        @Override public double maxValue() { return 2.0; }
        @Override public net.minecraft.util.KeyDispatchDataCodec<? extends DensityFunction> codec() {
            return DensityFunctions.constant(0.0).codec();
        }
    }

    private static final class SwitchDensity implements DensityFunction.SimpleFunction {
        @Override
        public double compute(FunctionContext context) {
            return switch (context.blockX()) {
                case -1 -> -1.0;
                case 0 -> 0.0;
                case 1 -> 1.0;
                default -> 2.0;
            };
        }

        @Override public DensityFunction mapAll(Visitor visitor) { return visitor.apply(this); }
        @Override public double minValue() { return -1.0; }
        @Override public double maxValue() { return 2.0; }
        @Override public net.minecraft.util.KeyDispatchDataCodec<? extends DensityFunction> codec() {
            return DensityFunctions.constant(0.0).codec();
        }
    }

    private static final class RemainderDensity implements DensityFunction.SimpleFunction {
        @Override
        public double compute(FunctionContext context) {
            int remainder = context.blockX() % 16;
            return remainder < 0 ? remainder - 0.5 : remainder + 0.5;
        }

        @Override public DensityFunction mapAll(Visitor visitor) { return visitor.apply(this); }
        @Override public double minValue() { return -16.5; }
        @Override public double maxValue() { return 15.5; }
        @Override public net.minecraft.util.KeyDispatchDataCodec<? extends DensityFunction> codec() {
            return DensityFunctions.constant(0.0).codec();
        }
    }

    private static void requireClose(double actual, double expected, String label) {
        if (Math.abs(actual - expected) > 1.0e-12) {
            throw new AssertionError(label + " expected " + expected + ", got " + actual);
        }
    }

    private static void requireOpcode(String name, int expected) {
        int actual = GpuDensityGraphBuffer.operation(name);
        if (actual != expected) {
            throw new AssertionError(name + " opcode expected " + expected + ", got " + actual);
        }
    }

    private static final class ScalarMathDensity implements DensityFunction.SimpleFunction {
        @Override public double compute(FunctionContext context) {
            double x = context.blockX();
            double y = context.blockY();
            double z = context.blockZ();
            return Math.floor(y * 0.25) + Math.ceil(x * 0.1)
                    + Math.sqrt(Math.abs(x)) + Math.sin(x * 0.05)
                    + Math.cos(z * 0.05) + Math.log(Math.exp(0.25))
                    + Math.atan2(z, x) * 0.05
                    + Math.pow(0.5 + Math.abs(x) * 0.01 + Math.abs(z) * 0.005, 1.7) * 0.1
                    + Math.signum(x) * 0.01
                    + Math.tanh(x * 0.01) * 0.1
                    + x / (Math.abs(z) + 1.0) * 0.002
                    + net.minecraft.util.Mth.map(x, -128.0, 128.0, -1.0, 1.0)
                    + net.minecraft.util.Mth.clampedMap(y, -32.0, 32.0, -1.0, 1.0)
                    + net.minecraft.util.Mth.inverseLerp(z, -64.0, 64.0)
                    + net.minecraft.util.Mth.absMax(x, z) * 0.001
                    + net.minecraft.util.Mth.smoothstep(x / 256.0)
                    + net.minecraft.util.Mth.clampedLerp(-2.0, 3.0, x / 32.0) * 0.01
                    + net.minecraft.util.Mth.lerp2(x / 64.0, y / 32.0,
                            -3.0, 1.0, 2.0, -1.0) * 0.02
                    + net.minecraft.util.Mth.lerp3(x / 64.0, y / 32.0, z / 64.0,
                            -2.0, 1.0, 0.5, 3.0, 4.0, -1.5, 2.5, -3.0) * 0.01;
        }
        @Override public DensityFunction mapAll(Visitor visitor) { return visitor.apply(this); }
        @Override public double minValue() { return -100.0; }
        @Override public double maxValue() { return 100.0; }
        @Override public net.minecraft.util.KeyDispatchDataCodec<? extends DensityFunction> codec() {
            return DensityFunctions.constant(0.0).codec();
        }
    }

    private static final class DynamicPowerDensity implements DensityFunction.SimpleFunction {
        @Override public double compute(FunctionContext context) {
            return Math.pow(-Math.abs((double) context.blockX()) - 0.25, context.blockY() * 0.5);
        }
        @Override public DensityFunction mapAll(Visitor visitor) { return visitor.apply(this); }
        @Override public double minValue() { return Double.NEGATIVE_INFINITY; }
        @Override public double maxValue() { return Double.POSITIVE_INFINITY; }
        @Override public net.minecraft.util.KeyDispatchDataCodec<? extends DensityFunction> codec() {
            return DensityFunctions.constant(0.0).codec();
        }
    }

    private interface StaticTerrainConstants {
        double SCALE = initializeScale();
        int OFFSET = initializeOffset();
        private static double initializeScale() { return Math.sqrt(2.0) / 7.0; }
        private static int initializeOffset() { return 11; }
    }

    private static final class StaticConstantDensity
            implements DensityFunction.SimpleFunction, StaticTerrainConstants {
        @Override public double compute(FunctionContext context) {
            return context.blockX() * SCALE + OFFSET;
        }
        @Override public DensityFunction mapAll(Visitor visitor) { return visitor.apply(this); }
        @Override public double minValue() { return -100_000.0; }
        @Override public double maxValue() { return 100_000.0; }
        @Override public net.minecraft.util.KeyDispatchDataCodec<? extends DensityFunction> codec() {
            return DensityFunctions.constant(0.0).codec();
        }
    }

    private static final class IntegerMathDensity implements DensityFunction.SimpleFunction {
        @Override public double compute(FunctionContext context) {
            int x = context.blockX();
            x += 3;
            int product = x * 100;
            int shifted = product + 17;
            int quotient = shifted / 7;
            return quotient + x % 13;
        }
        @Override public DensityFunction mapAll(Visitor visitor) { return visitor.apply(this); }
        @Override public double minValue() { return Integer.MIN_VALUE; }
        @Override public double maxValue() { return Integer.MAX_VALUE; }
        @Override public net.minecraft.util.KeyDispatchDataCodec<? extends DensityFunction> codec() {
            return DensityFunctions.constant(0.0).codec();
        }
    }

    private static final class IntegerBitwiseDensity implements DensityFunction.SimpleFunction {
        @Override public double compute(FunctionContext context) {
            int value = context.blockX();
            int mixed = value ^ (value << 13);
            mixed = (mixed >> 7) | (mixed >>> 25);
            mixed *= 0x45d9f3;
            mixed ^= mixed >>> 16;
            return (mixed & 0xffff) * 0.001;
        }
        @Override public DensityFunction mapAll(Visitor visitor) { return visitor.apply(this); }
        @Override public double minValue() { return 0.0; }
        @Override public double maxValue() { return 65.535; }
        @Override public net.minecraft.util.KeyDispatchDataCodec<? extends DensityFunction> codec() {
            return DensityFunctions.constant(0.0).codec();
        }
    }

    private static final class IntegerDivisionOverflowDensity implements DensityFunction.SimpleFunction {
        @Override public double compute(FunctionContext context) {
            int numerator = context.blockX() + Integer.MIN_VALUE;
            return numerator / -1;
        }
        @Override public DensityFunction mapAll(Visitor visitor) { return visitor.apply(this); }
        @Override public double minValue() { return Integer.MIN_VALUE; }
        @Override public double maxValue() { return Integer.MAX_VALUE; }
        @Override public net.minecraft.util.KeyDispatchDataCodec<? extends DensityFunction> codec() {
            return DensityFunctions.constant(0.0).codec();
        }
    }

    private static final class DynamicIntegerDivisionDensity implements DensityFunction.SimpleFunction {
        @Override public double compute(FunctionContext context) {
            return context.blockX() / context.blockY();
        }
        @Override public DensityFunction mapAll(Visitor visitor) { return visitor.apply(this); }
        @Override public double minValue() { return Integer.MIN_VALUE; }
        @Override public double maxValue() { return Integer.MAX_VALUE; }
        @Override public net.minecraft.util.KeyDispatchDataCodec<? extends DensityFunction> codec() {
            return DensityFunctions.constant(0.0).codec();
        }
    }

    private static final class FloorDivisionDensity implements DensityFunction.SimpleFunction {
        @Override public double compute(FunctionContext context) {
            int x = context.blockX();
            int positiveQuotient = Math.floorDiv(x, 16);
            int positiveRemainder = Math.floorMod(x, 16);
            int negativeRemainder = Math.floorMod(x, -16);
            return positiveQuotient * 1_000 + positiveRemainder * 10 + negativeRemainder;
        }
        @Override public DensityFunction mapAll(Visitor visitor) { return visitor.apply(this); }
        @Override public double minValue() { return -140_000_000_000.0; }
        @Override public double maxValue() { return 140_000_000_000.0; }
        @Override public net.minecraft.util.KeyDispatchDataCodec<? extends DensityFunction> codec() {
            return DensityFunctions.constant(0.0).codec();
        }
    }

    private static final class DynamicFloorDivisionDensity implements DensityFunction.SimpleFunction {
        @Override public double compute(FunctionContext context) {
            return Math.floorDiv(context.blockX(), context.blockY())
                    + Math.floorMod(context.blockX(), context.blockY());
        }
        @Override public DensityFunction mapAll(Visitor visitor) { return visitor.apply(this); }
        @Override public double minValue() { return Integer.MIN_VALUE; }
        @Override public double maxValue() { return Integer.MAX_VALUE; }
        @Override public net.minecraft.util.KeyDispatchDataCodec<? extends DensityFunction> codec() {
            return DensityFunctions.constant(0.0).codec();
        }
    }

    private static final class NarrowingDensity implements DensityFunction.SimpleFunction {
        @Override public double compute(FunctionContext context) {
            int x = context.blockX();
            byte byteValue = (byte) x;
            short shortValue = (short) (x + 32_000);
            char charValue = (char) (x + 65_535);
            return byteValue + shortValue + charValue;
        }
        @Override public DensityFunction mapAll(Visitor visitor) { return visitor.apply(this); }
        @Override public double minValue() { return -65_536.0; }
        @Override public double maxValue() { return 131_071.0; }
        @Override public net.minecraft.util.KeyDispatchDataCodec<? extends DensityFunction> codec() {
            return DensityFunctions.constant(0.0).codec();
        }
    }

    private static final class FloatArithmeticDensity implements DensityFunction.SimpleFunction {
        @Override public double compute(FunctionContext context) {
            double source = context.blockX() * 0.125;
            float rounded = (float) source;
            float product = rounded * 1.125f;
            int integral = (int) product;
            int sourceIntegral = (int) source;
            float integralFloat = (float) integral;
            float remainder = product % 3.0f;
            return (double) integral + (double) product + (double) sourceIntegral
                    + (double) integralFloat + (double) remainder;
        }
        @Override public DensityFunction mapAll(Visitor visitor) { return visitor.apply(this); }
        @Override public double minValue() { return -10.0; }
        @Override public double maxValue() { return 10.0; }
        @Override public net.minecraft.util.KeyDispatchDataCodec<? extends DensityFunction> codec() {
            return DensityFunctions.constant(0.0).codec();
        }
    }

    private static final class FloatCompareDensity implements DensityFunction.SimpleFunction {
        @Override public double compute(FunctionContext context) {
            float x = context.blockX() * 0.5f;
            float value = x / 0.0f;
            if (value > 0.0f) return 1.0;
            if (value < 0.0f) return -1.0;
            return 0.0;
        }
        @Override public DensityFunction mapAll(Visitor visitor) { return visitor.apply(this); }
        @Override public double minValue() { return -1.0; }
        @Override public double maxValue() { return 1.0; }
        @Override public net.minecraft.util.KeyDispatchDataCodec<? extends DensityFunction> codec() {
            return DensityFunctions.constant(0.0).codec();
        }
    }
}
