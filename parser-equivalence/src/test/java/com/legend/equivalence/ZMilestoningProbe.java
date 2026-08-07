package com.legend.equivalence;

import org.finos.legend.engine.language.pure.grammar.from.PureGrammarParser;
import org.junit.jupiter.api.Test;

/** Fine probe: engine milestoning-dimension spans vs REAL columns. */
class ZMilestoningProbe {

    @Test
    void spans() throws Exception {
        String src = """
                ###Relational
                Database my::M
                (
                  Table T1
                  (
                    milestoning
                    (
                      business(BUS_FROM = f, BUS_THRU = t),
                      processing(PROCESSING_IN = i, PROCESSING_OUT = o)
                    )
                    id INTEGER PRIMARY KEY, f DATE, t DATE, i TIMESTAMP, o TIMESTAMP
                  )
                  Table T2
                  (
                    milestoning( business(BUS_FROM = f, BUS_THRU = t) )
                    id INTEGER PRIMARY KEY, f DATE, t DATE
                  )
                )
                """;
        var mapper = org.finos.legend.engine.shared.core.ObjectMapperFactory
                .getNewStandardObjectMapperWithPureProtocolExtensionSupports();
        var pmcd = PureGrammarParser.newInstance().parseModel(src);
        String[] lines = src.split("\n");
        for (var e : pmcd.getElements()) {
            String json = mapper.writeValueAsString(e);
            if (!json.contains("Milestoning")) {
                continue;
            }
            var node = mapper.readTree(json);
            for (var t : node.get("schemas").get(0).get("tables")) {
                for (var m : t.get("milestoning")) {
                    var si = m.get("sourceInformation");
                    int sl = si.get("startLine").asInt();
                    int sc = si.get("startColumn").asInt();
                    int el = si.get("endLine").asInt();
                    int ec = si.get("endColumn").asInt();
                    System.out.println("DIM " + m.get("_type").asText()
                            + " engine=" + sl + ":" + sc + ".." + el + ":" + ec
                            + " | line[sl]='" + lines[sl - 1] + "'"
                            + " | line[el]='" + lines[el - 1] + "'");
                }
            }
        }
    }
}
