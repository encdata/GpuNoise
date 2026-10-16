package dev.gpunoise;

import java.io.InputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Compiles a conservative, side-effect-free subset of custom Java density
 * implementations into the same IR used by the Vulkan graph compiler.
 *
 * <p>This is deliberately a bytecode compiler rather than a class-name
 * heuristic. It only accepts methods whose complete instruction stream can
 * be represented by the IR. Forward conditionals become GPU compare/select
 * nodes. Loops, mutable fields, unknown calls, or unsupported values reject
 * the whole method so the original Java function remains authoritative.</p>
 */
final class GpuDensityBytecodeCompiler {
    private static final String CONTEXT_DESC =
            "Lnet/minecraft/world/level/levelgen/DensityFunction$FunctionContext;";
    private static final String COMPUTE_DESC = "(" + CONTEXT_DESC + ")D";
    private static final int MAX_STATIC_LOOP_ITERATIONS = 64;

    record Result(GpuDensityExpression expression, String rejection) {
        Result {
            if ((expression == null) == (rejection == null))
                throw new IllegalArgumentException("bytecode result must contain exactly one outcome");
        }
        static Result accepted(GpuDensityExpression expression) { return new Result(expression, null); }
        static Result rejected(String reason) { return new Result(null, reason); }
    }

    private GpuDensityBytecodeCompiler() {}

    static Result compile(DensityFunction function) {
        Objects.requireNonNull(function, "function");
        Class<?> type = function.getClass();
        if (type.getName().startsWith("net.minecraft."))
            return Result.rejected("Minecraft implementation is handled by the vanilla graph lowering");
        try {
            ClassNode owner = null;
            MethodNode compute = null;
            // Density implementations often inherit a shared compute method
            // from a mod's abstract base class. Resolve Java's virtual method
            // lookup before interpreting bytecode, rather than requiring each
            // concrete density class to redeclare the method.
            for (Class<?> current = type; current != null && current != DensityFunction.class;
                    current = current.getSuperclass()) {
                ClassNode candidate = readClassNode(current, Type.getInternalName(current));
                MethodNode method = findMethod(candidate, "compute", COMPUTE_DESC);
                if (method != null && (method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) == 0) {
                    owner = candidate;
                    compute = method;
                    break;
                }
            }
            if (compute == null) {
                Class<?> defaultOwner = findDefaultComputeInterface(type);
                if (defaultOwner != null) {
                    owner = readClassNode(defaultOwner, Type.getInternalName(defaultOwner));
                    compute = findMethod(owner, "compute", COMPUTE_DESC);
                }
            }
            if (compute == null) return Result.rejected("compute(FunctionContext):double bytecode was not found in the density class hierarchy");
            Interpreter interpreter = new Interpreter(function, owner, compute,
                    new GpuDensityExpression.Builder(), 0, new int[]{50_000}, Value.receiver(Kind.THIS),
                    new Value[]{Value.receiver(Kind.CONTEXT)});
            return interpreter.run();
        } catch (UnsupportedBytecode rejected) {
            return Result.rejected(rejected.getMessage());
        } catch (RuntimeException failure) {
            return Result.rejected("bytecode inspection failed: " + failureSummary(failure));
        }
    }

    private static ClassNode readClassNode(Class<?> resourceOwner, String internalName) {
        String resource = "/" + internalName + ".class";
        try (InputStream bytecode = resourceOwner.getResourceAsStream(resource)) {
            if (bytecode == null) throw new UnsupportedBytecode(
                    "class bytecode resource is unavailable: " + internalName.replace('/', '.'));
            ClassNode node = new ClassNode(Opcodes.ASM9);
            new ClassReader(bytecode).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return node;
        } catch (IOException failure) {
            throw new UnsupportedBytecode("cannot read helper bytecode " + internalName.replace('/', '.')
                    + ": " + failureSummary(failure));
        }
    }

    private static MethodNode findMethod(ClassNode owner, String name, String descriptor) {
        for (MethodNode method : owner.methods) {
            if (method.name.equals(name) && method.desc.equals(descriptor)) return method;
        }
        return null;
    }

    private record ResolvedMethod(ClassNode owner, MethodNode method) {}

    private static ResolvedMethod resolveHelperMethod(Class<?> receiverType, String symbolicOwner,
                                                       String name, String descriptor,
                                                       boolean staticCall) {
        Class<?> symbolicClass = resolveOwnerClass(receiverType, symbolicOwner);
        if (staticCall) {
            for (Class<?> current = symbolicClass; current != null; current = current.getSuperclass()) {
                ClassNode node = readClassNode(receiverType, Type.getInternalName(current));
                MethodNode method = findMethod(node, name, descriptor);
                if (method != null && (method.access & Opcodes.ACC_STATIC) != 0)
                    return new ResolvedMethod(node, method);
            }
            return null;
        }
        if (!symbolicClass.isAssignableFrom(receiverType)) return null;
        if (!symbolicClass.isInterface()) {
            boolean reachedSymbolicOwner = false;
            for (Class<?> current = receiverType; current != null; current = current.getSuperclass()) {
                if (current == symbolicClass) reachedSymbolicOwner = true;
                if (!reachedSymbolicOwner && !symbolicClass.isAssignableFrom(current)) continue;
                ClassNode node = readClassNode(receiverType, Type.getInternalName(current));
                MethodNode method = findMethod(node, name, descriptor);
                if (method == null || (method.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT)) != 0)
                    continue;
                if (current != symbolicClass && (method.access & Opcodes.ACC_PRIVATE) != 0) continue;
                return new ResolvedMethod(node, method);
            }
        }

        Set<Class<?>> interfaces = new HashSet<>();
        collectInterfaces(symbolicClass.isInterface() ? new Class<?>[]{symbolicClass}
                : symbolicClass.getInterfaces(), interfaces);
        List<ResolvedMethod> candidates = new ArrayList<>();
        for (Class<?> type : interfaces) {
            ClassNode node = readClassNode(receiverType, Type.getInternalName(type));
            MethodNode method = findMethod(node, name, descriptor);
            if (method != null && (method.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT)) == 0)
                candidates.add(new ResolvedMethod(node, method));
        }
        ResolvedMethod selected = null;
        for (ResolvedMethod candidate : candidates) {
            Class<?> candidateType = resolveOwnerClass(receiverType, candidate.owner().name);
            boolean shadowed = false;
            for (ResolvedMethod other : candidates) {
                Class<?> otherType = resolveOwnerClass(receiverType, other.owner().name);
                if (candidateType != otherType && candidateType.isAssignableFrom(otherType)) {
                    shadowed = true;
                    break;
                }
            }
            if (!shadowed) {
                if (selected != null) return null;
                selected = candidate;
            }
        }
        return selected;
    }

    private static Class<?> findDefaultComputeInterface(Class<?> densityType) {
        Set<Class<?>> interfaces = new HashSet<>();
        for (Class<?> current = densityType; current != null; current = current.getSuperclass()) {
            collectInterfaces(current.getInterfaces(), interfaces);
        }
        List<Class<?>> candidates = new ArrayList<>();
        for (Class<?> candidate : interfaces) {
            if (!DensityFunction.class.isAssignableFrom(candidate)) continue;
            try {
                java.lang.reflect.Method method = candidate.getDeclaredMethod("compute",
                        DensityFunction.FunctionContext.class);
                int modifiers = method.getModifiers();
                if (method.getReturnType() == double.class && !Modifier.isStatic(modifiers)
                        && !Modifier.isAbstract(modifiers)) candidates.add(candidate);
            } catch (NoSuchMethodException ignored) {
                // The implementation may be inherited from a parent interface.
            }
        }
        Class<?> selected = null;
        for (Class<?> candidate : candidates) {
            boolean shadowed = false;
            for (Class<?> other : candidates) {
                if (candidate != other && candidate.isAssignableFrom(other)) {
                    shadowed = true;
                    break;
                }
            }
            if (!shadowed) {
                if (selected != null) throw new UnsupportedBytecode(
                        "multiple unrelated density interfaces provide default compute implementations");
                selected = candidate;
            }
        }
        return selected;
    }

    private static void collectInterfaces(Class<?>[] interfaces, Set<Class<?>> collected) {
        for (Class<?> type : interfaces) {
            if (collected.add(type)) collectInterfaces(type.getInterfaces(), collected);
        }
    }

    private static boolean isInlineableHelperOwner(String internalName) {
        return !(internalName.startsWith("java/") || internalName.startsWith("javax/")
                || internalName.startsWith("jdk/") || internalName.startsWith("sun/")
                || internalName.startsWith("net/minecraft/")
                || internalName.startsWith("org/lwjgl/"));
    }

    private static boolean isDensityHierarchyOwner(Class<?> densityType, String internalName) {
        for (Class<?> current = densityType;
                current != null && current != DensityFunction.class;
                current = current.getSuperclass()) {
            if (current.getName().startsWith("net.minecraft.")) continue;
            if (Type.getInternalName(current).equals(internalName)) return true;
            for (Class<?> implemented : current.getInterfaces()) {
                if (isDensityHierarchyInterface(implemented, internalName)) return true;
            }
        }
        return false;
    }

    private static boolean isDensityHierarchyInterface(Class<?> type, String internalName) {
        if (type.getName().startsWith("net.minecraft.")) return false;
        if (Type.getInternalName(type).equals(internalName)) return true;
        for (Class<?> parent : type.getInterfaces()) {
            if (isDensityHierarchyInterface(parent, internalName)) return true;
        }
        return false;
    }

    private static boolean isObjectHierarchyOwner(Class<?> objectType, String internalName) {
        for (Class<?> current = objectType; current != null; current = current.getSuperclass()) {
            if (Type.getInternalName(current).equals(internalName)) return true;
            for (Class<?> implemented : current.getInterfaces()) {
                if (isObjectHierarchyInterface(implemented, internalName)) return true;
            }
        }
        return false;
    }

    private static boolean isObjectHierarchyInterface(Class<?> type, String internalName) {
        if (Type.getInternalName(type).equals(internalName)) return true;
        for (Class<?> parent : type.getInterfaces()) {
            if (isObjectHierarchyInterface(parent, internalName)) return true;
        }
        return false;
    }

    private static String failureSummary(Exception failure) {
        String message = failure.getMessage();
        return failure.getClass().getSimpleName()
                + (message == null || message.isBlank() ? "" : ": " + message);
    }

    private enum Kind { VALUE, THIS, CONTEXT, NOISE, DENSITY, ARRAY, OBJECT, COMPARE }
    private enum Axis { X, Y, Z }
    private record LoopRegion(int firstInstruction, int backEdgeInstruction) {}

    private record Value(Kind kind, int expression, NormalNoise noise, DensityFunction density,
                         Object referenceValue,
                         Axis axis, double axisScale, Double constant,
                         int comparisonRight, String comparisonMode) {
        static Value value(int expression, Double constant) {
            return new Value(Kind.VALUE, expression, null, null, null, null, 0.0, constant, -1, null);
        }
        static Value coordinate(int expression, Axis axis, double scale) {
            return new Value(Kind.VALUE, expression, null, null, null, axis, scale, null, -1, null);
        }
        static Value receiver(Kind kind) {
            return new Value(kind, -1, null, null, null, null, 0.0, null, -1, null);
        }
        static Value noise(NormalNoise noise) {
            return new Value(Kind.NOISE, -1, noise, null, null, null, 0.0, null, -1, null);
        }
        static Value density(DensityFunction density) {
            return new Value(Kind.DENSITY, -1, null, density, null, null, 0.0, null, -1, null);
        }
        static Value array(Object value) {
            return new Value(Kind.ARRAY, -1, null, null, value, null, 0.0, null, -1, null);
        }
        static Value object(Object value) {
            return new Value(Kind.OBJECT, -1, null, null, value, null, 0.0, null, -1, null);
        }
        static Value comparison(int left, int right, String mode) {
            return new Value(Kind.COMPARE, left, null, null, null, null, 0.0, null, right, mode);
        }
    }

    private static final class Interpreter {
        private final DensityFunction function;
        private final ClassNode owner;
        private final MethodNode method;
        private final GpuDensityExpression.Builder builder;
        private final int inlineDepth;
        private final int[] remainingInstructionBudget;
        private List<Value> stack = new ArrayList<>();
        private Map<Integer, Value> locals = new HashMap<>();
        private final AbstractInsnNode[] instructions;
        private final Map<LabelNode, Integer> labels = new java.util.IdentityHashMap<>();
        private final List<LoopRegion> loopRegions = new ArrayList<>();
        private int branchForks;
        private Map<Integer, Integer> loopIterations = new HashMap<>();

        Interpreter(DensityFunction function, ClassNode owner, MethodNode method,
                    GpuDensityExpression.Builder builder, int inlineDepth, int[] remainingInstructionBudget,
                    Value receiver, Value[] arguments) {
            this.function = function;
            this.owner = owner;
            this.method = method;
            this.builder = builder;
            this.inlineDepth = inlineDepth;
            this.remainingInstructionBudget = remainingInstructionBudget;
            this.instructions = method.instructions.toArray();
            for (int i = 0; i < instructions.length; i++) {
                if (instructions[i] instanceof LabelNode label) labels.put(label, i);
            }
            for (int i = 0; i < instructions.length; i++) {
                if (instructions[i] instanceof JumpInsnNode jump
                        && (jump.getOpcode() == Opcodes.GOTO || branchRelation(jump.getOpcode()) != null)) {
                    Integer target = labels.get(jump.label);
                    if (target != null && target <= i) loopRegions.add(new LoopRegion(target, i));
                }
            }
            int local = 0;
            if ((method.access & Opcodes.ACC_STATIC) == 0) {
                if (receiver == null) reject("instance method has no receiver");
                locals.put(local++, receiver);
            }
            Type[] argumentTypes = Type.getArgumentTypes(method.desc);
            if (argumentTypes.length != arguments.length)
                reject("inlined method argument layout does not match its descriptor");
            for (int i = 0; i < argumentTypes.length; i++) {
                locals.put(local, arguments[i]);
                local += argumentTypes[i].getSize();
            }
        }

        Result run() {
            Value result = runInline();
            if (result.kind() != Kind.VALUE) reject("compute method returned a non-expression value");
            return Result.accepted(builder.build(result.expression()));
        }

        private Value runInline() {
            return executePath(0, new ArrayList<>(stack), new HashMap<>(locals));
        }

        private Value executePath(int start, List<Value> pathStack, Map<Integer, Value> pathLocals) {
            stack = pathStack;
            locals = pathLocals;
            for (int pc = start; pc < instructions.length; pc++) {
                AbstractInsnNode instruction = instructions[pc];
                int type = instruction.getType();
                if (type == AbstractInsnNode.LABEL || type == AbstractInsnNode.LINE
                        || type == AbstractInsnNode.FRAME) continue;
                try {
                    if (--remainingInstructionBudget[0] < 0)
                        reject("inlined control-flow expansion exceeds 50,000 bytecode steps");
                    if (instruction instanceof InsnNode insn) {
                        if (insn.getOpcode() == Opcodes.DCMPL || insn.getOpcode() == Opcodes.DCMPG
                                || insn.getOpcode() == Opcodes.FCMPL || insn.getOpcode() == Opcodes.FCMPG) {
                            Value right = popValue("floating comparison"), left = popValue("floating comparison");
                            String mode = (insn.getOpcode() == Opcodes.DCMPL || insn.getOpcode() == Opcodes.FCMPL)
                                    ? "DCMPL" : "DCMPG";
                            stack.add(Value.comparison(left.expression(), right.expression(), mode));
                            continue;
                        }
                        if (execute(insn.getOpcode())) return finish();
                    } else if (instruction instanceof VarInsnNode variable) {
                        variable(variable);
                    } else if (instruction instanceof IincInsnNode increment) {
                        increment(increment);
                    } else if (instruction instanceof FieldInsnNode field) {
                        field(field);
                    } else if (instruction instanceof MethodInsnNode call) {
                        invoke(call);
                    } else if (instruction instanceof LdcInsnNode constant) {
                        ldc(constant.cst);
                    } else if (instruction instanceof IntInsnNode constant) {
                        if (constant.getOpcode() != Opcodes.BIPUSH && constant.getOpcode() != Opcodes.SIPUSH)
                            reject("unsupported integer operand instruction " + opcodeName(constant.getOpcode()));
                        pushIntegerConstant(constant.operand);
                    } else if (instruction instanceof JumpInsnNode jump) {
                        Integer target = labels.get(jump.label);
                        if (target == null) reject("jump target is missing from the method instruction stream");
                        if (jump.getOpcode() == Opcodes.GOTO) {
                            if (target <= pc) {
                                if (!insideStaticLoop(pc)) reject("backward branch or loop is not supported");
                                int iterations = loopIterations.merge(pc, 1, Integer::sum);
                                if (iterations > MAX_STATIC_LOOP_ITERATIONS)
                                    reject("statically bounded loop exceeds 64 iterations");
                            }
                            return executePath(target, new ArrayList<>(stack), new HashMap<>(locals));
                        }
                        String relation = branchRelation(jump.getOpcode());
                        if (relation == null) reject("unsupported conditional branch " + opcodeName(jump.getOpcode()));
                        int condition = branchCondition(jump.getOpcode(), relation);
                        Double knownCondition = builder.constantValue(condition);
                        if (target <= pc && knownCondition == null)
                            reject("loop condition is not statically bounded by constants");
                        if (knownCondition != null) {
                            if (knownCondition != 0.0 && target <= pc) {
                                int iterations = loopIterations.merge(pc, 1, Integer::sum);
                                if (iterations > MAX_STATIC_LOOP_ITERATIONS)
                                    reject("statically bounded loop exceeds 64 iterations");
                            } else if (knownCondition == 0.0 && target <= pc) {
                                loopIterations.remove(pc);
                            }
                            if (knownCondition != 0.0) pc = target - 1;
                            continue;
                        }
                        if (target <= pc) reject("backward conditional branch is not supported");
                        if (insideStaticLoop(pc))
                            reject("dynamic conditional branches inside a static loop are not supported");
                        if (++branchForks > 128) reject("conditional expansion exceeds 128 branch points");
                        List<Value> branchStack = new ArrayList<>(stack);
                        Map<Integer, Value> branchLocals = new HashMap<>(locals);
                        Map<Integer, Integer> loopSnapshot = new HashMap<>(loopIterations);
                        Value whenJumpTaken = executePath(target,
                                new ArrayList<>(branchStack), new HashMap<>(branchLocals));
                        loopIterations = new HashMap<>(loopSnapshot);
                        Value whenFallThrough = executePath(pc + 1,
                                new ArrayList<>(branchStack), new HashMap<>(branchLocals));
                        loopIterations = loopSnapshot;
                        if (whenJumpTaken.kind() != Kind.VALUE || whenFallThrough.kind() != Kind.VALUE)
                            reject("conditional branch returned a nonnumeric value");
                        int merged = builder.select(condition, whenJumpTaken.expression(), whenFallThrough.expression());
                        return Value.value(merged, null);
                    } else if (instruction instanceof TableSwitchInsnNode tableSwitch) {
                        if (insideStaticLoop(pc)) reject("switches inside a static loop are not supported");
                        int count = tableSwitch.labels.size();
                        if (count > 64) reject("table switch exceeds the 64-case compiler limit");
                        int[] keys = new int[count];
                        for (int i = 0; i < count; i++) keys[i] = tableSwitch.min + i;
                        return switchBranch(pc, tableSwitch.dflt, tableSwitch.labels, keys);
                    } else if (instruction instanceof LookupSwitchInsnNode lookupSwitch) {
                        if (insideStaticLoop(pc)) reject("switches inside a static loop are not supported");
                        int count = lookupSwitch.labels.size();
                        if (count > 64) reject("lookup switch exceeds the 64-case compiler limit");
                        int[] keys = new int[count];
                        for (int i = 0; i < count; i++) keys[i] = lookupSwitch.keys.get(i);
                        return switchBranch(pc, lookupSwitch.dflt, lookupSwitch.labels, keys);
                    } else {
                        reject("control flow or unsupported instruction " + instruction.getClass().getSimpleName());
                    }
                } catch (UnsupportedBytecode failure) {
                    String location = function.getClass().getName() + "." + method.name + method.desc
                            + " at bytecode instruction " + pc + " (" + instructionLocation(instruction) + ")";
                    throw new UnsupportedBytecode(location + ": " + failure.getMessage(), failure);
                }
            }
            reject("method has no supported return instruction");
            return null;
        }

        private Value switchBranch(int pc, LabelNode defaultTarget,
                                   List<LabelNode> caseTargets, int[] keys) {
            Value selector = popValue("integer switch");
            Integer defaultIndex = labels.get(defaultTarget);
            if (defaultIndex == null || defaultIndex <= pc)
                reject("backward or missing switch default target is not supported");
            List<Integer> targetIndices = new ArrayList<>(caseTargets.size());
            for (LabelNode target : caseTargets) {
                Integer targetIndex = labels.get(target);
                if (targetIndex == null || targetIndex <= pc)
                    reject("backward or missing switch case target is not supported");
                targetIndices.add(targetIndex);
            }
            if (branchForks + caseTargets.size() > 128)
                reject("switch expansion exceeds the 128 branch-point limit");
            branchForks += caseTargets.size();
            List<Value> baseStack = new ArrayList<>(stack);
            Map<Integer, Value> baseLocals = new HashMap<>(locals);
            Map<Integer, Integer> loopSnapshot = new HashMap<>(loopIterations);
            Value defaultValue = executePath(defaultIndex,
                    new ArrayList<>(baseStack), new HashMap<>(baseLocals));
            if (defaultValue.kind() != Kind.VALUE) reject("switch default returned a nonnumeric value");
            int selected = defaultValue.expression();
            for (int caseIndex = caseTargets.size() - 1; caseIndex >= 0; caseIndex--) {
                loopIterations = new HashMap<>(loopSnapshot);
                Value caseValue = executePath(targetIndices.get(caseIndex),
                        new ArrayList<>(baseStack), new HashMap<>(baseLocals));
                if (caseValue.kind() != Kind.VALUE) reject("switch case returned a nonnumeric value");
                int key = keys[caseIndex];
                if (Math.abs((long) key) > 16_777_216L)
                    reject("switch key exceeds exact integer range of the GPU float coordinate ABI");
                int keyNode = builder.constant(key);
                int condition = builder.compare("EQ", selector.expression(), keyNode);
                selected = builder.select(condition, caseValue.expression(), selected);
            }
            loopIterations = loopSnapshot;
            return Value.value(selected, null);
        }

        private boolean insideStaticLoop(int pc) {
            for (LoopRegion region : loopRegions) {
                if (pc >= region.firstInstruction() && pc <= region.backEdgeInstruction()) return true;
            }
            return false;
        }

        private int branchCondition(int opcode, String relation) {
            if (opcode >= Opcodes.IF_ICMPEQ && opcode <= Opcodes.IF_ICMPLE) {
                Value right = popValue("two-operand conditional branch");
                Value left = popValue("two-operand conditional branch");
                return builder.compare(relation, left.expression(), right.expression());
            }
            Value value = pop("conditional branch");
            if (value.kind() == Kind.COMPARE) {
                return builder.compare(value.comparisonMode() + "_" + relation,
                        value.expression(), value.comparisonRight());
            }
            if (value.kind() != Kind.VALUE) reject("conditional branch requires a numeric value");
            return builder.compare(relation, value.expression(), builder.constant(0.0));
        }

        private static String branchRelation(int opcode) {
            return switch (opcode) {
                case Opcodes.IFEQ, Opcodes.IF_ICMPEQ -> "EQ";
                case Opcodes.IFNE, Opcodes.IF_ICMPNE -> "NE";
                case Opcodes.IFLT, Opcodes.IF_ICMPLT -> "LT";
                case Opcodes.IFLE, Opcodes.IF_ICMPLE -> "LE";
                case Opcodes.IFGT, Opcodes.IF_ICMPGT -> "GT";
                case Opcodes.IFGE, Opcodes.IF_ICMPGE -> "GE";
                default -> null;
            };
        }

        private boolean execute(int opcode) {
            switch (opcode) {
                case Opcodes.NOP -> { }
                case Opcodes.ACONST_NULL -> reject("null values are not representable in density expressions");
                case Opcodes.ICONST_M1 -> pushIntegerConstant(-1);
                case Opcodes.ICONST_0 -> pushIntegerConstant(0);
                case Opcodes.ICONST_1 -> pushIntegerConstant(1);
                case Opcodes.ICONST_2 -> pushIntegerConstant(2);
                case Opcodes.ICONST_3 -> pushIntegerConstant(3);
                case Opcodes.ICONST_4 -> pushIntegerConstant(4);
                case Opcodes.ICONST_5 -> pushIntegerConstant(5);
                case Opcodes.FCONST_0 -> pushConstant(0.0);
                case Opcodes.FCONST_1 -> pushConstant(1.0);
                case Opcodes.FCONST_2 -> pushConstant(2.0);
                case Opcodes.DCONST_0 -> pushConstant(0.0);
                case Opcodes.DCONST_1 -> pushConstant(1.0);
                case Opcodes.I2D, Opcodes.F2D -> { }
                case Opcodes.I2F -> numericCast("I2F");
                case Opcodes.D2F -> numericCast("D2F");
                case Opcodes.F2I -> numericCast("F2I");
                case Opcodes.D2I -> numericCast("D2I");
                case Opcodes.L2D, Opcodes.L2F,
                        Opcodes.I2L, Opcodes.F2L, Opcodes.D2L ->
                        reject("lossy or wide numeric conversion is not represented by the float density IR");
                case Opcodes.DADD -> binary(GpuDensityExpression.Operation.ADD);
                case Opcodes.DSUB -> binary(GpuDensityExpression.Operation.SUBTRACT);
                case Opcodes.DMUL -> binary(GpuDensityExpression.Operation.MULTIPLY);
                case Opcodes.DDIV -> binary(GpuDensityExpression.Operation.DIVIDE);
                case Opcodes.FADD -> floatBinary("FADD");
                case Opcodes.FSUB -> floatBinary("FSUB");
                case Opcodes.FMUL -> floatBinary("FMUL");
                case Opcodes.FDIV -> floatBinary("FDIV");
                case Opcodes.DREM -> binary(GpuDensityExpression.Operation.REMAINDER);
                case Opcodes.IREM -> integerRemainder();
                case Opcodes.FREM -> floatBinary("FREM");
                case Opcodes.LREM -> reject("wide-integer remainder is not represented by the density IR");
                case Opcodes.IADD -> integerBinary("IADD");
                case Opcodes.ISUB -> integerBinary("ISUB");
                case Opcodes.IMUL -> integerBinary("IMUL");
                case Opcodes.IDIV -> integerDivision();
                case Opcodes.IAND -> integerBinary("IAND");
                case Opcodes.IOR -> integerBinary("IOR");
                case Opcodes.IXOR -> integerBinary("IXOR");
                case Opcodes.ISHL -> integerBinary("ISHL");
                case Opcodes.ISHR -> integerBinary("ISHR");
                case Opcodes.IUSHR -> integerBinary("IUSHR");
                case Opcodes.LADD, Opcodes.LSUB, Opcodes.LMUL, Opcodes.LDIV ->
                        reject("wide-integer arithmetic is not represented by the density IR");
                case Opcodes.DNEG -> {
                    Value input = popValue("unary negation");
                    int minusOne = builder.constant(-1.0);
                    int result = builder.apply(GpuDensityExpression.Operation.MULTIPLY, input.expression(), minusOne);
                    stack.add(Value.value(result, input.constant() == null ? null : -input.constant()));
                }
                case Opcodes.INEG -> {
                    Value input = popValue("integer negation");
                    int result = builder.integerNegate(input.expression());
                    stack.add(Value.value(result, input.constant() == null ? null : (double) -(int)(double) input.constant()));
                }
                case Opcodes.LNEG -> reject("wide-integer negation is not represented by the density IR");
                case Opcodes.I2B -> integerConversion("I2B");
                case Opcodes.I2C -> integerConversion("I2C");
                case Opcodes.I2S -> integerConversion("I2S");
                case Opcodes.FNEG -> floatNegate();
                case Opcodes.DRETURN, Opcodes.IRETURN -> { return true; }
                case Opcodes.FRETURN, Opcodes.LRETURN ->
                        reject("float or wide-integer return is not represented by the density IR");
                case Opcodes.ARETURN -> reject("compute method returned a nonnumeric value");
                case Opcodes.POP -> pop("POP");
                case Opcodes.DUP -> stack.add(peek("DUP"));
                case Opcodes.IALOAD, Opcodes.LALOAD, Opcodes.FALOAD, Opcodes.DALOAD,
                        Opcodes.AALOAD, Opcodes.BALOAD, Opcodes.CALOAD, Opcodes.SALOAD -> arrayLoad(opcode);
                case Opcodes.ARRAYLENGTH -> arrayLength();
                case Opcodes.SWAP -> {
                    Value first = pop("SWAP"), second = pop("SWAP");
                    stack.add(first); stack.add(second);
                }
                default -> reject("unsupported JVM opcode " + opcodeName(opcode));
            }
            return false;
        }

        private void variable(VarInsnNode instruction) {
            int opcode = instruction.getOpcode();
            if (opcode == Opcodes.ALOAD || opcode == Opcodes.ILOAD || opcode == Opcodes.FLOAD
                    || opcode == Opcodes.DLOAD || opcode == Opcodes.LLOAD) {
                Value value = locals.get(instruction.var);
                if (value == null) reject("read of an uninitialized local variable " + instruction.var);
                stack.add(value);
                return;
            }
            if (opcode == Opcodes.ASTORE) {
                locals.put(instruction.var, pop("reference local store"));
                return;
            }
            if (opcode == Opcodes.ISTORE || opcode == Opcodes.FSTORE
                    || opcode == Opcodes.DSTORE || opcode == Opcodes.LSTORE) {
                locals.put(instruction.var, popValue("numeric local store"));
                return;
            }
            reject("unsupported local-variable opcode " + opcodeName(opcode));
        }

        private void increment(IincInsnNode instruction) {
            Value current = locals.get(instruction.var);
            if (current == null || current.kind() != Kind.VALUE)
                reject("IINC reads an uninitialized or nonnumeric local variable " + instruction.var);
            int amount = builder.constant(instruction.incr);
            int expression = builder.integerOperation("IADD", current.expression(), amount);
            Double constant = current.constant() == null ? null
                    : (double) ((int) (double) current.constant() + instruction.incr);
            locals.put(instruction.var, Value.value(expression, constant));
        }

        private void field(FieldInsnNode instruction) {
            boolean staticRead = instruction.getOpcode() == Opcodes.GETSTATIC;
            if (!staticRead && instruction.getOpcode() != Opcodes.GETFIELD)
                reject("field writes are not supported: " + instruction.owner + "." + instruction.name);
            Object fieldInstance = function;
            if (!staticRead) {
                Value receiver = pop("field access");
                if (receiver.kind() == Kind.OBJECT
                        && isObjectHierarchyOwner(receiver.referenceValue().getClass(), instruction.owner)) {
                    fieldInstance = receiver.referenceValue();
                } else if (receiver.kind() != Kind.THIS) {
                    reject("field access through an unsupported object: " + instruction.name);
                }
            }
            Object fieldValue = readField(fieldInstance, instruction.owner, instruction.name,
                    instruction.desc, staticRead);
            if (fieldValue instanceof Number number) {
                switch (instruction.desc) {
                    case "B", "C", "I", "S", "Z" -> pushIntegerConstant(number.intValue());
                    case "F", "D" -> pushConstant(number.doubleValue());
                    default -> reject("wide numeric field is not represented by the density IR: "
                            + instruction.name);
                }
            } else if (fieldValue instanceof Boolean booleanValue && instruction.desc.equals("Z")) {
                pushIntegerConstant(booleanValue ? 1 : 0);
            } else if (fieldValue instanceof Character characterValue && instruction.desc.equals("C")) {
                pushIntegerConstant(characterValue);
            } else if (fieldValue instanceof NormalNoise noise) {
                stack.add(Value.noise(noise));
            } else if (fieldValue instanceof DensityFunction density) {
                stack.add(Value.density(density));
            } else if (fieldValue != null && fieldValue.getClass().isArray()) {
                stack.add(Value.array(fieldValue));
            } else if (fieldValue != null && fieldValue.getClass().isRecord()) {
                stack.add(Value.object(fieldValue));
            } else {
                reject("field " + instruction.name + " has unsupported runtime type "
                        + (fieldValue == null ? "null" : fieldValue.getClass().getName()));
            }
        }

        private void arrayLoad(int opcode) {
            Value index = popValue("array index");
            Value array = pop("array reference");
            if (array.kind() != Kind.ARRAY) reject("array load requires a final density-object array field");
            if (index.constant() == null || !Double.isFinite(index.constant())
                    || index.constant() != Math.rint(index.constant())
                    || index.constant() < Integer.MIN_VALUE || index.constant() > Integer.MAX_VALUE) {
                reject("array index must be a compile-time integer");
            }
            int elementIndex = (int) (double) index.constant();
            Object values = array.referenceValue();
            int length = java.lang.reflect.Array.getLength(values);
            if (elementIndex < 0 || elementIndex >= length) {
                reject("constant array index " + elementIndex + " is outside length " + length);
            }
            Class<?> component = values.getClass().getComponentType();
            boolean validArray = switch (opcode) {
                case Opcodes.IALOAD -> component == int.class;
                case Opcodes.LALOAD -> component == long.class;
                case Opcodes.FALOAD -> component == float.class;
                case Opcodes.DALOAD -> component == double.class;
                case Opcodes.BALOAD -> component == byte.class || component == boolean.class;
                case Opcodes.CALOAD -> component == char.class;
                case Opcodes.SALOAD -> component == short.class;
                case Opcodes.AALOAD -> !component.isPrimitive();
                default -> false;
            };
            if (!validArray) reject("array load opcode does not match " + component.getTypeName() + "[]");
            Object value = java.lang.reflect.Array.get(values, elementIndex);
            if (value instanceof Number number) {
                if (opcode == Opcodes.IALOAD || opcode == Opcodes.BALOAD
                        || opcode == Opcodes.CALOAD || opcode == Opcodes.SALOAD) {
                    pushIntegerConstant(number.intValue());
                } else if (opcode == Opcodes.FALOAD) {
                    pushConstant(number.floatValue());
                } else if (opcode == Opcodes.DALOAD) {
                    pushConstant(number.doubleValue());
                } else {
                    reject("long array elements are not represented by the density IR");
                }
            } else if (value instanceof Boolean booleanValue && opcode == Opcodes.BALOAD) {
                pushIntegerConstant(booleanValue ? 1 : 0);
            } else if (value instanceof Character characterValue && opcode == Opcodes.CALOAD) {
                pushIntegerConstant(characterValue);
            } else if (value instanceof NormalNoise noise && opcode == Opcodes.AALOAD) {
                stack.add(Value.noise(noise));
            } else if (value instanceof DensityFunction density && opcode == Opcodes.AALOAD) {
                stack.add(Value.density(density));
            } else if (value != null && value.getClass().isArray() && opcode == Opcodes.AALOAD) {
                stack.add(Value.array(value));
            } else {
                reject("array element type is not a supported scalar, noise, or density: "
                        + (value == null ? "null" : value.getClass().getName()));
            }
        }

        private void arrayLength() {
            Value array = pop("array length");
            if (array.kind() != Kind.ARRAY) reject("array length requires a final density-object array field");
            pushIntegerConstant(java.lang.reflect.Array.getLength(array.referenceValue()));
        }

        private void invoke(MethodInsnNode call) {
            Type[] argumentTypes = Type.getArgumentTypes(call.desc);
            Value[] arguments = new Value[argumentTypes.length];
            for (int i = arguments.length - 1; i >= 0; i--) arguments[i] = pop("method argument");
            Value receiver = call.getOpcode() == Opcodes.INVOKESTATIC ? null : pop("method receiver");
            String owner = call.owner;
            String name = call.name;

            if (owner.equals("net/minecraft/world/level/levelgen/DensityFunction$FunctionContext")
                    && receiver != null && receiver.kind() == Kind.CONTEXT && arguments.length == 0) {
                Axis axis = switch (name) {
                    case "blockX" -> Axis.X;
                    case "blockY" -> Axis.Y;
                    case "blockZ" -> Axis.Z;
                    default -> null;
                };
                if (axis == null) reject("unsupported FunctionContext method " + name);
                int expression = builder.coordinate(switch (axis) {
                    case X -> GpuDensityExpression.Coordinate.X;
                    case Y -> GpuDensityExpression.Coordinate.Y;
                    case Z -> GpuDensityExpression.Coordinate.Z;
                });
                stack.add(Value.coordinate(expression, axis, 1.0));
                return;
            }

            if (receiver != null && receiver.kind() == Kind.NOISE
                    && owner.equals("net/minecraft/world/level/levelgen/synth/NormalNoise")
                    && name.equals("getValue") && arguments.length == 3) {
                Value x = arguments[0], y = arguments[1], z = arguments[2];
                if (x.kind() != Kind.VALUE || y.kind() != Kind.VALUE || z.kind() != Kind.VALUE
                        || x.axis() != Axis.X || y.axis() != Axis.Y || z.axis() != Axis.Z
                        || Float.floatToIntBits((float) x.axisScale()) != Float.floatToIntBits((float) z.axisScale())) {
                    reject("NormalNoise.getValue coordinates must be direct X/Y/Z axes with matching horizontal scales");
                }
                int expression = builder.noise(receiver.noise(), x.axisScale(), y.axisScale());
                stack.add(Value.value(expression, null));
                return;
            }

            if (receiver != null && receiver.kind() == Kind.DENSITY
                    && owner.equals("net/minecraft/world/level/levelgen/DensityFunction")
                    && name.equals("compute") && arguments.length == 1
                    && arguments[0].kind() == Kind.CONTEXT) {
                stack.add(Value.value(builder.density(receiver.density()), null));
                return;
            }

            if (call.getOpcode() == Opcodes.INVOKESTATIC && isMathOwner(owner)) {
                mathCall(owner, name, call.desc, arguments);
                return;
            }

            boolean sameClass = owner.equals(this.owner.name);
            boolean densityHierarchyHelper = receiver != null && receiver.kind() == Kind.THIS
                    && isDensityHierarchyOwner(function.getClass(), owner);
            boolean nestedObjectHelper = receiver != null && receiver.kind() == Kind.OBJECT
                    && isInlineableHelperOwner(owner)
                    && isObjectHierarchyOwner(receiver.referenceValue().getClass(), owner);
            boolean externalStaticHelper = call.getOpcode() == Opcodes.INVOKESTATIC
                    && !sameClass && isInlineableHelperOwner(owner);
            if (sameClass || densityHierarchyHelper || nestedObjectHelper || externalStaticHelper) {
                Class<?> receiverType = receiver != null && receiver.kind() == Kind.OBJECT
                        ? receiver.referenceValue().getClass() : function.getClass();
                ResolvedMethod resolved = resolveHelperMethod(receiverType, owner, name, call.desc,
                        call.getOpcode() == Opcodes.INVOKESTATIC);
                if (resolved != null) {
                    ClassNode helperOwner = resolved.owner();
                    MethodNode target = resolved.method();
                    if (inlineDepth >= 16) reject("helper-call depth exceeds 16");
                    boolean targetStatic = (target.access & Opcodes.ACC_STATIC) != 0;
                    if (targetStatic != (call.getOpcode() == Opcodes.INVOKESTATIC))
                        reject("helper invocation staticness does not match its method declaration");
                    if (!targetStatic && (receiver == null
                            || receiver.kind() != Kind.THIS && receiver.kind() != Kind.OBJECT))
                        reject("instance helper must be called on the density object or immutable record");
                    if (Type.getReturnType(target.desc).getSort() == Type.VOID)
                        reject("void helper calls have side effects and cannot be inlined");
                    Value result = new Interpreter(function, helperOwner, target, builder, inlineDepth + 1,
                            remainingInstructionBudget, targetStatic ? null : receiver, arguments).runInline();
                    if (result.kind() != Kind.VALUE)
                        reject("helper returned a nonnumeric value");
                    stack.add(result);
                    return;
                }
            }
            reject("opaque method call " + owner.replace('/', '.') + "." + name + call.desc);
        }

        private void mathCall(String owner, String name, String descriptor, Value[] arguments) {
            if (owner.equals("net/minecraft/util/Mth") && name.equals("sqrt")
                    && descriptor.equals("(F)F") && arguments.length == 1) {
                Value input = arguments[0];
                int result = builder.map(GpuDensityExpression.Mapping.SQRT_FLOAT, input.expression());
                Double constant = input.constant() == null ? null
                        : (double) (float) Math.sqrt(input.constant().floatValue());
                stack.add(Value.value(result, constant));
                return;
            }
            if (owner.equals("net/minecraft/util/Mth") && arguments.length == 1
                    && descriptor.equals("(F)F")
                    && (name.equals("sin") || name.equals("cos"))) {
                Value input = arguments[0];
                GpuDensityExpression.Mapping mapping = name.equals("sin")
                        ? GpuDensityExpression.Mapping.SIN_FLOAT : GpuDensityExpression.Mapping.COS_FLOAT;
                int result = builder.map(mapping, input.expression());
                Double constant = input.constant() == null ? null
                        : (double) (name.equals("sin")
                                ? GpuDensityGraphBuffer.mthSin(input.constant().floatValue())
                                : GpuDensityGraphBuffer.mthCos(input.constant().floatValue()));
                stack.add(Value.value(result, constant));
                return;
            }
            if (owner.equals("net/minecraft/util/Mth") && arguments.length == 1
                    && (descriptor.equals("(F)I") || descriptor.equals("(D)I"))
                    && (name.equals("floor") || name.equals("ceil"))) {
                Value input = arguments[0];
                GpuDensityExpression.Mapping mapping = name.equals("floor")
                        ? GpuDensityExpression.Mapping.FLOOR : GpuDensityExpression.Mapping.CEIL;
                int rounded = builder.map(mapping, input.expression());
                boolean floatInput = descriptor.equals("(F)I");
                int result = builder.cast(floatInput ? "F2I" : "D2I", rounded);
                Double constant = input.constant() == null ? null
                        : (double) (int) (name.equals("floor")
                                ? Math.floor(input.constant()) : Math.ceil(input.constant()));
                stack.add(Value.value(result, constant));
                return;
            }
            if ((owner.equals("java/lang/Math") || owner.equals("java/lang/StrictMath")
                    || owner.equals("net/minecraft/util/Mth")) && name.equals("abs")
                    && descriptor.equals("(I)I") && arguments.length == 1) {
                Value input = arguments[0];
                int zero = builder.constant(0.0);
                int negative = builder.compare("LT", input.expression(), zero);
                int absolute = builder.integerNegate(input.expression());
                int result = builder.select(negative, absolute, input.expression());
                Double constant = input.constant() == null ? null
                        : (double) Math.abs((int) (double) input.constant());
                stack.add(Value.value(result, constant));
                return;
            }
            if (owner.equals("net/minecraft/util/Mth") && name.equals("square")
                    && descriptor.equals("(I)I") && arguments.length == 1) {
                Value input = arguments[0];
                int result = builder.integerOperation("IMUL", input.expression(), input.expression());
                Double constant = input.constant() == null ? null
                        : (double) ((int) (double) input.constant() * (int) (double) input.constant());
                stack.add(Value.value(result, constant));
                return;
            }
            if ((owner.equals("java/lang/Math") || owner.equals("java/lang/StrictMath"))
                    && (descriptor.equals("(DD)D") || descriptor.equals("(FF)F"))
                    && arguments.length == 2
                    && (name.equals("min") || name.equals("max"))) {
                stack.add(mathBinary(name.equals("min") ? GpuDensityExpression.Operation.MIN
                        : GpuDensityExpression.Operation.MAX, arguments[0], arguments[1]));
                return;
            }
            if ((owner.equals("java/lang/Math") || owner.equals("java/lang/StrictMath")
                    || owner.equals("net/minecraft/util/Mth"))
                    && descriptor.equals("(II)I") && arguments.length == 2
                    && (name.equals("floorDiv") || name.equals("floorMod"))) {
                requireNonzeroIntegerDivisor(arguments[1], name);
                Value quotient = floorDivision(arguments[0], arguments[1]);
                if (name.equals("floorDiv")) {
                    stack.add(quotient);
                } else {
                    Value product = integerMath(quotient, arguments[1], "IMUL");
                    stack.add(integerMath(arguments[0], product, "ISUB"));
                }
                return;
            }
            if ((owner.equals("java/lang/Math") || owner.equals("java/lang/StrictMath"))
                    && descriptor.equals("(D)D") && arguments.length == 1) {
                GpuDensityExpression.Mapping mapping = switch (name) {
                    case "floor" -> GpuDensityExpression.Mapping.FLOOR;
                    case "ceil" -> GpuDensityExpression.Mapping.CEIL;
                    case "sqrt" -> GpuDensityExpression.Mapping.SQRT;
                    case "sin" -> GpuDensityExpression.Mapping.SIN;
                    case "cos" -> GpuDensityExpression.Mapping.COS;
                    case "exp" -> GpuDensityExpression.Mapping.EXP;
                    case "log" -> GpuDensityExpression.Mapping.LOG;
                    case "tanh" -> GpuDensityExpression.Mapping.TANH;
                    default -> null;
                };
                if (mapping != null) {
                    Value input = arguments[0];
                    int result = builder.map(mapping, input.expression());
                    Double constant = input.constant() == null ? null
                            : (double) (float) switch (mapping) {
                                case FLOOR -> Math.floor(input.constant());
                                case CEIL -> Math.ceil(input.constant());
                                case SQRT -> Math.sqrt(input.constant());
                                case SIN -> Math.sin(input.constant());
                                case COS -> Math.cos(input.constant());
                                case EXP -> Math.exp(input.constant());
                                case LOG -> Math.log(input.constant());
                                case TANH -> Math.tanh(input.constant());
                                default -> throw new AssertionError(mapping);
                            };
                    stack.add(Value.value(result, constant));
                    return;
                }
            }
            if ((owner.equals("java/lang/Math") || owner.equals("java/lang/StrictMath")
                    || owner.equals("net/minecraft/util/Mth"))
                    && (descriptor.equals("(D)D") || descriptor.equals("(F)F"))
                    && arguments.length == 1
                    && name.equals("abs")) {
                int result = builder.map(GpuDensityExpression.Mapping.ABS, arguments[0].expression());
                stack.add(Value.value(result, arguments[0].constant() == null
                        ? null : Math.abs(arguments[0].constant())));
                return;
            }
            if (owner.equals("net/minecraft/util/Mth") && name.equals("square") && arguments.length == 1) {
                Value input = arguments[0];
                if (descriptor.equals("(D)D")) {
                    int result = builder.map(GpuDensityExpression.Mapping.SQUARE, input.expression());
                    stack.add(Value.value(result, input.constant() == null ? null
                            : input.constant() * input.constant()));
                } else if (descriptor.equals("(F)F")) {
                    int result = builder.floatOperation("FMUL", input.expression(), input.expression());
                    float value = input.constant() == null ? 0.0F : input.constant().floatValue();
                    Double constant = input.constant() == null ? null : (double) (value * value);
                    stack.add(Value.value(result, constant));
                } else {
                    reject("unsupported Mth.square overload " + descriptor);
                }
                return;
            }
            if ((owner.equals("java/lang/Math") || owner.equals("java/lang/StrictMath"))
                    && name.equals("pow") && descriptor.equals("(DD)D") && arguments.length == 2) {
                if (arguments[1].constant() != null
                        && (arguments[1].constant() == 2.0 || arguments[1].constant() == 3.0)) {
                    Value input = arguments[0];
                    GpuDensityExpression.Mapping mapping = arguments[1].constant() == 2.0
                            ? GpuDensityExpression.Mapping.SQUARE : GpuDensityExpression.Mapping.CUBE;
                    int result = builder.map(mapping, input.expression());
                    Double constant = input.constant() == null ? null
                            : arguments[1].constant() == 2.0
                                    ? input.constant() * input.constant()
                                    : input.constant() * input.constant() * input.constant();
                    stack.add(Value.value(result, constant));
                } else {
                    stack.add(mathBinary(GpuDensityExpression.Operation.POW,
                            arguments[0], arguments[1]));
                }
                return;
            }
            if ((owner.equals("java/lang/Math") || owner.equals("java/lang/StrictMath")
                    || owner.equals("net/minecraft/util/Mth")) && descriptor.equals("(DD)D")
                    && arguments.length == 2
                    && (name.equals("min") || name.equals("max"))) {
                var operation = name.equals("min") ? GpuDensityExpression.Operation.MIN
                        : GpuDensityExpression.Operation.MAX;
                int result = builder.apply(operation, arguments[0].expression(), arguments[1].expression());
                Double constant = combineConstant(operation, arguments[0].constant(), arguments[1].constant());
                stack.add(Value.value(result, constant));
                return;
            }
            if ((owner.equals("java/lang/Math") || owner.equals("java/lang/StrictMath"))
                    && name.equals("atan2") && descriptor.equals("(DD)D")
                    && arguments.length == 2) {
                stack.add(mathBinary(GpuDensityExpression.Operation.ATAN2,
                        arguments[0], arguments[1]));
                return;
            }
            boolean javaSignum = (owner.equals("java/lang/Math") || owner.equals("java/lang/StrictMath"))
                    && name.equals("signum")
                    && (descriptor.equals("(D)D") || descriptor.equals("(F)F"));
            boolean minecraftSign = owner.equals("net/minecraft/util/Mth")
                    && name.equals("sign") && descriptor.equals("(D)I");
            if ((javaSignum || minecraftSign) && arguments.length == 1) {
                Value input = arguments[0];
                int zero = builder.constant(0.0);
                int negative = builder.compare("LT", input.expression(), zero);
                int positive = builder.compare("GT", input.expression(), zero);
                int minusOne = builder.constant(-1.0), plusOne = builder.constant(1.0);
                int nonPositive = builder.select(negative, minusOne, input.expression());
                int result = builder.select(positive, plusOne, nonPositive);
                Double constant = input.constant() == null ? null
                        : input.constant() > 0.0 ? 1.0
                        : input.constant() < 0.0 ? -1.0 : input.constant();
                stack.add(Value.value(result, constant));
                return;
            }
            if (owner.equals("net/minecraft/util/Mth") && descriptor.equals("(DD)D")
                    && arguments.length == 2 && name.equals("absMax")) {
                Value leftAbs = mappedValue(GpuDensityExpression.Mapping.ABS, arguments[0]);
                Value rightAbs = mappedValue(GpuDensityExpression.Mapping.ABS, arguments[1]);
                stack.add(mathBinary(GpuDensityExpression.Operation.MAX, leftAbs, rightAbs));
                return;
            }
            if (owner.equals("net/minecraft/util/Mth") && arguments.length == 1
                    && descriptor.equals("(F)F") && name.equals("frac")) {
                Value input = arguments[0];
                int rounded = builder.map(GpuDensityExpression.Mapping.FLOOR, input.expression());
                int flooredInt = builder.cast("F2I", rounded);
                int flooredFloat = builder.cast("I2F", flooredInt);
                stack.add(floatMathBinary("FSUB", input,
                        Value.value(flooredFloat, input.constant() == null ? null
                                : (double) (float) (int) Math.floor(input.constant()))));
                return;
            }
            if (owner.equals("net/minecraft/util/Mth") && arguments.length == 2
                    && (descriptor.equals("(FF)F") || descriptor.equals("(DD)D"))
                    && name.equals("positiveModulo")) {
                boolean floatResult = descriptor.equals("(FF)F");
                Value remainder = floatResult
                        ? floatMathBinary("FREM", arguments[0], arguments[1])
                        : mathBinary(GpuDensityExpression.Operation.REMAINDER, arguments[0], arguments[1]);
                Value shifted = floatResult
                        ? floatMathBinary("FADD", remainder, arguments[1])
                        : mathBinary(GpuDensityExpression.Operation.ADD, remainder, arguments[1]);
                Value result = floatResult
                        ? floatMathBinary("FREM", shifted, arguments[1])
                        : mathBinary(GpuDensityExpression.Operation.REMAINDER, shifted, arguments[1]);
                stack.add(result);
                return;
            }
            if (owner.equals("net/minecraft/util/Mth") && arguments.length == 1
                    && (descriptor.equals("(F)F") || descriptor.equals("(D)D"))
                    && name.equals("wrapDegrees")) {
                stack.add(wrapDegreesValue(arguments[0], descriptor.equals("(F)F")));
                return;
            }
            if (owner.equals("net/minecraft/util/Mth") && arguments.length == 2
                    && descriptor.equals("(FF)F") && name.equals("triangleWave")) {
                Value input = arguments[0], period = arguments[1];
                Value remainder = floatMathBinary("FREM", input, period);
                Value halfPeriod = floatMathBinary("FMUL", period,
                        Value.value(builder.constant(0.5), 0.5));
                Value centered = floatMathBinary("FSUB", remainder, halfPeriod);
                int absolute = builder.map(GpuDensityExpression.Mapping.ABS, centered.expression());
                Value quarterPeriod = floatMathBinary("FMUL", period,
                        Value.value(builder.constant(0.25), 0.25));
                Value triangle = floatMathBinary("FSUB", Value.value(absolute, null), quarterPeriod);
                stack.add(floatMathBinary("FDIV", triangle, quarterPeriod));
                return;
            }
            if (owner.equals("net/minecraft/util/Mth") && arguments.length == 2
                    && descriptor.equals("(FF)F")
                    && (name.equals("degreesDifference") || name.equals("degreesDifferenceAbs"))) {
                Value difference = floatMathBinary("FSUB", arguments[1], arguments[0]);
                Value wrapped = wrapDegreesValue(difference, true);
                if (name.equals("degreesDifferenceAbs")) {
                    int absolute = builder.map(GpuDensityExpression.Mapping.ABS, wrapped.expression());
                    wrapped = Value.value(absolute, null);
                }
                stack.add(wrapped);
                return;
            }
            if (owner.equals("net/minecraft/util/Mth") && arguments.length == 3
                    && descriptor.equals("(FFF)F") && name.equals("rotateIfNecessary")) {
                Value difference = floatMathBinary("FSUB", arguments[1], arguments[0]);
                Value wrapped = wrapDegreesValue(difference, true);
                Value negativeLimit = floatNegated(arguments[2]);
                Value clamped = clampValue(wrapped, negativeLimit, arguments[2], true);
                stack.add(floatMathBinary("FSUB", arguments[1], clamped));
                return;
            }
            if (owner.equals("net/minecraft/util/Mth") && arguments.length == 3
                    && descriptor.equals("(FFF)F")
                    && (name.equals("approach") || name.equals("approachDegrees"))) {
                Value current = arguments[0], target = arguments[1], step = arguments[2];
                if (name.equals("approachDegrees")) {
                    Value delta = floatMathBinary("FSUB", target, current);
                    Value wrapped = wrapDegreesValue(delta, true);
                    target = floatMathBinary("FADD", current, wrapped);
                }
                Value absoluteStep = mappedValue(GpuDensityExpression.Mapping.ABS, step);
                int below = builder.compare("LT", current.expression(), target.expression());
                Value increasing = floatMathBinary("FADD", current, absoluteStep);
                Value decreasing = floatMathBinary("FSUB", current, absoluteStep);
                Value increasingResult = clampValue(increasing, current, target, true);
                Value decreasingResult = clampValue(decreasing, target, current, true);
                int result = builder.select(below, increasingResult.expression(), decreasingResult.expression());
                stack.add(Value.value(result, null));
                return;
            }
            if (owner.equals("net/minecraft/util/Mth") && arguments.length == 3
                    && (descriptor.equals("(FFF)F") || descriptor.equals("(DDD)D"))
                    && name.equals("rotLerp")) {
                boolean floatResult = descriptor.equals("(FFF)F");
                Value difference = floatResult
                        ? floatMathBinary("FSUB", arguments[2], arguments[1])
                        : mathBinary(GpuDensityExpression.Operation.SUBTRACT, arguments[2], arguments[1]);
                Value wrapped = wrapDegreesValue(difference, floatResult);
                Value scaled = floatResult
                        ? floatMathBinary("FMUL", arguments[0], wrapped)
                        : mathBinary(GpuDensityExpression.Operation.MULTIPLY, arguments[0], wrapped);
                stack.add(floatResult
                        ? floatMathBinary("FADD", arguments[1], scaled)
                        : mathBinary(GpuDensityExpression.Operation.ADD, arguments[1], scaled));
                return;
            }
            if (owner.equals("net/minecraft/util/Mth")
                    && (name.equals("length") || name.equals("lengthSquared"))) {
                boolean floatResult = descriptor.equals("(FFF)F");
                boolean valid = floatResult && arguments.length == 3 && name.equals("lengthSquared")
                        || descriptor.equals("(DD)D") && arguments.length == 2
                        || descriptor.equals("(DDD)D") && arguments.length == 3;
                if (valid) {
                    Value sum = lengthSquaredValue(arguments, floatResult);
                    if (name.equals("length")) sum = squareRootValue(sum, false);
                    stack.add(sum);
                    return;
                }
            }
            if (owner.equals("net/minecraft/util/Mth") && arguments.length == 3
                    && descriptor.equals("(FII)I")
                    && (name.equals("lerpInt") || name.equals("lerpDiscrete"))) {
                Value difference = integerMath(arguments[2], arguments[1], "ISUB");
                if (name.equals("lerpDiscrete")) {
                    difference = integerMath(difference, Value.value(builder.constant(1.0), 1.0), "ISUB");
                }
                Value differenceFloat = Value.value(builder.cast("I2F", difference.expression()), null);
                Value scaled = floatMathBinary("FMUL", arguments[0], differenceFloat);
                int floored = builder.cast("F2I",
                        builder.map(GpuDensityExpression.Mapping.FLOOR, scaled.expression()));
                Value result = integerMath(arguments[1], Value.value(floored, null), "IADD");
                if (name.equals("lerpDiscrete")) {
                    int positive = builder.compare("GT", arguments[0].expression(), builder.constant(0.0));
                    int increment = builder.select(positive, builder.constant(1.0), builder.constant(0.0));
                    result = integerMath(result, Value.value(increment, null), "IADD");
                }
                stack.add(result);
                return;
            }
            if (owner.equals("net/minecraft/util/Mth") && descriptor.equals("(D)D")
                    && arguments.length == 1 && name.equals("smoothstep")) {
                Value x = arguments[0];
                Value x2 = mathBinary(GpuDensityExpression.Operation.MULTIPLY, x, x);
                Value x3 = mathBinary(GpuDensityExpression.Operation.MULTIPLY, x2, x);
                Value sixX = mathBinary(GpuDensityExpression.Operation.MULTIPLY,
                        Value.value(builder.constant(6.0), 6.0), x);
                Value inner = mathBinary(GpuDensityExpression.Operation.SUBTRACT,
                        sixX, Value.value(builder.constant(15.0), 15.0));
                Value innerTimesX = mathBinary(GpuDensityExpression.Operation.MULTIPLY, inner, x);
                Value polynomial = mathBinary(GpuDensityExpression.Operation.ADD,
                        innerTimesX, Value.value(builder.constant(10.0), 10.0));
                stack.add(mathBinary(GpuDensityExpression.Operation.MULTIPLY, x3, polynomial));
                return;
            }
            if (owner.equals("net/minecraft/util/Mth") && descriptor.equals("(DDD)D")
                    && arguments.length == 3 && name.equals("inverseLerp")) {
                Value numerator = mathBinary(GpuDensityExpression.Operation.SUBTRACT,
                        arguments[0], arguments[1]);
                Value denominator = mathBinary(GpuDensityExpression.Operation.SUBTRACT,
                        arguments[2], arguments[1]);
                stack.add(mathBinary(GpuDensityExpression.Operation.DIVIDE, numerator, denominator));
                return;
            }
            if (owner.equals("net/minecraft/util/Mth") && descriptor.equals("(FFF)F")
                    && arguments.length == 3 && name.equals("inverseLerp")) {
                stack.add(floatInverseLerp(arguments[0], arguments[1], arguments[2]));
                return;
            }
            if (owner.equals("net/minecraft/util/Mth") && descriptor.equals("(DDDDD)D")
                    && arguments.length == 5 && (name.equals("map") || name.equals("clampedMap"))) {
                Value tNumerator = mathBinary(GpuDensityExpression.Operation.SUBTRACT,
                        arguments[0], arguments[1]);
                Value tDenominator = mathBinary(GpuDensityExpression.Operation.SUBTRACT,
                        arguments[2], arguments[1]);
                Value t = mathBinary(GpuDensityExpression.Operation.DIVIDE, tNumerator, tDenominator);
                if (name.equals("clampedMap")) t = clampUnitLikeClampedLerp(t);
                Value outputRange = mathBinary(GpuDensityExpression.Operation.SUBTRACT,
                        arguments[4], arguments[3]);
                Value scaled = mathBinary(GpuDensityExpression.Operation.MULTIPLY, t, outputRange);
                stack.add(mathBinary(GpuDensityExpression.Operation.ADD, arguments[3], scaled));
                return;
            }
            if (owner.equals("net/minecraft/util/Mth") && descriptor.equals("(FFFFF)F")
                    && arguments.length == 5 && (name.equals("map") || name.equals("clampedMap"))) {
                Value delta = floatInverseLerp(arguments[0], arguments[1], arguments[2]);
                Value mapped = name.equals("map")
                        ? floatLerpValue(delta, arguments[3], arguments[4])
                        : floatClampedLerp(arguments[3], arguments[4], delta);
                stack.add(mapped);
                return;
            }
            if (owner.equals("net/minecraft/util/Mth") && name.equals("lerp")
                    && descriptor.equals("(DDD)D") && arguments.length == 3) {
                stack.add(lerpValue(arguments[0], arguments[1], arguments[2]));
                return;
            }
            if (owner.equals("net/minecraft/util/Mth") && name.equals("lerp")
                    && descriptor.equals("(FFF)F") && arguments.length == 3) {
                stack.add(floatLerpValue(arguments[0], arguments[1], arguments[2]));
                return;
            }
            if (owner.equals("net/minecraft/util/Mth") && name.equals("lerp2")
                    && descriptor.equals("(DDDDDD)D") && arguments.length == 6) {
                stack.add(lerp2Value(arguments[0], arguments[1], arguments[2], arguments[3],
                        arguments[4], arguments[5]));
                return;
            }
            if (owner.equals("net/minecraft/util/Mth") && name.equals("lerp3")
                    && descriptor.equals("(DDDDDDDDDDD)D") && arguments.length == 11) {
                Value lower = lerp2Value(arguments[0], arguments[1],
                        arguments[3], arguments[4], arguments[5], arguments[6]);
                Value upper = lerp2Value(arguments[0], arguments[1],
                        arguments[7], arguments[8], arguments[9], arguments[10]);
                stack.add(lerpValue(arguments[2], lower, upper));
                return;
            }
            if (owner.equals("net/minecraft/util/Mth") && name.equals("clampedLerp")
                    && descriptor.equals("(DDD)D") && arguments.length == 3) {
                Value start = arguments[0], end = arguments[1], delta = arguments[2];
                int zero = builder.constant(0.0), one = builder.constant(1.0);
                int below = builder.compare("LT", delta.expression(), zero);
                int above = builder.compare("GT", delta.expression(), one);
                Value interpolated = lerpValue(delta, start, end);
                int highResult = builder.select(above, end.expression(), interpolated.expression());
                int result = builder.select(below, start.expression(), highResult);
                Double constant = delta.constant() == null ? null
                        : delta.constant() < 0.0 ? start.constant()
                        : delta.constant() > 1.0 ? end.constant() : interpolated.constant();
                stack.add(Value.value(result, constant));
                return;
            }
            if (owner.equals("net/minecraft/util/Mth") && name.equals("clampedLerp")
                    && descriptor.equals("(FFF)F") && arguments.length == 3) {
                stack.add(floatClampedLerp(arguments[0], arguments[1], arguments[2]));
                return;
            }
            if (owner.equals("net/minecraft/util/Mth") && name.equals("clamp")
                    && (descriptor.equals("(DDD)D") || descriptor.equals("(FFF)F"))
                    && arguments.length == 3) {
                stack.add(clampValue(arguments[0], arguments[1], arguments[2],
                        descriptor.equals("(FFF)F")));
                return;
            }
            reject("unsupported math operation " + owner.replace('/', '.') + "." + name);
        }

        private Value mathBinary(GpuDensityExpression.Operation operation, Value left, Value right) {
            int expression = builder.apply(operation, left.expression(), right.expression());
            return Value.value(expression, combineConstant(operation, left.constant(), right.constant()));
        }

        private Value clampValue(Value input, Value minimum, Value maximum, boolean floatResult) {
            if (minimum.constant() != null && maximum.constant() != null
                    && minimum.constant() <= maximum.constant() && !floatResult) {
                int result = builder.clamp(input.expression(), minimum.constant(), maximum.constant());
                Double constant = input.constant() == null ? null
                        : Math.max(minimum.constant(), Math.min(maximum.constant(), input.constant()));
                return Value.value(result, constant);
            }
            int belowMinimum = builder.compare("LT", input.expression(), minimum.expression());
            int aboveMaximum = builder.compare("GT", input.expression(), maximum.expression());
            int upperClamped = builder.select(aboveMaximum, maximum.expression(), input.expression());
            int result = builder.select(belowMinimum, minimum.expression(), upperClamped);
            Double constant = null;
            if (input.constant() != null && minimum.constant() != null && maximum.constant() != null) {
                double value = input.constant() < minimum.constant() ? minimum.constant()
                        : input.constant() > maximum.constant() ? maximum.constant() : input.constant();
                constant = floatResult ? (double) (float) value : value;
            }
            return Value.value(result, constant);
        }

        private Value lerpValue(Value delta, Value start, Value end) {
            Value difference = mathBinary(GpuDensityExpression.Operation.SUBTRACT, end, start);
            Value scaled = mathBinary(GpuDensityExpression.Operation.MULTIPLY, delta, difference);
            return mathBinary(GpuDensityExpression.Operation.ADD, start, scaled);
        }

        private Value floatMathBinary(String operation, Value left, Value right) {
            int expression = builder.floatOperation(operation, left.expression(), right.expression());
            Double constant = null;
            if (left.constant() != null && right.constant() != null) {
                float a = left.constant().floatValue(), b = right.constant().floatValue();
                float result = switch (operation) {
                    case "FADD" -> a + b;
                    case "FSUB" -> a - b;
                    case "FMUL" -> a * b;
                    case "FDIV" -> a / b;
                    default -> throw new AssertionError(operation);
                };
                if (Float.isFinite(result)) constant = (double) result;
            }
            return Value.value(expression, constant);
        }

        private Value floatNegated(Value input) {
            int expression = builder.floatOperation("FNEG", input.expression(), -1);
            Double constant = input.constant() == null ? null
                    : (double) -(float) input.constant().doubleValue();
            return Value.value(expression, constant);
        }

        private Value lengthSquaredValue(Value[] coordinates, boolean floatResult) {
            Value sum = null;
            for (Value coordinate : coordinates) {
                Value square = floatResult
                        ? floatMathBinary("FMUL", coordinate, coordinate)
                        : mathBinary(GpuDensityExpression.Operation.MULTIPLY, coordinate, coordinate);
                sum = sum == null ? square : floatResult
                        ? floatMathBinary("FADD", sum, square)
                        : mathBinary(GpuDensityExpression.Operation.ADD, sum, square);
            }
            return sum;
        }

        private Value squareRootValue(Value input, boolean floatResult) {
            int expression = builder.map(floatResult
                    ? GpuDensityExpression.Mapping.SQRT_FLOAT : GpuDensityExpression.Mapping.SQRT,
                    input.expression());
            Double constant = input.constant() == null ? null : floatResult
                    ? (double) (float) Math.sqrt((float) (double) input.constant())
                    : Math.sqrt(input.constant());
            if (constant != null && !Double.isFinite(constant)) constant = null;
            return Value.value(expression, constant);
        }

        private Value floatLerpValue(Value delta, Value start, Value end) {
            Value difference = floatMathBinary("FSUB", end, start);
            Value scaled = floatMathBinary("FMUL", delta, difference);
            return floatMathBinary("FADD", start, scaled);
        }

        private Value floatInverseLerp(Value value, Value start, Value end) {
            Value numerator = floatMathBinary("FSUB", value, start);
            Value denominator = floatMathBinary("FSUB", end, start);
            return floatMathBinary("FDIV", numerator, denominator);
        }

        private Value floatClampedLerp(Value start, Value end, Value delta) {
            int zero = builder.constant(0.0), one = builder.constant(1.0);
            int below = builder.compare("LT", delta.expression(), zero);
            int above = builder.compare("GT", delta.expression(), one);
            Value interpolated = floatLerpValue(delta, start, end);
            int highResult = builder.select(above, end.expression(), interpolated.expression());
            int result = builder.select(below, start.expression(), highResult);
            Double constant = delta.constant() == null ? null
                    : delta.constant() < 0.0 ? start.constant()
                    : delta.constant() > 1.0 ? end.constant() : interpolated.constant();
            return Value.value(result, constant);
        }

        private Value wrapDegreesValue(Value input, boolean floatResult) {
            Value fullTurn = Value.value(builder.constant(floatResult ? 360.0F : 360.0), 360.0);
            Value halfTurn = Value.value(builder.constant(floatResult ? 180.0F : 180.0), 180.0);
            Value remainder = floatResult
                    ? floatMathBinary("FREM", input, fullTurn)
                    : mathBinary(GpuDensityExpression.Operation.REMAINDER, input, fullTurn);
            int upper = builder.compare("GE", remainder.expression(), halfTurn.expression());
            Value minusTurn = floatResult
                    ? floatMathBinary("FSUB", remainder, fullTurn)
                    : mathBinary(GpuDensityExpression.Operation.SUBTRACT, remainder, fullTurn);
            int upperAdjusted = builder.select(upper, minusTurn.expression(), remainder.expression());
            int lower = builder.compare("LT", upperAdjusted, builder.constant(-180.0));
            Value plusTurn = floatResult
                    ? floatMathBinary("FADD", Value.value(upperAdjusted, null), fullTurn)
                    : mathBinary(GpuDensityExpression.Operation.ADD, Value.value(upperAdjusted, null), fullTurn);
            return Value.value(builder.select(lower, plusTurn.expression(), upperAdjusted), null);
        }

        private Value floorDivision(Value dividend, Value divisor) {
            Value quotient = integerMath(dividend, divisor, "IDIV");
            Value remainder = integerMath(dividend, divisor, "IREM");
            int zero = builder.constant(0.0);
            int one = builder.constant(1.0);
            int remainderNonzero = builder.compare("NE", remainder.expression(), zero);
            int signsDiffer = builder.compare("LT",
                    builder.integerOperation("IXOR", dividend.expression(), divisor.expression()), zero);
            int decrement = builder.integerOperation("IAND", remainderNonzero, signsDiffer);
            Value adjusted = integerMath(quotient, Value.value(one, 1.0), "ISUB");
            int result = builder.select(decrement, adjusted.expression(), quotient.expression());
            Double constant = dividend.constant() == null ? null
                    : (double) Math.floorDiv((int) (double) dividend.constant(),
                            (int) (double) divisor.constant());
            return Value.value(result, constant);
        }

        private Value integerMath(Value left, Value right, String integerOperation) {
            int expression = builder.integerOperation(integerOperation, left.expression(), right.expression());
            Double constant = null;
            if (left.constant() != null && right.constant() != null) {
                int a = (int) (double) left.constant(), b = (int) (double) right.constant();
                constant = switch (integerOperation) {
                    case "IMUL" -> (double) (a * b);
                    case "ISUB" -> (double) (a - b);
                    case "IDIV" -> (double) (a / b);
                    case "IREM" -> (double) (a % b);
                    default -> throw new AssertionError(integerOperation);
                };
            }
            return Value.value(expression, constant);
        }

        private Value lerp2Value(Value delta1, Value delta2, Value start1, Value end1,
                                 Value start2, Value end2) {
            // Mth.lerp2 interpolates each X pair first, then interpolates the
            // two results along Y. Keeping the vanilla axis order matters for
            // custom terrain expressions with asymmetric corner values.
            Value first = lerpValue(delta1, start1, end1);
            Value second = lerpValue(delta1, start2, end2);
            return lerpValue(delta2, first, second);
        }

        private Value mappedValue(GpuDensityExpression.Mapping mapping, Value input) {
            int expression = builder.map(mapping, input.expression());
            Double constant = input.constant() == null ? null : Math.abs(input.constant());
            return Value.value(expression, constant);
        }

        private Value clampUnitLikeClampedLerp(Value input) {
            int zero = builder.constant(0.0), one = builder.constant(1.0);
            int below = builder.compare("LT", input.expression(), zero);
            int above = builder.compare("GT", input.expression(), one);
            int capHigh = builder.select(above, one, input.expression());
            int clamped = builder.select(below, zero, capHigh);
            Double constant = input.constant() == null ? null
                    : input.constant() < 0.0 ? 0.0 : input.constant() > 1.0 ? 1.0 : input.constant();
            return Value.value(clamped, constant);
        }

        private void binary(GpuDensityExpression.Operation operation) {
            Value right = popValue("binary operation"), left = popValue("binary operation");
            int expression = builder.apply(operation, left.expression(), right.expression());
            Double constant = combineConstant(operation, left.constant(), right.constant());
            Axis axis = null;
            double scale = 0.0;
            if (operation == GpuDensityExpression.Operation.MULTIPLY) {
                if (left.axis() != null && right.constant() != null) {
                    axis = left.axis(); scale = left.axisScale() * right.constant();
                } else if (right.axis() != null && left.constant() != null) {
                    axis = right.axis(); scale = right.axisScale() * left.constant();
                }
            } else if (operation == GpuDensityExpression.Operation.DIVIDE
                    && left.axis() != null && right.constant() != null && right.constant() != 0.0) {
                axis = left.axis(); scale = left.axisScale() / right.constant();
            }
            stack.add(axis == null ? Value.value(expression, constant)
                    : Value.coordinate(expression, axis, scale));
        }

        private void integerRemainder() {
            Value right = popValue("integer remainder"), left = popValue("integer remainder");
            requireNonzeroIntegerDivisor(right, "integer remainder");
            int expression = builder.integerOperation("IREM", left.expression(), right.expression());
            Double constant = left.constant() == null ? null
                    : (double) ((int) (double) left.constant() % (int) (double) right.constant());
            stack.add(Value.value(expression, constant));
        }

        private void integerBinary(String operation) {
            Value right = popValue(operation), left = popValue(operation);
            int expression = builder.integerOperation(operation, left.expression(), right.expression());
            Double constant = null;
            if (left.constant() != null && right.constant() != null) {
                int a = (int) (double) left.constant(), b = (int) (double) right.constant();
                constant = switch (operation) {
                    case "IADD" -> (double) (a + b);
                    case "ISUB" -> (double) (a - b);
                    case "IMUL" -> (double) (a * b);
                    case "IAND" -> (double) (a & b);
                    case "IOR" -> (double) (a | b);
                    case "IXOR" -> (double) (a ^ b);
                    case "ISHL" -> (double) (a << (b & 31));
                    case "ISHR" -> (double) (a >> (b & 31));
                    case "IUSHR" -> (double) (a >>> (b & 31));
                    default -> null;
                };
            }
            stack.add(Value.value(expression, constant));
        }

        private void integerDivision() {
            Value right = popValue("integer division"), left = popValue("integer division");
            requireNonzeroIntegerDivisor(right, "integer division");
            int expression = builder.integerOperation("IDIV", left.expression(), right.expression());
            Double constant = left.constant() == null ? null
                    : (double) ((int) (double) left.constant() / (int) (double) right.constant());
            stack.add(Value.value(expression, constant));
        }

        private void integerConversion(String operation) {
            Value input = popValue(operation);
            int expression = builder.integerConversion(operation, input.expression());
            Double constant = input.constant() == null ? null : (double) switch (operation) {
                case "I2B" -> (byte) (int) (double) input.constant();
                case "I2C" -> (char) (int) (double) input.constant();
                case "I2S" -> (short) (int) (double) input.constant();
                default -> throw new AssertionError(operation);
            };
            stack.add(Value.value(expression, constant));
        }

        private void floatBinary(String operation) {
            Value right = popValue(operation), left = popValue(operation);
            int expression = builder.floatOperation(operation, left.expression(), right.expression());
            Double constant = null;
            if (left.constant() != null && right.constant() != null) {
                float a = (float) (double) left.constant(), b = (float) (double) right.constant();
                float result = switch (operation) {
                    case "FADD" -> a + b;
                    case "FSUB" -> a - b;
                    case "FMUL" -> a * b;
                    case "FDIV" -> a / b;
                    case "FREM" -> a % b;
                    default -> throw new AssertionError(operation);
                };
                if (Float.isFinite(result)) constant = (double) result;
            }
            stack.add(Value.value(expression, constant));
        }

        private void floatNegate() {
            Value input = popValue("float negation");
            int expression = builder.floatOperation("FNEG", input.expression(), -1);
            stack.add(Value.value(expression, input.constant() == null ? null
                    : (double) -(float) (double) input.constant()));
        }

        private void numericCast(String operation) {
            Value input = popValue(operation);
            int expression = builder.cast(operation, input.expression());
            Double constant = null;
            if (input.constant() != null) {
                double value = input.constant();
                constant = switch (operation) {
                    case "I2F" -> (double) (float) (int) value;
                    case "D2F" -> (double) (float) value;
                    case "F2I" -> (double) (int) (float) value;
                    case "D2I" -> (double) (int) value;
                    default -> throw new AssertionError(operation);
                };
            }
            stack.add(Value.value(expression, constant));
        }

        private static void requireNonzeroIntegerDivisor(Value divisor, String operation) {
            if (divisor.constant() == null || !Double.isFinite(divisor.constant())
                    || divisor.constant() != Math.rint(divisor.constant()) || divisor.constant() == 0.0
                    || divisor.constant() < Integer.MIN_VALUE || divisor.constant() > Integer.MAX_VALUE
                    || (double) divisor.constant().floatValue() != divisor.constant()) {
                reject(operation + " requires a compile-time nonzero 32-bit divisor");
            }
        }

        private Value finish() {
            if (stack.size() != 1) reject("return stack has " + stack.size() + " values instead of one");
            Value result = stack.get(0);
            if (result.kind() != Kind.VALUE) reject("compute method returned a non-expression value");
            return result;
        }

        private void pushConstant(double value) {
            int expression = builder.constant(value);
            stack.add(Value.value(expression, (double) (float) value));
        }

        private void pushIntegerConstant(int value) {
            if ((double) (float) value != value)
                reject("integer constant is outside the exact range of the GPU constant ABI");
            int expression = builder.constant(value);
            stack.add(Value.value(expression, (double) value));
        }

        private void ldc(Object constant) {
            if (constant instanceof Number number) {
                if (constant instanceof Integer integer) pushIntegerConstant(integer);
                else if (constant instanceof Long) reject("long constants are not represented by the density IR");
                else if (constant instanceof Double) pushConstant(number.doubleValue());
                else pushConstant(number.doubleValue());
                return;
            }
            reject("unsupported constant-pool value "
                    + (constant == null ? "null" : constant.getClass().getName()));
        }

        private Value popValue(String operation) {
            Value value = pop(operation);
            if (value.kind() != Kind.VALUE) reject(operation + " expected a numeric expression");
            return value;
        }

        private Value pop(String operation) {
            if (stack.isEmpty()) reject("operand stack underflow during " + operation);
            return stack.remove(stack.size() - 1);
        }

        private Value peek(String operation) {
            if (stack.isEmpty()) reject("operand stack underflow during " + operation);
            return stack.get(stack.size() - 1);
        }

        private static boolean isMathOwner(String owner) {
            return owner.equals("java/lang/Math") || owner.equals("java/lang/StrictMath")
                    || owner.equals("net/minecraft/util/Mth");
        }

        private static Double combineConstant(GpuDensityExpression.Operation operation, Double a, Double b) {
            if (a == null || b == null) return null;
            double result = switch (operation) {
                case ADD -> a + b;
                case SUBTRACT -> a - b;
                case MULTIPLY -> a * b;
                case DIVIDE -> a / b;
                case MIN -> Math.min(a, b);
                case MAX -> Math.max(a, b);
                case REMAINDER -> a % b;
                case ATAN2 -> Math.atan2(a, b);
                case POW -> Math.pow(a, b);
            };
            return Double.isFinite(result) ? (double) (float) result : null;
        }
    }

    private static Object readField(Object instance, String owner, String name, String descriptor,
                                    boolean staticAccess) {
        Class<?> root = staticAccess ? resolveOwnerClass(instance.getClass(), owner) : instance.getClass();
        Field field = findField(root, owner, name);
        if (field == null) {
            throw new UnsupportedBytecode("field owner is not in the density class/interface hierarchy: " + owner);
        }
        try {
            if (!Type.getDescriptor(field.getType()).equals(descriptor)) {
                throw new UnsupportedBytecode("field descriptor does not match runtime field: " + name);
            }
            if (!field.trySetAccessible()) throw new UnsupportedBytecode("field is inaccessible: " + name);
            int modifiers = field.getModifiers();
            if (Modifier.isStatic(modifiers) != staticAccess)
                throw new UnsupportedBytecode("field access kind does not match runtime field: " + name);
            if (!Modifier.isFinal(modifiers))
                throw new UnsupportedBytecode("mutable field is not supported: " + name);
            return field.get(staticAccess ? null : instance);
        } catch (IllegalAccessException failure) {
            throw new UnsupportedBytecode("field cannot be read: " + name);
        }
    }

    private static Class<?> resolveOwnerClass(Class<?> resourceOwner, String internalName) {
        String binaryName = internalName.replace('/', '.');
        ClassLoader[] loaders = {resourceOwner.getClassLoader(), Thread.currentThread().getContextClassLoader(),
                GpuDensityBytecodeCompiler.class.getClassLoader()};
        for (ClassLoader loader : loaders) {
            if (loader == null) continue;
            try {
                return Class.forName(binaryName, false, loader);
            } catch (ClassNotFoundException | LinkageError ignored) {
                // Try the remaining mod and runtime class loaders.
            }
        }
        throw new UnsupportedBytecode("field owner class is unavailable: " + binaryName);
    }

    private static Field findField(Class<?> type, String owner, String name) {
        Class<?> symbolicOwner = findOwnerType(type, owner);
        return symbolicOwner == null ? null : resolveField(symbolicOwner, name);
    }

    private static Class<?> findOwnerType(Class<?> type, String owner) {
        if (Type.getInternalName(type).equals(owner)) return type;
        for (Class<?> implemented : type.getInterfaces()) {
            Class<?> match = findOwnerType(implemented, owner);
            if (match != null) return match;
        }
        Class<?> parent = type.getSuperclass();
        return parent == null ? null : findOwnerType(parent, owner);
    }

    /** Resolves a JVM field reference through the symbolic owner's interfaces and superclass. */
    private static Field resolveField(Class<?> owner, String name) {
        try { return owner.getDeclaredField(name); }
        catch (NoSuchFieldException missing) {
            for (Class<?> implemented : owner.getInterfaces()) {
                Field inherited = resolveField(implemented, name);
                if (inherited != null) return inherited;
            }
            Class<?> parent = owner.getSuperclass();
            return parent == null ? null : resolveField(parent, name);
        }
    }

    private static void reject(String reason) { throw new UnsupportedBytecode(reason); }

    private static String instructionLocation(AbstractInsnNode instruction) {
        if (instruction instanceof MethodInsnNode call)
            return "call " + call.owner.replace('/', '.') + "." + call.name + call.desc;
        if (instruction instanceof FieldInsnNode field)
            return "field " + field.owner.replace('/', '.') + "." + field.name + field.desc;
        return instruction.getClass().getSimpleName() + "/opcode-" + instruction.getOpcode();
    }

    private static String opcodeName(int opcode) {
        if (opcode == Opcodes.GOTO) return "GOTO";
        if (opcode >= Opcodes.IFEQ && opcode <= Opcodes.IF_ACMPNE) return "conditional branch";
        if (opcode == Opcodes.TABLESWITCH || opcode == Opcodes.LOOKUPSWITCH) return "switch";
        if (opcode == Opcodes.JSR || opcode == Opcodes.RET) return "subroutine";
        return "opcode-" + opcode;
    }

    private static final class UnsupportedBytecode extends RuntimeException {
        UnsupportedBytecode(String message) { super(message); }
        UnsupportedBytecode(String message, Throwable cause) { super(message, cause); }
    }
}
