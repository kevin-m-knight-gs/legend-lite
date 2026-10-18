// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.testbackend;

import com.legend.model.ConnectionDefinition.DatabaseType;
import com.legend.testing.EmbeddedMariaDb;

import java.sql.Connection;
import java.sql.SQLException;

/** MariaDB's sessions: this JVM's embedded MariaDB 11.4, a new session on its database {@code pct}. */
final class MariaDbBackend extends ServerBackend {

    static final MariaDbBackend INSTANCE = new MariaDbBackend();

    private MariaDbBackend() {
    }

    @Override
    public DatabaseType type() {
        return DatabaseType.MariaDB;
    }

    @Override
    public String name() {
        return "mariadb";
    }

    /** A session as the platform opens one, as the launcher's user, the test database made on first use (the
     *  launcher's setting). */
    @Override
    Connection session(String database) throws SQLException {
        java.util.Properties settings = new java.util.Properties();
        settings.setProperty("user", EmbeddedMariaDb.USER);
        settings.setProperty("createDatabaseIfNotExist", "true");
        return openedAsThePlatformOpens(database, settings);
    }

    @Override
    int port() {
        return EmbeddedMariaDb.shared().port();
    }

    @Override
    String database() {
        return "pct";
    }
}
