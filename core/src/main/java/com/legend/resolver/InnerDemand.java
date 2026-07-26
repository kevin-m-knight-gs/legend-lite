// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.resolver;

import com.legend.compiler.spec.typed.TypedFilter;
import com.legend.compiler.spec.typed.TypedLambda;
import com.legend.compiler.spec.typed.TypedNativeCall;
import com.legend.compiler.spec.typed.TypedPropertyAccess;
import com.legend.compiler.spec.typed.TypedSpec;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * INNER-PREDICATE DEMAND over a navigation head: which lambdas run against
 * the head's TARGET rows, and which of the target's own properties they
 * read. Feeds the target materialization (demanded leaves whose bindings
 * read the target's join slots pull those slots' LEFT joins — W4) and the
 * nested-scope registries (R1 recursive demand).
 *
 * <p>Two consumer shapes contribute, both keyed on the head path over the
 * ENCLOSING lambda's own variable:
 * <ul>
 *   <li>{@code $p.head->filter(f)->exists(g)}-family — a native call whose
 *       receiver is a filter chain over the head (the emptiness family);</li>
 *   <li>{@code $p.head->filter(f)->toOne().leaf} — FILTERED NAVIGATION
 *       consumed as a VALUE (the qualifier family; the shape
 *       {@code Substitution.filteredNavLeafRead} rewrites). Its predicate
 *       reads land on the target pipeline, so its class-typed slot hops
 *       must be demanded here or they die at the rewritePath slot wall.</li>
 * </ul>
 *
 * <p>Every op-level lambda is scanned with its OWN parameter as the
 * instance variable — filter predicates AND the map/project lambdas that
 * hold value-position reads. No shadow-stop (audit-13 B7: the exists
 * rewrite resolves nested predicates through fresh scopes; over-demand is
 * duplicate-safe under EXISTS). Identity-deduped: ops repeat their
 * sources, so the walk sees the same lambda more than once.
 */
final class InnerDemand {

    private InnerDemand() {
    }

    /** NAV-DATE demand (#32): temporal spec dates that READ A NAVIGATION
     * off the parent row ({@code $o.orderDetails.settlementDate}) demand
     * that chain like any other read — the composed date column must
     * materialize for the outer-date calculus to window against it. */
    static Set<List<String>> navDatePaths(
            java.util.Collection<TemporalFrame.TemporalSpec> specs) {
        Set<List<String>> out = new LinkedHashSet<>();
        for (TemporalFrame.TemporalSpec sp : specs) {
            for (TypedSpec dexp : sp.dates()) {
                List<String> path = TemporalFrame.singleVarChain(dexp);
                if (path != null && path.size() >= 2) {
                    out.add(path);
                }
            }
        }
        return out;
    }

    /** {@code paths} with the NAV-DATE chains PREPENDED (registration
     * order matters: the date chain must precede its consuming head). */
    static Set<List<String>> withNavDatePaths(Set<List<String>> paths,
            java.util.Collection<TemporalFrame.TemporalSpec> specs) {
        Set<List<String>> datePaths = navDatePaths(specs);
        if (datePaths.isEmpty()) {
            return paths;
        }
        Set<List<String>> merged = new LinkedHashSet<>(datePaths);
        merged.addAll(paths);
        return merged;
    }

    /** The nav-step aliases carrying NAV-DATE chains — these steps SINK
     * below every consuming head join ({@link Pipelines#sinkNavSteps}):
     * the composed date column must sit on the head's LEFT row. */
    static Set<String> navDateAliases(
            java.util.Collection<TemporalFrame.TemporalSpec> specs,
            java.util.Map<String, String> navHeadByAlias) {
        Set<String> heads = new LinkedHashSet<>();
        for (List<String> dp : navDatePaths(specs)) {
            heads.add(dp.get(0));
        }
        Set<String> aliases = new LinkedHashSet<>();
        for (var e : navHeadByAlias.entrySet()) {
            if (heads.contains(e.getValue())) {
                aliases.add(e.getKey());
            }
        }
        return aliases;
    }

    /** The target-property HEADS the head's inner predicates read. */
    static Set<String> leaves(List<TypedSpec> ops, String head) {
        Set<String> out = new LinkedHashSet<>();
        for (TypedLambda lam : lambdas(ops, List.of(head))) {
            if (!lam.parameters().isEmpty()) {
                collectParamPathHeads(lam,
                        lam.parameters().get(0), out);
            }
        }
        return out;
    }

    /** Nav-step demand for an ExistsSub target (#69/#70): pred paths
     * (correlated + closed parked — the Fork family) and CONTINUED leaf
     * chains all demand the target's OWN class-typed navigate steps; the
     * materialization joins them and the SubNav dispatch reads through
     * the prefixes. Fills {@code aliasOut} (prop &rarr; alias), returns
     * the demanded alias set. Identity dedup keeps join count
     * engine-equal. */
    static Set<String> navStepDemand(ClassSource t, Set<String> navStepKeys,
            TypedLambda corrPred, List<TypedLambda> parkedPreds,
            Set<List<String>> chains, java.util.Map<String, String> aliasOut) {
        Set<String> demand = new LinkedHashSet<>();
        Set<List<String>> paths = new LinkedHashSet<>();
        if (corrPred != null) {
            for (TypedSpec b : corrPred.body()) {
                StoreResolver.consumedPaths(b, corrPred.parameters().get(0),
                        paths);
            }
        }
        for (TypedLambda cp : parkedPreds) {
            for (TypedSpec b : cp.body()) {
                StoreResolver.consumedPaths(b, cp.parameters().get(0), paths);
            }
        }
        for (List<String> pp : paths) {
            if (pp.size() >= 2) {
                demandStep(t, navStepKeys, pp.get(0), demand, aliasOut);
            }
        }
        for (List<String> lc : chains) {
            demandStep(t, navStepKeys, lc.get(0), demand, aliasOut);
        }
        return demand;
    }

    private static void demandStep(ClassSource t, Set<String> navStepKeys,
            String prop, Set<String> demand,
            java.util.Map<String, String> aliasOut) {
        TypedSpec hb = t.bindings().get(prop);
        String al = hb == null ? null
                : StoreResolver.navSlotAlias(hb, t.rowVar(), navStepKeys);
        if (al != null) {
            demand.add(al);
            aliasOut.put(prop, al);
        }
    }

    /** CONTINUED leaf chains past a value-position filtered navigation:
     * a read {@code <head>->filter(..)->toOne().p1...pn} yields
     * {@code [p1..pn]} — the class hops the ExistsSub target must
     * materialize (nav-step demand) so the scalar leaf projects through
     * the joined row (the orgByName('X').parent.name family). */
    static Set<List<String>> leafChains(List<TypedSpec> ops, String head) {
        Set<List<String>> out = new LinkedHashSet<>();
        for (TypedSpec op : ops) {
            scanForChains(op, head, out);
        }
        return out;
    }

    private static void scanForChains(TypedSpec n, String head,
            Set<List<String>> out) {
        if (n instanceof TypedLambda lam && !lam.parameters().isEmpty()) {
            for (TypedSpec b : lam.body()) {
                collectChains(b, lam.parameters().get(0), head, out);
            }
        }
        for (TypedSpec ch : n.children()) {
            scanForChains(ch, head, out);
        }
    }

    private static void collectChains(TypedSpec n, String userVar,
            String head, Set<List<String>> out) {
        if (n instanceof TypedPropertyAccess) {
            java.util.LinkedList<String> chain = new java.util.LinkedList<>();
            TypedSpec src = n;
            while (src instanceof TypedPropertyAccess p) {
                chain.addFirst(p.property());
                src = p.source();
            }
            while (src instanceof TypedNativeCall w && w.args().size() == 1
                    && (w.callee().qualifiedName().equals(
                            "meta::pure::functions::multiplicity::toOne")
                        || w.callee().qualifiedName().equals(
                            "meta::pure::functions::collection::first")
                        || w.callee().qualifiedName().equals(
                            "meta::pure::functions::collection::head"))) {
                src = w.args().get(0);
            }
            boolean sawFilter = false;
            while (src instanceof TypedFilter tf) {
                sawFilter = true;
                src = tf.source();
            }
            if (sawFilter && chain.size() >= 2) {
                List<String> hp = Substitution.pathOf(src, userVar);
                if (hp != null && hp.size() == 1 && hp.get(0).equals(head)) {
                    out.add(List.copyOf(chain));
                }
            }
        }
        for (TypedSpec ch : n.children()) {
            collectChains(ch, userVar, head, out);
        }
    }

    /** Multi-hop paths consumed DIRECTLY under an emptiness-family
     * call — the class-typed-leaf EXISTS registration keys off these. */
    static void collectEmptinessChainPaths(TypedSpec n, String userVar,
            Set<List<String>> out) {
        if (n instanceof TypedNativeCall c && !c.args().isEmpty()) {
            String key = c.callee().signatureKey();
            if (com.legend.builtin.Pure.nativeNamed("isEmpty", key)
                    || com.legend.builtin.Pure.nativeNamed("isNotEmpty", key)
                    || com.legend.builtin.Pure.nativeNamed("exists", key)) {
            List<String> p =
                    Substitution.pathOf(c.args().get(0), userVar);
                if (p != null && p.size() >= 2) {
                    out.add(p);
                }
            }
        }
        if (n instanceof TypedLambda l && l.parameters().contains(userVar)) {
            return;   // shadowing: the substitution stops here too
        }
        for (TypedSpec c : n.children()) {
            collectEmptinessChainPaths(c, userVar, out);
        }
    }

    /** Heads of property paths over {@code param} in the lambda's body. */
    static void collectParamPathHeads(TypedSpec n, String param,
            Set<String> out) {
        List<String> p = Substitution.pathOf(n, param);
        if (p != null && !p.isEmpty()) {
            out.add(p.get(0));
        }
        for (TypedSpec ch : n.children()) {
            collectParamPathHeads(ch, param, out);
        }
    }

    /** The inner lambdas (predicates over the head's target rows). */
    static List<TypedLambda> lambdas(List<TypedSpec> ops, List<String> path) {
        List<TypedLambda> found = new ArrayList<>();
        for (TypedSpec op : ops) {
            scanForLambdas(op, path, found);
        }
        IdentityHashMap<TypedLambda, Boolean> seen = new IdentityHashMap<>();
        List<TypedLambda> out = new ArrayList<>();
        for (TypedLambda lam : found) {
            if (seen.put(lam, Boolean.TRUE) == null) {
                out.add(lam);
            }
        }
        return out;
    }

    private static void scanForLambdas(TypedSpec n, List<String> path,
            List<TypedLambda> out) {
        if (n instanceof TypedLambda lam && !lam.parameters().isEmpty()) {
            for (TypedSpec b : lam.body()) {
                collect(b, lam.parameters().get(0), path, out);
            }
        }
        for (TypedSpec ch : n.children()) {
            scanForLambdas(ch, path, out);
        }
    }

    private static void collect(TypedSpec n, String userVar,
            List<String> path, List<TypedLambda> out) {
        if (n instanceof TypedNativeCall c && !c.args().isEmpty()) {
            // unwrap ->filter(f) chains on the receiver: their lambdas
            // demand target leaves too (filter-wrapped emptiness)
            TypedSpec recv = c.args().get(0);
            List<TypedLambda> chainLams = new ArrayList<>();
            while (recv instanceof TypedFilter tf) {
                chainLams.add(tf.predicate());
                recv = tf.source();
            }
            List<String> p = Substitution.pathOf(recv, userVar);
            if (p != null && p.equals(path)) {
                if (c.args().size() == 2
                        && c.args().get(1) instanceof TypedLambda lam
                        && !lam.parameters().isEmpty()) {
                    out.add(lam);
                }
                out.addAll(chainLams);
            }
        }
        if (n instanceof TypedPropertyAccess pna) {
            // filtered navigation consumed as a VALUE: unwrap the
            // multiplicity wrappers filteredNavLeafRead unwraps, then the
            // filter chain (same recognizer — they must not drift)
            TypedSpec src = pna.source();
            while (src instanceof TypedNativeCall w && w.args().size() == 1
                    && (w.callee().qualifiedName().equals(
                            "meta::pure::functions::multiplicity::toOne")
                        || w.callee().qualifiedName().equals(
                            "meta::pure::functions::collection::first")
                        || w.callee().qualifiedName().equals(
                            "meta::pure::functions::collection::head"))) {
                src = w.args().get(0);
            }
            List<TypedLambda> navLams = new ArrayList<>();
            while (src instanceof TypedFilter tf) {
                navLams.add(tf.predicate());
                src = tf.source();
            }
            if (!navLams.isEmpty()) {
                List<String> np = Substitution.pathOf(src, userVar);
                if (np != null && np.equals(path)) {
                    out.addAll(navLams);
                }
            }
        }
        for (TypedSpec ch : n.children()) {
            collect(ch, userVar, path, out);
        }
    }

    /** task #78 scalar-subquery IN: find in/contains calls whose
     * COLLECTION argument is an object-space chain rooted at TypedGetAll
     * (a let-inlined class query — engine temp-table semantics); resolve
     * each via the caller's dispatcher into a single-column relation and
     * key it by the CALL NODE identity for the substitution arm. */
    static java.util.Map<com.legend.compiler.spec.typed.TypedSpec,
            Substitution.InQueryRead> inQueryReads(
            java.util.List<com.legend.compiler.spec.typed.TypedSpec> ops,
            java.util.List<com.legend.compiler.spec.typed.TypedLambda> terminals,
            java.util.function.Function<com.legend.compiler.spec.typed.TypedSpec,
                    com.legend.compiler.spec.typed.TypedSpec> rawResolver) {
        java.util.List<com.legend.compiler.spec.typed.TypedSpec> roots =
                new java.util.ArrayList<>();
        for (com.legend.compiler.spec.typed.TypedSpec op : ops) {
            if (op instanceof com.legend.compiler.spec.typed.TypedFilter f) {
                roots.addAll(f.predicate().body());
            }
        }
        for (com.legend.compiler.spec.typed.TypedLambda fn : terminals) {
            roots.addAll(fn.body());
        }
        // a trailing ->distinct() (the NATIVE-CALL spelling at this stage)
        // rides OUTSIDE the resolved relation as a relation-level DISTINCT;
        // an unresolvable chain returns null and keeps its ordinary wall.
        java.util.function.Function<com.legend.compiler.spec.typed.TypedSpec,
                com.legend.compiler.spec.typed.TypedSpec> resolver = chain -> {
            try {
                if (chain instanceof com.legend.compiler.spec.typed
                        .TypedNativeCall dc
                        && dc.args().size() == 1
                        && com.legend.builtin.Pure.nativeNamed("distinct",
                                dc.callee().signatureKey())) {
                    com.legend.compiler.spec.typed.TypedSpec rel0 =
                            rawResolver.apply(dc.args().get(0));
                    return rel0 == null ? null
                            : new com.legend.compiler.spec.typed
                                    .TypedDistinct(rel0, java.util.List.of(),
                                    rel0.info());
                }
                // trailing ->limit(n)/->take(n) (native-call spelling
                // pre-substitution, like distinct above) wraps the
                // resolved relation as a relation-level LIMIT (the
                // tdsContains TDS chains — task #78)
                if (chain instanceof com.legend.compiler.spec.typed
                        .TypedNativeCall lc
                        && lc.args().size() == 2
                        && (com.legend.builtin.Pure.nativeNamed("limit",
                                lc.callee().signatureKey())
                            || com.legend.builtin.Pure.nativeNamed("take",
                                lc.callee().signatureKey()))) {
                    com.legend.compiler.spec.typed.TypedSpec rel0 =
                            rawResolver.apply(lc.args().get(0));
                    return rel0 == null ? null
                            : new com.legend.compiler.spec.typed
                                    .TypedLimit(rel0, lc.args().get(1),
                                    rel0.info());
                }
                return rawResolver.apply(chain);
            } catch (RuntimeException e) {
                return null;
            }
        };
        return inQueryReadsOver(roots, resolver);
    }

    /** The path scanner's shape: (node, userVar, out-path-set). */
    @FunctionalInterface
    interface PathScan {
        void scan(com.legend.compiler.spec.typed.TypedSpec n, String userVar,
                java.util.Set<java.util.List<String>> out);
    }

    /** AUTO-MAP with a MULTI-PATH body (map(chain, m|$m.first+' '+
     * $m.last) — an inlined derived leaf over a navigation chain):
     * pathOf flattens only single-path bodies, so compose the map
     * SOURCE's path with each body leaf path — the demand the
     * substitution's own walk consumes (one-funnel discipline, #78). */
    static void composeAutoMapPaths(com.legend.compiler.spec.typed.TypedSpec n,
            String userVar, java.util.Set<java.util.List<String>> out,
            PathScan scanner) {
        if (!(n instanceof com.legend.compiler.spec.typed.TypedMap am)
                || am.mapper().parameters().size() != 1) {
            return;
        }
        java.util.List<String> prefix =
                Substitution.pathOf(am.source(), userVar);
        if (prefix == null) {
            return;
        }
        java.util.Set<java.util.List<String>> bodyPaths =
                new java.util.LinkedHashSet<>();
        for (com.legend.compiler.spec.typed.TypedSpec mb : am.mapper().body()) {
            scanner.scan(mb, am.mapper().parameters().get(0), bodyPaths);
        }
        for (java.util.List<String> bp : bodyPaths) {
            java.util.List<String> full = new java.util.ArrayList<>(prefix);
            full.addAll(bp);
            out.add(full);
        }
    }

    /** tdsContains fn lambdas bind the OUTER object: hand each lambda
     * body + its own param to the caller's scanner (the shadow stop in
     * both path walkers would otherwise drop them — task #78). */
    static void scanTdsContainsFns(com.legend.compiler.spec.typed.TypedSpec n,
            String userVar,
            java.util.function.BiConsumer<com.legend.compiler.spec.typed
                    .TypedSpec, String> each) {
        if (!(n instanceof com.legend.compiler.spec.typed.TypedNativeCall tdc)
                || !com.legend.builtin.Pure.nativeNamed("tdsContains",
                        tdc.callee().signatureKey())
                || tdc.args().size() < 2
                || !(tdc.args().get(0) instanceof
                        com.legend.compiler.spec.typed.TypedVariable ov)
                || !ov.name().equals(userVar)) {
            return;
        }
        com.legend.compiler.spec.typed.TypedSpec fns = tdc.args().get(1);
        java.util.List<com.legend.compiler.spec.typed.TypedSpec> fl =
                fns instanceof com.legend.compiler.spec.typed
                        .TypedCollection tcl
                ? tcl.elements() : java.util.List.of(fns);
        for (com.legend.compiler.spec.typed.TypedSpec f : fl) {
            if (f instanceof com.legend.compiler.spec.typed.TypedLambda lam2
                    && lam2.parameters().size() == 1) {
                for (com.legend.compiler.spec.typed.TypedSpec b
                        : lam2.body()) {
                    each.accept(b, lam2.parameters().get(0));
                }
            }
        }
    }

    private static java.util.Map<com.legend.compiler.spec.typed.TypedSpec,
            Substitution.InQueryRead> inQueryReadsOver(
            java.util.List<com.legend.compiler.spec.typed.TypedSpec> roots,
            java.util.function.Function<com.legend.compiler.spec.typed.TypedSpec,
                    com.legend.compiler.spec.typed.TypedSpec> resolver) {
        java.util.Map<com.legend.compiler.spec.typed.TypedSpec,
                Substitution.InQueryRead> out = new java.util.IdentityHashMap<>();
        for (com.legend.compiler.spec.typed.TypedSpec r : roots) {
            collectInQuery(r, resolver, out);
        }
        return out;
    }

    private static void collectInQuery(
            com.legend.compiler.spec.typed.TypedSpec n,
            java.util.function.Function<com.legend.compiler.spec.typed.TypedSpec,
                    com.legend.compiler.spec.typed.TypedSpec> resolver,
            java.util.Map<com.legend.compiler.spec.typed.TypedSpec,
                    Substitution.InQueryRead> out) {
        if (n instanceof com.legend.compiler.spec.typed.TypedNativeCall tc
                && com.legend.builtin.Pure.nativeNamed("tdsContains",
                        tc.callee().signatureKey())
                && tc.args().size() >= 3) {
            // tdsContains: the TDS arg is a relation CHAIN (project over
            // a class extent) — resolve it like the in-subquery colls;
            // the substitution arm pairs functions to columns (task #78)
            com.legend.compiler.spec.typed.TypedSpec tdsArg =
                    tc.args().get(tc.args().size() == 3 ? 2 : 3);
            com.legend.compiler.spec.typed.TypedSpec rel =
                    resolver.apply(tdsArg);
            if (rel != null && rel.info().type() instanceof
                    com.legend.compiler.element.type.Type.RelationType) {
                out.put(tc, new Substitution.InQueryRead(rel, null));
            }
        }
        if (n instanceof com.legend.compiler.spec.typed.TypedNativeCall c
                && c.args().size() == 2) {
            String key = c.callee().signatureKey();
            boolean isIn = com.legend.builtin.Pure.nativeNamed("in", key);
            boolean isContains =
                    com.legend.builtin.Pure.nativeNamed("contains", key);
            if (isIn || isContains) {
                com.legend.compiler.spec.typed.TypedSpec coll =
                        isContains ? c.args().get(0) : c.args().get(1);
                if (rootsAtGetAll(coll)
                        && !(coll.info().type()
                                instanceof com.legend.compiler.element.type
                                        .Type.RelationType)) {
                    com.legend.compiler.spec.typed.TypedSpec rel =
                            resolver.apply(coll);
                    if (rel != null && rel.info().type()
                            instanceof com.legend.compiler.element.type
                                    .Type.RelationType rt
                            && rt.columns().size() == 1) {
                        out.put(c, new Substitution.InQueryRead(rel,
                                rt.columns().get(0).name()));
                    }
                }
            }
        }
        for (com.legend.compiler.spec.typed.TypedSpec ch : n.children()) {
            collectInQuery(ch, resolver, out);
        }
    }

    private static boolean rootsAtGetAll(
            com.legend.compiler.spec.typed.TypedSpec n) {
        com.legend.compiler.spec.typed.TypedSpec cur = n;
        while (cur != null) {
            if (cur instanceof com.legend.compiler.spec.typed.TypedGetAll) {
                return true;
            }
            java.util.List<com.legend.compiler.spec.typed.TypedSpec> ch =
                    cur.children();
            cur = ch.isEmpty() ? null : ch.get(0);
        }
        return false;
    }

    /** OCCURRENCE-SPLIT demand: a 3+-hop chain read in BOTH filter and
     * projection position — its snapshot sub-union step joins once per
     * occurrence class (engine per-call join identity, row-semantic). */
    static java.util.Set<String> occurrenceSplitChains(
            java.util.Set<java.util.List<String>> filterPaths,
            java.util.Set<java.util.List<String>> projectionPaths) {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        for (java.util.List<String> fp : filterPaths) {
            if (fp.size() < 3) {
                continue;
            }
            for (java.util.List<String> pp : projectionPaths) {
                if (pp.size() >= 3 && pp.get(0).equals(fp.get(0))
                        && pp.get(1).equals(fp.get(1))) {
                    out.add(fp.get(0) + "." + fp.get(1));
                }
            }
        }
        return out;
    }
}
