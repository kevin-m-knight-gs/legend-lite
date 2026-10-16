// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.exec;

import com.legend.model.ConnectionDefinition;
import com.legend.model.ConnectionDefinition.DatabaseType;

/** Snowflake's sessions: the platform opens no declared connection to it yet (its sessions are the test harnesses');
 *  a session handed to execution is checked against its driver's product name. */
final class SnowflakeConnector implements DatabaseConnector {

    static final SnowflakeConnector INSTANCE = new SnowflakeConnector();

    private SnowflakeConnector() {
    }

    @Override
    public DatabaseType type() {
        return DatabaseType.Snowflake;
    }

    @Override
    public String jdbcProduct() {
        return "Snowflake";
    }

    @Override
    public Sessions.Opening opening(ConnectionDefinition def) {
        return DatabaseConnector.notOpened(def);
    }
}
