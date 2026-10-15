// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.testcases;

import com.legend.json.Json;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * SQL Anywhere 17's answers to the {@code #IQ{...}#} island's parity cases, as {@code tools/iq-oracle} snaps them into
 * {@code iq-parity/iq-answers.jsonl} (its README.md, "The files"): each run's ({@code key@profile}) column names and
 * SQL Anywhere types with its rows of text cells, or its error, stamped with the {@code sqlSha} of the SQL, the
 * profile and schema.yaml. Read here into {@link SqlParity}'s answers -- each text cell a grid cell by its column's
 * type -- and guarded against the cases, so the file cannot silently fall behind them: a run with no answer is
 * UNSNAPPED, an answer whose {@code sqlSha} is not its run's is STALE, an answer of no live run is GONE; each fails the
 * lane. Re-snap with {@code tools/iq-oracle/snap.sh --write}.
 *
 * <p>IQ island, landing 4 (WP1), 2026-10-14: the file holds a second oracle's answers too -- a real SAP IQ 17.1's (the
 * user's HANA Cloud data lake), snapped by {@code snap.sh --oracle iq} for the {@code iq17-*} runs, each marked
 * {@code "oracle":"iq"} with the server's version and database properties. Such an answer is IQ's own, never a
 * decision's rows ({@link #expected}), and stamped without the case's decision ({@link #sqlSha(IqParity.Run, byte[])});
 * a run real IQ cannot judge here (a database property or PUBLIC option other than the server's, a SET at the user's
 * or PUBLIC's level, a statement that hangs IQ) is answered {@code not_judged} with the reason, and counted SKIP.
 */
public final class IqAnswers {

    private IqAnswers() {
    }

    /** SQL Anywhere's answer to a run, as snapped: its {@code sqlSha}, and its result (column names and types) or its
     *  error. */
    public record Snap(String key, String profile, String sqlSha, List<String> columns, List<String> types,
            @com.legend.base.Nullable List<List<@com.legend.base.Nullable String>> rows,
            @com.legend.base.Nullable String error, List<Integer> scales, String judge,
            @com.legend.base.Nullable String notJudged) {

        /** SQL Anywhere's answer (IQ island, landing 4: {@code judge}, the oracle that answered, {@code sa} or
         *  {@code iq}, and {@code notJudged}, why the live IQ could not judge the run, are the live IQ's answers'). */
        public Snap(String key, String profile, String sqlSha, List<String> columns, List<String> types,
                @com.legend.base.Nullable List<List<@com.legend.base.Nullable String>> rows,
                @com.legend.base.Nullable String error, List<Integer> scales) {
            this(key, profile, sqlSha, columns, types, rows, error, scales, "sa", null);
        }

        /** An answer of no recorded scales (a test's). */
        public Snap(String key, String profile, String sqlSha, List<String> columns, List<String> types,
                @com.legend.base.Nullable List<List<@com.legend.base.Nullable String>> rows,
                @com.legend.base.Nullable String error) {
            this(key, profile, sqlSha, columns, types, rows, error, List.of());
        }
    }

    /** The answers file: {@code key@profile} to the snapped answer (its first line, the run's meta, skipped). */
    public static Map<String, Snap> read(Path file) throws IOException {
        Map<String, Snap> answers = new LinkedHashMap<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            Json.Obj o = (Json.Obj) Json.parse(line);
            if (o.getOr("id", null) == null) {
                continue;
            }
            String key = ((Json.Str) o.get("id")).value();
            String profile = ((Json.Str) o.get("profile")).value();
            String sha = ((Json.Str) o.get("sqlSha")).value();
            Json.Node error = o.getOr("error", null);
            String judge = o.getOr("oracle", null) instanceof Json.Str by ? by.value() : "sa";
            Snap snap;
            if (o.getOr("not_judged", null) instanceof Json.Str why) {
                // IQ island, landing 4 (WP1): a run the live IQ cannot judge, and why
                snap = new Snap(key, profile, sha, List.of(), List.of(), null, null, List.of(), judge, why.value());
            } else if (error instanceof Json.Obj err) {
                snap = new Snap(key, profile, sha, List.of(), List.of(), null, "SQLCODE "
                        + ((Json.Num) err.get("sqlcode")).longValue() + ", SQLSTATE "
                        + ((Json.Str) err.get("sqlstate")).value() + ": " + ((Json.Str) err.get("message")).value(),
                        List.of(), judge, null);
            } else {
                List<String> columns = new ArrayList<>();
                List<String> types = new ArrayList<>();
                List<Integer> scales = new ArrayList<>();
                for (Json.Node n : ((Json.Arr) o.get("columns")).items()) {
                    List<Json.Node> c = ((Json.Arr) n).items();
                    columns.add(((Json.Str) c.get(0)).value());
                    types.add(((Json.Str) c.get(1)).value());
                    scales.add(c.size() > 3 ? (int) ((Json.Num) c.get(3)).longValue() : 0);
                }
                snap = new Snap(key, profile, sha, columns, types, cells(o.get("rows")), null, scales, judge, null);
            }
            answers.put(key + "@" + profile, snap);
        }
        return answers;
    }

    /** Rows of text cells (or null), as the answers file and a case's {@code expect} write them. */
    private static List<List<@com.legend.base.Nullable String>> cells(Json.Node rows) {
        List<List<@com.legend.base.Nullable String>> out = new ArrayList<>();
        for (Json.Node r : ((Json.Arr) rows).items()) {
            List<@com.legend.base.Nullable String> row = new ArrayList<>();
            for (Json.Node c : ((Json.Arr) r).items()) {
                row.add(c instanceof Json.Str s ? s.value() : null);
            }
            out.add(row);
        }
        return out;
    }

    /** What the run's judge says: SQL Anywhere's snapped answer, or the decided rows of the case's decision, read by
     *  the snapped answer's column types. */
    public static SqlParity.Answer expected(IqParity.Run run, Snap snap) {
        // IQ island, landing 4 (WP1): real IQ judges an iq17-* run by its own answer, never a decision's rows
        String expect = IqParity.liveIq(run.profile()) ? null : run.c().expect();
        if (expect != null) {
            if (snap.rows() == null) {
                throw new IllegalStateException(run.key() + ": a decided case whose snapped answer is an error has no"
                        + " column types for its rows");
            }
            return new SqlParity.Rows(grid(snap.columns(), snap.types(), cells(Json.parse(expect))));
        }
        if (snap.error() != null) {
            return new SqlParity.Rejected("", java.util.Objects.requireNonNull(snap.error()));
        }
        return new SqlParity.Rows(grid(snap.columns(), snap.types(), java.util.Objects.requireNonNull(snap.rows())));
    }

    private static SqlParity.Grid grid(List<String> columns, List<String> types,
            List<List<@com.legend.base.Nullable String>> rows) {
        List<List<Object>> out = new ArrayList<>();
        for (List<@com.legend.base.Nullable String> row : rows) {
            if (row.size() != columns.size()) {
                throw new IllegalStateException("a row of " + row.size() + " cells for " + columns.size() + " columns");
            }
            List<Object> cells = new ArrayList<>();
            for (int i = 0; i < row.size(); i++) {
                String v = row.get(i);
                cells.add(v == null ? null : cell(v, types.get(i)));
            }
            out.add(cells);
        }
        return new SqlParity.Grid(columns, out);
    }

    /** SQL Anywhere's integer types: their cells are integers, compared exactly. A BIT is one: IQ's BIT is the
     *  integer 0 or 1, not a truth value (the island reads a BIT column so). */
    static final java.util.Set<String> INTEGER_TYPES = java.util.Set.of("integer", "smallint", "bigint", "tinyint",
            "unsigned int", "unsigned bigint", "unsigned smallint", "bit");

    /** A snapped cell as a grid cell, by SQL Anywhere's type: an integer (a BIT too) a BigInteger, a numeric a
     *  BigDecimal ({@code .01} as written), a double a Double, a timestamp in SqlParity's spelling (no zeros ending its
     *  fraction), anything else text. */
    static Object cell(String v, String type) {
        if (v.equals("?") && !type.contains("char") && !type.equals("string") && !type.contains("text")) {
            // SQL Anywhere's "a value it cannot show": tools/iq-oracle never snaps one (review 8, m3)
            throw new IllegalStateException("a snapped '?' cell in a " + type + " column: SQL Anywhere could not show"
                    + " the value; skip the case (tools/iq-oracle refuses to snap it)");
        }
        if (INTEGER_TYPES.contains(type)) {
            return new BigInteger(v);
        }
        return switch (type) {
            case "numeric", "decimal" -> new BigDecimal(v);
            case "double", "float", "real" -> Double.valueOf(v);
            case "timestamp" -> {
                String fraction = v.length() > 19 ? v.substring(20).replaceAll("0+$", "") : "";
                yield fraction.isEmpty() ? v.substring(0, 19) : v.substring(0, 19) + "." + fraction;
            }
            default -> v;
        };
    }

    /** {@code sha256(sql 0x00 profile 0x00 decision 0x00 expect 0x00 schema.yaml)}, lower-case hex (tools/iq-oracle's
     *  format 2; the decision and its rows empty for a case SQL Anywhere judges): what an answer is stamped with, so
     *  editing a case's SQL, its profile, its decided rows or the schema makes its answer STALE. */
    public static String sqlSha(String sql, String profile, @com.legend.base.Nullable String decision,
            @com.legend.base.Nullable String expect, byte[] schema) {
        return sqlSha(sql, profile, decision, expect, null, schema);
    }

    /** {@link #sqlSha}, IQ island, 2026-10-14 (M1e): with the case's {@code params}, when it binds variables (one more
     *  part; a case without them keeps its sha). */
    public static String sqlSha(String sql, String profile, @com.legend.base.Nullable String decision,
            @com.legend.base.Nullable String expect, @com.legend.base.Nullable String params, byte[] schema) {
        return sqlSha(sql, profile, decision, expect, params, null, schema);
    }

    /** {@link #sqlSha}, IQ island, 2026-10-14 (the options framework): with the case's {@code settings} (IQ's settings
     *  by level, {@code database.<name>=<value>, query.<name>=<value>}), when it has any, as one more part {@code settings=<text>} after the params' (a case without them keeps
     *  its sha). */
    public static String sqlSha(String sql, String profile, @com.legend.base.Nullable String decision,
            @com.legend.base.Nullable String expect, @com.legend.base.Nullable String params,
            @com.legend.base.Nullable String settings, byte[] schema) {
        try {
            MessageDigest d = MessageDigest.getInstance("SHA-256");
            List<String> parts = new ArrayList<>(List.of(sql, profile, decision == null ? "" : decision,
                    expect == null ? "" : expect));
            if (params != null) {
                parts.add(params);
            }
            if (settings != null) {
                parts.add("settings=" + settings);
            }
            for (String part : parts) {
                d.update(part.getBytes(StandardCharsets.UTF_8));
                d.update((byte) 0);
            }
            d.update(schema);
            return HexFormat.of().formatHex(d.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The {@code sqlSha} a run's answer is stamped with: its case's SQL, the profile, the decision and its rows (none
     *  for an iq17-* run, which real IQ judges: IQ island, landing 4, WP1), its params and settings, schema.yaml. */
    public static String sqlSha(IqParity.Run r, byte[] schema) {
        boolean liveIq = IqParity.liveIq(r.profile());
        return sqlSha(r.c().sql(), r.profile().spelling(), liveIq ? null : r.c().decision(),
                liveIq ? null : r.c().expect(), r.c().params(), r.c().settings(), schema);
    }

    /** The answers file against the runs: UNSNAPPED (a run with no answer), STALE (an answer whose sqlSha is not its
     *  run's), GONE (an answer of no live run). */
    public static List<String> guards(List<IqParity.Run> runs, Map<String, Snap> answers, byte[] schema) {
        List<String> problems = new ArrayList<>();
        Set<String> live = new java.util.HashSet<>();
        for (IqParity.Run r : runs) {
            if (r.c().skip() != null) {
                continue;
            }
            live.add(r.key());
            Snap snap = answers.get(r.key());
            boolean liveIq = IqParity.liveIq(r.profile());
            if (snap == null) {
                problems.add("UNSNAPPED: " + r.key() + " has no answer in iq-answers.jsonl (tools/iq-oracle/snap.sh"
                        + (liveIq ? " --oracle iq" : "") + " --write)");
            } else if (!snap.sqlSha().equals(sqlSha(r, schema))) {
                problems.add("STALE: " + r.key() + "'s answer was snapped for other SQL, profile, decided rows or"
                        + " schema");
            } else if (!snap.judge().equals(liveIq ? "iq" : "sa")) {
                // IQ island, landing 4 (WP1): an iq17-* run is judged by the live IQ only, any other by SQL Anywhere
                problems.add("ORACLE: " + r.key() + "'s answer is " + snap.judge() + "'s, and its judge is "
                        + (liveIq ? "the live IQ's (snap.sh --oracle iq)" : "SQL Anywhere's (snap.sh)"));
            }
        }
        for (String k : answers.keySet()) {
            if (!live.contains(k)) {
                problems.add("GONE: " + k + " is answered in iq-answers.jsonl and is no live case");
            }
        }
        return problems;
    }
}
