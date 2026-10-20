// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.lowering;

import com.legend.sql.OutputCol;
import com.legend.sql.SqlExpr;
import com.legend.sql.SqlQuery;
import com.legend.sql.SqlSelect;
import com.legend.sql.SqlSource;
import com.legend.sql.SqlType;
import com.legend.sql.dialect.DuckDb;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** A grid side's row ordinal is computed where its plan defines the order (SideOrdinal; channel B's reduce and pivot
 *  tests on MariaDB and Trino, 2026-10-20, whose databases kept neither a sorted CTE's order nor a literal's). */
class SideOrdinalTest {

    private static final OutputCol A = new OutputCol("a", SqlType.Scalar.INTEGER, false);

    private static SqlSource.Values literal() {
        return new SqlSource.Values(List.of(List.of(new SqlExpr.IntLit(2)), List.of(new SqlExpr.IntLit(1))),
                List.of("a"), "t", List.of(A));
    }

    @Test
    void aLiteralsRowsCarryTheirIndex() {
        SqlQuery q = SideOrdinal.withOrdinal(SqlSelect.starOf(literal()).withOutputs(List.of(A)));
        assertEquals(List.of("a", SideOrdinal.ORD), q.outputs().stream().map(OutputCol::name).toList());
        assertEquals("""
                SELECT *
                FROM (VALUES (2, 1), (1, 2)) AS t(a, __ord)""", new DuckDb().render(q));
    }

    @Test
    void aSortedSideIsNumberedByItsOwnKeys() {
        SqlSelect sorted = SqlSelect.starOf(literal()).withOutputs(List.of(A))
                .withOrderBy(List.of(new SqlSelect.SortKey(SqlExpr.Column.of("t", A), false, null, null)));
        String sql = new DuckDb().render(SideOrdinal.withOrdinal(sorted));
        assertEquals(1, sql.split("ROW_NUMBER\\(\\) OVER \\(ORDER BY __o\\.a DESC", -1).length - 1, sql);
    }

    @Test
    void aSideWhoseOrderThePlanDoesNotDefineIsNumberedWhereItIsRead() {
        SqlSelect grouped = SqlSelect.starOf(literal()).withOutputs(List.of(A)).withGroupBy(
                List.of(SqlExpr.Column.of("t", A)));
        String sql = new DuckDb().render(SideOrdinal.withOrdinal(grouped));
        assertEquals(1, sql.split("ROW_NUMBER\\(\\) OVER \\(\\)", -1).length - 1, sql);
    }
}
