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

    /** D3 (StoreResolver.trackedElementClass): a reference to a
     * registry-tracked element is a store anchor — the metaclass extent
     * restricted to the element's key. */
    private final java.util.function.Predicate<
            com.legend.compiler.spec.typed.TypedPackageableRef> elementRef;

    /** A constructed metamodel instance the store carries as rows
     * (ConstructedInstances) anchors exactly like an element reference. */
    private final java.util.function.Predicate<
            com.legend.compiler.spec.typed.TypedNewInstance> constructedRow;

    /** A plan handle whose nodes the store carries as rows (PlanRows)
     * anchors like a constructed instance. */
    private final java.util.function.Predicate<TypedNativeCall> planHandle;

    Anchors(java.util.function.Predicate<
            com.legend.compiler.spec.typed.TypedPackageableRef> elementRef,
            java.util.function.Predicate<
                    com.legend.compiler.spec.typed.TypedNewInstance> constructedRow,
            java.util.function.Predicate<TypedNativeCall> planHandle) {
        this.constructedRow = constructedRow;
        this.elementRef = elementRef;
        this.planHandle = planHandle;
    }

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
                if (c instanceof com.legend.compiler.spec.typed.TypedPackageableRef pr) {
                    // an element reference anchors ONLY as the SOURCE of a
                    // navigation (D3); as an argument (from(mapping, rt),
                    // execute(f, mapping, rt), tableReference(db, …)) it
                    // is a value
                    if (elementRef.test(pr) && navigatesSource(n, c)) {
                        v = true;
                        break;
                    }
                    continue;
                }
                if (c instanceof com.legend.compiler.spec.typed.TypedNewInstance ni
                        && constructedRow.test(ni) && navigatesSource(n, c)) {
                    v = true;
                    break;
                }
                if (c instanceof TypedNativeCall pn && planHandle.test(pn)
                        && navigatesSource(n, c)) {
                    v = true;
                    break;
                }
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

    /** Whether {@code c} sits in {@code n}'s SOURCE position — the object-
     * spine shapes (the same node kinds {@link #objectSpine} walks). */
    private static boolean navigatesSource(TypedSpec n, TypedSpec c) {
        return switch (n) {
            case TypedPropertyAccess pa -> pa.source() == c;
            case TypedMap m -> m.source() == c;
            case TypedFilter f -> f.source() == c;
            case TypedFrom fr -> fr.source() == c;
            case TypedLimit l -> l.source() == c;
            case TypedDrop d -> d.source() == c;
            case TypedSlice sl -> sl.source() == c;
            case TypedSortBy sb -> sb.source() == c;
            case com.legend.compiler.spec.typed.TypedCast tc -> tc.source() == c;
            case TypedNativeCall nc -> !nc.args().isEmpty() && nc.args().get(0) == c
                    && (ClassSorts.isFirstLike(nc) || StoreResolver.isStaticAt(nc)
                            || StoreResolver.isClassToOne(nc)
                            || Pipelines.isClassDistinct(nc)
                            || ClassSorts.classSortOf(nc) != null
                            || nc.callee().qualifiedName().equals(
                                    Substitution.ELEMENT_TO_PATH_FQN));
            default -> false;
        };
    }

    /** The object-space spine rules (formerly StoreResolver.isObjectSpace). */
    private boolean objectSpine(TypedSpec source) {
        return switch (source) {
            case TypedGetAll ignored -> true;
            // an element REFERENCE of a tracked metaclass IS its row (D3)
            case com.legend.compiler.spec.typed.TypedPackageableRef pr
                    when elementRef.test(pr) -> true;
            // a CONSTRUCTED metamodel instance the store carries as rows
            case com.legend.compiler.spec.typed.TypedNewInstance ni
                    when constructedRow.test(ni) -> true;
            // a PLAN HANDLE whose nodes the store carries as rows (PlanRows)
            case TypedNativeCall pn when planHandle.test(pn) -> true;
            // a CLASS-typed property HOP over an object-space chain IS
            // object space (the auto-map flatten re-roots at its target)
            case TypedPropertyAccess pa
                    when pa.info().type() instanceof Type.ClassType ->
                    spaceOf(pa.source()) == Space.OBJECT;
            // ->map with a CLASS-result mapper stays in object space
            case TypedMap m
                    when m.mapper().functionType().result()
                            .type() instanceof Type.ClassType ->
                    spaceOf(m.source()) == Space.OBJECT;
            case TypedFrom fr -> spaceOf(fr.source()) == Space.OBJECT;
            // ->cast(@Sub) in chain position re-types the chain (the
            // total-membership rule, StoreResolver.collectOpChain)
            case com.legend.compiler.spec.typed.TypedCast c
                    when c.target() instanceof Type.ClassType ->
                    spaceOf(c.source()) == Space.OBJECT;
            case TypedFilter f -> spaceOf(f.source()) == Space.OBJECT;
            case TypedLimit l -> spaceOf(l.source()) == Space.OBJECT;
            case TypedDrop d -> spaceOf(d.source()) == Space.OBJECT;
            case TypedSlice sl -> spaceOf(sl.source()) == Space.OBJECT;
            case TypedSortBy sb -> spaceOf(sb.source()) == Space.OBJECT;
            case TypedNativeCall c when ClassSorts.isFirstLike(c) ->
                    spaceOf(c.args().get(0)) == Space.OBJECT;
            case TypedNativeCall c when StoreResolver.isStaticAt(c) ->
                    spaceOf(c.args().get(0)) == Space.OBJECT;
            case TypedNativeCall c when StoreResolver.isClassToOne(c) ->
                    spaceOf(c.args().get(0)) == Space.OBJECT;
            case TypedNativeCall c when Pipelines.isClassDistinct(c) ->
                    spaceOf(c.args().get(0)) == Space.OBJECT;
            case TypedNativeCall c when ClassSorts.classSortOf(c) != null ->
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
