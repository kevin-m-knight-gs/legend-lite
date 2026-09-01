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
                            .append(fallbacks).append(")\n");
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
            fallback("flip-error: " + t.getClass().getSimpleName(),
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
        // EFFECT ROUTING (effectful cutover, 2026-09-01): effects split
        // by RE-RUN SAFETY. dropAndCreate*/TDG bodies are STATE-
        // CONVERGENT (drop-then-create DDL + temp-table
        // materialization: a walk re-run after any partial platform
        // execution lands the same end state), so they flip with
        // ordinary fallback semantics. executeInDb runs ARBITRARY
        // SQL — a re-run could double a bare write — so those bodies
        // keep the gate (fallback BEFORE execution, never after).
        try {
            switch (effectKind(resolved, ctx)) {
                case IRREVERSIBLE -> {
                    return fallback("effectful", test);
                }
                case NONE, RERUNNABLE -> {
                }
            }
        } catch (RuntimeException e) {
            return fallback("wall-type: " + bucketOf(e.getMessage()), test);
        }
        List<Boolean> events = new ArrayList<>();
        try {
            // the oracle registers beside the listener (SQLTEXT charter
            // §2) — the flipped env carries it for the verdict arms
            Compiler.executeResolved(resolved, ctx, runtimeFqn, conn,
                    (name, pass, detail) -> events.add(pass),
                    ReplayOracle.INSTANCE);
        } catch (com.legend.error.NotImplementedException e) {
            return fallback("wall-exec: " + bucketOf(e.getMessage()), test);
        } catch (java.sql.SQLException e) {
            // the walk passes what the platform fails (or the platform
            // hit a data-layer error): the REAL-divergence burn list —
            // walk re-scores (safe: effects are state-convergent), row
            // counted
            return fallback("platform-fail: " + bucketOf(e.getMessage()),
                    test);
        } catch (RuntimeException e) {
            return fallback("wall-exec: " + e.getClass().getSimpleName()
                    + ": " + bucketOf(e.getMessage()), test);
        }
        long passes = events.stream().filter(Boolean::booleanValue).count();
        if (passes != events.size()
                || events.isEmpty() && asserts > 0) {
            return fallback("platform-fail: verdict stream "
                    + passes + "/" + events.size(), test);
        }
        FLIPPED.incrementAndGet();
        return new EngineTestExecutor.Outcome.Ran((int) passes, 0,
                asserts > 0 ? statements.size() : executable,
                List.of(), List.of());
    }

    private enum EffectKind { NONE, RERUNNABLE, IRREVERSIBLE }

    /** Re-run-safety classification, judged at BODY level: the walk
     * re-runs the WHOLE body, so convergence is a property of the
     * ordered effect stream, not of one call. The dropAndCreate DDL
     * natives and TDG generators are
     * convergent by construction; executeInDb literals classify by
     * verb — read-only statements are free, DROP is free, and
     * CREATE/INSERT are legal once a DROP occurred earlier in the body
     * (the corpus's drop-led setup idiom: a re-run re-drops before it
     * re-creates and re-seeds, landing the same end state). Any other
     * verb, or NON-literal SQL, is IRREVERSIBLE and keeps the gate. */
    private static EffectKind effectKind(ValueSpecification resolved,
            com.legend.compiler.element.ModelContext ctx) {
        if (!Compiler.hasStatementEffects(resolved, ctx)) {
            return EffectKind.NONE;
        }
        com.legend.compiler.spec.SpecCompiler specs =
                new com.legend.compiler.spec.SpecCompiler(ctx);
        List<String> sqls = new ArrayList<>();
        java.util.Set<String> visiting = new java.util.HashSet<>();
        for (com.legend.compiler.spec.typed.TypedSpec s
                : specs.typeQueryBody(resolved)) {
            if (!collectExecInDb(s, specs, visiting, sqls)) {
                return EffectKind.IRREVERSIBLE;   // non-literal SQL
            }
        }
        boolean dropped = false;
        for (String sql : sqls) {
            for (String st : sql.split(";")) {
                String t = st.strip();
                if (t.isEmpty()) {
                    continue;
                }
                String verb = t.split("\\s+", 2)[0].toUpperCase(
                        java.util.Locale.ROOT);
                switch (verb) {
                    case "SELECT", "SHOW", "WITH", "VALUES", "CALL" -> {
                    }
                    case "DROP" -> dropped = true;
                    case "CREATE", "INSERT" -> {
                        if (!dropped) {
                            return EffectKind.IRREVERSIBLE;
                        }
                    }
                    default -> {
                        return EffectKind.IRREVERSIBLE;
                    }
                }
            }
        }
        return EffectKind.RERUNNABLE;
    }

    /** Append every executeInDb SQL literal under {@code n} (in body
     * order, through compiled user-function bodies); false when any
     * executeInDb argument is not a literal string. */
    private static boolean collectExecInDb(
            com.legend.compiler.spec.typed.TypedSpec n,
            com.legend.compiler.spec.SpecCompiler specs,
            java.util.Set<String> visiting, List<String> out) {
        if (n instanceof com.legend.compiler.spec.typed.TypedNativeCall nc
                && com.legend.compiler.element.type.PlatformTypes
                        .EXECUTE_IN_DB.equals(nc.callee().qualifiedName())) {
            if (nc.args().isEmpty() || !(nc.args().get(0)
                    instanceof com.legend.compiler.spec.typed
                            .TypedCString sql)) {
                return false;
            }
            out.add(sql.value());
            return true;
        }
        if (n instanceof com.legend.compiler.spec.typed.TypedUserCall uc) {
            String key = uc.callee().signatureKey();
            if (visiting.add(key)) {   // cycles collect once
                try {
                    for (com.legend.compiler.spec.typed.TypedSpec stmt
                            : specs.compile(uc.callee()).body()) {
                        if (!collectExecInDb(stmt, specs, visiting, out)) {
                            return false;
                        }
                    }
                } finally {
                    visiting.remove(key);
                }
            }
        }
        for (com.legend.compiler.spec.typed.TypedSpec c : n.children()) {
            if (!collectExecInDb(c, specs, visiting, out)) {
                return false;
            }
        }
        return true;
    }

    private static EngineTestExecutor.@com.legend.Nullable Outcome fallback(
            String bucket, String witness) {
        BUCKETS.computeIfAbsent(bucket, k -> new AtomicLong())
                .incrementAndGet();
        WITNESSES.putIfAbsent(bucket, witness);
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
