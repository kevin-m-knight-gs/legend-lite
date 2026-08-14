// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.builtin;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The NATIVE-CATALOG governance gate (invention audit 2026-08-14 §5/§6):
 * the grammar surface is oracle-checked every gate-8 run, but the
 * FUNCTION catalog was pinned only against its own golden file — a 20th
 * invented native could land by regenerating the golden. This test closes
 * that: every native in the lite-internal package must be one of the two
 * NAMED, per-name-verified sets. Both sets shrink-only.
 */
class NativeCatalogGovernanceTest {

    @Test
    void everyLiteInternalNativeIsNamedAndVerified() {
        var unaccounted = Pure.liteInternalNatives().stream()
                .map(q -> q.substring(q.lastIndexOf("::") + 2))
                .filter(bare -> !Pure.INTERNAL_DESUGAR.contains(bare)
                        && !Pure.ENGINE_VOCAB_SHIMS.contains(bare))
                .sorted().distinct().toList();
        assertEquals(java.util.List.of(), unaccounted,
                "lite-internal natives outside the verified sets — a new"
                        + " name needs the per-name upstream check"
                        + " (LITE_INVENTION_CENSUS.md) before it exists");
    }

    @Test
    void theVerifiedSetsOnlyShrink() {
        // 13 internal-desugar + 7 engine-vocabulary shims, verified
        // per-name 2026-08-14 (maxDate/minDate/variantTo/percentileCont/
        // percentileDisc deleted; traverse + _Traversal deleted after the
        // ruling — navigate subsumed the old engine-lite traverse and the
        // tests were ALREADY on navigate, only comments said traverse).
        // Growth is a NEW invention; shrink is always allowed.
        assertEquals(true, Pure.INTERNAL_DESUGAR.size() <= 13,
                "INTERNAL_DESUGAR grew: " + Pure.INTERNAL_DESUGAR);
        assertEquals(true, Pure.ENGINE_VOCAB_SHIMS.size() <= 7,
                "ENGINE_VOCAB_SHIMS grew: " + Pure.ENGINE_VOCAB_SHIMS);
    }
}
