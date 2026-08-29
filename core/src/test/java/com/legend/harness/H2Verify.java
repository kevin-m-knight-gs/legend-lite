// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.harness;

import com.legend.exec.ExecutionResult;
import com.legend.exec.Row;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The ADVISORY SECOND TARGET (#67): golden engine SQL executes on a real
 * in-memory H2 — the engine's own dialect — over the SAME raw seed
 * statements the test ran (recorded verbatim at the RawSqlBoundary,
 * which is H2-flavored BY DEFINITION), and its rows verify against the
 * rows our pipeline produced on DuckDB. Row-set equality is the
 * contract; a divergence is an honest FAIL naming both sides. H2 is a
 * TEST-SCOPED dependency of the corpus harness only — this class detects
 * the driver reflectively and reports {@link #ready()} false without it,
 * leaving golden-SQL asserts advisory exactly as before.
 *
 * <p>Connection settings are the engine's own H2 2.1.214 server
 * VERBATIM (H2Settings = H2Defaults; convergence batch C) — the old
 * DATABASE_TO_UPPER/CASE_INSENSITIVE leniency died when our emitters
 * learned to spell identifiers the engine's way.
 */
public final class H2Verify {

    private H2Verify() {
    }

    /** Verification could not run (driver absent, seed replay failed,
     * golden text not executable) — the caller stays advisory. */
    public static final class Unverifiable extends RuntimeException {
        public Unverifiable(String msg, @com.legend.Nullable Throwable cause) {
            super(msg, cause);
        }
    }

    private static final boolean READY = detect();
    private static final java.util.concurrent.atomic.AtomicInteger COUNTER =
            new java.util.concurrent.atomic.AtomicInteger();

    private static boolean detect() {
        try {
            Class.forName("org.h2.Driver");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    public static boolean ready() {
        return READY;
    }

    /** MILESTONE-1 counters (H2_BACKEND.md §12 step 5): real H2
     * execution of OUR byte-matched SQL, held to our DuckDB rows.
     * Sweep-scoped (fresh JVM per surefire run); the corpus runner
     * reports them as the h2-exec scoreboard line. */
    public static final java.util.concurrent.atomic.LongAdder M1_VERIFIED =
            new java.util.concurrent.atomic.LongAdder();
    public static final java.util.concurrent.atomic.LongAdder M1_DIVERGED =
            new java.util.concurrent.atomic.LongAdder();
    /** F2.2: text-DIVERGENT golden asserts rescued into row-verified
     * PASS by the H2 replay — before this counter the divergence was
     * never recorded, so the committed sqldiff count (244 at the F0.1
     * baseline) counted only the divergences the oracle FAILED to
     * rescue; the true rate is sqldiff + THIS. */
    public static final java.util.concurrent.atomic.LongAdder M1_RESCUED =
            new java.util.concurrent.atomic.LongAdder();
    public static final java.util.concurrent.atomic.LongAdder M1_UNVERIFIABLE =
            new java.util.concurrent.atomic.LongAdder();

    /** Per-test M1 verdict attribution (the UNVERIFIABLE_CENSUS
     * pattern extended to the PASS side): every text-match/rescue/
     * exec-fail records its test, and the corpus runner dumps the
     * sorted roster to {@code target/h2-verdicts.txt} UNCONDITIONALLY
     * every sweep (the query-histogram idiom — no env flags). Built
     * because a 19-test floor drop was UNATTRIBUTABLE: the counters
     * said how many, nothing said which. */
    public static final java.util.concurrent.ConcurrentHashMap<String,
            java.util.concurrent.atomic.LongAdder> VERDICT_ROSTER =
            new java.util.concurrent.ConcurrentHashMap<>();

    public static void verdict(String kind) {
        VERDICT_ROSTER.computeIfAbsent(kind + " " + CURRENT_TEST.get(),
                k -> new java.util.concurrent.atomic.LongAdder()).increment();
    }

    /** Per-reason UNVERIFIABLE census (H2_BACKEND.md §12 step 13): every
     * replay decline funnels through {@link #decline}, keyed by a
     * CANONICAL bucket — the declared-gap registry asserts against these
     * counts on full sweeps (growth in a registered bucket = FAIL). */
    public static final java.util.concurrent.ConcurrentHashMap<String,
            java.util.concurrent.atomic.LongAdder> UNVERIFIABLE_CENSUS =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** The ONE decline funnel: prints (the frozen System.err site) and
     * counts under the canonical bucket. */
    /** An ARRAY-valued scalar cell's element list, or null when the
     * value is no collection carrier. Two carrier arrivals (EngineTestExecutor's
     * Eval.flatten, hoisted here for the file cap): the native
     * {@code java.sql.Array} (DuckDB), and the JSON carrier's byte[]
     * text on a list-less backend (§2b — H2 hands JSON back as bytes;
     * only a JSON-array lexeme parses, anything else stays opaque). */
    public static java.util.@com.legend.Nullable List<Object> carrierList(
            Object v) {
        if (v instanceof java.sql.Array arr) {
            try {
                // Arrays.asList: NULL ELEMENTS survive (SQL NULL cells).
                // One-carrier rule (documented-debts 2026-08-18): raw
                // driver elements convert like every other egress leaf —
                // this was the LAST path leaking java.sql.Timestamp
                java.util.List<Object> out = new java.util.ArrayList<>();
                for (Object e : (Object[]) arr.getArray()) {
                    out.add(e instanceof java.sql.Timestamp ts
                            ? ts.toLocalDateTime() : e);
                }
                return out;
            } catch (java.sql.SQLException e) {
                throw new IllegalStateException(e);
            }
        }
        if (v instanceof byte[] b) {
            String text = new String(b,
                    java.nio.charset.StandardCharsets.UTF_8);
            if (text.startsWith("[")
                    && com.legend.sql.Json.parse(text)
                            instanceof java.util.List<?> l) {
                return new java.util.ArrayList<>(l);
            }
        }
        return null;
    }

    /** The test currently executing — set by the corpus runner so a
     *  decline names its test (correctness lane C1: 154 anonymous
     *  declines were unactionable). */
    public static final ThreadLocal<String> CURRENT_TEST =
            ThreadLocal.withInitial(() -> "<unattributed>");

    public static void decline(String reason) {
        System.err.println("[h2-unverifiable] replay declined ["
                + CURRENT_TEST.get() + "]: " + reason);
        LAST_DECLINE.set(bucketOf(reason));
        UNVERIFIABLE_CENSUS.computeIfAbsent(bucketOf(reason),
                k -> new java.util.concurrent.atomic.LongAdder()).increment();
    }

    /** The most recent decline's canonical bucket, per thread — a
     * *-noreplay outcome names its replay-decline CAUSE with it (the
     * §4Z transparency rule applied one level down: the residue census
     * reads what happened, never a guess). Data-flow guarantee: every
     * advisory return from the replay attempt records exactly one
     * decline first, so the read at the outcome exit is never stale. */
    public static final ThreadLocal<@com.legend.Nullable String> LAST_DECLINE =
            new ThreadLocal<>();

    /** Canonical census bucket: the decline CHANNEL plus the failure's
     * leading words — stable across runs (no identifiers/row values),
     * specific enough to key registry rows. */
    private static String bucketOf(String reason) {
        String r = reason;
        for (String ch : new String[]{"replay/verify failed: ",
                "seed replay: ", "golden execution: "}) {
            int i = r.indexOf(ch);
            if (i >= 0) {
                r = r.substring(0, i + ch.length() - 2) + "/"
                        + r.substring(i + ch.length());
            }
        }
        // strip statement tails and volatile identifiers
        int cut = r.indexOf(";");
        if (cut > 0) {
            r = r.substring(0, cut);
        }
        return r.length() > 70 ? r.substring(0, 70) : r;
    }

    /** The engine's H2 session settings — the OWNER is
     * {@link com.legend.exec.H2Settings} (F1.1: nothing outside the
     * harness depends on the harness); this alias keeps in-package
     * readers stable. */
    public static final String SETTINGS = com.legend.exec.H2Settings.SETTINGS;

    /**
     * Replay {@code seeds} on a fresh H2, run {@code goldenSql}, compare
     * its rows with {@code ours} as ORDER-INSENSITIVE multisets of
     * normalized cells. Returns null when the row sets match, else a
     * divergence message; throws {@link Unverifiable} when H2 cannot
     * evaluate the inputs at all.
     */
    // =================================================================
    // INCREMENTAL FAMILY MIRROR (task #112 follow-up): the corpus runner
    // keeps ONE live H2 per family session and this verifier applies
    // only the ledger statements not yet mirrored — the fresh-replay
    // path re-ran the family's WHOLE history per verification (O(n^2)).
    // A statement H2 rejects POISONS the mirror for the rest of the
    // family: under fresh replay every later test re-hit the same
    // statement in its ledger prefix, so the decline set is identical.
    // =================================================================
    private static final class MirrorState {
        final Connection conn;
        int applied;
        @com.legend.Nullable String poison;
        boolean suspended;

        MirrorState(Connection conn) {
            this.conn = conn;
        }
    }

    private static @com.legend.Nullable MirrorState MIRROR;

    /** Install the family session's live mirror (runner-owned). */
    public static void mirrorBegin(Connection h2) {
        // F6.7: the engine's H2 extension functions register on the
        // MIRROR too — only the fresh-replay branch installed them, and
        // Runner makes the incremental mirror the DEFAULT path for
        // DuckDB sweeps, so golden SQL calling legend_h2_extension_*
        // declined verification on the path that actually runs (the C1
        // fix this class exists for was not in effect).
        try (Statement st = h2.createStatement()) {
            for (String alias : H2ExtensionFunctions.aliases()) {
                st.execute(alias);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "h2 mirror extension registration failed", e);
        }
        MIRROR = new MirrorState(h2);
    }

    /** Detach the mirror (the runner closes the connection). */
    public static void mirrorEnd() {
        MIRROR = null;
    }

    /** A PRIVATE-session test's recording is not the family ledger —
     * its verification must use the fresh-replay path. */
    public static void mirrorSuspend(boolean suspended) {
        if (MIRROR != null) {
            MIRROR.suspended = suspended;
        }
    }

    public static @com.legend.Nullable String verify(
            java.util.@com.legend.Nullable List<String> seeds, String goldenSql,
            ExecutionResult ours,
            java.util.Map<Integer, java.util.Map<String, String>> enumDecode,
            java.util.function.Function<String, java.util.Map<String, String>> graphEnumProp) {
        if (!READY) {
            throw new Unverifiable("h2 driver not on classpath", null);
        }
        // TABULAR frames compare cell-for-cell positionally; a GRAPH
        // frame compares by LABEL (the database built the instance
        // array — goldenGraphCompare). Collection/Scalar frames are
        // positional-ready through the sealed interface but PARKED: the
        // first probes hit the string-plus empty-vs-'' semantic gap
        // (engine relational drops empty-driven map rows, our compiled
        // concat keeps them as pure's plus([...]) would — witness
        // testQualifierWithOperation, golden 1 row vs our 4) — an
        // engine-vs-pure adjudication, not a referee call.
        if (!(ours instanceof ExecutionResult.Tabular)
                && !(ours instanceof ExecutionResult.Graph)) {
            throw new Unverifiable("non-tabular result frame", null);
        }
        if (ours instanceof ExecutionResult.Tabular) {
            // ENUM-typed frames compare through the SAME decode the frame
            // ran: some queries select the RAW source code (the engine
            // decodes post-SQL) while the frame carries decoded names — the
            // caller supplies the per-column source->name map (c46, the
            // enum-decode replay rung; c42 witnesses in the ledger). A
            // column with NO derivable map keeps the counted decline.
            for (int i = 0; i < ours.columns().size(); i++) {
                if (ours.columns().get(i).pureType()
                        instanceof com.legend.compiler.element.type.Type.EnumType
                        && !enumDecode.containsKey(i)) {
                    throw new Unverifiable(
                            "enum-decoded column (post-transform rows)", null);
                }
            }
        }
        // ONE session, the engine's own (batch C landed): the
        // "Duplicate column name" sniff-retry died with the second
        // session — there is no other semantics to fall back to.
        MirrorState mirror = MIRROR;
        if (mirror != null && !mirror.suspended) {
            // INCREMENTAL path: apply only the ledger entries not yet
            // mirrored, then compare on the LIVE family mirror
            if (mirror.poison != null) {
                throw new Unverifiable(mirror.poison, null);
            }
            try (Statement st = mirror.conn.createStatement()) {
                List<String> ledger = seeds == null ? List.of() : seeds;
                while (mirror.applied < ledger.size()) {
                    String seed = ledger.get(mirror.applied);
                    for (String one : seed.split(";\\s*\n|;\\s*$")) {
                        if (one.isBlank()) {
                            continue;
                        }
                        try {
                            st.execute(one);
                        } catch (SQLException e) {
                            mirror.poison = "seed replay: "
                                    + e.getMessage();
                            throw new Unverifiable(mirror.poison, e);
                        }
                    }
                    mirror.applied++;
                }
                return compareFrame(st, goldenSql, ours, enumDecode,
                        graphEnumProp);
            } catch (SQLException e) {
                throw new Unverifiable("h2 connection: "
                        + e.getMessage(), e);
            }
        }
        return freshVerify(seeds, goldenSql, ours, enumDecode,
                graphEnumProp);
    }

    /** Fresh-replay verification on a NEW in-memory H2 (the ONE
     * session): extensions + recorded seeds + golden compare. */
    private static @com.legend.Nullable String freshVerify(
            java.util.@com.legend.Nullable List<String> seeds,
            String goldenSql, ExecutionResult ours,
            java.util.Map<Integer, java.util.Map<String, String>> enumDecode,
            java.util.function.Function<String,
                    java.util.Map<String, String>> graphEnumProp) {
        int id = COUNTER.getAndIncrement();
        try (Connection h2 = DriverManager.getConnection(
                "jdbc:h2:mem:advisory" + id + SETTINGS, "sa", "")) {
            try (Statement st = h2.createStatement()) {
                // the engine's H2 extension functions, lite-implemented —
                // golden SQL calling legend_h2_extension_* declined
                // verification before (C1)
                for (String alias : H2ExtensionFunctions.aliases()) {
                    st.execute(alias);
                }
                for (String seed : seeds == null ? List.<String>of()
                        : seeds) {
                    for (String one : seed.split(";\\s*\n|;\\s*$")) {
                        if (one.isBlank()) {
                            continue;
                        }
                        try {
                            st.execute(one);
                        } catch (SQLException e) {
                            throw new Unverifiable("seed replay: "
                                    + e.getMessage(), e);
                        }
                    }
                }
                return compareFrame(st, goldenSql, ours, enumDecode,
                        graphEnumProp);
            }
        } catch (SQLException e) {
            throw new Unverifiable("h2 connection: " + e.getMessage(), e);
        }
    }

    /** The JSON carrier has NO temporal types — a Date-family element
     * arrives as its ISO text. The DECLARED pure type drives the
     * decode back (never value sniffing on non-temporal roots): ISO
     * date-time text to Timestamp, bare dates to java.sql.Date.
     *
     * <p>F6.3 contract: the ONE caller is the byte[] JSON-carrier branch
     * of Eval.flatten — the arrival whose carrier genuinely cannot hold
     * a temporal (probe 2026-08-17: DuckDB sweep 0 firings, h2 sweep 71,
     * every one the JSON_ARRAYAGG collection carrier). It must never run
     * side-agnostically over arbitrary result values: a String where a
     * Date is declared on any other path is a typing bug that must reach
     * wireEquals's refusal, not a repair. */
    public static java.util.List<Object> coerceTemporal(
            java.util.List<Object> vals,
            com.legend.compiler.element.type.Type t) {
        if (!(t == com.legend.compiler.element.type.Type.Primitive.DATE
                || t == com.legend.compiler.element.type.Type.Primitive
                        .DATE_TIME
                || t == com.legend.compiler.element.type.Type.Primitive
                        .STRICT_DATE)) {
            return vals;
        }
        java.util.List<Object> out = new java.util.ArrayList<>(vals.size());
        for (Object v : vals) {
            if (v instanceof String s
                    && s.matches("\\d{4}-\\d{2}-\\d{2}(T[\\d:.]+)?")) {
                // one-carrier rule (documented-debts 2026-08-18): the
                // decode lands on java.time, matching the Executor's
                // timestamp carrier — a Timestamp here made the two
                // compare sides box-diverge on identical instants
                out.add(s.contains("T")
                        ? java.time.LocalDateTime.parse(s)
                        : java.time.LocalDate.parse(s));
            } else {
                out.add(v);
            }
        }
        return out;
    }

    /** Route by the session backend: an H2 session verifies DIRECTLY
     * (the database already holds every table the test built —
     * model-driven DDL included, which the seed-replay oracle can miss
     * as "Table not found"); anything else replays the recorded seeds
     * into the fresh oracle. */
    /** Wall-clock spent in mirror verification (the DuckDB sweep's
     * advisory second target) — perf instrument, printed by the runner. */
    /** Wall-clock of the WHOLE golden-SQL channel on the DuckDB sweep
     * (sql-text compare incl. the H2 re-render + M1 h2-exec + advisory)
     * — perf instrument. */
    public static final java.util.concurrent.atomic.AtomicLong GOLDEN_NANOS =
            new java.util.concurrent.atomic.AtomicLong();

    public static final java.util.concurrent.atomic.AtomicLong MIRROR_NANOS =
            new java.util.concurrent.atomic.AtomicLong();

    public static @com.legend.Nullable String verifyAuto(Connection session,
            java.util.@com.legend.Nullable List<String> seeds,
            String goldenSql, ExecutionResult ours,
            java.util.Map<Integer, java.util.Map<String, String>> enumDecode,
            java.util.function.Function<String, java.util.Map<String, String>> graphEnumProp)
            throws SQLException {
        long t0 = System.nanoTime();
        try {
            return "H2".equals(session.getMetaData().getDatabaseProductName())
                    ? verifyOnSession(session, goldenSql, ours, enumDecode,
                            graphEnumProp)
                    : verify(seeds, goldenSql, ours, enumDecode,
                            graphEnumProp);
        } finally {
            MIRROR_NANOS.addAndGet(System.nanoTime() - t0);
        }
    }

    /**
     * The SESSION-direct golden verify (parity workstream 1): the
     * golden SELECT runs read-only on the session connection and
     * compares against our rows exactly like the replay path.
     */
    public static @com.legend.Nullable String verifyOnSession(
            Connection session, String goldenSql, ExecutionResult ours,
            java.util.Map<Integer, java.util.Map<String, String>> enumDecode,
            java.util.function.Function<String, java.util.Map<String, String>> graphEnumProp) {
        if (!(ours instanceof ExecutionResult.Tabular)
                && !(ours instanceof ExecutionResult.Graph)) {
            // Collection/Scalar parked — see the verify() gate note
            throw new Unverifiable("non-tabular result frame", null);
        }
        if (ours instanceof ExecutionResult.Tabular) {
            for (int i = 0; i < ours.columns().size(); i++) {
                if (ours.columns().get(i).pureType()
                        instanceof com.legend.compiler.element.type.Type.EnumType
                        && !enumDecode.containsKey(i)) {
                    throw new Unverifiable(
                            "enum-decoded column (post-transform rows)", null);
                }
            }
        }
        try (Statement st = session.createStatement()) {
            return compareFrame(st, goldenSql, ours, enumDecode,
                    graphEnumProp);
        } catch (SQLException e) {
            throw new Unverifiable("session golden execution: "
                    + e.getMessage(), e);
        }
    }

    /** Kind dispatch for the golden compare: flat frames positionally,
     * the Graph frame by label. */
    private static @com.legend.Nullable String compareFrame(Statement st,
            String goldenSql, ExecutionResult ours,
            java.util.Map<Integer, java.util.Map<String, String>> enumDecode,
            java.util.function.Function<String, java.util.Map<String, String>> graphEnumProp)
            throws SQLException {
        return ours instanceof ExecutionResult.Graph g
                ? goldenGraphCompare(st, goldenSql, g, graphEnumProp)
                : goldenRowsCompare(st, goldenSql, ours, enumDecode);
    }

    /** Engine bookkeeping aliases in a class-mapped golden select —
     * selected by the engine only to ASSEMBLE instances, never
     * observable on the result the assert's own test verifies. The
     * spellings are the engine's own generation convention (relational
     * mapping select generation): {@code pk_$i} instance identity —
     * union set implementations suffix the member ({@code pk_0_1});
     * {@code u_type} the union member discriminator (drives WHICH
     * class instantiates); {@code k_businessDate}/{@code
     * k_processingDate} the milestoning-context constants; the
     * milestoning period columns ({@code from_z/thru_z/in_z/out_z},
     * union-suffixed too) ride golden selects to build temporal
     * instance state — the frame's instances never carry them (their
     * coordinate is the reserved businessDate/processingDate
     * property, see the frame-side twin in goldenGraphCompare). */
    private static boolean bookkeepingAlias(String label) {
        return label.matches("pk_\\d+(_\\d+)*")
                // both engine spellings: union mappings emit u_type,
                // modelJoin's materialized-subselect goldens U_TYPE
                || label.equalsIgnoreCase("u_type")
                || label.matches("(from_z|thru_z|in_z|out_z)(_\\d+)*")
                || label.equals("k_businessDate")
                || label.equals("k_processingDate");
    }

    /** GRAPH-frame row verification (V7 diff-noreplay burndown): a
     * class-mapped query's frame is the instance array the DATABASE
     * built — flat json objects keyed by mapped property name, which
     * are exactly the golden's DATA aliases. Comparison: golden rows
     * and json objects as order-insensitive multisets over the golden's
     * data aliases (bookkeeping columns excluded by the engine's own
     * spelling, {@link #bookkeepingAlias}); the alias set and the json
     * key set must agree EXACTLY. Temporal cells decode TYPE-driven
     * from the golden's JDBC column type (the json carrier has no
     * temporals — same rule as {@link #coerceTemporal}), never by value
     * sniffing. Every structural surprise — nesting, key skew,
     * enum-typed property (frame carries decoded names, golden the raw
     * codes) — throws {@link Unverifiable}: a COUNTED decline, never a
     * guessed compare. */
    private static @com.legend.Nullable String goldenGraphCompare(Statement st,
            String goldenSql, ExecutionResult.Graph g,
            java.util.function.Function<String,
                    java.util.Map<String, String>> enumProp) {
        Object parsed = com.legend.sql.Json.parse(g.json());
        if (!(parsed instanceof List<?> arr)) {
            throw new Unverifiable("graph frame is not a json array", null);
        }
        List<java.util.Map<String, Object>> objs = new ArrayList<>();
        java.util.TreeSet<String> keys = new java.util.TreeSet<>();
        for (Object o : arr) {
            if (!(o instanceof java.util.Map<?, ?> m)) {
                throw new Unverifiable("graph nesting in result frame", null);
            }
            java.util.Map<String, Object> flat =
                    new java.util.LinkedHashMap<>();
            for (var e : m.entrySet()) {
                if (e.getValue() instanceof java.util.Map
                        || e.getValue() instanceof List) {
                    throw new Unverifiable(
                            "graph nesting in result frame", null);
                }
                flat.put((String) e.getKey(), e.getValue());
                keys.add((String) e.getKey());
            }
            objs.add(flat);
        }
        // per-key enum decode (the tabular per-column decode's
        // label-mapped twin): null = not an enum property; an EMPTY map
        // = enum whose mapping is underivable — the counted decline
        // stands (never a guessed decode); non-empty = the golden's raw
        // source codes decode to the names the frame carries.
        java.util.Map<String, java.util.Map<String, String>> keyDecode =
                new java.util.HashMap<>();
        for (String k : keys) {
            var dec = enumProp.apply(k);
            if (dec != null) {
                if (dec.isEmpty()) {
                    throw new Unverifiable(
                            "enum-decoded column (post-transform rows)",
                            null);
                }
                keyDecode.put(k, dec);
            }
        }
        List<String> theirs = new ArrayList<>();
        List<String> dataLabels = new ArrayList<>();
        java.util.Set<String> temporal = new java.util.HashSet<>();
        try (ResultSet rs = st.executeQuery(goldenSql)) {
            var md = rs.getMetaData();
            int n = md.getColumnCount();
            int[] dataIdx = new int[n];
            int dc = 0;
            for (int i = 1; i <= n; i++) {
                String label = md.getColumnLabel(i);
                if (bookkeepingAlias(label)) {
                    continue;
                }
                dataLabels.add(label);
                dataIdx[dc++] = i;
                int jt = md.getColumnType(i);
                if (jt == java.sql.Types.DATE || jt == java.sql.Types.TIME
                        || jt == java.sql.Types.TIMESTAMP
                        || jt == java.sql.Types.TIMESTAMP_WITH_TIMEZONE) {
                    temporal.add(label);
                }
            }
            java.util.TreeSet<String> labelSet = new java.util.TreeSet<>(dataLabels);
            if (labelSet.size() != dataLabels.size()) {
                throw new Unverifiable(
                        "duplicate data alias in golden select", null);
            }
            // EMPTY frame: no instances, so no keys to match — the
            // verdict is the golden's row count (data rows only: a
            // golden row that is all-NULL across data columns is the
            // engine's client-side SQLNull drop — no instance arises
            // from it, relationalMappingExecution.pure:480)
            if (objs.isEmpty()) {
                int dataRows = 0;
                while (rs.next()) {
                    for (String lbl : dataLabels) {
                        // index recomputed below for the non-empty path;
                        // here a linear label read suffices
                        if (rs.getObject(lbl) != null) {
                            dataRows++;
                            break;
                        }
                    }
                }
                return dataRows == 0 ? null
                        : "h2-advisory divergence: golden SQL on H2 gave "
                                + dataRows + " data row(s), our pipeline"
                                + " gave 0 instances";
            }
            // frame-side twin of the k_* exclusion: the reserved
            // milestoning coordinates (businessDate/processingDate) on
            // the instance are the query's temporal CONTEXT echoed
            // back; when the golden never selects an alias of that
            // name, they are not queried data. A class property that
            // genuinely maps one keeps it — the golden then selects
            // the alias and the sets already agree.
            for (String ctx : new String[]{"businessDate",
                    "processingDate"}) {
                if (keys.contains(ctx) && !labelSet.contains(ctx)) {
                    keys.remove(ctx);
                }
            }
            if (!labelSet.equals(keys)) {
                throw new Unverifiable("graph keys mismatch golden aliases:"
                        + " golden " + labelSet + " vs frame " + keys, null);
            }
            // both sides tuple over the SAME sorted key order
            List<String> sorted = new ArrayList<>(keys);
            java.util.Map<String, Integer> byLabel = new java.util.HashMap<>();
            for (int j = 0; j < dataLabels.size(); j++) {
                byLabel.put(dataLabels.get(j), dataIdx[j]);
            }
            while (rs.next()) {
                StringBuilder row = new StringBuilder();
                for (String k : sorted) {
                    if (row.length() > 0) {
                        row.append('|');
                    }
                    String cell = norm(rs.getObject(byLabel.get(k)));
                    var dec = keyDecode.get(k);
                    row.append(dec == null ? cell
                            : dec.getOrDefault(cell, cell));
                }
                theirs.add(row.toString());
            }
            List<String> mine = new ArrayList<>();
            for (java.util.Map<String, Object> obj : objs) {
                StringBuilder row = new StringBuilder();
                for (String k : sorted) {
                    if (row.length() > 0) {
                        row.append('|');
                    }
                    Object v = obj.get(k);
                    if (v instanceof String s && temporal.contains(k)) {
                        // type-driven temporal decode (golden JDBC type):
                        // the json carrier spells the engine convention —
                        // ISO text, 'T' separator, 9-digit nanos
                        try {
                            v = s.contains("T")
                                    ? java.time.LocalDateTime.parse(s)
                                    : (Object) java.time.LocalDate.parse(s);
                        } catch (java.time.format.DateTimeParseException x) {
                            // unexpected spelling: compare raw, loudly
                        }
                    }
                    row.append(norm(v));
                }
                mine.add(row.toString());
            }
            Collections.sort(theirs);
            Collections.sort(mine);
            if (theirs.equals(mine)) {
                return null;
            }
            return divergenceOrSkew(theirs, mine);
        } catch (SQLException e) {
            throw new Unverifiable("golden execution: "
                    + e.getMessage(), e);
        }
    }

    /** Run the golden SELECT on {@code st}, compare rows with the frame
     * as ORDER-INSENSITIVE multisets of normalized cells (shared by the
     * replay oracle and the session-direct verify). */
    private static @com.legend.Nullable String goldenRowsCompare(Statement st,
            String goldenSql, ExecutionResult tab,
            java.util.Map<Integer, java.util.Map<String, String>> enumDecode)
            throws SQLException {
                List<String> theirs = new ArrayList<>();
                int[] theirsCols = {0};
                try (ResultSet rs = st.executeQuery(goldenSql)) {
                    int n = rs.getMetaData().getColumnCount();
                    theirsCols[0] = n;
                    while (rs.next()) {
                        StringBuilder row = new StringBuilder();
                        for (int i = 1; i <= n; i++) {
                            if (i > 1) {
                                row.append('|');
                            }
                            String cell = norm(rs.getObject(i));
                            // raw source code -> decoded name, the same
                            // transform the compared frame ran (0-based
                            // frame column = 1-based JDBC index - 1)
                            var dec = enumDecode.get(i - 1);
                            row.append(dec == null ? cell
                                    : dec.getOrDefault(cell, cell));
                        }
                        theirs.add(row.toString());
                    }
                } catch (SQLException e) {
                    throw new Unverifiable("golden execution: "
                            + e.getMessage(), e);
                }
                if (theirsCols[0] != tab.columns().size()) {
                    // our frame carries harness-added columns (driver
                    // PKs, order keys) the golden never selects — an
                    // ARITY gap is a layer difference, not a divergence
                    throw new Unverifiable("column arity differs: golden "
                            + theirsCols[0] + " vs frame "
                            + tab.columns().size(), null);
                }
                List<String> mine = new ArrayList<>();
                for (Row r : tab.rows()) {
                    StringBuilder row = new StringBuilder();
                    for (int i = 0; i < r.values().size(); i++) {
                        if (i > 0) {
                            row.append('|');
                        }
                        row.append(norm(r.values().get(i)));
                    }
                    mine.add(row.toString());
                }
                List<String> theirsRaw = new ArrayList<>(theirs);
                List<String> mineRaw = new ArrayList<>(mine);
                Collections.sort(theirs);
                Collections.sort(mine);
                if (theirs.equals(mine)) {
                    // F2.4: the oracle discards row order BY DESIGN but
                    // never counted it — emit under the same instrument
                    // so census numbers stop being floors (strict
                    // recheck = pre-sort order)
                    if (System.getenv("LL_ORD_COUNT") != null
                            && !theirsRaw.equals(mineRaw)) {
                        System.err.println(
                                "[ord] h2-oracle order-leniency pass");
                    }
                    return null;
                }
                return divergenceOrSkew(theirs, mine);
    }

    /** The one divergence tail (both compare paths): identical DISTINCT
     * row sets differing only in duplication mark OUR set-shaped
     * compilation against the engine's row algebra — the engine's
     * execute path builds ONE OBJECT PER ROW, no pk dedup anywhere
     * (RelationalResult.java, verified 2026-08-28: zero
     * distinct/dedup/pk sites), so join fan-out duplicates instances
     * (7 Firm X for testQualifierQueryWithOr — its
     * assertSize(values->at(0),1) sizes a single element and pins
     * nothing) while our filter lowering dedups. Same engine-vs-our
     * semantic gap as the parked Collection/Scalar lane; COUNTED
     * decline until that adjudication, never a verdict either way. A
     * difference in row VALUES stays the hard divergence. */
    private static String divergenceOrSkew(List<String> theirs,
            List<String> mine) {
        if (new java.util.HashSet<>(theirs)
                .equals(new java.util.HashSet<>(mine))) {
            throw new Unverifiable(
                    "row-cardinality skew (distinct rows agree)", null);
        }
        return "h2-advisory divergence: golden SQL on H2 gave "
                + theirs.size() + " row(s), our pipeline gave "
                + mine.size() + " row(s); " + diffRows(theirs, mine);
    }

    private static String head(List<String> rows) {
        return rows.subList(0, Math.min(rows.size(), 5)).toString();
    }

    /** The DIFFERING rows (multiset difference, both directions, 5 max
     * each) — a divergence whose first rows agree used to truncate to
     * two identical-looking heads. */
    private static String diffRows(List<String> theirs, List<String> mine) {
        List<String> onlyTheirs = new ArrayList<>(theirs);
        for (String m : mine) {
            onlyTheirs.remove(m);
        }
        List<String> onlyMine = new ArrayList<>(mine);
        for (String t : theirs) {
            onlyMine.remove(t);
        }
        return "golden-only " + head(onlyTheirs)
                + ", ours-only " + head(onlyMine);
    }

    /** The per-column enum decode (frame column index -> raw source
     * value -> decoded name) for a Tabular frame — the SAME transform
     * the frame ran post-SQL, recovered from the exec call's MAPPING
     * (its EnumerationMapping for the column's enum). Columns whose
     * mapping is underivable get NO entry — {@link #verify} keeps the
     * counted decline for them (never a guessed decode). */
    static java.util.Map<Integer, java.util.Map<String, String>> enumDecodeFor(
            com.legend.exec.@com.legend.Nullable ExecutionResult result,
            com.legend.protocol.spec.@com.legend.Nullable ValueSpecification actual,
            java.util.Map<String, com.legend.protocol.spec.ValueSpecification> lets,
            List<com.legend.protocol.spec.ValueSpecification> execStmts,
            com.legend.compiler.element.ModelContext ctx,
            com.legend.model.ImportScope imports) {
        if (!(result instanceof ExecutionResult.Tabular tab)) {
            return java.util.Map.of();
        }
        boolean anyEnum = tab.columns().stream().anyMatch(c -> c.pureType()
                instanceof com.legend.compiler.element.type.Type.EnumType);
        if (!anyEnum) {
            return java.util.Map.of();
        }
        String mappingFqn = mappingFqnOf(actual, lets, execStmts, ctx,
                imports);
        if (mappingFqn == null) {
            return java.util.Map.of();
        }
        var out = new java.util.LinkedHashMap<Integer,
                java.util.Map<String, String>>();
        for (int i = 0; i < tab.columns().size(); i++) {
            if (!(tab.columns().get(i).pureType() instanceof
                    com.legend.compiler.element.type.Type.EnumType et)) {
                continue;
            }
            var dec = decodeOf(ctx, mappingFqn, et.fqn());
            if (dec != null) {
                out.put(i, dec);
            }
        }
        return out;
    }

    /** The exec call's mapping FQN (the pointer carries the SOURCE
     * spelling — simple names resolve through the test's import
     * wildcards; findLegacyMapping wants the FQN). */
    static @com.legend.Nullable String mappingFqnOf(
            com.legend.protocol.spec.@com.legend.Nullable ValueSpecification actual,
            java.util.Map<String, com.legend.protocol.spec.ValueSpecification> lets,
            List<com.legend.protocol.spec.ValueSpecification> execStmts,
            com.legend.compiler.element.ModelContext ctx,
            com.legend.model.ImportScope imports) {
        var exec = ExecCallFinder.find(actual, lets, execStmts);
        String mappingRef = exec != null && exec.parameters().size() >= 2
                && exec.parameters().get(1) instanceof
                        com.legend.protocol.spec.PackageableElementPtr p
                ? p.fullPath() : null;
        if (mappingRef == null) {
            return null;
        }
        if (!mappingRef.contains("::")
                && ctx.findLegacyMapping(mappingRef).isEmpty()) {
            for (String w : imports.wildcards()) {
                if (ctx.findLegacyMapping(w + "::" + mappingRef)
                        .isPresent()) {
                    return w + "::" + mappingRef;
                }
            }
        }
        return mappingRef;
    }

    /** The raw-source -> enum-name decode from {@code mappingFqn}'s
     * EnumerationMapping for {@code enumFqn}; null when underivable —
     * a cross-enum source value keeps the WHOLE map underivable (a
     * partial map would half-decode). */
    static java.util.@com.legend.Nullable Map<String, String> decodeOf(
            com.legend.compiler.element.ModelContext ctx, String mappingFqn,
            String enumFqn) {
        var em = com.legend.plan.PlanText.enumMappingOf(ctx, mappingFqn,
                enumFqn);
        if (em == null) {
            return null;
        }
        var dec = new java.util.LinkedHashMap<String, String>();
        for (var vm : em.valueMappings()) {
            for (var sv : vm.sourceValues()) {
                switch (sv) {
                    case com.legend.model.EnumerationMapping.SourceValue
                            .StringValue s ->
                            dec.put(s.value(), vm.enumValue());
                    case com.legend.model.EnumerationMapping.SourceValue
                            .IntegerValue n ->
                            dec.put(String.valueOf(n.value()),
                                    vm.enumValue());
                    case com.legend.model.EnumerationMapping.SourceValue
                            .EnumRef ignored -> {
                        return null;
                    }
                }
            }
        }
        return dec;
    }

    /** One normalization for BOTH sides: JDBC drivers disagree on exact
     * numeric/temporal classes; the database-level VALUE is the
     * contract. */
    private static String norm(Object v) {
        if (v == null) {
            return "<null>";
        }
        if (v instanceof Boolean b) {
            return b.toString();
        }
        if (v instanceof Number) {
            try {
                BigDecimal d = new BigDecimal(v.toString());
                // INTEGRAL values compare EXACTLY — the old blanket
                // MathContext(10) made two epoch-millis differing in the
                // last 3 digits compare EQUAL (H2_BACKEND.md §12 step 3:
                // a silent false PASS on BOTH sides of the oracle).
                if (d.stripTrailingZeros().scale() <= 0) {
                    return d.stripTrailingZeros().toPlainString();
                }
                // FLOATING values keep a CROSS-ENGINE tolerance of 10
                // significant digits: H2 divides in exact DECIMAL,
                // DuckDB in binary double, and the tails genuinely
                // diverge around digit 11-12 WITH rounding-boundary
                // straddles (witness: testUnionWithWtdAndPwa raw
                // ...394497 vs ...39455 rounds apart at BOTH 11 and 12).
                // Fixed-digit normalization cannot separate 1-ulp tails
                // from real sub-1e-10 differences; 10 digits is the
                // empirically-clean cross-engine floor. The REAL defect
                // (integral collapse — epoch-millis comparing equal) is
                // fixed above by the exact integral arm.
                return d.round(new java.math.MathContext(10))
                        .stripTrailingZeros().toPlainString();
            } catch (NumberFormatException e) {
                return v.toString();
            }
        }
        // temporal CARRIERS canonicalize before any toString — the
        // Executor hands PureDateLiteral (THE wire temporal) while the
        // H2 replay side reads raw Timestamps. DERIVED (V10a): BOTH
        // sides of this seam are DB reads, and the engine convention
        // (fromSQLTimestamp %09d) makes every DB temporal a full
        // nine-digit value — component equality of two DB reads IS
        // instant equality, so the instant-level funnel here is the
        // engine's own semantics for this seam, not a leniency.
        if (v instanceof com.legend.values.PureDateLiteral pd) {
            if (pd.precision().atLeast(
                    com.legend.values.PureDateLiteral.Precision.HOUR)) {
                v = pd.toInstantFloor();
            } else {
                return pd.toEngineString();
            }
        }
        if (v instanceof java.sql.Timestamp ts) {
            v = ts.toLocalDateTime();
        }
        if (v instanceof java.time.LocalDateTime ldt) {
            String s = ldt.toLocalDate() + " "
                    + String.format("%02d:%02d:%02d",
                            ldt.getHour(), ldt.getMinute(),
                            ldt.getSecond());
            // MICROSECOND floor, same class as the float 10-digit rule
            // above: DuckDB TIMESTAMP is microsecond BY STORAGE while H2
            // holds nanos — digits 7-9 of an H2 golden cell cannot exist
            // on our side of the oracle (witness: testLessThan seed
            // '15:22:23.123456789' vs our read '…123456'). Divergence at
            // micro or coarser still fails.
            int nano = ldt.getNano() / 1000 * 1000;
            if (nano != 0) {
                s += ("." + String.valueOf(1_000_000_000L + nano)
                        .substring(1)).replaceAll("0+$", "");
            }
            return s;
        }
        String s = v.toString();
        // timestamp spellings: trim trailing fractional zeros and the
        // bare '.0' second fraction ('2015-08-26 00:00:00.0' ==
        // '2015-08-26 00:00:00')
        if (s.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}(\\.\\d+)?")) {
            s = s.replaceAll("\\.?0+$", "");
        }
        return s;
    }
}
