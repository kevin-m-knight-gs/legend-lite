// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.harness;

import com.legend.model.spec.AppliedFunction;
import com.legend.model.spec.KeyExpression;
import com.legend.model.spec.NewInstance;
import com.legend.model.spec.ValueSpecification;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The connection-equality contract (engine storeContract.pure:55-65),
 * HOST-evaluated: {@code runRelationalRouterExtensionConnectionEquality
 * (c1, c2)} compares two {@code ^RelationalDatabaseConnection} literals
 * structurally — {@code type}, {@code timeZone}, {@code
 * quoteIdentifiers}, {@code datasourceSpecification} (deep instance
 * equality), {@code authenticationStrategy}
 * (compareObjectsWithPossiblyNoProperties: both-absent is equal) and
 * {@code postProcessors} — while the {@code element} (store name) is
 * excluded by the engine's own rule. The tests never execute SQL; the
 * routed extension machinery (routerExtensions &rarr; match over
 * eval'd lambdas) exists only to DISPATCH to this comparison, so the
 * harness owns the terminal rule directly.
 *
 * <p>{@link #tryEval} returns null when the expression is not this
 * call — the assert falls through to the ordinary pipeline.
 */
final class ConnEquality {

    private ConnEquality() {
    }

    static @com.legend.Nullable Boolean tryEval(
            @com.legend.Nullable ValueSpecification v) {
        boolean negate = false;
        if (v instanceof AppliedFunction nf
                && TestBody.simpleName(nf.function()).equals("not")
                && nf.parameters().size() == 1) {
            v = nf.parameters().get(0);
            negate = true;
        }
        if (!(v instanceof AppliedFunction af
                && TestBody.simpleName(af.function()).equals(
                        "runRelationalRouterExtensionConnectionEquality")
                && af.parameters().size() == 2)) {
            return null;
        }
        boolean eq = structEquals(af.parameters().get(0),
                af.parameters().get(1));
        return negate ? !eq : eq;
    }

    /** Deep instance equality over the SUBSTITUTED literals: instances
     * compare per-key (union of keys; a key absent on both sides is
     * equal — the engine's no-properties tolerance), {@code element}
     * never compares; everything else rides record equality. */
    private static boolean structEquals(
            @com.legend.Nullable ValueSpecification a,
            @com.legend.Nullable ValueSpecification b) {
        if (a instanceof NewInstance na && b instanceof NewInstance nb) {
            if (!na.className().equals(nb.className())) {
                return false;
            }
            Set<String> keys = new LinkedHashSet<>(na.properties().keySet());
            keys.addAll(nb.properties().keySet());
            keys.remove("element");
            for (String k : keys) {
                KeyExpression ka = na.properties().get(k);
                KeyExpression kb = nb.properties().get(k);
                if (!structEquals(ka == null ? null : ka.value(),
                        kb == null ? null : kb.value())) {
                    return false;
                }
            }
            return true;
        }
        return java.util.Objects.equals(a, b);
    }
}
