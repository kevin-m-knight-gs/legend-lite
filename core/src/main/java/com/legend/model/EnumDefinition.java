package com.legend.model;

import java.util.List;
import java.util.Objects;

/**
 * A parsed Pure {@code Enum} declaration.
 *
 * <p>Pure syntax:
 * <pre>
 *   Enum package::EnumName { VALUE1, VALUE2, VALUE3 }
 * </pre>
 *
 * <p>Mirrors engine's {@code com.gs.legend.model.def.EnumDefinition}.
 * Engine's {@code simpleName()} / {@code packagePath()} default methods,
 * {@code hasValue(String)} convenience, and varargs factories are
 * deliberately omitted &mdash; callers can use {@code values().contains(v)}
 * inline. See {@link PackageableElement} for the rationale on
 * {@code simpleName} / {@code packagePath}.
 *
 * @param qualifiedName fully qualified enum name (e.g. {@code "model::Status"})
 * @param values        the enum value names; must be non-empty
 * @throws IllegalArgumentException if {@code values} is empty
 */
public record EnumDefinition(String qualifiedName, List<String> values) implements PackageableElement {

    public EnumDefinition {
        Objects.requireNonNull(qualifiedName, "Qualified name cannot be null");
        Objects.requireNonNull(values, "Values cannot be null");
        // An EMPTY enum is legal Legend: the whole value list is optional
        // in engine's rule — `BRACE_OPEN (enumValue (COMMA enumValue)*)?
        // BRACE_CLOSE` (DomainParserGrammar.g4:118-121) — and engine's own
        // corpus writes one (dataSpace-emptyEnum.pure, whose class then
        // types a property with it at [0..1], so the property can only ever
        // be empty). A type with an empty extent is useless, not malformed.
        values = List.copyOf(values);
    }
}
