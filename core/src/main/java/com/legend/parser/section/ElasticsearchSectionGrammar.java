// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.parser.section;

import com.legend.lexer.TokenType;
import com.legend.parser.TokenStreamCursor;
import com.legend.protocol.Protocol;

import java.util.ArrayList;
import java.util.List;

/** {@code ###Elasticsearch} — {@code Elasticsearch7Cluster qn { indices:
 *  [ name: { properties: [ p: Keyword ]; } ]; }} (ZTailProbe
 *  "elastic-cluster"). */
public final class ElasticsearchSectionGrammar
        implements ElementwiseSectionGrammar {

    public static final ElasticsearchSectionGrammar INSTANCE =
            new ElasticsearchSectionGrammar();

    private ElasticsearchSectionGrammar() {
    }

    @Override
    public String name() {
        return "Elasticsearch";
    }

    @Override
    public String qualifiedNameOf(Protocol.Element e) {
        return ((Protocol.PElasticsearch7Cluster) e).qualifiedName();
    }

    @Override
    public com.legend.model.PackageableElement toModel(Protocol.Element element) {
        Protocol.PElasticsearch7Cluster s =
                (Protocol.PElasticsearch7Cluster) element;
        return new com.legend.model.GenericSectionElementDefinition(
                "Elasticsearch", "Elasticsearch7Cluster", s.qualifiedName(),
                java.util.Map.of(), null);
    }

    @Override
    public Protocol.Element parseOne(TokenStreamCursor c) {
        SectionParse.Head h = SectionParse.head(c, "Elasticsearch7Cluster");
        c.expect(TokenType.BRACE_OPEN);
        List<Protocol.PElasticsearch7Cluster.PEsIndex> indices =
                new ArrayList<>();
        java.util.Set<String> seenKeys = new java.util.HashSet<>();
        while (!c.atEnd() && c.peek() != TokenType.BRACE_CLOSE) {
            String key = c.parseIdentifier();
            TokenStreamCursor.once(seenKeys, key, c);
            c.expect(TokenType.COLON);
            if (!"indices".equals(key)) {
                throw c.error("unknown Elasticsearch7Cluster key '"
                        + key + "'");
            }
            c.expect(TokenType.BRACKET_OPEN);
            while (c.peek() != TokenType.BRACKET_CLOSE) {
                indices.add(parseIndex(c));
                if (!c.match(TokenType.COMMA)) {
                    break;
                }
            }
            c.expect(TokenType.BRACKET_CLOSE);
            c.expect(TokenType.SEMI_COLON);
        }
        c.expect(TokenType.BRACE_CLOSE);
        return new Protocol.PElasticsearch7Cluster(h.pkg(), h.name(), indices,
                c.spanOf(h.declStart(), c.pos() - 1));
    }

    /** {@code name: Type} or {@code name: Type { fields: [...] ; }} —
     *  the engine walker's per-type wire triple (key/_pure_protocol_type/
     *  type; probed live incl. the DOUBLED _pure_protocol_type quirk). */
    private static Protocol.PElasticsearch7Cluster.PEsProperty parseProperty(
            TokenStreamCursor c) {
        String propName = nameOrString(c);
        c.expect(TokenType.COLON);
        String kind = c.parseIdentifier();
        String[] t = switch (kind) {
            case "Keyword" -> new String[]{"keyword", "keywordProperty", "keyword"};
            case "Text" -> new String[]{"text", "textProperty", "text"};
            case "Date" -> new String[]{"date", "dateProperty", "date"};
            case "Short" -> new String[]{"_short", "shortNumberProperty", "short"};
            case "Byte" -> new String[]{"_byte", "byteNumberProperty", "byte"};
            case "Integer" -> new String[]{"integer", "integerNumberProperty", "integer"};
            case "Long" -> new String[]{"_long", "longNumberProperty", "long"};
            case "Float" -> new String[]{"_float", "floatNumberProperty", "float"};
            case "HalfFloat" -> new String[]{"half_float", "halfFloatNumberProperty", "half_float"};
            case "Double" -> new String[]{"_double", "doubleNumberProperty", "double"};
            case "Boolean" -> new String[]{"_boolean", "booleanProperty", "boolean"};
            case "Object" -> new String[]{"object", "objectProperty", "object"};
            case "Nested" -> new String[]{"nested", "nestedProperty", "nested"};
            default -> throw c.error("unmapped Elasticsearch property type '"
                    + kind + "'");
        };
        List<Protocol.PElasticsearch7Cluster.PEsProperty> fields = null;
        List<Protocol.PElasticsearch7Cluster.PEsProperty> children = null;
        if (c.match(TokenType.BRACE_OPEN)) {
            while (!c.atEnd() && c.peek() != TokenType.BRACE_CLOSE) {
                String fk = c.parseIdentifier();
                c.expect(TokenType.COLON);
                List<Protocol.PElasticsearch7Cluster.PEsProperty> into;
                if ("fields".equals(fk)) {
                    fields = fields == null ? new ArrayList<>() : fields;
                    into = fields;
                } else if ("properties".equals(fk)) {
                    children = children == null ? new ArrayList<>() : children;
                    into = children;
                } else {
                    throw c.error("unknown property key '" + fk + "'");
                }
                c.expect(TokenType.BRACKET_OPEN);
                while (c.peek() != TokenType.BRACKET_CLOSE) {
                    into.add(parseProperty(c));
                    if (!c.match(TokenType.COMMA)) {
                        break;
                    }
                }
                c.expect(TokenType.BRACKET_CLOSE);
                c.expect(TokenType.SEMI_COLON);
            }
            c.expect(TokenType.BRACE_CLOSE);
        }
        return new Protocol.PElasticsearch7Cluster.PEsProperty(propName,
                t[0], t[1], t[2], fields, children);
    }

    private static String nameOrString(TokenStreamCursor c) {
        if (c.peek() == TokenType.STRING) {
            return SectionParse.stringValue(c);
        }
        return c.parseIdentifier();
    }

    private static Protocol.PElasticsearch7Cluster.PEsIndex parseIndex(
            TokenStreamCursor c) {
        String indexName = nameOrString(c);
        c.expect(TokenType.COLON);
        c.expect(TokenType.BRACE_OPEN);
        List<Protocol.PElasticsearch7Cluster.PEsProperty> props =
                new ArrayList<>();
        while (c.peek() != TokenType.BRACE_CLOSE) {
            String pk = c.parseIdentifier();
            c.expect(TokenType.COLON);
            if (!"properties".equals(pk)) {
                throw c.error("unknown index key '" + pk + "'");
            }
            c.expect(TokenType.BRACKET_OPEN);
            while (c.peek() != TokenType.BRACKET_CLOSE) {
                props.add(parseProperty(c));
                if (!c.match(TokenType.COMMA)) {
                    break;
                }
            }
            c.expect(TokenType.BRACKET_CLOSE);
            c.expect(TokenType.SEMI_COLON);
        }
        c.expect(TokenType.BRACE_CLOSE);
        return new Protocol.PElasticsearch7Cluster.PEsIndex(indexName, props);
    }
}
