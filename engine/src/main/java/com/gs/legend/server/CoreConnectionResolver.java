package com.gs.legend.server;

import com.gs.legend.exec.ConnectionResolver;
import com.gs.legend.model.def.AuthenticationSpec;
import com.gs.legend.model.def.ConnectionDefinition;
import com.gs.legend.model.def.ConnectionSpecification;
import com.legend.model.ParsedModel;
import com.legend.model.RuntimeDefinition;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Runtime-name &rarr; live JDBC connection, resolved from the CORE parse
 * (engine-lite deletion): finds the runtime's first connection binding in
 * {@code com.legend.model} records and hands the EXISTING
 * {@link ConnectionResolver} an equivalent definition. This class is the
 * ONLY remaining translation between the core model and the execution
 * layer's connection records; it dies when the exec layer reads core
 * records directly.
 */
final class CoreConnectionResolver {

    private CoreConnectionResolver() {
    }

    static Connection resolve(String pureSource, String runtimeName)
            throws SQLException {
        ParsedModel model = com.legend.parser.ElementParser.parse(pureSource);
        RuntimeDefinition runtime = null;
        for (var el : model.elements()) {
            if (el instanceof RuntimeDefinition r
                    && (r.qualifiedName().equals(runtimeName)
                            || simpleName(r.qualifiedName())
                                    .equals(runtimeName))) {
                runtime = r;
            }
        }
        if (runtime == null) {
            throw new IllegalArgumentException(
                    "Runtime not found: " + runtimeName);
        }
        var bindings = runtime.connectionBindings();
        if (bindings.isEmpty()) {
            throw new IllegalArgumentException(
                    "Runtime has no connection bindings: " + runtimeName);
        }
        var connRefs = bindings.values().iterator().next();
        if (connRefs.isEmpty()) {
            throw new IllegalArgumentException(
                    "Runtime has no connection bindings: " + runtimeName);
        }
        String connectionRef = connRefs.get(0);
        com.legend.model.ConnectionDefinition def = null;
        for (var el : model.elements()) {
            if (el instanceof com.legend.model.ConnectionDefinition c
                    && (c.qualifiedName().equals(connectionRef)
                            || simpleName(c.qualifiedName())
                                    .equals(connectionRef))) {
                def = c;
            }
        }
        if (def == null) {
            throw new IllegalArgumentException(
                    "Connection not found: " + connectionRef);
        }
        return ConnectionResolver.resolve(toExecDefinition(def));
    }

    /** Core connection record &rarr; the exec layer's definition — same
     *  semantics, different package; TestAuth folds to the resolver's
     *  no-op auth. */
    private static ConnectionDefinition toExecDefinition(
            com.legend.model.ConnectionDefinition c) {
        ConnectionSpecification spec = switch (c.specification()) {
            case com.legend.model.ConnectionSpecification.InMemory im ->
                    new ConnectionSpecification.InMemory();
            case com.legend.model.ConnectionSpecification.LocalFile lf ->
                    new ConnectionSpecification.LocalFile(lf.path());
            case com.legend.model.ConnectionSpecification.StaticDatasource s ->
                    new ConnectionSpecification.StaticDatasource(
                            s.host(), s.port(), s.database());
            // LocalH2 (in-memory H2) and everything else the exec layer
            // treats as in-process for tests
            default -> new ConnectionSpecification.InMemory();
        };
        return new ConnectionDefinition(
                c.qualifiedName(), c.storeName(),
                ConnectionDefinition.DatabaseType.valueOf(
                        c.databaseType().name()),
                spec, new AuthenticationSpec.NoAuth());
    }

    private static String simpleName(String fqn) {
        int cut = fqn.lastIndexOf("::");
        return cut < 0 ? fqn : fqn.substring(cut + 2);
    }
}
