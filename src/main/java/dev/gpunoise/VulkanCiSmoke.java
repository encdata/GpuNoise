package dev.gpunoise;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.concurrent.TimeUnit;
import net.minecraft.util.RandomSource;

/** Headless Vulkan smoke test for CI's Mesa Lavapipe device. */
public final class VulkanCiSmoke {
    private static final float EXPECTED = 0.375f;

    private VulkanCiSmoke() {}

    public static void main(String[] args) throws Exception {
        // Called by the CI server after NeoForge has initialized the registry
        // and mod-loading lifecycle. A standalone JavaExec cannot safely boot
        // these fixtures because NeoForge's LoadingModList is unavailable.
        GpuDensityExpressionSmoke.verify();
        verifyTranslatorRegistry();
        GpuDevice device = GpuProbe.findPreferredDevice(GpuNoiseConfig.GpuVendorPreference.AUTO);
        if (device == null) throw new IllegalStateException("No Vulkan compute device found");
        if (!VulkanComputeProbe.verify(device)) {
            throw new IllegalStateException("Vulkan compute queue probe failed on " + device.name());
        }

        // Supply an already-lowered one-node IR directly. This smoke test
        // exercises Vulkan shader dispatch without booting NeoForge registries.
        GpuDensityGraph graph = new GpuDensityGraph(
                List.of(new GpuDensityGraph.Node("Constant", new int[0], new double[]{EXPECTED}, null, 0)),
                new int[]{0}, List.of(), List.of(), List.of(), List.of(), 1);
        System.out.println("Vulkan CI smoke: creating submission ring");
        VulkanSubmissionRing ring = VulkanBootstrapThread.call(() -> new VulkanSubmissionRing(device));
        try (ring) {
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
            float[] floatingBranchResults = ring.submitGraphRoots(floatingFixture.graph(), x, y, z)
                    .get(60, TimeUnit.SECONDS);
            for (int sample = 0; sample < x.length; sample++) {
                double expected = floatingFixture.javaFunction().compute(
                        new net.minecraft.world.level.levelgen.DensityFunction.SinglePointContext(
                                (int) x[sample], (int) y[sample], (int) z[sample]));
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

            GpuDensityExpressionSmoke.VulkanBranchFixture mathFixture =
                    GpuDensityExpressionSmoke.scalarMathFixture();
            float[] mathX = {-81.0f, -1.0f, 0.0f, 17.0f, 192.0f};
            float[] mathY = {-17.0f, 0.0f, 1.0f, 9.0f, 80.0f};
            float[] mathZ = {-5.0f, 1.0f, 0.0f, 31.0f, -64.0f};
            float[] mathResults = ring.submitGraphRoots(mathFixture.graph(), mathX, mathY, mathZ)
                    .get(60, TimeUnit.SECONDS);
            for (int sample = 0; sample < mathX.length; sample++) {
                double expected = mathFixture.javaFunction().compute(
                        new net.minecraft.world.level.levelgen.DensityFunction.SinglePointContext(
                                (int) mathX[sample], (int) mathY[sample], (int) mathZ[sample]));
                if (Math.abs(mathResults[sample] - expected) > 0.005) {
                    throw new IllegalStateException("Scalar Math Java bytecode mismatch at sample " + sample
                            + ": expected " + expected + ", got " + mathResults[sample]);
                }
            }

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
                    GpuDensityExpressionSmoke.scalarMathFixture();
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

            // Production chunks reuse shared lattice corners and interleave
            // twelve terrain roots at each grid sample. Exercise both strides
            // across multiple cells, including samples on cell boundaries.
            int rootStride = 12;
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
            // Four equal-distance centers pin the precise tie order in the
            // three nearest slots, including vanilla's inclusive third test.
            centers[0][0] = new int[]{queries[0][0] + 1, queries[0][1], queries[0][2]};
            centers[0][1] = new int[]{queries[0][0] - 1, queries[0][1], queries[0][2]};
            centers[0][2] = new int[]{queries[0][0], queries[0][1] + 1, queries[0][2]};
            centers[0][3] = new int[]{queries[0][0], queries[0][1], queries[0][2] + 1};
            ByteBuffer nearestInput = GpuAquiferNearestThree.pack(queries, centers);
            ByteBuffer queuedGridMetadata = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder());
            queuedGridMetadata.putInt(-16).putInt(-8).putInt(32)
                    .putInt(2).putInt(2).putInt(2).putInt(1).putInt(1).flip();
            var queuedGrid = ring.submitGraphRootsGrid(graph, queuedGridMetadata, 1, 8);
            System.out.println("Vulkan CI smoke: dispatching aquifer nearest-three selection");
            var nearestFuture = ring.submitAquiferNearestThree(nearestInput, queryCount);
            GpuAquiferSubstance.Input[] substanceCases = {
                    new GpuAquiferSubstance.Input(0.2, 0.25, 50,
                            40, 24, 12, 1, 10, 20,
                            GpuAquiferSubstance.OTHER, GpuAquiferSubstance.OTHER,
                            GpuAquiferSubstance.OTHER, 0, false, false, 400),
                    new GpuAquiferSubstance.Input(-0.2, 0.25, 50,
                            40, 24, 12, 1, 10, 20,
                            GpuAquiferSubstance.OTHER, GpuAquiferSubstance.OTHER,
                            GpuAquiferSubstance.OTHER, 77, true, false, 401),
                    new GpuAquiferSubstance.Input(-0.2, 0.25, 50,
                            40, 24, 12, 10, 35, 60,
                            GpuAquiferSubstance.OTHER, GpuAquiferSubstance.OTHER,
                            GpuAquiferSubstance.OTHER, 0, false, false, 402),
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
                    // Second/third is the deciding pressure pair.
                    new GpuAquiferSubstance.Input(-0.5, 0.0, 30,
                            30, 30, 30, 1, 2, 3,
                            GpuAquiferSubstance.OTHER, GpuAquiferSubstance.WATER,
                            GpuAquiferSubstance.LAVA, 0, false, false, 409)
            };
            var substanceFuture = ring.submitAquiferSubstance(
                    GpuAquiferSubstance.pack(substanceCases), substanceCases.length);
            int[] nearest = nearestFuture.get(60, TimeUnit.SECONDS);
            int[] substanceResults = substanceFuture.get(60, TimeUnit.SECONDS);
            assertConstantResults("queued terrain grid", queuedGrid.get(60, TimeUnit.SECONDS), 8);
            System.out.println("Vulkan CI smoke: aquifer nearest-three selection completed");
            for (int q = 0; q < queryCount; q++) {
                int[] expected = GpuAquiferNearestThree.select(queries[q], centers[q]);
                for (int field = 0; field < GpuAquiferNearestThree.OUTPUT_INTS; field++) {
                    int actual = nearest[q * GpuAquiferNearestThree.OUTPUT_INTS + field];
                    if (actual != expected[field]) {
                        throw new IllegalStateException("Aquifer nearest-three mismatch at query " + q
                                + ", field " + field + ": expected " + expected[field] + ", got " + actual);
                    }
                }
            }
            System.out.println("Vulkan CI smoke: aquifer substance decisions completed");
            for (int i = 0; i < substanceCases.length; i++) {
                int[] expected = GpuAquiferSubstance.evaluate(substanceCases[i]);
                for (int field = 0; field < GpuAquiferSubstance.OUTPUT_INTS; field++) {
                    int actual = substanceResults[i * GpuAquiferSubstance.OUTPUT_INTS + field];
                    if (actual != expected[field]) {
                        throw new IllegalStateException("Aquifer substance mismatch at case " + i
                                + ", field " + field + ": expected " + expected[field] + ", got " + actual);
                    }
                }
            }
        }

        System.out.println("Vulkan CI smoke passed on " + device.name()
                + ": coordinate and terrain grids passed; interpolation preserved axis-coded values across isotropic cells, anisotropic cells, and shared 12-root terrain lattices; aquifer pressure, nearest-three selection, and substance decisions matched Java reference cases.");
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
