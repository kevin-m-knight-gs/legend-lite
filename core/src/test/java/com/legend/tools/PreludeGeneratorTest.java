// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.tools;

import com.legend.Compiler;
import com.legend.compiler.NameResolver;
import com.legend.lexer.Lexer;
import com.legend.lexer.TokenStream;
import com.legend.model.ClassDefinition;
import com.legend.model.EnumDefinition;
import com.legend.model.ImportScope;
import com.legend.model.PackageableElement;
import com.legend.model.ParsedModel;
import com.legend.parser.Dialect;
import com.legend.parser.ElementParser;
import com.legend.protocol.DerivedPropertyDefinition;
import com.legend.protocol.ParameterDefinition;
import com.legend.protocol.TypeExpression;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * THE PRELUDE GENERATOR — the prelude is a MODULE
 * (docs/SYSTEM_PRELUDE_DESIGN_2026_09_08.md §10, docs/PRELUDE_MODULE_HOMEWORK_2026_09_08.md):
 * the library shapes a program may name are DATA, generated from the spec —
 * never hand-typed. This tool reads the engine and legend-pure checkouts
 * (spec), finds every class/enum the corpus and the platform's Java name
 * (plus the closure of what those declarations name — the module is a
 * CLOSED library the boot layer checks, T5), parses their files with OUR
 * parser, and writes each declaration VERBATIM — constraints, stereotypes,
 * tagged values, derived properties, defaults, exactly as the spec spells
 * it — under its spec file's imports, into
 * {@code core/src/main/resources/com/legend/builtin/prelude.pure}. The
 * compiler resolves and normalizes that module ONCE per process as the
 * boot layer ({@code Compiler.bootLayer}), so a derived property lifts
 * like a user class's and nothing is re-printed.
 *
 * <p>Shapes {@code Pure.java} still declares by hand and the system
 * metamodel's own elements are skipped (phase 2 migrates the hand shapes).
 * A graph class the corpus tree ALSO declares is listed at the foot of the
 * module — the T4 receipt list phase 3 burns.
 *
 * <p>Modes: {@code -Dprelude.generate=1} WRITES the file; {@code
 * -Dprelude.census=1} also writes one row per declaration to
 * {@code target/prelude-census.tsv} (HOMEWORK §4); otherwise the test
 * regenerates in memory and asserts the committed file is current (the
 * parity guard — the spec moved, or someone edited by hand).
 */
class PreludeGeneratorTest {

    private static final Path OUT = Path.of(
            "src/main/resources/com/legend/builtin/prelude.pure");

    /** Packages whose shapes are not (yet) generated — each line a decision. */
    private static final List<String> EXCLUDED_PACKAGE_PREFIXES = List.of(
            // VERSIONED protocol payload classes (nine copies of the same
            // shapes, meta::protocols::pure::v1_2x_0::…). The one TEMPLATE copy
            // the engine's own programs name — meta::protocols::pure::vX_X_X::
            // metamodel::m3 (the relational extension's tdsToRelation adapter
            // types its transfers over the template AppliedFunction) — is
            // admitted; see excludedByDecision() (Phase 5 batch 147, strict first)
            "meta::protocols::",
            // m3 path classes: `Path<-U,V|m> extends Function<{U[1]->V[m]}>`
            // generalizes with a NON-identity argument, which the kernel's
            // positional-pairing rule refuses (NativeFunctionTest.
            // parameterizedGeneralizationsAreIdentityArgument) — a kernel
            // gap to lift before these shapes can be data
            "meta::pure::metamodel::path::");
    /** Individual declarations left out, each with its reason. */
    private static final Map<String, String> EXCLUDED_CLASSES = Map.of();
    /** The protocol TEMPLATE package admitted out of the versioned exclusion. */
    private static final String PROTOCOL_TEMPLATE_M3 = "meta::protocols::pure::vX_X_X::metamodel::m3::";


    @Test
    @DisplayName("prelude.pure is the generator's current output (regenerate with -Dprelude.generate=1)")
    void preludeIsCurrent() throws Exception {
        String generated = generate();
        if ("1".equals(System.getProperty("prelude.generate"))) {
            Files.createDirectories(OUT.getParent());
            Files.writeString(OUT, generated, StandardCharsets.UTF_8);
            System.out.println("[prelude] wrote " + OUT + " (" + generated.lines().count() + " lines)");
            return;
        }
        assertTrue(Files.exists(OUT), "prelude.pure missing — run with -Dprelude.generate=1");
        assertEquals(generated, Files.readString(OUT, StandardCharsets.UTF_8),
                "prelude.pure is stale: the spec moved or the file was edited by hand —"
                        + " regenerate with -Dprelude.generate=1");
    }

    // ------------------------------------------------------------------
    // the generator
    // ------------------------------------------------------------------

    static String generate() throws IOException {
        Path engine = Path.of(System.getProperty("legend.engine.root",
                "/Users/neemsandv/legend/legend-engine"));
        Path pure = Path.of(System.getProperty("legend.pure.root",
                "/Users/neemsandv/legend/legend-pure"));
        List<Path> roots = List.of(
                engine.resolve("legend-engine-xts-relationalStore"),
                engine.resolve("legend-engine-core/legend-engine-core-pure"),
                // the service metamodel (core_service): ^Service(...) in the
                // execution-strategy tests (batch 57)
                engine.resolve("legend-engine-xts-service/legend-engine-language-pure-dsl-service-pure/"
                        + "src/main/resources/core_service"),
                pure);
        Path corpus = engine.resolve("legend-engine-xts-relationalStore/legend-engine-xt-relationalStore-generation/"
                + "legend-engine-xt-relationalStore-pure/legend-engine-xt-relationalStore-core-pure/"
                + "src/main/resources/core_relational/relational");

        // 1. the spec index: every Class/Enum FQN -> its defining file
        Map<String, Path> index = new TreeMap<>();
        for (Path root : roots) {
            try (Stream<Path> s = Files.walk(root)) {
                for (Path f : s.filter(p -> p.toString().endsWith(".pure")).sorted().toList()) {
                    Matcher m = DECL_HEADER.matcher(Files.readString(f, StandardCharsets.UTF_8));
                    while (m.find()) {
                        index.putIfAbsent(m.group(2), f);
                    }
                }
            }
        }

        // 1b. PROVENANCE of every hand declaration: a class Pure.java still
        // declares by hand must be a spec shape (indexed — a Java-referenced
        // definition or a carrier awaiting migration), an m3 BOOTSTRAP shape
        // (legend-pure's m3.pure graph, unreadable by this generator), or an
        // allowlisted platform carrier with its reason — a GUESSED shape
        // (the ::metamodel::DateLiteral of 2026-09-04) is a generator error
        Set<String> m3 = new LinkedHashSet<>();
        Matcher m3h = M3_HEADER.matcher(Files.readString(pure.resolve(
                "legend-pure-core/legend-pure-m3-core/src/main/resources/platform/pure/grammar/m3.pure"),
                StandardCharsets.UTF_8));
        while (m3h.find()) {
            String at = m3h.group(3);
            // m3.pure's UN-ANNOTATED bootstrap declarations (no @package):
            // the root package's Package class and the primitive types
            String pkg = at == null
                    ? ("Class".equals(m3h.group(1)) ? "meta::pure::metamodel" : "meta::pure::metamodel::type")
                    : String.join("::", at.replace("Root.children[", "").replace("].children[", "::")
                            .replace("].children", "").replace("]", "").split("::"));
            m3.add(pkg + "::" + m3h.group(2));
        }
        List<String> unprovenanced = new ArrayList<>();
        for (String fqn : handDeclaredFqns()) {
            if (!index.containsKey(fqn) && !m3.contains(fqn) && !HAND_CARRIERS.containsKey(fqn)) {
                unprovenanced.add(fqn);
            }
        }
        if (!unprovenanced.isEmpty()) {
            throw new IllegalStateException("prelude generator: hand-declared classes with no"
                    + " provenance (not in the spec index, not in m3.pure, not an allowlisted"
                    + " carrier): " + unprovenanced);
        }

        // 2. what the GRAPH declares itself (the corpus tree, the program
        // libraries, the shape files) — for the T4 receipts only: a vocabulary
        // class the graph also declares is listed at the foot of the module
        Set<String> corpusDefined = new LinkedHashSet<>();
        Set<String> javaDemand = new LinkedHashSet<>();
        List<Path> scanned = new ArrayList<>();
        try (Stream<Path> s = Files.walk(corpus)) {
            scanned.addAll(s.filter(p -> p.toString().endsWith(".pure")).sorted().toList());
        }
        scanned.addAll(com.legend.rcorpus.Corpus.LIBRARY_FILES);
        scanned.addAll(com.legend.rcorpus.Corpus.SHAPE_FILES);
        for (Path f : scanned) {
            if (!Files.isRegularFile(f)) {
                continue;
            }
            for (String line : Files.readString(f, StandardCharsets.UTF_8).split("\n")) {
                Matcher d = DECL.matcher(line);
                if (d.find()) {
                    corpusDefined.add(d.group(2));
                }
            }
        }
        // the SYSTEM LAYER's own Pure text (SystemMetamodel: the metamodel
        // store's classes, mappings and views) names vocabulary bare through
        // its imports (SQLExecutionNode.resultColumns: SQLResultColumn[*])
        List<String> texts = new ArrayList<>();
        String systemText = com.legend.builtin.SystemMetamodel.source();
        texts.add(systemText);
        Set<String> systemDemand = new LinkedHashSet<>();   // what the system metamodel's source names
        {
            for (String src : texts) {
                Set<String> sink = systemDemand;
                List<String> imports = new ArrayList<>();
                for (String line : src.split("\n")) {
                    Matcher d = DECL.matcher(line);
                    if (d.find()) {
                        corpusDefined.add(d.group(2));
                    }
                    Matcher im = IMPORT.matcher(line.strip());
                    if (im.matches()) {
                        imports.add(im.group(1));
                    }
                }
                // every FULLY-QUALIFIED name the source spells, in any
                // position (dynamicNew(meta::…::LateralJoin, …) names its
                // class as an ARGUMENT)
                Matcher fq = FQN_TOKEN.matcher(src);
                while (fq.find()) {
                    if (index.containsKey(fq.group())) {
                        sink.add(fq.group());
                    }
                }
                // SUPERTYPES of the corpus's own declarations (Class X extends
                // A, B<T>): the corpus class needs them to resolve (TableTDS
                // extends TabularDataSetImplementation — its `store` end)
                List<String> bareRefs = new ArrayList<>();
                Matcher ex = EXTENDS_CLAUSE.matcher(src);
                while (ex.find()) {
                    for (String part : ex.group(1).split(",")) {
                        String nm = part.trim().replaceAll("<.*$", "").trim();
                        if (!nm.isEmpty()) {
                            bareRefs.add(nm);
                        }
                    }
                }
                Matcher r = TYPE_REF.matcher(src);
                while (r.find()) {
                    bareRefs.add(r.group(1) != null ? r.group(1) : r.group(2));
                }
                for (String n : bareRefs) {
                    if (index.containsKey(n)) {
                        sink.add(n);
                        continue;
                    }
                    List<String> scope = new ArrayList<>(imports);
                    scope.addAll(NameResolver.CORE_IMPORTS);   // real pure's implicit imports
                    for (String imp : scope) {
                        if (index.containsKey(imp + "::" + n)) {
                            sink.add(imp + "::" + n);
                            break;
                        }
                    }
                }
            }
        }
        // JAVA demand = the platform's VOCABULARY: the native SIGNATURES in
        // Pure.java, the classes Java CONSTRUCTS (an explicit receipt list — a
        // text scan cannot tell "constructs" from "compares"), the system
        // metamodel's source (below)
        for (String line : Files.readString(Path.of("src/main/java/com/legend/builtin/Pure.java"),
                StandardCharsets.UTF_8).split("\n")) {
            if (line.contains("signature(\"")) {
                Matcher r = FQN_TOKEN.matcher(line);
                while (r.find()) {
                    if (index.containsKey(r.group())) {
                        javaDemand.add(r.group());
                    }
                }
            }
        }
        for (String fqn : com.legend.compiler.element.type.PlatformTypes.constructedVocabulary()) {
            if (index.containsKey(fqn)) {
                javaDemand.add(fqn);
            }
        }
        javaDemand.addAll(systemDemand);
        // owned = the HAND-declared natives (read from Pure.java's SOURCE, so
        // the generator never depends on the module it writes), the system
        // layer and the corpus's own definitions
        Set<String> platformOwned = new LinkedHashSet<>(handDeclaredFqns());
        platformOwned.addAll(com.legend.builtin.SystemMetamodel.elementFqns());
        Set<String> owned = new LinkedHashSet<>(platformOwned);
        owned.addAll(corpusDefined);

        // 3. parse + resolve the defining files, closing over referenced types
        // (Spec.close: the closure walk, reusable — the T1 demand below runs it too)
        Set<String> knownFqns = new LinkedHashSet<>(index.keySet());
        knownFqns.addAll(owned);
        Spec spec = new Spec(index, platformOwned, corpusDefined, knownFqns);
        // THE DEMAND IS T1 (PRELUDE_MODULE_HOMEWORK §2, PHASE3_DEMAND_CUT_HOMEWORK;
        // batch 155 = phase 3b-2): (1) legend-pure's platform packages WHOLE —
        // every class and enum under the nine platform roots, minus the decided
        // exclusions and the spec's test packages, demanded or not; (2) the
        // platform's VOCABULARY — what its Java constructs (the receipt list
        // PlatformTypes.CONSTRUCTED_VOCABULARY), names in a native signature,
        // or names in the system metamodel's source; (3) the closure of those
        // declarations. "The corpus names it" is no reason (T2): an engine
        // class a program needs enters that program's graph by file
        // (Corpus.SHAPE_FILES). A vocabulary class the corpus tree also
        // declares is generated all the same — the graph's copy yields (T4).
        Set<String> todaySeed = new LinkedHashSet<>();
        for (String fqn : javaDemand) {
            if (!handDeclaredFqns().contains(fqn) && !excluded(fqn)
                    && !com.legend.builtin.SystemMetamodel.elementFqns().contains(fqn)) {
                todaySeed.add(fqn);
            }
        }
        List<Path> platformRoots = new ArrayList<>();
        for (String r : SpecBodyCensusTest.PLATFORM_ROOTS) {
            platformRoots.add(pure.resolve(r));
        }
        for (Map.Entry<String, Path> e : index.entrySet()) {
            boolean platform = platformRoots.stream().anyMatch(e.getValue()::startsWith);
            if (platform && !owned.contains(e.getKey()) && !excluded(e.getKey())) {
                todaySeed.add(e.getKey());
            }
        }
        Closure today = spec.close(todaySeed);
        Set<String> want = today.want();
        Set<String> pulledFromCorpus = today.pulledFromCorpus();
        Map<String, PackageableElement> resolved = spec.resolved;
        Map<String, String> declText = spec.declText;
        Map<String, String> fileOf = spec.fileOf;
        Map<String, Integer> offsetOf = spec.offsetOf;
        Map<String, ImportScope> scopeOf = spec.scopeOf;
        for (String fqn : want) {
            if (!resolved.containsKey(fqn)) {
                throw new IllegalStateException("prelude generator: '" + fqn
                        + "' is indexed at " + index.get(fqn) + " but did not parse as a class/enum");
            }
            if (!declText.containsKey(fqn)) {
                throw new IllegalStateException("prelude generator: no declaration text for '" + fqn + "'");
            }
        }
        checkClosed(want, resolved, platformOwned, corpusDefined, index);

        // THE T1 DEMAND (PHASE3_DEMAND_CUT_HOMEWORK, phase 3a — reported, not yet
        // emitted): legend-pure's platform packages whole + the platform's
        // vocabulary (native signatures, the system metamodel's source, the
        // classes Java constructs) + closure. Census mode writes the diff
        // against today's demand: target/prelude-t1-diff.tsv (keep / leave / enter)
        if ("1".equals(System.getProperty("prelude.census"))) {
            Set<String> t1Seed = new LinkedHashSet<>();
            for (Map.Entry<String, Path> e : index.entrySet()) {
                boolean platform = platformRoots.stream().anyMatch(e.getValue()::startsWith);
                if (platform && !owned.contains(e.getKey()) && !excluded(e.getKey())) {
                    t1Seed.add(e.getKey());
                }
            }
            Set<String> vocabulary = new LinkedHashSet<>();
            for (String line : Files.readString(Path.of("src/main/java/com/legend/builtin/Pure.java"),
                    StandardCharsets.UTF_8).split("\n")) {
                if (line.contains("signature(\"")) {
                    Matcher r = FQN_TOKEN.matcher(line);
                    while (r.find()) {
                        vocabulary.add(r.group());
                    }
                }
            }
            vocabulary.addAll(systemDemand);
            vocabulary.addAll(com.legend.compiler.element.type.PlatformTypes.constructedVocabulary());
            for (String fqn : vocabulary) {
                if (index.containsKey(fqn) && !handDeclaredFqns().contains(fqn) && !excluded(fqn)
                        && !com.legend.builtin.SystemMetamodel.elementFqns().contains(fqn)) {
                    t1Seed.add(fqn);
                }
            }
            Closure t1 = spec.close(t1Seed);
            List<String> rows = new ArrayList<>();
            rows.add("fqn\tstatus\tsource\tfile");
            int keep = 0;
            int leave = 0;
            int enter = 0;
            Set<String> all = new TreeSet<>(want);
            all.addAll(t1.want());
            for (String fqn : all) {
                String status = want.contains(fqn) && t1.want().contains(fqn) ? "keep"
                        : want.contains(fqn) ? "leave" : "enter";
                if (status.equals("keep")) {
                    keep++;
                } else if (status.equals("leave")) {
                    leave++;
                } else {
                    enter++;
                }
                String file = relative(fileOf.get(fqn), engine, pure);
                String source = file.startsWith("legend-pure/") ? "legend-pure" : "legend-engine";
                rows.add(String.join("\t", fqn, status, source, file));
            }
            Files.createDirectories(Path.of("target"));
            Files.write(Path.of("target/prelude-t1-diff.tsv"), rows);
            System.out.println("[prelude-t1] today=" + want.size() + " t1=" + t1.want().size()
                    + " keep=" + keep + " leave=" + leave + " enter=" + enter
                    + " -> target/prelude-t1-diff.tsv");
        }

        // THE CENSUS (-Dprelude.census=1, HOMEWORK §4): one row per wanted
        // declaration — where it comes from, who demands it, what the module
        // must carry. Snapshot: docs/PRELUDE_MODULE_CENSUS_2026_09_08.tsv
        if ("1".equals(System.getProperty("prelude.census"))) {
            List<String> rows = new ArrayList<>();
            rows.add("fqn\tsource\tdemand\tcorpusDefined\tconstraints\tderived\tstereotypes\ttaggedValues\tdefaults\tescapes\tfile");
            for (String fqn : new TreeSet<>(want)) {
                PackageableElement el = resolved.get(fqn);
                String file = relative(fileOf.get(fqn), engine, pure);
                String source = file.startsWith("legend-pure/") ? "legend-pure"
                        : file.startsWith("legend-engine/") ? "legend-engine" : "?";
                String dem = javaDemand.contains(fqn) ? "java" : todaySeed.contains(fqn) ? "platform" : "closure";
                if (el instanceof ClassDefinition cd) {
                    long defaults = cd.properties().stream().filter(ClassDefinition.PropertyDefinition::hasDefault).count();
                    rows.add(String.join("\t", fqn, source, dem, String.valueOf(corpusDefined.contains(fqn)),
                            String.valueOf(cd.constraints().size()), String.valueOf(cd.derivedProperties().size()),
                            String.valueOf(cd.stereotypes().size()), String.valueOf(cd.taggedValues().size()),
                            String.valueOf(defaults), "", file));
                } else {
                    rows.add(String.join("\t", fqn, source, dem, String.valueOf(corpusDefined.contains(fqn)),
                            "enum", "", "", "", "", "", file));
                }
            }
            Files.createDirectories(Path.of("target"));
            Files.write(Path.of("target/prelude-census.tsv"), rows);
            System.out.println("[prelude-census] " + (rows.size() - 1) + " rows -> target/prelude-census.tsv");
        }

        // 4. EMIT: one ###Pure section per (spec file, import scope), the
        // scope's imports, then each declaration VERBATIM in source order
        StringBuilder sb = new StringBuilder();
        sb.append("// Copyright 2026 Legend Contributors\n");
        sb.append("// SPDX-License-Identifier: Apache-2.0\n");
        sb.append("//\n");
        sb.append("// GENERATED — do not edit (com.legend.tools.PreludeGeneratorTest, -Dprelude.generate=1).\n");
        sb.append("// THE PRELUDE AS A MODULE (docs/SYSTEM_PRELUDE_DESIGN_2026_09_08.md §10,\n");
        sb.append("// docs/PRELUDE_MODULE_HOMEWORK_2026_09_08.md): the library shapes the corpus and the platform's Java\n");
        sb.append("// name, copied VERBATIM from the legend-pure / legend-engine spec — one ###Pure section per spec file\n");
        sb.append("// and import scope, each declaration exactly as the spec spells it (constraints, stereotypes, tagged\n");
        sb.append("// values, derived properties, defaults). Compiled through the user pipeline as the boot layer beside\n");
        sb.append("// the system metamodel (Compiler.bootLayer): resolved under these imports, normalized, cached once.\n");
        sb.append("// Shapes Pure.java still declares by hand are skipped here until their hand copy is deleted.\n");
        // MODULE ORDER IS A RULE (HOMEWORK §9.12): legend-pure's sections
        // before legend-engine's, each tier by spec path, declarations in
        // source order — the resolver's bare-name fallback reads the module
        // in this order (first claimant wins after the catalog). Section
        // key: tier, relative file, then the scope (a file may open several
        // ###Pure sections with different imports; each element keeps its own)
        Map<String, List<String>> bySection = new TreeMap<>();
        Map<String, ImportScope> sectionScope = new LinkedHashMap<>();
        for (String fqn : want) {
            String file = relative(fileOf.get(fqn), engine, pure);
            ImportScope scope = scopeOf.get(fqn);
            String tier = file.startsWith("legend-pure/") ? "0" : "1";
            String key = tier + "\t" + file + "\t" + String.join(",", scope.wildcards());
            bySection.computeIfAbsent(key, k -> new ArrayList<>()).add(fqn);
            sectionScope.putIfAbsent(key, scope);
        }
        int classes = 0;
        int enums = 0;
        for (Map.Entry<String, List<String>> section : bySection.entrySet()) {
            String file = section.getKey().split("\t")[1];
            sb.append("\n###Pure\n// ").append(file).append('\n');
            for (String pkg : sectionScope.get(section.getKey()).wildcards()) {
                sb.append("import ").append(pkg).append("::*;\n");
            }
            List<String> inOrder = new ArrayList<>(section.getValue());
            inOrder.sort(java.util.Comparator.comparingInt(offsetOf::get));
            for (String fqn : inOrder) {
                if (resolved.get(fqn) instanceof ClassDefinition) {
                    classes++;
                } else {
                    enums++;
                }
                sb.append(declText.get(fqn)).append("\n\n");
            }
        }
        sb.append("// ").append(classes).append(" classes, ").append(enums).append(" enums.\n");
        if (!pulledFromCorpus.isEmpty()) {
            sb.append("// T4 RECEIPTS — declared by the corpus tree too; the prelude wins, the graph's copy yields\n");
            sb.append("// (Compiler.withoutPreludeShadows); this list burns to zero in phase 3:\n");
            for (String c : new TreeSet<>(pulledFromCorpus)) {
                sb.append("//   ").append(c).append('\n');
            }
        }
        String module = sb.toString();
        // the whole module parses as ONE model, sections and imports included,
        // to exactly the wanted declarations
        ParsedModel whole = ElementParser.parse(module, Dialect.LEGEND_PLATFORM);
        Set<String> parsedFqns = new TreeSet<>();
        whole.elements().forEach(e -> parsedFqns.add(e.qualifiedName()));
        if (whole.elements().size() != classes + enums || !parsedFqns.equals(new TreeSet<>(want))) {
            Set<String> missing = new TreeSet<>(want);
            missing.removeAll(parsedFqns);
            Set<String> extra = new TreeSet<>(parsedFqns);
            extra.removeAll(want);
            throw new IllegalStateException("prelude generator: the module parses to "
                    + whole.elements().size() + " elements, expected " + (classes + enums)
                    + "; missing " + missing + ", extra " + extra);
        }
        return module;
    }

    /** One closure walk's result. */
    record Closure(Set<String> want, Set<String> pulledFromCorpus) {
    }

    /**
     * The spec as parsed on demand: files parsed once and cached across
     * closure walks; {@link #close} takes a SEED of wanted FQNs and returns
     * it closed over every type the wanted declarations name (HOMEWORK
     * §9.9 — declarations only, never bodies, §9a).
     */
    static final class Spec {
        final Map<String, Path> index;
        final Set<String> platformOwned;
        final Set<String> corpusDefined;
        final Set<String> knownFqns;
        final Map<String, PackageableElement> resolved = new LinkedHashMap<>();
        final Map<String, String> declText = new LinkedHashMap<>();
        final Map<String, String> fileOf = new LinkedHashMap<>();
        final Map<String, Integer> offsetOf = new LinkedHashMap<>();
        final Map<String, ImportScope> scopeOf = new LinkedHashMap<>();
        final Set<Path> parsedFiles = new LinkedHashSet<>();
        final Map<String, TokenStream> tokensOf = new LinkedHashMap<>();

        Spec(Map<String, Path> index, Set<String> platformOwned, Set<String> corpusDefined,
                Set<String> knownFqns) {
            this.index = index;
            this.platformOwned = platformOwned;
            this.corpusDefined = corpusDefined;
            this.knownFqns = knownFqns;
        }

        Closure close(Set<String> seed) throws IOException {
            Set<String> want = new LinkedHashSet<>(seed);
            Set<String> pulledFromCorpus = new LinkedHashSet<>();
            for (String fqn : seed) {
                if (corpusDefined.contains(fqn)) {
                    pulledFromCorpus.add(fqn);
                }
            }
            boolean grew = true;
            while (grew) {
                grew = false;
                List<Compiler.ModelSource> sources = new ArrayList<>();
                for (String fqn : new ArrayList<>(want)) {
                    Path f = index.get(fqn);
                    if (f != null && parsedFiles.add(f)) {
                        sources.add(new Compiler.ModelSource(f.toString(),
                                Files.readString(f, StandardCharsets.UTF_8)));
                    }
                }
                if (!sources.isEmpty()) {
                    List<String> parseWalls = new ArrayList<>();
                    ParsedModel parsed = Compiler.parseSources(sources,
                            (name, err) -> parseWalls.add(name + " => " + err),
                            Dialect.LEGEND_PLATFORM).model();
                    if (!parseWalls.isEmpty()) {
                        throw new IllegalStateException("prelude generator: spec files that do not"
                                + " parse (a parser gap to fix, never a hand copy): " + parseWalls);
                    }
                    Map<String, String> walls = new LinkedHashMap<>();
                    ParsedModel r = NameResolver.resolveAlongside(parsed, knownFqns, walls);
                    // a pulled FILE also carries functions (never emitted): only a
                    // wanted class/enum that fails to resolve is a generator error
                    Map<String, String> shapeWalls = new LinkedHashMap<>();
                    walls.forEach((fqn, msg) -> {
                        if (want.contains(fqn)) {
                            shapeWalls.put(fqn, msg);
                        }
                    });
                    if (!shapeWalls.isEmpty()) {
                        throw new IllegalStateException("prelude generator: unresolved names in"
                                + " wanted declarations: " + shapeWalls);
                    }
                    for (PackageableElement el : r.elements()) {
                        if (el instanceof ClassDefinition || el instanceof EnumDefinition) {
                            resolved.putIfAbsent(el.qualifiedName(), el);
                            String srcName = parsed.elementSources().get(el.qualifiedName());
                            Integer off = parsed.elementOffsets().get(el.qualifiedName());
                            if (srcName != null && off != null && !declText.containsKey(el.qualifiedName())) {
                                for (Compiler.ModelSource ms : sources) {
                                    if (ms.name().equals(srcName)) {
                                        TokenStream ts = tokensOf.computeIfAbsent(srcName,
                                                k -> Lexer.tokenize(ms.text()));
                                        declText.put(el.qualifiedName(), declarationText(ts, ms.text(), off,
                                                el instanceof EnumDefinition));
                                        fileOf.put(el.qualifiedName(), srcName);
                                        offsetOf.put(el.qualifiedName(), off);
                                        scopeOf.put(el.qualifiedName(), parsed.elementImports()
                                                .getOrDefault(el.qualifiedName(), ImportScope.empty()));
                                    }
                                }
                            }
                        }
                    }
                }
                // CLOSURE (HOMEWORK §9.9): every type a wanted declaration names —
                // supertypes, stored and derived property types, derived parameter
                // types — is part of that shape's graph and is admitted when the
                // spec declares it and it is not a DECIDED exclusion; the
                // spec-test-package rule governs DEMAND only (the engine's
                // SqlFunction.tests : SqlFunctionTest[*] names a tests:: class).
                // PLATFORM ownership (hand + system) stops the walk; a corpus-tree
                // class is admitted and listed (T4 receipt — the graph's copy yields)
                for (String fqn : new ArrayList<>(want)) {
                    PackageableElement el = resolved.get(fqn);
                    if (el instanceof ClassDefinition cd) {
                        for (String ref : referencedFqns(cd)) {
                            if (!platformOwned.contains(ref) && !excludedByDecision(ref)
                                    && index.containsKey(ref) && want.add(ref)) {
                                if (corpusDefined.contains(ref)) {
                                    pulledFromCorpus.add(ref);
                                }
                                grew = true;
                            }
                        }
                    }
                }
            }
            return new Closure(want, pulledFromCorpus);
        }
    }

    /** CLOSURE COMPLETENESS (T5 — the module is a closed library the boot
     * layer checks eagerly): every type a generated declaration names must
     * be owned by the platform, generated, a primitive, or one of the
     * class's own type parameters — a bare or dangling name here is a
     * generator gap or an exclusion to widen, never an omitted class. */
    static void checkClosed(Set<String> want, Map<String, PackageableElement> resolved,
            Set<String> platformOwned, Set<String> corpusDefined, Map<String, Path> index) {
        java.util.SortedMap<String, String> dangling = new TreeMap<>();
        for (String fqn : want) {
            if (resolved.get(fqn) instanceof ClassDefinition cd) {
                Set<String> names = new LinkedHashSet<>();
                for (TypeExpression t : cd.superClasses()) {
                    collectAll(t, names);
                }
                for (ClassDefinition.PropertyDefinition p : cd.properties()) {
                    collectAll(p.type(), names);
                }
                for (DerivedPropertyDefinition dp : cd.derivedProperties()) {
                    collectAll(dp.type(), names);
                    for (ParameterDefinition pd : dp.parameters()) {
                        collectAll(pd.type(), names);
                    }
                }
                for (String n : names) {
                    boolean ok = cd.typeParams().contains(n) || n.equals("?")
                            || n.startsWith("meta::pure::metamodel::type::")
                            || platformOwned.contains(n) || want.contains(n);
                    if (!ok) {
                        dangling.put(fqn + " -> " + n, excludedByDecision(n) ? "excluded package"
                                : index.containsKey(n) ? "indexed but not closed"
                                : corpusDefined.contains(n) ? "graph-owned, not indexed"
                                : "unresolved/bare name");
                    }
                }
            }
        }
        if (!dangling.isEmpty()) {
            throw new IllegalStateException("prelude generator: dangling type references in"
                    + " generated declarations (widen the closure, lift an exclusion, or exclude the"
                    + " referencing class):\n  " + dangling.entrySet().stream()
                            .map(e -> e.getKey() + " [" + e.getValue() + "]")
                            .collect(java.util.stream.Collectors.joining("\n  ")));
        }
    }

    /**
     * The declaration's VERBATIM text, delimited by THE PARSER (HOMEWORK
     * §9.7): from the element's first token (the parser's own element offset)
     * to where {@code parseClassDefinition} / {@code parseEnumDefinition}
     * leaves the cursor. A header tagged-value block, a constraint block, a
     * brace inside a string literal — the parser that will read the module
     * decides, never a regex or a brace count.
     */
    static String declarationText(TokenStream tokens, String source, int offset, boolean isEnum) {
        int i = -1;
        for (int k = 0; k < tokens.count(); k++) {
            if (tokens.start(k) == offset) {
                i = k;
                break;
            }
        }
        if (i < 0) {
            throw new IllegalStateException("prelude generator: no token starts at offset " + offset);
        }
        ElementParser p = ElementParser.at(tokens, i, Dialect.LEGEND_PLATFORM);
        if (isEnum) {
            p.parseEnumDefinition();
        } else {
            p.parseClassDefinition(false);
        }
        return source.substring(tokens.start(i), tokens.end(p.pos() - 1));
    }

    /** The spec file's path relative to its checkout root — the module is a
     * committed resource and carries no machine's absolute paths. */
    static String relative(String absolute, Path engine, Path pure) {
        Path f = Path.of(absolute);
        if (f.startsWith(engine)) {
            return "legend-engine/" + engine.relativize(f).toString().replace('\\', '/');
        }
        if (f.startsWith(pure)) {
            return "legend-pure/" + pure.relativize(f).toString().replace('\\', '/');
        }
        throw new IllegalStateException("prelude generator: " + absolute + " is under neither checkout root");
    }

    /** The FQNs {@code Pure.java} declares by hand ({@code native Class …}
     * and {@code Enum …} text), read from the source file. */
    static Set<String> handDeclaredFqns() throws IOException {
        String src = Files.readString(Path.of("src/main/java/com/legend/builtin/Pure.java"),
                StandardCharsets.UTF_8);
        Set<String> out = new LinkedHashSet<>();
        Matcher c = Pattern.compile("native Class ([A-Za-z0-9_]+(?:::[A-Za-z0-9_]+)+)").matcher(src);
        while (c.find()) {
            out.add(c.group(1));
        }
        Matcher e = Pattern.compile("\\bEnum (meta::[A-Za-z0-9_:]+)").matcher(src);
        while (e.find()) {
            out.add(e.group(1));
        }
        return out;
    }

    /** The exclusions that are DECISIONS (named classes, the versioned
     * protocol packages, m3 paths) — what the CLOSURE honours. */
    private static boolean excludedByDecision(String fqn) {
        return EXCLUDED_CLASSES.containsKey(fqn)
                || (EXCLUDED_PACKAGE_PREFIXES.stream().anyMatch(fqn::startsWith)
                        && !fqn.startsWith(PROTOCOL_TEMPLATE_M3));
    }

    /** What DEMAND honours: the decisions plus the spec-test-package rule. */
    private static boolean excluded(String fqn) {
        // a spec TEST MODEL (…::tests::Person, …::test::shared::dest::Person)
        // is corpus/library input, never a platform shape — the platform's
        // own test-support namespace (meta::pure::functions::test) stays
        return excludedByDecision(fqn)
                || (fqn.matches(".*::tests?::.*") && !fqn.startsWith("meta::pure::functions::test::")
                        // the spec's PCT harness (meta::pure::test::pct / ::surveyor) — the natives
                        // executeTest/executePCTTest/loadPCTManifest name its shapes (batch 150)
                        && !fqn.startsWith("meta::pure::test::"));
    }

    /** A declaration header, stereotypes/tags and line breaks tolerated
     * ({@code Class <<typemodifiers.abstract>>\n  meta::…::RoutedValueSpecification}). */
    private static final Pattern DECL_HEADER = Pattern.compile(
            "(?m)^(Class|Enum)\\s+(?:<<[^>]*>>\\s*)*(?:\\{[^}]*\\}\\s*)?([A-Za-z0-9_]+(?:::[A-Za-z0-9_]+)+)");
    private static final Pattern DECL = Pattern.compile(
            "^(Class|Enum)\\s+(?:<<[^>]*>>\\s*)*(?:\\{[^}]*\\}\\s*)?([A-Za-z0-9_]+(?:::[A-Za-z0-9_]+)+)");
    /** An m3.pure bootstrap header: {@code ^Root.…children[Class] Name @Root.…children[pkg].children}. */
    private static final Pattern M3_HEADER = Pattern.compile(
            "(?m)^\\^Root\\.[^ ]*children\\[(Class|PrimitiveType|Enumeration)\\] ([A-Za-z_][A-Za-z0-9_]*)(?: @(Root\\.[^ \\n]*))?$");
    /** Platform carriers declared by hand with no spec/m3 counterpart, each with its reason. */
    private static final Map<String, String> HAND_CARRIERS = Map.ofEntries(
            // the PRIMITIVE types: m3 PrimitiveType instances (bootstrap), the
            // language's own value kinds — the compiler's SQL type wall keys on them
            Map.entry("meta::pure::metamodel::type::Number", "m3 primitive"),
            Map.entry("meta::pure::metamodel::type::Integer", "m3 primitive"),
            Map.entry("meta::pure::metamodel::type::Float", "m3 primitive"),
            Map.entry("meta::pure::metamodel::type::Decimal", "m3 primitive"),
            Map.entry("meta::pure::metamodel::type::String", "m3 primitive"),
            Map.entry("meta::pure::metamodel::type::Boolean", "m3 primitive"),
            Map.entry("meta::pure::metamodel::type::Byte", "m3 primitive"),
            Map.entry("meta::pure::metamodel::type::Date", "m3 primitive"),
            Map.entry("meta::pure::metamodel::type::StrictDate", "m3 primitive"),
            Map.entry("meta::pure::metamodel::type::DateTime", "m3 primitive"),
            Map.entry("meta::pure::metamodel::type::LatestDate", "m3 primitive"),
            Map.entry("meta::pure::metamodel::type::StrictTime", "m3 primitive"));
    private static final Pattern EXTENDS_CLAUSE = Pattern.compile(
            "(?m)^(?:Class|Association)\\b[^\\n{]*?\\bextends\\s+([^\\n{\\[]+)");
    private static final Pattern FQN_TOKEN = Pattern.compile("meta::[A-Za-z0-9_]+(?:::[A-Za-z0-9_]+)+");
    private static final Pattern IMPORT = Pattern.compile("^import\\s+([A-Za-z0-9_:]+)::\\*;");
    private static final Pattern TYPE_REF = Pattern.compile(
            "(?:@|\\^|instanceOf\\(|:\\s*)((?:[A-Za-z0-9_]+::)*[A-Z][A-Za-z0-9_]*)"
            // an ENUM VALUE reference (TemporalUnit.YEAR, DurationUnit.YEARS)
            // names its enumeration too — the value's owner is demand
            + "|(?<![\\w$.])((?:[A-Za-z0-9_]+::)*[A-Z][A-Za-z0-9_]*)\\.[A-Z][A-Z0-9_]*\\b");

    // ------------------------------------------------------------------
    // what a declaration names (the closure walks these)
    // ------------------------------------------------------------------

    /** Every QUALIFIED type a class declaration names: supertypes, stored
     * and derived property types, derived parameter types. */
    static Set<String> referencedFqns(ClassDefinition cd) {
        Set<String> out = new LinkedHashSet<>();
        for (TypeExpression t : cd.superClasses()) {
            collectAll(t, out);
        }
        for (ClassDefinition.PropertyDefinition p : cd.properties()) {
            collectAll(p.type(), out);
        }
        for (DerivedPropertyDefinition dp : cd.derivedProperties()) {
            collectAll(dp.type(), out);
            for (ParameterDefinition pd : dp.parameters()) {
                collectAll(pd.type(), out);
            }
        }
        out.removeIf(n -> !n.contains("::"));
        return out;
    }

    /** Every name a type expression mentions, bare names included. */
    private static void collectAll(TypeExpression t, Set<String> out) {
        switch (t) {
            case TypeExpression.NameRef nr -> out.add(nr.name());
            case TypeExpression.Generic g -> {
                out.add(g.name());
                g.arguments().forEach(a -> collectAll(a, out));
            }
            case TypeExpression.FunctionType ft -> {
                ft.parameters().forEach(p -> collectAll(p.type(), out));
                collectAll(ft.result().type(), out);
            }
            case TypeExpression.RelationType rt -> rt.columns().forEach(c -> collectAll(c.type(), out));
            case TypeExpression.SchemaAlgebra sa -> {
                collectAll(sa.left(), out);
                collectAll(sa.right(), out);
            }
        }
    }
}
