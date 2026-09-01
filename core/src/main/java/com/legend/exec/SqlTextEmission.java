// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.exec;

/**
 * THE TEXT-EMISSION CENSUS (SQLTEXT charter §0/§8.6): under the
 * row-verdict arms, SQL-text match/diff is a CENSUS NUMBER — never a
 * verdict. Every sql-text verdict arm records what the TEXT did beside
 * the row outcome; dialect spelling work retires diff rows
 * class-by-class (the shrink-only emission ratchet lands with charter
 * slice 6). Measurement only — nothing here can affect a verdict (the
 * CanonicalDivergence pattern; the runner prints the counters each
 * sweep).
 */
public final class SqlTextEmission {

    private SqlTextEmission() {
    }

    /** Census-probe isolation (the SqlTypeCensus.probeSuspend idiom):
     * the dual-channel probe re-executes asserts the primary lane
     * already counted — its duplicate arm firings must not move these
     * counters or the oracle's decline census. */
    private static final ThreadLocal<Boolean> PROBE =
            ThreadLocal.withInitial(() -> Boolean.FALSE);

    public static void probeSuspend(boolean on) {
        PROBE.set(on);
    }

    public static boolean probeSuspended() {
        return PROBE.get();
    }

    /** Rows matched and the two texts were byte-equal. */
    public static final java.util.concurrent.atomic.LongAdder TEXT_MATCHED =
            new java.util.concurrent.atomic.LongAdder();

    /** Rows matched; the texts differ — an EMISSION gap, not a bug. */
    public static final java.util.concurrent.atomic.LongAdder TEXT_DIVERGED =
            new java.util.concurrent.atomic.LongAdder();

    /** The row leg declined and TEXT was the verdict (§4 residue —
     * foreign dialects, by-design-unrunnable), by counted reason. */
    public static final java.util.concurrent.ConcurrentHashMap<String,
            java.util.concurrent.atomic.LongAdder> TEXT_VERDICT =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** Set when the sql-text arm CLAIMED an assert this thread (every
     * exit from that point is the arm's verdict). The dual-channel
     * probe consumes it to classify walk-vs-arm outcomes under their
     * OWN census — the walk judges TEXT, the arm judges ROWS, so their
     * divergence is DESIGNED and must not feed the pinned disagree
     * channel. */
    private static final ThreadLocal<Boolean> ARM_FIRED =
            ThreadLocal.withInitial(() -> Boolean.FALSE);

    public static void armFired() {
        ARM_FIRED.set(true);
    }

    /** Read-and-clear. */
    public static boolean consumeArmFired() {
        boolean v = ARM_FIRED.get();
        ARM_FIRED.set(false);
        return v;
    }

    public static void textVerdict(String reason) {
        if (PROBE.get()) {
            return;
        }
        TEXT_VERDICT.computeIfAbsent(reason,
                k -> new java.util.concurrent.atomic.LongAdder()).increment();
    }

    public static void textMatched() {
        if (!PROBE.get()) {
            TEXT_MATCHED.increment();
        }
    }

    public static void textDiverged() {
        if (!PROBE.get()) {
            TEXT_DIVERGED.increment();
        }
    }
}
