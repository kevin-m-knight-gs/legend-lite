// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.exec;

import com.legend.setup.CsvSeed.Step;

import java.util.List;

/**
 * Runs a connection's setup on a session: the statements and rows the plan side writes
 * ({@link com.legend.setup.CsvSeed}), executed here under the {@code SEED} origin. The text half moved to the plan
 * side on 2026-10-08 (docs/EXECUTION_PLAN_BOUNDARY_2026_10_05.md §9); this is the half that holds a connection.
 */
public final class SetupRunner {

    private SetupRunner() {
    }

    /** Runs a connection's setup steps on the session, as the engine does when it
     *  ESTABLISHES the connection; {@code recorder} is the referee's ledger (null = none). */
    public static void run(List<Step> setups, java.sql.Connection connection,
            com.legend.sql.dialect.SqlDialect dialect,
            com.legend.sql.dialect.RawSqlBoundary.@com.legend.base.Nullable Recorder recorder) {
        for (Step step : setups) {
            switch (step) {
                case Step.Sql blob -> {
                    for (String stmt : com.legend.sql.RawSql.splitStatements(blob.text())) {
                        boolean query;
                        try (var __o = StatementOrigin.enter(StatementOrigin.SEED)) {
                            query = send(connection,
                                    new com.legend.setup.CsvSeed.Step.Sql(stmt, blob.writtenFor()).runOn(dialect));
                        }
                        if (recorder != null) {
                            recorder.recordExecuted(stmt, query);
                        }
                    }
                }
                // test data's ROWS: the engine's bulk load when it has one; the
                // referee's ledger records the one insert that lands the same rows
                case Step.Rows rows -> {
                    try (var __o = StatementOrigin.enter(StatementOrigin.SEED)) {
                        load(connection, dialect, rows.load());
                    }
                    if (recorder != null) {
                        recorder.recordExecuted(rows.text(dialect), false);
                    }
                }
            }
        }
    }

    /** One setup statement sent on {@code session} under the origin in force, counted as every statement is
     *  ({@link StatementOrigin#sent}): whether it answered rows, which are not read. */
    public static boolean send(java.sql.Connection session, String statement) {
        StatementOrigin.sent(statement);
        try (java.sql.Statement st = session.createStatement()) {
            return st.execute(statement);
        } catch (java.sql.SQLException e) {
            throw PlanSessions.dataError(e, session);
        }
    }

    /** {@code load}'s rows into their table on {@code session}: by the engine's bulk load where it has one, else as
     *  the one insert the dialect writes. */
    static void load(java.sql.Connection session, com.legend.sql.dialect.SqlDialect dialect,
            com.legend.setup.RowLoad load) {
        if (load.rows().isEmpty()) {
            return;
        }
        try {
            BulkLoad bulk = BulkLoads.of(session);
            if (bulk != null) {
                BulkLoads.load(bulk, session, load.staged(dialect));
                return;
            }
        } catch (java.sql.SQLException e) {
            throw PlanSessions.dataError(e, session);
        }
        send(session, dialect.render(load.values()));
    }
}
