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
 */
public final class ExecutionTrace {
    private ExecutionTrace() {
    }

    private static final ThreadLocal<String> LAST = new ThreadLocal<>();

    /** The statement text as the database receives it: the trace comment
     * line, then the SQL. Publishes the comment as the last stamp. */
    public static String stamp(String sql) {
        String comment = "-- \"executionTraceID\" : \""
                + java.util.UUID.randomUUID() + "\"";
        LAST.set(comment);
        return comment + "\n" + sql;
    }

    /** The comment of the most recent stamped execution on this thread
     * (null before any). */
    public static @com.legend.Nullable String lastComment() {
        return LAST.get();
    }
}
