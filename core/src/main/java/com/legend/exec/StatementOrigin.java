// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0
package com.legend.exec;

/**
 * CENSUS: where every statement the platform or the test harness sends to a
 * database comes from — measurement only, printed by the corpus lanes, read by
 * no verdict. The north star (lean ladder, 2026-09-20) is ONE statement per test
 * body: every origin but {@link #BODY} is a statement outside it, and the census
 * names them by kind and by test so the next leg is chosen by count, not by
 * intuition. The origin is a thread-scoped mark ({@link #enter}) set by the site
 * that decides WHY a statement runs; the executor counts every statement under
 * the mark in force ({@link #count()}); harness senders that bypass the executor
 * count themselves.
 */
public enum StatementOrigin {
    /** The body's fused verdict statement (one per test body). */
    BODY,
    /** A body that fell back to per-assert statements. */
    FALLBACK,
    /** A let's frame RUN at the let (the host judge's path). */
    LET,
    /** A value-position execute: its result IS the value asked for. */
    VALUE,
    /** A runtime's declared setup statements and CSV loads (the seeding boundary). */
    SEED,
    /** Session setup: attach / use / settings / extension aliases. */
    SESSION,
    /** The read-only system metamodel database. */
    SYSTEM,
    /** The SQL-text referee running OUR plan or text for rows. */
    REFEREE_OURS,
    /** The SQL-text referee replaying the engine's golden SQL (and its seeds). */
    REFEREE_GOLDEN,
    /** A body's raw statement native ({@code executeInDb}, {@code dropAndCreate…InDb}) outside a fixture. */
    RAW,
    /** A verdict SIDE the judge evaluates outside the body's statement (the host judge's
     * sides; a database-mode shape the batch declined). */
    SIDE,
    /** A body statement that is neither a let frame nor an assert, executed on its own. */
    STATEMENT,
    /** The referee's H2 mirror replaying the seed ledger (the referee's cost, not the product's). */
    MIRROR_SEED,
    /** A metadata probe: reported columns, pivot keys. */
    PROBE,
    /** Test-data generation. */
    TDG,
    /** No site claimed the statement — the census's own residual. */
    OTHER;

    private static final ThreadLocal<StatementOrigin> CURRENT =
            ThreadLocal.withInitial(() -> OTHER);
    private static final java.util.concurrent.atomic.AtomicLong[] COUNTS =
            new java.util.concurrent.atomic.AtomicLong[values().length];

    static {
        for (int i = 0; i < COUNTS.length; i++) {
            COUNTS[i] = new java.util.concurrent.atomic.AtomicLong();
        }
    }

    /** The mark in force on this thread. */
    public static StatementOrigin current() {
        return CURRENT.get();
    }

    /** {@link #enter} only when no site has marked the thread yet — a site that
     * serves marked callers (a fixture's seeding, the referee) and unmarked ones.
     * {@link #STATEMENT} is the weak outer mark of a body statement: a raw native
     * or a side evaluated inside one names itself over it. */
    public static Scope enterIfUnmarked(StatementOrigin origin) {
        StatementOrigin now = CURRENT.get();
        return enter(now == OTHER || now == STATEMENT && origin != STATEMENT ? origin : now);
    }

    /** Sets the mark until the scope closes (restoring the previous one). */
    public static Scope enter(StatementOrigin origin) {
        StatementOrigin previous = CURRENT.get();
        CURRENT.set(origin);
        return new Scope(previous);
    }

    /** A scoped mark; closing restores what was in force before. */
    public record Scope(StatementOrigin previous) implements AutoCloseable {
        @Override
        public void close() {
            CURRENT.set(previous);
        }
    }

    /** One statement sent under the mark in force. */
    public static void count() {
        count(CURRENT.get());
    }

    public static void count(StatementOrigin origin) {
        COUNTS[origin.ordinal()].incrementAndGet();
    }

    /** The counts so far, by ordinal. */
    public static long[] snapshot() {
        long[] out = new long[COUNTS.length];
        for (int i = 0; i < out.length; i++) {
            out[i] = COUNTS[i].get();
        }
        return out;
    }

    /** {@code name=count …} for a snapshot (or a delta of two). */
    public static String census(long[] counts) {
        StringBuilder sb = new StringBuilder();
        for (StatementOrigin o : values()) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(o.name().toLowerCase(java.util.Locale.ROOT)).append('=')
                    .append(counts[o.ordinal()]);
        }
        return sb.toString();
    }
}
