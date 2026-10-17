// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.testbackend;

import com.legend.model.ConnectionDefinition.DatabaseType;
import com.legend.testing.EmbeddedClickHouse;

/** ClickHouse's sessions: this JVM's embedded ClickHouse 25.8, a new session (its driver's HTTP) on {@code default}. */
final class ClickHouseBackend extends ServerBackend {

    static final ClickHouseBackend INSTANCE = new ClickHouseBackend();

    private ClickHouseBackend() {
    }

    @Override
    public DatabaseType type() {
        return DatabaseType.ClickHouse;
    }

    @Override
    public String name() {
        return "clickhouse";
    }

    @Override
    String url() {
        return EmbeddedClickHouse.shared().jdbcUrl(database());
    }

    @Override
    int port() {
        return EmbeddedClickHouse.shared().port();
    }

    @Override
    String database() {
        return "default";
    }

    /** A session of its own: ClickHouse keeps a temporary table only in an HTTP session. */
    @Override
    String systemUrl() {
        return EmbeddedClickHouse.shared().sessionJdbcUrl(database());
    }
}
