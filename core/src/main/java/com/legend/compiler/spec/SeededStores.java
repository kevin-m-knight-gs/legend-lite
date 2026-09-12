// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0
package com.legend.compiler.spec;

import com.legend.builtin.Subsumed;
import com.legend.compiler.element.type.PlatformTypes;
import com.legend.compiler.spec.typed.TypedNativeCall;
import com.legend.compiler.spec.typed.TypedPackageableRef;
import com.legend.compiler.spec.typed.TypedSpec;
import com.legend.compiler.spec.typed.TypedUserCall;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * The STORES a typed program seeds, transitively through user calls — a
 * fact the platform states about a program (beside
 * {@code StatementExecutor.containsEffect}, the same walk), read by the
 * corpus runner's fixture-on-demand: the engine's suite runs a package's
 * BeforePackage setups only for that package's tests, so a golden whose
 * mapping reads another package's store was judged by text; with this
 * fact the runner finds the setup that seeds the store and the rows leg
 * judges rows (corpus-zero program, 2026-09-12).
 *
 * <p>A store is named ONLY by a typed element reference the program
 * passes to a K-native: the Database argument of
 * {@code dropAndCreateTableInDb} ({@link
 * PlatformTypes#DROP_AND_CREATE_TABLE_IN_DB}) and the store argument of
 * {@code connectionByElement} ({@link PlatformTypes#CONNECTION_BY_ELEMENT}
 * — the connection the fixture's inserts run over). No SQL text and no
 * table name is read: the mapping/store metamodel names the store, and
 * a program that reaches the store only through a computed value states
 * no fact.
 */
public final class SeededStores {

    private SeededStores() {
    }

    /** The store FQNs {@code node} seeds; memoized per callee signature,
     * a cycle scores the in-progress callee empty. */
    public static Set<String> of(TypedSpec node, SpecCompiler specs,
            Map<String, Set<String>> memo) {
        Set<String> out = new LinkedHashSet<>();
        collect(node, specs, memo, out);
        return out;
    }

    private static void collect(TypedSpec node, SpecCompiler specs,
            Map<String, Set<String>> memo, Set<String> out) {
        if (node instanceof TypedNativeCall nc) {
            String fqn = nc.callee().qualifiedName();
            int storeArg = PlatformTypes.DROP_AND_CREATE_TABLE_IN_DB.equals(fqn) ? 0
                    : PlatformTypes.CONNECTION_BY_ELEMENT.equals(fqn) ? 1 : -1;
            if (storeArg >= 0 && nc.args().size() > storeArg
                    && nc.args().get(storeArg) instanceof TypedPackageableRef store) {
                out.add(store.fullPath());
            }
        }
        if (node instanceof TypedUserCall uc
                && Subsumed.of(uc.callee().qualifiedName()).isEmpty()) {
            String key = uc.callee().signatureKey();
            Set<String> known = memo.get(key);
            if (known == null) {
                memo.put(key, Set.of());   // in-progress: cycles score empty
                Set<String> seeded = new LinkedHashSet<>();
                try {
                    for (TypedSpec stmt : specs.compile(uc.callee()).body()) {
                        collect(stmt, specs, memo, seeded);
                    }
                } catch (TypeInferenceException e) {
                    // an un-typeable callee cannot execute: no fact
                    seeded = Set.of();
                }
                memo.put(key, seeded);
                known = seeded;
            }
            out.addAll(known);
        }
        for (TypedSpec c : node.children()) {
            collect(c, specs, memo, out);
        }
    }
}
