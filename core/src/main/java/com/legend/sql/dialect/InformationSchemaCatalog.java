// SPDX-License-Identifier: Apache-2.0

package com.legend.sql.dialect;

import com.legend.sql.SqlSource;

import java.util.List;

/**
 * The catalog grids ({@link SqlSource.Catalog}) read from the SQL standard's {@code information_schema}: the dialects
 * whose databases are proven to keep it so call this, each saying where it was proven; every other refuses the grid
 * (the dialect program's Phase 3, 2026-10-16: this text was the compiler's, {@code compiler/spec/CatalogGrids}, and is
 * unchanged).
 *
 * <p>Each grid has the columns JDBC's {@code DatabaseMetaData} fixes, named by the source's outputs, in order. Its
 * identifier columns project {@code upper(...)}, the engine's spelling (its reference database uppercases unquoted
 * identifiers, which is what the engine's tests assert; the session's store is case-preserving). A pattern is a
 * {@code LIKE} pattern ({@code %} and {@code _}, the {@code DatabaseMetaData} contract); a null pattern matches every
 * name.
 */
final class InformationSchemaCatalog {

    private InformationSchemaCatalog() {
    }

    /** The system schemas no grid lists: the tests read their own DDL, never the database's internals. */
    private static final String NOT_SYSTEM = " NOT IN ('information_schema','pg_catalog')";

    /** How a database's catalog names a column's type. {@code typeName} is the expression of the name its JDBC driver
     *  reports ({@code TYPE_NAME}); {@code typeKey} the expression of the name its {@code java.sql.Types} code is
     *  read by; {@code codes} each such name's code, in order. A name not among them is OTHER, as
     *  {@code DatabaseMetaData} reports a type it has no code for. */
    record Types(String typeName, String typeKey, java.util.Map<String, TypeCode> codes) {
        Types {
            // a copy in the order given, read-only. Not Collections.unmodifiableSequencedMap: TeaVM's class library
            // (the browser planner, //wasm:planner) has none
            codes = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(codes));
        }
    }

    /** The {@code java.sql.Types} codes a catalog grid reports, each named as its {@code java.sql.Types} field
     *  (legend-pure's {@code JavaSqlTypeNames}, the {@code SQL_TYPE_NAME} column): the JDBC specification's numbers. */
    enum TypeCode {
        CHAR(1), NUMERIC(2), DECIMAL(3), INTEGER(4), SMALLINT(5), FLOAT(6), REAL(7), DOUBLE(8), VARCHAR(12),
        BOOLEAN(16), DATE(91), TIME(92), TIMESTAMP(93), BINARY(-2), VARBINARY(-3), BIGINT(-5), TINYINT(-6),
        BLOB(2004), TIMESTAMP_WITH_TIMEZONE(2014);

        private final int code;

        TypeCode(int code) {
            this.code = code;
        }
    }

    /** The grid's query, written by {@code renderer} (its string literals), its types named by {@code types}. The
     *  {@code DATA_TYPE} column is the type's {@code java.sql.Types} code and {@code SQL_TYPE_NAME} that code's name,
     *  as legend-pure's {@code FetchDbColumnsMetaData} appends it to {@code DatabaseMetaData.getColumns}. */
    static SqlWriter write(SqlWriter writer, SqlSource.Catalog c, AnsiSqlRenderer renderer, Types types) {
        List<String> projections = switch (c.grid()) {
            case SCHEMAS -> List.of("upper(schema_name)", "upper(catalog_name)");
            case TABLES -> List.of("upper(table_catalog)", "upper(table_schema)", "upper(table_name)", "table_type",
                    "NULL");
            case COLUMNS -> List.of("upper(table_catalog)", "upper(table_schema)", "upper(table_name)",
                    "upper(column_name)", typeCase(types, t -> String.valueOf(t.code), "1111", renderer),
                    types.typeName(), typeCase(types, t -> renderer.stringLit(t.name()), renderer.stringLit("OTHER"),
                            renderer));
            case PRIMARY_KEYS -> List.of("NULL", "upper(k.table_schema)", "upper(k.table_name)",
                    "upper(k.column_name)", "k.ordinal_position", "NULL");
        };
        if (projections.size() != c.outputs().size()) {
            throw new IllegalStateException("the " + c.grid() + " catalog grid has " + projections.size()
                    + " columns, its source " + c.outputs().size());
        }
        writer.append("SELECT ");
        for (int i = 0; i < projections.size(); i++) {
            writer.append(i == 0 ? "" : ", ").append(projections.get(i)).append(" AS \"")
                    .append(c.outputs().get(i).name()).append("\"");
        }
        return switch (c.grid()) {
            case SCHEMAS -> like(writer.append(" FROM information_schema.schemata WHERE schema_name" + NOT_SYSTEM),
                    "upper(schema_name)", c.schemaPattern(), renderer).append(" ORDER BY 1");
            case TABLES -> like(like(writer.append(" FROM information_schema.tables WHERE table_schema" + NOT_SYSTEM),
                    "upper(table_schema)", c.schemaPattern(), renderer),
                    "upper(table_name)", c.tablePattern(), renderer).append(" ORDER BY 2, 3");
            case COLUMNS -> like(like(like(writer.append(" FROM information_schema.columns WHERE table_schema"
                    + NOT_SYSTEM), "upper(table_schema)", c.schemaPattern(), renderer),
                    "upper(table_name)", c.tablePattern(), renderer),
                    "upper(column_name)", c.columnPattern(), renderer).append(" ORDER BY 2, 3, ordinal_position");
            // the standard key_column_usage and table_constraints pair (the tables are created with their keys)
            case PRIMARY_KEYS -> like(like(writer.append(" FROM information_schema.key_column_usage k"
                    + " JOIN information_schema.table_constraints t ON k.constraint_name = t.constraint_name"
                    + " AND k.table_schema = t.table_schema AND k.table_name = t.table_name"
                    + " WHERE t.constraint_type = 'PRIMARY KEY' AND k.table_schema" + NOT_SYSTEM),
                    "upper(k.table_schema)", c.schemaPattern(), renderer),
                    "upper(k.table_name)", c.tablePattern(), renderer).append(" ORDER BY 2, 3, 5");
        };
    }

    /** {@code CASE <typeKey> WHEN <name> THEN <value> ... ELSE <otherwise> END}, a value for each type name. */
    private static String typeCase(Types types, java.util.function.Function<TypeCode, String> value,
            String otherwise, AnsiSqlRenderer renderer) {
        StringBuilder sql = new StringBuilder("CASE ").append(types.typeKey());
        types.codes().forEach((name, code) -> sql.append(" WHEN ").append(renderer.stringLit(name)).append(" THEN ")
                .append(value.apply(code)));
        return sql.append(" ELSE ").append(otherwise).append(" END").toString();
    }

    private static SqlWriter like(SqlWriter writer, String expr, @com.legend.base.Nullable String pattern,
            AnsiSqlRenderer renderer) {
        return pattern == null ? writer : writer.append(" AND " + expr + " LIKE ").append(renderer.stringLit(pattern));
    }
}
