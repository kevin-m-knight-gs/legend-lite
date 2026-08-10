// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.parser.section;

import com.legend.lexer.Lexer;
import com.legend.lexer.TokenStream;
import com.legend.lexer.TokenType;
import com.legend.parser.TokenStreamCursor;
import com.legend.protocol.Protocol;
import com.legend.protocol.ProtocolEmitter;

import java.util.ArrayList;
import java.util.List;

/**
 * THE {@code ###Connection} grammar — the first built-in section behind the
 * {@link com.legend.spi.SectionGrammar} seam (SECTION_PROGRAM_HANDOFF.md
 * §2.6). Owns every connection spelling: the four standalone element flavors
 * ({@code RelationalDatabaseConnection}, {@code JsonModelConnection},
 * {@code XmlModelConnection}, {@code ModelChainConnection}) and the embedded
 * runtime-island form, which shares the exact same VALUE grammar — one
 * grammar, several feeds, so the divergence that let the old straight-to-model
 * twin mis-parse {@code testDataSetupSqls} arrays for months cannot recur.
 *
 * <p>Engine-parity shapes are probed byte-for-byte (ZConnectionProbe).
 * legend-lite additionally accepts its OWN extension flavors for
 * engine-independent DuckDB operation — {@code InMemory {}},
 * {@code LocalFile { path }}, {@code LocalH2 { url }}, {@code Static}'s
 * {@code database:} key spelling, {@code NoAuth {}} and the literal-username
 * {@code UsernamePassword} — the same posture as {@code PRelTypeRef}: a NAMED
 * lite superset the corpus never contains and the emitter refuses to put on
 * the wire.
 */
public final class ConnectionSectionGrammar implements LexableSectionGrammar {

    /** The one stateless instance the registry hands out. */
    public static final ConnectionSectionGrammar INSTANCE =
            new ConnectionSectionGrammar();

    private ConnectionSectionGrammar() {
    }

    @Override
    public String name() {
        return "Connection";
    }

    /** The SPI feed: re-lex the raw section text and drive the shared parse,
     *  emitting each element's protocol JSON. Spans are slice-relative (line
     *  1 = the section's first content line); an external host maps them
     *  through its own walker offsets. */
    @Override
    public void parse(com.legend.spi.SectionSource src,
            com.legend.spi.ElementSink out) {
        Cursor c = new Cursor(Lexer.tokenize(src.text()), 0, 0);
        while (!c.atEnd()) {
            if (c.peek() == TokenType.IMPORT) {
                SectionImports.parseImport(c);
                continue;
            }
            Protocol.PConnection pc = parseElement(c);
            out.accept(pc.qualifiedName(), ProtocolEmitter.emitElement(pc));
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
            return com.legend.model.FromProtocol.toConnectionElement(
                    (Protocol.PConnection) element);
        } catch (com.legend.model.FromProtocol.UnsupportedConnectionShape u) {
            throw new UnsupportedElementShape(u.reason());
        }
    }

    // ============================================================
    // Element envelope
    // ============================================================

    /**
     * One {@code <Flavor> qn { body }} connection element at the cursor.
     * The BODY grammar is shared with embedded runtime islands
     * ({@link #parseIslandValue}), so there is one connection grammar.
     * Envelope and value share one span (ZConnectionProbe).
     */
    public static Protocol.PConnection parseElement(TokenStreamCursor c) {
        int declStart = c.pos();
        String flavor = c.safeText();
        c.advance();
        String qn = Protocol.unquotePath(c.parseQualifiedName());
        int cut = qn.lastIndexOf("::");
        String pkg = cut < 0 ? "" : qn.substring(0, cut);
        String name = cut < 0 ? qn : qn.substring(cut + 2);
        Protocol.PConnectionValue value = parseValue(c, flavor, declStart, true);
        return new Protocol.PConnection(pkg, name, value,
                c.spanOf(declStart, c.pos() - 1));
    }

    /** An embedded runtime-island body ({@code #{ Flavor {...} }#} content,
     *  already extracted): re-lex with the engine's walker-offset rule (line
     *  offset every line, column offset first line only) so spans stay
     *  file-absolute. */
    public static Protocol.PConnectionValue parseIslandValue(String islandText,
            int baseLine, int baseColumn) {
        Cursor c = new Cursor(Lexer.tokenize(islandText),
                baseLine - 1, baseColumn - 1);
        String flavor = c.safeText();
        int fStart = c.pos();
        c.advance();
        return parseValue(c, flavor, fStart, false);
    }

    /**
     * One connection VALUE body {@code { key: ...; }} for {@code flavor}.
     * {@code standalone} controls the model-connection {@code element}
     * field ({@code "ModelStore"} for section elements, absent embedded).
     * Corpus-censused shapes plus the named lite extensions; anything else
     * refuses loudly.
     */
    public static Protocol.PConnectionValue parseValue(TokenStreamCursor c,
            String flavor, int declStart, boolean standalone) {
        return switch (flavor) {
            case "JsonModelConnection", "XmlModelConnection" -> {
                ModelConnBody body = parseModelConnectionBody(c);
                com.legend.protocol.SourceInfo span =
                        c.spanOf(declStart, c.pos() - 1);
                yield "JsonModelConnection".equals(flavor)
                        ? new Protocol.PJsonModelConnection(
                                body.cls(), body.clsSpan(),
                                standalone ? "ModelStore" : null, body.url(), span)
                        : new Protocol.PXmlModelConnection(
                                body.cls(), body.clsSpan(),
                                standalone ? "ModelStore" : null, body.url(), span);
            }
            case "ModelChainConnection" -> parseModelChainBody(c, declStart,
                    standalone);
            case "RelationalDatabaseConnection" ->
                    parseRelationalConnectionBody(c, declStart);
            case "ServiceStoreConnection" ->
                    parseServiceStoreConnectionBody(c, declStart);
            case "DeephavenConnection" -> parseDeephavenConnectionBody(c);
            case "MongoDBConnection" -> parseMongoConnectionBody(c, declStart);
            default -> throw c.error("unsupported connection flavor: " + flavor);
        };
    }

    private record ModelConnBody(String cls,
            com.legend.protocol.SourceInfo clsSpan, String url) {
    }

    private static ModelConnBody parseModelConnectionBody(TokenStreamCursor c) {
        c.expect(TokenType.BRACE_OPEN);
        String cls = null;
        com.legend.protocol.SourceInfo clsSpan = null;
        String url = null;
        while (!c.atEnd() && c.peek() != TokenType.BRACE_CLOSE) {
            String key = c.parseIdentifier();
            c.expect(TokenType.COLON);
            if ("class".equals(key)) {
                int cS = c.pos();
                cls = Protocol.unquotePath(c.parseQualifiedName());
                clsSpan = c.spanOf(cS, c.pos() - 1);
            } else if ("url".equals(key)) {
                String quoted = c.text();
                c.expect(TokenType.STRING);
                url = TokenStreamCursor.unquoteAndUnescape(quoted, c);
            } else {
                throw c.error("unknown model-connection key: " + key);
            }
            c.expect(TokenType.SEMI_COLON);
        }
        c.expect(TokenType.BRACE_CLOSE);
        if (cls == null || clsSpan == null || url == null) {
            throw c.error("model connection needs class and url");
        }
        return new ModelConnBody(cls, clsSpan, url);
    }

    private static Protocol.PModelChainConnection parseModelChainBody(
            TokenStreamCursor c, int declStart, boolean standalone) {
        c.expect(TokenType.BRACE_OPEN);
        List<String> mappings = new ArrayList<>();
        com.legend.protocol.SourceInfo mapSpan = null;
        while (!c.atEnd() && c.peek() != TokenType.BRACE_CLOSE) {
            int kS = c.pos();
            String key = c.safeText();
            if (c.peek() != TokenType.MAPPINGS) {
                throw c.error("unknown ModelChainConnection key: " + key);
            }
            c.advance();
            c.expect(TokenType.COLON);
            c.expect(TokenType.BRACKET_OPEN);
            while (c.peek() != TokenType.BRACKET_CLOSE && !c.atEnd()) {
                mappings.add(Protocol.unquotePath(c.parseQualifiedName()));
                c.match(TokenType.COMMA);
            }
            c.expect(TokenType.BRACKET_CLOSE);
            c.match(TokenType.SEMI_COLON);      // span INCLUDES the ';' (probe)
            mapSpan = c.spanOf(kS, c.pos() - 1);
        }
        c.expect(TokenType.BRACE_CLOSE);
        if (mapSpan == null) {
            throw c.error("ModelChainConnection needs mappings");
        }
        return new Protocol.PModelChainConnection(
                standalone ? "ModelStore" : null, mappings, mapSpan,
                c.spanOf(declStart, c.pos() - 1));
    }

    /** {@code { store: qn; baseUrl: 'url'; }} (ZTailProbe
     *  "servicestore-conn"). */
    private static Protocol.PServiceStoreConnection
            parseServiceStoreConnectionBody(TokenStreamCursor c, int declStart) {
        c.expect(TokenType.BRACE_OPEN);
        String element = null;
        com.legend.protocol.SourceInfo elementSpan = null;
        String baseUrl = null;
        while (!c.atEnd() && c.peek() != TokenType.BRACE_CLOSE) {
            String key = c.parseIdentifier();
            c.expect(TokenType.COLON);
            switch (key) {
                case "store" -> {
                    int eS = c.pos();
                    element = Protocol.unquotePath(c.parseQualifiedName());
                    elementSpan = c.spanOf(eS, c.pos() - 1);
                }
                case "baseUrl" -> baseUrl = stringValue(c);
                default -> throw c.error(
                        "unknown ServiceStoreConnection key: " + key);
            }
            c.expect(TokenType.SEMI_COLON);
        }
        c.expect(TokenType.BRACE_CLOSE);
        if (baseUrl == null) {
            throw c.error("ServiceStoreConnection needs baseUrl");
        }
        return new Protocol.PServiceStoreConnection(baseUrl, element,
                elementSpan, c.spanOf(declStart, c.pos() - 1));
    }

    /** {@code { store: qn; serverUrl: 'url' authentication: # PSK { psk:
     *  'v'; }#; }} — serverUrl takes NO semicolon in the corpus spelling;
     *  the value span runs the FIRST body key through the island close
     *  (ZTailProbe "deephaven-conn"). */
    private static Protocol.PDeephavenConnection
            parseDeephavenConnectionBody(TokenStreamCursor c) {
        c.expect(TokenType.BRACE_OPEN);
        int bodyStart = c.pos();
        String element = null;
        com.legend.protocol.SourceInfo elementSpan = null;
        String serverUrl = null;
        String psk = null;
        int islandEndTok = -1;
        while (!c.atEnd() && c.peek() != TokenType.BRACE_CLOSE) {
            String key = c.parseIdentifier();
            c.expect(TokenType.COLON);
            switch (key) {
                case "store" -> {
                    int eS = c.pos();
                    element = Protocol.unquotePath(c.parseQualifiedName());
                    elementSpan = c.spanOf(eS, c.pos() - 1);
                }
                case "serverUrl" -> serverUrl = stringValue(c);
                case "authentication" -> {
                    if (c.peek() != TokenType.ISLAND_OPEN) {
                        throw c.error("DeephavenConnection authentication"
                                + " must be a # PSK {...}# island");
                    }
                    String kind = islandKind(c);
                    if (!"PSK".equals(kind)) {
                        throw c.error("unsupported Deephaven auth kind: "
                                + kind);
                    }
                    IslandParse ip = reLexIsland(c);
                    islandEndTok = ip.endTok();
                    Cursor ic = ip.cursor();
                    while (!ic.atEnd()) {
                        String ik = ic.parseIdentifier();
                        ic.expect(TokenType.COLON);
                        if (!"psk".equals(ik)) {
                            throw ic.error("unknown PSK key: " + ik);
                        }
                        psk = stringValue(ic);
                        ic.expect(TokenType.SEMI_COLON);
                    }
                }
                default -> throw c.error(
                        "unknown DeephavenConnection key: " + key);
            }
            c.match(TokenType.SEMI_COLON);
        }
        int closeTok = c.pos();
        c.expect(TokenType.BRACE_CLOSE);
        if (serverUrl == null || psk == null) {
            throw c.error("DeephavenConnection needs serverUrl and"
                    + " authentication");
        }
        // engine's walker composes the value span END from TWO nodes: the
        // LINE of the connection's closing brace and the COLUMN of the
        // island's '}' (corpus DIFF pinned the cross-product)
        var sp = c.spanOf(bodyStart, closeTok);
        com.legend.protocol.SourceInfo vSpan = islandEndTok >= 0
                ? new com.legend.protocol.SourceInfo("", sp.startLine(),
                        sp.startColumn(), sp.endLine(),
                        c.tokens().startColumn(islandEndTok))
                : sp;
        return new Protocol.PDeephavenConnection(serverUrl, psk, element,
                elementSpan, vSpan);
    }

    /** {@code { database: id; store: qn; serverURLs: [host:port,...];
     *  authentication: # UserPassword {...}#; }} (ZTailProbe
     *  "mongodb-conn"). */
    private static Protocol.PMongoDbConnection parseMongoConnectionBody(
            TokenStreamCursor c, int declStart) {
        c.expect(TokenType.BRACE_OPEN);
        String database = null;
        String element = null;
        com.legend.protocol.SourceInfo elementSpan = null;
        List<Protocol.PMongoServerUrl> urls = new ArrayList<>();
        Protocol.PMongoAuth auth = null;
        while (!c.atEnd() && c.peek() != TokenType.BRACE_CLOSE) {
            int keyTok = c.pos();
            String key = c.parseIdentifier();
            c.expect(TokenType.COLON);
            switch (key) {
                case "database" -> database = c.parseIdentifier();
                case "store" -> {
                    int eS = c.pos();
                    element = Protocol.unquotePath(c.parseQualifiedName());
                    elementSpan = c.spanOf(eS, c.pos() - 1);
                }
                case "serverURLs" -> {
                    c.expect(TokenType.BRACKET_OPEN);
                    while (!c.atEnd() && c.peek() != TokenType.BRACKET_CLOSE) {
                        String host = c.parseIdentifier();
                        c.expect(TokenType.COLON);
                        long port = Long.parseLong(c.text());
                        c.expect(TokenType.INTEGER);
                        urls.add(new Protocol.PMongoServerUrl(host, port));
                        c.match(TokenType.COMMA);
                    }
                    c.expect(TokenType.BRACKET_CLOSE);
                }
                case "authentication" -> {
                    if (c.peek() != TokenType.ISLAND_OPEN) {
                        throw c.error("MongoDBConnection authentication must"
                                + " be a # UserPassword {...}# island");
                    }
                    String kind = islandKind(c);
                    if (!"UserPassword".equals(kind)) {
                        throw c.error("unsupported MongoDB auth kind: " + kind);
                    }
                    IslandParse ip = reLexIsland(c);
                    Cursor ic = ip.cursor();
                    String username = null;
                    Protocol.PMongoSecret secret = null;
                    while (!ic.atEnd()) {
                        String ik = ic.parseIdentifier();
                        ic.expect(TokenType.COLON);
                        if ("username".equals(ik)) {
                            username = stringValue(ic);
                            ic.expect(TokenType.SEMI_COLON);
                        } else if ("password".equals(ik)) {
                            int vS = ic.pos();
                            String sk = ic.parseIdentifier();
                            String wireKind;
                            String wireField;
                            switch (sk) {
                                case "PropertiesFileSecret" -> {
                                    wireKind = "properties";
                                    wireField = "propertyName";
                                }
                                case "SystemPropertiesSecret" -> {
                                    wireKind = "systemproperties";
                                    wireField = "systemPropertyName";
                                }
                                default -> throw ic.error(
                                        "unsupported secret kind: " + sk);
                            }
                            ic.expect(TokenType.BRACE_OPEN);
                            String fieldKey = ic.parseIdentifier();
                            ic.expect(TokenType.COLON);
                            String v = stringValue(ic);
                            ic.expect(TokenType.SEMI_COLON);
                            ic.expect(TokenType.BRACE_CLOSE);
                            if (!wireField.equals(fieldKey)) {
                                throw ic.error("unknown " + sk + " key: "
                                        + fieldKey);
                            }
                            secret = new Protocol.PMongoSecret(wireKind,
                                    wireField, v, ic.spanOf(vS, ic.pos() - 1));
                            ic.expect(TokenType.SEMI_COLON);
                        } else {
                            throw ic.error("unknown UserPassword key: " + ik);
                        }
                    }
                    if (username == null || secret == null) {
                        throw c.error("UserPassword needs username and"
                                + " password");
                    }
                    // the auth span is the island CONTENT region: it
                    // STARTS at the first content token and its end
                    // overshoots the island close by 3 — the reparse quirk
                    // family PRelationData pins
                    var aSp = c.spanOf(keyTok, ip.endTok());
                    var firstTok = ip.cursor().spanOf(0, 0);
                    auth = new Protocol.PMongoAuth(username, secret,
                            new com.legend.protocol.SourceInfo("",
                                    firstTok.startLine(),
                                    firstTok.startColumn(),
                                    aSp.endLine(), aSp.endColumn() + 3));
                }
                default -> throw c.error(
                        "unknown MongoDBConnection key: " + key);
            }
            c.match(TokenType.SEMI_COLON);
        }
        c.expect(TokenType.BRACE_CLOSE);
        if (database == null || auth == null) {
            throw c.error("MongoDBConnection needs database and"
                    + " authentication");
        }
        return new Protocol.PMongoDbConnection(database, urls, auth, element,
                elementSpan, c.spanOf(declStart, c.pos() - 1));
    }

    /** The DSL type between {@code #} and {@code {} of the island opener at
     *  the cursor (e.g. {@code # PSK {} → "PSK"}), NOT consumed. */
    private static String islandKind(TokenStreamCursor c) {
        String t = c.text();
        int brace = t.indexOf('{');
        return t.substring(1, brace < 0 ? t.length() : brace).trim();
    }

    /** Consume the island at the cursor and re-lex its content with walker
     *  offsets so inner spans stay file-absolute. */
    private record IslandParse(Cursor cursor, int endTok) {
    }

    private static IslandParse reLexIsland(TokenStreamCursor c) {
        c.advance();                                // ISLAND_OPEN
        int embStart = c.pos();
        int depth = 0;
        while (!c.atEnd()) {
            TokenType t = c.peek();
            if (t == TokenType.ISLAND_START) {
                depth++;
            } else if (t == TokenType.ISLAND_END) {
                if (depth == 0) {
                    break;
                }
                depth--;
            }
            c.advance();
        }
        String emb = c.reconstructText(embStart, c.pos());
        int endTok = c.pos();
        c.expect(TokenType.ISLAND_END);
        Cursor ic = new Cursor(Lexer.tokenize(emb),
                c.tokens().startLine(embStart) - 1,
                c.tokens().startColumn(embStart) - 1);
        return new IslandParse(ic, endTok);
    }

    private static Protocol.PRelationalDatabaseConnection
            parseRelationalConnectionBody(TokenStreamCursor c, int declStart) {
        c.expect(TokenType.BRACE_OPEN);
        String element = null;
        com.legend.protocol.SourceInfo elementSpan = null;
        String dbType = null;
        Protocol.PDatasourceSpec spec = null;
        Protocol.PAuthStrategy auth = null;
        List<Protocol.PMapperPostProcessor> posts = new ArrayList<>();
        Boolean quoteIdentifiers = null;
        String timeZone = null;
        while (!c.atEnd() && c.peek() != TokenType.BRACE_CLOSE) {
            int kS = c.pos();
            String key = c.parseIdentifier();
            c.expect(TokenType.COLON);
            switch (key) {
                case "store" -> {
                    int eS = c.pos();
                    element = Protocol.unquotePath(c.parseQualifiedName());
                    elementSpan = c.spanOf(eS, c.pos() - 1);
                    c.expect(TokenType.SEMI_COLON);
                }
                case "type" -> {
                    dbType = c.parseIdentifier();
                    c.expect(TokenType.SEMI_COLON);
                }
                case "specification" -> spec = parseDatasourceSpec(c, kS);
                case "auth" -> auth = parseAuthStrategy(c, kS);
                case "postProcessors" -> parseMapperPostProcessors(c, posts);
                case "quoteIdentifiers" -> {
                    quoteIdentifiers = parseBoolean(c);
                    c.expect(TokenType.SEMI_COLON);
                }
                case "timezone" -> {
                    // the VALUE keeps its quotes on the wire (probe timezone)
                    timeZone = c.text();
                    c.expect(TokenType.STRING);
                    c.expect(TokenType.SEMI_COLON);
                }
                default -> throw c.error(
                        "unknown RelationalDatabaseConnection key: " + key);
            }
        }
        c.expect(TokenType.BRACE_CLOSE);
        if (dbType == null || spec == null) {
            // store: is OPTIONAL (probe test-auth-empty-body-no-store —
            // element+span omitted from the wire entirely)
            throw c.error("RelationalDatabaseConnection needs type and"
                    + " specification");
        }
        if (auth == null) {
            // lite-extension posture carried over from the retired
            // straight-to-model twin: a LOCAL spec (InMemory / LocalFile /
            // LocalH2) may omit auth and defaults to NoAuth; a remote Static
            // spec without auth stays the loud error the engine gives (the
            // corpus always spells auth, so parity never sees this branch)
            if (spec instanceof Protocol.PStaticSpec) {
                throw c.error("RelationalDatabaseConnection needs auth");
            }
            auth = new Protocol.PNoAuth(c.spanOf(declStart, c.pos() - 1));
        }
        return new Protocol.PRelationalDatabaseConnection(
                auth, dbType, spec, element, elementSpan, posts,
                quoteIdentifiers, timeZone, c.spanOf(declStart, c.pos() - 1));
    }

    private static Boolean parseBoolean(TokenStreamCursor c) {
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

    /** One {@code key: 'string';} value. */
    private static String stringValue(TokenStreamCursor c) {
        String quoted = c.text();
        c.expect(TokenType.STRING);
        return TokenStreamCursor.unquoteAndUnescape(quoted, c);
    }

    /** {@code postProcessors: [ mapper { mappers: [ table {...}, schema
     *  {...} ]; } ]} — only the mapper flavor exists in the corpus; table
     *  mappers carry from/to/schemaFrom/schemaTo (probe post-processors). */
    private static void parseMapperPostProcessors(TokenStreamCursor c,
            List<Protocol.PMapperPostProcessor> out) {
        c.expect(TokenType.BRACKET_OPEN);
        while (c.peek() != TokenType.BRACKET_CLOSE && !c.atEnd()) {
            String kind = c.parseIdentifier();
            if (!"mapper".equals(kind)) {
                throw c.error("unsupported postProcessor flavor: " + kind);
            }
            c.expect(TokenType.BRACE_OPEN);
            List<Protocol.PMapper> mappers = new ArrayList<>();
            while (!c.atEnd() && c.peek() != TokenType.BRACE_CLOSE) {
                String key = c.parseIdentifier();
                c.expect(TokenType.COLON);
                if (!"mappers".equals(key)) {
                    throw c.error("unknown mapper key: " + key);
                }
                c.expect(TokenType.BRACKET_OPEN);
                while (c.peek() != TokenType.BRACKET_CLOSE && !c.atEnd()) {
                    mappers.add(parseOneMapper(c));
                    c.match(TokenType.COMMA);
                }
                c.expect(TokenType.BRACKET_CLOSE);
                c.expect(TokenType.SEMI_COLON);
            }
            c.expect(TokenType.BRACE_CLOSE);
            out.add(new Protocol.PMapperPostProcessor(mappers));
            c.match(TokenType.COMMA);
        }
        c.expect(TokenType.BRACKET_CLOSE);
        c.expect(TokenType.SEMI_COLON);
    }

    private static Protocol.PMapper parseOneMapper(TokenStreamCursor c) {
        String flavor = c.parseIdentifier();
        c.expect(TokenType.BRACE_OPEN);
        java.util.Map<String, String> kv = new java.util.HashMap<>();
        while (!c.atEnd() && c.peek() != TokenType.BRACE_CLOSE) {
            String key = c.parseIdentifier();
            c.expect(TokenType.COLON);
            String quoted = c.text();
            c.expect(TokenType.STRING);
            kv.put(key, TokenStreamCursor.unquoteAndUnescape(quoted, c));
            c.expect(TokenType.SEMI_COLON);
        }
        c.expect(TokenType.BRACE_CLOSE);
        String from = kv.get("from");
        String to = kv.get("to");
        if (from == null || to == null) {
            throw c.error("mapper needs from and to");
        }
        return switch (flavor) {
            case "table" -> {
                String sf = kv.get("schemaFrom");
                String st = kv.get("schemaTo");
                if (sf == null || st == null) {
                    throw c.error("table mapper needs schemaFrom and schemaTo");
                }
                yield new Protocol.PTableMapper(from, to, sf, st);
            }
            case "schema" -> new Protocol.PSchemaMapper(from, to);
            default -> throw c.error("unsupported mapper flavor: " + flavor);
        };
    }

    /** Spec span runs the {@code specification} KEYWORD through the closing
     *  token of the body (probes: LocalH2 one-line and Static multi-line). */
    private static Protocol.PDatasourceSpec parseDatasourceSpec(
            TokenStreamCursor c, int keywordTok) {
        String kind = c.parseIdentifier();
        return switch (kind) {
            case "LocalH2" -> {
                List<String> sqls = null;
                String csv = null;
                String url = null;
                c.expect(TokenType.BRACE_OPEN);
                while (!c.atEnd() && c.peek() != TokenType.BRACE_CLOSE) {
                    String key = c.parseIdentifier();
                    c.expect(TokenType.COLON);
                    if ("testDataSetupSqls".equals(key)) {
                        sqls = new ArrayList<>();
                        c.expect(TokenType.BRACKET_OPEN);
                        while (c.peek() != TokenType.BRACKET_CLOSE && !c.atEnd()) {
                            String quoted = c.text();
                            c.expect(TokenType.STRING);
                            sqls.add(TokenStreamCursor.unquoteAndUnescape(quoted, c));
                            c.match(TokenType.COMMA);
                        }
                        c.expect(TokenType.BRACKET_CLOSE);
                    } else if ("testDataSetupCSV".equals(key)) {
                        // wire spelling flips case: testDataSetupCsv (probe)
                        String quoted = c.text();
                        c.expect(TokenType.STRING);
                        csv = TokenStreamCursor.unquoteAndUnescape(quoted, c);
                    } else if ("url".equals(key)) {
                        // lite extension: engine's LocalH2 has no url key
                        String quoted = c.text();
                        c.expect(TokenType.STRING);
                        url = TokenStreamCursor.unquoteAndUnescape(quoted, c);
                    } else {
                        throw c.error("unknown LocalH2 key: " + key);
                    }
                    c.match(TokenType.SEMI_COLON);
                }
                c.expect(TokenType.BRACE_CLOSE);
                c.expect(TokenType.SEMI_COLON); // span INCLUDES the ';' (probe)
                yield new Protocol.PH2Local(csv, sqls, url,
                        c.spanOf(keywordTok, c.pos() - 1));
            }
            case "Static" -> {
                c.expect(TokenType.BRACE_OPEN);
                String name = null;
                String host = null;
                Long port = null;
                while (!c.atEnd() && c.peek() != TokenType.BRACE_CLOSE) {
                    String key = c.parseIdentifier();
                    c.expect(TokenType.COLON);
                    switch (key) {
                        // 'name' is the engine spelling, 'database' the lite
                        // one — both name the same wire field (databaseName)
                        case "name", "database" -> {
                            String quoted = c.text();
                            c.expect(TokenType.STRING);
                            name = TokenStreamCursor.unquoteAndUnescape(quoted, c);
                        }
                        case "host" -> {
                            String quoted = c.text();
                            c.expect(TokenType.STRING);
                            host = TokenStreamCursor.unquoteAndUnescape(quoted, c);
                        }
                        case "port" -> {
                            port = Long.parseLong(c.text());
                            c.expect(TokenType.INTEGER);
                        }
                        default -> throw c.error("unknown Static key: " + key);
                    }
                    c.expect(TokenType.SEMI_COLON);
                }
                c.expect(TokenType.BRACE_CLOSE);
                c.expect(TokenType.SEMI_COLON); // span INCLUDES the ';' (probe)
                if (name == null || host == null) {
                    throw c.error("Static needs name (or database) and host");
                }
                yield new Protocol.PStaticSpec(name, host,
                        port == null ? 0 : port,
                        c.spanOf(keywordTok, c.pos() - 1));
            }
            case "InMemory" -> {
                // lite extension: an in-process DuckDB, empty body
                c.expect(TokenType.BRACE_OPEN);
                c.expect(TokenType.BRACE_CLOSE);
                c.expect(TokenType.SEMI_COLON);
                yield new Protocol.PInMemory(c.spanOf(keywordTok, c.pos() - 1));
            }
            case "LocalFile" -> {
                // lite extension: a file-backed DuckDB/SQLite
                c.expect(TokenType.BRACE_OPEN);
                String path = null;
                while (!c.atEnd() && c.peek() != TokenType.BRACE_CLOSE) {
                    String key = c.parseIdentifier();
                    c.expect(TokenType.COLON);
                    if (!"path".equals(key)) {
                        throw c.error("unknown LocalFile key: " + key);
                    }
                    if (c.peek() == TokenType.STRING) {
                        path = TokenStreamCursor.unquoteAndUnescape(c.text(), c);
                        c.advance();
                    } else if (c.peek() == TokenType.QUOTED_STRING) {
                        // path: "/tmp/db.duckdb" — the OTHER quote character
                        path = TokenStreamCursor.stripDoubleQuotes(c.text());
                        c.advance();
                    } else {
                        throw c.error("LocalFile path must be a string");
                    }
                    c.expect(TokenType.SEMI_COLON);
                }
                c.expect(TokenType.BRACE_CLOSE);
                c.expect(TokenType.SEMI_COLON);
                if (path == null) {
                    throw c.error("LocalFile needs path");
                }
                yield new Protocol.PLocalFile(path,
                        c.spanOf(keywordTok, c.pos() - 1));
            }
            case "Snowflake" -> {
                c.expect(TokenType.BRACE_OPEN);
                String name = null;
                String account = null;
                String warehouse = null;
                String region = null;
                String accountType = null;
                String cloudType = null;
                Boolean enableQueryTags = null;
                String organization = null;
                String role = null;
                while (!c.atEnd() && c.peek() != TokenType.BRACE_CLOSE) {
                    String key = c.parseIdentifier();
                    c.expect(TokenType.COLON);
                    switch (key) {
                        case "name" -> name = stringValue(c);
                        case "account" -> account = stringValue(c);
                        case "warehouse" -> warehouse = stringValue(c);
                        case "region" -> region = stringValue(c);
                        // a BARE enum identifier (VPS / MultiTenant)
                        case "accountType" -> accountType = c.parseIdentifier();
                        case "cloudType" -> cloudType = stringValue(c);
                        case "enableQueryTags" ->
                                enableQueryTags = parseBoolean(c);
                        case "organization" -> organization = stringValue(c);
                        case "role" -> role = stringValue(c);
                        default -> throw c.error("unknown Snowflake key: " + key);
                    }
                    c.expect(TokenType.SEMI_COLON);
                }
                c.expect(TokenType.BRACE_CLOSE);
                c.expect(TokenType.SEMI_COLON);
                if (name == null || account == null || warehouse == null
                        || region == null) {
                    throw c.error("Snowflake needs name, account, warehouse"
                            + " and region");
                }
                yield new Protocol.PSnowflakeSpec(account, accountType,
                        cloudType, name, enableQueryTags, organization, region,
                        role, warehouse, c.spanOf(keywordTok, c.pos() - 1));
            }
            case "Spanner" -> {
                c.expect(TokenType.BRACE_OPEN);
                String projectId = null;
                String instanceId = null;
                String databaseId = null;
                while (!c.atEnd() && c.peek() != TokenType.BRACE_CLOSE) {
                    String key = c.parseIdentifier();
                    c.expect(TokenType.COLON);
                    switch (key) {
                        case "projectId" -> projectId = stringValue(c);
                        case "instanceId" -> instanceId = stringValue(c);
                        case "databaseId" -> databaseId = stringValue(c);
                        default -> throw c.error("unknown Spanner key: " + key);
                    }
                    c.expect(TokenType.SEMI_COLON);
                }
                c.expect(TokenType.BRACE_CLOSE);
                c.expect(TokenType.SEMI_COLON);
                if (projectId == null || instanceId == null
                        || databaseId == null) {
                    throw c.error("Spanner needs projectId, instanceId and"
                            + " databaseId");
                }
                yield new Protocol.PSpannerSpec(databaseId, instanceId,
                        projectId, c.spanOf(keywordTok, c.pos() - 1));
            }
            case "Databricks" -> {
                c.expect(TokenType.BRACE_OPEN);
                String hostname = null;
                String port = null;
                String protocol = null;
                String httpPath = null;
                while (!c.atEnd() && c.peek() != TokenType.BRACE_CLOSE) {
                    String key = c.parseIdentifier();
                    c.expect(TokenType.COLON);
                    switch (key) {
                        case "hostname" -> hostname = stringValue(c);
                        // port is a quoted STRING in source and on the wire
                        case "port" -> port = stringValue(c);
                        case "protocol" -> protocol = stringValue(c);
                        case "httpPath" -> httpPath = stringValue(c);
                        default -> throw c.error("unknown Databricks key: " + key);
                    }
                    c.expect(TokenType.SEMI_COLON);
                }
                c.expect(TokenType.BRACE_CLOSE);
                c.expect(TokenType.SEMI_COLON);
                if (hostname == null || port == null || protocol == null
                        || httpPath == null) {
                    throw c.error("Databricks needs hostname, port, protocol"
                            + " and httpPath");
                }
                yield new Protocol.PDatabricksSpec(hostname, httpPath, port,
                        protocol, c.spanOf(keywordTok, c.pos() - 1));
            }
            case "BigQuery" -> {
                c.expect(TokenType.BRACE_OPEN);
                String projectId = null;
                String defaultDataset = null;
                while (!c.atEnd() && c.peek() != TokenType.BRACE_CLOSE) {
                    String key = c.parseIdentifier();
                    c.expect(TokenType.COLON);
                    switch (key) {
                        case "projectId" -> projectId = stringValue(c);
                        case "defaultDataset" -> defaultDataset = stringValue(c);
                        default -> throw c.error("unknown BigQuery key: " + key);
                    }
                    c.expect(TokenType.SEMI_COLON);
                }
                c.expect(TokenType.BRACE_CLOSE);
                c.expect(TokenType.SEMI_COLON);
                if (projectId == null || defaultDataset == null) {
                    throw c.error("BigQuery needs projectId and defaultDataset");
                }
                yield new Protocol.PBigQuerySpec(defaultDataset, projectId,
                        c.spanOf(keywordTok, c.pos() - 1));
            }
            default -> throw c.error("unsupported datasource specification: "
                    + kind + " (corpus-censused shapes only)");
        };
    }

    /** Auth span runs the {@code auth} KEYWORD through the last body token
     *  (bodyless: through the kind identifier). */
    private static Protocol.PAuthStrategy parseAuthStrategy(
            TokenStreamCursor c, int keywordTok) {
        String kind = c.parseIdentifier();
        return switch (kind) {
            case "DefaultH2" -> {
                if (c.peek() == TokenType.BRACE_OPEN) {
                    c.advance();                // optional EMPTY body (lite)
                    c.expect(TokenType.BRACE_CLOSE);
                }
                c.expect(TokenType.SEMI_COLON); // span INCLUDES the ';' (probe)
                yield new Protocol.PH2Default(c.spanOf(keywordTok, c.pos() - 1));
            }
            case "Test" -> {
                if (c.peek() == TokenType.BRACE_OPEN) {
                    c.advance();                // optional EMPTY body (probe)
                    c.expect(TokenType.BRACE_CLOSE);
                }
                c.expect(TokenType.SEMI_COLON);
                yield new Protocol.PTestAuth(c.spanOf(keywordTok, c.pos() - 1));
            }
            case "DelegatedKerberos" -> {
                String principal = null;
                if (c.peek() == TokenType.BRACE_OPEN) {
                    c.advance();
                    while (!c.atEnd() && c.peek() != TokenType.BRACE_CLOSE) {
                        String key = c.parseIdentifier();
                        c.expect(TokenType.COLON);
                        if (!"serverPrincipal".equals(key)) {
                            throw c.error("unknown DelegatedKerberos key: " + key);
                        }
                        String quoted = c.text();
                        c.expect(TokenType.STRING);
                        principal = TokenStreamCursor.unquoteAndUnescape(quoted, c);
                        c.expect(TokenType.SEMI_COLON);
                    }
                    c.expect(TokenType.BRACE_CLOSE);
                }
                c.expect(TokenType.SEMI_COLON);
                yield new Protocol.PDelegatedKerberos(principal,
                        c.spanOf(keywordTok, c.pos() - 1));
            }
            case "UserNamePassword" -> {
                c.expect(TokenType.BRACE_OPEN);
                String base = null;
                String user = null;
                String pass = null;
                while (!c.atEnd() && c.peek() != TokenType.BRACE_CLOSE) {
                    String key = c.parseIdentifier();
                    c.expect(TokenType.COLON);
                    String quoted = c.text();
                    c.expect(TokenType.STRING);
                    String v = TokenStreamCursor.unquoteAndUnescape(quoted, c);
                    switch (key) {
                        case "baseVaultReference" -> base = v;
                        case "userNameVaultReference" -> user = v;
                        case "passwordVaultReference" -> pass = v;
                        default -> throw c.error(
                                "unknown UserNamePassword key: " + key);
                    }
                    c.expect(TokenType.SEMI_COLON);
                }
                c.expect(TokenType.BRACE_CLOSE);
                c.expect(TokenType.SEMI_COLON);
                if (user == null || pass == null) {
                    throw c.error("UserNamePassword needs userNameVaultReference"
                            + " and passwordVaultReference");
                }
                yield new Protocol.PUserNamePassword(base, user, pass,
                        c.spanOf(keywordTok, c.pos() - 1));
            }
            case "NoAuth" -> {
                // lite extension: empty body optional
                if (c.peek() == TokenType.BRACE_OPEN) {
                    c.advance();
                    c.expect(TokenType.BRACE_CLOSE);
                }
                c.expect(TokenType.SEMI_COLON);
                yield new Protocol.PNoAuth(c.spanOf(keywordTok, c.pos() - 1));
            }
            case "UsernamePassword" -> {
                // lite extension: literal username + vault ref (lower-case n)
                c.expect(TokenType.BRACE_OPEN);
                String username = null;
                String passRef = null;
                while (!c.atEnd() && c.peek() != TokenType.BRACE_CLOSE) {
                    String key = c.parseIdentifier();
                    c.expect(TokenType.COLON);
                    String quoted = c.text();
                    c.expect(TokenType.STRING);
                    String v = TokenStreamCursor.unquoteAndUnescape(quoted, c);
                    switch (key) {
                        case "username" -> username = v;
                        case "passwordVaultRef" -> passRef = v;
                        default -> throw c.error(
                                "unknown UsernamePassword key: " + key);
                    }
                    c.expect(TokenType.SEMI_COLON);
                }
                c.expect(TokenType.BRACE_CLOSE);
                c.expect(TokenType.SEMI_COLON);
                if (username == null || passRef == null) {
                    throw c.error("UsernamePassword needs username and"
                            + " passwordVaultRef");
                }
                yield new Protocol.PPlainUserPassword(username, passRef,
                        c.spanOf(keywordTok, c.pos() - 1));
            }
            case "SnowflakePublic" -> {
                c.expect(TokenType.BRACE_OPEN);
                String publicUserName = null;
                String privateKey = null;
                String passPhrase = null;
                while (!c.atEnd() && c.peek() != TokenType.BRACE_CLOSE) {
                    String key = c.parseIdentifier();
                    c.expect(TokenType.COLON);
                    switch (key) {
                        case "publicUserName" -> publicUserName = stringValue(c);
                        case "privateKeyVaultReference" ->
                                privateKey = stringValue(c);
                        case "passPhraseVaultReference" ->
                                passPhrase = stringValue(c);
                        default -> throw c.error(
                                "unknown SnowflakePublic key: " + key);
                    }
                    c.expect(TokenType.SEMI_COLON);
                }
                c.expect(TokenType.BRACE_CLOSE);
                c.expect(TokenType.SEMI_COLON);
                if (publicUserName == null || privateKey == null
                        || passPhrase == null) {
                    throw c.error("SnowflakePublic needs publicUserName,"
                            + " privateKeyVaultReference and"
                            + " passPhraseVaultReference");
                }
                yield new Protocol.PSnowflakePublic(passPhrase, privateKey,
                        publicUserName, c.spanOf(keywordTok, c.pos() - 1));
            }
            case "GCPApplicationDefaultCredentials" -> {
                if (c.peek() == TokenType.BRACE_OPEN) {
                    c.advance();                // optional EMPTY body
                    c.expect(TokenType.BRACE_CLOSE);
                }
                c.expect(TokenType.SEMI_COLON);
                yield new Protocol.PGCPApplicationDefaultCredentials(
                        c.spanOf(keywordTok, c.pos() - 1));
            }
            case "ApiToken" -> {
                c.expect(TokenType.BRACE_OPEN);
                String apiToken = null;
                while (!c.atEnd() && c.peek() != TokenType.BRACE_CLOSE) {
                    String key = c.parseIdentifier();
                    c.expect(TokenType.COLON);
                    if (!"apiToken".equals(key)) {
                        throw c.error("unknown ApiToken key: " + key);
                    }
                    apiToken = stringValue(c);
                    c.expect(TokenType.SEMI_COLON);
                }
                c.expect(TokenType.BRACE_CLOSE);
                c.expect(TokenType.SEMI_COLON);
                if (apiToken == null) {
                    throw c.error("ApiToken needs apiToken");
                }
                yield new Protocol.PApiToken(apiToken,
                        c.spanOf(keywordTok, c.pos() - 1));
            }
            case "MiddleTierUserNamePassword" -> {
                c.expect(TokenType.BRACE_OPEN);
                String vaultReference = null;
                while (!c.atEnd() && c.peek() != TokenType.BRACE_CLOSE) {
                    String key = c.parseIdentifier();
                    c.expect(TokenType.COLON);
                    if (!"vaultReference".equals(key)) {
                        throw c.error(
                                "unknown MiddleTierUserNamePassword key: " + key);
                    }
                    vaultReference = stringValue(c);
                    c.expect(TokenType.SEMI_COLON);
                }
                c.expect(TokenType.BRACE_CLOSE);
                c.expect(TokenType.SEMI_COLON);
                if (vaultReference == null) {
                    throw c.error("MiddleTierUserNamePassword needs"
                            + " vaultReference");
                }
                yield new Protocol.PMiddleTierUserNamePassword(vaultReference,
                        c.spanOf(keywordTok, c.pos() - 1));
            }
            default -> throw c.error("unsupported auth strategy: " + kind
                    + " (corpus-censused shapes only)");
        };
    }

    // ============================================================
    // The re-lex cursor (island + SPI feeds)
    // ============================================================

    /** A minimal cursor over a re-lexed slice, with the walker-offset span
     *  rule for embedded islands. The shared-stream feed uses the host
     *  parser's own cursor instead. */
    private static final class Cursor implements TokenStreamCursor {

        private final TokenStream tokens;
        private int pos;
        private final int lineOffset;
        private final int colOffset;

        Cursor(TokenStream tokens, int lineOffset, int colOffset) {
            this.tokens = tokens;
            this.lineOffset = lineOffset;
            this.colOffset = colOffset;
        }

        @Override
        public TokenStream tokens() {
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
