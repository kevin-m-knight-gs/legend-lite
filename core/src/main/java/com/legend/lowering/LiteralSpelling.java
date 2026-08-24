// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.lowering;

import com.legend.compiler.element.type.Type;
import com.legend.sql.SqlExpr;
import com.legend.sql.SqlFn;
import com.legend.sql.SqlType;

import java.util.List;

/**
 * THE ONE OWNER of pure's value-spelling grammar in SQL (F10 proper
 * slice 1, docs/F10_CARRIER_DESIGN.md §3): every place that writes a
 * pure value AS TEXT inside a query builds its spelling HERE. Before
 * this module the knowledge lived in three divergent copies — the
 * verdict lane's canon leaves ({@code CanonicalRenderSql}), the
 * execution lane's print forms ({@code Scalars.floatRepr} +
 * {@code MixedEncoding} element ids), and the host-side parse.
 *
 * <p>TWO NAMED TABLES, deliberately kept apart (never silently merged):
 *
 * <ul>
 *   <li><b>LITERAL grammar</b> ({@link #literal}, {@link #leaf}) — the
 *       six mutually disjoint source spellings (bare int, pointed
 *       float, D-suffix decimal, quoted string, bare bool, %-prefixed
 *       temporal). The byte-verdict language, and — slice 2 — the
 *       kind-faithful carrier's cell encoding.</li>
 *   <li><b>PRINT forms</b> ({@link #floatPrint},
 *       {@link #decimalPrintD}, {@link #datePrint},
 *       {@link #dateTimePrint}) — pure's toString output as the
 *       execution wire spells it today (dates WITHOUT the % prefix,
 *       DateTime with the +0000 suffix, Decimal with the D the canon
 *       strips back off).</li>
 * </ul>
 *
 * <p>KNOWN DIVERGENCES between the tables (recorded, resolved by later
 * slices — slice 1 is byte-identical by charter):
 * float — {@link #floatCanon} unfolds EVERY exponent textually and
 * unifies zeros to '0.0'; {@link #floatPrint} re-renders through
 * DECIMAL(38,18)/HUGEINT and keeps the exponent beyond that envelope.
 * temporal — the LITERAL grammar prefixes %, print forms do not;
 * {@link #temporalCanon} normalizes both wire spellings through one
 * text pipeline.
 */
public final class LiteralSpelling {

    private LiteralSpelling() {
    }

    // ==================================================================
    // LITERAL grammar (verdict canon; slice-2 carrier)
    // ==================================================================

    /** The canon LEAF print of a scalar kind (no literal framing) —
     * null = unclaimed kind (the caller declines, counted). */
    public static @com.legend.Nullable SqlExpr leaf(SqlExpr v, Type t) {
        if (t == Type.Primitive.STRING) {
            return v;
        }
        if (t == Type.Primitive.BOOLEAN || t == Type.Primitive.INTEGER
                || t == Type.Primitive.STRICT_DATE) {
            // DuckDB casts: booleans print true/false, integers bare,
            // dates ISO — already the H1 forms
            return new SqlExpr.Cast(v, SqlType.Scalar.VARCHAR);
        }
        if (t == Type.Primitive.DECIMAL
                || t instanceof Type.PrecisionDecimal) {
            // PrecisionDecimal IS Decimal with a declared shape — same
            // scale-normalized canonical form (V6 burn)
            return decimalCanon(v);
        }
        if (t instanceof Type.EnumType) {
            // enum values ride the wire as their NAMES (the canonical
            // form per H1: bare member name) — the kind gate already
            // scoped equality to the SAME enumeration
            return v;
        }
        if (t == Type.Primitive.FLOAT) {
            return floatCanon(v);
        }
        if (t == Type.Primitive.DATE_TIME || t == Type.Primitive.DATE) {
            return temporalCanon(v);
        }
        return null;
    }

    /** The full PURE-LITERAL spelling: {@link #leaf} plus the framing
     * that makes the six forms mutually disjoint (quotes + escapes for
     * strings, D suffix for decimals, % prefix for temporals). */
    public static @com.legend.Nullable SqlExpr literal(SqlExpr v, Type kind) {
        SqlExpr leaf = leaf(v, kind);
        if (leaf == null) {
            return null;
        }
        if (kind == Type.Primitive.STRING) {
            // pure string literal: backslash then quote escape, quoted
            SqlExpr escaped = SqlExpr.Call.of(SqlFn.REPLACE,
                    SqlExpr.Call.of(SqlFn.REPLACE, leaf,
                            new SqlExpr.StringLit("\\"),
                            new SqlExpr.StringLit("\\\\")),
                    new SqlExpr.StringLit("'"),
                    new SqlExpr.StringLit("\\'"));
            return SqlExpr.Call.of(SqlFn.CONCAT,
                    SqlExpr.Call.of(SqlFn.CONCAT,
                            new SqlExpr.StringLit("'"), escaped),
                    new SqlExpr.StringLit("'"));
        }
        if (kind == Type.Primitive.DECIMAL
                || kind instanceof Type.PrecisionDecimal) {
            // a PrecisionDecimal IS a Decimal with declared shape — its
            // pure literal is D-suffixed the same (grammar hole found
            // by the 2b select carrier: the typed side's candidate
            // spelled '1.0' against the carrier's '1.0D')
            return SqlExpr.Call.of(SqlFn.CONCAT, leaf,
                    new SqlExpr.StringLit("D"));
        }
        if (kind == Type.Primitive.STRICT_DATE
                || kind == Type.Primitive.DATE_TIME) {
            return SqlExpr.Call.of(SqlFn.CONCAT,
                    new SqlExpr.StringLit("%"), leaf);
        }
        return leaf;   // Integer bare, Float with its point, Boolean bare
    }

    /** Decimal: SCALE-PRESERVING (X2, VERDICT_RULE_AUDIT — engine
     * Decimal equality is getValue().equals, scale-sensitive; the old
     * scale-normalized canon followed the deleted compareTo grant).
     * CAST already preserves scale ('8.00'); only the wire's 'D'
     * representation suffix (variant/identity VARCHAR channel: 2D,
     * 1.0D) normalizes away. */
    static SqlExpr decimalCanon(SqlExpr v) {
        return SqlExpr.Call.of(SqlFn.REGEXP_REPLACE,
                new SqlExpr.Cast(v, SqlType.Scalar.VARCHAR),
                new SqlExpr.StringLit("[Dd]$"),
                new SqlExpr.StringLit(""));
    }

    /**
     * Float: fixed-point ALWAYS (H1 — pure never prints exponent
     * notation). DuckDB's CAST is shortest-repr but switches to
     * exponent for small/large magnitudes; those unfold through a
     * COMPLETE textual exponent unfold. Non-finite
     * spellings pass through — out of the claimed domain (§4), they
     * can never equal legitimate canonical text and the parallel host
     * referee names them residue.
     */
    static SqlExpr floatCanon(SqlExpr v) {
        SqlExpr base = new SqlExpr.Cast(v, SqlType.Scalar.VARCHAR);
        SqlExpr unfolded = exponentUnfold(base);
        return new SqlExpr.Case(List.of(
                // ZEROS UNIFY (spec §3, witness parseFloat('-000.000')):
                // pure grants 0.0 == -0.0, so the canonical render of
                // every zero is '0.0'. Detected TEXTUALLY (F10 slice 1):
                // the old v = 0.0 compare forced SQL to cast the COLUMN
                // to DOUBLE, which errored the whole wrapped query on
                // print-form identity carriers ('7.345D') — the canon
                // must be TOTAL over any column it can meet. For genuine
                // DOUBLE columns the zero texts are exactly 0.0/-0.0,
                // so the regex is equivalence, not leniency.
                new SqlExpr.Case.When(
                        SqlExpr.Call.of(SqlFn.REGEXP_FULL_MATCH, base,
                                new SqlExpr.StringLit("-?0+(\\.0+)?")),
                        new SqlExpr.StringLit("0.0")),
                new SqlExpr.Case.When(has(base, "e"), unfolded),
                new SqlExpr.Case.When(SqlExpr.Call.of(SqlFn.NOT,
                        has(base, ".")),
                        SqlExpr.Call.of(SqlFn.CONCAT, base,
                                new SqlExpr.StringLit(".0")))),
                base);
    }

    /**
     * Temporal (DateTime/Date stamps): the scalar-channel form —
     * {@code T}-separated, trailing subsecond zeros stripped,
     * {@code +0000} on time-bearing values only. Handles BOTH wire
     * spellings (a TIMESTAMP cell's cast and the precision-faithful
     * VARCHAR convention) through one text pipeline.
     */
    static SqlExpr temporalCanon(SqlExpr v) {
        // an ALREADY-SUFFIXED wire text (the variant-identity channel
        // prints pure's +0000 form) normalizes before the pipeline —
        // the suffix re-appends canonically at the end.
        // SUBSECOND PRECISION IS PRESERVED AS WRITTEN (A1, spec §3:
        // .000 != .0 != none are DISTINCT pure values — AbstractPureDate
        // compares the exact subsecond STRING). The old trailing-zero
        // strip was a NO-OP on TIMESTAMP casts (DuckDB already prints
        // minimal subseconds — probed 2026-08-23) and WRONG on the
        // precision-faithful VARCHAR convention, where the text is
        // authoritative.
        SqlExpr bare = SqlExpr.Call.of(SqlFn.REGEXP_REPLACE,
                new SqlExpr.Cast(v, SqlType.Scalar.VARCHAR),
                new SqlExpr.StringLit("(\\+0000|Z)$"),
                new SqlExpr.StringLit(""));
        SqlExpr t = SqlExpr.Call.of(SqlFn.REPLACE, bare,
                new SqlExpr.StringLit(" "), new SqlExpr.StringLit("T"));
        SqlExpr timeBearing = has(t, "T");
        return new SqlExpr.Case(List.of(new SqlExpr.Case.When(timeBearing,
                SqlExpr.Call.of(SqlFn.CONCAT, t,
                        new SqlExpr.StringLit("+0000")))),
                t);
    }

    /**
     * COMPLETE textual exponent unfold (V10c — replaces the bounded
     * DECIMAL(38,18) cast, which silently zeroed values beyond its
     * envelope): the shortest-repr mantissa digits shift by the
     * exponent as TEXT, so any finite double prints fixed-point
     * exactly — {@code 1.3421e-08 → 0.000000013421},
     * {@code 1e+300 → 1000…000.0}. Pure never prints exponent
     * notation (H1); now neither can we, for any magnitude.
     */
    private static SqlExpr exponentUnfold(SqlExpr base) {
        SqlExpr sign = new SqlExpr.Case(List.of(new SqlExpr.Case.When(
                SqlExpr.Call.of(SqlFn.STARTS_WITH, base,
                        new SqlExpr.StringLit("-")),
                new SqlExpr.StringLit("-"))),
                new SqlExpr.StringLit(""));
        // mantissa without sign, e.g. '1.3421'; its digits '13421';
        // intLen = digits before the dot; exp as an integer
        SqlExpr mant = SqlExpr.Call.of(SqlFn.REGEXP_EXTRACT, base,
                new SqlExpr.StringLit("-?([0-9]+(?:\\.[0-9]+)?)e"),
                new SqlExpr.IntLit(1));
        SqlExpr digits = SqlExpr.Call.of(SqlFn.REPLACE, mant,
                new SqlExpr.StringLit("."), new SqlExpr.StringLit(""));
        SqlExpr dotPos = SqlExpr.Call.of(SqlFn.STRPOS, mant,
                new SqlExpr.StringLit("."));
        SqlExpr intLen = new SqlExpr.Case(List.of(new SqlExpr.Case.When(
                SqlExpr.Call.of(SqlFn.EQUAL, dotPos, new SqlExpr.IntLit(0)),
                SqlExpr.Call.of(SqlFn.LENGTH, mant))),
                SqlExpr.Call.of(SqlFn.MINUS, dotPos, new SqlExpr.IntLit(1)));
        SqlExpr exp = new SqlExpr.Cast(SqlExpr.Call.of(SqlFn.REGEXP_EXTRACT,
                base, new SqlExpr.StringLit("e([+-]?[0-9]+)$"),
                new SqlExpr.IntLit(1)), SqlType.Scalar.INTEGER);
        SqlExpr pointPos = SqlExpr.Call.of(SqlFn.PLUS, intLen, exp);
        SqlExpr dLen = SqlExpr.Call.of(SqlFn.LENGTH, digits);
        // three shapes by where the point lands
        SqlExpr tiny = SqlExpr.Call.of(SqlFn.CONCAT,
                SqlExpr.Call.of(SqlFn.CONCAT, new SqlExpr.StringLit("0."),
                        zeros(SqlExpr.Call.of(SqlFn.MINUS,
                                new SqlExpr.IntLit(0), pointPos))),
                digits);
        SqlExpr huge = SqlExpr.Call.of(SqlFn.CONCAT,
                SqlExpr.Call.of(SqlFn.CONCAT, digits,
                        zeros(SqlExpr.Call.of(SqlFn.MINUS, pointPos, dLen))),
                new SqlExpr.StringLit(".0"));
        SqlExpr mid = SqlExpr.Call.of(SqlFn.CONCAT,
                SqlExpr.Call.of(SqlFn.CONCAT,
                        SqlExpr.Call.of(SqlFn.SUBSTRING, digits,
                                new SqlExpr.IntLit(1), pointPos),
                        new SqlExpr.StringLit(".")),
                SqlExpr.Call.of(SqlFn.SUBSTRING, digits,
                        SqlExpr.Call.of(SqlFn.PLUS, pointPos,
                                new SqlExpr.IntLit(1))));
        SqlExpr body = new SqlExpr.Case(List.of(
                new SqlExpr.Case.When(SqlExpr.Call.of(SqlFn.LESS_EQUAL,
                        pointPos, new SqlExpr.IntLit(0)), tiny),
                new SqlExpr.Case.When(SqlExpr.Call.of(SqlFn.GREATER_EQUAL,
                        pointPos, dLen), huge)),
                mid);
        return SqlExpr.Call.of(SqlFn.CONCAT, sign, body);
    }

    /** {@code n} zeros (RPAD over empty; negative n yields ''). */
    private static SqlExpr zeros(SqlExpr n) {
        // rpad's length parameter binds INTEGER, not BIGINT
        return SqlExpr.Call.of(SqlFn.RPAD, new SqlExpr.StringLit(""),
                new SqlExpr.Cast(
                        SqlExpr.Call.of(SqlFn.GREATEST, n,
                                new SqlExpr.IntLit(0)),
                        SqlType.Scalar.INTEGER),
                new SqlExpr.StringLit("0"));
    }

    private static SqlExpr has(SqlExpr text, String needle) {
        return SqlExpr.Call.of(SqlFn.GREATER,
                SqlExpr.Call.of(SqlFn.STRPOS, text,
                        new SqlExpr.StringLit(needle)),
                new SqlExpr.IntLit(0));
    }

    /** F10 slice 2 — the MIXED-NUMERIC carrier encoder: a literal
     * collection whose elements are ≥2 DISTINCT numeric kinds rebuilds
     * as an array of pure-literal spellings (each element carries its
     * own kind; the DOUBLE promotion that erased Integer 1 into 1.0
     * dies here). Null = not this shape (homogeneous, non-numeric,
     * non-literal) — the caller keeps its lane. */
    static @com.legend.Nullable SqlExpr mixedNumericArray(
            com.legend.compiler.spec.typed.TypedSpec spec, SqlExpr lowered) {
        if (!(spec instanceof com.legend.compiler.spec.typed.TypedCollection c)
                || !(lowered instanceof SqlExpr.ArrayLit la)
                || c.elements().size() < 2
                || la.elements().size() != c.elements().size()) {
            return null;
        }
        java.util.List<SqlExpr> spelled =
                new java.util.ArrayList<>(la.elements().size());
        java.util.Set<Type> kinds = new java.util.HashSet<>();
        for (int i = 0; i < c.elements().size(); i++) {
            Type t = c.elements().get(i).info().type();
            Type kind = t instanceof Type.PrecisionDecimal
                    ? Type.Primitive.DECIMAL : t;
            if (kind != Type.Primitive.INTEGER && kind != Type.Primitive.FLOAT
                    && kind != Type.Primitive.DECIMAL) {
                return null;
            }
            kinds.add(kind);
            SqlExpr lit = literal(la.elements().get(i), kind);
            if (lit == null) {
                return null;
            }
            spelled.add(lit);
        }
        return kinds.size() < 2 ? null : new SqlExpr.ArrayLit(spelled);
    }

    /** F10 slice 3b — the STRUCTURAL INVERSE of the element spellings,
     * for consumers that DECOMPOSE a spelled collection statically
     * (format's printf args want the raw values back). Pattern-matches
     * exactly the trees this file emits — one owner, both directions,
     * so the shapes cannot drift apart. Null = not a recognized
     * spelling tree (caller must not guess). */
    static @com.legend.Nullable SqlExpr unspell(SqlExpr spelled) {
        // integer / boolean / strict-date leaf: CAST(x AS VARCHAR)
        if (spelled instanceof SqlExpr.Cast c
                && c.target() == SqlType.Scalar.VARCHAR) {
            return c.value();
        }
        // float: the canon CASE whose zeros-unify WHEN regex-matches
        // CAST(x AS VARCHAR) — recover x from the first WHEN's probe
        if (spelled instanceof SqlExpr.Case cs
                && !cs.whens().isEmpty()
                && cs.whens().get(0).condition() instanceof SqlExpr.Call fm
                && fm.fn() == SqlFn.REGEXP_FULL_MATCH
                && fm.args().get(0) instanceof SqlExpr.Cast fc
                && fc.target() == SqlType.Scalar.VARCHAR) {
            return fc.value();
        }
        if (spelled instanceof SqlExpr.Call cc && cc.fn() == SqlFn.CONCAT) {
            List<SqlExpr> a = cc.args();
            // decimal: CONCAT(strip-D(CAST(x AS VARCHAR)), 'D')
            if (a.size() == 2 && a.get(1) instanceof SqlExpr.StringLit d
                    && d.value().equals("D")
                    && a.get(0) instanceof SqlExpr.Call rr
                    && rr.fn() == SqlFn.REGEXP_REPLACE
                    && rr.args().get(0) instanceof SqlExpr.Cast dc) {
                return dc.value();
            }
            // temporals: CONCAT('%', print) — print = strftime(x, fmt)
            // (dates / datetimes; datetime print is CONCAT(strftime,
            // '+0000')) or the partial-date string x itself
            if (a.size() == 2 && a.get(0) instanceof SqlExpr.StringLit pc
                    && pc.value().equals("%")) {
                SqlExpr body = a.get(1);
                if (body instanceof SqlExpr.Call bp
                        && bp.fn() == SqlFn.CONCAT
                        && bp.args().get(0) instanceof SqlExpr.Call st
                        && st.fn() == SqlFn.STRFTIME) {
                    return st.args().get(0);
                }
                if (body instanceof SqlExpr.Call st2
                        && st2.fn() == SqlFn.STRFTIME) {
                    return st2.args().get(0);
                }
                return body;   // partial-date string carrier
            }
            // string: CONCAT(CONCAT('\'', escaped), '\'') — escaped =
            // REPLACE(REPLACE(x, ...), ...)
            if (a.size() == 2 && a.get(0) instanceof SqlExpr.Call oc
                    && oc.fn() == SqlFn.CONCAT
                    && oc.args().get(0) instanceof SqlExpr.StringLit q
                    && q.value().equals("'")
                    && oc.args().get(1) instanceof SqlExpr.Call r1
                    && r1.fn() == SqlFn.REPLACE
                    && r1.args().get(0) instanceof SqlExpr.Call r2
                    && r2.fn() == SqlFn.REPLACE) {
                return r2.args().get(0);
            }
        }
        return null;
    }

        /** F10 3b — UNSPELL a LITERAL-marked value wholesale: the marker
     * cast strips and every element inverts through {@link #unspell}.
     * Null = not our marked shape or an element didn't invert (caller
     * keeps its lane). The HARMONIZATION rule rides on this: a spelled
     * literal meeting a computed/JSON consumer converts BACK to raw —
     * comparisons and conformance casts behave exactly as before the
     * carrier, while literal-vs-literal pairs byte-compare in the
     * grammar. */
    static @com.legend.Nullable SqlExpr unspellMarked(SqlExpr e) {
        if (!(e instanceof SqlExpr.Cast mk)) {
            return null;
        }
        if (mk.target() == SqlType.Scalar.LITERAL) {
            return unspell(mk.value());
        }
        if (mk.target() instanceof SqlType.Array a
                && a.element() == SqlType.Scalar.LITERAL
                && mk.value() instanceof SqlExpr.ArrayLit la) {
            java.util.List<SqlExpr> raw =
                    new java.util.ArrayList<>(la.elements().size());
            for (SqlExpr el : la.elements()) {
                SqlExpr u = unspell(el);
                if (u == null) {
                    return null;
                }
                raw.add(u);
            }
            return new SqlExpr.ArrayLit(raw);
        }
        return null;
    }

    // ==================================================================
    // PRINT forms (execution wire; pure toString spellings)
    // ==================================================================

    /**
     * Pure prints a Float via its MINIMAL decimal repr: DuckDB's shortest
     * round-trip VARCHAR cast already matches ('1.5', '2.0') EXCEPT where it
     * chooses exponent notation — those re-render plain through a
     * DECIMAL(38,18) cast with trailing zeros trimmed (and a bare trailing
     * dot restored to '.0'). Magnitudes outside DECIMAL(38,18) keep the
     * exponent form.
     */
    public static SqlExpr floatPrint(SqlExpr x) {
        SqlExpr s = new SqlExpr.Cast(x, SqlType.Scalar.VARCHAR);
        // FRACTION-FREE values render through HUGEINT — exact plain digits
        // for the whole [1e16, 1e38) band where the DECIMAL(38,18) cast
        // fabricates garbage (audit: 1e18 printed ...042.42...); every
        // double >= 2^53 is fraction-free, so all large magnitudes take
        // this branch.
        SqlExpr intPlain = SqlExpr.Call.of(SqlFn.CONCAT,
                new SqlExpr.Cast(new SqlExpr.Cast(x, SqlType.Scalar.HUGEINT),
                        SqlType.Scalar.VARCHAR),
                new SqlExpr.StringLit(".0"));
        SqlExpr plain = SqlExpr.Call.of(SqlFn.RTRIM,
                new SqlExpr.Cast(new SqlExpr.Cast(x, new SqlType.Decimal(38, 18)),
                        SqlType.Scalar.VARCHAR),
                new SqlExpr.StringLit("0"));
        SqlExpr fixed = new SqlExpr.Case(List.of(new SqlExpr.Case.When(
                SqlExpr.Call.of(SqlFn.ENDS_WITH, plain, new SqlExpr.StringLit(".")),
                SqlExpr.Call.of(SqlFn.CONCAT, plain, new SqlExpr.StringLit("0")))),
                plain);
        SqlExpr hasExp = SqlExpr.Call.of(SqlFn.GREATER,
                SqlExpr.Call.of(SqlFn.STRPOS, s, new SqlExpr.StringLit("e")),
                new SqlExpr.IntLit(0));
        SqlExpr fractionFree = SqlExpr.Call.of(SqlFn.AND,
                SqlExpr.Call.of(SqlFn.EQUAL, x, SqlExpr.Call.of(SqlFn.FLOOR_RAW, x)),
                SqlExpr.Call.of(SqlFn.LESS,
                        SqlExpr.Call.of(SqlFn.ABS, x), new SqlExpr.FloatLit(1e38)));
        // The DECIMAL path stays only where the scale-18 cast is exact for
        // short-decimal values: fractional magnitudes in [1e-17, 2^53)
        // (below 1e-17 the scale rounds — 1.5e-18 gained a digit; audit).
        SqlExpr inRange = SqlExpr.Call.of(SqlFn.AND,
                SqlExpr.Call.of(SqlFn.GREATER_EQUAL,
                        SqlExpr.Call.of(SqlFn.ABS, x), new SqlExpr.FloatLit(1e-17)),
                SqlExpr.Call.of(SqlFn.LESS,
                        SqlExpr.Call.of(SqlFn.ABS, x), new SqlExpr.FloatLit(9.007199254740992e15)));
        return new SqlExpr.Case(List.of(
                new SqlExpr.Case.When(
                        SqlExpr.Call.of(SqlFn.AND, hasExp, fractionFree), intPlain),
                new SqlExpr.Case.When(
                        SqlExpr.Call.of(SqlFn.AND, hasExp, inRange), fixed)), s);
    }

    /** Decimal PRINT form: the value's cast text with the {@code D}
     * suffix (the identity-channel wire spelling the canon strips). */
    public static SqlExpr decimalPrintD(SqlExpr x) {
        return SqlExpr.Call.of(SqlFn.CONCAT,
                new SqlExpr.Cast(x, SqlType.Scalar.VARCHAR),
                new SqlExpr.StringLit("D"));
    }

    /** StrictDate LITERAL from a typed DATE value: % + ISO date. */
    public static SqlExpr strictDateLiteral(SqlExpr x) {
        return SqlExpr.Call.of(SqlFn.CONCAT,
                new SqlExpr.StringLit("%"), datePrint(x));
    }

    /** DateTime LITERAL at the element's STATIC subsecond precision
     * (the caller resolves the format): % + T-separated print + +0000.
     * Precision-faithful by construction — pairs with the A1 fix
     * (temporalCanon no longer strips written subseconds). */
    public static SqlExpr dateTimeLiteral(SqlExpr x, SqlExpr.FormatLit fmt) {
        return SqlExpr.Call.of(SqlFn.CONCAT,
                new SqlExpr.StringLit("%"), dateTimePrint(x, fmt));
    }

    /** PARTIAL-date LITERAL: the string cell IS the body (master's
     * pinned partial-date carrier); % prefixes it. */
    public static SqlExpr partialDateLiteral(SqlExpr text) {
        return SqlExpr.Call.of(SqlFn.CONCAT,
                new SqlExpr.StringLit("%"), text);
    }

    /** StrictDate PRINT form: bare ISO date (no % — print, not literal). */
    public static SqlExpr datePrint(SqlExpr x) {
        return SqlExpr.Call.of(SqlFn.STRFTIME, x,
                new SqlExpr.FormatLit(com.legend.sql.DateFmt.DATE));
    }

    /** DateTime PRINT form: strftime at the literal's own subsecond
     * precision (a STATIC attribute the caller resolves) with pure's
     * {@code +0000} suffix. */
    public static SqlExpr dateTimePrint(SqlExpr x, SqlExpr.FormatLit fmt) {
        return SqlExpr.Call.of(SqlFn.CONCAT,
                SqlExpr.Call.of(SqlFn.STRFTIME, x, fmt),
                new SqlExpr.StringLit("+0000"));
    }
}
