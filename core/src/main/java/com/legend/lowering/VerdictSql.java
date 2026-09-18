// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.lowering;

import com.legend.sql.OutputCol;
import com.legend.sql.SqlAgg;
import com.legend.sql.SqlExpr;
import com.legend.sql.SqlFn;
import com.legend.sql.SqlQuery;
import com.legend.sql.SqlSelect;
import com.legend.sql.SqlSource;
import com.legend.sql.SqlType;
import com.legend.sql.SqlWith;

import java.util.ArrayList;
import java.util.List;

/**
 * DATABASE-MODE JUDGING, leg 3.1 (docs/DATABASE_MODE_HOMEWORK_2026_09_18.md
 * §4b): ONE statement decides an equality assert. Both sides are the
 * canon-wrapped side plans the verdict lane already lowers
 * ({@link CanonicalRenderSql#wrapWithCanon}); this class composes them
 * into
 *
 * <pre>
 * WITH __e AS (SELECT canon AS __c, row_number() OVER () AS __rn FROM (e) w),
 *      __a AS (...)
 * SELECT (frame(__e) IS NOT DISTINCT FROM frame(__a)) AS __verdict,
 *        frame(__e) AS __expected, frame(__a) AS __actual,
 *        CASE WHEN a null canon cell THEN 'null-canon-cell' END AS __unjudged
 * </pre>
 *
 * where {@code frame} is CANONICAL_FORM_SPEC's side framing in SQL — the
 * same rule {@code AssertVerdicts.frame} applied in Java: no element
 * {@code '[]'}, one element its bare text, many {@code '[a, b]'} in the
 * side's order (arrival order for an ordered side, canon-text order for a
 * multiset side). The verdict column can never be NULL (P-19: a NULL
 * verdict read as a pass was the highest-ranked risk); the evidence
 * columns ARE the failure message; {@code __unjudged} names a shape the
 * statement could not decide, and the caller FAILS the assert with it.
 * No Java compares a value.
 */
public final class VerdictSql {

    private VerdictSql() {
    }

    public static final String VERDICT = "__verdict";
    public static final String EXPECTED = "__expected";
    public static final String ACTUAL = "__actual";
    public static final String UNJUDGED = "__unjudged";

    /** One canon-wrapped side: the wrapped plan, the name of the canon
     * column that decides (one of {@code __canon<i>}), whether the side
     * is a collection, and whether its elements order by canon text (the
     * multiset forms) or by arrival (an ordered assert). */
    public record Side(SqlQuery wrapped, String canonColumn, boolean many,
            boolean byCanonText) {
    }

    private static final String C = "__c";
    private static final String RN = "__rn";

    /** The equality verdict statement over two framed sides. */
    public static SqlQuery equality(Side e, Side a) {
        List<SqlWith.Cte> ctes = List.of(
                new SqlWith.Cte("__e", canonRows(e)),
                new SqlWith.Cte("__a", canonRows(a)));
        SqlExpr fe = frame("__e", e);
        SqlExpr fa = frame("__a", a);
        SqlExpr verdict = SqlExpr.Call.of(SqlFn.NULL_SAFE_EQUAL, fe, fa);
        SqlExpr unjudged = new SqlExpr.Case(List.of(
                new SqlExpr.Case.When(
                        SqlExpr.Call.of(SqlFn.OR, nullCells("__e"), nullCells("__a")),
                        new SqlExpr.StringLit("null-canon-cell"))),
                null);
        List<SqlSelect.Projection> ps = List.of(
                new SqlSelect.Projection(verdict, VERDICT,
                        new OutputCol(VERDICT, SqlType.Scalar.BOOLEAN, false)),
                new SqlSelect.Projection(fe, EXPECTED,
                        new OutputCol(EXPECTED, SqlType.Scalar.VARCHAR, false)),
                new SqlSelect.Projection(fa, ACTUAL,
                        new OutputCol(ACTUAL, SqlType.Scalar.VARCHAR, false)),
                new SqlSelect.Projection(unjudged, UNJUDGED,
                        new OutputCol(UNJUDGED, SqlType.Scalar.VARCHAR, true)));
        SqlSelect body = new SqlSelect(ps, false, new SqlSource.Dual(), null,
                List.of(), null, null, List.of(), null, null, List.of());
        return new SqlWith(ctes, body);
    }

    /** A HOST-CONSTANT side (a compile-time fact the pipeline answered
     * without SQL — a generated seed-data string, a rendered DDL text, a
     * folded literal) bound as a VALUES relation, so the comparison still
     * happens in the database (TWO_DESIGN_LEGS §2.6 blocker 1:
     * database-ADJUDICATED, not database-computed — the ledger says
     * which). Null when a value has no literal spelling here (the caller
     * reports it unjudged by kind). */
    public static @com.legend.Nullable SqlQuery constantPlan(List<Object> values) {
        List<List<SqlExpr>> rows = new ArrayList<>();
        SqlType type = SqlType.Scalar.VARCHAR;   // the EMPTY constant's column kind
        for (Object v : values) {
            SqlExpr lit;
            SqlType t;
            switch (v) {
                case String str -> { lit = new SqlExpr.StringLit(str); t = SqlType.Scalar.VARCHAR; }
                case Long l -> { lit = new SqlExpr.IntLit(l); t = SqlType.Scalar.BIGINT; }
                case Integer i -> { lit = new SqlExpr.IntLit(i); t = SqlType.Scalar.BIGINT; }
                case Boolean b -> { lit = new SqlExpr.BoolLit(b); t = SqlType.Scalar.BOOLEAN; }
                case null, default -> { return null; }
            }
            if (!rows.isEmpty() && type != t) {
                return null;   // a mixed constant collection has no one column type
            }
            type = t;
            rows.add(List.of(lit));
        }
        OutputCol out = new OutputCol("value", type, rows.isEmpty());
        SqlSource src = rows.isEmpty()
                ? new SqlSource.Subselect(new SqlSelect(
                        List.of(new SqlSelect.Projection(new SqlExpr.NullLit(), "value", out)),
                        false, new SqlSource.Dual(),
                        new SqlExpr.BoolLit(false), List.of(), null, null, List.of(),
                        null, null, List.of(out)), "k", null)
                : new SqlSource.Values(rows, List.of("value"), "k", List.of(out));
        return new SqlSelect(
                List.of(new SqlSelect.Projection(SqlExpr.Column.of("k", out), "value", out)),
                false, src, null, List.of(), null, null, List.of(), null, null, List.of(out));
    }

    /** The relation the verdict statement returns (the executor's
     * TABULAR decode needs the declared schema). */
    public static com.legend.compiler.element.type.Type.RelationType schema() {
        var one = new com.legend.compiler.element.type.Multiplicity.Bounded(1, 1);
        var opt = new com.legend.compiler.element.type.Multiplicity.Bounded(0, 1);
        var t = com.legend.compiler.element.type.Type.Primitive.STRING;
        return new com.legend.compiler.element.type.Type.RelationType(List.of(
                new com.legend.compiler.element.type.Type.RelationType.Column(VERDICT,
                        com.legend.compiler.element.type.Type.Primitive.BOOLEAN, one),
                new com.legend.compiler.element.type.Type.RelationType.Column(EXPECTED, t, one),
                new com.legend.compiler.element.type.Type.RelationType.Column(ACTUAL, t, one),
                new com.legend.compiler.element.type.Type.RelationType.Column(UNJUDGED, t, opt)));
    }

    /** {@code SELECT canon AS __c, row_number() OVER () AS __rn FROM (wrapped) w}
     * — the side reduced to its deciding canon texts in arrival order. */
    private static SqlQuery canonRows(Side s) {
        // the canon column is TEXT by contract (an Integer canon is the
        // bare number expression until cast); the framing concatenates
        SqlExpr canon = new SqlExpr.Cast(
                SqlExpr.Column.of("w", s.wrapped().outputs(), s.canonColumn()),
                SqlType.Scalar.VARCHAR);
        SqlQuery inner = s.wrapped();
        if (s.byCanonText() && inner instanceof SqlSelect ws && !ws.orderBy().isEmpty()) {
            // a canon-ordered side re-orders by __c in the aggregate; the
            // wrap's own ORDER BY is redundant here and, inlined into a
            // CTE over a literal side, DuckDB rejects "ORDER BY a literal"
            inner = new SqlSelect(ws.projections(), ws.distinct(), ws.from(), ws.where(),
                    ws.groupBy(), ws.having(), ws.qualify(), List.of(), ws.limit(),
                    ws.offset(), ws.outputs());
        }
        SqlExpr rn = new SqlExpr.WindowCall(
                new SqlAgg.RankingFn(SqlAgg.Fn.ROW_NUMBER, List.of()),
                List.of(), List.of(), null);
        OutputCol cOut = new OutputCol(C, SqlType.Scalar.VARCHAR, true);
        OutputCol rnOut = new OutputCol(RN, SqlType.Scalar.BIGINT, false);
        return new SqlSelect(List.of(
                        new SqlSelect.Projection(canon, C, cOut),
                        new SqlSelect.Projection(rn, RN, rnOut)),
                false, new SqlSource.Subselect(inner, "w", null), null,
                List.of(), null, null, List.of(), null, null,
                List.of(cOut, rnOut));
    }

    private static List<OutputCol> cteOutputs() {
        return List.of(new OutputCol(C, SqlType.Scalar.VARCHAR, true),
                new OutputCol(RN, SqlType.Scalar.BIGINT, false));
    }

    private static SqlSource cte(String name) {
        return new SqlSource.Table(name, name, cteOutputs());
    }

    private static SqlExpr col(String cteName, String col) {
        return SqlExpr.Column.of(cteName, cteOutputs(), col);
    }

    /** {@code (SELECT <agg> FROM cte)} as a scalar. */
    private static SqlExpr scalarOver(String cteName, SqlExpr projected,
            String alias, SqlType type, @com.legend.Nullable Long limit) {
        OutputCol out = new OutputCol(alias, type, true);
        return new SqlExpr.ScalarSubquery(new SqlSelect(
                List.of(new SqlSelect.Projection(projected, alias, out)),
                false, cte(cteName), null, List.of(), null, null,
                limit == null ? List.of()
                        : List.of(new SqlSelect.SortKey(col(cteName, RN), true, null, null)),
                limit, null, List.of(out)));
    }

    private static SqlExpr count(String cteName) {
        return scalarOver(cteName,
                new SqlAgg.Reducer(SqlAgg.Fn.COUNT, List.of(col(cteName, RN)),
                        false, List.of()),
                "__n", SqlType.Scalar.BIGINT, null);
    }

    /** {@code (SELECT count(*) FROM cte WHERE __c IS NULL) > 0}. */
    private static SqlExpr nullCells(String cteName) {
        OutputCol out = new OutputCol("__nulls", SqlType.Scalar.BIGINT, false);
        SqlExpr n = new SqlExpr.ScalarSubquery(new SqlSelect(
                List.of(new SqlSelect.Projection(
                        new SqlAgg.Reducer(SqlAgg.Fn.COUNT, List.of(col(cteName, RN)),
                                false, List.of()), "__nulls", out)),
                false, cte(cteName),
                SqlExpr.Call.of(SqlFn.IS_NULL, col(cteName, C)),
                List.of(), null, null, List.of(), null, null, List.of(out)));
        return SqlExpr.Call.of(SqlFn.GREATER, n, new SqlExpr.IntLit(0));
    }

    /** The spec's side framing in SQL. */
    private static SqlExpr frame(String cteName, Side s) {
        SqlExpr empty = new SqlExpr.StringLit("[]");
        if (!s.many()) {
            // a [0..1] side: its one row's canon, or '[]' when there is
            // none or the cell is NULL (every empty form canons '[]')
            return SqlExpr.Call.of(SqlFn.COALESCE,
                    scalarOver(cteName, col(cteName, C), "__one",
                            SqlType.Scalar.VARCHAR, 1L),
                    empty);
        }
        SqlExpr n = count(cteName);
        SqlExpr key = s.byCanonText() ? col(cteName, C) : col(cteName, RN);
        SqlExpr joined = scalarOver(cteName,
                new SqlAgg.Reducer(SqlAgg.Fn.STRING_AGG,
                        List.of(col(cteName, C), new SqlExpr.StringLit(", ")),
                        false,
                        List.of(new SqlSelect.SortKey(key, true, null, null))),
                "__joined", SqlType.Scalar.VARCHAR, null);
        SqlExpr framed = SqlExpr.Call.of(SqlFn.CONCAT,
                new SqlExpr.StringLit("["), joined, new SqlExpr.StringLit("]"));
        List<SqlExpr.Case.When> whens = new ArrayList<>();
        whens.add(new SqlExpr.Case.When(
                SqlExpr.Call.of(SqlFn.EQUAL, n, new SqlExpr.IntLit(0)), empty));
        whens.add(new SqlExpr.Case.When(
                SqlExpr.Call.of(SqlFn.EQUAL, n, new SqlExpr.IntLit(1)),
                scalarOver(cteName, col(cteName, C), "__one",
                        SqlType.Scalar.VARCHAR, 1L)));
        return new SqlExpr.Case(whens, framed);
    }
}
