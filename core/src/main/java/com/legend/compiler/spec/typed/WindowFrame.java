package com.legend.compiler.spec.typed;

/**
 * A CLASSIFIED window frame (remediation T3.1): {@code rows(a,b)} /
 * {@code _range(...)} decided ONCE, at the checker, where the literal
 * bounds are validated &mdash; not re-derived in the lowerer from the
 * sign of a numeric literal. Pure data; the SQL layer's
 * {@code SqlExpr.WindowCall.Frame} is a 1:1 structural map of this shape
 * (the typed layer must not depend on the SQL IR).
 */
public record WindowFrame(Kind kind, Bound from, Bound to) {

    public enum Kind { ROWS, RANGE }

    public sealed interface Bound {
        record UnboundedPreceding() implements Bound {
        }

        record Preceding(Number n) implements Bound {
        }

        record CurrentRow() implements Bound {
        }

        record Following(Number n) implements Bound {
        }

        record UnboundedFollowing() implements Bound {
        }

        /** {@code INTERVAL n UNIT PRECEDING} — the _RangeInterval frame side. */
        record IntervalPreceding(long n, String unit) implements Bound {
        }

        record IntervalFollowing(long n, String unit) implements Bound {
        }
    }
}
