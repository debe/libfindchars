package org.knownhosts.libfindchars.compiler.inline;

import java.lang.classfile.*;
import java.lang.classfile.instruction.*;
import java.util.*;

/**
 * Removes unreachable branches after constant folding.
 * <p>
 * After {@link ConstantFolder} replaces {@code getfield} loads of {@code @Inline int}
 * fields with constant pushes, this pass:
 * <ol>
 *   <li>Resolves constant comparisons ({@code iconst_X; iconst_Y; if_icmpXX label})
 *       to unconditional branches or fall-through</li>
 *   <li>Resolves constant single-operand branches ({@code iconst_X; ifeq/ifne/iflt/... label})</li>
 *   <li>Removes unreachable code between unconditional control flow and the next label</li>
 * </ol>
 */
public final class DeadCodeEliminator {

    private DeadCodeEliminator() {}

    /**
     * Eliminate dead code from constant-folded comparisons.
     */
    public static byte[] eliminate(byte[] classBytes) {
        var cf = ClassFile.of();
        ClassModel model = cf.parse(classBytes);

        return cf.transformClass(model, (classBuilder, classElement) -> {
            if (classElement instanceof MethodModel method) {
                var codeOpt = method.findAttribute(Attributes.code());
                if (codeOpt.isPresent()) {
                    classBuilder.withMethodBody(
                            method.methodName().stringValue(),
                            method.methodTypeSymbol(),
                            method.flags().flagsMask(),
                            code -> eliminateInMethod(code, codeOpt.get()));
                } else {
                    classBuilder.with(classElement);
                }
            } else {
                classBuilder.with(classElement);
            }
        });
    }

    private static void eliminateInMethod(CodeBuilder cb, CodeModel codeModel) {
        var elements = codeModel.elementList();

        // First pass: collect elements and indices, resolve constant comparisons.
        // Use parallel lists: elements + a map of indices that should become goto targets.
        List<CodeElement> resolved = new ArrayList<>();
        Map<Integer, Label> resolvedGotos = new HashMap<>(); // index → goto target

        for (int i = 0; i < elements.size(); i++) {
            var elem = elements.get(i);

            // Pattern: iconst_X; iconst_Y; if_icmpXX label
            if (elem instanceof BranchInstruction bi && isIfIcmp(bi.opcode())) {
                int constVal2 = peekConst(resolved, resolved.size() - 1);
                int constVal1 = peekConst(resolved, resolved.size() - 2);
                if (constVal1 != Integer.MIN_VALUE && constVal2 != Integer.MIN_VALUE) {
                    boolean taken = evaluateIcmp(bi.opcode(), constVal1, constVal2);
                    resolved.remove(resolved.size() - 1);
                    resolved.remove(resolved.size() - 1);
                    if (taken) {
                        resolvedGotos.put(resolved.size(), bi.target());
                        resolved.add(elem); // placeholder
                    }
                    continue;
                }
            }

            // Pattern: iconst_X; ifeq/ifne/iflt/ifge/ifgt/ifle label
            if (elem instanceof BranchInstruction bi && isIfSingle(bi.opcode())) {
                int constVal = peekConst(resolved, resolved.size() - 1);
                if (constVal != Integer.MIN_VALUE) {
                    boolean taken = evaluateIfSingle(bi.opcode(), constVal);
                    resolved.remove(resolved.size() - 1);
                    if (taken) {
                        resolvedGotos.put(resolved.size(), bi.target());
                        resolved.add(elem); // placeholder
                    }
                    continue;
                }
            }

            resolved.add(elem);
        }

        // Second pass: find reachable labels
        Set<Label> reachableLabels = new HashSet<>();
        for (int idx = 0; idx < resolved.size(); idx++) {
            var gotoTarget = resolvedGotos.get(idx);
            if (gotoTarget != null) {
                reachableLabels.add(gotoTarget);
                continue;
            }
            var elem = resolved.get(idx);
            if (elem instanceof BranchInstruction bi) {
                reachableLabels.add(bi.target());
            } else if (elem instanceof LookupSwitchInstruction lsi) {
                reachableLabels.add(lsi.defaultTarget());
                for (var c : lsi.cases()) reachableLabels.add(c.target());
            } else if (elem instanceof TableSwitchInstruction tsi) {
                reachableLabels.add(tsi.defaultTarget());
                for (var c : tsi.cases()) reachableLabels.add(c.target());
            } else if (elem instanceof ExceptionCatch ec) {
                reachableLabels.add(ec.tryStart());
                reachableLabels.add(ec.tryEnd());
                reachableLabels.add(ec.handler());
            }
        }

        // Third pass: emit code, removing unreachable sections
        boolean unreachable = false;
        for (int idx = 0; idx < resolved.size(); idx++) {
            var elem = resolved.get(idx);

            if (elem instanceof LabelTarget lt) {
                if (reachableLabels.contains(lt.label())) {
                    unreachable = false;
                }
                cb.labelBinding(lt.label());
                continue;
            }

            if (unreachable) continue;

            // Skip debug attributes that reference removed code
            if (elem instanceof LocalVariable || elem instanceof LocalVariableType) continue;

            var gotoTarget = resolvedGotos.get(idx);
            if (gotoTarget != null) {
                cb.goto_(gotoTarget);
                unreachable = true;
            } else if (elem instanceof ReturnInstruction) {
                cb.with(elem);
                unreachable = true;
            } else if (elem instanceof ThrowInstruction) {
                cb.with(elem);
                unreachable = true;
            } else if (elem instanceof BranchInstruction bi && bi.opcode() == Opcode.GOTO) {
                cb.with(elem);
                unreachable = true;
            } else {
                cb.with(elem);
            }
        }
    }

    private static int peekConst(List<CodeElement> elements, int index) {
        if (index < 0 || index >= elements.size()) return Integer.MIN_VALUE;
        var elem = elements.get(index);
        if (elem instanceof ConstantInstruction ci) {
            if (ci.constantValue() instanceof Integer iv) return iv;
        }
        return Integer.MIN_VALUE;
    }

    private static boolean isIfIcmp(Opcode opcode) {
        return opcode == Opcode.IF_ICMPEQ || opcode == Opcode.IF_ICMPNE
                || opcode == Opcode.IF_ICMPLT || opcode == Opcode.IF_ICMPGE
                || opcode == Opcode.IF_ICMPGT || opcode == Opcode.IF_ICMPLE;
    }

    private static boolean isIfSingle(Opcode opcode) {
        return opcode == Opcode.IFEQ || opcode == Opcode.IFNE
                || opcode == Opcode.IFLT || opcode == Opcode.IFGE
                || opcode == Opcode.IFGT || opcode == Opcode.IFLE;
    }

    private static boolean evaluateIcmp(Opcode opcode, int val1, int val2) {
        return switch (opcode) {
            case IF_ICMPEQ -> val1 == val2;
            case IF_ICMPNE -> val1 != val2;
            case IF_ICMPLT -> val1 < val2;
            case IF_ICMPGE -> val1 >= val2;
            case IF_ICMPGT -> val1 > val2;
            case IF_ICMPLE -> val1 <= val2;
            default -> throw new IllegalArgumentException("Not an if_icmp: " + opcode);
        };
    }

    private static boolean evaluateIfSingle(Opcode opcode, int val) {
        return switch (opcode) {
            case IFEQ -> val == 0;
            case IFNE -> val != 0;
            case IFLT -> val < 0;
            case IFGE -> val >= 0;
            case IFGT -> val > 0;
            case IFLE -> val <= 0;
            default -> throw new IllegalArgumentException("Not an if: " + opcode);
        };
    }

}
