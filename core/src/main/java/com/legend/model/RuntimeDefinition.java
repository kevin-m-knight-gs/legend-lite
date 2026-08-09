package com.legend.model;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A parsed Pure {@code Runtime} declaration &mdash; binds one or more
 * {@code Mapping}s to a set of store-to-connection bindings.
 *
 * <p>Pure syntax:
 * <pre>
 *   Runtime my::MyRuntime
 *   {
 *     mappings: [ my::MyMapping ];
 *     connections:
 *     [
 *       store::PersonDb: store::InMemoryDuckDb
 *     ];
 *   }
 * </pre>
 *
 * <p>Or with an embedded JSON connection:
 * <pre>
 *   connections: [
 *     ModelStore: [
 *       json: #{ JsonModelConnection { class: ...; url: '...'; } }#
 *     ]
 *   ];
 * </pre>
 *
 * <p>Engine's record exposes a {@code getConnectionForStore(store)} convenience
 * helper and {@code hasJsonConnections()}. Core/'s parser record is pure data;
 * callers can compute those inline.
 *
 * @param qualifiedName       fully qualified runtime name
 * @param mappings            qualified names of bound {@code Mapping}s (declaration order)
 * @param connectionBindings  store qualified name &rarr; connection qualified
 *                            names, in declaration order — engine allows
 *                            SEVERAL connections per store
 * @param jsonConnections     inline {@code JsonModelConnection} bindings parsed
 *                            from {@code #{ ... }#} islands (cross-baked into
 *                            the bound mappings by {@code ModelBuilder})
 * @param inlineConnections   NON-json embedded islands (relational, xml,
 *                            model-chain) hoisted to anonymous elements whose
 *                            names carry the reserved {@code $} sigil;
 *                            {@code ModelBuilder.ingestRuntime} registers them
 *                            so bindings referencing them resolve
 */
public record RuntimeDefinition(
        String qualifiedName,
        List<String> mappings,
        Map<String, List<String>> connectionBindings,
        List<JsonModelConnection> jsonConnections,
        List<PackageableElement> inlineConnections) implements PackageableElement {

    public RuntimeDefinition {
        Objects.requireNonNull(qualifiedName, "Qualified name cannot be null");
        mappings = mappings == null ? List.of() : List.copyOf(mappings);
        connectionBindings = connectionBindings == null ? Map.of()
                : deepCopy(connectionBindings);
        jsonConnections = jsonConnections == null ? List.of() : List.copyOf(jsonConnections);
        inlineConnections = inlineConnections == null ? List.of()
                : List.copyOf(inlineConnections);
    }

    /** Convenience for the overwhelmingly common one-connection-per-store
     *  runtime shape. */
    public RuntimeDefinition(String qualifiedName, List<String> mappings,
            Map<String, String> singleBindings,
            List<JsonModelConnection> jsonConnections) {
        this(qualifiedName, mappings, widen(singleBindings), jsonConnections,
                List.of());
    }

    private static Map<String, List<String>> widen(Map<String, String> m) {
        Map<String, List<String>> out = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, String> e : m.entrySet()) {
            out.put(e.getKey(), List.of(e.getValue()));
        }
        return out;
    }

    private static Map<String, List<String>> deepCopy(Map<String, List<String>> m) {
        Map<String, List<String>> out = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, List<String>> e : m.entrySet()) {
            out.put(e.getKey(), List.copyOf(e.getValue()));
        }
        return java.util.Collections.unmodifiableMap(out);
    }
}
