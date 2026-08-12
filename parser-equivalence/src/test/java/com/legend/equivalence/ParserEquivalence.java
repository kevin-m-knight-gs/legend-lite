package com.legend.equivalence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.finos.legend.engine.protocol.pure.m3.PackageableElement;
import org.finos.legend.engine.protocol.pure.v1.model.context.PureModelContextData;
import org.finos.legend.engine.shared.core.ObjectMapperFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    private final ObjectMapper mapper =
            ObjectMapperFactory.getNewStandardObjectMapperWithPureProtocolExtensionSupports();

    /**
     * Compare one source: the ORACLE's elements against the elements of
     * legend-lite's PRODUCTION document parser — {@code
     * PmcdParser.parseSections}, the same code {@code parseDocument} runs.
     * The harness enumerates nothing itself (HARNESS_SIMPLIFICATION_PLAN
     * Phase 1: the old ~350-line site scanner ran DIFFERENT lite code than
     * production, with demonstrated divergence — the measuring instrument
     * was more permissive than the thing measured).
     */
    public List<Verdict> compare(Corpus.Source src) {
        List<Verdict> out = new ArrayList<>();
        Ref ref;
        try {
            ref = referenceElements(src);
        } catch (Throwable t) {
            // ONE named verdict per rejected file — silence is not evidence
            out.add(new Verdict(Kind.REFERENCE_REJECTED, src.id(), "-", root(t)));
            return out;
        }
        Map<String, List<String>> referenceBytes = ref.byFqn();
        List<com.legend.parser.PmcdParser.DocSection> sections;
        try {
            sections = com.legend.parser.PmcdParser.parseSections(src.text());
        } catch (Throwable t) {
            out.add(new Verdict(Kind.PARSE_FAIL, src.id(), "-", root(t)));
            return out;
        }
        for (com.legend.parser.PmcdParser.DocSection sec : sections) {
            for (com.legend.parser.PmcdParser.DocElement el : sec.elements()) {
                String fqn = el.path();
                String actual = el.json();
                List<String> queue = referenceBytes.get(fqn);
                // the engine allows DUPLICATE paths across sections (a Class
                // and a Mapping both named X) — dequeue the entry of OUR
                // element's wire _type, falling back to the head so a real
                // kind mismatch still DIFFs
                String expected = null;
                if (queue != null && !queue.isEmpty()) {
                    int tq = actual.indexOf('"', 10);
                    String prefix = tq > 0 ? actual.substring(0, tq + 1) : actual;
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
                    out.add(new Verdict(Kind.LITE_EXTRA, src.id(), fqn,
                            "no reference element"));
                    continue;
                }
                out.add(Comparators.sameBytes(expected, actual)
                        ? new Verdict(Kind.MATCH, src.id(), fqn, "")
                        : new Verdict(Kind.DIFF, src.id(), fqn,
                                firstDivergence(expected, actual)));
            }
        }
        // DRAIN: every reference element we never produced is a named row —
        // the production parser claims EVERY section, so a leftover is a
        // real front-door disagreement, not a scope gap.
        for (Map.Entry<String, List<String>> e : referenceBytes.entrySet()) {
            for (String leftover : e.getValue()) {
                out.add(new Verdict(Kind.LITE_MISSED, src.id(), e.getKey(),
                        "reference element never compared ("
                                + ref.sectionOf().getOrDefault(e.getKey(), "?")
                                + ")"));
            }
        }
        return out;
    }

    private record Ref(Map<String, List<String>> byFqn,
                       Map<String, String> sectionOf) {
    }

    private Ref referenceElements(Corpus.Source src) throws Exception {
        // shared with the whole-document sweep — ONE oracle parse per source
        PureModelContextData pmcd;
        try {
            pmcd = OracleParses.acquire(src);
        } catch (Exception | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
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
