package com.legend.harness;

import com.legend.compiler.element.ModelContext;
import com.legend.compiler.spec.SpecCompiler;
import com.legend.protocol.spec.LambdaFunction;
import com.legend.protocol.spec.ValueSpecification;
import com.legend.model.ImportScope;
import com.legend.compiler.NameResolver;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * HARNESS-DELETION item 1 census (measurement only, flag-gated):
 * can this test's WHOLE statement list compile as ONE unit on the
 * platform — a zero-arg lambda body through name resolution + typing?
 * The per-test flip's fallback census starts from these buckets; the
 * statement walk it will replace is {@link EngineTestExecutor#run}.
 *
 * <p>Enable with {@code -Dll.wholetest.census}; a histogram lands in
 * {@code target/wholetest-census.txt} at JVM exit. The probe swallows
 * everything — measurement must never move a verdict.
 */
final class WholeTestCensus {

    private WholeTestCensus() {
    }

    private static final Map<String, AtomicLong> BUCKETS =
            new ConcurrentHashMap<>();
    private static final Map<String, String> WITNESSES =
            new ConcurrentHashMap<>();

    static {
        if (System.getProperty("ll.wholetest.census") != null) {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    StringBuilder sb = new StringBuilder();
                    long total = BUCKETS.values().stream()
                            .mapToLong(AtomicLong::get).sum();
                    sb.append("whole-test single-unit compile census ")
                            .append("(bodies=").append(total).append(")\n");
                    // insertion order (post-process with sort -rn):
                    // the harness sort-site guard reserves ordering for
                    // enumerated comparison policy, never report format
                    BUCKETS.forEach((k, v) -> sb.append(String.format(
                            "%6d  %s%n", v.get(), k))
                            .append("        e.g. ")
                            .append(WITNESSES.getOrDefault(k, "?"))
                            .append('\n'));
                    java.nio.file.Files.writeString(
                            java.nio.file.Path.of("target/wholetest-census.txt"),
                            sb.toString());
                } catch (Throwable ignored) {
                    // census write best-effort at shutdown
                }
            }));
        }
    }

    static void probe(ModelContext ctx, List<ValueSpecification> statements,
            ImportScope imports, String testFqn) {
        if (System.getProperty("ll.wholetest.census") == null) {
            return;
        }
        String bucket;
        try {
            ValueSpecification whole =
                    new LambdaFunction(List.of(), List.copyOf(statements));
            ValueSpecification resolved = NameResolver.resolveQuery(
                    whole, imports, ctx.elementFqns());
            new SpecCompiler(ctx).typeQueryBody(resolved);
            bucket = "typed-ok";
        } catch (Throwable t) {
            String m = String.valueOf(t.getMessage());
            bucket = t.getClass().getSimpleName() + ": "
                    + firstLine(m).replaceAll("'[^']*'", "'_'")
                            .replaceAll("\\d+", "N");
            WITNESSES.putIfAbsent(bucket, testFqn + " :: " + firstLine(m));
        }
        BUCKETS.computeIfAbsent(bucket, k -> new AtomicLong()).incrementAndGet();
        WITNESSES.putIfAbsent(bucket, testFqn);
    }

    private static String firstLine(String s) {
        int i = s.indexOf('\n');
        String l = i < 0 ? s : s.substring(0, i);
        return l.length() > 140 ? l.substring(0, 140) : l;
    }
}
