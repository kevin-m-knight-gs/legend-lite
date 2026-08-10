// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.parser.section;

import com.legend.lexer.TokenType;
import com.legend.parser.TokenStreamCursor;
import com.legend.protocol.Protocol;

import java.util.ArrayList;
import java.util.List;

/**
 * THE {@code ###DataSpace} grammar — fifth built-in behind the
 * {@link com.legend.spi.SectionGrammar} seam. Owns {@code DataSpace}
 * elements to the corpus-censused scope: decorated envelope,
 * executionContexts (with {@code testData} islands carried raw),
 * defaultExecutionContext, title/description, executables (path form AND
 * inline-query form), diagrams, supportInfo (raw) and the
 * include/exclude {@code elements} scope list.
 *
 * <p>Like {@code ###Service}: no WIRE shape claimed — emission walls and
 * the parity harness keeps DataSpace files OUT_OF_SCOPE. The grammar's job
 * is the parse/transform seam.
 */
public final class DataSpaceSectionGrammar implements LexableSectionGrammar {

    /** The one stateless instance the registry hands out. */
    public static final DataSpaceSectionGrammar INSTANCE =
            new DataSpaceSectionGrammar();

    private DataSpaceSectionGrammar() {
    }

    @Override
    public String name() {
        return "DataSpace";
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
            Protocol.PDataSpace ds = parseElement(c);
            out.accept(ds.qualifiedName(),
                    com.legend.protocol.ProtocolEmitter.emitElement(ds));
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
        return com.legend.model.FromProtocol.toDataSpaceDefinition(
                (Protocol.PDataSpace) element);
    }

    /** One {@code DataSpace <<...>> {...tags} qn { body }} element. */
    public static Protocol.PDataSpace parseElement(TokenStreamCursor c) {
        int declStart = c.pos();
        if (!c.isIdentifierToken(c.peek())
                || !"DataSpace".equals(c.safeText())) {
            throw c.error("unsupported ###DataSpace element: " + c.safeText());
        }
        c.advance();                                // 'DataSpace'
        TokenStreamCursor.Decorations dec = c.parseDecorations();
        String qn = Protocol.unquotePath(c.parseQualifiedName());
        int cut = qn.lastIndexOf("::");
        String pkg = cut < 0 ? "" : qn.substring(0, cut);
        String name = cut < 0 ? qn : qn.substring(cut + 2);
        c.expect(TokenType.BRACE_OPEN);

        List<Protocol.PDataSpaceContext> contexts = new ArrayList<>();
        String defaultContext = null;
        String title = null;
        String description = null;
        List<Protocol.PDataSpaceExecutable> executables = new ArrayList<>();
        List<Protocol.PDataSpaceDiagram> diagrams = new ArrayList<>();
        String supportInfoSource = null;
        List<String> elements = new ArrayList<>();

        while (!c.atEnd() && c.peek() != TokenType.BRACE_CLOSE) {
            String key = c.parseIdentifier();
            c.expect(TokenType.COLON);
            switch (key) {
                case "executionContexts" -> parseContexts(c, contexts);
                case "defaultExecutionContext" -> {
                    defaultContext = stringValue(c);
                    c.expect(TokenType.SEMI_COLON);
                }
                case "title" -> {
                    title = stringValue(c);
                    c.expect(TokenType.SEMI_COLON);
                }
                case "description" -> {
                    description = stringValue(c);
                    c.expect(TokenType.SEMI_COLON);
                }
                case "executables" -> parseExecutables(c, executables);
                case "diagrams" -> parseDiagrams(c, diagrams);
                case "supportInfo" -> {
                    // Email { address: '...'; } — kind + RAW body, carried
                    String kind = c.parseIdentifier();
                    int bs = c.pos();
                    if (c.peek() == TokenType.BRACE_OPEN) {
                        skipBalanced(c, TokenType.BRACE_OPEN,
                                TokenType.BRACE_CLOSE);
                    }
                    supportInfoSource = kind + " "
                            + c.reconstructText(bs, c.pos());
                    c.expect(TokenType.SEMI_COLON);
                }
                case "elements" -> {
                    // [ model, -model::experiment ] — exclusions keep '-'
                    c.expect(TokenType.BRACKET_OPEN);
                    while (c.peek() != TokenType.BRACKET_CLOSE && !c.atEnd()) {
                        boolean excluded = c.match(TokenType.MINUS);
                        String path = Protocol.unquotePath(
                                c.parseQualifiedName());
                        elements.add(excluded ? "-" + path : path);
                        c.match(TokenType.COMMA);
                    }
                    c.expect(TokenType.BRACKET_CLOSE);
                    c.expect(TokenType.SEMI_COLON);
                }
                default -> throw c.error("unknown key '" + key
                        + "' inside DataSpace '" + qn + "'");
            }
        }
        c.expect(TokenType.BRACE_CLOSE);
        return new Protocol.PDataSpace(pkg, name, dec.stereotypes(),
                dec.taggedValues(), contexts, defaultContext, title,
                description, executables, diagrams, supportInfoSource,
                elements, c.spanOf(declStart, c.pos() - 1));
    }

    private static void parseContexts(TokenStreamCursor c,
            List<Protocol.PDataSpaceContext> out) {
        c.expect(TokenType.BRACKET_OPEN);
        while (c.peek() != TokenType.BRACKET_CLOSE && !c.atEnd()) {
            c.expect(TokenType.BRACE_OPEN);
            String name = null;
            String title = null;
            String description = null;
            String mapping = null;
            String defaultRuntime = null;
            String testDataSource = null;
            while (!c.atEnd() && c.peek() != TokenType.BRACE_CLOSE) {
                String key = c.parseIdentifier();
                c.expect(TokenType.COLON);
                switch (key) {
                    case "name" -> name = stringValue(c);
                    case "title" -> title = stringValue(c);
                    case "description" -> description = stringValue(c);
                    case "mapping" -> mapping =
                            Protocol.unquotePath(c.parseQualifiedName());
                    case "defaultRuntime" -> defaultRuntime =
                            Protocol.unquotePath(c.parseQualifiedName());
                    case "testData" -> {
                        // Reference #{ path }# — kind + RAW island, carried
                        String kind = c.parseIdentifier();
                        testDataSource = kind + " #{" + rawIsland(c) + "}#";
                    }
                    default -> throw c.error(
                            "unknown executionContexts key: " + key);
                }
                c.expect(TokenType.SEMI_COLON);
            }
            c.expect(TokenType.BRACE_CLOSE);
            if (name == null || mapping == null || defaultRuntime == null) {
                throw c.error("an execution context needs name, mapping and"
                        + " defaultRuntime");
            }
            out.add(new Protocol.PDataSpaceContext(name, title, description,
                    mapping, defaultRuntime, testDataSource));
            c.match(TokenType.COMMA);
        }
        c.expect(TokenType.BRACKET_CLOSE);
        c.expect(TokenType.SEMI_COLON);
    }

    private static void parseExecutables(TokenStreamCursor c,
            List<Protocol.PDataSpaceExecutable> out) {
        c.expect(TokenType.BRACKET_OPEN);
        while (c.peek() != TokenType.BRACKET_CLOSE && !c.atEnd()) {
            c.expect(TokenType.BRACE_OPEN);
            String id = null;
            String title = null;
            String description = null;
            String executable = null;
            String querySource = null;
            String contextKey = null;
            while (!c.atEnd() && c.peek() != TokenType.BRACE_CLOSE) {
                String key = c.parseIdentifier();
                c.expect(TokenType.COLON);
                switch (key) {
                    // ids appear as bare identifiers AND integers
                    case "id" -> {
                        id = c.safeText();
                        c.advance();
                    }
                    case "title" -> title = stringValue(c);
                    case "description" -> description = stringValue(c);
                    case "executable" -> {
                        executable = Protocol.unquotePath(c.parseQualifiedName());
                        if (c.peek() == TokenType.PAREN_OPEN) {
                            // a FUNCTION POINTER with its full signature —
                            // executable: fn():TabularDataSet[1]; — kept as
                            // written
                            executable += rawToSemicolon(c);
                        }
                    }
                    case "query" -> querySource = rawToSemicolon(c);
                    case "executionContextKey" -> contextKey = stringValue(c);
                    default -> throw c.error("unknown executables key: " + key);
                }
                c.expect(TokenType.SEMI_COLON);
            }
            c.expect(TokenType.BRACE_CLOSE);
            if (title == null || (executable == null && querySource == null)) {
                throw c.error("an executable needs a title and an executable"
                        + " path or query");
            }
            out.add(new Protocol.PDataSpaceExecutable(id, title, description,
                    executable, querySource, contextKey));
            c.match(TokenType.COMMA);
        }
        c.expect(TokenType.BRACKET_CLOSE);
        c.expect(TokenType.SEMI_COLON);
    }

    private static void parseDiagrams(TokenStreamCursor c,
            List<Protocol.PDataSpaceDiagram> out) {
        c.expect(TokenType.BRACKET_OPEN);
        while (c.peek() != TokenType.BRACKET_CLOSE && !c.atEnd()) {
            c.expect(TokenType.BRACE_OPEN);
            String title = null;
            String description = null;
            String diagram = null;
            while (!c.atEnd() && c.peek() != TokenType.BRACE_CLOSE) {
                String key = c.parseIdentifier();
                c.expect(TokenType.COLON);
                switch (key) {
                    case "title" -> title = stringValue(c);
                    case "description" -> description = stringValue(c);
                    case "diagram" -> diagram =
                            Protocol.unquotePath(c.parseQualifiedName());
                    default -> throw c.error("unknown diagrams key: " + key);
                }
                c.expect(TokenType.SEMI_COLON);
            }
            c.expect(TokenType.BRACE_CLOSE);
            if (title == null || diagram == null) {
                throw c.error("a diagram entry needs title and diagram");
            }
            out.add(new Protocol.PDataSpaceDiagram(title, description, diagram));
            c.match(TokenType.COMMA);
        }
        c.expect(TokenType.BRACKET_CLOSE);
        c.expect(TokenType.SEMI_COLON);
    }

    /** Raw token text up to (not consuming) the next top-level {@code ;} —
     *  depth-aware across ALL bracket kinds (brace-lambda queries carry
     *  inner semicolons). */
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
