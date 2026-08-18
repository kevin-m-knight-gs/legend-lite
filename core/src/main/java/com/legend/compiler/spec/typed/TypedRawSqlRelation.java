// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.compiler.spec.typed;

import com.legend.compiler.element.type.ExprType;

import java.util.List;

/**
 * An AUTHORED-SQL relation source (One-Platform Plan Phase 1c,
 * user-ratified design): the ordered, materialized rows of one raw
 * statement — {@code executeInDb(sql, ...).rows} and the fetchDb
 * catalog grids. The {@code TypedTds} family's sibling: a relation
 * SOURCE whose columns are LATE-BOUND (the dynamic-pivot precedent —
 * nothing is known statically, so {@link #info()} carries an
 * empty-column {@code RelationType}; by-NAME reads carry their own
 * column names, and schema-shaped reads pin at the probe).
 *
 * <p>The carried {@code sql} is USER/CORPUS-AUTHORED text (the
 * {@code executeInDb} argument, or the registered catalog SQL) — never
 * platform-composed; the RawSql quarantine's contract rides from here
 * to {@code SqlSource.RawSql}. Backend adaptation of the authored text
 * is the DIALECT's own rewrite pass, never this node's business.
 *
 * @param sql  the authored statement text (single statement, no
 *             trailing {@code ;})
 * @param info an empty-column relation type — columns are late-bound
 */
public record TypedRawSqlRelation(String sql, ExprType info)
        implements TypedSpec {

    @Override
    public List<TypedSpec> children() {
        return List.of();
    }

    @Override
    public TypedSpec withChildren(List<TypedSpec> kids) {
        TypedSpec.expectChildren(kids, 0, "TypedRawSqlRelation");
        return this;
    }
}
