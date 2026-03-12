package org.knownhosts.libfindchars.compiler.inline;

import java.lang.classfile.*;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.constant.ClassDesc;
import java.util.HashMap;
import java.util.Map;

/**
 * Bytecode-level inliner that transplants {@code @Inline}-annotated method bodies
 * into generated class bytes, eliminating invokestatic calls in the hot path.
 * <p>
 * Usage:
 * <pre>
 * byte[] classBytes = gen.generateClassBytes();
 * classBytes = BytecodeInliner.inline(classBytes, EngineKernel.class);
 * </pre>
 */
public final class BytecodeInliner {

    private static final String INLINE_DESCRIPTOR = "Lorg/knownhosts/libfindchars/api/Inline;";

    private BytecodeInliner() {}

    /**
     * Inline all invokestatic calls to {@code @Inline}-annotated methods
     * of {@code targetClass} within the given class bytes.
     *
     * @param classBytes the class file bytes to transform
     * @param targetClass the class containing {@code @Inline} methods
     * @return transformed class bytes with inlined method bodies
     */
    public static byte[] inline(byte[] classBytes, Class<?> targetClass) {
        // Read target class bytes
        byte[] targetBytes = readClassBytes(targetClass);
        var cf = ClassFile.of();
        ClassModel targetModel = cf.parse(targetBytes);
        ClassDesc targetOwner = targetModel.thisClass().asSymbol();

        // Build maps of @Inline methods and all methods
        Map<String, MethodModel> inlineMethods = new HashMap<>();
        Map<String, MethodModel> allMethods = new HashMap<>();

        for (var method : targetModel.methods()) {
            String key = method.methodName().stringValue() + method.methodType().stringValue();
            allMethods.put(key, method);

            if (hasInlineAnnotation(method)) {
                inlineMethods.put(key, method);
            }
        }

        if (inlineMethods.isEmpty()) {
            return classBytes;
        }

        // Parse and transform the input class
        ClassModel inputModel = cf.parse(classBytes);
        var inliner = new MethodInliner(inlineMethods, allMethods, targetOwner);

        return cf.transformClass(inputModel, ClassTransform.transformingMethodBodies(
                (cb, elem) -> {
                    if (elem instanceof InvokeInstruction ii
                            && ii.opcode() == Opcode.INVOKESTATIC
                            && ii.owner().asSymbol().equals(targetOwner)) {
                        String key = ii.name().stringValue() + ii.type().stringValue();
                        var method = inlineMethods.get(key);
                        if (method != null) {
                            int maxDepth = getMaxDepth(method);
                            // Use slot 20 as base — well above the generated find() locals
                            inliner.inlineMethod(cb, method, 20, 0, maxDepth);
                            return;
                        }
                    }
                    cb.with(elem);
                }));
    }

    private static boolean hasInlineAnnotation(MethodModel method) {
        return method.findAttribute(Attributes.runtimeVisibleAnnotations())
                .map(ann -> ann.annotations().stream()
                        .anyMatch(a -> a.classSymbol().descriptorString().equals(INLINE_DESCRIPTOR)))
                .orElse(false);
    }

    private static int getMaxDepth(MethodModel method) {
        return method.findAttribute(Attributes.runtimeVisibleAnnotations())
                .flatMap(ann -> ann.annotations().stream()
                        .filter(a -> a.classSymbol().descriptorString().equals(INLINE_DESCRIPTOR))
                        .findFirst()
                        .flatMap(a -> a.elements().stream()
                                .filter(e -> e.name().stringValue().equals("maxDepth"))
                                .findFirst()
                                .map(e -> {
                                    if (e.value() instanceof java.lang.classfile.AnnotationValue.OfInt oi) {
                                        return oi.intValue();
                                    }
                                    return 4;
                                })))
                .orElse(4);
    }

    private static byte[] readClassBytes(Class<?> clazz) {
        String resourceName = "/" + clazz.getName().replace('.', '/') + ".class";
        try (var is = clazz.getResourceAsStream(resourceName)) {
            if (is == null) {
                throw new IllegalStateException("Cannot find class bytes for: " + clazz.getName());
            }
            return is.readAllBytes();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Failed to read class bytes for: " + clazz.getName(), e);
        }
    }
}
