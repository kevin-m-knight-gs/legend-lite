// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.lineage;

import com.legend.sql.SqlExpr;
import com.legend.sql.SqlSelect;
import com.legend.sql.SqlSource;
import com.legend.sql.SqlUnion;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The #44 lineage analyzer over HAND-BUILT SQL plans: demand-driven
 * column collection with the engine's context vocabulary (value reads =
 * TableAliasColumn, join keys = JoinTreeNode), resolved through
 * subselect pass-throughs and union branches.
 */
class LineageScanTest {

    private static SqlExpr col(String t, String c) {
        return new SqlExpr.Column(t, c);
    }

    private static SqlExpr eq(SqlExpr l, SqlExpr r) {
        return new SqlExpr.Call(com.legend.sql.SqlFn.EQUAL, List.of(l, r));
    }

    private static SqlSelect select(List<SqlSelect.Projection> proj,
            SqlSource from, SqlExpr where) {
        return new SqlSelect(proj, false, from, where, List.of(), null, null,
                List.of(), null, null, List.of());
    }

    @Test
    void flatJoinWithFilter() {
        // SELECT t0.NAME AS name FROM personTable t0
        // LEFT JOIN db.firmTable t1 ON t0.FIRMID = t1.ID
        // WHERE t1.LEGAL = 'x'
        SqlSource from = new SqlSource.Join(
                new SqlSource.Table("personTable", "t0", List.of()),
                new SqlSource.Table("db.firmTable", "t1", List.of()),
                SqlSource.Join.Kind.LEFT,
                eq(col("t0", "FIRMID"), col("t1", "ID")));
        SqlSelect q = select(
                List.of(new SqlSelect.Projection(col("t0", "NAME"), "name")),
                from,
                eq(col("t1", "LEGAL"), new SqlExpr.StringLit("x")));
        assertEquals(List.of(
                "firmTable.ID <JoinTreeNode>",
                "firmTable.LEGAL <TableAliasColumn>",
                "personTable.FIRMID <JoinTreeNode>",
                "personTable.NAME <TableAliasColumn>"),
                ScanColumns.strings(q));
    }

    @Test
    void subselectPassThrough() {
        // SELECT t2.nm FROM (SELECT t0.NAME AS nm, t0.ID AS id FROM T t0) t2
        // JOIN U t3 ON t2.id = t3.fk
        SqlSelect inner = select(List.of(
                new SqlSelect.Projection(col("t0", "NAME"), "nm"),
                new SqlSelect.Projection(col("t0", "ID"), "id")),
                new SqlSource.Table("T", "t0", List.of()), null);
        SqlSource from = new SqlSource.Join(
                new SqlSource.Subselect(inner, "t2", null),
                new SqlSource.Table("U", "t3", List.of()),
                SqlSource.Join.Kind.INNER,
                eq(col("t2", "id"), col("t3", "fk")));
        SqlSelect q = select(
                List.of(new SqlSelect.Projection(col("t2", "nm"), "nm")),
                from, null);
        assertEquals(List.of(
                "T.ID <JoinTreeNode>",
                "T.NAME <TableAliasColumn>",
                "U.fk <JoinTreeNode>"),
                ScanColumns.strings(q));
    }

    @Test
    void unionBranchesBothResolve() {
        // SELECT t4.c FROM (SELECT a.x AS c FROM A a
        //                   UNION ALL SELECT b.y AS c FROM B b) t4
        SqlSelect left = select(
                List.of(new SqlSelect.Projection(col("a", "x"), "c")),
                new SqlSource.Table("A", "a", List.of()), null);
        SqlSelect right = select(
                List.of(new SqlSelect.Projection(col("b", "y"), "c")),
                new SqlSource.Table("B", "b", List.of()), null);
        SqlSelect q = select(
                List.of(new SqlSelect.Projection(col("t4", "c"), "c")),
                new SqlSource.Subselect(
                        new SqlUnion(List.of(left, right), true, List.of()),
                        "t4", null),
                null);
        assertEquals(List.of(
                "A.x <TableAliasColumn>",
                "B.y <TableAliasColumn>"),
                ScanColumns.strings(q));
    }
}
