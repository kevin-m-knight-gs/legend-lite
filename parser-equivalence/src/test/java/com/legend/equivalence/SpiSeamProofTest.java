package com.legend.equivalence;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.finos.legend.engine.language.pure.grammar.from.PureGrammarParser;
import org.finos.legend.engine.language.pure.grammar.from.extension.PureGrammarParserExtensions;
import org.finos.legend.engine.protocol.pure.v1.model.context.PureModelContextData;
import org.finos.legend.engine.shared.core.ObjectMapperFactory;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * THE SEAM PROOF: two engine parsers in one JVM — VANILLA (no extensions, the four
 * built-ins) and SPI (legend-lite registered for {@code ###Pure} through the engine's
 * own {@code SectionParser} extension point, which {@code visitSection} consults FIRST).
 * Over every Pure-only corpus file both must produce byte-identical FULL
 * PureModelContextData JSON — SectionIndex included, so the bridge's Section building
 * (imports, element paths, spans through the walker offsets) is on the hook too, a
 * surface the element-level harness never compared.
 */
class SpiSeamProofTest {

    @Test
    void engineWithLegendLiteSpiMatchesVanillaEngineByteForByte() throws Exception {
        ObjectMapper mapper =
                ObjectMapperFactory.getNewStandardObjectMapperWithPureProtocolExtensionSupports();
        PureGrammarParser vanilla = PureGrammarParser.newInstance(
                PureGrammarParserExtensions.fromExtensions(List.of()));
        PureGrammarParser spi = PureGrammarParser.newInstance(
                PureGrammarParserExtensions.fromExtensions(
                        List.of(LegendLiteSectionParser.extension())));

        int match = 0;
        int bothReject = 0;
        int strictLenient = 0;
        List<String> strictLenientIds = new ArrayList<>();
        List<String> diffs = new ArrayList<>();
        List<String> engineAsymmetry = new ArrayList<>();
        List<String> asymmetric = new ArrayList<>();
        // NO pureOnly gate (implementation audit §3.1): mixed-section files are
        // the production drop-in shape — the SPI routes ###Pure through
        // legend-lite while the engine's own parsers take the other sections,
        // and BOTH sides must still agree on the full PMCD
        for (Corpus.Source src : Corpus.all()) {
            String vanillaJson;
            try {
                PureModelContextData v = vanilla.parseModel(src.text());
                vanillaJson = mapper.writeValueAsString(v);
            } catch (Throwable t) {
                // THE PARSER leniency census (implementation audit §3.3): the
                // bridge ratchet below bounds what the SPI seam lets through,
                // but the raw parseStrict surface is ~4x as lenient — measure
                // it HERE, on the same vanilla verdict, scoped to Pure-only
                // files (mixed-file leniency is the section-parity worklist)
                if (pureOnly(src.text())) {
                    try {
                        com.legend.parser.ElementParser.parseStrict(src.text());
                        strictLenient++;
                        strictLenientIds.add(src.id() + " :: vanilla: "
                                + trim(t.getMessage()));
                    } catch (Throwable ignored) {
                        // strict agrees with vanilla — the honest outcome
                    }
                }
                try {
                    spi.parseModel(src.text());
                    asymmetric.add("SPI-ACCEPTS " + src.id() + " :: vanilla: "
                            + trim(t.getMessage()));
                } catch (Throwable expected) {
                    bothReject++;
                }
                continue;
            }
            String spiJson;
            try {
                spiJson = mapper.writeValueAsString(spi.parseModel(src.text()));
            } catch (Throwable t) {
                asymmetric.add("SPI-REJECTS " + src.id() + " :: " + trim(t.getMessage()));
                continue;
            }
            if (vanillaJson.equals(spiJson)) {
                match++;
            } else if (spiJson.equals(mapper.writeValueAsString(mapper.readTree(vanillaJson)
                    .traverse(mapper).readValueAs(PureModelContextData.class)))) {
                // the ENGINE'S OWN JSON round-trip (readTree -> protocol -> serialize)
                // produces OUR bytes: the delta is an engine serialize-only field (e.g.
                // ColSpec classInstance multiplicity), invisible to every JSON-first
                // consumer — attributed, not a bridge bug
                engineAsymmetry.add(src.id());
            } else {
                diffs.add(src.id() + " :: " + elementDelta(mapper, vanillaJson, spiJson));
            }
        }

        StringBuilder report = new StringBuilder();
        report.append("SPI SEAM PROOF — engine+legend-lite vs vanilla engine, full PMCD\n")
                .append("=".repeat(72)).append('\n')
                .append(String.format("files byte-identical  : %d%n", match))
                .append(String.format("both reject           : %d%n", bothReject))
                .append(String.format("engine JSON-asymmetry : %d%n", engineAsymmetry.size()))
                .append(String.format("DIFF (BUG)            : %d%n", diffs.size()))
                .append(String.format("asymmetric rejects    : %d%n", asymmetric.size()))
                .append(String.format("parseStrict lenient   : %d (Pure-only files vanilla rejects)%n",
                        strictLenient));
        report.append(String.format("  (SPI-ACCEPTS %d / SPI-REJECTS %d)%n",
                asymmetric.stream().filter(a -> a.startsWith("SPI-ACCEPTS")).count(),
                asymmetric.stream().filter(a -> a.startsWith("SPI-REJECTS")).count()));
        engineAsymmetry.stream().limit(20)
                .forEach(d -> report.append("  ENGINE-ASYM ").append(d).append('\n'));
        diffs.stream().limit(20).forEach(d -> report.append("  DIFF ").append(d).append('\n'));
        asymmetric.stream().filter(a -> a.startsWith("SPI-REJECTS")).limit(20)
                .forEach(d -> report.append("  ").append(d).append('\n'));
        asymmetric.stream().filter(a -> a.startsWith("SPI-ACCEPTS")).limit(200)
                .forEach(d -> report.append("  ").append(d).append('\n'));
        StringBuilder lense = new StringBuilder();
        strictLenientIds.forEach(d -> lense.append(d).append('\n'));
        Files.writeString(Path.of("target", "parser-leniency.txt"), lense.toString());
        Files.writeString(Path.of("target", "spi-seam-report.txt"), report.toString());
        System.out.println(report);

        assertTrue(match >= MIN_FILES_MATCHED,
                "seam coverage shrank: " + match + " < " + MIN_FILES_MATCHED);
        assertEquals(0, diffs.size(),
                "the SPI-backed engine diverged from vanilla — see target/spi-seam-report.txt");
        assertEquals(0, asymmetric.stream()
                        .filter(a -> a.startsWith("SPI-REJECTS")).count(),
                "SPI rejected a file vanilla accepts — see target/spi-seam-report.txt");
        assertTrue(asymmetric.size() <= MAX_LENIENT_ACCEPTS,
                "leniency census grew: " + asymmetric.size() + " > " + MAX_LENIENT_ACCEPTS
                        + " — see target/spi-seam-report.txt");
        assertTrue(engineAsymmetry.size() <= MAX_ENGINE_JSON_ASYMMETRY,
                "engine JSON-asymmetry bucket grew: " + engineAsymmetry.size()
                        + " — see target/spi-seam-report.txt");
        assertTrue(strictLenient <= MAX_PARSER_LENIENT_ACCEPTS,
                "parseStrict leniency census grew: " + strictLenient + " > "
                        + MAX_PARSER_LENIENT_ACCEPTS
                        + " — see target/parser-leniency.txt");
    }

    /** The census scope — vanilla-rejected files whose every section is Pure. */
    private static final Pattern SECTION = Pattern.compile("(?m)^###(\\w+)");

    private static boolean pureOnly(String text) {
        Matcher m = SECTION.matcher(text);
        while (m.find()) {
            if (!"Pure".equals(m.group(1))) {
                return false;
            }
        }
        return true;
    }

    /** Bumped deliberately as coverage grows.
     * 4,011 -> 4,051: the pureOnly gate is deleted — mixed-section files flow
     * through the seam (the production drop-in shape); most both-reject under
     * the extension-less vanilla baseline, +40 parse end-to-end. */
    private static final int MIN_FILES_MATCHED = 4051;

    /** The attributed SPI-ACCEPTS census — files VANILLA rejects that we parse. Every
     *  one is (a) m3-only dialect the engine grammar never supported ('Primitive',
     *  '^' instances, class projections that NPE the engine walker), (b) the engine's
     *  own 'allVersionsInRange ... is not supported' walker TODO, or (c) an artifact of
     *  the extension-LESS vanilla baseline ('Can't find an embedded Pure parser',
     *  'Unknown embedded data type') that a production engine classpath accepts. None
     *  can be stored through a vanilla engine, so accepting them cannot change drop-in
     *  behavior. Ratcheted DOWN only. */
    private static final int MAX_LENIENT_ACCEPTS = 170;

    /** THE PARSER'S OWN leniency bound (implementation audit §3.3) — files the
     *  extension-less vanilla engine rejects that raw {@code parseStrict}
     *  accepts. Distinct from {@code MAX_LENIENT_ACCEPTS}: that bounds the SPI
     *  BRIDGE (a site scanner that skips unrecognised tokens); this bounds the
     *  strict parser surface itself. Ratcheted DOWN only; the two converge as
     *  the bridge goes total. */
    private static final int MAX_PARSER_LENIENT_ACCEPTS = 743;
    // 742 -> 743 (2026-08-09, Measure element wired): m2m/tests/legend/
    // unitMeasure.pure — an engine PLATFORM source legend-pure compiles in
    // production — now parses to its Measure instead of dying there; the
    // vanilla engine still refuses the file at its 'Primitive' (the
    // engine-subsets-pure category, same as shared.pure beside it).

    /** Files where the delta is the engine's OWN serialize-only field (proven: the
     *  engine's readTree -> protocol -> serialize round-trip of ITS OWN output equals
     *  our bytes — e.g. ColSpec classInstance 'multiplicity', dropped by the engine's
     *  deserializer). Invisible to every JSON-first consumer. */
    private static final int MAX_ENGINE_JSON_ASYMMETRY = 8;

    private static String trim(String msg) {
        String m = String.valueOf(msg).replaceAll("\\s+", " ");
        return m.substring(0, Math.min(140, m.length()));
    }

    /** Attribute a whole-PMCD mismatch: missing/extra element paths first (alignment),
     *  then the first differing element, then the raw byte delta. */
    private static String elementDelta(ObjectMapper mapper, String vanillaJson,
            String spiJson) {
        try {
            var va = mapper.readTree(vanillaJson).get("elements");
            var sp = mapper.readTree(spiJson).get("elements");
            java.util.Map<String, String> vm = new java.util.LinkedHashMap<>();
            java.util.Map<String, String> sm = new java.util.LinkedHashMap<>();
            for (var e : va) {
                vm.put(e.get("package") + "::" + e.get("name"), e.toString());
            }
            for (var e : sp) {
                sm.put(e.get("package") + "::" + e.get("name"), e.toString());
            }
            var missing = new java.util.ArrayList<>(vm.keySet());
            missing.removeAll(sm.keySet());
            var extra = new java.util.ArrayList<>(sm.keySet());
            extra.removeAll(vm.keySet());
            if (!missing.isEmpty() || !extra.isEmpty()) {
                return "missing=" + missing.stream().limit(3).toList()
                        + " extra=" + extra.stream().limit(3).toList();
            }
            for (var k : vm.keySet()) {
                if (!vm.get(k).equals(sm.get(k))) {
                    return "element " + k + " :: "
                            + firstDelta(vm.get(k), sm.get(k));
                }
            }
            return "elements equal; delta elsewhere :: "
                    + firstDelta(vanillaJson, spiJson);
        } catch (Exception e) {
            return firstDelta(vanillaJson, spiJson);
        }
    }

    private static String firstDelta(String a, String b) {
        int n = Math.min(a.length(), b.length());
        for (int i = 0; i < n; i++) {
            if (a.charAt(i) != b.charAt(i)) {
                return "byte " + i + " | vanilla …"
                        + a.substring(Math.max(0, i - 40), Math.min(a.length(), i + 40))
                        + " | spi …"
                        + b.substring(Math.max(0, i - 40), Math.min(b.length(), i + 40));
            }
        }
        return "length " + a.length() + " vs " + b.length();
    }
}
