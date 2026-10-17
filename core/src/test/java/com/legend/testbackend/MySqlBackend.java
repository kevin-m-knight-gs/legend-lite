// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.testbackend;

import com.legend.model.ConnectionDefinition.DatabaseType;
import com.legend.testing.EmbeddedMySql;

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

    @Override
    String url() {
        return EmbeddedMySql.shared().jdbcUrl(database());
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
