// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package org.finos.legend.lite.pct.extension;

import com.legend.model.ConnectionDefinition.DatabaseType;
import com.legend.testing.EmbeddedClickHouse;
import com.legend.testing.EmbeddedMariaDb;
import com.legend.testing.EmbeddedMongoDb;
import com.legend.testing.EmbeddedMySql;
import com.legend.testing.EmbeddedPostgres;
import com.legend.testing.EmbeddedTrino;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * The database a PCT lane runs its suite on, chosen by the target ({@code LEGENDLITE_PCT_BACKEND}, an
 * environment variable: it must survive the fork into the test JVM), one connection per executed
 * expression:
 *
 * <ul>
 *   <li>unset: a fresh in-memory DuckDB;</li>
 *   <li>{@code h2}: a fresh in-memory H2, with the portability sweep's session settings;</li>
 *   <li>{@code postgres}: this JVM's embedded Postgres 16 (leg P2), a new session on its database;</li>
 *   <li>{@code sqlite}: a fresh in-memory SQLite (the driver's own build, in-process);</li>
 *   <li>{@code mariadb}: this JVM's embedded MariaDB 11.4, a new session on its database {@code pct};</li>
 *   <li>{@code mysql}: this JVM's embedded MySQL 8.4, a new session on its database {@code pct};</li>
 *   <li>{@code trino}: this JVM's embedded Trino 483, a new session on its memory catalog's {@code default} schema;</li>
 *   <li>{@code clickhouse}: this JVM's embedded ClickHouse 25.8, a new session (its driver's HTTP) on {@code default};</li>
 *   <li>{@code databricks}, {@code snowflake}, {@code bigquery}: a real account's warehouse, one session held for the
 *       suite ({@link CloudLane}: its coordinates and credentials from {@code LEGENDLITE_PCT_CLOUD_PROPERTIES}).</li>
 * </ul>
 *
 * Session settings are the dialect's, applied at the platform's connection seam
 * ({@code SqlDialect.sessionSetup}, from {@code Compiler.dialectOf}); this only opens the connection.
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

    /** The lanes' databases: each one's declared type (the storeless runtime's connection), its session and, for a
     *  server, its system connection. */
    private enum Lane {
        DUCKDB(DatabaseType.DuckDB) {
            @Override
            Connection open() throws SQLException {
                return DriverManager.getConnection("jdbc:duckdb:");
            }
        },
        H2(DatabaseType.H2) {
            @Override
            Connection open() throws SQLException {
                return DriverManager.getConnection("jdbc:h2:mem:" + com.legend.exec.H2Settings.SETTINGS, "sa", "");
            }
        },
        SQLITE(DatabaseType.SQLite) {
            @Override
            Connection open() throws SQLException {
                return DriverManager.getConnection("jdbc:sqlite::memory:");
            }
        },
        MARIADB(DatabaseType.MariaDB) {
            @Override
            Connection open() throws SQLException {
                return DriverManager.getConnection(EmbeddedMariaDb.shared().jdbcUrl("pct"));
            }

            @Override
            Connection openSystem() throws SQLException {
                return DriverManager.getConnection(EmbeddedMariaDb.shared().jdbcUrl("pct"));
            }
        },
        MYSQL(DatabaseType.MySQL) {
            @Override
            Connection open() throws SQLException {
                return DriverManager.getConnection(EmbeddedMySql.shared().jdbcUrl("pct"));
            }

            @Override
            Connection openSystem() throws SQLException {
                return DriverManager.getConnection(EmbeddedMySql.shared().jdbcUrl("pct"));
            }
        },
        TRINO(DatabaseType.Trino) {
            @Override
            Connection open() throws SQLException {
                return DriverManager.getConnection(EmbeddedTrino.shared().jdbcUrl("default"));
            }

            @Override
            Connection openSystem() throws SQLException {
                return DriverManager.getConnection(EmbeddedTrino.shared().jdbcUrl("default"));
            }
        },
        CLICKHOUSE(DatabaseType.ClickHouse) {
            @Override
            Connection open() throws SQLException {
                return DriverManager.getConnection(EmbeddedClickHouse.shared().jdbcUrl("default"));
            }

            @Override
            Connection openSystem() throws SQLException {
                // a session of its own: ClickHouse keeps a temporary table only in an HTTP session
                return DriverManager.getConnection(EmbeddedClickHouse.shared().sessionJdbcUrl("default"));
            }
        },
        POSTGRES(DatabaseType.Postgres) {
            @Override
            Connection open() throws SQLException {
                return DriverManager.getConnection(EmbeddedPostgres.shared().jdbcUrl("postgres"));
            }

            @Override
            Connection openSystem() throws SQLException {
                return DriverManager.getConnection(EmbeddedPostgres.shared().jdbcUrl("postgres"));
            }
        },
        DATABRICKS(DatabaseType.Databricks) {
            @Override
            Connection open() throws SQLException {
                return CloudLane.connect(CloudLane.Cloud.DATABRICKS);
            }

            @Override
            Connection openSystem() throws SQLException {
                return CloudLane.openSystem(CloudLane.Cloud.DATABRICKS);
            }
        },
        SNOWFLAKE(DatabaseType.Snowflake) {
            @Override
            Connection open() throws SQLException {
                return CloudLane.connect(CloudLane.Cloud.SNOWFLAKE);
            }

            @Override
            Connection openSystem() throws SQLException {
                return CloudLane.openSystem(CloudLane.Cloud.SNOWFLAKE);
            }
        },
        BIGQUERY(DatabaseType.BigQuery) {
            @Override
            Connection open() throws SQLException {
                return CloudLane.connect(CloudLane.Cloud.BIGQUERY);
            }

            @Override
            Connection openSystem() throws SQLException {
                return CloudLane.openSystem(CloudLane.Cloud.BIGQUERY);
            }
        },
        /** MongoDB 8.0 (docs/MONGODB_PLAN_2026_10_16.md §6, 2026-10-17): no JDBC session -- channel A runs through
         *  the plan path ({@link #throughThePlan}: the expression planned to Pure's TDS text, run by the plan runner on
         *  the session the connector opens), as the lane's database is reached by its connector alone. */
        MONGODB(DatabaseType.MongoDB) {
            @Override
            Connection open() throws SQLException {
                throw new IllegalStateException("the MongoDB lane has no JDBC session: channel A runs through the"
                        + " plan (PctBackend.throughThePlan, PctPlanRoute)");
            }

            @Override
            Connection openSystem() throws SQLException {
                throw new IllegalStateException("the MongoDB lane has no system database: the metamodel store on"
                        + " MongoDB is refused by name (docs/MONGODB_PLAN_2026_10_16.md D13)");
            }
        };

        final DatabaseType type;

        Lane(DatabaseType type) {
            this.type = type;
        }

        abstract Connection open() throws SQLException;

        /** A NEW session for the platform's system database on this server (closing it closes it). An in-process
         *  lane has none: asking is a harness bug. */
        Connection openSystem() throws SQLException {
            throw new IllegalStateException(this + " is in-process: the platform's system database is its own"
                    + " private instance, on no system connection");
        }

        boolean inProcess() {
            return com.legend.exec.Sessions.inProcess(type);
        }
    }

    private static Lane lane() {
        String backend = System.getenv("LEGENDLITE_PCT_BACKEND");
        if (backend == null) {
            return Lane.DUCKDB;
        }
        return switch (backend) {
            case "h2" -> Lane.H2;
            case "postgres" -> Lane.POSTGRES;
            case "sqlite" -> Lane.SQLITE;
            case "mariadb" -> Lane.MARIADB;
            case "mysql" -> Lane.MYSQL;
            case "trino" -> Lane.TRINO;
            case "clickhouse" -> Lane.CLICKHOUSE;
            case "databricks" -> Lane.DATABRICKS;
            case "snowflake" -> Lane.SNOWFLAKE;
            case "bigquery" -> Lane.BIGQUERY;
            case "mongodb" -> Lane.MONGODB;
            default -> throw new IllegalStateException("LEGENDLITE_PCT_BACKEND=" + backend + ": a PCT lane runs on"
                    + " DuckDB (unset), h2, postgres, sqlite, mariadb, mysql, trino, clickhouse, databricks, snowflake,"
                    + " bigquery or mongodb");
        };
    }

    /** The lane's name, as {@code LEGENDLITE_PCT_BACKEND} spells it ({@code duckdb} when unset). */
    public static String name() {
        return lane().name().toLowerCase(java.util.Locale.ROOT);
    }

    /** Whether this lane is the reference lane, an in-memory DuckDB. */
    public static boolean isDuckDb() {
        return lane() == Lane.DUCKDB;
    }

    /** Whether channel A runs through the plan path (the expression planned to Pure's TDS text and run by the plan
     *  runner on the session the plan's target opens) rather than the legacy executor on a JDBC session: every lane
     *  (the plan path program's step 9, docs/PLAN_PATH_PROGRAM_2026_10_17.md §7). The MongoDB lane ran here first
     *  (docs/MONGODB_PLAN_2026_10_16.md §6), the DuckDB lane since 2026-10-18 (the user: "flip and let's fix
     *  everything"; exact at its first landing), the rest since 2026-10-19, each burned down in its own container
     *  (docs/STEP9_LANE_BRIEF_2026_10_19.md). The legacy route is deleted with step 11. */
    public static boolean throughThePlan() {
        return true;
    }

    /** The system property the MongoDB lane's storeless runtime names for its password (a vault reference in the
     *  declared connection; the embedded server's throwaway password is set under it when the runtime is declared). */
    static final String MONGODB_PASSWORD_PROPERTY = "legend.mongodb.pct.password";

    /** This lane's storeless runtime declared alone: the session {@link #connect} opens. */
    public static String storelessRuntime() {
        return withStorelessRuntime("");
    }

    /** {@code model} with this lane's storeless runtime declared beside it: the session {@link #connect} opens. */
    static String withStorelessRuntime(String model) {
        Lane lane = lane();
        return switch (lane) {
            case DUCKDB, H2, SQLITE -> com.legend.test.StorelessRuntime.with(model, lane.type);
            case POSTGRES -> com.legend.test.StorelessRuntime.onServer(model, lane.type, "127.0.0.1",
                    EmbeddedPostgres.shared().port(), "postgres");
            case MARIADB -> com.legend.test.StorelessRuntime.onServer(model, lane.type, "127.0.0.1",
                    EmbeddedMariaDb.shared().port(), "pct");
            case MYSQL -> com.legend.test.StorelessRuntime.onServer(model, lane.type, "127.0.0.1",
                    EmbeddedMySql.shared().port(), "pct");
            // the coordinates of the session connect() opens, declared as Trino's own specification
            case TRINO -> com.legend.test.StorelessRuntime.declaring(model, lane.type, "specification: Trino { host:"
                    + " '127.0.0.1'; port: " + EmbeddedTrino.shared().port() + "; catalog: 'memory'; schema: 'default';"
                    + " };\n  auth: Test");
            case CLICKHOUSE -> com.legend.test.StorelessRuntime.onServer(model, lane.type, "127.0.0.1",
                    EmbeddedClickHouse.shared().port(), "default");
            case DATABRICKS -> CloudLane.withStorelessRuntime(model, CloudLane.Cloud.DATABRICKS);
            case SNOWFLAKE -> CloudLane.withStorelessRuntime(model, CloudLane.Cloud.SNOWFLAKE);
            case BIGQUERY -> CloudLane.withStorelessRuntime(model, CloudLane.Cloud.BIGQUERY);
            case MONGODB -> {
                System.setProperty(MONGODB_PASSWORD_PROPERTY, EmbeddedMongoDb.PASSWORD);
                yield com.legend.test.StorelessRuntime.onMongo(model, "127.0.0.1", EmbeddedMongoDb.shared().port(),
                        EmbeddedMongoDb.DATABASE, EmbeddedMongoDb.USER, MONGODB_PASSWORD_PROPERTY);
            }
        };
    }

    /** A session on this lane's database. */
    public static Connection connect() throws SQLException {
        return lane().open();
    }

    /**
     * The sessions channel A's plans run on ({@link PctPlanRoute}; step 9, 2026-10-19). An in-process lane's plan
     * opens its own session from its target ({@link com.legend.exec.PlanSessions#shared}: the declared in-memory
     * database, set up by the plan), as MongoDB's does through its connector. A SERVER lane's plan runs on the
     * harness's own session, as the legacy route's did ({@code PctExecuteNative} opened one per case): a NEW session
     * per plan, opened as the lane's SYSTEM session ({@code Lane.openSystem}: the one that holds a session's own
     * tables -- ClickHouse's HTTP session, Snowflake's schema, BigQuery's session -- which the plan's session
     * statements make for the system metamodel store), checked by the runner against the plan's target and given the
     * target's session statements as any session is, and closed after. The platform cannot open it itself: the
     * storeless runtime declares the server's coordinates with {@code auth: Test}, which names no user, and
     * {@code Sessions.openingFor} admits no credential and has no opening for Trino, ClickHouse or the clouds, so the
     * driver connected as the OS user (MySQL 8.4's root by caching_sha2_password, refused; Postgres's "role does not
     * exist") or not at all. A declared server connection opened with its credentials through a vault is the
     * platform's item (the program's §7 residue), not this harness's. A plan with setup on a server lane goes to the
     * plan's own source, which refuses it by name: the server's database is the user's.
     */
    public static com.legend.exec.PlanSessions.Source planSessions() {
        com.legend.exec.PlanSessions.Source own = com.legend.exec.PlanSessions.shared();
        return switch (lane()) {
            case DUCKDB, H2, SQLITE, MONGODB -> own;
            case POSTGRES, MARIADB, MYSQL, TRINO, CLICKHOUSE, DATABRICKS, SNOWFLAKE, BIGQUERY -> target -> {
                if (!target.setup().isEmpty()) {
                    return own.open(target);
                }
                Connection c;
                try {
                    c = lane().openSystem();
                } catch (SQLException e) {
                    throw new IllegalStateException("the " + name() + " lane's session could not be opened: "
                            + e.getMessage(), e);
                }
                return new com.legend.exec.Sessions.Session() {
                    @Override
                    public Connection connection() {
                        return c;
                    }

                    @Override
                    public void close() {
                        try {
                            c.close();
                        } catch (SQLException e) {
                            throw new IllegalStateException("the " + name() + " lane's session could not be closed: "
                                    + e.getMessage(), e);
                        }
                    }
                };
            };
        };
    }

    private static volatile @com.legend.base.Nullable String serverVersion;

    /** The version of the server this lane's sessions connect to, read once off one session (the plan route plans
     *  for it: H2's dialect spells by the version, `TypedQuery.executionPlan`'s last argument; step 9, 2026-10-19);
     *  null for the lane with no JDBC session (MongoDB). */
    public static @com.legend.base.Nullable String serverVersion() {
        if (lane() == Lane.MONGODB) {
            return null;
        }
        String v = serverVersion;
        if (v == null) {
            try (Connection c = connect()) {
                v = com.legend.exec.Sessions.version(c);
            } catch (SQLException e) {
                throw new IllegalStateException("the " + name() + " lane's server version could not be read: "
                        + e.getMessage(), e);
            }
            serverVersion = v;
        }
        return v;
    }

    /** The host's system connections: a server lane's own engine, opened by {@link Lane#openSystem}; none for an
     *  in-process lane. */
    public static java.util.Map<DatabaseType, com.legend.exec.Sessions.Opener> systemConnections() {
        Lane lane = lane();
        return lane.inProcess() ? java.util.Map.of() : java.util.Map.of(lane.type, lane::openSystem);
    }
}
