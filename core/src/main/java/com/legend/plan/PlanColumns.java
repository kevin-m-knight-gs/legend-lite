// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0
package com.legend.plan;

import com.legend.compiler.element.type.ExprType;
import com.legend.compiler.element.type.Multiplicity;
import com.legend.compiler.element.type.Type;
import com.legend.executionplan.ColumnFacts;
import com.legend.executionplan.ExecutionPlan;
import com.legend.sql.SqlQuery;

import java.util.List;

/**
 * A query's result columns with their three type facts (docs/PLAN_PATH_PROGRAM_2026_10_17.md §3): the typer's
 * columns of the root ({@link UpstreamRelationType#columns}: a relation's, or a value's one column {@code value})
 * joined with the lowered query's slots and the store's declared types ({@link ColumnFacts}). A graph result has
 * no columns: its objects are the wire's.
 */
public final class PlanColumns {
    private PlanColumns() {
    }

    public static List<ExecutionPlan.Column> of(ExprType root, SqlQuery lowered) {
        if (ResultShape.of(root) == ResultShape.GRAPH) {
            return List.of();
        }
        return ColumnFacts.columns(UpstreamRelationType.columns(root).stream()
                .map(c -> new ColumnFacts.Pure(c.name(), c.type().typeName(), multiplicity(c.multiplicity())))
                .toList(), lowered, shapeOf(root));
    }

    /** The typed result's shape of a root: a relation's rows, a graph fetch's objects, else a value's elements
     *  (the plan's {@code TdsResult.Shape} for the compiler's {@link ResultShape}). */
    public static ExecutionPlan.TdsResult.Shape shapeOf(ExprType root) {
        return switch (ResultShape.of(root)) {
            case TABULAR -> ExecutionPlan.TdsResult.Shape.RELATION;
            case GRAPH -> ExecutionPlan.TdsResult.Shape.OBJECTS;
            case SCALAR, COLLECTION -> ExecutionPlan.TdsResult.Shape.VALUE;
        };
    }

    /** A column's multiplicity in the plan's terms (a column's is always bounded). */
    public static ExecutionPlan.Multiplicity multiplicity(Multiplicity m) {
        Multiplicity.Bounded b = m.requireBounded("a result column's multiplicity");
        return new ExecutionPlan.Multiplicity(b.lower(), b.upper());
    }

    /** {@link #of(ExprType, SqlQuery)} for a schema the planner already has ({@link Type.RelationType}):
     *  {@code schema}'s columns over {@code lowered}, in a result of {@code shape}. */
    public static List<ExecutionPlan.Column> of(Type.RelationType schema, SqlQuery lowered,
            ExecutionPlan.TdsResult.Shape shape) {
        return ColumnFacts.columns(schema.columns().stream()
                .map(c -> new ColumnFacts.Pure(c.name(), c.type().typeName(), multiplicity(c.multiplicity())))
                .toList(), lowered, shape);
    }
}
