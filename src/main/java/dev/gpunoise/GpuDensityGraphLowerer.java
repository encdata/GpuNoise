package dev.gpunoise;

import java.util.Set;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Validates the graph IR before a Vulkan evaluator is allowed to consume it. */
final class GpuDensityGraphLowerer {
    private static final Set<String> SUPPORTED = Set.of(
            "Constant", "Noise", "Ap2", "MulOrAdd", "Clamp", "Mapped", "Marker", "GpuCoordinate", "GpuMath", "GpuCompare", "GpuSelect", "GpuInt", "GpuFloat", "GpuCast",
            "Shift", "ShiftA", "ShiftB", "ShiftedNoise", "Spline", "BlendedNoise", "YClampedGradient", "BlendDensity", "RangeChoice", "WeirdScaledSampler", "GpuNoise3"
    );

    private GpuDensityGraphLowerer() {}

    private record NodeKey(String opcode, List<Integer> children, List<Long> constants, String payload) {}
    private record NodePair(int left, int right) {}
    private static final Set<String> PURE_BRANCH_VALUES = Set.of(
            "Constant", "Noise", "Ap2", "MulOrAdd", "Clamp", "Mapped", "GpuCoordinate",
            "GpuMath", "GpuCompare", "GpuSelect", "GpuInt", "GpuFloat", "GpuCast",
            "Shift", "ShiftA", "ShiftB", "ShiftedNoise", "Spline", "BlendedNoise",
            "YClampedGradient", "BlendDensity", "RangeChoice", "WeirdScaledSampler", "GpuNoise3");

    static GpuDensityGraph lower(GpuDensityGraph graph) {
        validateGraphShape(graph);
        for (GpuDensityGraph.Node node : graph.nodes()) {
            if (!SUPPORTED.contains(node.opcode())) {
                throw new IllegalArgumentException("GPU graph opcode not lowered: " + node.opcode());
            }
        }
        // The compiler reserves parent indices while walking the graph. Reorder
        // the IR into child-before-parent order before uploading it; the shader
        // is intentionally iterative and must never read an uncomputed node.
        int[] order = new int[graph.nodes().size()];
        byte[] state = new byte[order.length]; // 0 = unseen, 1 = active, 2 = complete
        int count = topologicalOrder(graph, state, order);
        if (count > PersistentVulkanNormalNoiseEngine.GRAPH_NODE_CAPACITY) {
            throw new IllegalArgumentException("GPU density graph has " + count
                    + " reachable nodes; Vulkan capacity is " + PersistentVulkanNormalNoiseEngine.GRAPH_NODE_CAPACITY);
        }
        // Keep only nodes reachable from the selected roots. This is important
        // for terrain-only graphs: unrelated climate and vein branches should
        // not execute for every terrain sample.
        int[] remap = new int[order.length];
        for (int i = 0; i < count; i++) remap[order[i]] = i;
        List<GpuDensityGraph.Node> nodes = new ArrayList<>(count);
        List<net.minecraft.world.level.levelgen.DensityFunction> sourceFunctions = new ArrayList<>(count);
        Map<NodeKey, Integer> commonNodes = new HashMap<>(count * 2);
        for (int ordered = 0; ordered < count; ordered++) {
            int old = order[ordered];
            GpuDensityGraph.Node node = graph.nodes().get(old);
            if (node.opcode().equals("Marker") && !"Interpolated".equals(node.payload())) {
                remap[old] = remap[node.children()[0]];
                continue;
            }
            int selectedBranch = constantBranch(graph, node);
            if (selectedBranch >= 0) {
                remap[old] = remap[selectedBranch];
                continue;
            }
            int[] children = node.children();
            for (int i = 0; i < children.length; i++) if (children[i] >= 0) children[i] = remap[children[i]];
            double[] constants = node.constants();
            GpuDensityGraph.Node folded = foldConstantNode(nodes, node, children, constants);
            if (folded != null) {
                node = folded;
                children = node.children();
                constants = node.constants();
            }
            // Marker identity carries Java delegation and interpolated-corner
            // semantics. BlendedNoise entries also retain their source object
            // for independent CPU/GPU parity checks, so only merge pure IR ops.
            if (!node.opcode().equals("Marker") && !node.opcode().equals("BlendedNoise")) {
                NodeKey key = nodeKey(node.opcode(), children, constants, node.payload());
                Integer shared = commonNodes.get(key);
                if (shared != null) {
                    remap[old] = shared;
                    continue;
                }
                commonNodes.put(key, nodes.size());
            }
            remap[old] = nodes.size();
            nodes.add(new GpuDensityGraph.Node(node.opcode(), children, constants, node.payload()));
            sourceFunctions.add(graph.sourceFunctions().get(old));
        }
        int[] roots = graph.routerRoots();
        for (int i = 0; i < roots.length; i++) {
            // Unsupported roots are never read by the runtime. Mapping them
            // to a live placeholder lets the shader retain its stable router
            // ABI without keeping their unreachable subgraphs in the hot
            // per-sample instruction stream.
            roots[i] = graph.supportsRouterRoot(i) ? remap[roots[i]] : 0;
        }
        int[] splineRemap = new int[graph.splines().size()];
        Arrays.fill(splineRemap, -1);
        List<GpuDensityGraph.Spline> splines = new ArrayList<>(graph.splines().size());
        for (int splineIndex = 0; splineIndex < graph.splines().size(); splineIndex++) {
            GpuDensityGraph.Spline spline = graph.splines().get(splineIndex);
            if (state[spline.coordinateNode()] != 2) continue;
            boolean valuesVisited = true;
            for (int value : spline.valueNodes()) if (state[value] != 2) valuesVisited = false;
            if (!valuesVisited) continue;
            int[] values = spline.valueNodes();
            for (int i = 0; i < values.length; i++) values[i] = remap[values[i]];
            splineRemap[splineIndex] = splines.size();
            splines.add(new GpuDensityGraph.Spline(remap[spline.coordinateNode()], spline.locations(), spline.derivatives(), values));
        }
        // Constant folding and selector elimination can make nodes dead after
        // the initial source-graph pruning pass. Compact again while treating
        // spline coordinates and control-point values as additional roots.
        CompactedGraph compacted = compactOptimizedGraph(nodes, sourceFunctions, roots, splines);
        nodes = compacted.nodes();
        sourceFunctions = compacted.sourceFunctions();
        roots = compacted.roots();
        splines = compacted.splines();
        // Root pruning can remove spline tables that appeared earlier in the
        // source graph. Rewrite each surviving Spline instruction's table ID
        // alongside the compacted spline-point array.
        for (int index = 0; index < nodes.size(); index++) {
            GpuDensityGraph.Node node = nodes.get(index);
            if (!node.opcode().equals("Spline")) continue;
            double[] constants = node.constants();
            if (constants.length == 0) {
                throw new IllegalArgumentException("GPU spline instruction " + index + " has no table ID");
            }
            int oldSplineId = (int) constants[0];
            if (oldSplineId < 0 || oldSplineId >= splineRemap.length || splineRemap[oldSplineId] < 0) {
                throw new IllegalArgumentException("GPU spline instruction " + index
                        + " references an unreachable spline table " + oldSplineId);
            }
            constants[0] = splineRemap[oldSplineId];
            nodes.set(index, new GpuDensityGraph.Node(node.opcode(), node.children(), constants, node.payload()));
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
        return new GpuDensityGraph(registerNodes, roots, graph.noises(), registerSplines, graph.blended(),
                sourceFunctions, registerCount, graph.supportedRouterRootMask(),
                graph.unsupportedRouterRootReasons());
    }

    /**
     * Folds only operations whose shader fast-math and precise paths produce
     * the same exactly float-representable result. This keeps the folded
     * constant identical to the value serialized through the float ABI.
     */
    private static GpuDensityGraph.Node foldConstantNode(List<GpuDensityGraph.Node> nodes,
                                                          GpuDensityGraph.Node node,
                                                          int[] children, double[] constants) {
        if (node.opcode().equals("Ap2") && children.length == 2) {
            Double left = constantNodeValue(nodes, children[0]);
            Double right = constantNodeValue(nodes, children[1]);
            if (left == null || right == null) return null;
            double precise;
            double fast;
            switch (node.payload() == null ? "" : node.payload()) {
                case "ADD" -> {
                    precise = left + right;
                    fast = (double) ((float) left + (float) right);
                }
                case "MUL" -> {
                    precise = left == 0.0 ? 0.0 : left * right;
                    fast = left == 0.0 ? 0.0 : (double) ((float) left * (float) right);
                }
                default -> { return null; }
            }
            if (sameFloatResult(precise, fast)) {
                return new GpuDensityGraph.Node("Constant", new int[0], new double[]{precise}, null);
            }
        }
        if (node.opcode().equals("Clamp") && children.length == 1 && constants.length >= 2) {
            Double input = constantNodeValue(nodes, children[0]);
            if (input == null) return null;
            double minimum = (double) (float) constants[0];
            double maximum = (double) (float) constants[1];
            // Avoid undefined/reversed bounds and signed-zero corner cases in
            // GLSL clamp implementations. Strict comparisons select one input
            // value exactly, so both shader precision variants agree.
            if (!Double.isFinite(input) || !Double.isFinite(minimum) || !Double.isFinite(maximum)
                    || !(minimum < maximum) || input == 0.0 || minimum == 0.0 || maximum == 0.0) return null;
            double result = input < minimum ? minimum : input > maximum ? maximum : input;
            return new GpuDensityGraph.Node("Constant", new int[0], new double[]{result}, null);
        }
        return null;
    }

    private static Double constantNodeValue(List<GpuDensityGraph.Node> nodes, int index) {
        GpuDensityGraph.Node node = nodes.get(index);
        if (!node.opcode().equals("Constant")) return null;
        double[] constants = node.constants();
        return constants.length == 1 ? (double) (float) constants[0] : null;
    }

    private static boolean sameFloatResult(double precise, double fast) {
        return Double.isFinite(precise) && Double.isFinite(fast)
                && (double) (float) precise == precise
                && Double.doubleToRawLongBits(precise) == Double.doubleToRawLongBits(fast);
    }

    private record CompactedGraph(List<GpuDensityGraph.Node> nodes,
                                  List<net.minecraft.world.level.levelgen.DensityFunction> sourceFunctions,
                                  int[] roots, List<GpuDensityGraph.Spline> splines) {}

    private static CompactedGraph compactOptimizedGraph(List<GpuDensityGraph.Node> inputNodes,
            List<net.minecraft.world.level.levelgen.DensityFunction> inputSources,
            int[] inputRoots, List<GpuDensityGraph.Spline> inputSplines) {
        int size = inputNodes.size();
        boolean[] visited = new boolean[size];
        boolean[] active = new boolean[size];
        int[] order = new int[size];
        int[] stack = new int[size];
        int[] nextChild = new int[size];
        List<Integer> starts = new ArrayList<>(inputRoots.length + inputSplines.size() * 5);
        for (int root : inputRoots) starts.add(root);
        for (GpuDensityGraph.Spline spline : inputSplines) {
            starts.add(spline.coordinateNode());
            for (int value : spline.valueNodes()) starts.add(value);
        }
        int ordered = 0;
        for (int start : starts) {
            if (visited[start]) continue;
            int depth = 0;
            stack[depth] = start;
            nextChild[depth] = 0;
            active[start] = true;
            while (depth >= 0) {
                int current = stack[depth];
                int[] children = inputNodes.get(current).children();
                if (nextChild[depth] < children.length) {
                    int child = children[nextChild[depth]++];
                    if (active[child]) {
                        throw new IllegalArgumentException("optimized GPU density graph contains a cycle at node " + child);
                    }
                    if (!visited[child]) {
                        depth++;
                        stack[depth] = child;
                        nextChild[depth] = 0;
                        active[child] = true;
                    }
                } else {
                    active[current] = false;
                    visited[current] = true;
                    order[ordered++] = current;
                    depth--;
                }
            }
        }
        int[] remap = new int[size];
        for (int index = 0; index < ordered; index++) remap[order[index]] = index;
        List<GpuDensityGraph.Node> compactNodes = new ArrayList<>(ordered);
        List<net.minecraft.world.level.levelgen.DensityFunction> compactSources = new ArrayList<>(ordered);
        for (int index = 0; index < ordered; index++) {
            int old = order[index];
            GpuDensityGraph.Node node = inputNodes.get(old);
            int[] children = node.children();
            for (int child = 0; child < children.length; child++) children[child] = remap[children[child]];
            compactNodes.add(new GpuDensityGraph.Node(node.opcode(), children, node.constants(), node.payload()));
            compactSources.add(inputSources.get(old));
        }
        int[] compactRoots = inputRoots.clone();
        for (int root = 0; root < compactRoots.length; root++) compactRoots[root] = remap[compactRoots[root]];
        List<GpuDensityGraph.Spline> compactSplines = new ArrayList<>(inputSplines.size());
        for (GpuDensityGraph.Spline spline : inputSplines) {
            int[] values = spline.valueNodes();
            for (int value = 0; value < values.length; value++) values[value] = remap[values[value]];
            compactSplines.add(new GpuDensityGraph.Spline(remap[spline.coordinateNode()],
                    spline.locations(), spline.derivatives(), values));
        }
        return new CompactedGraph(compactNodes, compactSources, compactRoots, compactSplines);
    }

    private static NodeKey nodeKey(String opcode, int[] children, double[] constants, String payload) {
        List<Integer> childKey = new ArrayList<>(children.length);
        for (int child : children) childKey.add(child);
        List<Long> constantKey = new ArrayList<>(constants.length);
        for (double constant : constants) constantKey.add(Double.doubleToRawLongBits(constant));
        return new NodeKey(opcode, List.copyOf(childKey), List.copyOf(constantKey), payload);
    }

    /** Selects branches whose condition is a position-independent constant. */
    private static int constantBranch(GpuDensityGraph graph, GpuDensityGraph.Node node) {
        int[] children = node.children();
        if (node.opcode().equals("RangeChoice") && children.length == 3) {
            if (samePureBranchValue(graph, children[1], children[2])) return children[1];
            double[] bounds = node.constants();
            if (bounds.length < 2) return -1;
            Double input = constantValue(graph, children[0]);
            if (input == null) return -1;
            float minInclusive = (float) bounds[0];
            float maxExclusive = (float) bounds[1];
            return input >= minInclusive && input < maxExclusive ? children[1] : children[2];
        }
        if (node.opcode().equals("GpuSelect") && children.length == 3) {
            if (samePureBranchValue(graph, children[1], children[2])) return children[1];
            Double condition = constantValue(graph, children[0]);
            if (condition != null) return condition != 0.0 ? children[1] : children[2];
        }
        return -1;
    }

    /** Compares branch values structurally without recursion or marker/cache identity assumptions. */
    private static boolean samePureBranchValue(GpuDensityGraph graph, int leftRoot, int rightRoot) {
        if (leftRoot == rightRoot) return true;
        List<GpuDensityGraph.Node> nodes = graph.nodes();
        ArrayDeque<NodePair> pending = new ArrayDeque<>();
        Set<NodePair> visited = new java.util.HashSet<>();
        pending.addLast(new NodePair(leftRoot, rightRoot));
        while (!pending.isEmpty()) {
            NodePair pair = pending.removeLast();
            if (pair.left() == pair.right() || !visited.add(pair)) continue;
            GpuDensityGraph.Node left = nodes.get(pair.left());
            GpuDensityGraph.Node right = nodes.get(pair.right());
            if (!left.opcode().equals(right.opcode()) || !PURE_BRANCH_VALUES.contains(left.opcode())
                    || !java.util.Objects.equals(left.payload(), right.payload())) return false;
            double[] leftConstants = left.constants();
            double[] rightConstants = right.constants();
            if (leftConstants.length != rightConstants.length) return false;
            for (int index = 0; index < leftConstants.length; index++) {
                if (Double.doubleToRawLongBits(leftConstants[index])
                        != Double.doubleToRawLongBits(rightConstants[index])) return false;
            }
            int[] leftChildren = left.children();
            int[] rightChildren = right.children();
            if (leftChildren.length != rightChildren.length) return false;
            for (int child = 0; child < leftChildren.length; child++) {
                pending.addLast(new NodePair(leftChildren[child], rightChildren[child]));
            }
        }
        return true;
    }

    /** Reads constants through only transparent wrappers, matching the float-backed shader ABI. */
    private static Double constantValue(GpuDensityGraph graph, int nodeIndex) {
        List<GpuDensityGraph.Node> nodes = graph.nodes();
        for (int depth = 0; depth < nodes.size(); depth++) {
            GpuDensityGraph.Node node = nodes.get(nodeIndex);
            if (node.opcode().equals("Constant")) {
                double[] value = node.constants();
                return value.length == 1 ? (double) (float) value[0] : null;
            }
            if (!node.opcode().equals("Marker") || "Interpolated".equals(node.payload())
                    || node.children().length != 1) return null;
            nodeIndex = node.children()[0];
        }
        return null;
    }

    /** Re-lowers the source graph after parity removes one or more GPU roots. */
    static GpuDensityGraph lowerVerifiedRoots(GpuDensityGraph source, int verifiedRootMask) {
        int activeMask = source.supportedRouterRootMask() & verifiedRootMask;
        if (activeMask == 0) {
            throw new IllegalArgumentException("No terrain roots passed GPU parity verification");
        }
        String[] reasons = source.unsupportedRouterRootReasons();
        for (int root = 0; root < reasons.length; root++) {
            if ((activeMask & (1 << root)) == 0 && reasons[root] == null) {
                reasons[root] = "terrain parity verification failed";
            }
        }
        GpuDensityGraph filtered = new GpuDensityGraph(source.nodes(), source.routerRoots(),
                source.noises(), source.splines(), source.blended(), source.sourceFunctions(),
                source.registerCount(), activeMask, reasons);
        return lower(filtered);
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
        // NoiseChunk's interpolated markers are exported at cell corners and
        // consumed by a second GPU evaluation at every block. Their value
        // aliases the child only when no other graph edge or router root needs
        // the child's distinct lifetime. Otherwise keep separate registers.
        boolean[] rootValue = new boolean[count];
        for (int root : roots) rootValue[root] = true;
        boolean[] aliasMarker = new boolean[count];
        for (int index = 0; index < count; index++) {
            GpuDensityGraph.Node node = nodes.get(index);
            if (!node.opcode().equals("Marker") || !"Interpolated".equals(node.payload())) continue;
            int[] children = node.children();
            if (children.length != 1) {
                throw new IllegalArgumentException("interpolated marker " + index
                        + " must have exactly one source node");
            }
            int child = children[0];
            aliasMarker[index] = lastUse[child] == index && !rootValue[child];
            lastUse[index] = count;
            if (aliasMarker[index]) lastUse[child] = count;
        }
        List<List<Integer>> releases = new ArrayList<>(count + 1);
        for (int index = 0; index <= count; index++) releases.add(new ArrayList<>());
        for (int index = 0; index < count; index++) releases.get(lastUse[index]).add(index);
        int[] slots = new int[count];
        ArrayDeque<Integer> free = new ArrayDeque<>();
        int next = 0;
        for (int index = 0; index < count; index++) {
            GpuDensityGraph.Node node = nodes.get(index);
            if (aliasMarker[index]) {
                slots[index] = slots[node.children()[0]];
                continue;
            }
            // Inputs whose final use is this instruction can safely be reused:
            // GLSL evaluates the right hand side before assigning the output.
            for (int released : releases.get(index)) free.addLast(slots[released]);
            slots[index] = free.isEmpty() ? next++ : free.removeFirst();
        }
        return slots;
    }

    private static void validateGraphShape(GpuDensityGraph graph) {
        List<GpuDensityGraph.Node> nodes = graph.nodes();
        if (nodes.isEmpty()) throw new IllegalArgumentException("GPU density graph has no nodes");
        if (graph.sourceFunctions().size() != nodes.size()) {
            throw new IllegalArgumentException("GPU density graph source-function table has "
                    + graph.sourceFunctions().size() + " entries for " + nodes.size() + " nodes");
        }
        for (int root : graph.routerRoots()) requireNodeIndex(root, nodes.size(), "router root");
        for (int index = 0; index < nodes.size(); index++) {
            GpuDensityGraph.Node node = nodes.get(index);
            for (int child : node.children()) {
                requireNodeIndex(child, nodes.size(), "child of node " + index);
            }
            validateCustomNode(node, index, graph.noises().size());
            if ("GpuInt".equals(node.opcode())
                    && ("IDIV".equals(node.payload()) || "IREM".equals(node.payload()))) {
                int divisorIndex = node.children()[1];
                GpuDensityGraph.Node divisor = nodes.get(divisorIndex);
                double[] value = divisor.constants();
                if (!"Constant".equals(divisor.opcode()) || value.length != 1
                        || !Double.isFinite(value[0]) || value[0] != Math.rint(value[0])
                        || value[0] == 0.0 || value[0] < Integer.MIN_VALUE
                        || value[0] > Integer.MAX_VALUE) {
                    throw new IllegalArgumentException("GPU integer division node " + index
                            + " requires a nonzero constant 32-bit divisor");
                }
            }
        }
        for (int splineIndex = 0; splineIndex < graph.splines().size(); splineIndex++) {
            GpuDensityGraph.Spline spline = graph.splines().get(splineIndex);
            requireNodeIndex(spline.coordinateNode(), nodes.size(), "coordinate of spline " + splineIndex);
            int points = spline.locations().length;
            if (points == 0 || points > 64 || spline.derivatives().length != points
                    || spline.valueNodes().length != points) {
                throw new IllegalArgumentException("GPU spline " + splineIndex
                        + " has inconsistent arrays or exceeds the 64-point shader limit");
            }
            for (int value : spline.valueNodes()) requireNodeIndex(value, nodes.size(), "value of spline " + splineIndex);
        }
    }

    private static void requireNodeIndex(int index, int count, String role) {
        if (index < 0 || index >= count) {
            throw new IllegalArgumentException("GPU density graph has invalid " + role + " index " + index
                    + " (node count " + count + ")");
        }
    }

    private static void validateCustomNode(GpuDensityGraph.Node node, int index, int noiseCount) {
        int children = node.children().length;
        double[] parameters = node.constants();
        int constants = parameters.length;
        boolean valid = switch (node.opcode()) {
            case "Marker" -> children == 1 && constants == 0;
            case "GpuCoordinate" -> children == 0 && constants == 0
                    && node.payload() != null && Set.of("X", "Y", "Z").contains(node.payload());
            case "GpuMath" -> children == 2 && constants == 0
                    && node.payload() != null
                    && Set.of("ADD", "SUBTRACT", "MULTIPLY", "DIVIDE", "MIN", "MAX",
                            "EXPR_ADD", "EXPR_SUBTRACT", "EXPR_MULTIPLY", "EXPR_DIVIDE",
                            "EXPR_MIN", "EXPR_MAX", "REMAINDER", "EXPR_REMAINDER", "ATAN2", "EXPR_ATAN2",
                            "POW", "EXPR_POW", "COPY_SIGN", "EXPR_COPY_SIGN")
                            .contains(node.payload());
            case "GpuCompare" -> children == 2 && constants == 0
                    && node.payload() != null && Set.of("EQ", "NE", "LT", "LE", "GT", "GE",
                            "DCMPL_EQ", "DCMPL_NE", "DCMPL_LT", "DCMPL_LE", "DCMPL_GT", "DCMPL_GE",
                            "DCMPG_EQ", "DCMPG_NE", "DCMPG_LT", "DCMPG_LE", "DCMPG_GT", "DCMPG_GE",
                            "FCMPL_EQ", "FCMPL_NE", "FCMPL_LT", "FCMPL_LE", "FCMPL_GT", "FCMPL_GE",
                            "FCMPG_EQ", "FCMPG_NE", "FCMPG_LT", "FCMPG_LE", "FCMPG_GT", "FCMPG_GE")
                            .contains(node.payload());
            case "GpuSelect" -> children == 3 && constants == 0 && node.payload() == null;
            case "GpuInt" -> (children == 1 && node.payload() != null
                    && Set.of("INEG", "I2B", "I2C", "I2S").contains(node.payload()) || children == 2
                    && node.payload() != null
                    && Set.of("IADD", "ISUB", "IMUL", "IDIV", "IREM", "IAND", "IOR", "IXOR",
                            "ISHL", "ISHR", "IUSHR").contains(node.payload()))
                    && constants == 0;
            case "GpuFloat" -> (children == 1 && "FNEG".equals(node.payload()) || children == 2
                    && node.payload() != null
                    && Set.of("FADD", "FSUB", "FMUL", "FDIV", "FREM", "FCOPY_SIGN").contains(node.payload()))
                    && constants == 0;
            case "GpuCast" -> children == 1 && constants == 0 && node.payload() != null
                    && Set.of("I2F", "D2F", "F2I", "D2I").contains(node.payload());
            case "Noise" -> children == 0 && constants == 3
                    && Double.isFinite(parameters[0]) && parameters[0] == Math.rint(parameters[0])
                    && parameters[0] >= 0 && parameters[0] < noiseCount
                    && Double.isFinite(parameters[1]) && Double.isFinite(parameters[2]);
            case "GpuNoise3" -> children == 3 && constants == 1
                    && Double.isFinite(parameters[0]) && parameters[0] == Math.rint(parameters[0])
                    && parameters[0] >= 0 && parameters[0] < noiseCount;
            default -> true;
        };
        if (!valid) {
            throw new IllegalArgumentException("Malformed GPU density extension node " + index
                    + " (" + node.opcode() + ")");
        }
    }

    /** Iterative DFS avoids JVM stack exhaustion on large graphs supplied by mods. */
    private static int topologicalOrder(GpuDensityGraph graph, byte[] state, int[] order) {
        List<GpuDensityGraph.Node> nodes = graph.nodes();
        int[] nodeStack = new int[nodes.size()];
        int[] childStack = new int[nodes.size()];
        int count = 0;
        int[] roots = graph.routerRoots();
        for (int rootIndex = 0; rootIndex < roots.length; rootIndex++) {
            if (graph.supportedRouterRootMask() != 0 && !graph.supportsRouterRoot(rootIndex)) continue;
            int root = roots[rootIndex];
            if (state[root] == 2) continue;
            int depth = 0;
            nodeStack[depth] = root;
            childStack[depth] = 0;
            state[root] = 1;
            while (depth >= 0) {
                int nodeIndex = nodeStack[depth];
                int[] children = traversalChildren(graph, nodeIndex);
                if (childStack[depth] < children.length) {
                    int child = children[childStack[depth]++];
                    if (state[child] == 1) {
                        throw new IllegalArgumentException("GPU density graph contains a dependency cycle at node " + child);
                    }
                    if (state[child] == 0) {
                        depth++;
                        nodeStack[depth] = child;
                        childStack[depth] = 0;
                        state[child] = 1;
                    }
                } else {
                    state[nodeIndex] = 2;
                    order[count++] = nodeIndex;
                    depth--;
                }
            }
        }
        return count;
    }

    /** Avoids visiting dead branch subgraphs when the selector is statically constant. */
    private static int[] traversalChildren(GpuDensityGraph graph, int nodeIndex) {
        GpuDensityGraph.Node node = graph.nodes().get(nodeIndex);
        if (node.opcode().equals("Marker") && !"Interpolated".equals(node.payload())) {
            return node.children();
        }
        int selected = constantBranch(graph, node);
        if (selected >= 0) return new int[]{selected};
        return node.children();
    }
}
