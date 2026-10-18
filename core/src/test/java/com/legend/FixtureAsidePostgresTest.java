// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend;

import com.legend.model.ConnectionDefinition.DatabaseType;
import com.legend.testbackend.FixtureAside;
import com.legend.testbackend.TestBackend;
import com.legend.testbackend.TestBackends;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A clashing fixture set aside in a Postgres corpus workspace (the dialect program's defect 5; its Phase 6, part 2,
 * 2026-10-17): its unqualified tables land apart from the workspace's same-named ones, the workspace reads its own
 * first and the aside's after, and the aside outlives the rollback of the test body's attempt it was set in. Postgres's
 * DDL and {@code SET} are transactional: an aside made on the workspace's own session went with the rollback while the
 * corpus run counted the fixture provided.
 */
class FixtureAsidePostgresTest {

    private static final TestBackend POSTGRES = TestBackends.of(DatabaseType.Postgres);

    @Test
    void anAsideOutlivesTheRollbackOfTheAttemptItWasSetIn() throws SQLException {
        try (Connection workspace = POSTGRES.openWorkspace()) {
            try (Statement s = workspace.createStatement()) {
                s.execute("CREATE TABLE t (id INTEGER)");
                s.execute("INSERT INTO t VALUES (1)");
            }
            // a test body's attempt: the workspace's transaction stays open until it passes or fails
            workspace.setAutoCommit(false);
            FixtureAside aside = POSTGRES.setFixtureAside(workspace);
            try (Statement s = aside.connection().createStatement()) {
                // a same-named table: set aside, never written over the workspace's
                s.execute("CREATE TABLE t (id INTEGER)");
                s.execute("INSERT INTO t VALUES (2)");
                s.execute("CREATE TABLE u (id INTEGER)");
                s.execute("INSERT INTO u VALUES (3)");
            }
            aside.restore();
            // the body failed
            workspace.rollback();
            workspace.setAutoCommit(true);
            try (Statement s = workspace.createStatement()) {
                assertEquals(1, single(s, "SELECT id FROM t"), "the workspace's own table is read first");
                assertEquals(3, single(s, "SELECT id FROM u"), "the aside's table is read after, rollback or none");
            }
        }
    }

    private static int single(Statement s, String sql) throws SQLException {
        try (ResultSet r = s.executeQuery(sql)) {
            r.next();
            int value = r.getInt(1);
            assertEquals(false, r.next(), sql + " gave more than one row");
            return value;
        }
    }
}
