// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.equivalence;

import org.finos.legend.engine.language.pure.grammar.from.extension.PureGrammarParserExtension;
import org.finos.legend.engine.language.pure.grammar.from.extension.SectionParser;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * THE DENOMINATOR: every {@code ###Section} legend-engine can parse, asked of
 * engine's own extension registry rather than grepped for.
 *
 * <p>Section names live behind constants in each
 * {@code PureGrammarParserExtension}, so grepping the sources finds a handful
 * of string literals and misses the rest — which is exactly how a coverage
 * table ends up listing only the sections we happened to trip over. The
 * registry is the authority, and it is one call away.
 *
 * <p>Prints the roster for {@code docs/PROTOCOL_MIGRATION_CENSUS.md} and
 * fails if it SHRINKS, so an upstream pull that adds a section shows up as a
 * number rather than as silence.
 */
class EngineSectionRosterTest {

    /** Sections engine registered at the pinned oracle version (5.88.1).
     *  A pull that ADDS one should widen the census, not pass in silence. */
    private static final int MIN_SECTIONS = 22;

    @Test
    void listEverySectionEngineCanParse() {
        // Walk every registered extension and ask IT for its section
        // parsers. PureGrammarParserExtensions indexes BY NAME
        // (getExtraSectionParser(type)) and cannot be enumerated, so the
        // ServiceLoader is the way in.
        List<String> names = new ArrayList<>();
        for (PureGrammarParserExtension ext
                : java.util.ServiceLoader.load(PureGrammarParserExtension.class)) {
            for (SectionParser p : ext.getExtraSectionParsers()) {
                names.add(p.getSectionTypeName());
            }
        }
        // ###Pure is the implicit default section and has no extension
        TreeSet<String> sorted = new TreeSet<>(names);
        sorted.add("Pure");

        System.out.println("[engine-sections] " + sorted.size()
                + " sections engine can parse:");
        sorted.forEach(n -> System.out.println("[engine-section] " + n));

        assertTrue(sorted.size() >= MIN_SECTIONS,
                "engine section roster shrank to " + sorted.size()
                        + " — did the oracle jars change? " + sorted);
    }
}
