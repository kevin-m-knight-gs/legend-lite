// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0
package com.legend.resolver;

import com.legend.compiler.element.type.ExprType;
import com.legend.compiler.element.type.Type;
import com.legend.compiler.spec.typed.TypedJoinSlot;
import com.legend.compiler.spec.typed.TypedLambda;
import com.legend.compiler.spec.typed.TypedNavigate;
import com.legend.compiler.spec.typed.TypedSpec;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * FIRST-READ ORDER of a class pipeline's step joins (corpus-zero program,
 * cluster B, 2026-09-12). The resolver mints a class source's steps in
 * PHASE order — a nav-date chain registers first and sinks deepest
 * ({@link Pipelines#sinkNavSteps}), a demand-pass slot lands above — while
 * the engine attaches root-anchored joins in the order the query first
 * reads them (filter paths, then the terminal's columns: group keys
 * before aggregates, projection columns left to right). The two orders
 * agree on rows and differ on text, and the plan-text goldens are text.
 *
 * <p>{@link #byFirstRead} re-sequences the maximal run of consecutive
 * steps at the top of a pipeline — {@link TypedNavigate} steps with an
 * alias and {@link TypedJoinSlot}s — so that steps appear in
 * {@code order}, keeping every step AFTER the steps it depends on: the
 * sibling aliases its own predicate reads (a nested navigation's parent)
 * and {@code mustFollow} (a milestoned head's window reads a nav-date
 * step's composed column, stamped later in the walk — the sink's
 * invariant, preserved). Steps absent from {@code order} keep their
 * relative order at the end (the walk cancels undemanded ones). Any
 * other node kind bounds the run; nothing crosses it. A LEFT-join chain
 * whose conditions read only the root and earlier steps is
 * order-independent on rows, so this is a spelling decision made once,
 * here, and never in the lowering.
 */
final class SlotOrder {

    private SlotOrder() {
    }

    static TypedSpec byFirstRead(TypedSpec pipeline, List<String> order,
            Map<String, Set<String>> mustFollow) {
        if (order.isEmpty()) {
            return pipeline;
        }
        List<TypedSpec> topDown = new ArrayList<>();
        TypedSpec base = pipeline;
        while (true) {
            if (base instanceof TypedNavigate nv && nv.alias().isPresent()) {
                topDown.add(base);
                base = nv.source();
            } else if (base instanceof TypedJoinSlot js) {
                topDown.add(base);
                base = js.source();
            } else {
                break;
            }
        }
        if (topDown.size() < 2) {
            return pipeline;
        }
        List<TypedSpec> original = new ArrayList<>(topDown);
        java.util.Collections.reverse(original);   // bottom-up: chain order
        Set<String> aliases = new LinkedHashSet<>();
        for (TypedSpec s : original) {
            aliases.add(alias(s));
        }
        List<TypedSpec> placed = new ArrayList<>();
        Set<String> placedAliases = new LinkedHashSet<>();
        List<TypedSpec> pending = new ArrayList<>(original);
        while (!pending.isEmpty()) {
            TypedSpec pick = null;
            int pickRank = Integer.MAX_VALUE;
            for (TypedSpec s : pending) {
                Set<String> deps = dependencies(s, aliases);
                deps.addAll(mustFollow.getOrDefault(alias(s), Set.of()));
                deps.retainAll(aliases);
                if (!placedAliases.containsAll(deps)) {
                    continue;
                }
                int r = order.indexOf(alias(s));
                int rr = r < 0 ? Integer.MAX_VALUE - 1 : r;
                if (pick == null || rr < pickRank) {
                    pick = s;
                    pickRank = rr;
                }
            }
            if (pick == null) {
                throw new IllegalStateException("resolver bug: step dependencies form a cycle among "
                        + aliases);
            }
            pending.remove(pick);
            placed.add(pick);
            placedAliases.add(alias(pick));
        }
        if (placed.equals(original)) {
            return pipeline;
        }
        // rebuild the chain over the base; each step's row = the left row
        // plus the columns the step itself added (its info minus its
        // original source's), so intermediate row types stay exact
        TypedSpec out = base;
        for (TypedSpec s : placed) {
            Type.RelationType left = Type.requireRelationSchema(out.info().type());
            Type.RelationType was = Type.requireRelationSchema(s.info().type());
            Type.RelationType before = Type.requireRelationSchema(source(s).info().type());
            Set<String> beforeNames = new LinkedHashSet<>();
            for (Type.Column c : before.columns()) {
                beforeNames.add(c.name());
            }
            List<Type.Column> row = new ArrayList<>(left.columns());
            for (Type.Column c : was.columns()) {
                if (!beforeNames.contains(c.name())) {
                    row.add(c);
                }
            }
            ExprType info = new ExprType(Type.relation(new Type.RelationType(row)),
                    s.info().multiplicity());
            out = s instanceof TypedNavigate nv
                    ? new TypedNavigate(out, nv.alias(), nv.target(), nv.predicate(),
                            nv.pairedPredicate(), nv.frameName(), nv.form(), info)
                    : new TypedJoinSlot(out, ((TypedJoinSlot) s).alias(),
                            ((TypedJoinSlot) s).target(), ((TypedJoinSlot) s).condition(),
                            ((TypedJoinSlot) s).frameName(), info);
        }
        return out;
    }

    private static String alias(TypedSpec step) {
        return step instanceof TypedNavigate nv ? nv.alias().orElseThrow()
                : ((TypedJoinSlot) step).alias();
    }

    private static TypedSpec source(TypedSpec step) {
        return step instanceof TypedNavigate nv ? nv.source() : ((TypedJoinSlot) step).source();
    }

    /** The sibling steps {@code step}'s own condition reads on its left row. */
    private static Set<String> dependencies(TypedSpec step, Set<String> aliases) {
        TypedLambda cond = step instanceof TypedNavigate nv ? nv.predicate()
                : ((TypedJoinSlot) step).condition();
        Set<String> deps = new LinkedHashSet<>();
        if (cond.parameters().isEmpty()) {
            return deps;
        }
        String leftParam = cond.parameters().get(0);
        String own = alias(step);
        for (String other : aliases) {
            if (other.equals(own)) {
                continue;
            }
            for (TypedSpec b : cond.body()) {
                if (Pipelines.referencesAliasOn(b, leftParam, Set.of(other))) {
                    deps.add(other);
                    break;
                }
            }
        }
        return deps;
    }
}
