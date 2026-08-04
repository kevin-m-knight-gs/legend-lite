// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ERROR-SHAPE GUARDRAILS — the Phase-2b lock on the try/catch/finally
 * review, upgraded per NULL_GATE_VERIFICATION G4: broad catches are
 * pinned at EXACT per-file counts (shrink-only, the
 * ObservabilityGuardrailTest shape), the broad-type pattern matches
 * multi-catch in any position, and the two §6.5 rules that were missing
 * (catch-that-returns-a-value, {@code endsWith} on FQN strings) are
 * pinned. Known residual OUTSIDE these rules' static reach:
 * {@code engine Runner.unknownTypePull} regexes an exception message
 * through a local — retired by the deferred unknown-element
 * rebucketing (typed exception from the resolver), tracked there.
 */
class ErrorShapeGuardrailTest {

    /**
     * Broad-catch census at review time — EXACT counts per file, each a
     * reviewed, documented boundary (module drop-and-wall, or-null
     * probes, harness advisory paths). Shrink-only: a new broad catch
     * anywhere, including in these files, fails until reviewed.
     */
    private static final Map<String, Integer> BROAD_CATCH_COUNTS = Map.ofEntries(
            Map.entry("ClassSources.java", 1),
            Map.entry("Compiler.java", 2),
            Map.entry("FunctionCompiler.java", 1),
            // 3 = the derived-child PROBE-AND-FALLBACK trio (reviewed):
            // navHeadRelation's assoc probe, hopJoin's assoc-vs-slot
            // probe, inlineDerivedCalls' compilable-callee probe — each
            // falls back to the next resolution route, never swallows a
            // verdict
            Map.entry("GraphEmission.java", 3),
            Map.entry("ScanRelations.java", 1),
            Map.entry("StatementExecutor.java", 1),
            Map.entry("StaticFold.java", 1),
            Map.entry("TestBody.java", 3),
            Map.entry("TreeLiterals.java", 1),
            Map.entry("Typer.java", 1),
            Map.entry("ValidateDesugar.java", 2));

    /** Broad type ANYWHERE in the catch parameter — multi-catch included
     * (the first version anchored it first and missed TestBody's
     * {@code catch (SQLException | RuntimeException)}). */
    private static final Pattern BROAD_CATCH = Pattern.compile(
            "catch \\(([^)]*)\\)");

    private static final Pattern BROAD_TYPE = Pattern.compile(
            "\\b(?:RuntimeException|Exception|Throwable)\\b");

    /** Designed catch-return sentinels at review time: harness
     * Unsupported buckets, UnfoldableRef isolation, overflow to
     * BigInteger, join-side search. Shrink-only. */
    private static final int CATCH_RETURNS_VALUE = 20;

    /** {@code endsWith("::…")} identification sites — the suffix-match
     * idiom exact-FQN doctrine retires; may only shrink. */
    private static final int ENDS_WITH_FQN = 23;

    @Test
    void noReturnOrThrowInsideFinally() throws IOException {
        List<String> bad = new ArrayList<>();
        for (Path p : mainSources()) {
            String src = Files.readString(p);
            Matcher m = Pattern.compile("\\} finally \\{").matcher(src);
            while (m.find()) {
                String body = blockAfter(src, m.end());
                if (Pattern.compile("\\breturn\\b|\\bthrow\\b")
                        .matcher(body).find()) {
                    bad.add(p.getFileName() + ":" + lineOf(src, m.start()));
                }
            }
        }
        assertTrue(bad.isEmpty(),
                "finally block contains return/throw (swallows in-flight"
                + " exceptions) — restructure: " + bad);
    }

    @Test
    void broadCatchCountsPinnedPerFile() throws IOException {
        Map<String, Integer> found = new HashMap<>();
        Map<String, List<Integer>> where = new HashMap<>();
        for (Path p : mainSources()) {
            String src = Files.readString(p);
            Matcher m = BROAD_CATCH.matcher(src);
            while (m.find()) {
                if (BROAD_TYPE.matcher(m.group(1)).find()) {
                    String f = p.getFileName().toString();
                    found.merge(f, 1, Integer::sum);
                    where.computeIfAbsent(f, k -> new ArrayList<>())
                            .add(lineOf(src, m.start()));
                }
            }
        }
        List<String> bad = new ArrayList<>();
        found.forEach((f, n) -> {
            int allowed = BROAD_CATCH_COUNTS.getOrDefault(f, 0);
            if (n > allowed) {
                bad.add(f + " has " + n + " broad catches (pinned at "
                        + allowed + ") at lines " + where.get(f));
            }
        });
        assertTrue(bad.isEmpty(),
                "broad catches grew — catch the specific exception, or"
                + " review + document the site and re-pin: " + bad);
    }

    /** A catch that RETURNS a value silently converts a failure into an
     * answer — the population was reviewed (all designed sentinels) and
     * is pinned; growth means a new site needs the same review. */
    @Test
    void catchThatReturnsValueOnlyShrinks() throws IOException {
        int n = 0;
        List<String> where = new ArrayList<>();
        Set<String> sentinels = Set.of("null", "Optional.empty()",
                "false", "true");
        for (Path p : mainSources()) {
            String src = Files.readString(p);
            Matcher m = Pattern.compile("catch \\([^)]+\\) \\{").matcher(src);
            while (m.find()) {
                String body = blockAfter(src, m.end());
                Matcher r = Pattern.compile("\\breturn ([^;]{1,80});")
                        .matcher(body);
                while (r.find()) {
                    if (!sentinels.contains(r.group(1).strip())) {
                        n++;
                        where.add(p.getFileName() + ":"
                                + lineOf(src, m.start()));
                        break;
                    }
                }
            }
        }
        assertTrue(n <= CATCH_RETURNS_VALUE,
                "catch-that-returns-a-value grew to " + n + " (pinned at "
                + CATCH_RETURNS_VALUE + ") — a caught failure must become"
                + " a designed sentinel or rethrow: " + where);
    }

    /** Identify elements by EXACT FQN, never suffix match. */
    @Test
    void fqnSuffixMatchingOnlyShrinks() throws IOException {
        int n = 0;
        for (Path p : mainSources()) {
            Matcher m = Pattern.compile("\\.endsWith\\(\"::")
                    .matcher(Files.readString(p));
            while (m.find()) {
                n++;
            }
        }
        assertTrue(n <= ENDS_WITH_FQN,
                "endsWith-on-FQN sites grew to " + n + " (pinned at "
                + ENDS_WITH_FQN + ") — identify by exact FQN");
    }

    /** Dispatching on exception MESSAGE TEXT couples control flow to
     * wording. Zero in core AND engine sources; stays zero. */
    @Test
    void noControlFlowOnExceptionMessageText() throws IOException {
        List<String> bad = new ArrayList<>();
        Pattern p1 = Pattern.compile(
                "getMessage\\(\\)\\s*\\.\\s*(contains|matches|startsWith|endsWith|equals)\\(");
        for (Path p : allSources()) {
            String src = Files.readString(p);
            Matcher m = p1.matcher(src);
            while (m.find()) {
                bad.add(p.getFileName() + ":" + lineOf(src, m.start()));
            }
        }
        assertTrue(bad.isEmpty(),
                "control flow inspects exception message text — use a typed"
                + " exception or a field instead: " + bad);
    }

    /** A catch body with NO statements and NO comment is an undocumented
     * swallow ({@code {}} and {@code { ; }} alike). */
    @Test
    void noUndocumentedEmptyCatch() throws IOException {
        List<String> bad = new ArrayList<>();
        Pattern empty = Pattern.compile(
                "catch \\([^)]+\\)\\s*\\{[\\s;]*\\}");
        for (Path p : mainSources()) {
            String src = Files.readString(p);
            Matcher m = empty.matcher(src);
            while (m.find()) {
                bad.add(p.getFileName() + ":" + lineOf(src, m.start()));
            }
        }
        assertTrue(bad.isEmpty(),
                "empty catch without a policy comment — document why the"
                + " swallow is safe: " + bad);
    }

    private static String blockAfter(String src, int open) {
        int i = open;
        int depth = 1;
        StringBuilder body = new StringBuilder();
        while (i < src.length() && depth > 0) {
            char c = src.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
            }
            body.append(c);
            i++;
        }
        return body.toString();
    }

    private static List<Path> mainSources() throws IOException {
        Path root = Path.of("src/main/java");
        try (Stream<Path> s = Files.walk(root)) {
            return s.filter(p -> p.toString().endsWith(".java")).toList();
        }
    }

    /** Core main + engine main (engine tests hold the ONE documented
     * residual, named in the class javadoc). */
    private static List<Path> allSources() throws IOException {
        List<Path> out = new ArrayList<>(mainSources());
        Path engine = Path.of("../engine/src/main/java");
        if (Files.isDirectory(engine)) {
            try (Stream<Path> s = Files.walk(engine)) {
                out.addAll(s.filter(p -> p.toString().endsWith(".java"))
                        .toList());
            }
        }
        return out;
    }

    private static int lineOf(String src, int offset) {
        return (int) src.chars().limit(offset).filter(c -> c == '\n').count() + 1;
    }
}
