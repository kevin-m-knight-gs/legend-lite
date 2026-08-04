package com.legend.protocol.spec;

import java.util.List;
import java.util.Objects;

/**
 * Generic function application &mdash; THE workhorse AST node.
 *
 * <p>In Pure, {@code $x->foo(y)} is sugar for {@code foo($x, y)}: the
 * arrow form prepends the receiver as the first parameter. After
 * parsing, both source forms produce identical {@link AppliedFunction}
 * nodes. Examples:
 *
 * <ul>
 *   <li>{@code abs(x)} &rarr; {@code AppliedFunction("abs", [x])}</li>
 *   <li>{@code $p->filter({p|...})} &rarr;
 *       {@code AppliedFunction("filter", [$p, lambda])}</li>
 *   <li>{@code Person.all()} &rarr;
 *       {@code AppliedFunction("all", [PackageableElementPtr("Person")])}</li>
 *   <li>{@code my::pkg::add(x, y)} &rarr;
 *       {@code AppliedFunction("my::pkg::add", [x, y])}</li>
 * </ul>
 *
 * <p>The {@code function} field preserves the source-level name
 * verbatim, including any FQN prefix; name resolution (simple name
 * &rarr; FQN) is the next pipeline stage's job (see Phase D
 * {@code NameResolver}).
 *
 * <h2>Deliberate divergence from engine's {@code AppliedFunction}</h2>
 *
 * <p>The engine record carries three extra fields:
 * <ul>
 *   <li>{@code boolean hasReceiver} &mdash; engine's own doc admits this
 *       exists "so the adapter can correctly reconstruct the old IR's
 *       source/args split". It is a back-compat shim for a legacy IR
 *       shape we don't carry. Semantically the arrow form and the
 *       prefix form are <em>the same expression</em> ({@code $x->foo(y)}
 *       is defined as sugar for {@code foo($x, y)}); after parsing,
 *       receiver-vs-non-receiver is irrelevant. Source-form recovery,
 *       if ever required, is a source-location concern that belongs on
 *       a sidecar, not on every node forever.</li>
 *   <li>{@code String sourceText}, {@code List<String> argTexts}
 *       &mdash; verbatim source-text snapshots, kept for engine's "UDF
 *       inlining via textual substitution" path. That path is exactly
 *       what the broader rewrite (see {@code progress/mr-rewrite-progress.md},
 *       {@code HirRewriter}) is moving the engine <em>away</em> from in
 *       favour of AST-based inlining. Importing it would violate
 *       AGENTS.md invariant 4 (no fallbacks / no textual escape
 *       hatches) and bloat every node with strings no consumer needs.
 *       AST-based inlining traverses the parameters directly.</li>
 * </ul>
 *
 * <p>Both omissions are the same pattern we already applied to
 * {@link com.legend.model.FunctionDefinition}: strip engine's
 * compiler-cache / legacy-IR carry-over so the parser record stays
 * pure parser data. Porting test fixtures from upstream legend-engine
 * costs one mechanical adapter call (drop the three trailing fields).
 *
 * @param function    function name as written in source, FQN preserved
 *                    if present (e.g. {@code "filter"} or
 *                    {@code "my::pkg::add"})
 * @param parameters  every parameter in source order; for arrow-form
 *                    source the receiver is at index 0
 */
public record AppliedFunction(
        String function,
        List<ValueSpecification> parameters,
        List<String> candidateFqns,
        @com.legend.Nullable com.legend.protocol.SourceInfo pos,
        boolean propertyCall) implements ValueSpecification {

    public AppliedFunction {
        Objects.requireNonNull(function, "function");
        Objects.requireNonNull(parameters, "parameters");
        parameters = List.copyOf(parameters);
        candidateFqns = candidateFqns == null ? List.of()
                : List.copyOf(candidateFqns);
    }

    /** Position-free form (resolver rewrites, synthesis, tests). The parser's span
     *  convention VARIES BY OPERATOR FAMILY — verified via ProbeWireShapes:
     *  infix arithmetic and comparisons span op..RHS-end; {@code equal}/{@code and}/
     *  {@code or} span the operator token only; named calls span the name token only;
     *  {@code not}-from-{@code !} spans {@code !}..operand-end. */
    public AppliedFunction(String function, List<ValueSpecification> parameters,
            List<String> candidateFqns) {
        this(function, parameters, candidateFqns, null, false);
    }

    /** Span-carrying form for ordinary (non-dot) applications. */
    public AppliedFunction(String function, List<ValueSpecification> parameters,
            List<String> candidateFqns, @com.legend.Nullable com.legend.protocol.SourceInfo pos) {
        this(function, parameters, candidateFqns, pos, false);
    }

    /** Position and the dot-call spelling marker are excluded from equality — see
     *  {@code ValueSpecEqualityTest}. {@code propertyCall} records that the source spelled
     *  this application as {@code receiver.name(args)}: the WIRE emits that form as a
     *  property node, not a func (harness DIFF on AccountWithConstraints), while the
     *  compiler treats both spellings identically. */
    @Override
    public boolean equals(Object o) {
        return o instanceof AppliedFunction other
                && function.equals(other.function())
                && parameters.equals(other.parameters())
                && candidateFqns.equals(other.candidateFqns());
    }

    @Override
    public int hashCode() {
        return Objects.hash(function, parameters, candidateFqns);
    }

    /**
     * The common form: no import-ambiguity candidates. {@code candidateFqns}
     * is filled ONLY by the name resolver when a SIMPLE call name matches
     * several imported packages — real pure merges same-named functions
     * from every imported package into ONE overload set and picks by
     * signature, so the resolver carries the candidate FQNs and the Typer
     * unions their overloads (types stay single-referent: an ambiguous
     * TYPE reference is still an error).
     */
    public AppliedFunction(String function, List<ValueSpecification> parameters) {
        this(function, parameters, List.of());
    }

    /** The parse-level {@code if} spelling — the single owner of the
     * name compare (string-dispatch freeze). */
    public static boolean isIf(AppliedFunction af) {
        return af.function().equals("if");
    }
}
