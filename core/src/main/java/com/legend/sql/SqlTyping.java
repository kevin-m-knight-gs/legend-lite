// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.sql;

import java.util.List;
import java.util.function.Function;

/**
 * TYPED-IR M1 (docs/TYPED_SQL_IR.md) — the ONE owner of per-node typing
 * rules. The Slice-1 bottom-up judgment's switch MOVED here as the rule
 * table the node constructors call: every {@link SqlExpr} stores its
 * {@link Verdict} at construction (computed from its children's stored
 * verdicts — the type is a property OF the tree), and this class owns
 * the per-function/per-shape rules those constructors invoke.
 *
 * <p>The judgment stays deliberately PARTIAL: {@code UNKNOWN} means "no
 * rule yet" — coverage grows rule by rule, and an untypeable expression
 * is COUNTED (the census), never guessed. A rule is added only when the
 * backend's behavior is certain; the aggregate numeric-promotion rules
 * ({@link #reducerType}) are written from an empirical probe of the
 * reference backend (DuckDB 1.5.0, 2026-08-24 — matrix recorded in
 * TYPED_SQL_IR.md M1 receipts); anything unprobed stays UNKNOWN.
 *
 * <p>{@link #judge} survives through M1/M2 as the SCOPE channel: it
 * re-binds the leaves the node channel does not know yet (Column types
 * from the caller's scope, LIST_TRANSFORM's lambda-parameter binding)
 * by REBUILDING the expression with typed leaves and reading the
 * rebuilt root's stored verdict — the rules run once, in the node
 * constructors, for both channels. The census compares the two
 * (judge-vs-node differential); M3 deletes the judge and flips its
 * consumers to {@code .type()} reads.
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

    public static final Verdict BOTTOM = new Verdict.Bottom();
    public static final Verdict UNKNOWN = new Verdict.Unknown();

    public static Verdict typed(SqlType t) {
        return new Verdict.Typed(t);
    }

    // shared scalar verdicts — constructed once, stored on every node of
    // the kind (constants, not a cache: there is no lifecycle)
    static final Verdict T_BOOLEAN = typed(SqlType.Scalar.BOOLEAN);
    static final Verdict T_BIGINT = typed(SqlType.Scalar.BIGINT);
    static final Verdict T_HUGEINT = typed(SqlType.Scalar.HUGEINT);
    static final Verdict T_DOUBLE = typed(SqlType.Scalar.DOUBLE);
    static final Verdict T_VARCHAR = typed(SqlType.Scalar.VARCHAR);
    static final Verdict T_DATE = typed(SqlType.Scalar.DATE);
    static final Verdict T_TIMESTAMP = typed(SqlType.Scalar.TIMESTAMP);
    static final Verdict T_JSON = typed(SqlType.Scalar.JSON);

    /** The three-valued judgment through the caller's LEAF scope:
     * {@code Typed(t)} — the expression produces exactly {@code t};
     * {@code Bottom} — the NULL value, admissible in any nullable slot;
     * {@code Unknown} — no rule / unresolvable reference. {@code scope}
     * resolves column references to their source's declared output type
     * — the LEAF AXIOMS (store DDL, TDS literals, subselect outputs);
     * an unresolvable column (correlated outer reference, ambiguity)
     * returns null there. */
    public static Verdict judge(SqlExpr e,
            Function<SqlExpr.Column, @com.legend.Nullable SqlType> scope) {
        return rebind(e, scope).type();
    }

    // M3 FLIP EXECUTED (2026-08-24): the two production consumers read
    // the tree's stored type directly. The slice-0 site differential
    // (judgeSite + counters + witnesses) DELETED with the flip — its
    // measurement (zero divergence, corpus + all five ChannelB lanes,
    // pinned) is recorded in TYPED_SQL_IR.md. The judge below remains
    // ONLY as the census differential's scope channel; it deletes with
    // the label flip (deletion-order note in the charter).

    /** The computed type of {@code e}, or null (no rule / unresolvable
     * reference / the NULL value) — the Typed-or-nothing projection of
     * {@link #judge} kept for consumers that only act on a concrete
     * type. */
    public static @com.legend.Nullable SqlType of(SqlExpr e,
            Function<SqlExpr.Column, @com.legend.Nullable SqlType> scope) {
        return judge(e, scope) instanceof Verdict.Typed t ? t.type() : null;
    }

    /** The judge's transitional mechanics (M1): rebuild the expression
     * with the ONLY knowledge the node channel lacks — scope-typed
     * {@code Column} leaves and LIST_TRANSFORM's parameter binding —
     * and let the node constructors recompute every composite verdict
     * bottom-up. One rule owner for both channels by construction;
     * an expression that binds nothing returns IDENTICALLY (mapChildren
     * is identity-preserving), so the judge adds exactly its leaf
     * knowledge and nothing else. Deleted at M3. */
    private static SqlExpr rebind(SqlExpr e,
            Function<SqlExpr.Column, @com.legend.Nullable SqlType> scope) {
        switch (e) {
            case SqlExpr.Column c -> {
                SqlType t = scope.apply(c);
                return t == null ? c
                        : new SqlExpr.Column(c.table(), c.name(), typed(t));
            }
            case SqlExpr.Call c when c.fn() == SqlFn.LIST_TRANSFORM
                    && c.args().size() == 2
                    && c.args().get(1) instanceof SqlExpr.Lambda lam
                    && lam.params().size() == 1 -> {
                SqlExpr src = rebind(c.args().get(0), scope);
                if (src.type() instanceof Verdict.Typed st
                        && st.type() instanceof SqlType.Array at) {
                    // the lambda parameter is bound to the ELEMENT type
                    String p = lam.params().get(0);
                    SqlExpr body = rebind(lam.body(), col ->
                            col.table() == null && p.equals(col.name())
                                    ? at.element() : scope.apply(col));
                    return new SqlExpr.Call(c.fn(), List.of(src,
                            new SqlExpr.Lambda(lam.params(), body)));
                }
                return src == c.args().get(0) ? c
                        : new SqlExpr.Call(c.fn(),
                                List.of(src, c.args().get(1)));
            }
            default -> {
                return e.mapChildren(ch -> rebind(ch, scope));
            }
        }
    }

    // ------------------------------------------------------------------
    // THE RULE TABLE — called by the node constructors (SqlExpr/SqlAgg).
    // Each rule is a function of the children's STORED verdicts; no rule
    // walks a finished tree.
    // ------------------------------------------------------------------

    /** {@link SqlExpr.Call} — the per-function rules (the Slice-1
     * switch, verbatim, lifted to verdicts). */
    static Verdict callType(SqlFn fn, List<SqlExpr> a) {
        return switch (fn) {
            case AND, OR, NOT, EQUAL, NOT_EQUAL, LESS, LESS_EQUAL, GREATER,
                    GREATER_EQUAL, IS_NULL, IS_NOT_NULL, IN, STARTS_WITH,
                    ENDS_WITH, MATCHES, REGEXP_FULL_MATCH, LIST_EXISTS,
                    LIST_FOR_ALL, IS_DISTINCT, ALL_DISTINCT, NULL_SAFE_EQUAL,
                    NULL_SAFE_NOT_EQUAL, LIST_BOOL_AND, LIST_BOOL_OR ->
                    T_BOOLEAN;
            case CONCAT, CONCAT_JOIN, UPPER, LOWER, TRIM, LTRIM, RTRIM,
                    REPLACE, SUBSTRING, LEFT, RIGHT, LPAD, RPAD,
                    REVERSE_STRING, UC_FIRST, LC_FIRST, SPLIT_PART,
                    REGEXP_EXTRACT, REGEXP_REPLACE, CHR, ENCODE_BASE64,
                    DECODE_BASE64, MD5, SHA1, SHA256, GUID, DAYNAME,
                    MONTHNAME, STRFTIME, TYPEOF, BOOL_TO_TEXT, FORMAT,
                    JSON_TYPE, CURRENT_USER_FN -> T_VARCHAR;
            case LENGTH, STRPOS, ASCII_CODE, LEVENSHTEIN, LIST_LENGTH,
                    LIST_POSITION, EXTRACT, DATE_DIFF, EPOCH_SECONDS,
                    EPOCH_MS, PARSE_INT -> T_BIGINT;
            case SQRT, CBRT, EXP, LN, LOG10, POW, PI, SIN, COS, TAN, ASIN,
                    ACOS, ATAN, ATAN2, SINH, COSH, TANH, COT, RADIANS,
                    DEGREES, DIVIDE, JARO_WINKLER -> T_DOUBLE;
            case TODAY, MAKE_DATE -> T_DATE;
            case NOW, MAKE_TIMESTAMP, STRPTIME, FROM_EPOCH_SECONDS,
                    FROM_EPOCH_MS -> T_TIMESTAMP;
            case TO_VARIANT, JSON_MERGE_PATCH, VARIANT_GET -> T_JSON;
            case VARIANT_ELEMENTS ->
                    typed(new SqlType.Array(SqlType.Scalar.JSON));
            case RANGE_FN -> typed(new SqlType.Array(SqlType.Scalar.BIGINT));
            case COALESCE -> uniform(a, BOTTOM);
            case LIST_FILTER, LIST_SORT, LIST_SORT_DESC, LIST_TAIL,
                    LIST_INIT, LIST_SLICE, LIST_DISTINCT, LIST_REVERSE ->
                    a.isEmpty() ? UNKNOWN : arrayPass(a.get(0).type());
            case LIST_CONCAT -> uniform(a, BOTTOM);
            case LIST_GET, UNNEST ->
                    a.isEmpty() ? UNKNOWN : element(a.get(0).type());
            case LIST_FLATTEN -> {
                if (a.isEmpty()) {
                    yield UNKNOWN;
                }
                yield a.get(0).type() instanceof Verdict.Typed t
                        && t.type() instanceof SqlType.Array outer
                        && outer.element() instanceof SqlType.Array inner
                        ? typed(inner) : UNKNOWN;
            }
            case LIST_TRANSFORM -> {
                if (a.size() != 2 || !(a.get(1) instanceof SqlExpr.Lambda lam)
                        || lam.params().size() != 1) {
                    yield UNKNOWN;
                }
                if (!(a.get(0).type() instanceof Verdict.Typed lt)
                        || !(lt.type() instanceof SqlType.Array)) {
                    yield UNKNOWN;
                }
                // the lambda parameter is bound to the ELEMENT type by
                // the knowledge OWNER — the judge's rebind (M1) or the
                // typed Lambda node (M2); this rule only reads the body
                yield lam.body().type() instanceof Verdict.Typed bt
                        ? typed(new SqlType.Array(bt.type())) : UNKNOWN;
            }
            // numeric promotion (PLUS/MINUS/TIMES/…) and everything
            // else: no rule yet — counted, never guessed
            default -> UNKNOWN;
        };
    }

    /** {@link SqlExpr.Case} — the branch family's shared type; a CASE
     * whose every branch is the NULL value is itself the NULL value. */
    static Verdict caseType(List<SqlExpr.Case.When> whens,
            @com.legend.Nullable SqlExpr otherwise) {
        java.util.List<SqlExpr> branches =
                new java.util.ArrayList<>(whens.size() + 1);
        for (SqlExpr.Case.When w : whens) {
            branches.add(w.then());
        }
        if (otherwise != null) {
            branches.add(otherwise);
        }
        return uniform(branches, BOTTOM);
    }

    /** {@link SqlExpr.ArrayLit} — the uniform element type, wrapped.
     * An all-NULL literal array is a definite ARRAY value with an
     * unknowable element type — UNKNOWN, not bottom. */
    static Verdict arrayLitType(List<SqlExpr> elements) {
        if (elements.isEmpty()) {
            return UNKNOWN;
        }
        return uniform(elements, UNKNOWN) instanceof Verdict.Typed t
                ? typed(new SqlType.Array(t.type())) : UNKNOWN;
    }

    /** {@link SqlExpr.StructLit} — every field's type, in declared
     * order; any untypeable or NULL field leaves the layout partial. */
    static Verdict structLitType(List<SqlExpr.StructLit.Field> fields) {
        java.util.List<SqlType.Struct.Field> fs =
                new java.util.ArrayList<>(fields.size());
        for (SqlExpr.StructLit.Field f : fields) {
            if (!(f.value().type() instanceof Verdict.Typed t)) {
                return UNKNOWN;
            }
            fs.add(new SqlType.Struct.Field(f.name(), t.type()));
        }
        return typed(new SqlType.Struct(fs));
    }

    /** {@link SqlExpr.StructGet} — the named field of a typed struct;
     * extraction from the NULL value is the NULL value. */
    static Verdict structGetType(SqlExpr source, String field) {
        Verdict sv = source.type();
        if (sv instanceof Verdict.Bottom) {
            return BOTTOM;
        }
        if (sv instanceof Verdict.Typed t
                && t.type() instanceof SqlType.Struct s) {
            for (SqlType.Struct.Field f : s.fields()) {
                if (f.name().equals(field)) {
                    return typed(f.type());
                }
            }
        }
        return UNKNOWN;
    }

    /** {@link SqlExpr.ScalarSubquery} — the single output's DECLARED
     * label (builder knowledge carried on the subquery, read here). */
    static Verdict scalarSubqueryType(SqlQuery sub) {
        return sub.outputs().size() == 1
                ? typed(sub.outputs().get(0).type()) : UNKNOWN;
    }

    /** {@link SqlExpr.CheckedOne} — the element of a definite list;
     * narrowing the NULL value flows the NULL value. */
    static Verdict checkedOneType(SqlExpr list) {
        Verdict lv = list.type();
        if (lv instanceof Verdict.Bottom) {
            return BOTTOM;
        }
        return lv instanceof Verdict.Typed t
                && t.type() instanceof SqlType.Array at
                ? typed(at.element()) : UNKNOWN;
    }

    /** {@link SqlExpr.WindowCall} — a windowed {@link SqlAgg.Reducer}
     * keeps its own promotion (probed: sum/avg/count/min OVER () match
     * the grouped results); ranking/value kinds have no rule yet. */
    static Verdict windowType(SqlAgg fn) {
        return fn instanceof SqlAgg.Reducer r ? r.type() : UNKNOWN;
    }

    /**
     * {@link SqlAgg.Reducer} — THE AGGREGATE PROMOTION RULES (the
     * Slice-1 header's deferred rule, M1 task 2). Written from an
     * empirical probe of the reference backend (DuckDB 1.5.0,
     * 2026-08-24; every arm below is a probed fact, matrix in
     * TYPED_SQL_IR.md M1 receipts):
     * <ul>
     * <li>COUNT counts rows — BIGINT, argument type irrelevant;</li>
     * <li>STRING_AGG concatenates — VARCHAR for every input;</li>
     * <li>BOOL_AND/BOOL_OR — BOOLEAN;</li>
     * <li>the moment family (STDDEV/VAR samp+pop, VARIANCE, CORR,
     *     COVAR) — DOUBLE for every numeric input;</li>
     * <li>SUM widens: integer family (and BOOLEAN) &rarr; HUGEINT
     *     (the wire census's adopt-pending fact, now typed at the
     *     source), DOUBLE &rarr; DOUBLE, Decimal(p,s) &rarr;
     *     Decimal(38,s);</li>
     * <li>AVG &rarr; DOUBLE over numerics (Decimal included);
     *     temporals average to TIMESTAMP;</li>
     * <li>MIN/MAX/ANY_VALUE/MODE/QUANTILE_DISC/ARG_MAX/ARG_MIN are
     *     element-preserving — identity (LITERAL carriers flow, the
     *     F10 3b rule);</li>
     * <li>MEDIAN/QUANTILE_CONT interpolate: integers &rarr; DOUBLE,
     *     Decimal stays, temporals &rarr; TIMESTAMP, and MEDIAN alone
     *     is identity on VARCHAR/BOOLEAN; interpolation over a LITERAL
     *     carrier would break the spelling contract — UNKNOWN;</li>
     * <li>LIST collects — Array(input).</li>
     * </ul>
     * Everything else (the Lowerer-internal markers, unprobed inputs)
     * stays UNKNOWN — certainty only, never a guess.
     */
    static Verdict reducerType(SqlAgg.Fn fn, Verdict arg0) {
        switch (fn) {
            case COUNT -> {
                return T_BIGINT;
            }
            case STRING_AGG -> {
                return T_VARCHAR;
            }
            case BOOL_AND, BOOL_OR -> {
                return T_BOOLEAN;
            }
            case STDDEV_SAMP, STDDEV_POP, VAR_SAMP, VAR_POP, STDDEV,
                    VARIANCE, CORR, COVAR_SAMP, COVAR_POP -> {
                return T_DOUBLE;
            }
            default -> {
            }
        }
        if (!(arg0 instanceof Verdict.Typed t0)) {
            return UNKNOWN;
        }
        SqlType t = t0.type();
        return switch (fn) {
            case SUM -> {
                if (integerFamily(t) || t == SqlType.Scalar.BOOLEAN) {
                    yield T_HUGEINT;
                }
                if (t == SqlType.Scalar.DOUBLE) {
                    yield T_DOUBLE;
                }
                yield t instanceof SqlType.Decimal d
                        ? typed(new SqlType.Decimal(38, d.scale())) : UNKNOWN;
            }
            case AVG -> {
                if (integerFamily(t) || t == SqlType.Scalar.DOUBLE
                        || t instanceof SqlType.Decimal) {
                    yield T_DOUBLE;
                }
                yield t == SqlType.Scalar.DATE
                        || t == SqlType.Scalar.TIMESTAMP
                        ? T_TIMESTAMP : UNKNOWN;
            }
            case MIN, MAX, ANY_VALUE, MODE, QUANTILE_DISC, ARG_MAX,
                    ARG_MIN -> typed(t);
            case MEDIAN, QUANTILE_CONT -> {
                if (integerFamily(t) || t == SqlType.Scalar.DOUBLE) {
                    yield T_DOUBLE;
                }
                if (t instanceof SqlType.Decimal) {
                    yield typed(t);
                }
                if (t == SqlType.Scalar.DATE
                        || t == SqlType.Scalar.TIMESTAMP) {
                    yield T_TIMESTAMP;
                }
                yield fn == SqlAgg.Fn.MEDIAN
                        && (t == SqlType.Scalar.VARCHAR
                                || t == SqlType.Scalar.BOOLEAN)
                        ? typed(t) : UNKNOWN;
            }
            case LIST -> typed(new SqlType.Array(t));
            default -> UNKNOWN;
        };
    }

    /** {@link SqlExpr.ReduceCollection} — the SAME aggregate promotion,
     * read through the collection carrier (probed: DuckDB's
     * {@code list_aggregate} matches the grouped aggregate's result
     * types element-wise). */
    static Verdict reduceCollectionType(SqlAgg.Fn fn, SqlExpr collection) {
        return collection.type() instanceof Verdict.Typed t
                && t.type() instanceof SqlType.Array at
                ? reducerType(fn, typed(at.element())) : UNKNOWN;
    }

    private static boolean integerFamily(SqlType t) {
        return t == SqlType.Scalar.BIGINT || t == SqlType.Scalar.INTEGER
                || t == SqlType.Scalar.HUGEINT;
    }

    /** An array-in/array-out passthrough (sort/filter/slice family);
     * transporting the NULL value flows the NULL value. */
    private static Verdict arrayPass(Verdict v) {
        if (v instanceof Verdict.Bottom) {
            return BOTTOM;
        }
        return v instanceof Verdict.Typed t
                && t.type() instanceof SqlType.Array ? v : UNKNOWN;
    }

    /** Element extraction (LIST_GET/UNNEST); extraction from the NULL
     * value is the NULL value. */
    private static Verdict element(Verdict v) {
        if (v instanceof Verdict.Bottom) {
            return BOTTOM;
        }
        return v instanceof Verdict.Typed t
                && t.type() instanceof SqlType.Array at
                ? typed(at.element()) : UNKNOWN;
    }

    /** The single shared type of a branch/argument family: BOTTOM
     * members are ADMISSIBLE anywhere and skipped; all typeable members
     * must agree exactly; an UNKNOWN member poisons. A family that is
     * entirely the NULL value resolves to {@code allBottom} — the
     * caller names what that means for its shape (the NULL value for
     * CASE/COALESCE, UNKNOWN for a literal array's element type). */
    private static Verdict uniform(List<SqlExpr> es, Verdict allBottom) {
        SqlType common = null;
        boolean saw = false;
        for (SqlExpr e : es) {
            Verdict v = e.type();
            if (v instanceof Verdict.Bottom) {
                saw = true;
                continue;
            }
            if (!(v instanceof Verdict.Typed t)) {
                return UNKNOWN;
            }
            saw = true;
            if (common == null) {
                common = t.type();
            } else if (!common.equals(t.type())) {
                return UNKNOWN;
            }
        }
        if (common != null) {
            return typed(common);
        }
        return saw ? allBottom : UNKNOWN;
    }
}
