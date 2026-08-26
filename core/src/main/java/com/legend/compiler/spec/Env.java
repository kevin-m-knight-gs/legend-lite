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
    private final boolean lenientNew;

    private Env(Map<String, ExprType> vars, boolean lenientNew) {
        this.vars = vars;
        this.lenientNew = lenientNew;
    }

    public static Env empty() {
        return new Env(Map.of(), false);
    }

    /** Look up a variable's type, empty if it is not in scope. */
    public Optional<ExprType> lookup(String name) {
        return Optional.ofNullable(vars.get(name));
    }

    /** A new environment with {@code name} bound to {@code type} (shadowing any existing binding). */
    public Env with(String name, ExprType type) {
        Map<String, ExprType> next = new LinkedHashMap<>(vars);
        next.put(name, type);
        return new Env(next, lenientNew);
    }

    /** GENERATED-SOURCE escape for the ^new missing-required
     * validation — real pure's NewValidator carries the same escape
     * ({@code isNewValidationExceptionSource}): mapping-SYNTHESIZED
     * ctor bodies legitimately construct partial instances (a mapping
     * maps a SUBSET of properties). Set by SpecCompiler for
     * {@code $class$}-marked synth functions; user-written ^new is
     * always validated. */
    public Env withLenientNew() {
        return new Env(vars, true);
    }

    public boolean lenientNew() {
        return lenientNew;
    }
}
