package com.legend.equivalence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.legend.lexer.Lexer;
import com.legend.parser.ElementParser;
import com.legend.protocol.Protocol;
import com.legend.protocol.ProtocolEmitter;
import org.finos.legend.engine.language.pure.grammar.from.PureGrammarParser;
import org.finos.legend.engine.protocol.pure.m3.PackageableElement;
import org.finos.legend.engine.protocol.pure.v1.model.context.PureModelContextData;
import org.finos.legend.engine.shared.core.ObjectMapperFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The differential comparator: legend-lite's parser and legend-engine's over the same input,
 * compared on <b>emitted bytes</b>.
 *
 * <h2>Why bytes and not object graphs</h2>
 * Upstream's {@code CString.multiLine} is excluded from {@code equals()} and present in JSON when
 * true. An object-graph comparator would silently miss it. Every verdict here is
 * {@code String.equals} on serialised JSON, produced by
 * {@code getNewStandardObjectMapperWithPureProtocolExtensionSupports()} — the mapper the HTTP
 * endpoint uses. The repo's other mapper is non-deterministic (no sorting, nulls included); using
 * it would silently measure nothing.
 *
 * <h2>Why per element</h2>
 * legend-lite cannot yet emit every element kind. Comparing whole files would collapse to
 * "unsupported" almost everywhere and tell us nothing. Comparing per element by FQN yields a real
 * verdict for what we do support and a <b>named wall</b> for what we do not — which is what turns
 * the corpus into a ranked worklist instead of a pass/fail bit.
 */
public final class ParserEquivalence {

    /** What happened to one element. */
    public enum Kind {
        /** Byte-identical. */
        MATCH,
        /** Both produced bytes; they differ. The only outcome that is a bug. */
        DIFF,
        /** We refused to emit, loudly and by name. Expected while coverage grows. */
        WALL,
        /** Our parser could not read it. */
        PARSE_FAIL,
        /** The reference parser rejected the input, so there is nothing to compare against. */
        REFERENCE_REJECTED
    }

    public record Verdict(Kind kind, String sourceId, String element, String detail) {
    }

    private static final Pattern SECTION = Pattern.compile("(?m)^###(\\w+)");

    private final ObjectMapper mapper =
            ObjectMapperFactory.getNewStandardObjectMapperWithPureProtocolExtensionSupports();
    private final PureGrammarParser reference = PureGrammarParser.newInstance();

    /**
     * Compare one source. Today this covers {@code Class} declarations in Pure-only sources; the
     * shape generalises as more element kinds gain emitters.
     */
    public List<Verdict> compare(Corpus.Source src) {
        List<Verdict> out = new ArrayList<>();
        Matcher m = SECTION.matcher(src.text());
        boolean pureOnly = true;
        while (m.find()) {
            if (!"Pure".equals(m.group(1))) {
                pureOnly = false;
                break;
            }
        }
        if (!pureOnly) {
            return out;                          // other sections have no emitter yet — not a wall, just out of scope
        }

        Map<String, String> referenceBytes = referenceElements(src);
        if (referenceBytes == null) {
            return out;                          // reference could not read it either; §see REFERENCE_REJECTED below
        }

        // Parse the WHOLE file so source information is file-absolute, then position the parser
        // at each top-level `Class` token. Parsing isolated chunks restarts line numbers at 1 —
        // a harness artefact that presents as a parser bug.
        com.legend.lexer.TokenStream ts = Lexer.tokenize(src.text());
        for (int i : ElementParser.topLevelIndexes(ts, com.legend.lexer.TokenType.CLASS)) {
            Protocol.PClass cls;
            try {
                cls = ElementParser.at(ts, i).parseClassDefinition(false);
            } catch (Throwable t) {
                out.add(new Verdict(Kind.PARSE_FAIL, src.id(), "?", root(t)));
                continue;
            }
            String fqn = cls.qualifiedName();
            String expected = referenceBytes.get(fqn);
            if (expected == null) {
                out.add(new Verdict(Kind.REFERENCE_REJECTED, src.id(), fqn, "no reference element"));
                continue;
            }
            String actual;
            try {
                actual = ProtocolEmitter.emitElement(cls);
            } catch (Throwable t) {
                out.add(new Verdict(Kind.WALL, src.id(), fqn, root(t)));
                continue;
            }
            out.add(expected.equals(actual)
                    ? new Verdict(Kind.MATCH, src.id(), fqn, "")
                    : new Verdict(Kind.DIFF, src.id(), fqn, firstDivergence(expected, actual)));
        }
        return out;
    }

    /** The reference parser's elements, serialised individually and keyed by FQN. */
    private Map<String, String> referenceElements(Corpus.Source src) {
        try {
            PureModelContextData pmcd = reference.parseModel(src.text());
            Map<String, String> byFqn = new LinkedHashMap<>();
            for (PackageableElement e : pmcd.getElements()) {
                byFqn.put(e.getPath(), mapper.writeValueAsString(e));
            }
            return byFqn;
        } catch (Throwable t) {
            return null;
        }
    }

    /** A diff message that names the exact JSON path, not just "differs". */
    private String firstDivergence(String expected, String actual) {
        try {
            JsonNode a = mapper.readTree(expected);
            JsonNode b = mapper.readTree(actual);
            List<String> paths = new ArrayList<>();
            walk("$", a, b, paths);
            if (!paths.isEmpty()) {
                return paths.get(0);
            }
        } catch (Exception ignored) {
            // fall through to a character offset
        }
        int n = Math.min(expected.length(), actual.length());
        int i = 0;
        while (i < n && expected.charAt(i) == actual.charAt(i)) {
            i++;
        }
        return "byte " + i + " | expected …" + snippet(expected, i) + " | actual …" + snippet(actual, i);
    }

    private static String snippet(String s, int i) {
        return s.substring(Math.max(0, i - 20), Math.min(s.length(), i + 40));
    }

    private static void walk(String path, JsonNode a, JsonNode b, List<String> out) {
        if (!out.isEmpty()) {
            return;
        }
        if (a == null || b == null || !a.getNodeType().equals(b.getNodeType())) {
            out.add(path + ": expected=" + a + " actual=" + b);
            return;
        }
        if (a.isObject()) {
            java.util.Set<String> keys = new java.util.TreeSet<>();
            a.fieldNames().forEachRemaining(keys::add);
            b.fieldNames().forEachRemaining(keys::add);
            for (String k : keys) {
                walk(path + "." + k, a.get(k), b.get(k), out);
            }
        } else if (a.isArray()) {
            if (a.size() != b.size()) {
                out.add(path + ": size expected=" + a.size() + " actual=" + b.size());
                return;
            }
            for (int i = 0; i < a.size(); i++) {
                walk(path + "[" + i + "]", a.get(i), b.get(i), out);
            }
        } else if (!a.equals(b)) {
            out.add(path + ": expected=" + a + " actual=" + b);
        }
    }

    private static String root(Throwable t) {
        Throwable r = t;
        while (r.getCause() != null) {
            r = r.getCause();
        }
        return String.valueOf(r.getMessage()).replaceAll("\\s+", " ").trim();
    }

    /**
     * The wall message with its element name stripped, so the report ranks by RULE.
     * Grouping on the raw message fragments one missing rule across hundreds of element names and
     * buries the actual worklist.
     */
    public static String rule(String detail) {
        return detail.replaceAll("\\(at [^)]*\\)\\s*", "").replaceAll("\\s+", " ").trim();
    }
}
