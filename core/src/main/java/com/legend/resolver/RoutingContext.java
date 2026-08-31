// SPDX-License-Identifier: Apache-2.0

package com.legend.resolver;

import com.legend.compiler.spec.typed.TypedDistinct;
import com.legend.compiler.spec.typed.TypedFilter;
import com.legend.compiler.spec.typed.TypedFrom;
import com.legend.compiler.spec.typed.TypedGraphFetch;
import com.legend.compiler.spec.typed.TypedGroupBy;
import com.legend.compiler.spec.typed.TypedLimit;
import com.legend.compiler.spec.typed.TypedMap;
import com.legend.compiler.spec.typed.TypedNativeCall;
import com.legend.compiler.spec.typed.TypedProject;
import com.legend.compiler.spec.typed.TypedSerialize;
import com.legend.compiler.spec.typed.TypedSlice;
import com.legend.compiler.spec.typed.TypedSortBy;
import com.legend.compiler.spec.typed.TypedSpec;
import com.legend.compiler.spec.typed.TypedUserCall;

/**
 * Call-site routing context (slice-1 job 1): the helpers that thread a
 * query's OWN mapping/chain context to every consumption point — the
 * spine fold for pre-walk consumers and the execute()/executionPlan()
 * argument context for the generic walk. Extracted from StoreResolver
 * (file-size guardrail); the semantics live with the resolver.
 */
final class RoutingContext {

    private RoutingContext() {
    }

    /** The mapping REF of an execute()/executionPlan() call, or null
     * when the node is not one (or its mapping argument is not a plain
     * reference — those keep the outer context). */
    static com.legend.compiler.spec.typed.@com.legend.Nullable
            TypedPackageableRef routedEntryMapping(TypedNativeCall nc) {
        String f = nc.callee().qualifiedName();
        boolean routed = com.legend.compiler.element.type.PlatformTypes
                .isExecuteFqn(f);
        return routed && nc.args().size() >= 2
                && nc.args().get(1) instanceof com.legend.compiler.spec
                        .typed.TypedPackageableRef mr ? mr : null;
    }

    static StoreResolver.Context routedContext(TypedNativeCall nc,
            StoreResolver.Context outer,
            com.legend.compiler.spec.SpecCompiler specs) {
        var mr = java.util.Objects.requireNonNull(routedEntryMapping(nc));
        java.util.List<String> chain = java.util.List.of();
        if (nc.args().size() >= 3) {
            TypedSpec rt = nc.args().get(2);
            if (rt instanceof TypedUserCall) {
                // the runtime is usually a HELPER call (m2m2r::runtime())
                // — inline once so the chain walker sees the constructed
                // ModelChainConnection (same rule as the plan lane's
                // connection-flag inlining)
                rt = new com.legend.compiler.spec.UserCallInliner(specs)
                        .inlineBody(java.util.List.of(rt)).get(0);
            }
            chain = TypedFrom.chainMappingsIn(rt);
        }
        return new StoreResolver.Context(mr.fullPath(),
                outer.runtimeFqn(), chain);
    }

    /** The context in effect at the chain's getAll: fold every in-chain
     * from() met on the source spine (deepest wins by composition) —
     * the SAME folding the collect walk applies at 'in-chain from()
     * re-scopes BOTH locals'. Pre-walk consumers (the synthetic-head
     * canonicalizer) must dispatch under THIS context, not the entry
     * context: an entry-captured context re-derives at consumption what
     * the query's own from() already decided (slice-1 job 1 — the
     * runtime-fallback firings were all such stale captures). */
    static StoreResolver.Context spineContext(TypedSpec top,
            StoreResolver.Context outer,
            java.util.function.BiFunction<TypedFrom, StoreResolver.Context,
                    StoreResolver.Context> fromContext) {
        StoreResolver.Context c = outer;
        TypedSpec cur = top;
        while (true) {
            if (cur instanceof TypedFrom fr) {
                c = fromContext.apply(fr, c);
                cur = fr.source();
            } else if (cur instanceof TypedSerialize sz) {
                cur = sz.source() instanceof TypedGraphFetch gf
                        ? gf.source() : sz.source();
            } else if (cur instanceof TypedProject n) {
                cur = n.source();
            } else if (cur instanceof TypedGroupBy n) {
                cur = n.source();
            } else if (cur instanceof TypedFilter n) {
                cur = n.source();
            } else if (cur instanceof TypedDistinct n) {
                cur = n.source();
            } else if (cur instanceof TypedLimit n) {
                cur = n.source();
            } else if (cur instanceof TypedSlice n) {
                cur = n.source();
            } else if (cur instanceof TypedSortBy n) {
                cur = n.source();
            } else if (cur instanceof TypedMap n) {
                cur = n.source();
            } else if (cur instanceof com.legend.compiler.spec.typed
                    .TypedPropertyAccess n) {
                cur = n.source();
            } else if (cur instanceof TypedNativeCall n
                    && !n.args().isEmpty()) {
                cur = n.args().get(0);
            } else {
                break;
            }
        }
        return c;
    }

    /** Per-class dispatch: the runtime candidate that BINDS the class wins
     * (chain-aware — ClassSources owns the binding logic). */
    /** The memo key of a context-dependent resolution (audit 23: runtime
     * + chain mappings participate — a mixed read poisoned the cache
     * across an in-chain from()). */
    static String contextKey(StoreResolver.Context c) {
        return (c.explicitMapping() == null ? "" : c.explicitMapping())
                + '\u0000'
                + (c.runtimeFqn() == null ? "" : c.runtimeFqn())
                + (c.chainMappings().isEmpty() ? ""
                        : '\u0000' + String.join(",", c.chainMappings()));
    }

}
