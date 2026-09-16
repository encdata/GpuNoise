package dev.gpunoise;

import java.util.Set;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.List;

/** Validates the graph IR before a Vulkan evaluator is allowed to consume it. */
final class GpuDensityGraphLowerer {
    private static final Set<String> SUPPORTED = Set.of(
            "Constant", "Noise", "Ap2", "MulOrAdd", "Clamp", "Mapped", "Marker",
            "Shift", "ShiftA", "ShiftB", "ShiftedNoise", "TectonicConfigNoise", "TectonicInvert", "Spline", "BlendedNoise", "YClampedGradient", "BlendDensity", "RangeChoice", "WeirdScaledSampler"
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
        // The generic compute evaluator used a values[1024] array indexed by
        // graph-node number. On consumer GPUs that array spills into local
        // memory. Reassign node values to physical slots based on their last
        // use, retaining only values which are live at the same time.
        int[] slots = allocateRegisters(nodes, roots);
        int registerCount = Arrays.stream(slots).max().orElse(0) + 1;
        List<GpuDensityGraph.Node> registerNodes = new ArrayList<>(nodes.size());
        for (int index = 0; index < nodes.size(); index++) {
            GpuDensityGraph.Node node = nodes.get(index);
            int[] children = node.children();
            for (int child = 0; child < children.length; child++) children[child] = slots[children[child]];
            registerNodes.add(new GpuDensityGraph.Node(node.opcode(), children, node.constants(), node.payload(), slots[index]));
        }
        for (int root = 0; root < roots.length; root++) roots[root] = slots[roots[root]];
        List<GpuDensityGraph.Spline> registerSplines = new ArrayList<>(splines.size());
        for (GpuDensityGraph.Spline spline : splines) {
            int[] values = spline.valueNodes();
            for (int value = 0; value < values.length; value++) values[value] = slots[values[value]];
            registerSplines.add(new GpuDensityGraph.Spline(slots[spline.coordinateNode()], spline.locations(), spline.derivatives(), values));
        }
        return new GpuDensityGraph(registerNodes, roots, graph.noises(), registerSplines, graph.blended(), sourceFunctions, registerCount);
    }

    private static int[] allocateRegisters(List<GpuDensityGraph.Node> nodes, int[] roots) {
        int count = nodes.size();
        int[] lastUse = new int[count];
        Arrays.fill(lastUse, -1);
        for (int index = 0; index < count; index++) {
            for (int child : nodes.get(index).children()) lastUse[child] = Math.max(lastUse[child], index);
        }
        // Router roots are copied after the final instruction, so their slots
        // must survive the rest of the program.
        for (int root : roots) lastUse[root] = count;
        List<List<Integer>> releases = new ArrayList<>(count + 1);
        for (int index = 0; index <= count; index++) releases.add(new ArrayList<>());
        for (int index = 0; index < count; index++) releases.get(lastUse[index]).add(index);
        int[] slots = new int[count];
        ArrayDeque<Integer> free = new ArrayDeque<>();
        int next = 0;
        for (int index = 0; index < count; index++) {
            // Inputs whose final use is this instruction can safely be reused:
            // GLSL evaluates the right hand side before assigning the output.
            for (int released : releases.get(index)) free.addLast(slots[released]);
            slots[index] = free.isEmpty() ? next++ : free.removeFirst();
        }
        return slots;
    }

    private static void visit(int index, List<GpuDensityGraph.Node> nodes, boolean[] visited, int[] order, int[] count) {
        if (index < 0 || visited[index]) return;
        visited[index] = true;
        for (int child : nodes.get(index).children()) visit(child, nodes, visited, order, count);
        order[count[0]++] = index;
    }
}
