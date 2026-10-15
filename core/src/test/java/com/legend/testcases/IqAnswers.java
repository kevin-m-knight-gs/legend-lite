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
 */
public final class IqAnswers {

    private IqAnswers() {
    }

    /** SQL Anywhere's answer to a run, as snapped: its {@code sqlSha}, and its result (column names and types) or its
     *  error. */
    public record Snap(String key, String profile, String sqlSha, List<String> columns, List<String> types,
            @com.legend.base.Nullable List<List<@com.legend.base.Nullable String>> rows,
            @com.legend.base.Nullable String error, List<Integer> scales) {

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
            Snap snap;
            if (error instanceof Json.Obj err) {
                snap = new Snap(key, profile, sha, List.of(), List.of(), null, "SQLCODE "
                        + ((Json.Num) err.get("sqlcode")).longValue() + ", SQLSTATE "
                        + ((Json.Str) err.get("sqlstate")).value() + ": " + ((Json.Str) err.get("message")).value());
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
                snap = new Snap(key, profile, sha, columns, types, cells(o.get("rows")), null, scales);
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
        String expect = run.c().expect();
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
        try {
            MessageDigest d = MessageDigest.getInstance("SHA-256");
            List<String> parts = new ArrayList<>(List.of(sql, profile, decision == null ? "" : decision,
                    expect == null ? "" : expect));
            if (params != null) {
                parts.add(params);
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
            if (snap == null) {
                problems.add("UNSNAPPED: " + r.key() + " has no answer in iq-answers.jsonl (tools/iq-oracle/snap.sh"
                        + " --write)");
            } else if (!snap.sqlSha().equals(sqlSha(r.c().sql(), r.profile().spelling(), r.c().decision(),
                    r.c().expect(), r.c().params(), schema))) {
                problems.add("STALE: " + r.key() + "'s answer was snapped for other SQL, profile, decided rows or"
                        + " schema");
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
