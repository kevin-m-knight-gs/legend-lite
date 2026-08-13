// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.parser.section;

import com.legend.lexer.TokenType;
import com.legend.parser.TokenStreamCursor;
import com.legend.protocol.Protocol;

/** Shared low-level parse helpers for the section grammars. */
final class SectionParse {

    private SectionParse() {
    }

    /** {@code Kind [decorations] qn} — the shared declaration head. */
    record Head(int declStart, TokenStreamCursor.Decorations dec,
                String pkg, String name) {
    }

    static Head head(TokenStreamCursor c, String expectedKind) {
        int declStart = c.pos();
        String kind = c.safeText();
        if (!expectedKind.equals(kind)) {
            throw c.error("expected " + expectedKind + ", got " + kind);
        }
        c.advance();
        TokenStreamCursor.Decorations dec = c.parseDecorations();
        String qn = Protocol.unquotePath(c.parseQualifiedName());
        int cut = qn.lastIndexOf("::");
        return new Head(declStart, dec,
                cut < 0 ? "" : qn.substring(0, cut),
                cut < 0 ? qn : qn.substring(cut + 2));
    }

    static String stringValue(TokenStreamCursor c) {
        String quoted = c.text();
        c.expect(TokenType.STRING);
        return TokenStreamCursor.unquoteAndUnescape(quoted, c);
    }

    /** {@code true|false} — shared by every grammar (was 6 private
     *  copies, adversarial audit census #20). */
    static Boolean booleanValue(TokenStreamCursor c) {
        if (c.peek() == TokenType.TRUE) {
            c.advance();
            return Boolean.TRUE;
        }
        if (c.peek() == TokenType.FALSE) {
            c.advance();
            return Boolean.FALSE;
        }
        throw c.error("expected true or false, got " + c.safeText());
    }

    /** The raw single-quoted token text, quotes INCLUDED. */
    static String rawStringToken(TokenStreamCursor c) {
        String raw = c.text();
        c.expect(TokenType.STRING);
        return raw;
    }

    /** {@code Avro -> avro}, {@code Keyword -> keyword} — the wire
     *  lowercases the keyword's first letter. */
    static String lowerFirst(String kind) {
        return Character.toLowerCase(kind.charAt(0)) + kind.substring(1);
    }

    /** A value expression riding to the terminating top-level {@code ;} —
     *  parsed by THE SpecParser on the token slice (file-absolute spans). */
    static com.legend.protocol.spec.ValueSpecification specToSemicolon(
            TokenStreamCursor c) {
        int bs = c.pos();
        int d = 0;
        while (!c.atEnd()) {
            TokenType tk = c.peek();
            switch (tk) {
                case PAREN_OPEN, BRACE_OPEN, BRACKET_OPEN -> d++;
                case PAREN_CLOSE, BRACE_CLOSE, BRACKET_CLOSE -> d--;
                default -> { }
            }
            if (tk == TokenType.SEMI_COLON && d <= 0) {
                break;
            }
            c.advance();
        }
        return com.legend.parser.SpecParser.parse(
                c.tokens().slice(bs, c.pos()), c.dialect());
    }
}
