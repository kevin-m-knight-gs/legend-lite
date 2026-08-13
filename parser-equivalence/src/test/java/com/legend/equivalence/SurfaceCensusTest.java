package com.legend.equivalence;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** THE COVERAGE CENSUS: the live oracle ENUMERATES its own grammar
 *  surface (ServiceLoader extensions) — every section, connection
 *  flavor and embedded-data type it registers must be either parsed by
 *  our registry or NAMED in docs/parser-surface-exclusions.tsv
 *  (reviewed, shrink-only). Kills the "we don't parse X yet" ambush
 *  class (found via the Elasticsearch surprise, 2026-08-14). */
class SurfaceCensusTest {

    @Test
    void everyEngineSurfaceIsParsedOrNamed() throws Exception {
        java.util.Set<String> excluded = new java.util.HashSet<>();
        java.nio.file.Path ledger = java.nio.file.Path.of(
                "../docs/parser-surface-exclusions.tsv");
        for (String line : java.nio.file.Files.readAllLines(ledger)) {
            String[] c = line.split("\t");
            if (c.length >= 2 && !"kind".equals(c[0])) {
                excluded.add(c[0] + ":" + c[1]);
            }
        }
        java.util.List<String> missing = new java.util.ArrayList<>();
        java.util.List<String> census = new java.util.ArrayList<>();

        var exts = org.finos.legend.engine.language.pure.grammar.from
                .extension.PureGrammarParserExtensionLoader.extensions();
        java.util.Set<String> ourSections = new java.util.TreeSet<>(
                com.legend.parser.SectionGrammarRegistry.all().keySet());
        // core (non-extension) sections our PMCD front end owns directly
        ourSections.addAll(java.util.List.of("Pure", "Mapping", "Connection",
                "Runtime", "Relational", "Data", "Service", "Diagram",
                "Text", "GenerationSpecification", "FileGeneration",
                "ExternalFormat"));
        for (var ext : exts) {
            for (var sp : ext.getExtraSectionParsers()) {
                String n = sp.getSectionTypeName();
                census.add("section\t" + n);
                if (!ourSections.contains(n)
                        && !excluded.contains("section:" + n)) {
                    missing.add("section:" + n);
                }
            }
            for (var cp : ext.getExtraConnectionParsers()) {
                String n = cp.getConnectionTypeName();
                census.add("connection\t" + n);
                if (!OUR_CONNECTION_FLAVORS.contains(n)
                        && !excluded.contains("connection:" + n)) {
                    missing.add("connection:" + n);
                }
            }
            for (var dp : ext.getExtraEmbeddedDataParsers()) {
                String n = dp.getType();
                census.add("embeddedData\t" + n);
                if (!OUR_EMBEDDED_DATA.contains(n)
                        && !excluded.contains("embeddedData:" + n)) {
                    missing.add("embeddedData:" + n);
                }
            }
            for (var mp : ext.getExtraMappingElementParsers()) {
                check("mappingElement", mp.getElementTypeName(),
                        OUR_MAPPING_ELEMENTS, excluded, census, missing);
            }
            for (var ip : ext.getExtraMappingTestInputDataParsers()) {
                check("mappingTestInputData", ip.getInputDataTypeName(),
                        OUR_TEST_INPUT_DATA, excluded, census, missing);
            }
            for (var ep : ext.getExtraEmbeddedPureParsers()) {
                check("embeddedPure", ep.getType(),
                        OUR_EMBEDDED_PURE, excluded, census, missing);
            }
            for (var ta : ext.getExtraTestAssertionParsers()) {
                check("testAssertion", ta.getType(),
                        OUR_TEST_ASSERTIONS, excluded, census, missing);
            }
            for (var mi : ext.getExtraMappingIncludeParsers()) {
                check("mappingInclude", mi.getMappingIncludeType(),
                        OUR_MAPPING_INCLUDES, excluded, census, missing);
            }
        }
        java.nio.file.Files.createDirectories(
                java.nio.file.Path.of("target"));
        java.nio.file.Files.write(
                java.nio.file.Path.of("target", "surface-census.tsv"), census);
        assertTrue(missing.isEmpty(),
                "ENGINE grammar surface we neither parse nor NAME in "
                + "docs/parser-surface-exclusions.tsv: " + missing);
    }

    private static void check(String kind, String name,
            java.util.Set<String> ours, java.util.Set<String> excluded,
            java.util.List<String> census, java.util.List<String> missing) {
        census.add(kind + "\t" + name);
        if (!ours.contains(name) && !excluded.contains(kind + ":" + name)) {
            missing.add(kind + ":" + name);
        }
    }

    private static final java.util.Set<String> OUR_MAPPING_ELEMENTS =
            java.util.Set.of("Pure", "Relational", "ServiceStore",
                    "XStore", "EnumerationMapping", "Operation", "AggregationAware",
                    "ModelJoin", "relation", "Relation");
    private static final java.util.Set<String> OUR_TEST_INPUT_DATA =
            java.util.Set.of("Object", "Relational", "RelationalCSV");
    private static final java.util.Set<String> OUR_EMBEDDED_PURE =
            java.util.Set.of("SQL", ">", "TDS");
    private static final java.util.Set<String> OUR_TEST_ASSERTIONS =
            java.util.Set.of("EqualTo", "EqualToJson", "EqualToTDS");
    private static final java.util.Set<String> OUR_MAPPING_INCLUDES =
            java.util.Set.of("mapping", "dataspace");

    /** v3: every keyword literal in the ENGINE's g4 grammars must be in
     *  docs/g4-keyword-snapshot.tsv — when the engine grows a keyword the
     *  gate goes red until it is classified (parsed / excluded / queued).
     *  The 75 UNCLASSIFIED rows are the review backlog, family-bucketed. */
    @org.junit.jupiter.api.Test
    void everyG4KeywordIsSnapshotted() throws Exception {
        String engineRoot = System.getProperty("legend.engine.root");
        org.junit.jupiter.api.Assumptions.assumeTrue(engineRoot != null);
        java.util.Set<String> snap = new java.util.HashSet<>();
        for (String line : java.nio.file.Files.readAllLines(
                java.nio.file.Path.of("../docs/g4-keyword-snapshot.tsv"))) {
            snap.add(line.split("\t")[0]);
        }
        java.util.List<String> fresh = new java.util.ArrayList<>();
        java.util.regex.Pattern rule = java.util.regex.Pattern.compile(
                "^[A-Z][A-Z0-9_]*\\s*:\\s*'([A-Za-z][A-Za-z0-9_]*)'\\s*;",
                java.util.regex.Pattern.MULTILINE);
        try (var walk = java.nio.file.Files.walk(
                java.nio.file.Path.of(engineRoot))) {
            for (var g4 : (Iterable<java.nio.file.Path>) walk
                    .filter(f -> f.toString().endsWith("Grammar.g4"))
                    .filter(f -> !f.toString().contains("/target/")
                            && !f.toString().contains("/test/"))::iterator) {
                var m = rule.matcher(java.nio.file.Files.readString(g4));
                while (m.find()) {
                    if (!snap.contains(m.group(1))) {
                        fresh.add(m.group(1) + " (" + g4.getFileName() + ")");
                    }
                }
            }
        }
        assertTrue(fresh.isEmpty(), "ENGINE g4 keywords not in "
                + "docs/g4-keyword-snapshot.tsv — classify them: " + fresh);
    }

    /** The flavors ConnectionSectionGrammar dispatches (keep in sync with
     *  its switch — the census fails loudly when the ENGINE grows one). */
    private static final java.util.Set<String> OUR_CONNECTION_FLAVORS =
            java.util.Set.of("JsonModelConnection", "XmlModelConnection",
                    "ModelChainConnection", "RelationalDatabaseConnection",
                    "ServiceStoreConnection", "DeephavenConnection",
                    "MongoDBConnection");

    private static final java.util.Set<String> OUR_EMBEDDED_DATA =
            java.util.Set.of("ExternalFormat", "ModelStore", "Relational",
                    "ServiceStore", "Reference");
}
