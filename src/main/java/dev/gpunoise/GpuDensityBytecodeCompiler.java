package dev.gpunoise;

import java.io.InputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
        try (InputStream bytecode = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) {
            if (bytecode == null) return Result.rejected("class bytecode resource is unavailable");
            ClassNode owner = new ClassNode(Opcodes.ASM9);
            new ClassReader(bytecode).accept(owner, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            MethodNode compute = null;
            for (MethodNode method : owner.methods) {
                if (method.name.equals("compute") && method.desc.equals(COMPUTE_DESC)) {
                    if (compute != null) return Result.rejected("class contains duplicate compute(FunctionContext) methods");
                    compute = method;
                }
            }
            if (compute == null) return Result.rejected("compute(FunctionContext):double bytecode was not found");
            Interpreter interpreter = new Interpreter(function, owner, compute,
                    new GpuDensityExpression.Builder(), 0, Value.receiver(Kind.THIS),
                    new Value[]{Value.receiver(Kind.CONTEXT)});
            return interpreter.run();
        } catch (UnsupportedBytecode rejected) {
            return Result.rejected(rejected.getMessage());
        } catch (IOException failure) {
            return Result.rejected("cannot read class bytecode: " + failureSummary(failure));
        } catch (RuntimeException failure) {
            return Result.rejected("bytecode inspection failed: " + failureSummary(failure));
        }
    }

    private static String failureSummary(Exception failure) {
        String message = failure.getMessage();
        return failure.getClass().getSimpleName()
                + (message == null || message.isBlank() ? "" : ": " + message);
    }

    private enum Kind { VALUE, THIS, CONTEXT, NOISE, DENSITY, COMPARE }
    private enum Axis { X, Y, Z }
    private record LoopRegion(int firstInstruction, int backEdgeInstruction) {}

    private record Value(Kind kind, int expression, NormalNoise noise, DensityFunction density,
                         Axis axis, double axisScale, Double constant,
                         int comparisonRight, String comparisonMode) {
        static Value value(int expression, Double constant) {
            return new Value(Kind.VALUE, expression, null, null, null, 0.0, constant, -1, null);
        }
        static Value coordinate(int expression, Axis axis, double scale) {
            return new Value(Kind.VALUE, expression, null, null, axis, scale, null, -1, null);
        }
        static Value receiver(Kind kind) {
            return new Value(kind, -1, null, null, null, 0.0, null, -1, null);
        }
        static Value noise(NormalNoise noise) {
            return new Value(Kind.NOISE, -1, noise, null, null, 0.0, null, -1, null);
        }
        static Value density(DensityFunction density) {
            return new Value(Kind.DENSITY, -1, null, density, null, 0.0, null, -1, null);
        }
        static Value comparison(int left, int right, String mode) {
            return new Value(Kind.COMPARE, left, null, null, null, 0.0, null, right, mode);
        }
    }

    private static final class Interpreter {
        private final DensityFunction function;
        private final ClassNode owner;
        private final MethodNode method;
        private final GpuDensityExpression.Builder builder;
        private final int inlineDepth;
        private List<Value> stack = new ArrayList<>();
        private Map<Integer, Value> locals = new HashMap<>();
        private final AbstractInsnNode[] instructions;
        private final Map<LabelNode, Integer> labels = new java.util.IdentityHashMap<>();
        private final List<LoopRegion> loopRegions = new ArrayList<>();
        private int branchForks;
        private int remainingInstructions = 50_000;
        private Map<Integer, Integer> loopIterations = new HashMap<>();

        Interpreter(DensityFunction function, ClassNode owner, MethodNode method,
                    GpuDensityExpression.Builder builder, int inlineDepth,
                    Value receiver, Value[] arguments) {
            this.function = function;
            this.owner = owner;
            this.method = method;
            this.builder = builder;
            this.inlineDepth = inlineDepth;
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
                if (--remainingInstructions < 0) reject("control-flow expansion exceeds 50,000 bytecode steps");
                AbstractInsnNode instruction = instructions[pc];
                int type = instruction.getType();
                if (type == AbstractInsnNode.LABEL || type == AbstractInsnNode.LINE
                        || type == AbstractInsnNode.FRAME) continue;
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
            if (!staticRead) {
                Value receiver = pop("field access");
                if (receiver.kind() != Kind.THIS)
                    reject("field access through a value other than the density object: " + instruction.name);
            }
            Object fieldValue = readField(function, instruction.owner, instruction.name,
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
            } else {
                reject("field " + instruction.name + " has unsupported runtime type "
                        + (fieldValue == null ? "null" : fieldValue.getClass().getName()));
            }
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

            if (owner.equals(this.owner.name)) {
                MethodNode target = null;
                for (MethodNode candidate : this.owner.methods) {
                    if (candidate.name.equals(name) && candidate.desc.equals(call.desc)) {
                        target = candidate;
                        break;
                    }
                }
                if (target != null) {
                    if (inlineDepth >= 16) reject("same-class helper-call depth exceeds 16");
                    boolean targetStatic = (target.access & Opcodes.ACC_STATIC) != 0;
                    if (targetStatic != (call.getOpcode() == Opcodes.INVOKESTATIC))
                        reject("helper invocation staticness does not match its method declaration");
                    if (!targetStatic && (receiver == null || receiver.kind() != Kind.THIS))
                        reject("same-class instance helper must be called on the density object");
                    if (Type.getReturnType(target.desc).getSort() == Type.VOID)
                        reject("void helper calls have side effects and cannot be inlined");
                    Value result = new Interpreter(function, this.owner, target, builder, inlineDepth + 1,
                            targetStatic ? null : receiver, arguments).runInline();
                    if (result.kind() != Kind.VALUE)
                        reject("same-class helper returned a nonnumeric value");
                    stack.add(result);
                    return;
                }
            }
            reject("opaque method call " + owner.replace('/', '.') + "." + name + call.desc);
        }

        private void mathCall(String owner, String name, String descriptor, Value[] arguments) {
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
                    && name.equals("signum") && descriptor.equals("(D)D");
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
            if (owner.equals("net/minecraft/util/Mth") && name.equals("lerp")
                    && descriptor.equals("(DDD)D") && arguments.length == 3) {
                stack.add(lerpValue(arguments[0], arguments[1], arguments[2]));
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
        Field field = findField(instance.getClass(), owner, name);
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
            if (staticAccess && !field.getType().isPrimitive())
                throw new UnsupportedBytecode("static final object fields are not numeric constants: " + name);
            return field.get(staticAccess ? null : instance);
        } catch (IllegalAccessException failure) {
            throw new UnsupportedBytecode("field cannot be read: " + name);
        }
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

    private static String opcodeName(int opcode) {
        if (opcode == Opcodes.GOTO) return "GOTO";
        if (opcode >= Opcodes.IFEQ && opcode <= Opcodes.IF_ACMPNE) return "conditional branch";
        if (opcode == Opcodes.TABLESWITCH || opcode == Opcodes.LOOKUPSWITCH) return "switch";
        if (opcode == Opcodes.JSR || opcode == Opcodes.RET) return "subroutine";
        return "opcode-" + opcode;
    }

    private static final class UnsupportedBytecode extends RuntimeException {
        UnsupportedBytecode(String message) { super(message); }
    }
}
