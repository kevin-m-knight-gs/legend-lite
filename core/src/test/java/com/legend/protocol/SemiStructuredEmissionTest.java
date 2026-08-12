// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.protocol;

import com.legend.lexer.Lexer;
import com.legend.lexer.TokenStream;
import com.legend.lexer.TokenType;
import com.legend.parser.MappingProtocolParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code extractFromSemiStructured(col, 'path', 'SQLTYPE')} in a relational
 * property mapping — the ENGINE's semistructured scalar extraction
 * (RelationalParserGrammar {@code functionOperation}; see
 * {@code core_relational/relational/tests/semistructured/}). On the wire it
 * is an ordinary {@code dynaFunc} whose parameters are the column and two
 * string literals, all arms that ARE oracle-pinned elsewhere; this test pins
 * the composition. It replaced legend-lite's retired json-column-get arrow
 * extension ({@code DATA->get('price', @Float)}), whose type argument needed
 * a wire node engine could never produce (probe json-get-spelling,
 * 2026-08-12).
 */
class SemiStructuredEmissionTest {

    private static String emitMapping(String source) {
        TokenStream ts = Lexer.tokenize(source);
        int at = -1;
        for (int i = 0; i < ts.count(); i++) {
            if (ts.type(i) == TokenType.MAPPING) {
                at = i;
                break;
            }
        }
        StringBuilder b = new StringBuilder();
        MappingEmitter.mapping(b, MappingProtocolParser.parse(ts, at, 1, com.legend.parser.Dialect.LEGEND_PLATFORM));
        return b.toString();
    }

    @Test
    void extractFromSemiStructuredIsAPlainDynaFuncOnTheWire() {
        String json = emitMapping(
                "\n###Mapping\nMapping my::M ( *model::Order: Relational { "
                + "~mainTable [store::DB] T_ORDERS "
                + "total: extractFromSemiStructured([store::DB] T_ORDERS.DATA, 'total', 'FLOAT') "
                + "} )");
        assertTrue(json.contains("\"_type\":\"dynaFunc\""), json);
        assertTrue(json.contains("\"funcName\":\"extractFromSemiStructured\""),
                json);
        // the path and SQL type are ordinary string literals
        assertTrue(json.contains("\"value\":\"total\""), json);
        assertTrue(json.contains("\"value\":\"FLOAT\""), json);
    }

    @Test
    void bareGetIsAPlainDynaFuncOnTheWire() {
        String json = emitMapping(
                "\n###Mapping\nMapping my::M ( *model::Event: Relational { "
                + "~mainTable [store::DB] T_EVENTS "
                + "price: get([store::DB] T_EVENTS.PAYLOAD, 'price') "
                + "} )");
        assertTrue(json.contains("\"funcName\":\"get\""), json);
        assertTrue(json.contains("\"value\":\"price\""), json);
    }
}
