// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.exec;

import com.legend.model.DatabaseDefinition;
import com.legend.model.RelationalDataType;

/**
 * DDL rendering from the COMPILED store model — the K-native
 * {@code dropAndCreateTableInDb} boundary (the real engine's
 * {@code toDDL.pure} walks the Database metamodel; legend-lite renders
 * from {@link DatabaseDefinition}). Lives in the EXEC (K-phase) package —
 * the SQL layer ({@code com.legend.sql}) stays standalone and never sees
 * the store model. Statements come out H2-flavored and
 * unquoted, exactly like the corpus's own raw seeds — the executing
 * {@link RawSqlBoundary} owns quoting and type adaptation, ONE
 * adaptation path for hand-written and model-derived DDL alike.
 *
 * <p>No PRIMARY KEY / NOT NULL constraints are emitted — a DELIBERATE
 * DIVERGENCE from the engine (its dropAndCreateTableInDb defaults
 * applyConstraints=true): milestoned test tables seed several versions
 * of one id, and DuckDB would reject the re-seeds the engine's H2 setup
 * tolerates. Parity here is with legend-lite's own legacy replay, not
 * the engine.
 */
public final class Ddl {

    private Ddl() {
    }

    /** {@code Drop table if exists s.T;} — the engine's dropTableStatement spelling. */
    public static String dropTable(String schema, String table) {
        return "Drop table if exists " + qualify(schema, table) + ";";
    }

    public static String createTable(DatabaseDefinition.TableDefinition def,
            String schema) {
        StringBuilder sb = new StringBuilder("Create Table ")
                .append(qualify(schema, def.name())).append("(");
        boolean first = true;
        for (DatabaseDefinition.ColumnDefinition col : def.columns()) {
            if (!first) {
                sb.append(", ");
            }
            first = false;
            // FULL-name quoting: corpus columns carry spaces and reserved
            // words ('Previous Fiscal Week Year', 'FIRST NAME') — the
            // dialect's quoteCreateColumns passes quoted heads through
            sb.append('"').append(col.name()).append('"').append(' ')
                    .append(spell(col.dataType()));
        }
        return sb.append(");").toString();
    }

    /** H2 2.1.214 reserved words (h2Extension2_1_214.pure:180-192) — the
     * engine's DDL-TEXT column quoting set (the second target's dialect,
     * same golden surface as the plan channel). */
    private static final java.util.Set<String> H2_RESERVED = java.util.Set.of(
            "all", "and", "array", "as", "between", "case", "check",
            "constraint", "cross", "current_catalog", "current_date",
            "current_schema", "current_time", "current_timestamp",
            "current_user", "distinct", "except", "exists", "false", "fetch",
            "for", "foreign", "from", "full", "group", "having", "if", "in",
            "inner", "intersect", "interval", "is", "join", "left", "like",
            "limit", "localtime", "localtimestamp", "minus", "natural", "not",
            "null", "offset", "on", "or", "order", "primary", "qualify",
            "row", "rownum", "select", "table", "true", "union", "unique",
            "unknown", "using", "values", "where", "window", "with",
            "_rowid_", "both", "groups", "ilike", "leading", "over",
            "partition", "range", "regexp", "rows", "top", "trailing");

    /** The ENGINE's createTableStatement TEXT (translateCreateTable-
     * StatementDefault, extensionDefaults.pure:609-620): reserved-word
     * column quoting, engine type spellings (INT), NULL / NOT NULL
     * nullability, trailing {@code , PRIMARY KEY(...)}. TEXT ONLY — the
     * EXECUTION form ({@link #createTable}) stays constraint-free (the
     * deliberate DuckDB re-seed divergence in this file's header). */
    public static String createTableStatementText(
            DatabaseDefinition.TableDefinition def, String schema) {
        StringBuilder sb = new StringBuilder("Create Table ")
                .append(qualify(schema, def.name())).append("(");
        boolean first = true;
        for (DatabaseDefinition.ColumnDefinition col : def.columns()) {
            if (!first) {
                sb.append(",");
            }
            first = false;
            sb.append(H2_RESERVED.contains(
                    col.name().toLowerCase(java.util.Locale.ROOT))
                    ? '"' + col.name() + '"' : col.name())
                    .append(' ').append(engineSpell(col.dataType()))
                    .append(col.primaryKey() || col.notNull()
                            ? " NOT NULL" : " NULL");
        }
        java.util.List<String> pks = def.columns().stream()
                .filter(DatabaseDefinition.ColumnDefinition::primaryKey)
                .map(DatabaseDefinition.ColumnDefinition::name).toList();
        if (!pks.isEmpty()) {
            sb.append(", PRIMARY KEY(").append(String.join(",", pks))
                    .append(')');
        }
        return sb.append(");").toString();
    }

    /** The ENGINE's dropTableStatement TEXT (translateDropTable-
     * StatementDefault): {@code Drop table if exists <schema.>table;}. */
    public static String dropTableStatementText(String schema, String table) {
        return "Drop table if exists " + qualify(schema, table) + ";";
    }

    /** dataTypeToSqlText parity (platform_store_relational/functions.pure
     * :68-96) — differs from the EXECUTION spelling only where the engine
     * text does (Integer spells INT). */
    private static String engineSpell(RelationalDataType t) {
        return t instanceof RelationalDataType.Integer_ ? "INT" : spell(t);
    }

    private static String qualify(String schema, String table) {
        return schema == null || schema.isEmpty() || "default".equals(schema)
                ? table : schema + "." + table;
    }

    /** The H2-flavored spelling of a store column type. */
    private static String spell(RelationalDataType t) {
        return switch (t) {
            case RelationalDataType.Bool ignored -> "BOOLEAN";
            case RelationalDataType.BigInt ignored -> "BIGINT";
            case RelationalDataType.SmallInt ignored -> "SMALLINT";
            case RelationalDataType.TinyInt ignored -> "TINYINT";
            case RelationalDataType.Integer_ ignored -> "INTEGER";
            // H2-FLAVORED on purpose: this DDL text flows through the
            // RawSqlBoundary like every hand-written
            // statement — ONE adaptation path (FLOAT->DOUBLE, BIT->BOOLEAN
            // live in DuckDb.quoteCreateColumns; audit 19 restored the
            // contract this file's header states).
            case RelationalDataType.Float_ ignored -> "FLOAT";
            case RelationalDataType.Double_ ignored -> "DOUBLE";
            case RelationalDataType.Real ignored -> "REAL";
            case RelationalDataType.Bit ignored -> "BIT";
            case RelationalDataType.Timestamp ignored -> "TIMESTAMP";
            case RelationalDataType.Date_ ignored -> "DATE";
            case RelationalDataType.Varchar v -> "VARCHAR(" + v.size() + ")";
            case RelationalDataType.Char_ c -> "CHAR(" + c.size() + ")";
            case RelationalDataType.Binary b -> "BINARY(" + b.size() + ")";
            case RelationalDataType.Varbinary v -> "VARBINARY(" + v.size() + ")";
            case RelationalDataType.Decimal d ->
                    "DECIMAL(" + d.precision() + ", " + d.scale() + ")";
            case RelationalDataType.Numeric n ->
                    "NUMERIC(" + n.precision() + ", " + n.scale() + ")";
            case RelationalDataType.SemiStructured ignored -> "JSON";
            // No DDL spelling by design — EXPLICIT so a new variant is a
            // compile error here, not a runtime surprise (T3.1).
            case RelationalDataType.Distinct ignored -> throw new IllegalStateException(
                    "no DDL spelling for store column type " + t);
            case RelationalDataType.Other ignored -> throw new IllegalStateException(
                    "no DDL spelling for store column type " + t);
            case RelationalDataType.Array ignored -> throw new IllegalStateException(
                    "no DDL spelling for store column type " + t);
            case RelationalDataType.Object_ ignored -> throw new IllegalStateException(
                    "no DDL spelling for store column type " + t);
        };
    }
}
