// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.testbackend;

import com.legend.model.ConnectionDefinition.DatabaseType;
import com.legend.testing.EmbeddedMariaDb;

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

    @Override
    String url() {
        return EmbeddedMariaDb.shared().jdbcUrl(database());
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
