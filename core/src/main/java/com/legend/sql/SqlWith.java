// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.sql;

import java.util.List;

/**
 * A WITH-prefixed query: named common table expressions ahead of a body
 * query that references them as tables. Minted by the CTE-extraction
 * post-processor ({@code SqlPostProcessors.extractSubqueriesAsCtes} — the
 * engine's cteExtractionPostProcessor: every subselect in the FROM tree
 * becomes {@code subquery_cte_<level>_<index>}); the body's outputs are
 * the query's.
 */
public record SqlWith(List<Cte> ctes, SqlQuery body) implements SqlQuery {

    public SqlWith {
        ctes = List.copyOf(ctes);
        if (ctes.isEmpty()) {
            throw new IllegalArgumentException("a WITH without expressions is its body");
        }
    }

    /** One {@code name AS (query)}. */
    public record Cte(String name, SqlQuery query) {
    }

    @Override
    public List<OutputCol> outputs() {
        return body.outputs();
    }
}
