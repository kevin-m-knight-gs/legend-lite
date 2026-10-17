// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.testbackend;


import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/** A database the tests run on an embedded server of this JVM ({@code com.legend.testing}): each session a new one on
 *  the server's test database, the system connection a session of the same server, and the storeless runtime the
 *  server's coordinates. */
abstract class ServerBackend implements TestBackend {

    /** The JDBC URL of a session on the server's test database (starting the server on first use). */
    abstract String url();

    /** The server's port. */
    abstract int port();

    /** The test database a session is on. */
    abstract String database();

    /** The JDBC URL of the platform's system connection: by default a session like any other. */
    String systemUrl() {
        return url();
    }

    /** Its name: what a parameterised test shows. */
    @Override
    public final String toString() {
        return name();
    }

    @Override
    public final Connection open() throws SQLException {
        return DriverManager.getConnection(url());
    }

    @Override
    public final boolean inProcess() {
        return false;
    }

    @Override
    public final Connection openSystem() throws SQLException {
        return DriverManager.getConnection(systemUrl());
    }

    /** The server's coordinates, as they are (C3b): a static datasource on this JVM's server. */
    @Override
    public String specificationAndAuth() {
        return "specification: Static { name: '" + database() + "'; host: '127.0.0.1'; port: " + port() + "; };\n"
                + "  auth: Test";
    }
}
