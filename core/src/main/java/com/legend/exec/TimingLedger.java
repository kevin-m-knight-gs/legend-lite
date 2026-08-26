// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.exec;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * TEMPORARY exact phase accounting for the G4-vs-G5 investigation
 * (2026-08-15): named nanotime accumulators, deterministic (no
 * sampling), dumped to {@code target/timing-ledger.txt} at JVM exit.
 * Sampling profilers answered "what burns CPU"; this answers "where
 * does the WALL CLOCK go", including IO and native waits, and must
 * reconcile against the suite total.
 */
public final class TimingLedger {

    private static final Map<String, AtomicLong> NS = new ConcurrentHashMap<>();
    private static final Map<String, AtomicLong> COUNT = new ConcurrentHashMap<>();
    private static final long JVM_START = System.nanoTime();

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(TimingLedger::dump));
    }

    private TimingLedger() {
    }

    public static void add(String bucket, long nanos) {
        NS.computeIfAbsent(bucket, k -> new AtomicLong()).addAndGet(nanos);
        COUNT.computeIfAbsent(bucket, k -> new AtomicLong()).incrementAndGet();
    }

    public static void dump() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("jvm.wall\t%.1fs%n",
                (System.nanoTime() - JVM_START) / 1e9));
        for (Map.Entry<String, AtomicLong> e : new TreeMap<>(NS).entrySet()) {
            AtomicLong n = COUNT.get(e.getKey());
            sb.append(String.format("%s\t%.2fs\tn=%d%n", e.getKey(),
                    e.getValue().get() / 1e9,
                    n == null ? 0 : n.get()));
        }
        // ALSO print (the census idiom): the file write below resolved
        // against the fork's working dir and failed SILENTLY on the
        // corpus lane for weeks (root has no target/) — the G4 phase
        // breakdown was vanishing exactly when timing questions came
        // up. The log line survives any working dir.
        for (String line : sb.toString().split("\n")) {
            System.out.println("[timing] " + line);
        }
        try {
            java.nio.file.Files.writeString(
                    java.nio.file.Path.of("target", "timing-ledger.txt"),
                    sb.toString());
        } catch (java.io.IOException ignore) {
            // no target/ dir (non-Maven invocation): the ledger is a
            // best-effort diagnostic file, never load-bearing
        }
    }
}
