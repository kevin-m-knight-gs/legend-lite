// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.compiler.spec;

import com.legend.compiler.element.type.Multiplicity;
import com.legend.compiler.element.type.ExprType;
import com.legend.compiler.element.type.Type;
import com.legend.compiler.spec.typed.TypedCollection;
import com.legend.compiler.spec.typed.TypedConcatenate;
import com.legend.compiler.spec.typed.TypedEval;
import com.legend.compiler.spec.typed.TypedFrom;
import com.legend.compiler.spec.typed.TypedLambda;
import com.legend.compiler.spec.typed.TypedLet;
import com.legend.compiler.spec.typed.TypedMap;
import com.legend.compiler.spec.typed.TypedNativeCall;
import com.legend.compiler.spec.typed.TypedNewInstance;
import com.legend.compiler.spec.typed.TypedPackageableRef;
import com.legend.compiler.spec.typed.TypedSpec;
import com.legend.compiler.spec.typed.TypedUserCall;
import com.legend.compiler.spec.typed.TypedVariable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The {@code execute(f, mapping, runtime, ext)} CHAIN ASSEMBLY — the
 * compiler half of frame building (Invariant 7: minting typed nodes is
 * compiler work). {@link #prepare} peels the query argument to its
 * lambda and validates the mapping reference; {@link #chain} inlines the
 * body and attaches the execution context as a {@link TypedFrom}. The
 * executor interleaves its execution-bound steps between the two calls
 * (runtime-argument effects, table-replace recording) and owns the
 * eager run — WHEN a frame's value exists is the executor's; WHAT the
 * chain means is the compiler's.
 */
public final class ExecuteChainAssembly {

    private ExecuteChainAssembly() {
    }

    /** The peeled, validated query half: the zero-arg query lambda and
     * the explicit mapping reference (null under the empty-mapping
     * sentinel {@code ^Mapping(name='')} — every branch then carries
     * its own {@code ->from()}). */
    public record Prepared(TypedLambda lam,
            @com.legend.Nullable TypedPackageableRef mref) {
    }

    /** The assembled chain and whether its ROOT is relation-shaped (the
     * engine's {@code Result.values} for a TDS query holds ONE TDS; for
     * a class or scalar root, values IS the collection). */
    public record Chain(TypedSpec chain, boolean relationRooted) {
    }

    /** A let-bound argument resolves through the caller's let prefix
     * ({@code let q = |...|; execute($q, ...)}). */
    public static TypedSpec letBound(TypedSpec arg,
            List<TypedSpec> letPrefix) {
        if (arg instanceof TypedVariable v) {
            for (int i = letPrefix.size() - 1; i >= 0; i--) {
                if (letPrefix.get(i) instanceof TypedLet let
                        && let.name().equals(v.name())) {
                    return let.value();
                }
            }
        }
        return arg;
    }

    /** Peel the query argument to its zero-arg lambda (β-inline a
     * lambda-building user call; read through preval/withFeatureFlags
     * plan-time wrappers; fold concatenateTemporalTdsQueries BY
     * EMISSION) and validate the mapping argument. */
    public static Prepared prepare(TypedNativeCall ec,
            List<TypedSpec> letPrefix, SpecCompiler specs) {
        TypedSpec q = letBound(ec.args().get(0), letPrefix);
        // a LAMBDA-BUILDING user call in query position (corpus
        // buildQuery(value) returning FunctionDefinition<{->Person[*]}>):
        // β-inline it — the body's single expression IS the lambda literal
        if (q instanceof TypedUserCall) {
            q = new UserCallInliner(specs).inlineBody(List.of(q)).get(0);
        }
        // preval(query, extensions) / withFeatureFlags(query, flags):
        // plan-time wrappers, IDENTITY for row semantics — read through
        // to the wrapped query lambda.
        while (q instanceof TypedNativeCall pv
                && ("meta::pure::router::preeval::preval"
                        .equals(pv.callee().qualifiedName())
                    || "meta::pure::executionPlan::featureFlag::withFeatureFlags"
                        .equals(pv.callee().qualifiedName()))) {
            q = letBound(pv.args().get(0), letPrefix);
        }
        // concatenateTemporalTdsQueries(lfs): the real body folds the
        // queries into concatenate SFEs (reflection metamodel) — the SAME
        // semantics BY EMISSION: fold the lambdas' result expressions
        // into a TypedConcatenate chain under one zero-arg lambda.
        if (q instanceof TypedNativeCall cq
                && "meta::relational::milestoning::concatenateTemporalTdsQueries"
                        .equals(cq.callee().qualifiedName())) {
            q = concatenateFold(cq, letPrefix, specs);
        }
        if (!(q instanceof TypedLambda lam) || !lam.parameters().isEmpty()) {
            throw new com.legend.error.NotImplementedException(
                    "execute() whose query argument is not a lambda");
        }
        TypedSpec mArg = letBound(ec.args().get(1), letPrefix);
        // the EMPTY-MAPPING SENTINEL ^Mapping(name='') (testFrom.pure:30):
        // every branch carries its own ->from() — no explicit mapping to
        // attach; the chain's from() walls stay the honest failure
        boolean sentinelMapping = mArg instanceof TypedNewInstance sni
                && "meta::pure::mapping::Mapping".equals(sni.classFqn());
        TypedPackageableRef mref = null;
        if (!sentinelMapping) {
            if (!(mArg instanceof TypedPackageableRef mr)) {
                throw new com.legend.error.NotImplementedException(
                        "execute() mapping argument must be a mapping reference");
            }
            mref = mr;
        }
        return new Prepared(lam, mref);
    }

    private static TypedSpec concatenateFold(TypedNativeCall cq,
            List<TypedSpec> letPrefix, SpecCompiler specs) {
        TypedSpec lfsArg = letBound(cq.args().get(0), letPrefix);
        // evaluateAndDeactivate may wrap the WHOLE collection
        // ([...]->evaluateAndDeactivate()) — identity, peel first
        while (lfsArg instanceof TypedNativeCall ow
                && ow.args().size() == 1
                && "meta::pure::functions::meta::evaluateAndDeactivate"
                        .equals(ow.callee().qualifiedName())) {
            lfsArg = letBound(ow.args().get(0), letPrefix);
        }
        // MAP-BUILT collections ($bds->map(bd|{|...}->eAD())): β-expand
        // the map over the literal elements — one TypedEval per element,
        // reduced by the inliner (the full β-substitution engine)
        if (lfsArg instanceof TypedMap mapC
                && letBound(mapC.mapper(), letPrefix)
                        instanceof TypedLambda mapLam
                && mapLam.parameters().size() == 1
                && letBound(mapC.source(), letPrefix)
                        instanceof TypedCollection dc) {
            List<TypedSpec> expanded = new ArrayList<>(dc.elements().size());
            for (TypedSpec d : dc.elements()) {
                expanded.add(new UserCallInliner(specs)
                        .inlineBody(List.of(new TypedEval(
                                mapLam, List.of(d),
                                mapLam.body().get(mapLam.body().size() - 1)
                                        .info())))
                        .get(0));
            }
            lfsArg = new TypedCollection(expanded, lfsArg.info());
        }
        List<TypedSpec> els = lfsArg instanceof TypedCollection tc
                ? tc.elements() : List.of(lfsArg);
        List<TypedSpec> queries = new ArrayList<>();
        for (TypedSpec e : els) {
            TypedSpec le = letBound(e, letPrefix);
            while (le instanceof TypedNativeCall w
                    && w.args().size() == 1
                    && "meta::pure::functions::meta::evaluateAndDeactivate"
                            .equals(w.callee().qualifiedName())) {
                le = letBound(w.args().get(0), letPrefix);
            }
            if (!(le instanceof TypedLambda ql) || !ql.parameters().isEmpty()) {
                throw new com.legend.error.NotImplementedException(
                        "concatenateTemporalTdsQueries over a non-literal"
                        + " lambda collection is not supported yet"
                        + " (element " + le.getClass().getSimpleName()
                        + ", carrier " + lfsArg.getClass().getSimpleName()
                        + ")");
            }
            queries.add(ql.body().get(ql.body().size() - 1));
        }
        TypedSpec folded = queries.get(0);
        for (int qi = 1; qi < queries.size(); qi++) {
            folded = new TypedConcatenate(folded, queries.get(qi),
                    folded.info());
        }
        return new TypedLambda(List.of(), List.of(folded),
                new ExprType(new Type.FunctionType(List.of(),
                        new Type.Param(folded.info().type(),
                                folded.info().multiplicity())),
                        Multiplicity.Bounded.ONE));
    }

    /**
     * Inline the prepared lambda's body against the caller's let prefix
     * and attach the execution context: a chain with no {@code ->from()}
     * inside gains one from the EXPLICIT mapping argument (plus the
     * ambient runtime and the runtime argument's ModelChainConnection
     * mappings / JSON sources — the XStore rule, same as FromChecker's
     * instance-runtime arm). Inliner-consumed query lets accumulate into
     * {@code queryLetsSink} (the resolver's let env resolves surviving
     * reads — engine inScopeVars).
     */
    public static Chain chain(Prepared p, TypedNativeCall ec,
            List<TypedSpec> letPrefix, SpecCompiler specs,
            @com.legend.Nullable String runtimeFqn,
            Map<String, TypedSpec> queryLetsSink) {
        List<TypedSpec> qb = new ArrayList<>(letPrefix);
        qb.addAll(p.lam().body());
        var inliner = new UserCallInliner(specs);
        TypedSpec chain = inliner.inlineBody(qb).get(0);
        queryLetsSink.putAll(inliner.queryLets());
        if (!containsTypedFrom(chain)) {
            if (p.mref() == null) {
                throw new com.legend.error.NotImplementedException(
                        "execute() with the empty-mapping sentinel requires"
                        + " ->from() context inside the query");
            }
            Optional<TypedPackageableRef> runtime = runtimeFqn == null
                    ? Optional.empty()
                    : Optional.of(new TypedPackageableRef(runtimeFqn,
                            p.mref().info()));
            // the execute() RUNTIME ARGUMENT's connection content is
            // harness-ambient EXCEPT ModelChainConnection mappings — the
            // XStore chain: an M2M mapping's ~src classes resolve THROUGH
            // them (same rule as FromChecker's instance-runtime arm)
            List<String> chainMappings = ec.args().size() >= 3
                    ? TypedFrom.chainMappingsIn(
                            letBound(ec.args().get(2), letPrefix))
                    : List.of();
            Map<String, String> jsonSources = ec.args().size() >= 3
                    ? TypedFrom.jsonSourcesIn(
                            letBound(ec.args().get(2), letPrefix))
                    : Map.of();
            chain = new TypedFrom(chain, Optional.of(p.mref()), runtime,
                    chainMappings, jsonSources, chain.info());
        }
        return new Chain(chain,
                Type.isRelation(chain.info().type()));
    }

    /** Whether the chain (transitively) carries a {@code ->from()}. */
    public static boolean containsTypedFrom(TypedSpec n) {
        if (n instanceof TypedFrom) {
            return true;
        }
        for (TypedSpec c : n.children()) {
            if (containsTypedFrom(c)) {
                return true;
            }
        }
        return false;
    }
}
