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
                    js.target(), js.condition(), js.info());
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
}
