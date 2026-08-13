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
 * {@code ###DataQualityValidation} hosts THREE kinds (ZTailProbe
 * "dq-validation"/"dq-dataspace-ctx"/"dq-relation-validation"/
 * "dq-relation-comparison"): the graph-tree {@code DataQualityValidation},
 * {@code DataQualityRelationValidation} (query + named assertion lambdas)
 * and {@code DataQualityRelationComparison} (source/target lambdas). All
 * lambdas ride THE SpecParser and the spec wire.
 */
public final class DataQualityValidationSectionGrammar
        implements ElementwiseSectionGrammar {

    public static final DataQualityValidationSectionGrammar INSTANCE =
            new DataQualityValidationSectionGrammar();

    private DataQualityValidationSectionGrammar() {
    }

    @Override
    public String name() {
        return "DataQualityValidation";
    }

    @Override
    public String qualifiedNameOf(Protocol.Element e) {
        return switch (e) {
            case Protocol.PDataQualityValidation v -> v.qualifiedName();
            case Protocol.PDataQualityRelationValidation v ->
                    v.qualifiedName();
            case Protocol.PDataQualityRelationComparison v ->
                    v.qualifiedName();
            default -> throw new IllegalStateException(
                    "not a DataQualityValidation element: " + e.getClass());
        };
    }

    @Override
    public com.legend.model.PackageableElement toModel(Protocol.Element element) {
        String kind = switch (element) {
            case Protocol.PDataQualityValidation v -> "DataQualityValidation";
            case Protocol.PDataQualityRelationValidation v ->
                    "DataQualityRelationValidation";
            default -> "DataQualityRelationComparison";
        };
        return new com.legend.model.GenericSectionElementDefinition(
                "DataQualityValidation", kind, qualifiedNameOf(element),
                java.util.Map.of(), null);
    }

    @Override
    public Protocol.Element parseOne(TokenStreamCursor c) {
        String kind = c.safeText();
        return switch (kind) {
            case "DataQualityValidation" -> parseValidation(c);
            case "DataQualityRelationValidation" -> parseRelationValidation(c);
            case "DataQualityRelationComparison" -> parseRelationComparison(c);
            default -> throw c.error(
                    "unsupported ###DataQualityValidation element: " + kind);
        };
    }

    private static Protocol.PDataQualityValidation parseValidation(
            TokenStreamCursor c) {
        SectionParse.Head h = SectionParse.head(c, "DataQualityValidation");
        c.expect(TokenType.BRACE_OPEN);
        String ctxKind = null;
        String ctxPath = null;
        SourceInfo ctxPathSpan = null;
        String ctxSecond = null;
        SourceInfo ctxSecondSpan = null;
        Protocol.PDqTreeNode tree = null;
        com.legend.protocol.spec.ValueSpecification filter = null;
        java.util.Set<String> seenKeys = new java.util.HashSet<>();
        while (!c.atEnd() && c.peek() != TokenType.BRACE_CLOSE) {
            String key = c.parseIdentifier();
            TokenStreamCursor.once(seenKeys, key, c);
            c.expect(TokenType.COLON);
            switch (key) {
                case "context" -> {
                    ctxKind = c.parseIdentifier();
                    c.expect(TokenType.PAREN_OPEN);
                    int s = c.pos();
                    ctxPath = Protocol.unquotePath(c.parseQualifiedName());
                    ctxPathSpan = c.spanOf(s, c.pos() - 1);
                    if (c.match(TokenType.COMMA)) {
                        int s2 = c.pos();
                        ctxSecond = c.peek() == TokenType.STRING
                                ? SectionParse.stringValue(c)
                                : Protocol.unquotePath(c.parseQualifiedName());
                        ctxSecondSpan = c.spanOf(s2, c.pos() - 1);
                    }
                    c.expect(TokenType.PAREN_CLOSE);
                }
                case "validationTree" -> tree = parseTreeIsland(c);
                case "filter" -> filter = SectionParse.specToSemicolon(c);
                default -> throw c.error(
                        "unknown DataQualityValidation key '" + key + "'");
            }
            c.expect(TokenType.SEMI_COLON);
        }
        c.expect(TokenType.BRACE_CLOSE);
        if (ctxKind == null || ctxPath == null || tree == null) {
            throw com.legend.parser.TokenStreamCursor.throwAt(c.tokens(), h.declStart(),
                    "DataQualityValidation needs context and "
                    + "validationTree");
        }
        return new Protocol.PDataQualityValidation(h.pkg(), h.name(),
                h.dec().stereotypes(), h.dec().taggedValues(), ctxKind,
                ctxPath, java.util.Objects.requireNonNull(ctxPathSpan),
                ctxSecond, ctxSecondSpan, tree, filter,
                c.spanOf(h.declStart(), c.pos() - 1));
    }

    /** {@code $[ Class<c1, 'c2'>{ prop, sub<c>{...} } ]$}. */
    private static Protocol.PDqTreeNode parseTreeIsland(TokenStreamCursor c) {
        c.expect(TokenType.DOLLAR);
        c.expect(TokenType.BRACKET_OPEN);
        Protocol.PDqTreeNode root = parseTreeNode(c, true);
        c.expect(TokenType.BRACKET_CLOSE);
        c.expect(TokenType.DOLLAR);
        return root;
    }

    private static Protocol.PDqTreeNode parseTreeNode(TokenStreamCursor c,
            boolean root) {
        int s = c.pos();
        String qn = Protocol.unquotePath(c.parseQualifiedName());
        SourceInfo span = c.spanOf(s, c.pos() - 1);
        List<String> constraints = new ArrayList<>();
        if (c.match(TokenType.LESS_THAN)) {
            while (c.peek() != TokenType.GREATER_THAN) {
                constraints.add(c.peek() == TokenType.STRING
                        ? SectionParse.stringValue(c) : c.parseIdentifier());
                if (!c.match(TokenType.COMMA)) {
                    break;
                }
            }
            c.expect(TokenType.GREATER_THAN);
        }
        List<Protocol.PDqTreeNode> subTrees = new ArrayList<>();
        if (c.match(TokenType.BRACE_OPEN)) {
            while (c.peek() != TokenType.BRACE_CLOSE) {
                subTrees.add(parseTreeNode(c, false));
                if (!c.match(TokenType.COMMA)) {
                    break;
                }
            }
            c.expect(TokenType.BRACE_CLOSE);
        }
        return new Protocol.PDqTreeNode(root ? qn : null, root ? null : qn,
                constraints, subTrees, span);
    }

    private static Protocol.PDataQualityRelationValidation
            parseRelationValidation(TokenStreamCursor c) {
        SectionParse.Head h = SectionParse.head(c,
                "DataQualityRelationValidation");
        c.expect(TokenType.BRACE_OPEN);
        com.legend.protocol.spec.ValueSpecification query = null;
        List<Protocol.PDqRelationCheck> validations = new ArrayList<>();
        java.util.Set<String> seenKeys2 = new java.util.HashSet<>();
        while (!c.atEnd() && c.peek() != TokenType.BRACE_CLOSE) {
            String key = c.parseIdentifier();
            TokenStreamCursor.once(seenKeys2, key, c);
            c.expect(TokenType.COLON);
            switch (key) {
                case "query" -> query = SectionParse.specToSemicolon(c);
                case "validations" -> parseChecks(c, validations, h.declStart());
                default -> throw c.error(
                        "unknown DataQualityRelationValidation key '"
                                + key + "'");
            }
            c.expect(TokenType.SEMI_COLON);
        }
        c.expect(TokenType.BRACE_CLOSE);
        if (!seenKeys2.contains("validations")) {
            // engine-verbatim (sectioned negative pin #22)
            throw TokenStreamCursor.throwAt(c.tokens(), h.declStart(),
                    "Field 'validations' is required");
        }
        if (query == null) {
            throw com.legend.parser.TokenStreamCursor.throwAt(c.tokens(), h.declStart(),
                    "DataQualityRelationValidation needs a query");
        }
        return new Protocol.PDataQualityRelationValidation(h.pkg(), h.name(),
                h.dec().stereotypes(), h.dec().taggedValues(), query,
                validations, c.spanOf(h.declStart(), c.pos() - 1));
    }

    private static void parseChecks(TokenStreamCursor c,
            List<Protocol.PDqRelationCheck> out, int elementAnchor) {
        c.expect(TokenType.BRACKET_OPEN);
        while (c.peek() != TokenType.BRACKET_CLOSE) {
            c.expect(TokenType.BRACE_OPEN);
            String name = null;
            String description = null;
            com.legend.protocol.spec.ValueSpecification assertion = null;
            while (c.peek() != TokenType.BRACE_CLOSE) {
                String k = c.parseIdentifier();
                c.expect(TokenType.COLON);
                switch (k) {
                    case "name" -> name = SectionParse.stringValue(c);
                    case "description" ->
                            description = SectionParse.stringValue(c);
                    case "assertion" ->
                            assertion = SectionParse.specToSemicolon(c);
                    default -> throw c.error(
                            "unknown validation key '" + k + "'");
                }
                c.expect(TokenType.SEMI_COLON);
            }
            c.expect(TokenType.BRACE_CLOSE);
            if (name == null || assertion == null) {
                throw TokenStreamCursor.throwAt(c.tokens(), elementAnchor,
                        "validation needs name and assertion");
            }
            out.add(new Protocol.PDqRelationCheck(name, description,
                    assertion));
            if (!c.match(TokenType.COMMA)) {
                break;
            }
        }
        c.expect(TokenType.BRACKET_CLOSE);
    }

    private static Protocol.PDataQualityRelationComparison
            parseRelationComparison(TokenStreamCursor c) {
        SectionParse.Head h = SectionParse.head(c,
                "DataQualityRelationComparison");
        c.expect(TokenType.BRACE_OPEN);
        com.legend.protocol.spec.ValueSpecification source = null;
        com.legend.protocol.spec.ValueSpecification target = null;
        List<String> keys = new ArrayList<>();
        Protocol.PReconStrategy strategy = null;
        java.util.Set<String> seenKeys3 = new java.util.HashSet<>();
        while (!c.atEnd() && c.peek() != TokenType.BRACE_CLOSE) {
            String key = c.parseIdentifier();
            TokenStreamCursor.once(seenKeys3, key, c);
            c.expect(TokenType.COLON);
            switch (key) {
                case "source" -> source = SectionParse.specToSemicolon(c);
                case "target" -> target = SectionParse.specToSemicolon(c);
                case "keys" -> {
                    c.expect(TokenType.BRACKET_OPEN);
                    while (c.peek() != TokenType.BRACKET_CLOSE) {
                        keys.add(c.parseIdentifier());
                        if (!c.match(TokenType.COMMA)) {
                            break;
                        }
                    }
                    c.expect(TokenType.BRACKET_CLOSE);
                }
                case "strategy" -> strategy = parseReconStrategy(c, key);
                default -> throw c.error(
                        "unknown DataQualityRelationComparison key '"
                                + key + "'");
            }
            c.expect(TokenType.SEMI_COLON);
        }
        c.expect(TokenType.BRACE_CLOSE);
        if (source == null || target == null || strategy == null) {
            throw TokenStreamCursor.throwAt(c.tokens(), h.declStart(),
                    "DataQualityRelationComparison needs source, "
                    + "target and strategy");
        }
        return new Protocol.PDataQualityRelationComparison(h.pkg(), h.name(),
                source, target, keys, strategy,
                c.spanOf(h.declStart(), c.pos() - 1));
    }

    /** {@code MD5Hash ( '{' (sourceHashColumn|targetHashColumn|
     *  aggregatedHash)+ '}' )?} — engine grammar; the EMPTY block is an
     *  ANTLR refusal at the '}' (reprobe TestDataQualityParsing#30). */
    private static Protocol.PReconStrategy parseReconStrategy(
            TokenStreamCursor c, String key) {
        String kind = c.parseIdentifier();
        String sourceHash = null;
        String targetHash = null;
        Boolean aggregated = null;
        if (c.peek() == TokenType.BRACE_OPEN) {
            c.advance();
            if (c.peek() == TokenType.BRACE_CLOSE) {
                throw c.error("Unexpected token '}'");
            }
            java.util.Set<String> seen = new java.util.HashSet<>();
            while (!c.atEnd() && c.peek() != TokenType.BRACE_CLOSE) {
                int kS = c.pos();
                String k = c.parseIdentifier();
                TokenStreamCursor.once(seen, k, c, kS);
                c.expect(TokenType.COLON);
                switch (k) {
                    case "sourceHashColumn" -> sourceHash = c.parseIdentifier();
                    case "targetHashColumn" -> targetHash = c.parseIdentifier();
                    case "aggregatedHash" -> {
                        String b = c.parseIdentifier();
                        if (!"true".equals(b) && !"false".equals(b)) {
                            throw c.error("expected BOOLEAN, got '" + b + "'");
                        }
                        aggregated = Boolean.valueOf(b);
                    }
                    default -> throw TokenStreamCursor.throwAt(c.tokens(), kS,
                            "unknown strategy key '" + k + "'");
                }
                c.expect(TokenType.SEMI_COLON);
            }
            c.expect(TokenType.BRACE_CLOSE);
        }
        return new Protocol.PReconStrategy(kind, sourceHash, targetHash,
                aggregated);
    }
}
