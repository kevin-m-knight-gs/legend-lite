// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.lowering;

import com.legend.compiler.element.type.Type;
import com.legend.sql.SqlExpr;
import com.legend.sql.SqlFn;
import com.legend.sql.SqlType;

import java.util.List;

/**
 * R2's SQL-side canonical scalar render (docs/CANONICAL_FORM_SPEC.md
 * §2): the DATABASE computes the byte-channel text — tenet #1, the
 * render IS the semantic work; Java's only remaining act on a verdict
 * is comparing two DB-computed byte strings. The kind comes from the
 * STAMP (types drive construction — never runtime sniffing), and every
 * rule mirrors the host reference render ({@code CanonicalForm}), the
 * pair the divergence census holds together.
 *
 * <p>Returns null for kinds the SQL channel does not (yet) claim —
 * the caller falls back to the host lattice and the decline is
 * counted, never silent.
 */
public final class CanonicalRenderSql {

    private CanonicalRenderSql() {
    }

    /** Canonical VARCHAR of a scalar value expression, by STAMPED kind. */
    public static @com.legend.Nullable SqlExpr scalarCanon(SqlExpr v, Type t) {
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

    /** Decimal: scale-normalized, integral renders BARE (spec §2 —
     * forced by pure's numeric equality). CAST preserves scale
     * ('8.00'); strip trailing zeros after the dot, then a terminal
     * dot entirely. */
    private static SqlExpr decimalCanon(SqlExpr v) {
        // the wire's Decimal REPRESENTATION spelling carries pure's 'D'
        // suffix on the variant/identity VARCHAR channel (2D, 1.0D) —
        // normalize it away first, same rule shape as the temporal
        // +0000 strip (V6 round 2, user-caught canon-path divergence)
        SqlExpr bare = SqlExpr.Call.of(SqlFn.REGEXP_REPLACE,
                new SqlExpr.Cast(v, SqlType.Scalar.VARCHAR),
                new SqlExpr.StringLit("[Dd]$"),
                new SqlExpr.StringLit(""));
        return stripDot(stripTrailingZeros(bare), "");
    }

    /**
     * Float: fixed-point ALWAYS (H1 — pure never prints exponent
     * notation). DuckDB's CAST is shortest-repr but switches to
     * exponent for small/large magnitudes; those unfold through a
     * DECIMAL(38,18) re-print with trailing zeros stripped (one
     * decimal kept — integral floats keep {@code .0}). Non-finite
     * spellings pass through — out of the claimed domain (§4), they
     * can never equal legitimate canonical text and the parallel host
     * referee names them residue.
     */
    private static SqlExpr floatCanon(SqlExpr v) {
        SqlExpr base = new SqlExpr.Cast(v, SqlType.Scalar.VARCHAR);
        SqlExpr unfolded = stripDot(stripTrailingZeros(new SqlExpr.Cast(
                new SqlExpr.Cast(v, new SqlType.Decimal(38, 18)),
                SqlType.Scalar.VARCHAR)), ".0");
        return new SqlExpr.Case(List.of(
                // ZEROS UNIFY (spec §3, witness parseFloat('-000.000')):
                // pure grants 0.0 == -0.0, so the canonical render of
                // every zero is '0.0' — SQL's v = 0 catches both signs
                new SqlExpr.Case.When(
                        SqlExpr.Call.of(SqlFn.EQUAL, v,
                                new SqlExpr.FloatLit(0.0)),
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
    private static SqlExpr temporalCanon(SqlExpr v) {
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

    /**
     * The NUMERIC-VALUE canon (spec §3 pair rules): pure's numeric
     * equality is NON-TRANSITIVE (8 == 8D, 8D == 8.0, 8 != 8.0), so
     * one text cannot serve every pair — when a Decimal meets any
     * numeric, both sides compare by VALUE spelling (integral bare,
     * trailing zeros stripped); int×int and float×float compare their
     * EXACT canon; int×float is statically FALSE. This is the value
     * form: the exact canon with a terminal {@code .0} dropped.
     */
    public static @com.legend.Nullable SqlExpr numericValueCanon(SqlExpr v,
            Type t) {
        SqlExpr exact = scalarCanon(v, t);
        if (exact == null) {
            return null;
        }
        // FULL value normalization regardless of the side's spelling
        // channel: strip ALL trailing fractional zeros, then a bare
        // terminal dot — '353791.470'→'353791.47', '8.0'→'8',
        // '4.4'→'4.4' (the decimal canon applied to any numeric text)
        return stripDot(stripTrailingZeros(exact), "");
    }

    /** A TERMINAL dot rewrites to {@code replacement} ('' = integral
     * bare for Decimal; '.0' keeps one decimal for Float). */
    private static SqlExpr stripDot(SqlExpr text, String replacement) {
        return SqlExpr.Call.of(SqlFn.REGEXP_REPLACE, text,
                new SqlExpr.StringLit("\\.$"),
                new SqlExpr.StringLit(replacement));
    }
}
