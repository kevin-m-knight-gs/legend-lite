// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PARSING HAPPENS IN THE PARSER. Outside {@code com.legend.parser},
 * production code may not reference the parser package — no parse entry
 * calls, no {@code Dialect} levels — except the SANCTIONED consumers
 * below, each of which is a deliberate seam with a stated reason.
 * (Javadoc references are fine; the scan skips comment lines.)
 *
 * <p>Born 2026-08-12 after the collapse audit found a CHECKER parsing
 * strings (GraphFetchChecker's quote/eval fold, since moved to the
 * parser as the QuotedTreeCall carrier) with nothing guarding the
 * boundary. The servers and nlq left the list the same day: product
 * endpoints route through the Compiler (parseModel/parseQuery),
 * which is the provenance router.
 *
 * <p>SHRINK-ONLY: a new entry here is a reviewed architectural decision,
 * not a convenience.
 */
class ParserBoundaryArchTest {

    /** file suffix (path under src/main/java) → why it may parse. */
    private static final Map<String, String> SANCTIONED = Map.ofEntries(
            Map.entry("com/legend/Compiler.java",
                    "THE pipeline driver — the provenance router; the product parse"
                            + " facades (parseModel/parseQuery) live here"),
            Map.entry("com/legend/builtin/Pure.java",
                    "the bootstrap loader — the platform surface's one"
                            + " consumer"),
            Map.entry("com/legend/compiler/spec/SourceSubst.java",
                    "the let-inliner completes the quote/eval fold the"
                            + " moment substitution makes the argument"
                            + " literal — QuotedSpecParser front door, same"
                            + " carrier as SpecParser's parse-time fold"),
            Map.entry("com/legend/harness/HarnessSubstitution.java",
                    "the harness inliner completes the quote/eval fold when"
                            + " let-substitution makes the argument literal"
                            + " — same front door and carrier as SpecParser"),
            Map.entry("com/legend/harness/EngineTestExecutor.java",
                    "quote/eval natives (compileLegendGrammar) — the"
                            + " engine's LegendCompile equivalent"),
            Map.entry("com/legend/ide/ModelIndex.java",
                    "the IDE incremental-parse orchestrator"),
            Map.entry("com/legend/ide/ModelIndexer.java",
                    "the IDE incremental-parse orchestrator"),
            Map.entry("com/legend/ide/ModelOrchestrator.java",
                    "the IDE incremental-parse orchestrator — a product"
                            + " surface parsing token slices"));

    @Test
    void parsingHappensInTheParser() throws IOException {
        List<Path> roots = new ArrayList<>();
        roots.add(Path.of("src/main/java"));
        // sibling product modules compile against core's parser too
        for (String sibling : new String[] {"../nlq/src/main/java",
                "../server/src/main/java", "../pct/src/main/java"}) {
            Path p = Path.of(sibling);
            if (Files.isDirectory(p)) {
                roots.add(p);
            }
        }
        List<String> violations = new ArrayList<>();
        for (Path root : roots) {
            try (var walk = Files.walk(root)) {
                for (Path f : walk.filter(p -> p.toString().endsWith(".java"))
                        .toList()) {
                    String rel = root.relativize(f).toString()
                            .replace('\\', '/');
                    if (rel.startsWith("com/legend/parser/")
                            || SANCTIONED.containsKey(rel)) {
                        continue;
                    }
                    int line = firstParserReference(f);
                    if (line > 0) {
                        violations.add(rel + ":" + line);
                    }
                }
            }
        }
        assertTrue(violations.isEmpty(),
                () -> "parsing (or a Dialect level) outside com.legend.parser"
                        + " in unsanctioned files — parsing happens in the"
                        + " parser; if this seam is deliberate, sanction it"
                        + " with a reason:\n  "
                        + String.join("\n  ", violations));
    }

    /** First non-comment line referencing the parser package, or -1. */
    private static int firstParserReference(Path f) throws IOException {
        List<String> lines = Files.readAllLines(f);
        boolean inBlockComment = false;
        for (int i = 0; i < lines.size(); i++) {
            String t = lines.get(i).trim();
            if (inBlockComment) {
                if (t.contains("*/")) {
                    inBlockComment = false;
                }
                continue;
            }
            if (t.startsWith("//") || t.startsWith("*")) {
                continue;
            }
            if (t.startsWith("/*")) {
                if (!t.contains("*/")) {
                    inBlockComment = true;
                }
                continue;
            }
            if (t.contains("com.legend.parser.")) {
                return i + 1;
            }
        }
        return -1;
    }
}
