// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.sql.dialect;

/**
 * legend-engine's own text for a database whose engine spelling is built (the engine's test database H2, DB2 and
 * Composite): the dialect the engine-text and plan-text paths take, named for what it writes rather than for one
 * database (the dialect program's Phase 3, 2026-10-16: until then those paths took {@link EngineStyleH2}).
 */
public interface EngineTextDialect extends SqlDialect {

    /** A collection-valued plan parameter spliced as the engine's plan text writes it (its FreeMarker collection
     *  template). */
    String collectionSplice(com.legend.sql.SqlExpr.PlanParam cp);

    /** A relation alias as the engine's plan text writes it. */
    String renderedAlias(String alias);
}
