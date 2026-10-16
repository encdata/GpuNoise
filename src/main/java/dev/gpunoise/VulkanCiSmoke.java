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
            float[] gridResults = ring.submitGraphRootsGrid(metadata, 1, 8)
                    .get(60, TimeUnit.SECONDS);
            System.out.println("Vulkan CI smoke: terrain grid shader completed");
            assertConstantResults("terrain grid shader", gridResults, 8);

            // Exercise the cell-grid to block-density interpolation shader
            // used by the per-block terrain output path.
            float[] corners = new float[8];
            java.util.Arrays.fill(corners, EXPECTED);
            ByteBuffer interpolationMetadata = GpuInterpolationBatch.metadata(
                    1, 1, 1, 1, 2, 8, 8);
            System.out.println("Vulkan CI smoke: dispatching block interpolation shader");
            float[] blockResults = ring.submitInterpolation(corners, interpolationMetadata, 1, 8)
                    .get(60, TimeUnit.SECONDS);
            System.out.println("Vulkan CI smoke: block interpolation shader completed");
            assertConstantResults("block interpolation shader", blockResults, 8);
        }

        System.out.println("Vulkan CI smoke passed on " + device.name()
                + ": coordinate, terrain grid, and block interpolation shaders returned the expected value.");
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
}
