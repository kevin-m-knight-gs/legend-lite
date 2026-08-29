package com.legend.lowering;

import com.legend.sql.SqlExpr;
import com.legend.sql.SqlFn;
import com.legend.sql.SqlSelect;
import com.legend.sql.SqlSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The fold authority tested DIRECTLY: occupancy state in, placement out —
 * including the HAVING/QUALIFY slots that integration tests cannot reach
 * until groupBy/window lowering exists. Every rule and its boundary.
 */
class FoldTest {

    // Outputs are STAMPED — claims() is strict by design (an unstamped
    // source is a construction bug; see the audit's H5).
    private static final SqlSelect BARE = SqlSelect.starOf(
            new SqlSource.Table("T", "t0", List.of(
                    new com.legend.sql.OutputCol("A", com.legend.sql.SqlType.Scalar.BIGINT, false),
                    new com.legend.sql.OutputCol("B", com.legend.sql.SqlType.Scalar.BIGINT, false),
                    new com.legend.sql.OutputCol("AGE", com.legend.sql.SqlType.Scalar.BIGINT, false),
                    new com.legend.sql.OutputCol("NAME", com.legend.sql.SqlType.Scalar.VARCHAR, false),
                    new com.legend.sql.OutputCol("C", com.legend.sql.SqlType.Scalar.BIGINT, false))));

    private static SqlExpr col(String n) {
        // stamped from the fixture's own declared outputs (M2: a
        // resolved reference carries the source's declared type)
        return SqlExpr.Column.of("t0",
                ((SqlSource.Table) BARE.from()).outputs(), n);
    }

    @Test
    @DisplayName("filter slots: WHERE bare; HAVING over groupBy; QUALIFY over window refs")
    void filterSlots() {
        assertEquals(Fold.FilterSlot.WHERE, Fold.filterSlot(BARE, false));
        // ORDER BY does NOT force isolation — filtering commutes with sorting.
        assertEquals(Fold.FilterSlot.WHERE,
                Fold.filterSlot(BARE.withOrderBy(List.of(SqlSelect.SortKey.asc(col("A")))), false));
        assertEquals(Fold.FilterSlot.HAVING,
                Fold.filterSlot(BARE.withGroupBy(List.of(col("A"))), false));
        assertEquals(Fold.FilterSlot.QUALIFY, Fold.filterSlot(BARE, true));
        // Engine relational parity (PCT testExtendFilterOutNull green on
        // the H2 and DuckDB reference adapters): an ORDINARY predicate
        // folds to WHERE even over a window-carrying select — the window
        // sees the filtered rows. The mapping-seam isolation, not this
        // rule, protects mapped windowed relations.
        SqlExpr rank = new SqlExpr.WindowCall(
                new com.legend.sql.SqlAgg.RankingFn(com.legend.sql.SqlAgg.Fn.RANK, List.of()),
                List.of(), List.of(SqlSelect.SortKey.asc(col("A"))), null);
        assertEquals(Fold.FilterSlot.WHERE, Fold.filterSlot(
                BARE.withProjections(List.of(
                        new SqlSelect.Projection(rank, "r", null))), false));
        // ...and expression-deep containment stays available to the seam
        assertTrue(Fold.containsWindow(
                SqlExpr.Call.of(SqlFn.PLUS, rank, new SqlExpr.IntLit(1))));
    }

    @Test
    @DisplayName("filter isolates on every truncation/dedup boundary")
    void filterBoundaries() {
        assertEquals(Fold.FilterSlot.ISOLATE, Fold.filterSlot(BARE.withLimit(5L), false));
        assertEquals(Fold.FilterSlot.ISOLATE, Fold.filterSlot(BARE.withOffset(2L), false));
        assertEquals(Fold.FilterSlot.ISOLATE, Fold.filterSlot(BARE.withDistinct(), false));
        // ...even when the predicate would otherwise take HAVING or QUALIFY.
        assertEquals(Fold.FilterSlot.ISOLATE,
                Fold.filterSlot(BARE.withGroupBy(List.of(col("A"))).withLimit(1L), false));
        assertEquals(Fold.FilterSlot.ISOLATE, Fold.filterSlot(BARE.withLimit(1L), true));
    }

    @Test
    @DisplayName("projection folds until DISTINCT or truncation")
    void projectionRules() {
        assertTrue(Fold.projectionFolds(BARE));
        assertTrue(Fold.projectionFolds(BARE.withOrderBy(List.of(SqlSelect.SortKey.asc(col("A"))))));
        assertFalse(Fold.projectionFolds(BARE.withDistinct()));
        assertFalse(Fold.projectionFolds(BARE.withLimit(1L)));
        assertFalse(Fold.projectionFolds(BARE.withOffset(1L)));
    }

    @Test
    @DisplayName("narrowing distinct requires every ORDER BY key to survive")
    void distinctNarrowRules() {
        SqlSelect sorted = BARE.withOrderBy(List.of(SqlSelect.SortKey.asc(col("AGE"))));
        assertTrue(Fold.distinctNarrowFolds(sorted, List.of("AGE", "NAME")));
        assertFalse(Fold.distinctNarrowFolds(sorted, List.of("NAME")));
        assertTrue(Fold.distinctNarrowFolds(BARE, List.of("NAME")), "no ORDER BY, nothing to lose");
    }

    @Test
    @DisplayName("single-slot rules: sort, limit, offset, distinct")
    void singleSlotRules() {
        assertTrue(Fold.sortFolds(BARE));
        assertFalse(Fold.sortFolds(BARE.withOrderBy(List.of(SqlSelect.SortKey.asc(col("A"))))));
        assertTrue(Fold.limitFolds(BARE));
        assertFalse(Fold.limitFolds(BARE.withLimit(1L)));
        assertTrue(Fold.offsetFolds(BARE));
        assertFalse(Fold.offsetFolds(BARE.withOffset(1L)));
        assertFalse(Fold.offsetFolds(BARE.withLimit(1L)), "offset after limit shrinks the window");
        assertTrue(Fold.distinctFolds(BARE));
        assertFalse(Fold.distinctFolds(BARE.withOrderBy(List.of(SqlSelect.SortKey.asc(col("A"))))));
        assertFalse(Fold.distinctFolds(BARE.withGroupBy(List.of(col("A")))));
        assertFalse(Fold.distinctFolds(BARE.withLimit(1L)));
    }

    @Test
    @DisplayName("groupBy folds only onto a clean select; extend only minds DISTINCT")
    void groupByAndExtendRules() {
        assertTrue(Fold.groupByFolds(BARE));
        assertFalse(Fold.groupByFolds(BARE.withGroupBy(List.of(col("A")))));
        assertFalse(Fold.groupByFolds(BARE.withDistinct()));
        assertFalse(Fold.groupByFolds(BARE.withOrderBy(List.of(SqlSelect.SortKey.asc(col("A"))))),
                "grouping does not preserve order");
        assertFalse(Fold.groupByFolds(BARE.withLimit(1L)));

        assertTrue(Fold.extendFolds(BARE));
        assertTrue(Fold.extendFolds(BARE.withLimit(1L)),
                "extend commutes with truncation — row count untouched");
        assertTrue(Fold.extendFolds(BARE.withOrderBy(List.of(SqlSelect.SortKey.asc(col("A"))))));
        assertFalse(Fold.extendFolds(BARE.withDistinct()),
                "extending a deduped set would dedup WITH the new column");
    }

    @Test
    @DisplayName("resolveInto sees THROUGH a star projection to source columns")
    void resolveThroughStar() {
        SqlSelect extended = BARE.withProjections(List.of(
                new SqlSelect.Projection(new SqlExpr.Star("t0"), null, null),
                new SqlSelect.Projection(SqlExpr.Call.of(SqlFn.PLUS, col("A"), col("B")), "computed", null)));
        assertEquals(col("AGE"), Fold.resolveInto(extended, "AGE"),
                "unclaimed names pass through the star to the source");
        assertEquals(SqlExpr.Call.of(SqlFn.PLUS, col("A"), col("B")),
                Fold.resolveInto(extended, "computed"),
                "a pure-scalar computed column substitutes inline (engine"
                        + " one-flat-select; enum decode inversion pin)");
    }

    @Test
    @DisplayName("resolveInto: star → source column; plain projection → substituted; computed → null")
    void resolveIntoRules() {
        assertEquals(col("AGE"), Fold.resolveInto(BARE, "AGE"));
        SqlSelect projected = BARE.withProjections(List.of(
                new SqlSelect.Projection(col("AGE"), "YEARS", null),
                new SqlSelect.Projection(col("NAME"), null, null),
                new SqlSelect.Projection(SqlExpr.Call.of(SqlFn.PLUS, col("A"), col("B")), "SUM_AB", null)));
        assertEquals(col("AGE"), Fold.resolveInto(projected, "YEARS"),
                "renamed column substitutes to its source");
        assertEquals(col("NAME"), Fold.resolveInto(projected, "NAME"));
        assertEquals(SqlExpr.Call.of(SqlFn.PLUS, col("A"), col("B")),
                Fold.resolveInto(projected, "SUM_AB"),
                "a pure-scalar computed projection substitutes inline;"
                        + " ROW-SPACE shapes (reducers/windows/exists/"
                        + "unnest) still refuse — see scalarInlineable");
        assertNull(Fold.resolveInto(projected, "DROPPED"),
                "a column not in the projection is gone");
    }

    // ------------------------------------------------------------------
    // §E3 M-N2 — JOIN-PAD PROVENANCE at the read door and the frame.
    // ------------------------------------------------------------------

    private static SqlSource.Table table(String name, String alias,
            String... cols) {
        List<com.legend.sql.OutputCol> outs = new java.util.ArrayList<>();
        for (String c : cols) {
            outs.add(new com.legend.sql.OutputCol(c,
                    com.legend.sql.SqlType.Scalar.BIGINT, false));
        }
        return new SqlSource.Table(name, alias, outs);
    }

    private static boolean nul(SqlExpr.Column c) {
        return ((com.legend.sql.TypeFact.Typed) c.type()).nullable();
    }

    @Test
    @DisplayName("a read resolved from a padded join side is may-be-null;"
            + " the driving side keeps its DDL claim")
    void joinPadFlipsResolvedReads() {
        SqlSource.Join left = new SqlSource.Join(
                table("A", "a0", "ID"), table("B", "b0", "BID"),
                SqlSource.Join.Kind.LEFT, new SqlExpr.BoolLit(true));
        // LEFT pads the right side only
        assertFalse(nul(Fold.sourceColumn(left, "ID")));
        assertTrue(nul(Fold.sourceColumn(left, "BID")));
        // FULL pads both
        SqlSource.Join full = new SqlSource.Join(
                table("A", "a1", "ID"), table("B", "b1", "BID"),
                SqlSource.Join.Kind.FULL, new SqlExpr.BoolLit(true));
        assertTrue(nul(Fold.sourceColumn(full, "ID")));
        assertTrue(nul(Fold.sourceColumn(full, "BID")));
        // INNER pads neither
        SqlSource.Join inner = new SqlSource.Join(
                table("A", "a2", "ID"), table("B", "b2", "BID"),
                SqlSource.Join.Kind.INNER, new SqlExpr.BoolLit(true));
        assertFalse(nul(Fold.sourceColumn(inner, "ID")));
        assertFalse(nul(Fold.sourceColumn(inner, "BID")));
        // a RIGHT join pads even the DRIVING (left-spine) side
        SqlSource.Join right = new SqlSource.Join(
                table("A", "a3", "ID"), table("B", "b3", "BID"),
                SqlSource.Join.Kind.RIGHT, new SqlExpr.BoolLit(true));
        assertTrue(nul(Fold.sourceColumnDriving(right, "ID")));
    }

    @Test
    @DisplayName("a joined frame's born outputs weaken the pad side")
    void joinPadWeakensFrameOutputs() {
        SqlSource.Join left = new SqlSource.Join(
                table("A", "a0", "ID"), table("B", "b0", "BID"),
                SqlSource.Join.Kind.LEFT, new SqlExpr.BoolLit(true));
        List<com.legend.sql.OutputCol> outs = List.of(
                new com.legend.sql.OutputCol("ID",
                        com.legend.sql.SqlType.Scalar.BIGINT, false),
                new com.legend.sql.OutputCol("BID",
                        com.legend.sql.SqlType.Scalar.BIGINT, false));
        List<com.legend.sql.OutputCol> padded = Fold.padJoinOutputs(outs,
                left, java.util.Optional.empty(), name -> true);
        assertFalse(padded.get(0).nullable(), "left side keeps DDL truth");
        assertTrue(padded.get(1).nullable(), "right side is pad-weakened");
        // prefix renames follow the outer spelling
        List<com.legend.sql.OutputCol> renamed = List.of(
                new com.legend.sql.OutputCol("ID",
                        com.legend.sql.SqlType.Scalar.BIGINT, false),
                new com.legend.sql.OutputCol("p_BID",
                        com.legend.sql.SqlType.Scalar.BIGINT, false));
        List<com.legend.sql.OutputCol> padded2 = Fold.padJoinOutputs(
                renamed, left, java.util.Optional.of("p_"), name -> true);
        assertFalse(padded2.get(0).nullable());
        assertTrue(padded2.get(1).nullable(),
                "renamed right column weakens under its outer name");
    }
}
