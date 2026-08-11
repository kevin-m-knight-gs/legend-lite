package com.legend.equivalence;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.finos.legend.engine.language.pure.grammar.from.PureGrammarParser;
import org.finos.legend.engine.protocol.pure.v1.extension.PureProtocolExtensionLoader;
import org.finos.legend.engine.shared.core.ObjectMapperFactory;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** PROBE (protocol-type census): the engine's own inventory of
 *  user-expressible constructs is its Jackson {@code _type} universe —
 *  extension-registered subtypes plus the annotated cores. Coverage is
 *  SEMANTIC: which tags actually occur in the oracle's serialized PMCD
 *  of accepted corpus sources. An unseen tag = a construct users can
 *  type that no parity layer has ever exercised. Diagnostic. */
class ZProtocolTypeCoverageProbe {

    @Test
    void protocolTypeCoverage() throws Exception {
        // ---- roster: extension-registered subtype tags ----
        Map<String, String> tagToOwner = new TreeMap<>();
        PureProtocolExtensionLoader.extensions().forEach(ext ->
                ext.getExtraProtocolSubTypeInfoCollectors().forEach(c ->
                        c.value().forEach(info -> info.getSubTypes().forEach(
                                p -> tagToOwner.putIfAbsent(p.getTwo(),
                                        info.getSuperType()
                                                .getSimpleName())))));
        // ---- roster: annotated core hierarchies ----
        for (String root : new String[]{
                "org.finos.legend.engine.protocol.pure.m3.PackageableElement",
                "org.finos.legend.engine.protocol.pure.m3.valuespecification.ValueSpecification",
                "org.finos.legend.engine.protocol.pure.v1.model.packageableElement.mapping.ClassMapping",
                "org.finos.legend.engine.protocol.pure.v1.model.packageableElement.connection.Connection",
                "org.finos.legend.engine.protocol.pure.v1.model.data.EmbeddedData",
                "org.finos.legend.engine.protocol.pure.v1.model.test.assertion.TestAssertion"}) {
            try {
                Class<?> cls = Class.forName(root);
                JsonSubTypes st = cls.getAnnotation(JsonSubTypes.class);
                if (st != null) {
                    for (JsonSubTypes.Type t : st.value()) {
                        tagToOwner.putIfAbsent(t.name(),
                                cls.getSimpleName());
                    }
                }
            } catch (ClassNotFoundException e) {
                System.out.println("@@ NO-CLASS " + root);
            }
        }
        System.out.println("@@ protocol tags rostered: " + tagToOwner.size());

        // ---- coverage: _type tags in the oracle's OWN serialization ----
        PureGrammarParser oracle = PureGrammarParser.newInstance();
        ObjectMapper mapper = ObjectMapperFactory
                .getNewStandardObjectMapperWithPureProtocolExtensionSupports();
        Pattern tag = Pattern.compile("\"_type\"\\s*:\\s*\"([^\"]+)\"");
        Set<String> seen = new TreeSet<>();
        int accepted = 0;
        for (Corpus.Source src : Corpus.all()) {
            String json;
            try {
                json = mapper.writeValueAsString(
                        oracle.parseModel(src.text()));
                accepted++;
            } catch (Throwable t) {
                continue;
            }
            Matcher m = tag.matcher(json);
            while (m.find()) {
                seen.add(m.group(1));
            }
        }
        System.out.println("@@ accepted sources: " + accepted
                + "; tags seen in corpus: " + seen.size());
        Map<String, java.util.List<String>> uncoveredByOwner = new TreeMap<>();
        int uncovered = 0;
        for (var e : tagToOwner.entrySet()) {
            if (!seen.contains(e.getKey())) {
                uncovered++;
                uncoveredByOwner.computeIfAbsent(e.getValue(),
                        k -> new java.util.ArrayList<>()).add(e.getKey());
            }
        }
        System.out.println("@@ covered: " + (tagToOwner.size() - uncovered)
                + "/" + tagToOwner.size());
        uncoveredByOwner.forEach((o, tags) -> System.out.println(
                "@@ UNCOVERED [" + o + "] " + String.join(", ", tags)));
        Set<String> unrostered = new TreeSet<>(seen);
        unrostered.removeAll(tagToOwner.keySet());
        System.out.println("@@ seen-but-unrostered (core annotations not in"
                + " walk): " + unrostered.size());
    }
}
