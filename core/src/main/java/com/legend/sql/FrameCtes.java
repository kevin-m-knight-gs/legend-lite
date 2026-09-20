// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.sql;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Leg 3.4 step 2: the frame CTEs a query references, DEFINED at its head
 * (leg 3.4 step 2, docs/DATABASE_MODE_HOMEWORK_2026_09_18.md §4aa). A side
 * plan stays a bare select referencing {@code frame_x} ({@link
 * SqlSource.Cte}); whoever makes SQL text of it — the fused verdict
 * statement, a standalone execution, a prepare — attaches the definitions
 * it needs, {@code MATERIALIZED}. The plan itself never carries a WITH, so
 * every builder that reads a plan as a select keeps reading it. */
public final class FrameCtes {

    private FrameCtes() {
    }

    /** {@code q} with the definitions of every frame it references ahead
     * of it (each once, MATERIALIZED, in the definitions' order); {@code q}
     * itself when it references none. */
    public static SqlQuery attach(SqlQuery q, Map<String, SqlQuery> definitions) {
        if (definitions.isEmpty()) {
            return q;
        }
        Set<String> names = referenced(q);
        if (names.isEmpty()) {
            return q;
        }
        List<SqlWith.Cte> ctes = new ArrayList<>();
        for (Map.Entry<String, SqlQuery> d : definitions.entrySet()) {
            if (names.contains(d.getKey())) {
                ctes.add(new SqlWith.Cte(d.getKey(), d.getValue(), true));
            }
        }
        for (String n : names) {
            if (!definitions.containsKey(n)) {
                throw new IllegalStateException("frame '" + n + "' is referenced but not defined");
            }
        }
        return SqlWith.prepend(ctes, q);
    }

    /** The names of the CTE sources {@code q} references, first-seen order. */
    public static Set<String> referenced(SqlQuery q) {
        Set<String> out = new LinkedHashSet<>();
        new SqlRewriter() {
            @Override
            protected SqlSource source(SqlSource s) {
                if (s instanceof SqlSource.Cte c) {
                    out.add(c.name());
                }
                return s;
            }
        }.rewriteRoot(q);
        return out;
    }
}
