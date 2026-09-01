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
    /** The running test forces an engine ISOLATION STRATEGY via
     * ^RelationalDebugContext(forcedIsolation=...) — a debug-mechanism
     * pin, not user semantics (batch-0 ruling: the chooser is
     * non-binding mechanism). The engine's strategies are ROW-DIVERGENT
     * in VALUE position (forced::testQualifierWithOperation golden
     * keeps 4 rows incl. NULL-minted values; the default golden keeps
     * 1), so a forced VALUE-frame golden pins a strategy our one
     * default-mode compiler deliberately does not choose — its row
     * compare DECLINES (counted). Row-preserving positions keep their
     * referee: strategies agree there, and a divergence would be real. */
    public static final ThreadLocal<Boolean> FORCED_MECHANISM =
            ThreadLocal.withInitial(() -> Boolean.FALSE);

    /** The verified query chain is a SUB-COLLECTION of a class extent
     * (getAll root through subset-preserving ops — computed STATICALLY
     * by EngineTestExecutor.extentSubset at the verify site, the §7
     * order-policy doctrine applied to multiplicity). Pure semantics
     * then guarantees each instance at most once, so the graph
     * compare may collapse golden-side full-row duplicates (pk
     * included) as the engine's join fan-out re-manufacturing the
     * same object — row-13 adjudication, SQLTEXT charter §6.1,
     * 2026-09-01. OUR side never collapses: an over-duplicating
     * pipeline still diverges loudly. */
    public static final ThreadLocal<Boolean> EXTENT_SUBSET =
            ThreadLocal.withInitial(() -> Boolean.FALSE);

    /** The verified query chain ENDS IN SORT — the walk's own static
     * order fact (EngineTestExecutor.endsInSort via the order-policy
     * view), computed at the verify site exactly like
     * {@link #EXTENT_SUBSET}. Charter §7: ordered queries compare IN
     * ORDER, unordered as multisets — this flag is the oracle
     * compare's gate (and, under LL_ORD_COUNT, the blast-radius
     * instrument's classification). */
    public static final ThreadLocal<Boolean> ORDERED_QUERY =
            ThreadLocal.withInitial(() -> Boolean.FALSE);

    /** The ordered chain's EFFECTIVE sort-key column/property names
     * (EngineTestExecutor.sortKeyCols — the sort nearest the tail, the
     * engine's own last-sort-wins semantics), null when underivable.
     * The §7 in-order compare needs them for TIES: rows equal on the
     * sort keys have no defined relative order on either backend
     * (testSortByLambdaMultiple: two Johns under {@code order by
     * FIRSTNAME asc} — H2 and DuckDB both correct, orders differ), so
     * the compare checks the key SEQUENCE positionally and the full
     * rows as multisets WITHIN each tie run. Ordered + null keys =
     * counted decline. */
    public static final ThreadLocal<java.util.@com.legend.Nullable List<String>> SORT_KEYS =
            new ThreadLocal<>();

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

    /** Wall-clock spent in mirror verification (the DuckDB sweep's
     * advisory second target) — perf instrument, printed by the runner. */
    /** Wall-clock of the WHOLE golden-SQL channel on the DuckDB sweep
     * (sql-text compare incl. the H2 re-render + M1 h2-exec + advisory)
     * — perf instrument. */
    public static final java.util.concurrent.atomic.AtomicLong GOLDEN_NANOS =
            new java.util.concurrent.atomic.AtomicLong();

    public static final java.util.concurrent.atomic.AtomicLong MIRROR_NANOS =
            new java.util.concurrent.atomic.AtomicLong();


    /** ENUM-typed frames compare through the SAME decode the frame
     * ran: some queries select the RAW source code (the engine decodes
     * post-SQL) while the frame carries decoded names — the caller
     * supplies the per-column source->name map (c46, the enum-decode
     * replay rung). A column with NO derivable map keeps the counted
     * decline. Shared precheck of the {@link ReplayOracle} verify
     * entry points (Graph frames decode per-key instead). */
    static void enumPrecheck(ExecutionResult ours,
            java.util.Map<Integer, java.util.Map<String, String>> enumDecode) {
        if (ours instanceof ExecutionResult.Graph) {
            return;
        }
        for (int i = 0; i < ours.columns().size(); i++) {
            if (ours.columns().get(i).pureType()
                    instanceof com.legend.compiler.element.type.Type.EnumType
                    && !enumDecode.containsKey(i)) {
                throw new Unverifiable(
                        "enum-decoded column (post-transform rows)", null);
            }
        }
    }

    /** Kind dispatch for the golden compare: flat frames positionally,
     * the Graph frame by label ({@link ReplayOracle}-called — the
     * comparison policy seam). */
    static @com.legend.Nullable String compareFrame(Statement st,
            String goldenSql, ExecutionResult ours,
            java.util.Map<Integer, java.util.Map<String, String>> enumDecode,
            java.util.function.Function<String, java.util.Map<String, String>> graphEnumProp)
            throws SQLException {
        if (FORCED_MECHANISM.get()
                && !(ours instanceof ExecutionResult.Tabular)
                && !(ours instanceof ExecutionResult.Graph)) {
            // see FORCED_MECHANISM: value-frame strategies are
            // row-divergent by the engine's own goldens
            throw new Unverifiable(
                    "forced-isolation golden over a VALUE frame"
                    + " (engine debug-mechanism pin)", null);
        }
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
        // full golden rows (EVERY column, pk identity included),
        // parallel to {@code theirs} — the row-13 collapse's key: two
        // rows identical INCLUDING their pk are the same instance
        // assembled twice by join fan-out; same pk with ANY differing
        // cell keeps both rows and stays a loud divergence
        List<String> fullRows = new ArrayList<>();
        boolean hasPk = false;
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
                    hasPk |= label.matches("pk_\\d+(_\\d+)*");
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
            // ONE-DIRECTIONAL key rule (sql-exec burn 2026-08-30): every
            // FRAME key must appear in the golden — a frame property the
            // golden never selects is OUR bug and stays the loud decline.
            // GOLDEN-ONLY aliases are the engine's ASSEMBLY PLUMBING
            // (association stitch keys zzfirmId/yyID, physical key
            // spellings ACC_NUM beside acctNum, milestoning
            // snapshotDate_N coordinates, sort keys) — the OBJECT is the
            // observable and our frame IS the fetch tree by
            // construction, so they drop, COUNTED on the verdict roster.
            if (!labelSet.containsAll(keys)) {
                throw new Unverifiable("graph keys mismatch golden aliases:"
                        + " golden " + labelSet + " vs frame " + keys, null);
            }
            if (!keys.containsAll(labelSet)) {
                verdict("golden-stitch-keys-dropped");
            }
            // both sides tuple over the SAME sorted key order
            List<String> sorted = new ArrayList<>(keys);
            // §7: the sort-key indexes address the shared tuple order
            // (ordered queries only; underivable/absent keys decline)
            int[] keyIdx = ORDERED_QUERY.get()
                    ? sortKeyIndexes(sorted) : null;
            List<String> theirKeys = new ArrayList<>();
            List<String> mineKeys = new ArrayList<>();
            java.util.Map<String, Integer> byLabel = new java.util.HashMap<>();
            for (int j = 0; j < dataLabels.size(); j++) {
                byLabel.put(dataLabels.get(j), dataIdx[j]);
            }
            while (rs.next()) {
                StringBuilder row = new StringBuilder();
                String[] cells = new String[sorted.size()];
                int ci = 0;
                for (String k : sorted) {
                    if (row.length() > 0) {
                        row.append('|');
                    }
                    String cell = norm(rs.getObject(byLabel.get(k)));
                    var dec = keyDecode.get(k);
                    cells[ci] = dec == null ? cell
                            : dec.getOrDefault(cell, cell);
                    row.append(cells[ci++]);
                }
                theirs.add(row.toString());
                if (keyIdx != null) {
                    theirKeys.add(keyTuple(cells, keyIdx));
                }
                StringBuilder full = new StringBuilder();
                for (int i = 1; i <= n; i++) {
                    if (i > 1) {
                        full.append('|');
                    }
                    full.append(norm(rs.getObject(i)));
                }
                fullRows.add(full.toString());
            }
            List<String> mine = new ArrayList<>();
            for (java.util.Map<String, Object> obj : objs) {
                StringBuilder row = new StringBuilder();
                String[] cells = new String[sorted.size()];
                int ci = 0;
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
                    cells[ci] = norm(v);
                    row.append(cells[ci++]);
                }
                mine.add(row.toString());
                if (keyIdx != null) {
                    mineKeys.add(keyTuple(cells, keyIdx));
                }
            }
            // Row-13 adjudication (SQLTEXT charter §6.1, landed
            // 2026-09-01): on an EXTENT-SUBSET query (pure guarantees
            // each instance at most once) whose golden carries the
            // engine's pk identity stamp, golden rows identical across
            // EVERY column (pk included) are one instance
            // re-manufactured per joined row (engine default emission
            // leaves fan legs unfiltered — testQualifierQueryWithOr:
            // 7x (1,'Firm X') where pure answers once; its own asserts
            // pin nothing, assertSize(values->at(0),1) sizes a single
            // element). Collapse GOLDEN-side only, then the SAME
            // strict compare — an over-duplicating pipeline on OUR
            // side still diverges loudly. The collapse preserves
            // first-occurrence order, so it composes with the §7
            // ordered path below.
            List<String> collapsed = null;
            List<String> collapsedKeys = null;
            if (EXTENT_SUBSET.get() && hasPk) {
                java.util.Set<String> seenFull = new java.util.HashSet<>();
                collapsed = new ArrayList<>();
                collapsedKeys = keyIdx == null ? null : new ArrayList<>();
                for (int i = 0; i < theirs.size(); i++) {
                    if (seenFull.add(fullRows.get(i))) {
                        collapsed.add(theirs.get(i));
                        if (collapsedKeys != null) {
                            collapsedKeys.add(theirKeys.get(i));
                        }
                    }
                }
            }
            // §7 RATIFIED (flip landed 2026-09-01): ordered instance
            // queries compare IN ORDER, ties grouped (orderedVerdict).
            // Unordered — and ordered rows without usable tie
            // boundaries (keyIdx null, counted) — keep the multiset
            // compare.
            if (ORDERED_QUERY.get() && keyIdx != null) {
                if (orderedVerdict(theirs, mine, theirKeys,
                        mineKeys) == null) {
                    return null;
                }
                if (collapsed != null && orderedVerdict(collapsed, mine,
                        collapsedKeys, mineKeys) == null) {
                    verdict("golden-fanout-collapsed");
                    return null;
                }
                return divergence(theirs, mine);
            }
            if (ORDERED_QUERY.get()) {
                ordFallback();
            }
            List<String> sortedTheirs = new ArrayList<>(theirs);
            Collections.sort(sortedTheirs);
            Collections.sort(mine);
            if (sortedTheirs.equals(mine)) {
                return null;
            }
            if (collapsed != null) {
                Collections.sort(collapsed);
                if (collapsed.equals(mine)) {
                    verdict("golden-fanout-collapsed");
                    return null;
                }
            }
            return divergence(sortedTheirs, mine);
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
                // VALUE frames (Collection/Scalar) compare at the
                // OBSERVABLE boundary: a [*]-valued query flattens
                // per-root collections, so the engine's raw NULL row
                // (empty collection under a preserved root) never
                // reaches .values — the engine's own assert is the
                // receipt (testChainedUnionsWithMapAggregation,
                // testUnionWithExtends.pure:291 asserts ONE value while
                // its golden SQL returns that value plus a NULL row).
                // GOLDEN-side only, single-column NULL rows only: our
                // pipeline drops these in-DB (egress null-drop, Blocker
                // 1), so a lane that wrongly KEEPS a null still fails.
                boolean valueFrame =
                        !(tab instanceof ExecutionResult.Tabular);
                // §7: an ORDERED query resolves its sort-key column
                // INDEXES against the frame schema up front (positional
                // arity with the golden is asserted below, so the same
                // indexes address both sides). Underivable keys or a
                // key the compared output does not carry = counted
                // decline — never a guessed tie policy.
                int[] keyIdx = ORDERED_QUERY.get()
                        ? sortKeyIndexes(tab.columns().stream()
                                .map(c -> c.name()).toList())
                        : null;
                List<String> theirKeys = new ArrayList<>();
                try (ResultSet rs = st.executeQuery(goldenSql)) {
                    int n = rs.getMetaData().getColumnCount();
                    theirsCols[0] = n;
                    while (rs.next()) {
                        StringBuilder row = new StringBuilder();
                        boolean allNull = true;
                        String[] cells = new String[n];
                        for (int i = 1; i <= n; i++) {
                            if (i > 1) {
                                row.append('|');
                            }
                            Object raw = rs.getObject(i);
                            allNull &= raw == null;
                            String cell = norm(raw);
                            // raw source code -> decoded name, the same
                            // transform the compared frame ran (0-based
                            // frame column = 1-based JDBC index - 1)
                            var dec = enumDecode.get(i - 1);
                            cells[i - 1] = dec == null ? cell
                                    : dec.getOrDefault(cell, cell);
                            row.append(cells[i - 1]);
                        }
                        if (valueFrame && n == 1 && allNull) {
                            continue;
                        }
                        theirs.add(row.toString());
                        if (keyIdx != null) {
                            theirKeys.add(keyTuple(cells, keyIdx));
                        }
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
                List<String> mineKeys = new ArrayList<>();
                for (Row r : tab.rows()) {
                    StringBuilder row = new StringBuilder();
                    String[] cells = new String[r.values().size()];
                    for (int i = 0; i < r.values().size(); i++) {
                        if (i > 0) {
                            row.append('|');
                        }
                        cells[i] = norm(r.values().get(i));
                        row.append(cells[i]);
                    }
                    mine.add(row.toString());
                    if (keyIdx != null) {
                        mineKeys.add(keyTuple(cells, keyIdx));
                    }
                }
                // §7 RATIFIED (flip landed 2026-09-01, blast radius
                // measured first: 2 ordered-query leniency passes, both
                // the ASC-nulls placement defect, burned by the
                // nulls-low emission fix in the same slice): an ORDERED
                // query's row order is CONTRACT — compare IN ORDER,
                // ties grouped (orderedVerdict). Unordered queries —
                // and ordered rows without usable tie boundaries
                // (keyIdx null, counted residue) — keep the multiset
                // compare (the 103 measured unordered leniency passes
                // are incidental backend order, legitimate forever).
                if (ORDERED_QUERY.get() && keyIdx != null) {
                    return orderedVerdict(theirs, mine, theirKeys,
                            mineKeys);
                }
                if (ORDERED_QUERY.get()) {
                    ordFallback();
                }
                List<String> theirsRaw = new ArrayList<>(theirs);
                List<String> mineRaw = new ArrayList<>(mine);
                Collections.sort(theirs);
                Collections.sort(mine);
                if (theirs.equals(mine)) {
                    // F2.4: the oracle discards row order for UNORDERED
                    // queries by §7 — still counted under the
                    // instrument so census numbers stay honest (strict
                    // recheck = pre-sort order)
                    if (System.getenv("LL_ORD_COUNT") != null
                            && !theirsRaw.equals(mineRaw)) {
                        System.err.println(
                                "[ord] h2-oracle order-leniency pass"
                                + " unordered");
                    }
                    return null;
                }
                return divergence(theirs, mine);
    }

    /** Resolve the {@link #SORT_KEYS} names to indexes in
     * {@code columnNames} (exact match first, case-insensitive
     * fallback — TDS labels vs mapped-property spellings). NULL = the
     * §7 strict path has no tie boundaries here — underivable keys
     * (computed sort expressions) or keys the compared output does not
     * carry (sort-then-rename, non-projected keys). The compare then
     * KEEPS the multiset verdict for this row set — verification is
     * preserved, the residual order-leniency stays COUNTED under
     * LL_ORD_COUNT with its own tag (a named burn candidate), never a
     * decline that loses a working row verdict. */
    private static int @com.legend.Nullable [] sortKeyIndexes(
            List<String> columnNames) {
        List<String> keys = SORT_KEYS.get();
        if (keys == null) {
            return null;
        }
        int[] idx = new int[keys.size()];
        for (int k = 0; k < keys.size(); k++) {
            int at = columnNames.indexOf(keys.get(k));
            if (at < 0) {
                for (int i = 0; i < columnNames.size(); i++) {
                    if (columnNames.get(i).equalsIgnoreCase(keys.get(k))) {
                        at = i;
                        break;
                    }
                }
            }
            if (at < 0) {
                return null;
            }
            idx[k] = at;
        }
        return idx;
    }

    /** The counted §7 residue: an ORDERED query whose strict compare
     * had no usable tie boundaries rode the multiset verdict. */
    private static void ordFallback() {
        if (System.getenv("LL_ORD_COUNT") != null) {
            System.err.println(
                    "[ord] h2-oracle ordered-keys-unmappable multiset"
                            + " fallback");
        }
    }

    private static String keyTuple(String[] cells, int[] keyIdx) {
        StringBuilder k = new StringBuilder();
        for (int i : keyIdx) {
            if (k.length() > 0) {
                k.append('|');
            }
            k.append(cells[i]);
        }
        return k.toString();
    }

    /** The §7 IN-ORDER verdict with TIE GROUPS: both sides' sort-key
     * sequences must agree POSITIONALLY (order is contract); full rows
     * compare as multisets WITHIN each maximal run of equal
     * consecutive keys (rows tied on the sort key have no defined
     * relative order on either backend —
     * testSortByLambdaMultiple's two Johns). */
    private static @com.legend.Nullable String orderedVerdict(
            List<String> theirs, List<String> mine,
            List<String> theirKeys, List<String> mineKeys) {
        if (theirs.size() != mine.size()
                || !theirKeys.equals(mineKeys)) {
            return divergence(theirs, mine);
        }
        int i = 0;
        while (i < theirs.size()) {
            int j = i + 1;
            while (j < theirKeys.size()
                    && theirKeys.get(j).equals(theirKeys.get(i))) {
                j++;
            }
            List<String> a = new ArrayList<>(theirs.subList(i, j));
            List<String> b = new ArrayList<>(mine.subList(i, j));
            Collections.sort(a);
            Collections.sort(b);
            if (!a.equals(b)) {
                return divergence(theirs, mine);
            }
            i = j;
        }
        return null;
    }

    /** The one divergence tail (both compare paths). The old
     * "row-cardinality skew" decline that lived here was ADJUDICATED
     * and burned (SQLTEXT charter §6.1, 2026-09-01): our lowering
     * introduces zero uncommanded dedup — the engine's extra rows are
     * its one-object-per-row algebra (RelationalResult.java, zero
     * distinct/dedup/pk sites) fanning out over unfiltered join legs,
     * unpinned by its own asserts. Instance frames now resolve it via
     * the graph compare's EXTENT_SUBSET pk-collapse (a real row
     * verdict); everywhere else — value/tabular frames, where pure
     * semantics PRESERVES duplicates — a duplication difference is a
     * REAL divergence and fails loudly. */
    private static String divergence(List<String> theirs,
            List<String> mine) {
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

    /** The shared TDG row referee tail: header (projection) equality,
     * then ORDER-INSENSITIVE row equality under the cell canon. */
    static @com.legend.Nullable String multisetCompare(
            List<String> golden, List<String> ourRows) {
        String gCols = golden.get(0);
        String oCols = ourRows.get(0);
        if (!gCols.equals(oCols)) {
            // DIFFERENT projections for the same step — a DEMAND
            // divergence, not a row divergence: named residue
            throw new Unverifiable("projection differs: golden "
                    + gCols + " vs ours " + oCols, null);
        }
        List<String> g = new java.util.ArrayList<>(golden);
        List<String> o = new java.util.ArrayList<>(ourRows);
        java.util.Collections.sort(g);
        java.util.Collections.sort(o);
        return g.equals(o) ? null
                : "tdg-replay: golden rows " + (golden.size() - 1)
                        + " vs ours " + (ourRows.size() - 1)
                        + " — first diff at " + firstDiff(g, o);
    }

    /** A chained fetch's LIVE-SESSION transcript rows (the generator's
     * per-fetch capture) rendered in the {@link #rawRows} shape — same
     * header row, same name-sorted columns, same cell canon — so both
     * referee sides speak one spelling. */
    public static List<String> transcriptRows(List<String> cols,
            List<List<Object>> rows) {
        int n = cols.size();
        String[] names = new String[n];
        for (int i = 0; i < n; i++) {
            names[i] = cols.get(i).toLowerCase();
        }
        Integer[] order = nameOrder(names);
        List<String> out = new java.util.ArrayList<>(rows.size() + 1);
        StringBuilder hdr = new StringBuilder("<cols>");
        for (int k = 0; k < n; k++) {
            hdr.append('|').append(names[order[k]]);
        }
        out.add(hdr.toString());
        for (List<Object> row : rows) {
            StringBuilder sb = new StringBuilder();
            for (int k = 0; k < n; k++) {
                int i = order[k];
                if (k > 0) {
                    sb.append('|');
                }
                sb.append(names[i]).append('=').append(norm(row.get(i)));
            }
            out.add(sb.toString());
        }
        return out;
    }


    /** The ONE column-ordering policy for the TDG row referee (rawRows
     * and transcriptRows both render through it — two-sided by
     * construction): indices sorted by lowercased column NAME. */
    private static Integer[] nameOrder(String[] names) {
        Integer[] order = new Integer[names.length];
        for (int i = 0; i < names.length; i++) {
            order[i] = i;
        }
        java.util.Arrays.sort(order,
                java.util.Comparator.comparing(i -> names[i]));
        return order;
    }

    /** Rows as NAME-SORTED {@code col=val|...} strings plus one
     * {@code <cols>} header row — column ORDER is not a fetch contract,
     * column IDENTITY is (a projection mismatch compares as unequal
     * header rows, never a garbled cell compare). */
    static List<String> rawRows(Statement st, String sql)
            throws SQLException {
        List<String> out = new java.util.ArrayList<>();
        try (ResultSet rs = st.executeQuery(sql)) {
            var md = rs.getMetaData();
            int n = md.getColumnCount();
            String[] names = new String[n];
            for (int i = 0; i < n; i++) {
                names[i] = md.getColumnLabel(i + 1).toLowerCase();
            }
            Integer[] order = nameOrder(names);
            StringBuilder hdr = new StringBuilder("<cols>");
            for (int k = 0; k < n; k++) {
                hdr.append('|').append(names[order[k]]);
            }
            out.add(hdr.toString());
            while (rs.next()) {
                StringBuilder row = new StringBuilder();
                for (int k = 0; k < n; k++) {
                    int i = order[k];
                    if (k > 0) {
                        row.append('|');
                    }
                    row.append(names[i]).append('=')
                            .append(norm(rs.getObject(i + 1)));
                }
                out.add(row.toString());
            }
        }
        return out;
    }

    private static String firstDiff(List<String> g, List<String> o) {
        int n = Math.min(g.size(), o.size());
        for (int i = 0; i < n; i++) {
            if (!g.get(i).equals(o.get(i))) {
                return "[" + g.get(i) + "] vs [" + o.get(i) + "]";
            }
        }
        return g.size() > n ? "golden extra [" + g.get(n) + "]"
                : o.size() > n ? "ours extra [" + o.get(n) + "]" : "?";
    }

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
