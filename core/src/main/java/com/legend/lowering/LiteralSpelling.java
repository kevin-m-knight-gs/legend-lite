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
        if (kind == Type.Primitive.DECIMAL) {
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
        // the suffix re-appends canonically at the end
        SqlExpr bare = SqlExpr.Call.of(SqlFn.REGEXP_REPLACE,
                new SqlExpr.Cast(v, SqlType.Scalar.VARCHAR),
                new SqlExpr.StringLit("(\\+0000|Z)$"),
                new SqlExpr.StringLit(""));
        SqlExpr t = SqlExpr.Call.of(SqlFn.REPLACE, bare,
                new SqlExpr.StringLit(" "), new SqlExpr.StringLit("T"));
        SqlExpr stripped = stripDot(stripTrailingZeros(t), "");
        SqlExpr timeBearing = has(stripped, "T");
        return new SqlExpr.Case(List.of(new SqlExpr.Case.When(timeBearing,
                SqlExpr.Call.of(SqlFn.CONCAT, stripped,
                        new SqlExpr.StringLit("+0000")))),
                stripped);
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

    /** {@code (\.\d*?)0+$} — trailing zeros after a dot strip; a
     * dotless text is untouched. */
    private static SqlExpr stripTrailingZeros(SqlExpr text) {
        return SqlExpr.Call.of(SqlFn.REGEXP_REPLACE, text,
                new SqlExpr.StringLit("(\\.\\d*?)0+$"),
                new SqlExpr.StringLit("\\1"));
    }

    /** A TERMINAL dot rewrites to {@code replacement} ('' = integral
     * bare for Decimal; '.0' keeps one decimal for Float). */
    private static SqlExpr stripDot(SqlExpr text, String replacement) {
        return SqlExpr.Call.of(SqlFn.REGEXP_REPLACE, text,
                new SqlExpr.StringLit("\\.$"),
                new SqlExpr.StringLit(replacement));
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
