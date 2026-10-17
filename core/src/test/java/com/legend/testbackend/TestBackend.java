// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.testbackend;

import com.legend.database.Databases;
import com.legend.model.ConnectionDefinition.DatabaseType;
import com.legend.sql.dialect.SqlDialect;
import com.legend.test.StorelessRuntime;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * One database as the tests run on it (the dialect program's Phase 6, docs/DIALECT_LEAK_CENSUS_AND_PLAN_2026_10_12.md):
 * a session of its own, the storeless runtime a model declares for that session, and, for a server, the system
 * connection the platform's system database lives on. The tests' counterpart of a connector: each database's harness
 * knowledge in a class of its own ({@code DuckDbBackend}, {@code PostgresBackend} ...), {@link TestBackends} the one
 * place a test finds it. A test names no database: it takes {@link TestBackends#platform()}, the backend a lane names,
 * or every one ({@link TestBackends#all()}).
 */
public interface TestBackend {

    /** The database type its sessions are on. */
    DatabaseType type();

    /** Its name as a lane spells it ({@code LEGENDLITE_PCT_BACKEND}): lower case. */
    String name();

    /** A session on it, for one test: in-process, a private in-memory instance; a server, a new session on the test
     *  server's database; a cloud, the suite's one session (closing it is a no-op). */
    Connection open() throws SQLException;

    /** Whether it runs in-process (the platform opens its own private instances of it), or is a server. */
    boolean inProcess();

    /** A NEW session for the platform's system database on this server (closing it closes it). An in-process backend
     *  has none: the platform opens its own private instance. */
    Connection openSystem() throws SQLException;

    /** The specification and authentication a connection declares for the session {@link #open} opens (a
     *  {@code RelationalDatabaseConnection}'s body after its {@code type}): an in-process database's in-memory
     *  specification, a server's coordinates, a cloud's coordinates with its credential by reference. */
    String specificationAndAuth();

    /** A connection's body for the session {@link #open} opens, as a {@code RelationalDatabaseConnection} declares it:
     *  its type, specification and authentication, each ending in {@code ;}. */
    default String connectionBody() {
        return "type: " + type() + ";\n  " + specificationAndAuth() + ";";
    }

    /** {@code model} with the storeless runtime on this backend declared beside it: the session {@link #open} opens. */
    default String withStorelessRuntime(String model) {
        return StorelessRuntime.declaring(model, type(), specificationAndAuth());
    }

    /** The dialect its queries are written in. */
    default SqlDialect dialect() {
        return Databases.dialect(type());
    }
}
