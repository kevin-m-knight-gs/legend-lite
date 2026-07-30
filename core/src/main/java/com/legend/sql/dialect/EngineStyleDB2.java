// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.sql.dialect;

import com.legend.sql.SqlExpr;
import com.legend.sql.SqlType;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The engine's DB2 SQL text (toSQLString parity) — shares the
 * engine-style select/alias plan with {@link EngineStyleH2} and respells
 * only the dialect-owned function forms the DB2 goldens pin:
 * infix {@code concat}, bare {@code trim}, {@code varchar(16000)} string
 * casts, {@code reverse}. Every unpinned form inherits the H2 spelling —
 * an unmatched golden stays an HONEST text FAIL, never a guessed
 * respell.
 */
public class EngineStyleDB2 extends EngineStyleH2 {

    public EngineStyleDB2() {
    }

    public EngineStyleDB2(boolean quoteIdentifiers, String timeZone) {
        super(quoteIdentifiers, timeZone);
    }

    /** DB2 plan goldens wrap a top-level WHERE conjunction in one extra
     * paren pair ({@code where ((A) and (B))}). */
    @Override
    protected String whereSql(SqlExpr w) {
        return w instanceof SqlExpr.Call c
                && c.fn() == com.legend.sql.SqlFn.AND
                ? "(" + expr(w, 0) + ")" : expr(w, 0);
    }

    /** DB2 QUOTES boolean placeholders — the mapped case-expr yields
     * {@code 'true'}/{@code 'false'} strings. */
    @Override
    protected String holderArgs(SqlExpr.PlanParam.Kind k) {
        return k == SqlExpr.PlanParam.Kind.BOOLEAN
                ? "\"\\'\" \"\\'\" {}" : super.holderArgs(k);
    }

    /** DB2 has no group-by-alias: every key spells the PHYSICAL
     * expression (testToSQLStringWithAggregationDB2 golden —
     * {@code group by "root".FIRSTNAME}); the engine resolves aliases
     * before rendering for DB2 where H2 keeps the TDS alias text. */
    @Override
    protected String groupKey(com.legend.sql.SqlSelect s, SqlExpr e) {
        return expr(e, 0);
    }

    @Override
    protected String call(SqlExpr.Call c, int parentPrec) {
        List<SqlExpr> a = c.args();
        return switch (c.fn()) {
            // DB2 string concatenation is the infix operator, the whole
            // chain parenthesized once: (a concat b concat c)
            case CONCAT -> "(" + flattenConcat(a).stream()
                    .map(x -> expr(x, 0))
                    .collect(Collectors.joining(" concat ")) + ")";
            case TRIM -> a.size() == 1
                    ? "trim(" + expr(a.get(0), 0) + ")"
                    : super.call(c, parentPrec);
            case REVERSE_STRING -> "reverse(" + expr(a.get(0), 0) + ")";
            // DB2 spells dayOfYear as the bare function
            // (db2Extension.pure:91 — 'dayofyear(%s)')
            case EXTRACT -> c.args().size() == 2
                    && c.args().get(0) instanceof SqlExpr.StringLit pt
                    && "doy".equals(pt.value())
                    ? "dayofyear(" + expr(c.args().get(1), 0) + ")"
                    : super.call(c, parentPrec);
            // DB2 wraps left/right in trim (db2Extension.pure:100/:115 —
            // 'trim(left(%s, %s))'/'trim(right(%s, %s))')
            case LEFT -> "trim(left(" + expr(a.get(0), 0) + ", "
                    + expr(a.get(1), 0) + "))";
            case RIGHT -> "trim(right(" + expr(a.get(0), 0) + ", "
                    + expr(a.get(1), 0) + "))";
            case TODAY -> "date(current date)";
            case LENGTH -> "CHARACTER_LENGTH(" + expr(a.get(0), 0)
                    + ",CODEUNITS32)";
            // firstDayOf* family: DB2 rebuilds the truncation from epoch
            // date(1) with labeled year/month arithmetic (per-unit golden
            // text, parens verbatim — quarter parenthesizes the YEARS
            // term, month/year do not)
            case DATE_TRUNC -> {
                if (a.size() == 2 && a.get(0) instanceof SqlExpr.StringLit u) {
                    String anchor = a.get(1) instanceof SqlExpr.Call tc
                            && tc.fn() == com.legend.sql.SqlFn.TODAY
                            ? "current date" : expr(a.get(1), 0);
                    String spelled = switch (u.value()) {
                        case "year" -> "date(1) + (year(" + anchor
                                + ")-1) YEARS";
                        case "month" -> "date(1) + (year(" + anchor
                                + ")-1) YEARS + (month(" + anchor
                                + ")-1) MONTHS";
                        case "quarter" -> "date(1) + ((year(" + anchor
                                + ")-1) YEARS) + (3 * QUARTER(" + anchor
                                + ") - 3) MONTHS";
                        default -> null;
                    };
                    if (spelled != null) {
                        yield spelled;
                    }
                }
                throw new IllegalStateException("date_trunc unit has no"
                        + " engine-DB2 spelling yet: " + a);
            }
            // DB2 interval arithmetic: x - 1 MONTHS / x + 3 DAYS (the
            // relative-date goldens) — the IR's ADD_INTERVAL
            // [unitFn, count, anchor] respells as infix labeled units
            case ADD_INTERVAL -> {
                if (a.size() == 3 && a.get(0) instanceof SqlExpr.StringLit u
                        && a.get(1) instanceof SqlExpr.IntLit n) {
                    String unit = switch (u.value()) {
                        case "to_years" -> "YEARS";
                        case "to_months" -> "MONTHS";
                        case "to_days" -> "DAYS";
                        case "to_hours" -> "HOURS";
                        case "to_minutes" -> "MINUTES";
                        case "to_seconds" -> "SECONDS";
                        default -> null;
                    };
                    long v = n.value();
                    if (unit == null && u.value().equals("to_weeks")) {
                        // DB2 has no WEEKS label — the engine spells
                        // weeks as days ('- 14 DAYS' for 2 weeks)
                        unit = "DAYS";
                        v = v * 7;
                    }
                    if (unit != null) {
                        yield expr(a.get(2), 0)
                                + (v < 0 ? " - " + (-v) : " + " + v)
                                + " " + unit;
                    }
                }
                throw new IllegalStateException("interval shape has no"
                        + " engine-DB2 spelling yet: " + a);
            }
            // parse-date family: DB2's to_date/timestamp_format take the
            // whole string with the Java-style pattern (no substring, no
            // space after the comma — the goldens' exact text)
            case STRPTIME -> {
                if (a.size() == 2 && a.get(1) instanceof SqlExpr.FormatLit fl) {
                    String java = db2Pattern(fl);
                    if (java != null) {
                        boolean dateOnly = !fl.parts()
                                .contains(com.legend.sql.DateFmt.Part.HOUR2);
                        yield (dateOnly ? "to_date(" : "timestamp_format(")
                                + expr(a.get(0), 0) + ",'" + java + "')";
                    }
                }
                throw new IllegalStateException("strptime format has no"
                        + " engine-DB2 spelling yet: " + a);
            }
            default -> super.call(c, parentPrec);
        };
    }

    /** TYPED format parts → DB2's pattern spelling; null when a part has
     * no mapping (the caller throws — never a silent fallback). */
    private static String db2Pattern(SqlExpr.FormatLit fl) {
        StringBuilder out = new StringBuilder();
        for (com.legend.sql.DateFmt d : fl.parts()) {
            switch (d) {
                case com.legend.sql.DateFmt.Text t -> out.append(t.s());
                case com.legend.sql.DateFmt.Part p -> {
                    String java = switch (p) {
                        case YEAR4 -> "yyyy";
                        case MONTH2 -> "MM";
                        case DAY2 -> "dd";
                        case HOUR2 -> "hh";
                        case MIN2 -> "mm";
                        case SEC2 -> "ss";
                        case SUBSEC_MIN -> "mmm";
                        default -> null;
                    };
                    if (java == null) {
                        return null;
                    }
                    out.append(java);
                }
            }
        }
        return out.toString();
    }

    @Override
    protected String variantAwareCast(SqlExpr.Cast c) {
        // DB2 string casts carry the engine's explicit width
        if (c.target() instanceof SqlType.Scalar s
                && s == SqlType.Scalar.VARCHAR) {
            return "cast(" + expr(c.value(), 0) + " as varchar(16000))";
        }
        // to_date/timestamp_format and infix interval arithmetic are
        // already date-typed — the IR's normalizing casts render bare
        // (the goldens carry no cast)
        if (c.target() instanceof SqlType.Scalar s2
                && (s2 == SqlType.Scalar.DATE || s2 == SqlType.Scalar.TIMESTAMP)
                && c.value() instanceof SqlExpr.Call pc
                && (pc.fn() == com.legend.sql.SqlFn.STRPTIME
                        || pc.fn() == com.legend.sql.SqlFn.ADD_INTERVAL)) {
            return expr(pc, 0);
        }
        return super.variantAwareCast(c);
    }

}
