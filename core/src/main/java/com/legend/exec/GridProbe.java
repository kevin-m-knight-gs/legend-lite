// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.exec;

import com.legend.sql.SqlSelect;
import com.legend.sql.SqlSource;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * The LIMIT-0 metadata probe — the ONE execution-bound ingredient of
 * late-bound raw-grid resolution (staged compilation, Invariant 7): the
 * REWRITE pass lives in {@code resolver.RawGridSchema} and takes the
 * probed column roster as INPUT through its {@code SchemaOracle}; this
 * class is the executor's oracle implementation. A schema read, never
 * values (the E1 probe discipline; moved from ResultNav at its Phase 1c
 * deletion). The one {@code new SqlSource.RawSql} here is a chartered
 * construction site (RawSqlLedgerTest register); the text is the
 * AUTHORED statement, MIR-rendered through the dialect like every
 * query.
 */
public final class GridProbe {

    private GridProbe() {
    }

    /** The grid's projection NAMES for the authored SQL. */
    public static List<String> probeNames(String sql, Connection conn,
            com.legend.sql.dialect.SqlDialect dialect) throws SQLException {
        SqlSelect probe = SqlSelect.starOf(
                new SqlSource.RawSql(sql, "_p", List.of()))
                .withLimit(0L);
        try (var st = conn.createStatement();
                var rs = st.executeQuery(dialect.render(probe))) {
            var md = rs.getMetaData();
            List<String> names = new ArrayList<>(md.getColumnCount());
            for (int i = 1; i <= md.getColumnCount(); i++) {
                names.add(md.getColumnLabel(i));
            }
            return names;
        }
    }
}
