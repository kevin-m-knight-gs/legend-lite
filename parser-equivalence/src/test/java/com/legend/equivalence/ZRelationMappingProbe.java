package com.legend.equivalence;

import org.finos.legend.engine.language.pure.grammar.from.PureGrammarParser;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * PROBE (W10-L1): the engine's wire JSON for the RELATION class-mapping
 * family the 4.138.2 oracle newly accepts (relation-emit-models). Decoded
 * shapes drive the MappingProtocolParser/emitter extension; delete-or-keep
 * per probe policy once the wire notes are recorded.
 */
class ZRelationMappingProbe {

    private static final String EMIT = "/Users/neemsandv/legend/legend-engine/"
            + "legend-engine-xts-relationalStore/legend-engine-xt-"
            + "relationalStore-emit/src/test/resources/relation-emit-models/";

    private void probeFile(String rel) throws Exception {
        var mapper = org.finos.legend.engine.shared.core.ObjectMapperFactory
                .getNewStandardObjectMapperWithPureProtocolExtensionSupports();
        String src = Files.readString(Path.of(EMIT + rel));
        try {
            var pmcd = PureGrammarParser.newInstance().parseModel(src);
            for (var e : pmcd.getElements()) {
                if (e.getPath().contains("SectionIndex")
                        || !(e instanceof org.finos.legend.engine.protocol.pure
                                .v1.model.packageableElement.mapping.Mapping)) {
                    continue;
                }
                System.out.println("== " + rel + " :: " + e.getPath());
                System.out.println(mapper.writeValueAsString(e));
            }
        } catch (Throwable t) {
            System.out.println("== " + rel + " REJECTED: "
                    + String.valueOf(t.getMessage()).replaceAll("\\s+", " "));
        }
    }

    @Test
    void relationMappingShapes() throws Exception {
        probeFile("relation-src/mapping/srcMapping.pure");
        probeFile("relation-simple/mapping/employeeMapping.pure");
        probeFile("relation-embedded/mapping/embeddedMapping.pure");
        probeFile("relation-enumeration/mapping/enumMapping.pure");
        probeFile("relation-expression-rhs/mapping/exprMapping.pure");
        probeFile("relation-filter/mapping/filterMapping.pure");
    }

    @Test
    void relationMappingShapes2() throws Exception {
        probeFile("relation-include/mapping/includeMapping.pure");
        probeFile("relation-inline-embedded/mapping/inlineEmbeddedMapping.pure");
        probeFile("relation-join/mapping/joinMapping.pure");
        probeFile("relation-modelJoin/mapping/modelJoinMapping.pure");
        probeFile("relation-modelJoin-chained/mapping/chainedModelJoinMapping.pure");
        probeFile("relation-primary-key/mapping/primaryKeyMapping.pure");
    }

    @Test
    void relationMappingShapes3() throws Exception {
        probeFile("relation-relational-union/mapping/mixedUnionMapping.pure");
        probeFile("relation-union/mapping/unionMapping.pure");
        probeFile("relation-union-enum/mapping/unionEnumMapping.pure");
        probeFile("relation-window-function/mapping/windowFunctionMapping.pure");
    }
}
