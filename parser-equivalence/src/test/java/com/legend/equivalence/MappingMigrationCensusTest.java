// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.equivalence;

import com.legend.lexer.Lexer;
import com.legend.lexer.TokenStream;
import com.legend.lexer.TokenType;
import com.legend.model.ClassMapping;
import com.legend.model.MappingDefinition;
import com.legend.model.PropertyMapping;
import com.legend.protocol.Protocol;
import com.legend.protocol.Realization;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * M0 — WHAT THE MAPPING TRANSFORM MUST HANDLE, RANKED
 * (PARSER_COMPLETENESS_PLAN.md §1: "Sizing it is the first task, not a guess").
 *
 * <p><b>This test deliberately does NOT measure readability.</b>
 * {@link MigrationSizingTest} already owns that question and answers it per
 * FILE — 24 legacy-only files, 18 of them one cause. Two harnesses counting
 * "the same" thing over different denominators is how 2,298/2,575 and
 * 2,398/2,798 came to look like a regression; there is one owner per number
 * here.
 *
 * <p>What this adds is the SHAPE of the work: histograms of the model
 * variants a {@code FromProtocol} mapping arm must PRODUCE and the protocol
 * variants it must CONSUME, ranked by corpus frequency so M1 attacks the
 * biggest bucket first instead of grinding variants alphabetically. Plus the
 * short list of protocol shapes with genuinely no model home, which need
 * loud refusals rather than silent mappings.
 *
 * <p>Reports only; the ratchet arrives with M1, when there is a transform to
 * ratchet. And per R3: these counts size the work, they do not prove it. Only
 * gates 4/5/6 do that.
 */
class MappingMigrationCensusTest {

    @Test
    void sizeTheMappingTransform() throws Exception {
        List<Corpus.Source> sources = Corpus.all();
        Assumptions.assumeTrue(!sources.isEmpty(),
                "no corpus on disk — set -Dlegend.engine.root / -Dlegend.pure.root");

        Map<String, Integer> modelClassKinds = new TreeMap<>();
        Map<String, Integer> modelPmKinds = new TreeMap<>();
        Map<String, Integer> protocolClassKinds = new TreeMap<>();
        Map<String, Integer> protocolAssocKinds = new TreeMap<>();
        Map<String, Integer> noModelHome = new TreeMap<>();
        int modelElements = 0;
        int protocolElements = 0;

        for (Corpus.Source src : sources) {
            String text = src.text();
            TokenStream ts;
            try {
                ts = Lexer.tokenize(text);
            } catch (Throwable lexFailed) {
                continue;
            }
            for (int i = 0; i < ts.count(); i++) {
                if (ts.type(i) != TokenType.MAPPING || !declPos(ts, i)) {
                    continue;
                }
                try {
                    countModel(com.legend.parser.ElementParser.parseMappingAt(ts, i),
                            modelClassKinds, modelPmKinds);
                    modelElements++;
                } catch (Throwable ignored) {
                    // readability is MigrationSizingTest's question, not ours
                }
                try {
                    // the AggregationAware span emulation needs the enclosing
                    // section's first content line; passing -1 fabricates 33
                    // false gaps (MigrationSizingTest says the same)
                    countProtocol(com.legend.parser.MappingProtocolParser.parse(
                                    ts, i, sectionStartLine(text, ts.start(i))),
                            protocolClassKinds, protocolAssocKinds, noModelHome);
                    protocolElements++;
                } catch (Throwable ignored) {
                    // ditto
                }
            }
        }

        StringBuilder b = new StringBuilder();
        b.append("M0 — MAPPING TRANSFORM CENSUS\n").append("=".repeat(72)).append('\n')
                .append("readability differential: see MigrationSizingTest"
                        + " (24 legacy-only FILES, 18 of one cause)\n")
                .append(String.format("mapping elements read via legacy model : %d%n",
                        modelElements))
                .append(String.format("mapping elements read via protocol     : %d%n",
                        protocolElements));
        section(b, "PRODUCE — model class-mapping variants", modelClassKinds);
        section(b, "PRODUCE — model property-mapping variants (M1 SLICE ORDER)",
                modelPmKinds);
        section(b, "CONSUME — protocol class-mapping variants", protocolClassKinds);
        section(b, "CONSUME — protocol association-mapping variants", protocolAssocKinds);
        section(b, "NO MODEL HOME — needs a loud refusal or a new model variant",
                noModelHome);

        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target", "mapping-migration-census.txt"), b.toString());
        System.out.println(b);
    }

    /**
     * The legacy parser returns ONE OF TWO model shapes, chosen per element:
     * {@code LegacyMappingDefinition} carries the legacy DSL surface tree
     * ({@link ClassMapping} variants, which {@code MappingNormalizer} rewrites
     * to canonical), {@link MappingDefinition} is the clean-sheet function form
     * (MappingGrammarParser.java:105-120). The transform's target is whichever
     * the source text selects, so both are counted.
     */
    private static void countModel(com.legend.model.PackageableElement m,
            Map<String, Integer> classKinds, Map<String, Integer> pmKinds) {
        if (m instanceof com.legend.model.LegacyMappingDefinition legacy) {
            classKinds.merge("[legacy-DSL element]", 1, Integer::sum);
            for (ClassMapping cm : legacy.classMappings()) {
                classKinds.merge(cm.getClass().getSimpleName(), 1, Integer::sum);
                if (cm instanceof ClassMapping.Relational rel) {
                    for (PropertyMapping pm : rel.propertyMappings()) {
                        countPm(pm, pmKinds);
                    }
                }
            }
        } else if (m instanceof MappingDefinition canonical) {
            classKinds.merge("[clean-sheet element]", 1, Integer::sum);
            for (MappingDefinition.ClassBinding cb : canonical.classBindings()) {
                classKinds.merge("ClassBinding:" + cb.kind()
                        + (cb.realization() instanceof Realization.Ref ? ":Ref" : ":Inline"),
                        1, Integer::sum);
            }
        }
    }

    /** Embedded PMs nest, and the nested ones are transform work too. */
    private static void countPm(PropertyMapping pm, Map<String, Integer> pmKinds) {
        pmKinds.merge(pm.getClass().getSimpleName(), 1, Integer::sum);
        if (pm instanceof PropertyMapping.Embedded emb) {
            for (PropertyMapping inner : emb.propertyMappings()) {
                countPm(inner, pmKinds);
            }
        }
    }

    private static void countProtocol(Protocol.PMapping m,
            Map<String, Integer> classKinds, Map<String, Integer> assocKinds,
            Map<String, Integer> noModelHome) {
        for (Protocol.PClassMapping cm : m.classMappings()) {
            classKinds.merge(cm.getClass().getSimpleName(), 1, Integer::sum);
            // ClassMapping permits Relational | Pure | Union | Inheritance |
            // RelationFunction — there is no merge-operation variant.
            if (cm instanceof Protocol.PClassMappingMergeOperation) {
                noModelHome.merge("PClassMappingMergeOperation"
                        + " (ClassMapping has no merge variant)", 1, Integer::sum);
            }
            // NOT absent — modelled LOSSILY ON PURPOSE: the legacy parser
            // keeps ~mainMapping as a Relational/Pure flagged
            // aggregationAwareMain and SKIPS the Views: block, so
            // rewrite-activity asserts fail honestly instead of silently
            // (MappingGrammarParser.java:456-512). The transform must
            // reproduce that flattening, not invent a variant.
            if (cm instanceof Protocol.PClassMappingAggregationAware) {
                noModelHome.merge("PClassMappingAggregationAware"
                        + " — FLATTENED BY DESIGN to ~mainMapping; Views dropped",
                        1, Integer::sum);
            }
        }
        for (Object am : m.associationMappings()) {
            assocKinds.merge(am.getClass().getSimpleName(), 1, Integer::sum);
        }
        // NOT a gap, and the naive name-match said otherwise on the first run:
        // AssociationMapping permits Relational | Cross | ModelJoin, so the
        // xstore and model-join protocol arms DO have model homes.
        if (!m.testSuites().isEmpty()) {
            noModelHome.merge("testSuites — model keeps only the RAW TEXT"
                    + " (MappingDefinition.testSuitesSource)",
                    m.testSuites().size(), Integer::sum);
        }
        if (!m.tests().isEmpty()) {
            noModelHome.merge("legacy tests — no model field at all",
                    m.tests().size(), Integer::sum);
        }
    }

    private static void section(StringBuilder b, String title,
            Map<String, Integer> counts) {
        b.append('\n').append(title).append('\n').append("-".repeat(72)).append('\n');
        if (counts.isEmpty()) {
            b.append("  (none)\n");
            return;
        }
        counts.entrySet().stream()
                .sorted((x, y) -> y.getValue() - x.getValue())
                .limit(30)
                .forEach(e -> b.append(String.format("  %5d  %s%n",
                        e.getValue(), e.getKey())));
    }

    /** Same computation ParserEquivalence uses — see MigrationSizingTest. */
    private static int sectionStartLine(String text, int offset) {
        Matcher m = Pattern.compile("(?m)^###Mapping\\b").matcher(text);
        int header = -1;
        while (m.find() && m.start() < offset) {
            header = m.start();
        }
        if (header < 0) {
            return -1;
        }
        int line = 1;
        for (int i = 0; i < header; i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        return line + 1;
    }

    private static boolean declPos(TokenStream ts, int i) {
        if (i == 0) {
            return true;
        }
        TokenType prev = ts.type(i - 1);
        return prev == TokenType.BRACE_CLOSE || prev == TokenType.SEMI_COLON
                || prev == TokenType.PAREN_CLOSE;
    }
}
