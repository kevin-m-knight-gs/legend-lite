// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.harness;

import com.legend.compiler.element.ModelContext;
import com.legend.model.ImportScope;
import com.legend.protocol.spec.AppliedFunction;
import com.legend.protocol.spec.CBoolean;
import com.legend.protocol.spec.CInteger;
import com.legend.protocol.spec.CString;
import com.legend.protocol.spec.LambdaFunction;
import com.legend.protocol.spec.Variable;
import com.legend.protocol.spec.ValueSpecification;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** ASSERT LOOP over already-materialised values (adjudication ledger
 * cluster 28): {@code $result.values.x->map(f|assert(...))} is an
 * assertion loop, not a query — evaluate the SOURCE host-side, lift
 * each value to a literal, and push the substituted asserts through
 * the normal checkAssert path. A non-liftable element falls through
 * to the loud pipeline wall — never a skipped element (the
 * vacuous-pass failure mode this router already suffered once). */
final class AssertLoopForm {

    private AssertLoopForm() {
    }

    static boolean consume(AppliedFunction mapAf,
            java.util.Deque<ValueSpecification> work,
            Map<String, ValueSpecification> lets,
            List<ValueSpecification> execStmts, java.util.Set<String> execVars,
            Map<String, ValueSpecification> execChains, ModelContext ctx,
            ImportScope imports, String runtimeFqn, Connection conn)
            throws java.sql.SQLException {
        if (!EngineTestExecutor.simpleName(mapAf.function()).equals("map")
                || mapAf.parameters().size() != 2
                || !(mapAf.parameters().get(1) instanceof LambdaFunction al)
                || al.parameters().size() != 1
                || al.body().isEmpty()
                || !al.body().stream().allMatch(b ->
                        b instanceof AppliedFunction bf
                                && EngineTestExecutor.simpleName(bf.function())
                                        .startsWith("assert"))) {
            return false;
        }
        EngineTestExecutor.Eval src = EngineTestExecutor.eval(
                mapAf.parameters().get(0), lets, execStmts, execVars,
                execChains, ctx, imports, runtimeFqn, conn);
        List<ValueSpecification> lifted = new ArrayList<>();
        for (Object v : src.values()) {
            ValueSpecification lit = switch (v) {
                case String sv -> new CString(sv);
                case Long lv -> new CInteger(lv);
                case Integer iv -> new CInteger(iv.longValue());
                case Double dv ->
                        new com.legend.protocol.spec.CFloat(dv, null);
                case Boolean bv -> new CBoolean(bv);
                default -> null;
            };
            if (lit == null) {
                return false;   // non-liftable: the loud wall owns it
            }
            lifted.add(lit);
        }
        String pv = al.parameters().get(0).name();
        List<ValueSpecification> pushed = new ArrayList<>();
        for (ValueSpecification lit : lifted) {
            for (ValueSpecification b : al.body()) {
                pushed.add(EngineTestExecutor.subst(b, Map.of(pv, lit)));
            }
        }
        for (int i = pushed.size() - 1; i >= 0; i--) {
            work.addFirst(pushed.get(i));
        }
        return true;
    }

    /** The {@code $exp->forAll(e|$act->contains($e))} SUBSET shape:
     * returns {expected, actual} expressions, or null when the arg is
     * not this idiom (the predicate must be a contains of the forAll
     * binder itself). */
    static ValueSpecification @com.legend.Nullable [] forAllContains(
            ValueSpecification a0) {
        if (a0 instanceof AppliedFunction fa
                && EngineTestExecutor.simpleName(fa.function()).equals("forAll")
                && fa.parameters().size() == 2
                && fa.parameters().get(1) instanceof LambdaFunction lam
                && lam.parameters().size() == 1
                && lam.body().size() == 1
                && lam.body().get(0) instanceof AppliedFunction cont
                && EngineTestExecutor.simpleName(cont.function()).equals("contains")
                && cont.parameters().size() == 2
                && cont.parameters().get(1) instanceof Variable ev
                && ev.name().equals(lam.parameters().get(0).name())) {
            return new ValueSpecification[] {
                    fa.parameters().get(0), cont.parameters().get(0)};
        }
        return null;
    }

}
