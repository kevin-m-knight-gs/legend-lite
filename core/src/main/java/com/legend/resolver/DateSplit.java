// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.resolver;

import com.legend.compiler.spec.typed.TypedFilter;
import com.legend.compiler.spec.typed.TypedGroupBy;
import com.legend.compiler.spec.typed.TypedLambda;
import com.legend.compiler.spec.typed.TypedMilestonedAccess;
import com.legend.compiler.spec.typed.TypedProject;
import com.legend.compiler.spec.typed.TypedPropertyAccess;
import com.legend.compiler.spec.typed.TypedSortBy;
import com.legend.compiler.spec.typed.TypedSpec;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The TWO-DATES-PER-HEAD date splitter (StoreResolver PHASE 1b,
 * extracted at the file-size guardrail) — see
 * {@link #splitDatedHeads}. */
final class DateSplit {

    private DateSplit() {
    }

    /** PHASE 1b — TWO-DATES-PER-HEAD (engine keys separate joins by
     * date): when ONE chain is navigated with DIFFERENT temporal
     * arguments, each distinct date-set beyond the first renames to a
     * date-fingerprinted synthetic head ('product#d1') — a separate join
     * identity carrying its own spec; SyntheticHeads.realHead() keeps every model lookup
     * transparent. Same-date accesses keep sharing one join
     * (merge-by-identity). Runs BEFORE spec collection so the conflict
     * throw never fires for split chains. Mutates {@code ops} in place;
     * returns the (possibly rewritten) terminal. */
    static TypedSpec splitDatedHeads(List<TypedSpec> ops, TypedSpec top,
            TemporalFrame temporal, SyntheticHeads synthetics) {
        Map<String, List<com.legend.compiler.spec.typed
                .TypedMilestonedAccess>> datedByChain =
                new LinkedHashMap<>();
        Set<String> plainHeads = new LinkedHashSet<>();
        for (TypedSpec op : ops) {
            if (op instanceof TypedFilter f) {
                for (TypedSpec b : f.predicate().body()) {
                    collectDatedNodes(b, f.predicate().parameters().get(0),
                            datedByChain, plainHeads);
                }
            }
            if (op instanceof TypedSortBy sb) {
                for (TypedSpec b : sb.key().body()) {
                    collectDatedNodes(b, sb.key().parameters().get(0),
                            datedByChain, plainHeads);
                }
            }
        }
        if (top instanceof TypedProject || top instanceof TypedGroupBy) {
            for (TypedLambda fn : terminalLambdas(top)) {
                for (TypedSpec b : fn.body()) {
                    collectDatedNodes(b, fn.parameters().get(0), datedByChain,
                            plainHeads);
                }
            }
        }
        IdentityHashMap<TypedSpec, String> dateRenames =
                new IdentityHashMap<>();
        IdentityHashMap<TypedSpec, Boolean> ctxStrips =
                new IdentityHashMap<>();
        for (var chainDates : datedByChain.entrySet()) {
            // BASE identity is RESERVED for the propagation whenever a
            // plain access coexists — including one created by STRIPPING
            // an explicit context-equal date (that access IS the
            // propagation, engine merge-by-identity) — so a foreign date
            // never shares the propagated join
            // (testMilestoneDatePropogationThru*IsIndenpendent...)
            Map<TemporalFrame.TemporalSpec, String> byArgs =
                    new LinkedHashMap<>();
            boolean headLevel = !chainDates.getKey().contains(".");
            boolean plainCoexists = headLevel
                    && plainHeads.contains(chainDates.getKey());
            TemporalFrame.TemporalSpec ctx = null;
            List<com.legend.compiler.spec.typed.TypedMilestonedAccess>
                    foreign = new ArrayList<>();
            List<com.legend.compiler.spec.typed.TypedMilestonedAccess>
                    ctxEqual = new ArrayList<>();
            for (var ma : chainDates.getValue()) {
                ctx = temporal.contextSpec(ma.info().type() instanceof
                        com.legend.compiler.element.type.Type.ClassType ct
                        ? ct.fqn() : null);
                TemporalFrame.TemporalSpec spec = new TemporalFrame.TemporalSpec(
                        temporal.normalizeContextDates(ma.dates()), ma.sweep());
                if (spec.equals(ctx)) {
                    ctxEqual.add(ma);
                } else {
                    foreign.add(ma);
                }
            }
            // ctx-equal dates STRIP only when a FOREIGN date coexists on
            // the chain (the collision case) — an all-context chain keeps
            // its dated channel (embedded multi-hop heads have no plain
            // route yet: testConstraintUsageOfThisMilestoningContext1)
            if (!foreign.isEmpty()) {
                for (var ma : ctxEqual) {
                    ctxStrips.put(ma, Boolean.TRUE);
                }
                plainCoexists = plainCoexists
                        || (headLevel && !ctxEqual.isEmpty());
            }
            if (plainCoexists && ctx != null) {
                byArgs.put(ctx, chainDates.getValue().get(0).property());
            }
            for (var ma : foreign) {
                TemporalFrame.TemporalSpec spec = new TemporalFrame.TemporalSpec(
                        temporal.normalizeContextDates(ma.dates()), ma.sweep());
                String name = byArgs.get(spec);
                if (name == null) {
                    name = byArgs.isEmpty() ? ma.property()
                            : synthetics.mintDateName(ma.property());
                    byArgs.put(spec, name);
                }
                if (!name.equals(ma.property())) {
                    dateRenames.put(ma, name);
                }
            }
        }
        if (!ctxStrips.isEmpty() || !dateRenames.isEmpty()) {
            for (int i = 0; i < ops.size(); i++) {
                ops.set(i, synthetics.replaceDatedNodes(ops.get(i),
                        dateRenames, ctxStrips));
            }
            top = synthetics.replaceDatedNodes(top, dateRenames, ctxStrips);
        }
        return top;
    }

    /** Milestoned accesses grouped by their dotted chain (mirrors
     * {@link #collectTemporalNodes}'s walk — anything the conflict
     * detector would see, the date splitter sees first). */
    private static void collectDatedNodes(TypedSpec n, String userVar,
            Map<String, List<com.legend.compiler.spec.typed
                    .TypedMilestonedAccess>> out, Set<String> plainHeads) {
        if (n instanceof TypedMilestonedAccess ma) {
            List<String> p = Substitution.pathOf(ma, userVar);
            if (p != null) {
                out.computeIfAbsent(String.join(".", p),
                        k -> new ArrayList<>()).add(ma);
            }
        }
        // PLAIN head reads ($p.classification.type — no date args): the
        // splitter reserves the BASE join identity for these, so a
        // foreign-dated access on the same head must mint its own
        if (n instanceof TypedPropertyAccess pa
                && pa.source() instanceof
                        com.legend.compiler.spec.typed.TypedVariable v
                && v.name().equals(userVar)) {
            plainHeads.add(pa.property());
        }
        if (n instanceof TypedLambda l && l.parameters().contains(userVar)) {
            return;
        }
        for (TypedSpec c : n.children()) {
            collectDatedNodes(c, userVar, out, plainHeads);
        }
    }

    private static List<TypedLambda> terminalLambdas(TypedSpec top) {
        return StoreResolver.terminalLambdas(top);
    }
}
