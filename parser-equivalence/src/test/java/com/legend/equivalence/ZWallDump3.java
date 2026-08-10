package com.legend.equivalence;

import org.junit.jupiter.api.Test;

/** Dump ALL wall verdicts with their source ids (diagnostic, wire burn). */
class ZWallDump3 {

    @Test
    void dump() {
        ParserEquivalence eq = new ParserEquivalence();
        for (Corpus.Source s : Corpus.all()) {
            for (ParserEquivalence.Verdict v : eq.compare(s)) {
                if (v.kind() == ParserEquivalence.Kind.WALL) {
                    System.out.println("WALLROW " + v.sourceId() + " :: "
                            + v.detail().replaceAll("\\s+", " "));
                }
            }
        }
    }
}
