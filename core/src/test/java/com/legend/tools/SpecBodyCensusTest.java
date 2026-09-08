// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.tools;

import com.legend.Compiler;
import com.legend.compiler.element.ModelContext;
import com.legend.compiler.element.TypedFunction;
import com.legend.compiler.spec.SpecCompiler;
import com.legend.model.PackageableElement;
import com.legend.model.ParsedModel;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * THE TYPING CENSUS (docs/SYSTEM_PRELUDE_DESIGN_2026_09_08.md §6): load
 * legend-pure's PLATFORM packages as a model — every class, every derived
 * property (lifted to {@code <owner>$prop$<name>}), every Pure-bodied
 * function; the spec's {@code native function} declarations drop (the
 * registry is their definition, channel B's doctrine) — and TYPE every body
 * exactly once. Compiling never executes (§5): a reflective call types
 * against its registered signature. Each failure is one row with its
 * reason; the rows are the typing WORK LIST, which should trend to zero
 * (missing vocabulary, typer gaps). Written to
 * {@code target/spec-body-census.txt}; a summary by reason prints.
 *
 * <p>Census only for now (report, no pin): the numbers decide the batch-148
 * order. It needs the pure checkout ({@code -Dlegend.pure.root}) and skips
 * without it.
 */
class SpecBodyCensusTest {

    static final List<String> PLATFORM_ROOTS = List.of(
            "legend-pure-core/legend-pure-m3-core/src/main/resources/platform",
            "legend-pure-core/legend-pure-m3-precisePrimitives/src/main/resources/platform_precise_primitives",
            "legend-pure-dsl/legend-pure-dsl-diagram/legend-pure-m2-dsl-diagram-pure/src/main/resources/platform_dsl_diagram",
            "legend-pure-dsl/legend-pure-dsl-graph/legend-pure-m2-dsl-graph-pure/src/main/resources/platform_dsl_graph",
            "legend-pure-dsl/legend-pure-dsl-mapping/legend-pure-m2-dsl-mapping-pure/src/main/resources/platform_dsl_mapping",
            "legend-pure-dsl/legend-pure-dsl-path/legend-pure-m2-dsl-path-pure/src/main/resources/platform_dsl_path",
            "legend-pure-dsl/legend-pure-dsl-store/legend-pure-m2-dsl-store-pure/src/main/resources/platform_dsl_store",
            "legend-pure-dsl/legend-pure-dsl-tds/legend-pure-m2-dsl-tds-pure/src/main/resources/platform_dsl_tds",
            "legend-pure-store/legend-pure-store-relational/legend-pure-m2-store-relational-pure/src/main/resources/platform_store_relational");

    @Test
    @DisplayName("typing census: every Pure body in legend-pure's platform packages typed once, failures as rows")
    void census() throws IOException {
        Path pure = Path.of(System.getProperty("legend.pure.root",
                System.getProperty("user.home") + "/legend/legend-pure"));
        Assumptions.assumeTrue(Files.isDirectory(pure.resolve(PLATFORM_ROOTS.get(0))),
                "legend-pure checkout not present");

        // 1. LOAD — every platform .pure file as a source; files that do not
        // parse and elements the model integrity refuses are recorded, never
        // silently dropped (channel B's wall-collection loop, bounded)
        List<Compiler.ModelSource> sources = new ArrayList<>();
        for (String r : PLATFORM_ROOTS) {
            Path root = pure.resolve(r);
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(root)) {
                for (Path f : walk.filter(p -> p.toString().endsWith(".pure")).sorted().toList()) {
                    sources.add(new Compiler.ModelSource(
                            r.substring(r.lastIndexOf('/') + 1) + ":" + root.relativize(f),
                            Files.readString(f, StandardCharsets.UTF_8)));
                }
            }
        }
        List<String> loadWalls = new ArrayList<>();
        ModelContext ctx = null;
        int fileCount = sources.size();
        for (int round = 0; round < 400 && ctx == null; round++) {
            List<String> parseWalls = new ArrayList<>();
            Compiler.ParsedModule module = Compiler.parseSources(sources,
                    (name, err) -> parseWalls.add(name + ": PARSE " + first(err)),
                    com.legend.parser.Dialect.LEGEND_PLATFORM);
            // the spec's native declarations are the SPEC of natives the
            // registry defines — the registry is the definition; they drop
            List<PackageableElement> kept = module.model().elements().stream()
                    .filter(e -> !(e instanceof com.legend.model.NativeFunctionDefinition))
                    .toList();
            ParsedModel pruned = new ParsedModel(kept, module.model().imports(),
                    module.model().source(), module.model().elementOffsets(),
                    module.model().elementImports(), module.model().elementSources(),
                    module.model().unclaimedSections());
            try {
                ctx = Compiler.buildModel(pruned);
                loadWalls.addAll(parseWalls);
            } catch (com.legend.error.ModelException e) {
                String el = e.element();
                String src = el == null ? null : module.model().elementSources().get(el);
                if (src == null) {
                    throw e;
                }
                final String drop = src;
                sources.removeIf(s -> s.name().equals(drop));
                loadWalls.add(drop + ": MODEL " + first(e.getMessage()));
            }
        }
        if (ctx == null) {
            throw new IllegalStateException("model did not converge");
        }

        // 2. TYPE — every function the model holds (the spec's Pure-bodied
        // functions plus every derived property lifted to <owner>$prop$<name>),
        // each body typed once through the ordinary compile entry
        SpecCompiler specs = new SpecCompiler(ctx);
        List<String> ok = new ArrayList<>();
        Map<String, String> failures = new TreeMap<>();
        Map<String, Integer> byReason = new TreeMap<>();
        int natives = 0;
        for (String fqn : new java.util.TreeSet<>(ctx.functionFqns())) {
            List<TypedFunction> overloads;
            try {
                overloads = ctx.findFunction(fqn);
            } catch (RuntimeException e) {
                failures.put(fqn, "SIGNATURE " + first(e.getMessage()));
                bump(byReason, "signature");
                continue;
            }
            for (TypedFunction fn : overloads) {
                if (fn.isNative() || fn.body().isEmpty()) {
                    natives++;
                    continue;
                }
                String id = fn.qualifiedName() + "(" + fn.parameters().stream()
                        .map(p -> p.type().typeName() + p.multiplicity().text())
                        .collect(java.util.stream.Collectors.joining(",")) + ")";
                try {
                    specs.compile(fn);
                    ok.add(id);
                } catch (RuntimeException e) {
                    String msg = first(e.getMessage());
                    failures.put(id, e.getClass().getSimpleName() + " " + msg + at(e));
                    bump(byReason, reasonClass(msg));
                }
            }
        }

        // 3. REPORT
        List<String> out = new ArrayList<>();
        out.add("# spec body typing census — " + java.time.LocalDate.now());
        out.add("# files=" + fileCount + " loadWalls=" + loadWalls.size()
                + " functions typed OK=" + ok.size() + " FAILED=" + failures.size()
                + " (natives skipped=" + natives + ")");
        out.add("# by reason: " + byReason);
        out.add("");
        out.add("## load walls");
        out.addAll(loadWalls);
        out.add("");
        out.add("## typing failures");
        failures.forEach((k, v) -> out.add(k + " :: " + v));
        Files.createDirectories(Path.of("target"));
        Files.write(Path.of("target/spec-body-census.txt"), out);
        System.out.println("[spec-census] files=" + fileCount + " loadWalls=" + loadWalls.size()
                + " typedOK=" + ok.size() + " failed=" + failures.size()
                + " nativesSkipped=" + natives);
        System.out.println("[spec-census] byReason=" + byReason);
    }

    /** A coarse reason class for the summary — the rows carry the full text. */
    static String reasonClass(String msg) {
        if (msg.contains("unknown function")) {
            return "unknown-function";
        }
        if (msg.contains("has no property")) {
            return "unknown-property";
        }
        if (msg.contains("Unknown type") || msg.contains("is not a known")) {
            return "unknown-type";
        }
        if (msg.contains("no overload")) {
            return "overload";
        }
        if (msg.contains("type variable") || msg.contains("cannot also bind")
                || msg.contains("no common supertype")) {
            return "kernel";
        }
        if (msg.contains("NormalizeRequired") || msg.contains("cannot inline")) {
            return "normalize";
        }
        return "other";
    }

    /** The first platform frame of a failure — WHERE the typer gave up
     * (a report column; the message alone does not locate a typer bug). */
    private static String at(RuntimeException e) {
        for (StackTraceElement f : e.getStackTrace()) {
            if (f.getClassName().startsWith("com.legend.")) {
                return " @ " + f.getClassName().substring(f.getClassName().lastIndexOf('.') + 1)
                        + "." + f.getMethodName() + ":" + f.getLineNumber();
            }
        }
        return "";
    }

    private static void bump(Map<String, Integer> m, String k) {
        m.merge(k, 1, Integer::sum);
    }

    private static String first(String s) {
        if (s == null) {
            return "";
        }
        int nl = s.indexOf('\n');
        String one = nl < 0 ? s : s.substring(0, nl);
        return one.length() > 600 ? one.substring(0, 600) : one;
    }
}
