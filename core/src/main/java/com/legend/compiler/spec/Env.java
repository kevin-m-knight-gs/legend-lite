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
 *
 * <p>Row-vs-Relation: the environment carries NO binding-kind bit. Whether
 * a variable holds one row (a bare struct) or a whole relation (wrapped
 * {@code Relation<T>}) is read off its TYPE &mdash; the split's whole point.
 */
public final class Env {

    private final Map<String, ExprType> vars;
    private final Map<String, com.legend.protocol.spec.ValueSpecification> exprAliases;
    private final boolean lenientNew;

    private Env(Map<String, ExprType> vars,
            Map<String, com.legend.protocol.spec.ValueSpecification> exprAliases,
            boolean lenientNew) {
        this.vars = vars;
        this.exprAliases = exprAliases;
        this.lenientNew = lenientNew;
    }

    public static Env empty() {
        return new Env(Map.of(), Map.of(), false);
    }

    /** Look up a variable's type, empty if it is not in scope. */
    public Optional<ExprType> lookup(String name) {
        return Optional.ofNullable(vars.get(name));
    }

    /** A new environment with {@code name} bound to {@code type} (shadowing any
     * existing binding — including any syntactic alias under that name: a lambda
     * parameter shadowing a {@code let} must never substitute the outer
     * expression). */
    public Env with(String name, ExprType type) {
        Map<String, ExprType> next = new LinkedHashMap<>(vars);
        next.put(name, type);
        if (exprAliases.containsKey(name)) {
            Map<String, com.legend.protocol.spec.ValueSpecification> nextAliases =
                    new LinkedHashMap<>(exprAliases);
            nextAliases.remove(name);
            return new Env(next, nextAliases, lenientNew);
        }
        return new Env(next, exprAliases, lenientNew);
    }

    /** A {@code let} binding with its SYNTACTIC value alongside the type —
     * the checker-level substitution channel for the few checkers that need
     * SYNTAX, not just a type (match's branch collection through a let-bound
     * variable). Sound because pure's {@code let} is immutable and
     * referentially transparent; the Lowerer's {@code letBindings} applies
     * the same principle at lowering time. */
    public Env withLet(String name, ExprType type,
            com.legend.protocol.spec.ValueSpecification expr) {
        Map<String, ExprType> next = new LinkedHashMap<>(vars);
        next.put(name, type);
        Map<String, com.legend.protocol.spec.ValueSpecification> nextAliases =
                new LinkedHashMap<>(exprAliases);
        nextAliases.put(name, expr);
        return new Env(next, nextAliases, lenientNew);
    }

    /** The let-bound SYNTAX for {@code name}, empty when the name is not a
     * let binding in scope (or is shadowed). */
    public Optional<com.legend.protocol.spec.ValueSpecification> exprAlias(String name) {
        return Optional.ofNullable(exprAliases.get(name));
    }

    /** GENERATED-SOURCE escape for the ^new missing-required
     * validation — real pure's NewValidator carries the same escape
     * ({@code isNewValidationExceptionSource}): mapping-SYNTHESIZED
     * ctor bodies legitimately construct partial instances (a mapping
     * maps a SUBSET of properties). Set by SpecCompiler for
     * {@code $class$}-marked synth functions; user-written ^new is
     * always validated. */
    public Env withLenientNew() {
        return new Env(vars, exprAliases, true);
    }

    public boolean lenientNew() {
        return lenientNew;
    }
}
