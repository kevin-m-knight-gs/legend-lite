package com.legend.equivalence;

import org.finos.legend.engine.language.pure.grammar.from.PureGrammarParser;
import org.junit.jupiter.api.Test;

/** Wire-shape probe for ###Runtime (Phase D commit 3). Diagnostic only. */
class ZRuntimeProbe {

    private void probe(String label, String src) throws Exception {
        var mapper = org.finos.legend.engine.shared.core.ObjectMapperFactory
                .getNewStandardObjectMapperWithPureProtocolExtensionSupports();
        try {
            var pmcd = PureGrammarParser.newInstance().parseModel(src);
            for (var e : pmcd.getElements()) {
                if (e.getPath().contains("SectionIndex") || e.getPath().startsWith("__")) {
                    continue;
                }
                System.out.println("== " + label + " :: " + e.getPath());
                System.out.println(mapper.writeValueAsString(e));
            }
        } catch (Throwable t) {
            System.out.println("== " + label + " REJECTED: "
                    + String.valueOf(t.getMessage()).replaceAll("\\s+", " "));
        }
    }

    @Test
    void shapes() throws Exception {
        probe("plain", """
                ###Runtime
                Runtime my::R
                {
                  mappings: [my::M1, my::M2];
                  connections: [
                    my::Store1: [ id1: my::Conn1, id2: my::Conn2 ],
                    ModelStore: [ id3: my::Conn3 ]
                  ];
                }
                """);
        probe("no-connections", """
                ###Runtime
                Runtime my::R2
                {
                  mappings: [];
                }
                """);
        probe("connection-stores", """
                ###Runtime
                Runtime my::R3
                {
                  mappings: [my::M];
                  connectionStores: [ my::C1: [my::S1, my::S2] ];
                }
                """);
        probe("connection-stores-empty", """
                ###Runtime
                Runtime my::R6
                {
                  mappings: [my::M];
                  connectionStores: [ my::C1: [] ];
                }
                """);
        probe("single", """
                ###Runtime
                SingleConnectionRuntime my::R4
                {
                  mappings: [my::M];
                  connection: my::C;
                }
                """);
        probe("embedded-json", """
                ###Runtime
                Runtime my::R5
                {
                  mappings: [my::M];
                  connections: [
                    ModelStore: [ c1: #{ JsonModelConnection { class: my::K; url: 'data:x'; } }# ]
                  ];
                }
                """);
    }
}
