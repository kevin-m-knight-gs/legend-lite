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
 * {@code ###ServiceStore} — the service-store DSL (ZTailProbe
 * "svcstore-empty"/"svcstore-rich"): an optional description (parsed,
 * never wired), then services and recursively nested service groups.
 * Parameters carry typed references ({@code String} / {@code [Integer]} /
 * {@code Class <- binding}) and OpenAPI-ish facets whose {@code x = y}
 * texts keep their own spans.
 */
public final class ServiceStoreSectionGrammar
        implements ElementwiseSectionGrammar {

    public static final ServiceStoreSectionGrammar INSTANCE =
            new ServiceStoreSectionGrammar();

    private ServiceStoreSectionGrammar() {
    }

    @Override
    public String name() {
        return "ServiceStore";
    }

    @Override
    public String qualifiedNameOf(Protocol.Element e) {
        return ((Protocol.PServiceStoreDefinition) e).qualifiedName();
    }

    @Override
    public com.legend.model.PackageableElement toModel(Protocol.Element element) {
        Protocol.PServiceStoreDefinition s =
                (Protocol.PServiceStoreDefinition) element;
        return new com.legend.model.GenericSectionElementDefinition(
                "ServiceStore", "ServiceStore", s.qualifiedName(),
                java.util.Map.of(), null);
    }

    @Override
    public Protocol.Element parseOne(TokenStreamCursor c) {
        SectionParse.Head h = SectionParse.head(c, "ServiceStore");
        c.expect(TokenType.PAREN_OPEN);
        String description = null;
        List<Protocol.PServiceStoreElement> elements = new ArrayList<>();
        while (!c.atEnd() && c.peek() != TokenType.PAREN_CLOSE) {
            String kw = c.safeText();
            switch (kw) {
                case "description" -> {
                    c.advance();
                    c.expect(TokenType.COLON);
                    description = SectionParse.stringValue(c);
                    c.expect(TokenType.SEMI_COLON);
                }
                case "Service" -> elements.add(parseService(c));
                case "ServiceGroup" -> elements.add(parseGroup(c));
                default -> throw c.error(
                        "unknown ServiceStore element: " + kw);
            }
        }
        c.expect(TokenType.PAREN_CLOSE);
        return new Protocol.PServiceStoreDefinition(h.pkg(), h.name(),
                description, elements, c.spanOf(h.declStart(), c.pos() - 1));
    }

    private static Protocol.PSsServiceGroup parseGroup(TokenStreamCursor c) {
        int start = c.pos();
        c.advance();
        String id = c.parseIdentifier();
        c.expect(TokenType.PAREN_OPEN);
        String path = null;
        List<Protocol.PServiceStoreElement> elements = new ArrayList<>();
        while (!c.atEnd() && c.peek() != TokenType.PAREN_CLOSE) {
            String kw = c.safeText();
            switch (kw) {
                case "path" -> {
                    c.advance();
                    c.expect(TokenType.COLON);
                    path = SectionParse.stringValue(c);
                    c.expect(TokenType.SEMI_COLON);
                }
                case "Service" -> elements.add(parseService(c));
                case "ServiceGroup" -> elements.add(parseGroup(c));
                default -> throw c.error(
                        "unknown ServiceGroup element: " + kw);
            }
        }
        c.expect(TokenType.PAREN_CLOSE);
        if (path == null) {
            throw c.error("ServiceGroup '" + id + "' needs a path");
        }
        return new Protocol.PSsServiceGroup(id, path, elements,
                c.spanOf(start, c.pos() - 1));
    }

    private static Protocol.PSsService parseService(TokenStreamCursor c) {
        int start = c.pos();
        c.advance();
        String id = c.parseIdentifier();
        c.expect(TokenType.PAREN_OPEN);
        String path = null;
        Protocol.PSsTypeRef requestBody = null;
        String method = null;
        List<Protocol.PSsParam> parameters = null;
        Protocol.PSsTypeRef response = null;
        List<String> security = new ArrayList<>();
        while (!c.atEnd() && c.peek() != TokenType.PAREN_CLOSE) {
            String key = c.parseIdentifier();
            c.expect(TokenType.COLON);
            switch (key) {
                case "path" -> path = SectionParse.stringValue(c);
                case "requestBody" -> requestBody = parseTypeRef(c);
                case "method" -> method = c.parseIdentifier();
                case "parameters" -> parameters = parseParameters(c);
                case "response" -> response = parseTypeRef(c);
                case "security" -> {
                    c.expect(TokenType.BRACKET_OPEN);
                    while (c.peek() != TokenType.BRACKET_CLOSE) {
                        security.add(c.parseIdentifier());
                        if (!c.match(TokenType.COMMA)) {
                            break;
                        }
                    }
                    c.expect(TokenType.BRACKET_CLOSE);
                }
                default -> throw c.error(
                        "unknown Service key '" + key + "'");
            }
            c.expect(TokenType.SEMI_COLON);
        }
        c.expect(TokenType.PAREN_CLOSE);
        if (path == null || method == null || response == null) {
            throw c.error("Service '" + id + "' needs path, method and"
                    + " response");
        }
        return new Protocol.PSsService(id, path, requestBody, method,
                parameters, response, security,
                c.spanOf(start, c.pos() - 1));
    }

    private static List<Protocol.PSsParam> parseParameters(
            TokenStreamCursor c) {
        List<Protocol.PSsParam> out = new ArrayList<>();
        c.expect(TokenType.PAREN_OPEN);
        while (c.peek() != TokenType.PAREN_CLOSE) {
            out.add(parseParameter(c));
            if (!c.match(TokenType.COMMA)) {
                break;
            }
        }
        c.expect(TokenType.PAREN_CLOSE);
        return out;
    }

    private static Protocol.PSsParam parseParameter(TokenStreamCursor c) {
        int start = c.pos();
        String name;
        if (c.peek() == TokenType.QUOTED_STRING) {
            String raw = c.text();
            c.advance();
            name = raw.substring(1, raw.length() - 1);
        } else {
            name = c.parseIdentifier();
        }
        c.expect(TokenType.COLON);
        Protocol.PSsTypeRef type = parseTypeRef(c);
        Boolean allowReserved = null;
        Boolean required = null;
        String location = null;
        String style = null;
        SourceInfo styleSpan = null;
        Boolean explode = null;
        SourceInfo explodeSpan = null;
        String enumeration = null;
        if (c.match(TokenType.PAREN_OPEN)) {
            while (c.peek() != TokenType.PAREN_CLOSE) {
                int optStart = c.pos();
                String opt = c.parseIdentifier();
                c.expect(TokenType.EQUAL);
                switch (opt) {
                    case "allowReserved" -> allowReserved = bool(c);
                    case "required" -> required = bool(c);
                    case "location" -> location = c.parseIdentifier();
                    case "style" -> {
                        style = c.parseIdentifier();
                        styleSpan = c.spanOf(optStart, c.pos() - 1);
                    }
                    case "explode" -> {
                        explode = bool(c);
                        explodeSpan = c.spanOf(optStart, c.pos() - 1);
                    }
                    case "enum" -> enumeration = Protocol.unquotePath(
                            c.parseQualifiedName());
                    default -> throw c.error(
                            "unknown parameter option '" + opt + "'");
                }
                if (!c.match(TokenType.COMMA)) {
                    break;
                }
            }
            c.expect(TokenType.PAREN_CLOSE);
        }
        if (location == null) {
            throw c.error("parameter '" + name + "' needs a location");
        }
        return new Protocol.PSsParam(name, type, allowReserved, required,
                location.toUpperCase(java.util.Locale.ROOT), style,
                styleSpan, explode, explodeSpan, enumeration,
                c.spanOf(start, c.pos() - 1));
    }

    /** {@code String | [Integer] | Class <- binding | [Class <- binding]}. */
    private static Protocol.PSsTypeRef parseTypeRef(TokenStreamCursor c) {
        int start = c.pos();
        boolean list = c.match(TokenType.BRACKET_OPEN);
        String head = Protocol.unquotePath(c.parseQualifiedName());
        String complexType = null;
        String binding = null;
        if (c.peek() == TokenType.LESS_THAN) {
            c.advance();
            c.expect(TokenType.MINUS);
            complexType = head;
            binding = Protocol.unquotePath(c.parseQualifiedName());
        }
        if (list) {
            c.expect(TokenType.BRACKET_CLOSE);
        }
        SourceInfo span = c.spanOf(start, c.pos() - 1);
        return complexType != null
                ? new Protocol.PSsTypeRef(null, complexType, binding, list,
                        span)
                : new Protocol.PSsTypeRef(head, null, null, list, span);
    }

    private static Boolean bool(TokenStreamCursor c) {
        if (c.match(TokenType.TRUE)) {
            return Boolean.TRUE;
        }
        if (c.match(TokenType.FALSE)) {
            return Boolean.FALSE;
        }
        throw c.error("expected true or false, got " + c.safeText());
    }
}
