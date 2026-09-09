package com.legend.rcorpus;

import com.legend.Compiler;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** THE EAGER COMPILE — a MEASUREMENT, not a gate (USER 2026-09-09: "before
 * we add to gate let's do all the work, then decide"): every body in the
 * corpus's compiled world typed up front through Compiler.compileAllBodies.
 * Run by name only ({@code -Dtest=EagerCorpusCompileProbe}; the Probe suffix
 * keeps it out of surefire's default set). Writes target/eager-corpus.txt
 * (by reason, by package, by source file, every failing body).
 * {@code -Deager.world2=1} adds the second world (the corpus + legend-pure's
 * platform packages whole). Measured 2026-09-09: 9,099 bodies, 1,605 fail
 * (COMPILE_EVERYTHING_HOMEWORK §10). */
class EagerCorpusCompileProbe {

    @Test
    void eagerCompileEverything() throws Exception {
        long t0 = System.nanoTime();
        MinimalCorpus corpus = new MinimalCorpus();
        long t1 = System.nanoTime();
        Map<String, String> walls = Compiler.compileAllBodies(corpus.context());
        long t2 = System.nanoTime();
        int total = 0;
        for (String fqn : corpus.context().functionFqns()) {
            try {
                for (var f : corpus.context().findFunction(fqn)) {
                    if (f.body().isPresent()) {
                        total++;
                    }
                }
            } catch (RuntimeException e) {
                total++;
            }
        }
        Map<String, Integer> byReason = new TreeMap<>();
        Map<String, Integer> byPackage = new TreeMap<>();
        for (var e : walls.entrySet()) {
            byReason.merge(reasonClass(e.getValue()), 1, Integer::sum);
            String k = e.getKey();
            String pkg = k.contains("::") ? k.substring(0, k.indexOf("::", k.indexOf("::") + 2)) : k;
            byPackage.merge(pkg, 1, Integer::sum);
        }
        Map<String, Integer> bySource = new TreeMap<>();
        Map<String, Integer> bodiesBySource = new TreeMap<>();
        for (String fqn : corpus.context().functionFqns()) {
            String src = corpus.elementSources().getOrDefault(fqn, "?");
            bodiesBySource.merge(src, 1, Integer::sum);
        }
        for (String k : walls.keySet()) {
            String fqn = k.contains("(") ? k.substring(0, k.indexOf('(')) : k;
            bySource.merge(corpus.elementSources().getOrDefault(fqn, "?"), 1, Integer::sum);
        }
        List<String> out = new ArrayList<>();
        out.add("# by source (failed/total): " + bySource.entrySet().stream()
                .sorted((x, y) -> y.getValue() - x.getValue())
                .map(e -> e.getKey() + "=" + e.getValue() + "/" + bodiesBySource.getOrDefault(e.getKey(), 0))
                .toList());
        out.add("# eager corpus compile — bodies=" + total + " failed=" + walls.size()
                + " build=" + (t1 - t0) / 1_000_000 + "ms typeAll=" + (t2 - t1) / 1_000_000 + "ms");
        out.add("# by reason: " + byReason);
        out.add("# by top package: " + byPackage);
        out.add("");
        walls.forEach((k, v) -> out.add(k + " :: " + v.replace('\n', ' ')));
        Files.createDirectories(Path.of("target"));
        if (!"1".equals(System.getProperty("eager.world2"))) {
            Files.write(Path.of("target/eager-corpus.txt"), out);
            System.out.println(out.get(0));
            System.out.println(out.get(1));
            return;
        }
        // WORLD 2: the corpus + legend-pure's platform packages WHOLE (their
        // bodied FUNCTIONS, which the prelude does not carry) — what closes?
        Path pure = Path.of(System.getProperty("legend.pure.root", "/Users/neemsandv/legend/legend-pure"));
        List<Compiler.ModelSource> w2 = new ArrayList<>(corpus.sources());
        for (String r : com.legend.tools.SpecBodyCensusTest.PLATFORM_ROOTS) {
            Path root = pure.resolve(r);
            if (!Files.isDirectory(root)) continue;
            try (var walk = Files.walk(root)) {
                for (Path f : walk.filter(x -> x.toString().endsWith(".pure")).sorted().toList()) {
                    w2.add(new Compiler.ModelSource("platform:" + root.relativize(f), Files.readString(f)));
                }
            }
        }
        com.legend.compiler.element.ModelContext ctx2 = null;
        List<String> w2walls = new ArrayList<>();
        for (int round = 0; round < 400 && ctx2 == null; round++) {
            List<String> pw = new ArrayList<>();
            Compiler.ParsedModule m2 = Compiler.parseSources(w2, (n, e) -> pw.add(n + ": " + e), com.legend.parser.Dialect.LEGEND_PLATFORM);
            var kept = m2.model().elements().stream().filter(e -> !(e instanceof com.legend.model.NativeFunctionDefinition)).toList();
            var pruned = new com.legend.model.ParsedModel(kept, m2.model().imports(), m2.model().source(), m2.model().elementOffsets(), m2.model().elementImports(), m2.model().elementSources(), m2.model().unclaimedSections());
            try {
                // the corpus's own tolerant build (poison, don't drop)
                Compiler.BuiltModule b2 = Compiler.buildModule(pruned);
                ctx2 = b2.context(); w2walls.addAll(pw);
                w2walls.add("element walls: " + b2.walls().size());
            } catch (com.legend.error.ModelException e) {
                String el = e.element(); String src = el == null ? null : m2.model().elementSources().get(el);
                if (src == null) throw e;
                final String drop = src; w2.removeIf(s -> s.name().equals(drop)); w2walls.add(drop + ": MODEL " + e.getMessage());
            }
        }
        Map<String, String> walls2 = Compiler.compileAllBodies(ctx2);
        Map<String, Integer> byReason2 = new TreeMap<>();
        for (var e : walls2.entrySet()) byReason2.merge(reasonClass(e.getValue()), 1, Integer::sum);
        // failures that closed: keys in world 1 absent in world 2
        long closed = walls.keySet().stream().filter(k -> !walls2.containsKey(k)).count();
        out.add("# WORLD 2 (corpus + legend-pure platform functions): failed=" + walls2.size() + " closed-from-world-1=" + closed + " worldWalls=" + w2walls.size());
        out.add("# WORLD 2 by reason: " + byReason2);
        Map<String, Integer> names2 = new TreeMap<>();
        for (var v : walls2.values()) { var mm = java.util.regex.Pattern.compile("unknown function '([^']+)'").matcher(v); if (mm.find()) { String n = mm.group(1); names2.merge(n.substring(n.lastIndexOf(':') + 1), 1, Integer::sum); } }
        out.add("# WORLD 2 top unknown functions: " + names2.entrySet().stream().sorted((x, y) -> y.getValue() - x.getValue()).limit(25).toList());
        // NEW in world 2: bodies that fail there and did not exist / did not fail in world 1
        Map<String, Integer> newReason = new TreeMap<>(); Map<String, Integer> newTypes = new TreeMap<>(); Map<String, Integer> newMsgs = new TreeMap<>();
        Map<String, Integer> newSrc = new TreeMap<>();
        for (var e : walls2.entrySet()) {
            if (walls.containsKey(e.getKey())) continue;
            String v = e.getValue(); newReason.merge(reasonClass(v), 1, Integer::sum);
            var mt = java.util.regex.Pattern.compile("(?:Unknown type: '|')([^']+)' is not a known|Unknown type: '([^']+)'").matcher(v);
            if (mt.find()) { String n = mt.group(1) != null ? mt.group(1) : mt.group(2); newTypes.merge(n.substring(n.lastIndexOf(':') + 1), 1, Integer::sum); }
            String msg = v.replaceAll("'[^']*'", "'_'"); newMsgs.merge(msg.length() > 110 ? msg.substring(0, 110) : msg, 1, Integer::sum);
            String k = e.getKey(); String fqn = k.contains("(") ? k.substring(0, k.indexOf('(')) : k;
            String pkg = fqn.contains("::") ? fqn.substring(0, Math.min(fqn.length(), fqn.indexOf("::", fqn.indexOf("::", fqn.indexOf("::") + 2) + 2) > 0 ? fqn.indexOf("::", fqn.indexOf("::", fqn.indexOf("::") + 2) + 2) : fqn.length())) : fqn;
            newSrc.merge(pkg, 1, Integer::sum);
        }
        out.add("# WORLD 2 NEW failures by reason: " + newReason);
        out.add("# WORLD 2 NEW by package: " + newSrc.entrySet().stream().sorted((x, y) -> y.getValue() - x.getValue()).limit(14).toList());
        out.add("# WORLD 2 NEW top unknown types: " + newTypes.entrySet().stream().sorted((x, y) -> y.getValue() - x.getValue()).limit(20).toList());
        out.add("# WORLD 2 NEW top messages: " + newMsgs.entrySet().stream().sorted((x, y) -> y.getValue() - x.getValue()).limit(12).toList());
        out.add("# WORLD 2 walls: " + w2walls);
        Files.write(Path.of("target/eager-corpus.txt"), out);
        for (String l : out) if (l.startsWith("# WORLD 2")) System.out.println(l);
    }


    static String reasonClass(String msg) {
        if (msg.contains("unknown function")) return "unknown-function";
        if (msg.contains("has no property")) return "unknown-property";
        if (msg.contains("not a known primitive, class, or enum") || msg.contains("Unknown type")) return "unknown-type";
        if (msg.contains("no overload") || msg.contains("overload")) return "overload";
        if (msg.contains("engine machinery")) return "walled";
        if (msg.contains("not supported yet") || msg.contains("not resolvable")) return "not-implemented";
        return "kernel/other";
    }
}
