// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0
package com.legend.exec;

/**
 * The engine's EXECUTION TRACE stamp (batch 83): every relational
 * statement the engine executes is prefixed with the SQL comment
 * {@code -- "executionTraceID" : "<uuid>"} (its RelationalExecutor mints
 * the id per execution) and the RelationalActivity records that comment.
 * This platform does the same at its one JDBC boundary: the statement
 * the database receives carries the comment, and the last stamp is
 * published (thread-local) for the activity row of the frame that just
 * ran — one fact, recorded where it happened, never invented at the row.
 * The stamp is state of the execution ENVIRONMENT (batch 137), not the
 * thread.
 */
public final class ExecutionTrace {

    /** The comment of the most recent stamped execution on THIS trace
     * (null before any) — per-environment state since batch 137 (Phase
     * 2b), a thread-local before. */
    private @com.legend.Nullable String last;

    /** The statement text as the database receives it: the trace comment
     * line, then the SQL. Publishes the comment as this trace's last stamp. */
    public String stamp(String sql) {
        String comment = comment();
        last = comment;
        return comment + "\n" + sql;
    }

    public @com.legend.Nullable String lastComment() {
        return last;
    }

    /** A stamped statement whose comment nobody will read (no trace given). */
    public static String stampOnly(String sql) {
        return comment() + "\n" + sql;
    }

    private static String comment() {
        return "-- \"executionTraceID\" : \"" + java.util.UUID.randomUUID() + "\"";
    }
}
