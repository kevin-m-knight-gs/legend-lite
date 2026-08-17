// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.lowering;

import com.legend.compiler.element.type.Type;
import com.legend.sql.DateFmt;
import com.legend.sql.OutputCol;
import com.legend.sql.SqlAgg;
import com.legend.sql.SqlExpr;
import com.legend.sql.SqlFn;
import com.legend.sql.SqlSelect;
import com.legend.sql.SqlSource;
import com.legend.sql.SqlType;

import java.util.ArrayList;
import java.util.List;

/**
 * The RENDER phase (F4.1/F4.2, foundations Phase 4): result text is a
 * PLAN PROJECTION the database executes — Java never touches the value
 * bytes (the graph-fetch precedent, generalized; charter clauses 1–3:
 * headers/envelopes come from typed plan facts, only the LiteralFold-
 * admitted kinds may render in Java, everything else is byte transport).
 *
 * <p>This first form is the engine's {@code meta::relational::tests::
 * csv::toCSV} text (helperFunctions.pure:198-232), constructed in SQL:
 * <pre>
 *   header-names(each toCSVString)->joinStrings(',') + '\n'
 *   + rows(cells joined ',')->joinStrings('', '\n', '\n')
 * </pre>
 * Cell rule ({@code toCSVString}): a NULL cell renders {@code ''}
 * ({@code 'TDSNull'} under renderTdsNull); a date-kinded cell formats
 * {@code yyyy-MM-dd} (no escape — the engine escapes only the datetime
 * branch); a datetime formats {@code yyyy-MM-dd HH:mm:ss} then escapes;
 * everything else is {@code toString()->escapeCSVString()} with
 * RFC4180 double-quote escaping. The escape rule is spelled ONCE below
 * — the header goes through the SAME SQL expression over literal names
 * so no second copy exists in Java.
 */
final class Render {

    private Render() {
    }

    /** The Lowerer's toCSV dispatch target: arity/format admission +
     *  typed-column collection + the render wrap. The 4-arg overload
     *  accepts only the DEFAULT format pair (SimpleDateTimeFormat /
     *  ISO8601DateFormat, as the zero-arg calls or their folded
     *  '%t{...}' literals); anything else stays loud. */
    static SqlExpr lowerToCsv(
            com.legend.compiler.spec.typed.TypedNativeCall tc,
            java.util.function.Function<
                    com.legend.compiler.spec.typed.TypedSpec, SqlSelect> relation,
            String alias) {
        boolean renderTdsNull = switch (tc.args().size()) {
            case 1 -> false;
            case 2, 4 -> {
                com.legend.compiler.spec.typed.TypedSpec last =
                        tc.args().get(tc.args().size() - 1);
                if (!(last instanceof
                        com.legend.compiler.spec.typed.TypedCBoolean b)) {
                    throw new com.legend.error.NotImplementedException(
                            "toCSV: renderTdsNull must be a literal");
                }
                if (tc.args().size() == 4
                        && (!isDefaultCsvFormat(tc.args().get(1),
                                "%t{yyyy-MM-dd HH:mm:ss}")
                            || !isDefaultCsvFormat(tc.args().get(2),
                                "%t{yyyy-MM-dd}"))) {
                    throw new com.legend.error.NotImplementedException(
                            "toCSV: only the default date formats are lowered");
                }
                yield b.value();
            }
            default -> throw new com.legend.error.NotImplementedException(
                    "toCSV: unexpected arity " + tc.args().size());
        };
        SqlSelect inner = relation.apply(tc.args().get(0));
        java.util.Map<String, Type.Column> byName = new java.util.HashMap<>();
        if (tc.args().get(0).info().type() instanceof Type.RelationType rt) {
            for (Type.Column col : rt.columns()) {
                byName.put(col.name(), col);
            }
        }
        List<Type.Column> relCols = inner.outputs().stream()
                .map(oc -> {
                    Type.Column t = byName.get(oc.name());
                    if (t == null) {
                        throw new IllegalStateException("toCSV: output column '"
                                + oc.name() + "' has no typed relation column");
                    }
                    return t;
                }).toList();
        return new SqlExpr.ScalarSubquery(
                csv(inner, relCols, renderTdsNull, alias));
    }

    /** The toCSV default-format pair — the zero-arg format fns or their
     *  folded literal text. */
    private static boolean isDefaultCsvFormat(
            com.legend.compiler.spec.typed.TypedSpec arg, String pattern) {
        if (arg instanceof com.legend.compiler.spec.typed.TypedCString cs) {
            return pattern.equals(cs.value());
        }
        return arg instanceof com.legend.compiler.spec.typed.TypedNativeCall f
                && f.args().isEmpty()
                && (f.callee().qualifiedName().equals(
                        "meta::pure::functions::date::SimpleDateTimeFormat")
                        ? pattern.startsWith("%t{yyyy-MM-dd HH")
                        : f.callee().qualifiedName().equals(
                                "meta::pure::functions::date::ISO8601DateFormat")
                                && pattern.equals("%t{yyyy-MM-dd}"));
    }

    /** The whole toCSV text as a one-column scalar select over the
     *  relation plan. {@code colTypes} are the relation's PURE column
     *  types in output order (dates dispatch by KIND — F5.4: the kind
     *  is a typed fact, never a value sniff). */
    static SqlSelect csv(SqlSelect inner, List<Type.Column> relCols,
            boolean renderTdsNull, String rowAlias) {
        List<OutputCol> cols = inner.outputs();
        if (cols.size() != relCols.size()) {
            throw new IllegalStateException("toCSV: " + cols.size()
                    + " output columns vs " + relCols.size()
                    + " typed columns");
        }
        // ORDER: the engine renders rows in RESULT order. An ordered
        // inner select keeps its order INSIDE the aggregate; only plain
        // output-column keys can hoist — anything else is a loud wall,
        // never a silently unordered render.
        String aggAlias = rowAlias + "_a";
        if (cols.isEmpty()) {
            throw new IllegalStateException("toCSV over a zero-column relation");
        }
        // the per-row line: cells joined ','
        SqlExpr line = cell(new SqlExpr.Column(rowAlias, cols.get(0).name()),
                relCols.get(0), cols.get(0).type(), renderTdsNull);
        for (int i = 1; i < cols.size(); i++) {
            line = cat(line, new SqlExpr.StringLit(","),
                    cell(new SqlExpr.Column(rowAlias, cols.get(i).name()),
                            relCols.get(i), cols.get(i).type(),
                            renderTdsNull));
        }
        List<SqlSelect.Projection> rowProjs = new ArrayList<>();
        List<OutputCol> rowOuts = new ArrayList<>();
        rowProjs.add(new SqlSelect.Projection(line, "_csv_line"));
        rowOuts.add(new OutputCol("_csv_line", SqlType.Scalar.VARCHAR, false));
        List<SqlSelect.SortKey> aggOrder = hoistOrder(inner, cols,
                rowAlias, aggAlias, new Object[] {rowProjs, rowOuts});
        // the header line: names through the SAME escape expression
        SqlExpr header = escapeCsv(new SqlExpr.StringLit(cols.get(0).name()));
        for (int i = 1; i < cols.size(); i++) {
            header = cat(header, new SqlExpr.StringLit(","),
                    escapeCsv(new SqlExpr.StringLit(cols.get(i).name())));
        }
        SqlExpr nl = new SqlExpr.StringLit("\n");
        // rows->joinStrings('', '\n', '\n'): string_agg(line, '\n') with
        // the hoisted order; ZERO rows aggregate to NULL -> '' (the
        // engine's empty joinStrings is prefix+suffix)
        SqlExpr rowsJoined = SqlExpr.Call.of(SqlFn.COALESCE,
                new SqlAgg.Reducer(SqlAgg.Fn.STRING_AGG,
                        List.of(new SqlExpr.Column(aggAlias, "_csv_line"), nl),
                        false, aggOrder),
                new SqlExpr.StringLit(""));
        SqlExpr text = cat(header, nl, rowsJoined, nl);
        SqlSelect rows = SqlSelect.starOf(
                        new SqlSource.Subselect(inner, rowAlias, null))
                .withProjections(rowProjs, rowOuts);
        return SqlSelect.starOf(new SqlSource.Subselect(rows, aggAlias, null))
                .withProjections(
                        List.of(new SqlSelect.Projection(text, "csv")),
                        List.of(new OutputCol("csv",
                                SqlType.Scalar.VARCHAR, false)));
    }

    /** The Lowerer's relation-toString dispatch target (engine
     *  toString.pure:19-35): the '#TDS' text form, built by the DB.
     *  typesAndMuls=true stays loud until a demand names it. */
    static SqlExpr lowerToString(
            com.legend.compiler.spec.typed.TypedNativeCall tc,
            java.util.function.Function<
                    com.legend.compiler.spec.typed.TypedSpec, SqlSelect> relation,
            String alias) {
        if (tc.args().size() == 2
                && (!(tc.args().get(1) instanceof
                        com.legend.compiler.spec.typed.TypedCBoolean b)
                        || b.value())) {
            throw new com.legend.error.NotImplementedException(
                    "relation toString(typesAndMuls) is lowered only for"
                    + " the literal-false form");
        }
        SqlSelect inner = relation.apply(tc.args().get(0));
        java.util.Map<String, Type.Column> byName = new java.util.HashMap<>();
        if (tc.args().get(0).info().type() instanceof Type.RelationType rt) {
            for (Type.Column col : rt.columns()) {
                byName.put(col.name(), col);
            }
        }
        List<Type.Column> relCols = inner.outputs().stream()
                .map(oc -> {
                    Type.Column t = byName.get(oc.name());
                    if (t == null) {
                        throw new IllegalStateException("toString: output"
                                + " column '" + oc.name()
                                + "' has no typed relation column");
                    }
                    return t;
                }).toList();
        return new SqlExpr.ScalarSubquery(
                tdsString(inner, relCols, alias));
    }

    /** The '#TDS' text (engine toString.pure:24-35): {@code #TDS\n} +
     *  {@code '   '} + names (quoted unless simple:
     *  {@code ^[a-zA-Z0-9_]+$} or already quote-bearing) + one
     *  {@code '   '}-prefixed line per row + {@code \n#}; empty
     *  relation = a blank rows segment. Cell = the engine's {@code s()}:
     *  NULL prints {@code null}, a String containing '{'/'[' quotes
     *  with backslash/quote escaping, everything else is the pure print
     *  form. Header names are typed plan facts (charter clause 1). */
    static SqlSelect tdsString(SqlSelect inner, List<Type.Column> relCols,
            String rowAlias) {
        List<OutputCol> cols = inner.outputs();
        String aggAlias = rowAlias + "_a";
        if (cols.isEmpty()) {
            throw new IllegalStateException(
                    "toString over a zero-column relation");
        }
        StringBuilder hdr = new StringBuilder("   ");
        for (int i = 0; i < cols.size(); i++) {
            String name = cols.get(i).name().trim();
            boolean simple = name.startsWith("'")
                    || name.matches("^[a-zA-Z0-9_]+$");
            if (i > 0) {
                hdr.append(',');
            }
            hdr.append(simple ? name : "'" + name + "'");
        }
        SqlExpr line = new SqlExpr.StringLit("   ");
        for (int i = 0; i < cols.size(); i++) {
            SqlExpr c = tdsCell(
                    new SqlExpr.Column(rowAlias, cols.get(i).name()),
                    relCols.get(i).type(), cols.get(i).type());
            line = i == 0 ? cat(line, c)
                    : cat(line, new SqlExpr.StringLit(","), c);
        }
        List<SqlSelect.Projection> rowProjs = new ArrayList<>();
        List<OutputCol> rowOuts = new ArrayList<>();
        rowProjs.add(new SqlSelect.Projection(line, "_tds_line"));
        rowOuts.add(new OutputCol("_tds_line", SqlType.Scalar.VARCHAR, false));
        SqlExpr nl = new SqlExpr.StringLit("\n");
        SqlExpr rowsJoined = SqlExpr.Call.of(SqlFn.COALESCE,
                new SqlAgg.Reducer(SqlAgg.Fn.STRING_AGG,
                        List.of(new SqlExpr.Column(aggAlias, "_tds_line"), nl),
                        false, hoistOrder(inner, cols, rowAlias, aggAlias,
                                new Object[] {rowProjs, rowOuts})),
                new SqlExpr.StringLit(""));
        SqlExpr text = cat(new SqlExpr.StringLit("#TDS\n"
                        + hdr + "\n"), rowsJoined,
                new SqlExpr.StringLit("\n#"));
        SqlSelect rows = SqlSelect.starOf(
                        new SqlSource.Subselect(inner, rowAlias, null))
                .withProjections(rowProjs, rowOuts);
        return SqlSelect.starOf(new SqlSource.Subselect(rows, aggAlias, null))
                .withProjections(
                        List.of(new SqlSelect.Projection(text, "tds")),
                        List.of(new OutputCol("tds",
                                SqlType.Scalar.VARCHAR, false)));
    }

    /** The engine {@code s()} cell (s.pure:23-38). */
    private static SqlExpr tdsCell(SqlExpr c, Type t, SqlType slot) {
        boolean variant = t instanceof Type.ClassType vc
                && com.legend.compiler.element.type.PlatformTypes
                        .isVariant(vc);
        SqlExpr rendered;
        if (variant) {
            rendered = cat(new SqlExpr.StringLit("'"),
                    SqlExpr.Call.of(SqlFn.REPLACE,
                            SqlExpr.Call.of(SqlFn.REPLACE,
                                    Scalars.pureToString(t, c),
                                    new SqlExpr.StringLit("\\"),
                                    new SqlExpr.StringLit("\\\\")),
                            new SqlExpr.StringLit("'"),
                            new SqlExpr.StringLit("\\'")),
                    new SqlExpr.StringLit("'"));
        } else if (t == Type.Primitive.STRING) {
            SqlExpr braced = SqlExpr.Call.of(SqlFn.OR,
                    contains(c, "{"), contains(c, "["));
            rendered = new SqlExpr.Case(List.of(new SqlExpr.Case.When(braced,
                    cat(new SqlExpr.StringLit("'"),
                            SqlExpr.Call.of(SqlFn.REPLACE,
                                    SqlExpr.Call.of(SqlFn.REPLACE, c,
                                            new SqlExpr.StringLit("\\"),
                                            new SqlExpr.StringLit("\\\\")),
                                    new SqlExpr.StringLit("'"),
                                    new SqlExpr.StringLit("\\'")),
                            new SqlExpr.StringLit("'")))),
                    c);
        } else if (t == Type.Primitive.STRICT_DATE
                || (t == Type.Primitive.DATE
                        && slot == SqlType.Scalar.DATE)) {
            rendered = SqlExpr.Call.of(SqlFn.STRFTIME, c,
                    new SqlExpr.FormatLit(DateFmt.DATE));
        } else {
            rendered = Scalars.pureToString(t, c);
        }
        return new SqlExpr.Case(List.of(new SqlExpr.Case.When(
                SqlExpr.Call.of(SqlFn.IS_NULL, c),
                new SqlExpr.StringLit(variant ? "'null'" : "null"))),
                rendered);
    }

    /** write(rel, accessor)'s observable: the COUNT of rows written
     *  (a TDS-relation accessor destination has no physical table — the
     *  write is vacuous, only the count is observable). */
    static SqlExpr writeCount(SqlSelect src, String alias) {
        SqlSelect count = SqlSelect.starOf(
                        new SqlSource.Subselect(src, alias, null))
                .withProjections(List.of(new SqlSelect.Projection(
                                new SqlAgg.Reducer(SqlAgg.Fn.COUNT,
                                        List.of(), false, List.of()), null)),
                        List.of(new OutputCol("count",
                                SqlType.Scalar.BIGINT, false)));
        return new SqlExpr.ScalarSubquery(count);
    }

    /** RESULT-ORDER hoist: an ordered inner select keeps its order
     *  INSIDE the aggregate; only plain output-column keys hoist —
     *  anything else walls loudly, never a silently unordered render.
     *  {@code carry} (when non-null) receives the pass-through
     *  projections/outputs the row subselect must carry. */
    @SuppressWarnings("unchecked")
    private static List<SqlSelect.SortKey> hoistOrder(SqlSelect inner,
            List<OutputCol> cols, String rowAlias, String aggAlias,
            Object @com.legend.Nullable [] carry) {
        List<SqlSelect.SortKey> aggOrder = new ArrayList<>();
        int ord = 0;
        for (SqlSelect.SortKey k : inner.orderBy()) {
            String out = k.outputName() != null ? k.outputName()
                    : k.expr() instanceof SqlExpr.Column c ? c.name() : null;
            OutputCol src = out == null ? null : cols.stream()
                    .filter(oc -> oc.name().equals(out)).findFirst()
                    .orElse(null);
            if (src == null) {
                throw new com.legend.error.NotImplementedException(
                        "render over an ordered relation whose sort key is"
                        + " not an output column");
            }
            String oname = "_ord" + ord++;
            if (carry != null) {
                ((List<SqlSelect.Projection>) carry[0]).add(
                        new SqlSelect.Projection(
                                new SqlExpr.Column(rowAlias, src.name()),
                                oname));
                ((List<OutputCol>) carry[1]).add(
                        new OutputCol(oname, src.type(), src.nullable()));
            }
            aggOrder.add(new SqlSelect.SortKey(
                    new SqlExpr.Column(aggAlias, oname), k.ascending(),
                    k.nullOrder(), null));
        }
        return aggOrder;
    }

    /** One cell — the engine's {@code toCSVString} dispatch, typed.
     *  (A count-based render for to-many cells was tried and DELETED:
     *  zero live corpus firings, and its LIST_GET/LIST_LENGTH emissions
     *  violate the carrier-purity tenet — when a to-many cell demand
     *  appears it gets a SEMANTIC node with dialect strategies, per
     *  CARRIER_REDESIGN.md tenet #1. The named residue is
     *  docs/CSV_DIFFERENTIAL.md mechanism 3.) */
    private static SqlExpr cell(SqlExpr c, Type.Column col,
            SqlType slot, boolean renderTdsNull) {
        Type t = col.type();
        SqlExpr rendered;
        if (t == Type.Primitive.STRICT_DATE
                || (t == Type.Primitive.DATE
                        && slot == SqlType.Scalar.DATE)) {
            // date-only branch: format WITHOUT escape (engine
            // formatDateTime escapes only the datetime arm). An ABSTRACT
            // Date over a DATE slot is a StrictDate READ (the engine's
            // relational read of a DATE column yields hasHour=false) —
            // the SLOT KIND is the typed fact, F5.4's rule.
            rendered = SqlExpr.Call.of(SqlFn.STRFTIME, c,
                    new SqlExpr.FormatLit(DateFmt.DATE));
        } else if (t == Type.Primitive.DATE) {
            // ABSTRACT Date over a TIMESTAMP slot: the engine's
            // formatDateTime rule is DEFINED OVER THE VALUE (hasHour) —
            // date-only values print yyyy-MM-dd, timed values print the
            // datetime form. The SQL spells the engine's own rule; the
            // one residue (a TRUE midnight DateTime under an abstract
            // Date slot prints date-only) is the F5.4 slot-erasure,
            // identical on the engine's own relational read-back of a
            // DATE-precision-less TIMESTAMP.
            SqlExpr timed = SqlExpr.Call.of(SqlFn.NOT_EQUAL,
                    SqlExpr.Call.of(SqlFn.STRFTIME, c,
                            new SqlExpr.FormatLit(DateFmt.TIME_ONLY)),
                    new SqlExpr.StringLit("00:00:00"));
            rendered = new SqlExpr.Case(List.of(new SqlExpr.Case.When(timed,
                    escapeCsv(SqlExpr.Call.of(SqlFn.STRFTIME, c,
                            new SqlExpr.FormatLit(DateFmt.CSV_DATETIME))))),
                    SqlExpr.Call.of(SqlFn.STRFTIME, c,
                            new SqlExpr.FormatLit(DateFmt.DATE)));
        } else if (t == Type.Primitive.DATE_TIME
                || t == Type.Primitive.LATEST_DATE) {
            // the DateTime kinds: datetime spelling
            rendered = escapeCsv(SqlExpr.Call.of(SqlFn.STRFTIME, c,
                    new SqlExpr.FormatLit(DateFmt.CSV_DATETIME)));
        } else {
            rendered = escapeCsv(Scalars.pureToString(t, c));
        }
        return new SqlExpr.Case(List.of(new SqlExpr.Case.When(
                SqlExpr.Call.of(SqlFn.IS_NULL, c),
                new SqlExpr.StringLit(renderTdsNull ? "TDSNull" : ""))),
                rendered);
    }

    /** THE RFC4180 escape rule ({@code escapeCSVString}), spelled once:
     *  quote when the text contains comma, quote, LF or CR; embedded
     *  quotes double. */
    private static SqlExpr escapeCsv(SqlExpr s) {
        SqlExpr needs = or(contains(s, ","), or(contains(s, "\""),
                or(contains(s, "\n"), contains(s, "\r"))));
        return new SqlExpr.Case(List.of(new SqlExpr.Case.When(needs,
                cat(new SqlExpr.StringLit("\""),
                        SqlExpr.Call.of(SqlFn.REPLACE, s,
                                new SqlExpr.StringLit("\""),
                                new SqlExpr.StringLit("\"\"")),
                        new SqlExpr.StringLit("\"")))),
                s);
    }

    private static SqlExpr contains(SqlExpr s, String needle) {
        return SqlExpr.Call.of(SqlFn.GREATER,
                SqlExpr.Call.of(SqlFn.STRPOS, s,
                        new SqlExpr.StringLit(needle)),
                new SqlExpr.IntLit(0));
    }

    private static SqlExpr or(SqlExpr a, SqlExpr b) {
        return SqlExpr.Call.of(SqlFn.OR, a, b);
    }

    private static SqlExpr cat(SqlExpr... parts) {
        SqlExpr out = parts[0];
        for (int i = 1; i < parts.length; i++) {
            out = SqlExpr.Call.of(SqlFn.CONCAT, out, parts[i]);
        }
        return out;
    }
}
