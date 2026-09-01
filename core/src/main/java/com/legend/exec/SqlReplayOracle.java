// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.exec;

/**
 * THE REPLAY-ORACLE SPI (SQLTEXT_ROW_VERDICT_CHARTER §2, the
 * {@link AssertListener} precedent): rows for a SQL text, executed on
 * the referee's oracle database with the run's recorded seed ledger
 * applied. The platform defines only this seam and carries it on
 * {@code ExecEnv}; a test harness registers its implementation per run
 * (the H2 mirror). Production registers nothing — a SQL-text assert
 * that needs the oracle then WALLS loudly, which is correct: no
 * goldens exist outside tests. The platform never learns what a
 * golden is, where the ledger lives, or which database answers.
 *
 * <p>The plan-replay entry (charter §5) joins this interface with the
 * plan-replayer slice — its signature derives from that replayer's
 * real consumption, never guessed ahead of it.
 */
public interface SqlReplayOracle {

    /**
     * Execute {@code sql} on the seeded oracle session and return its
     * result whole: column labels, JDBC type codes, and raw cell
     * values ({@code getObject} arrivals, untransformed — comparison
     * policy normalizes, never the oracle). Any exception means the
     * oracle could not answer; the caller's decline policy applies.
     */
    OracleRows rows(String sql) throws java.sql.SQLException;

    /** One materialized oracle result: {@code labels}/{@code
     * jdbcTypes} aligned by column, {@code rows} of raw cells. */
    record OracleRows(java.util.List<String> labels,
            java.util.List<Integer> jdbcTypes,
            java.util.List<java.util.List<@com.legend.Nullable Object>> rows) {
    }
}
