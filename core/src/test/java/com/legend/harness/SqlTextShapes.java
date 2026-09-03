// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0
package com.legend.harness;

/**
 * The sql-text TEXT-VERDICT ledger (sqltext homework, 2026-09-03): for
 * every assert the platform's sql-text arm judged by TEXT (the rows leg
 * declined — {@code SqlTextEmission.TEXT_VERDICT} counts the reasons),
 * the runner attributes the reason to the test that produced it and
 * dumps {@code target/sqltext-text-verdict-roster.txt}. The former
 * shape census / {@code allSimple} pre-decline gate is gone (batch 37):
 * every sql-assert shape is attempted on the platform.
 */
public final class SqlTextShapes {
    private SqlTextShapes() {
    }

    /** per-test line: "reason xN :: test" for every assert the sql-text
     * arm judged by TEXT (the runner attributes the counter deltas). */
    public static final java.util.concurrent.ConcurrentLinkedQueue<String>
            TEXT_VERDICT_ROSTER = new java.util.concurrent.ConcurrentLinkedQueue<>();
}
