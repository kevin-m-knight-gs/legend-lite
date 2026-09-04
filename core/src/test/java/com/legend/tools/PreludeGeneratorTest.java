// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.tools;

import com.legend.Compiler;
import com.legend.compiler.NameResolver;
import com.legend.model.ClassDefinition;
import com.legend.model.EnumDefinition;
import com.legend.model.PackageableElement;
import com.legend.model.ParsedModel;
import com.legend.protocol.Multiplicity;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * THE PRELUDE GENERATOR (docs/DECLARATIONS_HOMEWORK_2026_09_04.md, option
 * S, user-ratified 2026-09-04): the library SHAPES a program may name are
 * DATA, generated from the spec — never hand-typed. This tool reads the
 * engine and legend-pure checkouts (spec), finds every class/enum the
 * corpus references (plus the transitive closure of the types those
 * declarations name — the model integrity pass is eager), parses their
 * files with OUR parser, resolves names with OUR resolver, and prints each
 * declaration in the prelude's {@code native Class …} form with fully
 * qualified names into {@code core/src/main/java/com/legend/builtin/Prelude.java}.
 *
 * <p>Declarations ONLY (WORLD_MAP §3): stored properties with their
 * {@code <<equality.Key>>} and default marker, supertypes, type
 * parameters; derived properties, constraints, tagged values and every
 * function are NOT emitted. Shapes already owned by {@code Pure.java}
 * natives, the system metamodel, or a corpus source are skipped (natives
 * win at lookup; corpus duplicates would refuse the build).
 *
 * <p>Modes: {@code -Dprelude.generate=1} WRITES the file; otherwise the
 * test regenerates in memory and asserts the committed file is current
 * (the parity guard — the spec moved, or someone edited by hand).
 */
class PreludeGeneratorTest {

    private static final Path OUT = Path.of(
            "src/main/java/com/legend/builtin/Prelude.java");

    /** Packages whose shapes are not (yet) generated — each line a decision. */
    private static final List<String> EXCLUDED_PACKAGE_PREFIXES = List.of(
            // protocol-version payload classes (nine copies of the same eight
            // mapping shapes, meta::protocols::pure::v1_2x_0::…): admitted in a
            // later slice with their own witness
            "meta::protocols::",
            // m3 path classes: `Path<-U,V|m> extends Function<{U[1]->V[m]}>`
            // generalizes with a NON-identity argument, which the kernel's
            // positional-pairing rule refuses (NativeFunctionTest.
            // parameterizedGeneralizationsAreIdentityArgument) — a kernel
            // gap to lift before these shapes can be data
            "meta::pure::metamodel::path::");
    /** Individual declarations left out, each with its reason. */
    private static final Map<String, String> EXCLUDED_CLASSES = Map.of(
            // names a versioned protocol class (meta::protocols::pure::vX_X_X
            // AppliedFunction) — the protocol packages are excluded wholesale
            "meta::pure::tds::toRelation::TdsToRelationExtension_V_X_X", "protocol-version adapter");


    @Test
    @DisplayName("Prelude.java is the generator's current output (regenerate with -Dprelude.generate=1)")
    void preludeIsCurrent() throws Exception {
        String generated = generate();
        if ("1".equals(System.getProperty("prelude.generate"))) {
            Files.writeString(OUT, generated, StandardCharsets.UTF_8);
            System.out.println("[prelude] wrote " + OUT + " (" + generated.lines().count() + " lines)");
            return;
        }
        assertTrue(Files.exists(OUT), "Prelude.java missing — run with -Dprelude.generate=1");
        assertEquals(generated, Files.readString(OUT, StandardCharsets.UTF_8),
                "Prelude.java is stale: the spec moved or the file was edited by hand —"
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
            String pkg = String.join("::", m3h.group(2).replace("Root.children[", "").replace("].children[", "::")
                    .replace("].children", "").replace("]", "").split("::"));
            m3.add(pkg + "::" + m3h.group(1));
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

        // 2. what the corpus defines itself, and what it names
        Set<String> corpusDefined = new LinkedHashSet<>();
        Set<String> demand = new LinkedHashSet<>();
        Set<String> javaDemand = new LinkedHashSet<>();
        List<Path> scanned = new ArrayList<>();
        try (Stream<Path> s = Files.walk(corpus)) {
            scanned.addAll(s.filter(p -> p.toString().endsWith(".pure")).sorted().toList());
        }
        // the admitted PROGRAM libraries (Corpus.LIBRARY_FILES) are corpus
        // input too: their signatures are eager, so their shapes are demand
        scanned.addAll(com.legend.rcorpus.Corpus.LIBRARY_FILES);
        // the SYSTEM LAYER's own Pure text (SystemMetamodel: the metamodel
        // store's classes, mappings and views) names library shapes bare
        // through its imports (SQLExecutionNode.resultColumns:
        // SQLResultColumn[*]) — scanned like a corpus file
        List<String> texts = new ArrayList<>();
        for (Path f : scanned) {
            texts.add(Files.readString(f, StandardCharsets.UTF_8));
        }
        String systemText = com.legend.builtin.SystemMetamodel.source();
        texts.add(systemText);
        {
            for (String src : texts) {
                // the system layer is PLATFORM demand: what it names must exist
                // without the corpus (a corpus-defined shape is generated too)
                Set<String> sink = src == systemText ? javaDemand : demand;
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
        // JAVA demand: every spec FQN the platform's own sources name — the
        // native SIGNATURES and remaining hand declarations in Pure.java, and
        // the FQN literals the compiler/resolver/lowering code dispatches on
        // (Prelude.java itself excluded: generated output is never demand)
        try (Stream<Path> s = Files.walk(Path.of("src/main/java"))) {
            for (Path f : s.filter(p -> p.toString().endsWith(".java")
                    && !p.getFileName().toString().equals("Prelude.java")).sorted().toList()) {
                Matcher r = FQN_TOKEN.matcher(Files.readString(f, StandardCharsets.UTF_8));
                while (r.find()) {
                    // a spec TEST MODEL (…::tests::Person) named in a harness
                    // comment or fixture is corpus input, never a platform shape
                    if (index.containsKey(r.group()) && !r.group().matches(".*::tests?::.*")) {
                        javaDemand.add(r.group());
                    }
                }
            }
        }
        // owned = the HAND-declared natives (read from Pure.java's SOURCE, so
        // the generator never depends on the previous Prelude.java loading),
        // the system layer and the corpus's own definitions
        Set<String> platformOwned = new LinkedHashSet<>(handDeclaredFqns());
        platformOwned.addAll(com.legend.builtin.SystemMetamodel.elementFqns());
        Set<String> owned = new LinkedHashSet<>(platformOwned);
        owned.addAll(corpusDefined);

        // 3. parse + resolve the defining files, closing over referenced types
        Map<String, PackageableElement> resolved = new LinkedHashMap<>();
        Map<String, String> declText = new LinkedHashMap<>();   // fqn -> the declaration's source text
        Set<Path> parsedFiles = new LinkedHashSet<>();
        Set<String> want = new LinkedHashSet<>();
        for (String fqn : demand) {
            if (!owned.contains(fqn) && !excluded(fqn)) {
                want.add(fqn);
            }
        }
        // what the PLATFORM names must exist without the corpus: a library
        // class that happens to be defined inside the corpus tree
        // (scanRelations::RelationTree, TestDataGenResult) is generated all
        // the same — the corpus loader's own copy is shadowed by the native
        for (String fqn : javaDemand) {
            if (!handDeclaredFqns().contains(fqn) && !excluded(fqn)
                    && !com.legend.builtin.SystemMetamodel.elementFqns().contains(fqn)) {
                want.add(fqn);
            }
        }
        Set<String> knownFqns = new LinkedHashSet<>(index.keySet());
        knownFqns.addAll(owned);
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
                        com.legend.parser.Dialect.LEGEND_PLATFORM).model();
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
                        if (srcName != null && off != null) {
                            for (Compiler.ModelSource ms : sources) {
                                if (ms.name().equals(srcName)) {
                                    declText.putIfAbsent(el.qualifiedName(), declarationText(ms.text(), off));
                                }
                            }
                        }
                    }
                }
            }
            // closure: every type a wanted declaration names — PLATFORM
            // ownership only (hand + system): a corpus-defined shape a
            // generated declaration names is generated too, or the platform
            // would not stand without the corpus (SQLExecutionNode.resultColumns)
            for (String fqn : new ArrayList<>(want)) {
                PackageableElement el = resolved.get(fqn);
                if (el instanceof ClassDefinition cd) {
                    for (String ref : referencedFqns(cd)) {
                        if (!platformOwned.contains(ref) && !excluded(ref) && index.containsKey(ref)
                                && want.add(ref)) {
                            grew = true;
                        }
                    }
                }
            }
        }
        for (String fqn : want) {
            if (!resolved.containsKey(fqn)) {
                throw new IllegalStateException("prelude generator: '" + fqn
                        + "' is indexed at " + index.get(fqn) + " but did not parse as a class/enum");
            }
        }
        // closure completeness (the model integrity pass is eager): every
        // type a generated declaration names must be owned, generated, a
        // primitive, or one of the class's own type parameters — a bare or
        // dangling name here is a generator gap or an exclusion to widen
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
                for (String n : names) {
                    boolean ok = cd.typeParams().contains(n) || n.equals("?")
                            || n.startsWith("meta::pure::metamodel::type::")
                            || owned.contains(n) || want.contains(n);
                    if (!ok) {
                        dangling.put(fqn + " -> " + n, excluded(n) ? "excluded package"
                                : index.containsKey(n) ? "indexed but not closed" : "unresolved/bare name");
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

        // 4. print
        StringBuilder sb = new StringBuilder();
        sb.append("// Copyright 2026 Legend Contributors\n");
        sb.append("// SPDX-License-Identifier: Apache-2.0\n\n");
        sb.append("package com.legend.builtin;\n\n");
        sb.append("import com.legend.model.ClassDefinition;\n");
        sb.append("import com.legend.model.EnumDefinition;\n");
        sb.append("import java.util.List;\n\n");
        sb.append("/**\n");
        sb.append(" * GENERATED — do not edit (com.legend.tools.PreludeGeneratorTest,\n");
        sb.append(" * {@code -Dprelude.generate=1}). The library SHAPES the corpus names,\n");
        sb.append(" * copied from the engine/legend-pure spec with their equality keys:\n");
        sb.append(" * declarations only, no bodies (docs/WORLD_MAP.md §3,\n");
        sb.append(" * docs/DECLARATIONS_HOMEWORK_2026_09_04.md). Shapes {@code Pure.java}\n");
        sb.append(" * still declares by hand are skipped here until their hand copy is\n");
        sb.append(" * deleted; the generator's parity test keeps this file current.\n");
        sb.append(" */\n");
        sb.append("public final class Prelude {\n\n");
        sb.append("    private Prelude() {\n    }\n\n");
        sb.append("    /** Touch to register every generated shape before Pure's index is built. */\n");
        sb.append("    static void load() {\n    }\n\n");
        sb.append("    /** The generated class FQNs (the generator's own exclusion set). */\n");
        sb.append("    public static java.util.Set<String> classFqns() {\n");
        sb.append("        return CLASSES.stream().map(ClassDefinition::qualifiedName)\n");
        sb.append("                .collect(java.util.stream.Collectors.toUnmodifiableSet());\n    }\n\n");
        sb.append("    /** A generated class by FQN — for tests and the few Java sites that\n");
        sb.append("     * need the DEFINITION rather than the name. */\n");
        sb.append("    public static ClassDefinition cls(String fqn) {\n");
        sb.append("        return CLASSES.stream().filter(c -> c.qualifiedName().equals(fqn)).findFirst()\n");
        sb.append("                .orElseThrow(() -> new IllegalArgumentException(\"not a generated class: \" + fqn));\n    }\n\n");
        sb.append("    /** A generated enum by FQN. */\n");
        sb.append("    public static EnumDefinition enumOf(String fqn) {\n");
        sb.append("        return ENUMS.stream().filter(e -> e.qualifiedName().equals(fqn)).findFirst()\n");
        sb.append("                .orElseThrow(() -> new IllegalArgumentException(\"not a generated enum: \" + fqn));\n    }\n\n");
        sb.append("    /** The generated enum FQNs. */\n");
        sb.append("    public static java.util.Set<String> enumFqns() {\n");
        sb.append("        return ENUMS.stream().map(EnumDefinition::qualifiedName)\n");
        sb.append("                .collect(java.util.stream.Collectors.toUnmodifiableSet());\n    }\n\n");
        int classes = 0;
        int enums = 0;
        List<String> classLines = new ArrayList<>();
        List<String> enumLines = new ArrayList<>();
        for (String fqn : new java.util.TreeSet<>(want)) {
            PackageableElement el = resolved.get(fqn);
            if (el instanceof ClassDefinition cd) {
                String text = printClass(cd, declText.getOrDefault(fqn, ""));
                roundTrip(fqn, text);
                classLines.add("            Pure.nativeClass(" + javaString(text) + "),");
                classes++;
            } else if (el instanceof EnumDefinition ed) {
                String text = printEnum(ed);
                roundTrip(fqn, text);
                enumLines.add("            Pure.nativeEnum(" + javaString(text) + "),");
                enums++;
            }
        }
        sb.append("    /** ").append(classes).append(" classes. */\n");
        sb.append("    static final List<ClassDefinition> CLASSES = List.of(\n");
        sb.append(String.join("\n", classLines).replaceAll(",$", "")).append("\n    );\n\n");
        sb.append("    /** ").append(enums).append(" enums. */\n");
        sb.append("    static final List<EnumDefinition> ENUMS = List.of(\n");
        sb.append(String.join("\n", enumLines).replaceAll(",$", "")).append("\n    );\n");
        sb.append("}\n");
        return sb.toString();
    }

    /** Every printed declaration must parse back through the prelude's own
     * door (one element, platform dialect) — a printer gap fails HERE with
     * the text, never at class-load. */
    private static void roundTrip(String fqn, String text) {
        try {
            var parsed = com.legend.parser.ElementParser.parse(text,
                    com.legend.parser.Dialect.LEGEND_PLATFORM);
            if (parsed.elements().size() != 1) {
                throw new IllegalStateException("parsed " + parsed.elements().size() + " elements");
            }
        } catch (RuntimeException e) {
            throw new IllegalStateException("prelude generator: printed declaration of " + fqn
                    + " does not parse: " + e.getMessage() + "\n  " + text, e);
        }
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

    private static boolean excluded(String fqn) {
        // a spec TEST MODEL (…::tests::Person, …::test::shared::dest::Person)
        // is corpus/library input, never a platform shape — the platform's
        // own test-support namespace (meta::pure::functions::test) stays
        return EXCLUDED_CLASSES.containsKey(fqn)
                || (fqn.matches(".*::tests?::.*") && !fqn.startsWith("meta::pure::functions::test::"))
                || EXCLUDED_PACKAGE_PREFIXES.stream().anyMatch(fqn::startsWith);
    }

    /** A declaration header, stereotypes/tags and line breaks tolerated
     * ({@code Class <<typemodifiers.abstract>>\n  meta::…::RoutedValueSpecification}). */
    private static final Pattern DECL_HEADER = Pattern.compile(
            "(?m)^(Class|Enum)\\s+(?:<<[^>]*>>\\s*)*(?:\\{[^}]*\\}\\s*)?([A-Za-z0-9_]+(?:::[A-Za-z0-9_]+)+)");
    private static final Pattern DECL = Pattern.compile(
            "^(Class|Enum)\\s+(?:<<[^>]*>>\\s*)*(?:\\{[^}]*\\}\\s*)?([A-Za-z0-9_]+(?:::[A-Za-z0-9_]+)+)");
    /** An m3.pure bootstrap header: {@code ^Root.…children[Class] Name @Root.…children[pkg].children}. */
    private static final Pattern M3_HEADER = Pattern.compile(
            "(?m)^\\^Root\\.[^ ]*children\\[(?:Class|PrimitiveType|Enumeration)\\] ([A-Za-z_][A-Za-z0-9_]*) @(Root\\.[^ \\n]*)");
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
    // the printer: resolved records -> prelude declaration text
    // ------------------------------------------------------------------

    static Set<String> referencedFqns(ClassDefinition cd) {
        Set<String> out = new LinkedHashSet<>();
        for (TypeExpression t : cd.superClasses()) {
            collect(t, out);
        }
        for (ClassDefinition.PropertyDefinition p : cd.properties()) {
            collect(p.type(), out);
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

    private static void collect(TypeExpression t, Set<String> out) {
        switch (t) {
            case TypeExpression.NameRef nr -> out.add(nr.name());
            case TypeExpression.Generic g -> {
                out.add(g.name());
                g.arguments().forEach(a -> collect(a, out));
            }
            case TypeExpression.FunctionType ft -> {
                ft.parameters().forEach(p -> collect(p.type(), out));
                collect(ft.result().type(), out);
            }
            case TypeExpression.RelationType rt -> rt.columns().forEach(c -> collect(c.type(), out));
            case TypeExpression.SchemaAlgebra sa -> {
                collect(sa.left(), out);
                collect(sa.right(), out);
            }
        }
    }

    /** The declaration's own text: from its start offset to the brace that
     * closes its body (the spec's verbatim default expressions live there). */
    static String declarationText(String source, int offset) {
        int open = source.indexOf('{', offset);
        if (open < 0) {
            return "";
        }
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return source.substring(offset, i + 1);
                }
            }
        }
        return source.substring(offset);
    }

    /** The VERBATIM default expression of a property, sliced from the
     * declaration text ({@code name : Type[m] = <expr>;}); only literal
     * defaults (string, number, boolean) are admitted — an enum or
     * expression default would need qualification and is a loud gap. */
    private static String defaultText(String declText, String property) {
        Matcher m = Pattern.compile("(?m)^\\s*(?:<<[^>]*>>\\s*)*" + Pattern.quote(property)
                + "\\s*:\\s*[^;=]+?=\\s*([^;]+);").matcher(declText);
        if (!m.find()) {
            throw new IllegalStateException("prelude generator: default of '" + property
                    + "' not found in the declaration text");
        }
        String v = m.group(1).strip();
        // admitted verbatim: literals, and expressions whose every capitalised
        // name is already fully qualified (the spec spells
        // `= ^meta::pure::runtime::ExecutionContext()`); a BARE type/enum
        // name would need this file's imports and is a loud gap
        Matcher bare = Pattern.compile("(?<![:A-Za-z0-9_])[A-Z][A-Za-z0-9_]*").matcher(v);
        if (!bare.find() || v.startsWith("'")) {
            return v;
        }
        throw new IllegalStateException("prelude generator: default '" + v + "' on '"
                + property + "' names a bare type — extend the printer");
    }

    static String printClass(ClassDefinition cd) {
        return printClass(cd, "");
    }

    static String printClass(ClassDefinition cd, String declText) {
        StringBuilder sb = new StringBuilder("native Class ").append(cd.qualifiedName());
        if (!cd.typeParams().isEmpty()) {
            sb.append('<').append(String.join(", ", cd.typeParams())).append('>');
        }
        if (!cd.superClasses().isEmpty()) {
            sb.append(" extends ");
            List<String> sups = new ArrayList<>();
            for (TypeExpression s : cd.superClasses()) {
                sups.add(printType(s));
            }
            sb.append(String.join(", ", sups));
        }
        sb.append(" {");
        for (ClassDefinition.PropertyDefinition p : cd.properties()) {
            sb.append(' ');
            boolean key = p.stereotypes().stream().anyMatch(st ->
                    com.legend.compiler.element.type.PlatformTypes.isProfile(st.profileName(),
                            com.legend.compiler.element.type.PlatformTypes.EQUALITY_PROFILE)
                            && st.stereotypeName().equals("Key"));
            if (key) {
                sb.append("<<equality.Key>> ");
            }
            sb.append(p.name()).append(": ").append(printType(p.type()))
                    .append(printMult(p.multiplicity()));
            if (p.hasDefault()) {
                // the parsed record keeps the FACT of a default (NewChecker
                // reads only that); the prelude still carries the spec's
                // VERBATIM literal, sliced from the declaration text
                sb.append(" = ").append(defaultText(declText, p.name()));
            }
            sb.append(';');
        }
        sb.append(" }");
        return sb.toString();
    }

    static String printEnum(EnumDefinition ed) {
        return "Enum " + ed.qualifiedName() + " { " + String.join(", ", ed.values()) + " }";
    }

    static String printType(TypeExpression t) {
        return switch (t) {
            case TypeExpression.NameRef nr -> nr.name();
            case TypeExpression.Generic g -> {
                List<String> args = new ArrayList<>();
                g.arguments().forEach(a -> args.add(printType(a)));
                String mults = g.multiplicityArguments().isEmpty() ? ""
                        : "|" + String.join(", ", g.multiplicityArguments());
                yield g.name() + "<" + String.join(", ", args) + mults + ">";
            }
            case TypeExpression.FunctionType ft -> {
                List<String> ps = new ArrayList<>();
                for (TypeExpression.TypedParameter p : ft.parameters()) {
                    ps.add(printType(p.type()) + printMult(p.multiplicity()));
                }
                yield "{" + String.join(", ", ps) + "->" + printType(ft.result().type())
                        + printMult(ft.result().multiplicity()) + "}";
            }
            case TypeExpression.RelationType rt -> {
                List<String> cs = new ArrayList<>();
                for (TypeExpression.Column c : rt.columns()) {
                    cs.add(c.name() + ":" + printType(c.type()) + printMult(c.multiplicity()));
                }
                yield "(" + String.join(", ", cs) + ")";
            }
            case TypeExpression.SchemaAlgebra sa -> throw new IllegalStateException(
                    "prelude generator: schema algebra in a declaration type — extend the printer");
        };
    }

    static String printMult(Multiplicity m) {
        return switch (m) {
            case Multiplicity.Concrete c -> {
                if (c.upperBound() == null) {
                    yield c.lowerBound() == 0 ? "[*]" : "[" + c.lowerBound() + "..*]";
                }
                yield c.lowerBound() == c.upperBound() ? "[" + c.lowerBound() + "]"
                        : "[" + c.lowerBound() + ".." + c.upperBound() + "]";
            }
            case Multiplicity.Parameter p -> "[" + p.name() + "]";
        };
    }

    private static String javaString(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
