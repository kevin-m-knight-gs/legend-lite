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
        return firstDiff(expected, actual, "$", JsonCompare::documentLeaf);
    }

    /** WIRE-VALUE tree equality: leaves under
     * {@link PureAsserts#equalScalar} (policies included). */
    public static boolean wireTree(@com.legend.Nullable Object expected,
            @com.legend.Nullable Object actual) {
        return firstDiff(expected, actual, "$",
                PureAsserts::equalScalar) == null;
    }

    /** The ONE walker: containers structurally (objects by key set,
     * arrays ordered element-wise), leaves by the door's rule. Null =
     * equal; else the first differing path. */
    static @com.legend.Nullable String firstDiff(
            @com.legend.Nullable Object e, @com.legend.Nullable Object a,
            String path, BiPredicate<Object, Object> leaf) {
        if (e instanceof Map<?, ?> em && a instanceof Map<?, ?> am) {
            if (!em.keySet().equals(am.keySet())) {
                return path + " expected keys " + em.keySet()
                        + ", got " + am.keySet();
            }
            for (Object k : em.keySet()) {
                String d = firstDiff(em.get(k), am.get(k),
                        path + "." + k, leaf);
                if (d != null) {
                    return d;
                }
            }
            return null;
        }
        if (e instanceof List<?> el && a instanceof List<?> al) {
            if (el.size() != al.size()) {
                return path + " expected " + el.size()
                        + " element(s), got " + al.size();
            }
            for (int i = 0; i < el.size(); i++) {
                String d = firstDiff(el.get(i), al.get(i),
                        path + "[" + i + "]", leaf);
                if (d != null) {
                    return d;
                }
            }
            return null;
        }
        // container-vs-leaf mismatches reach the leaf rule and fail there
        return leaf.test(e, a) ? null
                : path + " expected " + abbreviate(String.valueOf(e))
                        + ", got " + abbreviate(String.valueOf(a));
    }

    /** The DOCUMENT leaf rule: numbers numerically WITHIN kind
     * (BigDecimal by compareTo — scale drops: the engine prints 5.0
     * where our envelope prints 5.000000000 for the same DECIMAL(38,9)
     * value), all else strict. Long-vs-BigDecimal stays UNEQUAL on
     * purpose — an integer-typed expectation against a decimal wire
     * value is a typing bug this compare must catch (the migrated
     * harness rationale, verbatim). */
    private static boolean documentLeaf(@com.legend.Nullable Object e,
            @com.legend.Nullable Object a) {
        if (e instanceof java.math.BigDecimal be
                && a instanceof java.math.BigDecimal ba) {
            return be.compareTo(ba) == 0;
        }
        return java.util.Objects.equals(e, a);
    }

    private static String abbreviate(String s) {
        return s.length() <= 120 ? s : s.substring(0, 120) + "…";
    }
}
