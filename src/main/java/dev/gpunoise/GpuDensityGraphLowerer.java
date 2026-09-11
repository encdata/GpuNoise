package dev.gpunoise;

import java.util.Set;
import java.util.ArrayList;
import java.util.List;

/** Validates the graph IR before a Vulkan evaluator is allowed to consume it. */
final class GpuDensityGraphLowerer {
    private static final Set<String> SUPPORTED = Set.of(
            "Constant", "Noise", "Ap2", "MulOrAdd", "Clamp", "Mapped", "Marker",
            "Shift", "ShiftA", "ShiftB", "ShiftedNoise", "Spline", "BlendedNoise", "YClampedGradient", "BlendDensity", "RangeChoice", "WeirdScaledSampler"
    );

    private GpuDensityGraphLowerer() {}

    static GpuDensityGraph lower(GpuDensityGraph graph) {
        for (GpuDensityGraph.Node node : graph.nodes()) {
            if (!SUPPORTED.contains(node.opcode())) {
                throw new IllegalArgumentException("GPU graph opcode not lowered: " + node.opcode());
            }
        }
        // The compiler reserves parent indices while walking the graph. Reorder
        // the IR into child-before-parent order before uploading it; the shader
        // is intentionally iterative and must never read an uncomputed node.
        int[] order = new int[graph.nodes().size()];
        boolean[] visited = new boolean[order.length];
        int[] count = {0};
        for (int root : graph.routerRoots()) visit(root, graph.nodes(), visited, order, count);
        // Keep only nodes reachable from the selected roots. This is important
        // for terrain-only graphs: unrelated climate and vein branches should
        // not execute for every terrain sample.
        int[] remap = new int[order.length];
        for (int i = 0; i < order.length; i++) remap[order[i]] = i;
        List<GpuDensityGraph.Node> nodes = new ArrayList<>(order.length);
        List<net.minecraft.world.level.levelgen.DensityFunction> sourceFunctions = new ArrayList<>(order.length);
        for (int old : order) {
            GpuDensityGraph.Node node = graph.nodes().get(old);
            int[] children = node.children();
            for (int i = 0; i < children.length; i++) if (children[i] >= 0) children[i] = remap[children[i]];
            nodes.add(new GpuDensityGraph.Node(node.opcode(), children, node.constants(), node.payload()));
            sourceFunctions.add(graph.sourceFunctions().get(old));
        }
        int[] roots = graph.routerRoots();
        for (int i = 0; i < roots.length; i++) roots[i] = remap[roots[i]];
        List<GpuDensityGraph.Spline> splines = new ArrayList<>(graph.splines().size());
        for (GpuDensityGraph.Spline spline : graph.splines()) {
            if (!visited[spline.coordinateNode()]) continue;
            boolean valuesVisited = true;
            for (int value : spline.valueNodes()) if (!visited[value]) valuesVisited = false;
            if (!valuesVisited) continue;
            int[] values = spline.valueNodes();
            for (int i = 0; i < values.length; i++) values[i] = remap[values[i]];
            splines.add(new GpuDensityGraph.Spline(remap[spline.coordinateNode()], spline.locations(), spline.derivatives(), values));
        }
        return new GpuDensityGraph(nodes, roots, graph.noises(), splines, graph.blended(), sourceFunctions);
    }

    private static void visit(int index, List<GpuDensityGraph.Node> nodes, boolean[] visited, int[] order, int[] count) {
        if (index < 0 || visited[index]) return;
        visited[index] = true;
        for (int child : nodes.get(index).children()) visit(child, nodes, visited, order, count);
        order[count[0]++] = index;
    }
}
