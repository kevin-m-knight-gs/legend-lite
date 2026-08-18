// SPDX-License-Identifier: Apache-2.0

package com.legend.exec;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * The {@code fetchDb*MetaData} K-native backend (E4.b,
 * JAVA_EVICTION_PLAN): catalog queries over the AMBIENT session's
 * {@code information_schema} — the database the raw writes actually
 * seeded (the F6.6 rule; the throwaway shadow-H2 replay is DELETED).
 * Grids carry the JDBC {@code DatabaseMetaData} column names and
 * ordinals the engine tests index into; identifier columns project
 * {@code upper(...)} — the engine-parity spelling (H2 uppercases
 * unquoted DDL identifiers, which is exactly what the corpus goldens
 * assert; the ambient store is case-preserving). Every VALUE is
 * database-produced; Java composes the catalog query (orchestration)
 * and decodes the result by contract (egress).
 */
public final class DbMetaData {

    private DbMetaData() {
    }

    /** System schemas the catalog queries exclude — the tests navigate
     * the corpus's own DDL, never the engine's internals. */
    private static final String NOT_SYSTEM =
            " NOT IN ('information_schema','pg_catalog')";

    /** The catalog query TEXT alone — the E4.e grid-read compiler
     * composes further SQL over it (the chain projection). */
    public static String fetchSql(String nativeFqn,
            @com.legend.Nullable String schemaPattern,
            @com.legend.Nullable String tablePattern,
            @com.legend.Nullable String columnPattern) {
        return switch (com.legend.compiler.element.type.PlatformTypes
                .fetchDbKind(nativeFqn)) {
            case SCHEMAS -> "SELECT upper(schema_name) AS \"TABLE_SCHEM\","
                    + " upper(catalog_name) AS \"TABLE_CATALOG\""
                    + " FROM information_schema.schemata"
                    + " WHERE schema_name" + NOT_SYSTEM
                    + like("upper(schema_name)", schemaPattern)
                    + " ORDER BY 1";
            case TABLES -> "SELECT upper(table_catalog) AS \"TABLE_CAT\","
                    + " upper(table_schema) AS \"TABLE_SCHEM\","
                    + " upper(table_name) AS \"TABLE_NAME\","
                    + " table_type AS \"TABLE_TYPE\", NULL AS \"REMARKS\""
                    + " FROM information_schema.tables"
                    + " WHERE table_schema" + NOT_SYSTEM
                    + like("upper(table_schema)", schemaPattern)
                    + like("upper(table_name)", tablePattern)
                    + " ORDER BY 2, 3";
            case COLUMNS -> "SELECT upper(table_catalog) AS \"TABLE_CAT\","
                    + " upper(table_schema) AS \"TABLE_SCHEM\","
                    + " upper(table_name) AS \"TABLE_NAME\","
                    + " upper(column_name) AS \"COLUMN_NAME\","
                    + " CASE " + BASE_TYPE + TYPE_CODE
                    + " ELSE 1111 END AS \"DATA_TYPE\","
                    + " " + BASE_TYPE_EXPR + " AS \"TYPE_NAME\","
                    // the engine APPENDS SQL_TYPE_NAME (the java.sql.Types
                    // NAME of the column's type) — FetchDbColumnsMetadata
                    + " " + BASE_TYPE_EXPR + " AS \"SQL_TYPE_NAME\""
                    + " FROM information_schema.columns"
                    + " WHERE table_schema" + NOT_SYSTEM
                    + like("upper(table_schema)", schemaPattern)
                    + like("upper(table_name)", tablePattern)
                    + like("upper(column_name)", columnPattern)
                    + " ORDER BY 2, 3, ordinal_position";
            case PRIMARY_KEYS -> throw new IllegalStateException(
                    "primary keys route through fetchPrimaryKeys (model"
                    + " facts — the ambient DDL omits PK constraints)");
        };
    }

    /** The PRIMARY_KEYS grid: the connection store's PK facts are MODEL
     * text (the ambient DDL deliberately omits the constraints —
     * milestoned re-seeds), composed as literal rows and
     * existence-filtered against the LIVE catalog in SQL; the database
     * produces every value, uppercased by the same engine-parity rule. */
    /** The PK catalog query TEXT alone (null = no facts → empty grid) —
     * the E4.e grid-read compiler composes over it. */
    public static @com.legend.Nullable String pkSql(List<String[]> facts,
            @com.legend.Nullable String schemaPattern,
            @com.legend.Nullable String tablePattern) {
        if (facts.isEmpty()) {
            return null;
        }
        StringBuilder values = new StringBuilder();
        for (String[] f : facts) {
            if (values.length() > 0) {
                values.append(", ");
            }
            values.append("(NULL, upper('").append(esc(f[0]))
                    .append("'), upper('").append(esc(f[1]))
                    .append("'), upper('").append(esc(f[2]))
                    .append("'), ").append(Integer.parseInt(f[3]))
                    .append(", NULL)");
        }
        return "SELECT * FROM (VALUES " + values
                + ") AS pk(\"TABLE_CAT\", \"TABLE_SCHEM\", \"TABLE_NAME\","
                + " \"COLUMN_NAME\", \"KEY_SEQ\", \"PK_NAME\")"
                + " WHERE EXISTS (SELECT 1 FROM information_schema.tables t"
                + " WHERE upper(t.table_name) = pk.\"TABLE_NAME\""
                + " AND (pk.\"TABLE_SCHEM\" = 'DEFAULT'"
                + " OR upper(t.table_schema) = pk.\"TABLE_SCHEM\"))"
                + like("pk.\"TABLE_SCHEM\"", schemaPattern)
                + like("pk.\"TABLE_NAME\"", tablePattern)
                + " ORDER BY 2, 3, 5";
    }

    private static String esc(String s) {
        return s.replace("'", "''");
    }

    /** The grid's projection names per kind — WE authored the catalog
     * projections above, so the names are compilation facts (the E4.e
     * chain compiler's columnNames / positional-index arithmetic). */
    public static List<String> gridColumns(
            com.legend.compiler.element.type.PlatformTypes.FetchDbKind k) {
        return switch (k) {
            case SCHEMAS -> List.of("TABLE_SCHEM", "TABLE_CATALOG");
            case TABLES -> List.of("TABLE_CAT", "TABLE_SCHEM",
                    "TABLE_NAME", "TABLE_TYPE", "REMARKS");
            case COLUMNS -> List.of("TABLE_CAT", "TABLE_SCHEM",
                    "TABLE_NAME", "COLUMN_NAME", "DATA_TYPE", "TYPE_NAME",
                    "SQL_TYPE_NAME");
            case PRIMARY_KEYS -> List.of("TABLE_CAT", "TABLE_SCHEM",
                    "TABLE_NAME", "COLUMN_NAME", "KEY_SEQ", "PK_NAME");
        };
    }

    /** The PK fact rows (schema, table, column, seq) of the connection's
     * store — the database referenced inside the connection argument's
     * typed tree (variables resolve through the enclosing lets),
     * include-closure merged. MODEL-fact collection (compilation-class),
     * never value evaluation. */
    public static List<String[]> pkFacts(
            com.legend.compiler.element.@com.legend.Nullable ModelContext ctx,
            com.legend.compiler.spec.typed.TypedSpec connArg,
            java.util.Map<String,
                    com.legend.compiler.spec.typed.TypedSpec> lets) {
        String dbFqn = ctx == null ? null
                : findDbRef(ctx, connArg, lets, new java.util.HashSet<>());
        if (ctx == null || dbFqn == null) {
            throw new com.legend.error.NotImplementedException(
                    "fetchDb primary keys — no database reference found"
                    + " in the connection argument");
        }
        List<String[]> facts = new ArrayList<>();
        collectPks(ctx, dbFqn, facts, new java.util.LinkedHashSet<>());
        return facts;
    }

    private static void collectPks(
            com.legend.compiler.element.ModelContext ctx, String dbFqn,
            List<String[]> out, java.util.Set<String> seen) {
        if (!seen.add(dbFqn)) {
            return;
        }
        var dbo = ctx.findDatabase(dbFqn);
        if (dbo.isEmpty()) {
            return;
        }
        var db = dbo.get();
        for (String inc : db.includes()) {
            collectPks(ctx, inc, out, seen);
        }
        for (var t : db.tables()) {
            tablePks("default", t, out);
        }
        for (var sd : db.schemas()) {
            for (var t : sd.tables()) {
                tablePks(sd.name(), t, out);
            }
        }
    }

    private static void tablePks(String schema,
            com.legend.model.DatabaseDefinition.TableDefinition t,
            List<String[]> out) {
        int seq = 1;
        for (var c : t.columns()) {
            if (c.primaryKey()) {
                out.add(new String[] {schema, t.name(), c.name(),
                        String.valueOf(seq++)});
            }
        }
    }

    private static @com.legend.Nullable String findDbRef(
            com.legend.compiler.element.ModelContext ctx,
            com.legend.compiler.spec.typed.TypedSpec n,
            java.util.Map<String,
                    com.legend.compiler.spec.typed.TypedSpec> lets,
            java.util.Set<String> visitedVars) {
        if (n instanceof
                com.legend.compiler.spec.typed.TypedPackageableRef pr
                && ctx.isDatabase(pr.fullPath())) {
            return pr.fullPath();
        }
        if (n instanceof com.legend.compiler.spec.typed.TypedVariable v) {
            var bound = lets.get(v.name());
            return bound != null && visitedVars.add(v.name())
                    ? findDbRef(ctx, bound, lets, visitedVars) : null;
        }
        for (var c : n.children()) {
            String hit = findDbRef(ctx, c, lets, visitedVars);
            if (hit != null) {
                return hit;
            }
        }
        return null;
    }

    /** The parameterized-type head ({@code DECIMAL(10,2)} → DECIMAL) —
     * the java.sql.Types NAME for every scalar the corpus declares. */
    private static final String BASE_TYPE =
            "CASE WHEN data_type LIKE 'DECIMAL%' THEN 'DECIMAL'"
            + " ELSE data_type END";

    private static final String BASE_TYPE_EXPR = "(" + BASE_TYPE + ")";

    /** {@code java.sql.Types} codes for the JDBC DATA_TYPE ordinal. */
    private static final String TYPE_CODE =
            " WHEN 'INTEGER' THEN 4 WHEN 'VARCHAR' THEN 12"
            + " WHEN 'BIGINT' THEN -5 WHEN 'SMALLINT' THEN 5"
            + " WHEN 'TINYINT' THEN -6 WHEN 'DOUBLE' THEN 8"
            + " WHEN 'FLOAT' THEN 6 WHEN 'REAL' THEN 7"
            + " WHEN 'DECIMAL' THEN 3 WHEN 'DATE' THEN 91"
            + " WHEN 'TIMESTAMP' THEN 93 WHEN 'BOOLEAN' THEN 16";

    /** JDBC LIKE-pattern filter ({@code %}/{@code _} wildcards — the
     * DatabaseMetaData pattern contract); a null pattern matches all. */
    private static String like(String expr,
            @com.legend.Nullable String pattern) {
        return pattern == null ? ""
                : " AND " + expr + " LIKE '" + pattern.replace("'", "''")
                        + "'";
    }

    /** A raw QUERY over the AMBIENT session (F6.6, audit §5 A9): reads
     * run against the database the raw writes actually seeded — CSV
     * loads and generator inserts included. The caller owns the
     * connection's lifecycle. */
}
