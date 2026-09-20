// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.compiler.spec.typed;

import com.legend.compiler.element.type.ExprType;
import com.legend.sql.SqlQuery;

import java.util.List;

/**
 * A REFERENCE to an already-planned execute() frame (leg 3.4 step 2,
 * docs/DATABASE_MODE_HOMEWORK_2026_09_18.md §4aa): the relation a
 * {@code let r = execute(...)} bound, read as {@code $r.values} by an
 * assert side. Instead of the frame's chain PASTED into the side (the
 * frame's query re-lowered and re-run per read), the side lowers to a
 * reference to a named relation ({@code FROM frame_r}) whose definition —
 * the frame's own plan, lowered once at the let — the statement carries as
 * a CTE ({@code WITH frame_r AS MATERIALIZED (…)}), hoisted once per fused
 * verdict statement. {@link #info()} is the chain's own type (the Typer's
 * view, unchanged); {@link #plan()} carries the frame's outputs — the WIRE
 * kinds the reference's columns take.
 *
 * @param name the CTE name ({@code frame_<let>})
 * @param info the frame chain's type (a relation)
 * @param plan the frame's plan as planned at its let
 */
public record TypedFrameRef(String name, ExprType info, SqlQuery plan)
        implements TypedSpec {

    @Override
    public List<TypedSpec> children() {
        return List.of();
    }

    @Override
    public TypedSpec withChildren(List<TypedSpec> kids) {
        TypedSpec.expectChildren(kids, 0, "TypedFrameRef");
        return this;
    }

    @Override
    public TypedSpec withInfo(ExprType info) {
        return new TypedFrameRef(name, info, plan);
    }

    /** Every frame reference under {@code n}, in pre-order. */
    public static void collect(TypedSpec n, List<TypedFrameRef> out) {
        if (n instanceof TypedFrameRef fr) {
            out.add(fr);
            return;
        }
        for (TypedSpec c : n.children()) {
            collect(c, out);
        }
    }
}
