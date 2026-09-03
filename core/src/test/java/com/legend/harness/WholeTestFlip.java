package com.legend.harness;

import com.legend.Compiler;
import com.legend.compiler.NameResolver;
import com.legend.compiler.element.ModelContext;
import com.legend.model.ImportScope;
import com.legend.protocol.spec.AppliedFunction;
import com.legend.protocol.spec.LambdaFunction;
import com.legend.protocol.spec.ValueSpecification;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * HARNESS-DELETION item 1, slice 3 — the SCORING FLIP
 * ({@code -Dll.wholetest.flip.score}; the migration itself, not an
 * instrument): a test whose whole body the platform can run scores
 * from the platform's assert verdicts (the {@code AssertListener}
 * events); everything else falls back to the legacy walk with a
 * COUNTED reason. The fallback census is the program's burn-down
 * surface (charter: WHOLETEST_COMPILATION_CHARTER.md).
 *
 * <p>Walk-routes, each a named census bucket:
 * <ul>
 * <li>{@code text-policy} — the body carries golden-TEXT asserts
 *     (SQL/plan text producers or the sql-assert forms): their
 *     advisory/rescue policy lives in the walk and DIES with emission
 *     byte-parity (blueprint item 4) — never ported, per user ruling
 *     2026-08-31 ("do this later if we need it").</li>
 * <li>{@code effectful} — the body writes; single-execution semantics
 *     only, joins at cutover confidence.</li>
 * <li>{@code assert-free} — the walk's executed-statement detail
 *     string is the scoreboard row; joins with a detail-parity leg.</li>
 * <li>{@code seed-softened} — the walk owns seed-failure softening.</li>
 * <li>{@code wall-*}/{@code platform-fail} — platform can't run or
 *     fails the body the walk passes (re-running the walk is safe:
 *     the effect gate proved the body read-only). platform-fail rows
 *     are the REAL-divergence burn list (TDSNull membership,
 *     grid-canon renders — see charter slice 3 notes).</li>
 * </ul>
 */
public final class WholeTestFlip {

    private WholeTestFlip() {
    }

    private static final Map<String, AtomicLong> BUCKETS =
            new ConcurrentHashMap<>();
    private static final Map<String, String> WITNESSES =
            new ConcurrentHashMap<>();
    private static final AtomicLong FLIPPED = new AtomicLong();
    /** Failure-path instruments for the transactional flip attempt:
     * mirror detaches (cursor ran ahead of a rolled-back attempt) and
     * rollback failures (session state unknown — investigate any
     * sweep showing one). */
    private static final AtomicLong MIRROR_DETACHES = new AtomicLong();
    private static final AtomicLong ROLLBACKS = new AtomicLong();
    private static final AtomicLong ROLLBACK_FAILURES = new AtomicLong();
    /** Flipped-test ROSTER (diffable attribution): a ±1 run-to-run
     * wobble in the ratchet was unattributable — the fallback file
     * names only fallbacks. Dumped beside it at shutdown. */
    private static final java.util.concurrent.ConcurrentLinkedQueue<String>
            FLIPPED_TESTS = new java.util.concurrent.ConcurrentLinkedQueue<>();
    /** Bucket → EVERY fallback test (the per-bucket roster; metamodel
     * handoff 2026-09-02 §5 step 1): the fallback file names ONE
     * witness per bucket, so a bucket whose wall prints no per-test
     * debug line (the two HN-vocabulary buckets, ~88 tests) could be
     * counted but not named. Dumped beside the other two rosters at
     * shutdown as {@code target/wholetest-flip-buckets.txt}. */
    private static final Map<String,
            java.util.concurrent.ConcurrentLinkedQueue<String>> BUCKET_TESTS =
            new ConcurrentHashMap<>();

    /** Runner-pin accessors (the shrink-only migration ratchet). */
    public static long flippedCount() {
        return FLIPPED.get();
    }

    public static long fallbackCount() {
        return BUCKETS.values().stream().mapToLong(AtomicLong::get).sum();
    }

    static {
        if (enabled()) {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    StringBuilder sb = new StringBuilder();
                    long fallbacks = BUCKETS.values().stream()
                            .mapToLong(AtomicLong::get).sum();
                    sb.append("whole-test scoring flip (flipped=")
                            .append(FLIPPED.get()).append(" fallbacks=")
                            .append(fallbacks)
                            .append(" rollbacks=")
                            .append(ROLLBACKS.get())
                            .append(" mirror-detaches=")
                            .append(MIRROR_DETACHES.get())
                            .append(" rollback-failures=")
                            .append(ROLLBACK_FAILURES.get())
                            .append(")\n");
                    // insertion order (post-process with sort -rn): the
                    // harness sort-site guard reserves ordering for
                    // comparison policy, never report format
                    BUCKETS.forEach((k, v) -> sb.append(String.format(
                            "%6d  %s%n", v.get(), k))
                            .append("        e.g. ")
                            .append(WITNESSES.getOrDefault(k, "?"))
                            .append('\n'));
                    java.nio.file.Files.writeString(
                            java.nio.file.Path.of(
                                    "target/wholetest-flip-fallbacks.txt"),
                            sb.toString());
                    java.nio.file.Files.writeString(
                            java.nio.file.Path.of(
                                    "target/wholetest-flipped.txt"),
                            FLIPPED_TESTS.stream().sorted()
                                    .collect(java.util.stream.Collectors
                                            .joining("\n")) + "\n");
                    // bucket roster: buckets by name, tests by name —
                    // display ordering for a DIFFABLE file (the
                    // flipped-roster class; no comparison flows here)
                    StringBuilder bk = new StringBuilder();
                    BUCKET_TESTS.keySet().stream().sorted().forEach(k -> {
                        java.util.List<String> tests = BUCKET_TESTS.get(k)
                                .stream().sorted().toList();
                        bk.append(String.format("%6d  %s%n",
                                tests.size(), k));
                        for (String t : tests) {
                            bk.append("        ").append(t).append('\n');
                        }
                    });
                    java.nio.file.Files.writeString(
                            java.nio.file.Path.of(
                                    "target/wholetest-flip-buckets.txt"),
                            bk.toString());
                } catch (Throwable ignored) {
                    // census write best-effort at shutdown
                }
            }));
        }
    }

    private static boolean enabled() {
        // DEFAULT-ON (2026-08-31, after the gate items burned/attributed:
        // untyped=0, counter moves witnessed as the guards' own
        // shape-driven doctrine classes, text/dual-channel pins
        // byte-identical under the flagged sweep). Opt-out for A/B.
        return System.getProperty("ll.wholetest.flip.score.off") == null;
    }

    /** Non-null = the platform ran and scored the whole test; null =
     * the walk owns it (reason censused). Never throws: any surprise
     * is a fallback row, and the walk's verdict stands. */
    static EngineTestExecutor.@com.legend.Nullable Outcome tryFlip(
            ModelContext ctx, List<ValueSpecification> statements,
            ImportScope imports, String runtimeFqn, Connection conn,
            boolean emptinessUnverifiable,
            @com.legend.Nullable List<String> seedFailures) {
        if (!enabled()) {
            return null;
        }
        String test = com.legend.exec.CanonicalDivergence.CONTEXT_SOURCE.get();
        try {
            return tryFlipInner(ctx, statements, imports, runtimeFqn, conn,
                    emptinessUnverifiable, seedFailures, test);
        } catch (Throwable t) {
            fallback("flip-error: " + t.getClass().getSimpleName(), test,
                    test + " :: " + t.getMessage());
            return null;
        }
    }

    private static EngineTestExecutor.@com.legend.Nullable Outcome tryFlipInner(
            ModelContext ctx, List<ValueSpecification> statements,
            ImportScope imports, String runtimeFqn, Connection conn,
            boolean emptinessUnverifiable,
            @com.legend.Nullable List<String> seedFailures, String test) {
        if (emptinessUnverifiable
                || seedFailures != null && !seedFailures.isEmpty()) {
            return fallback("seed-softened", test);
        }
        // PER-ASSERT text gating (item 3): only asserts that JUDGE text
        // route the test to the walk's rescue policy — matching the
        // walk's own recognizer (a golden-SQL read anywhere in the
        // ASSERT's expression, incl. through a let-bound producer:
        // taint flows let-to-let in statement order). A producer that
        // feeds prints or nothing does not exclude the test.
        int asserts = 0;
        java.util.Set<String> tainted = new java.util.LinkedHashSet<>();
        for (ValueSpecification s : statements) {
            if (s instanceof AppliedFunction let
                    && let.function().equals("letFunction")
                    && let.parameters().size() == 2
                    && let.parameters().get(0)
                            instanceof com.legend.protocol.spec.CString ln) {
                ValueSpecification rhs = let.parameters().get(1);
                if (EngineTestExecutor.containsSqlProducer(rhs, ctx)
                        || EngineTestExecutor.referencesAny(rhs, tainted)) {
                    tainted.add(ln.value());
                }
                continue;
            }
            if (s instanceof AppliedFunction af
                    && EngineTestExecutor.resolvesTo(af, ctx,
                            EngineTestExecutor.ASSERT_FORM_FQNS)) {
                asserts++;
                if (EngineTestExecutor.resolvesTo(af, ctx,
                        EngineTestExecutor.SQL_ASSERT_FORM_FQNS)
                        || EngineTestExecutor.containsSqlProducer(s, ctx)
                        || EngineTestExecutor.referencesAny(s, tainted)) {
                    // SQLTEXT slice 3a: bodies whose every sql assert
                    // is the tosqlstring-simple shape flip — the
                    // platform's SqlTextVerdicts arm judges them on
                    // rows (the flip's env carries the oracle). All
                    // other shapes keep the text-policy fallback and
                    // the shape census records them
                    // (target/sqltext-shape-census.txt).
                    if (SqlTextShapes.allSimple(statements, ctx)) {
                        break;
                    }
                    SqlTextShapes.record(test, statements, ctx);
                    return fallback("text-policy", test);
                }
            }
        }
        int executable = 0;
        boolean printMaterial = false;
        if (asserts == 0) {
            // ASSERT-FREE bodies: running to completion IS the contract
            // (engine parity). Countable work = non-let, non-print
            // expression statements — the platform's print is a NO-OP
            // whose argument never evaluates, so a print-driven body
            // (plan-print tests) must stay on the walk, which evaluates
            // the print material as the test's whole contract; and a
            // lets-only body stays SHAPE ('no verifying assertions')
            // exactly as the walk scores it.
            for (ValueSpecification s : statements) {
                if (!(s instanceof AppliedFunction af2)
                        || af2.function().equals("letFunction")) {
                    continue;
                }
                if (EngineTestExecutor.resolvesTo(af2, ctx,
                        EngineTestExecutor.PRINT_FQNS)) {
                    printMaterial = true;
                    continue;
                }
                executable++;
            }
            if (printMaterial) {
                return fallback("assert-free-print", test);
            }
            if (executable == 0) {
                return fallback("assert-free-inert", test);
            }
        }
        ValueSpecification resolved;
        try {
            resolved = NameResolver.resolveQuery(
                    new LambdaFunction(List.of(), List.copyOf(statements)),
                    imports, ctx.elementFqns());
        } catch (RuntimeException e) {
            return fallback("wall-resolve: " + bucketOf(e.getMessage()), test);
        }
        // EFFECT ATOMICITY (effectful cutover, 2026-09-01 — the charter
        // burn-map item; replaced the static verb classification): every
        // effect-bearing body executes inside a TRANSACTION on the
        // session connection — commit only after the verdict stream
        // passes, rollback on ANY failure exit so the walk's fallback
        // re-run starts from pristine state. Re-run safety is a property
        // of the MECHANISM now, not of the SQL text, so computed-SQL
        // bodies (TDG loadAndTestExecution, the modelJoin setups — the
        // old 82-test "effectful" bucket) flip like everything else.
        // Ledger discipline: statements recorded during a rolled-back
        // attempt truncate with it (unrecordLast, range edition); a
        // mirror whose cursor ran ahead mid-body DETACHES to the
        // fresh-replay path (ReplayOracle.mirrorDetachIfAhead —
        // failure-path only).
        boolean effectful;
        try {
            effectful = Compiler.hasStatementEffects(resolved, ctx);
        } catch (RuntimeException e) {
            if (System.getenv("LL_TMP_DEBUG") != null) {
                System.err.println("[flip-wall-debug] " + test + " :: "
                        + e.getMessage());
            }
            return fallback("wall-type: " + bucketOf(e.getMessage()), test);
        }
        List<Boolean> events = new ArrayList<>();
        com.legend.sql.dialect.RawSqlBoundary.LedgerMark mark = null;
        boolean txn = false;
        if (effectful) {
            try {
                mark = ReplayOracle.beginAttempt(conn);
                txn = true;
            } catch (java.sql.SQLException e) {
                return fallback("flip-txn: begin failed: "
                        + bucketOf(e.getMessage()), test);
            }
        }
        String reason = null;
        try {
            try {
                // the oracle registers beside the listener (SQLTEXT
                // charter §2) — the flipped env carries it for the
                // verdict arms
                Compiler.executeResolved(resolved, ctx, runtimeFqn, conn,
                        (name, pass, detail) -> events.add(pass),
                        ReplayOracle.INSTANCE);
            } catch (com.legend.error.NotImplementedException e) {
                reason = "wall-exec: " + bucketOf(e.getMessage());
            } catch (com.legend.error.DataError
                    | com.legend.error.AssertFailed e) {
                // the seam: platform failures are AssertFailed/DataError
                // now the walk passes what the platform fails (or the
                // platform hit a data-layer error): the REAL-divergence
                // burn list — walk re-scores (safe: the transaction
                // rolled the attempt back), row counted
                if (System.getenv("LL_TMP_DEBUG") != null) {
                    StackTraceElement[] st = e.getStackTrace();
                    System.err.println("[flip-fail-debug] " + test + " :: "
                            + e.getClass().getSimpleName() + ": "
                            + String.valueOf(e.getMessage()).replace('\n', '|')
                            + " @ " + (st.length > 0 ? st[0] : "?")
                            + (st.length > 1 ? " < " + st[1] : "")
                            + (st.length > 2 ? " < " + st[2] : ""));
                }
                reason = "platform-fail: " + bucketOf(e.getMessage());
            } catch (RuntimeException e) {
                if (System.getenv("LL_TMP_DEBUG") != null) {
                    StackTraceElement[] st = e.getStackTrace();
                    System.err.println("[flip-fail-debug] " + test + " :: "
                            + e.getClass().getSimpleName() + ": "
                            + e.getMessage()
                            + " @ " + (st.length > 0 ? st[0] : "?")
                            + (st.length > 1 ? " < " + st[1] : "")
                            + (st.length > 2 ? " < " + st[2] : ""));
                }
                reason = "wall-exec: " + e.getClass().getSimpleName()
                        + ": " + bucketOf(e.getMessage());
            }
            long passes = events.stream()
                    .filter(Boolean::booleanValue).count();
            if (reason == null && (passes != events.size()
                    || events.isEmpty() && asserts > 0)) {
                reason = "platform-fail: verdict stream "
                        + passes + "/" + events.size();
            }
            if (reason == null && txn) {
                try {
                    ReplayOracle.commitAttempt(conn);
                    txn = false;
                } catch (java.sql.SQLException e) {
                    reason = "flip-txn: commit failed: "
                            + bucketOf(e.getMessage());
                }
            }
            if (reason != null) {
                return fallback(reason, test);
            }
            FLIPPED.incrementAndGet();
            FLIPPED_TESTS.add(test);
            return new EngineTestExecutor.Outcome.Ran((int) passes, 0,
                    asserts > 0 ? statements.size() : executable,
                    List.of(), List.of());
        } finally {
            if (txn) {
                // failure exit: restore the family-session WORLD to the
                // mark (ReplayOracle owns the protocol) so the walk's
                // re-run is a FIRST run
                try {
                    boolean detached = ReplayOracle.rollbackAttempt(conn,
                            java.util.Objects.requireNonNull(mark, "mark"));
                    ROLLBACKS.incrementAndGet();
                    if (detached) {
                        MIRROR_DETACHES.incrementAndGet();
                    }
                } catch (java.sql.SQLException e) {
                    // session state unknown — the walk still runs; keep
                    // this LOUD (stderr always, plus the census header
                    // counter) so a sweep with one of these is suspect
                    ROLLBACK_FAILURES.incrementAndGet();
                    System.err.println("[flip-txn] ROLLBACK FAILED for "
                            + test + ": " + e.getMessage());
                }
            }
        }
    }

    private static EngineTestExecutor.@com.legend.Nullable Outcome fallback(
            String bucket, String test) {
        return fallback(bucket, test, test);
    }

    /** {@code test} = the roster row (the test's name only);
     * {@code witness} = the e.g. line of the census file (may carry the
     * error message after the name). */
    private static EngineTestExecutor.@com.legend.Nullable Outcome fallback(
            String bucket, String test, String witness) {
        BUCKETS.computeIfAbsent(bucket, k -> new AtomicLong())
                .incrementAndGet();
        WITNESSES.putIfAbsent(bucket, witness);
        BUCKET_TESTS.computeIfAbsent(bucket,
                k -> new java.util.concurrent.ConcurrentLinkedQueue<>())
                .add(test);
        return null;
    }

    private static String bucketOf(@com.legend.Nullable String m) {
        String s = String.valueOf(m);
        for (String line : s.split("\n")) {
            if (!line.isBlank()) {
                s = line;
                break;
            }
        }
        return (s.length() > 120 ? s.substring(0, 120) : s)
                .replaceAll("'[^']*'", "'_'").replaceAll("\\d+", "N");
    }
}
