// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.parser.section;

import com.legend.lexer.Lexer;
import com.legend.lexer.TokenType;
import com.legend.parser.TokenStreamCursor;
import com.legend.protocol.Protocol;
import com.legend.protocol.ProtocolEmitter;

import java.util.ArrayList;
import java.util.List;

/**
 * THE {@code ###Runtime} grammar — second built-in behind the
 * {@link com.legend.spi.SectionGrammar} seam (SECTION_PROGRAM_HANDOFF.md
 * §2.6, same shape as {@link ConnectionSectionGrammar}). Owns
 * {@code Runtime} and {@code SingleConnectionRuntime} elements with full
 * wire fidelity — connection IDs, source order, per-node spans (ZRuntimeProbe
 * pins the byte shapes). Embedded {@code #{ <Flavor> {...} }#} islands
 * delegate to the Connection grammar, which owns every connection VALUE
 * spelling.
 */
public final class RuntimeSectionGrammar implements LexableSectionGrammar {

    /** The one stateless instance the registry hands out. */
    public static final RuntimeSectionGrammar INSTANCE =
            new RuntimeSectionGrammar();

    private RuntimeSectionGrammar() {
    }

    @Override
    public String name() {
        return "Runtime";
    }

    /** The SPI feed: re-lex and drive the shared parse (spans slice-relative,
     *  like {@link ConnectionSectionGrammar#parse}). */
    @Override
    public void parse(com.legend.spi.SectionSource src,
            com.legend.spi.ElementSink out) {
        var c = new SliceCursor(Lexer.tokenize(src.text()));
        while (!c.atEnd()) {
            if (c.peek() == TokenType.IMPORT) {
                SectionImports.parseImport(c);
                continue;
            }
            Protocol.PRuntime pr = parseElement(c);
            out.accept(pr.qualifiedName(), ProtocolEmitter.emitElement(pr));
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
        try {
            return com.legend.model.FromProtocol.toRuntimeElement(
                    (Protocol.PRuntime) element);
        } catch (com.legend.model.FromProtocol.UnsupportedConnectionShape u) {
            throw new UnsupportedElementShape(u.reason());
        }
    }

    /**
     * One {@code Runtime | SingleConnectionRuntime qn { ... }} element at
     * the cursor, into its protocol record — full wire fidelity; the
     * execution-path {@code RuntimeDefinition} derives from this, so there
     * is ONE runtime grammar.
     */
    public static Protocol.PRuntime parseElement(TokenStreamCursor c) {
        int declStart = c.pos();
        boolean single = c.peek() == TokenType.SINGLE_CONNECTION_RUNTIME;
        c.advance();                        // Runtime | SingleConnectionRuntime
        String qn = Protocol.unquotePath(c.parseQualifiedName());
        int cut = qn.lastIndexOf("::");
        String pkg = cut < 0 ? "" : qn.substring(0, cut);
        String name = cut < 0 ? qn : qn.substring(cut + 2);
        c.expect(TokenType.BRACE_OPEN);
        List<Protocol.PPointer> mappings = new ArrayList<>();
        List<Protocol.PStoreConnections> connections = new ArrayList<>();
        List<Protocol.PConnectionStores> connectionStores = new ArrayList<>();
        int mappingsKeys = parseBodyKeys(c, single, qn,
                TokenType.BRACE_CLOSE, mappings, connections,
                connectionStores);
        if (mappingsKeys == 0) {
            throw c.error("Field 'mappings' is required");
        }
        c.expect(TokenType.BRACE_CLOSE);
        return new Protocol.PRuntime(pkg, name, single, mappings, connections,
                connectionStores, c.spanOf(declStart, c.pos() - 1));
    }

    private static void parseConnections(TokenStreamCursor c,
            List<Protocol.PStoreConnections> out) {
        c.expect(TokenType.BRACKET_OPEN);
        while (c.peek() != TokenType.BRACKET_CLOSE && !c.atEnd()) {
            int sStart = c.pos();
            String store = Protocol.unquotePath(c.parseQualifiedName());
            Protocol.PPointer storePtr = new Protocol.PPointer("STORE", store,
                    c.spanOf(sStart, c.pos() - 1));
            c.expect(TokenType.COLON);
            c.expect(TokenType.BRACKET_OPEN);
            List<Protocol.PIdentifiedConnection> ids = new ArrayList<>();
            while (c.peek() != TokenType.BRACKET_CLOSE && !c.atEnd()) {
                int idStart = c.pos();
                String id = c.parseIdentifier();
                c.expect(TokenType.COLON);
                Protocol.PConnectionValue value;
                if (c.peek() == TokenType.ISLAND_OPEN) {
                    value = parseEmbeddedConnection(c);
                } else {
                    int cStart = c.pos();
                    String conn = Protocol.unquotePath(c.parseQualifiedName());
                    value = new Protocol.PConnectionPointer(conn,
                            c.spanOf(cStart, c.pos() - 1));
                }
                ids.add(new Protocol.PIdentifiedConnection(id, value,
                        c.spanOf(idStart, c.pos() - 1)));
                c.match(TokenType.COMMA);
            }
            int groupEnd = c.pos();
            c.expect(TokenType.BRACKET_CLOSE);
            out.add(new Protocol.PStoreConnections(storePtr, ids,
                    c.spanOf(sStart, groupEnd)));
            c.match(TokenType.COMMA);
        }
        c.expect(TokenType.BRACKET_CLOSE);
        c.match(TokenType.SEMI_COLON);
    }

    /** Embedded {@code #{ <Flavor> {...} }#} island — extract the coarse
     *  chunks and hand the Connection grammar the raw text with the
     *  engine's walker-offset reparse rule, so spans stay file-absolute. */
    private static Protocol.PConnectionValue parseEmbeddedConnection(
            TokenStreamCursor c) {
        c.advance();                                // ISLAND_OPEN
        int embStart = c.pos();
        while (c.peek() != TokenType.ISLAND_END && !c.atEnd()) {
            c.advance();
        }
        int embEnd = c.pos();
        c.expect(TokenType.ISLAND_END);
        String emb = c.reconstructText(embStart, embEnd);
        int baseLine = c.tokens().startLine(embStart);
        int baseCol = c.tokens().startColumn(embStart);
        if (c instanceof SliceCursorOffset oc) {
            // offsets COMPOSE when the runtime itself is a re-lexed
            // service island (spans stay file-absolute)
            baseLine += oc.lineOffset;
            baseCol += oc.colOffset;
        }
        return ConnectionSectionGrammar.parseIslandValue(emb, baseLine,
                baseCol);
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

    /** The runtime BODY key loop (mappings/connections/connectionStores) —
     *  shared by the section element and embedded service islands
     *  ({@code close == null} runs to the end of a re-lexed island).
     *  Returns how many times the {@code mappings} key appeared (the
     *  caller's required-field check). ENGINE-VERBATIM field-cardinality
     *  validation (harvest TestRuntimeGrammarParser#testRuntime): counts
     *  are FIELD OCCURRENCES, and the connectionStores once-message names
     *  the PROTOCOL field ('connectionPointerStores'), not the source
     *  key. */
    static int parseBodyKeys(TokenStreamCursor c, boolean single, String qn,
            @com.legend.Nullable TokenType close,
            List<Protocol.PPointer> mappings,
            List<Protocol.PStoreConnections> connections,
            List<Protocol.PConnectionStores> connectionStores) {
        int seenMappings = 0;
        int seenConnections = 0;
        int seenConnStores = 0;
        while (!c.atEnd() && (close == null || c.peek() != close)) {
            TokenType key = c.peek();
            String keyText = c.safeText();
            c.advance();
            c.expect(TokenType.COLON);
            if (key == TokenType.MAPPINGS && ++seenMappings > 1) {
                throw c.error("Field 'mappings' should be specified"
                        + " only once");
            }
            if (key == TokenType.CONNECTIONS && !single
                    && ++seenConnections > 1) {
                throw c.error("Field 'connections' should be specified"
                        + " only once");
            }
            if ("connectionStores".equals(keyText) && !single
                    && ++seenConnStores > 1) {
                throw c.error("Field 'connectionPointerStores' should be"
                        + " specified only once");
            }
            if (key == TokenType.MAPPINGS) {
                c.expect(TokenType.BRACKET_OPEN);
                while (c.peek() != TokenType.BRACKET_CLOSE && !c.atEnd()) {
                    int mStart = c.pos();
                    String path = Protocol.unquotePath(c.parseQualifiedName());
                    mappings.add(new Protocol.PPointer("MAPPING", path,
                            c.spanOf(mStart, c.pos() - 1)));
                    if (!c.match(TokenType.COMMA)) {
                        break;
                    }
                }
                c.expect(TokenType.BRACKET_CLOSE);
                c.match(TokenType.SEMI_COLON);
            } else if (key == TokenType.CONNECTIONS && !single) {
                parseConnections(c, connections);
            } else if (!single && "connectionStores".equals(keyText)) {
                c.expect(TokenType.BRACKET_OPEN);
                while (c.peek() != TokenType.BRACKET_CLOSE && !c.atEnd()) {
                    int cStart = c.pos();
                    String conn = Protocol.unquotePath(c.parseQualifiedName());
                    com.legend.protocol.SourceInfo cSpan =
                            c.spanOf(cStart, c.pos() - 1);
                    c.expect(TokenType.COLON);
                    c.expect(TokenType.BRACKET_OPEN);
                    List<Protocol.PStorePointer> stores = new ArrayList<>();
                    while (c.peek() != TokenType.BRACKET_CLOSE && !c.atEnd()) {
                        int pStart = c.pos();
                        stores.add(new Protocol.PStorePointer(
                                Protocol.unquotePath(c.parseQualifiedName()),
                                c.spanOf(pStart, c.pos() - 1)));
                        c.match(TokenType.COMMA);
                    }
                    int groupEnd = c.pos();
                    c.expect(TokenType.BRACKET_CLOSE);
                    connectionStores.add(new Protocol.PConnectionStores(
                            new Protocol.PConnectionPointer(conn, cSpan),
                            stores, c.spanOf(cStart, groupEnd)));
                    c.match(TokenType.COMMA);
                }
                c.expect(TokenType.BRACKET_CLOSE);
                c.match(TokenType.SEMI_COLON);
            } else if (key == TokenType.CONNECTION && single) {
                int cStart = c.pos();
                String conn = Protocol.unquotePath(c.parseQualifiedName());
                com.legend.protocol.SourceInfo cSpan =
                        c.spanOf(cStart, c.pos() - 1);
                connectionStores.add(new Protocol.PConnectionStores(
                        new Protocol.PConnectionPointer(conn, cSpan),
                        List.of(), cSpan));
                c.match(TokenType.SEMI_COLON);
            } else {
                throw c.error("unknown key '" + keyText + "' inside "
                        + (single ? "SingleConnectionRuntime" : "Runtime")
                        + " '" + qn + "'");
            }
        }
        return seenMappings;
    }

    /** An embedded anonymous runtime island body, re-lexed with the walker
     *  offset rule so spans stay file-absolute; the wire span is
     *  CONTENT-anchored (first..last content token). */
    public static Protocol.PEmbeddedRuntime parseEmbeddedBody(
            String islandText, int baseLine, int baseColumn) {
        var tokens = com.legend.lexer.Lexer.tokenize(islandText);
        var ic = new SliceCursorOffset(tokens, baseLine - 1, baseColumn - 1);
        // the wire span is CONTENT-anchored: first..last re-lexed token
        com.legend.protocol.SourceInfo contentSpan = tokens.count() > 0
                ? ic.spanOf(0, tokens.count() - 1)
                : new com.legend.protocol.SourceInfo("", baseLine,
                        baseColumn, baseLine, baseColumn);
        List<Protocol.PPointer> mappings = new ArrayList<>();
        List<Protocol.PStoreConnections> connections = new ArrayList<>();
        List<Protocol.PConnectionStores> connectionStores = new ArrayList<>();
        parseBodyKeys(ic, false, "<embedded>", null, mappings, connections,
                connectionStores);
        return new Protocol.PEmbeddedRuntime(mappings, connections,
                connectionStores, contentSpan);
    }

    /** Offset cursor for island re-lex (same shape as the connection
     *  grammar's). */
    private static final class SliceCursorOffset implements TokenStreamCursor {

        private final com.legend.lexer.TokenStream tokens;
        private int pos;
        private final int lineOffset;
        private final int colOffset;

        SliceCursorOffset(com.legend.lexer.TokenStream tokens, int lineOffset,
                int colOffset) {
            this.tokens = tokens;
            this.lineOffset = lineOffset;
            this.colOffset = colOffset;
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

        @Override
        public com.legend.protocol.SourceInfo spanOf(int fromTok, int toTok) {
            return TokenStreamCursor.shiftIsland(
                    TokenStreamCursor.super.spanOf(fromTok, toTok),
                    lineOffset, colOffset);
        }
    }

}
