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

    /** The engine's session EXACTLY as H2Defaults spells it
     * (legend-engine-xt-relationalStore-h2-execution-2.1.214,
     * H2Defaults.java): case-SENSITIVE identifiers, no
     * DATABASE_TO_UPPER override, and the engine's own NON_KEYWORDS
     * list (which includes OVER — ours does not). The replay oracle
     * retries case-collision goldens on this session: engine goldens
     * legally alias e.g. {@code "city"} and {@code CITY} in one
     * subselect (verified on STOCK h2-2.1.214, 2026-08-28 probe) —
     * only our CASE_INSENSITIVE_IDENTIFIERS session rejects them. */
    public static final String ENGINE_CASED =
            ";NON_KEYWORDS=ANY,ASYMMETRIC,AUTHORIZATION,CAST,"
            + "CURRENT_PATH,CURRENT_ROLE,DAY,DEFAULT,ELSE,END,HOUR,KEY,"
            + "MINUTE,MONTH,SECOND,SESSION_USER,SET,SOME,SYMMETRIC,"
            + "SYSTEM_USER,TO,UESCAPE,USER,VALUE,WHEN,YEAR,OVER"
            + ";MODE=LEGACY";
}
