package dev.gpunoise;

import net.minecraft.util.RandomSource;
import net.minecraft.core.Holder;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import java.util.HashSet;
import java.util.Set;

/** CPU-side public-API checks used by the GitHub Actions Vulkan smoke entry point. */
final class GpuDensityExpressionSmoke {
    private static final double FLOAT_ABI_ABSOLUTE_EPSILON = 1.0e-6;
    private static final double FLOAT_ABI_RELATIVE_EPSILON = 1.0e-7;

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

    static VulkanBranchFixture transformedNoiseFixture() {
        NormalNoise noise = NormalNoise.create(RandomSource.create(811L), -2, 1.0, 0.5);
        DensityFunction function = new TransformedNoiseDensity(noise,
                new ConstantReferenceDensity(0.03125), new ConstantReferenceDensity(-0.0625));
        GpuDensityGraph graph = GpuDensityGraphLowerer.lower(
                GpuDensityGraphCompiler.compileDensityFunction(function, true));
        return new VulkanBranchFixture(graph, function);
    }

    /** Exercises the vanilla Noise opcode independently of the custom GpuNoise3 expression path. */
    static VulkanBranchFixture vanillaNoiseOpcodeFixture() {
        NormalNoise noise = NormalNoise.create(RandomSource.create(913L), -2, 1.0, 0.5);
        DensityFunction function = new DirectNormalNoiseDensity(noise);
        GpuDensityGraph graph = new GpuDensityGraph(
                java.util.List.of(new GpuDensityGraph.Node("Noise", new int[0],
                        new double[]{0.0, 1.0, 1.0}, null)),
                new int[]{0}, java.util.List.of(noise), java.util.List.of(), java.util.List.of(),
                java.util.List.of(function));
        return new VulkanBranchFixture(GpuDensityGraphLowerer.lower(graph), function);
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

    static VulkanBranchFixture doubleMinMaxFixture() {
        return graphFixture(new DoubleMinMaxDensity());
    }

    static VulkanBranchFixture minMaxEdgeFixture() {
        return graphFixture(new MinMaxEdgeDensity());
    }

    static VulkanBranchFixture floatMathFixture() {
        return graphFixture(new FloatMathDensity());
    }

    static VulkanBranchFixture floatMinMaxEdgeFixture() {
        return graphFixture(new FloatMinMaxEdgeDensity());
    }

    static VulkanBranchFixture dynamicClampFixture() {
        return graphFixture(new DynamicClampDensity());
    }

    static VulkanBranchFixture transcendentalMathFixture() {
        return graphFixture(new TranscendentalMathDensity());
    }

    static VulkanBranchFixture powerMathFixture() {
        return graphFixture(new PowerMathDensity());
    }

    static VulkanBranchFixture atan2MathFixture() {
        return graphFixture(new Atan2MathDensity());
    }

    static VulkanBranchFixture atan2SignedZeroFixture() {
        return graphFixture(new Atan2SignedZeroDensity());
    }

    static VulkanBranchFixture signumMathFixture() {
        return graphFixture(new SignumMathDensity());
    }

    static VulkanBranchFixture copySignMathFixture() {
        return graphFixture(new CopySignMathDensity());
    }

    static VulkanBranchFixture floatCopySignMathFixture() {
        return graphFixture(new FloatCopySignMathDensity());
    }

    static VulkanBranchFixture mapMathFixture() {
        return graphFixture(new MapMathDensity());
    }

    static VulkanBranchFixture interpolationMathFixture() {
        return graphFixture(new InterpolationMathDensity());
    }

    static VulkanBranchFixture floatInterpolationMathFixture() {
        return graphFixture(new FloatInterpolationMathDensity());
    }

    static VulkanBranchFixture moduloAndAngleMathFixture() {
        return graphFixture(new ModuloAndAngleMathDensity());
    }

    static VulkanBranchFixture triangleWaveMathFixture() {
        return graphFixture(new TriangleWaveMathDensity());
    }

    static VulkanBranchFixture angleMotionMathFixture() {
        return graphFixture(new AngleMotionMathDensity());
    }

    static VulkanBranchFixture vectorLengthMathFixture() {
        return graphFixture(new VectorLengthMathDensity());
    }

    static VulkanBranchFixture integerInterpolationMathFixture() {
        return graphFixture(new IntegerInterpolationMathDensity());
    }

    static VulkanBranchFixture externalStaticHelperFixture() {
        return graphFixture(new ExternalStaticHelperDensity());
    }

    static VulkanBranchFixture constantArrayHelperFixture() {
        return graphFixture(new ConstantArrayHelperDensity());
    }

    static VulkanBranchFixture inheritedComputeFixture() {
        return graphFixture(new InheritedComputeDensity());
    }

    static VulkanBranchFixture defaultInterfaceComputeFixture() {
        return graphFixture(new DefaultInterfaceDensity());
    }

    static VulkanBranchFixture recordConfigurationFixture() {
        return graphFixture(new RecordConfigurationDensity());
    }

    static VulkanBranchFixture finalFieldConfigurationFixture() {
        return graphFixture(new FinalFieldConfigurationDensity());
    }

    static VulkanBranchFixture holderBackedConfigurationFixture() {
        return graphFixture(new HolderBackedConfigurationDensity());
    }

    static VulkanBranchFixture enumSwitchConfigurationFixture() {
        return graphFixture(new EnumSwitchConfigurationDensity());
    }

    static VulkanBranchFixture stringSwitchConfigurationFixture() {
        return graphFixture(new StringSwitchConfigurationDensity());
    }

    static VulkanBranchFixture stringComparisonConfigurationFixture() {
        return graphFixture(new StringComparisonConfigurationDensity());
    }

    static VulkanBranchFixture objectsEqualsConfigurationFixture() {
        return graphFixture(new ObjectsEqualsConfigurationDensity());
    }

    static VulkanBranchFixture enumEqualityConfigurationFixture() {
        return graphFixture(new EnumEqualityConfigurationDensity());
    }

    static VulkanBranchFixture holderBackedEnumConfigurationFixture() {
        return graphFixture(new HolderBackedEnumConfigurationDensity());
    }

    static VulkanBranchFixture holderTypeGuardConfigurationFixture() {
        return graphFixture(new HolderTypeGuardConfigurationDensity());
    }

    static VulkanBranchFixture mixedTerrainRootFixture() {
        DensityFunction supported = new FinalFieldConfigurationDensity();
        GpuDensityGraph baseline = GpuDensityGraphLowerer.lower(
                GpuDensityGraphCompiler.compileTerrainRoots(new DensityFunction[]{supported}));
        DensityFunction fallback = new PartiallyUnsupportedTerrainDensity(
                new ConstantReferenceDensity(0.25), new UnsupportedTerrainDensity());
        GpuDensityGraph graph = GpuDensityGraphLowerer.lower(GpuDensityGraphCompiler.compileTerrainRoots(
                new DensityFunction[]{supported, fallback}));
        if (!graph.supportsRouterRoot(0) || graph.supportsRouterRoot(1)
                || graph.unsupportedRouterRootReasons()[1] == null) {
            throw new AssertionError("unsupported custom terrain root should be isolated from its GPU sibling");
        }
        String rejection = graph.unsupportedRouterRootReasons()[1];
        if (!rejection.contains("dependency path [")
                || !rejection.contains(PartiallyUnsupportedTerrainDensity.class.getName())
                || !rejection.contains(UnsupportedTerrainDensity.class.getName())) {
            throw new AssertionError("unsupported custom terrain diagnostic omitted its dependency path: "
                    + rejection);
        }
        if (graph.nodes().size() != baseline.nodes().size()
                || graph.noises().size() != baseline.noises().size()) {
            throw new AssertionError("failed root compilation leaked nodes or samplers into its GPU sibling");
        }
        return new VulkanBranchFixture(graph, supported);
    }

    static VulkanBranchFixture minecraftMathOverloadsFixture() {
        return graphFixture(new MinecraftMathOverloadsDensity());
    }

    private static VulkanBranchFixture graphFixture(DensityFunction function) {
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

    static VulkanBranchFixture doubleDivisionFixture() {
        DensityFunction function = new DoubleDivisionDensity();
        return new VulkanBranchFixture(GpuDensityGraphLowerer.lower(
                GpuDensityGraphCompiler.compileDensityFunction(function, true)), function);
    }

    static VulkanBranchFixture boundedLoopFixture() {
        DensityFunction function = new BoundedLoopDensity();
        return new VulkanBranchFixture(GpuDensityGraphLowerer.lower(
                GpuDensityGraphCompiler.compileDensityFunction(function, true)), function);
    }

    static VulkanBranchFixture boundedConditionalLoopFixture() {
        DensityFunction function = new BoundedConditionalLoopDensity();
        return new VulkanBranchFixture(GpuDensityGraphLowerer.lower(
                GpuDensityGraphCompiler.compileDensityFunction(function, true)), function);
    }

    static VulkanBranchFixture boundedSwitchLoopFixture() {
        DensityFunction function = new BoundedSwitchLoopDensity();
        return new VulkanBranchFixture(GpuDensityGraphLowerer.lower(
                GpuDensityGraphCompiler.compileDensityFunction(function, true)), function);
    }

    static void verify() {
        verifyWorldgenDependencyIndex();
        verifyParityRootPruning();
        verifyTerrainDecisionFallback();
        verifyTransparentMarkerElimination();
        verifyConstantBranchPruning();
        verifyConstantArithmeticFolding();
        requireOpcode("EXPR_ADD", 1);
        requireOpcode("EXPR_SUBTRACT", 2);
        requireOpcode("EXPR_MULTIPLY", 3);
        requireOpcode("EXPR_DIVIDE", 4);
        requireOpcode("DIVIDE", 4);
        requireOpcode("EXPR_MIN", 5);
        requireOpcode("EXPR_MAX", 6);
        requireOpcode("EXPR_ATAN2", 8);
        requireOpcode("EXPR_POW", 9);
        requireOpcode("EXPR_COPY_SIGN", 10);
        requireOpcode("FCOPY_SIGN", 7);
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
        int fcmplLess = nanCompareBuilder.compare("FCMPL_LT", nanValue, zeroConstant);
        int fcmplGreaterEqual = nanCompareBuilder.compare("FCMPL_GE", nanValue, zeroConstant);
        int fcmpgLess = nanCompareBuilder.compare("FCMPG_LT", nanValue, zeroConstant);
        int fcmpgGreaterEqual = nanCompareBuilder.compare("FCMPG_GE", nanValue, zeroConstant);
        requireClose(nanCompareBuilder.build(dcmplLess).evaluate(0.0, 0.0, 0.0), 1.0,
                "DCMPL NaN ordering for less-than");
        requireClose(nanCompareBuilder.build(dcmplGreaterEqual).evaluate(0.0, 0.0, 0.0), 0.0,
                "DCMPL NaN ordering for greater-or-equal");
        requireClose(nanCompareBuilder.build(dcmpgLess).evaluate(0.0, 0.0, 0.0), 0.0,
                "DCMPG NaN ordering for less-than");
        requireClose(nanCompareBuilder.build(dcmpgGreaterEqual).evaluate(0.0, 0.0, 0.0), 1.0,
                "DCMPG NaN ordering for greater-or-equal");
        requireClose(nanCompareBuilder.build(fcmplLess).evaluate(0.0, 0.0, 0.0), 1.0,
                "FCMPL NaN ordering for less-than");
        requireClose(nanCompareBuilder.build(fcmplGreaterEqual).evaluate(0.0, 0.0, 0.0), 0.0,
                "FCMPL NaN ordering for greater-or-equal");
        requireClose(nanCompareBuilder.build(fcmpgLess).evaluate(0.0, 0.0, 0.0), 0.0,
                "FCMPG NaN ordering for less-than");
        requireClose(nanCompareBuilder.build(fcmpgGreaterEqual).evaluate(0.0, 0.0, 0.0), 1.0,
                "FCMPG NaN ordering for greater-or-equal");

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
        if (coordinateOnly.unsupportedOperation(GpuDensityExpression.Backend.TERRAIN_GRID) != null) {
            throw new AssertionError("terrain-grid lowering should support coordinate IR nodes");
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

    /** Exercises primitive-index growth, collisions, exact tuple matching, and builder ownership. */
    private static void verifyWorldgenDependencyIndex() {
        final int count = 20_000;
        int[] xs = new int[count], ys = new int[count], zs = new int[count];
        Set<GpuWorldgenDependency.Coordinate> expected = new HashSet<>(count + 8);
        GpuWorldgenDependency.Builder builder = new GpuWorldgenDependency.Builder();
        for (int i = 0; i < count; i++) {
            int x = i * 7_919 - 83_000_000;
            int y = i % 769 - 384;
            int z = 71_003_003 - i * 104_729;
            xs[i] = x;
            ys[i] = y;
            zs[i] = z;
            expected.add(new GpuWorldgenDependency.Coordinate(x, y, z));
            builder.add(x, y, z).add(x, y, z);
        }
        builder.add(Integer.MIN_VALUE, Integer.MAX_VALUE, -1)
                .add(Integer.MAX_VALUE, Integer.MIN_VALUE, 1);
        expected.add(new GpuWorldgenDependency.Coordinate(Integer.MIN_VALUE, Integer.MAX_VALUE, -1));
        expected.add(new GpuWorldgenDependency.Coordinate(Integer.MAX_VALUE, Integer.MIN_VALUE, 1));

        GpuWorldgenDependency dependency = builder.build();
        if (dependency.samples() != expected.size() || dependency.pages() != 1)
            throw new AssertionError("primitive worldgen index lost or duplicated coordinates: samples="
                    + dependency.samples() + ", expected=" + expected.size());
        for (int i = 0; i < count; i++) {
            if (!dependency.contains(xs[i], ys[i], zs[i]))
                throw new AssertionError("primitive worldgen index missed tuple " + i);
        }
        if (!dependency.contains(Integer.MIN_VALUE, Integer.MAX_VALUE, -1)
                || !dependency.contains(Integer.MAX_VALUE, Integer.MIN_VALUE, 1)
                || dependency.contains(Integer.MIN_VALUE, Integer.MAX_VALUE, 1)
                || dependency.contains(17, 19, 23)) {
            throw new AssertionError("primitive worldgen index did not preserve exact three-axis membership");
        }
        try {
            builder.add(1, 2, 3);
            throw new AssertionError("built worldgen dependency builder accepted mutation");
        } catch (IllegalStateException expectedFailure) {
            // A built index is shared by the immutable dependency and must no longer be mutable.
        }
    }

    private static void verifyTransparentMarkerElimination() {
        DensityFunction source = DensityFunctions.constant(7.0);
        GpuDensityGraph transparent = new GpuDensityGraph(
                java.util.List.of(
                        new GpuDensityGraph.Node("Constant", new int[0], new double[]{7.0}, null),
                        new GpuDensityGraph.Node("Marker", new int[]{0}, new double[0], "HolderHolder"),
                        new GpuDensityGraph.Node("Marker", new int[]{1}, new double[0], "C2MECompiledDensityFunction"),
                        new GpuDensityGraph.Node("Marker", new int[]{2}, new double[0], "GpuDensityExpression")),
                new int[]{3}, java.util.List.of(), java.util.List.of(), java.util.List.of(),
                java.util.List.of(source, source, source, source));
        GpuDensityGraph lowered = GpuDensityGraphLowerer.lower(transparent);
        if (lowered.nodes().size() != 1 || lowered.routerRoots()[0] != 0
                || !lowered.nodes().get(0).opcode().equals("Constant")) {
            throw new AssertionError("transparent density markers should collapse to their shader child");
        }
        GpuDensityGraph interpolated = new GpuDensityGraph(
                java.util.List.of(
                        new GpuDensityGraph.Node("Constant", new int[0], new double[]{7.0}, null),
                        new GpuDensityGraph.Node("Marker", new int[]{0}, new double[0], "Interpolated")),
                new int[]{1}, java.util.List.of(), java.util.List.of(), java.util.List.of(),
                java.util.List.of(source, source));
        GpuDensityGraph interpolationLowered = GpuDensityGraphLowerer.lower(interpolated);
        if (interpolationLowered.nodes().size() != 2
                || interpolationLowered.interpolatedMarkerCount() != 1
                || interpolationLowered.interpolatedMarkerRegisters().length != 1
                || interpolationLowered.nodes().get(1).register() != interpolationLowered.nodes().get(0).register()
                || interpolationLowered.interpolatedMarkerRegisters()[0] != interpolationLowered.nodes().get(0).register()) {
            throw new AssertionError("exclusive interpolation markers should export their child's live register");
        }
        if (!interpolationLowered.routerRootsWithInterpolatedMarkers()[0]
                || lowered.routerRootsWithInterpolatedMarkers()[0]) {
            throw new AssertionError("block parity classification must follow interpolation markers through router roots");
        }
        GpuDensityGraph sharedMarkerValue = new GpuDensityGraph(
                java.util.List.of(
                        new GpuDensityGraph.Node("Constant", new int[0], new double[]{7.0}, null),
                        new GpuDensityGraph.Node("Marker", new int[]{0}, new double[0], "Interpolated")),
                new int[]{0, 1}, java.util.List.of(), java.util.List.of(), java.util.List.of(),
                java.util.List.of(source, source));
        GpuDensityGraph sharedLowered = GpuDensityGraphLowerer.lower(sharedMarkerValue);
        if (sharedLowered.nodes().get(0).register() == sharedLowered.nodes().get(1).register()) {
            throw new AssertionError("an interpolated marker must not alias a child also exported as a router root");
        }
        boolean[] sharedMarkerDependencies = sharedLowered.routerRootsWithInterpolatedMarkers();
        if (sharedMarkerDependencies[0] || !sharedMarkerDependencies[1]) {
            throw new AssertionError("shared router roots must retain independent interpolation-marker dependencies");
        }
    }

    private static void verifyConstantArithmeticFolding() {
        DensityFunction source = DensityFunctions.constant(0.0);
        GpuDensityGraph exactAddition = new GpuDensityGraph(
                java.util.List.of(
                        new GpuDensityGraph.Node("Constant", new int[0], new double[]{1.25}, null),
                        new GpuDensityGraph.Node("Constant", new int[0], new double[]{2.5}, null),
                        new GpuDensityGraph.Node("Ap2", new int[]{0, 1}, new double[0], "ADD")),
                new int[]{2}, java.util.List.of(), java.util.List.of(), java.util.List.of(),
                java.util.List.of(source, source, source));
        GpuDensityGraph folded = GpuDensityGraphLowerer.lower(exactAddition);
        if (folded.nodes().size() != 1 || !folded.nodes().get(0).opcode().equals("Constant")
                || folded.nodes().get(0).constants()[0] != 3.75) {
            throw new AssertionError("exact constant terrain addition should fold before shader dispatch");
        }

        GpuDensityGraph roundedAddition = new GpuDensityGraph(
                java.util.List.of(
                        new GpuDensityGraph.Node("Constant", new int[0], new double[]{0.1}, null),
                        new GpuDensityGraph.Node("Constant", new int[0], new double[]{100_000_000.0}, null),
                        new GpuDensityGraph.Node("Ap2", new int[]{0, 1}, new double[0], "ADD")),
                new int[]{2}, java.util.List.of(), java.util.List.of(), java.util.List.of(),
                java.util.List.of(source, source, source));
        GpuDensityGraph retained = GpuDensityGraphLowerer.lower(roundedAddition);
        int rootRegister = retained.routerRoots()[0];
        GpuDensityGraph.Node rootProducer = null;
        for (GpuDensityGraph.Node node : retained.nodes()) {
            if (node.register() == rootRegister) rootProducer = node;
        }
        if (rootProducer == null || !rootProducer.opcode().equals("Ap2")) {
            throw new AssertionError("constant addition must stay executable when folding changes float-ABI precision");
        }

        GpuDensityGraph constantClamp = new GpuDensityGraph(
                java.util.List.of(
                        new GpuDensityGraph.Node("Constant", new int[0], new double[]{2.0}, null),
                        new GpuDensityGraph.Node("Clamp", new int[]{0}, new double[]{-1.0, 1.0}, null)),
                new int[]{1}, java.util.List.of(), java.util.List.of(), java.util.List.of(),
                java.util.List.of(source, source));
        GpuDensityGraph clamped = GpuDensityGraphLowerer.lower(constantClamp);
        if (clamped.nodes().size() != 1 || !clamped.nodes().get(0).opcode().equals("Constant")
                || clamped.nodes().get(0).constants()[0] != 1.0) {
            throw new AssertionError("strict constant clamp bounds should fold without changing shader precision");
        }

        GpuDensityGraph foldedSelector = new GpuDensityGraph(
                java.util.List.of(
                        new GpuDensityGraph.Node("Constant", new int[0], new double[]{1.0}, null),
                        new GpuDensityGraph.Node("Constant", new int[0], new double[]{1.0}, null),
                        new GpuDensityGraph.Node("Ap2", new int[]{0, 1}, new double[0], "ADD"),
                        new GpuDensityGraph.Node("Constant", new int[0], new double[]{10.0}, null),
                        new GpuDensityGraph.Node("Constant", new int[0], new double[]{20.0}, null),
                        new GpuDensityGraph.Node("RangeChoice", new int[]{2, 3, 4}, new double[]{1.5, 2.5}, null)),
                new int[]{5}, java.util.List.of(), java.util.List.of(), java.util.List.of(),
                java.util.List.of(source, source, source, source, source, source));
        GpuDensityGraph selected = GpuDensityGraphLowerer.lower(foldedSelector);
        if (selected.nodes().size() != 1 || !selected.nodes().get(0).opcode().equals("Constant")
                || selected.nodes().get(0).constants()[0] != 10.0) {
            throw new AssertionError("folded constant conditions should prune selector branches before shader dispatch");
        }

        String[] mappings = {"ABS", "SQUARE", "CUBE", "HALF_NEGATIVE", "QUARTER_NEGATIVE", "FLOOR", "CEIL"};
        double[] expectedMappings = {3.25, 10.5625, -34.328125, -1.625, -0.8125, -4.0, -3.0};
        for (int mapping = 0; mapping < mappings.length; mapping++) {
            GpuDensityGraph constantMapping = new GpuDensityGraph(
                    java.util.List.of(
                            new GpuDensityGraph.Node("Constant", new int[0], new double[]{-3.25}, null),
                            new GpuDensityGraph.Node("Mapped", new int[]{0}, new double[0], mappings[mapping])),
                    new int[]{1}, java.util.List.of(), java.util.List.of(), java.util.List.of(),
                    java.util.List.of(source, source));
            GpuDensityGraph mapped = GpuDensityGraphLowerer.lower(constantMapping);
            if (mapped.nodes().size() != 1 || !mapped.nodes().get(0).opcode().equals("Constant")
                    || mapped.nodes().get(0).constants()[0] != expectedMappings[mapping]) {
                throw new AssertionError("exact constant " + mappings[mapping]
                        + " density mapping should fold before terrain dispatch");
            }
        }

        GpuDensityGraph zeroProduct = new GpuDensityGraph(
                java.util.List.of(
                        new GpuDensityGraph.Node("Constant", new int[0], new double[]{-0.0}, null),
                        new GpuDensityGraph.Node("GpuCoordinate", new int[0], new double[0], "X"),
                        new GpuDensityGraph.Node("Ap2", new int[]{0, 1}, new double[0], "MUL")),
                new int[]{2}, java.util.List.of(), java.util.List.of(), java.util.List.of(),
                java.util.List.of(source, source, source));
        GpuDensityGraph zeroFolded = GpuDensityGraphLowerer.lower(zeroProduct);
        if (zeroFolded.nodes().size() != 1 || !zeroFolded.nodes().get(0).opcode().equals("Constant")
                || Double.doubleToRawLongBits(zeroFolded.nodes().get(0).constants()[0]) != 0L) {
            throw new AssertionError("Ap2 multiplication by signed zero must fold to its defined positive zero");
        }

        GpuDensityGraph identityProduct = new GpuDensityGraph(
                java.util.List.of(
                        new GpuDensityGraph.Node("Constant", new int[0], new double[]{1.0}, null),
                        new GpuDensityGraph.Node("GpuCoordinate", new int[0], new double[0], "X"),
                        new GpuDensityGraph.Node("Ap2", new int[]{0, 1}, new double[0], "MUL")),
                new int[]{2}, java.util.List.of(), java.util.List.of(), java.util.List.of(),
                java.util.List.of(source, source, source));
        GpuDensityGraph identityLowered = GpuDensityGraphLowerer.lower(identityProduct);
        if (identityLowered.nodes().size() != 1
                || !identityLowered.nodes().get(0).opcode().equals("GpuCoordinate")) {
            throw new AssertionError("Ap2 multiplication with a left identity should reuse its child value");
        }

        GpuDensityGraph negativeZeroAdd = new GpuDensityGraph(
                java.util.List.of(
                        new GpuDensityGraph.Node("GpuCoordinate", new int[0], new double[0], "X"),
                        new GpuDensityGraph.Node("Constant", new int[0], new double[]{-0.0}, null),
                        new GpuDensityGraph.Node("Ap2", new int[]{0, 1}, new double[0], "ADD")),
                new int[]{2}, java.util.List.of(), java.util.List.of(), java.util.List.of(),
                java.util.List.of(source, source, source));
        GpuDensityGraph zeroAddLowered = GpuDensityGraphLowerer.lower(negativeZeroAdd);
        if (zeroAddLowered.nodes().size() != 1
                || !zeroAddLowered.nodes().get(0).opcode().equals("GpuCoordinate")) {
            throw new AssertionError("adding negative zero should preserve the other Ap2 input");
        }
    }

    private static void verifyConstantBranchPruning() {
        DensityFunction source = DensityFunctions.constant(0.0);
        GpuDensityGraph branches = new GpuDensityGraph(
                java.util.List.of(
                        new GpuDensityGraph.Node("Constant", new int[0], new double[]{1.0}, null),
                        new GpuDensityGraph.Node("Constant", new int[0], new double[]{10.0}, null),
                        new GpuDensityGraph.Node("GpuCoordinate", new int[0], new double[0], "X"),
                        new GpuDensityGraph.Node("RangeChoice", new int[]{0, 1, 2},
                                new double[]{0.5, 1.5}, null),
                        new GpuDensityGraph.Node("Constant", new int[0], new double[]{0.0}, null),
                        new GpuDensityGraph.Node("Constant", new int[0], new double[]{20.0}, null),
                        new GpuDensityGraph.Node("GpuSelect", new int[]{4, 1, 5}, new double[0], null),
                        new GpuDensityGraph.Node("GpuSelect", new int[]{2, 1, 1}, new double[0], null)),
                new int[]{3, 6, 7}, java.util.List.of(), java.util.List.of(), java.util.List.of(),
                java.util.List.of(source, source, source, source, source, source, source, source));
        GpuDensityGraph lowered = GpuDensityGraphLowerer.lower(branches);
        int[] roots = lowered.routerRoots();
        if (lowered.nodes().size() != 2 || roots[0] == roots[1] || roots[0] != roots[2]
                || !lowered.nodes().get(roots[0]).opcode().equals("Constant")
                || !lowered.nodes().get(roots[1]).opcode().equals("Constant")
                || lowered.nodes().get(roots[0]).constants()[0] != 10.0
                || lowered.nodes().get(roots[1]).constants()[0] != 20.0) {
            throw new AssertionError("constant range/select branches should prune unused shader subgraphs");
        }
        GpuDensityGraphBuffer.pack(lowered);

        GpuDensityGraph equivalentBranches = new GpuDensityGraph(
                java.util.List.of(
                        new GpuDensityGraph.Node("GpuCoordinate", new int[0], new double[0], "X"),
                        new GpuDensityGraph.Node("Constant", new int[0], new double[]{12.0}, null),
                        new GpuDensityGraph.Node("Constant", new int[0], new double[]{12.0}, null),
                        new GpuDensityGraph.Node("RangeChoice", new int[]{0, 1, 2}, new double[]{-1.0, 1.0}, null)),
                new int[]{3}, java.util.List.of(), java.util.List.of(), java.util.List.of(),
                java.util.List.of(source, source, source, source));
        GpuDensityGraph equivalentLowered = GpuDensityGraphLowerer.lower(equivalentBranches);
        if (equivalentLowered.nodes().size() != 1
                || !equivalentLowered.nodes().get(0).opcode().equals("Constant")
                || equivalentLowered.nodes().get(0).constants()[0] != 12.0) {
            throw new AssertionError("structurally identical range branches should eliminate selector and condition nodes");
        }

        GpuDensityGraph markerBranches = new GpuDensityGraph(
                java.util.List.of(
                        new GpuDensityGraph.Node("Constant", new int[0], new double[]{3.0}, null),
                        new GpuDensityGraph.Node("Marker", new int[]{0}, new double[0], "Interpolated"),
                        new GpuDensityGraph.Node("Marker", new int[]{0}, new double[0], "Interpolated"),
                        new GpuDensityGraph.Node("GpuCoordinate", new int[0], new double[0], "X"),
                        new GpuDensityGraph.Node("GpuSelect", new int[]{3, 1, 2}, new double[0], null)),
                new int[]{4}, java.util.List.of(), java.util.List.of(), java.util.List.of(),
                java.util.List.of(source, source, source, source, source));
        GpuDensityGraph markerLowered = GpuDensityGraphLowerer.lower(markerBranches);
        if (markerLowered.interpolatedMarkerCount() != 2
                || markerLowered.nodes().stream().noneMatch(node -> node.opcode().equals("GpuSelect"))) {
            throw new AssertionError("distinct interpolated marker inputs must not be merged by structural branch folding");
        }
    }

    private static void verifyTerrainDecisionFallback() {
        if (!GpuTerrainDensityParity.requiresJavaFallback(0.1, -0.1)
                || !GpuTerrainDensityParity.requiresJavaFallback(0.001, 0.1)
                || !GpuTerrainDensityParity.requiresJavaFallback(Double.NaN, Double.NaN)
                || GpuTerrainDensityParity.requiresJavaFallback(0.1, 0.1001)) {
            throw new AssertionError("terrain parity fallback did not isolate unsafe density decisions");
        }
    }

    private static void verifyParityRootPruning() {
        DensityFunction duplicateSource = DensityFunctions.constant(1.0);
        GpuDensityGraph duplicateGraph = new GpuDensityGraph(
                java.util.List.of(
                        new GpuDensityGraph.Node("Constant", new int[0], new double[]{2.0}, null),
                        new GpuDensityGraph.Node("Constant", new int[0], new double[]{3.0}, null),
                        new GpuDensityGraph.Node("GpuMath", new int[]{0, 1}, new double[0], "MULTIPLY"),
                        new GpuDensityGraph.Node("GpuMath", new int[]{0, 1}, new double[0], "MULTIPLY")),
                new int[]{2, 3}, java.util.List.of(), java.util.List.of(), java.util.List.of(),
                java.util.List.of(duplicateSource, duplicateSource, duplicateSource, duplicateSource),
                4, 0b11, new String[2]);
        GpuDensityGraph deduplicated = GpuDensityGraphLowerer.lower(duplicateGraph);
        if (deduplicated.nodes().size() != 3 || deduplicated.routerRoots()[0] != deduplicated.routerRoots()[1]) {
            throw new AssertionError("identical pure terrain subgraphs should share lowered GPU instructions");
        }
        GpuDensityGraphBuffer.pack(deduplicated);

        GpuDensityGraph markerGraph = interpolatedMarkerGraphForVulkanSmoke();
        GpuDensityGraphBuffer.Packed markerPacked = GpuDensityGraphBuffer.pack(markerGraph);
        int markerChildFlags = markerPacked.nodes().getInt(36);
        if ((markerChildFlags & GpuDensityGraphBuffer.BLOCK_SKIP_FLAG) == 0) {
            throw new AssertionError("block pass should skip a node used only by an interpolated marker");
        }
        boolean sharedMarkerChildKept = false;
        for (int node = 0; node < markerPacked.nodeCount(); node++) {
            int offset = node * GpuDensityGraphBuffer.NODE_BYTES;
            if (markerPacked.nodes().getInt(offset) == 1
                    && markerPacked.nodes().getFloat(offset + 16) == 8.0f) {
                sharedMarkerChildKept = (markerPacked.nodes().getInt(offset + 36)
                        & GpuDensityGraphBuffer.BLOCK_SKIP_FLAG) == 0;
            }
        }
        if (!sharedMarkerChildKept) {
            throw new AssertionError("block pass must retain a marker child also used by another router root");
        }

        DensityFunction first = DensityFunctions.constant(1.0);
        DensityFunction second = DensityFunctions.constant(2.0);
        GpuDensityGraph source = new GpuDensityGraph(
                java.util.List.of(
                        new GpuDensityGraph.Node("Constant", new int[0], new double[]{1.0}, null),
                        new GpuDensityGraph.Node("Constant", new int[0], new double[]{2.0}, null)),
                new int[]{0, 1}, java.util.List.of(), java.util.List.of(), java.util.List.of(),
                java.util.List.of(first, second), 2, 0b11, new String[2]);
        GpuDensityGraph filtered = GpuDensityGraphLowerer.lowerVerifiedRoots(source, 0b01);
        if (filtered.nodes().size() != 1 || filtered.supportedRouterRootMask() != 0b01
                || !filtered.supportsRouterRoot(0) || filtered.supportsRouterRoot(1)) {
            throw new AssertionError("parity root pruning must remove only the failed root's graph");
        }
        GpuDensityGraphBuffer.pack(filtered);
    }

    static GpuDensityGraph interpolatedMarkerGraphForVulkanSmoke() {
        DensityFunction interpolationSource = DensityFunctions.constant(2.0);
        DensityFunction interpolationMarker = DensityFunctions.constant(2.0);
        DensityFunction sharedSource = DensityFunctions.constant(8.0);
        DensityFunction sharedMarker = DensityFunctions.constant(8.0);
        DensityFunction otherInput = DensityFunctions.constant(3.0);
        DensityFunction combined = DensityFunctions.constant(5.0);
        DensityFunction zero = DensityFunctions.constant(0.0);
        DensityFunction sharedCombined = DensityFunctions.constant(8.0);
        int[] roots = new int[GpuTerrainRoots.COUNT];
        java.util.Arrays.fill(roots, 5);
        roots[1] = 7;
        roots[2] = 2;
        roots[3] = 8;
        return GpuDensityGraphLowerer.lower(new GpuDensityGraph(
                java.util.List.of(
                        new GpuDensityGraph.Node("GpuCoordinate", new int[0], new double[0], "X"),
                        new GpuDensityGraph.Node("Marker", new int[]{0}, new double[0], "Interpolated"),
                        new GpuDensityGraph.Node("Constant", new int[0], new double[]{8.0}, null),
                        new GpuDensityGraph.Node("Marker", new int[]{2}, new double[0], "Interpolated"),
                        new GpuDensityGraph.Node("Constant", new int[0], new double[]{3.0}, null),
                        new GpuDensityGraph.Node("GpuMath", new int[]{1, 1}, new double[0], "EXPR_MULTIPLY"),
                        new GpuDensityGraph.Node("Constant", new int[0], new double[]{0.0}, null),
                        new GpuDensityGraph.Node("GpuMath", new int[]{3, 6}, new double[0], "ADD"),
                        new GpuDensityGraph.Node("GpuMath", new int[]{1, 4}, new double[0], "ADD")),
                roots, java.util.List.of(), java.util.List.of(), java.util.List.of(),
                java.util.List.of(interpolationSource, interpolationMarker, sharedSource, sharedMarker,
                        otherInput, combined, zero, sharedCombined)));
    }

    private static void verifyBytecodeFrontend() {
        verifyStandaloneMath(new FloatMathDensity(), "float Math.min/max");
        verifyStandaloneMath(new FloatMinMaxEdgeDensity(), "float Math.min/max NaN and signed zero");
        NormalNoise noise = NormalNoise.create(RandomSource.create(811L), -2, 1.0, 0.5);
        DensityFunction accessorDensity = new DensityAccessorFunction(
                new ConstantReferenceDensity(0.125));
        GpuDensityBytecodeCompiler.Result accessorResult =
                GpuDensityBytecodeCompiler.compile(accessorDensity);
        if (accessorResult.expression() == null) {
            throw new AssertionError("density-returning helper accessors should inline generically: "
                    + accessorResult.rejection());
        }
        requireClose(accessorResult.expression().evaluate(4, 8, 12),
                accessorDensity.compute(new DensityFunction.SinglePointContext(4, 8, 12)),
                1.0e-7, "density-returning helper accessor");
        GpuDensityGraphBuffer.pack(GpuDensityGraphLowerer.lower(
                GpuDensityGraphCompiler.compileDensityFunction(accessorDensity, true)));

        DensityFunction transformedNoise = new TransformedNoiseDensity(noise,
                new ConstantReferenceDensity(0.03125), new ConstantReferenceDensity(-0.0625));
        GpuDensityBytecodeCompiler.Result transformedNoiseResult =
                GpuDensityBytecodeCompiler.compile(transformedNoise);
        if (transformedNoiseResult.expression() == null) {
            throw new AssertionError("transformed NormalNoise coordinates should compile generically: "
                    + transformedNoiseResult.rejection());
        }
        if (transformedNoiseResult.expression().nodes().stream()
                .noneMatch(node -> "GpuNoise3".equals(node.opcode()))) {
            throw new AssertionError("transformed noise coordinates must remain explicit IR inputs");
        }

        DensityFunction noiseHolderDensity = new NoiseHolderTransformedNoiseDensity(
                new DensityFunction.NoiseHolder(null, noise),
                new ConstantReferenceDensity(0.03125), new ConstantReferenceDensity(-0.0625));
        GpuDensityBytecodeCompiler.Result noiseHolderResult =
                GpuDensityBytecodeCompiler.compile(noiseHolderDensity);
        if (noiseHolderResult.expression() == null) {
            throw new AssertionError("DensityFunction.NoiseHolder.getValue should lower through generic IR: "
                    + noiseHolderResult.rejection());
        }
        for (int[] point : new int[][]{{0, 0, 0}, {17, -9, 31}, {-128, 72, 256}}) {
            DensityFunction.SinglePointContext context =
                    new DensityFunction.SinglePointContext(point[0], point[1], point[2]);
            requireFloatAbiClose(noiseHolderResult.expression().evaluate(point[0], point[1], point[2]),
                    noiseHolderDensity.compute(context), "NoiseHolder transformed noise");
        }
        GpuDensityGraphBuffer.pack(GpuDensityGraphLowerer.lower(
                GpuDensityGraphCompiler.compileDensityFunction(noiseHolderDensity, true)));
        for (int[] point : new int[][]{{-193, -64, -117}, {-1, 0, 1}, {37, 91, -248}}) {
            requireClose(transformedNoiseResult.expression().evaluate(point[0], point[1], point[2]),
                    transformedNoise.compute(new DensityFunction.SinglePointContext(point[0], point[1], point[2])),
                    1.0e-7, "generic transformed noise at x=" + point[0]);
        }
        GpuDensityGraphBuffer.pack(GpuDensityGraphLowerer.lower(
                GpuDensityGraphCompiler.compileDensityFunction(transformedNoise, true)));

        BytecodeDensity density = new BytecodeDensity(noise, new ConstantReferenceDensity(0.125),
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
        boolean hasDependencyMarker = false;
        boolean hasExpressionMath = false;
        for (GpuDensityGraph.Node node : compiledGraph.nodes()) {
            hasDependencyMarker |= "GpuDensityBytecodeDependency".equals(node.payload());
            hasExpressionMath |= "GpuMath".equals(node.opcode()) || "Ap2".equals(node.opcode());
        }
        if (!hasDependencyMarker || !hasExpressionMath) {
            throw new AssertionError("custom bytecode graph did not retain its nested dependency and math IR");
        }
        GpuDensityGraph loweredGraph = GpuDensityGraphLowerer.lower(compiledGraph);
        if (loweredGraph.routerRootCount() != 1 || loweredGraph.registerCount() <= 0) {
            throw new AssertionError("custom density did not survive graph lowering as one GPU root");
        }
        // Graph lowering deliberately erases transparent delegation markers;
        // the dependency and math IR were asserted on the pre-lowered graph.
        GpuDensityGraphBuffer.Packed packed = GpuDensityGraphBuffer.pack(loweredGraph);
        if (packed.nodeCount() != loweredGraph.nodes().size() || packed.nodes().remaining()
                != (packed.nodeCount() + packed.blockNodeCount()) * GpuDensityGraphBuffer.NODE_BYTES) {
            throw new AssertionError("lowered custom density graph did not produce a complete GPU node buffer");
        }

        FinalFieldConfigurationDensity objectConfiguration = new FinalFieldConfigurationDensity();
        GpuDensityBytecodeCompiler.Result objectConfigured =
                GpuDensityBytecodeCompiler.compile(objectConfiguration);
        if (objectConfigured.expression() == null) {
            throw new AssertionError("final-field helper object bytecode should compile: "
                    + objectConfigured.rejection());
        }
        int[][] helperPoints = {{-193, -64, -117}, {-1, 0, 1}, {37, 91, -248}, {256, 319, 128}};
        for (int[] point : helperPoints) {
            DensityFunction.SinglePointContext context = new DensityFunction.SinglePointContext(
                    point[0], point[1], point[2]);
            requireClose(objectConfigured.expression().evaluate(point[0], point[1], point[2]),
                    objectConfiguration.compute(context), 1.0e-7,
                    "final-field helper object at x=" + point[0]);
        }
        GpuDensityGraphBuffer.pack(GpuDensityGraphLowerer.lower(
                GpuDensityGraphCompiler.compileDensityFunction(objectConfiguration, true)));
        mixedTerrainRootFixture();

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

        BoundedConditionalLoopDensity conditionalLoop = new BoundedConditionalLoopDensity();
        GpuDensityBytecodeCompiler.Result conditionalLoopResult =
                GpuDensityBytecodeCompiler.compile(conditionalLoop);
        if (conditionalLoopResult.expression() == null) {
            throw new AssertionError("bounded loop with dynamic terrain branches should compile: "
                    + conditionalLoopResult.rejection());
        }
        for (int xValue : new int[]{-17, -3, -1, 0, 1, 3, 17}) {
            double expected = conditionalLoop.compute(new DensityFunction.SinglePointContext(xValue, 0, 0));
            requireClose(conditionalLoopResult.expression().evaluate(xValue, 0, 0), expected, 1.0e-7,
                    "dynamic branch inside bounded loop at x=" + xValue);
        }
        GpuDensityGraphBuffer.pack(boundedConditionalLoopFixture().graph());

        BoundedSwitchLoopDensity switchLoop = new BoundedSwitchLoopDensity();
        GpuDensityBytecodeCompiler.Result switchLoopResult = GpuDensityBytecodeCompiler.compile(switchLoop);
        if (switchLoopResult.expression() == null) {
            throw new AssertionError("bounded loop with a dynamic switch should compile: "
                    + switchLoopResult.rejection());
        }
        for (int xValue : new int[]{-7, -3, -1, 0, 1, 2, 8}) {
            double expected = switchLoop.compute(new DensityFunction.SinglePointContext(xValue, 0, 0));
            requireClose(switchLoopResult.expression().evaluate(xValue, 0, 0), expected, 1.0e-7,
                    "dynamic switch inside bounded loop at x=" + xValue);
        }
        GpuDensityGraphBuffer.pack(boundedSwitchLoopFixture().graph());

        verifyMinecraftLerpBytecode();

        ScalarMathDensity scalarMath = new ScalarMathDensity();
        GpuDensityBytecodeCompiler.Result scalarMathResult = GpuDensityBytecodeCompiler.compile(scalarMath);
        if (scalarMathResult.expression() == null) {
            throw new AssertionError("common scalar Math bytecode should compile: " + scalarMathResult.rejection());
        }
        for (int[] point : new int[][]{{-81, -17, -5}, {-1, 0, 1}, {17, 9, 31}}) {
            DensityFunction.SinglePointContext context = new DensityFunction.SinglePointContext(
                    point[0], point[1], point[2]);
            requireFloatAbiClose(scalarMathResult.expression().evaluate(point[0], point[1], point[2]),
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

        verifyStandaloneMath(new DoubleMinMaxDensity(), "double Math.min/max");
        verifyStandaloneMath(new MinMaxEdgeDensity(), "Math.min/max NaN and signed zero");
        verifyStandaloneMath(new FloatMathDensity(), "float Math overloads and Mth.square");
        verifyStandaloneMath(new TranscendentalMathDensity(), "transcendental Math");
        verifyStandaloneMath(new PowerMathDensity(), "pow");
        verifyStandaloneMath(new Atan2MathDensity(), "atan2");
        verifyStandaloneMath(new Atan2SignedZeroDensity(), "atan2 signed zero");
        verifyStandaloneMath(new SignumMathDensity(), "signum");
        verifyStandaloneMath(new MapMathDensity(), "Mth mapping functions");
        verifyStandaloneMath(new InterpolationMathDensity(), "Mth interpolation functions");
        verifyStandaloneMath(new FloatInterpolationMathDensity(), "Mth float interpolation functions");
        verifyStandaloneMath(new ModuloAndAngleMathDensity(), "Mth modulo and angle functions");
        verifyStandaloneMath(new TriangleWaveMathDensity(), "Mth triangle wave");
        verifyStandaloneMath(new AngleMotionMathDensity(), "Mth angle motion functions");
        verifyStandaloneMath(new VectorLengthMathDensity(), "Mth vector length functions");
        verifyStandaloneMath(new IntegerInterpolationMathDensity(), "Mth integer interpolation functions");
        verifyStandaloneMath(new ExternalStaticHelperDensity(), "external static terrain helpers");
        verifyStandaloneMath(new ConstantArrayHelperDensity(), "constant-index terrain arrays");
        verifyStandaloneMath(new InheritedComputeDensity(), "inherited terrain compute implementation");
        verifyStandaloneMath(new DefaultInterfaceDensity(), "default interface terrain compute implementation");
        verifyStandaloneMath(new RecordConfigurationDensity(), "immutable terrain record configuration");
        verifyStandaloneMath(new EnumSwitchConfigurationDensity(), "enum-switch terrain configuration");
        verifyStandaloneMath(new StringSwitchConfigurationDensity(), "string-switch terrain configuration");
        verifyStandaloneMath(new StringComparisonConfigurationDensity(), "case-insensitive string terrain configuration");
        verifyStandaloneMath(new ObjectsEqualsConfigurationDensity(), "immutable Objects.equals terrain configuration");
        GpuDensityBytecodeCompiler.Result customObjectsEquals = GpuDensityBytecodeCompiler.compile(
                new CustomObjectsEqualsDensity());
        if (customObjectsEquals.expression() != null || customObjectsEquals.rejection() == null
                || !customObjectsEquals.rejection().contains("opaque method call java.util.Objects.equals")) {
            throw new AssertionError("custom object equality must remain on Java with a method diagnostic: "
                    + customObjectsEquals.rejection());
        }
        verifyStandaloneMath(new EnumEqualityConfigurationDensity(), "enum-equality terrain configuration");
        verifyStandaloneMath(new HolderBackedEnumConfigurationDensity(), "holder-backed enum terrain configuration");
        verifyStandaloneMath(new HolderTypeGuardConfigurationDensity(), "holder-backed typed terrain configuration");
        GpuDensityBytecodeCompiler.Result mutableEnum = GpuDensityBytecodeCompiler.compile(
                new MutableEnumConfigurationDensity());
        if (mutableEnum.expression() != null || mutableEnum.rejection() == null
                || !mutableEnum.rejection().contains("mutable field is not supported: mode")) {
            throw new AssertionError("mutable enum configuration must stay on Java with a field diagnostic: "
                    + mutableEnum.rejection());
        }
        verifyStandaloneMath(new HolderBackedConfigurationDensity(), "bound registry-holder terrain configuration");
        verifyStandaloneMath(new MinecraftMathOverloadsDensity(), "Mth float and integer overloads");
        verifyStandaloneMath(new DynamicClampDensity(), "dynamic Mth.clamp overloads");

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
            requireFloatAbiClose(staticConstantResult.expression().evaluate(xValue, 0, 0), expected,
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
            requireFloatAbiClose(bitwiseResult.expression().evaluate(xValue, 0, 0), expected,
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
        GpuDensityBytecodeCompiler.Result dynamicDivision =
                GpuDensityBytecodeCompiler.compile(new DynamicIntegerDivisionDensity());
        if (dynamicDivision.expression() != null) {
            throw new AssertionError("integer division by a dynamic divisor must remain on the Java path");
        }
        if (!dynamicDivision.rejection().contains("DynamicIntegerDivisionDensity.compute")
                || !dynamicDivision.rejection().contains("bytecode instruction")) {
            throw new AssertionError("bytecode fallback diagnostics must identify the source method and instruction: "
                    + dynamicDivision.rejection());
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

    private static final class ConstantReferenceDensity implements DensityFunction.SimpleFunction {
        private final double value;

        private ConstantReferenceDensity(double value) { this.value = value; }
        @Override public double compute(FunctionContext context) { return value; }
        @Override public DensityFunction mapAll(Visitor visitor) { return visitor.apply(this); }
        @Override public double minValue() { return value; }
        @Override public double maxValue() { return value; }
        @Override public net.minecraft.util.KeyDispatchDataCodec<? extends DensityFunction> codec() { return null; }
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

    private static final class BoundedConditionalLoopDensity implements DensityFunction.SimpleFunction {
        @Override public double compute(FunctionContext context) {
            double sum = 0.0;
            for (int octave = 0; octave < 3; octave++) {
                if (context.blockX() + octave < 0) {
                    sum += context.blockX() * (0.125 + octave * 0.01);
                } else {
                    sum -= octave * 0.25;
                }
            }
            return sum;
        }
        @Override public DensityFunction mapAll(Visitor visitor) { return visitor.apply(this); }
        @Override public double minValue() { return -100_000.0; }
        @Override public double maxValue() { return 100_000.0; }
        @Override public net.minecraft.util.KeyDispatchDataCodec<? extends DensityFunction> codec() {
            return DensityFunctions.constant(0.0).codec();
        }
    }

    private static final class BoundedSwitchLoopDensity implements DensityFunction.SimpleFunction {
        @Override public double compute(FunctionContext context) {
            double sum = 0.0;
            for (int step = 0; step < 2; step++) {
                switch ((context.blockX() + step) % 3) {
                    case -1 -> sum += step * 0.5 + 0.25;
                    case 0 -> sum += context.blockX() * 0.125;
                    default -> sum -= step * 0.25;
                }
            }
            return sum;
        }
        @Override public DensityFunction mapAll(Visitor visitor) { return visitor.apply(this); }
        @Override public double minValue() { return -100_000.0; }
        @Override public double maxValue() { return 100_000.0; }
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

    private static final class TransformedNoiseDensity implements DensityFunction.SimpleFunction {
        private final NormalNoise noise;
        private final DensityFunction shiftX;
        private final DensityFunction shiftZ;

        private TransformedNoiseDensity(NormalNoise noise, DensityFunction shiftX, DensityFunction shiftZ) {
            this.noise = noise;
            this.shiftX = shiftX;
            this.shiftZ = shiftZ;
        }

        @Override
        public double compute(FunctionContext context) {
            double x = (context.blockX() + shiftX.compute(context)) * 0.03125;
            double z = (context.blockZ() + shiftZ.compute(context)) * 0.03125;
            return noise.getValue(x, 0.0, z) * 1.7 + 0.4;
        }

        @Override public DensityFunction mapAll(Visitor visitor) { return visitor.apply(this); }
        @Override public double minValue() { return -2.0; }
        @Override public double maxValue() { return 2.0; }
        @Override public net.minecraft.util.KeyDispatchDataCodec<? extends DensityFunction> codec() {
            return DensityFunctions.constant(0.0).codec();
        }
    }

    /** Mirrors the wrapper call shape used by Tectonic's configurable noise density. */
    private static final class NoiseHolderTransformedNoiseDensity implements DensityFunction.SimpleFunction {
        private final DensityFunction.NoiseHolder noise;
        private final DensityFunction shiftX;
        private final DensityFunction shiftZ;

        private NoiseHolderTransformedNoiseDensity(DensityFunction.NoiseHolder noise,
                                                   DensityFunction shiftX, DensityFunction shiftZ) {
            this.noise = noise;
            this.shiftX = shiftX;
            this.shiftZ = shiftZ;
        }

        @Override
        public double compute(FunctionContext context) {
            double x = (context.blockX() + shiftX.compute(context)) * 0.03125;
            double z = (context.blockZ() + shiftZ.compute(context)) * 0.03125;
            return noise.getValue(x, 0.0, z) * 1.7 + 0.4;
        }

        @Override public DensityFunction mapAll(Visitor visitor) { return visitor.apply(this); }
        @Override public double minValue() { return -2.0; }
        @Override public double maxValue() { return 2.0; }
        @Override public net.minecraft.util.KeyDispatchDataCodec<? extends DensityFunction> codec() {
            return DensityFunctions.constant(0.0).codec();
        }
    }

    private static final class DirectNormalNoiseDensity implements DensityFunction.SimpleFunction {
        private final NormalNoise noise;

        private DirectNormalNoiseDensity(NormalNoise noise) { this.noise = noise; }

        @Override
        public double compute(FunctionContext context) {
            return noise.getValue(context.blockX(), context.blockY(), context.blockZ());
        }

        @Override public DensityFunction mapAll(Visitor visitor) { return visitor.apply(this); }
        @Override public double minValue() { return -2.0; }
        @Override public double maxValue() { return 2.0; }
        @Override public net.minecraft.util.KeyDispatchDataCodec<? extends DensityFunction> codec() {
            return DensityFunctions.constant(0.0).codec();
        }
    }

    private static final class DensityAccessorFunction implements DensityFunction.SimpleFunction {
        private final DensityFunction input;

        private DensityAccessorFunction(DensityFunction input) { this.input = input; }

        private DensityFunction input() { return input; }

        @Override public double compute(FunctionContext context) { return input().compute(context) * 2.5; }
        @Override public DensityFunction mapAll(Visitor visitor) { return visitor.apply(this); }
        @Override public double minValue() { return -10.0; }
        @Override public double maxValue() { return 10.0; }
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
        requireClose(actual, expected, 1.0e-12, label);
    }

    private static void requireFloatAbiClose(double actual, double expected, String label) {
        double tolerance = Math.max(FLOAT_ABI_ABSOLUTE_EPSILON,
                Math.abs(expected) * FLOAT_ABI_RELATIVE_EPSILON);
        requireClose(actual, expected, tolerance, label);
    }

    private static void verifyStandaloneMath(DensityFunction function, String label) {
        GpuDensityBytecodeCompiler.Result result = GpuDensityBytecodeCompiler.compile(function);
        if (result.expression() == null) {
            throw new AssertionError(label + " should compile: " + result.rejection());
        }
        for (int[] point : new int[][]{{-81, -17, -5}, {-1, 0, 1}, {0, 0, 0}, {17, 9, 31}}) {
            double expected = function.compute(new DensityFunction.SinglePointContext(
                    point[0], point[1], point[2]));
            requireFloatAbiClose(result.expression().evaluate(point[0], point[1], point[2]), expected,
                    label + " CPU IR at " + java.util.Arrays.toString(point));
        }
        GpuDensityGraphBuffer.pack(GpuDensityGraphLowerer.lower(
                GpuDensityGraphCompiler.compileDensityFunction(function, true)));
    }

    private static void requireClose(double actual, double expected, double epsilon, String label) {
        if (Math.abs(actual - expected) > epsilon) {
            throw new AssertionError(label + " expected " + expected + ", got " + actual);
        }
    }

    private static void verifyMinecraftLerpBytecode() {
        DensityFunction[] functions = {new Lerp2Density(), new Lerp3Density()};
        String[] labels = {"Mth.lerp2", "Mth.lerp3"};
        int[][] points = {{-81, -17, -5}, {-1, 0, 1}, {17, 9, 31}, {127, -32, -64}};
        for (int index = 0; index < functions.length; index++) {
            GpuDensityBytecodeCompiler.Result result = GpuDensityBytecodeCompiler.compile(functions[index]);
            if (result.expression() == null) {
                throw new AssertionError(labels[index] + " bytecode should compile: " + result.rejection());
            }
            for (int[] point : points) {
                double expected = functions[index].compute(
                        new DensityFunction.SinglePointContext(point[0], point[1], point[2]));
                requireClose(result.expression().evaluate(point[0], point[1], point[2]), expected,
                        labels[index] + " at " + java.util.Arrays.toString(point));
            }
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

    private abstract static class NumericSmokeDensity implements DensityFunction.SimpleFunction {
        @Override public DensityFunction mapAll(Visitor visitor) { return visitor.apply(this); }
        @Override public double minValue() { return -100.0; }
        @Override public double maxValue() { return 100.0; }
        @Override public net.minecraft.util.KeyDispatchDataCodec<? extends DensityFunction> codec() {
            return DensityFunctions.constant(0.0).codec();
        }
    }

    private static final class DoubleMinMaxDensity extends NumericSmokeDensity {
        @Override public double compute(FunctionContext context) {
            double x = context.blockX(), z = context.blockZ();
            return Math.min(x * 0.02, z * 0.01) + Math.max(x * -0.015, z * 0.025);
        }
    }

    private static final class MinMaxEdgeDensity extends NumericSmokeDensity {
        @Override public double compute(FunctionContext context) {
            double xZero = context.blockX() * -0.0;
            double yZero = context.blockY() * -0.0;
            double nan = (double) context.blockX() / (double) context.blockY();
            return switch (context.blockZ()) {
                case 0 -> Math.min(xZero, yZero);
                case 1 -> Math.max(xZero, yZero);
                case 2 -> Math.min(nan, xZero);
                case 3 -> Math.max(yZero, nan);
                default -> Math.min(xZero, yZero);
            };
        }
    }

    private static final class FloatMathDensity extends NumericSmokeDensity {
        @Override public double compute(FunctionContext context) {
            float x = context.blockX(), z = context.blockZ();
            return Math.min(x * 0.125F, Math.max(z * -0.25F, -2.5F)) * 0.1
                    + Math.abs(z * -0.03125F) * 0.05
                    + net.minecraft.util.Mth.square(x * 0.0078125F) * 0.125;
        }
    }

    private static final class FloatMinMaxEdgeDensity extends NumericSmokeDensity {
        @Override public double compute(FunctionContext context) {
            float xZero = context.blockX() * -0.0F;
            float yZero = context.blockY() * -0.0F;
            float nan = (float) context.blockX() / (float) context.blockY();
            return switch (context.blockZ()) {
                case 0 -> Math.min(xZero, yZero);
                case 1 -> Math.max(xZero, yZero);
                case 2 -> Math.min(nan, xZero);
                case 3 -> Math.max(yZero, nan);
                default -> Math.min(xZero, yZero);
            };
        }
    }

    private static final class DynamicClampDensity extends NumericSmokeDensity {
        @Override public double compute(FunctionContext context) {
            double x = context.blockX(), y = context.blockY(), z = context.blockZ();
            float fx = (float) x, fy = (float) y, fz = (float) z;
            return net.minecraft.util.Mth.clamp(x * 0.02, y * 0.01, z * 0.03) * 0.01
                    + net.minecraft.util.Mth.clamp(fx * 0.01F, fy * 0.02F, fz * 0.03F) * 0.01;
        }
    }

    private static final class TranscendentalMathDensity extends NumericSmokeDensity {
        @Override public double compute(FunctionContext context) {
            double x = context.blockX(), y = context.blockY(), z = context.blockZ();
            return Math.floor(y * 0.25) + Math.ceil(x * 0.1)
                    + Math.sqrt(Math.abs(x)) * 0.01 + Math.sin(x * 0.05)
                    + Math.cos(z * 0.05) + Math.log(Math.exp(0.25)) * 0.1
                    + Math.tanh(x * 0.01) * 0.1;
        }
    }

    private static final class PowerMathDensity extends NumericSmokeDensity {
        @Override public double compute(FunctionContext context) {
            double x = context.blockX(), z = context.blockZ();
            return Math.pow(0.5 + Math.abs(x) * 0.01 + Math.abs(z) * 0.005, 1.7) * 0.1;
        }
    }

    private static final class Atan2MathDensity extends NumericSmokeDensity {
        @Override public double compute(FunctionContext context) {
            return Math.atan2(context.blockZ(), context.blockX()) * 0.05;
        }
    }

    private static final class Atan2SignedZeroDensity extends NumericSmokeDensity {
        @Override public double compute(FunctionContext context) {
            double y = context.blockX() * -0.0;
            double x = context.blockZ() * -0.0;
            return Math.atan2(y, x);
        }
    }

    private static final class SignumMathDensity extends NumericSmokeDensity {
        @Override public double compute(FunctionContext context) {
            return Math.signum(context.blockX()) * 0.01;
        }
    }

    private static final class CopySignMathDensity extends NumericSmokeDensity {
        @Override public double compute(FunctionContext context) {
            double magnitude = Math.sqrt(context.blockX() * -1.0);
            double sign = context.blockY() * -0.0;
            return Math.copySign(magnitude, sign);
        }
    }

    private static final class FloatCopySignMathDensity extends NumericSmokeDensity {
        @Override public double compute(FunctionContext context) {
            float x = context.blockX(), y = context.blockY();
            float magnitude = x / y;
            float sign = y * -0.0F;
            return Math.copySign(magnitude, sign);
        }
    }

    private static final class MapMathDensity extends NumericSmokeDensity {
        @Override public double compute(FunctionContext context) {
            double x = context.blockX(), y = context.blockY(), z = context.blockZ();
            return net.minecraft.util.Mth.map(x, -128.0, 128.0, -1.0, 1.0)
                    + net.minecraft.util.Mth.clampedMap(y, -32.0, 32.0, -1.0, 1.0)
                    + net.minecraft.util.Mth.inverseLerp(z, -64.0, 64.0)
                    + net.minecraft.util.Mth.absMax(x, z) * 0.001
                    + net.minecraft.util.Mth.smoothstep(x / 256.0);
        }
    }

    private static final class InterpolationMathDensity extends NumericSmokeDensity {
        @Override public double compute(FunctionContext context) {
            double x = context.blockX(), y = context.blockY(), z = context.blockZ();
            return net.minecraft.util.Mth.clampedLerp(-2.0, 3.0, x / 32.0) * 0.01
                    + net.minecraft.util.Mth.lerp2(x / 64.0, y / 32.0,
                            -3.0, 1.0, 2.0, -1.0) * 0.02
                    + net.minecraft.util.Mth.lerp3(x / 64.0, y / 32.0, z / 64.0,
                            -2.0, 1.0, 0.5, 3.0, 4.0, -1.5, 2.5, -3.0) * 0.01;
        }
    }

    private static final class FloatInterpolationMathDensity extends NumericSmokeDensity {
        @Override public double compute(FunctionContext context) {
            float x = context.blockX() * 0.01F;
            float y = context.blockY() * 0.02F;
            return net.minecraft.util.Mth.lerp(x, -3.0F, 4.0F) * 0.1F
                    + net.minecraft.util.Mth.clampedLerp(-2.0F, 5.0F, y) * 0.1F
                    + net.minecraft.util.Mth.inverseLerp(x, -1.0F, 1.0F) * 0.1F
                    + net.minecraft.util.Mth.map(x, -1.0F, 1.0F, -2.0F, 4.0F) * 0.1F
                    + net.minecraft.util.Mth.clampedMap(y, -1.0F, 1.0F, -2.0F, 4.0F) * 0.1F;
        }
    }

    private static final class ModuloAndAngleMathDensity extends NumericSmokeDensity {
        @Override public double compute(FunctionContext context) {
            float x = context.blockX() * 0.25F;
            double z = context.blockZ() * 0.125;
            return net.minecraft.util.Mth.frac(x) * 0.1F
                    + net.minecraft.util.Mth.positiveModulo(x, 17.0F) * 0.001F
                    + net.minecraft.util.Mth.positiveModulo(z, 31.0) * 0.001
                    + net.minecraft.util.Mth.wrapDegrees(x) * 0.0001F
                    + net.minecraft.util.Mth.wrapDegrees(z) * 0.0001;
        }
    }

    private static final class TriangleWaveMathDensity extends NumericSmokeDensity {
        @Override public double compute(FunctionContext context) {
            float x = context.blockX() * 0.25F;
            float period = 5.25F + Math.abs(context.blockY() * 0.125F);
            return net.minecraft.util.Mth.triangleWave(x, period) * 0.25F
                    + net.minecraft.util.Mth.triangleWave(context.blockZ() * -0.5F, 8.0F) * 0.125F;
        }
    }

    private static final class AngleMotionMathDensity extends NumericSmokeDensity {
        @Override public double compute(FunctionContext context) {
            float current = context.blockX() * 2.5F;
            float target = context.blockZ() * -3.25F;
            float step = context.blockY() * 0.125F;
            return net.minecraft.util.Mth.degreesDifference(current, target) * 0.001F
                    + net.minecraft.util.Mth.degreesDifferenceAbs(current, target) * 0.001F
                    + net.minecraft.util.Mth.approach(current, target, step) * 0.01F
                    + net.minecraft.util.Mth.approachDegrees(current, target, step) * 0.01F
                    + net.minecraft.util.Mth.rotateIfNecessary(current, target, step) * 0.001F
                    + net.minecraft.util.Mth.rotLerp(0.35F, current, target) * 0.001F
                    + net.minecraft.util.Mth.rotLerp(context.blockY() * 0.01,
                            context.blockX() * 2.5, context.blockZ() * -3.25) * 0.0001;
        }
    }

    private static final class VectorLengthMathDensity extends NumericSmokeDensity {
        @Override public double compute(FunctionContext context) {
            float x = context.blockX() * 0.125F;
            float y = context.blockY() * -0.0625F;
            float z = context.blockZ() * 0.25F;
            double dx = context.blockX() * 0.03125;
            double dy = context.blockY() * -0.015625;
            double dz = context.blockZ() * 0.0625;
            return net.minecraft.util.Mth.lengthSquared(x, y, z) * 0.001F
                    + net.minecraft.util.Mth.length(dx, dz) * 0.01
                    + net.minecraft.util.Mth.length(dx, dy, dz) * 0.01
                    + net.minecraft.util.Mth.lengthSquared(dx, dy) * 0.001
                    + net.minecraft.util.Mth.lengthSquared(dx, dy, dz) * 0.001;
        }
    }

    private static final class IntegerInterpolationMathDensity extends NumericSmokeDensity {
        @Override public double compute(FunctionContext context) {
            float delta = context.blockX() * 0.00390625F;
            return net.minecraft.util.Mth.lerpInt(delta, -300_001, 280_003) * 0.001
                    + net.minecraft.util.Mth.lerpDiscrete(delta, -23, 37) * 0.01;
        }
    }

    private static final class ExternalStaticHelperDensity extends NumericSmokeDensity {
        @Override public double compute(FunctionContext context) {
            return TerrainMathLibrary.shape(context.blockY(), 0.125)
                    + TerrainMathLibrary.ridge(context.blockX(), context.blockZ()) * 0.05
                    + TerrainMathLibrary.octave(context.blockX() * 0.03125, 2) * 0.01;
        }
    }

    private static final class TerrainMathLibrary {
        private static final double RIDGE_SCALE = 0.0625;
        private static final double[] OCTAVE_WEIGHTS = {0.5, -0.25, 0.125, -0.0625};

        private static double ridge(double x, double z) {
            return Math.abs(x - z) * RIDGE_SCALE + Math.sin(x * 0.03125);
        }

        private static double shape(int y, double scale) {
            double elevation = y * scale;
            double ridge = ridge(elevation, y - elevation);
            return ridge * 0.5 + (y > 0 ? elevation * 0.01 : -elevation * 0.02);
        }

        private static double octave(double input, int octave) {
            return Math.sin(input * (octave + 1)) * OCTAVE_WEIGHTS[octave];
        }
    }

    private static final class ConstantArrayHelperDensity extends NumericSmokeDensity {
        private final float[] octaveWeights = {0.5F, -0.25F, 0.125F, -0.0625F, 0.03125F};

        @Override public double compute(FunctionContext context) {
            float x = context.blockX() * 0.03125F;
            float sum = 0.0F;
            for (int octave = 0; octave < octaveWeights.length; octave++) {
                sum += net.minecraft.util.Mth.sin(x * (octave + 1)) * octaveWeights[octave];
            }
            return sum;
        }
    }

    private abstract static class TerrainShapeBase extends NumericSmokeDensity {
        private final double horizontalScale = 0.03125;
        private final double verticalScale = 0.125;

        protected final double shape(int x, int y, int z) {
            double ridge = Math.abs(x - z) * horizontalScale;
            double elevation = y * verticalScale;
            return ridge + (y > 0 ? elevation * 0.01 : -elevation * 0.02);
        }
    }

    private abstract static class SharedTerrainDensity extends TerrainShapeBase {
        @Override public double compute(FunctionContext context) {
            return shape(context.blockX(), context.blockY(), context.blockZ());
        }
    }

    private static final class InheritedComputeDensity extends SharedTerrainDensity {}

    private interface DefaultInterfaceTerrainDensity extends DensityFunction.SimpleFunction {
        @Override default double compute(FunctionContext context) {
            double x = context.blockX() * 0.03125;
            double z = context.blockZ() * 0.0625;
            return Math.sin(x - z) * 0.5 + Math.abs(x + z) * 0.01;
        }

        @Override default DensityFunction mapAll(Visitor visitor) { return visitor.apply(this); }
        @Override default double minValue() { return -2.0; }
        @Override default double maxValue() { return 2.0; }
        @Override default net.minecraft.util.KeyDispatchDataCodec<? extends DensityFunction> codec() {
            return DensityFunctions.constant(0.0).codec();
        }
    }

    private static final class DefaultInterfaceDensity implements DefaultInterfaceTerrainDensity {}

    private record TerrainShapeParameters(double horizontalScale, double verticalScale) {}

    private enum TerrainMode { RIDGE, BASIN, SHELF }

    private static final class EnumSwitchConfigurationDensity extends NumericSmokeDensity {
        private final TerrainMode mode = TerrainMode.BASIN;

        @Override public double compute(FunctionContext context) {
            return switch (mode) {
                case RIDGE -> context.blockX() * 0.125 + context.blockY() * 0.0625;
                case BASIN -> context.blockZ() * 0.25 - context.blockY() * 0.03125;
                case SHELF -> Math.abs(context.blockX() - context.blockZ()) * 0.5;
            };
        }
    }

    private static final class StringSwitchConfigurationDensity extends NumericSmokeDensity {
        // These collide under String.hashCode(), and the shared hash is outside
        // the exact float range; the equals chain must still select correctly.
        private final String mode = "BBBBBB";

        @Override public double compute(FunctionContext context) {
            return switch (mode) {
                case "AaAaAa" -> context.blockX() * 0.125 + context.blockY() * 0.0625;
                case "BBBBBB" -> context.blockZ() * 0.25 - context.blockY() * 0.03125;
                case "ridge" -> Math.abs(context.blockX() - context.blockZ()) * 0.5;
                default -> context.blockY() * 0.125;
            };
        }
    }

    private static final class StringComparisonConfigurationDensity extends NumericSmokeDensity {
        private final String mode = "RIDGE";

        @Override public double compute(FunctionContext context) {
            if ("ridge".equalsIgnoreCase(mode)) {
                return Math.abs(context.blockX() - context.blockZ()) * 0.5;
            }
            return context.blockY() * 0.125;
        }
    }

    private static final class ObjectsEqualsConfigurationDensity extends NumericSmokeDensity {
        private final String mode = "BASIN";

        @Override public double compute(FunctionContext context) {
            if (java.util.Objects.equals(mode, "BASIN")) {
                return context.blockZ() * 0.25 - context.blockY() * 0.03125;
            }
            return context.blockX() * 0.125;
        }
    }

    private static final class CustomObjectsEqualsDensity extends NumericSmokeDensity {
        private final TerrainShapeParametersObject configuration =
                new TerrainShapeParametersObject(0.5, 0.25);

        @Override public double compute(FunctionContext context) {
            return java.util.Objects.equals(configuration, configuration)
                    ? context.blockX() * 0.125 : context.blockZ() * 0.25;
        }
    }

    private static final class EnumEqualityConfigurationDensity extends NumericSmokeDensity {
        private static final TerrainMode MODE = TerrainMode.SHELF;

        @Override public double compute(FunctionContext context) {
            if (MODE == TerrainMode.RIDGE) return context.blockX() * 0.125;
            if (MODE == TerrainMode.SHELF) return context.blockZ() * 0.25 + 3.0;
            return context.blockY() * 0.5;
        }
    }

    private static final class HolderBackedEnumConfigurationDensity extends NumericSmokeDensity {
        private final Holder<TerrainMode> mode = Holder.direct(TerrainMode.RIDGE);

        @Override public double compute(FunctionContext context) {
            return switch (mode.value()) {
                case RIDGE -> context.blockX() * 0.125 + context.blockY() * 0.0625;
                case BASIN -> context.blockZ() * 0.25 - context.blockY() * 0.03125;
                case SHELF -> Math.abs(context.blockX() - context.blockZ()) * 0.5;
            };
        }
    }

    private static final class MutableEnumConfigurationDensity extends NumericSmokeDensity {
        private TerrainMode mode = TerrainMode.RIDGE;

        @Override public double compute(FunctionContext context) {
            return mode == TerrainMode.RIDGE
                    ? context.blockX() * 0.125 : context.blockZ() * 0.25;
        }
    }

    private static final class HolderTypeGuardConfigurationDensity extends NumericSmokeDensity {
        private final Holder<Object> configuration = Holder.direct(
                new TerrainShapeParameters(0.03125, 0.125));

        @Override public double compute(FunctionContext context) {
            Object value = configuration.value();
            if (value instanceof TerrainShapeParameters parameters) {
                return Math.sin(context.blockX() * parameters.horizontalScale()) * 0.25
                        + Math.abs(context.blockY() * parameters.verticalScale()) * 0.01;
            }
            return context.blockZ() * 0.125;
        }
    }

    private static final class TerrainShapeParametersObject {
        private final double horizontalScale;
        private final double verticalScale;

        private TerrainShapeParametersObject(double horizontalScale, double verticalScale) {
            this.horizontalScale = horizontalScale;
            this.verticalScale = verticalScale;
        }

        private double terrainContribution(double x, int y) {
            double elevation = y * verticalScale;
            return Math.sin(x * horizontalScale) * 0.25 + Math.abs(elevation) * 0.01;
        }
    }

    private static final class FinalFieldConfigurationDensity extends NumericSmokeDensity {
        private final TerrainShapeParametersObject parameters =
                new TerrainShapeParametersObject(0.03125, 0.125);

        @Override public double compute(FunctionContext context) {
            return parameters.terrainContribution(context.blockX(), context.blockY());
        }
    }

    private static final class HolderBackedConfigurationDensity extends NumericSmokeDensity {
        private final Holder<TerrainShapeParameters> parameters = Holder.direct(
                new TerrainShapeParameters(0.03125, 0.125));
        private final Holder<Float> floatScale = Holder.direct(0.125F);

        @Override public double compute(FunctionContext context) {
            TerrainShapeParameters shape = parameters.value();
            return Math.sin(context.blockX() * shape.horizontalScale()) * 0.25
                    + Math.abs(context.blockY() * floatScale.value()) * 0.01;
        }
    }

    private static final class UnsupportedTerrainDensity extends NumericSmokeDensity {
        private final NormalNoise sampler = NormalNoise.create(RandomSource.create(913L), -2, 1.0, 0.5);

        @Override public double compute(FunctionContext context) {
            // Build a noise-backed GPU expression before the opaque call so
            // rollback covers abandoned nodes and sampler registrations.
            return sampler.getValue(context.blockX() * 0.03125,
                    context.blockY() * 0.125, context.blockZ() * 0.03125) + Math.random();
        }
    }

    private static final class PartiallyUnsupportedTerrainDensity extends NumericSmokeDensity {
        private final DensityFunction supportedChild;
        private final DensityFunction unsupportedChild;
        private final NormalNoise sampler = NormalNoise.create(RandomSource.create(914L), -2, 1.0, 0.5);

        private PartiallyUnsupportedTerrainDensity(DensityFunction supportedChild,
                                                   DensityFunction unsupportedChild) {
            this.supportedChild = supportedChild;
            this.unsupportedChild = unsupportedChild;
        }

        @Override public double compute(FunctionContext context) {
            double sample = sampler.getValue(context.blockX() * 0.03125,
                    context.blockY() * 0.125, context.blockZ() * 0.03125);
            return supportedChild.compute(context) + sample + unsupportedChild.compute(context);
        }
    }

    private static final class RecordConfigurationDensity extends NumericSmokeDensity {
        private final TerrainShapeParameters parameters = new TerrainShapeParameters(0.03125, 0.125);

        @Override public double compute(FunctionContext context) {
            double x = context.blockX() * parameters.horizontalScale();
            double y = context.blockY() * parameters.verticalScale();
            return Math.sin(x) * 0.25 + Math.abs(y) * 0.01;
        }
    }

    private static final class MinecraftMathOverloadsDensity extends NumericSmokeDensity {
        @Override public double compute(FunctionContext context) {
            int x = context.blockX();
            float fx = x * 0.125F;
            double z = context.blockZ();
            return net.minecraft.util.Mth.floor(fx * 0.7F) * 0.01
                    + net.minecraft.util.Mth.ceil(z * 0.1) * 0.01
                    + net.minecraft.util.Mth.sin(fx) * 0.1F
                    + net.minecraft.util.Mth.cos(fx) * 0.1F
                    + net.minecraft.util.Mth.sqrt(net.minecraft.util.Mth.abs(fx)) * 0.001F
                    + net.minecraft.util.Mth.abs(x) * 1.0e-9
                    + net.minecraft.util.Mth.square(x) * 1.0e-12;
        }
    }

    private static final class Lerp2Density implements DensityFunction.SimpleFunction {
        @Override public double compute(FunctionContext context) {
            return net.minecraft.util.Mth.lerp2(context.blockX() / 64.0, context.blockY() / 32.0,
                    -3.0, 1.0, 2.0, -1.0);
        }
        @Override public DensityFunction mapAll(Visitor visitor) { return visitor.apply(this); }
        @Override public double minValue() { return -4.0; }
        @Override public double maxValue() { return 4.0; }
        @Override public net.minecraft.util.KeyDispatchDataCodec<? extends DensityFunction> codec() {
            return DensityFunctions.constant(0.0).codec();
        }
    }

    private static final class Lerp3Density implements DensityFunction.SimpleFunction {
        @Override public double compute(FunctionContext context) {
            return net.minecraft.util.Mth.lerp3(context.blockX() / 64.0, context.blockY() / 32.0,
                    context.blockZ() / 64.0, -2.0, 1.0, 0.5, 3.0, 4.0, -1.5, 2.5, -3.0);
        }
        @Override public DensityFunction mapAll(Visitor visitor) { return visitor.apply(this); }
        @Override public double minValue() { return -8.0; }
        @Override public double maxValue() { return 8.0; }
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

    private static final class DoubleDivisionDensity implements DensityFunction.SimpleFunction {
        @Override public double compute(FunctionContext context) { return context.blockX() / 0.0; }
        @Override public DensityFunction mapAll(Visitor visitor) { return visitor.apply(this); }
        @Override public double minValue() { return Double.NEGATIVE_INFINITY; }
        @Override public double maxValue() { return Double.POSITIVE_INFINITY; }
        @Override public net.minecraft.util.KeyDispatchDataCodec<? extends DensityFunction> codec() {
            return DensityFunctions.constant(0.0).codec();
        }
    }
}
