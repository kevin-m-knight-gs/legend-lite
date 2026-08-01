// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.sql.dialect;

import com.legend.sql.SqlRewriter;

/**
 * THE STRATEGY PASS (CARRIER_REDESIGN.md §1): rewrites SEMANTIC
 * collection nodes — SubAggregate, Membership, CollectionSource,
 * CollectionValue, landing rung by rung — into this dialect's emission.
 * Dispatch is capability-driven: the dialect declares which strategies
 * it supports (data, like Spellings/Lexicon rows); a node with no rule
 * on this dialect throws the typed {@link DialectCapability} wall,
 * budget-counted by the portability sweep.
 *
 * <p>SINGLE-COMPILER CONTRACT (tenet #1, user-set, HARD): the Lowerer
 * emits only semantic nodes; every backend idiom — including DuckDB's
 * native {@code list()}/UNNEST/array literals — exists ONLY as a rule
 * here. Each rung deletes the corresponding direct emission upstream
 * in the same commit ({@code CarrierPurityRatchetTest} enforces the
 * burn-down; it freezes at zero when R5 lands).
 *
 * <p>R0 state: IDENTITY — the semantic nodes do not exist yet; this
 * pass is the wired seam they land into (R1: SubAggregate).
 */
public final class CarrierStrategies extends SqlRewriter {
    // R0: identity — SqlRewriter's default hooks. Rules land per rung.
}
