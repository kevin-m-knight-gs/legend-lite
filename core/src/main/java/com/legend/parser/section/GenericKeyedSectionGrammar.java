// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.parser.section;

import com.legend.lexer.TokenType;
import com.legend.parser.TokenStreamCursor;
import com.legend.protocol.Protocol;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The SHARED grammar for the small keyed sections — ExternalFormat,
 * FileGeneration, the function-activator family (MemSql / BigQuery /
 * HostedService / FunctionJar), Text, GenerationSpecification,
 * DataQualityValidation, ServiceStore and MongoDB. One instance per
 * section, each registered with its CENSUSED element-kind set (an open set
 * only for FileGeneration, whose engine grammar is itself generic over
 * generator kinds).
 *
 * <p>Elements come in two body styles, detected after the qualified name:
 * a keyed BRACE body ({@code Kind qn { key: value; ... }}) whose keys are
 * structured and whose values ride raw to the terminating {@code ;}
 * (depth-aware across every bracket kind, so {@code $[...]$} validation
 * trees and nested schema arrays capture whole), or a raw PAREN body
 * ({@code Kind qn ( ... )} — ServiceStore / MongoDB store definitions)
 * captured balanced.
 *
 * <p>No wire shape is claimed for any of these — emission walls, and the
 * parity out-of-scope ledger stands until a section claims bytes.
 */
public final class GenericKeyedSectionGrammar implements LexableSectionGrammar {

    private final String section;
    private final @com.legend.Nullable Set<String> kinds;

    /** {@code kinds == null} means ANY identifier heads an element (the
     *  FileGeneration posture — engine's own grammar is generic there). */
    public GenericKeyedSectionGrammar(String section,
            @com.legend.Nullable Set<String> kinds) {
        this.section = section;
        this.kinds = kinds;
    }

    @Override
    public String name() {
        return section;
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
            Protocol.PGenericSectionElement e = parseElement(c);
            out.accept(e.qualifiedName(),
                    com.legend.protocol.ProtocolEmitter.emitElement(e));
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
        Protocol.PGenericSectionElement g =
                (Protocol.PGenericSectionElement) element;
        return new com.legend.model.GenericSectionElementDefinition(
                g.section(), g.kind(), g.qualifiedName(), g.fields(),
                g.bodySource());
    }

    private Protocol.PGenericSectionElement parseElement(TokenStreamCursor c) {
        int declStart = c.pos();
        if (!c.isIdentifierToken(c.peek())) {
            throw c.error("unsupported ###" + section + " element: "
                    + c.safeText());
        }
        String kind = c.safeText();
        if (kinds != null && !kinds.contains(kind)) {
            throw c.error("unsupported ###" + section + " element: " + kind);
        }
        c.advance();
        TokenStreamCursor.Decorations dec = c.parseDecorations();
        String qn = Protocol.unquotePath(c.parseQualifiedName());
        int cut = qn.lastIndexOf("::");
        String pkg = cut < 0 ? "" : qn.substring(0, cut);
        String name = cut < 0 ? qn : qn.substring(cut + 2);

        java.util.Map<String, String> fields = new java.util.LinkedHashMap<>();
        String bodySource = null;
        if (c.peek() == TokenType.PAREN_OPEN) {
            // store-definition style (ServiceStore / MongoDB Database)
            int bs = c.pos();
            skipBalanced(c, TokenType.PAREN_OPEN, TokenType.PAREN_CLOSE);
            bodySource = c.reconstructText(bs, c.pos());
        } else {
            c.expect(TokenType.BRACE_OPEN);
            while (!c.atEnd() && c.peek() != TokenType.BRACE_CLOSE) {
                String key = c.parseIdentifier();
                c.expect(TokenType.COLON);
                fields.put(key, rawToSemicolon(c));
                c.expect(TokenType.SEMI_COLON);
            }
            c.expect(TokenType.BRACE_CLOSE);
        }
        return new Protocol.PGenericSectionElement(pkg, name, section, kind,
                dec.stereotypes(), dec.taggedValues(),
                java.util.Map.copyOf(fields), bodySource,
                c.spanOf(declStart, c.pos() - 1));
    }

    /** Raw value text to the terminating top-level {@code ;} — depth-aware
     *  across every bracket kind, so {@code $[...]$} trees, nested arrays
     *  and lambdas capture whole. */
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
            if (tk == TokenType.BRACE_CLOSE && d < 0) {
                break;      // an element body closing without the final ';'
            }
            c.advance();
        }
        return c.reconstructText(bs, c.pos());
    }

    private static void skipBalanced(TokenStreamCursor c, TokenType open,
            TokenType close) {
        c.expect(open);
        int depth = 1;
        while (!c.atEnd() && depth > 0) {
            TokenType t = c.peek();
            if (t == open) {
                depth++;
            } else if (t == close) {
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
