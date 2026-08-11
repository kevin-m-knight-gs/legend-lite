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
 * The ONE emitter arm that cannot be pinned against legend-engine.
 *
 * <p>Every other constant in this package is the verbatim output of engine's
 * own parser (see {@link ProtocolEmitterTest}). {@link Protocol.PRelTypeRef}
 * admits no such pin: it exists because legend-lite accepts a postfix
 * {@code DATA->get('price', @Float)} inside a relational property mapping,
 * and engine's {@code relationalPropertyMapping} has no arrow production at
 * all — so the reference parser rejects the source and can never produce a
 * byte to compare against.
 *
 * <p>That makes this arm invisible to BOTH oracles: the corpus never writes
 * the construct, so gate 4 and gate 8's byte comparison never reach it.
 * Without this test it is unexecuted code carrying an unpinned wire shape,
 * which is how a guess survives review.
 *
 * <p>So this pins what it CAN: that the arm runs, and that it spells a type
 * the way the rest of the protocol already does — {@code packageableType} /
 * {@code fullPath}, which IS oracle-verified for generic types in
 * {@link GenericTypeEmissionTest}. Consistency with a checked spelling is
 * the strongest claim available here; it is not parity, and nothing about
 * this shape should be described as verified against engine.
 */
class RelationalTypeRefEmissionTest {

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
        MappingEmitter.mapping(b, MappingProtocolParser.parse(ts, at, 1));
        return b.toString();
    }

    @Test
    void typeArgumentInAnArrowChainReachesTheWire() {
        String json = emitMapping(
                "\n###Mapping\nMapping my::M ( *model::Order: Relational { "
                + "~mainTable [store::DB] T_ORDERS "
                + "total: [store::DB] T_ORDERS.DATA->get('total', @Float) "
                + "} )");
        // the chain itself is an ordinary dynaFunc with the receiver first
        assertTrue(json.contains("\"_type\":\"dynaFunc\""), json);
        assertTrue(json.contains("\"funcName\":\"get\""), json);
        // ...and the @Float argument rides the same spelling the protocol
        // already uses for a type in value position
        assertTrue(json.contains("\"_type\":\"packageableType\""),
                () -> "the type argument must reach the wire, got: " + json);
        assertTrue(json.contains("\"fullPath\":\"Float\""),
                () -> "the type NAME must survive, got: " + json);
    }

    @Test
    void arrowChainWithoutATypeArgumentEmitsNoTypeRef() {
        String json = emitMapping(
                "\n###Mapping\nMapping my::M ( *model::Event: Relational { "
                + "~mainTable [store::DB] T_EVENTS "
                + "price: [store::DB] T_EVENTS.PAYLOAD->get('price') "
                + "} )");
        assertTrue(json.contains("\"funcName\":\"get\""), json);
        assertTrue(!json.contains("packageableType"),
                () -> "no @Type was written, so none should be emitted: " + json);
    }
}
