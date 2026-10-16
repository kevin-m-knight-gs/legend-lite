// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.sql;

/**
 * A JSON value's kind, as the IR compares {@link SqlFn#JSON_TYPE}'s answer with one ({@link SqlExpr.JsonKindLit}).
 * Until 2026-10-15 each rode as DuckDB's own {@code json_type} name in a string ({@code 'OBJECT'}, {@code 'VARCHAR'},
 * {@code 'UBIGINT'}), which every other dialect's JSON_TYPE emulated (the dialect program's Phase 2, decision D3:
 * the meanings stay the platform's, the strings become typed). Each dialect names a kind as its JSON_TYPE answers it.
 */
public enum JsonKind {
    OBJECT, ARRAY, NULL, STRING,
    /** A whole number that fits a signed 64-bit integer. */
    INTEGER,
    /** A whole number past the signed 64-bit range, up to the unsigned one's. */
    UNSIGNED_INTEGER,
    /** A number with a fraction or an exponent. */
    FLOAT,
    BOOLEAN
}
