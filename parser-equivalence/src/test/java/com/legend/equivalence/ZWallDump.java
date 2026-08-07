package com.legend.equivalence;

import org.junit.jupiter.api.Test;

/** Dump walled snippets by source id (diagnostic). */
class ZWallDump {

    @Test
    void dump() {
        java.util.Map<String, java.util.List<ParserEquivalence.Verdict>> byMsg =
                new java.util.TreeMap<>();
        java.util.Map<String, Corpus.Source> srcById = new java.util.HashMap<>();
        ParserEquivalence eq = new ParserEquivalence();
        for (Corpus.Source s : Corpus.all()) {
            srcById.put(s.id(), s);
            for (ParserEquivalence.Verdict v : eq.compare(s)) {
                if (v.kind() == ParserEquivalence.Kind.WALL
                        && v.detail().startsWith("mapping: [")) {
                    byMsg.computeIfAbsent(v.detail(), k -> new java.util.ArrayList<>()).add(v);
                }
            }
        }
        for (var e : byMsg.entrySet()) {
            ParserEquivalence.Verdict v = e.getValue().get(0);
            System.out.println("==== x" + e.getValue().size() + " " + e.getKey()
                    + "  @ " + v.sourceId());
            // print the failing line region from the source
            String d = e.getKey();
            int l0 = Integer.parseInt(d.substring(d.indexOf('[') + 1, d.indexOf(':', d.indexOf('['))));
            String[] lines = srcById.get(v.sourceId()).text().split("\n");
            for (int i = Math.max(0, l0 - 4); i < Math.min(lines.length, l0 + 2); i++) {
                System.out.println((i + 1) + ": " + lines[i]);
            }
        }
    }
}
