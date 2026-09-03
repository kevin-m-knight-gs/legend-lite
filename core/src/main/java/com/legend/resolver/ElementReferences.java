// SPDX-License-Identifier: Apache-2.0

package com.legend.resolver;

import com.legend.compiler.element.ModelContext;
import com.legend.compiler.element.TypedFunction;
import com.legend.compiler.element.type.ExprType;
import com.legend.compiler.element.type.Multiplicity;
import com.legend.compiler.element.type.Type;
import com.legend.compiler.spec.typed.TypedCString;
import com.legend.compiler.spec.typed.TypedFilter;
import com.legend.compiler.spec.typed.TypedGetAll;
import com.legend.compiler.spec.typed.TypedLambda;
import com.legend.compiler.spec.typed.TypedNativeCall;
import com.legend.compiler.spec.typed.TypedPackageableRef;
import com.legend.compiler.spec.typed.TypedPropertyAccess;
import com.legend.compiler.spec.typed.TypedSpec;
import com.legend.compiler.spec.typed.TypedVariable;
import com.legend.error.MappingResolutionException;
import com.legend.error.NotImplementedException;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Supplier;

/**
 * Metamodel-as-relations, resolver side (step 3, 2026-09-02).
 *
 * <p><b>D3 — ELEMENT REFERENCE = ROW.</b> A reference to a registry-tracked,
 * system-mapped element ({@code B1Mapping}, typed as its metaclass) IS that
 * metaclass's extent restricted to the element's primary key (the D2
 * identity: the FQN), so navigations off a named element are ordinary
 * store reads. The restriction is an OBJECT-SPACE filter over the
 * primary-key pseudo-binding ({@code ClassMapping.primaryKeyBinding}), so
 * it rides every position a class filter rides. A bare reference (an
 * argument, a let) stays a value.
 *
 * <p><b>Chain-position casts.</b> {@code ->cast(@Sub)} over a chain is a
 * re-typing when the mapping PROVES every row conforms: the navigation is
 * ROUTED to one member set whose class conforms ({@code prop[setId]: @J}),
 * or the class's Operation extent (a union's declared members, an
 * inheritance op's mapped subclasses) is all conforming. Anything partial
 * stays loud (the witness-gated chain cast is not built).
 */
final class ElementReferences {

    private final ModelContext ctx;
    private final ClassSources sources;
    private final BiFunction<StoreResolver.Context, String, String> dispatch;
    private final Supplier<@com.legend.Nullable TypedFunction> equalCallee;

    ElementReferences(ModelContext ctx, ClassSources sources,
            BiFunction<StoreResolver.Context, String, String> dispatch,
            Supplier<@com.legend.Nullable TypedFunction> equalCallee) {
        this.ctx = ctx;
        this.sources = sources;
        this.dispatch = dispatch;
        this.equalCallee = equalCallee;
    }

    /** The metaclass FQN when {@code pr} references a tracked, system-mapped
     * element (a seeded extent AND a row: a Database reference is a value
     * today — no rows); else null. */
    @com.legend.Nullable String trackedElementClass(TypedPackageableRef pr) {
        return pr.info().type() instanceof Type.ClassType ct
                && ctx.classifierInstances(ct.fqn()) != null
                && sources.binds(com.legend.builtin.SystemMetamodel.MAPPING_FQN,
                        ct.fqn()) ? ct.fqn() : null;
    }

    /** The element's row as an object-space chain head. A composite key
     * has no element spelling — loud. */
    TypedSpec elementRow(TypedPackageableRef pr, String classFqn,
            StoreResolver.Context context, Supplier<String> freshVar) {
        return elementRowByKey(pr.fullPath(), classFqn, context, freshVar);
    }

    /** {@code classFqn}'s extent restricted to the row keyed {@code key}
     * (an element's path, a constructed instance's content id). */
    TypedSpec elementRowByKey(String key, String classFqn,
            StoreResolver.Context context, Supplier<String> freshVar) {
        String mappingFqn = dispatch.apply(context, classFqn);
        List<String> pk = new ArrayList<>();
        var md = ctx.findMapping(mappingFqn).orElse(null);
        if (md != null) {
            for (var cb : md.classBindingsWithIncludes(ctx::findMapping)) {
                if (cb.classFqn().equals(classFqn)) {
                    pk = cb.primaryKeyColumns();
                    break;
                }
            }
        }
        if (pk.size() != 1) {
            throw new NotImplementedException("element reference '"
                    + key + "': the metaclass row of " + classFqn
                    + " keys on " + pk + " — one FQN key column is required");
        }
        var one = Multiplicity.Bounded.ONE;
        Type.ClassType ct = new Type.ClassType(classFqn);
        TypedGetAll all = new TypedGetAll(classFqn, List.of(), false, false,
                new ExprType(ct, Multiplicity.Bounded.ZERO_MANY));
        String v = freshVar.get();
        TypedSpec keyRead = new TypedPropertyAccess(
                new TypedVariable(v, new ExprType(ct, one)),
                com.legend.model.ClassMapping.primaryKeyBinding(pk.get(0)),
                new ExprType(Type.Primitive.STRING, one));
        TypedSpec pred = new TypedNativeCall(java.util.Objects.requireNonNull(
                equalCallee.get(), "resolver bug: no equal registration"),
                List.of(keyRead, new TypedCString(key,
                        new ExprType(Type.Primitive.STRING, one))),
                new ExprType(Type.Primitive.BOOLEAN, one));
        var fn = new Type.FunctionType(List.of(new Type.Param(ct, one)),
                new Type.Param(Type.Primitive.BOOLEAN, one));
        return new TypedFilter(all, new TypedLambda(List.of(v), List.of(pred),
                new ExprType(fn, one)), all.info());
    }

    /** A navigation ROUTED to one member set lands on that set's class:
     * total when it conforms to {@code target}. False when the source is
     * not a routed class-typed hop (the extent rule decides then). */
    boolean castTotalByRoute(StoreResolver.Context context, TypedSpec source,
            String target) {
        if (!(source instanceof TypedPropertyAccess hp)
                || !(hp.source().info().type() instanceof Type.ClassType oc)) {
            return false;
        }
        String mappingFqn;
        try {
            mappingFqn = dispatch.apply(context, oc.fqn());
        } catch (MappingResolutionException e) {
            return false;
        }
        String routed = ctx.routedTargetClass(mappingFqn, oc.fqn(), hp.property());
        return routed != null && ctx.isSubtype(routed, target);
    }

    /** Whether every row of {@code srcClass}'s extent in the context's
     * mapping conforms to {@code target}: a UNION op's declared members,
     * else an INHERITANCE op's mapped subclasses (includes closed). */
    boolean totalMembershipCast(StoreResolver.Context context, String srcClass,
            String target) {
        if (!ctx.isSubtype(target, srcClass)) {
            return false;
        }
        String mappingFqn;
        try {
            mappingFqn = dispatch.apply(context, srcClass);
        } catch (MappingResolutionException e) {
            return false;
        }
        List<String> unionMembers = ctx.unionMemberClasses(mappingFqn, srcClass);
        if (unionMembers != null) {
            for (String m : unionMembers) {
                if (!ctx.isSubtype(m, target)) {
                    return false;
                }
            }
            return !unionMembers.isEmpty();
        }
        var md = ctx.findMapping(mappingFqn).orElse(null);
        if (md == null) {
            return false;
        }
        boolean any = false;
        for (var cb : md.classBindingsWithIncludes(ctx::findMapping)) {
            if (cb.classFqn().equals(srcClass)
                    || !ctx.isSubtype(cb.classFqn(), srcClass)) {
                continue;
            }
            any = true;
            if (!ctx.isSubtype(cb.classFqn(), target)) {
                return false;
            }
        }
        return any;
    }
    /** "intrinsic" = bound in the SYSTEM mapping (the registry's extents
     * are a subset: every seeded metaclass is mapped there, and so are
     * the metaclasses reached by navigation — SetImplementation, Table —
     * whose rows the seed derives). */
    boolean intrinsicClass(String classFqn) {
        if (ctx.classifierInstances(classFqn) != null || sources.binds(
                com.legend.builtin.SystemMetamodel.MAPPING_FQN, classFqn)) {
            return true;
        }
        // an ABSTRACT metaclass between an inheritance op and its bound
        // member (PropertyMappingsImplementation): its extent is its
        // bound subclasses' — the same store
        var md = ctx.findMapping(com.legend.builtin.SystemMetamodel.MAPPING_FQN)
                .orElse(null);
        if (md == null || ctx.findClass(classFqn).isEmpty()) {
            return false;
        }
        for (var cb : md.classBindings()) {
            if (!cb.classFqn().equals(classFqn)
                    && ctx.isSubtype(cb.classFqn(), classFqn)) {
                return true;
            }
        }
        return false;
    }


    /** A chain ROOT the store carries as rows: the re-rooted head and the
     * context it resolves under. */
    record RootRow(TypedSpec row, StoreResolver.Context context) {
    }

    /**
     * The row-root arms of the chain walk (StoreResolver.collectOpChain):
     * an ELEMENT REFERENCE (D3 — the metaclass extent keyed by FQN), a
     * PLAN HANDLE (PlanRows under the handle's content id), a FUNCTION
     * VALUE's body ($f.expressionSequence over a lambda — FunctionBodyRows
     * under the lambda's scope, registered on first meeting) and a
     * CONSTRUCTED instance (the tree's scope). Null when {@code cur} is
     * none of them.
     */
    @com.legend.Nullable RootRow rowRoot(TypedSpec cur, StoreResolver.Context context,
            ConstructedInstances constructed,
            java.util.function.Predicate<TypedNativeCall> planHandle,
            Supplier<String> freshVar) {
        if (cur instanceof TypedPackageableRef pr && trackedElementClass(pr) != null) {
            return new RootRow(elementRow(pr, java.util.Objects.requireNonNull(
                    trackedElementClass(pr)), context, freshVar), context);
        }
        if (cur instanceof TypedNativeCall pn && planHandle.test(pn)) {
            String scope = com.legend.plan.PlanRows.scopeId(pn);
            StoreResolver.Context inner = context.withConstructedScope(scope);
            return new RootRow(elementRowByKey(scope, java.util.Objects.requireNonNull(
                    com.legend.compiler.element.type.PlatformTypes.handleRowClass(pn.callee().qualifiedName(), pn.callee().returnType())), inner, freshVar), inner);
        }
        if (cur instanceof TypedLambda flam) {
            String scope = FunctionBodyRows.scopeId(flam);
            if (!constructed.has(scope)) {
                constructed.register(scope, FunctionBodyRows.rows(scope, flam, ctx));
            }
            StoreResolver.Context inner = context.withConstructedScope(scope);
            return new RootRow(elementRowByKey(scope,
                    "meta::pure::metamodel::function::FunctionDefinition", inner,
                    freshVar), inner);
        }
        if (cur instanceof com.legend.compiler.spec.typed.TypedNewInstance cni
                && constructed.rowId(cni) != null) {
            String scope = java.util.Objects.requireNonNull(constructed.rowId(cni));
            StoreResolver.Context inner = context.withConstructedScope(scope);
            return new RootRow(elementRowByKey(scope, cni.classFqn(), inner, freshVar),
                    inner);
        }
        return null;
    }
}
