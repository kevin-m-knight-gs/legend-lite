// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.sql;

import java.util.List;

/**
 * The ONE owner of SQL typing knowledge (docs/TYPED_SQL_IR.md): the
 * per-node typing RULES the {@link SqlExpr} constructors call (the type
 * is a property OF the tree, computed once at construction), the
 * ADMISSIBILITY relation (the registered label-carrier pairs), and the
 * label RECONCILIATION {@link SqlSelect}'s constructor applies. The
 * transitional judge (consumption-side re-derivation, then a
 * leaf-binding rebuilder) is DELETED — its parity with the stored
 * types was pinned at zero divergence on every lane first.
 *
 * <p>Typing stays deliberately PARTIAL: {@code UNKNOWN} means "no rule
 * yet" — coverage grows rule by rule, and an untypeable expression is
 * COUNTED (the census untyped ceiling), never guessed. A rule is added
 * only when the backend's behavior is certain; the aggregate
 * numeric-promotion rules ({@link #reducerType}) come from an
 * empirical probe of the reference backend (DuckDB 1.5.0, 2026-08-24 —
 * matrix in the charter's M1 receipts).
 */
public final class SqlTyping {

    private SqlTyping() {
    }

    public static final TypeFact BOTTOM = new TypeFact.Bottom();
    public static final TypeFact UNKNOWN = new TypeFact.Unknown();

    public static TypeFact typed(SqlType t) {
        return new TypeFact.Typed(t);
    }

    // shared scalar verdicts — constructed once, stored on every node of
    // the kind (constants, not a cache: there is no lifecycle)
    static final TypeFact T_BOOLEAN = typed(SqlType.Scalar.BOOLEAN);
    static final TypeFact T_INTEGER = typed(SqlType.Scalar.INTEGER);
    static final TypeFact T_BIGINT = typed(SqlType.Scalar.BIGINT);
    static final TypeFact T_HUGEINT = typed(SqlType.Scalar.HUGEINT);
    static final TypeFact T_DOUBLE = typed(SqlType.Scalar.DOUBLE);
    static final TypeFact T_VARCHAR = typed(SqlType.Scalar.VARCHAR);
    static final TypeFact T_DATE = typed(SqlType.Scalar.DATE);
    static final TypeFact T_TIMESTAMP = typed(SqlType.Scalar.TIMESTAMP);
    static final TypeFact T_JSON = typed(SqlType.Scalar.JSON);

    // ------------------------------------------------------------------
    // THE LABEL FLIP (TYPED_SQL_IR.md §3/§6, executed 2026-08-24): an
    // OutputCol label is the PURE-CONTRACT erasure; the projection's
    // stored type is the WIRE. SqlSelect's canonical constructor
    // reconciles the two: equal or REGISTERED-admissible keeps the
    // contract; anything else was a label lie and the label adopts the
    // wire. The admissibility relation MOVED here from the census (the
    // flip encodes it; the census reads the same relation).
    // ------------------------------------------------------------------

    /** Label reconciliation — called by {@link SqlSelect}'s canonical
     * constructor (the compact-constructor idiom: the select's labels
     * are a property of the select, computed once). Equal or ADMITTED
     * keeps the pure-contract erasure; a label lie ADOPTS the wire.
     * (A third CONFORM-by-emitted-cast verdict was tried at this seam
     * and REVERTED by the referee — a type-pair cannot distinguish
     * the concrete-Float conversion from the abstract-Number identity
     * carrier; conformance by emission lives at the STAMP-GUARDED
     * mapping-read seam in the lowering — T4 leg 1.) Star frames
     * carry no per-column claim. */
    static @com.legend.Nullable List<OutputCol> reconcileLabels(
            List<SqlSelect.Projection> projections,
            @com.legend.Nullable List<OutputCol> outputs) {
        if (outputs == null || projections.size() != outputs.size()) {
            return outputs;
        }
        List<OutputCol> os = null;
        for (int i = 0; i < outputs.size(); i++) {
            if (!(projections.get(i).expr().type()
                    instanceof TypeFact.Typed t)) {
                continue;   // unknown/bottom wires keep the contract
            }
            OutputCol oc = outputs.get(i);
            SqlType computed = t.type();
            if (computed.equals(oc.type())
                    || admissible(oc.type(), computed)) {
                continue;
            }
            if (os == null) {
                os = new java.util.ArrayList<>(outputs);
            }
            os.set(i, new OutputCol(oc.name(), computed, oc.nullable()));
        }
        return os == null ? outputs : List.copyOf(os);
    }

    /** THE ADMISSIBILITY RELATION (T3 user-audited 2026-08-23; MOVED
     * from the census and EXTENDED by the 2026-08-24 flip adjudication
     * — witnesses per row in TYPED_SQL_IR.md): the registered
     * (declared, computed) carrier pairs — each a DELIBERATE
     * representation choice. Everything not here that differs is a
     * label lie, and the reconciliation adopts the wire.
     *
     * <p>HONESTY NOTE (from the T3 audit): these are TYPE-PAIR rules,
     * some COARSER than their justifying conventions; T4 conditions
     * them on the pure STAMP or retires them by emission. The reverse
     * temporal direction (DATE label &larr; TIMESTAMP wire) stays
     * DELIBERATELY absent — a bug, never a carrier. An
     * INTEGER&larr;BIGINT narrowing row was REMOVED 2026-08-23:
     * label-narrowing is only value-safe, which a type rule cannot
     * see. */
    public static boolean admissible(SqlType declared, SqlType computed) {
        // partial-precision temporal carriage (D-arc): SQL temporals
        // cannot hold pure's partial precisions, so temporal slots may
        // carry the precision-faithful VARCHAR wire
        if ((declared == SqlType.Scalar.TIMESTAMP
                || declared == SqlType.Scalar.DATE)
                && computed == SqlType.Scalar.VARCHAR) {
            return true;
        }
        // SUBSUMPTION at the abstract-Date slot (F5.4): the TIMESTAMP
        // label is where abstract Date erases; a StrictDate value's
        // DATE wire is a subtype in a supertype slot
        if (declared == SqlType.Scalar.TIMESTAMP
                && computed == SqlType.Scalar.DATE) {
            return true;
        }
        // the NUMBER-slot identity carrier: pure literal spellings
        // (1 / 7.345 / 2D) keep every fine kind's identity in text;
        // DOUBLE is where the abstract-Number stamp erases
        if (declared == SqlType.Scalar.DOUBLE
                && computed == SqlType.Scalar.VARCHAR) {
            return true;
        }
        // Float-erasure numeric carriage, NARROWED at T4 attempt 2
        // (charter §4bR Slice A): the Decimal limb is DELETED — those
        // 48 rows now conform BY EMISSION at the MappingNormalizer
        // pairing seam (concrete Float over a DECIMAL column casts in
        // SQL; measured zero traffic before the delete). The
        // INTEGER-family limb STAYS with a NEW referee receipt (this
        // slice's first sweep): Float-declared over an INT column is
        // IDENTITY — the validation-showcase golden prints the raw
        // 'Quantity not in range: 1000000' (toString computed IN SQL;
        // consistent with the engine's identity mechanism, §4bY).
        // These rows carry the same two-population split as the
        // VARCHAR arm below (the deletion experiment left 20x
        // DOUBLE<>BIGINT wire reds) and retire with the same
        // declaration-vs-fixture skew census, not by blanket adoption.
        if (declared == SqlType.Scalar.DOUBLE
                && (computed == SqlType.Scalar.BIGINT
                        || computed == SqlType.Scalar.INTEGER)) {
            return true;
        }
        // String-slot rows — KEPT, mechanism CORRECTED same day
        // (charter §4bY, engine-code receipts): the engine NEVER
        // converts here — its TDS transform is identity unless an enum
        // transformer exists (legend-pure functions.pure:218) and its
        // fetch is ResultSet-metadata-keyed (ResultSetValueHandlers:
        // INTEGER->LONG, VARCHAR->STRING). Both goldens are RAW-WIRE
        // prints; the apparent contradiction was FIXTURE SKEW: the
        // in.pure setup executes Create Table InteractionTable(id
        // VARCHAR(200)...) while the ###Relational store declares
        // ID INT (relationalSetUp.pure:1397) — '4' is the identity
        // read of a VARCHAR wire, and tree.pure's raw 11 is the
        // identity read of a genuine INT wire. This arm therefore
        // excuses TWO populations a type-pair cannot split: (a) skew
        // rows — the computed stamp (store-declaration-derived) lies
        // about the actual fixture; label truthful; (b) genuine-INT
        // rows — the label lies and adoption is correct (tree.pure
        // asserts raw; the deletion experiment CURED these,
        // int-or-null 83->53, while breaking (a): 42x BIGINT<>VARCHAR
        // wire reds). Retirement = the declaration-vs-fixture skew
        // census (the setup DDL runs through our own platform — the
        // split is statically knowable), then per-population: skew
        // rows to a named registry, genuine rows adopt and this arm
        // deletes.
        if (declared == SqlType.Scalar.VARCHAR
                && computed == SqlType.Scalar.BIGINT) {
            return true;
        }
        // serialize-as-text (the m2m/graphFetch egress): DuckDB serves
        // JSON as its text; the conform-by-emission cast is a later,
        // golden-text-gated slice
        if (declared == SqlType.Scalar.VARCHAR
                && computed == SqlType.Scalar.JSON) {
            return true;
        }
        // (M4 retirement attempt: the FLIP-ADJUDICATION collection-
        // carrier row — computed Array(a) admissible under element
        // label a — DELETED to measure; the charter lists it as
        // retiring with the landing.)
        // (The pure-Decimal erasure rows now ADOPT the wire's own
        // precision — strictly more truthful, decode-identical.)
        // Decimal WIDENING is lossless: a narrower computed decimal
        // fits any wider label at the same scale
        return declared instanceof SqlType.Decimal d
                && computed instanceof SqlType.Decimal c2
                && d.scale() == c2.scale()
                && d.precision() >= c2.precision();
    }

    // ------------------------------------------------------------------
    // THE RULE TABLE — called by the node constructors (SqlExpr/SqlAgg).
    // Each rule is a function of the children's STORED verdicts; no rule
    // walks a finished tree.
    // ------------------------------------------------------------------

    /** {@link SqlExpr.Call} — the per-function rules (the Slice-1
     * switch, verbatim, lifted to verdicts). */
    static TypeFact callType(SqlFn fn, List<SqlExpr> a) {
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
                yield a.get(0).type() instanceof TypeFact.Typed t
                        && t.type() instanceof SqlType.Array outer
                        && outer.element() instanceof SqlType.Array inner
                        ? typed(inner) : UNKNOWN;
            }
            case LIST_TRANSFORM -> {
                if (a.size() != 2 || !(a.get(1) instanceof SqlExpr.Lambda lam)
                        || lam.params().size() != 1) {
                    yield UNKNOWN;
                }
                if (!(a.get(0).type() instanceof TypeFact.Typed lt)
                        || !(lt.type() instanceof SqlType.Array)) {
                    yield UNKNOWN;
                }
                // the lambda parameter is bound to the ELEMENT type by
                // the knowledge OWNER — the judge's rebind (M1) or the
                // typed Lambda node (M2); this rule only reads the body
                yield lam.body().type() instanceof TypeFact.Typed bt
                        ? typed(new SqlType.Array(bt.type())) : UNKNOWN;
            }
            // ARITHMETIC PROMOTION (DuckDB 1.5.0 probed matrix,
            // 2026-08-24 — receipts in TYPED_SQL_IR.md): any DOUBLE
            // operand wins; an all-integer family promotes to its
            // widest member; DECIMAL operands follow version-specific
            // precision formulas and stay deliberately UNKNOWN; the
            // NULL value propagates (arithmetic is strict). PLUS/MINUS
            // also carry the probed date arms (DATE ± int -> DATE,
            // DATE - DATE -> BIGINT).
            case PLUS, MINUS -> {
                if (a.size() == 2) {
                    TypeFact l = a.get(0).type();
                    TypeFact r = a.get(1).type();
                    if (l instanceof TypeFact.Typed lt
                            && r instanceof TypeFact.Typed rt) {
                        boolean lDate = lt.type() == SqlType.Scalar.DATE;
                        boolean rDate = rt.type() == SqlType.Scalar.DATE;
                        if (lDate && rDate) {
                            yield fn == SqlFn.MINUS ? T_BIGINT : UNKNOWN;
                        }
                        if (lDate && integerKind(rt.type())
                                || rDate && fn == SqlFn.PLUS
                                        && integerKind(lt.type())) {
                            yield T_DATE;
                        }
                    }
                }
                yield numericPromotion(a);
            }
            case TIMES, MOD, REM -> numericPromotion(a);
            case NEGATE, ABS -> {
                if (a.isEmpty()) {
                    yield UNKNOWN;
                }
                TypeFact f = a.get(0).type();
                if (f instanceof TypeFact.Bottom) {
                    yield BOTTOM;
                }
                yield f instanceof TypeFact.Typed t
                        && (integerKind(t.type())
                                || t.type() == SqlType.Scalar.DOUBLE
                                || t.type() instanceof SqlType.Decimal)
                        ? f : UNKNOWN;   // sign flip keeps the domain
            }
            case INT_DIVIDE -> {
                TypeFact p2 = numericPromotion(a);
                if (p2 instanceof TypeFact.Typed t
                        || p2 instanceof TypeFact.Bottom) {
                    yield p2;   // int//int keeps width (probed)
                }
                // any DOUBLE/DECIMAL operand: probed DOUBLE — but that
                // falls out of numericPromotion for DOUBLE; a decimal
                // operand stays UNKNOWN (unprobed corners)
                yield UNKNOWN;
            }
            case ROUND -> {
                if (a.isEmpty()) {
                    yield UNKNOWN;
                }
                TypeFact f = a.get(0).type();
                if (f instanceof TypeFact.Bottom) {
                    yield BOTTOM;
                }
                yield f instanceof TypeFact.Typed t
                        ? integerKind(t.type()) ? f
                                : t.type() == SqlType.Scalar.DOUBLE
                                        ? T_DOUBLE : UNKNOWN
                        : UNKNOWN;
            }
            case CEILING, FLOOR -> {
                if (a.isEmpty()) {
                    yield UNKNOWN;
                }
                TypeFact f = a.get(0).type();
                if (f instanceof TypeFact.Bottom) {
                    yield BOTTOM;
                }
                if (f instanceof TypeFact.Typed t) {
                    if (integerKind(t.type())
                            || t.type() == SqlType.Scalar.DOUBLE) {
                        yield T_DOUBLE;
                    }
                    if (t.type() instanceof SqlType.Decimal d) {
                        yield typed(new SqlType.Decimal(d.precision(), 0));
                    }
                }
                yield UNKNOWN;
            }
            // everything else: no rule yet — counted, never guessed
            default -> UNKNOWN;
        };
    }

    /** {@link SqlExpr.Case} — the branch family's shared type; a CASE
     * whose every branch is the NULL value is itself the NULL value. */
    static TypeFact caseType(List<SqlExpr.Case.When> whens,
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
    static TypeFact arrayLitType(List<SqlExpr> elements) {
        if (elements.isEmpty()) {
            return UNKNOWN;
        }
        return uniform(elements, UNKNOWN) instanceof TypeFact.Typed t
                ? typed(new SqlType.Array(t.type())) : UNKNOWN;
    }

    /** {@link SqlExpr.StructLit} — every field's type, in declared
     * order; any untypeable or NULL field leaves the layout partial. */
    static TypeFact structLitType(List<SqlExpr.StructLit.Field> fields) {
        java.util.List<SqlType.Struct.Field> fs =
                new java.util.ArrayList<>(fields.size());
        for (SqlExpr.StructLit.Field f : fields) {
            if (!(f.value().type() instanceof TypeFact.Typed t)) {
                return UNKNOWN;
            }
            fs.add(new SqlType.Struct.Field(f.name(), t.type()));
        }
        return typed(new SqlType.Struct(fs));
    }

    /** {@link SqlExpr.StructGet} — the named field of a typed struct;
     * extraction from the NULL value is the NULL value. */
    static TypeFact structGetType(SqlExpr source, String field) {
        TypeFact sv = source.type();
        if (sv instanceof TypeFact.Bottom) {
            return BOTTOM;
        }
        if (sv instanceof TypeFact.Typed t
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
    static TypeFact scalarSubqueryType(SqlQuery sub) {
        return sub.outputs().size() == 1
                ? typed(sub.outputs().get(0).type()) : UNKNOWN;
    }

    /** {@link SqlExpr.CheckedOne} — the element of a definite list;
     * narrowing the NULL value flows the NULL value. */
    static TypeFact checkedOneType(SqlExpr list) {
        TypeFact lv = list.type();
        if (lv instanceof TypeFact.Bottom) {
            return BOTTOM;
        }
        return lv instanceof TypeFact.Typed t
                && t.type() instanceof SqlType.Array at
                ? typed(at.element()) : UNKNOWN;
    }

    /** {@link SqlExpr.WindowCall} — a windowed {@link SqlAgg.Reducer}
     * keeps its own promotion (probed: sum/avg/count/min OVER () match
     * the grouped results); ranking/value kinds have no rule yet. */
    static TypeFact windowType(SqlAgg fn) {
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
    static TypeFact reducerType(SqlAgg.Fn fn, TypeFact arg0) {
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
        if (!(arg0 instanceof TypeFact.Typed t0)) {
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
    static TypeFact reduceCollectionType(SqlAgg.Fn fn, SqlExpr collection) {
        return collection.type() instanceof TypeFact.Typed t
                && t.type() instanceof SqlType.Array at
                ? reducerType(fn, typed(at.element())) : UNKNOWN;
    }

    private static boolean integerFamily(SqlType t) {
        return t == SqlType.Scalar.BIGINT || t == SqlType.Scalar.INTEGER
                || t == SqlType.Scalar.HUGEINT;
    }

    /** An array-in/array-out passthrough (sort/filter/slice family);
     * transporting the NULL value flows the NULL value. */
    private static TypeFact arrayPass(TypeFact v) {
        if (v instanceof TypeFact.Bottom) {
            return BOTTOM;
        }
        return v instanceof TypeFact.Typed t
                && t.type() instanceof SqlType.Array ? v : UNKNOWN;
    }

    /** Element extraction (LIST_GET/UNNEST); extraction from the NULL
     * value is the NULL value. */
    private static TypeFact element(TypeFact v) {
        if (v instanceof TypeFact.Bottom) {
            return BOTTOM;
        }
        return v instanceof TypeFact.Typed t
                && t.type() instanceof SqlType.Array at
                ? typed(at.element()) : UNKNOWN;
    }

    private static boolean integerKind(SqlType t) {
        return t == SqlType.Scalar.INTEGER || t == SqlType.Scalar.BIGINT
                || t == SqlType.Scalar.HUGEINT;
    }

    /** Binary/n-ary arithmetic promotion (the probed matrix): any
     * DOUBLE operand &rarr; DOUBLE; all-integer &rarr; the widest
     * member; a DECIMAL/temporal/unknown operand &rarr; UNKNOWN
     * (DuckDB's decimal precision arithmetic is version-specific —
     * never guessed); any NULL-value operand &rarr; the NULL value
     * (arithmetic is strict). */
    private static TypeFact numericPromotion(List<SqlExpr> a) {
        if (a.isEmpty()) {
            return UNKNOWN;
        }
        boolean bottom = false;
        boolean dbl = false;
        int width = 0;
        for (SqlExpr e : a) {
            TypeFact f = e.type();
            if (f instanceof TypeFact.Bottom) {
                bottom = true;
                continue;
            }
            if (!(f instanceof TypeFact.Typed t)) {
                return UNKNOWN;
            }
            SqlType ty = t.type();
            if (ty == SqlType.Scalar.DOUBLE) {
                dbl = true;
            } else if (ty == SqlType.Scalar.INTEGER) {
                width = Math.max(width, 1);
            } else if (ty == SqlType.Scalar.BIGINT) {
                width = Math.max(width, 2);
            } else if (ty == SqlType.Scalar.HUGEINT) {
                width = Math.max(width, 3);
            } else {
                return UNKNOWN;
            }
        }
        if (bottom) {
            return BOTTOM;   // NULL propagates through arithmetic
        }
        if (dbl) {
            return T_DOUBLE;
        }
        return switch (width) {
            case 1 -> T_INTEGER;
            case 2 -> T_BIGINT;
            case 3 -> T_HUGEINT;
            default -> UNKNOWN;
        };
    }

    /** The single shared type of a branch/argument family: BOTTOM
     * members are ADMISSIBLE anywhere and skipped; all typeable members
     * must agree exactly; an UNKNOWN member poisons. A family that is
     * entirely the NULL value resolves to {@code allBottom} — the
     * caller names what that means for its shape (the NULL value for
     * CASE/COALESCE, UNKNOWN for a literal array's element type). */
    private static TypeFact uniform(List<SqlExpr> es, TypeFact allBottom) {
        SqlType common = null;
        boolean saw = false;
        for (SqlExpr e : es) {
            TypeFact v = e.type();
            if (v instanceof TypeFact.Bottom) {
                saw = true;
                continue;
            }
            // an error() member RAISES — it never yields a value — so in
            // a branch family it is bottom-like: admissible anywhere,
            // never the family's type (witness: the checked-extract CASE,
            // error-guard + LIST_GET over Array(LITERAL), types LITERAL)
            if (e instanceof SqlExpr.Call c && c.fn() == SqlFn.ERROR) {
                saw = true;
                continue;
            }
            if (!(v instanceof TypeFact.Typed t)) {
                return UNKNOWN;
            }
            saw = true;
            if (common == null) {
                common = t.type();
            } else if (!common.equals(t.type())) {
                common = branchPromote(common, t.type());
                if (common == null) {
                    return UNKNOWN;
                }
            }
        }
        if (common != null) {
            return typed(common);
        }
        return saw ? allBottom : UNKNOWN;
    }

    /** BRANCH-FAMILY promotion (DuckDB 1.5.0 probed, 2026-08-24 —
     * CASE/COALESCE mixed members; the same lattice benefits ArrayLit
     * and LIST_CONCAT through this shared rule): widest integer wins;
     * any DOUBLE wins over the integer family; DATE+TIMESTAMP promote
     * to TIMESTAMP. DECIMAL pairs follow version-specific precision
     * formulas and cross-kind pairs ERROR at execution — both null
     * here (UNKNOWN), never guessed. */
    private static @com.legend.Nullable SqlType branchPromote(
            SqlType a, SqlType b) {
        if (integerKind(a) && integerKind(b)) {
            return intWidth(a) >= intWidth(b) ? a : b;
        }
        if (a == SqlType.Scalar.DOUBLE && integerKind(b)
                || b == SqlType.Scalar.DOUBLE && integerKind(a)) {
            return SqlType.Scalar.DOUBLE;
        }
        if (a == SqlType.Scalar.DATE && b == SqlType.Scalar.TIMESTAMP
                || a == SqlType.Scalar.TIMESTAMP
                        && b == SqlType.Scalar.DATE) {
            return SqlType.Scalar.TIMESTAMP;
        }
        return null;
    }

    private static int intWidth(SqlType t) {
        return t == SqlType.Scalar.INTEGER ? 1
                : t == SqlType.Scalar.BIGINT ? 2 : 3;
    }
}
