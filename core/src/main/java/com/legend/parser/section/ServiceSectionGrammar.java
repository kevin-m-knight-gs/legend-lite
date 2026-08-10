// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.parser.section;

import com.legend.lexer.TokenType;
import com.legend.parser.SpecParser;
import com.legend.parser.TokenStreamCursor;
import com.legend.protocol.Protocol;

import java.util.ArrayList;
import java.util.List;

/**
 * THE {@code ###Service} grammar — third built-in behind the
 * {@link com.legend.spi.SectionGrammar} seam (SECTION_PROGRAM_HANDOFF.md
 * §2.6). Owns {@code Service} and {@code ExecutionEnvironment} elements to
 * the corpus-censused scope: envelope decorations, pattern / owners /
 * documentation / autoActivateUpdates, Single AND Multi executions (the
 * retired straight-to-model twin refused Multi and the legacy {@code test:}
 * block outright), and RAW-captured legacy-test / testSuites payloads.
 *
 * <p>No engine WIRE shape is claimed yet: {@code ProtocolEmitter} walls on
 * {@code PService}, and the byte-parity harness keeps Service files
 * OUT_OF_SCOPE. This grammar's job today is the parse/transform seam — the
 * drop-in surface accepting exactly the section content the engine accepts.
 */
public final class ServiceSectionGrammar implements LexableSectionGrammar {

    /** The one stateless instance the registry hands out. */
    public static final ServiceSectionGrammar INSTANCE =
            new ServiceSectionGrammar();

    private ServiceSectionGrammar() {
    }

    @Override
    public String name() {
        return "Service";
    }

    /** The SPI feed would emit protocol JSON, and Service has no claimed
     *  wire shape yet — so it walls at the emitter, loudly, per element. */
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
            case Protocol.PService s -> s.qualifiedName();
            case Protocol.PExecutionEnvironment ee -> ee.qualifiedName();
            default -> throw new IllegalStateException(
                    "not a service-section element: " + e.getClass());
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
        return com.legend.model.FromProtocol.toServiceSectionElement(element);
    }

    /** One element at the cursor: {@code Service} or
     *  {@code ExecutionEnvironment}. */
    public static Protocol.Element parseElement(TokenStreamCursor c) {
        if (c.peek() == TokenType.SERVICE) {
            return parseService(c);
        }
        if (c.isIdentifierToken(c.peek())
                && "ExecutionEnvironment".equals(c.safeText())) {
            return parseExecutionEnvironment(c);
        }
        throw c.error("unsupported ###Service element: " + c.safeText());
    }

    // ============================================================
    // Service
    // ============================================================

    private static Protocol.PService parseService(TokenStreamCursor c) {
        int declStart = c.pos();
        c.expect(TokenType.SERVICE);
        TokenStreamCursor.Decorations dec = c.parseDecorations();
        String qn = Protocol.unquotePath(c.parseQualifiedName());
        int cut = qn.lastIndexOf("::");
        String pkg = cut < 0 ? "" : qn.substring(0, cut);
        String name = cut < 0 ? qn : qn.substring(cut + 2);
        c.expect(TokenType.BRACE_OPEN);

        String pattern = null;
        String title = null;
        List<String> owners = new ArrayList<>();
        String ownershipSource = null;
        String documentation = null;
        Boolean autoActivate = null;
        Protocol.PServiceExecution execution = null;
        String testSource = null;
        String testSuitesSource = null;
        String postValidationsSource = null;

        while (!c.atEnd() && c.peek() != TokenType.BRACE_CLOSE) {
            String key = c.parseIdentifier();
            c.expect(TokenType.COLON);
            switch (key) {
                case "pattern" -> {
                    pattern = stringValue(c);
                    c.expect(TokenType.SEMI_COLON);
                }
                case "title" -> {
                    // engine ServiceParserGrammar.g4: serviceTitle
                    title = stringValue(c);
                    c.expect(TokenType.SEMI_COLON);
                }
                case "ownership" -> {
                    // ownership: Deployment {...} | UserList {...} — kind +
                    // RAW body, carried (engine: serviceOwnership rule)
                    String kind = c.parseIdentifier();
                    int bs = c.pos();
                    if (c.peek() == TokenType.BRACE_OPEN) {
                        skipBalanced(c, TokenType.BRACE_OPEN,
                                TokenType.BRACE_CLOSE);
                    }
                    ownershipSource = kind + " " + c.reconstructText(bs, c.pos());
                    c.expect(TokenType.SEMI_COLON);
                }
                case "postValidations" -> {
                    // engine: servicePostValidations — [ {...}, ... ]; RAW
                    int bs = c.pos();
                    skipBalanced(c, TokenType.BRACKET_OPEN,
                            TokenType.BRACKET_CLOSE);
                    postValidationsSource = c.reconstructText(bs, c.pos());
                    c.match(TokenType.SEMI_COLON);
                }
                case "documentation" -> {
                    documentation = stringValue(c);
                    c.expect(TokenType.SEMI_COLON);
                }
                case "autoActivateUpdates" -> {
                    autoActivate = booleanValue(c);
                    c.expect(TokenType.SEMI_COLON);
                }
                case "owners" -> {
                    c.expect(TokenType.BRACKET_OPEN);
                    while (c.peek() != TokenType.BRACKET_CLOSE && !c.atEnd()) {
                        owners.add(stringValue(c));
                        c.match(TokenType.COMMA);
                    }
                    c.expect(TokenType.BRACKET_CLOSE);
                    c.expect(TokenType.SEMI_COLON);
                }
                case "execution" -> execution = parseExecution(c, qn);
                case "test" -> {
                    // legacy single test — kind + RAW balanced body, carried
                    // (same posture as testSuites below)
                    String kind = c.parseIdentifier();
                    int bs = c.pos();
                    skipBalanced(c, TokenType.BRACE_OPEN, TokenType.BRACE_CLOSE);
                    testSource = kind + " " + c.reconstructText(bs, c.pos());
                    c.match(TokenType.SEMI_COLON);
                }
                case "testSuites" -> {
                    // the entire balanced block as raw text — D-3
                    TokenType opener = c.peek();
                    if (opener != TokenType.BRACE_OPEN
                            && opener != TokenType.BRACKET_OPEN) {
                        throw c.error("expected '{' or '[' after testSuites:,"
                                + " got " + opener);
                    }
                    int bs = c.pos();
                    skipBalanced(c, opener,
                            opener == TokenType.BRACE_OPEN
                                    ? TokenType.BRACE_CLOSE
                                    : TokenType.BRACKET_CLOSE);
                    testSuitesSource = c.reconstructText(bs, c.pos());
                    c.match(TokenType.SEMI_COLON);
                }
                default -> throw c.error("unknown key '" + key
                        + "' inside Service '" + qn + "'");
            }
        }
        c.expect(TokenType.BRACE_CLOSE);
        if (execution == null) {
            throw c.error("Service '" + qn + "' has no execution");
        }
        return new Protocol.PService(pkg, name, dec.stereotypes(),
                dec.taggedValues(), pattern, title, owners, ownershipSource,
                documentation, autoActivate, execution, testSource,
                testSuitesSource, postValidationsSource,
                c.spanOf(declStart, c.pos() - 1));
    }

    private static Protocol.PServiceExecution parseExecution(
            TokenStreamCursor c, String qn) {
        String kind = c.parseIdentifier();
        return switch (kind) {
            case "Single" -> {
                c.expect(TokenType.BRACE_OPEN);
                com.legend.protocol.spec.ValueSpecification query = null;
                String mapping = null;
                String runtime = null;
                String embeddedRuntime = null;
                while (!c.atEnd() && c.peek() != TokenType.BRACE_CLOSE) {
                    String key = c.parseIdentifier();
                    c.expect(TokenType.COLON);
                    switch (key) {
                        case "query" -> query = parseQuery(c, qn);
                        case "mapping" -> {
                            mapping = Protocol.unquotePath(c.parseQualifiedName());
                            c.expect(TokenType.SEMI_COLON);
                        }
                        case "runtime" -> {
                            if (c.peek() == TokenType.ISLAND_OPEN) {
                                // an embedded ANONYMOUS runtime — carried RAW
                                embeddedRuntime = rawIsland(c);
                            } else {
                                runtime = Protocol.unquotePath(
                                        c.parseQualifiedName());
                            }
                            c.expect(TokenType.SEMI_COLON);
                        }
                        default -> throw c.error("unknown key '" + key
                                + "' inside Service.execution");
                    }
                }
                c.expect(TokenType.BRACE_CLOSE);
                if (query == null) {
                    throw c.error("Service '" + qn + "' has no query expression");
                }
                yield new Protocol.PSingleExecution(query, mapping, runtime,
                        embeddedRuntime);
            }
            case "Multi" -> {
                c.expect(TokenType.BRACE_OPEN);
                com.legend.protocol.spec.ValueSpecification query = null;
                String executionKey = null;
                List<Protocol.PKeyedExecution> executions = new ArrayList<>();
                while (!c.atEnd() && c.peek() != TokenType.BRACE_CLOSE) {
                    String key = c.parseIdentifier();
                    if ("executions".equals(key)) {
                        // executions['QA']: { mapping: ...; runtime: ...; }
                        c.expect(TokenType.BRACKET_OPEN);
                        String keyValue = stringValue(c);
                        c.expect(TokenType.BRACKET_CLOSE);
                        c.expect(TokenType.COLON);
                        executions.add(parseKeyedBody(c, keyValue));
                        c.match(TokenType.COMMA);
                        continue;
                    }
                    c.expect(TokenType.COLON);
                    switch (key) {
                        case "query" -> query = parseQuery(c, qn);
                        case "key" -> {
                            executionKey = stringValue(c);
                            c.expect(TokenType.SEMI_COLON);
                        }
                        default -> throw c.error("unknown key '" + key
                                + "' inside Service.execution Multi");
                    }
                }
                c.expect(TokenType.BRACE_CLOSE);
                if (query == null || executionKey == null) {
                    throw c.error("Multi execution of '" + qn
                            + "' needs query and key");
                }
                yield new Protocol.PMultiExecution(query, executionKey,
                        executions);
            }
            default -> throw c.error("unsupported execution kind: " + kind
                    + " (expected Single or Multi)");
        };
    }

    /** {@code { mapping: ...; runtime: ...; }} for one keyed environment. */
    private static Protocol.PKeyedExecution parseKeyedBody(
            TokenStreamCursor c, String keyValue) {
        c.expect(TokenType.BRACE_OPEN);
        String mapping = null;
        String runtime = null;
        while (!c.atEnd() && c.peek() != TokenType.BRACE_CLOSE) {
            String key = c.parseIdentifier();
            c.expect(TokenType.COLON);
            switch (key) {
                case "mapping" -> mapping =
                        Protocol.unquotePath(c.parseQualifiedName());
                case "runtime" -> runtime =
                        Protocol.unquotePath(c.parseQualifiedName());
                default -> throw c.error(
                        "unknown key '" + key + "' inside keyed execution");
            }
            c.expect(TokenType.SEMI_COLON);
        }
        c.expect(TokenType.BRACE_CLOSE);
        return new Protocol.PKeyedExecution(keyValue, mapping, runtime);
    }

    /** {@code query: |expr...;} — scan to the top-level {@code ;} (paren
     *  depth aware) and spec-parse the slice, exactly as the retired twin
     *  did. */
    private static com.legend.protocol.spec.ValueSpecification parseQuery(
            TokenStreamCursor c, String qn) {
        c.match(TokenType.PIPE);            // optional leading '|'
        int bs = c.pos();
        int d = 0;
        while (!c.atEnd()) {
            TokenType tk = c.peek();
            switch (tk) {
                // ALL bracket kinds count — a {|let x; expr;} brace-lambda
                // query carries ';' INSIDE braces (powerbi dataspaces)
                case PAREN_OPEN, BRACE_OPEN, BRACKET_OPEN -> d++;
                case PAREN_CLOSE, BRACE_CLOSE, BRACKET_CLOSE -> d--;
                default -> { }
            }
            if (tk == TokenType.SEMI_COLON && d <= 0) {
                break;
            }
            c.advance();
        }
        if (c.pos() == bs) {
            throw c.error("empty query expression in Service '" + qn + "'");
        }
        com.legend.protocol.spec.ValueSpecification v =
                SpecParser.parse(c.tokens().slice(bs, c.pos()));
        c.expect(TokenType.SEMI_COLON);
        return v;
    }

    // ============================================================
    // ExecutionEnvironment
    // ============================================================

    private static Protocol.PExecutionEnvironment parseExecutionEnvironment(
            TokenStreamCursor c) {
        int declStart = c.pos();
        c.advance();                        // 'ExecutionEnvironment'
        String qn = Protocol.unquotePath(c.parseQualifiedName());
        int cut = qn.lastIndexOf("::");
        String pkg = cut < 0 ? "" : qn.substring(0, cut);
        String name = cut < 0 ? qn : qn.substring(cut + 2);
        c.expect(TokenType.BRACE_OPEN);
        List<Protocol.PKeyedExecution> executions = new ArrayList<>();
        while (!c.atEnd() && c.peek() != TokenType.BRACE_CLOSE) {
            String key = c.parseIdentifier();
            c.expect(TokenType.COLON);
            if (!"executions".equals(key)) {
                throw c.error("unknown key '" + key
                        + "' inside ExecutionEnvironment '" + qn + "'");
            }
            c.expect(TokenType.BRACKET_OPEN);
            while (c.peek() != TokenType.BRACKET_CLOSE && !c.atEnd()) {
                // KEY: { mapping; runtime; } — bare identifier key here,
                // unlike Multi's quoted executions['KEY']
                String keyValue = c.parseIdentifier();
                c.expect(TokenType.COLON);
                executions.add(parseKeyedBody(c, keyValue));
                c.match(TokenType.COMMA);
            }
            c.expect(TokenType.BRACKET_CLOSE);
            c.match(TokenType.SEMI_COLON);
        }
        c.expect(TokenType.BRACE_CLOSE);
        return new Protocol.PExecutionEnvironment(pkg, name, executions,
                c.spanOf(declStart, c.pos() - 1));
    }

    // ============================================================
    // Small shared helpers
    // ============================================================

    private static String stringValue(TokenStreamCursor c) {
        String quoted = c.text();
        c.expect(TokenType.STRING);
        return TokenStreamCursor.unquoteAndUnescape(quoted, c);
    }

    private static Boolean booleanValue(TokenStreamCursor c) {
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

    /** One {@code #{ ... }#} island's raw content text. */
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
