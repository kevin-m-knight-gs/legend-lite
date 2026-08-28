// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.resolver;

import com.legend.compiler.element.type.ExprType;
import com.legend.compiler.element.type.Multiplicity;
import com.legend.compiler.element.type.Type;
import com.legend.compiler.spec.typed.TypedFuncCol;
import com.legend.compiler.spec.typed.TypedLambda;
import com.legend.compiler.spec.typed.TypedProject;
import com.legend.compiler.spec.typed.TypedPropertyAccess;
import com.legend.compiler.spec.typed.TypedSpec;

import java.util.List;

/**
 * The SCALAR VALUE-COLLECTION read synthesis (StoreResolver's
 * map/property funnel): the single-column projection a scalar map or
 * property read over instances becomes. Pure node construction;
 * StoreResolver owns resolution and the callee catalog.
 */
final class ScalarValueReads {

    private ScalarValueReads() {
    }

    /** The synthetic single-column projection for a scalar map/property
     * read over instances. {@code valueMult} is the ORIGINAL expression's
     * multiplicity — a to-many read is a VALUE COLLECTION and the scalar
     * lowering must LIST-aggregate it (contains/in consumers), while a
     * to-one read stays the bare scalar subquery. The COLLECTION fact
     * rides the relation's ExprType (valueMult); the COLUMN declares the
     * PER-CELL multiplicity — each row holds ONE cell (pair-#4
     * elimination, STAMP_DISCIPLINE_PROGRAM: copying the collection
     * mult onto the column stamped every per-row read many — the C5
     * u_map__active witness and the invariant's one abusable skip). */
    static TypedProject scalarMapAsProject(TypedSpec source,
            TypedLambda mapper, Multiplicity valueMult) {
        TypedSpec body = mapper.body().get(mapper.body().size() - 1);
        String name = com.legend.sql.SqlSelect.SYNTH_MAP_COL
                + (body instanceof TypedPropertyAccess bpa
                        ? bpa.property() : "value");
        Type.Param result = mapper.functionType().result();
        Multiplicity cellMult =
                result.multiplicity() instanceof Multiplicity.Bounded rb
                        && rb.upper() != null && rb.upper() <= 1
                ? result.multiplicity()
                : Multiplicity.Bounded.ZERO_ONE;
        // DEEP_AUDIT R2: a FLOW-adjudicated toOne/trustOne wrap over an
        // optional read stamps [1] in pure but its SQL CELL can be NULL
        // (the engine lane emits no guard — §7b witness: user toOne =
        // bare LEFT JOIN; the engine drops the NULL client-side,
        // SQLNull->[]). The COLUMN mult tells the CARRIER's truth so
        // the egress filter and the CompactList consumers drop exactly
        // as the engine does. map(p|$p.nick->toOne()) hit the egress
        // WALL before this.
        if (cellMult instanceof Multiplicity.Bounded cb && cb.lower() == 1
                && cellCanBeEmpty(body)) {
            cellMult = Multiplicity.Bounded.ZERO_ONE;
        }
        Type.RelationType row =
                new Type.RelationType(List.of(
                        new Type.Column(name, result.type(), cellMult)));
        return new TypedProject(source,
                List.of(new TypedFuncCol(name, mapper)),
                new ExprType(Type.relation(row), valueMult));
    }

    /** The cell UNDER any flow-adjudicated toOne-family wraps can be
     * empty — descend to the wrapped read and ask ITS stamp. */
    private static boolean cellCanBeEmpty(TypedSpec body) {
        while (body instanceof com.legend.compiler.spec.typed
                        .TypedNativeCall nc
                && com.legend.builtin.Pure.isToOneCall(
                        nc.callee().qualifiedName())
                && !nc.args().isEmpty()) {
            body = nc.args().get(0);
        }
        return body.info().multiplicity()
                        instanceof Multiplicity.Bounded b
                && b.lower() == 0;
    }
}
