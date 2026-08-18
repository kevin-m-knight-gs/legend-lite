// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.exec;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * THE corpus raw-SQL boundary (audit 23 / R0 relocation): the engine's test
 * corpus hands {@code executeInDb} LITERAL H2-flavored DDL/DML strings
 * (unquoted keyword column names, {@code CURRENT_TIMESTAMP()}, H2 type
 * kinds, H2 statement orderings). Something must translate them to the
 * executing backend, and it happens HERE — never in the dialect renderers,
 * which speak only the SQL IR, and never against platform-GENERATED SQL,
 * which is IR-rendered and must not pass through this class.
 *
 * <p>CONTRACT (aspirational until F7.4): text-level translation of
 * corpus-authored statements only — TODAY Java-generated DDL
 * (Ddl.setUpDataSqlsText, spelled H2-style on purpose) also routes
 * through here; RawSqlLedgerTest pins the caller set shrink-only.
 * This is the one sanctioned home for pattern-based SQL rewriting; adding a
 * recognizer anywhere else is an architecture violation (RUNNABILITY_PLAN
 * R0 rule). The designed replacement is the parser leg — raw statements
 * parsed into the SQL IR and rendered per dialect (LEGEND_SQL_VISION) —
 * at which point this class shrinks to the parser call.
 */
public final class RawSqlBoundary {

    private RawSqlBoundary() {
    }

    /** RAW-statement recorder (#67 H2 advisory backend): every corpus
     * statement passing this boundary is H2-flavored BY DEFINITION, so
     * the recorded stream replays verbatim on a real H2 to seed the
     * advisory second target. Installed per test by the harness; null =
     * off. */
    private static final ThreadLocal<List<String>> RECORDER =
            new ThreadLocal<>();

    public static void record(List<String> sink) {
        if (sink == null) {
            RECORDER.remove();
            META_RECORDER.remove();
        } else {
            RECORDER.set(sink);
            META_RECORDER.set(new java.util.ArrayList<>());
        }
    }

    public static @com.legend.Nullable List<String> recording() {
        return RECORDER.get();
    }

    /** Drop the most recently recorded statement — called by executors
     * when the statement FAILED on the session: the recording must
     * mirror executed reality or the H2 advisory replay dies on
     * statements the session itself rejected (family-session ledger,
     * task #112). Translation records eagerly; failure unrecords. */
    public static void unrecordLast() {
        List<String> sink = RECORDER.get();
        if (sink != null && !sink.isEmpty()) {
            sink.remove(sink.size() - 1);
        }
    }

    /** METADATA-ONLY side channel: engine DDL semantics DuckDB
     * deliberately skips (PRIMARY KEY constraints, schema creates) that
     * ONLY the fetchDb* metadata replay consumes. Kept OUT of the main
     * recording — the H2Verify row-replay stream must stay exactly the
     * corpus's own statements (a synthetic ALTER failing there would
     * downgrade row-verified tests to advisory). */
    private static final ThreadLocal<List<String>> META_RECORDER =
            new ThreadLocal<>();

    public static void recordMeta(String sql) {
        List<String> sink = META_RECORDER.get();
        if (sink != null) {
            sink.add(sql);
        }
    }

    public static @com.legend.Nullable List<String> metaRecording() {
        return META_RECORDER.get();
    }

    private static final Pattern CREATE_HEAD = Pattern.compile(
            "(?i)^\\s*create\\s+table\\s+[\\w.\"]+\\s*\\(");

    private static final Pattern INSERT_COLS = Pattern.compile(
            "(?i)^(\\s*insert\\s+into\\s+[\\w.\"]+\\s*\\()([^)]*)(\\))");

    // Hoisted out of String.replaceAll/matches call sites, which recompile
    // the pattern on EVERY invocation. Measured hot: a JFR profile of the
    // corpus sweep put Pattern.compile at 149 execution samples, 93 of them
    // under h2ToDuckDb and 75 under quoteCreateColumns — this boundary runs
    // per seeded statement, per test, across 2,019 corpus tests. Semantics
    // are unchanged: String.replaceAll(re, r) IS
    // Pattern.compile(re).matcher(s).replaceAll(r).
    private static final Pattern CURRENT_TS = Pattern.compile(
            "(?i)\\bCURRENT_TIMESTAMP\\(\\)");

    private static final Pattern DROP_SCHEMA = Pattern.compile(
            "(?i)\\bdrop\\s+schema\\s+(\\w+)\\s+if\\s+exists\\b");

    private static final Pattern CREATE_SCHEMA = Pattern.compile(
            "(?i)\\bcreate\\s+schema\\s+(?!if\\b)(\\w+)");

    private static final Pattern NON_COLUMN_HEAD = Pattern.compile(
            "(?i)primary|constraint|foreign|unique|check");

    private static final Pattern FLOAT_KIND = Pattern.compile("(?i)\\bFLOAT\\b");

    /** An UNALIASED {@code count(*)} projection item (followed by a comma
     * or FROM — never inside a larger expression, where an alias would be
     * a syntax error). H2's JDBC names it {@code COUNT(*)} and the engine
     * tests read {@code .value('COUNT(*)')} (F6.6 witness:
     * ddl::dropAndCreateTable); DuckDB names it {@code count_star()} —
     * the alias preserves H2's OBSERVABLE naming on the DuckDB target. */
    private static final Pattern COUNT_STAR_ITEM = Pattern.compile(
            "(?i)\\bcount\\(\\s*\\*\\s*\\)(?=\\s*(,|from\\b))");

    private static final Pattern BIT_KIND = Pattern.compile("(?i)\\bBIT\\b");

    private static final Pattern CLOB_KIND = Pattern.compile("(?i)\\bCLOB\\b");

    /**
     * One corpus-authored H2 statement, translated for DuckDB execution:
     * quote keyword column names in CREATE/INSERT column lists (legal
     * unquoted on H2, syntax errors on DuckDB), drop
     * {@code CURRENT_TIMESTAMP()} parens, and map H2 type KINDS on the
     * type part only (FLOAT is an 8-byte double on H2; BIT is a boolean).
     * Callers split multi-statement blobs first — recognizers anchor at
     * statement start.
     */
    /** Wall-clock spent translating raw H2 to DuckDB — perf instrument. */
    public static final java.util.concurrent.atomic.AtomicLong XLATE_NANOS =
            new java.util.concurrent.atomic.AtomicLong();

    public static String h2ToDuckDb(String sql) {
        long t0 = System.nanoTime();
        try {
            return h2ToDuckDb0(sql);
        } finally {
            XLATE_NANOS.addAndGet(System.nanoTime() - t0);
        }
    }

    private static String h2ToDuckDb0(String sql) {
        List<String> sink = RECORDER.get();
        if (sink != null) {
            sink.add(sql);
        }
        String out = CURRENT_TS.matcher(sql).replaceAll("CURRENT_TIMESTAMP");
        out = COUNT_STAR_ITEM.matcher(out)
                .replaceAll("count(*) AS \"COUNT(*)\"");
        // H2 accepts name-first `Drop schema <name> if exists cascade`
        // (corpus testTDSJoin.pure:1047); DuckDB only parses IF EXISTS
        // before the name
        out = DROP_SCHEMA.matcher(out).replaceAll("Drop schema if exists $1");
        // schema creation is idempotent at this boundary: H2 test dbs are
        // per-connection ephemeral, the DuckDB catalog persists across a
        // family's seeds — a re-run `create schema X` must not abort the
        // seed chain
        out = CREATE_SCHEMA.matcher(out).replaceAll("Create schema if not exists $1");
        Matcher cm = CREATE_HEAD.matcher(out);
        if (cm.find()) {
            return quoteCreateColumns(out, cm.end());
        }
        Matcher m = INSERT_COLS.matcher(out);
        if (!m.find()) {
            return out;
        }
        StringBuilder cols = new StringBuilder();
        for (String c : m.group(2).split(",")) {
            if (cols.length() > 0) {
                cols.append(", ");
            }
            String name = c.strip();
            cols.append(name.startsWith("\"") ? name : "\"" + name + "\"");
        }
        return m.group(1) + cols + m.group(3) + out.substring(m.end(3));
    }

    /**
     * Quote the column NAME of each top-level column definition in a
     * CREATE TABLE literal (constraint entries — PRIMARY KEY(...) etc —
     * pass through). The type part maps H2's FLOAT (an 8-byte double) to
     * DOUBLE (DuckDB's FLOAT is REAL) — on the TYPE PART only: a
     * whole-statement replace once renamed a column literally named
     * "float" (relationalSetUp testTable).
     */
    private static String quoteCreateColumns(String sql, int bodyStart) {
        int depth = 1;
        int end = bodyStart;
        while (end < sql.length() && depth > 0) {
            char c = sql.charAt(end);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            }
            end++;
        }
        String body = sql.substring(bodyStart, end - 1);
        List<String> parts = new ArrayList<>();
        int d = 0;
        int start = 0;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '(') {
                d++;
            } else if (c == ')') {
                d--;
            } else if (c == ',' && d == 0) {
                parts.add(body.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(body.substring(start));
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            String col = part.strip();
            if (out.length() > 0) {
                out.append(", ");
            }
            int sp = 0;
            while (sp < col.length() && !Character.isWhitespace(col.charAt(sp))
                    && col.charAt(sp) != '(') {
                sp++;
            }
            String head = col.substring(0, sp);
            if (col.startsWith("\"")) {
                // pre-quoted name (model-derived DDL quotes fully): the
                // TYPE PART still needs the H2->DuckDB kind mapping
                int endq = col.indexOf('"', 1);
                out.append(col, 0, endq + 1)
                        .append(mapColumnTypes(col.substring(endq + 1)));
            } else if (NON_COLUMN_HEAD.matcher(head).matches()) {
                out.append(col);
            } else {
                // H2 semantics on the TYPE PART only: FLOAT is an 8-byte
                // double; BIT is a boolean (DuckDB's BIT is a bitstring)
                out.append('\"').append(head).append('\"')
                        .append(mapColumnTypes(col.substring(sp)));
            }
        }
        return sql.substring(0, bodyStart) + out + sql.substring(end - 1);
    }

    /**
     * H2 semantics on the TYPE PART only: FLOAT is an 8-byte double; BIT is a
     * boolean (DuckDB's BIT is a bitstring). Extracted so the three patterns
     * are compiled once rather than per column per statement per test — this
     * ran on the corpus seed path 2,019 times over.
     */
    private static String mapColumnTypes(String typePart) {
        String t = FLOAT_KIND.matcher(typePart).replaceAll("DOUBLE");
        t = BIT_KIND.matcher(t).replaceAll("BOOLEAN");
        return CLOB_KIND.matcher(t).replaceAll("TEXT");
    }
}
