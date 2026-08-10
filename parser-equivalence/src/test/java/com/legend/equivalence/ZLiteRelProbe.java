package com.legend.equivalence;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

/** PROBE (W10-L1): what OUR side throws on the relation-emit wall files. */
class ZLiteRelProbe {

    private static final String EMIT = "/Users/neemsandv/legend/legend-engine/"
            + "legend-engine-xts-relationalStore/legend-engine-xt-"
            + "relationalStore-emit/src/test/resources/relation-emit-models/";

    private void tryLite(String rel) throws Exception {
        String src = Files.readString(Path.of(EMIT + rel));
        try {
            var ts = com.legend.lexer.Lexer.tokenize(src);
            int n = 0;
            for (int i = 0; i < ts.count(); i++) {
                if (ts.type(i) == com.legend.lexer.TokenType.MAPPING
                        && (i == 0
                        || ts.type(i - 1) == com.legend.lexer.TokenType.BRACE_CLOSE
                        || ts.type(i - 1) == com.legend.lexer.TokenType.SEMI_COLON
                        || ts.type(i - 1) == com.legend.lexer.TokenType.PAREN_CLOSE)) {
                    var mp = com.legend.parser.MappingProtocolParser
                            .parse(ts, i, ts.sectionContentLine("Mapping",
                                    ts.start(i)));
                    com.legend.protocol.ProtocolEmitter.emitElement(mp);
                    n++;
                }
            }
            System.out.println("== " + rel + " EMITTED " + n);
        } catch (Throwable t) {
            StringBuilder frames = new StringBuilder();
            for (StackTraceElement f : t.getStackTrace()) {
                if (f.getClassName().startsWith("com.legend")) {
                    frames.append(" @ ").append(f);
                    if (frames.length() > 300) {
                        break;
                    }
                }
            }
            System.out.println("== " + rel + " THREW " + t.getClass().getSimpleName()
                    + " :: " + t.getMessage() + frames);
        }
    }

    @Test
    void liteSideFailures() throws Exception {
        tryLite("relation-embedded/mapping/embeddedMapping.pure");
        tryLite("relation-enumeration/mapping/enumMapping.pure");
        tryLite("relation-expression-rhs/mapping/exprMapping.pure");
        tryLite("relation-filter/mapping/filterMapping.pure");
        tryLite("relation-include/mapping/includeMapping.pure");
        tryLite("relation-inline-embedded/mapping/inlineEmbeddedMapping.pure");
        tryLite("relation-join/mapping/joinMapping.pure");
        tryLite("relation-modelJoin/mapping/modelJoinMapping.pure");
        tryLite("relation-modelJoin-chained/mapping/chainedModelJoinMapping.pure");
        tryLite("relation-primary-key/mapping/primaryKeyMapping.pure");
        tryLite("relation-relational-union/mapping/mixedUnionMapping.pure");
        tryLite("relation-simple/mapping/employeeMapping.pure");
        tryLite("relation-union/mapping/unionMapping.pure");
        tryLite("relation-union-enum/mapping/unionEnumMapping.pure");
        tryLite("relation-window-function/mapping/windowFunctionMapping.pure");
        tryLite("relation-src/mapping/srcMapping.pure");
    }
}
