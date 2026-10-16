// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.exec;

import com.legend.model.ConnectionDefinition;
import com.legend.model.ConnectionDefinition.DatabaseType;

/** Trino's sessions: the platform opens no declared connection to it yet (its sessions are the test harnesses');
 *  a session handed to execution is checked against its driver's product name. */
final class TrinoConnector implements DatabaseConnector {

    static final TrinoConnector INSTANCE = new TrinoConnector();

    private TrinoConnector() {
    }

    @Override
    public DatabaseType type() {
        return DatabaseType.Trino;
    }

    /** The Trino JDBC driver (measured, trino-jdbc 483). */
    @Override
    public String jdbcProduct() {
        return "Trino";
    }

    @Override
    public Sessions.Opening opening(ConnectionDefinition def) {
        return DatabaseConnector.notOpened(def);
    }
}
