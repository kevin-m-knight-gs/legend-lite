package com.legend.sql;

import java.util.List;

/**
 * A set operation over two or more branches. {@code all=true} is
 * {@code UNION ALL} (Pure {@code concatenate}); {@code all=false} is
 * deduplicating {@code UNION}.
 *
 * @param outputs branch schemas are identical by Phase-G typing; these are the
 *                first branch's columns
 */
public record SqlUnion(List<SqlQuery> branches, boolean all, List<OutputCol> outputs)
        implements SqlQuery {

    public SqlUnion {
        if (branches.size() < 2) {
            throw new IllegalArgumentException("a union needs at least two branches");
        }
        // label reconciliation at construction (the SqlSelect
        // compact-ctor idiom): the union's contract-derived outputs
        // adopt the branches' uniform computed labels, tolerance and
        // nullability — see SqlTyping.reconcileUnionLabels
        outputs = SqlTyping.reconcileUnionLabels(branches, outputs);
    }
}
