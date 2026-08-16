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
 * <p>Connection settings mirror the engine's own H2 2.1.214 test server
 * (H2Manager: NON_KEYWORDS + MODE=LEGACY) plus DATABASE_TO_UPPER=false so
 * the corpus's unquoted mixed-case DDL matches the goldens' quoted
 * column spellings.
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
                // Arrays.asList: NULL ELEMENTS survive (SQL NULL cells)
                return new java.util.ArrayList<>(java.util.Arrays.asList(
                        (Object[]) arr.getArray()));
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
        UNVERIFIABLE_CENSUS.computeIfAbsent(bucketOf(reason),
                k -> new java.util.concurrent.atomic.LongAdder()).increment();
    }

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
            java.util.Map<Integer, java.util.Map<String, String>> enumDecode) {
        if (!READY) {
            throw new Unverifiable("h2 driver not on classpath", null);
        }
        // only FLAT TABULAR frames compare cell-for-cell against raw SQL
        // rows: class/graph carriers wrap rows in JSON.
        if (!(ours instanceof ExecutionResult.Tabular tab)) {
            throw new Unverifiable("non-tabular result frame", null);
        }
        // ENUM-typed frames compare through the SAME decode the frame
        // ran: some queries select the RAW source code (the engine
        // decodes post-SQL) while the frame carries decoded names — the
        // caller supplies the per-column source->name map (c46, the
        // enum-decode replay rung; c42 witnesses in the ledger). A
        // column with NO derivable map keeps the counted decline.
        for (int i = 0; i < tab.columns().size(); i++) {
            if (tab.columns().get(i).pureType()
                    instanceof com.legend.compiler.element.type.Type.EnumType
                    && !enumDecode.containsKey(i)) {
                throw new Unverifiable(
                        "enum-decoded column (post-transform rows)", null);
            }
        }
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
                            mirror.poison = "seed replay: " + e.getMessage();
                            throw new Unverifiable(mirror.poison, e);
                        }
                    }
                    mirror.applied++;
                }
                return goldenRowsCompare(st, goldenSql, tab, enumDecode);
            } catch (SQLException e) {
                throw new Unverifiable("h2 connection: " + e.getMessage(), e);
            }
        }
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
                return goldenRowsCompare(st, goldenSql, tab, enumDecode);
            }
        } catch (SQLException e) {
            throw new Unverifiable("h2 connection: " + e.getMessage(), e);
        }
    }

    /** The JSON carrier has NO temporal types — a Date-family element
     * arrives as its ISO text. The DECLARED pure type drives the
     * decode back (never value sniffing on non-temporal roots): ISO
     * date-time text to Timestamp, bare dates to java.sql.Date. */
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
                out.add(s.contains("T")
                        ? java.sql.Timestamp.valueOf(s.replace('T', ' '))
                        : java.sql.Date.valueOf(s));
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
            java.util.Map<Integer, java.util.Map<String, String>> enumDecode)
            throws SQLException {
        long t0 = System.nanoTime();
        try {
            return "H2".equals(session.getMetaData().getDatabaseProductName())
                    ? verifyOnSession(session, goldenSql, ours, enumDecode)
                    : verify(seeds, goldenSql, ours, enumDecode);
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
            java.util.Map<Integer, java.util.Map<String, String>> enumDecode) {
        if (!(ours instanceof ExecutionResult.Tabular tab)) {
            throw new Unverifiable("non-tabular result frame", null);
        }
        for (int i = 0; i < tab.columns().size(); i++) {
            if (tab.columns().get(i).pureType()
                    instanceof com.legend.compiler.element.type.Type.EnumType
                    && !enumDecode.containsKey(i)) {
                throw new Unverifiable(
                        "enum-decoded column (post-transform rows)", null);
            }
        }
        try (Statement st = session.createStatement()) {
            return goldenRowsCompare(st, goldenSql, tab, enumDecode);
        } catch (SQLException e) {
            throw new Unverifiable("session golden execution: "
                    + e.getMessage(), e);
        }
    }

    /** Run the golden SELECT on {@code st}, compare rows with the frame
     * as ORDER-INSENSITIVE multisets of normalized cells (shared by the
     * replay oracle and the session-direct verify). */
    private static @com.legend.Nullable String goldenRowsCompare(Statement st,
            String goldenSql, ExecutionResult.Tabular tab,
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
                Collections.sort(theirs);
                Collections.sort(mine);
                if (theirs.equals(mine)) {
                    return null;
                }
                return "h2-advisory divergence: golden SQL on H2 gave "
                        + theirs.size() + " row(s) " + head(theirs)
                        + ", our pipeline gave " + mine.size() + " row(s) "
                        + head(mine);
    }

    private static String head(List<String> rows) {
        return rows.subList(0, Math.min(rows.size(), 5)).toString();
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
        var exec = ExecCallFinder.find(actual, lets, execStmts);
        String mappingRef = exec != null && exec.parameters().size() >= 2
                && exec.parameters().get(1) instanceof
                        com.legend.protocol.spec.PackageableElementPtr p
                ? p.fullPath() : null;
        if (mappingRef == null) {
            return java.util.Map.of();
        }
        // the pointer carries the SOURCE spelling — resolve simple names
        // through the test's import wildcards (findLegacyMapping wants
        // the FQN)
        String mappingFqn = mappingRef;
        if (!mappingRef.contains("::")
                && ctx.findLegacyMapping(mappingRef).isEmpty()) {
            for (String w : imports.wildcards()) {
                if (ctx.findLegacyMapping(w + "::" + mappingRef)
                        .isPresent()) {
                    mappingFqn = w + "::" + mappingRef;
                    break;
                }
            }
        }
        var out = new java.util.LinkedHashMap<Integer,
                java.util.Map<String, String>>();
        for (int i = 0; i < tab.columns().size(); i++) {
            if (!(tab.columns().get(i).pureType() instanceof
                    com.legend.compiler.element.type.Type.EnumType et)) {
                continue;
            }
            var em = com.legend.plan.PlanText.enumMappingOf(ctx, mappingFqn,
                    et.fqn());
            if (em == null) {
                continue;
            }
            var dec = new java.util.LinkedHashMap<String, String>();
            boolean whole = true;
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
                        // cross-enum source: not decodable here — the
                        // WHOLE column keeps the decline (a partial map
                        // would half-decode)
                        case com.legend.model.EnumerationMapping.SourceValue
                                .EnumRef ignored -> whole = false;
                    }
                }
            }
            if (whole) {
                out.put(i, dec);
            }
        }
        return out;
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
