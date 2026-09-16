// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.sql.dialect;

import com.legend.sql.SqlDdl.ColumnType;

/**
 * THE DDL SPELLINGS a dialect composes from: the store's declared column
 * type as SQL text, and the identifier rules the engine's own H2 DDL uses.
 * A dialect's {@link SqlDialect#ddlType} / {@link SqlDialect#ddlIdentifier}
 * pick from here and override only what its target spells differently —
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

    /** The ENGINE's column-name rule for H2 DDL TEXT — processColumnName
     * = columnNameToIdentifier THEN processIdentifierWithQuoteChar
     * (dbExtension.pure:611-614, extensionDefaults.pure:557-563). H2
     * leaves columnNameToIdentifier UNSET, and the DbConfig accessor
     * defaults it to IDENTITY (dbExtension.pure:155-158) — the
     * kerberos/date/first uppercase trio belongs to the dialects that
     * opt in (redshift, sqlserver, ...), NOT H2; testDDL.pure's goldens
     * pin bare {@code date}. F3.5 (audit A16): the engine's THREE quote
     * triggers — reserved word, pre-quoted, SPACE-BEARING. Reserved words
     * come from the dialect lexicon — the ONE H2 list. */
    public static String engineColumnName(String name) {
        if (name.startsWith("\"")
                || Lexicon.H2_ENGINE_TEXT.reservedWords()
                        .contains(name.toLowerCase(java.util.Locale.ROOT))
                || name.contains(" ")) {
            return '"' + name.replace("\"", "") + '"';
        }
        return name;
    }

    /** The H2 EXECUTION identifier: the engine's rule plus the execution
     *  necessity — a name that is not a plain identifier quotes. */
    public static String h2ExecIdentifier(String name) {
        if (!name.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            return '"' + name.replace("\"", "") + '"';
        }
        return engineColumnName(name);
    }
}
