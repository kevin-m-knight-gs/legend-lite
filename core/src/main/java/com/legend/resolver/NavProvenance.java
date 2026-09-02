package com.legend.resolver;

import com.legend.compiler.element.type.ExprType;
import com.legend.compiler.element.type.Type;
import com.legend.compiler.spec.typed.TypedGetAll;
import com.legend.compiler.spec.typed.TypedLambda;
import com.legend.compiler.spec.typed.TypedNavigate;
import com.legend.compiler.spec.typed.TypedSpec;
import com.legend.compiler.spec.typed.TypedVariable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Flatten PROVENANCE (the navigation-depth leg, 2026-09-02): the
 * navigate slots a hop materializes INSIDE its target as tails ride the
 * composed pipeline under composed prefixes; the next flatten of such a
 * head re-roots on them, a leaf path reads them through the AssocSub.
 * Also the inverse case — a slot the composition did NOT carry gets the
 * class's own step spliced onto the composed row. Extracted from
 * StoreResolver (file-size guardrail).
 */
final class NavProvenance {

    private final ClassSources sources;
    private final AssociationJoins assocMaterial;

    NavProvenance(ClassSources sources, AssociationJoins assocMaterial) {
        this.sources = sources;
        this.assocMaterial = assocMaterial;
    }

    /** A navigate-slot hop off a COMPOSED source whose step was stripped
     * inside an earlier hop's target (a to-many slot behind a row-count
     * op is never pre-joined there): the class's OWN step spliced onto
     * the composed row, its left reads re-pointed through the composed
     * prefix — the slot route then serves it as usual. Null when the hop
     * is not one of the class's navigate slots. */
    @com.legend.Nullable ClassSource spliceOwnStep(ClassSource src, String hop) {
        if (src.composedPrefix().isEmpty()) {
            return null;
        }
        ClassSource own = src.setId() != null
                ? sources.get(src.mappingFqn(), src.classFqn(), src.setId(), null, "")
                : sources.get(src.mappingFqn(), src.classFqn());
        var ownSteps = Pipelines.navSteps(own.pipeline());
        TypedSpec ob = own.bindings().get(hop);
        String oa = ob == null ? null
                : InnerDemand.navSlotAlias(ob, own.rowVar(), ownSteps.keySet());
        if (oa == null) {
            return null;
        }
        TypedNavigate st = java.util.Objects.requireNonNull(ownSteps.get(oa));
        TypedLambda pred = st.predicate();
        String lp = pred.parameters().get(0);
        Type.RelationType composedRow = src.rowType();
        TypedSpec body = Pipelines.prefixColumns(
                pred.body().get(pred.body().size() - 1), lp, src.composedPrefix(),
                v -> new TypedVariable(lp, new ExprType(composedRow,
                        com.legend.compiler.element.type.Multiplicity.Bounded.ONE)));
        TypedNavigate st2 = new TypedNavigate(src.pipeline(), st.alias(), st.target(),
                new TypedLambda(pred.parameters(), List.of(body), pred.info()),
                st.pairedPredicate(), st.frameName(), st.form(), src.pipeline().info());
        return new ClassSource(src.mappingFqn(), src.classFqn(), src.setId(), st2,
                src.rowVar(), src.bindings(), src.rowType(), src.sourceClass(),
                src.deferredWalls(), src.composedPrefix());
    }

    /** Flatten PROVENANCE for a navigate slot materialized INSIDE a hop
     * (as a tail): the slot's target rows ride the composed pipeline
     * under {@code outerPrefix + sub.prefix()}; the next flatten of that
     * head re-roots on them (flattenMaterializedNav), a leaf path reads
     * them through the AssocSub, and the sub's own materialized slots
     * ride along as SubNav children. The class is the navigate STEP's
     * target (a routed set's class), not the property's declared type. */
    void register(Map<String, Substitution.AssocSub> provOut,
            String head, String outerPrefix, Substitution.SubNav sub,
            ClassSource owner) {
        String navClass = navStepTargetClass(owner, head);
        if (navClass == null) {
            return;
        }
        ClassSource navSrc = sources.get(owner.mappingFqn(), navClass);
        provOut.putIfAbsent(head, new Substitution.AssocSub(
                outerPrefix + sub.prefix(), sub.rowVar(), sub.bindings(),
                navClass, Pipelines.slotAliases(navSrc.pipeline()),
                Map.of(), null, null, Map.of(),
                rebaseSubNavs(sub.children(), sub.prefix())));
    }

    /** A SubNav tree's prefixes are composed relative to the ROOT target
     * of its materialization (NavMaterializer); re-rooting the tree under
     * one of its nodes strips that node's prefix from every descendant
     * (the ancestor prefix is a documented invariant of the tree, so
     * this is structural, not string surgery). */
    private static Map<String, Substitution.SubNav> rebaseSubNavs(
            Map<String, Substitution.SubNav> kids, String ancestorPrefix) {
        if (kids.isEmpty()) {
            return kids;
        }
        Map<String, Substitution.SubNav> out = new LinkedHashMap<>();
        for (var e : kids.entrySet()) {
            Substitution.SubNav k = e.getValue();
            if (!k.prefix().startsWith(ancestorPrefix)) {
                throw new IllegalStateException("resolver bug: sub-nav '"
                        + e.getKey() + "' prefix '" + k.prefix()
                        + "' does not extend its ancestor's '" + ancestorPrefix + "'");
            }
            out.put(e.getKey(), new Substitution.SubNav(
                    k.prefix().substring(ancestorPrefix.length()), k.rowVar(),
                    k.bindings(), rebaseSubNavs(k.children(), ancestorPrefix)));
        }
        return out;
    }

    /** The class a navigate-slot head of {@code owner} lands on: the
     * step's own getAll target (a routed set's class), else the declared
     * property / association-end type. */
    @com.legend.Nullable String navStepTargetClass(ClassSource owner,
            String head) {
        TypedSpec b = owner.bindings().get(SyntheticHeads.realHead(head));
        var steps = Pipelines.navSteps(owner.pipeline());
        String alias = b == null ? null
                : InnerDemand.navSlotAlias(b, owner.rowVar(), steps.keySet());
        TypedNavigate step = alias == null ? null : steps.get(alias);
        if (step != null && step.target() instanceof TypedGetAll tg) {
            return tg.classFqn();
        }
        return assocMaterial.hopTargetClass(owner.classFqn(), head);
    }

}
