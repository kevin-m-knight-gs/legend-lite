// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.sql.dialect;

import com.legend.sql.OutputCol;
import com.legend.sql.SqlExpr;
import com.legend.sql.SqlFn;
import com.legend.sql.SqlSelect;
import com.legend.sql.SqlSource;
import com.legend.sql.SqlType;
import com.legend.sql.SqlWith;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Snowflake dialect's spellings no local database runs, pinned on the rendered text; each measured on the
 * Snowflake lane.
 */
class SnowflakeDialectTest {

    private static SqlSelect selectOne(SqlSource from) {
        return new SqlSelect(List.of(new SqlSelect.Projection(new SqlExpr.IntLit(1), "one", null)), false, from, null,
                List.of(), null, null, List.of(), null, null, List.of());
    }

    /**
     * A projection that IS error() is raised in its column's type, as a CASE branch is (2026-10-21): a bare raise is
     * text, and the TDS text's date print of the column, TO_CHAR with a format, was refused before the statement ran
     * (testNewDateError's {@code date(2016, 13)}, on the lane at 6c8de4e).
     */
    @Test
    void aProjectedRaiseIsTypedAsItsColumn() {
        SqlExpr raise = new SqlExpr.Call(SqlFn.ERROR, List.of(new SqlExpr.StringLit("Invalid month: 13")));
        String sql = new Snowflake().render(new SqlSelect(List.of(new SqlSelect.Projection(raise, "value",
                new OutputCol("value", SqlType.Scalar.TIMESTAMP, false))), false, new SqlSource.Dual(), null,
                List.of(), null, null, List.of(), null, null, List.of()));
        assertTrue(sql.contains("('Invalid month: 13') || CHR(31) AS TIMESTAMP_NTZ) AS \"value\""), sql);
    }

    /**
     * A CTE's name is quoted at its head as at every reference (2026-10-21): written bare it folds to upper case, and
     * the database judge's verdict reads its CTEs as tables, which Snowflake quotes -- "Object '"__se"' does not
     * exist" on every channel B test of the lane at 6c8de4e.
     */
    @Test
    void aCteIsNamedAsItIsRead() {
        List<OutputCol> outs = List.of(new OutputCol("n", SqlType.Scalar.BIGINT, false));
        SqlSource asTable = new SqlSource.Table("__se", "e", outs, false, Map.of(), com.legend.sql.NameQuoting.BARE);
        SqlSource asCte = new SqlSource.Cte("__se", "a", outs);
        String sql = new Snowflake().render(new SqlWith(List.of(new SqlWith.Cte("__se", selectOne(new SqlSource.Dual()))),
                selectOne(new SqlSource.Join(asTable, asCte, SqlSource.Join.Kind.CROSS, null))));
        assertTrue(sql.startsWith("WITH \"__se\" AS ("), sql);
        assertTrue(sql.contains("\"__se\" AS \"e\""), sql);
        assertTrue(sql.contains("\"__se\" AS \"a\""), sql);
        assertFalse(sql.matches("(?s).*[^\"]__se[^\"].*"), sql);
    }
}
