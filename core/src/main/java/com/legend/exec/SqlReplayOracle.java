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

    /** THE TDG FETCH-TEXT VERDICT (charter burn map, TDG scoring
     * flip): BOTH sides are generator fetch TEXTS — ours executes on
     * the calling session's own database, the golden replays on the
     * oracle, rows compare under the referee's multiset policy (the
     * generator's fetches carry no ORDER BY by construction; an
     * ordered or chained text DECLINES with its named reason). The
     * platform arm consumes the outcome; seeds and session policy
     * live with the implementation (testing side). */
    RowVerdict verifyFetchTexts(java.sql.Connection session,
            String goldenSql, String ourSql);

    /** One materialized oracle result: {@code labels}/{@code
     * jdbcTypes} aligned by column, {@code rows} of raw cells. */
    record OracleRows(java.util.List<String> labels,
            java.util.List<Integer> jdbcTypes,
            java.util.List<java.util.List<@com.legend.Nullable Object>> rows) {
    }

    /**
     * THE ROW VERDICT (charter §3.5d-6): replay {@code goldenSql} on
     * the seeded oracle and compare its rows against {@code ours}
     * under the oracle's OWN comparison policy (the §6 normalization
     * inventory + the §7 order rule live with the referee — charter
     * §2 puts comparison policy on the testing side; the platform arm
     * consumes the OUTCOME and owns the assert's judgment).
     * {@code session} is the executing connection (an H2 session
     * verifies directly); {@code mappingFqn}/{@code rootClassFqn}
     * drive the enum source-code→name decode where the query selects
     * raw codes. Never throws for a comparison problem — every
     * non-answer is a DECLINED outcome with its counted reason.
     */
    RowVerdict verify(java.sql.Connection session, String goldenSql,
            ExecutionResult ours,
            @com.legend.Nullable String mappingFqn,
            @com.legend.Nullable String rootClassFqn,
            com.legend.compiler.element.ModelContext ctx);

    /** MATCH = rows agree (the verdict of record); DIVERGED = rows
     * differ ({@code detail} says how — a REAL failure whatever the
     * text said); DECLINED = the oracle could not answer
     * ({@code detail} = the counted reason; the caller's §4 policy
     * applies — e.g. text stays the contract). */
    record RowVerdict(Outcome outcome, @com.legend.Nullable String detail) {
        public enum Outcome { MATCH, DIVERGED, DECLINED }

        public static RowVerdict match() {
            return new RowVerdict(Outcome.MATCH, null);
        }

        public static RowVerdict diverged(String detail) {
            return new RowVerdict(Outcome.DIVERGED, detail);
        }

        public static RowVerdict declined(String reason) {
            return new RowVerdict(Outcome.DECLINED, reason);
        }
    }
}
