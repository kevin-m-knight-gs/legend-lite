// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.exec;

import com.legend.model.ConnectionDefinition;
import com.legend.model.ConnectionDefinition.DatabaseType;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * One database's sessions (the dialect program's Phase 5, docs/DIALECT_LEAK_CENSUS_AND_PLAN_2026_10_12.md): the product
 * name its JDBC driver reports, how a connection declared on it is opened, whether it has a private in-process
 * instance, and what its sessions hold that the platform reads back. The execution side's counterpart of a dialect:
 * each database's knowledge in a class of its own ({@code DuckDbConnector}, {@code H2Connector} ...), and
 * {@link Connectors} the one place a declared type finds its connector. {@link Sessions} decides nothing by a database
 * of its own; it asks the connector.
 */
public interface DatabaseConnector {

    /** The database type this connector opens sessions on. */
    DatabaseType type();

    /** The product name its JDBC driver reports ({@code DatabaseMetaData.getDatabaseProductName}): what a session
     *  handed to execution is checked against. */
    String jdbcProduct();

    /** How {@code def}, a connection declared on this database, is opened; a specification this database is not opened
     *  from is refused by name ({@link #refused}). Its authentication is checked before
     *  ({@link Sessions#openingFor}). */
    Sessions.Opening opening(ConnectionDefinition def);

    /** Whether it has a private in-process instance ({@link #openPrivate}). A server has none: its system database is
     *  on a system connection the host configures ({@link Sessions.Opener}). */
    default boolean inProcess() {
        return false;
    }

    /** A PRIVATE in-memory instance, gone when the connection closes (the system database, the platform's engine). */
    default Connection openPrivate() throws SQLException {
        throw new com.legend.error.NotImplementedException("no in-memory instance of database type '" + type() + "'");
    }

    /** The raise {@code session} holds after a statement failed (docs/CLOUD_BACKENDS_PCT_2026_10_11.md §13), read and
     *  cleared: where this database's errors cannot carry a raise's text, its dialect keeps it in the session. Null
     *  when none is held, or this database keeps none (its error carries the raise). */
    default @com.legend.base.Nullable String raisedMessage(Connection session) throws SQLException {
        return null;
    }

    /** The specification and authentication a storeless runtime declares for this database's in-process session
     *  ({@code test.StorelessRuntime}), in the model's connection grammar. */
    default String storelessDeclaration() {
        throw new IllegalArgumentException("no storeless runtime on " + type()
                + ": the platform does not execute on it");
    }

    /** An in-memory database that outlives its connections by NAME ({@link Sessions.Opening.Named}): a connector whose
     *  database keeps one by name. */
    interface NamedMemory {

        /** A connection to the named in-memory database {@code name}, which outlives it. */
        Connection openNamed(String name) throws SQLException;

        /** A connection to the named in-memory database {@code name}, which lives only while a connection to it is
         *  open: its opener keeps this one, and when that one closes with no other open, the database is gone. */
        Connection openKept(String name) throws SQLException;

        /** Another connection to the kept database {@code name} ({@link #openKept}), which must still be open: one
         *  whose keeper closed is gone, and connecting fails by name rather than making an empty database of that
         *  name. */
        Connection joinKept(String name) throws SQLException;
    }

    /** {@code def}'s specification, refused: this database is not opened from it. */
    static Sessions.Opening refused(ConnectionDefinition def) {
        throw new com.legend.error.NotImplementedException("a " + def.databaseType() + " connection with a "
                + def.specification().getClass().getSimpleName() + " specification is not implemented (connection '"
                + def.qualifiedName() + "')");
    }

    /** {@code def}, refused: the platform opens no declared connection to its database yet (its sessions are the
     *  test harnesses' or a host's). */
    static Sessions.Opening notOpened(ConnectionDefinition def) {
        throw new com.legend.error.NotImplementedException("connections to database type '" + def.databaseType()
                + "' are not implemented (connection '" + def.qualifiedName() + "')");
    }
}
