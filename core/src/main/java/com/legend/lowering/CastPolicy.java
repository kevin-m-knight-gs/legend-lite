package com.legend.lowering;

import com.legend.compiler.element.type.Type;
import com.legend.sql.SqlExpr;

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
}
