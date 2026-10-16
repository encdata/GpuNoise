package dev.gpunoise;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Headless Vulkan smoke test for CI's Mesa Lavapipe device. */
public final class VulkanCiSmoke {
    private static final float EXPECTED = 0.375f;

    private VulkanCiSmoke() {}

    public static void main(String[] args) throws Exception {
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
        }

        System.out.println("Vulkan CI smoke passed on " + device.name()
                + ": coordinate and terrain grids passed; interpolation preserved axis-coded values for isotropic and anisotropic cells.");
    }

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
}
