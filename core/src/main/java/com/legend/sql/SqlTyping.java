// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.sql;

import java.util.List;
import java.util.function.Function;

/**
 * TYPED-IR Slice 1 — the bottom-up TYPING JUDGMENT over the SQL IR:
 * what type does this expression ACTUALLY produce, computed from the
 * expression itself (leaves up), never echoed from a pure-type stamp.
 * The judgment is deliberately PARTIAL: {@code null} means "no rule
 * yet" — coverage grows rule by rule, and an untypeable expression is
 * COUNTED (the census), never guessed. A rule is added only when the
 * backend's behavior is certain; numeric-promotion results (PLUS over
 * mixed widths, aggregate reducers) stay null until their per-dialect
 * rules are written.
 *
 * <p>This file is the future label authority (the label-lie program:
 * instrument &rarr; census &rarr; flip). In Slice 1 it only measures —
 * {@code OutputCol} labels stay stamp-derived and the census compares
 * the two.
 */
public final class SqlTyping {

    private SqlTyping() {
    }

    /** BOTTOM — the judgment's verdict for the NULL literal (and
     * all-NULL compositions): the value that inhabits EVERY nullable
     * slot (type theory's &perp;; DuckDB's own internal SQLNULL type,
     * resolved by context). Deliberately NOT pure's {@code Nil} —
     * pure's bottom types empty COLLECTIONS and pure has no null
     * value; the frontend-emptiness/backend-NULL line is drawn by the
     * verdict system and this name keeps it drawn. A BOTTOM column
     * AGREES with any NULLABLE label and is a LIE against a
     * non-nullable one — the distinction "excluded" would discard. */
    public sealed interface Verdict {
        record Typed(SqlType type) implements Verdict {
        }

        record Bottom() implements Verdict {
        }

        record Unknown() implements Verdict {
        }
    }

    private static final Verdict BOTTOM = new Verdict.Bottom();
    private static final Verdict UNKNOWN = new Verdict.Unknown();

    /** The three-valued judgment: {@code Typed(t)} — the expression
     * produces exactly {@code t}; {@code Bottom} — the NULL value,
     * admissible in any nullable slot; {@code Unknown} — no rule yet
     * (the coverage census). */
    public static Verdict judge(SqlExpr e,
            Function<SqlExpr.Column, @com.legend.Nullable SqlType> scope) {
        if (e instanceof SqlExpr.NullLit) {
            return BOTTOM;
        }
        if (allNullCase(e)) {
            return BOTTOM;
        }
        SqlType t = of(e, scope);
        return t == null ? UNKNOWN : new Verdict.Typed(t);
    }

    /** A CASE whose every branch is the NULL literal is itself the
     * NULL value (the composition rule bottom already obeys inside
     * {@code uniform}, surfaced at the verdict level). */
    private static boolean allNullCase(SqlExpr e) {
        if (!(e instanceof SqlExpr.Case cs)) {
            return false;
        }
        for (SqlExpr.Case.When w : cs.whens()) {
            if (!(w.then() instanceof SqlExpr.NullLit)) {
                return false;
            }
        }
        return cs.otherwise() == null
                || cs.otherwise() instanceof SqlExpr.NullLit;
    }

    /** The computed type of {@code e}, or null (no rule / unresolvable
     * reference). {@code scope} resolves column references to their
     * source's declared output type — the LEAF AXIOMS (store DDL, TDS
     * literals, subselect outputs); an unresolvable column (correlated
     * outer reference, ambiguity) returns null there. */
    public static @com.legend.Nullable SqlType of(SqlExpr e,
            Function<SqlExpr.Column, @com.legend.Nullable SqlType> scope) {
        return switch (e) {
            case SqlExpr.StringLit s -> SqlType.Scalar.VARCHAR;
            case SqlExpr.IntLit i -> SqlType.Scalar.BIGINT;
            case SqlExpr.FloatLit f -> SqlType.Scalar.DOUBLE;
            case SqlExpr.DecimalLit d -> new SqlType.Decimal(
                    Math.max(d.value().precision(), 1),
                    Math.max(d.value().scale(), 0));
            case SqlExpr.BoolLit b -> SqlType.Scalar.BOOLEAN;
            case SqlExpr.DateLit d -> SqlType.Scalar.DATE;
            case SqlExpr.TimestampLit t -> SqlType.Scalar.TIMESTAMP;
            case SqlExpr.NullLit n -> null;   // typeless — admissible anywhere
            case SqlExpr.Cast c -> c.target();
            case SqlExpr.Group g -> of(g.inner(), scope);
            case SqlExpr.Column c -> scope.apply(c);
            case SqlExpr.StructLit sl -> {
                java.util.List<SqlType.Struct.Field> fs =
                        new java.util.ArrayList<>(sl.fields().size());
                for (SqlExpr.StructLit.Field f : sl.fields()) {
                    SqlType ft = of(f.value(), scope);
                    if (ft == null) {
                        yield null;
                    }
                    fs.add(new SqlType.Struct.Field(f.name(), ft));
                }
                yield new SqlType.Struct(fs);
            }
            case SqlExpr.ArrayLit al -> {
                if (al.elements().isEmpty()) {
                    yield null;
                }
                SqlType et = uniform(al.elements(), scope);
                yield et == null ? null : new SqlType.Array(et);
            }
            case SqlExpr.StructGet sg -> {
                SqlType st = of(sg.source(), scope);
                yield st instanceof SqlType.Struct s
                        ? s.fields().stream()
                                .filter(f -> f.name().equals(sg.field()))
                                .map(SqlType.Struct.Field::type)
                                .findFirst().orElse(null)
                        : null;
            }
            case SqlExpr.Case cs -> {
                java.util.List<SqlExpr> branches = new java.util.ArrayList<>();
                for (SqlExpr.Case.When w : cs.whens()) {
                    branches.add(w.then());
                }
                if (cs.otherwise() != null) {
                    branches.add(cs.otherwise());
                }
                yield uniform(branches, scope);
            }
            case SqlExpr.Membership m -> SqlType.Scalar.BOOLEAN;
            case SqlExpr.Exists ex -> SqlType.Scalar.BOOLEAN;
            case SqlExpr.ScalarSubquery sq ->
                    sq.subquery().outputs().size() == 1
                            ? sq.subquery().outputs().get(0).type()
                            : null;
            case SqlExpr.CheckedOne co -> {
                SqlType lt = of(co.list(), scope);
                yield lt instanceof SqlType.Array at ? at.element() : null;
            }
            case SqlExpr.CompactList cl -> of(cl.list(), scope);
            case SqlExpr.OrderedListAgg ola -> SqlType.Scalar.VARCHAR;
            case SqlExpr.JsonObject jo -> SqlType.Scalar.JSON;
            case SqlExpr.JsonArrayAgg ja -> SqlType.Scalar.JSON;
            case SqlExpr.RowOrder ro -> SqlType.Scalar.BIGINT;
            case SqlExpr.DeferredTdsString dts -> SqlType.Scalar.VARCHAR;
            case SqlExpr.Call c -> call(c, scope);
            // no rule yet: window calls, reducers (numeric promotion),
            // fold, plan params, splices, stars, format literals,
            // lambdas (not value-positioned)
            default -> null;
        };
    }

    private static @com.legend.Nullable SqlType call(SqlExpr.Call c,
            Function<SqlExpr.Column, @com.legend.Nullable SqlType> scope) {
        List<SqlExpr> a = c.args();
        return switch (c.fn()) {
            case AND, OR, NOT, EQUAL, NOT_EQUAL, LESS, LESS_EQUAL, GREATER,
                    GREATER_EQUAL, IS_NULL, IS_NOT_NULL, IN, STARTS_WITH,
                    ENDS_WITH, MATCHES, REGEXP_FULL_MATCH, LIST_EXISTS,
                    LIST_FOR_ALL, IS_DISTINCT, ALL_DISTINCT, NULL_SAFE_EQUAL,
                    NULL_SAFE_NOT_EQUAL, LIST_BOOL_AND, LIST_BOOL_OR ->
                    SqlType.Scalar.BOOLEAN;
            case CONCAT, CONCAT_JOIN, UPPER, LOWER, TRIM, LTRIM, RTRIM,
                    REPLACE, SUBSTRING, LEFT, RIGHT, LPAD, RPAD,
                    REVERSE_STRING, UC_FIRST, LC_FIRST, SPLIT_PART,
                    REGEXP_EXTRACT, REGEXP_REPLACE, CHR, ENCODE_BASE64,
                    DECODE_BASE64, MD5, SHA1, SHA256, GUID, DAYNAME,
                    MONTHNAME, STRFTIME, TYPEOF, BOOL_TO_TEXT, FORMAT,
                    JSON_TYPE, CURRENT_USER_FN -> SqlType.Scalar.VARCHAR;
            case LENGTH, STRPOS, ASCII_CODE, LEVENSHTEIN, LIST_LENGTH,
                    LIST_POSITION, EXTRACT, DATE_DIFF, EPOCH_SECONDS,
                    EPOCH_MS, PARSE_INT -> SqlType.Scalar.BIGINT;
            case SQRT, CBRT, EXP, LN, LOG10, POW, PI, SIN, COS, TAN, ASIN,
                    ACOS, ATAN, ATAN2, SINH, COSH, TANH, COT, RADIANS,
                    DEGREES, DIVIDE, JARO_WINKLER -> SqlType.Scalar.DOUBLE;
            case TODAY, MAKE_DATE -> SqlType.Scalar.DATE;
            case NOW, MAKE_TIMESTAMP, STRPTIME, FROM_EPOCH_SECONDS,
                    FROM_EPOCH_MS -> SqlType.Scalar.TIMESTAMP;
            case TO_VARIANT, JSON_MERGE_PATCH, VARIANT_GET ->
                    SqlType.Scalar.JSON;
            case VARIANT_ELEMENTS -> new SqlType.Array(SqlType.Scalar.JSON);
            case RANGE_FN -> new SqlType.Array(SqlType.Scalar.BIGINT);
            case COALESCE -> uniform(a, scope);
            case LIST_FILTER, LIST_SORT, LIST_SORT_DESC, LIST_TAIL,
                    LIST_INIT, LIST_SLICE, LIST_DISTINCT, LIST_REVERSE ->
                    a.isEmpty() ? null : arrayOf(of(a.get(0), scope));
            case LIST_CONCAT -> uniform(a, scope);
            case LIST_GET, UNNEST -> {
                SqlType lt = a.isEmpty() ? null : of(a.get(0), scope);
                yield lt instanceof SqlType.Array at ? at.element() : null;
            }
            case LIST_FLATTEN -> {
                SqlType lt = a.isEmpty() ? null : of(a.get(0), scope);
                yield lt instanceof SqlType.Array outer
                        && outer.element() instanceof SqlType.Array inner
                        ? inner : null;
            }
            case LIST_TRANSFORM -> {
                if (a.size() != 2 || !(a.get(1) instanceof SqlExpr.Lambda lam)
                        || lam.params().size() != 1) {
                    yield null;
                }
                SqlType lt = of(a.get(0), scope);
                if (!(lt instanceof SqlType.Array at)) {
                    yield null;
                }
                // the lambda parameter is bound to the ELEMENT type
                String p = lam.params().get(0);
                SqlType bt = of(lam.body(), col ->
                        col.table() == null && p.equals(col.name())
                                ? at.element() : scope.apply(col));
                yield bt == null ? null : new SqlType.Array(bt);
            }
            // numeric promotion (PLUS/MINUS/TIMES/aggregate reducers/…)
            // and everything else: no rule yet — counted, never guessed
            default -> null;
        };
    }

    private static @com.legend.Nullable SqlType arrayOf(
            @com.legend.Nullable SqlType t) {
        return t instanceof SqlType.Array ? t : null;
    }

    /** The single shared type of a branch/argument family: NullLit
     * members are ADMISSIBLE anywhere and skipped; all typeable members
     * must agree exactly; an untypeable member poisons to null. */
    private static @com.legend.Nullable SqlType uniform(List<SqlExpr> es,
            Function<SqlExpr.Column, @com.legend.Nullable SqlType> scope) {
        SqlType common = null;
        for (SqlExpr e : es) {
            if (e instanceof SqlExpr.NullLit) {
                continue;
            }
            SqlType t = of(e, scope);
            if (t == null) {
                return null;
            }
            if (common == null) {
                common = t;
            } else if (!common.equals(t)) {
                return null;
            }
        }
        return common;
    }
}
