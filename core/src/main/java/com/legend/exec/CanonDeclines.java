// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.exec;

import java.util.List;

/**
 * D4 (V7_ARCH_AUDIT 2026-08-28) — THE DECLINE TAXONOMY REGISTER. The
 * byte channel refuses with named reasons, and the burn-down census
 * keys on their spellings: a casual rewording silently splits a
 * census class. Every classification PREFIX an emitter may use is
 * registered here; {@code CanonDeclineTaxonomyTest} scans the emitter
 * files and fails the build on an unregistered spelling. Adding a
 * prefix is deliberate: register it here (with the census reader in
 * mind) in the same commit as its first emitter.
 */
public final class CanonDeclines {

    private CanonDeclines() {
    }

    /** Registered classification prefixes for the inner byte-channel
     * decline census ({@code CanonicalDivergence.sqlDeclined} and the
     * canon wrap decline reasons). Order: verdict-layer gates, then
     * side/render channels, then wrap-time refusals. */
    public static final List<String> REGISTERED_PREFIXES = List.of(
            // verdict-layer gates (AssertVerdicts.sqlByteVerdict)
            "kind-gate:",
            "mixed-kind-collection",
            "unrefined-number",
            "cross-kind-numeric:",
            "any-pair:",
            "any-wire-tree:",
            "keyless-ctor-in-lambda:",
            "identityless-instance-wire:",
            // per-side canon channels
            "side-e:",
            "side-a:",
            "render-e:",
            "render-a:",
            "null-canon-cell",
            // TDS (grid) channels — TdsCompare
            "tds-side:",
            "tds-peer:",
            // wrap-time refusals — CanonicalRenderSql
            "tds-canon:",
            "non-scalar plan shape:",
            "any-carrier:",
            "map-key-shape:",
            "keyless-instance:",
            "instance-key-shape:",
            "unclaimed kind:",
            "canonical-order over",
            // driver-layer refusals — StatementExecutor / CanonRider
            "canon-exec:",
            "unsqlable-literal:",
            "non-sql-arm",
            "unclassified");
}
