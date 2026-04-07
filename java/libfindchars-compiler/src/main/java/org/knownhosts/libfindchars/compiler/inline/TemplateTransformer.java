package org.knownhosts.libfindchars.compiler.inline;

import java.lang.classfile.*;
import java.lang.classfile.instruction.*;
import java.lang.constant.ClassDesc;
import java.util.HashMap;
import java.util.Map;

/**
 * Orchestrates the bytecode specialization pipeline for template classes.
 * <p>
 * Given a template class (compiled from normal Java with {@code @Inline} annotations)
 * and a {@link SpecializationConfig}, produces specialized bytecode with:
 * <ol>
 *   <li>Inlined {@code @Inline private} method bodies</li>
 *   <li>Constant-folded {@code @Inline int} fields</li>
 *   <li>Dead code elimination on unreachable branches</li>
 * </ol>
 * <p>
 * The transformer is generic and not specific to any particular domain.
 *
 * <h2>Usage</h2>
 * <pre>
 * byte[] classBytes = readClassBytes(MyTemplate.class);
 * byte[] specialized = TemplateTransformer.transform(classBytes, config);
 * specialized = BytecodeInliner.inline(specialized, KernelClass.class);
 * </pre>
 */
public final class TemplateTransformer {

    private static final String INLINE_DESCRIPTOR = "Lorg/knownhosts/libfindchars/api/Inline;";

    private TemplateTransformer() {}

    /**
     * Transform a template class according to the given specialization config.
     *
     * @param classBytes  compiled .class bytes of the template
     * @param config      specialization parameters (constants, array sizes)
     * @return specialized class bytes
     */
    public static byte[] transform(byte[] classBytes, SpecializationConfig config) {
        byte[] result = classBytes;

        // 1. Inline @Inline private methods within the template itself
        result = inlinePrivateMethods(result);

        // 2. Constant fold @Inline int fields
        result = ConstantFolder.foldWithAloadRemoval(result, config.constants());

        // 3. Dead code elimination (benefits from constant folding)
        result = DeadCodeEliminator.eliminate(result);

        return result;
    }

    /**
     * Transform and rename the class to a new class descriptor.
     */
    public static byte[] transform(byte[] classBytes, SpecializationConfig config, ClassDesc newName) {
        byte[] result = transform(classBytes, config);
        return renameClass(result, newName);
    }

    /**
     * Inline all {@code @Inline}-annotated private instance methods within the class.
     * This transplants the method body at each call site ({@code invokespecial} or
     * {@code invokevirtual this}).
     */
    private static byte[] inlinePrivateMethods(byte[] classBytes) {
        var cf = ClassFile.of();
        ClassModel model = cf.parse(classBytes);
        ClassDesc owner = model.thisClass().asSymbol();

        // Find @Inline private methods
        Map<String, MethodModel> inlineMethods = new HashMap<>();
        Map<String, MethodModel> allMethods = new HashMap<>();

        for (var method : model.methods()) {
            String key = method.methodName().stringValue() + method.methodType().stringValue();
            allMethods.put(key, method);

            if (isInlineAnnotated(method) && isPrivateInstance(method)) {
                inlineMethods.put(key, method);
            }
        }

        if (inlineMethods.isEmpty()) return classBytes;

        var inliner = new MethodInliner(inlineMethods, allMethods, owner);

        // Transform: inline invokespecial/invokevirtual calls to @Inline private methods
        byte[] result = cf.transformClass(model, ClassTransform.transformingMethodBodies(
                (cb, elem) -> {
                    if (elem instanceof InvokeInstruction ii
                            && (ii.opcode() == Opcode.INVOKESPECIAL || ii.opcode() == Opcode.INVOKEVIRTUAL)
                            && ii.owner().asSymbol().equals(owner)) {
                        String key = ii.name().stringValue() + ii.type().stringValue();
                        var method = inlineMethods.get(key);
                        if (method != null) {
                            int maxDepth = BytecodeInliner.getMaxDepth(method);
                            // Pop 'this' from stack — private instance methods have 'this' as first arg
                            // The MethodInliner expects static-style params on stack,
                            // but for instance methods, 'this' is param 0.
                            // We need to include 'this' in the slot remapping.
                            inlineInstanceMethod(cb, method, inliner, owner, inlineMethods, allMethods, maxDepth, 20);
                            return;
                        }
                    }
                    cb.with(elem);
                }));

        // Remove the inlined private methods from the class
        var resultModel = cf.parse(result);
        return cf.transformClass(resultModel, ClassTransform.dropping(e ->
                e instanceof MethodModel mm && inlineMethods.containsKey(
                        mm.methodName().stringValue() + mm.methodType().stringValue())));
    }

    /**
     * Inline an instance method body. Instance methods have 'this' as slot 0,
     * so we need to handle that in the slot remapping.
     *
     * @param slotBase base slot offset for this inlining level — each recursive
     *                 inline uses a higher base to avoid clobbering parent locals
     */
    private static int inlineInstanceMethod(CodeBuilder cb, MethodModel method,
            MethodInliner staticInliner, ClassDesc owner,
            Map<String, MethodModel> inlineMethods, Map<String, MethodModel> allMethods,
            int maxDepth, int slotBase) {
        var codeAttr = method.findAttribute(Attributes.code()).orElseThrow();
        var desc = method.methodTypeSymbol();

        storeInstanceParams(cb, desc, slotBase);

        Map<Label, Label> labelMap = buildLabelMap(cb, codeAttr);

        int returnCount = countReturns(codeAttr);
        boolean multiReturn = returnCount > 1;
        Label endLabel = multiReturn ? cb.newLabel() : null;
        var returnKind = desc.returnType().descriptorString().equals("V")
                ? null : TypeKind.from(desc.returnType());
        int returnSlot = slotBase + codeAttr.maxLocals();
        int nextSlotBase = returnSlot + (multiReturn && returnKind != null ? returnKind.slotSize() : 0);

        int highWaterSlot = emitInstanceElements(cb, codeAttr, labelMap, slotBase, nextSlotBase,
                owner, inlineMethods, allMethods, staticInliner, maxDepth,
                multiReturn, endLabel, returnKind, returnSlot);

        if (multiReturn) {
            cb.labelBinding(endLabel);
            if (returnKind != null) cb.loadLocal(returnKind, returnSlot);
        }
        return highWaterSlot;
    }

    private static void storeInstanceParams(CodeBuilder cb, java.lang.constant.MethodTypeDesc desc, int slotBase) {
        for (int p = desc.parameterCount() - 1; p >= 0; p--) {
            cb.storeLocal(TypeKind.from(desc.parameterType(p)), slotBase + 1 + paramSlot(desc, p));
        }
        cb.astore(slotBase);
    }

    private static Map<Label, Label> buildLabelMap(CodeBuilder cb, CodeModel codeAttr) {
        Map<Label, Label> labelMap = new HashMap<>();
        for (var elem : codeAttr.elementList()) {
            if (elem instanceof LabelTarget lt) labelMap.put(lt.label(), cb.newLabel());
        }
        return labelMap;
    }

    private static int emitInstanceElements(CodeBuilder cb, CodeModel codeAttr, Map<Label, Label> labelMap,
            int slotBase, int nextSlotBase, ClassDesc owner,
            Map<String, MethodModel> inlineMethods, Map<String, MethodModel> allMethods,
            MethodInliner staticInliner, int maxDepth,
            boolean multiReturn, Label endLabel, TypeKind returnKind, int returnSlot) {
        int highWaterSlot = nextSlotBase;
        for (var elem : codeAttr.elementList()) {
            switch (elem) {
                case LabelTarget lt -> cb.labelBinding(labelMap.get(lt.label()));
                case LoadInstruction li -> cb.loadLocal(li.typeKind(), li.slot() + slotBase);
                case StoreInstruction si -> cb.storeLocal(si.typeKind(), si.slot() + slotBase);
                case IncrementInstruction ii -> cb.iinc(ii.slot() + slotBase, ii.constant());
                case BranchInstruction bi -> cb.branch(bi.opcode(), labelMap.get(bi.target()));
                case LookupSwitchInstruction lsi -> {
                    var cases = lsi.cases().stream()
                            .map(c -> SwitchCase.of(c.caseValue(), labelMap.get(c.target()))).toList();
                    cb.lookupswitch(labelMap.get(lsi.defaultTarget()), cases);
                }
                case TableSwitchInstruction tsi -> {
                    var cases = tsi.cases().stream()
                            .map(c -> SwitchCase.of(c.caseValue(), labelMap.get(c.target()))).toList();
                    cb.tableswitch(tsi.lowValue(), tsi.highValue(), labelMap.get(tsi.defaultTarget()), cases);
                }
                case ReturnInstruction _ -> {
                    if (multiReturn) {
                        if (returnKind != null) cb.storeLocal(returnKind, returnSlot);
                        cb.branch(Opcode.GOTO, endLabel);
                    }
                }
                case InvokeInstruction ii -> {
                    if ((ii.opcode() == Opcode.INVOKESPECIAL || ii.opcode() == Opcode.INVOKEVIRTUAL)
                            && ii.owner().asSymbol().equals(owner)) {
                        var callee = inlineMethods.get(ii.name().stringValue() + ii.type().stringValue());
                        if (callee != null && maxDepth > 0) {
                            int used = inlineInstanceMethod(cb, callee, staticInliner, owner,
                                    inlineMethods, allMethods, maxDepth - 1, nextSlotBase);
                            highWaterSlot = Math.max(highWaterSlot, used);
                        } else cb.with(ii);
                    } else cb.with(ii);
                }
                case ExceptionCatch ec -> cb.exceptionCatch(
                        labelMap.get(ec.tryStart()), labelMap.get(ec.tryEnd()),
                        labelMap.get(ec.handler()), ec.catchType());
                case LineNumber _, LocalVariable _, LocalVariableType _, CharacterRange _ -> {}
                case Instruction i -> cb.with(i);
                default -> {}
            }
        }
        return highWaterSlot;
    }

    private static int paramSlot(java.lang.constant.MethodTypeDesc desc, int p) {
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

    private static boolean isInlineAnnotated(MethodModel method) {
        return method.findAttribute(Attributes.runtimeVisibleAnnotations())
                .map(ann -> ann.annotations().stream()
                        .anyMatch(a -> a.classSymbol().descriptorString().equals(INLINE_DESCRIPTOR)))
                .orElse(false);
    }

    private static boolean isPrivateInstance(MethodModel method) {
        return method.flags().has(java.lang.reflect.AccessFlag.PRIVATE)
                && !method.flags().has(java.lang.reflect.AccessFlag.STATIC);
    }

    private static final ClassDesc CHUNK_FILTER_DESC =
            ClassDesc.of("org.knownhosts.libfindchars.api.ChunkFilter");

    /**
     * Devirtualize filter calls: rewrite {@code invokeinterface ChunkFilter.apply()}
     * to {@code invokestatic <targetOwner>.applyStatic()}. This converts the runtime
     * virtual dispatch into a static call that {@link BytecodeInliner} can inline.
     *
     * <p>Also removes the preceding {@code aload} of the {@code chunkFilter} field
     * (the receiver is not needed for a static call) by rewriting the
     * {@code getfield chunkFilter} to a {@code pop} (removes the {@code this} ref
     * that was loaded for the field access).
     */
    public static byte[] devirtualizeFilter(byte[] classBytes, ClassDesc targetOwner) {
        var cf = ClassFile.of();
        ClassModel model = cf.parse(classBytes);

        return cf.transformClass(model, ClassTransform.transformingMethodBodies(
                (cb, elem) -> {
                    if (elem instanceof InvokeInstruction ii
                            && ii.opcode() == Opcode.INVOKEINTERFACE
                            && ii.owner().asSymbol().equals(CHUNK_FILTER_DESC)
                            && ii.name().stringValue().equals("apply")) {
                        // Replace invokeinterface ChunkFilter.apply with invokestatic targetOwner.applyStatic
                        cb.invokestatic(targetOwner, "applyStatic", ii.typeSymbol());
                    } else if (elem instanceof FieldInstruction fi
                            && fi.opcode() == Opcode.GETFIELD
                            && fi.name().stringValue().equals("chunkFilter")) {
                        // The getfield pushed the filter ref; replace with pop to discard `this`
                        // that was loaded by the preceding aload_0 for the field access
                        cb.pop();
                    } else {
                        cb.with(elem);
                    }
                }));
    }

    /**
     * Rename a class in its bytecode. Rewrites the class name and all self-references
     * (field owners, method owners in the same class).
     */
    public static byte[] renameClass(byte[] classBytes, ClassDesc newName) {
        var cf = ClassFile.of();
        ClassModel model = cf.parse(classBytes);
        ClassDesc oldName = model.thisClass().asSymbol();

        return cf.build(newName, cb -> {
            cb.withFlags(model.flags().flagsMask());

            // Interfaces
            for (var iface : model.interfaces()) {
                cb.withInterfaceSymbols(iface.asSymbol());
            }

            // Superclass
            model.superclass().ifPresent(sc -> cb.withSuperclass(sc.asSymbol()));

            // Fields
            for (var field : model.fields()) {
                cb.withField(field.fieldName().stringValue(), field.fieldTypeSymbol(),
                        field.flags().flagsMask());
            }

            // Methods — rewrite self-references from oldName to newName
            for (var method : model.methods()) {
                var codeOpt = method.findAttribute(Attributes.code());
                if (codeOpt.isEmpty()) {
                    // Abstract or native — copy as-is
                    cb.withMethod(method.methodName().stringValue(), method.methodTypeSymbol(),
                            method.flags().flagsMask(), _ -> {});
                    continue;
                }
                cb.withMethodBody(method.methodName().stringValue(),
                        method.methodTypeSymbol(),
                        method.flags().flagsMask(),
                        code -> {
                            for (var elem : codeOpt.get().elementList()) {
                                if (elem instanceof FieldInstruction fi
                                        && fi.owner().asSymbol().equals(oldName)) {
                                    if (fi.opcode() == Opcode.GETFIELD) {
                                        code.getfield(newName, fi.name().stringValue(), fi.typeSymbol());
                                    } else if (fi.opcode() == Opcode.PUTFIELD) {
                                        code.putfield(newName, fi.name().stringValue(), fi.typeSymbol());
                                    } else {
                                        code.with(fi);
                                    }
                                } else if (elem instanceof InvokeInstruction ii
                                        && ii.owner().asSymbol().equals(oldName)) {
                                    if (ii.opcode() == Opcode.INVOKESPECIAL) {
                                        code.invokespecial(newName, ii.name().stringValue(), ii.typeSymbol());
                                    } else if (ii.opcode() == Opcode.INVOKEVIRTUAL) {
                                        code.invokevirtual(newName, ii.name().stringValue(), ii.typeSymbol());
                                    } else {
                                        code.with(ii);
                                    }
                                } else {
                                    switch (elem) {
                                        case Instruction i -> code.with(i);
                                        case LabelTarget lt -> code.labelBinding(lt.label());
                                        case ExceptionCatch ec -> code.exceptionCatch(
                                                ec.tryStart(), ec.tryEnd(), ec.handler(), ec.catchType());
                                        default -> {}
                                    }
                                }
                            }
                        });
            }
        });
    }
}
