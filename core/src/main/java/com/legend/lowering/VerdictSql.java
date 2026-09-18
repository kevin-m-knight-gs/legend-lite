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
        return statement(canonRows(e), canonRows(a), e.many(), a.many(),
                e.byCanonText(), List.of());
    }

    // ── the GRID forms (leg 3.1b): a TABULAR side rides the grid wrap
    // (CanonicalRenderSql.wrapTdsCanon: __rowcanon + __cell<i>); its value
    // PEER (a literal list) is framed into rows of the grid's width, or
    // compared as a loose cell pool (the sameElements form) — the same
    // rules TdsCompare.peerRowCanons / tdsCellCanons apply in Java.

    /** A grid side: its wrapped plan and its width (columns). */
    public record GridSide(SqlQuery wrapped, int width) {
    }

    /** A value peer of a grid: its wrapped plan, the literal-channel canon
     * column, and whether it is the EXPECTED side (the golden's
     * {@code 'TDSNull'} string cells spell the bare sentinel there only —
     * a real 'TDSNull' string on OUR wire stays quoted). */
    public record PeerSide(SqlQuery wrapped, String canonColumn, boolean expected) {
    }

    /** {@code assertEquals} over rows: the grid's row canons against the
     * peer's cells chunked by the grid's width; ordered by arrival, or as
     * a row multiset when {@code multiset}. {@code gridIsExpected} says
     * which side the grid is. */
    public static SqlQuery gridRows(GridSide grid, PeerSide peer,
            boolean gridIsExpected, boolean multiset) {
        SqlQuery g = gridRowCanons(grid.wrapped());
        SqlQuery p = peerRowCanons(peer, grid.width());
        SqlExpr divisible = SqlExpr.Call.of(SqlFn.NOT_EQUAL,
                SqlExpr.Call.of(SqlFn.MOD,
                        scalarOver("__peer", new SqlAgg.Reducer(SqlAgg.Fn.COUNT,
                                List.of(col("__peer", RN)), false, List.of()),
                                "__n", SqlType.Scalar.BIGINT, null),
                        new SqlExpr.IntLit(grid.width())),
                new SqlExpr.IntLit(0));
        List<SqlWith.Cte> extra = List.of(new SqlWith.Cte("__peer", peerCells(peer)));
        List<SqlExpr.Case.When> more = List.of(new SqlExpr.Case.When(divisible,
                new SqlExpr.StringLit("tds-peer: cells not divisible by width "
                        + grid.width())));
        return gridIsExpected
                ? statement(g, p, true, true, multiset, more, extra)
                : statement(p, g, true, true, multiset, more, extra);
    }

    /** {@code assertSameElements} over a grid: the loose CELL pool (every
     * cell of every row) against the peer's cells, as a multiset. */
    public static SqlQuery gridCells(GridSide grid, PeerSide peer,
            boolean gridIsExpected) {
        SqlQuery g = gridCellCanons(grid);
        SqlQuery p = peerCells(peer);
        return gridIsExpected
                ? statement(g, p, true, true, true, List.of())
                : statement(p, g, true, true, true, List.of());
    }

    /** Two grids: row canons against row canons. */
    public static SqlQuery gridPair(SqlQuery e, SqlQuery a, boolean multiset) {
        return statement(gridRowCanons(e), gridRowCanons(a), true, true, multiset, List.of());
    }

    private static SqlQuery statement(SqlQuery eRows, SqlQuery aRows,
            boolean eMany, boolean aMany, boolean byCanonText,
            List<SqlExpr.Case.When> moreUnjudged) {
        return statement(eRows, aRows, eMany, aMany, byCanonText, moreUnjudged, List.of());
    }

    /** The statement over two ROW SOURCES (each {@code (__c, __rn)}). */
    private static SqlQuery statement(SqlQuery eRows, SqlQuery aRows,
            boolean eMany, boolean aMany, boolean byCanonText,
            List<SqlExpr.Case.When> moreUnjudged, List<SqlWith.Cte> extraCtes) {
        List<SqlWith.Cte> ctes = new ArrayList<>(extraCtes);
        ctes.add(new SqlWith.Cte("__e", eRows));
        ctes.add(new SqlWith.Cte("__a", aRows));
        SqlExpr fe = frame("__e", eMany, byCanonText);
        SqlExpr fa = frame("__a", aMany, byCanonText);
        SqlExpr verdict = SqlExpr.Call.of(SqlFn.NULL_SAFE_EQUAL, fe, fa);
        List<SqlExpr.Case.When> whens = new ArrayList<>(moreUnjudged);
        whens.add(new SqlExpr.Case.When(
                SqlExpr.Call.of(SqlFn.OR, nullCells("__e"), nullCells("__a")),
                new SqlExpr.StringLit("null-canon-cell")));
        // a canon carrying the JSON-tree marker is never comparable (the
        // Java rule: decline on sight, F10's contract) — unjudged by name
        whens.add(new SqlExpr.Case.When(
                SqlExpr.Call.of(SqlFn.OR, treeCells("__e"), treeCells("__a")),
                new SqlExpr.StringLit("unclaimable tree cell")));
        SqlExpr unjudged = new SqlExpr.Case(whens, null);
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
        SqlExpr where = null;
        if (s.many() && inner instanceof SqlSelect vs && !vs.projections().isEmpty()
                && vs.projections().get(0).alias() != null) {
            // pure collections hold no empties: the executor's value decode
            // DROPS a NULL row of a value collection, and so does the canon
            // side (a NULL canon over a non-null value stays unjudged)
            where = SqlExpr.Call.of(SqlFn.IS_NOT_NULL,
                    SqlExpr.Column.of("w", vs.projections().get(0).alias(),
                            SqlType.Scalar.VARCHAR, true, OutputCol.Origin.DERIVED));
        }
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
                false, new SqlSource.Subselect(inner, "w", null), where,
                List.of(), null, null, List.of(), null, null,
                List.of(cOut, rnOut));
    }

    /** A grid side's row canons in arrival order: {@code SELECT
     * CAST(w.__rowcanon AS VARCHAR) AS __c, row_number() OVER () AS __rn}. */
    private static SqlQuery gridRowCanons(SqlQuery wrapped) {
        return rowsOf(new SqlExpr.Cast(
                SqlExpr.Column.of("w", wrapped.outputs(), CanonicalRenderSql.ROW_CANON),
                SqlType.Scalar.VARCHAR), wrapped);
    }

    /** A grid side's loose CELL pool: one row per cell of every row, the
     * per-cell canons the wrap projected ({@code __cell<i>}), stacked. */
    private static SqlQuery gridCellCanons(GridSide grid) {
        List<SqlQuery> branches = new ArrayList<>();
        OutputCol cOut = new OutputCol(C, SqlType.Scalar.VARCHAR, true);
        for (int i = 0; i < grid.width(); i++) {
            SqlExpr cell = new SqlExpr.Cast(SqlExpr.Column.of("w", grid.wrapped().outputs(),
                    CanonicalRenderSql.CELL_CANON + i), SqlType.Scalar.VARCHAR);
            branches.add(new SqlSelect(
                    List.of(new SqlSelect.Projection(cell, C, cOut)),
                    false, new SqlSource.Subselect(grid.wrapped(), "w", null), null,
                    List.of(), null, null, List.of(), null, null, List.of(cOut)));
        }
        SqlQuery stacked = branches.size() == 1 ? branches.get(0)
                : new com.legend.sql.SqlUnion(branches, true, List.of(cOut));
        // the pool's arrival order is meaningless (a multiset by definition);
        // __rn exists for the frame's count and the LIMIT 1 read only
        return rowsOf(SqlExpr.Column.of("w", List.of(cOut), C), stacked);
    }

    /** The peer's element canons as cells: the literal-channel canon,
     * the golden's quoted {@code 'TDSNull'} cell spelled as the bare
     * sentinel on the expected side. */
    private static SqlQuery peerCells(PeerSide peer) {
        SqlExpr c = new SqlExpr.Cast(
                SqlExpr.Column.of("w", peer.wrapped().outputs(), peer.canonColumn()),
                SqlType.Scalar.VARCHAR);
        if (peer.expected()) {
            c = new SqlExpr.Case(List.of(new SqlExpr.Case.When(
                    SqlExpr.Call.of(SqlFn.EQUAL, c, new SqlExpr.StringLit("'TDSNull'")),
                    new SqlExpr.StringLit("TDSNull"))), c);
        }
        return rowsOf(c, peer.wrapped());
    }

    /** The peer's cells chunked into rows of {@code width}: cells in
     * arrival order, grouped by {@code (rn - 1) - ((rn - 1) MOD width)}
     * (integer arithmetic on every dialect), each group joined by the
     * cell separator in cell order — the same framing
     * {@code TdsCompare.peerRowCanons} writes in Java. Reads the
     * {@code __peer} CTE ({@link #peerCells}). */
    private static SqlQuery peerRowCanons(PeerSide peer, int width) {
        SqlExpr rn = col("__peer", RN);
        SqlExpr rn0 = SqlExpr.Call.of(SqlFn.MINUS, rn, new SqlExpr.IntLit(1));
        SqlExpr group = SqlExpr.Call.of(SqlFn.MINUS, rn0,
                SqlExpr.Call.of(SqlFn.MOD, rn0, new SqlExpr.IntLit(width)));
        OutputCol cOut = new OutputCol(C, SqlType.Scalar.VARCHAR, true);
        OutputCol rnOut = new OutputCol(RN, SqlType.Scalar.BIGINT, false);
        SqlExpr joined = new SqlAgg.Reducer(SqlAgg.Fn.STRING_AGG,
                List.of(col("__peer", C), new SqlExpr.StringLit(CanonicalRenderSql.TDS_CELL_SEP)),
                false, List.of(new SqlSelect.SortKey(rn, true, null, null)));
        SqlExpr first = new SqlAgg.Reducer(SqlAgg.Fn.MIN, List.of(rn), false, List.of());
        return new SqlSelect(List.of(
                        new SqlSelect.Projection(joined, C, cOut),
                        new SqlSelect.Projection(first, RN, rnOut)),
                false, cte("__peer"), null, List.of(group), null, null,
                List.of(), null, null, List.of(cOut, rnOut));
    }

    /** {@code SELECT <canon> AS __c, row_number() OVER () AS __rn FROM (source) w}. */
    private static SqlQuery rowsOf(SqlExpr canon, SqlQuery source) {
        SqlExpr rn = new SqlExpr.WindowCall(
                new SqlAgg.RankingFn(SqlAgg.Fn.ROW_NUMBER, List.of()),
                List.of(), List.of(), null);
        OutputCol cOut = new OutputCol(C, SqlType.Scalar.VARCHAR, true);
        OutputCol rnOut = new OutputCol(RN, SqlType.Scalar.BIGINT, false);
        return new SqlSelect(List.of(
                        new SqlSelect.Projection(canon, C, cOut),
                        new SqlSelect.Projection(rn, RN, rnOut)),
                false, new SqlSource.Subselect(source, "w", null), null,
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

    /** {@code (SELECT count(*) FROM cte WHERE strpos(__c, marker) > 0) > 0}. */
    private static SqlExpr treeCells(String cteName) {
        OutputCol out = new OutputCol("__trees", SqlType.Scalar.BIGINT, false);
        SqlExpr n = new SqlExpr.ScalarSubquery(new SqlSelect(
                List.of(new SqlSelect.Projection(
                        new SqlAgg.Reducer(SqlAgg.Fn.COUNT, List.of(col(cteName, RN)),
                                false, List.of()), "__trees", out)),
                false, cte(cteName),
                SqlExpr.Call.of(SqlFn.GREATER,
                        SqlExpr.Call.of(SqlFn.STRPOS, col(cteName, C),
                                new SqlExpr.StringLit(CanonicalRenderSql.TREE_MARKER)),
                        new SqlExpr.IntLit(0)),
                List.of(), null, null, List.of(), null, null, List.of(out)));
        return SqlExpr.Call.of(SqlFn.GREATER, n, new SqlExpr.IntLit(0));
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
    private static SqlExpr frame(String cteName, boolean many, boolean byCanonText) {
        SqlExpr empty = new SqlExpr.StringLit("[]");
        if (!many) {
            // a [0..1] side: its one row's canon, or '[]' when there is
            // none or the cell is NULL (every empty form canons '[]')
            return SqlExpr.Call.of(SqlFn.COALESCE,
                    scalarOver(cteName, col(cteName, C), "__one",
                            SqlType.Scalar.VARCHAR, 1L),
                    empty);
        }
        SqlExpr n = count(cteName);
        SqlExpr key = byCanonText ? col(cteName, C) : col(cteName, RN);
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
