package org.knownhosts.libfindchars.compiler.inline;

import java.lang.classfile.*;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.util.Map;

/**
 * Replaces {@code getfield} loads of {@code @Inline int} fields with constant values.
 * After folding, loops with constant bounds become candidates for unrolling and
 * switch statements can resolve to specific cases.
 */
public final class ConstantFolder {

    private ConstantFolder() {}

    /**
     * Replace all {@code getfield} instructions for the given constant int fields
     * with their constant values. Handles the {@code aload_0 + getfield} pattern by
     * replacing getfield with {@code pop + iconst} (discards the objectref, pushes constant).
     */
    public static byte[] foldWithAloadRemoval(byte[] classBytes, Map<String, Integer> constants) {
        if (constants.isEmpty()) return classBytes;

        var cf = ClassFile.of();
        ClassModel model = cf.parse(classBytes);
        var classDesc = model.thisClass().asSymbol();

        return cf.transformClass(model, ClassTransform.transformingMethodBodies(
                (cb, elem) -> {
                    if (elem instanceof FieldInstruction fi
                            && fi.opcode() == Opcode.GETFIELD
                            && fi.owner().asSymbol().equals(classDesc)
                            && fi.typeSymbol().equals(ConstantDescs.CD_int)) {
                        String name = fi.name().stringValue();
                        Integer value = constants.get(name);
                        if (value != null) {
                            // getfield consumes objectref, pushes value
                            // Replace: pop objectref, push constant
                            cb.pop();
                            emitIconst(cb, value);
                            return;
                        }
                    }
                    cb.with(elem);
                }));
    }

    static void emitIconst(CodeBuilder code, int value) {
        switch (value) {
            case 0 -> code.iconst_0();
            case 1 -> code.iconst_1();
            case 2 -> code.iconst_2();
            case 3 -> code.iconst_3();
            case 4 -> code.iconst_4();
            case 5 -> code.iconst_5();
            default -> {
                if (value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE) {
                    code.bipush(value);
                } else if (value >= Short.MIN_VALUE && value <= Short.MAX_VALUE) {
                    code.sipush(value);
                } else {
                    code.ldc(value);
                }
            }
        }
    }
}
