// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0
package com.legend.lowering;

import com.legend.sql.SqlExpr;
import java.util.ArrayList;
import java.util.List;

/** {@code meta::legend::lite::tuple([a, b, …])} — the ROW of a tree's
 * leaf values, what the by-tree {@code isDistinct} compares on
 * (IsDistinctChecker): a struct literal, fields {@code f0..} in order.
 * One value, so the collection reducer distinct-counts it as one. */
final class TupleValue {
    private TupleValue() {
    }

    static SqlExpr of(List<SqlExpr> args) {
        List<SqlExpr> vs = args.size() == 1 && args.get(0) instanceof SqlExpr.ArrayLit a
                ? a.elements() : args;
        List<SqlExpr.StructLit.Field> fields = new ArrayList<>();
        for (int i = 0; i < vs.size(); i++) {
            fields.add(new SqlExpr.StructLit.Field("f" + i, vs.get(i)));
        }
        return new SqlExpr.StructLit(fields);
    }
}
