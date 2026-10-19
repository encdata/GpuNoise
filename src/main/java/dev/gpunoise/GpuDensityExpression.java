package dev.gpunoise;

import java.util.ArrayList;
import java.util.List;
import java.util.IdentityHashMap;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.synth.NormalNoise;

/**
 * Immutable, coordinate-aware expression returned by a custom density
 * translator or the conservative Java bytecode frontend. Expressions are
 * deliberately limited to operations with a defined Vulkan implementation;
 * unsupported bytecode remains on the CPU path.
 */
public final class GpuDensityExpression {
    public enum Operation { ADD, SUBTRACT, MULTIPLY, DIVIDE, MIN, MAX, REMAINDER, ATAN2, POW, COPY_SIGN }
    public enum Mapping {
        ABS, SQUARE, CUBE, HALF_NEGATIVE, QUARTER_NEGATIVE, SQUEEZE,
        FLOOR, CEIL, SQRT, SIN, COS, EXP, LOG, TANH, SIN_FLOAT, COS_FLOAT, SQRT_FLOAT
    }
    public enum Coordinate { X, Y, Z }
    public enum Backend { POINT_SAMPLE, TERRAIN_GRID }

    private static double gpuFloat(double value, String label) {
        float encoded = (float) value;
        if (!Double.isFinite(value) || !Float.isFinite(encoded)) {
            throw new IllegalArgumentException(label + " must be finite and representable by the GPU float ABI");
        }
        return encoded;
    }

    record Node(String opcode, int[] children, double[] constants, String payload) {
        Node {
            children = children.clone();
            constants = constants.clone();
        }
        @Override public int[] children() { return children.clone(); }
        @Override public double[] constants() { return constants.clone(); }
    }
    private record NodeKey(String opcode, List<Integer> children, List<Long> constants, String payload) {}

    private final List<Node> nodes;
    private final List<NormalNoise> noises;
    private final List<DensityFunction> densityReferences;
    private final int root;

    private GpuDensityExpression(List<Node> nodes, List<NormalNoise> noises,
                                 List<DensityFunction> densityReferences, int root) {
        if (nodes.isEmpty() || root < 0 || root >= nodes.size()) {
            throw new IllegalArgumentException("GPU density expression has no valid root");
        }
        boolean[] reachable = new boolean[nodes.size()];
        reachable[root] = true;
        // Builder nodes are topological: every child precedes its consumer.
        // Walking backward from the root therefore marks the complete live
        // subgraph without recursion or an auxiliary stack.
        for (int index = root; index >= 0; index--) {
            if (!reachable[index]) continue;
            for (int child : nodes.get(index).children()) {
                if (child < 0 || child >= index) {
                    throw new IllegalArgumentException("GPU expression node " + index
                            + " must reference earlier nodes only");
                }
                reachable[child] = true;
            }
        }
        boolean[] usedSamplers = new boolean[noises.size()];
        boolean[] usedDensityReferences = new boolean[densityReferences.size()];
        for (int index = 0; index <= root; index++) {
            if (!reachable[index]) continue;
            double[] parameters = nodes.get(index).constants();
            if (nodes.get(index).opcode().equals("Noise") || nodes.get(index).opcode().equals("GpuNoise3")) {
                int sampler = (int) parameters[0];
                if (sampler < 0 || sampler >= noises.size() || sampler != parameters[0]) {
                    throw new IllegalArgumentException("GPU expression references an invalid noise sampler");
                }
                usedSamplers[sampler] = true;
            } else if (nodes.get(index).opcode().equals("DensityRef")) {
                int reference = (int) parameters[0];
                if (reference < 0 || reference >= densityReferences.size() || reference != parameters[0]) {
                    throw new IllegalArgumentException("GPU expression references an invalid density dependency");
                }
                usedDensityReferences[reference] = true;
            }
        }
        int[] samplerRemap = new int[noises.size()];
        List<NormalNoise> compactNoises = new ArrayList<>();
        for (int sampler = 0; sampler < noises.size(); sampler++) {
            if (usedSamplers[sampler]) {
                samplerRemap[sampler] = compactNoises.size();
                compactNoises.add(noises.get(sampler));
            } else {
                samplerRemap[sampler] = -1;
            }
        }
        int[] densityRemap = new int[densityReferences.size()];
        List<DensityFunction> compactDensityReferences = new ArrayList<>();
        for (int reference = 0; reference < densityReferences.size(); reference++) {
            if (usedDensityReferences[reference]) {
                densityRemap[reference] = compactDensityReferences.size();
                compactDensityReferences.add(densityReferences.get(reference));
            } else {
                densityRemap[reference] = -1;
            }
        }
        int[] nodeRemap = new int[nodes.size()];
        List<Node> compactNodes = new ArrayList<>();
        for (int index = 0; index <= root; index++) {
            if (!reachable[index]) continue;
            Node node = nodes.get(index);
            int[] children = node.children();
            for (int child = 0; child < children.length; child++) children[child] = nodeRemap[children[child]];
            double[] parameters = node.constants();
            if (node.opcode().equals("Noise") || node.opcode().equals("GpuNoise3")) {
                parameters[0] = samplerRemap[(int) parameters[0]];
            } else if (node.opcode().equals("DensityRef")) {
                parameters[0] = densityRemap[(int) parameters[0]];
            }
            nodeRemap[index] = compactNodes.size();
            compactNodes.add(new Node(node.opcode(), children, parameters, node.payload()));
        }
        this.nodes = List.copyOf(compactNodes);
        this.noises = List.copyOf(compactNoises);
        this.densityReferences = List.copyOf(compactDensityReferences);
        this.root = nodeRemap[root];
    }

    List<Node> nodes() { return nodes; }
    List<NormalNoise> noises() { return noises; }
    List<DensityFunction> densityReferences() { return densityReferences; }
    int root() { return root; }

    /** Number of live expression nodes after dead-code removal and CSE. */
    public int nodeCount() { return nodes.size(); }

    /** Number of distinct live NormalNoise sampler objects. */
    public int samplerCount() { return noises.size(); }

    /** Root node index after expression compaction. */
    public int rootNode() { return root; }

    /**
     * Returns the first unsupported operation for a shader path, or null when
     * every live node has a defined lowering for that path.
     */
    public String unsupportedOperation(Backend backend) {
        if (backend == null) throw new IllegalArgumentException("GPU expression backend cannot be null");
        for (Node node : nodes) {
            if (backend == Backend.POINT_SAMPLE) continue;
            switch (node.opcode()) {
                case "Constant", "Noise", "GpuNoise3", "DensityRef", "Clamp", "Mapped", "GpuCoordinate",
                        "GpuCompare", "GpuSelect",
                        "GpuInt", "GpuFloat", "GpuCast" -> { }
                case "GpuMath" -> {
                    String operation = node.payload();
                    if (!"ADD".equals(operation) && !"SUBTRACT".equals(operation)
                            && !"MULTIPLY".equals(operation) && !"DIVIDE".equals(operation)
                            && !"ATAN2".equals(operation)
                            && !"POW".equals(operation)
                            && !"COPY_SIGN".equals(operation)
                            && !"MIN".equals(operation) && !"MAX".equals(operation)
                            && !"REMAINDER".equals(operation)) {
                        return "GpuMath(" + operation + ")";
                    }
                }
                default -> { return node.opcode(); }
            }
        }
        return null;
    }

    /**
     * Evaluates the immutable IR on the CPU. This is the reference for shader
     * differential checks; a mod translator must still compare this result to
     * its original DensityFunction before the expression is eligible.
     */
    public double evaluate(double x, double y, double z) {
        double gpuX = x;
        double gpuY = y;
        double gpuZ = z;
        double[] values = new double[nodes.size()];
        for (int index = 0; index < nodes.size(); index++) {
            Node node = nodes.get(index);
            int[] children = node.children();
            double value = switch (node.opcode()) {
                case "Constant" -> node.constants()[0];
                case "GpuCoordinate" -> switch (node.payload()) {
                    case "X" -> gpuX;
                    case "Y" -> gpuY;
                    case "Z" -> gpuZ;
                    default -> throw new IllegalStateException("Unknown GPU coordinate " + node.payload());
                };
                case "Noise" -> {
                    double[] parameters = node.constants();
                    NormalNoise sampler = noises.get((int) parameters[0]);
                    float nx = (float) gpuX * (float) parameters[1];
                    float ny = (float) gpuY * (float) parameters[2];
                    float nz = (float) gpuZ * (float) parameters[1];
                    yield (double) (float) sampler.getValue(nx, ny, nz);
                }
                case "GpuNoise3" -> {
                    double[] parameters = node.constants();
                    NormalNoise sampler = noises.get((int) parameters[0]);
                    yield (double) (float) sampler.getValue((float) values[children[0]],
                            (float) values[children[1]], (float) values[children[2]]);
                }
                case "DensityRef" -> {
                    DensityFunction dependency = densityReferences.get((int) node.constants()[0]);
                    yield dependency.compute(new DensityFunction.SinglePointContext(
                            (int) gpuX, (int) gpuY, (int) gpuZ));
                }
                case "GpuMath" -> {
                    double left = values[children[0]], right = values[children[1]];
                    yield switch (Operation.valueOf(node.payload())) {
                        case ADD -> left + right;
                        case SUBTRACT -> left - right;
                        case MULTIPLY -> left * right;
                        case DIVIDE -> left / right;
                        case MIN -> Math.min(left, right);
                        case MAX -> Math.max(left, right);
                        case REMAINDER -> left % right;
                        case ATAN2 -> Math.atan2(left, right);
                        case POW -> Math.pow(left, right);
                        case COPY_SIGN -> Math.copySign(left, right);
                    };
                }
                case "GpuCompare" -> {
                    double left = values[children[0]], right = values[children[1]];
                    yield compare(node.payload(), left, right) ? 1.0 : 0.0;
                }
                case "GpuSelect" -> values[children[0]] != 0.0
                        ? values[children[1]] : values[children[2]];
                case "GpuInt" -> evaluateInteger(node.payload(), values, children);
                case "GpuFloat" -> evaluateFloat(node.payload(), values, children);
                case "GpuCast" -> evaluateCast(node.payload(), values[children[0]]);
                case "Mapped" -> {
                    double input = values[children[0]];
                    yield switch (Mapping.valueOf(node.payload())) {
                        case ABS -> Math.abs(input);
                        case SQUARE -> input * input;
                        case CUBE -> input * input * input;
                        case FLOOR -> Math.floor(input);
                        case CEIL -> Math.ceil(input);
                        case SQRT -> Math.sqrt(input);
                        case SQRT_FLOAT -> (double) (float) Math.sqrt((float) input);
                        case SIN -> Math.sin(input);
                        case COS -> Math.cos(input);
                        case SIN_FLOAT -> (double) GpuDensityGraphBuffer.mthSin((float) input);
                        case COS_FLOAT -> (double) GpuDensityGraphBuffer.mthCos((float) input);
                        case EXP -> Math.exp(input);
                        case LOG -> Math.log(input);
                        case TANH -> Math.tanh(input);
                        case HALF_NEGATIVE -> input > 0.0 ? input : input * 0.5;
                        case QUARTER_NEGATIVE -> input > 0.0 ? input : input * 0.25;
                        case SQUEEZE -> {
                            double clamped = Math.max(-1.0, Math.min(1.0, input));
                            yield clamped * 0.5 - clamped * clamped * clamped / 24.0;
                        }
                    };
                }
                case "Clamp" -> Math.max(node.constants()[0],
                        Math.min(node.constants()[1], values[children[0]]));
                default -> throw new IllegalStateException("Unknown GPU expression opcode " + node.opcode());
            };
            values[index] = value;
        }
        return values[root];
    }

    private static boolean compare(String operation, double left, double right) {
        if (operation.startsWith("DCMPL_") || operation.startsWith("DCMPG_")
                || operation.startsWith("FCMPL_") || operation.startsWith("FCMPG_")) {
            boolean greaterOnNaN = operation.startsWith("DCMPG_") || operation.startsWith("FCMPG_");
            String relation = operation.substring(6);
            if (Double.isNaN(left) || Double.isNaN(right)) {
                return greaterOnNaN ? relation.equals("NE") || relation.equals("GT") || relation.equals("GE")
                        : relation.equals("NE") || relation.equals("LT") || relation.equals("LE");
            }
            operation = relation;
        }
        return switch (operation) {
            case "EQ" -> left == right;
            case "NE" -> left != right;
            case "LT" -> left < right;
            case "LE" -> left <= right;
            case "GT" -> left > right;
            case "GE" -> left >= right;
            default -> throw new IllegalStateException("Unknown GPU comparison " + operation);
        };
    }

    private static double evaluateInteger(String operation, double[] values, int[] children) {
        int left = (int) values[children[0]];
        if (operation.equals("INEG")) return -left;
        if (operation.equals("I2B")) return (byte) left;
        if (operation.equals("I2C")) return (char) left;
        if (operation.equals("I2S")) return (short) left;
        int right = (int) values[children[1]];
        return switch (operation) {
            case "IADD" -> left + right;
            case "ISUB" -> left - right;
            case "IMUL" -> left * right;
            case "IDIV" -> left / right;
            case "IREM" -> left % right;
            case "IAND" -> left & right;
            case "IOR" -> left | right;
            case "IXOR" -> left ^ right;
            case "ISHL" -> left << (right & 31);
            case "ISHR" -> left >> (right & 31);
            case "IUSHR" -> left >>> (right & 31);
            default -> throw new IllegalStateException("Unknown GPU integer operation " + operation);
        };
    }

    private static double evaluateFloat(String operation, double[] values, int[] children) {
        float left = (float) values[children[0]];
        if (operation.equals("FNEG")) return (double) -left;
        float right = (float) values[children[1]];
        float result = switch (operation) {
            case "FADD" -> left + right;
            case "FSUB" -> left - right;
            case "FMUL" -> left * right;
            case "FDIV" -> left / right;
            case "FREM" -> left % right;
            default -> throw new IllegalStateException("Unknown GPU float operation " + operation);
        };
        return (double) result;
    }

    private static double evaluateCast(String operation, double value) {
        return switch (operation) {
            case "I2F" -> (double) (float) (int) value;
            case "D2F" -> (double) (float) value;
            case "F2I" -> (double) (int) (float) value;
            case "D2I" -> (double) (int) value;
            default -> throw new IllegalStateException("Unknown GPU numeric conversion " + operation);
        };
    }

    /** Builds a single expression; each returned value can feed later nodes. */
    public static final class Builder {
        private final List<Node> nodes = new ArrayList<>();
        private final List<Double> knownConstants = new ArrayList<>();
        private final List<NormalNoise> noises = new ArrayList<>();
        private final Map<NormalNoise, Integer> noiseIndices = new IdentityHashMap<>();
        private final List<DensityFunction> densityReferences = new ArrayList<>();
        private final Map<DensityFunction, Integer> densityReferenceIndices = new IdentityHashMap<>();
        private final Map<NodeKey, Integer> commonNodes = new HashMap<>();

        public int coordinate(Coordinate coordinate) {
            return append("GpuCoordinate", new int[0], new double[0], coordinate.name());
        }

        public int constant(double value) {
            if (!Double.isFinite(value)) throw new IllegalArgumentException("GPU constants must be finite");
            return append("Constant", new int[0], new double[]{gpuFloat(value, "GPU constant")}, null);
        }

        Double constantValue(int index) {
            requireValue(index);
            return knownConstants.get(index);
        }

        /**
         * Adds a Minecraft noise sampler with explicit horizontal and vertical
         * coordinate scales. The sampler is serialized with the graph and
         * reused when the same object is referenced more than once.
         */
        public int noise(NormalNoise sampler, double horizontalScale, double verticalScale) {
            if (sampler == null) throw new IllegalArgumentException("GPU noise sampler cannot be null");
            horizontalScale = gpuFloat(horizontalScale, "GPU horizontal noise scale");
            verticalScale = gpuFloat(verticalScale, "GPU vertical noise scale");
            int samplerIndex = noiseIndices.computeIfAbsent(sampler, key -> {
                noises.add(key);
                return noises.size() - 1;
            });
            return append("Noise", new int[0],
                    new double[]{samplerIndex, horizontalScale, verticalScale}, null);
        }

        /** Adds a sampler at three already translated Java coordinate expressions. */
        public int noise(NormalNoise sampler, int x, int y, int z) {
            if (sampler == null) throw new IllegalArgumentException("GPU noise sampler cannot be null");
            requireValue(x); requireValue(y); requireValue(z);
            int samplerIndex = noiseIndices.computeIfAbsent(sampler, key -> {
                noises.add(key);
                return noises.size() - 1;
            });
            return append("GpuNoise3", new int[]{x, y, z}, new double[]{samplerIndex}, null);
        }

        /** Adds a nested DensityFunction dependency for graph-level recursive lowering. */
        public int density(DensityFunction dependency) {
            if (dependency == null) throw new IllegalArgumentException("GPU density dependency cannot be null");
            int dependencyIndex = densityReferenceIndices.computeIfAbsent(dependency, key -> {
                densityReferences.add(key);
                return densityReferences.size() - 1;
            });
            return append("DensityRef", new int[0], new double[]{dependencyIndex}, null);
        }

        public int apply(Operation operation, int left, int right) {
            requireValue(left); requireValue(right);
            Double leftConstant = knownConstants.get(left), rightConstant = knownConstants.get(right);
            if (leftConstant != null && rightConstant != null) {
                double folded = switch (operation) {
                    case ADD -> leftConstant + rightConstant;
                    case SUBTRACT -> leftConstant - rightConstant;
                    case MULTIPLY -> leftConstant * rightConstant;
                    case DIVIDE -> leftConstant / rightConstant;
                    case MIN -> Math.min(leftConstant, rightConstant);
                    case MAX -> Math.max(leftConstant, rightConstant);
                    case REMAINDER -> leftConstant % rightConstant;
                    case ATAN2 -> Math.atan2(leftConstant, rightConstant);
                    case POW -> Math.pow(leftConstant, rightConstant);
                    case COPY_SIGN -> Math.copySign(leftConstant, rightConstant);
                };
                if (Double.isFinite(folded) && (double) (float) folded == folded) return constant(folded);
            }
            return append("GpuMath", new int[]{left, right}, new double[0], operation.name());
        }

        public int map(Mapping mapping, int input) {
            requireValue(input);
            Double inputConstant = knownConstants.get(input);
            if (inputConstant != null) {
                double folded = mapConstant(mapping, inputConstant);
                if (Double.isFinite(folded) && (double) (float) folded == folded) return constant(folded);
            }
            return append("Mapped", new int[]{input}, new double[0], mapping.name());
        }

        public int clamp(int input, double minimum, double maximum) {
            requireValue(input);
            minimum = gpuFloat(minimum, "GPU clamp minimum");
            maximum = gpuFloat(maximum, "GPU clamp maximum");
            if (minimum > maximum) throw new IllegalArgumentException("GPU clamp bounds must be ordered");
            Double inputConstant = knownConstants.get(input);
            if (inputConstant != null) {
                return constant(Math.max(minimum, Math.min(maximum, inputConstant)));
            }
            return append("Clamp", new int[]{input}, new double[]{minimum, maximum}, null);
        }

        /** Emits an IEEE-aware comparison that produces exactly 0.0 or 1.0. */
        public int compare(String operation, int left, int right) {
            requireValue(left); requireValue(right);
            if (!List.of("EQ", "NE", "LT", "LE", "GT", "GE",
                    "DCMPL_EQ", "DCMPL_NE", "DCMPL_LT", "DCMPL_LE", "DCMPL_GT", "DCMPL_GE",
                    "DCMPG_EQ", "DCMPG_NE", "DCMPG_LT", "DCMPG_LE", "DCMPG_GT", "DCMPG_GE")
                    .contains(operation)) {
                throw new IllegalArgumentException("Unsupported GPU comparison " + operation);
            }
            Double a = knownConstants.get(left), b = knownConstants.get(right);
            if (a != null && b != null) {
                return constant(GpuDensityExpression.compare(operation, a, b) ? 1.0 : 0.0);
            }
            return append("GpuCompare", new int[]{left, right}, new double[0], operation);
        }

        public int integerOperation(String operation, int left, int right) {
            requireValue(left);
            requireValue(right);
            if (!List.of("IADD", "ISUB", "IMUL", "IDIV", "IREM", "IAND", "IOR", "IXOR",
                    "ISHL", "ISHR", "IUSHR").contains(operation))
                throw new IllegalArgumentException("unsupported GPU integer operation " + operation);
            Double a = knownConstants.get(left), b = knownConstants.get(right);
            if (a != null && b != null) {
                int x = (int) (double) a, y = (int) (double) b;
                if ((operation.equals("IDIV") || operation.equals("IREM")) && y == 0)
                    throw new IllegalArgumentException("integer division by zero is not representable in the GPU IR");
                int folded = switch (operation) {
                    case "IADD" -> x + y;
                    case "ISUB" -> x - y;
                    case "IMUL" -> x * y;
                    case "IDIV" -> x / y;
                    case "IREM" -> x % y;
                    case "IAND" -> x & y;
                    case "IOR" -> x | y;
                    case "IXOR" -> x ^ y;
                    case "ISHL" -> x << (y & 31);
                    case "ISHR" -> x >> (y & 31);
                    case "IUSHR" -> x >>> (y & 31);
                    default -> throw new AssertionError(operation);
                };
                if ((double) (float) folded == folded) return constant(folded);
            }
            return append("GpuInt", new int[]{left, right}, new double[0], operation);
        }

        public int integerNegate(int input) {
            requireValue(input);
            Double value = knownConstants.get(input);
            if (value != null) {
                int folded = -(int) (double) value;
                if ((double) (float) folded == folded) return constant(folded);
            }
            return append("GpuInt", new int[]{input}, new double[0], "INEG");
        }

        public int integerConversion(String operation, int input) {
            requireValue(input);
            if (!List.of("I2B", "I2C", "I2S").contains(operation))
                throw new IllegalArgumentException("unsupported GPU integer conversion " + operation);
            Double value = knownConstants.get(input);
            if (value != null) {
                int integer = (int) (double) value;
                return constant(switch (operation) {
                    case "I2B" -> (byte) integer;
                    case "I2C" -> (char) integer;
                    case "I2S" -> (short) integer;
                    default -> throw new AssertionError(operation);
                });
            }
            return append("GpuInt", new int[]{input}, new double[0], operation);
        }

        public int floatOperation(String operation, int left, int right) {
            requireValue(left);
            Double leftConstant = knownConstants.get(left);
            if (operation.equals("FNEG")) {
                if (leftConstant != null) {
                    float folded = -(float) (double) leftConstant;
                    if (Float.isFinite(folded)) return constant(folded);
                }
                return append("GpuFloat", new int[]{left}, new double[0], operation);
            }
            requireValue(right);
            if (!List.of("FADD", "FSUB", "FMUL", "FDIV", "FREM").contains(operation))
                throw new IllegalArgumentException("unsupported GPU float operation " + operation);
            Double rightConstant = knownConstants.get(right);
            if (leftConstant != null && rightConstant != null) {
                float x = (float) (double) leftConstant, y = (float) (double) rightConstant;
                float folded = switch (operation) {
                    case "FADD" -> x + y;
                    case "FSUB" -> x - y;
                    case "FMUL" -> x * y;
                    case "FDIV" -> x / y;
                    case "FREM" -> x % y;
                    default -> throw new AssertionError(operation);
                };
                if (Float.isFinite(folded)) return constant(folded);
            }
            return append("GpuFloat", new int[]{left, right}, new double[0], operation);
        }

        public int cast(String operation, int input) {
            requireValue(input);
            if (!List.of("I2F", "D2F", "F2I", "D2I").contains(operation))
                throw new IllegalArgumentException("unsupported GPU numeric conversion " + operation);
            Double value = knownConstants.get(input);
            if (value != null) {
                double folded = switch (operation) {
                    case "I2F" -> (double) (float) (int) (double) value;
                    case "D2F" -> (double) (float) (double) value;
                    case "F2I" -> (double) (int) (float) (double) value;
                    case "D2I" -> (double) (int) (double) value;
                    default -> throw new AssertionError(operation);
                };
                if (Double.isFinite(folded) && (double) (float) folded == folded) return constant(folded);
            }
            return append("GpuCast", new int[]{input}, new double[0], operation);
        }

        /** Selects one branch from two pure values in the immutable graph. */
        public int select(int condition, int whenTrue, int whenFalse) {
            requireValue(condition); requireValue(whenTrue); requireValue(whenFalse);
            Double known = knownConstants.get(condition);
            if (known != null) return known != 0.0 ? whenTrue : whenFalse;
            if (whenTrue == whenFalse) return whenTrue;
            return append("GpuSelect", new int[]{condition, whenTrue, whenFalse}, new double[0], null);
        }

        public GpuDensityExpression build(int root) {
            requireValue(root);
            return new GpuDensityExpression(nodes, noises, densityReferences, root);
        }

        private int append(String opcode, int[] children, double[] constants, String payload) {
            NodeKey key = key(opcode, children, constants, payload);
            Integer existing = commonNodes.get(key);
            if (existing != null) return existing;
            if (nodes.size() >= PersistentVulkanNormalNoiseEngine.GRAPH_NODE_CAPACITY) {
                throw new IllegalArgumentException("GPU density expression exceeds the Vulkan graph-node "
                        + "capacity of " + PersistentVulkanNormalNoiseEngine.GRAPH_NODE_CAPACITY);
            }
            int index = nodes.size();
            nodes.add(new Node(opcode, children, constants, payload));
            knownConstants.add(opcode.equals("Constant") ? constants[0] : null);
            commonNodes.put(key, index);
            return index;
        }

        private static NodeKey key(String opcode, int[] children, double[] constants, String payload) {
            List<Integer> childKey = new ArrayList<>(children.length);
            for (int child : children) childKey.add(child);
            List<Long> constantKey = new ArrayList<>(constants.length);
            for (double constant : constants) constantKey.add(Double.doubleToLongBits(constant));
            return new NodeKey(opcode, List.copyOf(childKey), List.copyOf(constantKey), payload);
        }

        private static double mapConstant(Mapping mapping, double input) {
            return switch (mapping) {
                case ABS -> Math.abs(input);
                case SQUARE -> input * input;
                case CUBE -> input * input * input;
                case FLOOR -> Math.floor(input);
                case CEIL -> Math.ceil(input);
                case SQRT -> Math.sqrt(input);
                case SQRT_FLOAT -> (double) (float) Math.sqrt((float) input);
                case SIN -> Math.sin(input);
                case COS -> Math.cos(input);
                case SIN_FLOAT -> (double) GpuDensityGraphBuffer.mthSin((float) input);
                case COS_FLOAT -> (double) GpuDensityGraphBuffer.mthCos((float) input);
                case EXP -> Math.exp(input);
                case LOG -> Math.log(input);
                case TANH -> Math.tanh(input);
                case HALF_NEGATIVE -> input > 0.0 ? input : input * 0.5;
                case QUARTER_NEGATIVE -> input > 0.0 ? input : input * 0.25;
                case SQUEEZE -> {
                    double clamped = Math.max(-1.0, Math.min(1.0, input));
                    yield clamped * 0.5 - clamped * clamped * clamped / 24.0;
                }
            };
        }

        private void requireValue(int index) {
            if (index < 0 || index >= nodes.size()) {
                throw new IllegalArgumentException("GPU expression inputs must refer to earlier nodes: " + index);
            }
        }
    }
}
