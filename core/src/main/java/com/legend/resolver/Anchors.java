package com.legend.resolver;

import com.legend.compiler.element.type.Type;
import com.legend.compiler.spec.typed.TypedDrop;
import com.legend.compiler.spec.typed.TypedFilter;
import com.legend.compiler.spec.typed.TypedFrom;
import com.legend.compiler.spec.typed.TypedGetAll;
import com.legend.compiler.spec.typed.TypedLimit;
import com.legend.compiler.spec.typed.TypedMap;
import com.legend.compiler.spec.typed.TypedNativeCall;
import com.legend.compiler.spec.typed.TypedNavigate;
import com.legend.compiler.spec.typed.TypedPropertyAccess;
import com.legend.compiler.spec.typed.TypedSlice;
import com.legend.compiler.spec.typed.TypedSortBy;
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

    private final java.util.IdentityHashMap<TypedSpec, Space> spaceMemo =
            new java.util.IdentityHashMap<>();

    /**
     * THE space classifier (one definition; memoized like {@link #anchored}).
     * Reads only STRONG inputs — the checked types in {@code info()} and the
     * node kinds — never re-deriving what the Typer decided.
     */
    Space spaceOf(TypedSpec n) {
        Space hit = spaceMemo.get(n);
        if (hit != null) {
            return hit;
        }
        Space v = objectSpine(n) ? Space.OBJECT
                : anchored(n) ? Space.ANCHORED
                : Space.INERT;
        spaceMemo.put(n, v);
        return v;
    }

    /** The object-space spine rules (formerly StoreResolver.isObjectSpace). */
    private boolean objectSpine(TypedSpec source) {
        return switch (source) {
            case TypedGetAll ignored -> true;
            // a CLASS-typed property HOP over an object-space chain IS
            // object space (the auto-map flatten re-roots at its target)
            case TypedPropertyAccess pa
                    when pa.info().type() instanceof Type.ClassType ->
                    spaceOf(pa.source()) == Space.OBJECT;
            // ->map with a CLASS-result mapper stays in object space
            case TypedMap m
                    when ((Type.FunctionType) m.mapper().info().type()).result()
                            .type() instanceof Type.ClassType ->
                    spaceOf(m.source()) == Space.OBJECT;
            case TypedFrom fr -> spaceOf(fr.source()) == Space.OBJECT;
            case TypedFilter f -> spaceOf(f.source()) == Space.OBJECT;
            case TypedLimit l -> spaceOf(l.source()) == Space.OBJECT;
            case TypedDrop d -> spaceOf(d.source()) == Space.OBJECT;
            case TypedSlice sl -> spaceOf(sl.source()) == Space.OBJECT;
            case TypedSortBy sb -> spaceOf(sb.source()) == Space.OBJECT;
            case TypedNativeCall c when StoreResolver.isFirstLike(c) ->
                    spaceOf(c.args().get(0)) == Space.OBJECT;
            case TypedNativeCall c when StoreResolver.isStaticAt(c) ->
                    spaceOf(c.args().get(0)) == Space.OBJECT;
            case TypedNativeCall c when StoreResolver.isClassToOne(c) ->
                    spaceOf(c.args().get(0)) == Space.OBJECT;
            case TypedNativeCall c when Pipelines.isClassDistinct(c) ->
                    spaceOf(c.args().get(0)) == Space.OBJECT;
            case TypedNativeCall c when StoreResolver.classSortOf(c) != null ->
                    spaceOf(c.args().get(0)) == Space.OBJECT;
            default -> false;
        };
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
