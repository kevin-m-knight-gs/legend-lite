package com.legend.lowering;

import com.legend.compiler.element.type.Type;
import com.legend.compiler.spec.typed.TypedCBoolean;
import com.legend.compiler.spec.typed.TypedCDate;
import com.legend.compiler.spec.typed.TypedCInteger;
import com.legend.compiler.spec.typed.TypedCString;
import com.legend.compiler.spec.typed.TypedCast;
import com.legend.compiler.spec.typed.TypedNativeCall;
import com.legend.compiler.spec.typed.TypedSpec;
import com.legend.sql.SqlExpr;

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
        return lowered;
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
}
