package dev.gpunoise;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Aquifer;
import dev.gpunoise.GpuAquiferDecisionData.AquiferQuery;
import dev.gpunoise.GpuAquiferDecisionData.AquiferQueries;

/** Headless Vulkan smoke test for CI's Mesa Lavapipe device. */
public final class VulkanCiSmoke {
    private static final float EXPECTED = 0.375f;
    private static volatile GpuDensityExpressionSmoke.VulkanBranchFixture registeredTranslatorFixture;

    private VulkanCiSmoke() {}

    /** Register and compile the adapter before terrain graph compilation freezes registrations. */
    static void prepareTranslatorIntegration() {
        if (registeredTranslatorFixture != null) return;
        synchronized (VulkanCiSmoke.class) {
            if (registeredTranslatorFixture == null) {
                registeredTranslatorFixture = createRegisteredTranslatorFixture();
            }
        }
    }

    public static void main(String[] args) throws Exception {
        // Called by the CI server after NeoForge has initialized the registry
        // and mod-loading lifecycle. A standalone JavaExec cannot safely boot
        // these fixtures because NeoForge's LoadingModList is unavailable.
        GpuDensityExpressionSmoke.VulkanBranchFixture registeredTranslator = registeredTranslatorFixture;
        if (registeredTranslator == null) {
            throw new IllegalStateException("Registered density adapter smoke was not prepared before graph compilation");
        }
        verifyTerrainParityRules();
        verifyChunkPipelineCancellation();
        GpuDensityExpressionSmoke.verify();
        verifyTranslatorRegistry();
        verifyAquiferStatusRegionCache();
        GpuDevice device = null;
        for (GpuDevice candidate : GpuProbe.findPreferredDevices(GpuNoiseConfig.GpuVendorPreference.AUTO)) {
            if (VulkanComputeProbe.verify(candidate)) {
                device = candidate;
                break;
            }
        }
        if (device == null) throw new IllegalStateException("No Vulkan compute device passed initialization");
        GpuDevice selectedDevice = device;

        // Supply an already-lowered one-node IR directly. This smoke test
        // exercises Vulkan shader dispatch without booting NeoForge registries.
        GpuDensityGraph graph = new GpuDensityGraph(
                List.of(new GpuDensityGraph.Node("Constant", new int[0], new double[]{EXPECTED}, null, 0)),
                new int[]{0}, List.of(), List.of(), List.of(), List.of(), 1);
        System.out.println("Vulkan CI smoke: creating submission ring");
        VulkanSubmissionRing ring = createTwoSlotSmokeRing(selectedDevice);
        try (ring) {
            assertNumericFixture(ring, registeredTranslator, "registered custom density translator",
                    new float[]{-193.0f, -1.0f, 37.0f, 256.0f},
                    new float[]{-64.0f, 0.0f, 91.0f, 319.0f},
                    new float[]{-117.0f, 1.0f, -248.0f, 128.0f});
            float[] x = {-64.0f, 0.0f, 16_777_216.0f, 30_000_000.0f};
            float[] y = {-64.0f, 0.0f, 128.0f, 320.0f};
            float[] z = {64.0f, 0.0f, -16_777_216.0f, -30_000_000.0f};
            System.out.println("Vulkan CI smoke: dispatching coordinate graph shader");
            float[] graphResults = ring.submitGraphRoots(graph, x, y, z)
                    .get(60, TimeUnit.SECONDS);
            System.out.println("Vulkan CI smoke: coordinate graph shader completed");
            assertConstantResults("coordinate graph shader", graphResults, x.length);

            GpuDensityGraph conditionalGraph = GpuDensityExpressionSmoke.conditionalGraphForVulkanSmoke();
            float[] conditionalResults = ring.submitGraphRoots(conditionalGraph, x, y, z)
                    .get(60, TimeUnit.SECONDS);
            for (int sample = 0; sample < x.length; sample++) {
                float expected = x[sample] < 0.0f ? -1.0f : (x[sample] == 0.0f ? 0.0f : 1.0f);
                if (Math.abs(conditionalResults[sample] - expected) > 1.0e-6f) {
                    throw new IllegalStateException("Conditional Java bytecode mismatch at sample " + sample
                            + ": expected " + expected + ", got " + conditionalResults[sample]);
                }
            }

            GpuDensityExpressionSmoke.VulkanBranchFixture floatingFixture =
                    GpuDensityExpressionSmoke.floatingConditionalFixture();
            // Keep noise-branch parity independent from the separate large
            // integer-coordinate fixtures above. This frontend currently
            // feeds NormalNoise through float coordinates, so values near
            // 2^24 test coordinate precision rather than branch lowering.
            float[] floatingX = {-4096.0f, -64.0f, 0.0f, 128.0f};
            float[] floatingY = {-320.0f, -64.0f, 0.0f, 320.0f};
            float[] floatingZ = {4096.0f, 64.0f, 0.0f, -128.0f};
            float[] floatingBranchResults = ring.submitGraphRoots(
                    floatingFixture.graph(), floatingX, floatingY, floatingZ)
                    .get(60, TimeUnit.SECONDS);
            for (int sample = 0; sample < floatingX.length; sample++) {
                double expected = floatingFixture.javaFunction().compute(
                        new net.minecraft.world.level.levelgen.DensityFunction.SinglePointContext(
                                (int) floatingX[sample], (int) floatingY[sample], (int) floatingZ[sample]));
                if (Math.abs(floatingBranchResults[sample] - expected) > 0.005) {
                    throw new IllegalStateException("Floating conditional Java bytecode mismatch at sample "
                            + sample + ": expected " + expected + ", got " + floatingBranchResults[sample]);
                }
            }

            float[] switchX = {-7.0f, -1.0f, 0.0f, 1.0f, 2.0f, 100.0f};
            float[] switchY = new float[switchX.length];
            float[] switchZ = new float[switchX.length];
            float[] switchResults = ring.submitGraphRoots(
                    GpuDensityExpressionSmoke.switchGraphForVulkanSmoke(), switchX, switchY, switchZ)
                    .get(60, TimeUnit.SECONDS);
            float[] switchExpected = {2.0f, -1.0f, 0.0f, 1.0f, 2.0f, 2.0f};
            for (int sample = 0; sample < switchX.length; sample++) {
                if (Math.abs(switchResults[sample] - switchExpected[sample]) > 1.0e-6f) {
                    throw new IllegalStateException("Switch Java bytecode mismatch at sample " + sample
                            + ": expected " + switchExpected[sample] + ", got " + switchResults[sample]);
                }
            }

            // Keep signed integer remainder parity isolated from switch
            // control-flow lowering. This is also the selector operation
            // used by the bounded switch-loop fixture below.
            GpuDensityGraph remainderGraph = GpuDensityExpressionSmoke.remainderGraphForVulkanSmoke();
            float[] remainderX = {-7.0f, -6.0f, -1.0f, 0.0f, 1.0f, 7.0f};
            float[] remainderZeros = new float[remainderX.length];
            float[] remainderResults = ring.submitGraphRoots(remainderGraph, remainderX,
                    remainderZeros, remainderZeros).get(60, TimeUnit.SECONDS);
            for (int sample = 0; sample < remainderX.length; sample++) {
                int remainder = (int) remainderX[sample] % 16;
                double expected = remainder < 0 ? remainder - 0.5 : remainder + 0.5;
                if (remainderResults[sample] != expected) {
                    throw new IllegalStateException("Signed integer remainder Vulkan mismatch at sample "
                            + sample + " (x=" + remainderX[sample] + "): expected " + expected
                            + ", got " + remainderResults[sample]);
                }
            }

            float[] mathX = {-81.0f, -1.0f, 0.0f, 17.0f, 192.0f};
            float[] mathY = {-17.0f, 0.0f, 1.0f, 9.0f, 80.0f};
            float[] mathZ = {-5.0f, 1.0f, 0.0f, 31.0f, -64.0f};
            assertNumericFixture(ring, GpuDensityExpressionSmoke.doubleMinMaxFixture(),
                    "double Math.min/max", mathX, mathY, mathZ);
            assertNumericFixture(ring, GpuDensityExpressionSmoke.minMaxEdgeFixture(),
                    "Math.min/max NaN and signed zero",
                    new float[]{1.0f, 1.0f, 1.0f, -1.0f, 0.0f, 0.0f},
                    new float[]{-1.0f, -1.0f, 1.0f, -1.0f, 0.0f, 0.0f},
                    new float[]{0.0f, 1.0f, 0.0f, 1.0f, 2.0f, 3.0f});
            assertNumericFixture(ring, GpuDensityExpressionSmoke.floatMathFixture(),
                    "float Math and Mth.square", mathX, mathY, mathZ);
            assertNumericFixture(ring, GpuDensityExpressionSmoke.floatMinMaxEdgeFixture(),
                    "float Math.min/max NaN and signed zero",
                    new float[]{1.0f, 1.0f, 0.0f, 0.0f},
                    new float[]{-1.0f, -1.0f, 0.0f, 0.0f},
                    new float[]{0.0f, 1.0f, 2.0f, 3.0f});
            assertNumericFixture(ring, GpuDensityExpressionSmoke.dynamicClampFixture(),
                    "dynamic double/float Mth.clamp", mathX, mathY, mathZ);
            assertNumericFixture(ring, GpuDensityExpressionSmoke.transcendentalMathFixture(),
                    "transcendental Math", mathX, mathY, mathZ);
            assertNumericFixture(ring, GpuDensityExpressionSmoke.powerMathFixture(),
                    "pow", mathX, mathY, mathZ);
            assertNumericFixture(ring, GpuDensityExpressionSmoke.atan2MathFixture(),
                    "atan2", mathX, mathY, mathZ);
            float[] atanAxes = {-1.0f, -1.0f, 1.0f, 1.0f, 0.0f};
            assertNumericFixture(ring, GpuDensityExpressionSmoke.atan2SignedZeroFixture(),
                    "atan2 signed-zero quadrants", atanAxes, new float[atanAxes.length],
                    new float[]{-1.0f, 1.0f, -1.0f, 1.0f, 0.0f});
            assertNumericFixture(ring, GpuDensityExpressionSmoke.signumMathFixture(),
                    "signum", mathX, mathY, mathZ);
            assertNumericFixture(ring, GpuDensityExpressionSmoke.copySignMathFixture(),
                    "Math.copySign signed zero and NaN",
                    new float[]{0.0f, 0.0f, 2.0f, 2.0f, -2.0f},
                    new float[]{-1.0f, 1.0f, -1.0f, 1.0f, 1.0f}, new float[5], true);
            assertNumericFixture(ring, GpuDensityExpressionSmoke.floatCopySignMathFixture(),
                    "Math.copySign float signed zero and NaN",
                    new float[]{0.0f, 0.0f, 1.0f, 1.0f, -1.0f},
                    new float[]{-1.0f, 1.0f, 1.0f, -1.0f, 1.0f}, new float[5], true);
            assertNumericFixture(ring, GpuDensityExpressionSmoke.mapMathFixture(),
                    "Mth mapping functions", mathX, mathY, mathZ);
            assertNumericFixture(ring, GpuDensityExpressionSmoke.interpolationMathFixture(),
                    "Mth interpolation functions", mathX, mathY, mathZ);
            assertNumericFixture(ring, GpuDensityExpressionSmoke.floatInterpolationMathFixture(),
                    "Mth float interpolation functions", mathX, mathY, mathZ);
            assertNumericFixture(ring, GpuDensityExpressionSmoke.moduloAndAngleMathFixture(),
                    "Mth modulo and angle functions", mathX, mathY, mathZ);
            float[] angleX = {-2160.0f, -720.0f, -716.0f, 716.0f, 720.0f, 2160.0f};
            float[] angleZ = {-1440.0f, -1432.0f, -1431.0f, 1431.0f, 1432.0f, 1440.0f};
            assertNumericFixture(ring, GpuDensityExpressionSmoke.moduloAndAngleMathFixture(),
                    "Mth modulo and angle boundary cases", angleX, new float[angleX.length], angleZ);
            assertNumericFixture(ring, GpuDensityExpressionSmoke.triangleWaveMathFixture(),
                    "Mth triangle wave", mathX, mathY, mathZ);
            assertNumericFixture(ring, GpuDensityExpressionSmoke.angleMotionMathFixture(),
                    "Mth angle motion functions", mathX, mathY, mathZ);
            float[] angleMotionX = {-73.0f, -72.0f, -71.0f, 71.0f, 72.0f, 73.0f};
            float[] angleMotionY = {-16.0f, -1.0f, 0.0f, 1.0f, 16.0f, 80.0f};
            assertNumericFixture(ring, GpuDensityExpressionSmoke.angleMotionMathFixture(),
                    "Mth angle motion boundary cases", angleMotionX, angleMotionY,
                    new float[angleMotionX.length]);
            assertNumericFixture(ring, GpuDensityExpressionSmoke.vectorLengthMathFixture(),
                    "Mth vector length functions", mathX, mathY, mathZ);
            assertNumericFixture(ring, GpuDensityExpressionSmoke.vectorLengthMathFixture(),
                    "Mth vector length zero vector", new float[]{0.0f},
                    new float[]{0.0f}, new float[]{0.0f});
            assertNumericFixture(ring, GpuDensityExpressionSmoke.integerInterpolationMathFixture(),
                    "Mth integer interpolation functions", mathX, mathY, mathZ);
            assertNumericFixture(ring, GpuDensityExpressionSmoke.externalStaticHelperFixture(),
                    "external static terrain helpers", mathX, mathY, mathZ);
            assertNumericFixture(ring, GpuDensityExpressionSmoke.constantArrayHelperFixture(),
                    "constant-index terrain arrays", mathX, mathY, mathZ);
            assertNumericFixture(ring, GpuDensityExpressionSmoke.inheritedComputeFixture(),
                    "inherited terrain compute implementation", mathX, mathY, mathZ);
            assertNumericFixture(ring, GpuDensityExpressionSmoke.defaultInterfaceComputeFixture(),
                    "default interface terrain compute implementation", mathX, mathY, mathZ);
            assertNumericFixture(ring, GpuDensityExpressionSmoke.recordConfigurationFixture(),
                    "immutable terrain record configuration", mathX, mathY, mathZ);
            assertNumericFixture(ring, GpuDensityExpressionSmoke.enumSwitchConfigurationFixture(),
                    "enum-switch terrain configuration", mathX, mathY, mathZ);
            assertNumericFixture(ring, GpuDensityExpressionSmoke.stringSwitchConfigurationFixture(),
                    "string-switch terrain configuration", mathX, mathY, mathZ);
            assertNumericFixture(ring, GpuDensityExpressionSmoke.stringComparisonConfigurationFixture(),
                    "case-insensitive string terrain configuration", mathX, mathY, mathZ);
            assertNumericFixture(ring, GpuDensityExpressionSmoke.objectsEqualsConfigurationFixture(),
                    "immutable Objects.equals terrain configuration", mathX, mathY, mathZ);
            assertNumericFixture(ring, GpuDensityExpressionSmoke.enumEqualityConfigurationFixture(),
                    "enum-equality terrain configuration", mathX, mathY, mathZ);
            assertNumericFixture(ring, GpuDensityExpressionSmoke.holderBackedEnumConfigurationFixture(),
                    "holder-backed enum terrain configuration", mathX, mathY, mathZ);
            assertNumericFixture(ring, GpuDensityExpressionSmoke.holderTypeGuardConfigurationFixture(),
                    "holder-backed typed terrain configuration", mathX, mathY, mathZ);
            assertNumericFixture(ring, GpuDensityExpressionSmoke.finalFieldConfigurationFixture(),
                    "final-field terrain helper object", mathX, mathY, mathZ);
            assertNumericFixture(ring, GpuDensityExpressionSmoke.holderBackedConfigurationFixture(),
                    "bound-holder terrain helper object", mathX, mathY, mathZ);
            assertNumericFixture(ring, GpuDensityExpressionSmoke.transformedNoiseFixture(),
                    "generic transformed NormalNoise coordinates",
                    new float[]{-193.0f, -1.0f, 37.0f, 256.0f},
                    new float[]{-64.0f, 0.0f, 91.0f, 319.0f},
                    new float[]{-117.0f, 1.0f, -248.0f, 128.0f});
            verifyLargeCoordinateNoiseParity(ring);
            assertNumericFixture(ring, GpuDensityExpressionSmoke.mixedTerrainRootFixture(),
                    "supported terrain root beside CPU fallback root", mathX, mathY, mathZ);
            GpuDensityExpressionSmoke.VulkanBranchFixture minecraftMathFixture =
                    GpuDensityExpressionSmoke.minecraftMathOverloadsFixture();
            float[] overloadX = {-2_147_483_648.0f, -46_341.0f, 0.0f, 46_341.0f, 2_147_483_520.0f};
            float[] overloadZ = {-17.0f, -1.0f, 0.0f, 31.0f, 64.0f};
            assertNumericFixture(ring, minecraftMathFixture, "Mth float and integer overloads",
                    overloadX, new float[overloadX.length], overloadZ);

            GpuDensityExpressionSmoke.VulkanBranchFixture integerFixture =
                    GpuDensityExpressionSmoke.integerMathFixture();
            float[] integerX = {-30_000_001.0f, -17.0f, -1.0f, 0.0f, 19.0f, 30_000_001.0f};
            float[] integerZeros = new float[integerX.length];
            float[] integerResults = ring.submitGraphRoots(integerFixture.graph(), integerX,
                    integerZeros, integerZeros).get(60, TimeUnit.SECONDS);
            for (int sample = 0; sample < integerX.length; sample++) {
                int xValue = (int) integerX[sample];
                double expected = integerFixture.javaFunction().compute(
                        new net.minecraft.world.level.levelgen.DensityFunction.SinglePointContext(xValue, 0, 0));
                if (Math.abs(integerResults[sample] - expected) > 128.0) {
                    throw new IllegalStateException("Integer arithmetic Java bytecode mismatch at sample " + sample
                            + ": expected " + expected + ", got " + integerResults[sample]);
                }
            }
            GpuDensityExpressionSmoke.VulkanBranchFixture bitwiseFixture =
                    GpuDensityExpressionSmoke.integerBitwiseFixture();
            float[] bitwiseX = {-30_000_001.0f, -257.0f, -1.0f, 0.0f,
                    1.0f, 19.0f, 257.0f, 30_000_001.0f};
            float[] bitwiseZeros = new float[bitwiseX.length];
            float[] bitwiseResults = ring.submitGraphRoots(bitwiseFixture.graph(), bitwiseX,
                    bitwiseZeros, bitwiseZeros).get(60, TimeUnit.SECONDS);
            for (int sample = 0; sample < bitwiseX.length; sample++) {
                int xValue = (int) bitwiseX[sample];
                double expected = bitwiseFixture.javaFunction().compute(
                        new net.minecraft.world.level.levelgen.DensityFunction.SinglePointContext(xValue, 0, 0));
                if (Math.abs(bitwiseResults[sample] - expected) > 0.005) {
                    throw new IllegalStateException("Integer bitwise/shift Java bytecode mismatch at sample " + sample
                            + ": expected " + expected + ", got " + bitwiseResults[sample]);
                }
            }
            GpuDensityExpressionSmoke.VulkanBranchFixture overflowFixture =
                    GpuDensityExpressionSmoke.integerDivisionOverflowFixture();
            float[] overflowX = {-1.0f, 0.0f, 1.0f};
            float[] overflowZeros = new float[overflowX.length];
            float[] overflowResults = ring.submitGraphRoots(overflowFixture.graph(), overflowX,
                    overflowZeros, overflowZeros).get(60, TimeUnit.SECONDS);
            for (int sample = 0; sample < overflowX.length; sample++) {
                int xValue = (int) overflowX[sample];
                double expected = overflowFixture.javaFunction().compute(
                        new net.minecraft.world.level.levelgen.DensityFunction.SinglePointContext(xValue, 0, 0));
                if (overflowResults[sample] != (float) expected) {
                    throw new IllegalStateException("Integer MIN_VALUE / -1 mismatch at sample " + sample
                            + ": expected " + expected + ", got " + overflowResults[sample]);
                }
            }
            GpuDensityExpressionSmoke.VulkanBranchFixture floorDivisionFixture =
                    GpuDensityExpressionSmoke.floorDivisionFixture();
            float[] floorX = {-33.0f, -32.0f, -17.0f, -16.0f, -15.0f, -1.0f,
                    0.0f, 1.0f, 15.0f, 16.0f, 17.0f, 32.0f, 33.0f};
            float[] floorZeros = new float[floorX.length];
            float[] floorResults = ring.submitGraphRoots(floorDivisionFixture.graph(), floorX,
                    floorZeros, floorZeros).get(60, TimeUnit.SECONDS);
            for (int sample = 0; sample < floorX.length; sample++) {
                double expected = floorDivisionFixture.javaFunction().compute(
                        new net.minecraft.world.level.levelgen.DensityFunction.SinglePointContext(
                                (int) floorX[sample], 0, 0));
                if (Math.abs(floorResults[sample] - expected) > 0.005) {
                    throw new IllegalStateException("Java floorDiv/floorMod mismatch at sample " + sample
                            + ": expected " + expected + ", got " + floorResults[sample]);
                }
            }
            GpuDensityExpressionSmoke.VulkanBranchFixture narrowingFixture =
                    GpuDensityExpressionSmoke.narrowingFixture();
            float[] narrowingX = {-257.0f, -129.0f, -1.0f, 0.0f, 127.0f, 128.0f, 255.0f, 65_537.0f};
            float[] narrowingZeros = new float[narrowingX.length];
            float[] narrowingResults = ring.submitGraphRoots(narrowingFixture.graph(), narrowingX,
                    narrowingZeros, narrowingZeros).get(60, TimeUnit.SECONDS);
            for (int sample = 0; sample < narrowingX.length; sample++) {
                int xValue = (int) narrowingX[sample];
                double expected = narrowingFixture.javaFunction().compute(
                        new net.minecraft.world.level.levelgen.DensityFunction.SinglePointContext(xValue, 0, 0));
                if (narrowingResults[sample] != (float) expected) {
                    throw new IllegalStateException("Integer narrowing Java bytecode mismatch at sample " + sample
                            + ": expected " + expected + ", got " + narrowingResults[sample]);
                }
            }
            GpuDensityExpressionSmoke.VulkanBranchFixture floatFixture =
                    GpuDensityExpressionSmoke.floatArithmeticFixture();
            float[] floatX = {-30_000_001.0f, -257.0f, -1.0f, 0.0f, 7.0f, 1_234_567.0f, 30_000_001.0f};
            float[] floatZeros = new float[floatX.length];
            float[] floatResults = ring.submitGraphRoots(floatFixture.graph(), floatX,
                    floatZeros, floatZeros).get(60, TimeUnit.SECONDS);
            for (int sample = 0; sample < floatX.length; sample++) {
                int xValue = (int) floatX[sample];
                double expected = floatFixture.javaFunction().compute(
                        new net.minecraft.world.level.levelgen.DensityFunction.SinglePointContext(xValue, 0, 0));
                if (Math.abs(floatResults[sample] - expected) > 0.5) {
                    throw new IllegalStateException("Float rounding Java bytecode mismatch at sample " + sample
                            + ": expected " + expected + ", got " + floatResults[sample]);
                }
            }
            GpuDensityExpressionSmoke.VulkanBranchFixture floatCompareFixture =
                    GpuDensityExpressionSmoke.floatCompareFixture();
            float[] comparisonX = {-7.0f, -1.0f, 0.0f, 1.0f, 7.0f};
            float[] comparisonZeros = new float[comparisonX.length];
            float[] comparisonResults = ring.submitGraphRoots(floatCompareFixture.graph(), comparisonX,
                    comparisonZeros, comparisonZeros).get(60, TimeUnit.SECONDS);
            for (int sample = 0; sample < comparisonX.length; sample++) {
                int xValue = (int) comparisonX[sample];
                double expected = floatCompareFixture.javaFunction().compute(
                        new net.minecraft.world.level.levelgen.DensityFunction.SinglePointContext(xValue, 0, 0));
                if (comparisonResults[sample] != (float) expected) {
                    throw new IllegalStateException("Float NaN compare Java bytecode mismatch at sample " + sample
                            + ": expected " + expected + ", got " + comparisonResults[sample]);
                }
            }
            GpuDensityExpressionSmoke.VulkanBranchFixture doubleDivisionFixture =
                    GpuDensityExpressionSmoke.doubleDivisionFixture();
            float[] divisionX = {-7.0f, 0.0f, 7.0f};
            float[] divisionZeros = new float[divisionX.length];
            float[] divisionResults = ring.submitGraphRoots(doubleDivisionFixture.graph(), divisionX,
                    divisionZeros, divisionZeros).get(60, TimeUnit.SECONDS);
            if (divisionResults[0] != Float.NEGATIVE_INFINITY
                    || !Float.isNaN(divisionResults[1])
                    || divisionResults[2] != Float.POSITIVE_INFINITY) {
                throw new IllegalStateException("Java double division IEEE parity failed: expected [-Infinity, NaN, Infinity], got ["
                        + divisionResults[0] + ", " + divisionResults[1] + ", " + divisionResults[2] + "]");
            }
            GpuDensityExpressionSmoke.VulkanBranchFixture loopFixture =
                    GpuDensityExpressionSmoke.boundedLoopFixture();
            float[] loopX = {-30_000_001.0f, -17.0f, 0.0f, 7.0f, 1_234_567.0f, 30_000_001.0f};
            float[] loopZeros = new float[loopX.length];
            float[] loopResults = ring.submitGraphRoots(loopFixture.graph(), loopX,
                    loopZeros, loopZeros).get(60, TimeUnit.SECONDS);
            for (int sample = 0; sample < loopX.length; sample++) {
                int xValue = (int) loopX[sample];
                double expected = loopFixture.javaFunction().compute(
                        new net.minecraft.world.level.levelgen.DensityFunction.SinglePointContext(xValue, 0, 0));
                if (Math.abs(loopResults[sample] - expected) > 0.5) {
                    throw new IllegalStateException("Bounded loop Java bytecode mismatch at sample " + sample
                            + ": expected " + expected + ", got " + loopResults[sample]);
                }
            }
            GpuDensityExpressionSmoke.VulkanBranchFixture conditionalLoopFixture =
                    GpuDensityExpressionSmoke.boundedConditionalLoopFixture();
            assertNumericFixture(ring, conditionalLoopFixture, "dynamic branch inside bounded loop",
                    new float[]{-17.0f, -3.0f, -1.0f, 0.0f, 1.0f, 3.0f, 17.0f},
                    new float[7], new float[7]);
            GpuDensityExpressionSmoke.VulkanBranchFixture switchLoopFixture =
                    GpuDensityExpressionSmoke.boundedSwitchLoopFixture();
            assertNumericFixture(ring, switchLoopFixture, "dynamic switch inside bounded loop",
                    new float[]{-7.0f, -3.0f, -1.0f, 0.0f, 1.0f, 2.0f, 8.0f},
                    new float[7], new float[7]);

            GpuDensityExpressionSmoke.VulkanBranchFixture powerFixture =
                    GpuDensityExpressionSmoke.dynamicPowerFixture();
            float[] powerX = {-2.0f, -2.0f, -2.0f, -2.0f, -2.0f, -2.0f};
            float[] powerY = {-6.0f, -1.0f, 0.0f, 1.0f, 4.0f, 6.0f};
            float[] powerZ = new float[powerX.length];
            float[] powerResults = ring.submitGraphRoots(powerFixture.graph(), powerX, powerY, powerZ)
                    .get(60, TimeUnit.SECONDS);
            for (int sample = 0; sample < powerX.length; sample++) {
                double expected = powerFixture.javaFunction().compute(
                        new net.minecraft.world.level.levelgen.DensityFunction.SinglePointContext(
                                (int) powerX[sample], (int) powerY[sample], 0));
                boolean mismatch = Double.isNaN(expected)
                        ? !Float.isNaN(powerResults[sample])
                        : Math.abs(powerResults[sample] - expected) > 0.005;
                if (mismatch) {
                    throw new IllegalStateException("Dynamic Java pow mismatch at sample " + sample
                            + ": expected " + expected + ", got " + powerResults[sample]);
                }
            }

            // One 2x2x2 grid exercises the GRID_INPUT shader variant and its
            // sample-to-coordinate indexing, not just arbitrary coordinates.
            ByteBuffer metadata = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder());
            metadata.putInt(-16).putInt(-8).putInt(32)
                    .putInt(2).putInt(2).putInt(2).putInt(1).putInt(1).flip();
            System.out.println("Vulkan CI smoke: dispatching terrain grid shader");
            float[] gridResults = ring.submitGraphRootsGrid(graph, metadata, 1, 8)
                    .get(60, TimeUnit.SECONDS);
            System.out.println("Vulkan CI smoke: terrain grid shader completed");
            assertConstantResults("terrain grid shader", gridResults, 8);

            GpuDensityExpressionSmoke.VulkanBranchFixture customGridFixture =
                    GpuDensityExpressionSmoke.interpolationMathFixture();
            float[] customGridResults = ring.submitGraphRootsGrid(
                    customGridFixture.graph(), metadata, 1, 8).get(60, TimeUnit.SECONDS);
            for (int sample = 0; sample < customGridResults.length; sample++) {
                int gridZ = sample % 2;
                int gridQ = sample / 2;
                int gridX = gridQ % 2;
                int gridY = gridQ / 2;
                int blockX = -16 + gridX;
                int blockY = -8 + gridY;
                int blockZ = 32 + gridZ;
                double expected = customGridFixture.javaFunction().compute(
                        new net.minecraft.world.level.levelgen.DensityFunction.SinglePointContext(
                                blockX, blockY, blockZ));
                if (Math.abs(customGridResults[sample] - expected) > 0.005) {
                    throw new IllegalStateException("Custom terrain-grid expression mismatch at sample " + sample
                            + ": expected " + expected + ", got " + customGridResults[sample]);
                }
            }

            // Exercise the cell-grid to block-density interpolation shader
            // used by the per-block terrain output path.
            float[] corners = new float[8];
            for (int cornerY = 0; cornerY < 2; cornerY++) {
                for (int cornerZ = 0; cornerZ < 2; cornerZ++) {
                    for (int cornerX = 0; cornerX < 2; cornerX++) {
                        int corner = (cornerY * 2 + cornerX) * 2 + cornerZ;
                        corners[corner] = cornerX * 0.25f + cornerY + cornerZ * 2.0f;
                    }
                }
            }
            ByteBuffer interpolationMetadata = GpuInterpolationBatch.metadata(
                    1, 1, 1, 1, 2, 2, 8, 8);
            System.out.println("Vulkan CI smoke: dispatching block interpolation shader");
            float[] blockResults = ring.submitInterpolation(corners, interpolationMetadata, 1, 8)
                    .get(60, TimeUnit.SECONDS);
            System.out.println("Vulkan CI smoke: block interpolation shader completed");
            assertInterpolatedResults(blockResults);

            // Minecraft terrain cells use different vertical and horizontal
            // sizes; verify four Y steps against the same two X/Z steps.
            ByteBuffer anisotropicMetadata = GpuInterpolationBatch.metadata(
                    1, 1, 1, 1, 2, 4, 8, 16);
            System.out.println("Vulkan CI smoke: dispatching anisotropic terrain interpolation");
            float[] anisotropicResults = ring.submitInterpolation(corners, anisotropicMetadata, 1, 16)
                    .get(60, TimeUnit.SECONDS);
            System.out.println("Vulkan CI smoke: anisotropic terrain interpolation completed");
            assertAnisotropicInterpolationResults(anisotropicResults);

            // Axis-coded linear values cannot expose interpolation-order or
            // float-contraction differences. Use non-associative corner data
            // and compare the production kernel bit-for-bit with the
            // Y-then-X-then-Z float sequence used by NoiseChunk's cache.
            float[] roundingCorners = {
                    -1.0e20f, -1.0e5f, -100.0f, 1.0e10f,
                    1.0e20f, -1.0e10f, -1.0e10f, 1.0e-5f
            };
            float[] roundingResults = ring.submitInterpolation(roundingCorners,
                            GpuInterpolationBatch.metadata(1, 1, 1, 1, 2, 2, 8, 8),
                            1, 8, 1, 1)
                    .get(60, TimeUnit.SECONDS);
            assertVanillaInterpolationRounding(roundingResults, roundingCorners);

            // Production chunks reuse shared lattice corners and interleave
            // twelve terrain roots at each grid sample. Exercise both strides
            // across multiple cells, including samples on cell boundaries.
            int rootStride = GpuTerrainRoots.COUNT;
            float[] terrainCorners = new float[3 * 3 * 3 * rootStride];
            for (int cornerY = 0; cornerY < 3; cornerY++) {
                for (int cornerX = 0; cornerX < 3; cornerX++) {
                    for (int cornerZ = 0; cornerZ < 3; cornerZ++) {
                        int sample = (cornerY * 3 + cornerX) * 3 + cornerZ;
                        int base = sample * rootStride;
                        java.util.Arrays.fill(terrainCorners, base, base + rootStride, 100.0f);
                        terrainCorners[base] = cornerX * 0.25f + cornerY + cornerZ * 2.0f;
                    }
                }
            }
            ByteBuffer terrainMetadata = GpuInterpolationBatch.metadata(
                    1, 2, 2, 2, 2, 4, 27, 128);
            System.out.println("Vulkan CI smoke: dispatching shared terrain lattice interpolation");
            float[] terrainResults = ring.submitInterpolation(terrainCorners, terrainMetadata,
                            1, 128, rootStride, 1)
                    .get(60, TimeUnit.SECONDS);
            System.out.println("Vulkan CI smoke: shared terrain lattice interpolation completed");
            assertSharedLatticeInterpolationResults(terrainResults);

            // Exercise the production sequence: evaluate cell corners, pass
            // interpolated Marker values into the block graph. Squaring the
            // marker after block interpolation proves nonlinear density work
            // runs after interpolation, rather than interpolating a composed
            // root at cell corners. Also verify marker-only children can skip.
            GpuDensityGraph markerGraph = GpuDensityExpressionSmoke.interpolatedMarkerGraphForVulkanSmoke();
            ByteBuffer markerGrid = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder());
            markerGrid.putInt(-32).putInt(-64).putInt(48)
                    .putInt(2).putInt(2).putInt(2).putInt(16).putInt(1).flip();
            ByteBuffer markerOutput = GpuInterpolationBatch.metadata(1, 1, 1, 1,
                    16, 1, 8, 256, -32, -64, 48);
            System.out.println("Vulkan CI smoke: dispatching block graph with interpolated marker inputs");
            GpuTerrainBatchResult markerTerrain = ring.submitGraphGridInterpolated(markerGraph,
                    markerGrid, 1, 8, markerOutput, 256).get(60, TimeUnit.SECONDS);
            GpuTerrainBlockResult markerBlocks = markerTerrain.blocks();
            if (markerBlocks.rootCount() != GpuTerrainRoots.COUNT
                    || !markerBlocks.contains(15, 0, 15)) {
                throw new IllegalStateException("Interpolated-marker block graph returned invalid output shape");
            }
            for (int blockZ = 0; blockZ < 16; blockZ++) {
                for (int blockX = 0; blockX < 16; blockX++) {
                    for (int root = 0; root < GpuTerrainRoots.COUNT; root++) {
                        float value = markerBlocks.valueAt(blockX, 0, blockZ, root);
                        float worldX = blockX - 32.0f;
                        float expected = root == 0 ? worldX * worldX
                                : root == 1 || root == 2 ? 8.0f : worldX + 3.0f;
                        if (Math.abs(value - expected) > 1.0e-5f) {
                            throw new IllegalStateException("Interpolated-marker block graph mismatch at "
                                    + blockX + ",0," + blockZ + " root " + root + ": expected "
                                    + expected + ", got " + value);
                        }
                    }
                }
            }
            System.out.println("Vulkan CI smoke: interpolated-marker block graph completed");

            // Match the production batch metadata writer: two chunks share
            // one dispatch but have independent corner, block, and world
            // coordinate origins. This catches local-offset reuse and stride
            // mistakes that a single-chunk fixture cannot expose.
            ByteBuffer multiMarkerGrid = ByteBuffer.allocateDirect(2 * 32)
                    .order(ByteOrder.nativeOrder());
            multiMarkerGrid.putInt(-32).putInt(-64).putInt(48)
                    .putInt(2).putInt(2).putInt(2).putInt(16).putInt(1);
            multiMarkerGrid.putInt(96).putInt(-32).putInt(-80)
                    .putInt(2).putInt(2).putInt(2).putInt(16).putInt(1).flip();
            ByteBuffer multiMarkerOutput = GpuInterpolationBatch.metadata(2, 1, 1, 1,
                    16, 1, 8, 256, -32, -64, 48);
            int secondOriginOffset = GpuInterpolationBatch.METADATA_BYTES + 32;
            multiMarkerOutput.putInt(secondOriginOffset, 96)
                    .putInt(secondOriginOffset + 4, -32)
                    .putInt(secondOriginOffset + 8, -80);
            System.out.println("Vulkan CI smoke: dispatching two chunks with distinct world origins");
            GpuTerrainBatchResult multiMarkerTerrain = ring.submitGraphGridInterpolated(markerGraph,
                    multiMarkerGrid, 2, 8, multiMarkerOutput, 256).get(60, TimeUnit.SECONDS);
            GpuTerrainBlockResult firstChunkBlocks = multiMarkerTerrain.blocks().slice(0, 256);
            GpuTerrainBlockResult secondChunkBlocks = multiMarkerTerrain.blocks().slice(256, 256);
            assertMarkerChunkCoordinates(firstChunkBlocks, -32);
            assertMarkerChunkCoordinates(secondChunkBlocks, 96);
            System.out.println("Vulkan CI smoke: two-chunk world-origin interpolation completed");

            // Production batches omit graph roots that did not pass parity.
            // Verify the compact output remains indexed by the original
            // router-root ID rather than by its packed output slot.
            int[] compactRoots = {GpuTerrainRoots.BARRIER, GpuTerrainRoots.FINAL_DENSITY};
            GpuTerrainBatchResult compactTerrain = ring.submitGraphGridInterpolated(markerGraph,
                    markerGrid.duplicate().order(ByteOrder.nativeOrder()), 1, 8,
                    markerOutput.duplicate().order(ByteOrder.nativeOrder()), 256, compactRoots)
                    .get(60, TimeUnit.SECONDS);
            GpuTerrainBlockResult compactBlocks = compactTerrain.blocks();
            if (compactBlocks.rootCount() != compactRoots.length
                    || compactBlocks.rootIndexAt(0) != GpuTerrainRoots.BARRIER
                    || compactBlocks.rootIndexAt(1) != GpuTerrainRoots.FINAL_DENSITY) {
                throw new IllegalStateException("Compact terrain block output lost its router-root mapping");
            }
            for (int blockX = 0; blockX < 16; blockX++) {
                float density = compactBlocks.valueAt(blockX, 0, 0, GpuTerrainRoots.FINAL_DENSITY);
                float barrier = compactBlocks.valueAt(blockX, 0, 0, GpuTerrainRoots.BARRIER);
                float worldX = blockX - 32.0f;
                float expectedDensity = worldX * worldX;
                if (Math.abs(density - expectedDensity) > 1.0e-5f || Math.abs(barrier - 8.0f) > 1.0e-5f) {
                    throw new IllegalStateException("Compact terrain block output mismatch at X=" + blockX);
                }
            }
            System.out.println("Vulkan CI smoke: parity-selected compact block output completed");

            GpuAquiferDecisionTable decisionTable = new GpuAquiferDecisionTable(-16, -64, 32, 16);
            net.minecraft.world.level.block.state.BlockState solidState =
                    net.minecraft.world.level.block.Blocks.STONE.defaultBlockState();
            int decisionIndex = 3 * 256 + 2 * 16 + 15;
            decisionTable.set(decisionIndex, solidState, -0.0, true);
            int defaultIndex = 3 * 256 + 3 * 16 + 15;
            decisionTable.set(defaultIndex, null, 1.25, false);
            if (decisionTable.indexOfMatch(-1, -61, 34, -0.0) != decisionIndex
                    || decisionTable.indexOfMatch(-1, -61, 34, 0.0) != -1
                    || decisionTable.state(decisionIndex) != solidState
                    || !decisionTable.schedulesFluidUpdate(decisionIndex)
                    || decisionTable.indexOfMatch(0, -61, 34, -0.0) != -1
                    || decisionTable.indexOfMatch(-1, -61, 35, 1.25) != defaultIndex
                    || decisionTable.state(defaultIndex) != null
                    || decisionTable.schedulesFluidUpdate(defaultIndex)) {
                throw new IllegalStateException("Primitive aquifer decision table lost exact density/state flags");
            }
            System.out.println("Vulkan CI smoke: compact aquifer decision table lookup completed");

            // Differentially exercise the first exact numeric component of
            // vanilla's aquifer block decision. Record fields and boundary
            // cases are shared with the production pressure input contract.
            int[][] pressureCases = {
                    {64, 63, 40, 0, 0},
                    {32, 48, 48, 0, 0},
                    {40, 48, 32, GpuAquiferPressure.WATER, GpuAquiferPressure.LAVA},
                    {40, 48, 32, 0, 0},
                    {-8, -12, 4, 0, 0},
                    {5, 0, 40, 0, 0}
            };
            float[] barriers = {0.25f, 0.0f, -0.75f, 0.5f, -0.2f, 1.0f};
            ByteBuffer pressureInput = ByteBuffer.allocateDirect(
                    pressureCases.length * GpuAquiferPressure.RECORD_BYTES).order(ByteOrder.nativeOrder());
            for (int i = 0; i < pressureCases.length; i++) {
                int[] c = pressureCases[i];
                pressureInput.putInt(c[0]).putInt(c[1]).putInt(c[2]).putInt(c[3]).putInt(c[4])
                        .putFloat(barriers[i]).putInt(0).putInt(0);
            }
            pressureInput.flip();
            System.out.println("Vulkan CI smoke: dispatching vanilla aquifer pressure kernel");
            float[] pressureValues = ring.submitAquiferPressure(pressureInput, pressureCases.length)
                    .get(60, TimeUnit.SECONDS);
            System.out.println("Vulkan CI smoke: aquifer pressure kernel completed");
            for (int i = 0; i < pressureCases.length; i++) {
                int[] c = pressureCases[i];
                float expected = GpuAquiferPressure.calculate(c[0], c[1], c[2], c[3], c[4], barriers[i]);
                if (Math.abs(pressureValues[i] - expected) > 1.0e-5f) {
                    throw new IllegalStateException("Aquifer pressure mismatch at case " + i
                            + ": expected " + expected + ", got " + pressureValues[i]);
                }
            }

            int queryCount = 4;
            int[][] queries = new int[queryCount][3];
            int[][][] centers = new int[queryCount][GpuAquiferNearestThree.CANDIDATES][3];
            for (int q = 0; q < queryCount; q++) {
                queries[q] = new int[]{q * 128 - 192, q * 17 - 31, 256 - q * 91};
                for (int i = 0; i < GpuAquiferNearestThree.CANDIDATES; i++) {
                    centers[q][i] = new int[]{queries[q][0] + ((i * 7) % 29) - 14,
                            queries[q][1] + ((i * 11) % 25) - 12,
                            queries[q][2] + ((i * 13) % 31) - 15};
                }
            }
            net.minecraft.world.level.levelgen.PositionalRandomFactory positionalRandom =
                    RandomSource.create(0x4750554e4f495345L).forkPositional();
            int[] negativeQuery = {-14, -1, -17};
            int[][] plannedCenters = GpuAquiferNearestThree.centersForQuery(
                    positionalRandom, negativeQuery);
            if (!java.util.Arrays.deepEquals(plannedCenters,
                    GpuAquiferNearestThree.centersForQuery(positionalRandom,
                            negativeQuery[0], negativeQuery[1], negativeQuery[2])))
                throw new IllegalStateException("Aquifer primitive-coordinate centers differ from array overload");
            int[][] expectedCenters = new int[GpuAquiferNearestThree.CANDIDATES][3];
            int baseX = Math.floorDiv(negativeQuery[0] - 5, 16);
            int baseY = Math.floorDiv(negativeQuery[1] + 1, 12);
            int baseZ = Math.floorDiv(negativeQuery[2] - 5, 16);
            int plannedIndex = 0;
            for (int gx = 0; gx <= 1; gx++) {
                for (int gy = -1; gy <= 1; gy++) {
                    for (int gz = 0; gz <= 1; gz++) {
                        RandomSource random = positionalRandom.at(baseX + gx, baseY + gy, baseZ + gz);
                        expectedCenters[plannedIndex++] = new int[]{(baseX + gx) * 16 + random.nextInt(10),
                                (baseY + gy) * 12 + random.nextInt(9),
                                (baseZ + gz) * 16 + random.nextInt(10)};
                    }
                }
            }
            if (!java.util.Arrays.deepEquals(plannedCenters, expectedCenters))
                throw new IllegalStateException("Aquifer positional-random neighborhood mismatch");
            int[][] plannedC2meCenters = GpuAquiferNearestThree.centersForC2meQuery(
                    positionalRandom, negativeQuery);
            if (!java.util.Arrays.deepEquals(plannedC2meCenters,
                    GpuAquiferNearestThree.centersForC2meQuery(positionalRandom,
                            negativeQuery[0], negativeQuery[1], negativeQuery[2])))
                throw new IllegalStateException("C2ME primitive-coordinate centers differ from array overload");
            int[][] expectedC2meCenters = new int[GpuAquiferNearestThree.CANDIDATES][3];
            int c2meIndex = 0;
            for (int gy = -1; gy <= 1; gy++) {
                for (int gz = 0; gz <= 1; gz++) {
                    for (int gx = 0; gx <= 1; gx++) {
                        RandomSource random = positionalRandom.at(baseX + gx, baseY + gy, baseZ + gz);
                        expectedC2meCenters[c2meIndex++] = new int[]{(baseX + gx) * 16 + random.nextInt(10),
                                (baseY + gy) * 12 + random.nextInt(9), (baseZ + gz) * 16 + random.nextInt(10)};
                    }
                }
            }
            if (!java.util.Arrays.deepEquals(plannedC2meCenters, expectedC2meCenters))
                throw new IllegalStateException("C2ME aquifer positional-random scan order mismatch");
            dev.gpunoise.GpuAquiferDecisionData.NeighborhoodCache neighborhoodCache =
                    new dev.gpunoise.GpuAquiferDecisionData.NeighborhoodCache(1);
            int[][][] cachedNeighborhoods = new int[128][][];
            for (int i = 0; i < cachedNeighborhoods.length; i++) {
                cachedNeighborhoods[i] = new int[][]{{i, -i, i * 3}};
                neighborhoodCache.put(i - 64, i * 17, 63 - i, cachedNeighborhoods[i]);
            }
            for (int i = 0; i < cachedNeighborhoods.length; i++) {
                if (neighborhoodCache.get(i - 64, i * 17, 63 - i) != cachedNeighborhoods[i])
                    throw new IllegalStateException("Aquifer primitive neighborhood cache lost an entry during growth");
            }
            if (neighborhoodCache.get(Integer.MIN_VALUE, 0, Integer.MAX_VALUE) != null)
                throw new IllegalStateException("Aquifer primitive neighborhood cache returned a false match");
            GpuAquiferDecisionData.FluidStatusCache fluidStatusCache =
                    new GpuAquiferDecisionData.FluidStatusCache(1);
            net.minecraft.world.level.levelgen.Aquifer.FluidStatus[] cachedStatuses =
                    new net.minecraft.world.level.levelgen.Aquifer.FluidStatus[128];
            long[] cachedPositions = new long[cachedStatuses.length];
            for (int i = 0; i < cachedStatuses.length; i++) {
                cachedPositions[i] = i * 0x9E3779B97F4A7C15L;
                cachedStatuses[i] = new net.minecraft.world.level.levelgen.Aquifer.FluidStatus(
                        i - 64, net.minecraft.world.level.block.Blocks.WATER.defaultBlockState());
                fluidStatusCache.put(cachedPositions[i], cachedStatuses[i]);
            }
            for (int i = 0; i < cachedStatuses.length; i++) {
                if (fluidStatusCache.get(cachedPositions[i]) != cachedStatuses[i])
                    throw new IllegalStateException("Aquifer fluid status cache lost an entry during growth");
            }
            if (fluidStatusCache.get(Long.MIN_VALUE) != null)
                throw new IllegalStateException("Aquifer fluid status cache returned a false match");
            // Four equal-distance centers pin vanilla's inclusive tie order
            // across all three nearest slots.
            centers[0][0] = new int[]{queries[0][0] + 1, queries[0][1], queries[0][2]};
            centers[0][1] = new int[]{queries[0][0] - 1, queries[0][1], queries[0][2]};
            centers[0][2] = new int[]{queries[0][0], queries[0][1] + 1, queries[0][2]};
            centers[0][3] = new int[]{queries[0][0], queries[0][1], queries[0][2] + 1};
            for (int i = 4; i < GpuAquiferNearestThree.CANDIDATES; i++)
                centers[0][i] = new int[]{queries[0][0] + 100 + i,
                        queries[0][1] + 100 + i, queries[0][2] + 100 + i};
            int[] expectedTieOrder = {3, 2, 1, 0, 1, 1, 1, 1};
            if (!java.util.Arrays.equals(expectedTieOrder,
                    GpuAquiferNearestThree.select(queries[0], centers[0])))
                throw new IllegalStateException("Aquifer equal-distance center order differs from vanilla");
            ByteBuffer nearestInput = GpuAquiferNearestThree.pack(queries, centers);
            List<AquiferQuery> queuedAquiferQueries = new java.util.ArrayList<>(queryCount);
            for (int query = 0; query < queryCount; query++) {
                queuedAquiferQueries.add(new AquiferQuery(query, queries[query][0], queries[query][1],
                        queries[query][2], 0.0, 0.0, false, centers[query]));
            }
            if (!nearestInput.equals(GpuAquiferNearestThree.pack(queuedAquiferQueries, 0, queryCount))) {
                throw new IllegalStateException("queued aquifer queries changed nearest-center packing");
            }
            AquiferQueries columnarQueries = new AquiferQueries(queryCount);
            for (int query = 0; query < queryCount; query++) {
                columnarQueries.add(query, queries[query][0], queries[query][1], queries[query][2],
                        query * 0.125, -query * 0.25, (query & 1) != 0, centers[query]);
            }
            if (!nearestInput.equals(GpuAquiferNearestThree.pack(columnarQueries, 0, queryCount))) {
                throw new IllegalStateException("columnar aquifer staging changed nearest-center packing");
            }
            ByteBuffer queuedGridMetadata = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder());
            queuedGridMetadata.putInt(-16).putInt(-8).putInt(32)
                    .putInt(2).putInt(2).putInt(2).putInt(1).putInt(1).flip();
            var queuedGrid = ring.submitGraphRootsGrid(graph, queuedGridMetadata, 1, 8);
            // Queue a fence-waiting production terrain dispatch behind the
            // asynchronous graph-root dispatch. Both reuse the engine's one
            // command buffer and fence, so the ring must not let the terrain
            // dispatch overwrite graph work before its completion is polled.
            var overlappingTerrain = ring.submitGraphGridInterpolated(markerGraph,
                    markerGrid.duplicate().order(ByteOrder.nativeOrder()), 1, 8,
                    markerOutput.duplicate().order(ByteOrder.nativeOrder()), 256);
            System.out.println("Vulkan CI smoke: dispatching four-center aquifer selection");
            var nearestFuture = ring.submitAquiferNearestThree(nearestInput, queryCount);
            GpuTerrainBlockResult overlappingBlocks = overlappingTerrain.get(60, TimeUnit.SECONDS).blocks();
            for (int blockX = 0; blockX < 16; blockX++) {
                float actual = overlappingBlocks.valueAt(blockX, 0, 0, GpuTerrainRoots.FINAL_DENSITY);
                if (Math.abs(actual - blockX * blockX) > 1.0e-5f) {
                    throw new IllegalStateException("Queued terrain output was corrupted by in-flight graph work at X="
                            + blockX + ": expected " + (blockX * blockX) + ", got " + actual);
                }
            }
            GpuAquiferSubstance.Input weightedThirdPressureCase =
                    new GpuAquiferSubstance.Input(-0.02, -0.3748834, 22,
                            23, 23, 63, 23, 90, 113, 137, 150,
                            GpuAquiferSubstance.WATER, GpuAquiferSubstance.WATER,
                            GpuAquiferSubstance.WATER, GpuAquiferSubstance.WATER,
                            0, 0, false, false, false, 417);
            GpuAquiferSubstance.Input positiveDensityThresholdCase =
                    new GpuAquiferSubstance.Input(0.001, 0.25, 22,
                            23, 23, 63, 23, 90, 113, 137, 150,
                            GpuAquiferSubstance.WATER, GpuAquiferSubstance.WATER,
                            GpuAquiferSubstance.WATER, GpuAquiferSubstance.WATER,
                            0, 0, false, false, false, 418);
            GpuAquiferSubstance.Input positiveDensityMaterialCase =
                    new GpuAquiferSubstance.Input(0.2, 0.0, 22,
                            0, 0, 0, 0, 0, 0, 0, 0,
                            GpuAquiferSubstance.OTHER, GpuAquiferSubstance.OTHER,
                            GpuAquiferSubstance.OTHER, GpuAquiferSubstance.OTHER,
                            0, 0, false, false, false, 0);
            GpuAquiferSubstance.Input[] substanceCases = {
                    new GpuAquiferSubstance.Input(0.2, 0.25, 50,
                            40, 24, 12, 1, 10, 20,
                            GpuAquiferSubstance.OTHER, GpuAquiferSubstance.OTHER,
                            GpuAquiferSubstance.OTHER, 0, false, false, 400),
                    new GpuAquiferSubstance.Input(-0.2, 0.25, 50,
                            40, 24, 12, 1, 10, 20,
                            GpuAquiferSubstance.OTHER, GpuAquiferSubstance.OTHER,
                            GpuAquiferSubstance.OTHER, 77, true, false, 401),
                    new GpuAquiferSubstance.Input(-0.0001, Double.NaN, 50,
                            40, 24, 12, 1, 10, 20,
                            GpuAquiferSubstance.OTHER, GpuAquiferSubstance.OTHER,
                            GpuAquiferSubstance.OTHER, 78, true, false, 410),
                    new GpuAquiferSubstance.Input(-0.2, 0.25, 50,
                            40, 24, 12, 10, 35, 60,
                            GpuAquiferSubstance.OTHER, GpuAquiferSubstance.OTHER,
                            GpuAquiferSubstance.OTHER, 0, false, false, 402),
                    // Vanilla schedules a fluid update for a non-positive
                    // similarity down to 1 - (12^2 - 10^2) / 25 == -0.76.
                    new GpuAquiferSubstance.Input(-0.2, 0.25, 50,
                            40, 24, 12, 10, 53, 60,
                            GpuAquiferSubstance.OTHER, GpuAquiferSubstance.OTHER,
                            GpuAquiferSubstance.OTHER, 0, false, false, 412),
                    new GpuAquiferSubstance.Input(-0.2, 0.25, 50,
                            40, 24, 12, 10, 55, 60,
                            GpuAquiferSubstance.OTHER, GpuAquiferSubstance.OTHER,
                            GpuAquiferSubstance.OTHER, 0, false, false, 413),
                    new GpuAquiferSubstance.Input(-0.2, 0.25, 50,
                            40, 24, 12, 0, 10, 35, 60, 0,
                            GpuAquiferSubstance.OTHER, GpuAquiferSubstance.OTHER,
                            GpuAquiferSubstance.OTHER, GpuAquiferSubstance.OTHER,
                            1, 0, false, false, true, 414),
                    new GpuAquiferSubstance.Input(-0.2, 0.25, 50,
                            40, 24, 12, 0, 10, 35, 60, 0,
                            GpuAquiferSubstance.OTHER, GpuAquiferSubstance.OTHER,
                            GpuAquiferSubstance.OTHER, GpuAquiferSubstance.OTHER,
                            0, 0, false, false, true, 415),
                    new GpuAquiferSubstance.Input(-0.2, 0.25, 50,
                            40, 40, 40, 40, 1, 2, 3, 4,
                            GpuAquiferSubstance.OTHER, GpuAquiferSubstance.OTHER,
                            GpuAquiferSubstance.OTHER, GpuAquiferSubstance.OTHER,
                            0b111111, 0, false, false, true, 416),
                    new GpuAquiferSubstance.Input(-0.2, 0.25, 50,
                            40, 24, 12, 10, 11, 12,
                            GpuAquiferSubstance.OTHER, GpuAquiferSubstance.OTHER,
                            GpuAquiferSubstance.OTHER, 0, false, false, 403),
                    new GpuAquiferSubstance.Input(-0.2, 0.25, 50,
                            40, 24, 12, 10, 11, 12,
                            GpuAquiferSubstance.WATER, GpuAquiferSubstance.OTHER,
                            GpuAquiferSubstance.OTHER, 0, false, true, 404),
                    new GpuAquiferSubstance.Input(-0.1, 0.0, 33,
                            40, 24, 10, 0, 1, 2,
                            GpuAquiferSubstance.OTHER, GpuAquiferSubstance.OTHER,
                            GpuAquiferSubstance.OTHER, 0, false, false, 405),
                    new GpuAquiferSubstance.Input(-8.32, 0.0, 33,
                            40, 24, 10, 0, 1, 2,
                            GpuAquiferSubstance.OTHER, GpuAquiferSubstance.OTHER,
                            GpuAquiferSubstance.OTHER, 0, false, false, 406),
                    new GpuAquiferSubstance.Input(-0.25, 0.3, 30,
                            30, 30, 30, 10, 11, 12,
                            GpuAquiferSubstance.OTHER, GpuAquiferSubstance.OTHER,
                            GpuAquiferSubstance.OTHER, 0, false, false, 407),
                    // Nearest/third is the deciding pressure pair.
                    new GpuAquiferSubstance.Input(-0.5, 0.0, 30,
                            30, 30, 30, 1, 2, 3,
                            GpuAquiferSubstance.WATER, GpuAquiferSubstance.OTHER,
                            GpuAquiferSubstance.LAVA, 0, false, false, 408),
                    // Vanilla weights the second/third pressure pair by both
                    // nearest similarities. Omitting similarity01 incorrectly
                    // turns this retained water result into air/default.
                    weightedThirdPressureCase,
                    positiveDensityMaterialCase,
                    positiveDensityThresholdCase,
                    // Second/third is the deciding pressure pair.
                    new GpuAquiferSubstance.Input(-0.5, 0.0, 30,
                            30, 30, 30, 1, 2, 3,
                            GpuAquiferSubstance.OTHER, GpuAquiferSubstance.WATER,
                            GpuAquiferSubstance.LAVA, 0, false, false, 409)
            };
            int substanceSliceCount = substanceCases.length - 2;
            ByteBuffer substanceSlice = GpuAquiferSubstance.pack(
                    List.of(substanceCases), 1, substanceSliceCount);
            GpuAquiferSubstance.Input[] expectedSubstanceSlice = java.util.Arrays.copyOfRange(
                    substanceCases, 1, substanceCases.length - 1);
            if (!substanceSlice.equals(GpuAquiferSubstance.pack(expectedSubstanceSlice))) {
                throw new IllegalStateException("aquifer substance range packing changed record order or values");
            }
            GpuAquiferSubstance.Input[] stagedSubstances = {
                    new GpuAquiferSubstance.Input(0.25, 0.0, 7,
                            0, 0, 0, 0, 0, 0, 0, 0,
                            GpuAquiferSubstance.OTHER, GpuAquiferSubstance.OTHER,
                            GpuAquiferSubstance.OTHER, GpuAquiferSubstance.OTHER,
                            0, 0, false, false, false, 0),
                    new GpuAquiferSubstance.Input(-1.0, 0.0, -4,
                            0, 0, 0, 0, 0, 0, 0, 0,
                            GpuAquiferSubstance.OTHER, GpuAquiferSubstance.OTHER,
                            GpuAquiferSubstance.OTHER, GpuAquiferSubstance.OTHER,
                            0, 0, true, false, true, 0),
                    new GpuAquiferSubstance.Input(-0.4, 0.125, 19,
                            30, 21, 12, 8, 2, 7, 13, 24,
                            GpuAquiferSubstance.WATER, GpuAquiferSubstance.OTHER,
                            GpuAquiferSubstance.LAVA, GpuAquiferSubstance.WATER,
                            0b101101, 0, false, true, false, 0)
            };
            GpuAquiferSubstance.BatchWriter substanceWriter = new GpuAquiferSubstance.BatchWriter(3, 2);
            substanceWriter.addPositiveDensity(0.25, 7, false);
            substanceWriter.addDirectGlobalFluid(-1.0, -4, true);
            List<ByteBuffer> stagedSubstanceBatches = new ArrayList<>(substanceWriter.drain());
            if (stagedSubstanceBatches.size() != 1
                    || stagedSubstanceBatches.get(0).remaining() != 2 * GpuAquiferSubstance.RECORD_BYTES) {
                throw new IllegalStateException("aquifer substance drain did not publish the completed rows");
            }
            GpuAquiferSubstance.Input aquiferSubstance = stagedSubstances[2];
            substanceWriter.addAquifer(aquiferSubstance.density(), aquiferSubstance.barrier(),
                    aquiferSubstance.y(), aquiferSubstance.level0(), aquiferSubstance.level1(),
                    aquiferSubstance.level2(), aquiferSubstance.level3(), aquiferSubstance.distance0(),
                    aquiferSubstance.distance1(), aquiferSubstance.distance2(), aquiferSubstance.distance3(),
                    aquiferSubstance.kind0(), aquiferSubstance.kind1(), aquiferSubstance.kind2(),
                    aquiferSubstance.kind3(), aquiferSubstance.statusEqualityMask(),
                    aquiferSubstance.belowIsLava(), aquiferSubstance.c2meAquifer());
            ByteBuffer stagedRecords = ByteBuffer.allocateDirect(3 * GpuAquiferSubstance.RECORD_BYTES)
                    .order(java.nio.ByteOrder.nativeOrder());
            List<ByteBuffer> trailingSubstanceBatches = substanceWriter.finish();
            if (trailingSubstanceBatches.size() != 1
                    || trailingSubstanceBatches.get(0).remaining() != GpuAquiferSubstance.RECORD_BYTES) {
                throw new IllegalStateException("aquifer substance writer did not accept rows after drain");
            }
            stagedSubstanceBatches.addAll(trailingSubstanceBatches);
            for (ByteBuffer batch : stagedSubstanceBatches) stagedRecords.put(batch);
            stagedRecords.flip();
            if (!stagedRecords.equals(GpuAquiferSubstance.pack(stagedSubstances))) {
                throw new IllegalStateException("streamed aquifer substance records changed layout or values");
            }
            for (ByteBuffer batch : stagedSubstanceBatches) GpuAquiferInputBufferPool.release(batch);
            long reusedBefore = GpuAquiferInputBufferPool.reusedBufferCount();
            GpuAquiferSubstance.BatchWriter reuseWriter = new GpuAquiferSubstance.BatchWriter(1, 2);
            reuseWriter.add(stagedSubstances[0]);
            List<ByteBuffer> reusedBatches = reuseWriter.finish();
            if (reusedBatches.size() != 1
                    || !reusedBatches.get(0).equals(GpuAquiferSubstance.pack(
                            new GpuAquiferSubstance.Input[]{stagedSubstances[0]}))
                    || GpuAquiferInputBufferPool.reusedBufferCount() <= reusedBefore) {
                throw new IllegalStateException("aquifer input slab pool failed to reuse a compatible buffer");
            }
            for (ByteBuffer batch : reusedBatches) GpuAquiferInputBufferPool.release(batch);
            var substanceFuture = ring.submitAquiferSubstance(
                    GpuAquiferSubstance.pack(substanceCases), substanceCases.length);
            GpuAquiferFluidStatus.Input[] fluidStatusCases = aquiferFluidStatusCases();
            int[] fluidStatusResults = ring.submitAquiferFluidStatus(
                    GpuAquiferFluidStatus.pack(fluidStatusCases), fluidStatusCases.length)
                    .get(60, TimeUnit.SECONDS);
            for (int row = 0; row < fluidStatusCases.length; row++) {
                int[] expected = GpuAquiferFluidStatus.evaluate(fluidStatusCases[row]);
                int base = row * GpuAquiferFluidStatus.OUTPUT_INTS;
                for (int field = 0; field < GpuAquiferFluidStatus.OUTPUT_INTS; field++) {
                    if (fluidStatusResults[base + field] != expected[field]) {
                        throw new IllegalStateException("C2ME OCL fluid-status mismatch at row " + row
                                + ", field " + field + ", expected=" + expected[field]
                                + ", actual=" + fluidStatusResults[base + field]);
                    }
                }
            }
            int[][] oneNearestQuery = {{0, 0, 0}};
            int[][] oneNearestCenters = new int[GpuAquiferNearestThree.CANDIDATES][3];
            for (int center = 0; center < oneNearestCenters.length; center++) {
                oneNearestCenters[center][0] = center * 16;
                oneNearestCenters[center][1] = (center % 3 - 1) * 12;
                oneNearestCenters[center][2] = (center / 3) * 16;
            }
            ByteBuffer combinedNearest = GpuAquiferNearestThree.pack(
                    oneNearestQuery, new int[][][]{oneNearestCenters});
            GpuAquiferPrefillResult combinedAquifer = ring.submitAquiferPrefill(
                    combinedNearest, 1, GpuAquiferFluidStatus.pack(fluidStatusCases),
                    fluidStatusCases.length).get(60, TimeUnit.SECONDS);
            int[] expectedNearest = GpuAquiferNearestThree.select(oneNearestQuery[0], oneNearestCenters);
            if (!java.util.Arrays.equals(expectedNearest, combinedAquifer.nearestRows())) {
                throw new IllegalStateException("combined C2ME aquifer nearest-center parity mismatch");
            }
            for (int index = 0; index < fluidStatusResults.length; index++) {
                if (fluidStatusResults[index] != combinedAquifer.fluidStatusRows()[index]) {
                    throw new IllegalStateException("combined C2ME aquifer prefill changed status output at int " + index);
                }
            }
            System.out.println("Vulkan CI smoke: co-submitted C2ME OCL aquifer prefill parity completed");
            System.out.println("Vulkan CI smoke: C2ME OCL aquifer fluid-status prefill parity completed");
            // The Tectonic integration profile can produce 576-block-high
            // chunks. Exercise the largest observed volume through both
            // decision kernels in one dispatch each.
            int chunkBatchSize = 16 * 16 * 576;
            if (chunkBatchSize > GpuAquiferNearestThree.MAX_QUERIES) {
                throw new IllegalStateException("Aquifer nearest-center capacity is smaller than a 384-block chunk");
            }
            int[][] nearestChunkQueries = new int[chunkBatchSize][];
            int[][][] nearestChunkCenters = new int[chunkBatchSize][][];
            java.util.Arrays.fill(nearestChunkQueries, queries[0]);
            java.util.Arrays.fill(nearestChunkCenters, centers[0]);
            ByteBuffer compactNearestBatch = GpuAquiferNearestThree.pack(nearestChunkQueries, nearestChunkCenters);
            int expectedCompactBytes = GpuAquiferNearestThree.HEADER_BYTES
                    + chunkBatchSize * GpuAquiferNearestThree.QUERY_RECORD_BYTES
                    + GpuAquiferNearestThree.CENTER_RECORD_BYTES;
            if (compactNearestBatch.remaining() != expectedCompactBytes) {
                throw new IllegalStateException("Shared aquifer neighborhoods were not compacted in the GPU payload");
            }
            var chunkNearestFuture = ring.submitAquiferNearestThree(compactNearestBatch, chunkBatchSize);
            if (chunkBatchSize > GpuAquiferSubstance.MAX_QUERIES) {
                throw new IllegalStateException("Aquifer substance capacity is smaller than a 384-block chunk");
            }
            GpuAquiferSubstance.Input[] chunkBatch = new GpuAquiferSubstance.Input[chunkBatchSize];
            java.util.Arrays.fill(chunkBatch, positiveDensityMaterialCase);
            var chunkSubstanceFuture = ring.submitAquiferSubstance(
                    GpuAquiferSubstance.pack(chunkBatch), chunkBatch.length);
            int[] nearest = nearestFuture.get(60, TimeUnit.SECONDS);
            int[] chunkNearestResults = chunkNearestFuture.get(60, TimeUnit.SECONDS);
            int[] substanceResults = substanceFuture.get(60, TimeUnit.SECONDS);
            int[] chunkSubstanceResults = chunkSubstanceFuture.get(60, TimeUnit.SECONDS);
            assertConstantResults("queued terrain grid", queuedGrid.get(60, TimeUnit.SECONDS), 8);
            System.out.println("Vulkan CI smoke: four-center aquifer selection completed");
            int[] expectedChunkNearest = GpuAquiferNearestThree.select(queries[0], centers[0]);
            if (chunkNearestResults.length != chunkBatchSize * GpuAquiferNearestThree.OUTPUT_INTS) {
                throw new IllegalStateException("Chunk-sized nearest-center batch returned truncated output");
            }
            for (int i = 0; i < chunkBatchSize; i++) {
                int base = i * GpuAquiferNearestThree.OUTPUT_INTS;
                for (int field = 0; field < GpuAquiferNearestThree.OUTPUT_INTS; field++) {
                    if (chunkNearestResults[base + field] != expectedChunkNearest[field]) {
                        throw new IllegalStateException("Chunk-sized nearest-center mismatch at row " + i
                                + ", field " + field);
                    }
                }
            }
            System.out.println("Vulkan CI smoke: full-chunk nearest-center batch completed (147,456 blocks, one dispatch)");
            for (int q = 0; q < queryCount; q++) {
                int[] expected = GpuAquiferNearestThree.select(queries[q], centers[q]);
                for (int field = 0; field < GpuAquiferNearestThree.OUTPUT_INTS; field++) {
                    int actual = nearest[q * GpuAquiferNearestThree.OUTPUT_INTS + field];
                    if (actual != expected[field]) {
                        throw new IllegalStateException("Aquifer four-center selection mismatch at query " + q
                                + ", field " + field + ": expected " + expected[field] + ", got " + actual);
                    }
                }
            }
            System.out.println("Vulkan CI smoke: aquifer substance decisions completed");
            if (chunkSubstanceResults.length != chunkBatchSize * GpuAquiferSubstance.OUTPUT_INTS) {
                throw new IllegalStateException("Chunk-sized aquifer batch returned a truncated output");
            }
            for (int i = 0; i < chunkBatchSize; i++) {
                if (chunkSubstanceResults[i] != GpuAquiferSubstance.packOutput(
                        GpuAquiferSubstance.RETURN_DEFAULT, false)) {
                    throw new IllegalStateException("Chunk-sized aquifer decision mismatch at row " + i);
                }
            }
            System.out.println("Vulkan CI smoke: full-chunk aquifer batch completed (147,456 blocks, one dispatch)");
            for (int i = 0; i < substanceCases.length; i++) {
                int[] expected = GpuAquiferSubstance.evaluate(substanceCases[i]);
                int expectedPacked = GpuAquiferSubstance.packOutput(expected[0], expected[2] != 0);
                int actualPacked = substanceResults[i];
                if (!GpuAquiferSubstance.isValidPackedOutput(actualPacked)
                        || actualPacked != expectedPacked) {
                    throw new IllegalStateException("Aquifer substance mismatch at case " + i
                            + ": expected packed " + expectedPacked + ", got " + actualPacked);
                }
            }
            int[] weightedThirdPressure = GpuAquiferSubstance.evaluate(weightedThirdPressureCase);
            int weightedThirdPressureIndex = java.util.Arrays.asList(substanceCases)
                    .indexOf(weightedThirdPressureCase);
            if (weightedThirdPressure[0] != GpuAquiferSubstance.RETURN_STATE
                    || weightedThirdPressure[1] != 417 || weightedThirdPressure[2] != 1) {
                throw new IllegalStateException("Vanilla second/third pressure contribution lost its nearest-similarity weight");
            }
            if (weightedThirdPressureIndex < 0
                    || substanceResults[weightedThirdPressureIndex] != GpuAquiferSubstance.packOutput(
                    GpuAquiferSubstance.RETURN_STATE, true)) {
                throw new IllegalStateException("Packed aquifer output lost the weighted second/third pressure decision");
            }
            if (GpuAquiferSubstance.evaluate(positiveDensityThresholdCase)[0]
                    != GpuAquiferSubstance.CPU_FALLBACK) {
                throw new IllegalStateException("Positive density near zero must use exact Java evaluation");
            }
            int[] positiveDensityMaterial = GpuAquiferSubstance.evaluate(positiveDensityMaterialCase);
            if (positiveDensityMaterial[0] != GpuAquiferSubstance.RETURN_DEFAULT
                    || positiveDensityMaterial[1] != 0 || positiveDensityMaterial[2] != 0) {
                throw new IllegalStateException("Positive-density terrain must select vanilla's default material result");
            }
        }

        System.out.println("Vulkan CI smoke passed on " + device.name()
                + ": coordinate and terrain grids passed; interpolation preserved axis-coded values across isotropic cells, anisotropic cells, shared 12-root terrain lattices, and non-associative Y-X-Z float rounding; aquifer pressure, four-center selection, and vanilla/C2ME substance decisions matched Java reference cases.");
    }

    private static void verifyChunkPipelineCancellation() throws Exception {
        GpuChunkPipelineGate queuedGate = new GpuChunkPipelineGate(1);
        CompletableFuture<Void> holdFirst = new CompletableFuture<>();
        AtomicInteger started = new AtomicInteger();
        CompletableFuture<Void> first = queuedGate.submit(() -> {
            started.incrementAndGet();
            return holdFirst;
        });
        CompletableFuture<Void> cancelledQueued = queuedGate.submit(() -> {
            started.incrementAndGet();
            return CompletableFuture.completedFuture(null);
        });
        if (!cancelledQueued.cancel(false)) {
            throw new IllegalStateException("queued GPU terrain request could not be cancelled");
        }
        holdFirst.complete(null);
        first.get(5, TimeUnit.SECONDS);
        if (started.get() != 1) {
            throw new IllegalStateException("cancelled queued GPU terrain request still started");
        }
        queuedGate.submit(() -> CompletableFuture.completedFuture(null)).get(5, TimeUnit.SECONDS);

        GpuChunkPipelineGate activeGate = new GpuChunkPipelineGate(1);
        CompletableFuture<Void> activeStage = new CompletableFuture<>();
        CompletableFuture<Void> active = activeGate.submit(() -> activeStage, activeStage::cancel);
        if (!active.cancel(true) || !activeStage.isCancelled()) {
            throw new IllegalStateException("running GPU terrain request did not receive cancellation");
        }
        AtomicInteger resumed = new AtomicInteger();
        activeGate.submit(() -> {
            resumed.incrementAndGet();
            return CompletableFuture.completedFuture(null);
        }).get(5, TimeUnit.SECONDS);
        if (resumed.get() != 1) {
            throw new IllegalStateException("GPU terrain gate did not release its cancelled active slot");
        }
        System.out.println("Vulkan CI smoke: chunk pipeline cancellation verified");
    }

    /** Exercise independent command buffers/fences even on Mesa's CPU Vulkan device. */
    private static VulkanSubmissionRing createTwoSlotSmokeRing(GpuDevice device) throws Exception {
        String configuredSlots = System.getProperty("gpunoise.submissionSlots");
        System.setProperty("gpunoise.submissionSlots", "2");
        try {
            return VulkanBootstrapThread.call(() -> new VulkanSubmissionRing(device));
        } finally {
            if (configuredSlots == null) System.clearProperty("gpunoise.submissionSlots");
            else System.setProperty("gpunoise.submissionSlots", configuredSlots);
        }
    }

    private static void verifyAquiferStatusRegionCache() {
        Object graph = new Object();
        Object fluidPicker = new Object();
        Aquifer.FluidStatus expected = new Aquifer.FluidStatus(63, Blocks.WATER.defaultBlockState());
        long position = net.minecraft.core.BlockPos.asLong(-48, 32, 80);
        GpuAquiferStatusRegionCache.clear();
        GpuAquiferStatusRegionCache.put(graph, fluidPicker, position, expected);
        if (GpuAquiferStatusRegionCache.get(graph, fluidPicker, position) != expected
                || GpuAquiferStatusRegionCache.get(new Object(), fluidPicker, position) != null
                || GpuAquiferStatusRegionCache.get(graph, new Object(), position) != null
                || GpuAquiferStatusRegionCache.get(graph, fluidPicker, position + 1L) != null) {
            throw new IllegalStateException("aquifer region cache did not preserve world, picker, and position identity");
        }
        Object otherGraph = new Object();
        GpuAquiferStatusRegionCache.put(otherGraph, fluidPicker, position, expected);
        if (!GpuAquiferStatusRegionCache.disable(graph, fluidPicker)
                || GpuAquiferStatusRegionCache.get(graph, fluidPicker, position) != null
                || GpuAquiferStatusRegionCache.get(otherGraph, fluidPicker, position) != expected) {
            throw new IllegalStateException("aquifer region cache could not isolate a disabled graph/picker pair");
        }
        GpuAquiferStatusRegionCache.putSurface(graph, fluidPicker, -64, 112, 73);
        GpuAquiferStatusRegionCache.putSurface(otherGraph, fluidPicker, -64, 112, 75);
        if (GpuAquiferStatusRegionCache.getSurface(graph, fluidPicker, -64, 112) != 73
                || GpuAquiferStatusRegionCache.getSurface(otherGraph, fluidPicker, -64, 112) != 75
                || GpuAquiferStatusRegionCache.getSurface(graph, new Object(), -64, 112)
                        != Integer.MIN_VALUE
                || !GpuAquiferStatusRegionCache.disableSurface(graph, fluidPicker)
                || GpuAquiferStatusRegionCache.getSurface(graph, fluidPicker, -64, 112)
                        != Integer.MIN_VALUE
                || GpuAquiferStatusRegionCache.getSurface(otherGraph, fluidPicker, -64, 112) != 75) {
            throw new IllegalStateException("aquifer region surface cache failed identity or invalidation checks");
        }
        GpuAquiferStatusRegionCache.clear();
        if (GpuAquiferStatusRegionCache.get(graph, fluidPicker, position) != null) {
            throw new IllegalStateException("aquifer region cache clear retained a fluid status");
        }
        System.out.println("Vulkan CI smoke: shared aquifer region-cache identity checks passed");
    }

    private static void verifyTerrainParityRules() {
        if (GpuTerrainParity.error(Float.NaN, Float.NaN) != 0.0f
                || !Float.isInfinite(GpuTerrainParity.error(Float.NaN, 0.0f))
                || !Float.isInfinite(GpuTerrainParity.error(Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY))
                || !Float.isInfinite(GpuTerrainParity.error(Float.POSITIVE_INFINITY, Float.MAX_VALUE))
                || !Float.isInfinite(GpuTerrainParity.decisionError(-1.0e-4f, 1.0e-4f))
                || GpuTerrainParity.decisionError(1.0e-4f, 1.0e-4f + 1.0e-6f) > 2.0e-6f
                || GpuTerrainParity.error(-0.0f, 0.0f) != 0.0f
                || GpuTerrainParity.error(1.0f, 1.0f + 1.0e-6f) > 2.0e-6f) {
            throw new IllegalStateException("Terrain parity comparison mishandled finite or non-finite values");
        }
        float[] x = new float[512], y = new float[512], z = new float[512];
        GpuTerrainParity.fillStartupSamples(x, y, z);
        boolean negativeX = false, positiveX = false, variedZ = false;
        for (int sample = 0; sample < x.length; sample++) {
            negativeX |= x[sample] < 0.0f;
            positiveX |= x[sample] > 0.0f;
            variedZ |= z[sample] != 0.0f;
        }
        if (!negativeX || !positiveX || !variedZ || y[0] != -128.0f
                || y[y.length - 1] != 256.0f) {
            throw new IllegalStateException("Terrain startup parity samples do not cover the intended 3D range");
        }
        System.out.println("Vulkan CI smoke: terrain parity classification and 3D sample coverage passed");
    }

    private static void assertMarkerChunkCoordinates(GpuTerrainBlockResult blocks, int originX) {
        if (!blocks.contains(15, 0, 15)) {
            throw new IllegalStateException("Batched marker output has an invalid chunk shape");
        }
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                float worldX = originX + x;
                for (int root = 0; root < GpuTerrainRoots.COUNT; root++) {
                    float expected = root == GpuTerrainRoots.FINAL_DENSITY ? worldX * worldX
                            : root == 1 || root == 2 ? 8.0f : worldX + 3.0f;
                    float actual = blocks.valueAt(x, 0, z, root);
                    if (Math.abs(actual - expected) > 1.0e-5f) {
                        throw new IllegalStateException("Batched marker world-coordinate mismatch for origin "
                                + originX + " at " + x + ",0," + z + " root " + root
                                + ": expected " + expected + ", got " + actual);
                    }
                }
            }
        }
    }

    private static void verifyTranslatorRegistry() {
        GpuDensityTranslatorRegistry<String> registry = new GpuDensityTranslatorRegistry<>();
        registry.register(CustomDensityFamily.class, "family", true);
        if (!"family".equals(registry.find(CustomDensityImplementation.class))) {
            throw new IllegalStateException("Custom density supertype translator was not selected");
        }
        registry.register(CustomDensityImplementation.class, "exact", false);
        if (!"exact".equals(registry.find(CustomDensityImplementation.class))) {
            throw new IllegalStateException("Exact custom density translator did not take precedence");
        }

        registry.register(LeftDensityApi.class, "left", true);
        registry.register(RightDensityApi.class, "right", true);
        try {
            registry.find(AmbiguousDensityApi.class);
            throw new IllegalStateException("Ambiguous custom density translators were accepted");
        } catch (IllegalStateException expected) {
            if (!expected.getMessage().startsWith("Ambiguous GPU density translators for ")) throw expected;
        }
        System.out.println("Vulkan CI smoke: custom density adapter inheritance and precedence passed");
    }

    private static GpuDensityExpressionSmoke.VulkanBranchFixture createRegisteredTranslatorFixture() {
        GpuDensityFunctionTranslators.register(RegisteredAdapterDensity.class,
                GpuDensityFunctionTranslator.expressionOnly(function -> {
                    GpuDensityExpression.Builder expression = new GpuDensityExpression.Builder();
                    int x = expression.coordinate(GpuDensityExpression.Coordinate.X);
                    int y = expression.coordinate(GpuDensityExpression.Coordinate.Y);
                    int z = expression.coordinate(GpuDensityExpression.Coordinate.Z);
                    int scaledX = expression.apply(GpuDensityExpression.Operation.MULTIPLY,
                            x, expression.constant(0.25));
                    int scaledZ = expression.apply(GpuDensityExpression.Operation.MULTIPLY,
                            z, expression.constant(0.125));
                    int horizontal = expression.apply(GpuDensityExpression.Operation.ADD, scaledX, y);
                    int root = expression.apply(GpuDensityExpression.Operation.SUBTRACT, horizontal, scaledZ);
                    return expression.build(root);
                }));
        RegisteredAdapterDensity function = new RegisteredAdapterDensity();
        GpuDensityGraph graph = GpuDensityGraphCompiler.compileDensityFunction(function, true);
        GpuDensityGraph lowered = GpuDensityGraphLowerer.lower(graph);
        boolean directExpression = lowered.nodes().stream()
                .anyMatch(node -> "GpuMath".equals(node.opcode())
                        && "EXPR_SUBTRACT".equals(node.payload()));
        if (!lowered.supportsRouterRoot(0) || !directExpression) {
            throw new IllegalStateException("Registered custom translator did not reach the terrain GPU IR");
        }
        System.out.println("Vulkan CI smoke: registered custom density adapter reached terrain IR");
        return new GpuDensityExpressionSmoke.VulkanBranchFixture(lowered, function);
    }

    private static final class RegisteredAdapterDensity implements net.minecraft.world.level.levelgen.DensityFunction.SimpleFunction {
        @Override
        public double compute(net.minecraft.world.level.levelgen.DensityFunction.FunctionContext context) {
            return context.blockX() * 0.25 + context.blockY() - context.blockZ() * 0.125;
        }

        @Override
        public net.minecraft.world.level.levelgen.DensityFunction mapAll(
                net.minecraft.world.level.levelgen.DensityFunction.Visitor visitor) {
            return visitor.apply(this);
        }

        @Override public double minValue() { return -10_000_000.0; }
        @Override public double maxValue() { return 10_000_000.0; }
        @Override public net.minecraft.util.KeyDispatchDataCodec<? extends net.minecraft.world.level.levelgen.DensityFunction> codec() {
            return net.minecraft.world.level.levelgen.DensityFunctions.constant(0.0).codec();
        }
    }

    public interface CustomDensityFamily {}
    public interface CustomDensityImplementation extends CustomDensityFamily {}
    public interface LeftDensityApi {}
    public interface RightDensityApi {}
    public interface AmbiguousDensityApi extends LeftDensityApi, RightDensityApi {}

    private static void assertConstantResults(String label, float[] values, int expectedCount) {
        if (values.length != expectedCount) {
            throw new IllegalStateException(label + " returned " + values.length
                    + " values; expected " + expectedCount);
        }
        for (int i = 0; i < values.length; i++) {
            if (Math.abs(values[i] - EXPECTED) > 1.0e-6f) {
                throw new IllegalStateException(label + " mismatch at sample " + i
                        + ": expected " + EXPECTED + ", got " + values[i]);
            }
        }
    }

    private static void assertNumericFixture(VulkanSubmissionRing ring,
            GpuDensityExpressionSmoke.VulkanBranchFixture fixture, String label,
            float[] x, float[] y, float[] z) throws Exception {
        assertNumericFixture(ring, fixture, label, x, y, z, false);
    }

    private static void assertNumericFixture(VulkanSubmissionRing ring,
            GpuDensityExpressionSmoke.VulkanBranchFixture fixture, String label,
            float[] x, float[] y, float[] z, boolean compareNaNSign) throws Exception {
        float[] actual = ring.submitGraphRoots(fixture.graph(), x, y, z).get(60, TimeUnit.SECONDS);
        int rootCount = fixture.graph().routerRootCount();
        if (rootCount == 0 || !fixture.graph().supportsRouterRoot(0)) {
            throw new IllegalStateException(label + " has no supported GPU root at output zero");
        }
        if (actual.length != x.length * rootCount) {
            throw new IllegalStateException(label + " returned " + actual.length
                    + " values for " + x.length + " samples and " + rootCount + " roots");
        }
        for (int sample = 0; sample < x.length; sample++) {
            double expected = fixture.javaFunction().compute(
                    new net.minecraft.world.level.levelgen.DensityFunction.SinglePointContext(
                            (int) x[sample], (int) y[sample], (int) z[sample]));
            float expectedGpuValue = (float) expected;
            float actualValue = actual[sample * rootCount];
            boolean mismatch = Double.isNaN(expected) ? !Float.isNaN(actualValue)
                    || compareNaNSign && (Double.doubleToRawLongBits(expected) < 0)
                            != (Float.floatToRawIntBits(actualValue) < 0)
                    : Double.isInfinite(expected) ? actualValue != expectedGpuValue
                    : expectedGpuValue == 0.0f ? actualValue != 0.0f
                            || (Double.doubleToRawLongBits(expected) < 0)
                                    != (Float.floatToRawIntBits(actualValue) < 0)
                    : Math.abs(actualValue - expectedGpuValue)
                            > Math.max(0.005f, Math.ulp(expectedGpuValue) * 1.01f);
            if (mismatch) {
                throw new IllegalStateException(label + " Vulkan mismatch at sample " + sample
                        + ": expected " + expected + ", got " + actualValue);
            }
        }
    }

    /** Grid metadata preserves integer world coordinates above float's exact-integer limit. */
    private static void verifyLargeCoordinateNoiseParity(VulkanSubmissionRing ring) throws Exception {
        GpuDensityExpressionSmoke.VulkanBranchFixture[] fixtures = {
                GpuDensityExpressionSmoke.vanillaNoiseOpcodeFixture(),
                GpuDensityExpressionSmoke.transformedNoiseFixture()
        };
        int[][] origins = {{4_096, 79, -4_096}, {-4_097, -64, 4_097},
                {16_777_216, 79, -16_777_217}, {-30_000_000, -64, 30_000_000}};
        for (GpuDensityExpressionSmoke.VulkanBranchFixture fixture : fixtures) {
            for (int[] origin : origins) {
                ByteBuffer metadata = ByteBuffer.allocateDirect(8 * Integer.BYTES).order(ByteOrder.nativeOrder());
                metadata.putInt(origin[0]).putInt(origin[1]).putInt(origin[2])
                        .putInt(2).putInt(1).putInt(1).putInt(1).putInt(1).flip();
                float[] actual = ring.submitGraphRootsGrid(fixture.graph(), metadata, 1, 2)
                        .get(60, TimeUnit.SECONDS);
                if (actual.length != 2) {
                    throw new IllegalStateException("large-coordinate noise grid returned "
                            + actual.length + " values; expected two");
                }
                for (int localX = 0; localX < 2; localX++) {
                    int x = origin[0] + localX;
                    double expected = fixture.javaFunction().compute(
                            new net.minecraft.world.level.levelgen.DensityFunction.SinglePointContext(
                                    x, origin[1], origin[2]));
                    float expectedFloat = (float) expected;
                    if (GpuTerrainParity.decisionError(actual[localX], expectedFloat) > 0.005f) {
                        throw new IllegalStateException("double-coordinate NormalNoise parity mismatch for "
                                + fixture.graph().nodes().get(fixture.graph().routerRoots()[0]).opcode()
                                + " at (" + x + ", " + origin[1] + ", " + origin[2] + "): expected "
                                + expected + ", got " + actual[localX]);
                    }
                }
            }
        }
    }

    private static void assertInterpolatedResults(float[] values) {
        if (values.length != 8) {
            throw new IllegalStateException("block interpolation shader returned " + values.length
                    + " values; expected 8");
        }
        for (int index = 0; index < values.length; index++) {
            int x = index & 1;
            int z = (index >> 1) & 1;
            int y = index >> 2;
            float expected = x * 0.125f + y * 0.5f + z;
            if (Math.abs(values[index] - expected) > 1.0e-6f) {
                throw new IllegalStateException("block interpolation axis mismatch at sample " + index
                        + ": expected " + expected + ", got " + values[index]);
            }
        }
    }

    private static GpuAquiferFluidStatus.Input[] aquiferFluidStatusCases() {
        float[] drySurface = new float[GpuAquiferFluidStatus.SURFACE_SAMPLES];
        int[] dryLevels = new int[GpuAquiferFluidStatus.GLOBAL_LEVEL_SAMPLES];
        int[] dryKinds = new int[GpuAquiferFluidStatus.GLOBAL_LEVEL_SAMPLES];
        int[] neighborLevels = dryLevels.clone();
        int[] neighborKinds = dryKinds.clone();
        neighborLevels[0] = 5;
        neighborKinds[0] = GpuAquiferFluidStatus.WATER;
        GpuAquiferFluidStatus.Input neighborStatus = new GpuAquiferFluidStatus.Input(
                0, 63, GpuAquiferFluidStatus.WATER, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f,
                drySurface.clone(), neighborLevels, neighborKinds);
        float[] highSurface = new float[GpuAquiferFluidStatus.SURFACE_SAMPLES];
        java.util.Arrays.fill(highSurface, 100.0f);
        int[] centerLevels = dryLevels.clone();
        int[] centerKinds = dryKinds.clone();
        centerKinds[0] = GpuAquiferFluidStatus.WATER;
        GpuAquiferFluidStatus.Input spreadLevel = new GpuAquiferFluidStatus.Input(
                0, 63, GpuAquiferFluidStatus.WATER, 0.7f, 0.31f, 0.0f, 0.0f, 0.0f,
                highSurface, centerLevels, centerKinds);
        int[] lavaLevels = dryLevels.clone();
        int[] lavaKinds = dryKinds.clone();
        lavaLevels[0] = 10;
        lavaKinds[0] = GpuAquiferFluidStatus.WATER;
        GpuAquiferFluidStatus.Input lavaOverride = new GpuAquiferFluidStatus.Input(
                -20, 63, GpuAquiferFluidStatus.WATER, -0.2f, 0.31f, 0.4f, 0.0f, 0.0f,
                drySurface.clone(), lavaLevels, lavaKinds);
        GpuAquiferFluidStatus.Input uncertainDeepDarkBoundary = new GpuAquiferFluidStatus.Input(
                0, 63, GpuAquiferFluidStatus.WATER, 0.0f, 0.0f, 0.0f, -0.225f, 0.0f,
                drySurface.clone(), dryLevels.clone(), dryKinds.clone());
        return new GpuAquiferFluidStatus.Input[]{neighborStatus, spreadLevel, lavaOverride,
                uncertainDeepDarkBoundary};
    }

    private static void assertAnisotropicInterpolationResults(float[] values) {
        if (values.length != 16) {
            throw new IllegalStateException("anisotropic interpolation shader returned " + values.length
                    + " values; expected 16");
        }
        for (int index = 0; index < values.length; index++) {
            int x = index & 1;
            int z = (index >> 1) & 1;
            int y = index >> 2;
            float expected = x * 0.125f + y * 0.25f + z;
            if (Math.abs(values[index] - expected) > 1.0e-6f) {
                throw new IllegalStateException("anisotropic interpolation spacing mismatch at sample " + index
                        + ": expected " + expected + ", got " + values[index]);
            }
        }
    }

    private static void assertVanillaInterpolationRounding(float[] values, float[] corners) {
        if (values.length != 8 || corners.length != 8) {
            throw new IllegalStateException("rounding interpolation fixture has an invalid size");
        }
        for (int index = 0; index < values.length; index++) {
            float tx = (index & 1) * 0.5f;
            float tz = ((index >> 1) & 1) * 0.5f;
            float ty = ((index >> 2) & 1) * 0.5f;
            float y00 = corners[0] + (corners[4] - corners[0]) * ty;
            float y10 = corners[2] + (corners[6] - corners[2]) * ty;
            float y01 = corners[1] + (corners[5] - corners[1]) * ty;
            float y11 = corners[3] + (corners[7] - corners[3]) * ty;
            float x0 = y00 + (y10 - y00) * tx;
            float x1 = y01 + (y11 - y01) * tx;
            float expected = x0 + (x1 - x0) * tz;
            if (Float.floatToIntBits(values[index]) != Float.floatToIntBits(expected)) {
                throw new IllegalStateException("vanilla interpolation rounding mismatch at sample " + index
                        + ": expected " + expected + ", got " + values[index]);
            }
        }
    }

    private static void assertSharedLatticeInterpolationResults(float[] values) {
        if (values.length != 128) {
            throw new IllegalStateException("shared-lattice interpolation returned " + values.length
                    + " values; expected 128");
        }
        for (int index = 0; index < values.length; index++) {
            int x = index & 3;
            int z = (index >> 2) & 3;
            int y = index >> 4;
            float expected = x * 0.125f + y * 0.25f + z;
            if (Math.abs(values[index] - expected) > 1.0e-6f) {
                throw new IllegalStateException("shared-lattice interpolation mismatch at sample " + index
                        + ": expected " + expected + ", got " + values[index]);
            }
        }
    }
}
