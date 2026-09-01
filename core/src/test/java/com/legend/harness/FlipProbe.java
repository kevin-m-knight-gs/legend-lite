package com.legend.harness;

import com.legend.Compiler;
import com.legend.compiler.NameResolver;
import com.legend.compiler.element.ModelContext;
import com.legend.model.ImportScope;
import com.legend.protocol.spec.LambdaFunction;
import com.legend.protocol.spec.ValueSpecification;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * HARNESS-DELETION item 1, slice 2 — the dual-run agreement instrument
 * (flag {@code -Dll.wholetest.flip}; migration scaffolding, dies at
 * cutover). AFTER the legacy walk scores a test, the probe re-runs the
 * WHOLE raw statement list as one unit through the platform
 * ({@code Compiler.executeResolved} with an
 * {@link com.legend.exec.AssertListener}) and compares verdicts against
 * the walk's own returned outcome. Every fact it needs comes from
 * COMPILED STATE, never harness heuristics: re-run safety is
 * {@code Compiler.hasStatementEffects} (the platform's transitive
 * effect scan — an effectful body is excluded, not re-executed), and
 * the legacy side is the {@code Outcome.Ran} the walk already returns.
 * Agreement histogram: {@code target/wholetest-flip-census.txt}. The
 * probe must never move a verdict: it swallows everything.
 */
final class FlipProbe {

    private FlipProbe() {
    }

    private static final Map<String, AtomicLong> BUCKETS =
            new ConcurrentHashMap<>();
    private static final Map<String, String> WITNESSES =
            new ConcurrentHashMap<>();

    static {
        if (enabled()) {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    StringBuilder sb = new StringBuilder();
                    long total = BUCKETS.values().stream()
                            .mapToLong(AtomicLong::get).sum();
                    sb.append("whole-test flip dual-run census (tests=")
                            .append(total).append(")\n");
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
                                    "target/wholetest-flip-census.txt"),
                            sb.toString());
                } catch (Throwable ignored) {
                    // census write best-effort at shutdown
                }
            }));
        }
    }

    private static boolean enabled() {
        return System.getProperty("ll.wholetest.flip") != null;
    }

    static void probe(EngineTestExecutor.@com.legend.Nullable Outcome legacy,
            ModelContext ctx, List<ValueSpecification> statements,
            ImportScope imports, String runtimeFqn, Connection conn) {
        if (!enabled()) {
            return;
        }
        String test = com.legend.exec.CanonicalDivergence.CONTEXT_SOURCE.get();
        try {
            String bucket = probeInner(legacy, ctx, statements, imports,
                    runtimeFqn, conn);
            BUCKETS.computeIfAbsent(bucket, k -> new AtomicLong())
                    .incrementAndGet();
            WITNESSES.putIfAbsent(bucket, test);
        } catch (Throwable t) {
            String b = "probe-error: " + t.getClass().getSimpleName();
            BUCKETS.computeIfAbsent(b, k -> new AtomicLong()).incrementAndGet();
            WITNESSES.putIfAbsent(b, test + " :: " + t.getMessage());
        }
    }

    private static String probeInner(
            EngineTestExecutor.@com.legend.Nullable Outcome legacy,
            ModelContext ctx, List<ValueSpecification> statements,
            ImportScope imports, String runtimeFqn, Connection conn) {
        if (!(legacy instanceof EngineTestExecutor.Outcome.Ran ran)) {
            return "out-of-scope: legacy " + (legacy == null ? "null"
                    : legacy.getClass().getSimpleName());
        }
        ValueSpecification resolved;
        try {
            resolved = NameResolver.resolveQuery(
                    new LambdaFunction(List.of(), List.copyOf(statements)),
                    imports, ctx.elementFqns());
        } catch (RuntimeException e) {
            return "wall-resolve: " + bucketOf(e.getMessage());
        }
        try {
            if (Compiler.hasStatementEffects(resolved, ctx)) {
                // re-executing writes would corrupt the family session's
                // state for later tests — effectful bodies join the flip
                // only at single-execution cutover, never dual-run
                return "cohort-excluded: effectful";
            }
        } catch (com.legend.error.NotImplementedException e) {
            return "wall-type: " + bucketOf(e.getMessage());
        } catch (RuntimeException e) {
            return "wall-type: " + e.getClass().getSimpleName() + ": "
                    + bucketOf(e.getMessage());
        }
        List<Boolean> events = new ArrayList<>();
        List<String> details = new ArrayList<>();
        com.legend.exec.AssertListener listener = (name, pass, detail) -> {
            events.add(pass);
            details.add(name + (pass ? "" : " :: " + detail));
        };
        String raised = null;
        // census isolation (V7_ASSERT_VERDICT_CHARTER §4.1 idiom): the
        // probe's duplicate executions must not double-feed the
        // sweep-pinned plan/wire censuses
        com.legend.exec.SqlTypeCensus.probeSuspend(true);
        com.legend.exec.CanonicalDivergence.muteAll(true);
        com.legend.exec.SqlTextEmission.probeSuspend(true);
        try {
            // the oracle registers beside the listener (SQLTEXT charter
            // §2): the env carries it for the verdict arms; production
            // envs carry null and a SQL assert walls loudly there
            Compiler.executeResolved(resolved, ctx, runtimeFqn, conn,
                    listener, ReplayOracle.INSTANCE);
        } catch (com.legend.error.NotImplementedException e) {
            return "wall-exec: " + bucketOf(e.getMessage());
        } catch (java.sql.SQLException e) {
            raised = String.valueOf(e.getMessage());
        } catch (RuntimeException e) {
            return "wall-exec: " + e.getClass().getSimpleName() + ": "
                    + bucketOf(e.getMessage());
        } finally {
            com.legend.exec.SqlTypeCensus.probeSuspend(false);
            com.legend.exec.CanonicalDivergence.muteAll(false);
            com.legend.exec.SqlTextEmission.probeSuspend(false);
        }
        boolean legacyPassed = ran.failures().isEmpty();
        boolean platformPassed = raised == null
                && events.stream().allMatch(Boolean::booleanValue);
        if (legacyPassed && platformPassed) {
            long passes = events.size();
            return passes == ran.verified() ? "agree-pass"
                    : "agree-pass-count-skew: platform " + passes
                            + " vs walk verified " + ran.verified();
        }
        if (!legacyPassed && !platformPassed) {
            return "agree-fail";
        }
        return "DISAGREE: legacy " + (legacyPassed ? "pass" : "fail")
                + " platform " + (platformPassed ? "pass"
                        : "fail(" + bucketOf(raised != null ? raised
                                : String.join("; ", details)) + ")");
    }

    private static String bucketOf(@com.legend.Nullable String m) {
        String s = String.valueOf(m);
        for (String line : s.split("\\n")) {
            if (!line.isBlank()) {
                s = line;
                break;
            }
        }
        return (s.length() > 120 ? s.substring(0, 120) : s)
                .replaceAll("'[^']*'", "'_'").replaceAll("\\d+", "N");
    }
}
