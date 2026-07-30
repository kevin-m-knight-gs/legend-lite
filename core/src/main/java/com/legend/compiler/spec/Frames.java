package com.legend.compiler.spec;

import com.legend.builtin.Pure;
import com.legend.compiler.spec.typed.TypedCDecimal;
import com.legend.compiler.spec.typed.TypedCFloat;
import com.legend.compiler.spec.typed.TypedCInteger;
import com.legend.compiler.spec.typed.TypedEnumValue;
import com.legend.compiler.spec.typed.TypedNativeCall;
import com.legend.compiler.spec.typed.TypedSpec;
import com.legend.compiler.spec.typed.WindowFrame;
import com.legend.error.LegendCompileException;
import com.legend.error.ModelException;

import java.util.List;

/**
 * Window-frame classification (remediation T3.1): {@code rows(a,b)} /
 * {@code _range(...)} / {@code _rangeInterval(...)} decided ONCE, where the
 * checker sees the literal bounds — including the boundary validation the
 * engine also performs at check time (its {@code ExtendChecker}). The
 * lowerer maps the resulting {@link WindowFrame} to the SQL IR shape 1:1;
 * it no longer recovers PRECEDING/FOLLOWING from the sign of a literal.
 */
final class Frames {

    private Frames() {
    }

    static WindowFrame classify(TypedSpec spec) {
        if (!(spec instanceof TypedNativeCall call)) {
            throw new IllegalStateException("window frame expects rows()/range(), got "
                    + spec.getClass().getSimpleName());
        }
        // INTERVAL ranges (_range(n, DurationUnit, m, DurationUnit) and the
        // unbounded mixes): each bounded side pairs an Integer with its
        // DurationUnit — RANGE BETWEEN INTERVAL n UNIT PRECEDING/FOLLOWING.
        List<TypedSpec> as = call.args();
        boolean interval = as.stream().anyMatch(a ->
                a instanceof TypedEnumValue ev
                        && ev.enumFqn().equals("meta::pure::functions::date::DurationUnit"));
        if (interval) {
            WindowFrame.Bound from;
            WindowFrame.Bound to;
            int i = 0;
            if (isUnboundedCall(as.get(i))) {
                from = new WindowFrame.Bound.UnboundedPreceding();
                i += 1;
            } else {
                from = intervalBound(as.get(i), as.get(i + 1), true);
                i += 2;
            }
            if (i < as.size() && isUnboundedCall(as.get(i))) {
                to = new WindowFrame.Bound.UnboundedFollowing();
            } else {
                to = intervalBound(as.get(i), as.get(i + 1), false);
            }
            return new WindowFrame(WindowFrame.Kind.RANGE, from, to);
        }
        boolean rows = Pure.nativeNamed("rows", call.callee().signatureKey());
        // LITERAL bounds validate here: a start beyond the end (2 FOLLOWING
        // .. 1 FOLLOWING; 1 FOLLOWING .. 1 PRECEDING) is a COMPILE error,
        // never bad SQL (PCT: invalid window frame boundary).
        Number from = numericBound(call.args().get(0));
        Number to = numericBound(call.args().get(1));
        if (from != null && to != null && from.doubleValue() > to.doubleValue()) {
            // Real rows()/_range() assert text verbatim (PCT message parity).
            throw new ModelException(
                    LegendCompileException.Phase.TYPE,
                    "Invalid window frame boundary - lower bound of window frame"
                            + " cannot be greater than the upper bound!");
        }
        return new WindowFrame(
                rows ? WindowFrame.Kind.ROWS : WindowFrame.Kind.RANGE,
                bound(call.args().get(0), true), bound(call.args().get(1), false));
    }

    private static WindowFrame.Bound bound(TypedSpec arg, boolean fromSide) {
        // A negative literal arrives as unary minus AROUND the number — unwrap.
        if (arg instanceof TypedNativeCall neg
                && Pure.nativeNamed("minus", neg.callee().signatureKey())
                && neg.args().size() == 1 && numericBound(neg.args().get(0)) != null) {
            return new WindowFrame.Bound.Preceding(java.util.Objects
                    .requireNonNull(numericBound(neg.args().get(0))));
        }
        Number n = numericBound(arg);
        if (n != null) {
            double v = n.doubleValue();
            if (v < 0) {
                return new WindowFrame.Bound.Preceding(negate(n));
            }
            if (v > 0) {
                return new WindowFrame.Bound.Following(n);
            }
            return new WindowFrame.Bound.CurrentRow();
        }
        if (arg instanceof TypedNativeCall call
                && Pure.nativeNamed("unbounded", call.callee().signatureKey())) {
            return fromSide ? new WindowFrame.Bound.UnboundedPreceding()
                    : new WindowFrame.Bound.UnboundedFollowing();
        }
        // NO fallback: an unrecognized bound is a loud error, never UNBOUNDED.
        throw new IllegalStateException("window frame bound must be a numeric literal or"
                + " unbounded(), got " + arg.getClass().getSimpleName());
    }

    private static boolean isUnboundedCall(TypedSpec arg) {
        return arg instanceof TypedNativeCall c
                && Pure.nativeNamed("unbounded", c.callee().signatureKey());
    }

    /** One INTERVAL frame side: signed Integer + DurationUnit literal. */
    private static WindowFrame.Bound intervalBound(TypedSpec amount, TypedSpec unit,
            boolean fromSide) {
        Number n = numericBound(amount);
        if (n == null || !(unit instanceof TypedEnumValue ev)) {
            throw new IllegalStateException("interval frame bound needs a literal"
                    + " Integer and a DurationUnit literal");
        }
        long v = n.longValue();
        if (v < 0) {
            return new WindowFrame.Bound.IntervalPreceding(-v, ev.value());
        }
        if (v > 0) {
            return new WindowFrame.Bound.IntervalFollowing(v, ev.value());
        }
        return new WindowFrame.Bound.CurrentRow();
    }

    /** The numeric value of a literal frame bound, or null (RANGE takes decimals). */
    private static @com.legend.Nullable Number numericBound(TypedSpec arg) {
        // A negative literal arrives as unary minus AROUND the number.
        if (arg instanceof TypedNativeCall neg
                && Pure.nativeNamed("minus", neg.callee().signatureKey())
                && neg.args().size() == 1) {
            Number inner = numericBound(neg.args().get(0));
            return inner == null ? null : -inner.doubleValue();
        }
        return switch (arg) {
            case TypedCInteger c -> c.value().longValue();
            case TypedCFloat c -> c.value();
            case TypedCDecimal c -> c.value();
            default -> null;
        };
    }

    private static Number negate(Number n) {
        return switch (n) {
            case Long l -> -l;
            case Double d -> -d;
            case java.math.BigDecimal b -> b.negate();
            default -> -n.doubleValue();
        };
    }
}
