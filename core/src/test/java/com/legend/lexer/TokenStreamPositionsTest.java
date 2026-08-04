package com.legend.lexer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the source-position math against <b>legend-engine's real output</b>.
 *
 * <p>The expectations below are not invented: they are the numbers legend-engine's own parser
 * emitted for the same source, captured 2026-08-04 (see
 * {@code com.legend.protocol.ProtocolEmitterTest}). Getting these wrong is the single easiest way
 * to fail byte-identity while every structural comparison still passes.
 *
 * <p>Engine convention: <b>1-based</b> lines, <b>1-based</b> start column, and an
 * <b>inclusive</b> end column.
 */
class TokenStreamPositionsTest {

    /**
     * <pre>
     * Class model::Person
     * {
     *   name: String[1];
     * }
     * </pre>
     * legend-engine reports the {@code String} type reference at
     * {@code startLine 3, startColumn 9, endLine 3, endColumn 14}.
     */
    private static final String SRC = "Class model::Person\n{\n  name: String[1];\n}\n";

    private static int indexOfToken(TokenStream ts, String text) {
        for (int i = 0; i < ts.count(); i++) {
            if (text.equals(ts.text(i))) {
                return i;
            }
        }
        throw new AssertionError("token not found: " + text);
    }

    @Test
    void matchesLegendEnginePositionsForATypeReference() {
        TokenStream ts = Lexer.tokenize(SRC);
        int i = indexOfToken(ts, "String");

        assertEquals(3, ts.startLine(i), "startLine");
        assertEquals(9, ts.startColumn(i), "startColumn");
        assertEquals(3, ts.endLine(i), "endLine");
        assertEquals(14, ts.endColumn(i), "endColumn — INCLUSIVE, engine convention");
    }

    @Test
    void propertyNameStartsAtColumnThree() {
        TokenStream ts = Lexer.tokenize(SRC);
        int i = indexOfToken(ts, "name");
        assertEquals(3, ts.startLine(i));
        assertEquals(3, ts.startColumn(i), "two spaces of indent, then 1-based column 3");
    }

    @Test
    void firstTokenIsLineOneColumnOne() {
        TokenStream ts = Lexer.tokenize(SRC);
        assertEquals(1, ts.startLine(0));
        assertEquals(1, ts.startColumn(0));
    }

    @Test
    void offsetMathHandlesLineBoundaries() {
        TokenStream ts = Lexer.tokenize("a\nbb\nccc");
        assertEquals(1, ts.lineOf(0));
        assertEquals(1, ts.columnOf(0));
        assertEquals(2, ts.lineOf(2), "first char after the first newline");
        assertEquals(1, ts.columnOf(2));
        assertEquals(2, ts.columnOf(3));
        assertEquals(3, ts.lineOf(5));
        assertEquals(1, ts.columnOf(5));
        assertEquals(3, ts.columnOf(7), "last char of the last line");
    }

    /** A slice must keep reporting positions in the ORIGINAL source, not the slice. */
    @Test
    void sliceKeepsOriginalSourcePositions() {
        TokenStream ts = Lexer.tokenize(SRC);
        int i = indexOfToken(ts, "String");
        TokenStream sliced = ts.slice(i, i + 1);
        assertEquals(3, sliced.startLine(0));
        assertEquals(9, sliced.startColumn(0));
        assertEquals(14, sliced.endColumn(0));
    }
}
