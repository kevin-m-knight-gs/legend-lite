// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.parser;

import com.legend.lexer.TokenStream;
import com.legend.lexer.TokenType;
import com.legend.protocol.Protocol;
import com.legend.protocol.SourceInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * PROTOCOL-path {@code ###Mapping} parse (section-parity leg 4), built
 * family-by-family against ZMappingProbe's oracle shapes. Families without
 * probed wire shapes REFUSE loudly — the harness turns each refusal into a
 * named WALL row on the worklist.
 */
public final class MappingProtocolParser implements TokenStreamCursor {

    private final TokenStream tokens;
    private int pos;

    private MappingProtocolParser(TokenStream tokens, int pos) {
        this.tokens = tokens;
        this.pos = pos;
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

    /** Parse one {@code Mapping qn ( ... )} at {@code tokenIndex}. */
    public static Protocol.PMapping parse(TokenStream ts, int tokenIndex) {
        return parse(ts, tokenIndex, -1);
    }

    /** As {@link #parse(TokenStream, int)} with the SECTION's first
     *  content line — AggregationAware nested-CM spans shift by
     *  {@code memberLine - sectionStartLine + 2} (probe agg-off-A/B). */
    public static Protocol.PMapping parse(TokenStream ts, int tokenIndex,
            int sectionStartLine) {
        MappingProtocolParser p = new MappingProtocolParser(ts, tokenIndex);
        p.sectionStartLine = sectionStartLine;
        return p.parseMapping();
    }

    /** -1 = unknown (AggregationAware members then refuse loudly). */
    private int sectionStartLine = -1;

    /** Lines the CURRENT ~modelOperation block's content shifts UP —
     *  braceLine minus tildeLine (engine anchor quirk). */
    private int aggLambdaShift = 0;

    /** Parse one {@code Data [decorations] qn { <body> }} at
     *  {@code tokenIndex} (probe data-section). */
    public static Protocol.PDataElement parseData(TokenStream ts,
            int tokenIndex) {
        return new MappingProtocolParser(ts, tokenIndex).parseDataElement();
    }

    private Protocol.PDataElement parseDataElement() {
        int declStart = pos;
        expectText("Data");
        Decorations dec = parseDecorations();
        String qn = Protocol.unquotePath(parseQualifiedName());
        int cut = qn.lastIndexOf("::");
        String pkg = cut < 0 ? "" : qn.substring(0, cut);
        String name = cut < 0 ? qn : qn.substring(cut + 2);
        expect(TokenType.BRACE_OPEN);
        Protocol.PDataBody body = storeKeyed()
                ? new Protocol.PDataResolverBody(parseDataResolvers())
                : new Protocol.PDataValueBody(parseEmbeddedValue());
        int close = pos;
        expect(TokenType.BRACE_CLOSE);
        return new Protocol.PDataElement(pkg, name, body, dec.stereotypes(),
                dec.taggedValues(), spanOf(declStart, close));
    }

    /** Which of the two ###Data envelopes this body is: a bare
     *  {@code <Kind> #{...}#} value, or {@code store::S: <value>;} resolver
     *  entries. Structural, not a keyword list — a qualified name followed
     *  by {@code ':'} is a resolver, anything else is a value (probe
     *  store-keyed). */
    private boolean storeKeyed() {
        int p = pos;
        // the lookahead must use the parser's OWN qualified-name rule: store
        // paths routinely open on a lexer KEYWORD ('store::S' — STORE is the
        // connection grammar's token), which a VALID_STRING test would miss
        while (p < tokens.count()
                && (isFqnSegmentToken(tokens.type(p))
                        || tokens.type(p) == TokenType.PATH_SEPARATOR)) {
            p++;
        }
        return p > pos && p < tokens.count()
                && tokens.type(p) == TokenType.COLON;
    }

    /** {@code store::S: <value>; ...} — each resolver's span runs the store
     *  path through the value's close, EXCLUDING the trailing {@code ';'}
     *  (probe store-keyed-multi). */
    private List<Protocol.PDataResolver> parseDataResolvers() {
        List<Protocol.PDataResolver> out = new ArrayList<>();
        while (!atEnd() && peek() != TokenType.BRACE_CLOSE) {
            int pS = pos;
            String store = Protocol.unquotePath(parseQualifiedName());
            SourceInfo storeSpan = spanOf(pS, pos - 1);
            expect(TokenType.COLON);
            Protocol.PEmbeddedDataValue value = parseEmbeddedValue();
            SourceInfo vSpan = embeddedSpan(value);
            expect(TokenType.SEMI_COLON);
            out.add(new Protocol.PDataResolver(
                    new Protocol.PElementRef(store, storeSpan), value,
                    new SourceInfo("", storeSpan.startLine(),
                            storeSpan.startColumn(), vSpan.endLine(),
                            vSpan.endColumn())));
        }
        return out;
    }

    /** ONE embedded-data value grammar — {@code ExternalFormat},
     *  {@code Reference}, {@code Relation}, {@code Relational} and
     *  {@code ModelStore} islands, shared by every site that admits an
     *  EmbeddedData (###Data bodies, resolvers, ModelStore entries). */
    private Protocol.PEmbeddedDataValue parseEmbeddedValue() {
        // 'Relational' is a LEXER KEYWORD, the rest lex as identifiers
        if (peek() == TokenType.RELATIONAL) {
            return parseRelationalCsv();
        }
        String kind = text();
        if ("ExternalFormat".equals(kind)) {
            return parseExternalFormat();
        }
        if ("Reference".equals(kind)) {
            int rTok = pos;
            advance();
            IslandBlock ri = readIsland();
            MappingProtocolParser rp = new MappingProtocolParser(
                    ri.tokens(), 0);
            String dataPath = Protocol.unquotePath(rp.parseQualifiedName());
            SourceInfo si = new SourceInfo("", tokens.startLine(rTok),
                    tokens.startColumn(rTok), ri.endLine(), ri.endColumn());
            return new Protocol.PDataReference(
                    new Protocol.PPointer("DATA", dataPath, si), si);
        }
        if ("Relation".equals(kind)) {
            advance();
            IslandBlock ri = readIsland();
            List<Protocol.PRelationElement> rels =
                    parseRelationElements(ri, true);
            // the accessor span starts at the FIRST element, not the
            // keyword, and its end column overshoots the island close by 3
            // (the engine's relation-data walker offset — probe relation-csv)
            SourceInfo first = rels.isEmpty()
                    ? spanOf(pos - 1, pos - 1)
                    : rels.get(0).sourceInformation();
            return new Protocol.PRelationData(rels,
                    new SourceInfo("", first.startLine(), first.startColumn(),
                            ri.endLine(), ri.endColumn() + 3));
        }
        if ("ModelStore".equals(kind)) {
            int msTok = pos;
            advance();
            IslandBlock island = readIsland();
            List<Protocol.PModelData> modelData = new ArrayList<>();
            MappingProtocolParser inner = new MappingProtocolParser(
                    island.tokens(), 0);
            while (!inner.atEnd()) {
                modelData.add(inner.parseModelEntry(island));
                inner.match(TokenType.COMMA);
            }
            return new Protocol.PModelStoreData(modelData,
                    new SourceInfo("", tokens.startLine(msTok),
                            tokens.startColumn(msTok), island.endLine(),
                            island.endColumn()));
        }
        throw error("embedded data kind '" + safeText() + "' is unbuilt");
    }

    /** {@code Relational #{ schema.table: 'csv' + 'csv'; ... }#} — the path
     *  is exactly TWO segments and the value is the escape-decoded
     *  concatenation of its literals; each table's span runs the schema
     *  token through the terminating {@code ';'} (probe relational-csv). */
    private Protocol.PRelationalCsvData parseRelationalCsv() {
        int kwTok = pos;
        expect(TokenType.RELATIONAL);
        IslandBlock island = readIsland();
        List<Protocol.PRelationalCsvTable> tables = new ArrayList<>();
        MappingProtocolParser in = new MappingProtocolParser(
                island.tokens(), 0);
        while (!in.atEnd()) {
            int tS = in.pos;
            String schema = Protocol.unquotePath(in.parseIdentifier());
            in.expect(TokenType.DOT);
            String table = Protocol.unquotePath(in.parseIdentifier());
            in.expect(TokenType.COLON);
            StringBuilder values = new StringBuilder();
            while (true) {
                String quoted = in.text();
                in.expect(TokenType.STRING);
                values.append(TokenStreamCursor.unquoteAndUnescape(quoted, in));
                if (!in.match(TokenType.PLUS)) {
                    break;
                }
            }
            int semi = in.pos;
            in.expect(TokenType.SEMI_COLON);
            tables.add(new Protocol.PRelationalCsvTable(schema, table,
                    values.toString(), in.spanOf(tS, semi)));
        }
        return new Protocol.PRelationalCsvData(tables,
                new SourceInfo("", tokens.startLine(kwTok),
                        tokens.startColumn(kwTok), island.endLine(),
                        island.endColumn()));
    }

    /** The span an embedded value contributes to its enclosing entry. */
    private static SourceInfo embeddedSpan(Protocol.PEmbeddedDataValue v) {
        return switch (v) {
            case Protocol.PExternalFormatData e -> e.sourceInformation();
            case Protocol.PDataReference r -> r.sourceInformation();
            case Protocol.PModelStoreData m -> m.sourceInformation();
            case Protocol.PRelationalCsvData c -> c.sourceInformation();
            // the accessor's own end overshoots by the walker offset; a
            // resolver anchors on the RAW island close (probe store-keyed)
            case Protocol.PRelationData d -> new SourceInfo("",
                    d.sourceInformation().startLine(),
                    d.sourceInformation().startColumn(),
                    d.sourceInformation().endLine(),
                    d.sourceInformation().endColumn() - 3);
        };
    }

    private Protocol.PMapping parseMapping() {
        int declStart = pos;
        expect(TokenType.MAPPING);
        String qn = Protocol.unquotePath(parseQualifiedName());
        int cut = qn.lastIndexOf("::");
        String pkg = cut < 0 ? "" : qn.substring(0, cut);
        String name = cut < 0 ? qn : qn.substring(cut + 2);
        expect(TokenType.PAREN_OPEN);
        List<Protocol.PAssociationMapping> assocMappings = new ArrayList<>();
        List<Protocol.PClassMapping> classMappings = new ArrayList<>();
        List<Protocol.PEnumerationMapping> enums = new ArrayList<>();
        List<Protocol.PMappingInclude> includes = new ArrayList<>();
        List<Protocol.PMappingTestSuite> suites = new ArrayList<>();
        List<Protocol.PLegacyMappingTest> legacyTests = new ArrayList<>();
        while (!atEnd() && peek() != TokenType.PAREN_CLOSE) {
            if (peek() == TokenType.VALID_STRING
                    && "MappingTests".equals(text())) {
                advance();
                expect(TokenType.BRACKET_OPEN);
                while (!atEnd() && peek() != TokenType.BRACKET_CLOSE) {
                    legacyTests.add(parseLegacyTest(qn));
                    match(TokenType.COMMA);
                }
                expect(TokenType.BRACKET_CLOSE);
                continue;
            }
            if (peek() == TokenType.MAPPING_TESTABLE_SUITES) {
                advance();
                expect(TokenType.COLON);
                expect(TokenType.BRACKET_OPEN);
                while (!atEnd() && peek() != TokenType.BRACKET_CLOSE) {
                    suites.add(parseTestSuite(qn));
                    match(TokenType.COMMA);
                }
                expect(TokenType.BRACKET_CLOSE);
                continue;
            }
            parseMember(assocMappings, classMappings, enums, includes);
        }
        expect(TokenType.PAREN_CLOSE);
        return new Protocol.PMapping(pkg, name, assocMappings, classMappings,
                enums, includes, suites, legacyTests,
                spanOf(declStart, pos - 1));
    }

    private void parseMember(List<Protocol.PAssociationMapping> assocMappings,
            List<Protocol.PClassMapping> classMappings,
            List<Protocol.PEnumerationMapping> enums,
            List<Protocol.PMappingInclude> includes) {
        if (peek() == TokenType.INCLUDE) {
            int s = pos;
            advance();
            if (peek() == TokenType.SERVICE_MAPPING
                    && tokens.type(Math.min(pos + 1, tokens.count() - 1))
                            != TokenType.PATH_SEPARATOR) {
                advance();                          // 'include mapping qn'
            } else if (peek() == TokenType.VALID_STRING
                    && "dataspace".equals(text())) {
                throw error("include dataspace is unbuilt");
            }
            String inc = Protocol.unquotePath(parseQualifiedName());
            String srcDb = null;
            String tgtDb = null;
            List<Protocol.PStoreSubstitution> subs = new ArrayList<>();
            if (peek() == TokenType.BRACKET_OPEN) {
                // include m[srcDb->tgtDb, ...] — MULTIPLE substitutions
                // emit NEITHER key (probe include-multi-subst)
                advance();
                while (peek() != TokenType.BRACKET_CLOSE && !atEnd()) {
                    srcDb = Protocol.unquotePath(parseQualifiedName());
                    expect(TokenType.ARROW);
                    tgtDb = Protocol.unquotePath(parseQualifiedName());
                    match(TokenType.COMMA);
                    subs.add(new Protocol.PStoreSubstitution(srcDb, tgtDb));
                }
                expect(TokenType.BRACKET_CLOSE);
                if (subs.size() > 1) {
                    // engine records NEITHER path when there is more than one
                    // pair (CorePureGrammarParser:504-516). The emitted keys
                    // follow it exactly; `subs` keeps what engine throws away
                    // so the MODEL does not lose the substitution.
                    srcDb = null;
                    tgtDb = null;
                }
            }
            includes.add(new Protocol.PMappingInclude(inc, srcDb, tgtDb, subs,
                    spanOf(s, pos - 1)));
            return;
        }
        int memberStart = pos;
        boolean root = match(TokenType.STAR);       // root marker
        int targetStart = pos;
        String target = Protocol.unquotePath(parseQualifiedName());
        SourceInfo targetSpan = spanOf(targetStart, pos - 1);
        String id = null;
        if (peek() == TokenType.BRACKET_OPEN) {
            advance();                              // [setId]
            id = parseSetId();
            expect(TokenType.BRACKET_CLOSE);
        }
        String extendsId = null;
        if (peek() == TokenType.EXTENDS) {
            advance();                              // extends [otherId]
            expect(TokenType.BRACKET_OPEN);
            extendsId = parseIdentifier();
            expect(TokenType.BRACKET_CLOSE);
        }
        expect(TokenType.COLON);
        String kind = safeText();
        if (peek() == TokenType.ENUMERATION_MAPPING) {
            advance();
            enums.add(parseEnumerationMapping(target, targetStart, targetSpan));
            return;
        }
        if (peek() == TokenType.RELATIONAL) {
            advance();
            if (peekIsAssociationBody()) {
                assocMappings.add(parseRelAssociationMapping(target,
                        memberStart, targetSpan, id));
                return;
            }
            classMappings.add(parseRelationalClassMapping(target, memberStart,
                    targetSpan, id, root, extendsId));
            return;
        }
        if (peek() == TokenType.VALID_STRING && "XStore".equals(text())) {
            advance();
            assocMappings.add(parseXStoreAssociationMapping(target,
                    memberStart, targetSpan));
            return;
        }
        if (peek() == TokenType.PURE_MAPPING) {
            advance();
            classMappings.add(parsePureClassMapping(target, memberStart,
                    targetSpan, id, root, extendsId));
            return;
        }
        if (peek() == TokenType.VALID_STRING
                && "AggregationAware".equals(text())) {
            advance();
            classMappings.add(parseAggregationAware(target, memberStart,
                    targetSpan, id, root));
            return;
        }
        if (peek() == TokenType.VALID_STRING && "ModelJoin".equals(text())) {
            advance();
            assocMappings.add(parseModelJoin(target, memberStart, targetSpan));
            return;
        }
        if (peek() == TokenType.VALID_STRING && "Relation".equals(text())) {
            advance();
            classMappings.add(parseRelationClassMapping(target, memberStart,
                    targetSpan, id, root));
            return;
        }
        if (peek() == TokenType.VALID_STRING && "Operation".equals(text())) {
            advance();
            // the engine PARSES but DROPS extends on Operation mappings
            // (OperationClassMappingParseTreeWalker TODO)
            classMappings.add(parseOperationClassMapping(target, memberStart,
                    targetSpan, id, root));
            return;
        }
        if (extendsId != null) {
            throw error("'extends' on this class-mapping kind is unbuilt");
        }
        throw error("mapping member kind '" + kind + "' is unbuilt");
    }

    /** {@code [*]cls: Relational { ~mainTable [db]T ~primaryKey(...)
     *  prop: <op>, ... }} — spans and shapes per probe
     *  relational-class-mapping; unsupported directives wall by name. */
    private Protocol.PClassMappingRel parseRelationalClassMapping(
            String target, int memberStart, SourceInfo targetSpan,
            @com.legend.Nullable String id, boolean root,
            @com.legend.Nullable String extendsId) {
        expect(TokenType.BRACE_OPEN);
        Protocol.PFilterMapping filter = null;
        Protocol.PTablePtr mainTable = null;
        List<Protocol.PRelOp> primaryKey = new ArrayList<>();
        List<Protocol.PRelOp> groupBy = new ArrayList<>();
        boolean distinct = false;
        List<Protocol.PPropertyMapping> props = new ArrayList<>();
        while (!atEnd() && peek() != TokenType.BRACE_CLOSE) {
            if (peek() == TokenType.MAIN_TABLE_CMD
                    || (peek() == TokenType.TILDE && "mainTable".equals(
                            tokens.text(Math.min(pos + 1, tokens.count() - 1))))) {
                if (peek() == TokenType.TILDE) {
                    advance();
                }
                advance();                          // mainTable
                expect(TokenType.BRACKET_OPEN);
                String db = Protocol.unquotePath(parseQualifiedName());
                expect(TokenType.BRACKET_CLOSE);
                int tS = pos;                       // FIRST ident anchors
                String schema = "default";
                String tbl = parseMaybeQuotedIdentifier();
                if (peek() == TokenType.DOT) {
                    advance();
                    schema = tbl;
                    tbl = parseMaybeQuotedIdentifier();
                }
                mainTable = new Protocol.PTablePtr(db, db, schema, tbl,
                        spanOf(tS, pos - 1));
                continue;
            }
            if (peek() == TokenType.PRIMARY_KEY_CMD) {
                advance();
                expect(TokenType.PAREN_OPEN);
                while (peek() != TokenType.PAREN_CLOSE && !atEnd()) {
                    primaryKey.add(parseEmbeddedOperation());
                    match(TokenType.COMMA);
                }
                expect(TokenType.PAREN_CLOSE);
                continue;
            }
            if (peek() == TokenType.DISTINCT_CMD) {
                advance();
                distinct = true;
                continue;
            }
            if (peek() == TokenType.GROUP_BY_CMD) {
                advance();
                expect(TokenType.PAREN_OPEN);
                while (peek() != TokenType.PAREN_CLOSE && !atEnd()) {
                    groupBy.add(parseEmbeddedOperation());
                    match(TokenType.COMMA);
                }
                expect(TokenType.PAREN_CLOSE);
                continue;
            }
            if (peek() == TokenType.FILTER_CMD) {
                filter = parseFilterMapping();
                continue;
            }
            if (peek() == TokenType.TILDE) {
                throw error("class-mapping directive '" + safeText()
                        + "' is unbuilt");
            }
            if (peek() == TokenType.VALID_STRING && "scope".equals(text())
                    && tokens.type(Math.min(pos + 1, tokens.count() - 1))
                            == TokenType.PAREN_OPEN) {
                parseScopeBlock(props, target, id);
                match(TokenType.COMMA);
                continue;
            }
            // property line: prop: <operation>
            parsePropertyLine(props, target, id, null, null);
        }
        int close = pos;
        expect(TokenType.BRACE_CLOSE);
        return new Protocol.PClassMappingRel(target, targetSpan, id, root,
                distinct, extendsId, filter, groupBy, mainTable, primaryKey,
                props,
                spanOf(memberStart, close));
    }

    /** {@code cls: Pure { ~src my::S  prop: <pure expr>, ... }} — the
     *  transforms parse through SpecParser (the ###Pure machinery) with
     *  absolute spans via token slices (probe pure-m2m). */
    private Protocol.PClassMappingPure parsePureClassMapping(String target,
            int memberStart, SourceInfo targetSpan,
            @com.legend.Nullable String id, boolean root,
            @com.legend.Nullable String extendsId) {
        expect(TokenType.BRACE_OPEN);
        String srcClass = null;
        SourceInfo srcSpan = null;
        List<com.legend.protocol.spec.ValueSpecification> filterBody = null;
        List<Protocol.PPurePropertyMapping> props = new ArrayList<>();
        while (!atEnd() && peek() != TokenType.BRACE_CLOSE) {
            if (peek() == TokenType.SRC_CMD) {
                advance();                          // '~src' is ONE token
                int sS = pos;
                srcClass = Protocol.unquotePath(parseQualifiedName());
                srcSpan = spanOf(sS, pos - 1);
                continue;
            }
            if (peek() == TokenType.FILTER_CMD) {
                advance();
                int fStart = pos;
                int depth = 0;
                while (!atEnd()) {
                    TokenType t = peek();
                    if (depth == 0) {
                        if (t == TokenType.BRACE_CLOSE
                                || t == TokenType.SRC_CMD
                                || t == TokenType.FILTER_CMD
                                || t == TokenType.PLUS) {
                            break;
                        }
                        if ((t == TokenType.VALID_STRING
                                || t == TokenType.STRING)
                                && pos + 1 < tokens.count()
                                && tokens.type(pos + 1) == TokenType.COLON) {
                            break;                  // next property line
                        }
                    }
                    if (t == TokenType.PAREN_OPEN
                            || t == TokenType.BRACKET_OPEN
                            || t == TokenType.BRACE_OPEN) {
                        depth++;
                    } else if (t == TokenType.PAREN_CLOSE
                            || t == TokenType.BRACKET_CLOSE
                            || t == TokenType.BRACE_CLOSE) {
                        depth--;
                    }
                    advance();
                }
                filterBody = SpecParser.parseCodeBlock(
                        tokens.slice(fStart, pos));
                continue;
            }
            if (peek() == TokenType.TILDE) {
                throw error("pure class-mapping directive '" + safeText()
                        + "' is unbuilt");
            }
            int pS = pos;
            boolean local = match(TokenType.PLUS);  // span INCLUDES the '+'
            int identTok = pos;
            String prop = parseIdentifier();
            SourceInfo propSpan = spanOf(identTok, pos - 1);
            boolean explode = match(TokenType.STAR);
            String srcId = id != null ? id : "";
            String tgtId = null;
            if (peek() == TokenType.BRACKET_OPEN) {
                advance();
                String first = parseSetId();
                if (peek() == TokenType.COMMA) {
                    advance();
                    srcId = first;
                    tgtId = parseSetId();
                } else {
                    tgtId = first;              // ONE id is the TARGET
                }
                expect(TokenType.BRACKET_CLOSE);
            }
            Protocol.PLocalProp localProp = null;
            expect(TokenType.COLON);
            String enumId = null;
            if (peek() == TokenType.ENUMERATION_MAPPING) {
                advance();                  // EnumerationMapping em: <expr>
                enumId = parseIdentifier();
                expect(TokenType.COLON);
            }
            if (local) {
                // +prop: Type[m]: <expr> — the local prop's span is the
                // IDENT (probe pure-decorations, unlike relational)
                String type = Protocol.unquotePath(parseQualifiedName());
                expect(TokenType.BRACKET_OPEN);
                long lower;
                Long upper = null;
                if (peek() == TokenType.STAR) {
                    advance();
                    lower = 0L;
                } else {
                    lower = Long.parseLong(text());
                    expect(TokenType.INTEGER);
                    upper = lower;
                }
                if (peek() == TokenType.DOT_DOT) {
                    advance();
                    if (peek() == TokenType.STAR) {
                        advance();
                        upper = null;
                    } else {
                        upper = Long.parseLong(text());
                        expect(TokenType.INTEGER);
                    }
                }
                expect(TokenType.BRACKET_CLOSE);
                localProp = new Protocol.PLocalProp(type, lower, upper,
                        propSpan);
                expect(TokenType.COLON);
            }
            int exprStart = pos;
            int depth = 0;
            while (!atEnd()) {
                TokenType t = peek();
                if (depth == 0 && (t == TokenType.COMMA
                        || t == TokenType.BRACE_CLOSE)) {
                    break;
                }
                if (t == TokenType.PAREN_OPEN || t == TokenType.BRACKET_OPEN
                        || t == TokenType.BRACE_OPEN) {
                    depth++;
                } else if (t == TokenType.PAREN_CLOSE
                        || t == TokenType.BRACKET_CLOSE
                        || t == TokenType.BRACE_CLOSE) {
                    depth--;
                }
                advance();
            }
            List<com.legend.protocol.spec.ValueSpecification> body =
                    SpecParser.parseCodeBlock(tokens.slice(exprStart, pos));
            props.add(new Protocol.PPurePropertyMapping(
                    local ? null : target, prop, propSpan, enumId, explode,
                    localProp, body, srcId, tgtId, spanOf(pS, pos - 1)));
            match(TokenType.COMMA);
        }
        int close = pos;
        expect(TokenType.BRACE_CLOSE);
        return new Protocol.PClassMappingPure(target, targetSpan, extendsId,
                id, root, srcClass, srcSpan, filterBody, props,
                spanOf(memberStart, close));
    }

    /** Whether the {@code Relational} body opens with the
     *  {@code AssociationMapping} keyword (association form). */
    private boolean peekIsAssociationBody() {
        return peek() == TokenType.BRACE_OPEN
                && tokens.count() > pos + 1
                && tokens.type(pos + 1) == TokenType.ASSOCIATION_MAPPING;
    }

    /** {@code assoc: Relational { AssociationMapping ( side: [db]@J, ... ) }}
     *  — sides are join-only navs; stores = the [db] pointers in order of
     *  first appearance (probe include-and-assoc). */
    private Protocol.PRelAssociationMapping parseRelAssociationMapping(
            String target, int memberStart, SourceInfo targetSpan,
            @com.legend.Nullable String id) {
        expect(TokenType.BRACE_OPEN);
        expect(TokenType.ASSOCIATION_MAPPING);
        expect(TokenType.PAREN_OPEN);
        List<Protocol.PRelAssocPropertyMapping> props = new ArrayList<>();
        List<String> stores = new ArrayList<>();
        while (!atEnd() && peek() != TokenType.PAREN_CLOSE) {
            int pS = pos;
            String prop = parseIdentifier();
            SourceInfo propSpan = spanOf(pS, pos - 1);
            String srcId = null;
            String tgtId = null;
            if (peek() == TokenType.BRACKET_OPEN) {
                // side[srcSet, tgtSet] (probe assoc-set-ids)
                advance();
                String first = parseSetId();
                if (peek() == TokenType.COMMA) {
                    advance();
                    srcId = first;
                    tgtId = parseSetId();
                } else {
                    tgtId = first;              // TARGET; assoc source null
                }
                expect(TokenType.BRACKET_CLOSE);
            }
            expect(TokenType.COLON);
            int oS = pos;
            Protocol.PRelOp op = parseEmbeddedOperation();
            if (op instanceof Protocol.PElemtWithJoins ej) {
                for (Protocol.PJoinPtr jp : ej.joins()) {
                    String jdb = jp.db();
                    if (jdb != null && !stores.contains(jdb)) {
                        stores.add(jdb);
                    }
                }
            }
            props.add(new Protocol.PRelAssocPropertyMapping(prop, propSpan,
                    op, srcId, tgtId, spanOf(oS - 1, pos - 1)));
            match(TokenType.COMMA);
        }
        expect(TokenType.PAREN_CLOSE);
        int close = pos;
        expect(TokenType.BRACE_CLOSE);
        return new Protocol.PRelAssociationMapping(
                new Protocol.PPointer("ASSOCIATION", target, targetSpan), id,
                props, stores, spanOf(memberStart, close));
    }

    /** {@code assoc: XStore { side: <cross expr>, ... }} — cross
     *  expressions are Pure lambdas via SpecParser (probe xstore). */
    private Protocol.PXStoreAssociationMapping parseXStoreAssociationMapping(
            String target, int memberStart, SourceInfo targetSpan) {
        expect(TokenType.BRACE_OPEN);
        List<Protocol.PXStorePropertyMapping> props = new ArrayList<>();
        while (!atEnd() && peek() != TokenType.BRACE_CLOSE) {
            int pS = pos;
            String prop = parseIdentifier();
            SourceInfo propSpan = spanOf(pS, pos - 1);
            String srcId = "";
            String tgtId = "";
            if (peek() == TokenType.BRACKET_OPEN) {
                // side[srcSet, tgtSet] (probe xstore-ids)
                advance();
                String first = parseSetId();
                if (peek() == TokenType.COMMA) {
                    advance();
                    srcId = first;
                    tgtId = parseSetId();
                } else {
                    tgtId = first;
                }
                expect(TokenType.BRACKET_CLOSE);
            }
            expect(TokenType.COLON);
            int exprStart = pos;
            int depth = 0;
            while (!atEnd()) {
                TokenType t = peek();
                if (depth == 0 && (t == TokenType.COMMA
                        || t == TokenType.BRACE_CLOSE)) {
                    break;
                }
                if (t == TokenType.PAREN_OPEN || t == TokenType.BRACKET_OPEN
                        || t == TokenType.BRACE_OPEN) {
                    depth++;
                } else if (t == TokenType.PAREN_CLOSE
                        || t == TokenType.BRACKET_CLOSE
                        || t == TokenType.BRACE_CLOSE) {
                    depth--;
                }
                advance();
            }
            List<com.legend.protocol.spec.ValueSpecification> body =
                    SpecParser.parseCodeBlock(tokens.slice(exprStart, pos));
            props.add(new Protocol.PXStorePropertyMapping(target, prop,
                    propSpan, body, srcId, tgtId, spanOf(pS, pos - 1)));
            match(TokenType.COMMA);
        }
        int close = pos;
        expect(TokenType.BRACE_CLOSE);
        return new Protocol.PXStoreAssociationMapping(
                new Protocol.PPointer("ASSOCIATION", target, targetSpan),
                props, spanOf(memberStart, close));
    }

    /** {@code cls[id]: Operation { fqn(p1, p2) }} — the called FQN maps to
     *  the operation discriminator by EXACT FQN; identification is never
     *  by suffix (probe operation; STORE_UNION vs ROUTER_UNION semantics
     *  differ, a contains-match would run the wrong one). */
    private Protocol.PClassMapping parseOperationClassMapping(
            String target, int memberStart, SourceInfo targetSpan,
            @com.legend.Nullable String id, boolean root) {
        expect(TokenType.BRACE_OPEN);
        int fqnTok = pos;
        String fqn = Protocol.unquotePath(parseQualifiedName());
        String operation = switch (fqn) {
            case "meta::pure::router::operations::union_OperationSetImplementation_1__SetImplementation_MANY_" ->
                    "STORE_UNION";
            case "meta::pure::router::operations::special_union_OperationSetImplementation_1__SetImplementation_MANY_" ->
                    "ROUTER_UNION";
            case "meta::pure::router::operations::inheritance_OperationSetImplementation_1__SetImplementation_MANY_" ->
                    "INHERITANCE";
            case "merge_OperationSetImplementation_1__SetImplementation_MANY_",
                 "meta::pure::router::operations::merge_OperationSetImplementation_1__SetImplementation_MANY_" ->
                    null;   // MERGE emits NO discriminator (probe merge-op)
            // UNKNOWN functions also emit NO discriminator (probe
            // custom-op-fn: a__SetImplementation_MANY_())
            default -> null;
        };
        expect(TokenType.PAREN_OPEN);
        List<String> params = new ArrayList<>();
        if (peek() == TokenType.BRACKET_OPEN) {
            // merge_...([p1,p2], {typed lambda}) — _type mergeOperation
            // WITH MERGE + validationFunction (probe merge-params-lambda)
            advance();
            while (peek() != TokenType.BRACKET_CLOSE && !atEnd()) {
                params.add(parseSetId());
                match(TokenType.COMMA);
            }
            expect(TokenType.BRACKET_CLOSE);
            expect(TokenType.COMMA);
            int lS = pos;
            expect(TokenType.BRACE_OPEN);
            int depth = 0;
            while (!atEnd()) {
                TokenType t = peek();
                if (t == TokenType.BRACE_OPEN) {
                    depth++;
                } else if (t == TokenType.BRACE_CLOSE) {
                    if (depth == 0) {
                        break;
                    }
                    depth--;
                }
                advance();
            }
            int lEnd = pos;
            expect(TokenType.BRACE_CLOSE);
            // the engine reparses ANTLR getText() — the lambda's TOKEN
            // TEXTS concatenated with NO whitespace — anchored at the
            // merge FQN token's line:column
            // (OperationClassMappingParseTreeWalker:86-93); emulate with
            // a padded re-lex of the same concatenation
            StringBuilder padded = new StringBuilder();
            for (int i = 1; i < tokens.startLine(fqnTok); i++) {
                padded.append('\n');
            }
            for (int i = 1; i < tokens.startColumn(fqnTok); i++) {
                padded.append(' ');
            }
            for (int i = lS; i <= lEnd; i++) {
                padded.append(tokens.text(i));
            }
            List<com.legend.protocol.spec.ValueSpecification> body =
                    SpecParser.parseCodeBlock(
                            com.legend.lexer.Lexer.tokenize(
                                    padded.toString()));
            if (body.size() != 1) {
                throw error("merge validation must be ONE lambda");
            }
            expect(TokenType.PAREN_CLOSE);
            match(TokenType.SEMI_COLON);
            int close = pos;
            expect(TokenType.BRACE_CLOSE);
            return new Protocol.PClassMappingMergeOperation(target,
                    targetSpan, id, root, params, body.get(0),
                    spanOf(memberStart, close));
        }
        while (peek() != TokenType.PAREN_CLOSE && !atEnd()) {
            params.add(parseSetId());
            match(TokenType.COMMA);
        }
        expect(TokenType.PAREN_CLOSE);
        match(TokenType.SEMI_COLON);                // trailing ';' legal
        int close = pos;
        expect(TokenType.BRACE_CLOSE);
        return new Protocol.PClassMappingOperation(target, targetSpan, id,
                root, operation, params, spanOf(memberStart, close));
    }

    /** A set-implementation id — bare NUMBERS are legal ids (probe
     *  numeric-id: {@code *test::A[1]} emits id "1"). */
    private String parseSetId() {
        if (peek() == TokenType.INTEGER) {
            String t = text();
            advance();
            return t;
        }
        return parseIdentifier();
    }

    /** {@code ~filter [db] NAME} or {@code ~filter [db]@J ... | [db2]NAME}
     *  — span '~' through the name (probe rel-filter/-joined). */
    private Protocol.PFilterMapping parseFilterMapping() {
        int fS = pos;
        advance();                                  // '~filter'
        expect(TokenType.BRACKET_OPEN);
        String db = Protocol.unquotePath(parseQualifiedName());
        expect(TokenType.BRACKET_CLOSE);
        List<Protocol.PJoinPtr> joins = new ArrayList<>();
        String fdb = db;
        String name;
        String firstType = null;
        if (peek() == TokenType.PAREN_OPEN) {
            // ~filter [db] (INNER)@J | ... (probe filter-jointype)
            advance();
            firstType = parseIdentifier();
            expect(TokenType.PAREN_CLOSE);
        }
        if (peek() == TokenType.AT) {
            String curDb = db;
            String pendingType = firstType;
            while (peek() == TokenType.AT) {
                int jS = pos;                       // span INCLUDES the '@'
                advance();
                joins.add(new Protocol.PJoinPtr(curDb, pendingType,
                        parseIdentifier(), spanOf(jS, pos - 1)));
                pendingType = null;
                if (peek() != TokenType.GREATER_THAN) {
                    break;
                }
                advance();
                if (peek() == TokenType.PAREN_OPEN) {
                    advance();
                    pendingType = parseIdentifier();
                    expect(TokenType.PAREN_CLOSE);
                }
                if (peek() == TokenType.BRACKET_OPEN) {
                    advance();
                    curDb = Protocol.unquotePath(parseQualifiedName());
                    expect(TokenType.BRACKET_CLOSE);
                }
            }
            expect(TokenType.PIPE);
            expect(TokenType.BRACKET_OPEN);
            fdb = Protocol.unquotePath(parseQualifiedName());
            expect(TokenType.BRACKET_CLOSE);
            name = parseIdentifier();
        } else {
            name = parseIdentifier();
        }
        return new Protocol.PFilterMapping(fdb, name, joins,
                spanOf(fS, pos - 1));
    }

    /** One {@code prop[srcId,tgtId]: [EnumerationMapping em:] <op>} line.
     *  ONE bracket id is the TARGET set (source stays the enclosing id);
     *  TWO are source,target (probe prop-set-ids). An inline
     *  {@code EnumerationMapping em:} rides enumMappingId (probe
     *  inline-enum-transform). */
    private void parsePropertyLine(List<Protocol.PPropertyMapping> props,
            @com.legend.Nullable String target, @com.legend.Nullable String id,
            @com.legend.Nullable String scopeDb,
            DatabaseProtocolParser.@com.legend.Nullable ScopeCtx scope) {
        int pS = pos;
        Protocol.PLocalProp localProp = null;
        boolean local = match(TokenType.PLUS);
        if (local) {
            pS = pos;                               // ident anchors the span
        }
        String prop = parseIdentifier();
        SourceInfo propSpan = spanOf(pS, pos - 1);
        String srcId = id;
        String tgtId = null;
        String embId = null;
        if (peek() == TokenType.BRACKET_OPEN) {
            advance();
            String first = parseSetId();
            if (peek() == TokenType.COMMA) {
                advance();
                srcId = first;
                tgtId = parseSetId();
            } else {
                // ONE id is the TARGET; source stays the ENCLOSING id
                // (engine RelationalParseTreeWalker:1211-1216)
                tgtId = first;
                embId = first;
            }
            expect(TokenType.BRACKET_CLOSE);
        }
        if (peek() == TokenType.PAREN_OPEN) {
            // prop[k] ( lines... ) — embedded block; the span is the
            // paren region; inner lines drop the class key and keep the
            // ENCLOSING set id as source (probe embedded-plain)
            int parenTok = pos;
            advance();
            if (peek() == TokenType.PAREN_CLOSE
                    && "Inline".equals(tokens.text(
                            Math.min(pos + 1, tokens.count() - 1)))) {
                // prop() Inline[setId] — span '('..']' (probe
                // inline-embedded)
                advance();
                advance();                          // Inline
                expect(TokenType.BRACKET_OPEN);
                String setId = parseIdentifier();
                int close = pos;
                expect(TokenType.BRACKET_CLOSE);
                props.add(new Protocol.PInlineEmbeddedPropertyMapping(target,
                        prop, propSpan, embId, setId,
                        spanOf(parenTok, close)));
                match(TokenType.COMMA);
                return;
            }
            List<Protocol.PPropertyMapping> inner = new ArrayList<>();
            List<Protocol.PRelOp> pk = new ArrayList<>();
            while (!atEnd() && peek() != TokenType.PAREN_CLOSE) {
                if (peek() == TokenType.PRIMARY_KEY_CMD) {
                    advance();
                    expect(TokenType.PAREN_OPEN);
                    while (peek() != TokenType.PAREN_CLOSE && !atEnd()) {
                        pk.add(parseOpInCtx(scopeDb, scope));
                        match(TokenType.COMMA);
                    }
                    expect(TokenType.PAREN_CLOSE);
                    continue;
                }
                parsePropertyLine(inner, null, id, scopeDb, scope);
            }
            int close = pos;
            expect(TokenType.PAREN_CLOSE);
            if (peek() == TokenType.OTHERWISE) {
                // ) Otherwise ( [tgt]:<op> ) — the embedded class
                // mapping's span runs paren..OTHERWISE-close but the
                // OUTER pm's span runs OTHERWISE..close; the op span
                // stretches back to the '[' (probe otherwise-embedded)
                int otherwiseTok = pos;
                advance();
                expect(TokenType.PAREN_OPEN);
                int oS = pos;
                expect(TokenType.BRACKET_OPEN);
                String tgt = parseSetId();
                expect(TokenType.BRACKET_CLOSE);
                expect(TokenType.COLON);
                Protocol.PRelOp op = parseOpInCtx(scopeDb, scope);
                op = withSpanStart(op, oS);
                int oClose = pos;
                expect(TokenType.PAREN_CLOSE);
                props.add(new Protocol.POtherwiseEmbeddedPropertyMapping(
                        target, prop, propSpan, embId, pk, inner, op, tgt,
                        spanOf(parenTok, oClose),
                        spanOf(otherwiseTok, oClose)));
                match(TokenType.COMMA);
                return;
            }
            props.add(new Protocol.PEmbeddedPropertyMapping(target, prop,
                    propSpan, embId, pk, inner, spanOf(parenTok, close)));
            match(TokenType.COMMA);
            return;
        }
        int colonTok = pos;
        expect(TokenType.COLON);
        if (local) {
            // +prop: Type[m]: <op> — span FIRST colon..bracket close
            String type = Protocol.unquotePath(parseQualifiedName());
            expect(TokenType.BRACKET_OPEN);
            long lower;
            Long upper = null;
            if (peek() == TokenType.STAR) {
                advance();
                lower = 0L;
            } else {
                lower = Long.parseLong(text());
                expect(TokenType.INTEGER);
                upper = lower;
            }
            if (peek() == TokenType.DOT_DOT) {
                advance();
                if (peek() == TokenType.STAR) {
                    advance();
                    upper = null;
                } else {
                    upper = Long.parseLong(text());
                    expect(TokenType.INTEGER);
                }
            }
            int mClose = pos;
            expect(TokenType.BRACKET_CLOSE);
            localProp = new Protocol.PLocalProp(type, lower, upper,
                    spanOf(colonTok, mClose));
            colonTok = pos;                         // PM span = SECOND colon
            expect(TokenType.COLON);
        }
        String enumId = null;
        if (peek() == TokenType.ENUMERATION_MAPPING) {
            advance();                              // EnumerationMapping em:
            enumId = parseIdentifier();
            expect(TokenType.COLON);
        }
        Protocol.PRelOp op = parseOpInCtx(scopeDb, scope);
        props.add(new Protocol.PRelPropertyMapping(
                localProp != null ? null : target, prop, propSpan, enumId,
                localProp, op, srcId, localProp != null ? null : tgtId,
                spanOf(colonTok, pos - 1)));
        match(TokenType.COMMA);
    }

    /** {@code scope([db]seg(.seg)?) ( prop-lines )} — the engine FLATTENS
     *  scope at parse: inner lines emit as plain property mappings whose
     *  table (when the header has one) spans the HEADER tokens (probe
     *  scope-forms). */
    private void parseScopeBlock(List<Protocol.PPropertyMapping> props,
            @com.legend.Nullable String target,
            @com.legend.Nullable String id) {
        advance();                                  // 'scope'
        expect(TokenType.PAREN_OPEN);
        expect(TokenType.BRACKET_OPEN);
        String db = Protocol.unquotePath(parseQualifiedName());
        expect(TokenType.BRACKET_CLOSE);
        DatabaseProtocolParser.ScopeCtx scope = null;
        if (peek() != TokenType.PAREN_CLOSE) {
            int g1 = pos;
            String seg1 = parseIdentifier();
            if (peek() == TokenType.DOT) {
                advance();
                parseIdentifier();
                scope = new DatabaseProtocolParser.ScopeCtx(db, seg1,
                        tokens.text(pos - 1), spanOf(g1, pos - 1), false);
            } else {
                scope = new DatabaseProtocolParser.ScopeCtx(db, "default",
                        seg1, spanOf(g1, g1), true);
            }
        }
        expect(TokenType.PAREN_CLOSE);
        expect(TokenType.PAREN_OPEN);
        while (!atEnd() && peek() != TokenType.PAREN_CLOSE) {
            parsePropertyLine(props, target, id, scope == null ? db : null,
                    scope);
        }
        expect(TokenType.PAREN_CLOSE);
    }

    /** The op with its span's START moved to token {@code startTok}. */
    private Protocol.PRelOp withSpanStart(Protocol.PRelOp op, int startTok) {
        SourceInfo st = spanOf(startTok, startTok);
        SourceInfo w = new SourceInfo("", st.startLine(), st.startColumn(),
                op.sourceInformation().endLine(),
                op.sourceInformation().endColumn());
        return switch (op) {
            case Protocol.PElemtWithJoins ej ->
                    new Protocol.PElemtWithJoins(ej.joins(),
                            ej.relationalElement(), w);
            case Protocol.PColumnRef c -> new Protocol.PColumnRef(c.column(),
                    c.table(), c.tableAlias(), w);
            case Protocol.PDynaFunc f -> new Protocol.PDynaFunc(f.funcName(),
                    f.parameters(), w);
            case Protocol.PRelLiteral l ->
                    new Protocol.PRelLiteral(l.value(), w);
            case Protocol.PRelLiteralList ll ->
                    new Protocol.PRelLiteralList(ll.values(), w);
        };
    }

    /** {@code assoc: ModelJoin { {x:T[1], y:U[1]|expr} }} — the inner
     *  braces are ONE typed ###Pure lambda through SpecParser (probe
     *  modeljoin); member span target..outer close. */
    private Protocol.PModelJoinAssociationMapping parseModelJoin(
            String target, int memberStart, SourceInfo targetSpan) {
        expect(TokenType.BRACE_OPEN);
        int lS = pos;
        int depth = 0;
        while (!atEnd()) {
            TokenType t = peek();
            if (t == TokenType.BRACE_OPEN) {
                depth++;
            } else if (t == TokenType.BRACE_CLOSE) {
                if (depth == 0) {
                    break;
                }
                depth--;
            }
            advance();
        }
        List<com.legend.protocol.spec.ValueSpecification> body =
                SpecParser.parseCodeBlock(tokens.slice(lS, pos));
        if (body.size() != 1) {
            throw error("ModelJoin body must be ONE lambda, got "
                    + body.size());
        }
        int close = pos;
        expect(TokenType.BRACE_CLOSE);
        return new Protocol.PModelJoinAssociationMapping(
                new Protocol.PPointer("ASSOCIATION", target, targetSpan),
                body.get(0), spanOf(memberStart, close));
    }

    /** {@code cls[id]: Relation { ~func <descriptor> prop: col, ... }} —
     *  the descriptor pointer path is the CANONICAL text (tokens joined
     *  with NO spaces); property lines map columns (probe relation-fn). */
    private Protocol.PClassMappingRelation parseRelationClassMapping(
            String target, int memberStart, SourceInfo targetSpan,
            @com.legend.Nullable String id, boolean root) {
        expect(TokenType.BRACE_OPEN);
        if (!(peek() == TokenType.TILDE
                && "func".equals(tokens.text(
                        Math.min(pos + 1, tokens.count() - 1))))) {
            throw error("Relation class mapping must open with ~func");
        }
        advance();
        advance();                                  // func
        int dS = pos;
        StringBuilder desc = new StringBuilder();
        int parens = 0;
        int angles = 0;
        while (!atEnd()) {
            TokenType t = peek();
            if (t == TokenType.PAREN_OPEN) {
                parens++;
            } else if (t == TokenType.PAREN_CLOSE) {
                parens--;
            } else if (t == TokenType.LESS_THAN) {
                angles++;
            } else if (t == TokenType.GREATER_THAN) {
                angles--;
            }
            desc.append(text());
            advance();
            if (parens == 0 && angles == 0
                    && tokens.type(pos - 1) == TokenType.BRACKET_CLOSE) {
                break;                              // ...[m] descriptor end
            }
        }
        SourceInfo fnSpan = spanOf(dS, pos - 1);
        List<String> pk = new ArrayList<>();
        if (peek() == TokenType.PRIMARY_KEY_CMD) {
            // ~primaryKey: [COL, ...] — bare column names (probe
            // relation-extras)
            advance();
            expect(TokenType.COLON);
            expect(TokenType.BRACKET_OPEN);
            while (peek() != TokenType.BRACKET_CLOSE && !atEnd()) {
                pk.add(parseIdentifier());
                match(TokenType.COMMA);
            }
            expect(TokenType.BRACKET_CLOSE);
        }
        List<Protocol.PRelationFnPropertyMapping> props = new ArrayList<>();
        while (!atEnd() && peek() != TokenType.BRACE_CLOSE) {
            int pS = pos;
            boolean local = match(TokenType.PLUS);
            int identTok = pos;
            String prop = parseIdentifier();
            SourceInfo propSpan = spanOf(identTok, pos - 1);
            if (peek() == TokenType.PAREN_OPEN
                    && tokens.type(Math.min(pos + 1, tokens.count() - 1))
                            != TokenType.PAREN_CLOSE) {
                // prop ( street: COL, ... ) — embedded with nested
                // classless pms; span prop..close paren (probe
                // relation-embedded)
                advance();
                List<Protocol.PRelationFnPropertyMapping> nested =
                        new ArrayList<>();
                while (!atEnd() && peek() != TokenType.PAREN_CLOSE) {
                    int nS = pos;
                    String nProp = parseIdentifier();
                    SourceInfo nSpan = spanOf(nS, pos - 1);
                    expect(TokenType.COLON);
                    String nCol = parseIdentifier();
                    nested.add(new Protocol.PRelationFnPropertyMapping(null,
                            nProp, nSpan, nCol, null, null, null, null,
                            spanOf(nS, pos - 1)));
                    match(TokenType.COMMA);
                }
                int pClose = pos;
                expect(TokenType.PAREN_CLOSE);
                props.add(new Protocol.PRelationFnPropertyMapping(target,
                        prop, propSpan, null, null, nested, null, id,
                        spanOf(pS, pClose)));
                match(TokenType.COMMA);
                continue;
            }
            if (peek() == TokenType.PAREN_OPEN) {
                // prop () Inline [set] (probe relation-inline)
                advance();
                expect(TokenType.PAREN_CLOSE);
                if (!(peek() == TokenType.INLINE)) {
                    throw error("expected Inline after ()");
                }
                advance();
                expect(TokenType.BRACKET_OPEN);
                String setId = parseSetId();
                int bClose = pos;
                expect(TokenType.BRACKET_CLOSE);
                props.add(new Protocol.PRelationFnPropertyMapping(target,
                        prop, propSpan, null, setId, null, null, id,
                        spanOf(pS, bClose)));
                match(TokenType.COMMA);
                continue;
            }
            expect(TokenType.COLON);
            Protocol.PLocalProp lp = null;
            if (local) {
                // +prop: Type[m]: COL — local SI = the IDENT, pm span
                // includes the '+', class KEPT (probe relation-extras)
                String type = Protocol.unquotePath(parseQualifiedName());
                expect(TokenType.BRACKET_OPEN);
                long lower;
                Long upper = null;
                if (peek() == TokenType.STAR) {
                    advance();
                    lower = 0L;
                } else {
                    lower = Long.parseLong(text());
                    expect(TokenType.INTEGER);
                    upper = lower;
                }
                if (peek() == TokenType.DOT_DOT) {
                    advance();
                    if (peek() == TokenType.STAR) {
                        advance();
                        upper = null;
                    } else {
                        upper = Long.parseLong(text());
                        expect(TokenType.INTEGER);
                    }
                }
                expect(TokenType.BRACKET_CLOSE);
                lp = new Protocol.PLocalProp(type, lower, upper, propSpan);
                expect(TokenType.COLON);
            }
            String col = parseIdentifier();
            props.add(new Protocol.PRelationFnPropertyMapping(target, prop,
                    propSpan, col, null, null, lp, id,
                    spanOf(pS, pos - 1)));
            match(TokenType.COMMA);
        }
        int close = pos;
        expect(TokenType.BRACE_CLOSE);
        return new Protocol.PClassMappingRelation(target, id, pk, props,
                desc.toString(), fnSpan, root, spanOf(memberStart, close));
    }

    /** {@code id: { function: |...; tests: [...] }} — suite span
     *  id..close; the query lambda reparses with the MAPPING path as its
     *  span sourceId (probe test-suites). */
    private Protocol.PMappingTestSuite parseTestSuite(String mappingFqn) {
        int sS = pos;
        String suiteId = parseIdentifier();
        expect(TokenType.COLON);
        expect(TokenType.BRACE_OPEN);
        com.legend.protocol.spec.ValueSpecification func = null;
        List<Protocol.PMappingTest> tests = new ArrayList<>();
        while (!atEnd() && peek() != TokenType.BRACE_CLOSE) {
            String key = text();
            if ("function".equals(key)) {
                advance();
                expect(TokenType.COLON);
                int fS = pos;
                int depth = 0;
                while (!atEnd()) {
                    TokenType t = peek();
                    if (depth == 0 && t == TokenType.SEMI_COLON) {
                        break;
                    }
                    if (t == TokenType.PAREN_OPEN
                            || t == TokenType.BRACKET_OPEN
                            || t == TokenType.BRACE_OPEN) {
                        depth++;
                    } else if (t == TokenType.PAREN_CLOSE
                            || t == TokenType.BRACKET_CLOSE
                            || t == TokenType.BRACE_CLOSE) {
                        depth--;
                    }
                    advance();
                }
                List<com.legend.protocol.spec.ValueSpecification> body =
                        SpecParser.parseCodeBlock(tokens.slice(fS, pos),
                                mappingFqn);
                if (body.size() != 1) {
                    throw error("suite function must be ONE lambda");
                }
                func = body.get(0);
                expect(TokenType.SEMI_COLON);
                continue;
            }
            if ("tests".equals(key)) {
                advance();
                expect(TokenType.COLON);
                expect(TokenType.BRACKET_OPEN);
                while (!atEnd() && peek() != TokenType.BRACKET_CLOSE) {
                    tests.add(parseMappingTest());
                    match(TokenType.COMMA);
                }
                expect(TokenType.BRACKET_CLOSE);
                match(TokenType.SEMI_COLON);
                continue;
            }
            throw error("mapping test-suite key '" + safeText()
                    + "' is unbuilt");
        }
        int close = pos;
        expect(TokenType.BRACE_CLOSE);
        if (func == null) {
            throw error("mapping test suite without a function");
        }
        return new Protocol.PMappingTestSuite(suiteId, func, tests,
                spanOf(sS, close));
    }

    /** {@code name ( query: |...; data: [<Object, JSON, cls, 'json'>];
     *  assert: 'str'; )} — the legacy MappingTests form (probe
     *  legacy-mapping-tests): test span name..close paren; assert span
     *  keyword..string; inputData span = the angle region. */
    private Protocol.PLegacyMappingTest parseLegacyTest(String mappingFqn) {
        int tS = pos;
        String name = parseIdentifier();
        expect(TokenType.PAREN_OPEN);
        com.legend.protocol.spec.ValueSpecification query = null;
        List<Protocol.PLegacyInputData> inputData = new ArrayList<>();
        String expected = null;
        SourceInfo assertSpan = null;
        while (!atEnd() && peek() != TokenType.PAREN_CLOSE) {
            String key = text();
            if ("query".equals(key)
                    || peek() == TokenType.MAPPING_TESTS_QUERY) {
                advance();
                expect(TokenType.COLON);
                int fS = pos;
                int depth = 0;
                while (!atEnd()) {
                    TokenType t = peek();
                    if (depth == 0 && t == TokenType.SEMI_COLON) {
                        break;
                    }
                    if (t == TokenType.PAREN_OPEN
                            || t == TokenType.BRACKET_OPEN
                            || t == TokenType.BRACE_OPEN) {
                        depth++;
                    } else if (t == TokenType.PAREN_CLOSE
                            || t == TokenType.BRACKET_CLOSE
                            || t == TokenType.BRACE_CLOSE) {
                        depth--;
                    }
                    advance();
                }
                List<com.legend.protocol.spec.ValueSpecification> body =
                        SpecParser.parseCodeBlock(tokens.slice(fS, pos),
                                mappingFqn);
                if (body.size() != 1) {
                    throw error("legacy test query must be ONE lambda");
                }
                query = body.get(0);
                expect(TokenType.SEMI_COLON);
                continue;
            }
            if ("data".equals(key)) {
                advance();
                expect(TokenType.COLON);
                expect(TokenType.BRACKET_OPEN);
                while (!atEnd() && peek() != TokenType.BRACKET_CLOSE) {
                    int dS = pos;
                    expect(TokenType.LESS_THAN);
                    String kind = parseIdentifier();
                    boolean relational = "Relational".equals(kind);
                    if (!relational && !"Object".equals(kind)) {
                        throw error("legacy input kind '" + kind
                                + "' is unbuilt");
                    }
                    expect(TokenType.COMMA);
                    String inputType = parseIdentifier();
                    expect(TokenType.COMMA);
                    String targetPath =
                            Protocol.unquotePath(parseQualifiedName());
                    expect(TokenType.COMMA);
                    String data = TokenStreamCursor.unquoteAndUnescape(
                            text(), this);
                    advance();
                    int dE = pos;
                    expect(TokenType.GREATER_THAN);
                    inputData.add(new Protocol.PLegacyInputData(relational,
                            targetPath, inputType, data, spanOf(dS, dE)));
                    match(TokenType.COMMA);
                }
                expect(TokenType.BRACKET_CLOSE);
                expect(TokenType.SEMI_COLON);
                continue;
            }
            if ("assert".equals(key)) {
                int aS = pos;
                advance();
                expect(TokenType.COLON);
                // engine: fromGrammarString(x, FALSE) — RAW body, quotes
                // stripped, NO unescape (MappingParseTreeWalker:295)
                String rawStr = text();
                expected = rawStr.substring(1, rawStr.length() - 1);
                advance();
                assertSpan = spanOf(aS, pos);   // span INCLUDES the ';'
                expect(TokenType.SEMI_COLON);
                continue;
            }
            throw error("legacy mapping-test key '" + safeText()
                    + "' is unbuilt");
        }
        int close = pos;
        expect(TokenType.PAREN_CLOSE);
        if (query == null || expected == null || assertSpan == null) {
            throw error("legacy mapping test needs query AND assert");
        }
        return new Protocol.PLegacyMappingTest(name, query, inputData,
                expected, assertSpan, spanOf(tS, close));
    }

    private Protocol.PMappingTest parseMappingTest() {
        int tS = pos;
        String testId = parseIdentifier();
        expect(TokenType.COLON);
        expect(TokenType.BRACE_OPEN);
        List<Protocol.PStoreTestData> data = new ArrayList<>();
        List<Protocol.PTestAssertion> asserts = new ArrayList<>();
        String doc = null;
        while (!atEnd() && peek() != TokenType.BRACE_CLOSE) {
            String key = text();
            if ("doc".equals(key)) {
                advance();
                expect(TokenType.COLON);
                doc = TokenStreamCursor.unquoteAndUnescape(text(), this);
                advance();
                expect(TokenType.SEMI_COLON);
                continue;
            }
            if ("data".equals(key)) {
                advance();
                expect(TokenType.COLON);
                expect(TokenType.BRACKET_OPEN);
                while (!atEnd() && peek() != TokenType.BRACKET_CLOSE) {
                    data.add(parseStoreTestData());
                    match(TokenType.COMMA);
                }
                expect(TokenType.BRACKET_CLOSE);
                expect(TokenType.SEMI_COLON);
                continue;
            }
            if ("asserts".equals(key)) {
                advance();
                expect(TokenType.COLON);
                expect(TokenType.BRACKET_OPEN);
                while (!atEnd() && peek() != TokenType.BRACKET_CLOSE) {
                    asserts.add(parseTestAssertion());
                    match(TokenType.COMMA);
                }
                expect(TokenType.BRACKET_CLOSE);
                expect(TokenType.SEMI_COLON);
                continue;
            }
            throw error("mapping test key '" + safeText() + "' is unbuilt");
        }
        int close = pos;
        expect(TokenType.BRACE_CLOSE);
        return new Protocol.PMappingTest(testId, doc, data, asserts,
                spanOf(tS, close));
    }

    /** {@code Store: ModelStore #{ path: ExternalFormat #{...}# }#}. */
    private Protocol.PStoreTestData parseStoreTestData() {
        int sS = pos;
        String storePath = Protocol.unquotePath(parseQualifiedName());
        SourceInfo storeSpan = spanOf(sS, pos - 1);
        expect(TokenType.COLON);
        if (peek() == TokenType.VALID_STRING && "Reference".equals(text())) {
            // Reference #{ my::DataElement }# — the pointer span runs the
            // Reference KEYWORD through the island close, like
            // ExternalFormat (probe reference-data)
            int refTok = pos;
            advance();
            IslandBlock refIsland = readIsland();
            MappingProtocolParser ri = new MappingProtocolParser(
                    refIsland.tokens(), 0);
            String dataPath = Protocol.unquotePath(ri.parseQualifiedName());
            Protocol.PPointer de = new Protocol.PPointer("DATA", dataPath,
                    new SourceInfo("",
                            tokens.startLine(refTok),
                            tokens.startColumn(refTok),
                            refIsland.endLine(),
                            refIsland.endColumn()));
            return new Protocol.PStoreTestData(
                    new Protocol.PPointer("STORE", storePath, storeSpan),
                    null, null, de, null, null,
                    new SourceInfo("", storeSpan.startLine(),
                            storeSpan.startColumn(), refIsland.endLine(),
                            refIsland.endColumn()));
        }
        if (peek() == TokenType.VALID_STRING && "Relation".equals(text())) {
            // Relation #{ schema.table: CSV rows...; }# (probe
            // relation-data-exact); cells TRIM both sides
            advance();
            IslandBlock ri = readIsland();
            List<Protocol.PRelationElement> rels =
                    parseRelationElements(ri, true);
            SourceInfo first = rels.isEmpty()
                    ? spanOf(pos - 1, pos - 1)
                    : rels.get(0).sourceInformation();
            SourceInfo accessor = new SourceInfo("", first.startLine(),
                    first.startColumn(), ri.endLine(), ri.endColumn() + 3);
            return new Protocol.PStoreTestData(
                    new Protocol.PPointer("STORE", storePath, storeSpan),
                    null, null, null, rels, accessor,
                    new SourceInfo("", storeSpan.startLine(),
                            storeSpan.startColumn(), ri.endLine(),
                            ri.endColumn()));
        }
        if (!(peek() == TokenType.VALID_STRING
                && "ModelStore".equals(text()))) {
            throw error("store test data kind '" + safeText()
                    + "' is unbuilt");
        }
        int msTok = pos;
        advance();                                  // ModelStore
        IslandBlock island = readIsland();
        List<Protocol.PModelData> modelData = new ArrayList<>();
        MappingProtocolParser inner = new MappingProtocolParser(
                island.tokens(), 0);
        while (!inner.atEnd()) {
            modelData.add(inner.parseModelEntry(island));
            inner.match(TokenType.COMMA);
        }
        // modelStore span = the ModelStore token .. the island close
        SourceInfo msSpan = new SourceInfo("",
                tokens.startLine(msTok), tokens.startColumn(msTok),
                island.endLine(), island.endColumn());
        return new Protocol.PStoreTestData(
                new Protocol.PPointer("STORE", storePath, storeSpan),
                modelData, msSpan, null, null, null,
                new SourceInfo("", storeSpan.startLine(),
                        storeSpan.startColumn(), island.endLine(),
                        island.endColumn()));
    }

    /** One {@code path: <value>} entry inside a ModelStore island — an
     *  embedded data value, or a {@code [ ^X(...) ]} INSTANCE collection
     *  parsed by the ###Pure expression machinery (probes test-suites,
     *  embedded-reference, data-section, model-instances). */
    private Protocol.PModelData parseModelEntry(IslandBlock island) {
        int mS = pos;
        String model = Protocol.unquotePath(parseQualifiedName());
        expect(TokenType.COLON);
        if (peek() == TokenType.BRACKET_OPEN) {
            int lS = pos;
            skipBracketBlock();
            int close = pos - 1;
            List<com.legend.protocol.spec.ValueSpecification> vs =
                    SpecParser.parseCodeBlock(
                            island.tokens().slice(lS, pos), spanSourceId());
            if (vs.size() != 1) {
                throw error("model instance data must be ONE collection");
            }
            // the engine BUILDS this collection in its data walker rather
            // than taking it from the parse tree, so the outer node carries
            // no span — only the leaf literals do (probe model-instances)
            com.legend.protocol.spec.ValueSpecification instances =
                    vs.get(0) instanceof com.legend.protocol.spec.PureCollection c
                            ? new com.legend.protocol.spec.PureCollection(c.values())
                            : vs.get(0);
            return new Protocol.PModelInstanceData(model, instances,
                    new SourceInfo("", island.tokens().startLine(mS),
                            island.tokens().startColumn(mS),
                            island.tokens().endLine(close),
                            island.tokens().endColumn(close)));
        }
        Protocol.PEmbeddedDataValue val = parseEmbeddedValue();
        SourceInfo vSi = embeddedSpan(val);
        return new Protocol.PModelEmbeddedData(model, val,
                new SourceInfo("", island.tokens().startLine(mS),
                        island.tokens().startColumn(mS),
                        vSi.endLine(), vSi.endColumn()));
    }

    /** Skip a bracket-balanced block starting AT the open bracket. */
    private void skipBracketBlock() {
        expect(TokenType.BRACKET_OPEN);
        int depth = 0;
        while (!atEnd()) {
            TokenType t = peek();
            if (t == TokenType.BRACKET_OPEN || t == TokenType.PAREN_OPEN
                    || t == TokenType.BRACE_OPEN) {
                depth++;
            } else if (t == TokenType.PAREN_CLOSE
                    || t == TokenType.BRACE_CLOSE) {
                depth--;
            } else if (t == TokenType.BRACKET_CLOSE) {
                if (depth == 0) {
                    advance();
                    return;
                }
                depth--;
            }
            advance();
        }
        throw error("unterminated instance collection");
    }

    /** {@code id: EqualToJson #{ expected: ExternalFormat #{...}#; }#}. */
    private Protocol.PTestAssertion parseTestAssertion() {
        int aS = pos;
        String assertId = parseIdentifier();
        expect(TokenType.COLON);
        if (peek() == TokenType.VALID_STRING && "Relation".equals(text())) {
            int rTok = pos;
            advance();
            int braceTok = pos;                     // the ISLAND_OPEN '#{'
            IslandBlock ri = readIsland();
            List<Protocol.PRelationElement> rels =
                    parseRelationElements(ri, false);
            if (rels.size() != 1) {
                throw error("Relation assertion must be ONE element");
            }
            match(TokenType.SEMI_COLON);
            // assertion expected spans run (braceLine, braceCol+2) ..
            // (semiLine-1, semiCol) — probe relation-rows; DATA elements
            // keep path..semi
            Protocol.PRelationElement re = rels.get(0);
            SourceInfo es = re.sourceInformation();
            Protocol.PRelationElement shifted =
                    new Protocol.PRelationElement(re.columns(), re.paths(),
                            re.rows(), new SourceInfo("",
                                    tokens.startLine(braceTok),
                                    tokens.startColumn(braceTok) + 2,
                                    es.endLine() - 1, es.endColumn()));
            return new Protocol.PTestAssertion(assertId, shifted,
                    new SourceInfo("", tokens.startLine(rTok),
                            tokens.startColumn(rTok), ri.endLine(),
                            ri.endColumn()));
        }
        if (!(peek() == TokenType.VALID_STRING
                && "EqualToJson".equals(text()))) {
            throw error("test assertion kind '" + safeText()
                    + "' is unbuilt");
        }
        int eqTok = pos;
        advance();                                  // EqualToJson
        IslandBlock island = readIsland();
        MappingProtocolParser inner = new MappingProtocolParser(
                island.tokens(), 0);
        if (!"expected".equals(inner.text())) {
            throw inner.error("assertion key '" + inner.safeText()
                    + "' is unbuilt");
        }
        inner.advance();
        inner.expect(TokenType.COLON);
        if (!(inner.peek() == TokenType.VALID_STRING
                && "ExternalFormat".equals(inner.text()))) {
            throw inner.error("assertion payload '" + inner.safeText()
                    + "' is unbuilt");
        }
        Protocol.PExternalFormatData ef = inner.parseExternalFormat();
        inner.match(TokenType.SEMI_COLON);
        return new Protocol.PTestAssertion(assertId, ef,
                new SourceInfo("", tokens.startLine(eqTok),
                        tokens.startColumn(eqTok), island.endLine(),
                        island.endColumn()));
    }

    /** {@code ExternalFormat #{ contentType: '...'; data: '...'; }#} —
     *  span ExternalFormat..}#. */
    private Protocol.PExternalFormatData parseExternalFormat() {
        int efTok = pos;
        advance();                                  // ExternalFormat
        IslandBlock island = readIsland();
        MappingProtocolParser inner = new MappingProtocolParser(
                island.tokens(), 0);
        String contentType = null;
        String dataStr = null;
        while (!inner.atEnd()) {
            String key = inner.text();
            inner.advance();
            inner.expect(TokenType.COLON);
            String v = TokenStreamCursor.unquoteAndUnescape(inner.text(),
                    inner);
            inner.advance();
            inner.expect(TokenType.SEMI_COLON);
            if ("contentType".equals(key)) {
                contentType = v;
            } else if ("data".equals(key)) {
                dataStr = v;
            } else {
                throw inner.error("external-format key '" + key
                        + "' is unbuilt");
            }
        }
        if (contentType == null || dataStr == null) {
            throw error("external format needs contentType AND data");
        }
        return new Protocol.PExternalFormatData(contentType, dataStr,
                new SourceInfo("", tokens.startLine(efTok),
                        tokens.startColumn(efTok), island.endLine(),
                        island.endColumn()));
    }

    /** One {@code #{ ... }#} island: RE-LEXES the raw content with
     *  line/column padding so inner spans stay absolute (island content
     *  arrives as raw chunks; same emulation as the merge getText
     *  reparse). Nested islands re-lex recursively through the SAME
     *  machinery. */
    private record IslandBlock(TokenStream tokens, int endLine,
            int endColumn, int contentStart, int contentEnd) { }

    private IslandBlock readIsland() {
        expect(TokenType.ISLAND_OPEN);
        int contentStart = tokens.start(pos);
        int depth = 0;
        while (!atEnd()) {
            TokenType t = peek();
            if (t == TokenType.ISLAND_START) {
                depth++;
            } else if (t == TokenType.ISLAND_END) {
                if (depth == 0) {
                    break;
                }
                depth--;
            }
            advance();
        }
        int endTok = pos;
        int contentEnd = tokens.start(endTok);
        expect(TokenType.ISLAND_END);
        String source = tokens.source();
        StringBuilder padded = new StringBuilder();
        int line = 1;
        int lastNl = -1;
        for (int i = 0; i < contentStart; i++) {
            if (source.charAt(i) == '\n') {
                line++;
                lastNl = i;
            }
        }
        for (int i = 1; i < line; i++) {
            padded.append('\n');
        }
        for (int i = lastNl + 1; i < contentStart; i++) {
            padded.append(' ');
        }
        padded.append(source, contentStart, contentEnd);
        return new IslandBlock(
                com.legend.lexer.Lexer.tokenize(padded.toString()),
                tokens.endLine(endTok), tokens.endColumn(endTok),
                contentStart, contentEnd);
    }

    /** {@code cls[id]: AggregationAware { Views: [...] , ~mainMapping:
     *  Relational {...} }} — see probes agg-off-A/B for the two span
     *  shift rules (nested CMs +DELTA lines; lambdas -1 line). */
    private Protocol.PClassMappingAggregationAware parseAggregationAware(
            String target, int memberStart, SourceInfo targetSpan,
            @com.legend.Nullable String id, boolean root) {
        if (sectionStartLine < 0) {
            throw error("AggregationAware needs the section start line"
                    + " (span-shift emulation)");
        }
        String outerId = id != null ? id : target.replace("::", "_");
        int braceOpenTok = pos;
        expect(TokenType.BRACE_OPEN);
        record AggView(boolean canAggregate, List<Object> groupBys,
                List<long[]> aggValues, int relTok) { }
        List<boolean[]> canAggs = new ArrayList<>();
        List<List<com.legend.protocol.spec.ValueSpecification>> groupBys =
                new ArrayList<>();
        List<List<Protocol.PAggregateValue>> aggVals = new ArrayList<>();
        List<Integer> aggBodyToks = new ArrayList<>();
        int mainBodyTok = 0;                        // 0 = absent
        while (!atEnd() && peek() != TokenType.BRACE_CLOSE) {
            if (peek() == TokenType.VALID_STRING && "Views".equals(text())) {
                advance();
                expect(TokenType.COLON);
                expect(TokenType.BRACKET_OPEN);
                while (!atEnd() && peek() != TokenType.BRACKET_CLOSE) {
                    expect(TokenType.PAREN_OPEN);
                    int tildeTok = pos;
                    expect(TokenType.TILDE);
                    expectText("modelOperation");
                    expect(TokenType.COLON);
                    int braceTok = pos;
                    expect(TokenType.BRACE_OPEN);
                    // engine anchors the spec sub-parse at the TILDE line
                    // but slices text after the BRACE — everything inside
                    // shifts UP by the gap (AggregationAwareMapping
                    // ParseTreeWalker.visitModelOperationContext)
                    aggLambdaShift = tokens.startLine(braceTok)
                            - tokens.startLine(tildeTok);
                    boolean canAgg = false;
                    List<com.legend.protocol.spec.ValueSpecification> gbs =
                            new ArrayList<>();
                    List<Protocol.PAggregateValue> avs = new ArrayList<>();
                    while (!atEnd() && peek() != TokenType.BRACE_CLOSE) {
                        expect(TokenType.TILDE);
                        String cmd = parseIdentifier();
                        switch (cmd) {
                            case "canAggregate" -> {
                                canAgg = Boolean.parseBoolean(text());
                                advance();
                            }
                            case "groupByFunctions" -> {
                                expect(TokenType.PAREN_OPEN);
                                while (peek() != TokenType.PAREN_CLOSE
                                        && !atEnd()) {
                                    gbs.add(parseAggLambda());
                                    match(TokenType.COMMA);
                                }
                                expect(TokenType.PAREN_CLOSE);
                            }
                            case "aggregateValues" -> {
                                expect(TokenType.PAREN_OPEN);
                                while (peek() != TokenType.PAREN_CLOSE
                                        && !atEnd()) {
                                    expect(TokenType.PAREN_OPEN);
                                    expect(TokenType.TILDE);
                                    expectText("mapFn");
                                    expect(TokenType.COLON);
                                    com.legend.protocol.spec
                                            .ValueSpecification mapFn =
                                            parseAggLambda();
                                    expect(TokenType.COMMA);
                                    expect(TokenType.TILDE);
                                    expectText("aggregateFn");
                                    expect(TokenType.COLON);
                                    com.legend.protocol.spec
                                            .ValueSpecification aggFn =
                                            parseAggLambda();
                                    expect(TokenType.PAREN_CLOSE);
                                    avs.add(new Protocol.PAggregateValue(
                                            mapFn, aggFn));
                                    match(TokenType.COMMA);
                                }
                                expect(TokenType.PAREN_CLOSE);
                            }
                            default -> throw error("modelOperation command"
                                    + " '~" + cmd + "' is unbuilt");
                        }
                        match(TokenType.COMMA);
                    }
                    expect(TokenType.BRACE_CLOSE);
                    match(TokenType.COMMA);
                    expect(TokenType.TILDE);
                    expectText("aggregateMapping");
                    expect(TokenType.COLON);
                    boolean aggIsPure = peek() == TokenType.PURE_MAPPING;
                    if (aggIsPure) {
                        advance();
                    } else {
                        expect(TokenType.RELATIONAL);
                    }
                    canAggs.add(new boolean[]{canAgg});
                    groupBys.add(gbs);
                    aggVals.add(avs);
                    aggBodyToks.add(aggIsPure ? -pos : pos);
                    skipBraceBlock();
                    expect(TokenType.PAREN_CLOSE);
                    match(TokenType.COMMA);
                }
                expect(TokenType.BRACKET_CLOSE);
                match(TokenType.COMMA);
                continue;
            }
            if (peek() == TokenType.TILDE
                    && "mainMapping".equals(tokens.text(
                            Math.min(pos + 1, tokens.count() - 1)))) {
                advance();
                advance();
                expect(TokenType.COLON);
                boolean mainIsPure = peek() == TokenType.PURE_MAPPING;
                if (mainIsPure) {
                    advance();
                } else {
                    expect(TokenType.RELATIONAL);
                }
                mainBodyTok = mainIsPure ? -pos : pos;
                skipBraceBlock();
                match(TokenType.COMMA);
                continue;
            }
            throw error("AggregationAware member '" + safeText()
                    + "' is unbuilt");
        }
        int close = pos;
        expect(TokenType.BRACE_CLOSE);
        if (mainBodyTok == 0) {
            throw error("AggregationAware without ~mainMapping");
        }
        // engine sub-parse shift (probes agg-off-A/B + corpus
        // mapping.pure/saleMapping): nested CM CONTENTS keep TRUE spans;
        // ONLY classSourceInformation (= the outer target span) and the
        // CM's sourceInformation (= the member span) shift DOWN by
        // DELTA = memberIslandBraceLine - sectionStartLine + 1
        int delta = tokens.startLine(braceOpenTok) - sectionStartLine + 1;
        SourceInfo shiftedTarget = shiftLines(targetSpan, delta);
        SourceInfo shiftedMember =
                shiftLines(spanOf(memberStart, close), delta);
        List<Protocol.PAggregateSetImplementation> asis = new ArrayList<>();
        for (int i = 0; i < aggBodyToks.size(); i++) {
            Protocol.PClassMapping cm = parseNested(aggBodyToks.get(i),
                    target, shiftedTarget,
                    outerId + "_Aggregate_" + i, id, root, shiftedMember);
            asis.add(new Protocol.PAggregateSetImplementation(
                    canAggs.get(i)[0], groupBys.get(i), aggVals.get(i), i,
                    cm));
        }
        Protocol.PClassMapping mainCm = parseNested(mainBodyTok,
                target, shiftedTarget, outerId + "_Main", id, root,
                shiftedMember);
        List<Protocol.PPurePropertyMapping> aggPms = new ArrayList<>();
        if (mainCm instanceof Protocol.PClassMappingPure pu) {
            // Pure main mappings COPY their pms onto the agg node
            // (walker:92-95, probe agg-pure-nested)
            aggPms.addAll(pu.propertyMappings());
        }
        return new Protocol.PClassMappingAggregationAware(target, outerId,
                asis, mainCm, aggPms, root, spanOf(memberStart, close));
    }

    private static SourceInfo shiftLines(SourceInfo si, int delta) {
        return new SourceInfo(si.sourceId(), si.startLine() + delta,
                si.startColumn(), si.endLine() + delta, si.endColumn());
    }

    /** A nested Relational CM parsed from the ORIGINAL stream (contents
     *  keep TRUE spans); its class span and OWN span are the SHIFTED
     *  outer spans (probe agg-off). */
    private Protocol.PClassMapping parseNested(int signedBodyTok,
            String target, SourceInfo shiftedTarget, String cmId,
            @com.legend.Nullable String explicitOuterId, boolean root,
            SourceInfo shiftedMember) {
        if (signedBodyTok < 0) {
            MappingProtocolParser p = new MappingProtocolParser(tokens,
                    -signedBodyTok);
            Protocol.PClassMappingPure cm = p.parsePureClassMapping(target,
                    -signedBodyTok, shiftedTarget, explicitOuterId, root,
                    null);
            return new Protocol.PClassMappingPure(cm.className(),
                    shiftedTarget, cm.extendsClassMappingId(), cmId,
                    cm.root(), cm.srcClass(),
                    cm.sourceClassSourceInformation(), cm.filter(),
                    cm.propertyMappings(), shiftedMember);
        }
        return parseNestedRel(signedBodyTok, target, shiftedTarget, cmId,
                explicitOuterId, root, shiftedMember);
    }

    private Protocol.PClassMappingRel parseNestedRel(int bodyTok,
            String target, SourceInfo shiftedTarget, String cmId,
            @com.legend.Nullable String explicitOuterId, boolean root,
            SourceInfo shiftedMember) {
        MappingProtocolParser p = new MappingProtocolParser(tokens, bodyTok);
        // pm sources keep the EXPLICIT outer id (or null) — the engine
        // suffixes the CM id only AFTER the sub-parse
        // (AggregationAwareMappingParseTreeWalker:218)
        Protocol.PClassMappingRel cm = p.parseRelationalClassMapping(target,
                bodyTok, shiftedTarget, explicitOuterId, root, null);
        return new Protocol.PClassMappingRel(cm.className(), shiftedTarget,
                cmId, cm.root(), cm.distinct(),
                cm.extendsClassMappingId(), cm.filter(), cm.groupBy(),
                cm.mainTable(), cm.primaryKey(), cm.propertyMappings(),
                shiftedMember);
    }

    /** One aggregate lambda expr — spans ride a CONSTANT -1 line shift
     *  (probe agg-off): pad-relex the slice one line short. */
    private com.legend.protocol.spec.ValueSpecification parseAggLambda() {
        int eS = pos;
        int depth = 0;
        while (!atEnd()) {
            TokenType t = peek();
            if (depth == 0 && (t == TokenType.COMMA
                    || t == TokenType.PAREN_CLOSE)) {
                break;
            }
            if (t == TokenType.PAREN_OPEN || t == TokenType.BRACKET_OPEN
                    || t == TokenType.BRACE_OPEN) {
                depth++;
            } else if (t == TokenType.PAREN_CLOSE
                    || t == TokenType.BRACKET_CLOSE
                    || t == TokenType.BRACE_CLOSE) {
                depth--;
            }
            advance();
        }
        StringBuilder padded = new StringBuilder();
        for (int i = 1; i < tokens.startLine(eS) - aggLambdaShift; i++) {
            padded.append('\n');
        }
        for (int i = 1; i < tokens.startColumn(eS); i++) {
            padded.append(' ');
        }
        padded.append(tokens.source(), tokens.start(eS),
                tokens.end(pos - 1));
        List<com.legend.protocol.spec.ValueSpecification> body =
                SpecParser.parseCodeBlock(
                        com.legend.lexer.Lexer.tokenize(padded.toString()));
        if (body.size() != 1) {
            throw error("aggregate lambda must be ONE expression");
        }
        return body.get(0);
    }

    /** Quoted table spellings KEEP their quotes as the wire name (same
     *  rule as DatabaseProtocolParser.parseIdentifier). */
    private String parseMaybeQuotedIdentifier() {
        if (peek() == TokenType.QUOTED_STRING) {
            String raw = text();
            advance();
            return raw;
        }
        return parseIdentifier();
    }

    /** {@code [schema.table:]\n HEADER \n ROWS... ;} groups inside a
     *  Relation island — a CSV sub-format the engine parses from raw
     *  chars; cells TRIM both sides (probe relation-data-exact). */
    private List<Protocol.PRelationElement> parseRelationElements(
            IslandBlock island, boolean withPaths) {
        String src = tokens.source();
        int p = island.contentStart();
        int end = island.contentEnd();
        List<Protocol.PRelationElement> out = new ArrayList<>();
        while (true) {
            while (p < end && Character.isWhitespace(src.charAt(p))) {
                p++;
            }
            if (p >= end) {
                break;
            }
            int elemStart = p;
            List<String> paths = new ArrayList<>();
            if (withPaths) {
                int eol = lineEnd(src, p, end);
                String line = src.substring(p, eol).strip();
                if (!line.endsWith(":")) {
                    throw error("relation data path line must end ':'");
                }
                splitOn(line.substring(0, line.length() - 1), '.', paths);
                p = eol + 1;
                while (p < end && Character.isWhitespace(src.charAt(p))) {
                    p++;
                }
            }
            int eol = lineEnd(src, p, end);
            List<String> columns = new ArrayList<>();
            splitCells(src.substring(p, eol).strip(), columns);
            p = eol + 1;
            List<List<String>> rows = new ArrayList<>();
            int semiPos = -1;
            while (p < end) {
                int rEol = lineEnd(src, p, end);
                String line = src.substring(p, rEol).strip();
                if (line.isEmpty()) {
                    p = rEol + 1;
                    continue;
                }
                boolean last = line.endsWith(";");
                if (last) {
                    semiPos = p + src.substring(p, rEol).lastIndexOf(';');
                    line = line.substring(0, line.length() - 1);
                }
                List<String> row = new ArrayList<>();
                splitCells(line, row);
                rows.add(row);
                p = rEol + 1;
                if (last) {
                    break;
                }
            }
            if (semiPos < 0) {
                throw error("relation data rows must end with ';'");
            }
            out.add(new Protocol.PRelationElement(columns, paths, rows,
                    new SourceInfo("", tokens.lineOf(elemStart),
                            tokens.columnOf(elemStart),
                            tokens.lineOf(semiPos),
                            tokens.columnOf(semiPos))));
        }
        return out;
    }

    private static int lineEnd(String src, int from, int end) {
        int i = from;
        while (i < end && src.charAt(i) != '\n') {
            i++;
        }
        return i;
    }

    /** One CSV line into cells. A {@code "} groups commas — but the engine's
     *  reader KEEPS the quotes in the value, so {@code "Doe, Jr"} is ONE cell
     *  whose text is still {@code "Doe, Jr"} (probe relation-quoted-cells).
     *  Trimming applies to the raw cell, hence never inside the quotes. */
    private static void splitCells(String s, List<String> out) {
        int start = 0;
        boolean quoted = false;
        for (int i = 0; i <= s.length(); i++) {
            if (i < s.length() && s.charAt(i) == '"') {
                quoted = !quoted;
            } else if (i == s.length() || (s.charAt(i) == ',' && !quoted)) {
                out.add(s.substring(start, i).strip());
                start = i + 1;
            }
        }
    }

    private static void splitOn(String s, char sep, List<String> out) {
        int start = 0;
        for (int i = 0; i <= s.length(); i++) {
            if (i == s.length() || s.charAt(i) == sep) {
                out.add(s.substring(start, i).strip());
                start = i + 1;
            }
        }
    }

    private void expectText(String want) {
        if (!want.equals(text())) {
            throw error("expected '" + want + "' but found '" + safeText()
                    + "'");
        }
        advance();
    }

    /** Skip a brace-balanced block starting AT the open brace. */
    private void skipBraceBlock() {
        expect(TokenType.BRACE_OPEN);
        int depth = 0;
        while (!atEnd()) {
            TokenType t = peek();
            if (t == TokenType.BRACE_OPEN) {
                depth++;
            } else if (t == TokenType.BRACE_CLOSE) {
                if (depth == 0) {
                    advance();
                    return;
                }
                depth--;
            }
            advance();
        }
        throw error("unclosed brace block");
    }

    /** One embedded op under the ACTIVE scope context (scope table >
     *  scope db > bare). */
    private Protocol.PRelOp parseOpInCtx(
            @com.legend.Nullable String scopeDb,
            DatabaseProtocolParser.@com.legend.Nullable ScopeCtx scope) {
        int[] posOut = new int[1];
        Protocol.PRelOp op;
        if (scope != null) {
            op = DatabaseProtocolParser.scopedOperationAt(tokens, pos, scope,
                    posOut);
        } else if (scopeDb != null) {
            op = DatabaseProtocolParser.operationAt(tokens, pos, scopeDb,
                    "default", posOut);
        } else {
            return parseEmbeddedOperation();
        }
        pos = posOut[0];
        return op;
    }

    /** One embedded relational operation via THE relational op grammar. */
    private Protocol.PRelOp parseEmbeddedOperation() {
        int[] posOut = new int[1];
        Protocol.PRelOp op = DatabaseProtocolParser.operationAt(tokens, pos,
                "", "default", posOut);
        pos = posOut[0];
        return op;
    }

    /** {@code path: EnumerationMapping id { V: [src, ...], ... }} — the
     *  mapping's span runs the TARGET path through the closing brace;
     *  pointer type ENUMERATION (probe enum-mapping). */
    private Protocol.PEnumerationMapping parseEnumerationMapping(
            String target, int targetStart, SourceInfo targetSpan) {
        String id = peek() == TokenType.BRACE_OPEN ? null : parseIdentifier();
        expect(TokenType.BRACE_OPEN);
        List<Protocol.PEnumValueMapping> rows = new ArrayList<>();
        while (!atEnd() && peek() != TokenType.BRACE_CLOSE) {
            String enumValue = parseIdentifier();
            expect(TokenType.COLON);
            List<Protocol.PEnumSourceValue> sources = new ArrayList<>();
            if (peek() == TokenType.BRACKET_OPEN) {
                advance();
                while (peek() != TokenType.BRACKET_CLOSE && !atEnd()) {
                    sources.add(parseSourceValue());
                    match(TokenType.COMMA);
                }
                expect(TokenType.BRACKET_CLOSE);
            } else {
                sources.add(parseSourceValue());
            }
            rows.add(new Protocol.PEnumValueMapping(enumValue, sources));
            match(TokenType.COMMA);
        }
        int close = pos;
        expect(TokenType.BRACE_CLOSE);
        return new Protocol.PEnumerationMapping(id,
                new Protocol.PPointer("ENUMERATION", target, targetSpan),
                rows, spanOf(targetStart, close));
    }

    private Protocol.PEnumSourceValue parseSourceValue() {
        if (peek() == TokenType.STRING) {
            String v = TokenStreamCursor.unquoteAndUnescape(text(), this);
            advance();
            return new Protocol.PEnumSourceValue(null, v);
        }
        if (peek() == TokenType.INTEGER) {
            long v = Long.parseLong(text());
            advance();
            return new Protocol.PEnumSourceValue(null, v);
        }
        if (peek() == TokenType.VALID_STRING) {
            // my::Other.bla — an enum VALUE reference (probe
            // enum-source-enumref)
            String enumPath = Protocol.unquotePath(parseQualifiedName());
            expect(TokenType.DOT);
            String v = parseIdentifier();
            return new Protocol.PEnumSourceValue(enumPath, v);
        }
        throw error("unsupported enum source value: " + safeText());
    }
}
