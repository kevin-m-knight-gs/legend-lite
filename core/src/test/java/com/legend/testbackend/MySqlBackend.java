// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.testbackend;

import com.legend.model.ConnectionDefinition.DatabaseType;
import com.legend.testing.EmbeddedMySql;

import java.sql.Connection;
import java.sql.SQLException;

/** MySQL's sessions: this JVM's embedded MySQL 8.4, a new session on its database {@code pct}. */
final class MySqlBackend extends ServerBackend {

    static final MySqlBackend INSTANCE = new MySqlBackend();

    private MySqlBackend() {
    }

    @Override
    public DatabaseType type() {
        return DatabaseType.MySQL;
    }

    @Override
    public String name() {
        return "mysql";
    }

    /** A session as the platform opens one, as the launcher's user, the test database made on first use (the
     *  launcher's setting). */
    @Override
    Connection session(String database) throws SQLException {
        java.util.Properties settings = new java.util.Properties();
        settings.setProperty("user", EmbeddedMySql.USER);
        settings.setProperty("createDatabaseIfNotExist", "true");
        return openedAsThePlatformOpens(database, settings);
    }

    @Override
    int port() {
        return EmbeddedMySql.shared().port();
    }

    @Override
    String database() {
        return "pct";
    }
}
