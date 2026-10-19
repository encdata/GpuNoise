package dev.gpunoise;

import java.util.List;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import net.minecraft.world.level.levelgen.DensityFunction;

/** Immutable serialized form of one vanilla NoiseRouter graph for GPU compilation. */
record GpuDensityGraph(List<Node> nodes, int[] routerRoots, List<NormalNoise> noises, List<Spline> splines, List<Blended> blended,
                       List<DensityFunction> sourceFunctions, int registerCount,
                       int supportedRouterRootMask, String[] unsupportedRouterRootReasons) {
    record Node(String opcode, int[] children, double[] constants, String payload, int register) {
        Node(String opcode, int[] children, double[] constants, String payload) {
            this(opcode, children, constants, payload, -1);
        }
        Node {
            children = children.clone();
            constants = constants.clone();
        }

        @Override public int[] children() { return children.clone(); }
        @Override public double[] constants() { return constants.clone(); }
    }
    record Spline(int coordinateNode, float[] locations, float[] derivatives, int[] valueNodes) {
        Spline { locations = locations.clone(); derivatives = derivatives.clone(); valueNodes = valueNodes.clone(); }
        @Override public float[] locations() { return locations.clone(); }
        @Override public float[] derivatives() { return derivatives.clone(); }
        @Override public int[] valueNodes() { return valueNodes.clone(); }
    }
    record Blended(MinecraftPerlinData minLimit, MinecraftPerlinData maxLimit, MinecraftPerlinData main,
                   double xzMultiplier, double yMultiplier, double xzFactor, double yFactor, double smearScaleMultiplier) {}

    GpuDensityGraph(List<Node> nodes, int[] routerRoots, List<NormalNoise> noises, List<Spline> splines,
                    List<Blended> blended, List<DensityFunction> sourceFunctions) {
        this(nodes, routerRoots, noises, splines, blended, sourceFunctions, nodes.size(),
                completeRootMask(routerRoots.length), new String[routerRoots.length]);
    }

    GpuDensityGraph(List<Node> nodes, int[] routerRoots, List<NormalNoise> noises, List<Spline> splines,
                    List<Blended> blended, List<DensityFunction> sourceFunctions, int registerCount) {
        this(nodes, routerRoots, noises, splines, blended, sourceFunctions, registerCount,
                completeRootMask(routerRoots.length), new String[routerRoots.length]);
    }

    GpuDensityGraph(List<Node> nodes, int[] routerRoots, List<NormalNoise> noises, List<Spline> splines,
                    List<Blended> blended, List<DensityFunction> sourceFunctions,
                    int supportedRouterRootMask, String[] unsupportedRouterRootReasons) {
        this(nodes, routerRoots, noises, splines, blended, sourceFunctions, nodes.size(),
                supportedRouterRootMask, unsupportedRouterRootReasons);
    }

    GpuDensityGraph {
        nodes = List.copyOf(nodes);
        routerRoots = routerRoots.clone();
        unsupportedRouterRootReasons = unsupportedRouterRootReasons.clone();
        noises = List.copyOf(noises);
        splines = List.copyOf(splines);
        blended = List.copyOf(blended);
        sourceFunctions = List.copyOf(sourceFunctions);
        if (registerCount <= 0) throw new IllegalArgumentException("register count must be positive");
        if (routerRoots.length > Integer.SIZE - 1
                || unsupportedRouterRootReasons.length != routerRoots.length) {
            throw new IllegalArgumentException("router root support metadata does not match the root table");
        }
        int validMask = completeRootMask(routerRoots.length);
        if ((supportedRouterRootMask & ~validMask) != 0) {
            throw new IllegalArgumentException("router root support mask contains an unknown root");
        }
        for (int root = 0; root < routerRoots.length; root++) {
            boolean supported = (supportedRouterRootMask & (1 << root)) != 0;
            if (supported == (unsupportedRouterRootReasons[root] != null)) {
                throw new IllegalArgumentException("router root " + root
                        + " must have either GPU support or an unsupported reason");
            }
        }
    }

    @Override public int[] routerRoots() { return routerRoots.clone(); }
    @Override public String[] unsupportedRouterRootReasons() { return unsupportedRouterRootReasons.clone(); }

    boolean supportsRouterRoot(int root) {
        return root >= 0 && root < routerRoots.length && (supportedRouterRootMask & (1 << root)) != 0;
    }

    /** Registers whose NoiseChunk marker requires per-function cell interpolation. */
    int[] interpolatedMarkerRegisters() {
        return nodes.stream()
                .filter(node -> node.opcode().equals("Marker") && "Interpolated".equals(node.payload()))
                .mapToInt(Node::register)
                .toArray();
    }

    private static int completeRootMask(int roots) {
        if (roots < 0 || roots > Integer.SIZE - 1) {
            throw new IllegalArgumentException("router graph has unsupported root count " + roots);
        }
        return roots == 0 ? 0 : (1 << roots) - 1;
    }
}
