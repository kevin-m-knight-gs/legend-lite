// SPDX-License-Identifier: Apache-2.0

package com.legend.exec;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@code fetchDb*MetaData} K-native backend: JDBC
 * {@link DatabaseMetaData} reads over the H2 SECOND TARGET. The corpus's
 * raw statements (recorded at the {@link RawSqlBoundary}) replay onto a
 * fresh default-mode H2 — engine-parity metadata semantics: H2 uppercases
 * unquoted DDL identifiers, which is exactly what the corpus goldens
 * assert (FETCHDBMETADATATESTSCHEMA1); DuckDB's case-preserving metadata
 * would diverge. Deliberately NOT {@code H2Verify}'s settings — that
 * connection disables DATABASE_TO_UPPER to mirror DuckDB for row replays.
 */
public final class DbMetaData {

    private DbMetaData() {
    }

    /** One fetched grid: column names + row values, a HOST value (the
     * orchestration channel never lowers these to SQL). */
    public record HostResultSet(List<String> columnNames,
            List<List<Object>> rows) {
    }

    private static final java.util.concurrent.atomic.AtomicInteger COUNTER =
            new java.util.concurrent.atomic.AtomicInteger();

    /** Default-upper H2 (unquoted DDL identifiers uppercase — the
     * fetchDb casing asserts) + case-insensitive MATCHING (the model DDL
     * quotes column names lowercase, corpus inserts reference them
     * unquoted — both targets must accept both spellings). */
    private static final String SETTINGS =
            ";MODE=LEGACY;CASE_INSENSITIVE_IDENTIFIERS=TRUE";

    /** java.sql.Types int -> field name (the engine's JavaSqlTypeNames). */
    private static final Map<Integer, String> SQL_TYPE_NAMES = sqlTypeNames();

    private static Map<Integer, String> sqlTypeNames() {
        Map<Integer, String> m = new LinkedHashMap<>();
        for (java.lang.reflect.Field f : java.sql.Types.class.getFields()) {
            try {
                m.putIfAbsent((Integer) f.get(null), f.getName());
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }
        return m;
    }

    public static HostResultSet fetch(String nativeFqn, String schemaPattern,
            String tablePattern, String columnPattern, List<String> recorded)
            throws SQLException {
        int id = COUNTER.getAndIncrement();
        try (Connection h2 = DriverManager.getConnection(
                "jdbc:h2:mem:fetchmeta" + id + SETTINGS, "sa", "")) {
            replay(h2, recorded);
            DatabaseMetaData md = h2.getMetaData();
            return switch (com.legend.compiler.element.type.PlatformTypes
                    .fetchDbKind(nativeFqn)) {
                case SCHEMAS -> grid(md.getSchemas(null, schemaPattern), false);
                case TABLES -> grid(md.getTables(null, schemaPattern,
                        tablePattern, null), false);
                // the engine APPENDS SQL_TYPE_NAME (java.sql.Types name of
                // DATA_TYPE at index 4) — FetchDbColumnsMetadata.java
                case COLUMNS -> grid(md.getColumns(null, schemaPattern,
                        tablePattern, columnPattern), true);
                case PRIMARY_KEYS -> grid(md.getPrimaryKeys(null,
                        schemaPattern, tablePattern), false);
            };
        }
    }

    /** A raw QUERY over the replayed H2 second target — the executeInDb
     * READ path (engine-parity column naming: COUNT(*) etc.). */
    public static HostResultSet query(String sql, List<String> recorded)
            throws SQLException {
        int id = COUNTER.getAndIncrement();
        try (Connection h2 = DriverManager.getConnection(
                "jdbc:h2:mem:execquery" + id + SETTINGS, "sa", "")) {
            replay(h2, recorded);
            try (Statement st = h2.createStatement()) {
                return grid(st.executeQuery(sql), false);
            }
        }
    }

    /** Best-effort replay: the recorded stream is H2-flavored by
     * definition; a statement H2 rejects is skipped (the metadata read
     * over what DID replay stays honest — asserts catch real gaps). */
    private static void replay(Connection h2, List<String> recorded)
            throws SQLException {
        try (Statement st = h2.createStatement()) {
            for (String blob : recorded == null ? List.<String>of() : recorded) {
                for (String one : blob.split(";\\s*\n|;\\s*$")) {
                    if (one.isBlank()) {
                        continue;
                    }
                    try {
                        st.execute(one);
                    } catch (SQLException e) {
                        // skipped — see javadoc
                        if (System.getenv("LL_TMP_DEBUG") != null) {
                            System.err.println("[h2-meta-replay] skip: "
                                    + one.strip().split("\\n")[0] + " => "
                                    + String.valueOf(e.getMessage())
                                            .split("\\n")[0]);
                        }
                    }
                }
            }
        }
    }

    private static HostResultSet grid(ResultSet rs, boolean sqlTypeName)
            throws SQLException {
        try (rs) {
            int n = rs.getMetaData().getColumnCount();
            List<String> names = new ArrayList<>(n + 1);
            for (int i = 1; i <= n; i++) {
                names.add(rs.getMetaData().getColumnName(i));
            }
            if (sqlTypeName) {
                names.add("SQL_TYPE_NAME");
            }
            List<List<Object>> rows = new ArrayList<>();
            while (rs.next()) {
                List<Object> row = new ArrayList<>(n + 1);
                for (int i = 1; i <= n; i++) {
                    row.add(rs.getObject(i));
                }
                if (sqlTypeName) {
                    // DATA_TYPE is getColumns' 5th column (index 4)
                    int dt = ((Number) row.get(4)).intValue();
                    String tn = SQL_TYPE_NAMES.get(dt);
                    if (tn == null) {
                        throw new IllegalStateException(
                                "no java.sql.Types name for " + dt);
                    }
                    row.add(tn);
                }
                rows.add(row);
            }
            return new HostResultSet(names, rows);
        }
    }
}
