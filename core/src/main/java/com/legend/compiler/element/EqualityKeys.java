// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.compiler.element;

import com.legend.compiler.element.type.Type;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * X5 — a class's {@code <<equality.Key>>} identity, resolved from the
 * model at COMPILE TIME (the engine's
 * {@code _Class.getEqualityKeyProperties}: simple properties carrying
 * the stereotype, own class first then supertypes, name-deduped).
 * Instance equality over a keyed class compares KEY PROPERTIES ONLY
 * ({@code EqualityUtilities.equal}); a keyless class refuses value
 * equality entirely (identity or FALSE) — {@code resolve} returns null
 * for it, and every consumer declines rather than judging.
 *
 * <p>{@code nested} carries a class-typed key's own key tree (the
 * engine recurses through {@code equal}); a class-typed key whose
 * class is KEYLESS poisons the whole resolution (equality through it
 * can never hold by value) — null, decline. Cycles likewise.
 */
public record EqualityKeys(String classFqn, List<Key> keys) {

    /** One key property: {@code many} marks a to-many key
     * ({@code List.values : T[*]} — engine compares the value
     * COLLECTIONS under the ordered list rule). */
    public record Key(String name, boolean many,
                      @com.legend.Nullable EqualityKeys nested) {
    }

    /** The class FQN a stamp names, or null for non-class stamps —
     * bare {@code ClassType} and parameterized {@code GenericType}
     * both name a classifier (the engine's classifier-match rule reads
     * the raw class; type arguments never change WHICH keys exist). */
    public static @com.legend.Nullable String fqnOf(Type t) {
        if (t instanceof Type.ClassType ct) {
            return ct.fqn();
        }
        if (t instanceof Type.GenericType gt) {
            return gt.rawFqn();
        }
        return null;
    }

    public static @com.legend.Nullable EqualityKeys resolve(
            ModelContext ctx, String classFqn) {
        return resolve(ctx, classFqn, new LinkedHashSet<>());
    }

    private static @com.legend.Nullable EqualityKeys resolve(
            ModelContext ctx, String classFqn, Set<String> inProgress) {
        if (!inProgress.add(classFqn)) {
            return null;   // key cycle — never claimable by value
        }
        try {
            List<Key> keys = new ArrayList<>();
            Set<String> seen = new LinkedHashSet<>();
            if (!collect(ctx, classFqn, inProgress, keys, seen,
                    new LinkedHashSet<>())) {
                return null;
            }
            return keys.isEmpty() ? null : new EqualityKeys(classFqn, keys);
        } finally {
            inProgress.remove(classFqn);
        }
    }

    /** Walks {@code fqn} then its supertypes (engine generalization
     * order), appending keyed stored properties. False = poisoned
     * (a class-typed key over a keyless class). */
    private static boolean collect(ModelContext ctx, String fqn,
            Set<String> inProgress, List<Key> out, Set<String> seenNames,
            Set<String> seenClasses) {
        if (!seenClasses.add(fqn)) {
            return true;   // diamond — already contributed
        }
        TypedClass tc = ctx.findClass(fqn).orElse(null);
        if (tc == null) {
            return true;   // unknown super — contributes nothing
        }
        for (Property p : tc.properties()) {
            if (!(p instanceof Property.Stored st) || !st.equalityKey()
                    || !seenNames.add(st.name())) {
                continue;
            }
            EqualityKeys nested = null;
            if (st.type() instanceof Type.ClassType ct) {
                nested = resolve(ctx, ct.fqn(), inProgress);
                if (nested == null) {
                    return false;   // keyless/cyclic class as a key
                }
            }
            out.add(new Key(st.name(), st.multiplicity().isMany(), nested));
        }
        for (String sup : tc.superClassFqns()) {
            if (!collect(ctx, sup, inProgress, out, seenNames,
                    seenClasses)) {
                return false;
            }
        }
        return true;
    }
}
