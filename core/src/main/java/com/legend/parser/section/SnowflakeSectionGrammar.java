// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.parser.section;

import com.legend.lexer.TokenType;
import com.legend.parser.TokenStreamCursor;
import com.legend.protocol.Protocol;

import java.util.ArrayList;
import java.util.List;

/**
 * THE {@code ###Snowflake} grammar — {@code SnowflakeApp} and
 * {@code SnowflakeM2MUdf} function activators, corpus-censused scope:
 * key-value bodies (applicationName / udfName, description,
 * deploymentSchema/Stage, usageRole), {@code ownership} raw-carried,
 * {@code function} as a full function pointer with its signature, and
 * {@code activationConfiguration} as a pointer. No wire shape claimed —
 * emission walls.
 */
public final class SnowflakeSectionGrammar implements LexableSectionGrammar {

    /** The one stateless instance the registry hands out. */
    public static final SnowflakeSectionGrammar INSTANCE =
            new SnowflakeSectionGrammar();

    private SnowflakeSectionGrammar() {
    }

    @Override
    public String name() {
        return "Snowflake";
    }

    @Override
    public void parse(com.legend.spi.SectionSource src,
            com.legend.spi.ElementSink out) {
        var c = new SliceCursor(com.legend.lexer.Lexer.tokenize(src.text()));
        while (!c.atEnd()) {
            if (c.peek() == TokenType.IMPORT) {
                SectionImports.parseImport(c);
                continue;
            }
            Protocol.PSnowflakeActivator a = parseElement(c);
            out.accept(a.qualifiedName(),
                    com.legend.protocol.ProtocolEmitter.emitElement(a));
        }
    }

    @Override
    public ParsedSection parseSection(TokenStreamCursor host,
            int sectionEndOffset) {
        List<ParsedElement> elements = new ArrayList<>();
        List<String> imports = new ArrayList<>();
        while (!host.atEnd()
                && host.tokens().start(host.pos()) < sectionEndOffset) {
            if (host.peek() == TokenType.IMPORT) {
                imports.add(SectionImports.parseImport(host));
                continue;
            }
            int at = host.tokens().start(host.pos());
            elements.add(new ParsedElement(parseElement(host), at));
        }
        return new ParsedSection(elements, imports);
    }

    @Override
    public com.legend.model.PackageableElement toModel(Protocol.Element element) {
        return com.legend.model.FromProtocol.toSnowflakeActivator(
                (Protocol.PSnowflakeActivator) element);
    }

    /** One {@code SnowflakeApp | SnowflakeM2MUdf qn { key: value; ... }}. */
    public static Protocol.PSnowflakeActivator parseElement(TokenStreamCursor c) {
        int declStart = c.pos();
        if (!c.isIdentifierToken(c.peek())) {
            throw c.error("unsupported ###Snowflake element: " + c.safeText());
        }
        String kind = c.safeText();
        if (!"SnowflakeApp".equals(kind) && !"SnowflakeM2MUdf".equals(kind)) {
            throw c.error("unsupported ###Snowflake element: " + kind);
        }
        c.advance();
        TokenStreamCursor.Decorations dec = c.parseDecorations();
        String qn = Protocol.unquotePath(c.parseQualifiedName());
        int cut = qn.lastIndexOf("::");
        String pkg = cut < 0 ? "" : qn.substring(0, cut);
        String name = cut < 0 ? qn : qn.substring(cut + 2);
        c.expect(TokenType.BRACE_OPEN);

        java.util.Map<String, String> fields = new java.util.LinkedHashMap<>();
        while (!c.atEnd() && c.peek() != TokenType.BRACE_CLOSE) {
            String key = c.parseIdentifier();
            c.expect(TokenType.COLON);
            switch (key) {
                case "applicationName", "udfName", "description",
                        "deploymentSchema", "deploymentStage", "usageRole" ->
                        fields.put(key, stringValue(c));
                case "function" -> {
                    // a full function pointer: path():Type[mult]
                    String fn = Protocol.unquotePath(c.parseQualifiedName());
                    if (c.peek() == TokenType.PAREN_OPEN) {
                        fn += rawToSemicolon(c);
                    }
                    fields.put(key, fn);
                }
                case "activationConfiguration" -> fields.put(key,
                        Protocol.unquotePath(c.parseQualifiedName()));
                case "ownership" -> {
                    String oKind = c.parseIdentifier();
                    int bs = c.pos();
                    if (c.peek() == TokenType.BRACE_OPEN) {
                        skipBalanced(c);
                    }
                    fields.put(key, oKind + " " + c.reconstructText(bs, c.pos()));
                }
                default -> throw c.error("unknown key '" + key + "' inside "
                        + kind + " '" + qn + "'");
            }
            c.expect(TokenType.SEMI_COLON);
        }
        c.expect(TokenType.BRACE_CLOSE);
        return new Protocol.PSnowflakeActivator(pkg, name, kind,
                dec.stereotypes(), dec.taggedValues(),
                java.util.Map.copyOf(fields), c.spanOf(declStart, c.pos() - 1));
    }

    private static String rawToSemicolon(TokenStreamCursor c) {
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
        return c.reconstructText(bs, c.pos());
    }

    private static String stringValue(TokenStreamCursor c) {
        String quoted = c.text();
        c.expect(TokenType.STRING);
        return TokenStreamCursor.unquoteAndUnescape(quoted, c);
    }

    private static void skipBalanced(TokenStreamCursor c) {
        c.expect(TokenType.BRACE_OPEN);
        int depth = 1;
        while (!c.atEnd() && depth > 0) {
            TokenType t = c.peek();
            if (t == TokenType.BRACE_OPEN) {
                depth++;
            } else if (t == TokenType.BRACE_CLOSE) {
                depth--;
            }
            c.advance();
        }
    }

    /** A minimal cursor over a re-lexed slice for the SPI feed. */
    private static final class SliceCursor implements TokenStreamCursor {

        private final com.legend.lexer.TokenStream tokens;
        private int pos;

        SliceCursor(com.legend.lexer.TokenStream tokens) {
            this.tokens = tokens;
        }

        @Override
        public com.legend.lexer.TokenStream tokens() {
            return tokens;
        }

        @Override
        public int pos() {
            return pos;
        }

        @Override
        public void setPos(int pos) {
            this.pos = pos;
        }
    }
}
