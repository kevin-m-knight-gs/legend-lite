package com.legend.equivalence;

import org.finos.legend.engine.language.pure.grammar.from.PureGrammarParser;
import org.junit.jupiter.api.Test;

class ZMongoMapWireProbeTest {
    @Test
    void probe() throws Exception {
        String src = "###Mapping\n"
                + "Mapping test::m\n"
                + "(\n"
                + "  *test::Person: MongoDB\n"
                + "  {\n"
                + "    ~mainCollection [test::db] person\n"
                + "    ~binding test::binding\n"
                + "  }\n"
                + ")\n";
        var mapper = org.finos.legend.engine.shared.core.ObjectMapperFactory
                .getNewStandardObjectMapperWithPureProtocolExtensionSupports();
        var model = PureGrammarParser.newInstance().parseModel(src);
        System.out.println("MMWIRE " + mapper.writeValueAsString(model));
    }
}
