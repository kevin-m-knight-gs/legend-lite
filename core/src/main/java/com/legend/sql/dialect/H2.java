// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.sql.dialect;

import com.legend.sql.SqlExpr;
import com.legend.sql.SqlFn;

import java.util.List;

/**
 * The H2 EXECUTION dialect (H2_BACKEND.md §12 step 8) — a sibling of
 * {@link DuckDb} extending {@link AnsiSqlRenderer}, NEVER
 * {@link EngineStyleH2} (whose output is byte-pinned to the engine's
 * golden TEXT and stays quarantined). This dialect's contract is
 * EXECUTABLE H2 2.1.214 SQL: every spelling here was probed against the
 * real jar or read from its function catalog; anything H2 cannot express
 * inherits the base renderer's LOUD walls and graduates into the
 * declared-gap registry (§9), never a silent wrong answer.
 *
 * <p>Capability notes (probed, see the doc's verification addendum):
 * native QUALIFY; NO correlated table-function arguments in any version
 * through 2.4.240 (LATERAL absent — the collection-carrier family walls);
 * JSON array indexing only at 2.2+ (no object-field navigation in any
 * version — the engine uses a Java UDF there, a route this dialect bans).
 */
public final class H2 extends AnsiSqlRenderer {

    public H2() {
        super(Lexicon.H2, TypeNames.H2, Spellings.H2);
    }

    @Override
    protected boolean supportsQualify() {
        return true;
    }

    @Override
    public boolean rawH2IsNative() {
        return true;
    }

    @Override
    protected String call(SqlExpr.Call c, int parentPrec) {
        List<SqlExpr> a = c.args();
        // integer division: the base renderer spells DuckDB's `a // b`,
        // and `//` is a LINE COMMENT on H2 — `SELECT 7 // 2` returns 7
        // silently (H2_BACKEND.md H5.2). H2's `/` over INT operands IS
        // integer division; CAST pins the operand type against widening.
        if (c.fn() == SqlFn.INT_DIVIDE) {
            return "CAST(" + expr(a.get(0), 6) + " / " + expr(a.get(1), 6)
                    + " AS BIGINT)";
        }
        // date arithmetic: the ANSI base spells DuckDB's interval idiom
        // `d + to_days(n)`; H2's form is dateadd(UNIT, n, d) — probed
        // 2.1.214 for DAY/MONTH/MICROSECOND, negative amounts, and
        // CAST-around composition.
        if (c.fn() == SqlFn.ADD_INTERVAL) {
            return "dateadd(" + dateUnit(((SqlExpr.StringLit) a.get(0))
                    .value()) + ", " + expr(a.get(1), 0) + ", "
                    + expr(a.get(2), 0) + ")";
        }
        // H2 has no date_part — the SQL-standard extract(UNIT FROM x)
        // form (probed 2.1.214: HOUR over TIMESTAMP and TIME)
        if (c.fn() == SqlFn.EXTRACT) {
            return "extract(" + ((SqlExpr.StringLit) a.get(0)).value()
                    .toUpperCase(java.util.Locale.ROOT) + " FROM "
                    + expr(a.get(1), 0) + ")";
        }
        // H2 has no starts_with — LEFT/CHAR_LENGTH equality (probed,
        // incl. '%' in the prefix: no LIKE-escaping hazard)
        if (c.fn() == SqlFn.STARTS_WITH) {
            return "(LEFT(" + expr(a.get(0), 0) + ", CHAR_LENGTH("
                    + expr(a.get(1), 0) + ")) = " + expr(a.get(1), 0) + ")";
        }
        // split_part (R5c): H2 has no token pick — the probed EXACT
        // spelling (empty tokens KEPT, missing token -> '', matching
        // DuckDB split_part on all 6 probed edges): separator-count CASE
        // guard + REGEXP_REPLACE with \Q\E-quoted separator (H2 regexes
        // are java.util.regex). Literal single-char separator + literal
        // positive index only — the strategy rule emits nothing else.
        if (c.fn() == SqlFn.SPLIT_PART
                && a.get(1) instanceof SqlExpr.StringLit sep
                && sep.value().length() == 1
                && a.get(2) instanceof SqlExpr.IntLit ix && ix.value() >= 1) {
            String str = expr(a.get(0), 0);
            String sepLit = stringLit(sep.value());
            String q = "\\Q" + sep.value() + "\\E";
            String cls = "[^" + q + "]*";
            String pat = stringLit("^(?:" + cls + q + "){"
                    + (ix.value() - 1) + "}(" + cls + ").*$");
            return "CASE WHEN (CHAR_LENGTH(" + str
                    + ") - CHAR_LENGTH(REPLACE(" + str + ", " + sepLit
                    + ", ''))) < " + (ix.value() - 1)
                    + " THEN '' ELSE REGEXP_REPLACE(" + str + ", " + pat
                    + ", '$1') END";
        }
        return super.call(c, parentPrec);
    }

    /** DurationUnit interval-fn name -> H2 dateadd unit keyword (the
     * engine's extensionDefaults mapToDBUnitType; duplicated from the
     * QUARANTINED EngineStyleH2 deliberately — semantic unit data, not
     * golden formatting). */
    private static String dateUnit(String unitFn) {
        return switch (unitFn) {
            case "to_years" -> "YEAR";
            case "to_months" -> "MONTH";
            case "to_weeks" -> "WEEK";
            case "to_days" -> "DAY";
            case "to_hours" -> "HOUR";
            case "to_minutes" -> "MINUTE";
            case "to_seconds" -> "SECOND";
            case "to_milliseconds" -> "MILLISECOND";
            case "to_microseconds" -> "MICROSECOND";
            default -> throw new DialectCapability(
                    "interval unit '" + unitFn
                    + "' reached a dialect without a dateadd unit");
        };
    }

    /** Collection VALUES ride the JSON carrier (§2b, probed 2.1.214):
     * {@code JSON_ARRAY(e1, ..., eN NULL ON NULL)} — NULL ON NULL keeps
     * DuckDB's [null] element semantics (default ABSENT drops them);
     * dates serialize as ISO strings. The executor's unwrap parses the
     * JSON text back to the element list. */
    @Override
    protected String arrayLit(java.util.List<SqlExpr> elements) {
        if (elements.isEmpty()) {
            return "JSON_ARRAY()";
        }
        return "JSON_ARRAY(" + elements.stream()
                .map(e -> expr(e, 0))
                .collect(java.util.stream.Collectors.joining(", "))
                + " NULL ON NULL)";
    }

    /** Scalar -> variant JSON: {@code CAST(x AS JSON)} — probed
     * 2.1.214: numbers stay JSON numbers, booleans true/false, strings
     * quote; exact toVariant semantics. */
    @Override
    protected String variantConstruct(List<SqlExpr> a) {
        return "CAST(" + expr(a.get(0), 0) + " AS JSON)";
    }

    /** H2's row-order pseudo-column (probed 2.1.214: bare and inside
     * STRING_AGG's ORDER BY). */
    @Override
    protected String rowOrderColumn() {
        return "_ROWID_";
    }

    /** FORMATDATETIME wants java.time patterns, not %-codes (probed
     * 2.1.214 byte-equal to DuckDB strftime on iso-micro, date, month/
     * weekday names, 12-hour + AM/PM). Literal text quotes ALWAYS —
     * the 'T' separator would otherwise parse as an era directive; the
     * enclosing {@code stringLit} doubles the quotes for SQL. */
    @Override
    protected String formatText(SqlExpr.FormatLit fl) {
        StringBuilder out = new StringBuilder();
        for (com.legend.sql.DateFmt p : fl.parts()) {
            out.append(switch (p) {
                case com.legend.sql.DateFmt.Text t ->
                        "'" + t.s().replace("'", "''") + "'";
                case com.legend.sql.DateFmt.Part part -> switch (part) {
                    case YEAR4 -> "yyyy";
                    case MONTH2 -> "MM";
                    case DAY2 -> "dd";
                    case HOUR2 -> "HH";
                    case MIN2 -> "mm";
                    case SEC2 -> "ss";
                    case SUBSEC_MICRO -> "SSSSSS";
                    // DuckDB %g trims trailing zeros — java.time has no
                    // trim token; the shape stays loud until witnessed.
                    case SUBSEC_MIN -> throw new DialectCapability(
                            "minimal-fraction date format reached a"
                            + " dialect without a trim-zeros token");
                    case MONTH_ABBREV -> "MMM";
                    case MONTH_NAME -> "MMMM";
                    case WEEKDAY_NAME -> "EEEE";
                    case HOUR12 -> "hh";
                    case HOUR12_NOPAD -> "h";
                    case AMPM -> "a";
                };
            });
        }
        return out.toString();
    }

    /** DuckDB's QUANTILE_CONT(v, q) has no H2 spelling — the SQL-standard
     * inverse-distribution form PERCENTILE_CONT(q) WITHIN GROUP (ORDER BY
     * v), probed 2.1.214. The LIST collect rides the JSON carrier:
     * {@code JSON_ARRAYAGG(x [ORDER BY ... NULLS ...] NULL ON NULL)} —
     * probed grammar (order clause BEFORE the null clause), zero rows ->
     * NULL and [null] element parity with DuckDB list(). NULLS placement
     * renders EXPLICITLY (the base reducer drops nullOrder). Other
     * reducers render on the base. */
    @Override
    protected String reducer(com.legend.sql.SqlAgg.Reducer r) {
        if (r.fn() == com.legend.sql.SqlAgg.Fn.QUANTILE_CONT && r.args().size() == 2
                && !r.distinct() && r.orderBy().isEmpty()) {
            return "PERCENTILE_CONT(" + expr(r.args().get(1), 0)
                    + ") WITHIN GROUP (ORDER BY " + expr(r.args().get(0), 0)
                    + ")";
        }
        if (r.fn() == com.legend.sql.SqlAgg.Fn.LIST && r.args().size() == 1
                && !r.distinct()) {
            StringBuilder sb = new StringBuilder("JSON_ARRAYAGG(")
                    .append(expr(r.args().get(0), 0));
            if (!r.orderBy().isEmpty()) {
                sb.append(" ORDER BY ");
                for (int i = 0; i < r.orderBy().size(); i++) {
                    var k = r.orderBy().get(i);
                    if (i > 0) {
                        sb.append(", ");
                    }
                    sb.append(expr(k.expr(), 0))
                            .append(k.ascending() ? " ASC" : " DESC");
                    if (k.nullOrder() != null) {
                        sb.append(k.nullOrder()
                                == com.legend.sql.SqlSelect.SortKey
                                        .NullOrder.NULLS_FIRST
                                ? " NULLS FIRST" : " NULLS LAST");
                    }
                }
            }
            return sb.append(" NULL ON NULL)").toString();
        }
        return super.reducer(r);
    }

    /** Banker's ROUND (probed on 2.1.214 against DuckDB ROUND_EVEN over
     * 2.5/3.5/-2.5/0.125/0.135/2.4 — all six equal): exact-.5 fractions
     * steer to the even integer of the scaled value, everything else is
     * plain ROUND. */
    @Override
    protected String roundHalfEven(List<SqlExpr> a) {
        String p = a.size() == 1 ? "1"
                : "POWER(10, " + expr(a.get(1), 0) + ")";
        String scaled = "(" + expr(a.get(0), 6) + " * " + p + ")";
        return "(CASE WHEN " + scaled + " - FLOOR(" + scaled + ") = 0.5"
                + " THEN (CASE WHEN MOD(CAST(FLOOR(" + scaled
                + ") AS BIGINT), 2) = 0 THEN FLOOR(" + scaled
                + ") ELSE FLOOR(" + scaled + ") + 1 END)"
                + " ELSE ROUND(" + scaled + ") END / " + p + ")";
    }

    /** SQL-standard constructor, probed on 2.1.214:
     * {@code JSON_OBJECT('k': v, ...)}. The kv list alternates
     * key-expression, value-expression. */
    @Override
    protected String jsonObject(SqlExpr.JsonObject j) {
        StringBuilder sb = new StringBuilder("JSON_OBJECT(");
        List<SqlExpr> kv = j.kv();
        for (int i = 0; i + 1 < kv.size(); i += 2) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(expr(kv.get(i), 0)).append(": ")
                    .append(expr(kv.get(i + 1), 0));
        }
        return sb.append(')').toString();
    }

    /** SQL-standard aggregate, probed on 2.1.214:
     * {@code COALESCE(JSON_ARRAYAGG(v ORDER BY ...), JSON '[]')} — the
     * COALESCE keeps the graph contract's empty-array-over-zero-rows. */
    @Override
    protected String jsonArrayAgg(SqlExpr.JsonArrayAgg j) {
        StringBuilder sb = new StringBuilder("COALESCE(JSON_ARRAYAGG(")
                .append(expr(j.value(), 0));
        if (!j.orderKeys().isEmpty()) {
            sb.append(" ORDER BY ");
            for (int i = 0; i < j.orderKeys().size(); i++) {
                var k = j.orderKeys().get(i);
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(expr(k.expr(), 0))
                        .append(k.desc() ? " DESC" : " ASC")
                        .append(" NULLS LAST");
            }
        }
        return sb.append("), JSON '[]')").toString();
    }

    /** H2 hands JSON values back as {@code byte[]} (UTF-8 text) — the
     * canonical envelope representation is the STRING (probed 2.1.214;
     * H2_BACKEND.md §12 step 11's first codec row). */
    @Override
    public @com.legend.Nullable Object normalize(@com.legend.Nullable Object jdbcValue,
            com.legend.sql.@com.legend.Nullable SqlType type) {
        if (jdbcValue instanceof byte[] b
                && type == com.legend.sql.SqlType.Scalar.JSON) {
            return new String(b, java.nio.charset.StandardCharsets.UTF_8);
        }
        return jdbcValue;
    }
}
