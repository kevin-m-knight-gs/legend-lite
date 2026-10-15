// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.sql.dialect;

/**
 * The platform's wire vocabulary for a result column's type ({@code BIGINT}, {@code DOUBLE}, {@code VARCHAR},
 * {@code DECIMAL(p,s)}, {@code TIMESTAMP}, {@code STRUCT}, {@code ARRAY}, {@code MAP}, {@code JSON}, ...): the names the
 * type census compares a plan's label with, and a late-bound or pivot-made column is typed by. A dialect reads its
 * driver's own names into it ({@link SqlDialect#wireTypeName}); folded here are the names that say one type whichever
 * driver gives them: the SQL type names' common synonyms and the spellings some drivers give them (Postgres's
 * {@code int8} and {@code _int4}, Trino's {@code ROW(...)}, Spark's {@code STRING}). A driver that means another type
 * by one reads it itself (SQLite's {@code NUMERIC}), and so does a driver whose name does not say one type alone
 * (Snowflake's {@code NUMBER}). The dialect program's Phase 4, 2026-10-15: until then the census held this table and
 * each database's own names, keyed by the dialect's class name.
 */
public final class WireNames {

    private WireNames() {
    }

    /** {@code name} in the wire vocabulary, by the names that say one type whichever driver gives them; {@code ""}
     *  for none. */
    public static String fold(@com.legend.base.Nullable String name) {
        if (name == null) {
            return "";
        }
        String n = name.toUpperCase(java.util.Locale.ROOT).trim();
        if (n.startsWith("STRUCT") || n.startsWith("ROW(")) {
            return "STRUCT";
        }
        if (n.startsWith("MAP")) {
            return "MAP";
        }
        // an array named by its element ({@code INTEGER[]}) or by its element type's internal name ({@code _int4})
        if (n.endsWith("[]") || n.startsWith("ARRAY") || n.startsWith("_")) {
            return "ARRAY";
        }
        if (n.equals("CHARACTER VARYING") || n.startsWith("VARCHAR")) {
            return "VARCHAR";
        }
        // the same types under their other common names
        switch (n) {
            case "INT2":
                return "SMALLINT";
            case "INT4", "INT":
                return "INTEGER";
            case "INT8":
                return "BIGINT";
            case "FLOAT4":
                return "FLOAT";
            case "FLOAT8":
                return "DOUBLE";
            case "BOOL":
                return "BOOLEAN";
            case "TEXT", "BPCHAR", "NAME", "STRING":
                return "VARCHAR";
            case "JSONB", "VARIANT":
                return "JSON";
            default:
                break;
        }
        if (n.startsWith("DECIMAL") || n.startsWith("NUMERIC")) {
            return n.replace("NUMERIC", "DECIMAL").replace(" ", "");
        }
        if (n.startsWith("TIMESTAMP")) {
            return "TIMESTAMP";
        }
        return n;
    }
}
