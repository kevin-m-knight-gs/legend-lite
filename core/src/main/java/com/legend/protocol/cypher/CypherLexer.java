// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.protocol.cypher;

import com.legend.protocol.sql.SqlAst.Pos;

import java.util.ArrayList;
import java.util.List;

/**
 * Cypher's tokens, as Neo4j reads them (docs/CYPHER_STUDY_2026_10_15.md §5.1): identifiers (a backticked one may
 * hold anything, {@code ``} an escaped backtick), keywords read later as identifiers (Cypher's keywords and function
 * names are case-insensitive, its names case-sensitive), integers (decimal, hexadecimal {@code 0x}, octal
 * {@code 0o}), floats (a point or an exponent), strings in single or double quotes with Neo4j's escapes, parameters
 * ({@code $name}), the operators and punctuation, and comments ({@code //} to the end of the line,
 * {@code /* ... *&#47;}). The scanner's cursor fields ({@code at}, {@code line}, {@code column}) are the SqlLexer's
 * shape, scoped to one scan ({@code CodeShapeGuardrailTest}).
 */
public final class CypherLexer {

    public enum Type {
        IDENT, INTEGER, FLOAT, STRING, PARAM,
        LPAREN, RPAREN, LBRACKET, RBRACKET, LBRACE, RBRACE, COMMA, COLON, DOT, DOTDOT, SEMI, PIPE,
        PLUS, MINUS, STAR, SLASH, PERCENT, CARET, EQ, NE, LT, LE, GT, GE, REGEX_EQ,
        ARROW_RIGHT, ARROW_LEFT, PLUS_EQ, EOF
    }

    /** A token: its type, its text (an identifier's as written, a backticked one's without the backticks, a string's
     *  decoded value), and its position. */
    public record Token(Type type, String text, boolean backticked, Pos pos, int start, int end) {
        public boolean is(Type t) {
            return type == t;
        }

        /** Whether this is the identifier {@code word}, compared without regard to case (a keyword). A backticked
         *  identifier is never a keyword. */
        public boolean isWord(String word) {
            return type == Type.IDENT && !backticked && text.equalsIgnoreCase(word);
        }
    }

    private final String src;
    private int at;
    private int line = 1;
    private int column = 1;

    private CypherLexer(String src) {
        this.src = src;
    }

    public static List<Token> tokenize(String src) {
        return new CypherLexer(src).scan();
    }

    private List<Token> scan() {
        List<Token> out = new ArrayList<>();
        while (true) {
            skipSpaceAndComments();
            if (at >= src.length()) {
                out.add(new Token(Type.EOF, "", false, pos(), at, at));
                return out;
            }
            out.add(next());
        }
    }

    private Pos pos() {
        return new Pos(line, column);
    }

    private char peek(int ahead) {
        int i = at + ahead;
        return i < src.length() ? src.charAt(i) : '\0';
    }

    private void advance() {
        char c = src.charAt(at++);
        if (c == '\n') {
            line++;
            column = 1;
        } else {
            column++;
        }
    }

    private void skipSpaceAndComments() {
        while (at < src.length()) {
            char c = src.charAt(at);
            if (Character.isWhitespace(c)) {
                advance();
            } else if (c == '/' && peek(1) == '/') {
                while (at < src.length() && src.charAt(at) != '\n') {
                    advance();
                }
            } else if (c == '/' && peek(1) == '*') {
                Pos start = pos();
                advance();
                advance();
                while (at < src.length() && !(src.charAt(at) == '*' && peek(1) == '/')) {
                    advance();
                }
                if (at >= src.length()) {
                    throw CypherErrors.syntax("unterminated comment", start);
                }
                advance();
                advance();
            } else {
                return;
            }
        }
    }

    private Token next() {
        Pos p = pos();
        char c = src.charAt(at);
        if (c == '`') {
            return backticked(p);
        }
        if (Character.isLetter(c) || c == '_') {
            int start = at;
            while (at < src.length() && (Character.isLetterOrDigit(src.charAt(at)) || src.charAt(at) == '_')) {
                advance();
            }
            return new Token(Type.IDENT, src.substring(start, at), false, p, start, at);
        }
        if (Character.isDigit(c) || (c == '.' && Character.isDigit(peek(1)))) {
            return number(p);
        }
        if (c == '\'' || c == '"') {
            return string(p, c);
        }
        if (c == '$') {
            advance();
            int start = at;
            while (at < src.length() && (Character.isLetterOrDigit(src.charAt(at)) || src.charAt(at) == '_')) {
                advance();
            }
            if (start == at) {
                if (peek(0) == '(') {
                    throw CypherErrors.unsupported("a dynamic label, type or property ($(...)) is not supported: the"
                            + " model is the schema", p);
                }
                throw CypherErrors.syntax("a parameter needs a name after '$'", p);
            }
            return new Token(Type.PARAM, src.substring(start, at), false, p, start - 1, at);
        }
        return symbol(p, c);
    }

    private Token backticked(Pos p) {
        int start = at;
        advance();
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (at >= src.length()) {
                throw CypherErrors.syntax("unterminated backticked identifier", p);
            }
            char c = src.charAt(at);
            if (c == '`') {
                if (peek(1) == '`') {
                    sb.append('`');
                    advance();
                    advance();
                    continue;
                }
                advance();
                return new Token(Type.IDENT, sb.toString(), true, p, start, at);
            }
            sb.append(c);
            advance();
        }
    }

    private Token number(Pos p) {
        int start = at;
        if (src.charAt(at) == '0' && (peek(1) == 'x' || peek(1) == 'X')) {
            advance();
            advance();
            while (at < src.length() && Character.digit(src.charAt(at), 16) >= 0) {
                advance();
            }
            return new Token(Type.INTEGER, src.substring(start, at), false, p, start, at);
        }
        if (src.charAt(at) == '0' && (peek(1) == 'o' || peek(1) == 'O')) {
            advance();
            advance();
            while (at < src.length() && Character.digit(src.charAt(at), 8) >= 0) {
                advance();
            }
            return new Token(Type.INTEGER, src.substring(start, at), false, p, start, at);
        }
        boolean isFloat = false;
        while (at < src.length() && Character.isDigit(src.charAt(at))) {
            advance();
        }
        if (at < src.length() && src.charAt(at) == '.' && Character.isDigit(peek(1))) {
            isFloat = true;
            advance();
            while (at < src.length() && Character.isDigit(src.charAt(at))) {
                advance();
            }
        }
        if (at < src.length() && (src.charAt(at) == 'e' || src.charAt(at) == 'E')) {
            char sign = peek(1);
            int digitsAt = (sign == '+' || sign == '-') ? 2 : 1;
            if (Character.isDigit(peek(digitsAt))) {
                isFloat = true;
                for (int i = 0; i < digitsAt; i++) {
                    advance();
                }
                while (at < src.length() && Character.isDigit(src.charAt(at))) {
                    advance();
                }
            }
        }
        return new Token(isFloat ? Type.FLOAT : Type.INTEGER, src.substring(start, at), false, p, start, at);
    }

    private Token string(Pos p, char quote) {
        int start = at;
        advance();
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (at >= src.length()) {
                throw CypherErrors.syntax("unterminated string", p);
            }
            char c = src.charAt(at);
            if (c == quote) {
                advance();
                return new Token(Type.STRING, sb.toString(), false, p, start, at);
            }
            if (c == '\\') {
                advance();
                if (at >= src.length()) {
                    throw CypherErrors.syntax("unterminated string", p);
                }
                char e = src.charAt(at);
                switch (e) {
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case 'r' -> sb.append('\r');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case '\\' -> sb.append('\\');
                    case '\'' -> sb.append('\'');
                    case '"' -> sb.append('"');
                    case 'u' -> {
                        advance();
                        sb.appendCodePoint(unicode(4, p));
                        continue;
                    }
                    case 'U' -> {
                        advance();
                        sb.appendCodePoint(unicode(8, p));
                        continue;
                    }
                    default -> throw CypherErrors.syntax("unknown escape '\\" + e + "' in a string", pos());
                }
                advance();
                continue;
            }
            sb.append(c);
            advance();
        }
    }

    private int unicode(int digits, Pos p) {
        if (at + digits > src.length()) {
            throw CypherErrors.syntax("a unicode escape needs " + digits + " hexadecimal digits", p);
        }
        int value = 0;
        for (int i = 0; i < digits; i++) {
            int d = Character.digit(src.charAt(at), 16);
            if (d < 0) {
                throw CypherErrors.syntax("a unicode escape needs " + digits + " hexadecimal digits", p);
            }
            value = value * 16 + d;
            advance();
        }
        return value;
    }

    private Token symbol(Pos p, char c) {
        char n = peek(1);
        Type t;
        int width = 1;
        switch (c) {
            case '(' -> t = Type.LPAREN;
            case ')' -> t = Type.RPAREN;
            case '[' -> t = Type.LBRACKET;
            case ']' -> t = Type.RBRACKET;
            case '{' -> t = Type.LBRACE;
            case '}' -> t = Type.RBRACE;
            case ',' -> t = Type.COMMA;
            case ':' -> t = Type.COLON;
            case ';' -> t = Type.SEMI;
            case '|' -> t = Type.PIPE;
            case '*' -> t = Type.STAR;
            case '/' -> t = Type.SLASH;
            case '%' -> t = Type.PERCENT;
            case '^' -> t = Type.CARET;
            case '.' -> {
                if (n == '.') {
                    t = Type.DOTDOT;
                    width = 2;
                } else {
                    t = Type.DOT;
                }
            }
            case '+' -> {
                if (n == '=') {
                    t = Type.PLUS_EQ;
                    width = 2;
                } else {
                    t = Type.PLUS;
                }
            }
            case '-' -> {
                if (n == '>') {
                    t = Type.ARROW_RIGHT;
                    width = 2;
                } else {
                    t = Type.MINUS;
                }
            }
            case '=' -> {
                if (n == '~') {
                    t = Type.REGEX_EQ;
                    width = 2;
                } else {
                    t = Type.EQ;
                }
            }
            case '<' -> {
                if (n == '>') {
                    t = Type.NE;
                    width = 2;
                } else if (n == '=') {
                    t = Type.LE;
                    width = 2;
                } else if (n == '-') {
                    t = Type.ARROW_LEFT;
                    width = 2;
                } else {
                    t = Type.LT;
                }
            }
            case '>' -> {
                if (n == '=') {
                    t = Type.GE;
                    width = 2;
                } else {
                    t = Type.GT;
                }
            }
            default -> throw CypherErrors.syntax("unexpected character '" + c + "'", p);
        }
        int start = at;
        String text = src.substring(at, at + width);
        for (int i = 0; i < width; i++) {
            advance();
        }
        return new Token(t, text, false, p, start, at);
    }
}
