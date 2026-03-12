package org.knownhosts.libfindchars.compiler.inline;

import java.lang.classfile.*;
import java.lang.classfile.instruction.*;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.util.HashMap;
import java.util.Map;

/**
 * Core inlining algorithm: transplants bytecodes from a source method
 * into a target CodeBuilder, remapping slots and labels.
 */
final class MethodInliner {

    private final Map<String, MethodModel> inlineMethods;
    private final Map<String, MethodModel> allMethods;
    private final ClassDesc targetOwner;

    MethodInliner(Map<String, MethodModel> inlineMethods,
                  Map<String, MethodModel> allMethods,
                  ClassDesc targetOwner) {
        this.inlineMethods = inlineMethods;
        this.allMethods = allMethods;
        this.targetOwner = targetOwner;
    }

    /**
     * Inline a method's bytecodes into the given CodeBuilder.
     *
     * @param cb        target code builder
     * @param method    the method to inline
     * @param slotBase  first local variable slot available for the inlined method
     * @param depth     current recursion depth
     * @param maxDepth  maximum recursion depth
     */
    void inlineMethod(CodeBuilder cb, MethodModel method, int slotBase, int depth, int maxDepth) {
        var codeAttr = method.findAttribute(Attributes.code()).orElseThrow(
                () -> new IllegalStateException("No Code attribute on method: " + method.methodName()));

        // Count parameters to pop from stack into local slots
        var desc = method.methodTypeSymbol();
        int paramCount = desc.parameterCount();

        // Store arguments from stack into remapped local slots (reverse order — stack is LIFO)
        for (int p = paramCount - 1; p >= 0; p--) {
            var paramType = desc.parameterType(p);
            var tk = TypeKind.from(paramType);
            cb.storeLocal(tk, slotBase + paramSlot(desc, p));
        }

        // Build label remap table
        Map<Label, Label> labelMap = new HashMap<>();
        for (var elem : codeAttr.elementList()) {
            if (elem instanceof LabelTarget lt) {
                labelMap.put(lt.label(), cb.newLabel());
            }
        }

        // Determine return handling
        int returnCount = countReturns(codeAttr);
        boolean multiReturn = returnCount > 1;
        Label endLabel = multiReturn ? cb.newLabel() : null;
        // For multi-return, allocate a temp slot for the return value
        var returnType = desc.returnType();
        var returnKind = returnType.descriptorString().equals("V") ? null : TypeKind.from(returnType);
        int returnSlot = slotBase + codeAttr.maxLocals();

        // Determine max locals of this method for recursive inlining slot base
        int nextSlotBase = slotBase + codeAttr.maxLocals() + (multiReturn && returnKind != null ? returnKind.slotSize() : 0);

        // Emit each element
        for (var elem : codeAttr.elementList()) {
            switch (elem) {
                case LabelTarget lt ->
                    cb.labelBinding(labelMap.get(lt.label()));

                case LoadInstruction li ->
                    cb.loadLocal(li.typeKind(), li.slot() + slotBase);

                case StoreInstruction si ->
                    cb.storeLocal(si.typeKind(), si.slot() + slotBase);

                case IncrementInstruction ii ->
                    cb.iinc(ii.slot() + slotBase, ii.constant());

                case BranchInstruction bi ->
                    cb.branch(bi.opcode(), labelMap.get(bi.target()));

                case LookupSwitchInstruction lsi -> {
                    var cases = lsi.cases().stream()
                            .map(c -> SwitchCase.of(c.caseValue(), labelMap.get(c.target())))
                            .toList();
                    cb.lookupswitch(labelMap.get(lsi.defaultTarget()), cases);
                }

                case TableSwitchInstruction tsi -> {
                    var cases = tsi.cases().stream()
                            .map(c -> SwitchCase.of(c.caseValue(), labelMap.get(c.target())))
                            .toList();
                    cb.tableswitch(tsi.lowValue(), tsi.highValue(),
                            labelMap.get(tsi.defaultTarget()), cases);
                }

                case ReturnInstruction ri -> {
                    if (!multiReturn) {
                        // Single return: just skip — value stays on stack
                    } else {
                        if (returnKind != null) {
                            cb.storeLocal(returnKind, returnSlot);
                        }
                        cb.branch(Opcode.GOTO, endLabel);
                    }
                }

                case InvokeInstruction ii -> {
                    if (ii.opcode() == Opcode.INVOKESTATIC
                            && ii.owner().asSymbol().equals(targetOwner)
                            && depth < maxDepth) {
                        String key = ii.name().stringValue() + ii.type().stringValue();
                        var callee = allMethods.get(key);
                        if (callee != null) {
                            inlineMethod(cb, callee, nextSlotBase, depth + 1, maxDepth);
                        } else {
                            cb.with(ii);
                        }
                    } else {
                        cb.with(ii);
                    }
                }

                case ExceptionCatch ec ->
                    cb.exceptionCatch(
                            labelMap.get(ec.tryStart()),
                            labelMap.get(ec.tryEnd()),
                            labelMap.get(ec.handler()),
                            ec.catchType());

                // Drop debug metadata from inlined source
                case LineNumber _ -> {}
                case LocalVariable _ -> {}
                case LocalVariableType _ -> {}
                case CharacterRange _ -> {}

                case Instruction i ->
                    cb.with(i);

                // Skip other pseudo-instructions (StackMapTableAttribute, etc.)
                default -> {}
            }
        }

        // Multi-return: bind end label and reload return value
        if (multiReturn) {
            cb.labelBinding(endLabel);
            if (returnKind != null) {
                cb.loadLocal(returnKind, returnSlot);
            }
        }
    }

    /**
     * Calculate the local variable slot offset for parameter p,
     * accounting for wide types (long, double) taking 2 slots.
     */
    private static int paramSlot(MethodTypeDesc desc, int p) {
        int slot = 0;
        for (int i = 0; i < p; i++) {
            slot += TypeKind.from(desc.parameterType(i)).slotSize();
        }
        return slot;
    }

    private static int countReturns(CodeModel code) {
        int count = 0;
        for (var elem : code.elementList()) {
            if (elem instanceof ReturnInstruction) count++;
        }
        return count;
    }
}
