package com.legend.lowering;

import com.legend.compiler.element.type.PlatformTypes;
import com.legend.compiler.element.type.Type;
import com.legend.compiler.spec.typed.TypedCDate;
import com.legend.compiler.spec.typed.TypedCollection;
import com.legend.compiler.spec.typed.TypedSpec;
import com.legend.sql.SqlExpr;
import com.legend.sql.SqlFn;
import com.legend.sql.SqlType;
import com.legend.values.PureDateLiteral;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The MIXED-ELEMENT two-channel encoding — DATABASE-EXECUTED — extracted
 * from {@link Scalars} (CodeShapeGuardrail file split, stamp C1 slice).
 * Elements of DIFFERENT concrete kinds under the Number/Date LUB split
 * into an IDENTITY channel (pure print form, computed BY SQL) and a
 * COMPARABLE channel (CAST AS DOUBLE / strptime-padded TIMESTAMP);
 * selections order by the comparable, return the identity. TENET:
 * encodings chosen by STATIC type; every value computation runs in the
 * database (elements may be arbitrary expressions).
 */
final class MixedEncoding {

    private MixedEncoding() {
    }

    /** Null when not per-element encodable or not mixed. */
    record MixedElems(List<SqlExpr> ids, List<SqlExpr> vals) {

        SqlExpr idList() {
            return new SqlExpr.ArrayLit(ids);
        }

        SqlExpr valList() {
            return new SqlExpr.ArrayLit(vals);
        }

        /** {@code ids[list_position(vals, <winner>)]} — the selection recipe. */
        SqlExpr select(SqlExpr winner) {
            return SqlExpr.Call.of(SqlFn.LIST_GET, idList(),
                    SqlExpr.Call.of(SqlFn.LIST_POSITION, valList(), winner));
        }
    }

    /** Identity consumers of the NUMBER-LUB literal carrier rebuild
     * element identity from the TYPED elements (encodeMixed), never from
     * the carrier (the numeric UNWRAP is {@link Numerics#numList}).
     * Non-carrier shapes pass through untouched. */
    static @com.legend.Nullable MixedElems mixedElems(TypedSpec arg,
                                 SqlExpr lowered) {
        if (!(arg instanceof TypedCollection c)
                || c.elements().size() < 2
                || !(lowered instanceof SqlExpr.ArrayLit la)
                || la.elements().size() != c.elements().size()) {
            return null;
        }
        Type lub = c.info().type();
        if (lub != Type.Primitive.NUMBER && lub != Type.Primitive.DATE) {
            return null;   // uniform-kind collections keep their native carrier
        }
        return encodeAll(c.elements(), la.elements());
    }

    /** The n-ary form: max(2D, 1.23) — each ARG one element. */
    static @com.legend.Nullable MixedElems mixedArgs(List<TypedSpec> args,
                                List<SqlExpr> lowered) {
        Set<Type> kinds = new HashSet<>();
        for (var a : args) {
            kinds.add(a.info().type());
        }
        return kinds.size() > 1 ? encodeAll(args, lowered) : null;
    }

    private static @com.legend.Nullable MixedElems encodeAll(
            List<TypedSpec> elems,
            List<SqlExpr> lowered) {
        List<SqlExpr> ids = new ArrayList<>();
        List<SqlExpr> vals = new ArrayList<>();
        for (int i = 0; i < elems.size(); i++) {
            if (!encodeMixed(elems.get(i), lowered.get(i), ids, vals)) {
                return null;
            }
        }
        return new MixedElems(ids, vals);
    }

    /**
     * One element's (identity, comparable) SQL pair, dispatched on its
     * STATIC type. All value work happens in SQL.
     */
    private static boolean encodeMixed(TypedSpec e,
                                       SqlExpr x,
                                       List<SqlExpr> ids,
                                       List<SqlExpr> vals) {
        // a carrier-wrapped element unwraps: identity/comparable both
        // build from the RAW value (floatRepr over json cannot type)
        if (x instanceof SqlExpr.Call cw && cw.fn() == SqlFn.TO_VARIANT) {
            x = cw.args().get(0);
        }
        Type t = e.info().type();
        if (t == Type.Primitive.INTEGER) {
            ids.add(new SqlExpr.Cast(x, SqlType.Scalar.VARCHAR));
            vals.add(new SqlExpr.Cast(x, SqlType.Scalar.DOUBLE));
            return true;
        }
        if (t == Type.Primitive.FLOAT) {
            ids.add(Scalars.floatRepr(x));   // pure float print, in SQL
            vals.add(x);
            return true;
        }
        if (t == Type.Primitive.DECIMAL || t instanceof Type.PrecisionDecimal) {
            ids.add(SqlExpr.Call.of(SqlFn.CONCAT,
                    new SqlExpr.Cast(x, SqlType.Scalar.VARCHAR),
                    new SqlExpr.StringLit("D")));
            vals.add(new SqlExpr.Cast(x, SqlType.Scalar.DOUBLE));
            return true;
        }
        if (t == Type.Primitive.STRICT_DATE) {
            ids.add(SqlExpr.Call.of(SqlFn.STRFTIME, x,
                    new SqlExpr.FormatLit(com.legend.sql.DateFmt.DATE)));
            vals.add(new SqlExpr.Cast(x, SqlType.Scalar.TIMESTAMP));
            return true;
        }
        if (t == Type.Primitive.DATE_TIME) {
            ids.add(SqlExpr.Call.of(SqlFn.CONCAT,
                    SqlExpr.Call.of(SqlFn.STRFTIME, x,
                            new SqlExpr.FormatLit(dateTimeFormatOf(e))),
                    new SqlExpr.StringLit("+0000")));
            vals.add(x);
            return true;
        }
        if (t == Type.Primitive.DATE) {
            // PARTIAL dates travel as STRINGS (master's pinned carrier): the
            // string IS the print form; the comparable composes via
            // make_timestamp from split components (strptime %Y rejects
            // 5-digit years; make_timestamp reaches year 294246).
            SqlExpr cmp = partialComparable(e, x);
            if (cmp == null) {
                return false;
            }
            ids.add(x);
            vals.add(cmp);
            return true;
        }
        return false;
    }

    /** A date operand's chronological comparable (strptime-padded partials); non-dates pass through. */
    static SqlExpr dateComparableOrSelf(TypedSpec e,
                                        SqlExpr x) {
        Type t = e.info().type();
        if (t == Type.Primitive.DATE) {
            SqlExpr cmp = partialComparable(e, x);
            if (cmp != null) {
                return cmp;
            }
        }
        if (t == Type.Primitive.STRICT_DATE) {
            return new SqlExpr.Cast(x, SqlType.Scalar.TIMESTAMP);
        }
        return x;
    }

    /** DateTime print format — subsecond DIGIT COUNT is a static attribute of the literal. */
    private static List<com.legend.sql.DateFmt> dateTimeFormatOf(TypedSpec e) {
        if (e instanceof TypedCDate cd
                && cd.value() instanceof PureDateLiteral.DateWithSubsecond) {
            return com.legend.sql.DateFmt.ISO_MICRO;
        }
        return com.legend.sql.DateFmt.ISO_LOCAL;
    }

    /**
     * A PARTIAL date string's chronological comparable, composed IN SQL:
     * {@code make_timestamp(split_part(x,'-',i)...)} per the STATIC
     * precision; null when the precision is not a known partial form.
     */
    private static @com.legend.Nullable SqlExpr partialComparable(TypedSpec e,
                                             SqlExpr x) {
        PureDateLiteral.Precision prec = Scalars.datePrecision(e);
        if (prec.atLeast(PureDateLiteral.Precision.HOUR)) {
            return null;
        }
        SqlExpr one = new SqlExpr.IntLit(1);
        SqlExpr zero = new SqlExpr.IntLit(0);
        SqlExpr year = new SqlExpr.Cast(
                SqlExpr.Call.of(SqlFn.SPLIT_PART, x, new SqlExpr.StringLit("-"), one),
                SqlType.Scalar.BIGINT);
        SqlExpr month = prec.atLeast(PureDateLiteral.Precision.MONTH) ? new SqlExpr.Cast(
                SqlExpr.Call.of(SqlFn.SPLIT_PART, x, new SqlExpr.StringLit("-"),
                        new SqlExpr.IntLit(2)),
                SqlType.Scalar.BIGINT) : one;
        SqlExpr day = prec.atLeast(PureDateLiteral.Precision.DAY) ? new SqlExpr.Cast(
                SqlExpr.Call.of(SqlFn.SPLIT_PART, x, new SqlExpr.StringLit("-"),
                        new SqlExpr.IntLit(3)),
                SqlType.Scalar.BIGINT) : one;
        return SqlExpr.Call.of(SqlFn.MAKE_TIMESTAMP, year, month, day, zero, zero, zero);
    }

    /** An Any-LUB {@code if} with DIFFERING branch kinds rides the
     * VARIANT carrier (the mixed-list discipline: a raw CASE cannot even
     * type — 'TDSNull' vs INT32, the TDS-getter witness); NULL stays the
     * bare empty carrier. Same-kind or non-Any ifs emit raw branches. */
    static SqlExpr lubCase(Type lub, TypedSpec thenB,
            @com.legend.Nullable TypedSpec elseB, SqlExpr cond,
            SqlExpr thenS, SqlExpr elseS) {
        boolean mixed = lub instanceof Type.ClassType ifCt
                && PlatformTypes.isAny(ifCt)
                && elseB != null
                && !thenB.info().type().equals(elseB.info().type());
        return new SqlExpr.Case(
                List.of(new SqlExpr.Case.When(cond,
                        mixed && !(thenS instanceof SqlExpr.NullLit)
                                ? SqlExpr.Call.of(SqlFn.TO_VARIANT, thenS)
                                : thenS)),
                mixed && !(elseS instanceof SqlExpr.NullLit)
                        ? SqlExpr.Call.of(SqlFn.TO_VARIANT, elseS)
                        : elseS);
    }

    /**
     * A primitive needle against class-typed elements (or vice versa) can
     * never be a member — the kinds are disjoint in pure's type system.
     * Any/mixed stays undecided (falls through to the SQL comparison).
     */
    static boolean kindMismatch(Type needle, Type elems) {
        boolean np = needle instanceof Type.Primitive || needle instanceof Type.PrecisionDecimal;
        boolean ep = elems instanceof Type.Primitive || elems instanceof Type.PrecisionDecimal;
        boolean nc = Scalars.isClassish(needle) && !PlatformTypes.isAny(needle);
        boolean ec = Scalars.isClassish(elems) && !PlatformTypes.isAny(elems);
        return (np && ec) || (nc && ep);
    }
}
