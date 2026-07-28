package com.legend.resolver;

import com.legend.compiler.spec.typed.TypedGetAll;
import com.legend.compiler.spec.typed.TypedNavigate;
import com.legend.compiler.spec.typed.TypedSpec;

/**
 * Store-anchor reachability &mdash; ALL the reaches in one place (remediation
 * T3.1: two same-named copies with silently different descent rules used to
 * live in StoreResolver and ClassSources).
 *
 * <ul>
 *   <li>{@link #anchored} &mdash; full descent, memoized per resolver pass:
 *       the resolveNode guard predicate. Memo keys are node IDENTITIES;
 *       rebuilt nodes get fresh identities, spliced-unchanged subtrees hit
 *       the memo, and the map is NEVER iterated &mdash; so identity keying is
 *       exact and order-insensitive. This was the O(guards&times;n&sup2;)
 *       full rescan under every guard.</li>
 *   <li>{@link #containsGetAll} &mdash; the same full descent, un-memoized,
 *       for static contexts (SubQueryLift).</li>
 *   <li>{@link #anchoredInFlow} &mdash; pipeline-FLOW reach: a navigate
 *       SLOT's target is getAll-shaped BY CONVENTION (the legacyNavigate
 *       emission), so only flow getAlls demand nested resolution
 *       (ClassSources).</li>
 * </ul>
 */
final class Anchors {

    private final java.util.IdentityHashMap<TypedSpec, Boolean> memo =
            new java.util.IdentityHashMap<>();

    /** Unresolved store anchor beneath {@code n} (full descent, memoized). */
    boolean anchored(TypedSpec n) {
        Boolean hit = memo.get(n);
        if (hit != null) {
            return hit;
        }
        boolean v = false;
        if (n instanceof TypedGetAll) {
            v = true;
        } else {
            for (TypedSpec c : n.children()) {
                if (anchored(c)) {
                    v = true;
                    break;
                }
            }
        }
        memo.put(n, v);
        return v;
    }

    /** Full descent for static contexts. */
    static boolean containsGetAll(TypedSpec n) {
        if (n instanceof TypedGetAll) {
            return true;
        }
        for (TypedSpec c : n.children()) {
            if (containsGetAll(c)) {
                return true;
            }
        }
        return false;
    }

    /** Pipeline-FLOW reach: skips a navigate's conventionally-getAll target. */
    static boolean anchoredInFlow(TypedSpec n) {
        if (n instanceof TypedGetAll) {
            return true;
        }
        if (n instanceof TypedNavigate nav) {
            return anchoredInFlow(nav.source());
        }
        for (TypedSpec c : n.children()) {
            if (anchoredInFlow(c)) {
                return true;
            }
        }
        return false;
    }
}
