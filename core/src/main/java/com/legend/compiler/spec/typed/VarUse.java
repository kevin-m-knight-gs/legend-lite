package com.legend.compiler.spec.typed;

/**
 * THE variable-occurrence probe (remediation T3.1): does the subtree read
 * {@code $var}? Shadowing-aware &mdash; a lambda that re-binds the name
 * stops the walk, so occurrences of an inner binder are never mistaken
 * for reads of the outer one. Five per-file copies existed; only one
 * stopped at shadowing lambdas. This is the single implementation
 * (specialized walkers with extra exemptions, e.g. the sanctioned slot
 * read, stay separate but must keep the same shadowing rule).
 */
public final class VarUse {

    private VarUse() {
    }

    /** Whether any {@code $var} read occurs beneath {@code n}. */
    public static boolean reads(TypedSpec n, String var) {
        if (n instanceof TypedVariable v && v.name().equals(var)) {
            return true;
        }
        if (n instanceof TypedLambda l && l.parameters().contains(var)) {
            return false;   // re-bound: inner occurrences are not ours
        }
        for (TypedSpec c : n.children()) {
            if (reads(c, var)) {
                return true;
            }
        }
        return false;
    }
}
