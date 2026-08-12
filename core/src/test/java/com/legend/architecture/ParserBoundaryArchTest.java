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
 * strings ({@code GraphFetchChecker}'s quote/eval fold) with nothing
 * guarding the boundary — a dialect decision was being made in checker
 * code and no architecture rule existed to catch it.
 *
 * <p>SHRINK-ONLY: a new entry here is a reviewed architectural decision,
 * not a convenience.
 */
class ParserBoundaryArchTest {

    /** file suffix (path under src/main/java) → why it may parse. */
    private static final Map<String, String> SANCTIONED = Map.ofEntries(
            Map.entry("com/legend/Compiler.java",
                    "THE pipeline driver — the provenance router that names"
                            + " levels for whole-model compiles"),
            Map.entry("com/legend/builtin/Pure.java",
                    "the bootstrap loader — the one LEGEND_PLATFORM consumer"),
            Map.entry("com/legend/harness/TestBody.java",
                    "quote/eval natives (compileLegendGrammar) — the"
                            + " engine's LegendCompile equivalent"),
            Map.entry("com/legend/compiler/spec/GraphFetchChecker.java",
                    "DEBT: the compileLegendValueSpecification quote/eval"
                            + " fold parses in a CHECKER; chartered fix ="
                            + " parser-side carrier (the GraphFetchLiteral"
                            + " pattern) — see HONEST_DEBT.md"),
            Map.entry("com/legend/ide/ModelIndex.java",
                    "the IDE incremental-parse orchestrator"),
            Map.entry("com/legend/ide/ModelIndexer.java",
                    "the IDE incremental-parse orchestrator"),
            Map.entry("com/legend/ide/ModelOrchestrator.java",
                    "the IDE incremental-parse orchestrator"),
            Map.entry("com/legend/server/ConnectionResolver.java",
                    "product endpoint — parseLegendLite only"),
            Map.entry("com/legend/server/DiagramService.java",
                    "product endpoint — parseLegendLite only"),
            Map.entry("com/legend/server/PureLspServer.java",
                    "product endpoint — parseLegendLite only"),
            Map.entry("org/finos/legend/engine/nlq/NlqModel.java",
                    "product endpoint — parseLegendLite only"),
            Map.entry("org/finos/legend/engine/nlq/NlqService.java",
                    "product endpoint — SpecParser at LEGEND_LITE"),
            Map.entry("org/finos/legend/engine/nlq/eval/NlqEvalMetrics.java",
                    "product endpoint — SpecParser at LEGEND_LITE"));

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
