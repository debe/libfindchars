package org.knownhosts.libfindchars.api;

import java.lang.annotation.*;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Inline {
    /** Max recursion depth for inlining private helpers called by this method. */
    int maxDepth() default 4;
}
