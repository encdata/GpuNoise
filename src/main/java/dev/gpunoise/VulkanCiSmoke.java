package dev.gpunoise;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.concurrent.TimeUnit;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;

/** Headless Vulkan smoke test for CI's Mesa Lavapipe device. */
public final class VulkanCiSmoke {
    private static final float EXPECTED = 0.375f;

    private VulkanCiSmoke() {}

    public static void main(String[] args) throws Exception {
        verifyDensityFunctionTranslators();
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

    private static void verifyDensityFunctionTranslators() {
        DensityFunction inheritedGraph = DensityFunctions.constant(0.25);
        GpuDensityFunctionTranslators.registerForSubtypes(CustomDensityFamily.class,
                ignored -> inheritedGraph);
        DensityFunction inherited = densityProxy(CustomDensityImplementation.class);
        if (!GpuDensityFunctionTranslators.hasTranslator(CustomDensityImplementation.class)
                || GpuDensityFunctionTranslators.translate(inherited) != inheritedGraph) {
            throw new IllegalStateException("Custom density supertype translator was not selected");
        }

        DensityFunction exactGraph = DensityFunctions.constant(0.75);
        @SuppressWarnings("unchecked")
        Class<DensityFunction> generatedType = (Class<DensityFunction>) inherited.getClass();
        GpuDensityFunctionTranslators.register(generatedType, ignored -> exactGraph);
        if (GpuDensityFunctionTranslators.translate(inherited) != exactGraph) {
            throw new IllegalStateException("Exact custom density translator did not take precedence");
        }

        GpuDensityFunctionTranslators.registerForSubtypes(LeftDensityApi.class,
                ignored -> DensityFunctions.constant(1.0));
        GpuDensityFunctionTranslators.registerForSubtypes(RightDensityApi.class,
                ignored -> DensityFunctions.constant(-1.0));
        DensityFunction ambiguous = densityProxy(LeftDensityApi.class, RightDensityApi.class);
        try {
            GpuDensityFunctionTranslators.translate(ambiguous);
            throw new IllegalStateException("Ambiguous custom density translators were accepted");
        } catch (IllegalStateException expected) {
            if (!expected.getMessage().startsWith("Ambiguous GPU density translators for ")) throw expected;
        }
        System.out.println("Vulkan CI smoke: custom density adapter inheritance and precedence passed");
    }

    private static DensityFunction densityProxy(Class<?>... interfaces) {
        return (DensityFunction) java.lang.reflect.Proxy.newProxyInstance(
                DensityFunction.class.getClassLoader(), interfaces,
                (proxy, method, arguments) -> {
                    Class<?> type = method.getReturnType();
                    if (!type.isPrimitive()) return null;
                    if (type == boolean.class) return false;
                    if (type == char.class) return (char) 0;
                    if (type == byte.class) return (byte) 0;
                    if (type == short.class) return (short) 0;
                    if (type == int.class) return 0;
                    if (type == long.class) return 0L;
                    if (type == float.class) return 0.0f;
                    if (type == double.class) return 0.0d;
                    return null;
                });
    }

    public interface CustomDensityFamily extends DensityFunction {}
    public interface CustomDensityImplementation extends CustomDensityFamily {}
    public interface LeftDensityApi extends DensityFunction {}
    public interface RightDensityApi extends DensityFunction {}

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
