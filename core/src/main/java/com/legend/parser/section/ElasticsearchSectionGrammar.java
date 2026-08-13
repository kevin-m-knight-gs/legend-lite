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

    private static Protocol.PElasticsearch7Cluster.PEsIndex parseIndex(
            TokenStreamCursor c) {
        String indexName = c.parseIdentifier();
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
                String propName = c.parseIdentifier();
                c.expect(TokenType.COLON);
                String type = c.parseIdentifier();
                props.add(new Protocol.PElasticsearch7Cluster.PEsProperty(
                        propName, SectionParse.lowerFirst(type)));
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
