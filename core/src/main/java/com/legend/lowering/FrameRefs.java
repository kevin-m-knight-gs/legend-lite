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
        return new SqlSelect(ps, false, src, null, List.of(), null, null, List.of(),
                null, null, cols);
    }
}
