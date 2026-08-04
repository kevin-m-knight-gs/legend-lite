package com.legend.protocol;

import java.util.Objects;

/**
 * A parameter declaration on a derived property or function — a parse product.
 *
 * <p>Formerly nested in {@code com.legend.model.ClassDefinition}; lifted to the protocol
 * layer because it is pure syntax (name, type expression, declared multiplicity) and the
 * parser's output types must not depend on the model.
 *
 * @param name         parameter name
 * @param type         parameter type
 * @param multiplicity declared multiplicity (concrete or parameter ref)
 */
public record ParameterDefinition(
        String name,
        TypeExpression type,
        Multiplicity multiplicity) {
    public ParameterDefinition {
        Objects.requireNonNull(name, "Parameter name cannot be null");
        Objects.requireNonNull(type, "Parameter type cannot be null");
        Objects.requireNonNull(multiplicity, "Parameter multiplicity cannot be null");
    }
}
