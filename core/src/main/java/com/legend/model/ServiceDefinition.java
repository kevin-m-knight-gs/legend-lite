package com.legend.model;

import com.legend.protocol.spec.ValueSpecification;

import java.util.List;
import java.util.Objects;

/**
 * A parsed Pure {@code Service} declaration &mdash; a REST endpoint bound to a
 * Pure query expression with optional mapping &amp; runtime references.
 *
 * <p>Pure syntax (excerpt):
 * <pre>
 *   Service my::api::GetPerson
 *   {
 *     pattern: '/api/person/{id}';
 *     documentation: 'fetch by id';
 *     execution: Single {
 *       query: |Person.all()->filter(p|$p.id == $id);
 *       mapping: my::PersonMapping;
 *       runtime: my::PersonRuntime;
 *     }
 *     testSuites: [ ... ]
 *   }
 * </pre>
 *
 * <h2>Deliberate divergences from engine's {@code ServiceDefinition}</h2>
 * <ul>
 *   <li><strong>No {@code toRegexPattern()} / {@code extractPathParams()}.</strong>
 *       Those convert {@code pattern} into HTTP-matching machinery and belong
 *       in a future {@code runtime/rest/} layer, not on a parser record.
 *       Derive on demand from {@link #pattern()}.</li>
 *   <li><strong>No convenience factories.</strong> Records get the canonical
 *       constructor; callers pass {@code null} for missing optional fields.</li>
 *   <li><strong>{@code testSuitesSource} is captured as raw source text</strong>
 *       (not parsed into typed records). Engine skips the block entirely; we
 *       preserve it so Phase B.4 can parse it once
 *       {@code LegacyMappingDefinition.TestSuiteDefinition} lands.
 *       Tracked as decision D-3 in core's README.</li>
 *   <li><strong>Unknown top-level keys throw</strong> (engine silently
 *       {@code skipToSemicolon}'s). Matches AGENTS.md invariant 4 (no fallbacks).</li>
 * </ul>
 *
 * @param qualifiedName     fully qualified service name
 * @param pattern           the URL pattern as written (e.g. {@code "/api/person/{id}"});
 *                          never {@code null} &mdash; defaults to {@code "/"} when absent
 * @param functionBody      parsed query expression (the AST between
 *                          {@code |...|} after {@code query:})
 * @param documentation     human-readable description, or {@code null} if absent
 * @param mappingRef        qualified name of the {@code Mapping} bound to this service,
 *                          or {@code null} if absent
 * @param runtimeRef        qualified name of the {@code Runtime} bound to this service,
 *                          or {@code null} if absent
 * @param testSuitesSource  raw text inside the {@code testSuites { ... }} block,
 *                          or {@code null} if absent. Parsed in Phase B.4.
 */
public record ServiceDefinition(
        String qualifiedName,
        String pattern,
        ValueSpecification functionBody,
        @com.legend.Nullable String documentation,
        @com.legend.Nullable String mappingRef,
        @com.legend.Nullable String runtimeRef,
        @com.legend.Nullable String testSuitesSource,
        List<String> owners,
        @com.legend.Nullable Boolean autoActivateUpdates,
        @com.legend.Nullable MultiExecution multiExecution,
        @com.legend.Nullable String testSource)
        implements PackageableElement {

    public ServiceDefinition {
        Objects.requireNonNull(qualifiedName, "Qualified name cannot be null");
        Objects.requireNonNull(pattern, "Pattern cannot be null");
        Objects.requireNonNull(functionBody, "Function body cannot be null");
        owners = owners == null ? List.of() : List.copyOf(owners);
    }

    /** The single-execution shape most callers build. */
    public ServiceDefinition(String qualifiedName, String pattern,
            ValueSpecification functionBody,
            @com.legend.Nullable String documentation,
            @com.legend.Nullable String mappingRef,
            @com.legend.Nullable String runtimeRef,
            @com.legend.Nullable String testSuitesSource) {
        this(qualifiedName, pattern, functionBody, documentation, mappingRef,
                runtimeRef, testSuitesSource, List.of(), null, null, null);
    }

    /** {@code execution: Multi} — one shared query, an execution-key
     *  parameter, and per-key mapping/runtime environments. When set,
     *  {@link #mappingRef()}/{@link #runtimeRef()} are null; execution
     *  machinery selects the environment by key at request time (not yet
     *  wired — services with a multi execution parse and carry). */
    public record MultiExecution(String key, List<KeyedExecution> executions) {
        public MultiExecution {
            Objects.requireNonNull(key, "Execution key cannot be null");
            executions = List.copyOf(executions);
        }
    }

    /** One keyed environment: {@code executions['QA']: { mapping; runtime; }}. */
    public record KeyedExecution(String keyValue,
            @com.legend.Nullable String mapping,
            @com.legend.Nullable String runtime) {
        public KeyedExecution {
            Objects.requireNonNull(keyValue, "Key value cannot be null");
        }
    }
}
