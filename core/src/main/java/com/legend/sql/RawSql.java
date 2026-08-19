// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.sql;

import java.util.ArrayList;
import java.util.List;

/**
 * Raw-SQL string utilities for the K-native {@code executeInDb} boundary:
 * a caller-supplied SQL BLOB (engine tests pass several statements in one
 * string) is split into single statements before execution. Dialect
 * adaptation of the statement text itself lives on
 * {@link com.legend.exec.RawSqlBoundary}.
 */
public final class RawSql {

    private RawSql() {
    }

    /** Split a SQL blob into single statements on top-level {@code ;} (string-aware). */
    public static List<String> splitStatements(String sql) {
        List<String> out = new ArrayList<>();
        int start = 0;
        int i = 0;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            if (c == '\'') {
                i = skipString(sql, i);
                continue;
            }
            if (c == ';') {
                String stmt = sql.substring(start, i).strip();
                if (!stmt.isEmpty()) {
                    out.add(stmt);
                }
                start = i + 1;
            }
            i++;
        }
        String tail = sql.substring(start).strip();
        if (!tail.isEmpty()) {
            out.add(tail);
        }
        return out;
    }

    /** First keywords that mark a statement as a row-producing READ —
     * the query/statement split (One-Platform Plan Phase 1c): a single
     * statement opening with one of these is a RELATION source; anything
     * else (DDL, DML, multi-statement blobs) is an EFFECT and keeps the
     * opaque execute-once path. */
    private static final java.util.Set<String> QUERY_KEYWORDS = java.util.Set.of(
            "SELECT", "WITH", "VALUES", "TABLE", "SHOW", "DESCRIBE");

    /** True iff {@code sql} is exactly ONE statement and that statement
     * is a row-producing READ (first keyword in {@link #QUERY_KEYWORDS},
     * comments skipped). The Typer's gate for typing an
     * {@code executeInDb} literal as a relation. */
    public static boolean isSingleQuery(String sql) {
        List<String> stmts = splitStatements(sql);
        if (stmts.size() != 1) {
            return false;
        }
        String s = skipLeadingComments(stmts.get(0));
        int e = 0;
        while (e < s.length() && Character.isLetter(s.charAt(e))) {
            e++;
        }
        return QUERY_KEYWORDS.contains(s.substring(0, e).toUpperCase(java.util.Locale.ROOT));
    }

    /** The statement with leading whitespace, {@code -- line} and
     * {@code /* block *}{@code /} comments removed. */
    private static String skipLeadingComments(String s) {
        int i = 0;
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '-' && i + 1 < n && s.charAt(i + 1) == '-') {
                int nl = s.indexOf('\n', i);
                i = nl < 0 ? n : nl + 1;
            } else if (c == '/' && i + 1 < n && s.charAt(i + 1) == '*') {
                int end = s.indexOf("*/", i + 2);
                i = end < 0 ? n : end + 2;
            } else {
                break;
            }
        }
        return s.substring(i);
    }

    /** Index just past the string literal opening at {@code i} (SQL {@code ''} doubling handled). */
    private static int skipString(String source, int i) {
        int n = source.length();
        i++;   // opening quote
        while (i < n) {
            char c = source.charAt(i);
            if (c == '\'') {
                if (i + 1 < n && source.charAt(i + 1) == '\'') {
                    i += 2;   // doubled quote = escaped quote inside the literal
                    continue;
                }
                return i + 1;
            }
            i++;
        }
        return n;
    }
}
