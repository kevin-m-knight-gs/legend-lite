// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.parser;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * THE section-routing authority (GRAMMAR_EXTENSIBILITY.md step 1 /
 * PARSER_DROP_IN_PLAN.md Phase 2.5): {@code ###Name} &rarr; the grammar
 * module that owns it. Built-ins register through the SAME registry a
 * third-party overlay will use — the dogfooding rule that keeps the
 * plug-in path honest. An unknown section stops being lexer raw-skip
 * silence and becomes an explicit, reportable "no grammar registered"
 * ({@link com.legend.model.ParsedModel#unclaimedSections()}).
 *
 * <p>Step 2 (the {@code legend-lite-spi} artifact) grows
 * {@link SectionGrammar} a {@code parse(SectionSource, ElementSink)}
 * surface and a {@code ServiceLoader} discovery pass; today the record
 * names the owner and whether the SHARED lexer may tokenize the content
 * (an opaque grammar's content — a Diagram color literal — must never
 * reach the Pure token rules).
 */
public final class SectionGrammarRegistry {

    private SectionGrammarRegistry() {
    }

    /** A built-in section: content flows through the main token pipeline,
     *  so its {@link com.legend.spi.SectionGrammar#parse} surface is never
     *  invoked — calling it is a wiring bug and throws. */
    private record BuiltIn(String name) implements com.legend.spi.SectionGrammar {
        @Override
        public boolean lexable() {
            return true;
        }

        @Override
        public void parse(com.legend.spi.SectionSource src,
                com.legend.spi.ElementSink out) {
            throw new IllegalStateException("built-in section '" + name
                    + "' parses through the main pipeline, not the SPI");
        }
    }

    private static final Map<String, com.legend.spi.SectionGrammar> REGISTRY =
            build();

    private static Map<String, com.legend.spi.SectionGrammar> build() {
        Map<String, com.legend.spi.SectionGrammar> m = new LinkedHashMap<>();
        for (String s : new String[]{"Pure", "Mapping", "Relational", "Data"}) {
            m.put(s, new BuiltIn(s));
        }
        // REAL built-in grammars (§2.6): these sections route through the
        // seam — ElementParser.parseModel dispatches the whole section to
        // them, and the remaining BuiltIn stubs shrink as each migrates
        m.put("Connection",
                com.legend.parser.section.ConnectionSectionGrammar.INSTANCE);
        m.put("Runtime",
                com.legend.parser.section.RuntimeSectionGrammar.INSTANCE);
        m.put("Service",
                com.legend.parser.section.ServiceSectionGrammar.INSTANCE);
        m.put("DataSpace",
                com.legend.parser.section.DataSpaceSectionGrammar.INSTANCE);
        m.put("Persistence",
                com.legend.parser.section.PersistenceSectionGrammar.INSTANCE);
        m.put("Snowflake",
                com.legend.parser.section.SnowflakeSectionGrammar.INSTANCE);
        // the small keyed sections share ONE grammar class, each instance
        // registered with its censused element-kind set (null = open, the
        // FileGeneration posture — engine's own file-gen grammar is generic)
        for (var g : new com.legend.parser.section.GenericKeyedSectionGrammar[]{
                new com.legend.parser.section.GenericKeyedSectionGrammar(
                        "ExternalFormat",
                        java.util.Set.of("Binding", "SchemaSet")),
                new com.legend.parser.section.GenericKeyedSectionGrammar(
                        "FileGeneration", null),
                new com.legend.parser.section.GenericKeyedSectionGrammar(
                        "MemSql", java.util.Set.of("MemSqlFunction")),
                new com.legend.parser.section.GenericKeyedSectionGrammar(
                        "BigQuery", java.util.Set.of("BigQueryFunction")),
                new com.legend.parser.section.GenericKeyedSectionGrammar(
                        "HostedService", java.util.Set.of("HostedService")),
                new com.legend.parser.section.GenericKeyedSectionGrammar(
                        "FunctionJar", java.util.Set.of("FunctionJar")),
                new com.legend.parser.section.GenericKeyedSectionGrammar(
                        "Text", java.util.Set.of("Text")),
                new com.legend.parser.section.GenericKeyedSectionGrammar(
                        "GenerationSpecification",
                        java.util.Set.of("GenerationSpecification")),
                new com.legend.parser.section.GenericKeyedSectionGrammar(
                        "DataQualityValidation",
                        java.util.Set.of("DataQualityValidation",
                                "DataQualityRelationValidation",
                                "DataQualityRelationComparison")),
                new com.legend.parser.section.GenericKeyedSectionGrammar(
                        "ServiceStore", java.util.Set.of("ServiceStore")),
                new com.legend.parser.section.GenericKeyedSectionGrammar(
                        "MongoDB", java.util.Set.of("Database"))}) {
            m.put(g.name(), g);
        }
        // ServiceLoader overlays — registered LAST so an extension claiming
        // a built-in name WINS (the engine's own shadowing rule: that is
        // exactly what lets legend-lite drop into the engine's ###Pure)
        for (com.legend.spi.SectionGrammar g : java.util.ServiceLoader
                .load(com.legend.spi.SectionGrammar.class)) {
            m.put(g.name(), g);
        }
        return m;
    }

    /** The grammar registered for {@code sectionName}, if any. */
    public static Optional<com.legend.spi.SectionGrammar> lookup(String sectionName) {
        return Optional.ofNullable(REGISTRY.get(sectionName));
    }
}
