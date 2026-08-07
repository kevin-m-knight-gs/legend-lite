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
        /** The reference parser threw on the WHOLE source — one verdict per
         * file, so the 25% silently-skipped bucket is counted, not invisible
         * (implementation audit §3.1). */
        REFERENCE_REJECTED,
        /** We produced an element the reference did not — the old, misnamed
         * "REFERENCE_REJECTED" arm. */
        LITE_EXTRA,
        /** The reference produced an element we never compared — the
         * one-directional hole (implementation audit §3.2). Must be zero. */
        LITE_MISSED,
        /** A reference element in a section we do not claim yet — the
         * closed-world row (grammar-compat §8): named, counted, and the
         * section-parity worklist. */
        OUT_OF_SCOPE
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
        // The pureOnly gate is DEAD (implementation audit §3.1 / grammar-
        // compat Phase A): a mixed-section file's ###Pure elements compare
        // like any other; non-Pure sections classify at the DRAIN as
        // OUT_OF_SCOPE rows, never silence. Pure CHAR RANGES scope our
        // site discovery (the lexer consumes section headers, so ranges
        // come from the same line-anchored ### rule it applies).
        List<long[]> pureRanges = sectionRanges(src.text(), "Pure", true);
        List<long[]> runtimeRanges = sectionRanges(src.text(), "Runtime", false);
        List<long[]> connectionRanges = sectionRanges(src.text(), "Connection", false);
        List<long[]> relationalRanges = sectionRanges(src.text(), "Relational", false);
        List<long[]> mappingRanges = sectionRanges(src.text(), "Mapping", false);

        Ref ref;
        try {
            ref = referenceElements(src);
        } catch (Throwable t) {
            // ONE named verdict per rejected file — silence is not evidence
            out.add(new Verdict(Kind.REFERENCE_REJECTED, src.id(), "-", root(t)));
            return out;
        }
        Map<String, List<String>> referenceBytes = ref.byFqn();

        // Parse the WHOLE file so source information is file-absolute, then position the parser
        // at each top-level `Class` token. Parsing isolated chunks restarts line numbers at 1 —
        // a harness artefact that presents as a parser bug.
        com.legend.lexer.TokenStream ts = Lexer.tokenize(src.text());
        java.util.List<int[]> sites = pureSites(ts, pureRanges);
        sites.addAll(runtimeSites(ts, runtimeRanges));
        sites.addAll(connectionSites(ts, connectionRanges));
        sites.addAll(markerSites(ts, relationalRanges,
                com.legend.lexer.TokenType.DATABASE, 8));
        sites.addAll(markerSites(ts, mappingRanges,
                com.legend.lexer.TokenType.MAPPING, 9));
        boolean runtimeWalled = false;
        boolean connectionWalled = false;
        boolean relationalWalled = false;
        boolean mappingWalled = false;
        for (int[] site : sites) {
            Protocol.Element el;
            String fqn;
            try {
                if (site[1] == 0) {
                    Protocol.PClass cls = ElementParser.at(ts, site[0]).parseClassDefinition(false);
                    el = cls;
                    fqn = cls.qualifiedName();
                } else if (site[1] == 1) {
                    Protocol.PEnumeration en = ElementParser.at(ts, site[0]).parseEnumDefinition();
                    el = en;
                    fqn = en.qualifiedName();
                } else if (site[1] == 2) {
                    Protocol.PProfile pr = ElementParser.at(ts, site[0]).parseProfileDefinition();
                    el = pr;
                    fqn = pr.qualifiedName();
                } else if (site[1] == 3) {
                    Protocol.PAssociation a = ElementParser.at(ts, site[0]).parseAssociationDefinition();
                    el = a;
                    fqn = a.qualifiedName();
                } else if (site[1] == 5) {
                    Protocol.PMeasure me = ElementParser.at(ts, site[0]).parseMeasureDefinition();
                    el = me;
                    fqn = me.qualifiedName();
                } else if (site[1] == 6) {
                    Protocol.PRuntime rt = ElementParser.at(ts, site[0]).parseRuntimeProtocol();
                    el = rt;
                    fqn = rt.qualifiedName();
                } else if (site[1] == 7) {
                    Protocol.PConnection cn = ElementParser.at(ts, site[0]).parseConnectionProtocol();
                    el = cn;
                    fqn = cn.qualifiedName();
                } else if (site[1] == 8) {
                    Protocol.PDatabase db = com.legend.parser
                            .DatabaseProtocolParser.parse(ts, site[0]);
                    el = db;
                    fqn = db.qualifiedName();
                } else if (site[1] == 9) {
                    Protocol.PMapping mp = com.legend.parser
                            .MappingProtocolParser.parse(ts, site[0]);
                    el = mp;
                    fqn = mp.qualifiedName();
                } else {
                    Protocol.PFunction fn = ElementParser.at(ts, site[0]).parseFunctionProtocol();
                    el = fn;
                    // the reference keys functions by their MANGLED path
                    fqn = fn.pkg().isEmpty() ? fn.mangledName()
                            : fn.pkg() + "::" + fn.mangledName();
                }
            } catch (Throwable t) {
                // a RUNTIME site refusing an unbuilt sub-grammar (embedded
                // RelationalDatabaseConnection, Connection-leg territory) is
                // a WALL — the section-parity worklist — not a parse bug;
                // its reference elements drain as WALL rows too
                if (site[1] == 6) {
                    runtimeWalled = true;
                    out.add(new Verdict(Kind.WALL, src.id(), "?",
                            "runtime: " + root(t)));
                } else if (site[1] == 7) {
                    connectionWalled = true;
                    out.add(new Verdict(Kind.WALL, src.id(), "?",
                            "connection: " + root(t)));
                } else if (site[1] == 8) {
                    relationalWalled = true;
                    out.add(new Verdict(Kind.WALL, src.id(), "?",
                            "relational: " + root(t)));
                } else if (site[1] == 9) {
                    mappingWalled = true;
                    out.add(new Verdict(Kind.WALL, src.id(), "?",
                            "mapping: " + root(t)));
                } else {
                    out.add(new Verdict(Kind.PARSE_FAIL, src.id(), "?", root(t)));
                }
                continue;
            }
            List<String> queue = referenceBytes.get(fqn);
            // the engine allows DUPLICATE paths across sections (a Class and a
            // Mapping both named X) — dequeue the entry of OUR element's kind,
            // falling back to the head so a real kind mismatch still DIFFs
            String expected = null;
            if (queue != null && !queue.isEmpty()) {
                String prefix = "{\"_type\":\""
                        + new String[]{"class", "Enumeration", "profile",
                                "association", "function", "measure",
                                "runtime", "connection", "relational",
                                "mapping"}[site[1]]
                        + "\"";
                int pick = 0;
                for (int q = 0; q < queue.size(); q++) {
                    if (queue.get(q).startsWith(prefix)) {
                        pick = q;
                        break;
                    }
                }
                expected = queue.remove(pick);
            }
            if (expected == null) {
                out.add(new Verdict(Kind.LITE_EXTRA, src.id(), fqn, "no reference element"));
                continue;
            }
            String actual;
            try {
                actual = ProtocolEmitter.emitElement(el);
            } catch (Throwable t) {
                out.add(new Verdict(Kind.WALL, src.id(), fqn, root(t)));
                continue;
            }
            out.add(expected.equals(actual)
                    ? new Verdict(Kind.MATCH, src.id(), fqn, "")
                    : new Verdict(Kind.DIFF, src.id(), fqn, firstDivergence(expected, actual)));
        }
        // DRAIN: every reference element we never looked up is a named row —
        // the comparison was one-directional and this is the other direction.
        // An element the SectionIndex places in an unclaimed section is the
        // section-parity worklist (OUT_OF_SCOPE); a PURE element left over
        // is a front-door disagreement (LITE_MISSED, asserted zero).
        for (Map.Entry<String, List<String>> e : referenceBytes.entrySet()) {
            for (String leftover : e.getValue()) {
                // the leftover's OWN _type decides Pure-ness — the SectionIndex
                // path->section map is ambiguous when the engine allows the
                // same path in two sections (Class X + Mapping X)
                boolean pureKind = false;
                for (String t : new String[]{"class", "Enumeration", "profile",
                        "association", "function", "measure", "runtime",
                        "connection", "relational", "mapping"}) {
                    if (leftover.startsWith("{\"_type\":\"" + t + "\"")) {
                        pureKind = true;
                        break;
                    }
                }
                if (pureKind && runtimeWalled
                        && leftover.startsWith("{\"_type\":\"runtime\"")) {
                    out.add(new Verdict(Kind.WALL, src.id(), e.getKey(),
                            "runtime: unbuilt sub-grammar (walled site)"));
                    continue;
                }
                if (pureKind && connectionWalled
                        && leftover.startsWith("{\"_type\":\"connection\"")) {
                    out.add(new Verdict(Kind.WALL, src.id(), e.getKey(),
                            "connection: unbuilt sub-grammar (walled site)"));
                    continue;
                }
                if (pureKind && relationalWalled
                        && leftover.startsWith("{\"_type\":\"relational\"")) {
                    out.add(new Verdict(Kind.WALL, src.id(), e.getKey(),
                            "relational: unbuilt sub-grammar (walled site)"));
                    continue;
                }
                if (pureKind && mappingWalled
                        && leftover.startsWith("{\"_type\":\"mapping\"")) {
                    out.add(new Verdict(Kind.WALL, src.id(), e.getKey(),
                            "mapping: unbuilt sub-grammar (walled site)"));
                    continue;
                }
                String sec = ref.sectionOf().getOrDefault(e.getKey(), "?");
                if (!pureKind && "Pure".equals(sec)) {
                    // duplicate-path collision (Class X + Mapping X): the
                    // path->section map answered for the Pure twin — label
                    // the row by the element's own _type instead
                    int t0 = leftover.indexOf('"', 10) + 1;
                    sec = "_type " + leftover.substring(t0, leftover.indexOf('"', t0));
                }
                out.add(pureKind
                        ? new Verdict(Kind.LITE_MISSED, src.id(), e.getKey(),
                                "reference element never compared")
                        : new Verdict(Kind.OUT_OF_SCOPE, src.id(), e.getKey(), sec));
            }
        }
        return out;
    }


    /** {@code {marker token type -> site parse kind}} for {@link #pureSites}. */
    private static final Map<com.legend.lexer.TokenType, Integer> MARKERS = Map.of(
            com.legend.lexer.TokenType.CLASS, 0,
            com.legend.lexer.TokenType.ENUM, 1,
            com.legend.lexer.TokenType.PROFILE, 2,
            com.legend.lexer.TokenType.ASSOCIATION, 3,
            com.legend.lexer.TokenType.FUNCTION, 4);

    /** Declaration sites scanned PER Pure range — the section-aware form of
     * {@code ElementParser.topLevelIndexes}/{@code measureSites}. Each range
     * opens at depth 0 with its first token a declaration position, so a
     * {@code ###Pure} that follows a Relational {@code )} still yields its
     * elements, and bracket depth from other sections cannot leak in. */
    private static java.util.List<int[]> pureSites(
            com.legend.lexer.TokenStream ts, List<long[]> ranges) {
        java.util.List<int[]> sites = new ArrayList<>();
        int cursor = 0;
        for (long[] r : ranges) {
            while (cursor < ts.count() && ts.start(cursor) < r[0]) {
                cursor++;
            }
            int depth = 0;
            boolean declPos = true;
            for (; cursor < ts.count() && ts.start(cursor) < r[1]; cursor++) {
                com.legend.lexer.TokenType t = ts.type(cursor);
                switch (t) {
                    case BRACE_OPEN, BRACKET_OPEN, PAREN_OPEN -> depth++;
                    case BRACE_CLOSE, BRACKET_CLOSE, PAREN_CLOSE -> depth--;
                    default -> {
                        Integer kind = MARKERS.get(t);
                        if (depth == 0 && declPos && kind != null
                                && (cursor + 1 >= ts.count() || ts.type(cursor + 1)
                                        != com.legend.lexer.TokenType.PATH_SEPARATOR)) {
                            sites.add(new int[]{cursor, kind});
                        } else if (depth == 0 && declPos
                                && t == com.legend.lexer.TokenType.VALID_STRING
                                && "Measure".equals(ts.text(cursor))) {
                            sites.add(new int[]{cursor, 5});
                        }
                    }
                }
                declPos = t == com.legend.lexer.TokenType.BRACE_CLOSE
                        || t == com.legend.lexer.TokenType.SEMI_COLON;
            }
        }
        return sites;
    }

    /** Line-anchored {@code ###} header scan — the lexer's own rule — as
     * char ranges of {@code section} bodies. {@code implicitPreamble}: text
     * before any header belongs to the default section (Pure). */
    private static List<long[]> sectionRanges(String text, String section,
            boolean implicitPreamble) {
        List<long[]> ranges = new ArrayList<>();
        long start = 0;
        boolean in = implicitPreamble;
        Matcher m = SECTION.matcher(text);
        while (m.find()) {
            if (in) {
                ranges.add(new long[]{start, m.start()});
            }
            in = section.equals(m.group(1));
            start = m.end();
        }
        if (in) {
            ranges.add(new long[]{start, text.length()});
        }
        return ranges;
    }

    /** Single-marker section sites at declaration positions. */
    private static java.util.List<int[]> markerSites(
            com.legend.lexer.TokenStream ts, List<long[]> ranges,
            com.legend.lexer.TokenType marker, int kind) {
        java.util.List<int[]> sites = new ArrayList<>();
        int cursor = 0;
        for (long[] r : ranges) {
            while (cursor < ts.count() && ts.start(cursor) < r[0]) {
                cursor++;
            }
            int depth = 0;
            boolean declPos = true;
            for (; cursor < ts.count() && ts.start(cursor) < r[1]; cursor++) {
                com.legend.lexer.TokenType t = ts.type(cursor);
                switch (t) {
                    case BRACE_OPEN, BRACKET_OPEN, PAREN_OPEN -> depth++;
                    case BRACE_CLOSE, BRACKET_CLOSE, PAREN_CLOSE -> depth--;
                    default -> {
                        if (depth == 0 && declPos && t == marker) {
                            sites.add(new int[]{cursor, kind});
                        }
                    }
                }
                declPos = t == com.legend.lexer.TokenType.BRACE_CLOSE
                        || t == com.legend.lexer.TokenType.SEMI_COLON
                        || t == com.legend.lexer.TokenType.PAREN_CLOSE;
            }
        }
        return sites;
    }

    /** Connection-section declaration sites: kind 7 — flavor keywords lex
     *  as identifiers (except RelationalDatabaseConnection's own token), so
     *  match by TEXT at declaration positions. */
    private static java.util.List<int[]> connectionSites(
            com.legend.lexer.TokenStream ts, List<long[]> ranges) {
        java.util.List<int[]> sites = new ArrayList<>();
        java.util.Set<String> flavors = java.util.Set.of("JsonModelConnection",
                "XmlModelConnection", "ModelChainConnection",
                "RelationalDatabaseConnection");
        int cursor = 0;
        for (long[] r : ranges) {
            while (cursor < ts.count() && ts.start(cursor) < r[0]) {
                cursor++;
            }
            int depth = 0;
            boolean declPos = true;
            for (; cursor < ts.count() && ts.start(cursor) < r[1]; cursor++) {
                com.legend.lexer.TokenType t = ts.type(cursor);
                switch (t) {
                    case BRACE_OPEN, BRACKET_OPEN, PAREN_OPEN -> depth++;
                    case BRACE_CLOSE, BRACKET_CLOSE, PAREN_CLOSE -> depth--;
                    default -> {
                        if (depth == 0 && declPos
                                && flavors.contains(ts.text(cursor))) {
                            sites.add(new int[]{cursor, 7});
                        }
                    }
                }
                declPos = t == com.legend.lexer.TokenType.BRACE_CLOSE
                        || t == com.legend.lexer.TokenType.SEMI_COLON;
            }
        }
        return sites;
    }

    /** Runtime-section declaration sites: kind 6, same per-range depth
     *  discipline as {@link #pureSites}. */
    private static java.util.List<int[]> runtimeSites(
            com.legend.lexer.TokenStream ts, List<long[]> ranges) {
        java.util.List<int[]> sites = new ArrayList<>();
        int cursor = 0;
        for (long[] r : ranges) {
            while (cursor < ts.count() && ts.start(cursor) < r[0]) {
                cursor++;
            }
            int depth = 0;
            boolean declPos = true;
            for (; cursor < ts.count() && ts.start(cursor) < r[1]; cursor++) {
                com.legend.lexer.TokenType t = ts.type(cursor);
                switch (t) {
                    case BRACE_OPEN, BRACKET_OPEN, PAREN_OPEN -> depth++;
                    case BRACE_CLOSE, BRACKET_CLOSE, PAREN_CLOSE -> depth--;
                    default -> {
                        if (depth == 0 && declPos
                                && (t == com.legend.lexer.TokenType.RUNTIME
                                        || t == com.legend.lexer.TokenType
                                                .SINGLE_CONNECTION_RUNTIME)) {
                            sites.add(new int[]{cursor, 6});
                        }
                    }
                }
                declPos = t == com.legend.lexer.TokenType.BRACE_CLOSE
                        || t == com.legend.lexer.TokenType.SEMI_COLON;
            }
        }
        return sites;
    }

    private static boolean inRanges(List<long[]> ranges, long offset) {
        for (long[] r : ranges) {
            if (offset >= r[0] && offset < r[1]) {
                return true;
            }
        }
        return false;
    }

    /** The reference parser's elements, serialised individually and keyed by FQN. The value
     *  is a LIST: a source may declare the same FQN twice (the SameElementInSameNamespace
     *  test family) and sites pair with reference elements in source order. */
    /** The reference's comparable elements plus, from the SectionIndex it
     * is excluded for being metadata, each element's SECTION parser name —
     * the drain's Pure/OUT_OF_SCOPE classifier. */
    private record Ref(Map<String, List<String>> byFqn,
                       Map<String, String> sectionOf) {
    }

    private Ref referenceElements(Corpus.Source src) throws Exception {
        PureModelContextData pmcd = reference.parseModel(src.text());
        Map<String, List<String>> byFqn = new LinkedHashMap<>();
        Map<String, String> sectionOf = new LinkedHashMap<>();
        for (PackageableElement e : pmcd.getElements()) {
            if (e instanceof org.finos.legend.engine.protocol.pure.v1.model
                    .packageableElement.section.SectionIndex si) {
                for (var sec : si.sections) {
                    for (String path : sec.elements) {
                        sectionOf.put(path, sec.parserName);
                    }
                }
                continue;
            }
            byFqn.computeIfAbsent(e.getPath(), k -> new ArrayList<>())
                    .add(mapper.writeValueAsString(e));
        }
        return new Ref(byFqn, sectionOf);
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
