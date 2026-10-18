// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.testbackend;

import com.legend.model.ConnectionDefinition.DatabaseType;
import com.legend.testing.EmbeddedTrino;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/** Trino's sessions: this JVM's embedded Trino 483, a new session on its memory catalog's {@code default} schema. */
final class TrinoBackend extends ServerBackend {

    static final TrinoBackend INSTANCE = new TrinoBackend();

    private TrinoBackend() {
    }

    @Override
    public DatabaseType type() {
        return DatabaseType.Trino;
    }

    @Override
    public String name() {
        return "trino";
    }

    /** The launcher's session: the platform opens none on Trino (its connector's opening is {@code notOpened}), so
     *  there is no platform route to hold it to. */
    @Override
    Connection session(String database) throws SQLException {
        return DriverManager.getConnection(EmbeddedTrino.shared().jdbcUrl(database));
    }

    @Override
    int port() {
        return EmbeddedTrino.shared().port();
    }

    @Override
    String database() {
        return "default";
    }

    /** The coordinates of the session {@link #open} opens, declared as Trino's own specification. */
    @Override
    public String specificationAndAuth() {
        return "specification: Trino { host: '" + HOST + "'; port: " + port() + "; catalog: 'memory'; schema: '"
                + database() + "'; };\n  auth: Test";
    }
}
