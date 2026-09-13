package dev.gpunoise;

import java.util.List;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import net.minecraft.world.level.levelgen.DensityFunction;

/** Immutable serialized form of one vanilla NoiseRouter graph for GPU compilation. */
record GpuDensityGraph(List<Node> nodes, int[] routerRoots, List<NormalNoise> noises, List<Spline> splines, List<Blended> blended,
                       List<DensityFunction> sourceFunctions, int registerCount) {
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
        this(nodes, routerRoots, noises, splines, blended, sourceFunctions, nodes.size());
    }

    GpuDensityGraph {
        nodes = List.copyOf(nodes);
        routerRoots = routerRoots.clone();
        noises = List.copyOf(noises);
        splines = List.copyOf(splines);
        blended = List.copyOf(blended);
        sourceFunctions = List.copyOf(sourceFunctions);
        if (registerCount <= 0) throw new IllegalArgumentException("register count must be positive");
    }

    @Override public int[] routerRoots() { return routerRoots.clone(); }
}
