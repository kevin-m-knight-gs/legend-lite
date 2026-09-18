// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.exec;

import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;

/**
 * THE STRUCTURAL TREE COMPARE (Phase 2 deep-audit remediation,
 * 2026-08-19): ONE walker for map/list trees, shared by the TWO doors
 * that produce them — with a documented LEAF rule per door, because the
 * doors carry different realities:
 *
 * <ul>
 * <li><b>WIRE-VALUE trees</b> (struct cells the Executor decodes into
 * maps at egress): leaves are DATABASE-WIRE values — they compare under
 * {@link PureAsserts#equalScalar} (pure scalar semantics PLUS the
 * adjudicated wire policies). Entry: the {@code PureAsserts} tree arms
 * delegate here. This unification FIXED a latent gap: nested LISTS
 * inside struct cells previously fell through to raw Java
 * {@code equals} — no pure numeric normalization.</li>
 * <li><b>PARSED-DOCUMENT trees</b> ({@code assertJsonStringsEqual}: two
 * JSON texts parsed and compared by content): leaves are DOCUMENT
 * tokens — numbers numerically within kind ({@code BigDecimal} by
 * {@code compareTo}), everything else strictly; NO wire policies (a
 * serialized document has no wire; {@code 1} vs {@code 1.0} in JSON
 * text is a REAL difference, matching the engine's parse-and-compare).
 * Entry: {@link #document}. Moved from the harness's private
 * {@code jsonDeepEquals}/{@code jsonDiffPath} — the Phase-2b treatment
 * applied to the one comparator left behind (its claimed ledger
 * exception was never actually registered — this registration replaces
 * it).</li>
 * </ul>
 */
public final class JsonCompare {

    private JsonCompare() {
    }

    /** PARSED-DOCUMENT equality (content, not text): null when equal,
     * else the first differing path — {@code $.a.b[3] expected X, got Y}
     * (the harness's diagnostic contract, verbatim). */
    public static @com.legend.Nullable String document(
            @com.legend.Nullable Object expected,
            @com.legend.Nullable Object actual) {
        return Equality.pureJson(expected, actual);
    }

    public static @com.legend.Nullable String documentUnorderedRoot(
            @com.legend.Nullable Object expected,
            @com.legend.Nullable Object actual) {
        return Equality.pureJsonUnorderedRoot(expected, actual);
    }

    static String canonicalText(@com.legend.Nullable Object v) {
        return Equality.canonicalText(v);
    }

    public static boolean wireTree(@com.legend.Nullable Object expected,
            @com.legend.Nullable Object actual) {
        return Equality.same(Equality.Typed.of(expected), Equality.Typed.of(actual));
    }
}
