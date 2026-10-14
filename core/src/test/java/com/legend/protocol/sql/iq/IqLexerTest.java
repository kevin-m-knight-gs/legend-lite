// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.protocol.sql.iq;

import com.legend.protocol.sql.SqlSyntaxException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * IQ's lexical rules ({@link IqLexer}, the research's GRAMMAR L1-L31) under the profiles' options: the identifier
 * quotes, strings and their escapes, numbers, comments, operators, reserved words, and the forms refused by name.
 */
class IqLexerTest {

    private static List<IqLexer.Token> lex(String sql) {
        return IqLexer.tokenize(sql, IqProfile.IQ16_DEFAULT);
    }

    private static String kinds(String sql, IqProfile profile) {
        StringBuilder b = new StringBuilder();
        for (IqLexer.Token t : IqLexer.tokenize(sql, profile)) {
            if (t.kind() != IqLexer.Kind.EOF) {
                b.append(b.length() > 0 ? " " : "").append(t.kind()).append(':').append(t.text());
            }
        }
        return b.toString();
    }

    private static String kinds(String sql) {
        return kinds(sql, IqProfile.IQ16_DEFAULT);
    }

    @Test
    void identifiersArePlainBracketedBackQuotedOrDoubleQuoted() {
        assertEquals("KEYWORD:SELECT WORD:a QUOTED:b c QUOTED:d QUOTED:E", kinds("SELECT a [b c] `d` \"E\""));
        assertEquals("WORD:@v WORD:#t WORD:$x WORD:_y WORD:café", kinds("@v #t $x _y café"));
    }

    @Test
    void doubleQuotesAreAStringUnderATdsLogin_aDoubledQuoteStandingForOne() {
        assertEquals("STRING:vc STRING:a\"b", kinds("\"vc\" \"a\"\"b\"", IqProfile.IQ16_TDS));
        // a delimited identifier has no doubling escape
        assertThrows(SqlSyntaxException.class, () -> lex("\"a\"\"b\""));
        assertThrows(SqlSyntaxException.class, () -> lex("[]"));
        assertThrows(SqlSyntaxException.class, () -> lex("[a\"b]"));
    }

    @Test
    void stringsDoubleTheirQuote_andReadBackslashEscapesOnlyUnderEscapeCharacterOn() {
        assertEquals("STRING:it's", kinds("'it''s'"));
        assertEquals("STRING:a\nb STRING:xAy STRING:q\\q STRING:\\", kinds("'a\\nb' 'x\\x41y' 'q\\q' '\\\\'"));
        assertEquals("STRING:a\\nb STRING:x\\x41y", kinds("'a\\nb' 'x\\x41y'", IqProfile.IQ16_TDS));
        // a backslash never escapes the quote: '\'' is unterminated
        SqlSyntaxException open = assertThrows(SqlSyntaxException.class, () -> lex("'\\''"));
        assertFalse(open.unsupported());
        assertTrue(open.reason().contains("SQLCODE -131"), open.reason());
    }

    @Test
    void numbersTakeAPointAndAnExponent_andATokenEndsWhereTheNextCannotExtendIt() {
        assertEquals("NUMBER:42 NUMBER:4.5 NUMBER:.5 NUMBER:5. NUMBER:1e10 NUMBER:1.5E-3", kinds("42 4.5 .5 5. 1e10 1.5E-3"));
        assertEquals("NUMBER:1 WORD:abc", kinds("1abc"));
        assertTrue(assertThrows(SqlSyntaxException.class, () -> lex("1e")).unsupported());
        assertTrue(assertThrows(SqlSyntaxException.class, () -> lex("0x1F")).unsupported());
        assertTrue(assertThrows(SqlSyntaxException.class, () -> lex("$5")).unsupported());
        assertTrue(assertThrows(SqlSyntaxException.class, () -> lex("N'x'")).unsupported());
    }

    @Test
    void commentsRunToTheEndOfTheLineOrNest_anOpenOneSwallowingTheRest() {
        assertEquals("NUMBER:1 NUMBER:2", kinds("1 -- one\n2 // two"));
        assertEquals("NUMBER:1 OP:, NUMBER:2", kinds("1 /* a /* b */ c */ , 2"));
        assertEquals("NUMBER:1", kinds("1 /* a /* b */ , 2"));
    }

    @Test
    void operatorsAreIqs() {
        assertEquals("OP:!= OP:!< OP:!> OP:<> OP:<= OP:>= OP:*= OP:=* OP:|| OP:^ OP:%", kinds("!= !< !> <> <= >= *= =* || ^ %"));
    }

    @Test
    void reservedWordsAreKeywords_theContextWordsAndIqsOwnFourAreNames() {
        assertEquals("KEYWORD:select KEYWORD:Date KEYWORD:FIRST WORD:limit WORD:timestamp WORD:iq WORD:TRUE",
                kinds("select Date FIRST limit timestamp iq TRUE"));
        assertTrue(IqLexer.reserved("WINDOW"));
        assertFalse(IqLexer.reserved("offset"));
    }

    @Test
    void aCharacterOutsideTheProfilesCharsetIsRefusedByName() {
        assertTrue(assertThrows(SqlSyntaxException.class, () -> lex("'€'")).unsupported());
        assertEquals("STRING:€", kinds("'€'", IqProfile.SQLA17_DEFAULT));
        // a no-break space is no IQ space
        assertFalse(assertThrows(SqlSyntaxException.class, () -> lex("1 2")).unsupported());
    }

    @Test
    void eachTokenKnowsItsPlaceAndItsSpan() {
        List<IqLexer.Token> tokens = lex("SELECT\n  [a b]");
        IqLexer.Token name = tokens.get(1);
        assertEquals(2, name.pos().line());
        assertEquals(3, name.pos().column());
        assertEquals("[a b]", "SELECT\n  [a b]".substring(name.start(), name.end()));
    }
}
