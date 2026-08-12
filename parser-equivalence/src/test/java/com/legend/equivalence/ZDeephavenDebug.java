package com.legend.equivalence;

import com.legend.lexer.Lexer;
import com.legend.parser.ElementParser;
import org.junit.jupiter.api.Test;

/** Scratch: our deephaven-connection emission vs the oracle's span. */
class ZDeephavenDebug {

    @Test
    void dump() {
        String src = """
                ###Connection
                DeephavenConnection my::DC
                {
                    store: my::DStore;
                    serverUrl: 'http://localhost:10000'
                    authentication: # PSK {
                        psk: 'myStaticPSK';
                    }#;
                }
                """;
        var ts = Lexer.tokenize(src);
        for (int i = 0; i < ts.count(); i++) {
            System.out.println("TOK " + i + " " + ts.type(i) + " ["
                    + ts.startLine(i) + ":" + ts.startColumn(i) + "-"
                    + ts.endLine(i) + ":" + ts.endColumn(i) + "] '"
                    + ts.text(i).replace("\n", "\\n") + "'");
        }
        var sites = ElementParser.topLevelIndexes(ts,
                com.legend.lexer.TokenType.VALID_STRING);
        com.legend.protocol.Protocol.PConnection pc =
                Surfaces.engineAt(ts, 0).parseConnectionProtocol();
        System.out.println("OURS: " + com.legend.protocol.ProtocolEmitter
                .emitElement(pc));
    }
}
