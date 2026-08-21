package com.legend.compiler.spec;

import com.legend.compiler.element.type.ExprType;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The lexical environment for body type-checking: variable name &rarr; its
 * {@link ExprType}. Holds a function's parameters and (later) {@code let}
 * bindings and lambda parameters in scope. Immutable &mdash; {@link #with}
 * returns an extended copy, so nested scopes never mutate their parent.
 */
public final class Env {

    private final Map<String, ExprType> vars;
    private final java.util.Set<String> rowParams;

    private Env(Map<String, ExprType> vars, java.util.Set<String> rowParams) {
        this.vars = vars;
        this.rowParams = rowParams;
    }

    public static Env empty() {
        return new Env(Map.of(), java.util.Set.of());
    }

    /** Look up a variable's type, empty if it is not in scope. */
    public Optional<ExprType> lookup(String name) {
        return Optional.ofNullable(vars.get(name));
    }

    /** A new environment with {@code name} bound to {@code type} (shadowing any existing binding). */
    public Env with(String name, ExprType type) {
        Map<String, ExprType> next = new LinkedHashMap<>(vars);
        next.put(name, type);
        java.util.Set<String> rp = rowParams.contains(name)
                ? shadowOut(name) : rowParams;
        return new Env(next, rp);
    }

    /** A LAMBDA-PARAMETER binding — the PER-ELEMENT/PER-ROW frame
     * (stamp discipline: a relation-typed lambda param is a ROW; a
     * let-bound relation variable is a relation VALUE — column reads
     * off the two take different multiplicity frames). */
    public Env withRow(String name, ExprType type) {
        Map<String, ExprType> next = new LinkedHashMap<>(vars);
        next.put(name, type);
        java.util.Set<String> rp = new java.util.HashSet<>(rowParams);
        rp.add(name);
        return new Env(next, rp);
    }

    /** True when {@code name} is bound as a lambda parameter (the
     * per-element frame), false for lets and function parameters. */
    public boolean isRowParam(String name) {
        return rowParams.contains(name);
    }

    private java.util.Set<String> shadowOut(String name) {
        java.util.Set<String> rp = new java.util.HashSet<>(rowParams);
        rp.remove(name);
        return rp;
    }
}
