// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.lowering;

import com.legend.compiler.spec.typed.TypedFrameRef;
import com.legend.sql.OutputCol;
import com.legend.sql.SqlExpr;
import com.legend.sql.SqlSelect;
import com.legend.sql.SqlSource;

import java.util.ArrayList;
import java.util.List;

/** Leg 3.4 step 2 (docs/DATABASE_MODE_HOMEWORK_2026_09_18.md §4aa): the
 * lowering of a reference to a PLANNED execute() frame — the frame plan's
 * columns projected explicitly, each with its plan SLOT type: exactly what
 * the pasted chain delivered to a side. The CTE itself is defined at the
 * statement's head by the executor and hoisted once per fused statement. */
final class FrameRefs {

    private FrameRefs() {
    }

    static SqlSelect reference(Lowerer lowerer, TypedFrameRef fr) {
        // the frame plan's own columns and SLOT types — what the pasted
        // chain delivered (the slot is the wire: a String-declared column
        // over an INT store reads as the integer it is)
        List<OutputCol> cols = fr.plan().outputs();
        // unique statement-wide (AliasPrefix): the frame's body and the sides
        // beside it mint bare t<n> aliases of their own
        String alias = com.legend.sql.AliasPrefix.frameReader(fr.name(), lowerer.nextAlias());
        SqlSource.Cte src = new SqlSource.Cte(fr.name(), alias, cols);
        List<SqlSelect.Projection> ps = new ArrayList<>(cols.size());
        for (OutputCol o : cols) {
            ps.add(new SqlSelect.Projection(SqlExpr.Column.of(alias, cols, o.name()),
                    o.name(), o));
        }
        // THE FRAME'S ORDER RIDES ITS REFERENCE (lean ladder rung 8): a CTE
        // boundary carries no order in SQL — a positional read over a
        // sorted frame (->sort(...)->at(n)) must re-state the sort over the
        // reference's own columns; a key the plan does not project cannot be
        // re-stated and the reference stays unordered (the plan's own sort
        // still shapes the CTE's rows)
        List<SqlSelect.SortKey> order = new ArrayList<>();
        if (fr.plan() instanceof SqlSelect ps2) {
            for (SqlSelect.SortKey k : ps2.orderBy()) {
                OutputCol projected = null;
                for (int i = 0; i < ps2.projections().size() && i < cols.size(); i++) {
                    SqlSelect.Projection p = ps2.projections().get(i);
                    if (p.expr().equals(k.expr())
                            || (k.outputName() != null && k.outputName().equals(p.alias()))) {
                        projected = cols.get(i);
                        break;
                    }
                }
                if (projected == null) {
                    order = List.of();
                    break;
                }
                order.add(new SqlSelect.SortKey(SqlExpr.Column.of(alias, cols, projected.name()),
                        k.ascending(), k.nullOrder(), projected.name()));
            }
        }
        return new SqlSelect(ps, false, src, null, List.of(), null, null, List.copyOf(order),
                null, null, cols);
    }
}
