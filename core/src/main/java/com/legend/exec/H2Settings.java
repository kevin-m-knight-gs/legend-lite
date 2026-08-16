// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.exec;

/**
 * The engine's H2 session settings (H2Manager parity) — ONE definition
 * shared by every H2 session opener (the harness replay oracle, the
 * {@code -Drcorpus.backend=h2} portability sweep, PCT's h2modern
 * adapter) so all targets open IDENTICAL sessions. Extracted from the
 * harness (F1.1, docs/FOUNDATIONS_PLAN.md) so nothing outside
 * {@code com.legend.harness} depends on the harness.
 */
public final class H2Settings {

    private H2Settings() {
    }

    /** JDBC-URL suffix, {@code ;KEY=VALUE} form. */
    public static final String SETTINGS =
            // CASE_INSENSITIVE_IDENTIFIERS mirrors DuckDB's matching —
            // the SAME recorded statements already ran there; quoted
            // model-DDL case vs unquoted corpus-INSERT case must not
            // diverge between the two targets
            ";MODE=LEGACY;DATABASE_TO_UPPER=false"
            + ";CASE_INSENSITIVE_IDENTIFIERS=TRUE;NON_KEYWORDS=ANY,"
            + "ASYMMETRIC,AUTHORIZATION,CAST,CURRENT_PATH,CURRENT_ROLE,"
            + "DAY,DEFAULT,ELSE,END,HOUR,KEY,MINUTE,MONTH,SECOND,"
            + "SESSION_USER,SET,SOME,SYMMETRIC,SYSTEM_USER,TO,UESCAPE,"
            + "USER,VALUE,WHEN,YEAR";
}
