package com.legend.compiler.element;

import java.util.List;
import java.util.Objects;

/**
 * A compiled Pure {@code Enum} (Phase F) &mdash; the typed counterpart of
 * {@link com.legend.model.EnumDefinition}. Pure data.
 *
 * <p>Structurally identical to its parser source (an enum is just a name plus
 * an ordered set of value names); it exists as its own {@code Typed*} record to
 * preserve the one-element-kind = one-record symmetry and to be referenced as a
 * {@link com.legend.compiler.element.type.Type.EnumType} by FQN.
 *
 * @param qualifiedName fully qualified enum name
 * @param values        value names in declaration order; non-empty
 */
public record TypedEnum(String qualifiedName, List<String> values) implements TypedNominal {

    public TypedEnum {
        Objects.requireNonNull(qualifiedName, "qualifiedName");
        Objects.requireNonNull(values, "values");
        // An EMPTY enum is legal Legend — see EnumDefinition. The compiled
        // type mirrors the model: an enum with no values, whose extent is
        // empty and whose typed properties can therefore only be empty.
        values = List.copyOf(values);
    }
}
