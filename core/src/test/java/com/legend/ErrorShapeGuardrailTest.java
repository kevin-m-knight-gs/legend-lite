// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ERROR-SHAPE GUARDRAILS — the Phase-2b lock on the try/catch/finally
 * review (2026-07-30, all 134 try sites individually classified). The
 * population was CLEAN at review time: every broad catch is a documented
 * wall or probe, every catch-that-returns-a-value returns a designed
 * sentinel (harness Unsupported buckets, UnfoldableRef isolation,
 * overflow&rarr;BigInteger), no finally swallows an in-flight exception,
 * and nothing dispatches on exception message text. THESE TESTS keep it
 * that way — each rule failing means a new site needs the same review
 * the originals got, not that the rule is wrong.
 */
class ErrorShapeGuardrailTest {

    /**
     * Files allowed to catch {@code RuntimeException}/{@code Exception}/
     * {@code Throwable}. Each entry is a reviewed, documented boundary:
     * module drop-and-wall (Compiler, FunctionCompiler, Typer's tolerant
     * imports), or-null probes (TreeLiterals, StaticFold, ScanRelations,
     * GraphEmission, ClassSources, ValidateDesugar sibling scans), the
     * seed probe in StatementExecutor, and the harness's advisory paths.
     * SHRINK only — a new entry needs a documented policy comment at the
     * catch site.
     */
    private static final Set<String> BROAD_CATCH_ALLOWLIST = Set.of(
            "StatementExecutor.java",
            "Compiler.java",
            "TreeLiterals.java",
            "TestBody.java",
            "ScanRelations.java",
            "FunctionCompiler.java",
            "Typer.java",
            "StaticFold.java",
            "GraphEmission.java",
            "ClassSources.java",
            "ValidateDesugar.java");

    private static final Pattern BROAD_CATCH = Pattern.compile(
            "catch \\((?:java\\.lang\\.)?(?:RuntimeException|Exception|Throwable)\\b");

    /** A {@code return}/{@code throw} inside {@code finally} silently
     * discards any in-flight exception — the one genuinely dangerous
     * finally shape. Zero at review time; stays zero. */
    @Test
    void noReturnOrThrowInsideFinally() throws IOException {
        List<String> bad = new ArrayList<>();
        for (Path p : mainSources()) {
            String src = Files.readString(p);
            Matcher m = Pattern.compile("\\} finally \\{").matcher(src);
            while (m.find()) {
                int i = m.end();
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

    /** Broad catches concentrate at named, reviewed boundaries. */
    @Test
    void broadCatchesOnlyAtReviewedBoundaries() throws IOException {
        List<String> bad = new ArrayList<>();
        for (Path p : mainSources()) {
            String src = Files.readString(p);
            Matcher m = BROAD_CATCH.matcher(src);
            while (m.find()) {
                if (!BROAD_CATCH_ALLOWLIST.contains(
                        p.getFileName().toString())) {
                    bad.add(p.getFileName() + ":" + lineOf(src, m.start()));
                }
            }
        }
        assertTrue(bad.isEmpty(),
                "broad catch outside the reviewed boundary allowlist —"
                + " catch the specific exception, or review + document the"
                + " site and extend the allowlist: " + bad);
    }

    /** Dispatching on exception MESSAGE TEXT couples control flow to
     * wording. Zero at review time; stays zero. */
    @Test
    void noControlFlowOnExceptionMessageText() throws IOException {
        List<String> bad = new ArrayList<>();
        Pattern p1 = Pattern.compile(
                "getMessage\\(\\)\\s*\\.\\s*(contains|matches|startsWith|endsWith|equals)\\(");
        for (Path p : mainSources()) {
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
     * swallow. Every deliberate swallow in the tree carries a policy
     * comment; keep that discipline. */
    @Test
    void noUndocumentedEmptyCatch() throws IOException {
        List<String> bad = new ArrayList<>();
        Pattern empty = Pattern.compile(
                "catch \\([^)]+\\)\\s*\\{\\s*\\}");
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

    private static List<Path> mainSources() throws IOException {
        Path root = Path.of("src/main/java");
        try (Stream<Path> s = Files.walk(root)) {
            return s.filter(p -> p.toString().endsWith(".java")).toList();
        }
    }

    private static int lineOf(String src, int offset) {
        return (int) src.chars().limit(offset).filter(c -> c == '\n').count() + 1;
    }
}
