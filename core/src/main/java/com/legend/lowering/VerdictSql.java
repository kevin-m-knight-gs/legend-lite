// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.lowering;

import com.legend.compiler.element.type.PlatformTypes;
import com.legend.compiler.element.type.Type;
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
    /** True when the verdict held ONLY through the declared 2-ULP Float
     * leniency (the harness counts it, as host mode counts its own). */
    public static final String LENIENT = "__lenient";

    /** One canon-wrapped side: the wrapped plan, the name of the canon
     * column that decides (one of {@code __canon<i>}), whether the side
     * is a collection, and whether its elements order by canon text (the
     * multiset forms) or by arrival (an ordered assert). */
    public record Side(SqlQuery wrapped, String canonColumn, boolean many,
            boolean byCanonText, boolean isFloat) {
    }

    private static final String C = "__c";
    private static final String RN = "__rn";
    private static final String V = "__v";

    /** The equality verdict statement over two framed sides. */
    public static SqlQuery equality(Side e, Side a) {
        SqlQuery er = canonRows(e);
        SqlQuery ar = canonRows(a);
        // the leniency walks the cells POSITIONALLY in arrival order in every
        // form (host mode: Equality.ordered first, the multiset only after)
        return statement(er, ar, e.many(), a.many(), e.byCanonText(), List.of(),
                List.of(), er, ar);
    }

    // ── the GRID forms (leg 3.1b): a TABULAR side rides the grid wrap
    // (CanonicalRenderSql.wrapTdsCanon: __rowcanon + __cell<i>); its value
    // PEER (a literal list) is framed into rows of the grid's width, or
    // compared as a loose cell pool (the sameElements form) — the same
    // rules TdsCompare.peerRowCanons / tdsCellCanons apply in Java.

    /** A grid side: its wrapped plan and its width (columns). */
    /** {@code floatColumns}: per column, whether it is DECLARED Float —
     * the 2-ULP leniency's operand columns. */
    /** {@code emptyIsNull}: per column, whether the assert's own grammar
     * conflates the empty string with NULL ({@code toCSV} prints both as
     * an empty cell) — the verdict then judges under that equivalence: a
     * String cell's empty canon reads as the TDSNull sentinel. */
    public record GridSide(SqlQuery wrapped, int width, List<Boolean> floatColumns,
            List<Boolean> emptyIsNull) {
        public GridSide(SqlQuery wrapped, int width, List<Boolean> floatColumns) {
            this(wrapped, width, floatColumns, List.of());
        }
        boolean emptyIsNullAt(int i) {
            return i < emptyIsNull.size() && emptyIsNull.get(i);
        }
    }

    /** A value peer of a grid: its wrapped plan, the literal-channel canon
     * column, and whether it is the EXPECTED side (the golden's
     * {@code 'TDSNull'} string cells spell the bare sentinel there only —
     * a real 'TDSNull' string on OUR wire stays quoted). */
    public record PeerSide(SqlQuery wrapped, String canonColumn, boolean expected,
            boolean isFloat) {
    }

    /** {@code assertEquals} over rows: the grid's row canons against the
     * peer's cells chunked by the grid's width; ordered by arrival, or as
     * a row multiset when {@code multiset}. {@code gridIsExpected} says
     * which side the grid is. */
    public static SqlQuery gridRows(GridSide grid, PeerSide peer,
            boolean gridIsExpected, boolean multiset) {
        SqlQuery g = gridRowCanons(grid);
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
        SqlQuery gc = gridCellsRowMajor(grid);
        SqlQuery pc = peerCells(peer);
        return gridIsExpected
                ? statement(g, p, true, true, multiset, more, extra, gc, pc)
                : statement(p, g, true, true, multiset, more, extra, pc, gc);
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

    // ── the ONE-LINE families (leg 3.1c): size / empty / contains / a boolean
    // condition / the tolerance assert / the forAll-contains subset — each a
    // predicate over the same row sources, returned in the same verdict row.

    /** {@code assertSize}: the side's row count against {@code n} (the size
     * side's one canon text as a BIGINT); {@code envelope} = the read is a
     * relation-rooted execute's {@code .values}, which holds ONE TDS. */
    public static SqlQuery size(SqlQuery sideRows, SqlQuery nRows, boolean envelope) {
        SqlExpr count = envelope ? new SqlExpr.IntLit(1) : count("__a");
        SqlExpr n = new SqlExpr.Cast(scalarOver("__n", col("__n", C), "__one",
                SqlType.Scalar.VARCHAR, 1L), SqlType.Scalar.BIGINT);
        return predicate(List.of(new SqlWith.Cte("__a", sideRows), new SqlWith.Cte("__n", nRows)),
                SqlExpr.Call.of(SqlFn.NULL_SAFE_EQUAL, count, n),
                new SqlExpr.Cast(n, SqlType.Scalar.VARCHAR),
                new SqlExpr.Cast(count, SqlType.Scalar.VARCHAR));
    }

    /** A GRAPH-shaped side (a class collection serialized as ONE JSON
     * document): its size is the number of ROOT ROWS the fold aggregates —
     * the host rule ({@code p instanceof List ? size : 1}) read off the
     * PLAN, not the document. An array-wrapped root ({@code JsonArrayAgg}
     * under the fold's VARCHAR cast / empty-array COALESCE) counts the rows
     * under the aggregate; a bare-object root is one document when a row
     * exists and NULL (0) otherwise. No JSON function on any dialect, no
     * document built to be measured. A plan that is not the fold's
     * one-projection select is a construction fault, loud. */
    public static SqlExpr graphCount(SqlQuery graphPlan) {
        // the canon wrap over a graph side is a pass-through select (the
        // document column beside its canon) around the fold: descend to it
        SqlQuery fold = graphPlan;
        for (int depth = 0; depth < 4 && fold instanceof SqlSelect w
                && !w.projections().isEmpty()
                && w.projections().get(0).expr() instanceof SqlExpr.Column
                && w.from() instanceof SqlSource.Subselect inner; depth++) {
            fold = inner.inner();
        }
        if (!(fold instanceof SqlSelect ps) || ps.projections().size() != 1
                || !ps.groupBy().isEmpty()) {
            throw new IllegalStateException("graph side: not the fold's one-projection select: "
                    + fold.getClass().getSimpleName()
                    + (fold instanceof SqlSelect gs ? " projections=" + gs.projections().stream()
                            .map(pr -> pr.alias() + ":" + pr.expr().getClass().getSimpleName()).toList()
                            + " groupBy=" + gs.groupBy().size() + " from=" + gs.from().getClass().getSimpleName()
                            : ""));
        }
        SqlExpr top = ps.projections().get(0).expr();
        while (true) {
            if (top instanceof SqlExpr.Cast c) {
                top = c.value();
            } else if (top instanceof SqlExpr.Call k && k.fn() == SqlFn.COALESCE
                    && !k.args().isEmpty()) {
                top = k.args().get(0);
            } else {
                break;
            }
        }
        OutputCol n = new OutputCol("__n", SqlType.Scalar.BIGINT, false);
        SqlSelect.Projection countStar = new SqlSelect.Projection(
                new SqlAgg.Reducer(SqlAgg.Fn.COUNT, List.of(), false, List.of()), "__n", n);
        if (top instanceof SqlExpr.JsonArrayAgg) {
            return new SqlExpr.ScalarSubquery(ps.withProjections(List.of(countStar)));
        }
        OutputCol one = new OutputCol("__one", SqlType.Scalar.BIGINT, false);
        SqlSelect first = new SqlSelect(
                List.of(new SqlSelect.Projection(new SqlExpr.IntLit(1), "__one", one)),
                ps.distinct(), ps.from(), ps.where(), ps.groupBy(), ps.having(), ps.qualify(),
                ps.orderBy(), 1L, ps.offset(), List.of(one));
        return new SqlExpr.ScalarSubquery(new SqlSelect(List.of(countStar), false,
                new SqlSource.Subselect(first, "w", null), null, List.of(), null, null,
                List.of(), null, null, List.of(n)));
    }

    /** {@code assertSize} over a graph side. */
    public static SqlQuery sizeOfGraph(SqlQuery graphPlan, SqlQuery nRows) {
        SqlExpr count = graphCount(graphPlan);
        SqlExpr n = new SqlExpr.Cast(scalarOver("__n", col("__n", C), "__one",
                SqlType.Scalar.VARCHAR, 1L), SqlType.Scalar.BIGINT);
        return predicate(List.of(new SqlWith.Cte("__n", nRows)),
                SqlExpr.Call.of(SqlFn.NULL_SAFE_EQUAL, count, n),
                new SqlExpr.Cast(n, SqlType.Scalar.VARCHAR),
                new SqlExpr.Cast(count, SqlType.Scalar.VARCHAR));
    }

    /** {@code assertEmpty} / {@code assertNotEmpty} over a graph side. */
    public static SqlQuery emptyOfGraph(SqlQuery graphPlan, boolean wantEmpty) {
        SqlExpr count = graphCount(graphPlan);
        SqlExpr isEmpty = SqlExpr.Call.of(SqlFn.EQUAL, count, new SqlExpr.IntLit(0));
        return predicate(List.of(),
                wantEmpty ? isEmpty : SqlExpr.Call.of(SqlFn.NOT, isEmpty),
                new SqlExpr.StringLit(wantEmpty ? "empty" : "not empty"),
                SqlExpr.Call.of(SqlFn.CONCAT, new SqlExpr.Cast(count, SqlType.Scalar.VARCHAR),
                        new SqlExpr.StringLit(" element(s)")));
    }

    /** {@code assertEmpty} / {@code assertNotEmpty}: the side's row count. */
    public static SqlQuery empty(SqlQuery sideRows, boolean wantEmpty) {
        SqlExpr count = count("__a");
        SqlExpr isEmpty = SqlExpr.Call.of(SqlFn.EQUAL, count, new SqlExpr.IntLit(0));
        return predicate(List.of(new SqlWith.Cte("__a", sideRows)),
                wantEmpty ? isEmpty : SqlExpr.Call.of(SqlFn.NOT, isEmpty),
                new SqlExpr.StringLit(wantEmpty ? "empty" : "not empty"),
                SqlExpr.Call.of(SqlFn.CONCAT, new SqlExpr.Cast(count, SqlType.Scalar.VARCHAR),
                        new SqlExpr.StringLit(" element(s)")));
    }

    /** {@code assertContains}: some element's canon equals the value's. */
    public static SqlQuery contains(SqlQuery collRows, SqlQuery valRows) {
        SqlExpr value = scalarOver("__v0", col("__v0", C), "__one", SqlType.Scalar.VARCHAR, 1L);
        OutputCol one = new OutputCol("__one", SqlType.Scalar.BIGINT, false);
        SqlExpr member = new SqlExpr.Exists(new SqlSelect(
                List.of(new SqlSelect.Projection(new SqlExpr.IntLit(1), "__one", one)),
                false, cte("__a"),
                SqlExpr.Call.of(SqlFn.NULL_SAFE_EQUAL, col("__a", C), value),
                List.of(), null, null, List.of(), null, null, List.of(one)));
        return predicate(List.of(new SqlWith.Cte("__a", collRows), new SqlWith.Cte("__v0", valRows)),
                member, value, frame("__a", true, true));
    }

    /** {@code assert(cond)} / {@code assertFalse(cond)}: the condition's one
     * canon text is {@code true} / {@code false}. */
    public static SqlQuery condition(SqlQuery condRows, boolean wantTrue) {
        SqlExpr c = scalarOver("__a", col("__a", C), "__one", SqlType.Scalar.VARCHAR, 1L);
        return predicate(List.of(new SqlWith.Cte("__a", condRows)),
                SqlExpr.Call.of(SqlFn.NULL_SAFE_EQUAL, c, new SqlExpr.StringLit(wantTrue ? "true" : "false")),
                new SqlExpr.StringLit(wantTrue ? "true" : "false"),
                SqlExpr.Call.of(SqlFn.COALESCE, c, new SqlExpr.StringLit("[]")));
    }

    /** {@code assertEqWithinTolerance(e, a, tol)}: {@code |e − a| ≤ tol} over
     * the three sides' canon texts as DOUBLEs. */
    public static SqlQuery tolerance(SqlQuery eRows, SqlQuery aRows, SqlQuery tolRows) {
        SqlExpr e = new SqlExpr.Cast(scalarOver("__e", col("__e", C), "__one", SqlType.Scalar.VARCHAR, 1L), SqlType.Scalar.DOUBLE);
        SqlExpr a = new SqlExpr.Cast(scalarOver("__a", col("__a", C), "__one", SqlType.Scalar.VARCHAR, 1L), SqlType.Scalar.DOUBLE);
        SqlExpr t = new SqlExpr.Cast(scalarOver("__t", col("__t", C), "__one", SqlType.Scalar.VARCHAR, 1L), SqlType.Scalar.DOUBLE);
        SqlExpr within = SqlExpr.Call.of(SqlFn.LESS_EQUAL,
                SqlExpr.Call.of(SqlFn.ABS, SqlExpr.Call.of(SqlFn.MINUS, e, a)), t);
        return predicate(List.of(new SqlWith.Cte("__e", eRows), new SqlWith.Cte("__a", aRows),
                        new SqlWith.Cte("__t", tolRows)),
                SqlExpr.Call.of(SqlFn.COALESCE, within, new SqlExpr.BoolLit(false)),
                new SqlExpr.Cast(e, SqlType.Scalar.VARCHAR), new SqlExpr.Cast(a, SqlType.Scalar.VARCHAR));
    }

    /** The {@code $need->forAll(n | $have->contains($n))} subset idiom:
     * no needed canon is absent from the haves. */
    public static SqlQuery subset(SqlQuery needRows, SqlQuery haveRows, boolean wantTrue) {
        OutputCol one = new OutputCol("__one", SqlType.Scalar.BIGINT, false);
        SqlExpr present = new SqlExpr.Exists(new SqlSelect(
                List.of(new SqlSelect.Projection(new SqlExpr.IntLit(1), "__one", one)),
                false, cte("__h"),
                SqlExpr.Call.of(SqlFn.NULL_SAFE_EQUAL, col("__h", C), col("__n", C)),
                List.of(), null, null, List.of(), null, null, List.of(one)));
        SqlExpr missing = new SqlExpr.Exists(new SqlSelect(
                List.of(new SqlSelect.Projection(new SqlExpr.IntLit(1), "__one", one)),
                false, cte("__n"), SqlExpr.Call.of(SqlFn.NOT, present),
                List.of(), null, null, List.of(), null, null, List.of(one)));
        SqlExpr holds = SqlExpr.Call.of(SqlFn.NOT, missing);
        return predicate(List.of(new SqlWith.Cte("__n", needRows), new SqlWith.Cte("__h", haveRows)),
                wantTrue ? holds : missing, new SqlExpr.StringLit(wantTrue ? "subset" : "not a subset"),
                frame("__n", true, true));
    }

    /** The RENDERED-TEXT arm (leg 3.1d): a database-rendered grid text
     * (toCSV / toString / a join) against a string — byte-equal is the
     * verdict; a differing pair is UNJUDGED here with the reason (host
     * mode's policy for that case — data lines as a multiset, a bounded
     * print-precision float tolerance per cell — is not a SQL rule yet;
     * the differential gate holds those rows up). */
    public static SqlQuery renderedText(SqlQuery eRows, SqlQuery aRows) {
        SqlExpr e = scalarOver("__e", col("__e", C), "__one", SqlType.Scalar.VARCHAR, 1L);
        SqlExpr a = scalarOver("__a", col("__a", C), "__one", SqlType.Scalar.VARCHAR, 1L);
        SqlExpr equal = SqlExpr.Call.of(SqlFn.NULL_SAFE_EQUAL, e, a);
        SqlExpr unjudged = new SqlExpr.Case(List.of(new SqlExpr.Case.When(
                SqlExpr.Call.of(SqlFn.NOT, equal),
                new SqlExpr.StringLit("rendered-text: not byte-equal (host policy: line multiset, cell tolerance)"))),
                null);
        return predicate(List.of(new SqlWith.Cte("__e", eRows), new SqlWith.Cte("__a", aRows)),
                equal, e, a, unjudged);
    }

    /** {@code assertTdsEquivalent(one, two, delta[, timeDelta])} (bucket 5): the
     * two grids' cells ROW-MAJOR, aligned by position; a numeric pair within
     * {@code delta}, a temporal pair within {@code timeDelta} seconds, any
     * other pair canon-equal (the host rule, TdsCompare.tdsEquivalent); the
     * cell counts must match. {@code kinds} = the columns' declared kinds
     * (both grids share the schema — the names were checked statically). */
    public static SqlQuery gridTolerance(GridSide one, GridSide two, List<Type> kinds,
            SqlQuery deltaRows, SqlQuery timeDeltaRows) {
        SqlQuery e = toleranceCells(one, kinds);
        SqlQuery a = toleranceCells(two, kinds);
        SqlExpr delta = new SqlExpr.Cast(scalarOver("__d", col("__d", C), "__one",
                SqlType.Scalar.VARCHAR, 1L), SqlType.Scalar.DOUBLE);
        SqlExpr timeDelta = new SqlExpr.Cast(scalarOver("__t", col("__t", C), "__one",
                SqlType.Scalar.VARCHAR, 1L), SqlType.Scalar.DOUBLE);
        SqlExpr en = SqlExpr.Column.of("__e", "__n", SqlType.Scalar.DOUBLE, true, OutputCol.Origin.DERIVED);
        SqlExpr an = SqlExpr.Column.of("__a", "__n", SqlType.Scalar.DOUBLE, true, OutputCol.Origin.DERIVED);
        SqlExpr es = SqlExpr.Column.of("__e", "__s", SqlType.Scalar.DOUBLE, true, OutputCol.Origin.DERIVED);
        SqlExpr as = SqlExpr.Column.of("__a", "__s", SqlType.Scalar.DOUBLE, true, OutputCol.Origin.DERIVED);
        SqlExpr within = SqlExpr.Call.of(SqlFn.OR,
                SqlExpr.Call.of(SqlFn.OR,
                        SqlExpr.Call.of(SqlFn.AND,
                                SqlExpr.Call.of(SqlFn.AND, SqlExpr.Call.of(SqlFn.IS_NOT_NULL, en),
                                        SqlExpr.Call.of(SqlFn.IS_NOT_NULL, an)),
                                SqlExpr.Call.of(SqlFn.LESS_EQUAL,
                                        SqlExpr.Call.of(SqlFn.ABS, SqlExpr.Call.of(SqlFn.MINUS, en, an)),
                                        SqlExpr.Call.of(SqlFn.ABS, delta))),
                        SqlExpr.Call.of(SqlFn.AND,
                                SqlExpr.Call.of(SqlFn.AND, SqlExpr.Call.of(SqlFn.IS_NOT_NULL, es),
                                        SqlExpr.Call.of(SqlFn.IS_NOT_NULL, as)),
                                SqlExpr.Call.of(SqlFn.LESS_EQUAL,
                                        SqlExpr.Call.of(SqlFn.ABS, SqlExpr.Call.of(SqlFn.MINUS, es, as)),
                                        SqlExpr.Call.of(SqlFn.ABS, timeDelta)))),
                SqlExpr.Call.of(SqlFn.NULL_SAFE_EQUAL, col("__e", C), col("__a", C)));
        // the BAD pairs: cells joined by position that are not within
        OutputCol rnOut = new OutputCol(RN, SqlType.Scalar.BIGINT, false);
        SqlSelect bad = new SqlSelect(List.of(new SqlSelect.Projection(col("__e", RN), RN, rnOut)),
                false, new SqlSource.Join(cte("__e"), cte("__a"), SqlSource.Join.Kind.INNER,
                        SqlExpr.Call.of(SqlFn.EQUAL, col("__e", RN), col("__a", RN))),
                SqlExpr.Call.of(SqlFn.NOT, SqlExpr.Call.of(SqlFn.COALESCE, within, new SqlExpr.BoolLit(false))),
                List.of(), null, null, List.of(), null, null, List.of(rnOut));
        SqlExpr sameCount = SqlExpr.Call.of(SqlFn.EQUAL, count("__e"), count("__a"));
        SqlExpr noBad = SqlExpr.Call.of(SqlFn.EQUAL, count("__p"), new SqlExpr.IntLit(0));
        return predicate(List.of(new SqlWith.Cte("__e", e), new SqlWith.Cte("__a", a),
                        new SqlWith.Cte("__d", deltaRows), new SqlWith.Cte("__t", timeDeltaRows),
                        new SqlWith.Cte("__p", bad)),
                SqlExpr.Call.of(SqlFn.AND, sameCount, noBad),
                new SqlExpr.Cast(count("__e"), SqlType.Scalar.VARCHAR),
                new SqlExpr.Cast(count("__a"), SqlType.Scalar.VARCHAR));
    }

    /** A grid's cells row-major with a NUMERIC value ({@code __n}, any
     * numeric kind) and a TEMPORAL value in epoch seconds ({@code __s}). */
    private static SqlQuery toleranceCells(GridSide grid, List<Type> kinds) {
        List<SqlQuery> branches = new ArrayList<>();
        OutputCol cOut = new OutputCol(C, SqlType.Scalar.VARCHAR, true);
        OutputCol rnOut = new OutputCol(RN, SqlType.Scalar.BIGINT, false);
        OutputCol nOut = new OutputCol("__n", SqlType.Scalar.DOUBLE, true);
        OutputCol sOut = new OutputCol("__s", SqlType.Scalar.DOUBLE, true);
        List<SqlSelect.Projection> values = grid.wrapped() instanceof SqlSelect ws
                ? ws.projections().subList(0, Math.min(grid.width(), ws.projections().size()))
                : List.of();
        for (int i = 0; i < grid.width(); i++) {
            SqlExpr cell = gridCell(grid, i);
            SqlExpr rowNo = new SqlExpr.WindowCall(
                    new SqlAgg.RankingFn(SqlAgg.Fn.ROW_NUMBER, List.of()),
                    List.of(), List.of(), null);
            SqlExpr ord = SqlExpr.Call.of(SqlFn.PLUS,
                    SqlExpr.Call.of(SqlFn.TIMES,
                            SqlExpr.Call.of(SqlFn.MINUS, rowNo, new SqlExpr.IntLit(1)),
                            new SqlExpr.IntLit(grid.width())),
                    new SqlExpr.IntLit(i + 1));
            Type k = i < kinds.size() ? kinds.get(i) : null;
            String alias = i < values.size() ? values.get(i).alias() : null;
            SqlExpr raw = alias != null
                    ? SqlExpr.Column.of("w", alias, SqlType.Scalar.VARCHAR, true,
                            OutputCol.Origin.DERIVED)
                    : null;
            boolean numeric = k == Type.Primitive.INTEGER || k == Type.Primitive.FLOAT
                    || k == Type.Primitive.DECIMAL || k == Type.Primitive.NUMBER
                    || k instanceof Type.PrecisionDecimal;
            boolean temporal = k == Type.Primitive.DATE_TIME || k == Type.Primitive.STRICT_DATE
                    || k == Type.Primitive.DATE;
            SqlExpr n = raw != null && numeric ? new SqlExpr.Cast(raw, SqlType.Scalar.DOUBLE)
                    : new SqlExpr.NullLit();
            // the grid's cells arrive DECODED as text (the fetch conformance:
            // nine-digit temporals) — a temporal cell casts back to a
            // timestamp for its epoch
            SqlExpr sec = raw != null && temporal
                    ? new SqlExpr.Cast(SqlExpr.Call.of(SqlFn.EPOCH_SECONDS,
                            new SqlExpr.Cast(raw, SqlType.Scalar.TIMESTAMP)), SqlType.Scalar.DOUBLE)
                    : new SqlExpr.NullLit();
            branches.add(new SqlSelect(List.of(
                            new SqlSelect.Projection(cell, C, cOut),
                            new SqlSelect.Projection(ord, RN, rnOut),
                            new SqlSelect.Projection(n, "__n", nOut),
                            new SqlSelect.Projection(sec, "__s", sOut)),
                    false, new SqlSource.Subselect(grid.wrapped(), "w", null), null,
                    List.of(), null, null, List.of(), null, null, List.of(cOut, rnOut, nOut, sOut)));
        }
        return branches.size() == 1 ? branches.get(0)
                : new com.legend.sql.SqlUnion(branches, true, List.of(cOut, rnOut, nOut, sOut));
    }

    /** The JSON verdict (bucket 3): the document the database built (its
     * objects' keys sorted by {@link com.legend.sql.JsonKeyOrder}) against
     * the golden's canonical text (compact, keys sorted, the engine's
     * root {@code [x] ≡ x} applied at compile time) — byte-equal is the
     * verdict; a differing pair is unjudged with its evidence. */
    public static SqlQuery jsonText(SqlQuery eRows, SqlQuery aRows) {
        SqlExpr e = scalarOver("__e", col("__e", C), "__one", SqlType.Scalar.VARCHAR, 1L);
        SqlExpr a = scalarOver("__a", col("__a", C), "__one", SqlType.Scalar.VARCHAR, 1L);
        SqlExpr equal = SqlExpr.Call.of(SqlFn.NULL_SAFE_EQUAL, e, a);
        SqlExpr unjudged = new SqlExpr.Case(List.of(new SqlExpr.Case.When(
                SqlExpr.Call.of(SqlFn.NOT, equal),
                new SqlExpr.StringLit("json: not byte-equal (keys sorted, compact)"))),
                null);
        return predicate(List.of(new SqlWith.Cte("__e", eRows), new SqlWith.Cte("__a", aRows)),
                equal, e, a, unjudged);
    }

    /** {@code assertJsonStringsEqual} over a document whose root array has
     * no defined order: the golden's element texts against the document's
     * root elements, as a multiset. */
    public static SqlQuery jsonRootMultiset(SqlQuery eElementRows, SqlQuery aDocRows) {
        OutputCol vOut = new OutputCol("value", SqlType.Scalar.JSON, true);
        SqlExpr doc = new SqlExpr.Cast(SqlExpr.Column.of("d", aDocRows.outputs(), C), SqlType.Scalar.JSON);
        SqlSelect elements = CollectionRelations.rows(SqlExpr.Call.of(SqlFn.VARIANT_ELEMENTS, doc),
                "value", vOut, List.of(vOut), new SqlSource.Subselect(aDocRows, "d", null));
        SqlQuery aRows = rowsOf(new SqlExpr.Cast(SqlExpr.Column.of("w", List.of(vOut), "value"),
                SqlType.Scalar.VARCHAR), elements);
        return statement(eElementRows, aRows, true, true, true, List.of());
    }

    /** One verdict row from a predicate: {@code __verdict} never NULL, the
     * two evidence texts, no unjudged, no leniency. */
    private static SqlQuery predicate(List<SqlWith.Cte> ctes, SqlExpr verdict,
            SqlExpr expected, SqlExpr actual) {
        return predicate(ctes, verdict, expected, actual, new SqlExpr.NullLit());
    }

    private static SqlQuery predicate(List<SqlWith.Cte> ctes, SqlExpr verdict,
            SqlExpr expected, SqlExpr actual, SqlExpr unjudged) {
        List<SqlSelect.Projection> ps = List.of(
                new SqlSelect.Projection(
                        SqlExpr.Call.of(SqlFn.COALESCE, verdict, new SqlExpr.BoolLit(false)), VERDICT,
                        new OutputCol(VERDICT, SqlType.Scalar.BOOLEAN, false)),
                new SqlSelect.Projection(expected, EXPECTED,
                        new OutputCol(EXPECTED, SqlType.Scalar.VARCHAR, true)),
                new SqlSelect.Projection(actual, ACTUAL,
                        new OutputCol(ACTUAL, SqlType.Scalar.VARCHAR, true)),
                new SqlSelect.Projection(unjudged, UNJUDGED,
                        new OutputCol(UNJUDGED, SqlType.Scalar.VARCHAR, true)),
                new SqlSelect.Projection(new SqlExpr.BoolLit(false), LENIENT,
                        new OutputCol(LENIENT, SqlType.Scalar.BOOLEAN, false)));
        SqlSelect body = new SqlSelect(ps, false, new SqlSource.Dual(), null,
                List.of(), null, null, List.of(), null, null, List.of());
        return ctes.isEmpty() ? body : new SqlWith(ctes, body);   // a WITH needs expressions
    }

    /** A side's rows for the predicate forms: a grid's row canons, or a
     * scalar / collection side's canons (NULL values dropped). */
    public static SqlQuery sideRows(SqlQuery wrapped, boolean grid, String canonColumn, boolean many) {
        return grid ? gridRowCanons(wrapped)
                : canonRows(new Side(wrapped, canonColumn, many, false, false));
    }

    /** A side's rows for COUNTING only (size / emptiness): no canon needed —
     * a collection of instances the canon declines still has a row count;
     * NULL values are dropped as everywhere (pure has no null value). */
    public static SqlQuery countRows(SqlQuery plan) {
        SqlExpr where = null;
        if (plan instanceof SqlSelect vs && !vs.projections().isEmpty()
                && vs.projections().get(0).alias() != null) {
            where = SqlExpr.Call.of(SqlFn.IS_NOT_NULL,
                    SqlExpr.Column.of("w", vs.projections().get(0).alias(),
                            SqlType.Scalar.VARCHAR, true, OutputCol.Origin.DERIVED));
        }
        return rowsOf(new SqlExpr.NullLit(), new SqlExpr.NullLit(), plan, where);
    }

    /** Two grids: row canons against row canons. */
    public static SqlQuery gridPair(SqlQuery e, SqlQuery a, boolean multiset) {
        return statement(gridRowCanons(e), gridRowCanons(a), true, true, multiset, List.of());
    }

    /** Two grids of ONE schema (a golden brought to rows against the
     * rendered relation): row canons against row canons, the cells walked
     * positionally for the declared-Float leniency, each side's empty-is-NULL
     * columns read as the sentinel. */
    public static SqlQuery gridPair(GridSide e, GridSide a, boolean multiset) {
        return statement(gridRowCanons(e), gridRowCanons(a), true, true, multiset, List.of(),
                List.of(), gridCellsRowMajor(e), gridCellsRowMajor(a));
    }

    private static SqlQuery statement(SqlQuery eRows, SqlQuery aRows,
            boolean eMany, boolean aMany, boolean byCanonText,
            List<SqlExpr.Case.When> moreUnjudged) {
        return statement(eRows, aRows, eMany, aMany, byCanonText, moreUnjudged, List.of());
    }

    private static SqlQuery statement(SqlQuery eRows, SqlQuery aRows,
            boolean eMany, boolean aMany, boolean byCanonText,
            List<SqlExpr.Case.When> moreUnjudged, List<SqlWith.Cte> extraCtes) {
        return statement(eRows, aRows, eMany, aMany, byCanonText, moreUnjudged, extraCtes,
                null, null);
    }

    /** The statement over two ROW SOURCES (each {@code (__c, __rn[, __v])});
     * {@code eCells}/{@code aCells} (each {@code (__c, __rn, __v)}, null =
     * no leniency for this form) are the POSITIONAL cell sequences the
     * declared 2-ULP Float leniency compares. */
    private static SqlQuery statement(SqlQuery eRows, SqlQuery aRows,
            boolean eMany, boolean aMany, boolean byCanonText,
            List<SqlExpr.Case.When> moreUnjudged, List<SqlWith.Cte> extraCtes,
            @com.legend.Nullable SqlQuery eCells, @com.legend.Nullable SqlQuery aCells) {
        List<SqlWith.Cte> ctes = new ArrayList<>(extraCtes);
        ctes.add(new SqlWith.Cte("__e", eRows));
        ctes.add(new SqlWith.Cte("__a", aRows));
        SqlExpr fe = frame("__e", eMany, byCanonText);
        SqlExpr fa = frame("__a", aMany, byCanonText);
        SqlExpr exact = SqlExpr.Call.of(SqlFn.NULL_SAFE_EQUAL, fe, fa);
        SqlExpr lenient;
        if (eCells != null && aCells != null) {
            ctes.add(new SqlWith.Cte("__ec", eCells));
            ctes.add(new SqlWith.Cte("__ac", aCells));
            lenient = lenient("__ec", "__ac");
        } else {
            lenient = new SqlExpr.BoolLit(false);
        }
        SqlExpr verdict = SqlExpr.Call.of(SqlFn.OR, exact, lenient);
        SqlExpr lenientOnly = SqlExpr.Call.of(SqlFn.AND,
                SqlExpr.Call.of(SqlFn.NOT, exact), lenient);
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
                        new OutputCol(UNJUDGED, SqlType.Scalar.VARCHAR, true)),
                new SqlSelect.Projection(lenientOnly, LENIENT,
                        new OutputCol(LENIENT, SqlType.Scalar.BOOLEAN, false)));
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
                new com.legend.compiler.element.type.Type.RelationType.Column(UNJUDGED, t, opt),
                new com.legend.compiler.element.type.Type.RelationType.Column(LENIENT,
                        com.legend.compiler.element.type.Type.Primitive.BOOLEAN, one)));
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
        if (inner instanceof SqlSelect vs && !vs.projections().isEmpty()
                && vs.projections().get(0).alias() != null) {
            // pure has no null VALUE: the executor's decode drops a NULL row
            // of a value collection and reads a NULL scalar as the EMPTY
            // collection — the canon side drops the row on every side, so an
            // empty [] (one NULL row) frames '[]' and never counts as a null
            // canon cell (a NULL canon over a non-null value stays unjudged)
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
        SqlExpr value = inner instanceof SqlSelect vs2 && !vs2.projections().isEmpty()
                ? doubleValue(vs2.projections().get(0), "w", s.isFloat()) : new SqlExpr.NullLit();
        return rowsOf(canon, value, inner, where);
    }

    /** A grid side's row canons in arrival order: {@code SELECT
     * CAST(w.__rowcanon AS VARCHAR) AS __c, row_number() OVER () AS __rn}. */
    private static SqlQuery gridRowCanons(SqlQuery wrapped) {
        return rowsOf(new SqlExpr.Cast(
                SqlExpr.Column.of("w", wrapped.outputs(), CanonicalRenderSql.ROW_CANON),
                SqlType.Scalar.VARCHAR), wrapped);
    }

    /** A grid's row canons; under a column's empty-is-NULL equivalence the
     * row canon is rebuilt from the cell canons ({@code __cell<i>} joined by
     * the cell separator, as the wrap builds {@code __rowcanon}). */
    private static SqlQuery gridRowCanons(GridSide grid) {
        if (grid.emptyIsNull().stream().noneMatch(b -> b)) {
            return gridRowCanons(grid.wrapped());
        }
        SqlExpr row = null;
        for (int i = 0; i < grid.width(); i++) {
            SqlExpr cell = gridCell(grid, i);
            row = row == null ? cell : SqlExpr.Call.of(SqlFn.CONCAT,
                    SqlExpr.Call.of(SqlFn.CONCAT, row,
                            new SqlExpr.StringLit(CanonicalRenderSql.TDS_CELL_SEP)), cell);
        }
        return rowsOf(java.util.Objects.requireNonNull(row), grid.wrapped());
    }

    /** One cell canon of a grid, the empty String canon ({@code ''}) read as
     * the sentinel where the column's grammar conflates it with NULL. */
    private static SqlExpr gridCell(GridSide grid, int i) {
        SqlExpr cell = new SqlExpr.Cast(SqlExpr.Column.of("w", grid.wrapped().outputs(),
                CanonicalRenderSql.CELL_CANON + i), SqlType.Scalar.VARCHAR);
        if (!grid.emptyIsNullAt(i)) {
            return cell;
        }
        return new SqlExpr.Case(List.of(new SqlExpr.Case.When(
                SqlExpr.Call.of(SqlFn.EQUAL, cell, new SqlExpr.StringLit("''")),
                new SqlExpr.StringLit(PlatformTypes.TDS_NULL_CELL))), cell);
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
        SqlExpr value = peer.wrapped() instanceof SqlSelect ps && !ps.projections().isEmpty()
                ? doubleValue(ps.projections().get(0), "w", peer.isFloat()) : new SqlExpr.NullLit();
        // pure has no null VALUE: the peer drops a NULL-value row like every
        // other side (an empty [] peer is one NULL row → zero cells)
        SqlExpr where = peer.wrapped() instanceof SqlSelect vs && !vs.projections().isEmpty()
                && vs.projections().get(0).alias() != null
                ? SqlExpr.Call.of(SqlFn.IS_NOT_NULL,
                        SqlExpr.Column.of("w", vs.projections().get(0).alias(),
                                SqlType.Scalar.VARCHAR, true, OutputCol.Origin.DERIVED))
                : null;
        return rowsOf(c, value, peer.wrapped(), where);
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

    /** {@code SELECT <canon> AS __c, row_number() OVER () AS __rn, <value> AS __v
     * FROM (source) w} — {@code value} the cell's DOUBLE value when the cell
     * IS a Float (the leniency's operand), NULL otherwise. */
    private static SqlQuery rowsOf(SqlExpr canon, SqlQuery source) {
        return rowsOf(canon, new SqlExpr.NullLit(), source, null);
    }

    private static SqlQuery rowsOf(SqlExpr canon, SqlExpr value, SqlQuery source,
            @com.legend.Nullable SqlExpr where) {
        SqlExpr rn = new SqlExpr.WindowCall(
                new SqlAgg.RankingFn(SqlAgg.Fn.ROW_NUMBER, List.of()),
                List.of(), List.of(), null);
        OutputCol cOut = new OutputCol(C, SqlType.Scalar.VARCHAR, true);
        OutputCol rnOut = new OutputCol(RN, SqlType.Scalar.BIGINT, false);
        OutputCol vOut = new OutputCol(V, SqlType.Scalar.DOUBLE, true);
        return new SqlSelect(List.of(
                        new SqlSelect.Projection(canon, C, cOut),
                        new SqlSelect.Projection(rn, RN, rnOut),
                        new SqlSelect.Projection(value, V, vOut)),
                false, new SqlSource.Subselect(source, "w", null), where,
                List.of(), null, null, List.of(), null, null,
                List.of(cOut, rnOut, vOut));
    }

    /** The DOUBLE value of a projection when its type fact says DOUBLE,
     * else NULL (only Float pairs take the leniency). */
    private static SqlExpr doubleValue(SqlSelect.Projection p, String table, boolean isFloat) {
        return isFloat && p.alias() != null
                ? new SqlExpr.Cast(SqlExpr.Column.of(table, p.alias(), SqlType.Scalar.DOUBLE,
                        true, OutputCol.Origin.DERIVED), SqlType.Scalar.DOUBLE)
                : new SqlExpr.NullLit();
    }

    /** A grid's cells ROW-MAJOR with their Float values: one row per cell,
     * {@code __rn = (row - 1) * width + i + 1} — the positional sequence
     * the leniency walks against the peer's cells. */
    private static SqlQuery gridCellsRowMajor(GridSide grid) {
        List<SqlQuery> branches = new ArrayList<>();
        OutputCol cOut = new OutputCol(C, SqlType.Scalar.VARCHAR, true);
        OutputCol rnOut = new OutputCol(RN, SqlType.Scalar.BIGINT, false);
        OutputCol vOut = new OutputCol(V, SqlType.Scalar.DOUBLE, true);
        List<SqlSelect.Projection> values = grid.wrapped() instanceof SqlSelect ws
                ? ws.projections().subList(0, Math.min(grid.width(), ws.projections().size()))
                : List.of();
        for (int i = 0; i < grid.width(); i++) {
            SqlExpr cell = gridCell(grid, i);
            SqlExpr rowNo = new SqlExpr.WindowCall(
                    new SqlAgg.RankingFn(SqlAgg.Fn.ROW_NUMBER, List.of()),
                    List.of(), List.of(), null);
            SqlExpr ord = SqlExpr.Call.of(SqlFn.PLUS,
                    SqlExpr.Call.of(SqlFn.TIMES,
                            SqlExpr.Call.of(SqlFn.MINUS, rowNo, new SqlExpr.IntLit(1)),
                            new SqlExpr.IntLit(grid.width())),
                    new SqlExpr.IntLit(i + 1));
            boolean isFloat = i < grid.floatColumns().size() && grid.floatColumns().get(i);
            SqlExpr v = i < values.size() ? doubleValue(values.get(i), "w", isFloat)
                    : new SqlExpr.NullLit();
            branches.add(new SqlSelect(List.of(
                            new SqlSelect.Projection(cell, C, cOut),
                            new SqlSelect.Projection(ord, RN, rnOut),
                            new SqlSelect.Projection(v, V, vOut)),
                    false, new SqlSource.Subselect(grid.wrapped(), "w", null), null,
                    List.of(), null, null, List.of(), null, null, List.of(cOut, rnOut, vOut)));
        }
        return branches.size() == 1 ? branches.get(0)
                : new com.legend.sql.SqlUnion(branches, true, List.of(cOut, rnOut, vOut));
    }

    /** The declared 2-ULP Float leniency as ONE predicate over two cell
     * sequences: same length, and every position either canon-equal or a
     * finite Double pair within {@code 2 * ulp(max(|x|, |y|))}, ulp spelled
     * {@code 2^(floor(log2(max)) - 52)} through {@code ln} (a boundary at
     * an exact power of two may differ from Math.ulp by one binade — the
     * differential gate measures it). */
    private static SqlExpr lenient(String ec, String ac) {
        SqlExpr ve = col(ec, V);
        SqlExpr va = col(ac, V);
        SqlExpr big = SqlExpr.Call.of(SqlFn.GREATEST,
                SqlExpr.Call.of(SqlFn.ABS, ve), SqlExpr.Call.of(SqlFn.ABS, va));
        SqlExpr finite = SqlExpr.Call.of(SqlFn.AND,
                SqlExpr.Call.of(SqlFn.LESS_EQUAL, SqlExpr.Call.of(SqlFn.ABS, ve),
                        new SqlExpr.FloatLit(Double.MAX_VALUE)),
                SqlExpr.Call.of(SqlFn.LESS_EQUAL, SqlExpr.Call.of(SqlFn.ABS, va),
                        new SqlExpr.FloatLit(Double.MAX_VALUE)));
        SqlExpr twoUlp = new SqlExpr.Case(List.of(new SqlExpr.Case.When(
                SqlExpr.Call.of(SqlFn.EQUAL, big, new SqlExpr.FloatLit(0.0)),
                new SqlExpr.FloatLit(0.0))),
                SqlExpr.Call.of(SqlFn.TIMES, new SqlExpr.FloatLit(2.0),
                        SqlExpr.Call.of(SqlFn.POW, new SqlExpr.FloatLit(2.0),
                                SqlExpr.Call.of(SqlFn.MINUS,
                                        SqlExpr.Call.of(SqlFn.FLOOR,
                                                SqlExpr.Call.of(SqlFn.DIVIDE,
                                                        SqlExpr.Call.of(SqlFn.LN, big),
                                                        SqlExpr.Call.of(SqlFn.LN, new SqlExpr.FloatLit(2.0)))),
                                        new SqlExpr.IntLit(52)))));
        SqlExpr pairOk = SqlExpr.Call.of(SqlFn.OR,
                SqlExpr.Call.of(SqlFn.NULL_SAFE_EQUAL, col(ec, C), col(ac, C)),
                SqlExpr.Call.of(SqlFn.AND,
                        SqlExpr.Call.of(SqlFn.AND,
                                SqlExpr.Call.of(SqlFn.IS_NOT_NULL, ve),
                                SqlExpr.Call.of(SqlFn.IS_NOT_NULL, va)),
                        SqlExpr.Call.of(SqlFn.AND, finite,
                                SqlExpr.Call.of(SqlFn.LESS_EQUAL,
                                        SqlExpr.Call.of(SqlFn.ABS,
                                                SqlExpr.Call.of(SqlFn.MINUS, ve, va)),
                                        twoUlp))));
        // a bad position exists?
        OutputCol one = new OutputCol("__one", SqlType.Scalar.BIGINT, false);
        SqlSource joined = new SqlSource.Join(cte(ec), cte(ac), SqlSource.Join.Kind.INNER,
                SqlExpr.Call.of(SqlFn.EQUAL, col(ec, RN), col(ac, RN)));
        SqlExpr bad = new SqlExpr.Exists(new SqlSelect(
                List.of(new SqlSelect.Projection(new SqlExpr.IntLit(1), "__one", one)),
                false, joined, SqlExpr.Call.of(SqlFn.NOT, pairOk),
                List.of(), null, null, List.of(), null, null, List.of(one)));
        SqlExpr sameCount = SqlExpr.Call.of(SqlFn.EQUAL,
                scalarOver(ec, new SqlAgg.Reducer(SqlAgg.Fn.COUNT, List.of(col(ec, RN)),
                        false, List.of()), "__n", SqlType.Scalar.BIGINT, null),
                scalarOver(ac, new SqlAgg.Reducer(SqlAgg.Fn.COUNT, List.of(col(ac, RN)),
                        false, List.of()), "__n", SqlType.Scalar.BIGINT, null));
        return SqlExpr.Call.of(SqlFn.AND, sameCount, SqlExpr.Call.of(SqlFn.NOT, bad));
    }

    private static List<OutputCol> cteOutputs() {
        return List.of(new OutputCol(C, SqlType.Scalar.VARCHAR, true),
                new OutputCol(RN, SqlType.Scalar.BIGINT, false),
                new OutputCol(V, SqlType.Scalar.DOUBLE, true));
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
