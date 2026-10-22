package dev.gpunoise;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Emits a graph-specialized GLSL evaluator from the validated GPU instruction stream. */
final class GpuDensityGraphShaderGenerator {
    private static final String BEGIN = "// GPU_NOISE_STATIC_GRAPH_EVAL_BEGIN";
    private static final String END = "// GPU_NOISE_STATIC_GRAPH_EVAL_END";
    private static final char[] HEX_DIGITS = "0123456789abcdef".toCharArray();

    private GpuDensityGraphShaderGenerator() {}

    static String specialize(String source, GpuDensityGraphBuffer.Packed packed, boolean blockProgram) {
        int nodeCount = blockProgram ? packed.blockNodeCount() : packed.nodeCount();
        int nodeOffset = blockProgram ? packed.blockNodeOffset() : 0;
        if (nodeCount <= 0 || nodeCount > PersistentVulkanNormalNoiseEngine.GRAPH_NODE_CAPACITY) {
            throw new IllegalArgumentException("density graph needs " + nodeCount
                    + " static shader instructions; capacity is "
                    + PersistentVulkanNormalNoiseEngine.GRAPH_NODE_CAPACITY);
        }
        int begin = source.indexOf(BEGIN);
        int end = source.indexOf(END);
        if (begin < 0 || end <= begin) {
            throw new IllegalStateException("density shader template has no static graph evaluation markers");
        }
        StringBuilder evaluator = new StringBuilder(Math.multiplyExact(nodeCount, 180));
        emitInstructions(evaluator, packed.nodes(), nodeOffset, nodeCount, blockProgram);
        String specialized = source.substring(0, begin) + evaluator + source.substring(end + END.length());
        if (blockProgram) {
            specialized = specialized.replace("bool blockEvaluation=(reserved & BLOCK_EVALUATION)!=0u;",
                            "bool blockEvaluation=true;")
                    .replace("uint nodeBase=blockEvaluation ? (rootCount >> 5u) : 0u;",
                            "uint nodeBase=" + packed.blockNodeOffset() + "u;");
        } else {
            specialized = specialized.replace("bool blockEvaluation=(reserved & BLOCK_EVALUATION)!=0u;",
                            "bool blockEvaluation=false;")
                    .replace("uint nodeBase=blockEvaluation ? (rootCount >> 5u) : 0u;", "uint nodeBase=0u;");
        }
        if (specialized.contains("for(uint i=0;i<nodeCount;i++)")
                || specialized.contains("switch(op)")) {
            throw new IllegalStateException("specialized density shader retained its dynamic graph interpreter");
        }
        return specialized;
    }

    private static void emitInstructions(StringBuilder out, ByteBuffer packedNodes, int nodeOffset,
                                         int nodeCount, boolean blockProgram) {
        ByteBuffer nodes = packedNodes.duplicate().order(ByteOrder.nativeOrder());
        long requiredBytes = Math.multiplyExact((long) nodeOffset + nodeCount,
                GpuDensityGraphBuffer.NODE_BYTES);
        if (requiredBytes > nodes.limit()) {
            throw new IllegalArgumentException("packed graph instruction stream is truncated");
        }
        nodes.position(Math.multiplyExact(nodeOffset, GpuDensityGraphBuffer.NODE_BYTES));
        for (int index = 0; index < nodeCount; index++) {
            int opcode = nodes.getInt();
            int child0 = nodes.getInt();
            int child1 = nodes.getInt();
            int child2 = nodes.getInt();
            float p0 = nodes.getFloat();
            float p1 = nodes.getFloat();
            float p2 = nodes.getFloat();
            float p3 = nodes.getFloat();
            int operation = nodes.getInt();
            int childCount = nodes.getInt() & ~GpuDensityGraphBuffer.BLOCK_SKIP_FLAG;
            int metadata = nodes.getInt();
            int result = nodes.getInt();
            if (opcode == 0) continue; // aliased interpolation marker; its register already has its value
            if (result < 0 || result >= GpuDensityGraphBuffer.MAX_SHADER_REGISTERS) {
                throw invalid(index, "result register " + result);
            }
            String a = opcode == 7 && blockProgram ? "" : register(child0, index, 0, childCount);
            String b = register(child1, index, 1, childCount);
            String c = register(child2, index, 2, childCount);
            out.append("    values[").append(result).append("]=");
            switch (opcode) {
                case 1 -> out.append(doubleFloat(p0));
                case 2 -> out.append("normalNoiseTerrain(dvec3(pExact.x*double(")
                        .append(doubleFloat(p1)).append("),pExact.y*double(").append(doubleFloat(p2))
                        .append("),pExact.z*double(").append(doubleFloat(p1)).append(")),uint(")
                        .append(doubleFloat(p0)).append("))");
                case 3 -> {
                    requireChildren(index, childCount, 2);
                    if (operation == 1) out.append("graphAdd(").append(a).append(',').append(b).append(')');
                    else if (operation == 2) out.append('(').append(a).append("==0.0?0.0:graphMultiply(")
                            .append(a).append(',').append(b).append("))");
                    else if (operation == 3) out.append("javaMin(").append(a).append(',').append(b).append(')');
                    else if (operation == 4) out.append("javaMax(").append(a).append(',').append(b).append(')');
                    else throw invalid(index, "unknown binary density operation " + operation);
                }
                case 4 -> {
                    requireChildren(index, childCount, 1);
                    out.append(operation == 2 ? "graphMultiply(" : "graphAdd(")
                            .append(a).append(',').append(doubleFloat(p2)).append(')');
                }
                case 5 -> {
                    requireChildren(index, childCount, 1);
                    out.append("clamp(").append(a).append(',').append(doubleFloat(p0)).append(',')
                            .append(doubleFloat(p1)).append(')');
                }
                case 6 -> {
                    requireChildren(index, childCount, 1);
                    emitMapping(out, index, operation, a);
                }
                case 7 -> {
                    requireChildren(index, childCount, 1);
                    if (blockProgram) {
                        out.append("double(interpolatedMarkers[").append(metadata).append("u])");
                    } else out.append(a);
                }
                case 8 -> out.append("normalNoiseTerrain(pExact*.25,uint(").append(doubleFloat(p0)).append("))*4.0");
                case 9 -> out.append("normalNoiseTerrain(dvec3(pExact.x,0.0,pExact.z)*.25,uint(")
                        .append(doubleFloat(p0)).append("))*4.0");
                case 10 -> out.append("normalNoiseTerrain(dvec3(pExact.z,pExact.x,0.0)*.25,uint(")
                        .append(doubleFloat(p0)).append("))*4.0");
                case 11 -> {
                    requireChildren(index, childCount, 3);
                    out.append("normalNoiseTerrain(dvec3(pExact.x*double(").append(doubleFloat(p0))
                            .append(")+values[").append(child0).append("],pExact.y*double(")
                            .append(doubleFloat(p1)).append(")+values[").append(child1)
                            .append("],pExact.z*double(").append(doubleFloat(p0)).append(")+values[")
                            .append(child2).append("]),uint(").append(doubleFloat(p2)).append("))");
                }
                case 12 -> {
                    requireChildren(index, childCount, 1);
                    out.append("splineValue(").append(a).append(",uint(").append(doubleFloat(p0))
                            .append(")*64u,uint(").append(doubleFloat(p1)).append("),values)");
                }
                case 13 -> out.append("blendedNoise64(pExact,uint(").append(doubleFloat(p0)).append("))");
                case 14 -> {
                    out.append("densityGradient(").append(doubleFloat(p2)).append(',').append(doubleFloat(p3))
                            .append(",clamp(graphDivide(graphSubtract(pExact.y,").append(doubleFloat(p0))
                            .append("),graphSubtract(").append(doubleFloat(p1)).append(',').append(doubleFloat(p0))
                            .append(")),0.0,1.0))");
                }
                case 15 -> {
                    requireChildren(index, childCount, 1);
                    out.append(a); // BlendDensity is the vanilla no-blending lerp (0, 1).
                }
                case 16 -> {
                    requireChildren(index, childCount, 3);
                    out.append('(').append(a).append(">=").append(doubleFloat(p0)).append("&&")
                            .append(a).append('<').append(doubleFloat(p1)).append("?").append(b).append(':')
                            .append(c).append(')');
                }
                case 17 -> {
                    requireChildren(index, childCount, 1);
                    out.append("double(spaghettiRarity(float(").append(a).append("),")
                            .append(operation == 0 ? "0u" : "1u").append("))*abs(normalNoiseTerrain(pExact/")
                            .append("double(spaghettiRarity(float(").append(a).append("),")
                            .append(operation == 0 ? "0u" : "1u").append(")),uint(")
                            .append(doubleFloat(p0)).append(")))");
                }
                case 20 -> out.append(operation == 0 ? "pExact.x" : operation == 1 ? "pExact.y" : "pExact.z");
                case 21 -> emitMath(out, index, operation, a, b, childCount);
                case 22 -> {
                    requireChildren(index, childCount, 2);
                    out.append("(compareValues(").append(a).append(',').append(b).append(',')
                            .append(operation).append("u)?1.0:0.0)");
                }
                case 23 -> {
                    requireChildren(index, childCount, 3);
                    out.append('(').append(a).append("!=0.0?").append(b).append(':').append(c).append(')');
                }
                case 24, 25 -> {
                    emitNumericOperation(out, index, opcode, operation, childCount, a, b);
                }
                case 26 -> {
                    requireChildren(index, childCount, 1);
                    out.append("javaNumericCast(").append(operation).append("u,").append(a).append(')');
                }
                case 27 -> {
                    requireChildren(index, childCount, 3);
                    out.append("normalNoiseTerrain(dvec3(").append(a).append(',').append(b).append(',')
                            .append(c).append("),uint(").append(doubleFloat(p0)).append("))");
                }
                default -> throw invalid(index, "unsupported GPU instruction opcode " + opcode);
            }
            out.append(";\n");
        }
    }

    private static void emitMath(StringBuilder out, int index, int operation, String a, String b, int count) {
        requireChildren(index, count, 2);
        out.append(switch (operation) {
            case 1 -> "graphAdd(" + a + "," + b + ")";
            case 2 -> "graphSubtract(" + a + "," + b + ")";
            case 3 -> "graphMultiply(" + a + "," + b + ")";
            case 4 -> "graphDivide(" + a + "," + b + ")";
            case 5 -> "javaMin(" + a + "," + b + ")";
            case 6 -> "javaMax(" + a + "," + b + ")";
            case 7 -> "javaRemainder(" + a + "," + b + ")";
            case 8 -> "javaAtan2(" + a + "," + b + ")";
            case 9 -> "javaPow(" + a + "," + b + ")";
            case 10 -> "javaCopySign(" + a + "," + b + ")";
            default -> throw invalid(index, "unknown Java math operation " + operation);
        });
    }

    private static void emitNumericOperation(StringBuilder out, int index, int opcode, int operation,
                                             int childCount, String left, String right) {
        boolean integer = opcode == 24;
        int maxOperation = integer ? 15 : 9;
        if (operation < 1 || operation > maxOperation) {
            throw invalid(index, "unknown " + (integer ? "Java integer" : "Java float")
                    + " operation " + operation);
        }
        boolean unary = integer ? operation >= 6 && operation <= 9 : operation == 6;
        requireChildren(index, childCount, unary ? 1 : 2);
        out.append(integer ? "javaIntOperation(" : "javaFloatOperation(")
                .append(operation).append("u,").append(left).append(',')
                .append(unary ? "0.0" : right).append(')');
    }

    private static void emitMapping(StringBuilder out, int index, int operation, String value) {
        String x = value;
        switch (operation) {
            case 5 -> out.append("abs(").append(x).append(')');
            case 6 -> out.append("graphMultiply(").append(x).append(',').append(x).append(')');
            case 7 -> out.append("graphMultiply(graphMultiply(").append(x).append(',').append(x)
                    .append("),").append(x).append(')');
            case 8 -> out.append('(').append(x).append(">0.0?").append(x).append(":graphMultiply(")
                    .append(x).append(",0.5))");
            case 9 -> out.append('(').append(x).append(">0.0?").append(x).append(":graphMultiply(")
                    .append(x).append(",0.25))");
            case 10 -> {
                String clamped = "clamp(" + x + ",-1.0,1.0)";
                out.append("graphSubtract(graphMultiply(").append(clamped)
                        .append(",0.5),graphDivide(graphMultiply(graphMultiply(").append(clamped)
                        .append(',').append(clamped).append("),").append(clamped).append("),24.0))");
            }
            case 11 -> out.append("floor(").append(x).append(')');
            case 12 -> out.append("ceil(").append(x).append(')');
            case 13 -> out.append("sqrt(").append(x).append(')');
            case 14 -> out.append("double(sin(float(").append(x).append(")))");
            case 15 -> out.append("double(cos(float(").append(x).append(")))");
            case 16 -> out.append("double(exp(float(").append(x).append(")))");
            case 17 -> out.append("double(log(float(").append(x).append(")))");
            case 18 -> out.append("mappedTanh(").append(x).append(')');
            case 19 -> out.append("double(mthSinValues[uint(int(float(").append(x)
                    .append(")*10430.378)&65535)])");
            case 20 -> out.append("double(mthSinValues[uint(int(float(").append(x)
                    .append(")*10430.378+16384.0)&65535)])");
            case 21 -> out.append("double(sqrt(float(").append(x).append(")))");
            default -> throw invalid(index, "unknown mapped operation " + operation);
        }
    }

    private static String register(int register, int instruction, int child, int childCount) {
        if (child >= childCount) return "";
        if (register < 0 || register >= GpuDensityGraphBuffer.MAX_SHADER_REGISTERS) {
            throw invalid(instruction, "child " + child + " register " + register);
        }
        return "values[" + register + "]";
    }

    private static void requireChildren(int instruction, int actual, int required) {
        if (actual < required) throw invalid(instruction, "needs " + required + " inputs but has " + actual);
    }

    private static IllegalArgumentException invalid(int instruction, String detail) {
        return new IllegalArgumentException("invalid density shader instruction " + instruction + ": " + detail);
    }

    private static String doubleFloat(float value) {
        return "double(uintBitsToFloat(" + unsigned(Float.floatToRawIntBits(value)) + "))";
    }

    private static String unsigned(int value) {
        StringBuilder result = new StringBuilder("0x");
        for (int shift = 28; shift >= 0; shift -= 4) result.append(HEX_DIGITS[(value >>> shift) & 0xf]);
        return result.append('u').toString();
    }
}
