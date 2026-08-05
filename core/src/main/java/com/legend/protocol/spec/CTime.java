package com.legend.protocol.spec;

import com.legend.values.PureTimeLiteral;
import java.util.Objects;

/**
 * Pure strict-time literal carrier &mdash; a {@link ValueSpecification}
 * wrapping a structured {@link PureTimeLiteral}. Source-form
 * {@code %10:30}, {@code %10:30:45}, {@code %10:30:45.123} all parse
 * to one of these.
 *
 * <p>Replaces the previous {@code CStrictTime(String value)}. The
 * structured value is validated at construction; downstream consumers
 * pattern-match on {@link PureTimeLiteral} variants instead of
 * re-parsing the string.
 */
public record CTime(PureTimeLiteral value, @com.legend.Nullable String written,
        @com.legend.Nullable com.legend.protocol.SourceInfo pos)
        implements ValueSpecification {
    public CTime {
        Objects.requireNonNull(value, "value");
    }

    /** Position-free convenience constructor. */
    public CTime(PureTimeLiteral value) {
        this(value, null, null);
    }

    /** Position is excluded from equality — see {@code ValueSpecEqualityTest}. */
    @Override
    public boolean equals(Object o) {
        return o instanceof CTime other && value.equals(other.value());
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }
}
