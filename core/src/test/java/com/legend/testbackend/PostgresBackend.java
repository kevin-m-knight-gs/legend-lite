// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.testbackend;

import com.legend.model.ConnectionDefinition.DatabaseType;
import com.legend.testing.EmbeddedPostgres;

/** Postgres's sessions: this JVM's embedded Postgres 16, a new session on its database {@code postgres}. */
final class PostgresBackend extends ServerBackend {

    static final PostgresBackend INSTANCE = new PostgresBackend();

    private PostgresBackend() {
    }

    @Override
    public DatabaseType type() {
        return DatabaseType.Postgres;
    }

    @Override
    public String name() {
        return "postgres";
    }

    @Override
    String url() {
        return EmbeddedPostgres.shared().jdbcUrl(database());
    }

    @Override
    int port() {
        return EmbeddedPostgres.shared().port();
    }

    @Override
    String database() {
        return "postgres";
    }
}
