package com.legend.model.spec;

import java.util.Objects;

/**
 * String literal. Example Pure source: {@code 'hello world'}.
 *
 * <p>The {@code value} is the unquoted, unescaped source text &mdash; the
 * surrounding single quotes are stripped and backslash escapes
 * ({@code \\}, {@code \'}, {@code \n}, {@code \t}, {@code \r}) are
 * resolved by the parser so consumers see the logical string content.
 */
public record CString(String value, @com.legend.Nullable com.legend.model.SourceInfo pos)
        implements ValueSpecification {

    /** Position-free convenience constructor — keeps hand-built test expectations compiling. */
    public CString(String value) {
        this(value, null);
    }

    /**
     * <b>Position is excluded from equality on purpose.</b> These records are compared
     * structurally by the compiler and by 111 hand-built test assertions of the form
     * {@code assertEquals(new CString(...), spec)}; including a span would break every one and
     * would make two structurally identical expressions unequal for no semantic reason.
     * {@code ValueSpecEqualityTest} guards this — do not "fix" it.
     */
    @Override
    public boolean equals(Object o) {
        return o instanceof CString other && java.util.Objects.equals(value, other.value());
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hashCode(value);
    }

    public CString {
        Objects.requireNonNull(value, "value");
    }
}
