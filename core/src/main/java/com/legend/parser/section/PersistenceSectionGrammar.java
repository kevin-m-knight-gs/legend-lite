// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.parser.section;

import com.legend.lexer.TokenType;
import com.legend.parser.TokenStreamCursor;
import com.legend.protocol.Protocol;

import java.util.ArrayList;
import java.util.List;

/**
 * THE {@code ###Persistence} grammar — sixth built-in behind the
 * {@link com.legend.spi.SectionGrammar} seam. Owns {@code Persistence} and
 * {@code PersistenceContext} elements to the corpus-censused scope: the
 * TOP-LEVEL keys are structured (doc, service, persistence pointer, trigger
 * kind) and the deep sub-DSLs (persister, serviceOutputTargets, tests,
 * notifier, platform, serviceParameters, sinkConnection) ride as RAW
 * balanced blocks — the sentinel's LENIENT ratchet arbitrates whether that
 * capture is too blind, and structures deeper if it ever grows.
 *
 * <p>No WIRE shape claimed — emission walls; parity oos unchanged.
 */
public final class PersistenceSectionGrammar implements LexableSectionGrammar {

    /** The one stateless instance the registry hands out. */
    public static final PersistenceSectionGrammar INSTANCE =
            new PersistenceSectionGrammar();

    private PersistenceSectionGrammar() {
    }

    @Override
    public String name() {
        return "Persistence";
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
            Protocol.Element e = parseElement(c);
            out.accept(qualifiedNameOf(e),
                    com.legend.protocol.ProtocolEmitter.emitElement(e));
        }
    }

    private static String qualifiedNameOf(Protocol.Element e) {
        return switch (e) {
            case Protocol.PPersistence p -> p.qualifiedName();
            case Protocol.PPersistenceContext p -> p.qualifiedName();
            default -> throw new IllegalStateException(
                    "not a persistence-section element: " + e.getClass());
        };
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
        return com.legend.model.FromProtocol.toPersistenceElement(element);
    }

    /** One element: {@code Persistence} or {@code PersistenceContext}. */
    public static Protocol.Element parseElement(TokenStreamCursor c) {
        if (!c.isIdentifierToken(c.peek())) {
            throw c.error("unsupported ###Persistence element: " + c.safeText());
        }
        return switch (c.safeText()) {
            case "Persistence" -> parsePersistence(c);
            case "PersistenceContext" -> parseContext(c);
            default -> throw c.error(
                    "unsupported ###Persistence element: " + c.safeText());
        };
    }

    private static Protocol.PPersistence parsePersistence(TokenStreamCursor c) {
        int declStart = c.pos();
        c.advance();                                // 'Persistence'
        TokenStreamCursor.Decorations dec = c.parseDecorations();
        String qn = Protocol.unquotePath(c.parseQualifiedName());
        int cut = qn.lastIndexOf("::");
        String pkg = cut < 0 ? "" : qn.substring(0, cut);
        String name = cut < 0 ? qn : qn.substring(cut + 2);
        c.expect(TokenType.BRACE_OPEN);

        String doc = null;
        String triggerSource = null;
        String service = null;
        String persisterSource = null;
        String serviceOutputTargetsSource = null;
        String notifierSource = null;
        String testsSource = null;

        while (!c.atEnd() && c.peek() != TokenType.BRACE_CLOSE) {
            String key = c.parseIdentifier();
            c.expect(TokenType.COLON);
            switch (key) {
                case "doc" -> {
                    doc = stringValue(c);
                    c.expect(TokenType.SEMI_COLON);
                }
                case "service" -> {
                    service = Protocol.unquotePath(c.parseQualifiedName());
                    c.expect(TokenType.SEMI_COLON);
                }
                case "trigger" -> {
                    // the OLD grammar omits the ';' after a block value
                    triggerSource = kindWithRawBody(c);
                    c.match(TokenType.SEMI_COLON);
                }
                case "persister" -> {
                    persisterSource = parsePersisterValidated(c);
                    c.match(TokenType.SEMI_COLON);
                }
                case "serviceOutputTargets" -> {
                    serviceOutputTargetsSource = rawBalanced(c);
                    c.match(TokenType.SEMI_COLON);
                }
                case "notifier" -> {
                    notifierSource = rawBalanced(c);
                    c.match(TokenType.SEMI_COLON);
                }
                case "tests" -> {
                    testsSource = parseTestsValidated(c);
                    c.match(TokenType.SEMI_COLON);
                }
                default -> throw c.error("unknown key '" + key
                        + "' inside Persistence '" + qn + "'");
            }
        }
        c.expect(TokenType.BRACE_CLOSE);
        return new Protocol.PPersistence(pkg, name, dec.stereotypes(),
                dec.taggedValues(), doc, triggerSource, service,
                persisterSource, serviceOutputTargetsSource, notifierSource,
                testsSource, c.spanOf(declStart, c.pos() - 1));
    }

    private static Protocol.PPersistenceContext parseContext(
            TokenStreamCursor c) {
        int declStart = c.pos();
        c.advance();                                // 'PersistenceContext'
        TokenStreamCursor.Decorations dec = c.parseDecorations();
        String qn = Protocol.unquotePath(c.parseQualifiedName());
        int cut = qn.lastIndexOf("::");
        String pkg = cut < 0 ? "" : qn.substring(0, cut);
        String name = cut < 0 ? qn : qn.substring(cut + 2);
        c.expect(TokenType.BRACE_OPEN);

        String persistence = null;
        String platformSource = null;
        String serviceParametersSource = null;
        String sinkConnectionSource = null;

        while (!c.atEnd() && c.peek() != TokenType.BRACE_CLOSE) {
            String key = c.parseIdentifier();
            c.expect(TokenType.COLON);
            switch (key) {
                case "persistence" -> {
                    persistence = Protocol.unquotePath(c.parseQualifiedName());
                    c.expect(TokenType.SEMI_COLON);
                }
                case "platform" -> {
                    platformSource = kindWithRawBody(c);
                    c.match(TokenType.SEMI_COLON);
                }
                case "serviceParameters" -> {
                    serviceParametersSource = rawBalanced(c);
                    c.match(TokenType.SEMI_COLON);
                }
                case "sinkConnection" -> {
                    sinkConnectionSource = kindWithRawBody(c);
                    c.match(TokenType.SEMI_COLON);
                }
                default -> throw c.error("unknown key '" + key
                        + "' inside PersistenceContext '" + qn + "'");
            }
        }
        c.expect(TokenType.BRACE_CLOSE);
        if (persistence == null) {
            throw c.error("PersistenceContext '" + qn
                    + "' needs a persistence pointer");
        }
        return new Protocol.PPersistenceContext(pkg, name, dec.stereotypes(),
                dec.taggedValues(), persistence, platformSource,
                serviceParametersSource, sinkConnectionSource,
                c.spanOf(declStart, c.pos() - 1));
    }

    /**
     * {@code Batch | Streaming { sink: ...; targetShape: ...; ingestMode:
     * ...; }} — parsed one level for the engine's PARSE-TIME validations
     * (its negative fixtures pin them), carried as written. The sink's
     * required field is per kind: {@code Relational} needs {@code
     * database}, {@code ObjectStorage} needs {@code binding}
     * (PersistenceParserGrammar.g4:406-417 + the walker's
     * validateAndExtractRequiredField).
     */
    private static String parsePersisterValidated(TokenStreamCursor c) {
        int start = c.pos();
        String kind = c.parseIdentifier();
        if (c.peek() == TokenType.BRACE_OPEN) {
            c.advance();
            while (!c.atEnd() && c.peek() != TokenType.BRACE_CLOSE) {
                String key = c.parseIdentifier();
                c.expect(TokenType.COLON);
                if ("sink".equals(key)) {
                    String sinkKind = c.parseIdentifier();
                    c.expect(TokenType.BRACE_OPEN);
                    java.util.Set<String> seen = new java.util.HashSet<>();
                    while (!c.atEnd() && c.peek() != TokenType.BRACE_CLOSE) {
                        String sk = c.parseIdentifier();
                        c.expect(TokenType.COLON);
                        c.parseQualifiedName();
                        c.expect(TokenType.SEMI_COLON);
                        seen.add(sk);
                    }
                    c.expect(TokenType.BRACE_CLOSE);
                    if ("Relational".equals(sinkKind)
                            && !seen.contains("database")) {
                        throw c.error("Field 'database' is required");
                    }
                    if ("ObjectStorage".equals(sinkKind)
                            && !seen.contains("binding")) {
                        throw c.error("Field 'binding' is required");
                    }
                } else {
                    // targetShape / ingestMode / deduplication / ... —
                    // kind + raw body, carried
                    kindWithRawBody(c);
                }
                c.match(TokenType.SEMI_COLON);
            }
            c.expect(TokenType.BRACE_CLOSE);
        }
        return kind.isEmpty() ? "" : c.reconstructText(start, c.pos());
    }

    /**
     * {@code tests: [ name: { testBatches: [ id: { data: { connection:
     * {...} } asserts: [...] } ] } ]} — structured to the batch level for
     * the engine's parse-time validations: {@code data} needs a
     * {@code connection} (the grammar itself), a batch needs non-empty
     * test data, and {@code asserts} is required (walker rules 828+).
     */
    private static String parseTestsValidated(TokenStreamCursor c) {
        int start = c.pos();
        c.expect(TokenType.BRACKET_OPEN);
        while (!c.atEnd() && c.peek() != TokenType.BRACKET_CLOSE) {
            c.parseIdentifier();                    // test name
            c.expect(TokenType.COLON);
            c.expect(TokenType.BRACE_OPEN);
            while (!c.atEnd() && c.peek() != TokenType.BRACE_CLOSE) {
                String key = c.parseIdentifier();
                c.expect(TokenType.COLON);
                if ("testBatches".equals(key)) {
                    c.expect(TokenType.BRACKET_OPEN);
                    while (!c.atEnd() && c.peek() != TokenType.BRACKET_CLOSE) {
                        parseTestBatch(c);
                        c.match(TokenType.COMMA);
                    }
                    c.expect(TokenType.BRACKET_CLOSE);
                } else {
                    // isTestDataFromServiceOutput / graphFetchPath
                    rawToSemi(c);
                    c.expect(TokenType.SEMI_COLON);
                }
            }
            c.expect(TokenType.BRACE_CLOSE);
            c.match(TokenType.COMMA);
        }
        c.expect(TokenType.BRACKET_CLOSE);
        return c.reconstructText(start, c.pos());
    }

    private static void parseTestBatch(TokenStreamCursor c) {
        c.parseIdentifier();                        // batch id
        c.expect(TokenType.COLON);
        c.expect(TokenType.BRACE_OPEN);
        boolean sawData = false;
        boolean sawAsserts = false;
        while (!c.atEnd() && c.peek() != TokenType.BRACE_CLOSE) {
            String key = c.parseIdentifier();
            c.expect(TokenType.COLON);
            if ("data".equals(key)) {
                c.expect(TokenType.BRACE_OPEN);
                boolean sawConnection = false;
                while (!c.atEnd() && c.peek() != TokenType.BRACE_CLOSE) {
                    String dk = c.parseIdentifier();
                    if (!"connection".equals(dk)) {
                        throw c.error("Unexpected token '" + dk
                                + "'. Valid alternatives: ['connection']");
                    }
                    c.expect(TokenType.COLON);
                    c.expect(TokenType.BRACE_OPEN);
                    c.parseIdentifier();            // embedded data kind
                    rawIsland(c);
                    c.expect(TokenType.BRACE_CLOSE);
                    sawConnection = true;
                }
                if (!sawConnection) {
                    throw c.error("Unexpected token '}'. Valid alternatives:"
                            + " ['connection']");
                }
                c.expect(TokenType.BRACE_CLOSE);
                sawData = true;
            } else if ("asserts".equals(key)) {
                c.expect(TokenType.BRACKET_OPEN);
                while (!c.atEnd() && c.peek() != TokenType.BRACKET_CLOSE) {
                    c.parseIdentifier();            // assert id
                    c.expect(TokenType.COLON);
                    c.parseIdentifier();            // assertion kind
                    rawIsland(c);
                    c.match(TokenType.COMMA);
                }
                c.expect(TokenType.BRACKET_CLOSE);
                sawAsserts = true;
            } else {
                throw c.error("unknown test-batch key: " + key);
            }
        }
        c.expect(TokenType.BRACE_CLOSE);
        if (!sawData) {
            // walker rule (PersistenceParseTreeWalker:828)
            throw c.error("TestData cannot be empty or null within"
                    + " Persistence TestBatch");
        }
        if (!sawAsserts) {
            throw c.error("Field 'asserts' is required");
        }
    }

    /** Raw value to the next top-level {@code ;} (not consumed). */
    private static void rawToSemi(TokenStreamCursor c) {
        int d = 0;
        while (!c.atEnd()) {
            TokenType tk = c.peek();
            switch (tk) {
                case PAREN_OPEN, BRACE_OPEN, BRACKET_OPEN -> d++;
                case PAREN_CLOSE, BRACE_CLOSE, BRACKET_CLOSE -> d--;
                default -> { }
            }
            if (tk == TokenType.SEMI_COLON && d <= 0) {
                return;
            }
            c.advance();
        }
    }

    /** {@code Kind} or {@code Kind { ... }} or a bare {@code #{...}#}
     *  island (sinkConnection can be an embedded connection) — kept as
     *  written. */
    private static String kindWithRawBody(TokenStreamCursor c) {
        if (c.peek() == TokenType.ISLAND_OPEN) {
            return "#{" + rawIsland(c) + "}#";
        }
        // a POINTER value (sinkConnection: test::Conn) parses the same way
        // a kind does — qualified, then no body follows
        String kind = Protocol.unquotePath(c.parseQualifiedName());
        if (c.peek() == TokenType.BRACE_OPEN) {
            int bs = c.pos();
            skipBalanced(c, TokenType.BRACE_OPEN, TokenType.BRACE_CLOSE);
            return kind + " " + c.reconstructText(bs, c.pos());
        }
        if (c.peek() == TokenType.ISLAND_OPEN) {
            // platform: AwsGlue #{ ... }# — kind + ISLAND body
            return kind + " #{" + rawIsland(c) + "}#";
        }
        return kind;
    }

    /** A raw balanced {@code { ... }} or {@code [ ... ]} block as written. */
    private static String rawBalanced(TokenStreamCursor c) {
        TokenType opener = c.peek();
        if (opener != TokenType.BRACE_OPEN && opener != TokenType.BRACKET_OPEN) {
            throw c.error("expected '{' or '[', got " + opener);
        }
        int bs = c.pos();
        skipBalanced(c, opener, opener == TokenType.BRACE_OPEN
                ? TokenType.BRACE_CLOSE : TokenType.BRACKET_CLOSE);
        return c.reconstructText(bs, c.pos());
    }

    private static String rawIsland(TokenStreamCursor c) {
        c.advance();                                // ISLAND_OPEN
        int bs = c.pos();
        int depth = 0;
        while (!c.atEnd()) {
            TokenType t = c.peek();
            if (t == TokenType.ISLAND_START) {
                depth++;                // a NESTED #...{ island opened
            } else if (t == TokenType.ISLAND_END) {
                if (depth == 0) {
                    break;
                }
                depth--;
            }
            c.advance();
        }
        String raw = c.reconstructText(bs, c.pos());
        c.expect(TokenType.ISLAND_END);
        return raw;
    }

    private static String stringValue(TokenStreamCursor c) {
        String quoted = c.text();
        c.expect(TokenType.STRING);
        return TokenStreamCursor.unquoteAndUnescape(quoted, c);
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
