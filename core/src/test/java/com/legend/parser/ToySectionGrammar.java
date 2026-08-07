package com.legend.parser;

import com.legend.spi.ElementSink;
import com.legend.spi.SectionGrammar;
import com.legend.spi.SectionSource;

/** THE overlay proof (Phase M step 2): a test-only jar section registered
 *  through the real ServiceLoader seam — exactly what an internal
 *  closed-source grammar jar will do. */
public final class ToySectionGrammar implements SectionGrammar {

    static volatile String lastText = null;

    @Override
    public String name() {
        return "Toy";
    }

    @Override
    public void parse(SectionSource src, ElementSink out) {
        lastText = src.text();
        // "Toy x::Name;" lines — a deliberately foreign micro-grammar
        for (String line : src.text().lines().toList()) {
            String t = line.strip();
            if (t.startsWith("Toy ") && t.endsWith(";")) {
                String fqn = t.substring(4, t.length() - 1).strip();
                out.accept(fqn, "{\"_type\":\"toy\",\"name\":\"" + fqn + "\"}");
            }
        }
    }
}
