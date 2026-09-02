// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.resolver;

import com.legend.builtin.Pure;
import com.legend.compiler.element.ModelContext;
import com.legend.compiler.element.TypedFunction;
import com.legend.compiler.element.type.ExprType;
import com.legend.compiler.element.type.Multiplicity;
import com.legend.compiler.element.type.Type;
import com.legend.compiler.spec.typed.TypedCString;
import com.legend.compiler.spec.typed.TypedCast;
import com.legend.compiler.spec.typed.TypedFilter;
import com.legend.compiler.spec.typed.TypedNativeCall;
import com.legend.compiler.spec.typed.TypedPackageableRef;
import com.legend.compiler.spec.typed.TypedPropertyAccess;
import com.legend.compiler.spec.typed.TypedSortBy;
import com.legend.compiler.spec.typed.TypedSpec;

import java.util.List;
import java.util.function.Function;

/**
 * Chain-shape normalizations the resolver applies BEFORE object-space
 * resolution (group F burn, 2026-09-02) — two pure rewrites the engine's
 * own mapping-metamodel bodies need:
 *
 * <ul>
 *   <li><b>{@code last()} over a sorted chain</b> is {@code first()} over
 *   the same chain sorted the other way: the nearest {@code sortBy} below
 *   (through the row-preserving filter / cast wrappers) flips direction and
 *   the call becomes the first-like row-count op the pipeline already
 *   lowers ({@code LIMIT 1}). A {@code last()} with no sort beneath stays
 *   as written — the resolver's loud wall, never an arbitrary row.</li>
 *   <li><b>Element identity equality</b>: {@code $x.cls == SomeElement}
 *   where the property is class-typed (a row) and the other side is a
 *   REFERENCE to a tracked, system-mapped element (D3) compares the D2
 *   identities — the row's primary-key pseudo-binding read against the
 *   reference's path literal ({@code elementToPath} on both sides, folded
 *   here). Pure's instance equality over metamodel elements IS identity.</li>
 * </ul>
 */
final class ChainNormalizer {

    private ChainNormalizer() {
    }

    private static final String LAST_FQN = "meta::pure::functions::collection::last";

    static TypedSpec normalize(TypedSpec n, ModelContext ctx,
            Function<TypedPackageableRef, java.util.Optional<String>> trackedElementClass) {
        TypedSpec r = n.mapChildren(c -> normalize(c, ctx, trackedElementClass));
        if (r instanceof TypedNativeCall c && c.args().size() == 1
                && LAST_FQN.equals(c.callee().qualifiedName())) {
            TypedSpec flipped = flipNearestSort(c.args().get(0));
            if (flipped != null) {
                return new TypedNativeCall(oneArgCallee(ctx,
                        "meta::pure::functions::collection::first"),
                        List.of(flipped), c.info());
            }
        }
        if (r instanceof TypedNativeCall c && c.args().size() == 2
                && Pure.nativeNamed("equal", c.callee().signatureKey())) {
            TypedSpec a = c.args().get(0);
            TypedSpec b = c.args().get(1);
            TypedSpec rw = identityEquality(c, a, b, ctx, trackedElementClass);
            if (rw == null) {
                rw = identityEquality(c, b, a, ctx, trackedElementClass);
            }
            if (rw != null) {
                return rw;
            }
        }
        return r;
    }

    /** {@code n} with its nearest sortBy (through filter / cast) reversed;
     * null when no sort is beneath. */
    private static @com.legend.Nullable TypedSpec flipNearestSort(TypedSpec n) {
        return switch (n) {
            case TypedSortBy sb -> new TypedSortBy(sb.source(), sb.key(),
                    !sb.ascending(), sb.keyAlias(), sb.info());
            case TypedFilter f -> {
                TypedSpec inner = flipNearestSort(f.source());
                yield inner == null ? null
                        : new TypedFilter(inner, f.predicate(), f.info());
            }
            case TypedCast tc -> {
                TypedSpec inner = flipNearestSort(tc.source());
                yield inner == null ? null : tc.withChildren(List.of(inner));
            }
            // a navigation preserves its source's order (pure map/flatten)
            case TypedPropertyAccess pa -> {
                TypedSpec inner = flipNearestSort(pa.source());
                yield inner == null ? null : pa.withChildren(List.of(inner));
            }
            default -> null;
        };
    }

    /** {@code equal(<class-typed row read>, <tracked element reference>)}
     * as key-equality; null when the pair is not that shape. */
    private static @com.legend.Nullable TypedSpec identityEquality(TypedNativeCall eq,
            TypedSpec row, TypedSpec ref, ModelContext ctx,
            Function<TypedPackageableRef, java.util.Optional<String>> trackedElementClass) {
        if (!(ref instanceof TypedPackageableRef pr)
                || !(row instanceof TypedPropertyAccess pa)
                || !(pa.info().type() instanceof Type.ClassType rowCls)) {
            return null;
        }
        String refCls = trackedElementClass.apply(pr).orElse(null);
        // a CLASS reference is typed Class<X> (Typer.classReference); its
        // element is a Class row when the Class metaclass is seeded
        if (refCls == null && pr.info().type() instanceof Type.GenericType g
                && g.rawFqn().equals(Pure.CLASS.qualifiedName())
                && ctx.classifierInstances(Pure.CLASS.qualifiedName()) != null) {
            refCls = Pure.CLASS.qualifiedName();
        }
        if (refCls == null || !(ctx.isSubtype(refCls, rowCls.fqn())
                || ctx.isSubtype(rowCls.fqn(), refCls))) {
            return null;
        }
        // the navigation's FOREIGN-KEY IDENTITY pseudo-binding (registered
        // by ClassSources when the slot's join is one equality on the
        // target's key) — a plain row read, so it resolves in every scope;
        // an unregisterable slot stays the loud "not mapped" wall
        ExprType str = new ExprType(Type.Primitive.STRING, Multiplicity.Bounded.ONE);
        TypedSpec keyRead = new TypedPropertyAccess(pa.source(),
                com.legend.model.ClassMapping.foreignKeyBinding(pa.property()), str);
        return new TypedNativeCall(eq.callee(),
                List.of(keyRead, new TypedCString(pr.fullPath(), str)), eq.info());
    }

    /** The one-parameter registration of a collection native by FQN. */
    static TypedFunction oneArgCallee(ModelContext ctx, String fqn) {
        var fns = ctx.findFunction(fqn).stream()
                .filter(f -> f.parameters().size() == 1).toList();
        if (fns.size() != 1) {
            throw new IllegalStateException("resolver bug: expected one 1-arg " + fqn);
        }
        return fns.get(0);
    }
}
