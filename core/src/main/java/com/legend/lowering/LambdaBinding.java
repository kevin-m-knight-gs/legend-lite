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
        boolean comparator = Scalars.comparatorNative(
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
