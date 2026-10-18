// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.testbackend;

import com.legend.model.ConnectionDefinition.DatabaseType;
import com.legend.testing.EmbeddedClickHouse;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

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

    /** The launcher's session: the platform opens none on ClickHouse (its connector's opening is
     *  {@code notOpened}), so there is no platform route to hold it to. */
    @Override
    Connection session(String database) throws SQLException {
        return DriverManager.getConnection(EmbeddedClickHouse.shared().jdbcUrl(database));
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
    Connection system() throws SQLException {
        return DriverManager.getConnection(EmbeddedClickHouse.shared().sessionJdbcUrl(database()));
    }
}
