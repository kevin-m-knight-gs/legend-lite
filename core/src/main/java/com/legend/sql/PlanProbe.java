// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.sql;

import java.util.List;
import java.util.Map;

/** A LIMIT-0 metadata probe's view of a plan: the CONCRETE output
 * columns (pivot-generated names included) and the names their types
 * are typed by: the wire vocabulary, as each database's dialect reads
 * its driver's names into it ({@code SqlDialect.typingWireName}). A
 * column whose type's name does not say it is absent, and
 * {@link #typeName} refuses it.
 * A schema fact carrier — produced at the execution seam, consumed by
 * lowering-side composition (E1, JAVA_EVICTION_PLAN). */
public record PlanProbe(List<OutputCol> outs, Map<String, String> typeNames) {

    /** The wire name {@code column} is typed by. A column whose driver names its type by a name that does not say it
     *  (Snowflake's NUMBER, an integer or a decimal by its scale) has none and is refused: typing it by that name, or
     *  by the probe's slot, would be a guess (AGENTS.md invariant 4; 2026-10-15, the audit of the dialect program's
     *  Phase 4). */
    public String typeName(String column) {
        String t = typeNames.get(column);
        if (t == null) {
            throw new IllegalStateException("column '" + column + "' has no type by its driver's type name: the"
                    + " probe has no such column, or the name does not say the type");
        }
        return t;
    }
}
