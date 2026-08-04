package com.legend.protocol;

import com.legend.protocol.spec.ValueSpecification;

import java.util.List;
import java.util.Objects;

/**
 * A derived (computed) property declaration — a parse product. Body is parsed eagerly
 * by {@code ElementParser} into a sequence of {@link ValueSpecification} statements
 * (the body grammar matches a function body's braced block).
 *
 * <p>Formerly nested in {@code com.legend.model.ClassDefinition}; lifted to the protocol
 * layer because the parser's output types must not depend on the model.
 *
 * @param name        property name
 * @param parameters  parameter list (zero or more)
 * @param realization inline body or function-ref binding
 * @param type        return type
 * @param multiplicity declared multiplicity
 */
public record DerivedPropertyDefinition(
        String name,
        List<ParameterDefinition> parameters,
        Realization realization,
        TypeExpression type,
        Multiplicity multiplicity) {
    public DerivedPropertyDefinition {
        Objects.requireNonNull(name, "Derived property name cannot be null");
        Objects.requireNonNull(type, "Derived property type cannot be null");
        Objects.requireNonNull(multiplicity, "Derived property multiplicity cannot be null");
        Objects.requireNonNull(realization, "Derived property realization cannot be null");
        parameters = parameters == null ? List.of() : List.copyOf(parameters);
    }

    /** Convenience: the sugar (inline-expression) form. */
    public DerivedPropertyDefinition(String name, List<ParameterDefinition> parameters,
                                     List<ValueSpecification> expression,
                                     TypeExpression type, Multiplicity multiplicity) {
        this(name, parameters, new Realization.Inline(expression), type, multiplicity);
    }

    /**
     * The inline body (sugar form). Valid only when the realization is an
     * {@link Realization.Inline}; a Door-4 function-ref binding has no inline
     * body (its realizing function is the bound FQN).
     */
    public List<ValueSpecification> expression() {
        if (realization instanceof Realization.Inline inl) return inl.body();
        throw new IllegalStateException(
                "derived property '" + name + "' is a function-ref binding, not an inline body");
    }
}
