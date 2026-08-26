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
        List<String> names = new ArrayList<>();
        for (String[] c : probeColumns(sql, conn, dialect)) {
            names.add(c[0]);
        }
        return names;
    }

    /** The grid's TYPED columns for the boundary resolver's oracle
     * (§4bZ-U follow-on): the database's own column type when the SQL
     * type maps to a Pure primitive; the trusted-Any wildcard column
     * otherwise — an upgrade over the old names-only stamp, never a
     * new wall. */
    public static List<com.legend.compiler.element.type.Type.Column>
            probeTypedColumns(String sql, Connection conn,
            com.legend.sql.dialect.SqlDialect dialect) throws SQLException {
        List<com.legend.compiler.element.type.Type.Column> cols =
                new ArrayList<>();
        for (String[] c : probeColumns(sql, conn, dialect)) {
            com.legend.compiler.element.type.Type t =
                    Executor.pureOfSqlTypeOrNull(c[1]);
            cols.add(t == null
                    ? com.legend.compiler.element.type.Type.RelationType
                            .trustedColumn(c[0])
                    : new com.legend.compiler.element.type.Type.Column(
                            c[0], t, com.legend.compiler.element.type
                                    .Multiplicity.Bounded.ZERO_ONE));
        }
        return cols;
    }

    /** The grid's projection (name, SQL type name) pairs — the SAME
     * LIMIT-0 metadata read, types included. */
    public static List<String[]> probeColumns(String sql, Connection conn,
            com.legend.sql.dialect.SqlDialect dialect) throws SQLException {
        SqlSelect probe = SqlSelect.starOf(
                new SqlSource.RawSql(sql, "_p", List.of()))
                .withLimit(0L);
        try (var st = conn.createStatement();
                var rs = st.executeQuery(dialect.render(probe))) {
            var md = rs.getMetaData();
            List<String[]> cols = new ArrayList<>(md.getColumnCount());
            for (int i = 1; i <= md.getColumnCount(); i++) {
                cols.add(new String[] {md.getColumnLabel(i),
                        md.getColumnTypeName(i)});
            }
            return cols;
        }
    }
}
