package com.legend.protocol.spec;

/**
 * Float literal. Example Pure source: {@code 3.14} or {@code 1.5e-3}.
 *
 * <p>Stored as {@code double} to mirror the engine record. Precision-
 * sensitive consumers should use {@link CDecimal} (Pure suffix
 * {@code d}, e.g. {@code 3.14d}). C.1 does not yet emit
 * {@link CDecimal}; that variant lands in C.5.
 */
public record CFloat(double value, @com.legend.Nullable com.legend.protocol.SourceInfo pos)
        implements ValueSpecification {

    /** Position-free convenience constructor. */
    public CFloat(double value) {
        this(value, null);
    }

    /** Position is excluded from equality — see {@code ValueSpecEqualityTest}. */
    @Override
    public boolean equals(Object o) {
        return o instanceof CFloat other
                && Double.compare(value, other.value()) == 0;
    }

    @Override
    public int hashCode() {
        return Double.hashCode(value);
    }
}
