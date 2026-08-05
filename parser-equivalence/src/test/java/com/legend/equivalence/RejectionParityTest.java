package com.legend.equivalence;

import org.finos.legend.engine.language.pure.grammar.from.PureGrammarParser;
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
 * The NEGATIVE corpus: upstream test files pin inputs the engine parser REJECTS
 * ({@code test(code, "PARSER error at [l:c]: ...")}). A drop-in must reject them too —
 * accepting an input the engine refuses is a silent divergence no positive corpus can
 * catch. Messages are engine-shaped and deliberately NOT compared; the LINE of the error
 * is compared informationally (reported, not gated — column conventions differ).
 *
 * <p>Pins pair by adjacency: a literal run spelling {@code PARSER error at [l:c]}
 * pairs with the run before it in the same file — the extractor's coverage numbers keep
 * the pairing honest. Pins whose input the CURRENT engine accepts (stale pins) are
 * counted and skipped, never silently dropped.
 */
class RejectionParityTest {

    private record Pin(String id, String input, int line, int col) {
    }

    private static final Pattern ERROR_PIN =
            Pattern.compile("^PARSER error at \\[(\\d+):(\\d+)(?:-[0-9:]+)?\\]");

    /** Sections the Pure-only filter admits — mirrors ParserEquivalence.compare. */
    private static final Pattern SECTION = Pattern.compile("(?m)^###(\\w+)");

    @Test
    void everyEngineRejectedInputIsRejectedHereToo() throws Exception {
        List<Pin> pins = extractPins();
        PureGrammarParser reference = PureGrammarParser.newInstance();

        int engineAccepts = 0;
        int rejectMatch = 0;
        int lineMatch = 0;
        List<String> misses = new ArrayList<>();
        for (Pin p : pins) {
            try {
                reference.parseModel(p.input());
                engineAccepts++;                    // stale pin — current engine accepts
                continue;
            } catch (Throwable expected) {
                // the pin holds: the engine rejects
            }
            try {
                parseStrict(p.input());
                misses.add(p.id() + " [expected error at " + p.line() + ":" + p.col() + "]");
            } catch (Throwable t) {
                rejectMatch++;
                String m = String.valueOf(t.getMessage());
                Matcher pos = Pattern.compile("\\[(\\d+):(\\d+)\\]").matcher(m);
                if (pos.find() && Integer.parseInt(pos.group(1)) == p.line()) {
                    lineMatch++;
                }
            }
        }

        StringBuilder report = new StringBuilder();
        report.append("REJECTION PARITY — inputs the engine parser refuses\n")
                .append("=".repeat(72)).append('\n')
                .append(String.format("error pins extracted  : %d%n", pins.size()))
                .append(String.format("stale (engine accepts): %d%n", engineAccepts))
                .append(String.format("REJECT_MATCH          : %d%n", rejectMatch))
                .append(String.format("REJECT_MISS (BUG)     : %d%n", misses.size()))
                .append(String.format("error-line agreement  : %d of %d%n", lineMatch, rejectMatch));
        if (!misses.isEmpty()) {
            report.append("\nMISSES — we accept what the engine refuses\n")
                    .append("-".repeat(72)).append('\n');
            misses.stream().limit(40).forEach(m -> report.append("  ").append(m).append('\n'));
        }
        Files.writeString(Path.of("target", "rejection-report.txt"), report.toString());
        System.out.println(report);

        assertTrue(pins.size() >= MIN_PINS,
                "negative corpus shrank: " + pins.size() + " pins < baseline " + MIN_PINS);
        assertEquals(0, misses.size(),
                "inputs the engine parser rejects were ACCEPTED here:\n"
                        + String.join("\n", misses.subList(0, Math.min(10, misses.size()))));
    }

    /** The STRICT drop-in surface: the full parse plus every element site through the
     *  same {@code ElementParser.at} path the byte-comparison uses (protocol-only
     *  constructs like test suites live there). */
    private static void parseStrict(String text) {
        com.legend.parser.ElementParser.parseStrict(text);
        var ts = com.legend.lexer.Lexer.tokenize(text);
        for (com.legend.lexer.TokenType marker : new com.legend.lexer.TokenType[]{
                com.legend.lexer.TokenType.CLASS, com.legend.lexer.TokenType.ENUM,
                com.legend.lexer.TokenType.PROFILE, com.legend.lexer.TokenType.ASSOCIATION,
                com.legend.lexer.TokenType.FUNCTION}) {
            for (int i : com.legend.parser.ElementParser.topLevelIndexes(ts, marker)) {
                var p = com.legend.parser.ElementParser.at(ts, i);
                switch (marker) {
                    case CLASS -> p.parseClassDefinition(false);
                    case ENUM -> p.parseEnumDefinition();
                    case PROFILE -> p.parseProfileDefinition();
                    case ASSOCIATION -> p.parseAssociationDefinition();
                    default -> p.parseFunctionProtocol();
                }
            }
        }
    }

    /** Bumped deliberately as extraction improves. Lowering it requires saying why. */
    private static final int MIN_PINS = 30;

    private List<Pin> extractPins() {
        List<Pin> pins = new ArrayList<>();
        for (Path root : new Path[]{Corpus.engineRoot(), Corpus.pureRoot()}) {
            for (InlineSnippets.FileRuns fr : InlineSnippets.literalRunsByFile(root)) {
                List<String> runs = fr.runs();
                for (int i = 1; i < runs.size(); i++) {
                    Matcher m = ERROR_PIN.matcher(runs.get(i));
                    if (!m.find()) {
                        continue;
                    }
                    String input = runs.get(i - 1);
                    if (input.length() < 10) {
                        continue;                   // not a code snippet
                    }
                    // Pure-only inputs: a broken Mapping section is invisible to a
                    // parser that lexes non-Pure sections opaquely — no parity claim
                    Matcher s = SECTION.matcher(input);
                    boolean pureOnly = true;
                    while (s.find()) {
                        if (!"Pure".equals(s.group(1))) {
                            pureOnly = false;
                            break;
                        }
                    }
                    if (!pureOnly) {
                        continue;
                    }
                    pins.add(new Pin(fr.id() + "#" + i, input,
                            Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))));
                }
            }
        }
        return pins;
    }
}
