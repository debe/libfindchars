package org.knownhosts.libfindchars.api;

import java.lang.annotation.*;

/**
 * Marks a method or field for zero-overhead specialization at bytecode level.
 * <p>
 * Behavior depends on the target:
 * <ul>
 *   <li>{@code static} method — inline body at {@code invokestatic} call sites</li>
 *   <li>{@code private} method — inline body at {@code invokespecial}/{@code invokevirtual this} call sites</li>
 *   <li>{@code int} field — fold to compile-time constant (replace {@code getfield} with {@code iconst}/{@code bipush})</li>
 * </ul>
 */
@Target({ElementType.METHOD, ElementType.FIELD})
@Retention(RetentionPolicy.RUNTIME)
public @interface Inline {
    /** Max recursion depth for inlining private helpers called by this method. */
    int maxDepth() default 4;
}
