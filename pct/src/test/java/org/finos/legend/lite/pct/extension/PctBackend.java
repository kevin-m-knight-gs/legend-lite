// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package org.finos.legend.lite.pct.extension;

import com.legend.model.ConnectionDefinition.DatabaseType;
import com.legend.testbackend.TestBackend;
import com.legend.testbackend.TestBackends;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * The database a PCT lane runs its suite on, chosen by the target ({@code LEGENDLITE_PCT_BACKEND}, an environment
 * variable: it must survive the fork into the test JVM) and named as its test backend names it
 * ({@code com.legend.testbackend.TestBackends.named}); unset, the platform's own engine, the reference lane. One
 * session per executed expression: each database's session, storeless runtime and system connection are its test
 * backend's (the dialect program's Phase 6, 2026-10-16: until then a lane enum here held them, one arm per database).
 *
 * <p>Session settings are the dialect's, applied at the platform's connection seam ({@code SqlDialect.sessionSetup},
 * from {@code Compiler.dialectOf}); this only opens the connection.
 *
 * <p>A server lane is also the host of a SYSTEM CONNECTION ({@link #systemConnections}): where the platform's system
 * database for that engine lives (a query reading the metamodel store runs there, apart from the user's session;
 * exec.SystemDatabase). This harness gives it the lane's own server and credentials, as its choice; a host
 * configures it apart. An in-process lane needs none (the platform opens its own private instance).
 *
 * <p>Both channels run on it (2026-10-11, the user's ask): channel A's adapter ({@link PctExecuteNative}) and channel
 * B's runner (channelb.ChannelB), so a lane's two verdicts come from the same database.
 */
public final class PctBackend {

    private PctBackend() {
    }

    /** This lane's backend: the one {@code LEGENDLITE_PCT_BACKEND} names, or the platform's when it is unset. */
    private static TestBackend backend() {
        String name = System.getenv("LEGENDLITE_PCT_BACKEND");
        return name == null ? TestBackends.platform() : TestBackends.named(name);
    }

    /** The lane's name, as {@code LEGENDLITE_PCT_BACKEND} spells it (the platform's when unset). */
    public static String name() {
        return backend().name();
    }

    /** Whether this lane is the reference lane: the platform's own engine. */
    public static boolean isReference() {
        return backend() == TestBackends.platform();
    }

    /** This lane's storeless runtime declared alone: the session {@link #connect} opens. */
    public static String storelessRuntime() {
        return withStorelessRuntime("");
    }

    /** {@code model} with this lane's storeless runtime declared beside it: the session {@link #connect} opens. */
    static String withStorelessRuntime(String model) {
        return backend().withStorelessRuntime(model);
    }

    /** A session on this lane's database. */
    public static Connection connect() throws SQLException {
        return backend().open();
    }

    /** The host's system connections: a server lane's own engine ({@link TestBackend#openSystem}); none for an
     *  in-process lane. */
    public static java.util.Map<DatabaseType, com.legend.exec.Sessions.Opener> systemConnections() {
        TestBackend backend = backend();
        return backend.inProcess() ? java.util.Map.of() : java.util.Map.of(backend.type(), backend::openSystem);
    }
}
