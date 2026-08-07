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

    /** One section grammar: its {@code ###name} and whether the shared
     *  lexer understands its content. */
    public record SectionGrammar(String name, boolean lexable) {
    }

    private static final Map<String, SectionGrammar> BUILT_INS = builtIns();

    private static Map<String, SectionGrammar> builtIns() {
        Map<String, SectionGrammar> m = new LinkedHashMap<>();
        for (String s : new String[]{"Pure", "Mapping", "Relational",
                "Connection", "Runtime"}) {
            m.put(s, new SectionGrammar(s, true));
        }
        return m;
    }

    /** The grammar registered for {@code sectionName}, if any. */
    public static Optional<SectionGrammar> lookup(String sectionName) {
        return Optional.ofNullable(BUILT_INS.get(sectionName));
    }
}
