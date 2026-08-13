// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.parser.section;

import com.legend.lexer.TokenType;
import com.legend.parser.TokenStreamCursor;
import com.legend.protocol.Protocol;
import com.legend.protocol.SourceInfo;

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
 * <p>Wire shape claimed (ZTailProbe "dataspace-rich"/"dataspace-email"):
 * {@code _type:"dataSpace"}, byte-exact via {@code TailEmitter}.
 */
public final class DataSpaceSectionGrammar
        implements ElementwiseSectionGrammar {

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
    public String qualifiedNameOf(Protocol.Element e) {
        return ((Protocol.PDataSpace) e).qualifiedName();
    }

    @Override
    public Protocol.Element parseOne(TokenStreamCursor c) {
        return parseElement(c);
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
        List<Protocol.PDataSpaceExecutable> executables = null;
        List<Protocol.PDataSpaceDiagram> diagrams = null;
        Protocol.PDataSpaceSupport supportInfo = null;
        List<Protocol.PDataSpaceElementRef> elements = null;

        java.util.Set<String> seenKeys = new java.util.HashSet<>();
        while (!c.atEnd() && c.peek() != TokenType.BRACE_CLOSE) {
            String key = c.parseIdentifier();
            TokenStreamCursor.once(seenKeys, key, c, declStart);
            c.expect(TokenType.COLON);
            switch (key) {
                case "executionContexts" -> parseContexts(c, contexts);
                case "defaultExecutionContext" -> {
                    defaultContext = SectionParse.stringValue(c);
                    c.expect(TokenType.SEMI_COLON);
                }
                case "title" -> {
                    title = SectionParse.stringValue(c);
                    c.expect(TokenType.SEMI_COLON);
                }
                case "description" -> {
                    description = SectionParse.stringValue(c);
                    c.expect(TokenType.SEMI_COLON);
                }
                case "executables" -> {
                    executables = new ArrayList<>();
                    parseExecutables(c, executables);
                }
                case "diagrams" -> {
                    diagrams = new ArrayList<>();
                    parseDiagrams(c, diagrams);
                }
                case "supportInfo" -> {
                    supportInfo = parseSupportInfo(c);
                    c.expect(TokenType.SEMI_COLON);
                }
                case "elements" -> {
                    // [ model, -model::experiment ] — the ref span
                    // includes the '-' of an exclusion
                    elements = new ArrayList<>();
                    c.expect(TokenType.BRACKET_OPEN);
                    while (c.peek() != TokenType.BRACKET_CLOSE && !c.atEnd()) {
                        int s = c.pos();
                        boolean excluded = c.match(TokenType.MINUS);
                        String path = Protocol.unquotePath(
                                c.parseQualifiedName());
                        elements.add(new Protocol.PDataSpaceElementRef(path,
                                excluded, c.spanOf(s, c.pos() - 1)));
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
        // engine-verbatim required fields (sectioned negative pins #4/#6)
        if (!seenKeys.contains("executionContexts")) {
            throw TokenStreamCursor.throwAt(c.tokens(), declStart,
                    "Field 'executionContexts' is required");
        }
        if (!seenKeys.contains("defaultExecutionContext")) {
            throw TokenStreamCursor.throwAt(c.tokens(), declStart,
                    "Field 'defaultExecutionContext' is required");
        }
        return new Protocol.PDataSpace(pkg, name, dec.stereotypes(),
                dec.taggedValues(), contexts, defaultContext, title,
                description, executables, diagrams, supportInfo,
                elements, c.spanOf(declStart, c.pos() - 1));
    }

    /** {@code Email { address: '...'; } | Combined { ...; emails: [..]; }}
     *  — span covers the value only ({@code Kind { ... }}). */
    private static Protocol.PDataSpaceSupport parseSupportInfo(
            TokenStreamCursor c) {
        int start = c.pos();
        String kind = c.parseIdentifier();
        c.expect(TokenType.BRACE_OPEN);
        String address = null;
        String documentationUrl = null;
        String website = null;
        String faqUrl = null;
        String supportUrl = null;
        List<String> emails = null;
        java.util.Set<String> seenKeys2 = new java.util.HashSet<>();
        while (!c.atEnd() && c.peek() != TokenType.BRACE_CLOSE) {
            String key = c.parseIdentifier();
            TokenStreamCursor.once(seenKeys2, key, c, start);
            c.expect(TokenType.COLON);
            switch (key) {
                case "address" -> address = SectionParse.stringValue(c);
                case "documentationUrl" -> documentationUrl = SectionParse.stringValue(c);
                case "website" -> website = SectionParse.stringValue(c);
                case "faqUrl" -> faqUrl = SectionParse.stringValue(c);
                case "supportUrl" -> supportUrl = SectionParse.stringValue(c);
                case "emails" -> {
                    emails = new ArrayList<>();
                    c.expect(TokenType.BRACKET_OPEN);
                    while (c.peek() != TokenType.BRACKET_CLOSE) {
                        emails.add(SectionParse.stringValue(c));
                        if (!c.match(TokenType.COMMA)) {
                            break;
                        }
                    }
                    c.expect(TokenType.BRACKET_CLOSE);
                }
                default -> throw c.error("unknown supportInfo key '" + key
                        + "'");
            }
            c.expect(TokenType.SEMI_COLON);
        }
        c.expect(TokenType.BRACE_CLOSE);
        SourceInfo span = c.spanOf(start, c.pos() - 1);
        return switch (kind) {
            case "Email" -> {
                if (address == null) {
                    // engine-verbatim (probed live: Email{} without address)
                    throw TokenStreamCursor.throwAt(c.tokens(), start,
                            "Field 'address' is required");
                }
                yield new Protocol.PDataSpaceSupport.PSupportEmail(address, span);
            }
            case "Combined" -> new Protocol.PDataSpaceSupport
                    .PSupportCombined(documentationUrl, website, faqUrl,
                            supportUrl, emails, span);
            default -> throw c.error("unknown supportInfo kind '" + kind
                    + "'");
        };
    }

    private static void parseContexts(TokenStreamCursor c,
            List<Protocol.PDataSpaceContext> out) {
        c.expect(TokenType.BRACKET_OPEN);
        while (c.peek() != TokenType.BRACKET_CLOSE && !c.atEnd()) {
            int ctxStart = c.pos();
            c.expect(TokenType.BRACE_OPEN);
            String name = null;
            String title = null;
            String description = null;
            String mapping = null;
            SourceInfo mappingSpan = null;
            String defaultRuntime = null;
            SourceInfo runtimeSpan = null;
            Protocol.PDataSpaceTestData testData = null;
            java.util.Set<String> seenKeys3 = new java.util.HashSet<>();
            while (!c.atEnd() && c.peek() != TokenType.BRACE_CLOSE) {
                int keyStart = c.pos();
                String key = c.parseIdentifier();
                TokenStreamCursor.once(seenKeys3, key, c, ctxStart);
                c.expect(TokenType.COLON);
                switch (key) {
                    case "name" -> name = SectionParse.stringValue(c);
                    case "title" -> title = SectionParse.stringValue(c);
                    case "description" -> description = SectionParse.stringValue(c);
                    case "mapping" -> mapping =
                            Protocol.unquotePath(c.parseQualifiedName());
                    case "defaultRuntime" -> defaultRuntime =
                            Protocol.unquotePath(c.parseQualifiedName());
                    case "testData" -> {
                        // Reference #{ path }# — the span covers the VALUE
                        // (kind through }#), no key, no semicolon
                        int vs = c.pos();
                        String kind = c.parseIdentifier();
                        String path = rawIsland(c).trim();
                        testData = new Protocol.PDataSpaceTestData(kind,
                                path, c.spanOf(vs, c.pos() - 1));
                    }
                    default -> throw c.error(
                            "unknown executionContexts key: " + key);
                }
                c.expect(TokenType.SEMI_COLON);
                // POINTER spans cover the whole `key: value;` statement
                if ("mapping".equals(key)) {
                    mappingSpan = c.spanOf(keyStart, c.pos() - 1);
                } else if ("defaultRuntime".equals(key)) {
                    runtimeSpan = c.spanOf(keyStart, c.pos() - 1);
                }
            }
            c.expect(TokenType.BRACE_CLOSE);
            if (name == null || mapping == null || mappingSpan == null
                    || defaultRuntime == null || runtimeSpan == null) {
                throw TokenStreamCursor.throwAt(c.tokens(), ctxStart,
                        "an execution context needs name, mapping and"
                        + " defaultRuntime");
            }
            out.add(new Protocol.PDataSpaceContext(name, title, description,
                    mapping, mappingSpan, defaultRuntime, runtimeSpan,
                    testData, c.spanOf(ctxStart, c.pos() - 1)));
            c.match(TokenType.COMMA);
        }
        c.expect(TokenType.BRACKET_CLOSE);
        c.expect(TokenType.SEMI_COLON);
    }

    private static void parseExecutables(TokenStreamCursor c,
            List<Protocol.PDataSpaceExecutable> out) {
        c.expect(TokenType.BRACKET_OPEN);
        while (c.peek() != TokenType.BRACKET_CLOSE && !c.atEnd()) {
            int entryStart = c.pos();
            c.expect(TokenType.BRACE_OPEN);
            String id = null;
            String title = null;
            String description = null;
            String executable = null;
            SourceInfo executableSpan = null;
            com.legend.protocol.spec.ValueSpecification query = null;
            String contextKey = null;
            java.util.Set<String> seenKeys4 = new java.util.HashSet<>();
            while (!c.atEnd() && c.peek() != TokenType.BRACE_CLOSE) {
                int keyStart = c.pos();
                String key = c.parseIdentifier();
                TokenStreamCursor.once(seenKeys4, key, c, entryStart);
                c.expect(TokenType.COLON);
                switch (key) {
                    // ids appear as bare identifiers AND integers — the
                    // wire stringifies both
                    case "id" -> {
                        id = c.safeText();
                        c.advance();
                    }
                    case "title" -> title = SectionParse.stringValue(c);
                    case "description" -> description = SectionParse.stringValue(c);
                    case "executable" -> {
                        executable = Protocol.unquotePath(c.parseQualifiedName());
                        if (c.peek() == TokenType.PAREN_OPEN) {
                            // a FUNCTION POINTER with its full signature —
                            // executable: fn():TabularDataSet[1]; — kept as
                            // written
                            executable += rawToSemicolon(c);
                        }
                    }
                    case "query" -> query = SectionParse.lambdaToSemicolon(c);
                    case "executionContextKey" -> contextKey = SectionParse.stringValue(c);
                    default -> throw c.error("unknown executables key: " + key);
                }
                c.expect(TokenType.SEMI_COLON);
                if ("executable".equals(key)) {
                    // the pointer span covers the whole `key: value;`
                    executableSpan = c.spanOf(keyStart, c.pos() - 1);
                }
            }
            c.expect(TokenType.BRACE_CLOSE);
            if (id == null && query != null) {
                // engine-verbatim (sectioned negative pin #66) — required
                // for the TEMPLATE form only; the pointer form
                // (executable: path) is id-less in oracle-accepted corpus
                // (dataSpaceWithExecutables.pure; the first ceiling here
                // over-tightened and refused three accepted files)
                throw com.legend.parser.TokenStreamCursor.throwAt(c.tokens(), entryStart,
                        "Field 'id' is required");
            }
            if (title == null || (executable == null && query == null)) {
                throw com.legend.parser.TokenStreamCursor.throwAt(c.tokens(), entryStart,
                        "an executable needs a title and an executable"
                        + " path or query");
            }
            out.add(new Protocol.PDataSpaceExecutable(id, title, description,
                    executable, executableSpan, query, contextKey,
                    c.spanOf(entryStart, c.pos() - 1)));
            c.match(TokenType.COMMA);
        }
        c.expect(TokenType.BRACKET_CLOSE);
        c.expect(TokenType.SEMI_COLON);
    }

    private static void parseDiagrams(TokenStreamCursor c,
            List<Protocol.PDataSpaceDiagram> out) {
        c.expect(TokenType.BRACKET_OPEN);
        while (c.peek() != TokenType.BRACKET_CLOSE && !c.atEnd()) {
            int entryStart = c.pos();
            c.expect(TokenType.BRACE_OPEN);
            String title = null;
            String description = null;
            String diagram = null;
            SourceInfo diagramSpan = null;
            java.util.Set<String> seenKeys5 = new java.util.HashSet<>();
            while (!c.atEnd() && c.peek() != TokenType.BRACE_CLOSE) {
                int keyStart = c.pos();
                String key = c.parseIdentifier();
                TokenStreamCursor.once(seenKeys5, key, c);
                c.expect(TokenType.COLON);
                switch (key) {
                    case "title" -> title = SectionParse.stringValue(c);
                    case "description" -> description = SectionParse.stringValue(c);
                    case "diagram" -> diagram =
                            Protocol.unquotePath(c.parseQualifiedName());
                    default -> throw c.error("unknown diagrams key: " + key);
                }
                c.expect(TokenType.SEMI_COLON);
                if ("diagram".equals(key)) {
                    diagramSpan = c.spanOf(keyStart, c.pos() - 1);
                }
            }
            c.expect(TokenType.BRACE_CLOSE);
            if (title == null || diagram == null || diagramSpan == null) {
                throw com.legend.parser.TokenStreamCursor.throwAt(
                        c.tokens(), entryStart,
                        "a diagram entry needs title and diagram");
            }
            out.add(new Protocol.PDataSpaceDiagram(title, description,
                    diagram, diagramSpan, c.spanOf(entryStart, c.pos() - 1)));
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


}
