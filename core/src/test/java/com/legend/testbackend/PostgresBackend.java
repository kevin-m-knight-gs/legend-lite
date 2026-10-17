// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.testbackend;

import com.legend.model.ConnectionDefinition.DatabaseType;
import com.legend.testing.EmbeddedPostgres;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** Postgres's sessions: this JVM's embedded Postgres 16, a new session on its database {@code postgres}. A corpus
 *  workspace is a DATABASE of its own on that server (2026-10-12, docs/CLOUD_BACKENDS_PCT_2026_10_11.md §20: the corpus
 *  stores' declared schemas live inside it undisturbed, as in a DuckDB catalog), created over one administrative
 *  connection and dropped when the workspace's connection closes; a fixture is set aside in a SCHEMA of it. */
final class PostgresBackend extends ServerBackend {

    static final PostgresBackend INSTANCE = new PostgresBackend();

    private final AtomicInteger workspaceIds = new AtomicInteger();

    /** The administrative connection (to the server's own {@code postgres} database): workspaces are made and dropped
     *  over it. */
    private @com.legend.base.Nullable Connection admin;

    /** The schema asides each workspace holds, in the order it set them aside. */
    private final Map<Connection, List<String>> asides = new IdentityHashMap<>();

    /** Each workspace's database, by its connection. */
    private final Map<Connection, String> databases = new IdentityHashMap<>();

    /** The asides one workspace may set: its search path names them all from its first statement. */
    static final int MAX_ASIDES = 64;

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

    /** A session as the platform opens one, as the launcher's user. */
    @Override
    Connection session(String database) throws SQLException {
        java.util.Properties settings = new java.util.Properties();
        settings.setProperty("user", EmbeddedPostgres.USER);
        return openedAsThePlatformOpens(database, settings);
    }

    @Override
    int port() {
        return EmbeddedPostgres.shared().port();
    }

    @Override
    String database() {
        return "postgres";
    }

    @Override
    public synchronized Connection openWorkspace() throws SQLException {
        if (admin == null) {
            admin = session(database());
        }
        String ws = "ws_" + workspaceIds.getAndIncrement();
        try (Statement st = admin.createStatement()) {
            com.legend.exec.StatementOrigin.count(com.legend.exec.StatementOrigin.SESSION);
            st.execute("CREATE DATABASE " + ws);
        }
        Connection workspace = session(ws);
        try (Statement st = workspace.createStatement()) {
            // its own schema first, then every aside it may set, in the order it sets them: a schema the path names
            // before it exists is skipped, so an aside made later is read last with no SET on the workspace, which a
            // body's attempt would undo when it rolls back (Postgres's SET is transactional)
            List<String> path = new ArrayList<>(List.of("public"));
            for (int i = 0; i < MAX_ASIDES; i++) {
                path.add(aside(i));
            }
            com.legend.exec.StatementOrigin.count(com.legend.exec.StatementOrigin.SESSION);
            st.execute("SET search_path TO " + String.join(", ", path));
        } catch (SQLException e) {
            workspace.close();
            drop(ws);
            throw e;
        }
        Connection proxied = closeDrops(workspace, ws);
        synchronized (asides) {
            databases.put(proxied, ws);
        }
        return proxied;
    }

    /** The {@code i}th aside's schema. */
    private static String aside(int i) {
        return "aside_" + i;
    }

    /** A SCHEMA aside in the workspace's database, made and filled by a session of its own: Postgres's DDL is
     *  transactional, and a body's attempt may hold the workspace's transaction, whose rollback would take the aside's
     *  schema and tables with it while the run counts the fixture provided. The fixture's session commits each
     *  statement; its unqualified DDL lands in the aside (its search path names the aside alone), and the workspace
     *  reads the aside after its own tables, its search path having named it from the first statement
     *  ({@link #openWorkspace}). The plan's defect 5: this lane's on-demand fixtures ran over the workspace's own
     *  tables. A fixture's schema-qualified names are not set aside. */
    @Override
    public FixtureAside setFixtureAside(Connection workspace) throws SQLException {
        List<String> held;
        String aside;
        String ws;
        synchronized (asides) {
            ws = databases.get(workspace);
            if (ws == null) {
                throw new IllegalArgumentException("not a postgres workspace: a fixture is set aside only in a"
                        + " workspace its backend opened");
            }
            held = asides.computeIfAbsent(workspace, c -> new ArrayList<>());
            if (held.size() == MAX_ASIDES) {
                throw new com.legend.error.NotImplementedException("a postgres workspace sets at most " + MAX_ASIDES
                        + " fixtures aside: its search path names that many");
            }
            aside = aside(held.size());
            held.add(aside);
        }
        Connection own = session(ws);
        try (Statement st = own.createStatement()) {
            for (String s : List.of("CREATE SCHEMA " + aside, "SET search_path TO " + aside)) {
                com.legend.exec.StatementOrigin.count(com.legend.exec.StatementOrigin.SESSION);
                st.execute(s);
            }
        } catch (SQLException e) {
            own.close();
            throw e;
        }
        List<String> all = List.copyOf(held);
        return new FixtureAside() {
            @Override
            public String name() {
                return aside;
            }

            @Override
            public Connection connection() {
                return own;
            }

            @Override
            public List<String> held() {
                return all;
            }

            /** The fixture's session closes; the workspace already reads the aside. */
            @Override
            public void restore() throws SQLException {
                own.close();
            }
        };
    }

    /** The workspace's connection, whose close drops its database (and its asides) too. */
    private Connection closeDrops(Connection inner, String ws) {
        Connection[] self = new Connection[1];
        InvocationHandler h = (proxy, method, args) -> {
            if ("close".equals(method.getName())) {
                try {
                    inner.close();
                } finally {
                    synchronized (asides) {
                        asides.remove(self[0]);
                        databases.remove(self[0]);
                    }
                    drop(ws);
                }
                return null;
            }
            try {
                return method.invoke(inner, args);
            } catch (java.lang.reflect.InvocationTargetException e) {
                throw e.getCause();
            }
        };
        Connection proxied = (Connection) Proxy.newProxyInstance(PostgresBackend.class.getClassLoader(),
                new Class<?>[] {Connection.class}, h);
        self[0] = proxied;
        return proxied;
    }

    private synchronized void drop(String ws) {
        try (Statement st = admin.createStatement()) {
            com.legend.exec.StatementOrigin.count(com.legend.exec.StatementOrigin.SESSION);
            st.execute("DROP DATABASE IF EXISTS " + ws + " WITH (FORCE)");
        } catch (SQLException e) {
            // a failed drop must not mask the test's own outcome; the server is this run's alone
            System.err.println("[corpus2] workspace " + ws + " not dropped: " + e.getMessage());
        }
    }
}
