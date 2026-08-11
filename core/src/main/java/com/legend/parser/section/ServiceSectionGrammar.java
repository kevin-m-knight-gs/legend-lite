// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.parser.section;

import com.legend.lexer.TokenType;
import com.legend.parser.SpecParser;
import com.legend.parser.TokenStreamCursor;
import com.legend.protocol.Protocol;
import com.legend.protocol.SourceInfo;

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
public final class ServiceSectionGrammar
        implements ElementwiseSectionGrammar {

    /** The one stateless instance the registry hands out. */
    public static final ServiceSectionGrammar INSTANCE =
            new ServiceSectionGrammar();

    private ServiceSectionGrammar() {
    }

    @Override
    public String name() {
        return "Service";
    }

    @Override
    public String qualifiedNameOf(Protocol.Element e) {
        return switch (e) {
            case Protocol.PService s -> s.qualifiedName();
            case Protocol.PExecutionEnvironment ee -> ee.qualifiedName();
            default -> throw new IllegalStateException(
                    "not a service-section element: " + e.getClass());
        };
    }

    @Override
    public Protocol.Element parseOne(TokenStreamCursor c) {
        return parseElement(c);
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
        String ownershipKind = null;
        String ownershipId = null;
        String documentation = null;
        Boolean autoActivate = null;
        Protocol.PServiceExecution execution = null;
        Protocol.PLegacyServiceTest test = null;
        List<Protocol.PServiceTestSuite> testSuites = null;
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
                    // ownership: DID { identifier: '...' } — kind + one
                    // identifier field (probed: deploymentOwnership)
                    ownershipKind = c.parseIdentifier();
                    c.expect(TokenType.BRACE_OPEN);
                    String ik = c.parseIdentifier();
                    c.expect(TokenType.COLON);
                    if (!"identifier".equals(ik)) {
                        throw c.error("unknown ownership key: " + ik);
                    }
                    ownershipId = stringValue(c);
                    c.match(TokenType.SEMI_COLON);
                    c.expect(TokenType.BRACE_CLOSE);
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
                case "test" -> test = parseLegacyTest(c);
                case "testSuites" -> {
                    testSuites = parseTestSuites(c);
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
        // ENGINE-VERBATIM required fields (ServiceParserGrammar), BOTH
        // surfaces: the old lenient default (pattern -> "/") had no
        // reference in either grammar and nothing depended on it —
        // conformed away in the own-corpus decision review (2026-08-11)
        if (pattern == null) {
            throw c.error("Field 'pattern' is required");
        }
        if (documentation == null) {
            throw c.error("Field 'documentation' is required");
        }
        return new Protocol.PService(pkg, name, dec.stereotypes(),
                dec.taggedValues(), pattern, title, owners, ownershipKind,
                ownershipId, documentation, autoActivate, execution, test,
                testSuites, postValidationsSource,
                c.spanOf(declStart, c.pos() - 1));
    }

    /** The legacy test BODY loop ({@code data:} / {@code asserts:}),
     *  shared by Single and Multi keyed entries; returns data. */
    private static @com.legend.Nullable String parseLegacyBody(
            TokenStreamCursor c,
            List<Protocol.PLegacyServiceTest.PLegacyAssert> asserts) {
        String data = null;
        while (!c.atEnd() && c.peek() != TokenType.BRACE_CLOSE) {
            String key = c.parseIdentifier();
            c.expect(TokenType.COLON);
            switch (key) {
                case "data" -> {
                    data = stringValue(c);
                    c.expect(TokenType.SEMI_COLON);
                }
                case "asserts" -> {
                    c.expect(TokenType.BRACKET_OPEN);
                    while (c.peek() != TokenType.BRACKET_CLOSE) {
                        int as = c.pos();
                        c.expect(TokenType.BRACE_OPEN);
                        c.expect(TokenType.BRACKET_OPEN);
                        if (c.peek() != TokenType.BRACKET_CLOSE) {
                            throw c.error("legacy test parameter values are"
                                    + " out of the corpus-censused scope");
                        }
                        c.expect(TokenType.BRACKET_CLOSE);
                        c.expect(TokenType.COMMA);
                        int ls = c.pos();
                        int d = 0;
                        while (!c.atEnd()) {
                            TokenType tk = c.peek();
                            switch (tk) {
                                case PAREN_OPEN, BRACE_OPEN, BRACKET_OPEN ->
                                        d++;
                                case PAREN_CLOSE, BRACKET_CLOSE -> d--;
                                case BRACE_CLOSE -> {
                                    d--;
                                }
                                default -> { }
                            }
                            if (tk == TokenType.BRACE_CLOSE && d < 0) {
                                break;
                            }
                            c.advance();
                        }
                        com.legend.protocol.spec.ValueSpecification lambda =
                                com.legend.parser.SpecParser.parse(
                                        c.tokens().slice(ls, c.pos()));
                        c.expect(TokenType.BRACE_CLOSE);
                        asserts.add(new Protocol.PLegacyServiceTest
                                .PLegacyAssert(lambda,
                                        c.spanOf(as, c.pos() - 1)));
                        if (!c.match(TokenType.COMMA)) {
                            break;
                        }
                    }
                    c.expect(TokenType.BRACKET_CLOSE);
                    c.expect(TokenType.SEMI_COLON);
                }
                default -> throw c.error("unknown legacy test key '" + key
                        + "'");
            }
        }
        return data;
    }

    /** {@code testSuites: [ id: { data?; tests } ]} — the wire spans
     *  anchor at ID tokens; connection data and asserts ride THE
     *  embedded-data / test-assertion machinery (probed
     *  service-suites2). */
    private static List<Protocol.PServiceTestSuite> parseTestSuites(
            TokenStreamCursor c) {
        List<Protocol.PServiceTestSuite> out = new ArrayList<>();
        c.expect(TokenType.BRACKET_OPEN);
        while (!c.atEnd() && c.peek() != TokenType.BRACKET_CLOSE) {
            int ss = c.pos();
            String id = c.parseIdentifier();
            // 4.138 COMPACT form: id 'doc'? ( ... ) — v1 keeps id: { ... }
            if (c.peek() == TokenType.STRING
                    || c.peek() == TokenType.PAREN_OPEN) {
                out.add(parseCompactSuite(c, ss, id));
                c.match(TokenType.COMMA);
                continue;
            }
            c.expect(TokenType.COLON);
            c.expect(TokenType.BRACE_OPEN);
            Protocol.PServiceTestSuite.PSuiteData data = null;
            List<Protocol.PServiceTestSuite.PSuiteTest> tests =
                    new ArrayList<>();
            while (!c.atEnd() && c.peek() != TokenType.BRACE_CLOSE) {
                int keyStart = c.pos();
                String key = c.parseIdentifier();
                c.expect(TokenType.COLON);
                switch (key) {
                    case "data" -> data = parseSuiteData(c, keyStart);
                    case "tests" -> parseSuiteTests(c, tests);
                    default -> throw c.error("unknown testSuite key '"
                            + key + "'");
                }
            }
            c.expect(TokenType.BRACE_CLOSE);
            out.add(new Protocol.PServiceTestSuite(id, null, data, tests,
                    c.spanOf(ss, c.pos() - 1)));
            c.match(TokenType.COMMA);
        }
        c.expect(TokenType.BRACKET_CLOSE);
        return out;
    }

    /** The 4.138 COMPACT suite (ZServiceV2Probe): {@code id 'doc'? (
     *  (path; | path: Kind #{...}#;)* (testId 'doc'? => <expected>;)* )}
     *  — {@code path;} is a referenceDataResolver, {@code path: ...} a
     *  baseDataResolver, and each test carries ONE "default"
     *  equalTo-assertion. The testData wire span anchors at the suite's
     *  DOC string. */
    private static Protocol.PServiceTestSuite parseCompactSuite(
            TokenStreamCursor c, int ss, String id) {
        String doc = null;
        int docTok = c.pos();
        if (c.peek() == TokenType.STRING) {
            doc = stringValue(c);
        }
        c.expect(TokenType.PAREN_OPEN);
        List<Protocol.PServiceTestSuite.PResolverData> resolvers =
                new ArrayList<>();
        List<Protocol.PServiceTestSuite.PSuiteTest> tests = new ArrayList<>();
        while (!c.atEnd() && c.peek() != TokenType.PAREN_CLOSE) {
            int es = c.pos();
            String name = Protocol.unquotePath(c.parseQualifiedName());
            int nameEnd = c.pos() - 1;
            if (c.peek() == TokenType.SEMI_COLON) {
                c.advance();                        // reference resolver
                // resolver spans END BEFORE the ';' (G8-adjudicated)
                resolvers.add(new Protocol.PServiceTestSuite.PResolverData(
                        null, name, c.spanOf(es, nameEnd),
                        c.spanOf(es, c.pos() - 2)));
            } else if (c.peek() == TokenType.COLON) {
                c.advance();                        // base resolver
                Protocol.PEmbeddedDataValue v = com.legend.parser
                        .MappingProtocolParser.parseEmbeddedValueAt(c);
                c.expect(TokenType.SEMI_COLON);
                resolvers.add(new Protocol.PServiceTestSuite.PResolverData(
                        v, name, c.spanOf(es, nameEnd),
                        c.spanOf(es, c.pos() - 2)));
            } else {
                String tdoc = null;                 // a test entry
                if (c.peek() == TokenType.STRING) {
                    tdoc = stringValue(c);
                }
                List<Protocol.PServiceTestSuite.PSuiteParam> parameters =
                        null;
                if (c.peek() == TokenType.PAREN_OPEN) {
                    c.advance();                    // ( name = value, ... )
                    parameters = new ArrayList<>();
                    while (c.peek() != TokenType.PAREN_CLOSE) {
                        String pn = c.parseIdentifier();
                        c.expect(TokenType.EQUAL);
                        int vs = c.pos();
                        int d = 0;
                        while (!c.atEnd()) {
                            TokenType tk = c.peek();
                            switch (tk) {
                                case PAREN_OPEN, BRACE_OPEN,
                                        BRACKET_OPEN -> d++;
                                case PAREN_CLOSE, BRACE_CLOSE,
                                        BRACKET_CLOSE -> d--;
                                default -> { }
                            }
                            if ((tk == TokenType.COMMA && d <= 0)
                                    || (tk == TokenType.PAREN_CLOSE
                                            && d < 0)) {
                                break;
                            }
                            c.advance();
                        }
                        parameters.add(new Protocol.PServiceTestSuite
                                .PSuiteParam(pn, SpecParser.parse(
                                        c.tokens().slice(vs, c.pos()))));
                        c.match(TokenType.COMMA);
                    }
                    c.expect(TokenType.PAREN_CLOSE);
                }
                List<String> keys = new ArrayList<>();
                if (c.peek() == TokenType.BRACKET_OPEN) {
                    c.advance();                    // [ 'KEY_A', ... ]
                    while (c.peek() != TokenType.BRACKET_CLOSE) {
                        keys.add(stringValue(c));
                        if (!c.match(TokenType.COMMA)) {
                            break;
                        }
                    }
                    c.expect(TokenType.BRACKET_CLOSE);
                }
                String fmt = null;
                if (c.match(TokenType.COLON)) {     // : PURE_TDSOBJECT
                    fmt = c.parseIdentifier();
                }
                c.expect(TokenType.EQUAL);
                c.expect(TokenType.GREATER_THAN);
                Protocol.PTestAssertion a = com.legend.parser
                        .MappingProtocolParser.parseDefaultAssertionAt(c);
                c.match(TokenType.SEMI_COLON);
                tests.add(new Protocol.PServiceTestSuite.PSuiteTest(name,
                        tdoc, fmt, keys, parameters, List.of(a),
                        c.spanOf(es, c.pos() - 1)));
            }
        }
        int close = c.pos();
        c.expect(TokenType.PAREN_CLOSE);
        Protocol.PServiceTestSuite.PSuiteData data = resolvers.isEmpty()
                ? null
                : new Protocol.PServiceTestSuite.PSuiteData(List.of(),
                        resolvers, c.spanOf(docTok, close));
        return new Protocol.PServiceTestSuite(id, doc, data, tests,
                c.spanOf(ss, close));
    }

    private static Protocol.PServiceTestSuite.PSuiteData parseSuiteData(
            TokenStreamCursor c, int keyStart) {
        c.expect(TokenType.BRACKET_OPEN);
        List<Protocol.PServiceTestSuite.PSuiteConnData> conns =
                new ArrayList<>();
        while (!c.atEnd() && c.peek() != TokenType.BRACKET_CLOSE) {
            String k = c.parseIdentifier();
            c.expect(TokenType.COLON);
            if (!"connections".equals(k)) {
                throw c.error("unknown suite data key '" + k + "'");
            }
            c.expect(TokenType.BRACKET_OPEN);
            while (!c.atEnd() && c.peek() != TokenType.BRACKET_CLOSE) {
                int cs = c.pos();
                // connection ids may be QUALIFIED paths
                String cid = Protocol.unquotePath(c.parseQualifiedName());
                c.expect(TokenType.COLON);
                Protocol.PEmbeddedDataValue value = com.legend.parser
                        .MappingProtocolParser.parseEmbeddedValueAt(c);
                conns.add(new Protocol.PServiceTestSuite.PSuiteConnData(cid,
                        value, c.spanOf(cs, c.pos() - 1)));
                c.match(TokenType.COMMA);
            }
            c.expect(TokenType.BRACKET_CLOSE);
        }
        c.expect(TokenType.BRACKET_CLOSE);
        return new Protocol.PServiceTestSuite.PSuiteData(conns, null,
                c.spanOf(keyStart, c.pos() - 1));
    }

    private static void parseSuiteTests(TokenStreamCursor c,
            List<Protocol.PServiceTestSuite.PSuiteTest> out) {
        c.expect(TokenType.BRACKET_OPEN);
        while (!c.atEnd() && c.peek() != TokenType.BRACKET_CLOSE) {
            int ts = c.pos();
            String id = c.parseIdentifier();
            c.expect(TokenType.COLON);
            c.expect(TokenType.BRACE_OPEN);
            String serializationFormat = null;
            List<String> keys = new ArrayList<>();
            List<Protocol.PServiceTestSuite.PSuiteParam> parameters = null;
            List<Protocol.PTestAssertion> asserts = new ArrayList<>();
            while (!c.atEnd() && c.peek() != TokenType.BRACE_CLOSE) {
                String key = c.parseIdentifier();
                c.expect(TokenType.COLON);
                switch (key) {
                    case "serializationFormat" -> {
                        serializationFormat = c.parseIdentifier();
                        c.expect(TokenType.SEMI_COLON);
                    }
                    case "keys" -> {
                        c.expect(TokenType.BRACKET_OPEN);
                        while (c.peek() != TokenType.BRACKET_CLOSE) {
                            keys.add(stringValue(c));
                            if (!c.match(TokenType.COMMA)) {
                                break;
                            }
                        }
                        c.expect(TokenType.BRACKET_CLOSE);
                        c.match(TokenType.SEMI_COLON);
                    }
                    case "parameters" -> {
                        // [ name = value, ... ] — values ride the spec
                        // wire (probed service-test-params)
                        parameters = new ArrayList<>();
                        c.expect(TokenType.BRACKET_OPEN);
                        while (c.peek() != TokenType.BRACKET_CLOSE) {
                            String pn = c.parseIdentifier();
                            c.expect(TokenType.EQUAL);
                            int vs2 = c.pos();
                            int d2 = 0;
                            while (!c.atEnd()) {
                                TokenType tk = c.peek();
                                switch (tk) {
                                    case PAREN_OPEN, BRACE_OPEN,
                                            BRACKET_OPEN -> d2++;
                                    case PAREN_CLOSE, BRACE_CLOSE,
                                            BRACKET_CLOSE -> d2--;
                                    default -> { }
                                }
                                if ((tk == TokenType.COMMA && d2 <= 0)
                                        || (tk == TokenType.BRACKET_CLOSE
                                                && d2 < 0)) {
                                    break;
                                }
                                c.advance();
                            }
                            parameters.add(new Protocol.PServiceTestSuite
                                    .PSuiteParam(pn, SpecParser.parse(
                                            c.tokens().slice(vs2,
                                                    c.pos()))));
                            c.match(TokenType.COMMA);
                        }
                        c.expect(TokenType.BRACKET_CLOSE);
                        c.match(TokenType.SEMI_COLON);
                    }
                    case "asserts" -> {
                        c.expect(TokenType.BRACKET_OPEN);
                        while (!c.atEnd()
                                && c.peek() != TokenType.BRACKET_CLOSE) {
                            asserts.add(com.legend.parser
                                    .MappingProtocolParser
                                    .parseTestAssertionAt(c));
                            c.match(TokenType.COMMA);
                        }
                        c.expect(TokenType.BRACKET_CLOSE);
                    }
                    default -> throw c.error("unknown suite test key '"
                            + key + "'");
                }
            }
            c.expect(TokenType.BRACE_CLOSE);
            out.add(new Protocol.PServiceTestSuite.PSuiteTest(id, null,
                    serializationFormat, keys, parameters, asserts,
                    c.spanOf(ts, c.pos() - 1)));
            c.match(TokenType.COMMA);
        }
        c.expect(TokenType.BRACKET_CLOSE);
    }

    /** {@code test: Single { data: '...'; asserts: [ { [params], lambda }
     *  ] }} — wire {@code singleExecutionTest}; the engine REFUSES an
     *  empty asserts list (sentinel parity). */
    private static Protocol.PLegacyServiceTest parseLegacyTest(
            TokenStreamCursor c) {
        int start = c.pos();
        String kind = c.parseIdentifier();
        if ("Multi".equals(kind)) {
            // Multi tests: tests['KEY']: { data; asserts } entries — the
            // entry span starts at the tests keyword (probed
            // service-multi-test); empty bodies legal
            c.expect(TokenType.BRACE_OPEN);
            List<Protocol.PLegacyServiceTest.PKeyedLegacyTest> keyed =
                    new ArrayList<>();
            while (!c.atEnd() && c.peek() != TokenType.BRACE_CLOSE) {
                int es = c.pos();
                String tk = c.parseIdentifier();
                if (!"tests".equals(tk)) {
                    throw c.error("unknown Multi test key '" + tk + "'");
                }
                c.expect(TokenType.BRACKET_OPEN);
                String keyValue = stringValue(c);
                c.expect(TokenType.BRACKET_CLOSE);
                c.expect(TokenType.COLON);
                c.expect(TokenType.BRACE_OPEN);
                List<Protocol.PLegacyServiceTest.PLegacyAssert> ka =
                        new ArrayList<>();
                String kd = parseLegacyBody(c, ka);
                c.expect(TokenType.BRACE_CLOSE);
                c.match(TokenType.SEMI_COLON);
                if (kd == null) {
                    throw c.error("Multi test '" + keyValue
                            + "' needs data");
                }
                keyed.add(new Protocol.PLegacyServiceTest.PKeyedLegacyTest(
                        keyValue, kd, ka, c.spanOf(es, c.pos() - 1)));
                c.match(TokenType.COMMA);
            }
            c.expect(TokenType.BRACE_CLOSE);
            c.match(TokenType.SEMI_COLON);
            return new Protocol.PLegacyServiceTest("Multi", null,
                    java.util.List.of(), keyed,
                    c.spanOf(start, c.pos() - 1));
        }
        if (!"Single".equals(kind)) {
            throw c.error("unsupported legacy test kind: " + kind);
        }
        c.expect(TokenType.BRACE_OPEN);
        List<Protocol.PLegacyServiceTest.PLegacyAssert> asserts =
                new ArrayList<>();
        String data = parseLegacyBody(c, asserts);
        c.expect(TokenType.BRACE_CLOSE);
        c.match(TokenType.SEMI_COLON);
        if (data == null) {
            throw c.error("legacy test needs data");
        }
        return new Protocol.PLegacyServiceTest("Single", data, asserts,
                java.util.List.of(), c.spanOf(start, c.pos() - 1));
    }

    private static Protocol.PServiceExecution parseExecution(
            TokenStreamCursor c, String qn) {
        int execStart = c.pos();
        String kind = c.parseIdentifier();
        return switch (kind) {
            case "Single" -> {
                c.expect(TokenType.BRACE_OPEN);
                com.legend.protocol.spec.ValueSpecification query = null;
                String mapping = null;
                SourceInfo mappingSpan = null;
                String runtime = null;
                SourceInfo runtimeSpan = null;
                Protocol.PEmbeddedRuntime embedded = null;
                while (!c.atEnd() && c.peek() != TokenType.BRACE_CLOSE) {
                    String key = c.parseIdentifier();
                    c.expect(TokenType.COLON);
                    switch (key) {
                        case "query" -> query = parseQuery(c, qn);
                        case "mapping" -> {
                            int ms = c.pos();
                            mapping = Protocol.unquotePath(c.parseQualifiedName());
                            mappingSpan = c.spanOf(ms, c.pos() - 1);
                            c.expect(TokenType.SEMI_COLON);
                        }
                        case "runtime" -> {
                            if (c.peek() == TokenType.ISLAND_OPEN) {
                                embedded = parseEmbeddedRuntime(c);
                            } else {
                                int rs = c.pos();
                                runtime = Protocol.unquotePath(
                                        c.parseQualifiedName());
                                runtimeSpan = c.spanOf(rs, c.pos() - 1);
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
                yield new Protocol.PSingleExecution(query, mapping,
                        mappingSpan, runtime, runtimeSpan, embedded,
                        c.spanOf(execStart, c.pos() - 1));
            }
            case "Multi" -> {
                c.expect(TokenType.BRACE_OPEN);
                com.legend.protocol.spec.ValueSpecification query = null;
                String executionKey = null;
                List<Protocol.PKeyedExecution> executions = null;
                while (!c.atEnd() && c.peek() != TokenType.BRACE_CLOSE) {
                    int keyTok = c.pos();
                    String key = c.parseIdentifier();
                    if ("executions".equals(key)) {
                        // executions['QA']: { mapping: ...; runtime: ...; }
                        // — the wire span starts at the KEY token (probed)
                        c.expect(TokenType.BRACKET_OPEN);
                        String keyValue = stringValue(c);
                        c.expect(TokenType.BRACKET_CLOSE);
                        c.expect(TokenType.COLON);
                        if (executions == null) {
                            executions = new ArrayList<>();
                        }
                        executions.add(parseKeyedBody(c, keyValue, keyTok));
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
                if (query == null) {
                    throw c.error("Multi execution of '" + qn
                            + "' needs a query");
                }
                yield new Protocol.PMultiExecution(query, executionKey,
                        executions, c.spanOf(execStart, c.pos() - 1));
            }
            default -> throw c.error("unsupported execution kind: " + kind
                    + " (expected Single or Multi)");
        };
    }

    /** {@code { mapping: ...; runtime: ...; }} for one keyed environment. */
    private static Protocol.PKeyedExecution parseKeyedBody(
            TokenStreamCursor c, String keyValue, int start) {
        c.expect(TokenType.BRACE_OPEN);
        String mapping = null;
        SourceInfo mappingSpan = null;
        String runtime = null;
        SourceInfo runtimeSpan = null;
        Protocol.PEmbeddedRuntime embedded = null;
        while (!c.atEnd() && c.peek() != TokenType.BRACE_CLOSE) {
            String key = c.parseIdentifier();
            c.expect(TokenType.COLON);
            switch (key) {
                case "mapping" -> {
                    int ms = c.pos();
                    mapping = Protocol.unquotePath(c.parseQualifiedName());
                    mappingSpan = c.spanOf(ms, c.pos() - 1);
                }
                case "runtime" -> {
                    if (c.peek() == TokenType.ISLAND_OPEN) {
                        embedded = parseEmbeddedRuntime(c);
                    } else {
                        int rs = c.pos();
                        runtime = Protocol.unquotePath(c.parseQualifiedName());
                        runtimeSpan = c.spanOf(rs, c.pos() - 1);
                    }
                }
                default -> throw c.error(
                        "unknown key '" + key + "' inside keyed execution");
            }
            c.expect(TokenType.SEMI_COLON);
        }
        c.expect(TokenType.BRACE_CLOSE);
        return new Protocol.PKeyedExecution(keyValue, mapping, mappingSpan,
                runtime, runtimeSpan, embedded,
                c.spanOf(start, c.pos() - 1));
    }

    /** An embedded anonymous runtime island — re-lexed through THE runtime
     *  grammar's body loop; the wire span is CONTENT-anchored (first..last
     *  content token, probed service-single). */
    private static Protocol.PEmbeddedRuntime parseEmbeddedRuntime(
            TokenStreamCursor c) {
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
        Protocol.PEmbeddedRuntime value = RuntimeSectionGrammar
                .parseEmbeddedBody(emb, c.tokens().startLine(embStart),
                        c.tokens().startColumn(embStart));
        c.expect(TokenType.ISLAND_END);
        return value;
    }

    /** {@code query: |expr...;} — scan to the top-level {@code ;} (paren
     *  depth aware) and spec-parse the slice, exactly as the retired twin
     *  did; the LEADING '|' stays in the slice so the wire query is the
     *  full lambda. */
    private static com.legend.protocol.spec.ValueSpecification parseQuery(
            TokenStreamCursor c, String qn) {
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
                // unlike Multi's quoted executions['KEY']; the wire span
                // starts at the KEY token (probed exec-env)
                int keyTok2 = c.pos();
                String keyValue = c.parseIdentifier();
                c.expect(TokenType.COLON);
                executions.add(parseKeyedBody(c, keyValue, keyTok2));
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

}
