// SPDX-License-Identifier: Apache-2.0

package com.legend.compiler.spec;

import com.legend.compiler.element.type.PlatformTypes;
import com.legend.compiler.spec.typed.TypedCString;
import com.legend.compiler.spec.typed.TypedNativeCall;
import com.legend.compiler.spec.typed.TypedSpec;

/**
 * Catalog-driven staging of Java-implemented natives (charter §4AG):
 * a call whose catalog entry says {@link PlatformTypes.NativeImpl
 * #JAVA_ROUTINE} evaluates WHERE IT STANDS — exact-FQN lookup,
 * position-blind, no statement silhouettes — and its value re-enters
 * the statement as a typed literal minted HERE (Invariant 7: typed
 * nodes are compiler work). {@code HANDLE}-kind calls stay symbolic;
 * their consumers force them. A routine that cannot serve a shape
 * throws its declared wall and NOTHING here catches it — the failure
 * is charged, loudly, to whichever channel demanded the value.
 */
public final class NativeDispatch {

    private NativeDispatch() {
    }

    /** The executor's half: compute the routine's value for a call.
     * String-valued today (the compiler-output surfaces); widens with
     * the ladder migration. Throws the routine's declared wall. */
    @FunctionalInterface
    public interface Routine {
        String value(TypedNativeCall call,
                java.util.List<TypedSpec> letPrefix);
    }

    /** The executor's plan-object walker ({@code planWalk}) as a staging
     * hook: given a chain whose spine bottoms at an opaque PLAN HANDLE,
     * return its walked host value — or null when the walk does not own
     * the shape (the chain then keeps its ordinary path and walls). */
    @FunctionalInterface
    public interface ChainWalk {
        @com.legend.Nullable Object walk(TypedSpec chain);
    }

    /** Stage one statement: every JAVA_ROUTINE call in it (bottom-up)
     * is replaced by its computed literal. */
    public static TypedSpec stage(TypedSpec stmt,
            java.util.List<TypedSpec> letPrefix,
            java.util.Map<String, Routine> routines) {
        return stage(stmt, letPrefix, routines, null);
    }

    /** Staging with the PLAN-CHAIN arm (wall-exec burn, 2026-09-01):
     * a SCALAR-typed chain over a plan handle ({@code
     * $plan.rootExecutionNode->allNodes(..)->filter(..)->cast(@X).prop})
     * evaluates through the executor's walk where it stands and re-enters
     * as a literal minted HERE — the planToString rule generalized from
     * one whole-call spelling to every walk-ownable chain. Chains the
     * walk declines keep their ordinary path (and its walls). */
    public static TypedSpec stage(TypedSpec stmt,
            java.util.List<TypedSpec> letPrefix,
            java.util.Map<String, Routine> routines,
            @com.legend.Nullable ChainWalk chainWalk) {

        // LAMBDA BODIES: staging descends into them, which is
        // CONSTANT HOISTING and semantics-preserving here — measured
        // 2026-08-31 (LL_STAGE_TRACE census): exactly 3 in-lambda
        // firings estate-wide (testAdjustDateTranslationInMappingAndQuery
        // x2, testSortQuotes x1), all evaluable because inlining
        // substitutes the loop parameter over a literal collection
        // BEFORE staging; a call reading an UNsubstituted parameter
        // fails LOUDLY in its routine (non-literal args rejected) —
        // never silently wrong. THE OBLIGATION stands for consumers
        // that establish their own evaluation context (assertError's
        // catch): those migrate ONLY together with a real staged
        // environment (lambda boundary + parameter scope).
        if (stmt instanceof TypedNativeCall co
                && PlatformTypes.IMPLEMENTATION_KIND.get(
                        co.callee().qualifiedName())
                        == PlatformTypes.NativeImpl.CONTEXT_OWNER) {
            // the call OWNS its arguments' evaluation context
            // (assertError's catch): staging does not enter them — the
            // arm's own evaluation stages them INSIDE that context
            return stmt;
        }
        TypedSpec n = stmt.mapChildren(
                c -> stage(c, letPrefix, routines, chainWalk));
        if (chainWalk != null && scalarChainOverPlanHandle(n)) {
            TypedSpec lit = mintWalked(chainWalk.walk(n), n.info());
            if (lit != null) {
                return lit;
            }
        }
        if (!(n instanceof TypedNativeCall nc)) {
            return n;
        }
        String fqn = nc.callee().qualifiedName();
        if (PlatformTypes.IMPLEMENTATION_KIND.get(fqn)
                != PlatformTypes.NativeImpl.JAVA_ROUTINE) {
            return n;
        }
        Routine r = routines.get(fqn);
        if (r == null) {
            throw new com.legend.error.NotImplementedException(
                    "catalog says '" + fqn + "' is JAVA_ROUTINE but the"
                    + " executor registered no routine for it");
        }
        return new TypedCString(r.value(nc, letPrefix), nc.info());
    }

    /** A SCALAR/scalar-collection-typed chain (not the handle itself)
     * whose source spine bottoms at the opaque plan handle — the shapes
     * the plan-chain arm may evaluate in place. */
    private static boolean scalarChainOverPlanHandle(TypedSpec n) {
        if (!scalarish(n.info().type()) || planHandle(n)) {
            return false;
        }
        TypedSpec cur = n;
        while (true) {
            TypedSpec src = switch (cur) {
                case com.legend.compiler.spec.typed.TypedPropertyAccess pa ->
                        pa.source();
                case com.legend.compiler.spec.typed.TypedCast tc ->
                        tc.source();
                case com.legend.compiler.spec.typed.TypedFilter tf ->
                        tf.source();
                case com.legend.compiler.spec.typed.TypedMap tm ->
                        tm.source();
                case TypedNativeCall c when !c.args().isEmpty() ->
                        c.args().get(0);
                default -> null;
            };
            if (src == null) {
                return false;
            }
            if (planHandle(src)) {
                return true;
            }
            cur = src;
        }
    }

    private static boolean planHandle(TypedSpec n) {
        return n instanceof TypedNativeCall nc
                && (PlatformTypes.EXECUTION_PLAN
                        .equals(nc.callee().qualifiedName())
                    || PlatformTypes.PREVAL
                        .equals(nc.callee().qualifiedName()));
    }

    private static boolean scalarish(
            com.legend.compiler.element.type.Type t) {
        return t == com.legend.compiler.element.type.Type.Primitive.STRING
                || t == com.legend.compiler.element.type.Type.Primitive.INTEGER
                || t == com.legend.compiler.element.type.Type.Primitive.BOOLEAN;
    }

    /** Mint the walked host value as a typed literal; null when the walk
     * declined the shape or produced a value with no literal form. */
    private static @com.legend.Nullable TypedSpec mintWalked(
            @com.legend.Nullable Object v,
            com.legend.compiler.element.type.ExprType info) {
        Object val = v instanceof java.util.List<?> l && l.size() == 1
                ? l.get(0) : v;
        return switch (val) {
            case String s -> new TypedCString(s, info);
            case Boolean b ->
                    new com.legend.compiler.spec.typed.TypedCBoolean(b, info);
            case Integer i ->
                    new com.legend.compiler.spec.typed.TypedCInteger(i, info);
            case Long l ->
                    new com.legend.compiler.spec.typed.TypedCInteger(l, info);
            case java.util.List<?> l
                    when l.stream().allMatch(e -> e instanceof String) ->
                    new com.legend.compiler.spec.typed.TypedCollection(
                            l.stream().<TypedSpec>map(e -> new TypedCString(
                                    (String) e, com.legend.compiler.element
                                            .type.ExprType.one(
                                            com.legend.compiler.element.type
                                                    .Type.Primitive.STRING)))
                                    .toList(), info);
            case null, default -> null;
        };
    }
}
