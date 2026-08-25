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
            // ENGINE-COMPAT carry-through (charter §4bZ, replaces the
            // two blanket coercion arms): a TAGGED read keeps its
            // declared label across the registered kind pairs, and the
            // slot records the tolerance (the wire census reads it; an
            // UNTAGGED mismatch falls through to adoption — loud).
            // The tag also PROPAGATES through stamped re-reads (an
            // upper select's column claims the lower's label — equal
            // types, tolerated fact): the slot stays marked at every
            // level, so the FINAL plan's outputs carry the tolerance
            // the wire census needs.
            boolean tol = t.tolerated()
                    && (computed.equals(oc.type())
                            || carryThrough(oc.type(), computed));
            if (tol && !oc.tolerated()) {
                if (os == null) {
                    os = new java.util.ArrayList<>(outputs);
                }
                os.set(i, new OutputCol(oc.name(), oc.type(),
                        oc.nullable(), true));
                continue;
            }
            if (computed.equals(oc.type())
                    || admissible(oc.type(), computed) || tol) {
                continue;
            }
            if (os == null) {
                os = new java.util.ArrayList<>(outputs);
            }
            os.set(i, new OutputCol(oc.name(), computed, oc.nullable()));
        }
        return os == null ? outputs : List.copyOf(os);
    }

    /** THE ENGINE-COMPAT CARRY-THROUGH RELATION (charter §4bZ — the
     * named, tag-gated home of the two DELETED blanket coercion arms):
     * the kind pairs the engine's raw carry-through produces at a
     * DECLARED property/column mismatch. Engine receipts: transform()
     * is identity unless enum (legend-pure functions.pure:218), the
     * fetch is ResultSet-metadata-keyed (ResultSetValueHandlers), no
     * validation exists on either side — the mismatches are model
     * facts the engine tolerates, receipted row-by-row by the
     * fixture-skew census (Runner.FIXTURE_SKEW). Consulted ONLY for
     * reads tagged at the mapping seam. */
    public static boolean carryThrough(SqlType declared, SqlType computed) {
        return (declared == SqlType.Scalar.VARCHAR
                        || declared == SqlType.Scalar.DOUBLE)
                && (computed == SqlType.Scalar.BIGINT
                        || computed == SqlType.Scalar.INTEGER
                        || computed == SqlType.Scalar.HUGEINT);
    }

    /** The mapping seam's TAG DOOR: rebuild a supplied-leaf column
     * read with the engine-compat tolerance on its fact (charter
     * §4bZ). Column is the one supplied-leaf node (its ctor keeps the
     * passed fact); computed-node facts are constructor-owned and
     * never overridden — a non-column read passes through untagged
     * and a downstream mismatch stays loud (counted, never hidden). */
    public static SqlExpr tolerateRead(SqlExpr e) {
        return e instanceof SqlExpr.Column c
                && c.type() instanceof TypeFact.Typed t && !t.tolerated()
                ? new SqlExpr.Column(c.table(), c.name(),
                        new TypeFact.Typed(t.type(), true))
                : e;
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
        // (The two BLANKET COERCION ARMS — DOUBLE <- BIGINT/INTEGER
        // and VARCHAR <- BIGINT — are DELETED, re-homed 2026-08-25 to
        // {@link #carryThrough}: the same kind pairs, now gated on the
        // mapping seam's PROVENANCE TAG (TypeFact.Typed.tolerated) so
        // only reads that genuinely crossed a declared property/column
        // mismatch tolerate; an untagged mismatch adopts the wire and
        // goes loud in the wire census. History + engine receipts on
        // carryThrough and in charter §4bY/§4bZ.)
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
                    NULL_SAFE_NOT_EQUAL, LIST_BOOL_AND, LIST_BOOL_OR,
                    XOR -> T_BOOLEAN;
            case CONCAT, CONCAT_JOIN, UPPER, LOWER, TRIM, LTRIM, RTRIM,
                    REPLACE, SUBSTRING, LEFT, RIGHT, LPAD, RPAD,
                    REVERSE_STRING, UC_FIRST, LC_FIRST, SPLIT_PART,
                    REGEXP_EXTRACT, REGEXP_REPLACE, CHR, ENCODE_BASE64,
                    DECODE_BASE64, MD5, SHA1, SHA256, GUID, DAYNAME,
                    MONTHNAME, STRFTIME, TYPEOF, BOOL_TO_TEXT, FORMAT,
                    JSON_TYPE, CURRENT_USER_FN, REPEAT_STR -> T_VARCHAR;
            case LENGTH, STRPOS, ASCII_CODE, LEVENSHTEIN, LIST_LENGTH,
                    LIST_POSITION, EXTRACT, DATE_DIFF, EPOCH_SECONDS,
                    EPOCH_MS, PARSE_INT -> T_BIGINT;
            case SQRT, CBRT, EXP, LN, LOG10, POW, PI, SIN, COS, TAN, ASIN,
                    ACOS, ATAN, ATAN2, SINH, COSH, TANH, COT, RADIANS,
                    DEGREES, DIVIDE, JARO_WINKLER -> T_DOUBLE;
            case TODAY, MAKE_DATE -> T_DATE;
            case NOW, MAKE_TIMESTAMP, STRPTIME, FROM_EPOCH_SECONDS,
                    FROM_EPOCH_MS, TIMEZONE -> T_TIMESTAMP;
            // PROBED 1.5.0 (2026-08-25 full-burn): the list aggregates
            // follow the SAME reducer promotions as their grouped
            // twins, read through the element (list_sum int->HUGEINT,
            // list_avg/median->DOUBLE, list_mode identity)
            case LIST_SUM -> a.isEmpty() ? UNKNOWN
                    : reduceCollectionType(SqlAgg.Fn.SUM, a.get(0));
            case LIST_AVG -> a.isEmpty() ? UNKNOWN
                    : reduceCollectionType(SqlAgg.Fn.AVG, a.get(0));
            case LIST_MEDIAN -> a.isEmpty() ? UNKNOWN
                    : reduceCollectionType(SqlAgg.Fn.MEDIAN, a.get(0));
            case LIST_MODE -> a.isEmpty() ? UNKNOWN
                    : reduceCollectionType(SqlAgg.Fn.MODE, a.get(0));
            // list_product -> DOUBLE for every numeric list (probed)
            case LIST_PRODUCT -> listProductType(a);
            // list_append keeps the list's type when the appended
            // element speaks it (probed; a promoting append is a
            // different type — UNKNOWN, never guessed)
            case LIST_APPEND -> listAppendType(a);
            // list_reduce yields the lambda BODY's value (the running
            // accumulator — params bound by the attachment door;
            // probed: list_reduce([ints], +) -> INTEGER)
            case LIST_REDUCE -> a.size() == 2
                    && a.get(1) instanceof SqlExpr.Lambda lam
                    && lam.body().type() instanceof TypeFact.Typed bt
                    ? typed(bt.type()) : UNKNOWN;
            // map family (probed): concat keeps the uniform Map type;
            // entries [{first K, second V}] build MAP(K, V); extract/
            // values yield the VALUE list, keys the KEY list
            case MAP_CONCAT -> uniform(a, BOTTOM);
            case MAP_FROM_ENTRIES -> mapFromEntriesType(a);
            case MAP_EXTRACT, MAP_VALUES -> a.isEmpty() ? UNKNOWN
                    : a.get(0).type() instanceof TypeFact.Typed t
                            && t.type() instanceof SqlType.Map m
                    ? typed(new SqlType.Array(m.value())) : UNKNOWN;
            case MAP_KEYS -> a.isEmpty() ? UNKNOWN
                    : a.get(0).type() instanceof TypeFact.Typed t
                            && t.type() instanceof SqlType.Map m
                    ? typed(new SqlType.Array(m.key())) : UNKNOWN;
            // PROBED on the reference jar (DuckDB 1.5.0, 2026-08-25):
            // date_trunc returns TIMESTAMP at EVERY granularity —
            // day/month/year included. (The 1.4.4 CLI returns DATE for
            // day-and-coarser: a live version-skew trap; the rule is
            // written from the 1.5.0 JDBC probe, the executing backend.)
            // The NULL value propagates; H2's DATE delivery rides the
            // TIMESTAMP<-DATE subsumption arm at the wire.
            case DATE_TRUNC_DAY, DATE_TRUNC -> {
                if (a.isEmpty()) {
                    yield UNKNOWN;
                }
                yield a.stream().anyMatch(
                        x -> x.type() instanceof TypeFact.Bottom)
                        ? BOTTOM : T_TIMESTAMP;
            }
            case TO_VARIANT, JSON_MERGE_PATCH, VARIANT_GET -> T_JSON;
            case VARIANT_ELEMENTS ->
                    typed(new SqlType.Array(SqlType.Scalar.JSON));
            // string splitters yield VARCHAR[] by definition (probed
            // 1.5.0 — the XStore traderKerb CASE chain's blind leaf)
            case SPLIT, REGEXP_EXTRACT_ALL ->
                    typed(new SqlType.Array(SqlType.Scalar.VARCHAR));
            case RANGE_FN -> typed(new SqlType.Array(SqlType.Scalar.BIGINT));
            case COALESCE -> uniform(a, BOTTOM);
            case LIST_FILTER, LIST_SORT, LIST_SORT_DESC, LIST_TAIL,
                    LIST_INIT, LIST_SLICE, LIST_DISTINCT, LIST_REVERSE ->
                    a.isEmpty() ? UNKNOWN : arrayPass(a.get(0).type());
            case LIST_CONCAT -> uniform(a, BOTTOM);
            case LIST_GET, UNNEST ->
                    a.isEmpty() ? UNKNOWN : element(a.get(0).type());
            // PROBED DuckDB 1.5.0 (2026-08-25, pct-tail burn):
            // list_max/list_min are ELEMENT-PRESERVING (int->int,
            // varchar->varchar, date->date — the identity family)
            case LIST_MAX, LIST_MIN ->
                    a.isEmpty() ? UNKNOWN : element(a.get(0).type());
            // date/timestamp + INTERVAL -> TIMESTAMP at EVERY unit,
            // DATE input included (probed; the NULL value propagates)
            case ADD_INTERVAL, ADD_INTERVAL_TEMPORAL -> {
                if (a.isEmpty()) {
                    yield UNKNOWN;
                }
                yield a.stream().anyMatch(
                        x -> x.type() instanceof TypeFact.Bottom)
                        ? BOTTOM : T_TIMESTAMP;
            }
            // bit ops are WIDTH-PRESERVING on the integer family
            // (probed: INT&INT->INT, BIGINT|INT->BIGINT — the widest
            // member; non-integer operands have no bit story)
            case BIT_AND, BIT_OR, BIT_XOR, BIT_NOT, BIT_SHIFT_LEFT,
                    BIT_SHIFT_RIGHT -> bitOpType(a);
            // greatest/least follow the BRANCH-FAMILY promotion
            // (probed: equal kinds identity, BIGINT/INT -> BIGINT;
            // decimal mixes follow version formulas — stay UNKNOWN)
            case GREATEST, LEAST -> uniform(a, BOTTOM);
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
                TypeFact dec = decimalArith(false, a);
                yield dec != null ? dec : numericPromotion(a);
            }
            case TIMES -> {
                TypeFact dec = decimalArith(true, a);
                yield dec != null ? dec : numericPromotion(a);
            }
            case MOD, REM -> numericPromotion(a);
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
            // CEILING/FLOOR/SIGN type as OUR OWN EMISSION, not the bare
            // backend function: the renderer spells them CAST(... AS
            // BIGINT) for every input (AnsiSqlRenderer — pure's
            // ceiling/floor/sign : Integer contract), so the delivered
            // wire is BIGINT always. (The old DOUBLE/Decimal arms
            // described bare ceil() — a rule-vs-emission lie the §4bZ
            // guest-list audit exposed: 20 wire rows the deleted
            // blanket arm had been hiding.)
            case CEILING, FLOOR, SIGN -> {
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
                        ? T_BIGINT : UNKNOWN;
            }
            // everything else: no rule yet — counted, never guessed
            default -> UNKNOWN;
        };
    }

    /** Bit ops (probed): width-preserving on the integer family. */
    private static TypeFact bitOpType(List<SqlExpr> a) {
        boolean bottom = false;
        int width = 0;
        for (SqlExpr e : a) {
            TypeFact f = e.type();
            if (f instanceof TypeFact.Bottom) {
                bottom = true;
            } else if (f instanceof TypeFact.Typed t
                    && integerKind(t.type())) {
                width = Math.max(width, intWidth(t.type()));
            } else {
                width = -1;
                break;
            }
        }
        if (width < 0 || a.isEmpty()) {
            return UNKNOWN;
        }
        if (bottom) {
            return BOTTOM;
        }
        return switch (width) {
            case 1 -> T_INTEGER;
            case 2 -> T_BIGINT;
            case 3 -> T_HUGEINT;
            default -> UNKNOWN;
        };
    }

    /** list_product -> DOUBLE for every numeric list (probed). */
    private static TypeFact listProductType(List<SqlExpr> a) {
        if (a.isEmpty()) {
            return UNKNOWN;
        }
        TypeFact f = a.get(0).type();
        if (f instanceof TypeFact.Bottom) {
            return BOTTOM;
        }
        return f instanceof TypeFact.Typed t
                && t.type() instanceof SqlType.Array ? T_DOUBLE : UNKNOWN;
    }

    /** list_append keeps the list's type when the appended element
     * speaks it (probed); a promoting append stays UNKNOWN. */
    private static TypeFact listAppendType(List<SqlExpr> a) {
        if (a.size() != 2 || !(a.get(0).type()
                instanceof TypeFact.Typed t
                && t.type() instanceof SqlType.Array at)) {
            return UNKNOWN;
        }
        TypeFact e2 = a.get(1).type();
        return e2 instanceof TypeFact.Bottom
                || (e2 instanceof TypeFact.Typed et
                        && et.type().equals(at.element()))
                ? typed(at) : UNKNOWN;
    }

    /** map_from_entries: [{first K, second V}] -> MAP(K, V) (probed). */
    private static TypeFact mapFromEntriesType(List<SqlExpr> a) {
        if (a.size() != 1 || !(a.get(0).type()
                instanceof TypeFact.Typed t
                && t.type() instanceof SqlType.Array at
                && at.element() instanceof SqlType.Struct st
                && st.fields().size() == 2)) {
            return UNKNOWN;
        }
        return typed(new SqlType.Map(st.fields().get(0).type(),
                st.fields().get(1).type()));
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
     * label (builder knowledge carried on the subquery, read here).
     * A LABEL-LESS single-projection wrap (the scalar-position value
     * envelopes: outs=0, one Reducer/expr projection — 340 census
     * rows, 2026-08-25) reads the projection's own STORED type — the
     * tree's construction-time knowledge through the select, never a
     * re-derivation. */
    static TypeFact scalarSubqueryType(SqlQuery sub) {
        if (sub.outputs().size() == 1) {
            return typed(sub.outputs().get(0).type());
        }
        if (sub instanceof SqlSelect s && s.outputs().isEmpty()
                && s.projections().size() == 1
                && !(s.projections().get(0).expr() instanceof SqlExpr.Star)
                && !(s.projections().get(0).expr()
                        instanceof SqlExpr.StarExcept)) {
            return s.projections().get(0).expr().type();
        }
        return UNKNOWN;
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

    /** {@link SqlExpr.FoldCall} — the fold's value is the ACCUMULATOR's:
     * typed only when the lambda BODY (the per-step accumulator) and
     * the INIT agree — a type-changing fold (int seed, string result
     * mid-flight) or an unknown body stays UNKNOWN, never guessed.
     * The list-boxed accumulator carrier ({@code accIsList}) has a
     * different wire shape — no rule. */
    static TypeFact foldType(SqlExpr.Lambda lambda, SqlExpr init,
            boolean accIsList) {
        if (accIsList) {
            return UNKNOWN;
        }
        return lambda.body().type() instanceof TypeFact.Typed bt
                && init.type() instanceof TypeFact.Typed it
                && bt.type().equals(it.type())
                ? typed(bt.type()) : UNKNOWN;
    }

    /** {@link SqlExpr.WindowCall} — a windowed {@link SqlAgg.Reducer}
     * keeps its own promotion (probed: sum/avg/count/min OVER () match
     * the grouped results). Ranking kinds PROBED on the reference jar
     * (DuckDB 1.5.0, 2026-08-25): row_number/rank/dense_rank/ntile
     * &rarr; BIGINT; percent_rank/cume_dist &rarr; DOUBLE. Value kinds
     * (LAG/LEAD/FIRST/LAST/NTH) are element-preserving — the first
     * argument's stored type (a LAG default arg widens only within the
     * family; an unknown arg stays unknown, never guessed). */
    static TypeFact windowType(SqlAgg fn) {
        return switch (fn) {
            case SqlAgg.Reducer r -> r.type();
            case SqlAgg.RankingFn rf -> switch (rf.fn()) {
                case ROW_NUMBER, RANK, DENSE_RANK, NTILE -> T_BIGINT;
                case PERCENT_RANK, CUME_DIST -> T_DOUBLE;
                default -> UNKNOWN;
            };
            case SqlAgg.ValueFn vf -> vf.args().isEmpty()
                    ? UNKNOWN : vf.args().get(0).type();
        };
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
                    // a SUM over an engine-compat TOLERATED read stays
                    // tolerated (§4bZ): the promotion computed from the
                    // STAMP kind may not match the wire's own promotion
                    // (Order.quantity Float[1] over orderTable INT,
                    // fixture FLOAT — sum wires DOUBLE while the stamp
                    // says HUGEINT; the tag lets the declared DOUBLE
                    // label stand, which matches the actual wire —
                    // testReprocessGroupByAlias, the wire-7 review)
                    yield t0.tolerated()
                            ? new TypeFact.Typed(SqlType.Scalar.HUGEINT,
                                    true)
                            : T_HUGEINT;
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
            // element-preserving: the ARG'S OWN FACT transports — the
            // engine-compat tolerance rides identity reducers (max of
            // raw carried values is a raw carried value, §4bZ)
            case MIN, MAX, ANY_VALUE, MODE, QUANTILE_DISC, ARG_MAX,
                    ARG_MIN -> t0;
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

    /** DECIMAL arithmetic promotion — PROBED on the reference jar
     * (DuckDB 1.5.0 JDBC, 2026-08-25; 17 probe points, every one
     * reproduced by this rule; the 1.4.4 CLI differs — version skew is
     * live, always probe the executing jar). Applies only when a
     * DECIMAL operand is present (null = not applicable, the caller
     * falls to {@link #numericPromotion}): any DOUBLE operand wins;
     * the NULL value propagates (strict); integers enter the fold as
     * DECIMAL(10,0)/BIGINT (19,0)/HUGEINT (38,0). Pairwise LEFT fold:
     * PLUS/MINUS s=max(s1,s2), w=max(p1-s1,p2-s2)+s+1; TIMES s=s1+s2,
     * w=p1+p2 — each step capped at the operands' storage-class
     * boundary: 18 when BOTH operand widths &le;18 (the int64 class —
     * the carry digit never promotes across it: probed
     * (18,0)+(18,0)&rarr;(18,0)), else 38. */
    private static @com.legend.Nullable TypeFact decimalArith(
            boolean multiply, List<SqlExpr> a) {
        if (a.isEmpty()) {
            return null;
        }
        boolean sawDecimal = false;
        boolean bottom = false;
        boolean dbl = false;
        java.util.List<int[]> ds = new java.util.ArrayList<>(a.size());
        for (SqlExpr e : a) {
            TypeFact f = e.type();
            if (f instanceof TypeFact.Bottom) {
                bottom = true;
                continue;
            }
            if (!(f instanceof TypeFact.Typed t)) {
                return null;
            }
            SqlType ty = t.type();
            if (ty == SqlType.Scalar.DOUBLE) {
                dbl = true;
            } else if (ty instanceof SqlType.Decimal d) {
                sawDecimal = true;
                ds.add(new int[] {d.precision(), d.scale()});
            } else if (ty == SqlType.Scalar.INTEGER) {
                ds.add(new int[] {10, 0});
            } else if (ty == SqlType.Scalar.BIGINT) {
                ds.add(new int[] {19, 0});
            } else if (ty == SqlType.Scalar.HUGEINT) {
                ds.add(new int[] {38, 0});
            } else {
                return null;
            }
        }
        if (!sawDecimal) {
            return null;   // the int/double lattice owns it
        }
        if (bottom) {
            return BOTTOM;
        }
        if (dbl) {
            return T_DOUBLE;
        }
        int p = ds.get(0)[0];
        int s = ds.get(0)[1];
        for (int i = 1; i < ds.size(); i++) {
            int p2 = ds.get(i)[0];
            int s2 = ds.get(i)[1];
            int cap = p <= 18 && p2 <= 18 ? 18 : 38;
            int ns;
            int nw;
            if (multiply) {
                ns = s + s2;
                nw = p + p2;
            } else {
                ns = Math.max(s, s2);
                nw = Math.max(p - s, p2 - s2) + ns + 1;
            }
            p = Math.min(nw, cap);
            s = ns;
        }
        return typed(new SqlType.Decimal(p, s));
    }

    /** Binary/n-ary arithmetic promotion (the probed matrix): any
     * DOUBLE operand &rarr; DOUBLE; all-integer &rarr; the widest
     * member; a DECIMAL/temporal/unknown operand &rarr; UNKNOWN
     * (DuckDB's decimal precision arithmetic is version-specific —
     * decimal operands take the PROBED {@link #decimalArith} rule at
     * the PLUS/MINUS/TIMES call sites; MOD/REM decimals stay unprobed
     * corners); any NULL-value operand &rarr; the NULL value
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
        boolean emptyArray = false;
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
            // the EMPTY array literal is pure's Nil — it conforms to
            // EVERY array type and constrains nothing in a branch
            // family (witness: concatenate's optional-prop lowering,
            // CASE WHEN … THEN [] ELSE [x] — 2026-08-25, the blind
            // leaf under the LIST_CONCAT/UNNEST untyped chains)
            if (e instanceof SqlExpr.ArrayLit al
                    && al.elements().isEmpty()) {
                saw = true;
                emptyArray = true;
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
            // a skipped [] only conforms to an ARRAY family — an empty
            // array beside scalar members is a shape clash, never typed
            return !emptyArray || common instanceof SqlType.Array
                    ? typed(common) : UNKNOWN;
        }
        if (emptyArray) {
            // an all-[]/NULL family is an empty-ARRAY value with an
            // unknowable element — never the NULL value
            return UNKNOWN;
        }
        return saw ? allBottom : UNKNOWN;
    }

    /** BRANCH-FAMILY promotion (DuckDB 1.5.0 probed, 2026-08-24 —
     * CASE/COALESCE mixed members; the same lattice benefits ArrayLit
     * and LIST_CONCAT through this shared rule): widest integer wins;
     * any DOUBLE wins over the integer family AND over decimals;
     * DATE+TIMESTAMP promote to TIMESTAMP. DECIMAL union promotion
     * PROBED 2026-08-25 (mixed literal lists): s=max(s1,s2),
     * w=maxIntDigits+s — NO carry digit (union holds either value,
     * it never adds them: [Dec(2,1), BIGINT] -> Dec(20,1)) — capped
     * 38; ints enter as (10,0)/(19,0)/(38,0). Remaining cross-kind
     * pairs ERROR at execution — null (UNKNOWN), never guessed. */
    private static @com.legend.Nullable SqlType branchPromote(
            SqlType a, SqlType b) {
        if (integerKind(a) && integerKind(b)) {
            return intWidth(a) >= intWidth(b) ? a : b;
        }
        boolean aNum = integerKind(a) || a instanceof SqlType.Decimal;
        boolean bNum = integerKind(b) || b instanceof SqlType.Decimal;
        if (a == SqlType.Scalar.DOUBLE && bNum
                || b == SqlType.Scalar.DOUBLE && aNum) {
            return SqlType.Scalar.DOUBLE;
        }
        if (aNum && bNum
                && (a instanceof SqlType.Decimal
                        || b instanceof SqlType.Decimal)) {
            int[] da = unionDec(a);
            int[] db = unionDec(b);
            int s = Math.max(da[1], db[1]);
            int w = Math.min(
                    Math.max(da[0] - da[1], db[0] - db[1]) + s, 38);
            return new SqlType.Decimal(w, s);
        }
        if (a == SqlType.Scalar.DATE && b == SqlType.Scalar.TIMESTAMP
                || a == SqlType.Scalar.TIMESTAMP
                        && b == SqlType.Scalar.DATE) {
            return SqlType.Scalar.TIMESTAMP;
        }
        return null;
    }

    private static int[] unionDec(SqlType t) {
        if (t instanceof SqlType.Decimal d) {
            return new int[] {d.precision(), d.scale()};
        }
        return new int[] {t == SqlType.Scalar.INTEGER ? 10
                : t == SqlType.Scalar.BIGINT ? 19 : 38, 0};
    }

    private static int intWidth(SqlType t) {
        return t == SqlType.Scalar.INTEGER ? 1
                : t == SqlType.Scalar.BIGINT ? 2 : 3;
    }
}
