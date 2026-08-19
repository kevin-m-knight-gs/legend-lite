package com.legend.lowering;

import com.legend.compiler.element.type.PlatformTypes;
import com.legend.compiler.element.type.Type;
import com.legend.compiler.spec.typed.TypedCBoolean;
import com.legend.compiler.spec.typed.TypedCDate;
import com.legend.compiler.spec.typed.TypedCInteger;
import com.legend.compiler.spec.typed.TypedCString;
import com.legend.compiler.spec.typed.TypedCast;
import com.legend.compiler.spec.typed.TypedNativeCall;
import com.legend.compiler.spec.typed.TypedSpec;
import com.legend.sql.SqlExpr;
import com.legend.sql.SqlFn;

import java.util.ArrayList;
import java.util.List;

/**
 * The one primitive-cast policy (extracted from the Lowerer, shared by
 * the scalar cast arm and the reduce/value channels): a CONVERTING
 * primitive cast emits SQL; widening/same-type/non-primitive is the
 * assertion no-op.
 */
final class CastPolicy {

    private CastPolicy() {
    }

    static SqlExpr castByPolicy(SqlExpr e, Type src, Type target) {
        if (isSqlPrimitive(target) && isSqlPrimitive(src)
                && !isWidening(src, target)
                && !PureSql.type(src).equals(PureSql.type(target))) {
            return new SqlExpr.Cast(e, PureSql.type(target));
        }
        return e;
    }

    /** Whether {@code tgt} is {@code src}'s primitive-lattice supertype (cast-as-assertion). */
    static boolean isWidening(Type src, Type tgt) {
        if (tgt == Type.Primitive.NUMBER) {
            return src == Type.Primitive.INTEGER || src == Type.Primitive.FLOAT
                    || src == Type.Primitive.DECIMAL || src instanceof Type.PrecisionDecimal;
        }
        if (tgt == Type.Primitive.DATE) {
            return src == Type.Primitive.STRICT_DATE || src == Type.Primitive.DATE_TIME;
        }
        return false;
    }

    static boolean isSqlPrimitive(Type t) {
        return (t instanceof Type.Primitive p
                        && p != Type.Primitive.BYTE && p != Type.Primitive.LATEST_DATE
                        && p != Type.Primitive.STRICT_TIME)
                || t instanceof Type.PrecisionDecimal;
    }

    /** In a comparison against a LITERAL side, a WIRE cast unwraps when
     * the literal speaks the cast SOURCE's type — engine predicates
     * compare the RAW mapped expression (testInWithDynaFunction golden:
     * case-string IN string-list, bare ID = 4); a literal matching the
     * cast TARGET keeps the cast ($i.active == true passes cast). */
    static SqlExpr comparisonWireOperand(TypedSpec typed,
            SqlExpr lowered, TypedSpec other) {
        TypedSpec t = typed;
        while (t instanceof TypedNativeCall nc && !nc.args().isEmpty()
                && "meta::pure::functions::multiplicity::toOne"
                        .equals(nc.callee().qualifiedName())) {
            t = nc.args().get(0);
        }
        if (t instanceof TypedCast tc && tc.wire()
                && lowered instanceof SqlExpr.Cast sc
                && literalish(other)
                && other.info().type().equals(tc.source().info().type())
                && !other.info().type().equals(tc.target())) {
            return sc.value();
        }
        // pure equality is COLLECTION equality: [x] IS x — a ONE-element
        // collection literal meets a to-one SCALAR literal at the element
        // (a match-arm [1] against the expected 1; Phase 4 channel B
        // testMatch* family). Narrow to a literal scalar other side: a
        // non-literal to-one may itself ride the list wire (take(1)), and
        // list = list must stay untouched.
        if (lowered instanceof SqlExpr.ArrayLit al && al.elements().size() == 1
                && literalish(other)
                && !(other instanceof com.legend.compiler.spec.typed.TypedCollection)
                && other.info().multiplicity()
                        instanceof com.legend.compiler.element.type.Multiplicity.Bounded ob
                && ob.upper() != null && ob.upper() == 1) {
            return al.elements().get(0);
        }
        return lowered;
    }

    /** {@link #comparisonWireOperand} PLUS the EQUALITY-only dual: a
     * to-one SCALAR literal against a MANY-typed VALUE side wraps as its
     * singleton list — x[many] == 1 IS x == [1] (map_values(...) == 1;
     * Phase 4 channel B testValues/testKeys). EQUAL-rule exclusive: the
     * IN rule shares the base seam for its needle, where wrapping is
     * membership-breaking (testInPrimitive), and a PROPERTY-NAVIGATION
     * other side keeps the bare compare — a relational to-many column is
     * scalar-per-row wire, never a list
     * (testFilterOnSimpleTypePropertyEq). */
    static SqlExpr equalityWireOperand(TypedSpec typed,
            SqlExpr lowered, TypedSpec other) {
        SqlExpr base = comparisonWireOperand(typed, lowered, other);
        if (base == lowered
                && !(other instanceof com.legend.compiler.spec.typed.TypedPropertyAccess)
                && literalish(typed)
                && !(typed instanceof com.legend.compiler.spec.typed.TypedCollection)
                && typed.info().multiplicity()
                        instanceof com.legend.compiler.element.type.Multiplicity.Bounded tb
                && tb.upper() != null && tb.upper() == 1
                && !(other.info().multiplicity()
                        instanceof com.legend.compiler.element.type.Multiplicity.Bounded om
                        && om.upper() != null && om.upper() <= 1)) {
            return new SqlExpr.ArrayLit(List.of(lowered));
        }
        return base;
    }

    static boolean literalish(TypedSpec v) {
        return switch (v) {
            case TypedCString ignored -> true;
            case TypedCInteger ignored -> true;
            case TypedCBoolean ignored -> true;
            case TypedCDate ignored -> true;
            case com.legend.compiler.spec.typed.TypedCollection c ->
                    c.elements().stream().allMatch(CastPolicy::literalish);
            default -> false;
        };
    }

    static TypedSpec cellRootUnwrapWire(TypedSpec b) {
        if (b instanceof TypedCast tc && tc.wire()
                && tc.target() == Type.Primitive.STRING) {
            return cellRootUnwrapWire(tc.source());
        }
        if (b instanceof TypedNativeCall nc
                && "meta::pure::functions::multiplicity::toOne"
                        .equals(nc.callee().qualifiedName())
                && !nc.args().isEmpty()) {
            TypedSpec inner = cellRootUnwrapWire(nc.args().get(0));
            if (inner != nc.args().get(0)) {
                List<TypedSpec> na = new ArrayList<>(nc.args());
                na.set(0, inner);
                return nc.withChildren(na);
            }
        }
        return b;
    }

    /** A compile-time-EMPTY operand: the LITERAL empty collection, or a
     * [0..0]-typed NIL value (the []-born bottom — a value computation
     * over empties, never a navigation). A [0..0]-typed value of any
     * OTHER type deliberately does not count — inheritance corpus models
     * carry subtype navigations the checker types [0..0] that still read
     * real columns (gate-4 association/inheritance regression when the
     * bare multiplicity criterion joined). */
    static boolean staticallyEmpty(TypedSpec t) {
        if (t instanceof com.legend.compiler.spec.typed.TypedCollection c
                && c.elements().isEmpty()) {
            return true;
        }
        return PlatformTypes.isNil(t.info().type())
                && t.info().multiplicity()
                        instanceof com.legend.compiler.element.type
                                .Multiplicity.Bounded b
                && b.upper() != null && b.upper() == 0;
    }

    /** {@code isEmpty(x)} in SQL, wire-shape by TYPE: a many-typed value
     * rides the list wire (NULL list = empty), a to-one/[0..1] value is
     * scalar (NULL = empty), a [1..n]-typed value is statically
     * non-empty. */
    static SqlExpr emptinessOf(TypedSpec t, SqlExpr e) {
        var m = t.info().multiplicity();
        if (m instanceof com.legend.compiler.element.type.Multiplicity
                        .Bounded b
                && b.lower() >= 1) {
            return new SqlExpr.BoolLit(false);
        }
        boolean many = !(m instanceof com.legend.compiler.element.type
                        .Multiplicity.Bounded b2)
                || b2.upper() == null || b2.upper() > 1;
        if (many) {
            return SqlExpr.Call.of(SqlFn.EQUAL,
                    SqlExpr.Call.of(SqlFn.COALESCE,
                            SqlExpr.Call.of(SqlFn.LIST_LENGTH, e),
                            new SqlExpr.IntLit(0)),
                    new SqlExpr.IntLit(0));
        }
        return SqlExpr.Call.of(SqlFn.IS_NULL, e);
    }
}
