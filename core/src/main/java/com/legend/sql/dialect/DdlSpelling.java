// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.sql.dialect;

import com.legend.sql.SqlDdl.ColumnType;

/**
 * THE DDL TYPE SPELLINGS a dialect composes from: the store's declared column
 * type as SQL text (the engine text's identifier rules are its own,
 * {@link EngineStyleH2}). A dialect's {@code ddlType} picks from here and
 * overrides only what its target spells differently —
 * the same shape as its query rendering (one compiler, dialect strategies;
 * no target is decided by a flag outside the dialect).
 */
public final class DdlSpelling {

    private DdlSpelling() {
    }

    /** The H2 spelling of a declared column type — the base every target
     *  shares; a dialect overrides the few it spells otherwise. */
    public static String h2Type(ColumnType t) {
        return switch (t) {
            case ColumnType.Plain p -> switch (p.kind()) {
                case BIGINT -> "BIGINT";
                case SMALLINT -> "SMALLINT";
                case TINYINT -> "TINYINT";
                case INTEGER -> "INTEGER";
                case FLOAT -> "FLOAT";
                case DOUBLE -> "DOUBLE";
                case REAL -> "REAL";
                case BIT -> "BIT";
                case TIMESTAMP -> "TIMESTAMP";
                case DATE -> "DATE";
                case JSON -> "JSON";
                case VARCHAR -> "VARCHAR";
                case OTHER, DISTINCT, ARRAY, OBJECT -> throw new IllegalStateException(
                        "no DDL spelling for declared column type " + p.kind());
            };
            case ColumnType.Sized z -> z.kind() + "(" + z.size() + ")";
            case ColumnType.Scaled c -> c.kind() + "(" + c.precision() + ", " + c.scale() + ")";
        };
    }

    /** THE engine {@code dataTypeToSqlText} spelling
     * (platform_store_relational/functions.pure:68-96), spelled ONCE —
     * the engine-text DDL and the metamodel walk's dataTypeToSqlText
     * native both read here (ratified E4 design: no type text is spelled
     * twice). Deltas from the H2 base: Integer spells INT; Other spells
     * OTHER (execution walls — a column of type Other cannot be created). */
    public static String engineText(ColumnType t) {
        if (t instanceof ColumnType.Plain p && p.kind() == ColumnType.Kind.INTEGER) {
            return "INT";
        }
        if (t instanceof ColumnType.Plain p && p.kind() == ColumnType.Kind.OTHER) {
            return "OTHER";
        }
        return h2Type(t);
    }
}
