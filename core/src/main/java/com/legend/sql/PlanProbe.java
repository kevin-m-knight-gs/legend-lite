// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.sql;

import java.util.List;
import java.util.Map;

/** A LIMIT-0 metadata probe's view of a plan: the CONCRETE output
 * columns (pivot-generated names included) and the names their types
 * are typed by: the wire vocabulary, as each database's dialect reads
 * its driver's names into it ({@code SqlDialect.typingWireName}); a
 * name that does not say its type stays the driver's own, which types
 * nothing.
 * A schema fact carrier — produced at the execution seam, consumed by
 * lowering-side composition (E1, JAVA_EVICTION_PLAN). */
public record PlanProbe(List<OutputCol> outs, Map<String, String> typeNames) {
}
