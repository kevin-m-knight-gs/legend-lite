// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.lowering;

import com.legend.compiler.spec.typed.TypedLambda;
import com.legend.compiler.spec.typed.TypedNativeCall;
import com.legend.compiler.spec.typed.TypedSpec;
import com.legend.lowering.Resolvers.ColumnResolver;
import com.legend.sql.SqlExpr;
import com.legend.sql.SqlType;
import com.legend.sql.TypeFact;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;

/**
 * Lambda-parameter scope binding at the LOWERING boundary
 * (CodeShapeGuardrail seam split from {@link Lowerer}, M4 re-land).
 *
 * <p>{@link #lowerNativeArgs} is the M4 capability that replaced the
 * parked branch's LambdaWire ThreadLocal (compensation #1): the
 * UNARY-LAMBDA BINDING CONVENTION. A one-parameter lambda argument of
 * a value-lane native ranges over the nearest PRECEDING collection
 * argument, so its body lowers with the parameter column STAMPED as
 * that collection's element — the body's own dispatch (pureToString's
 * Any arm, equality emission) then reads the carrier from the tree at
 * construction, never from a side channel (witness
 * testPctRemoveDuplicatesBy: a toString key over a LITERAL-carried
 * mix must print spellings, not variant-extract them). Comparator
 * lambdas bind at their consumer sites (Dedup — §3.2); fold's
 * two-parameter lambda (element, ACCUMULATOR) never matches the unary
 * rule; a lambda with no preceding definite-array argument lowers
 * unstamped exactly as before.
 */
final class LambdaBinding {

    private LambdaBinding() {
    }

    /** The COMPARATOR-CONVENTION natives (M4 §3.2 + post-landing
     * audit): a two-parameter lambda whose params BOTH stamp as the
     * function's ONE list's element, so comparator BODIES lower
     * element-stamped (dispatch is frozen at construction — witness
     * the NonStandardFunction toString comparators). ROSTER = the
     * exhaustive same-element (T,T)->_ signature sweep of both oracle
     * trees (2026-08-25): removeDuplicates, sort, and contains — the
     * audit's found regression. contains' convention is
     * eval($value, $x) — needle FIRST — and its param-0 element
     * stamp is honest ONLY because the rule substitutes a SPELLED,
     * LITERAL-marked needle there (MixedEncoding.markedNeedle;
     * witness ComparatorConventionTest incl. the kind-honest eq pin
     * that caught the raw-needle text collision). Keys are signature
     * keys, the rule table's own dispatch identity. NOT here: fold
     * (second param = ACCUMULATOR); relation join/asOfJoin (their
     * lambdas span TWO relations); removeAll (engine-core pure
     * composition, never lowered); and min/max — their comparator is
     * STRUCTURALLY RECOGNIZED at the rule (Comparators pattern-match,
     * the body never lowers as a body), and stamping its params broke
     * the recognizer's structural equality (gate-caught: G9
     * chB-std testMax/testMin, 'must apply the SAME key'). */
    private static final java.util.Set<String> COMPARATOR_NATIVES =
            java.util.stream.Stream.of("removeDuplicates", "sort",
                            "contains")
                    .flatMap(nm -> com.legend.builtin.Pure
                            .nativeKeysAt(nm).stream())
                    .collect(java.util.stream.Collectors
                            .toUnmodifiableSet());

    /** Inner-lambda scope: ALL its parameters shadow; everything else
     * resolves outward through the enclosing resolver. */
    static ColumnResolver lambdaResolver(
            List<String> params, ColumnResolver outer) {
        return (var, prop) -> {
            if (var == null || !params.contains(var)) {
                return outer.resolve(var, prop);
            }
            return prop == null ? new SqlExpr.Column(null, var)
                    : new SqlExpr.Column(var, prop);
        };
    }

    /** Native-call argument lowering under the unary-lambda binding
     * convention (see class doc). {@code scalarFn} is the enclosing
     * Lowerer's scalar lane. */
    static List<SqlExpr> lowerNativeArgs(TypedNativeCall n,
            ColumnResolver columns,
            BiFunction<TypedSpec, ColumnResolver, SqlExpr> scalarFn) {
        // dispatch identity is the SIGNATURE KEY (the same key the
        // Scalars rule table uses — audit 22a: never the bare FQN)
        boolean comparator = COMPARATOR_NATIVES.contains(
                n.callee().signatureKey());
        List<SqlExpr> out = new ArrayList<>(n.args().size());
        for (TypedSpec a : n.args()) {
            SqlExpr coll;
            if (a instanceof TypedLambda l
                    && (l.parameters().size() == 1
                            || (comparator && l.parameters().size() == 2))
                    && (coll = lastDefiniteArray(out)) != null) {
                // unary: the param IS the element; comparator natives
                // (§3.2): BOTH params are elements of the ONE list
                List<String> ps = l.parameters();
                ColumnResolver inner = lambdaResolver(ps, columns);
                ColumnResolver stamped = (var, prop) ->
                        var != null && ps.contains(var) && prop == null
                                ? SqlExpr.Column.param(var, coll)
                                : inner.resolve(var, prop);
                out.add(new SqlExpr.Lambda(ps,
                        scalarFn.apply(Lowerer.last(l), stamped)));
                continue;
            }
            out.add(scalarFn.apply(a, columns));
        }
        return out;
    }

    private static @com.legend.Nullable SqlExpr lastDefiniteArray(
            List<SqlExpr> lowered) {
        for (int i = lowered.size() - 1; i >= 0; i--) {
            if (lowered.get(i).type() instanceof TypeFact.Typed t
                    && t.type() instanceof SqlType.Array) {
                return lowered.get(i);
            }
        }
        return null;
    }
}
