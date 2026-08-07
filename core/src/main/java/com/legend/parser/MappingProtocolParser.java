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
        return new MappingProtocolParser(ts, tokenIndex).parseMapping();
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
        while (!atEnd() && peek() != TokenType.PAREN_CLOSE) {
            parseMember(assocMappings, classMappings, enums, includes);
        }
        expect(TokenType.PAREN_CLOSE);
        return new Protocol.PMapping(pkg, name, assocMappings, classMappings,
                enums, includes, spanOf(declStart, pos - 1));
    }

    private void parseMember(List<Protocol.PAssociationMapping> assocMappings,
            List<Protocol.PClassMapping> classMappings,
            List<Protocol.PEnumerationMapping> enums,
            List<Protocol.PMappingInclude> includes) {
        if (peek() == TokenType.INCLUDE) {
            int s = pos;
            advance();
            if (peek() == TokenType.MAPPING) {
                advance();                          // 'include mapping qn'
            } else if (peek() == TokenType.VALID_STRING
                    && "dataspace".equals(text())) {
                throw error("include dataspace is unbuilt");
            }
            String inc = Protocol.unquotePath(parseQualifiedName());
            String srcDb = null;
            String tgtDb = null;
            if (peek() == TokenType.BRACKET_OPEN) {
                // include m[srcDb->tgtDb] — store substitution
                advance();
                srcDb = Protocol.unquotePath(parseQualifiedName());
                expect(TokenType.ARROW);
                tgtDb = Protocol.unquotePath(parseQualifiedName());
                expect(TokenType.BRACKET_CLOSE);
            }
            includes.add(new Protocol.PMappingInclude(inc, srcDb, tgtDb,
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
            id = parseIdentifier();
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
                        memberStart, targetSpan));
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
        if (extendsId != null) {
            throw error("'extends' on a non-relational class mapping"
                    + " is unbuilt");
        }
        if (peek() == TokenType.PURE_MAPPING) {
            advance();
            classMappings.add(parsePureClassMapping(target, memberStart,
                    targetSpan, id, root));
            return;
        }
        if (peek() == TokenType.VALID_STRING && "Operation".equals(text())) {
            advance();
            classMappings.add(parseOperationClassMapping(target, memberStart,
                    targetSpan, id, root));
            return;
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
        Protocol.PTablePtr mainTable = null;
        List<Protocol.PRelOp> primaryKey = new ArrayList<>();
        List<Protocol.PRelOp> groupBy = new ArrayList<>();
        boolean distinct = false;
        List<Protocol.PRelPropertyMapping> props = new ArrayList<>();
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
                String tbl = parseIdentifier();
                if (peek() == TokenType.DOT) {
                    advance();
                    schema = tbl;
                    tbl = parseIdentifier();
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
            if (peek() == TokenType.TILDE || peek() == TokenType.FILTER_CMD) {
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
                distinct, extendsId, groupBy, mainTable, primaryKey, props,
                spanOf(memberStart, close));
    }

    /** {@code cls: Pure { ~src my::S  prop: <pure expr>, ... }} — the
     *  transforms parse through SpecParser (the ###Pure machinery) with
     *  absolute spans via token slices (probe pure-m2m). */
    private Protocol.PClassMappingPure parsePureClassMapping(String target,
            int memberStart, SourceInfo targetSpan,
            @com.legend.Nullable String id, boolean root) {
        expect(TokenType.BRACE_OPEN);
        String srcClass = null;
        SourceInfo srcSpan = null;
        List<Protocol.PPurePropertyMapping> props = new ArrayList<>();
        while (!atEnd() && peek() != TokenType.BRACE_CLOSE) {
            if (peek() == TokenType.SRC_CMD) {
                advance();                          // '~src' is ONE token
                int sS = pos;
                srcClass = Protocol.unquotePath(parseQualifiedName());
                srcSpan = spanOf(sS, pos - 1);
                continue;
            }
            if (peek() == TokenType.TILDE || peek() == TokenType.FILTER_CMD) {
                throw error("pure class-mapping directive '" + safeText()
                        + "' is unbuilt");
            }
            int pS = pos;
            String prop = parseIdentifier();
            SourceInfo propSpan = spanOf(pS, pos - 1);
            if (peek() == TokenType.STAR || peek() == TokenType.PLUS
                    || peek() == TokenType.BRACKET_OPEN) {
                throw error("pure property-mapping decoration '" + safeText()
                        + "' is unbuilt");
            }
            int colonTok = pos;
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
            props.add(new Protocol.PPurePropertyMapping(target, prop,
                    propSpan, body, id != null ? id : "",
                    spanOf(pS, pos - 1)));
            match(TokenType.COMMA);
        }
        int close = pos;
        expect(TokenType.BRACE_CLOSE);
        return new Protocol.PClassMappingPure(target, targetSpan, id, root,
                srcClass, srcSpan, props, spanOf(memberStart, close));
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
            String target, int memberStart, SourceInfo targetSpan) {
        expect(TokenType.BRACE_OPEN);
        expect(TokenType.ASSOCIATION_MAPPING);
        expect(TokenType.PAREN_OPEN);
        List<Protocol.PRelAssocPropertyMapping> props = new ArrayList<>();
        List<String> stores = new ArrayList<>();
        while (!atEnd() && peek() != TokenType.PAREN_CLOSE) {
            int pS = pos;
            String prop = parseIdentifier();
            SourceInfo propSpan = spanOf(pS, pos - 1);
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
                    op, spanOf(oS - 1, pos - 1)));
            match(TokenType.COMMA);
        }
        expect(TokenType.PAREN_CLOSE);
        int close = pos;
        expect(TokenType.BRACE_CLOSE);
        return new Protocol.PRelAssociationMapping(
                new Protocol.PPointer("ASSOCIATION", target, targetSpan),
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
                    propSpan, body, spanOf(pS, pos - 1)));
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
    private Protocol.PClassMappingOperation parseOperationClassMapping(
            String target, int memberStart, SourceInfo targetSpan,
            @com.legend.Nullable String id, boolean root) {
        expect(TokenType.BRACE_OPEN);
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
            default -> throw error("unsupported operation function: " + fqn);
        };
        expect(TokenType.PAREN_OPEN);
        List<String> params = new ArrayList<>();
        while (peek() != TokenType.PAREN_CLOSE && !atEnd()) {
            params.add(parseIdentifier());
            match(TokenType.COMMA);
        }
        expect(TokenType.PAREN_CLOSE);
        int close = pos;
        expect(TokenType.BRACE_CLOSE);
        return new Protocol.PClassMappingOperation(target, targetSpan, id,
                root, operation, params, spanOf(memberStart, close));
    }

    /** One {@code prop[srcId,tgtId]: [EnumerationMapping em:] <op>} line.
     *  ONE bracket id is the TARGET set (source stays the enclosing id);
     *  TWO are source,target (probe prop-set-ids). An inline
     *  {@code EnumerationMapping em:} rides enumMappingId (probe
     *  inline-enum-transform). */
    private void parsePropertyLine(List<Protocol.PRelPropertyMapping> props,
            String target, @com.legend.Nullable String id,
            @com.legend.Nullable String scopeDb,
            DatabaseProtocolParser.@com.legend.Nullable ScopeCtx scope) {
        int pS = pos;
        String prop = parseIdentifier();
        SourceInfo propSpan = spanOf(pS, pos - 1);
        String srcId = id;
        String tgtId = null;
        if (peek() == TokenType.BRACKET_OPEN) {
            advance();
            String first = parseIdentifier();
            if (peek() == TokenType.COMMA) {
                advance();
                srcId = first;
                tgtId = parseIdentifier();
            } else {
                tgtId = first;
            }
            expect(TokenType.BRACKET_CLOSE);
        }
        int colonTok = pos;
        expect(TokenType.COLON);
        String enumId = null;
        if (peek() == TokenType.ENUMERATION_MAPPING) {
            advance();                              // EnumerationMapping em:
            enumId = parseIdentifier();
            expect(TokenType.COLON);
        }
        Protocol.PRelOp op;
        if (scope != null) {
            int[] posOut = new int[1];
            op = DatabaseProtocolParser.scopedOperationAt(tokens, pos, scope,
                    posOut);
            pos = posOut[0];
        } else if (scopeDb != null) {
            int[] posOut = new int[1];
            op = DatabaseProtocolParser.operationAt(tokens, pos, scopeDb,
                    "default", posOut);
            pos = posOut[0];
        } else {
            op = parseEmbeddedOperation();
        }
        props.add(new Protocol.PRelPropertyMapping(target, prop, propSpan,
                enumId, op, srcId, tgtId, spanOf(colonTok, pos - 1)));
        match(TokenType.COMMA);
    }

    /** {@code scope([db]seg(.seg)?) ( prop-lines )} — the engine FLATTENS
     *  scope at parse: inner lines emit as plain property mappings whose
     *  table (when the header has one) spans the HEADER tokens (probe
     *  scope-forms). */
    private void parseScopeBlock(List<Protocol.PRelPropertyMapping> props,
            String target, @com.legend.Nullable String id) {
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
                        tokens.text(pos - 1), spanOf(g1, pos - 1));
            } else {
                scope = new DatabaseProtocolParser.ScopeCtx(db, "default",
                        seg1, spanOf(g1, g1));
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
        String id = parseIdentifier();
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
            return new Protocol.PEnumSourceValue(v);
        }
        if (peek() == TokenType.INTEGER) {
            long v = Long.parseLong(text());
            advance();
            return new Protocol.PEnumSourceValue(v);
        }
        throw error("unsupported enum source value: " + safeText());
    }
}
