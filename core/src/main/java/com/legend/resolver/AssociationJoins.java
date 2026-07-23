// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.resolver;

import com.legend.compiler.element.ModelContext;
import com.legend.compiler.element.type.ExprType;
import com.legend.compiler.element.type.Type;
import com.legend.compiler.spec.SpecCompiler;
import com.legend.compiler.spec.typed.TypedGetAll;
import com.legend.compiler.spec.typed.TypedLambda;
import com.legend.compiler.spec.typed.TypedNativeCall;
import com.legend.compiler.spec.typed.TypedPropertyAccess;
import com.legend.compiler.spec.typed.TypedSpec;
import com.legend.error.MappingResolutionException;
import com.legend.error.NotImplementedException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
/**
 * ASSOCIATION-ROUTE join material: the demanded association end's
 * target pipeline (materialized, temporally stamped, synthetic-pred
 * wrapped), the ORIENTED (parent, target) condition from the mapping's
 * legacyAssocPredicate emission, and the deterministic column prefix.
 * A stateless service over the model + sources; the per-resolution
 * frames (TemporalFrame) pass per call.
 */
final class AssociationJoins {

    private final ModelContext ctx;
    private final ClassSources sources;
    private final SpecCompiler specs;
    private final SyntheticHeads synthetics;

    AssociationJoins(ModelContext ctx, ClassSources sources,
            SpecCompiler specs, SyntheticHeads synthetics) {
        this.ctx = ctx;
        this.sources = sources;
        this.specs = specs;
        this.synthetics = synthetics;
    }

    /** The join material for an aggregated to-many head: the association
     * route, or the navigate-slot route (class-typed Join PM). */
    AssocJoin aggJoinMaterial(TemporalFrame temporal, ClassSource cs, String head, StoreResolver.Context context,
                                      Set<String> leaves,
                                      Set<List<String>> tgtNavPaths) {
        // synthetic identities (#fN/#cN) bind by their REAL property — the
        // raw lookup missed the navigate-slot route and fell into the
        // association route, which errors when the property is PM-mapped
        TypedSpec binding = cs.bindings().get(SyntheticHeads.realHead(head));
        if (binding == null) {
            return associationJoin(temporal, cs, head, context, false, leaves,
                    head, tgtNavPaths);
        }
        var navSteps = Pipelines.navSteps(cs.pipeline());
        String alias = StoreResolver.navSlotAlias(binding, cs.rowVar(), navSteps.keySet());
        var nav = navSteps.get(alias);
        String targetClass = ((TypedGetAll)
                nav.target()).classFqn();
        ClassSource t = sources.get(cs.mappingFqn(), targetClass);
        Set<String> targetSlots = Pipelines.slotAliases(t.pipeline());
        Set<String> targetDemand = new LinkedHashSet<>();
        if (!targetSlots.isEmpty()) {
            for (String leaf : leaves) {
                TypedSpec b = t.bindings().get(leaf);
                if (b != null) {
                    CorrelatedSubselects.collectAliasReads(b, t.rowVar(), targetSlots, targetDemand);
                }
            }
        }
        targetDemand = Pipelines.closeOverConditions(t.pipeline(), targetDemand);
        // #69: the correlated pred's / computed mapper's TARGET-side reads
        // may hop the target's OWN class-typed nav steps ($e.address.name
        // over the aggregated rows) — demand those steps and build depth-1
        // SubNavs for the composition's pass-1 dispatch (mirrors the
        // exists route). Deeper hops stay loud (empty children).
        TypedLambda corrN = synthetics.correlatedPred(head);
        Set<List<String>> predPaths = new LinkedHashSet<>(tgtNavPaths);
        if (corrN != null) {
            for (TypedSpec b : corrN.body()) {
                StoreResolver.consumedPaths(b,
                        corrN.parameters().get(0), predPaths);
            }
        }
        // CLOSED parked preds contribute nav demand too — the applyToPipe
        // application below rewrites them against this target, and a pred
        // hopping a class-typed nav step ($e.address.name == 'New York',
        // the chained-unions agg family) needs its SubNav registered
        // exactly like a correlated pred's reads (task #78)
        for (TypedLambda sp : synthetics.allPreds(head)) {
            for (TypedSpec b : sp.body()) {
                StoreResolver.consumedPaths(b,
                        sp.parameters().get(0), predPaths);
            }
        }
        var tNavSteps = Pipelines.navSteps(t.pipeline());
        Map<String, String> predNavAliases = new java.util.LinkedHashMap<>();
        Set<String> tNavDemand = new LinkedHashSet<>();
        for (List<String> pp : predPaths) {
            if (pp.size() < 2) {
                continue;
            }
            TypedSpec hb = t.bindings().get(pp.get(0));
            String al = hb == null ? null
                    : StoreResolver.navSlotAlias(hb, t.rowVar(),
                            tNavSteps.keySet());
            if (al != null) {
                tNavDemand.add(al);
                predNavAliases.put(pp.get(0), al);
            }
        }
        Pipelines.Materialized tMat = tNavDemand.isEmpty()
                ? Pipelines.materialize(
                        t.pipeline(), targetDemand, t.classFqn())
                : Pipelines.materialize(
                        t.pipeline(), targetDemand, tNavDemand, t.classFqn(),
                        (al2, tc2) -> Pipelines.materialize(
                                sources.get(cs.mappingFqn(), tc2).pipeline(),
                                java.util.Set.of(), tc2).pipeline());
        Map<String, Substitution.SubNav> tSubNavs = new java.util.LinkedHashMap<>();
        for (var pne : predNavAliases.entrySet()) {
            String pfx = tMat.slotPrefixes().get(pne.getValue());
            var stepT = tNavSteps.get(pne.getValue()).target();
            if (pfx == null || !(stepT instanceof TypedGetAll stg)) {
                continue;
            }
            ClassSource sub = sources.get(cs.mappingFqn(), stg.classFqn());
            tSubNavs.put(pne.getKey(), new Substitution.SubNav(
                    pfx, sub.rowVar(), sub.bindings()));
        }
        TypedSpec tPipe0 = temporal.temporalTargetPipe(cs, t, head,
                temporal.applyJoinTemporalFilters(tMat.pipeline(), t, Map.of()));
        // a lifted head's parked material (filter / union branches)
        // applies to the aggregated target exactly like the plain routes.
        // A CORRELATED pred does NOT apply here — it composes into the
        // aggregated subselect's WHERE at the FOLD (parent-copy
        // architecture, #69); the fold re-checks the head and is loud if
        // it cannot compose (applyToPipe passes corr-only heads through
        // unchanged, never silently filtering).
        tPipe0 = synthetics.applyToPipe(head, tPipe0, (p, pred) ->
                CorrelatedSubselects.predFilteredPipe(p, t, tMat.slotPrefixes(),
                        tSubNavs, pred, cs.mappingFqn()));
        return new AssocJoin(prefixFor(head, cs), t, tPipe0,
                (Type.RelationType) tPipe0.info().type(),
                withOuterDatedWindow(temporal, cs, t, head, nav.predicate(),
                        tPipe0),
                tMat.slotPrefixes(), tSubNavs);
    }

    /** UNION-to-union chained hop: rewrite a raw equality condition into
     * the member-paired OR form — {@code (a_0=b_0 AND c_0=d_0) OR
     * (a_1=b_1 AND c_1=d_1)} — when BOTH rows expose the {@code _i}
     * NULL-crossed variants of every read column and the raw column is
     * absent (engine sqlQueryMerging/V-family goldens; off-member NULLs
     * make same-member pairing exact). Null when not applicable — the
     * caller keeps its wall. */
    /** Rewrite {@code $v.<alias>.<col>} two-hop reads in {@code cond} to
     * the parent pipe's PROJECTED names — recovered from the member
     * project colspecs whose bodies read {@code toOne($row.alias.col)}
     * (the chained-lift emission). Null when nothing rewrote. */
    static TypedLambda rewriteChainedLiftReads(TypedLambda cond,
            TypedSpec parentPipe) {
        Map<String, String> byAliasCol = new java.util.LinkedHashMap<>();
        collectLiftColspecNames(parentPipe, byAliasCol);
        if (byAliasCol.isEmpty()) {
            return null;
        }
        String v = cond.parameters().get(0);
        boolean[] hit = {false};
        List<TypedSpec> body = cond.body().stream()
                .map(b -> rewriteTwoHop(b, v, byAliasCol, hit)).toList();
        return hit[0] ? new TypedLambda(cond.parameters(), body, cond.info())
                : null;
    }

    private static void collectLiftColspecNames(TypedSpec n,
            Map<String, String> out) {
        if (n instanceof com.legend.compiler.spec.typed.TypedProject pr) {
            for (var col : pr.columns()) {
                TypedSpec b = Pipelines.unwrapToOne(col.fn().body()
                        .get(col.fn().body().size() - 1));
                if (b instanceof TypedPropertyAccess pa
                        && pa.source() instanceof TypedPropertyAccess inner
                        && inner.source() instanceof
                                com.legend.compiler.spec.typed.TypedVariable) {
                    out.put(inner.property() + "\u0000" + pa.property(),
                            col.name());
                    // the generic slot rewriter's alias_col flattening —
                    // depth-1 reads under that spelling rename too
                    out.put(inner.property() + "_" + pa.property(),
                            col.name());
                }
            }
        }
        for (TypedSpec c : n.children()) {
            collectLiftColspecNames(c, out);
        }
    }

    private static TypedSpec rewriteTwoHop(TypedSpec n, String var,
            Map<String, String> byAliasCol, boolean[] hit) {
        if (n instanceof TypedPropertyAccess pa
                && pa.source() instanceof TypedPropertyAccess inner
                && inner.source() instanceof
                        com.legend.compiler.spec.typed.TypedVariable tv
                && tv.name().equals(var)) {
            String flat = byAliasCol.get(
                    inner.property() + "\u0000" + pa.property());
            if (flat != null) {
                hit[0] = true;
                return new TypedPropertyAccess(tv, flat, pa.info());
            }
        }
        if (n instanceof TypedPropertyAccess pd
                && pd.source() instanceof
                        com.legend.compiler.spec.typed.TypedVariable tv2
                && tv2.name().equals(var)
                && byAliasCol.containsKey(pd.property())) {
            hit[0] = true;
            return new TypedPropertyAccess(tv2,
                    byAliasCol.get(pd.property()), pd.info());
        }
        if (n instanceof TypedLambda l && l.parameters().contains(var)) {
            return n;
        }
        if (n instanceof TypedNativeCall c) {
            List<TypedSpec> args = c.args().stream()
                    .map(a -> rewriteTwoHop(a, var, byAliasCol, hit)).toList();
            return new TypedNativeCall(c.callee(), args, c.info());
        }
        return n;
    }

    /** TARGET-SIDE join-key widening: a distinct-narrowed or UNION target
     * must expose the key columns the association condition binds on
     * (engine partial-union goldens); the union wall names its head. */
    private static TypedSpec widenForConditionKeys(TypedLambda oriented,
            TypedSpec pipeline, String head, ClassSource cs) {
        Set<String> tgtReads = new LinkedHashSet<>();
        for (TypedSpec b : oriented.body()) {
            Pipelines.collectVarReads(b, oriented.parameters().get(1), tgtReads);
        }
        TypedSpec tPipe = Pipelines.widenDistinctForKeys(pipeline, tgtReads);
        try {
            return Pipelines.widenConcatenateForKeys(tPipe, tgtReads);
        } catch (NotImplementedException e) {
            throw new NotImplementedException(e.getMessage()
                    + " [association head '" + head + "' on "
                    + cs.classFqn() + "]");
        }
    }

    /** The chained-hop union arm: member-paired condition, else the
     * parent's ROUTED LIFT when it carries the head as a nav slot
     * (collectPairAssociationEntries put each per-pair route inside its
     * member thread — V4), else the loud wall. */
    AssocJoin chainedUnionHop(TemporalFrame temporal, ClassSource parent,
            AssocJoin aj, String head, String chainKey,
            StoreResolver.Context context, Set<String> leaves,
            String parentKey, Map<String, AssocJoin> joinsByChain,
            List<AssocJoin> assocJoins) {
        AssocJoin out = chainedUnionHopInner(temporal, parent, aj, head,
                chainKey, context, leaves);
        // V4 mid-key reads: the hop condition's two-hop parent reads
        // ($s.Y1_G.fk1) rewrite to the PARENT union's PROJECTED chained-
        // lift names (fk1_1 — already in every member thread; the colspec
        // bodies ARE the mapping).
        AssocJoin paj = joinsByChain.get(parentKey);
        if (paj != null) {
            // RAW pipeline: colspec bodies still carry the two-hop reads
            // the (alias,col)->projName mapping is recovered from
            TypedLambda rw = rewriteChainedLiftReads(out.condition(),
                    paj.target().pipeline());
            if (rw != null) {
                out = out.withCondition(rw);
            }
        }
        return out;
    }

    private AssocJoin chainedUnionHopInner(TemporalFrame temporal,
            ClassSource parent, AssocJoin aj, String head, String chainKey,
            StoreResolver.Context context, Set<String> leaves) {
        TypedLambda paired = memberPairedCondition(aj.condition(),
                (Type.RelationType) parent.pipeline().info().type(),
                aj.targetRow());
        if (paired != null) {
            return aj.withCondition(paired);
        }
        TypedSpec pb = parent.bindings().get(SyntheticHeads.realHead(head));
        if (pb != null && StoreResolver.navSlotAlias(pb, parent.rowVar(),
                Pipelines.navSteps(parent.pipeline()).keySet()) != null) {
            return aggJoinMaterial(temporal, parent, head, context, leaves,
                    Set.of());
        }
        throw new NotImplementedException("chained association hop '"
                + chainKey + "' navigates INTO a union-mapped class —"
                + " per-member route dispatch is not built yet");
    }

    /** The chained-hop union arm: paired condition or the loud wall. */
    AssocJoin pairChainedUnionHop(AssocJoin aj, ClassSource parent,
            String chainKey) {
        TypedLambda paired = memberPairedCondition(aj.condition(),
                (Type.RelationType) parent.pipeline().info().type(),
                aj.targetRow());
        if (paired == null) {
            throw new NotImplementedException("chained association hop '"
                    + chainKey + "' navigates INTO a union-mapped class —"
                    + " per-member route dispatch is not built yet");
        }
        return aj.withCondition(paired);
    }

    TypedLambda memberPairedCondition(TypedLambda cond,
            Type.RelationType srcRow, Type.RelationType tgtRow) {
        List<TypedSpec[]> eqs = new ArrayList<>();
        if (!collectEqualities(cond.body().get(cond.body().size() - 1), eqs)) {
            return null;
        }
        String sv = cond.parameters().get(0);
        String tv = cond.parameters().get(1);
        int n = -1;
        for (TypedSpec[] eq : eqs) {
            for (TypedSpec side : eq) {
                if (!(side instanceof TypedPropertyAccess pa
                        && pa.source() instanceof
                                com.legend.compiler.spec.typed.TypedVariable v)) {
                    return null;
                }
                Type.RelationType row = v.name().equals(sv) ? srcRow
                        : v.name().equals(tv) ? tgtRow : null;
                if (row == null) {
                    return null;
                }
                int k = suffixCount(row, pa.property());
                if (k < 2 || (n >= 0 && k != n)) {
                    return null;
                }
                n = k;
            }
        }
        var boolT = new ExprType(Type.Primitive.BOOLEAN,
                com.legend.compiler.element.type.Multiplicity.Bounded.ONE);
        TypedSpec or = null;
        for (int i = 0; i < n; i++) {
            TypedSpec grp = null;
            for (TypedSpec[] eq : eqs) {
                TypedSpec e = new TypedNativeCall(
                        ((TypedNativeCall) eq[2]).callee(),
                        List.of(suffixRead(eq[0], i, srcRow, tgtRow, sv),
                                suffixRead(eq[1], i, srcRow, tgtRow, sv)),
                        boolT);
                grp = grp == null ? e : boolCall("and", e, grp, boolT);
            }
            or = or == null ? grp : boolCall("or", grp, or, boolT);
        }
        return new TypedLambda(cond.parameters(), List.of(or), cond.info());
    }

    private TypedSpec boolCall(String op, TypedSpec a, TypedSpec b,
            ExprType boolT) {
        var fns = ctx.findFunction("meta::pure::functions::boolean::" + op)
                .stream().filter(f -> f.parameters().size() == 2).toList();
        if (fns.size() != 1) {
            throw new IllegalStateException(
                    "resolver bug: expected one 2-arg boolean::" + op);
        }
        return new TypedNativeCall(fns.get(0), List.of(a, b), boolT);
    }

    private static int suffixCount(Type.RelationType row, String col) {
        boolean raw = row.columns().stream()
                .anyMatch(c -> c.name().equals(col));
        if (raw) {
            return -1;
        }
        int k = 0;
        while (true) {
            final int i = k;
            if (row.columns().stream()
                    .noneMatch(c -> c.name().equals(col + "_" + i))) {
                return k;
            }
            k++;
        }
    }

    private static TypedSpec suffixRead(TypedSpec side, int i,
            Type.RelationType srcRow, Type.RelationType tgtRow, String sv) {
        TypedPropertyAccess pa = (TypedPropertyAccess) side;
        var v = (com.legend.compiler.spec.typed.TypedVariable) pa.source();
        Type.RelationType row = v.name().equals(sv) ? srcRow : tgtRow;
        String name = pa.property() + "_" + i;
        Type.Column c = row.columns().stream()
                .filter(x -> x.name().equals(name)).findFirst().orElseThrow();
        return new TypedPropertyAccess(v, name,
                new ExprType(c.type(), c.multiplicity()));
    }

    /** Extract {@code equal(a,b)} leaves from an AND-chain; each entry is
     * [left, right, theEqualNode]. False when any non-and/equal appears. */
    private static boolean collectEqualities(TypedSpec n,
            List<TypedSpec[]> out) {
        if (n instanceof TypedNativeCall c && c.args().size() == 2) {
            String q = c.callee().qualifiedName();
            if (q.endsWith("::equal")) {
                out.add(new TypedSpec[]{
                        Pipelines.unwrapToOne(c.args().get(0)),
                        Pipelines.unwrapToOne(c.args().get(1)), c});
                return true;
            }
            if (q.endsWith("::and")) {
                return collectEqualities(c.args().get(0), out)
                        && collectEqualities(c.args().get(1), out);
            }
        }
        return false;
    }

    /** OUTER-ROW date ($o.product($o.orderDate)): the temporal window
     * composes into the NAV/ASSOC CONDITION — both rows in scope (engine:
     * window in the join ON against "root".orderDate; temporalTargetPipe
     * left the pipe unstamped for such specs). Identity otherwise. */
    static TypedLambda withOuterDatedWindow(TemporalFrame temporal,
            ClassSource cs, ClassSource target, String head,
            TypedLambda cond, TypedSpec targetPipe) {
        String odc = temporal.outerDateColumn(head, cs);
        if (odc != null) {
            return temporal.outerDatedJoinCond(cond, cs.pipeline(), targetPipe,
                    target.classFqn(), odc);
        }
        TypedLambda bi = temporal.outerBiDatedJoinCond(cond, cs.pipeline(),
                targetPipe, cs, target, head);
        return bi != null ? bi : cond;
    }

    /** A demanded association navigation, ready to emit as a prefixed LEFT
     * join. {@code targetSubNavs}: the target's OWN demanded class-typed
     * nav steps (#69 — the correlated pred / computed mapper read through
     * them inside the aggregated subselect). */
    record AssocJoin(String prefix, ClassSource target,
                             TypedSpec targetPipeline,
                             Type.RelationType targetRow,
                             TypedLambda condition,
                             Map<String, String> targetSlotPrefixes,
                             Map<String, Substitution.SubNav> targetSubNavs,
                             TypedLambda corrSubPred) {

        AssocJoin(String prefix, ClassSource target, TypedSpec targetPipeline,
                  Type.RelationType targetRow, TypedLambda condition,
                  Map<String, String> targetSlotPrefixes) {
            this(prefix, target, targetPipeline, targetRow, condition,
                    targetSlotPrefixes, Map.of(), null);
        }

        AssocJoin(String prefix, ClassSource target, TypedSpec targetPipeline,
                  Type.RelationType targetRow, TypedLambda condition,
                  Map<String, String> targetSlotPrefixes,
                  Map<String, Substitution.SubNav> targetSubNavs) {
            this(prefix, target, targetPipeline, targetRow, condition,
                    targetSlotPrefixes, targetSubNavs, null);
        }

        AssocJoin withCondition(TypedLambda cond) {
            return new AssocJoin(prefix, target, targetPipeline, targetRow,
                    cond, targetSlotPrefixes, targetSubNavs, corrSubPred);
        }

        AssocJoin withTargetPipeline(TypedSpec pipe) {
            return new AssocJoin(prefix, target, pipe,
                    (Type.RelationType) pipe.info().type(), condition,
                    targetSlotPrefixes, targetSubNavs, corrSubPred);
        }
    }

    /**
     * A chained hop's prefix, ordinal-bumped against the ACCUMULATED column
     * set: the source row plus every already-registered join's prefixed
     * columns — the same guard {@link #prefixFor} gives hop 0.
     */
    static String chainedPrefix(String base, ClassSource cs,
                                        Map<String, AssocJoin> joinsByChain) {
        Set<String> taken = new LinkedHashSet<>();
        for (Type.Column c : cs.rowType().columns()) {
            taken.add(c.name());
        }
        for (AssocJoin aj : joinsByChain.values()) {
            for (Type.Column c : aj.targetRow().columns()) {
                taken.add(aj.prefix() + c.name());
            }
        }
        String prefix = base + "_";
        int ordinal = 2;
        while (hasPrefixCollision(prefix, taken)) {
            prefix = base + "_" + ordinal++ + "_";
        }
        return prefix;
    }

    /** Deterministic prefix with ordinal bump on collision against the parent row (plan §2.3). */
    static String prefixFor(String head, ClassSource cs) {
        Set<String> taken = new LinkedHashSet<>();
        for (Type.Column c : cs.rowType().columns()) {
            taken.add(c.name());
        }
        String prefix = head + "_";
        int ordinal = 2;
        while (hasPrefixCollision(prefix, taken)) {
            prefix = head + "_" + ordinal++ + "_";
        }
        return prefix;
    }

    private static boolean hasPrefixCollision(String prefix, Set<String> taken) {
        for (String t : taken) {
            if (t.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /** The mapping's AssociationBinding for {@code assocFqn}, searching the
     * include closure transitively (own definitions win, first match by
     * declaration order — the class-binding resolution's exact rule). */
    private java.util.Optional<com.legend.model.MappingDefinition.AssociationBinding>
            associationBindingInClosure(String mappingFqn, String assocFqn) {
        java.util.ArrayDeque<String> queue = new java.util.ArrayDeque<>();
        Set<String> seen = new LinkedHashSet<>();
        queue.add(mappingFqn);
        while (!queue.isEmpty()) {
            String fqn = queue.poll();
            if (!seen.add(fqn)) {
                continue;
            }
            var m = ctx.findMapping(fqn);
            if (m.isEmpty()) {
                continue;
            }
            var hit = m.get().associationBindings().stream()
                    .filter(ab -> ab.associationFqn().equals(assocFqn))
                    .findFirst();
            if (hit.isPresent()) {
                return hit;
            }
            for (var inc : m.get().includes()) {
                queue.add(inc.mappingPath());
            }
        }
        return java.util.Optional.empty();
    }

    /** The class an ASSOCIATION end named {@code prop} on {@code classFqn}
     * navigates to, if an association realizes it (the assoc-sub probe —
     * union V3). */
    java.util.Optional<String> assocTargetClassOf(String classFqn, String prop) {
        return ctx.findAssociationOf(classFqn, prop).map(a ->
                (a.property1().propertyName().equals(prop)
                        ? a.property1() : a.property2()).targetClassFqn());
    }

    AssocJoin associationJoin(TemporalFrame temporal, ClassSource cs, String head, StoreResolver.Context context,
                                      boolean forExists) {
        return associationJoin(temporal, cs, head, context, forExists, Set.of());
    }

    AssocJoin associationJoin(TemporalFrame temporal, ClassSource cs, String head, StoreResolver.Context context,
                                      boolean forExists, Set<String> demandedLeaves) {
        return associationJoin(temporal, cs, head, context, forExists, demandedLeaves, head);
    }

    /** {@code chainKey}: the dotted path prefix this hop sits at — the
     * temporal-spec registry key (= {@code head} for hop 0). */
    AssocJoin associationJoin(TemporalFrame temporal, ClassSource cs, String head, StoreResolver.Context context,
                                      boolean forExists, Set<String> demandedLeaves,
                                      String chainKey) {
        return associationJoin(temporal, cs, head, context, forExists,
                demandedLeaves, chainKey, Set.of());
    }

    /** {@code navTails}: TARGET-side nav paths past this head
     * (placeOfInterest.name on Location for
     * $e.locations.placeOfInterest.name) — each tail's first segment
     * demands the target's navigate slot and exposes a SubNav so the
     * multi-hop walk resolves through it (task #70/#78). */
    AssocJoin associationJoin(TemporalFrame temporal, ClassSource cs, String head, StoreResolver.Context context,
                                      boolean forExists, Set<String> demandedLeaves,
                                      String chainKey, Set<List<String>> navTails) {
        // A SYNTHETIC head resolves by its underlying property; its parked
        // predicate joins the leaf demand (the pred's own reads pull the
        // target's slots) and wraps the finished target pipeline below.
        String real = SyntheticHeads.realHead(head);
        List<TypedLambda> synthPreds = synthetics.allPreds(head);
        if (!synthPreds.isEmpty()) {
            Set<String> withPredLeaves = new LinkedHashSet<>(demandedLeaves);
            for (TypedLambda sp : synthPreds) {
                for (TypedSpec b : sp.body()) {
                    StoreResolver.collectParamPathHeads(b, sp.parameters().get(0),
                            withPredLeaves);
                }
            }
            demandedLeaves = withPredLeaves;
        }
        var assoc = ctx.findAssociationOf(cs.classFqn(), real).orElseThrow(() ->
                new MappingResolutionException("property '" + real + "' of class '"
                        + cs.classFqn() + "' is not mapped in mapping '"
                        + cs.mappingFqn() + "'", cs.classFqn()));
        // The end from the SAME association object — a separate index lookup
        // was a split-brain with findAssociationOf (audit blocker).
        var end = assoc.property1().propertyName().equals(real)
                ? assoc.property1() : assoc.property2();
        // A CONCRETE end joins: to-one flat, to-many with ROW EXPLOSION
        // (projection semantics — engine/V1/plangen unanimous). A
        // Parameter-multiplicity end stays denied (unknown cardinality).
        if (!forExists && !end.isConcrete()) {
            throw new NotImplementedException("navigation of association end '$"
                    + head + "' with non-concrete multiplicity "
                    + end.multiplicity() + " is not supported");
        }
        String targetClass = end.targetClassFqn();
        ClassSource target = sources.get(cs.mappingFqn(), targetClass);
        // The TARGET's own join slots materialize on demand too: a demanded
        // leaf whose binding reads a slot ($p.firm.country where country is
        // @FirmCountry-mapped) pulls that slot's LEFT join into the target
        // pipeline — nested navigation joins, the W4 slice.
        Set<String> targetSlots = Pipelines.slotAliases(target.pipeline());
        Set<String> targetDemand = new LinkedHashSet<>();
        if (!targetSlots.isEmpty()) {
            for (String leaf : demandedLeaves) {
                TypedSpec b = target.bindings().get(leaf);
                if (b != null) {
                    CorrelatedSubselects.collectAliasReads(b, target.rowVar(), targetSlots, targetDemand);
                }
            }
        }
        targetDemand = Pipelines.closeOverConditions(target.pipeline(), targetDemand);
        var tNavSteps3 = Pipelines.navSteps(target.pipeline());
        Set<String> tNavDemand3 = new LinkedHashSet<>();
        Map<String, String> tailNavAliases = new java.util.LinkedHashMap<>();
        for (List<String> tail : navTails) {
            String seg0 = tail.get(0);
            TypedSpec b3 = target.bindings().get(SyntheticHeads.realHead(seg0));
            String al3 = b3 == null ? null
                    : StoreResolver.navSlotAlias(b3, target.rowVar(),
                            tNavSteps3.keySet());
            if (al3 != null) {
                tNavDemand3.add(al3);
                tailNavAliases.put(seg0, al3);
            }
        }
        // EARLY predicate scan (before materialization): the association
        // CONDITION's target-side slot reads demand the target's navigate
        // steps — a ModelJoin/XStore condition navigating another
        // association ($employees.address.city) reads the nested nav
        // slot, and the stock slot machinery materializes it (user rule:
        // both are just navigate()). Target-side param ONLY — demanding
        // an unread to-many step would explode target rows.
        Map<String, Set<String>> nestedAssocReads =
                new java.util.LinkedHashMap<>();
        String condTgtVar = scanCondTargetReads(cs, assoc, real, targetClass,
                target, targetSlots, targetDemand, tNavSteps3.keySet(),
                tNavDemand3, nestedAssocReads);
        targetDemand = Pipelines.closeOverConditions(
                target.pipeline(), targetDemand);
        Pipelines.Materialized tMat0 = tNavDemand3.isEmpty()
                ? Pipelines.materialize(
                        target.pipeline(), targetDemand, target.classFqn())
                : Pipelines.materialize(
                        target.pipeline(), targetDemand, tNavDemand3,
                        target.classFqn(),
                        (aln, tcn) -> Pipelines.materialize(
                                sources.get(cs.mappingFqn(), tcn).pipeline(),
                                java.util.Set.of(), tcn).pipeline());
        TypedSpec basePipe = temporal.temporalTargetPipe(cs, target, chainKey,
                temporal.applyJoinTemporalFilters(tMat0.pipeline(), target,
                        Map.of()));
        // NESTED-ASSOCIATION widening (the navigate() rule): a condition
        // reading $tgt.<assocProp>.<col> joins the nested association's
        // target into the materialized pipe, prefixed — the recursive
        // navigate, exactly the chain-walk precedent (task #78)
        Map<String, String> nestedPrefixByProp =
                new java.util.LinkedHashMap<>();
        for (var ne : nestedAssocReads.entrySet()) {
            AssocJoin aj2 = aggJoinMaterial(temporal, target, ne.getKey(),
                    context, ne.getValue(), java.util.Set.of());
            String pfx = ne.getKey() + "_";
            Type.RelationType curRow = (Type.RelationType)
                    basePipe.info().type();
            java.util.List<Type.Column> wcols =
                    new java.util.ArrayList<>(curRow.columns());
            for (Type.Column c : aj2.targetRow().columns()) {
                wcols.add(new Type.Column(pfx + c.name(),
                        c.type(), c.multiplicity()));
            }
            basePipe = new com.legend.compiler.spec.typed.TypedJoin(
                    basePipe, aj2.targetPipeline(),
                    StoreResolver.leftKind(), aj2.condition(),
                    java.util.Optional.of(pfx),
                    new ExprType(new Type.RelationType(wcols),
                            com.legend.compiler.element.type.Multiplicity
                                    .Bounded.ONE));
            nestedPrefixByProp.put(ne.getKey(), pfx);
        }
        final Pipelines.Materialized tMat = new Pipelines.Materialized(
                basePipe, tMat0.slotPrefixes(), tMat0.stripped());

        // The predicate function: the AssociationBinding for the assoc,
        // searched across the INCLUDE CLOSURE (own mapping wins; audit V3:
        // the qualified YZ entries live in an included assoc mapping)
        var binding = associationBindingInClosure(cs.mappingFqn(),
                assoc.qualifiedName())
                .orElseThrow(() -> new MappingResolutionException("association '"
                        + assoc.qualifiedName() + "' is not mapped in mapping '"
                        + cs.mappingFqn() + "'"
                        // a dropped/poisoned property route often lands here
                        // (the assoc fallback) — surface the recorded reason
                        // (class-keyed, or the per-ASSOCIATION poison)
                        + ctx.mappingPoison(cs.mappingFqn(), cs.classFqn())
                                .or(() -> ctx.mappingPoison(cs.mappingFqn(),
                                        assoc.qualifiedName()))
                                .map(r -> " (" + r + ")").orElse(""),
                        assoc.qualifiedName()));
        var fns = ctx.findFunction(binding.predicateFunctionFqn());
        if (fns.size() != 1) {
            throw new IllegalStateException("resolver bug: association predicate '"
                    + binding.predicateFunctionFqn() + "' has " + fns.size() + " overloads");
        }
        var cf = specs.compile(fns.get(0));
        TypedSpec last = cf.body().get(cf.body().size() - 1);
        if (!(last instanceof TypedNativeCall call)
                || !call.callee().qualifiedName().equals(com.legend.builtin.Pure.LEGACY_ASSOC_PREDICATE_FQN)
                || call.args().size() != 5
                || !(call.args().get(4) instanceof TypedLambda cond)) {
            throw new IllegalStateException("resolver bug: association predicate body"
                    + " for '" + assoc.qualifiedName() + "' is not the"
                    + " legacyAssocPredicate(a,b,src,tgt,cond) emission: "
                    + last.getClass().getSimpleName());
        }
        // ORIENTATION: the predicate fn's params are (a: classA, b: classB)
        // and the cond's (srcRow, tgtRow) are their tables' rows in that
        // order (H1's emission). The TypedJoin condition binds
        // (leftRow=PARENT, rightRow=TARGET): if the parent is classB the
        // params reverse. Self-associations (parent == target) cannot
        // orient by class — the emission convention puts {target} (the
        // navigated destination when traversing property1) on tgtRow, so
        // property1 keeps the order and property2 reverses (pinned by the
        // executing self-association fixture).
        String classAFqn = ((Type.ClassType)
                fns.get(0).parameters().get(0).type()).fqn();
        // SUBTYPE-aware orientation (extends family): the navigating
        // parent may be a SUBCLASS of the association's declared end
        // class (B extends A navigating AE's 'e' end)
        boolean parentIsA = ctx.isSubtype(cs.classFqn(), classAFqn);
        boolean targetIsA = ctx.isSubtype(targetClass, classAFqn);
        if (!parentIsA && !targetIsA) {
            throw new IllegalStateException("resolver bug: association predicate '"
                    + binding.predicateFunctionFqn() + "' first param class '"
                    + classAFqn + "' is neither parent '" + cs.classFqn()
                    + "' nor target '" + targetClass + "'");
        }
        boolean reverse = cs.classFqn().equals(targetClass)
                ? !assoc.property1().propertyName().equals(real)
                : !parentIsA;
        TypedLambda oriented = rewriteNestedAssocCondReads(cond, condTgtVar,
                nestedPrefixByProp,
                (Type.RelationType) tMat.pipeline().info().type());
        if (reverse) {
            var ft = (Type.FunctionType)
                    cond.info().type();
            var swapped = new Type.FunctionType(
                    List.of(ft.params().get(1), ft.params().get(0)),
                    ft.result());
            oriented = new TypedLambda(List.of(cond.parameters().get(1),
                    cond.parameters().get(0)), cond.body(),
                    new ExprType(swapped,
                            com.legend.compiler.element.type.Multiplicity.Bounded.ONE));
        }
        // A CORRELATED lifted predicate ANDs into the CONDITION — the one
        // place both rows are in scope: its own param substitutes against
        // the TARGET bindings over the condition's target row; the residual
        // OUTER-variable reads substitute against the PARENT's bindings
        // over the condition's source row (audit 14 B-F1's correlation
        // pass). Runs BEFORE key collection so the pred's target reads
        // widen distinct/union keys too.
        TypedLambda corr = synthetics.correlatedPred(head);
        TypedLambda corrSub = null;
        if (corr != null) {
            // ROUTE RULE (engine parity, testFunctionVariables goldens):
            // a pred whose OUTER reads are plain parent properties
            // composes into the flat join ON (both rows in scope); a pred
            // demanding a parent NAV hop ($f.address.name) can never
            // resolve there — it rides the parent-copy subselect at the
            // fold (#69, the exploding variant of the aggregated shape).
            if (corrPredDemandsParentNav(corr)) {
                corrSub = corr;
            } else {
                oriented = andCorrelatedIntoCondition(oriented, corr, cs,
                        target, tMat.slotPrefixes());
            }
        }
        TypedSpec tPipe = widenForConditionKeys(oriented, tMat.pipeline(),
                head, cs);
        // audit 10: the target pipeline's OWN materialized slot joins to
        // milestoned tables filter by the temporal context too (every
        // milestoned table alias filters — the dead wall this replaces)
        tPipe = temporal.applyJoinTemporalFilters(tPipe, target, Map.of());
        tPipe = synthetics.applyToPipe(head, tPipe, (p, pred) ->
                CorrelatedSubselects.predFilteredPipe(p, target, tMat.slotPrefixes(),
                        pred, cs.mappingFqn()));
        Map<String, Substitution.SubNav> tailSubNavs =
                new java.util.LinkedHashMap<>();
        for (var tne : tailNavAliases.entrySet()) {
            String pfx3 = tMat.slotPrefixes().get(tne.getValue());
            var stepT3 = tNavSteps3.get(tne.getValue()).target();
            if (pfx3 == null || !(stepT3 instanceof TypedGetAll stg3)) {
                continue;
            }
            ClassSource sub3 = sources.get(cs.mappingFqn(), stg3.classFqn());
            tailSubNavs.put(tne.getKey(), new Substitution.SubNav(
                    pfx3, sub3.rowVar(), sub3.bindings()));
        }
        return new AssocJoin(prefixFor(head, cs), target, tPipe,
                (Type.RelationType)
                        tPipe.info().type(),
                withOuterDatedWindow(temporal, cs, target, chainKey, oriented, tPipe),
                tMat.slotPrefixes(), tailSubNavs, corrSub);
    }

    /** The correlation pass: two sequential substitutions over the lifted
     * predicate — own param via TARGET bindings onto the condition's
     * target row; each residual FREE variable via the PARENT's bindings
     * onto the condition's source row — then AND into the condition body. */
    /** True when a correlated pred's OUTER reads hop a parent NAVIGATION
     * ($f.address.name — depth >= 2): those can never resolve on the flat
     * join ON's source row; the pred rides the parent-copy subselect. */
    boolean corrPredDemandsParentNav(TypedLambda pred) {
        Set<String> free = new LinkedHashSet<>();
        for (TypedSpec b : pred.body()) {
            collectFreeVars(b, new LinkedHashSet<>(pred.parameters()), free);
        }
        for (String outer : free) {
            Set<List<String>> paths = new LinkedHashSet<>();
            for (TypedSpec b : pred.body()) {
                StoreResolver.consumedPaths(b, outer, paths);
            }
            for (List<String> p : paths) {
                if (p.size() >= 2) {
                    return true;
                }
            }
        }
        return false;
    }

    TypedLambda andCorrelatedIntoCondition(TypedLambda cond,
            TypedLambda pred, ClassSource parent, ClassSource target,
            Map<String, String> targetSlotPrefixes) {
        return andCorrelatedIntoCondition(cond, pred, parent, target,
                targetSlotPrefixes, Map.of(), Map.of());
    }

    TypedLambda andCorrelatedIntoCondition(TypedLambda cond,
            TypedLambda pred, ClassSource parent, ClassSource target,
            Map<String, String> targetSlotPrefixes,
            Map<String, Substitution.AssocSub> parentAssocs,
            Map<String, Substitution.SubNav> targetSubNavs) {
        // F2 (audit 21b): the condition's binders are emission-literal
        // names ({s,t} on the navigate route, {srcRow,tgtRow} on the
        // association route). A user variable sharing a name would be
        // CAPTURED — deleted from the free set and lowered as a
        // condition-row column read ("Table t1 has no column named
        // date"). Alpha-freshen both binders collision-driven (audit
        // 18's tRenamed discipline), and take the free set from the
        // ORIGINAL pred: after pass 1, introduced target-param reads
        // and same-named user vars are indistinguishable.
        Set<String> taken = new LinkedHashSet<>(pred.parameters());
        for (TypedSpec b : pred.body()) {
            collectVarNames(b, taken);
        }
        for (TypedSpec b : cond.body()) {
            collectVarNames(b, taken);
        }
        cond = freshenBinders(cond, taken);
        String srcParam = cond.parameters().get(0);
        String tgtParam = cond.parameters().get(1);
        Set<String> free = new LinkedHashSet<>();
        for (TypedSpec b : pred.body()) {
            collectFreeVars(b, new LinkedHashSet<>(pred.parameters()), free);
        }
        Set<String> unconvertedTgt = new LinkedHashSet<>(
                Pipelines.slotAliases(target.pipeline()));
        unconvertedTgt.removeAll(targetSlotPrefixes.keySet());
        var ft = (Type.FunctionType) cond.info().type();
        // the assoc-route cond declares concrete relation params; the
        // navigate-step emission is GENERIC (TypeVars) — the actual
        // pipelines carry the row shapes either way
        Type.RelationType srcRow = rowOr(ft.params().get(0).type(),
                parent.rowType());
        Type.RelationType tgtRow = rowOr(ft.params().get(1).type(),
                target.rowType());
        // pass 1: the pred's own param -> target bindings over tgtParam.
        // #69: the pred's TARGET-side reads may navigate the target's OWN
        // class-typed heads ($e.address.name over the navigated rows) —
        // the target's materialized SubNav tree dispatches them, with the
        // read landing prefixed on the condition's target row.
        Map<String, Substitution.AssocSub> tgtAssocs = new java.util.LinkedHashMap<>();
        for (var sne : targetSubNavs.entrySet()) {
            var sn = sne.getValue();
            tgtAssocs.put(sne.getKey(), new Substitution.AssocSub(
                    sn.prefix(), sn.rowVar(), sn.bindings(),
                    target.classFqn() + "." + sne.getKey(),
                    java.util.Set.of(), Map.of(), tgtParam, tgtRow,
                    Map.of(), sn.children()));
        }
        Substitution tgtSub = new Substitution(new Substitution.Target(
                new Substitution.RowScope(pred.parameters().get(0), tgtParam,
                        target.classFqn(), target.mappingFqn(),
                        target.rowVar(), target.bindings(), tgtRow,
                        unconvertedTgt, targetSlotPrefixes, Map.of()),
                new Substitution.Registries(tgtAssocs, java.util.Set.of(),
                        Map.of(), Map.of(), null, null),
                Substitution.TemporalView.NONE,
                true, true));
        TypedLambda pass1 = tgtSub.rewriteLambda(pred);
        // pass 2: each residual free variable (collected pre-pass-1,
        // shadow-aware) -> parent bindings over srcParam
        TypedSpec body = pass1.body().get(pass1.body().size() - 1);
        for (String outer : free) {
            // #69: OUTER reads may navigate the PARENT's class-typed
            // heads — the parent's registered AssocSubs dispatch them
            // (Registries.NONE walled every such read).
            Substitution srcSub = new Substitution(new Substitution.Target(
                    new Substitution.RowScope(outer, srcParam,
                            parent.classFqn(), parent.mappingFqn(),
                            parent.rowVar(), parent.bindings(),
                            srcRow,
                            new LinkedHashSet<>(
                                    Pipelines.slotAliases(parent.pipeline())),
                            Map.of(), Map.of()),
                    new Substitution.Registries(parentAssocs, java.util.Set.of(),
                            Map.of(), Map.of(), null, null),
                    Substitution.TemporalView.NONE, true, true));
            body = srcSub.rewriteLambda(new com.legend.compiler.spec.typed
                    .TypedLambda(List.of(outer), List.of(body), pred.info()))
                    .body().get(0);
        }
        var andFns = ctx.findFunction("meta::pure::functions::boolean::and")
                .stream().filter(f -> f.parameters().size() == 2).toList();
        if (andFns.size() != 1) {
            throw new IllegalStateException("resolver bug: expected one 2-arg"
                    + " boolean::and, found " + andFns.size());
        }
        TypedSpec existing = cond.body().get(cond.body().size() - 1);
        TypedSpec anded = new com.legend.compiler.spec.typed.TypedNativeCall(
                andFns.get(0), List.of(existing, body), existing.info());
        return new TypedLambda(cond.parameters(), List.of(anded), cond.info());
    }

    /**
     * The correlated predicate rewritten onto the SINGLE joined row of the
     * aggregated subselect (the engine's parent-copy architecture, #69):
     * the pred's own param reads TARGET bindings prefixed with
     * {@code targetPrefix}; each free OUTER variable reads the
     * PARENT-COPY's bindings (parent columns unprefixed on the joined
     * row, the copy's own demanded nav columns via its slot prefixes and
     * depth-1 SubNavs). Same alpha/capture discipline as the ON-clause
     * composition (audit 22a).
     */
    TypedLambda corrPredOnJoinedRow(TypedLambda pred, ClassSource parent,
            ClassSource target, String targetPrefix,
            Map<String, String> targetSlotPrefixes,
            Map<String, Substitution.SubNav> targetSubNavs,
            Map<String, String> parentCopySlotPrefixes,
            Map<String, Substitution.SubNav> parentCopySubNavs,
            String rowVar, Type.RelationType rowType) {
        Set<String> taken = new LinkedHashSet<>(pred.parameters());
        for (TypedSpec b : pred.body()) {
            collectVarNames(b, taken);
        }
        taken.add(rowVar);
        String ct = freshName("_ct", taken);
        String csv = freshName("_cs", taken);
        Set<String> free = new LinkedHashSet<>();
        for (TypedSpec b : pred.body()) {
            collectFreeVars(b, new LinkedHashSet<>(pred.parameters()), free);
        }
        var rowInfo = new ExprType(rowType,
                com.legend.compiler.element.type.Multiplicity.Bounded.ONE);
        Set<String> unconvertedTgt = new LinkedHashSet<>(
                Pipelines.slotAliases(target.pipeline()));
        unconvertedTgt.removeAll(targetSlotPrefixes.keySet());
        Map<String, Substitution.AssocSub> tgtAssocs = new java.util.LinkedHashMap<>();
        for (var e : targetSubNavs.entrySet()) {
            var sn = e.getValue();
            tgtAssocs.put(e.getKey(), new Substitution.AssocSub(
                    sn.prefix(), sn.rowVar(), sn.bindings(),
                    target.classFqn() + "." + e.getKey(),
                    java.util.Set.of(), Map.of(), ct, rowType,
                    Map.of(), sn.children()));
        }
        Substitution tgtSub = new Substitution(new Substitution.Target(
                new Substitution.RowScope(pred.parameters().get(0), ct,
                        target.classFqn(), target.mappingFqn(),
                        target.rowVar(), target.bindings(), rowType,
                        unconvertedTgt, targetSlotPrefixes, Map.of()),
                new Substitution.Registries(tgtAssocs, java.util.Set.of(),
                        Map.of(), Map.of(), null, null),
                Substitution.TemporalView.NONE,
                true, true));
        TypedLambda pass1 = tgtSub.rewriteLambda(pred);
        TypedSpec body = pass1.body().get(pass1.body().size() - 1);
        Map<String, Substitution.AssocSub> copyAssocs = new java.util.LinkedHashMap<>();
        for (var e : parentCopySubNavs.entrySet()) {
            var sn = e.getValue();
            copyAssocs.put(e.getKey(), new Substitution.AssocSub(
                    sn.prefix(), sn.rowVar(), sn.bindings(),
                    parent.classFqn() + "." + e.getKey(),
                    java.util.Set.of(), Map.of(), csv, rowType,
                    Map.of(), sn.children()));
        }
        Set<String> unconvertedPar = new LinkedHashSet<>(
                Pipelines.slotAliases(parent.pipeline()));
        unconvertedPar.removeAll(parentCopySlotPrefixes.keySet());
        for (String outer : free) {
            Substitution srcSub = new Substitution(new Substitution.Target(
                    new Substitution.RowScope(outer, csv,
                            parent.classFqn(), parent.mappingFqn(),
                            parent.rowVar(), parent.bindings(), rowType,
                            unconvertedPar, parentCopySlotPrefixes, Map.of()),
                    new Substitution.Registries(copyAssocs, java.util.Set.of(),
                            Map.of(), Map.of(), null, null),
                    Substitution.TemporalView.NONE, true, true));
            body = srcSub.rewriteLambda(new com.legend.compiler.spec.typed
                    .TypedLambda(List.of(outer), List.of(body), pred.info()))
                    .body().get(0);
        }
        // land both sides on the ONE joined row: target reads prefix,
        // parent-copy reads rename (parent columns ride unprefixed)
        body = Pipelines.prefixColumns(body, ct, targetPrefix,
                v -> new com.legend.compiler.spec.typed.TypedVariable(
                        rowVar, rowInfo));
        body = Pipelines.rewriteRowReads(body, csv, Map.of(), java.util.Set.of(),
                v -> new com.legend.compiler.spec.typed.TypedVariable(
                        rowVar, rowInfo));
        // the result kind is the INPUT lambda's (a Boolean pred, or a
        // computed mapper's scalar — this rewriter serves both)
        Type.Param res = pred.info().type() instanceof Type.FunctionType pft
                ? pft.result()
                : new Type.Param(Type.Primitive.BOOLEAN,
                        com.legend.compiler.element.type.Multiplicity
                                .Bounded.ONE);
        return new com.legend.compiler.spec.typed.TypedLambda(
                List.of(rowVar), List.of(body),
                new ExprType(
                        new Type.FunctionType(
                                List.of(new Type.Param(rowType,
                                        com.legend.compiler.element.type
                                                .Multiplicity.Bounded.ONE)),
                                res),
                        com.legend.compiler.element.type.Multiplicity
                                .Bounded.ONE));
    }


    /** The association CONDITION's target-side reads: slot/navigate
     * demand plus NESTED-association reads (the navigate() rule) —
     * returns the condition's target param name (null when the
     * predicate shape is unrecognized). Extracted seam (guardrail). */
    private String scanCondTargetReads(ClassSource cs,
            com.legend.model.AssociationDefinition assoc, String real,
            String targetClass, ClassSource target, Set<String> targetSlots,
            Set<String> targetDemand, Set<String> navStepKeys,
            Set<String> tNavDemand3,
            Map<String, Set<String>> nestedAssocReads) {
        String result = null;
        var bindE = associationBindingInClosure(cs.mappingFqn(),
                assoc.qualifiedName()).orElse(null);
        if (bindE != null) {
            var fnsE = ctx.findFunction(bindE.predicateFunctionFqn());
            var cfE = fnsE.size() == 1 ? specs.compile(fnsE.get(0)) : null;
            TypedSpec lastE = cfE == null ? null
                    : cfE.body().get(cfE.body().size() - 1);
            if (lastE instanceof TypedNativeCall callE
                    && callE.args().size() == 5
                    && callE.args().get(4) instanceof TypedLambda condE
                    && condE.parameters().size() == 2) {
                String classAFqnE = ((Type.ClassType)
                        fnsE.get(0).parameters().get(0).type()).fqn();
                boolean parentIsAE = ctx.isSubtype(cs.classFqn(), classAFqnE);
                boolean reverseE = cs.classFqn().equals(targetClass)
                        ? !assoc.property1().propertyName().equals(real)
                        : !parentIsAE;
                String tgtVarE = condE.parameters().get(reverseE ? 0 : 1);
                result = tgtVarE;
                for (TypedSpec b : condE.body()) {
                    // JOINSLOT reads (the ColSpec-join emission) and
                    // navigate-step reads both count
                    CorrelatedSubselects.collectAliasReads(b, tgtVarE,
                            targetSlots, targetDemand);
                    CorrelatedSubselects.collectAliasReads(b, tgtVarE,
                            navStepKeys, tNavDemand3);
                    // NESTED-ASSOCIATION reads ($tgt.address.city where
                    // address is an association of the target class):
                    // the navigate() rule — the nested association joins
                    // into the target after materialization (below)
                    collectNestedAssocReads(b, tgtVarE, targetClass,
                            nestedAssocReads);
                }
            }
        }
        return result;
    }

    /** SOURCE-SIDE nested reads (the navigate() rule, parent side): the
     * condition reads {@code $s.<assocProp>.<col>} where assocProp is an
     * association of the PARENT class — register the parent's own assoc
     * join FIRST (its prefixed columns land on the accumulated root row,
     * exactly like a query-demanded navigation) and re-point the
     * condition reads at them. Root-level (hop 0) only. */
    AssocJoin withSourceNestedAssocs(TemporalFrame temporal, ClassSource cs,
            AssocJoin aj, StoreResolver.Context context,
            java.util.List<AssocJoin> assocJoins,
            Map<String, AssocJoin> joinsByChain,
            Map<String, Substitution.AssocSub> assocs) {
        TypedLambda cond = aj.condition();
        if (cond.parameters().size() != 2) {
            return aj;
        }
        String srcVar = cond.parameters().get(0);
        Map<String, Set<String>> reads = new java.util.LinkedHashMap<>();
        for (TypedSpec b : cond.body()) {
            collectNestedAssocReads(b, srcVar, cs.classFqn(), reads);
        }
        if (reads.isEmpty()) {
            return aj;
        }
        Map<String, String> prefixByProp = new java.util.LinkedHashMap<>();
        java.util.List<Type.Column> lookupCols = new java.util.ArrayList<>();
        for (var re : reads.entrySet()) {
            String prop = re.getKey();
            AssocJoin aj0 = joinsByChain.get(prop);
            if (aj0 == null) {
                aj0 = associationJoin(temporal, cs, prop, context, false,
                        re.getValue(), prop);
                assocJoins.add(aj0);
                joinsByChain.put(prop, aj0);
                assocs.put(prop, new Substitution.AssocSub(aj0.prefix(),
                        aj0.target().rowVar(), aj0.target().bindings(),
                        aj0.target().classFqn(),
                        Pipelines.slotAliases(aj0.target().pipeline()),
                        aj0.targetSlotPrefixes(), null, null,
                        temporal.milestoneColumnsOf(
                                aj0.target().pipeline(),
                                aj0.target().classFqn())));
            }
            prefixByProp.put(prop, aj0.prefix());
            for (Type.Column c : aj0.targetRow().columns()) {
                lookupCols.add(new Type.Column(aj0.prefix() + c.name(),
                        c.type(), c.multiplicity()));
            }
        }
        TypedLambda cond2 = rewriteNestedAssocCondReads(cond, srcVar,
                prefixByProp, new Type.RelationType(lookupCols));
        return new AssocJoin(aj.prefix(), aj.target(), aj.targetPipeline(),
                aj.targetRow(), cond2, aj.targetSlotPrefixes(),
                aj.targetSubNavs(), aj.corrSubPred());
    }

    /** Nested-association reads in an association condition:
     * {@code $tgt.<assocProp>.<col>} where assocProp is an association
     * of the target class (the navigate() rule — task #78). */
    private void collectNestedAssocReads(TypedSpec n, String tgtVar,
            String targetClass, Map<String, Set<String>> out) {
        if (n instanceof com.legend.compiler.spec.typed.TypedPropertyAccess pa
                && pa.source() instanceof
                        com.legend.compiler.spec.typed.TypedPropertyAccess mid
                && mid.source() instanceof
                        com.legend.compiler.spec.typed.TypedVariable v
                && v.name().equals(tgtVar)
                && ctx.findAssociationOf(targetClass, mid.property())
                        .isPresent()) {
            out.computeIfAbsent(mid.property(),
                    k -> new LinkedHashSet<>()).add(pa.property());
            return;
        }
        for (TypedSpec c : n.children()) {
            collectNestedAssocReads(c, tgtVar, targetClass, out);
        }
    }

    /** The condition with nested-association reads re-pointed at the
     * WIDENED target row's prefixed columns. */
    static TypedLambda rewriteNestedAssocCondReads(TypedLambda cond,
            String tgtVar, Map<String, String> prefixByProp,
            Type.RelationType row) {
        if (tgtVar == null || prefixByProp.isEmpty()) {
            return cond;
        }
        java.util.List<TypedSpec> nb =
                new java.util.ArrayList<>(cond.body().size());
        for (TypedSpec b : cond.body()) {
            nb.add(nestedCondRead(b, tgtVar, prefixByProp, row));
        }
        return new TypedLambda(cond.parameters(), nb, cond.info());
    }

    private static TypedSpec nestedCondRead(TypedSpec n, String tgtVar,
            Map<String, String> pfx, Type.RelationType row) {
        if (n instanceof com.legend.compiler.spec.typed.TypedPropertyAccess pa
                && pa.source() instanceof
                        com.legend.compiler.spec.typed.TypedPropertyAccess mid
                && mid.source() instanceof
                        com.legend.compiler.spec.typed.TypedVariable v
                && v.name().equals(tgtVar)
                && pfx.containsKey(mid.property())) {
            String col = pfx.get(mid.property()) + pa.property();
            Type.Column c = row.columns().stream()
                    .filter(x -> x.name().equals(col)).findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "resolver bug: nested-association read column '"
                                    + col + "' missing from the widened"
                                    + " target row"));
            return new com.legend.compiler.spec.typed.TypedPropertyAccess(
                    new com.legend.compiler.spec.typed.TypedVariable(tgtVar,
                            new ExprType(row,
                                    com.legend.compiler.element.type
                                            .Multiplicity.Bounded.ONE)),
                    col, new ExprType(c.type(), c.multiplicity()));
        }
        if (n instanceof TypedNativeCall c2) {
            java.util.List<TypedSpec> args =
                    new java.util.ArrayList<>(c2.args().size());
            for (TypedSpec a : c2.args()) {
                args.add(nestedCondRead(a, tgtVar, pfx, row));
            }
            return new TypedNativeCall(c2.callee(), args, c2.info());
        }
        return n;
    }

    /** {@code λ(s,t). AND_k s.k == t.k} — the parent-key join-back of the
     * correlated aggregated subselect (key names preserved on both sides). */
    TypedLambda pkEqualityCond(List<String> keys,
            Type.RelationType srcRow, Type.RelationType subRow) {
        return pkEqualityCond(keys, keys, srcRow, subRow);
    }

    /** As above with the sub-side key columns RENAMED ({@code subKeys}
     * aligned with {@code keys}) — the exploding subselect projects parent
     * keys under collision-proof aliases. */
    TypedLambda pkEqualityCond(List<String> keys, List<String> subKeys,
            Type.RelationType srcRow, Type.RelationType subRow) {
        return pkEqualityCond(keys, subKeys, srcRow, subRow, false);
    }

    /** {@code orCombine}: UNION-split key pairs (ID_0/ID_1 — each NULL
     * off-member) join back on OR of same-name equalities; NULL=NULL is
     * not true in SQL, so exactly the member's own pair matches (the
     * engine's unionBase join-back — task #27 U4). */
    TypedLambda pkEqualityCond(List<String> keys, List<String> subKeys,
            Type.RelationType srcRow, Type.RelationType subRow,
            boolean orCombine) {
        var eqFns = ctx.findFunction("meta::pure::functions::boolean::equal")
                .stream().filter(f -> f.parameters().size() == 2).toList();
        var andFns = ctx.findFunction("meta::pure::functions::boolean::"
                + (orCombine ? "or" : "and"))
                .stream().filter(f -> f.parameters().size() == 2).toList();
        if (eqFns.size() != 1 || andFns.size() != 1) {
            throw new IllegalStateException("resolver bug: expected one 2-arg"
                    + " boolean::equal and boolean::and/or");
        }
        var boolOne = new ExprType(Type.Primitive.BOOLEAN,
                com.legend.compiler.element.type.Multiplicity.Bounded.ONE);
        TypedSpec acc = null;
        for (int ki = 0; ki < keys.size(); ki++) {
            String k = keys.get(ki);
            String sk = subKeys.get(ki);
            var sCol = srcRow.columns().stream()
                    .filter(c -> c.name().equals(k)).findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "resolver bug: parent key '" + k
                            + "' missing from the source row"));
            TypedSpec eq = new TypedNativeCall(eqFns.get(0), List.of(
                    new com.legend.compiler.spec.typed.TypedPropertyAccess(
                            new com.legend.compiler.spec.typed.TypedVariable("s",
                                    new ExprType(srcRow,
                                            com.legend.compiler.element.type
                                                    .Multiplicity.Bounded.ONE)),
                            k, new ExprType(sCol.type(), sCol.multiplicity())),
                    new com.legend.compiler.spec.typed.TypedPropertyAccess(
                            new com.legend.compiler.spec.typed.TypedVariable("t",
                                    new ExprType(subRow,
                                            com.legend.compiler.element.type
                                                    .Multiplicity.Bounded.ONE)),
                            sk, new ExprType(sCol.type(), sCol.multiplicity()))),
                    boolOne);
            acc = acc == null ? eq
                    : new TypedNativeCall(andFns.get(0), List.of(acc, eq), boolOne);
        }
        return new com.legend.compiler.spec.typed.TypedLambda(
                List.of("s", "t"), List.of(acc),
                new ExprType(
                        new Type.FunctionType(
                                List.of(new Type.Param(srcRow,
                                        com.legend.compiler.element.type
                                                .Multiplicity.Bounded.ONE),
                                        new Type.Param(subRow,
                                        com.legend.compiler.element.type
                                                .Multiplicity.Bounded.ONE)),
                                new Type.Param(Type.Primitive.BOOLEAN,
                                        com.legend.compiler.element.type
                                                .Multiplicity.Bounded.ONE)),
                        com.legend.compiler.element.type.Multiplicity
                                .Bounded.ONE));
    }

    private static String freshName(String base, Set<String> taken) {
        String n = base;
        int k = 2;
        while (taken.contains(n)) {
            n = base + k++;
        }
        taken.add(n);
        return n;
    }

    private static void collectVarNames(
            com.legend.compiler.spec.typed.TypedSpec n, Set<String> out) {
        if (n instanceof com.legend.compiler.spec.typed.TypedVariable v) {
            out.add(v.name());
        }
        n.children().forEach(c -> collectVarNames(c, out));
    }

    /** Shadow-AWARE free-variable reads: a name is bound only within its
     * binder's subtree — never by global name collision (the F2/F4
     * capture family). Enters lambdas through their bodies with the
     * params added to {@code bound}. */
    private static void collectFreeVars(TypedSpec n, Set<String> bound,
            Set<String> out) {
        if (n instanceof com.legend.compiler.spec.typed.TypedVariable v) {
            if (!bound.contains(v.name())) {
                out.add(v.name());
            }
            return;
        }
        if (n instanceof TypedLambda l) {
            Set<String> b2 = new LinkedHashSet<>(bound);
            b2.addAll(l.parameters());
            for (TypedSpec b : l.body()) {
                collectFreeVars(b, b2, out);
            }
            return;
        }
        for (TypedSpec c : n.children()) {
            collectFreeVars(c, bound, out);
        }
    }

    /** Alpha-rename any binder of {@code cond} colliding with a name in
     * {@code taken}, rewriting its reads through the ONE shadow-aware
     * row-read rewriter. The composed condition then cannot capture a
     * user variable by construction. */
    private static TypedLambda freshenBinders(TypedLambda cond,
            Set<String> taken) {
        List<String> params = new ArrayList<>(cond.parameters());
        List<TypedSpec> body = new ArrayList<>(cond.body());
        boolean changed = false;
        for (int i = 0; i < params.size(); i++) {
            String p0 = params.get(i);
            if (!taken.contains(p0)) {
                continue;
            }
            String p1 = p0;
            int k = 2;
            while (taken.contains(p1) || params.contains(p1)) {
                p1 = p0 + "_c" + k++;
            }
            final String to = p1;
            for (int j = 0; j < body.size(); j++) {
                body.set(j, Pipelines.rewriteRowReads(body.get(j), p0,
                        Map.of(), Set.of(), v -> new com.legend.compiler.spec
                                .typed.TypedVariable(to, v.info())));
            }
            params.set(i, p1);
            taken.add(p1);
            changed = true;
        }
        return changed ? new TypedLambda(params, body, cond.info()) : cond;
    }

    private static Type.RelationType rowOr(Type t,
            Type.RelationType fallback) {
        return t instanceof Type.RelationType rt ? rt : fallback;
    }
}
