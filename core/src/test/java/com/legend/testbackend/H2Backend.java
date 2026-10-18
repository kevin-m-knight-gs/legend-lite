// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.testbackend;

import com.legend.exec.H2Connector;
import com.legend.model.ConnectionDefinition.DatabaseType;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** H2's sessions: a private in-memory instance each, with the engine's session settings ({@code H2Connector.SETTINGS}).
 *  It is also the replay referee's database ({@link RefereeBackend}): legend-engine's goldens are H2 SQL, so the
 *  referee's sessions carry the engine's extension functions ({@link H2ExtensionFunctions}), and a corpus workspace on
 *  H2 is such a session. */
final class H2Backend extends InProcessBackend implements RefereeBackend {

    static final H2Backend INSTANCE = new H2Backend();

    /** The corpus's workspaces on H2, named apart ({@code c2s<N>}). */
    private final AtomicInteger workspaces = new AtomicInteger();

    /** The schema asides each workspace holds, in the order it set them aside. */
    private final Map<Connection, List<String>> asides = new WeakHashMap<>();

    private H2Backend() {
    }

    @Override
    public DatabaseType type() {
        return DatabaseType.H2;
    }

    @Override
    public String name() {
        return "h2";
    }

    @Override
    public boolean available() {
        try {
            Class.forName("org.h2.Driver");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    @Override
    public Connection openReferee(String name) throws SQLException {
        Connection referee = DriverManager.getConnection("jdbc:h2:mem:" + name + H2Connector.SETTINGS, "sa", "");
        // the engine's H2 test database carries its extension functions, and its goldens call them
        // (legend_h2_extension_*): every referee session does too
        try (Statement st = referee.createStatement()) {
            for (String alias : H2ExtensionFunctions.aliases()) {
                com.legend.exec.StatementOrigin.count(com.legend.exec.StatementOrigin.SESSION);
                st.execute(alias);
            }
        } catch (SQLException e) {
            referee.close();
            throw e;
        }
        return referee;
    }

    /** A corpus workspace on H2 is a fresh referee session: on this lane a golden runs on the workspace itself (the
     *  same-session referee), so the workspace carries the engine's extension functions too (Phase 0.6: a golden
     *  calling {@code legend_h2_extension_lpad} was a referee FAULT here). */
    @Override
    public Connection openWorkspace() throws SQLException {
        return openReferee("c2s" + workspaces.getAndIncrement());
    }

    /** A SCHEMA aside on the workspace itself (no catalog to attach, no per-transaction write limit): the fixture's
     *  unqualified DDL lands there and reads only it. */
    @Override
    public FixtureAside setFixtureAside(Connection workspace) throws SQLException {
        List<String> held;
        String aside;
        synchronized (asides) {
            held = asides.computeIfAbsent(workspace, c -> new ArrayList<>());
            aside = "ASIDE_" + held.size();
            held.add(aside);
        }
        try (Statement st = workspace.createStatement()) {
            for (String s : List.of("CREATE SCHEMA IF NOT EXISTS " + aside, "SET SCHEMA " + aside,
                    "SET SCHEMA_SEARCH_PATH " + aside)) {
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
                    for (String s : readOwnFirst(all)) {
                        com.legend.exec.StatementOrigin.count(com.legend.exec.StatementOrigin.SESSION);
                        st.execute(s);
                    }
                }
            }
        };
    }

    /** The mirror sets its aside as a schema too: the fixture's DDL lands there. */
    @Override
    public List<String> replayAside(String aside) {
        return List.of("CREATE SCHEMA IF NOT EXISTS " + aside, "SET SCHEMA " + aside);
    }

    /** The mirror reads {@code PUBLIC} first, then the asides. */
    @Override
    public List<String> replayRestore(List<String> held) {
        return readOwnFirst(held);
    }

    private static List<String> readOwnFirst(List<String> held) {
        return List.of("SET SCHEMA PUBLIC", "SET SCHEMA_SEARCH_PATH PUBLIC, " + String.join(", ", held));
    }
}
