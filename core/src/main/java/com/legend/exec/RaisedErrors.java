// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.exec;

/**
 * THE PROVENANCE SEAM for platform-raised error messages (truthfulness
 * burn B7, ADAPTER_NECESSITY_CENSUS §5c).
 *
 * <p>The lowering raises pure's own error messages IN SQL (the
 * {@code SqlFn.ERROR} guards — domain checks, bounds, bit-shift
 * limits), and every driver wraps a raised message in its own
 * transport envelope (DuckDB prefixes {@code Invalid Input Error: };
 * H2 appends statement context to SIGNAL text). The message inside is
 * OURS — composed by the compiler, pure's exact text — and the
 * envelope is the carrier's.
 *
 * <p>The renderer marks every raised message with a U+001F sentinel at
 * BOTH ends; this seam extracts BETWEEN the sentinels, which removes
 * the envelope wherever the driver put it (prefix, suffix, both). A
 * message WITHOUT the sentinel pair is a NATIVE database error and
 * passes through untouched — its error class and envelope survive, so
 * an envelope-blind strip can never launder a native error into pure's
 * expected text (the deep-audit H4 class-erasure weakness dies here).
 * ONE owner: the old per-consumer strips (the PCT adapter's
 * remapErrorMessage, AssertErrorNative's broad {@code "* Error: "}
 * regex) are deleted in the same batch.
 */
public final class RaisedErrors {

    /** U+001F (unit separator) — the provenance mark the renderer puts
     * around raised messages. Never appears in pure's own error text. */
    public static final char SENTINEL = '\u001F';

    private RaisedErrors() {
    }

    /** The message with the transport envelope removed IF this is a
     * platform-raised message (sentinel pair present); unchanged
     * otherwise. */
    public static @com.legend.Nullable String unwrap(
            @com.legend.Nullable String message) {
        if (message == null) {
            return null;
        }
        int first = message.indexOf(SENTINEL);
        int last = message.lastIndexOf(SENTINEL);
        return first >= 0 && last > first
                ? message.substring(first + 1, last)
                : message;
    }

    /** Funnel rethrow: a raised-message SQLException re-surfaces with
     * the clean text (state and cause preserved); everything else
     * returns as-is. */
    public static java.sql.SQLException unwrapped(java.sql.SQLException e) {
        String msg = e.getMessage();
        String clean = unwrap(msg);
        if (clean == null || clean.equals(msg)) {
            return e;
        }
        return new java.sql.SQLException(clean, e.getSQLState(), e);
    }
}
