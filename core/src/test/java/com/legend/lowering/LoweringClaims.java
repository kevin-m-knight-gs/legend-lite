// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.lowering;

import java.util.Set;

/** The four catalog-keyed lowering registries, read as CLAIMS
 *  ({@code com.legend.claims.Claims}, TEST scope — this helper lives in the test tree of the same package to read the package-private registries without widening main): every key is "this overload is lowered
 *  here". The registries themselves stay package-private; this is the one
 *  public face, and it is read-only. */
public final class LoweringClaims {

    private LoweringClaims() {
    }

    /** {@code Scalars.RULES} keys — SQL expression rules. */
    public static Set<String> scalarRuleKeys() {
        return Scalars.ruleKeys();
    }

    /** {@code Aggregates.REDUCERS} keys — SQL aggregates. */
    public static Set<String> reducerKeys() {
        return Aggregates.reducerKeys();
    }

    /** {@code Windows.FNS} keys — window functions. */
    public static Set<String> windowFnKeys() {
        return Windows.fnKeys();
    }

    /** {@code Windows.AGGREGATES} keys — window-only aggregates. */
    public static Set<String> windowAggregateKeys() {
        return Windows.aggregateKeys();
    }
}
