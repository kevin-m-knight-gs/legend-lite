// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.testbackend;

import com.legend.model.ConnectionDefinition.DatabaseType;
import com.legend.testing.EmbeddedPostgres;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
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

    @Override
    public synchronized Connection openWorkspace() throws SQLException {
        EmbeddedPostgres pg = EmbeddedPostgres.shared();
        if (admin == null) {
            admin = DriverManager.getConnection(pg.jdbcUrl(database()));
        }
        String ws = "ws_" + workspaceIds.getAndIncrement();
        try (Statement st = admin.createStatement()) {
            com.legend.exec.StatementOrigin.count(com.legend.exec.StatementOrigin.SESSION);
            st.execute("CREATE DATABASE " + ws);
        }
        return closeDrops(DriverManager.getConnection(pg.jdbcUrl(ws)), ws);
    }

    /** A SCHEMA aside in the workspace's database, on the workspace itself (Postgres's DDL is transactional, so the
     *  fixture needs no connection of its own): its unqualified DDL lands there and reads only it. The plan's defect 5:
     *  this lane's on-demand fixtures ran over the workspace's own tables. */
    @Override
    public FixtureAside setFixtureAside(Connection workspace) throws SQLException {
        List<String> held;
        String aside;
        synchronized (asides) {
            held = asides.computeIfAbsent(workspace, c -> new ArrayList<>());
            aside = "aside_" + held.size();
            held.add(aside);
        }
        try (Statement st = workspace.createStatement()) {
            for (String s : List.of("CREATE SCHEMA IF NOT EXISTS " + aside, "SET search_path TO " + aside)) {
                com.legend.exec.StatementOrigin.count(com.legend.exec.StatementOrigin.SESSION);
                st.execute(s);
            }
        }
        List<String> all = List.copyOf(held);
        return new FixtureAside() {
            @Override
            public String name() {
                return aside;
            }

            @Override
            public Connection connection() {
                return workspace;
            }

            @Override
            public List<String> held() {
                return all;
            }

            @Override
            public void restore() throws SQLException {
                try (Statement st = workspace.createStatement()) {
                    com.legend.exec.StatementOrigin.count(com.legend.exec.StatementOrigin.SESSION);
                    st.execute("SET search_path TO public, " + String.join(", ", all));
                }
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
