// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.lowering;

import com.legend.builtin.Pure;
import com.legend.compiler.element.type.Type;
import com.legend.compiler.spec.typed.TypedDistinct;
import com.legend.compiler.spec.typed.TypedNativeCall;
import com.legend.compiler.spec.typed.TypedSort;
import com.legend.compiler.spec.typed.TypedSpec;

import java.util.List;

/**
 * Value-collection natives over a SINGLE-COLUMN RELATION read (class-
 * rooted frame reads: {@code $result.values.legalName
 * ->removeDuplicates()->sort()}): dedup and order happen in RELATION
 * space — SELECT DISTINCT / ORDER BY inside the list subquery. The
 * list-space rules would re-embed the list subquery inside a SQL
 * lambda, which DuckDB's binder rejects (corpus testIsNotEmpty).
 */
final class ValueCollectionOps {

    private ValueCollectionOps() {
    }

    /** The relation-space rewrite of {@code n}, or null when {@code n} is
     * not a 1-arg removeDuplicates/sort over a single-column relation. */
    static @com.legend.Nullable TypedSpec relationSpaceRewrite(TypedNativeCall n) {
        if (n.args().size() != 1
                || !(n.args().get(0).info().type()
                        instanceof Type.RelationType rt)
                || rt.columns().size() != 1) {
            return null;
        }
        String key = n.callee().signatureKey();
        if (Pure.nativeNamed("removeDuplicates", key)) {
            return new TypedDistinct(n.args().get(0),
                    List.of(rt.columns().get(0).name()),
                    n.args().get(0).info());
        }
        if (Pure.nativeNamed("sort", key)) {
            return new TypedSort(n.args().get(0),
                    List.of(new TypedSort.TypedSortKey(
                            rt.columns().get(0).name(), true)),
                    n.args().get(0).info());
        }
        return null;
    }
}
