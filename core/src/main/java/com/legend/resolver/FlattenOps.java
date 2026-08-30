// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.resolver;

import com.legend.compiler.spec.typed.TypedDrop;
import com.legend.compiler.spec.typed.TypedFilter;
import com.legend.compiler.spec.typed.TypedLambda;
import com.legend.compiler.spec.typed.TypedLimit;
import com.legend.compiler.spec.typed.TypedSlice;
import com.legend.compiler.spec.typed.TypedSpec;
import com.legend.error.NotImplementedException;

import java.util.List;
import java.util.function.Function;

/**
 * Ops BELOW a class-flatten hop ({@code Firm.all()->filter(...)->at(0)
 * .employees}) apply to the flatten's LEFT (source-set) pipeline before
 * the association join — the filtered/limited source set is what the
 * join fans out (engine: the chain's own subselect joins the target).
 * Substitution is the caller's bindings-only rewriter — a pred that
 * navigates onward from the source class stays loud.
 */
final class FlattenOps {

    /** BELOW-OP SPLIT for a nav-slot flatten: ops whose reads pass
     * through the HOP head hoist ABOVE the materialization
     * (row-equivalent under the INNER hop) with the hop's AssocSub for
     * dispatch; the rest splice below with factory materials. Collects
     * the non-colliding paths ({@code spliceFull}) and the colliding
     * heads/tail-heads that extend the hop's own demand. */
    record BelowSplit(List<TypedSpec> hoisted, List<TypedSpec> spliceOps,
            java.util.Set<List<String>> spliceFull,
            java.util.Set<String> hopHeads,
            java.util.Set<String> hopTailHeads) {}

    static BelowSplit splitBelowOps(List<TypedSpec> belowOps,
            ClassSource src, @com.legend.Nullable String alias,
            java.util.Set<String> navStepKeys) {
        List<TypedSpec> hoisted = new java.util.ArrayList<>();
        List<TypedSpec> spliceOps = new java.util.ArrayList<>();
        java.util.Set<List<String>> spliceFull = new java.util.LinkedHashSet<>();
        java.util.Set<String> hopHeads = new java.util.LinkedHashSet<>();
        java.util.Set<String> hopTailHeads = new java.util.LinkedHashSet<>();
        for (TypedSpec op : belowOps) {
            List<TypedLambda> bls = switch (op) {
                case TypedFilter f -> List.of(f.predicate());
                case com.legend.compiler.spec.typed.TypedSortBy sb ->
                        List.of(sb.key());
                default -> List.of();
            };
            java.util.Set<List<String>> opPaths =
                    new java.util.LinkedHashSet<>();
            for (TypedLambda bl : bls) {
                if (bl.parameters().isEmpty()) {
                    continue;
                }
                for (TypedSpec b : bl.body()) {
                    StoreResolver.consumedPaths(b,
                            bl.parameters().get(0), opPaths);
                }
            }
            boolean collides = false;
            for (List<String> pp : opPaths) {
                TypedSpec hb = src.bindings().get(
                        SyntheticHeads.realHead(pp.get(0)));
                // null alias = no hop to collide with (the assoc-route /
                // re-root splices reuse this splitter for path collection)
                if (alias != null && hb != null
                        && alias.equals(InnerDemand.navSlotAlias(
                                hb, src.rowVar(), navStepKeys))) {
                    collides = true;
                    hopHeads.add(pp.get(0));
                    if (pp.size() >= 2) {
                        hopTailHeads.add(pp.get(1));
                    }
                }
            }
            if (collides) {
                hoisted.add(op);
            } else {
                spliceOps.add(op);
                spliceFull.addAll(opPaths);
            }
        }
        return new BelowSplit(hoisted, spliceOps, spliceFull,
                hopHeads, hopTailHeads);
    }

    private FlattenOps() {
    }

    /** SPLICE below-ops into a SLOT-ROUTE pipeline spine: the class
     * extent the ops act on is {@code base scan + mapping ~filter}, and
     * the flatten's navigate/joinslot steps must fan out from the
     * REDUCED set — so the spine rebuilds as
     * {@code steps( belowOps( mapFilters( base ) ) )}. The mapping
     * filter reads base columns only, so pulling it beneath the ops is
     * a syntactic move, not a semantic one; a LIMIT above it would be
     * wrong (limit-then-filter). */
    static TypedSpec spliceBelow(TypedSpec pipe, List<TypedSpec> belowOps,
            Function<TypedLambda, TypedLambda> sub) {
        if (pipe instanceof com.legend.compiler.spec.typed.TypedNavigate nv) {
            return new com.legend.compiler.spec.typed.TypedNavigate(
                    spliceBelow(nv.source(), belowOps, sub), nv.alias(),
                    nv.target(), nv.predicate(), nv.form(), nv.info());
        }
        if (pipe instanceof com.legend.compiler.spec.typed.TypedJoinSlot js) {
            return new com.legend.compiler.spec.typed.TypedJoinSlot(
                    spliceBelow(js.source(), belowOps, sub), js.alias(),
                    js.target(), js.condition(), js.frameName(), js.info());
        }
        if (pipe instanceof TypedFilter mf) {
            // mapping ~filter above the steps: keep it BELOW the ops
            return applyBelow(mf, belowOps, sub);
        }
        return applyBelow(pipe, belowOps, sub);
    }

    /** {@code belowOps} arrive in chain-walk order (topmost first);
     * application is bottom-up. Row-set-shaping ops beyond filter and
     * the limit family keep a loud wall — a sort/distinct below the hop
     * needs its own emission decision, not a silent guess. */
    static TypedSpec applyBelow(TypedSpec left, List<TypedSpec> belowOps,
            Function<TypedLambda, TypedLambda> sub) {
        TypedSpec p = left;
        for (int i = belowOps.size() - 1; i >= 0; i--) {
            TypedSpec op = belowOps.get(i);
            p = switch (op) {
                case TypedFilter f ->
                        new TypedFilter(p, sub.apply(f.predicate()), p.info());
                case TypedLimit l -> new TypedLimit(p, l.count(), p.info());
                case TypedDrop d -> new TypedDrop(p, d.count(), p.info());
                case TypedSlice sl ->
                        new TypedSlice(p, sl.start(), sl.stop(), p.info());
                default -> throw new NotImplementedException(
                        "object-space " + op.getClass().getSimpleName()
                        + " below a class-flatten hop is not supported yet");
            };
        }
        return p;
    }

    /** APPLY hop-colliding hoisted ops above the materialization: the
     * rewriter dispatches their reads through the hop's AssocSub —
     * row-equivalent to below-application under the INNER hop. Only
     * filters hoist; other op kinds keep a loud wall. */
    static TypedSpec applyHoisted(TypedSpec pipe, List<TypedSpec> hoisted,
            Function<TypedLambda, TypedLambda> sub) {
        TypedSpec p = pipe;
        for (TypedSpec op : hoisted) {
            if (!(op instanceof TypedFilter f)) {
                throw new NotImplementedException("hop-colliding below-op"
                        + " kind " + op.getClass().getSimpleName()
                        + " is not supported yet");
            }
            p = new TypedFilter(p, sub.apply(f.predicate()), p.info());
        }
        return p;
    }

    /** Heads read off the RE-ROOTED target class in the chain's lambdas —
     * the flatten's downstream demand (task #63: the hop target must
     * materialize WITH the nav/slot steps those heads dispatch through). */
    static java.util.Set<String> downstreamHeads(List<TypedSpec> ops,
            @com.legend.Nullable TypedSpec top) {
        java.util.Set<String> heads = new java.util.LinkedHashSet<>();
        collectLambdaHeads(ops == null ? List.of() : ops, heads);
        if (top != null) {
            collectLambdaHeads(List.of(top), heads);
        }
        return heads;
    }

    private static void collectLambdaHeads(List<TypedSpec> nodes,
            java.util.Set<String> out) {
        for (TypedSpec n : nodes) {
            if (n instanceof TypedLambda lam && !lam.parameters().isEmpty()) {
                InnerDemand.collectParamPathHeads(lam,
                        lam.parameters().get(0), out);
            }
            collectLambdaHeads(n.children(), out);
        }
    }

    /** Re-stamp the join carrying {@code prefix} INNER (audit 21b F3 —
     * the flatten's row-set contract). Walks the materialized spine
     * (joins + filters); not finding the join is a loud resolver bug,
     * never a silent LEFT. */
    static TypedSpec innerizeFlattenJoin(TypedSpec pipe, String prefix) {
        TypedSpec out = innerizeOrNull(pipe, prefix, "");
        if (out == null) {
            throw new IllegalStateException("resolver bug: flatten inner-stamp"
                    + " did not find the navigate join '" + prefix
                    + "' in the materialized pipeline");
        }
        return out;
    }

    /** {@code pipe} with the join whose COMPOSED prefix (outer join
     * prefixes concatenated down the right spine) matches {@code prefix}
     * stamped INNER, or null when absent. The composed walk serves the
     * multi-hop flatten: an inner hop's navigate join nests inside the
     * previous hop's join-right with only its local prefix. */
    private static @com.legend.Nullable TypedSpec innerizeOrNull(TypedSpec pipe, String prefix,
            String acc) {
        if (pipe instanceof com.legend.compiler.spec.typed.TypedJoin j) {
            String composed = j.prefix().map(p -> acc + p).orElse(null);
            if (composed != null && composed.equals(prefix)) {
                return new com.legend.compiler.spec.typed.TypedJoin(
                        j.left(), j.right(), AssociationJoins.innerKind(),
                        j.condition(), j.prefix(), j.frameName(), j.info(),
                false /* resolver-synth */);
            }
            TypedSpec left = innerizeOrNull(j.left(), prefix, acc);
            if (left != null) {
                return new com.legend.compiler.spec.typed.TypedJoin(
                        left, j.right(), j.kind(), j.condition(),
                        j.prefix(), j.frameName(), j.info(),
                false /* resolver-synth */);
            }
            if (composed != null && prefix.startsWith(composed)) {
                TypedSpec right = innerizeOrNull(j.right(), prefix, composed);
                if (right != null) {
                    return new com.legend.compiler.spec.typed.TypedJoin(
                            j.left(), right, j.kind(), j.condition(),
                            j.prefix(), j.frameName(), j.info(),
                false /* resolver-synth */);
                }
            }
            return null;
        }
        if (pipe instanceof TypedFilter f) {
            TypedSpec src = innerizeOrNull(f.source(), prefix, acc);
            return src == null ? null
                    : new TypedFilter(src, f.predicate(), f.info());
        }
        return null;
    }

    /** One re-pointed binding for the flatten's composed source: scalar
     * bindings ride {@link Pipelines#prefixColumns}; an EMBEDDED binding
     * (TypedNewInstance ctor over parent-alias columns) re-points each
     * inner property expression, keeping the ctor. */
    static TypedSpec prefixBinding(TypedSpec b, String targetRowVar,
            String prefix, String newRowVar,
            com.legend.compiler.element.type.ExprType rowInfo) {
        TypedSpec inner = b;
        if (inner instanceof com.legend.compiler.spec.typed.TypedNativeCall c
                && c.args().size() == 1
                && com.legend.builtin.Pure.isToOneCall(c.callee().qualifiedName())
                && c.args().get(0) instanceof
                        com.legend.compiler.spec.typed.TypedNewInstance) {
            inner = c.args().get(0);
        }
        if (inner instanceof com.legend.compiler.spec.typed.TypedNewInstance ctor) {
            java.util.Map<String, TypedSpec> props =
                    new java.util.LinkedHashMap<>();
            for (var pe : ctor.properties().entrySet()) {
                props.put(pe.getKey(), prefixBinding(pe.getValue(),
                        targetRowVar, prefix, newRowVar, rowInfo));
            }
            return new com.legend.compiler.spec.typed.TypedNewInstance(
                    ctor.classFqn(), props, ctor.info());
        }
        return Pipelines.prefixColumns(b, targetRowVar, prefix,
                v -> new com.legend.compiler.spec.typed.TypedVariable(
                        newRowVar, rowInfo));
    }
}
