package dev.gpunoise;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Emits a graph-specialized GLSL program from the validated GPU instruction stream. */
final class GpuDensityGraphShaderGenerator {
    private static final int MAX_SPECIALIZED_NODES = 8_192;
    private static final char[] HEX_DIGITS = "0123456789abcdef".toCharArray();

    private GpuDensityGraphShaderGenerator() {}

    static String specialize(String source, GpuDensityGraphBuffer.Packed packed, boolean blockProgram) {
        int tableSize = Math.addExact(packed.blockNodeOffset(), packed.blockNodeCount());
        if (tableSize <= 0 || tableSize > MAX_SPECIALIZED_NODES) {
            throw new IllegalArgumentException("density graph needs " + tableSize
                    + " shader constants; specialization limit is " + MAX_SPECIALIZED_NODES);
        }
        ByteBuffer nodes = packed.nodes().duplicate().order(ByteOrder.nativeOrder());
        nodes.position(0);
        if (nodes.remaining() < (long) tableSize * GpuDensityGraphBuffer.NODE_BYTES) {
            throw new IllegalArgumentException("packed graph instruction stream is truncated");
        }
        StringBuilder declarations = new StringBuilder(Math.multiplyExact(tableSize, 112));
        declarations.append("const Node gpunoiseNodes[").append(tableSize).append("] = Node[")
                .append(tableSize).append("](\n");
        for (int node = 0; node < tableSize; node++) {
            if (node != 0) declarations.append(",\n");
            declarations.append("Node(uvec4(");
            for (int field = 0; field < 4; field++) {
                if (field != 0) declarations.append(',');
                appendUnsigned(declarations, nodes.getInt());
            }
            declarations.append("),vec4(");
            for (int field = 0; field < 4; field++) {
                if (field != 0) declarations.append(',');
                declarations.append("uintBitsToFloat(");
                appendUnsigned(declarations, nodes.getInt());
                declarations.append(')');
            }
            declarations.append("),uvec4(");
            for (int field = 0; field < 4; field++) {
                if (field != 0) declarations.append(',');
                appendUnsigned(declarations, nodes.getInt());
            }
            declarations.append("))");
        }
        declarations.append("\n);\n");
        String specialized = source.replace("Node n=nodes[nodeBase+i];", "Node n=gpunoiseNodes[nodeBase+i];")
                .replace("for(uint i=0;i<nodeCount;i++)", "for(uint i=0u;i<SPECIALIZED_NODE_COUNT;i++)");
        if (specialized.equals(source)) {
            throw new IllegalStateException("density shader template did not contain the graph specialization sites");
        }
        int insertion = specialized.indexOf("void main(){");
        if (insertion < 0) throw new IllegalStateException("density shader template has no main function");
        int nodeCount = blockProgram ? packed.blockNodeCount() : packed.nodeCount();
        String defines = "#define SPECIALIZED_NODE_COUNT " + nodeCount + "u\n";
        if (blockProgram) {
            specialized = specialized.replace("uint nodeBase=blockEvaluation ? (rootCount >> 5u) : 0u;",
                            "uint nodeBase=" + packed.blockNodeOffset() + "u;")
                    .replace("bool blockEvaluation=(reserved & BLOCK_EVALUATION)!=0u;", "bool blockEvaluation=true;");
        } else {
            specialized = specialized.replace("uint nodeBase=blockEvaluation ? (rootCount >> 5u) : 0u;", "uint nodeBase=0u;")
                    .replace("bool blockEvaluation=(reserved & BLOCK_EVALUATION)!=0u;", "bool blockEvaluation=false;");
        }
        insertion = specialized.indexOf("void main(){");
        return specialized.substring(0, insertion) + defines + declarations + specialized.substring(insertion);
    }

    private static void appendUnsigned(StringBuilder output, int value) {
        output.append("0x");
        for (int shift = 28; shift >= 0; shift -= 4) output.append(HEX_DIGITS[(value >>> shift) & 0xf]);
        output.append('u');
    }
}
