package com.gs.legend.server;

import com.gs.legend.serial.ResultSerializer;
import com.gs.legend.serial.SerializerRegistry;
import com.legend.exec.ExecutionResult;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;

/**
 * Stateless query execution: parse → compile → plan → execute — the CORE
 * pipeline end to end (engine-lite deletion; the A/B settled). Results are
 * core records ({@link ExecutionResult}); this class owns orchestration and
 * connection resolution only.
 *
 * <p>Two orthogonal concerns, two method families:
 *
 * <h2>Snapshot ({@code execute}) — materializes all rows in memory</h2>
 * <ul>
 *   <li>{@link #execute(String, String, String, Connection)} — returns a
 *       typed {@link ExecutionResult} the caller can introspect.</li>
 *   <li>{@link #execute(String, String, String)} — same, auto-resolves
 *       the JDBC connection from the Runtime.</li>
 *   <li>{@link #execute(String, String, String, Connection, OutputStream, OutputFormat)}
 *       — materializes then writes serialized bytes to an OutputStream in
 *       the chosen {@link OutputFormat} (JSON, CSV).</li>
 *   <li>{@link #execute(String, String, String, OutputStream, OutputFormat)}
 *       — same, auto-resolves the connection.</li>
 * </ul>
 *
 * <h2>Streaming ({@code stream}) — writes JSON row-by-row, no materialization</h2>
 * <ul>
 *   <li>{@link #stream(String, String, String, Connection, OutputStream)} —
 *       iterates the JDBC ResultSet lazily and emits each row directly to the
 *       OutputStream ({@code Compiler.executeStreaming} →
 *       {@code Executor.stream}). Memory footprint is O(one row) regardless
 *       of result size. JSON only.</li>
 *   <li>{@link #stream(String, String, String, OutputStream)} — same,
 *       auto-resolves the connection.</li>
 * </ul>
 *
 * <p>Raw-SQL escape hatch:
 * <ul>
 *   <li>{@link #executeSql(String, String, String)} — runs arbitrary SQL
 *       against the Runtime's connection (no Pure parsing, no plan).</li>
 * </ul>
 */
public class QueryService {

    /**
     * Parse → compile → generate plan → execute with typed result.
     * Mappings are auto-discovered from the registry.
     */
    public ExecutionResult execute(String pureSource, String query, String runtimeName,
            Connection connection) throws SQLException {

        return Objects.requireNonNull(
                com.legend.Compiler.execute(pureSource, query, runtimeName, connection),
                "query produced no result");
    }

    /**
     * Convenience: resolves connection from Runtime, then executes.
     * Mappings are auto-discovered from the registry.
     */
    public ExecutionResult execute(String pureSource, String query, String runtimeName)
            throws SQLException {

        Connection conn = CoreConnectionResolver.resolve(pureSource, runtimeName);
        return execute(pureSource, query, runtimeName, conn);
    }

    /**
     * Snapshot write-to-output: parse → compile → plan → execute → serialize
     * in the requested {@link OutputFormat} to the caller's OutputStream.
     *
     * <p>All rows are materialized into an {@link ExecutionResult} in memory
     * before serialization begins. Use {@link #stream} for true streaming
     * without materialization (JSON only).
     *
     * <p>This method does NOT close {@code out}. Caller owns lifecycle.
     */
    public void execute(String pureSource, String query, String runtimeName,
            Connection connection, OutputStream out, OutputFormat format)
            throws SQLException, IOException {

        ExecutionResult result = execute(pureSource, query, runtimeName, connection);
        ResultSerializer serializer = SerializerRegistry.get(format.id());
        serializer.serialize(result, out);
    }

    /**
     * Convenience: auto-resolves the JDBC connection from the Runtime,
     * then calls {@link #execute(String, String, String, Connection, OutputStream, OutputFormat)}.
     */
    public void execute(String pureSource, String query, String runtimeName,
            OutputStream out, OutputFormat format)
            throws SQLException, IOException {

        Connection conn = CoreConnectionResolver.resolve(pureSource, runtimeName);
        execute(pureSource, query, runtimeName, conn, out, format);
    }

    /**
     * Execute raw SQL against the connection from the Runtime.
     */
    public ExecutionResult executeSql(String pureSource, String sql, String runtimeName)
            throws SQLException {

        Connection conn = CoreConnectionResolver.resolve(pureSource, runtimeName);

        try (java.sql.Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
            // DDL/DML: no result set — an empty relation, the caller's
            // "no columns" signal (the legacy empty() contract).
            return new ExecutionResult.Tabular(List.of(), List.of(),
                    new com.legend.compiler.element.type.Type.RelationType(List.of()));
        }
    }

    /**
     * True streaming JSON path — no {@code ExecutionResult} materialization.
     *
     * <p>For Tabular query plans, rows are pulled from the JDBC ResultSet one
     * at a time and written directly to {@code out} as they arrive. Memory
     * footprint is O(one row) regardless of result size.
     *
     * <p>Internally wraps {@code out} in a UTF-8 {@link OutputStreamWriter}
     * and delegates to {@code Compiler.executeStreaming} (the streaming
     * lowering's per-row {@code json_object} root pushed through
     * {@code Executor.stream}). After each row the writer is flushed, so HTTP
     * response bodies, sockets, and other downstream consumers observe
     * incremental delivery.
     *
     * <p>This method does NOT close {@code out}. Caller owns lifecycle.
     */
    public void stream(String pureSource, String query, String runtimeName,
            Connection connection, OutputStream out)
            throws SQLException, IOException {

        Writer writer = new OutputStreamWriter(out, StandardCharsets.UTF_8);
        com.legend.Compiler.executeStreaming(pureSource, query, runtimeName,
                connection, writer);
        writer.flush();
    }

    /**
     * Convenience: auto-resolves the JDBC connection from the Runtime,
     * then calls {@link #stream(String, String, String, Connection, OutputStream)}.
     */
    public void stream(String pureSource, String query, String runtimeName,
            OutputStream out)
            throws SQLException, IOException {

        Connection conn = CoreConnectionResolver.resolve(pureSource, runtimeName);
        stream(pureSource, query, runtimeName, conn, out);
    }

}
