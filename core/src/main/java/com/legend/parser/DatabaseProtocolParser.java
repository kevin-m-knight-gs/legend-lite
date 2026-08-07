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
 * PROTOCOL-path {@code ###Relational} Database parse (section-parity leg 3):
 * full span fidelity against ZRelationalProbe's oracle shapes. The
 * execution-path {@link RelationalGrammarParser} stays until execution
 * derives from protocol (Phase D settled the direction: {@code
 * com.legend.model} carries zero source positions, so a model&rarr;protocol
 * emitter is impossible — protocol is parsed, execution derives later).
 *
 * <p>Span rules probed, not guessed: a column node's span runs from ITS
 * table token to the end of the enclosing comparison's right operand (the
 * engine's ANTLR context spans); the implicit default schema takes the
 * whole database's span; {@code {target}} spells both table and alias of a
 * self-join column.
 */
public final class DatabaseProtocolParser implements TokenStreamCursor {

    private final TokenStream tokens;
    private int pos;
    private final String dbFqn;

    /** An active {@code scope([db]seg(.seg)?)} header (probe scope-forms):
     *  bare idents inside are columns of the HEADER table, whose pointer
     *  span points at the header tokens — the engine flattens scope at
     *  parse. Only headers WITH a table segment build one; db-only scopes
     *  just re-anchor {@code dbFqn}. */
    record ScopeCtx(String db, String schema, String table,
            SourceInfo tableSpan, boolean singleSeg) { }

    private final @com.legend.Nullable ScopeCtx scope;

    private DatabaseProtocolParser(TokenStream tokens, int pos, String dbFqn) {
        this(tokens, pos, dbFqn, null);
    }

    private DatabaseProtocolParser(TokenStream tokens, int pos, String dbFqn,
            @com.legend.Nullable ScopeCtx scope) {
        this.scope = scope;
        this.tokens = tokens;
        this.pos = pos;
        this.dbFqn = dbFqn;
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

    /** Relational identifiers admit QUOTED spellings, which KEEP their
     *  quotes as the wire name (probe: '"quoted col"'). */
    @Override
    public String parseIdentifier() {
        if (peek() == TokenType.QUOTED_STRING) {
            String raw = text();
            advance();
            return raw;
        }
        return TokenStreamCursor.super.parseIdentifier();
    }

    /** Package entry for OTHER section grammars (Mapping property
     *  mappings) that embed relational operations: parse one operation at
     *  {@code start}; the advanced position lands in {@code posOut[0]}. */
    static Protocol.PRelOp operationAt(TokenStream ts, int start,
            String dbFqn, String schemaCtx, int[] posOut) {
        DatabaseProtocolParser p = new DatabaseProtocolParser(ts, start, dbFqn);
        Protocol.PRelOp op = p.parseOperation(schemaCtx);
        posOut[0] = p.pos;
        return op;
    }

    /** Parse one {@code Database qn ( ... )} at {@code tokenIndex}. */
    public static Protocol.PDatabase parse(TokenStream ts, int tokenIndex) {
        DatabaseProtocolParser p = new DatabaseProtocolParser(ts, tokenIndex, "");
        return p.parseDatabase();
    }

    private Protocol.PDatabase parseDatabase() {
        int declStart = pos;
        expect(TokenType.DATABASE);
        Decorations dbDec = parseDecorations();
        String qn = Protocol.unquotePath(parseQualifiedName());
        int cut = qn.lastIndexOf("::");
        String pkg = cut < 0 ? "" : qn.substring(0, cut);
        String name = cut < 0 ? qn : qn.substring(cut + 2);
        DatabaseProtocolParser inner =
                new DatabaseProtocolParser(tokens, pos, qn);
        Protocol.PDatabase db = inner.parseBody(declStart, pkg, name,
                dbDec.stereotypes());
        this.pos = inner.pos;
        return db;
    }

    private Protocol.PDatabase parseBody(int declStart, String pkg, String name,
            List<Protocol.PStereotype> dbStereotypes) {
        expect(TokenType.PAREN_OPEN);
        List<Protocol.PPointer> includes = new ArrayList<>();
        List<Protocol.PDbTable> defaultTables = new ArrayList<>();
        List<Protocol.PDbView> defaultViews = new ArrayList<>();
        List<Protocol.PDbTable> defaultTabFns = new ArrayList<>();
        List<Protocol.PDbSchema> schemas = new ArrayList<>();
        List<Protocol.PDbJoin> joins = new ArrayList<>();
        List<Protocol.PDbFilter> filters = new ArrayList<>();
        while (!atEnd() && peek() != TokenType.PAREN_CLOSE) {
            switch (peek()) {
                case INCLUDE -> {
                    int s = pos;
                    advance();
                    String path = Protocol.unquotePath(parseQualifiedName());
                    includes.add(new Protocol.PPointer("STORE", path,
                            spanOf(s, pos - 1)));
                }
                case SCHEMA -> schemas.add(parseSchema());
                case TABLE -> defaultTables.add(parseTable());
                case VIEW -> defaultViews.add(parseView("default"));
                case VALID_STRING -> {
                    if (!"TabularFunction".equals(text())) {
                        throw error("unsupported Database member: '"
                                + safeText() + "'");
                    }
                    defaultTabFns.add(parseTabularFunction());
                }
                case JOIN -> {
                    int s = pos;
                    advance();
                    String jn = parseIdentifier();
                    expect(TokenType.PAREN_OPEN);
                    Protocol.PRelOp op = parseOperation("default");
                    expect(TokenType.PAREN_CLOSE);
                    joins.add(new Protocol.PDbJoin(jn, op, spanOf(s, pos - 1)));
                }
                case FILTER, MULTIGRAIN_FILTER -> {
                    int s = pos;
                    String ft = peek() == TokenType.MULTIGRAIN_FILTER
                            ? "multigrain" : "filter";
                    advance();
                    String fn = parseIdentifier();
                    expect(TokenType.PAREN_OPEN);
                    Protocol.PRelOp op = parseOperation("default");
                    expect(TokenType.PAREN_CLOSE);
                    filters.add(new Protocol.PDbFilter(ft, fn, op,
                            spanOf(s, pos - 1)));
                }
                default -> throw error("unsupported Database member: '"
                        + safeText() + "'");
            }
        }
        int close = pos;
        expect(TokenType.PAREN_CLOSE);
        SourceInfo dbSpan = spanOf(declStart, close);
        if (!defaultTables.isEmpty() || !defaultViews.isEmpty()
                || !defaultTabFns.isEmpty()) {
            // the implicit default schema takes the WHOLE database span and
            // sorts AFTER named schemas (engine walker append order)
            schemas.add(new Protocol.PDbSchema("default", defaultTables,
                    defaultViews, defaultTabFns, List.of(), List.of(), dbSpan));
        }
        return new Protocol.PDatabase(pkg, name, includes, schemas, joins,
                filters, dbStereotypes, dbSpan);
    }

    private record Decorations(List<Protocol.PStereotype> stereotypes,
            List<Protocol.PTaggedValue> taggedValues) {
    }

    /** {@code <<p::P.v, ...>>} and/or {@code {p::P.t = 'v', ...}} before a
     *  schema/table/view name — VALUE-only stereotype spans (probe
     *  decorated-schema-table). */
    private Decorations parseDecorations() {
        List<Protocol.PStereotype> stereos = new ArrayList<>();
        List<Protocol.PTaggedValue> tags = new ArrayList<>();
        if (peek() == TokenType.LESS_THAN) {
            advance();
            expect(TokenType.LESS_THAN);
            while (!atEnd() && peek() != TokenType.GREATER_THAN) {
                int pS = pos;
                String profile = Protocol.unquotePath(parseQualifiedName());
                SourceInfo pSpan = spanOf(pS, pos - 1);
                expect(TokenType.DOT);
                int vS = pos;
                String value = parseIdentifier();
                stereos.add(new Protocol.PStereotype(profile, value, pSpan,
                        spanOf(vS, vS)));
                match(TokenType.COMMA);
            }
            expect(TokenType.GREATER_THAN);
            expect(TokenType.GREATER_THAN);
        }
        if (peek() == TokenType.BRACE_OPEN) {
            advance();
            while (!atEnd() && peek() != TokenType.BRACE_CLOSE) {
                int tS = pos;
                String profile = Protocol.unquotePath(parseQualifiedName());
                SourceInfo pSpan = spanOf(tS, pos - 1);
                expect(TokenType.DOT);
                int vS = pos;
                String tagName = parseIdentifier();
                expect(TokenType.EQUAL);
                String quoted = text();
                expect(TokenType.STRING);
                String value = TokenStreamCursor.unquoteAndUnescape(quoted, this);
                tags.add(new Protocol.PTaggedValue(
                        new Protocol.PTag(profile, tagName, pSpan,
                                spanOf(vS, vS)),
                        value, spanOf(tS, pos - 1)));
                match(TokenType.COMMA);
            }
            expect(TokenType.BRACE_CLOSE);
        }
        return new Decorations(stereos, tags);
    }

    private Protocol.PDbSchema parseSchema() {
        int s = pos;
        expect(TokenType.SCHEMA);
        Decorations dec = parseDecorations();
        String name = parseIdentifier();
        expect(TokenType.PAREN_OPEN);
        List<Protocol.PDbTable> tables = new ArrayList<>();
        List<Protocol.PDbView> views = new ArrayList<>();
        List<Protocol.PDbTable> tabFns = new ArrayList<>();
        while (!atEnd() && peek() != TokenType.PAREN_CLOSE) {
            if (peek() == TokenType.TABLE) {
                tables.add(parseTable());
            } else if (peek() == TokenType.VIEW) {
                views.add(parseView(name));
            } else if (peek() == TokenType.VALID_STRING
                    && "TabularFunction".equals(text())) {
                tabFns.add(parseTabularFunction());
            } else {
                throw error("unsupported Schema member: '" + safeText() + "'");
            }
        }
        expect(TokenType.PAREN_CLOSE);
        return new Protocol.PDbSchema(name, tables, views, tabFns,
                dec.stereotypes(), dec.taggedValues(), spanOf(s, pos - 1));
    }

    /** {@code TabularFunction TF ( cols )} — slim table shape on the wire
     *  (columns + name + span; probe snapshot-quoted-tabfunc). */
    private Protocol.PDbTable parseTabularFunction() {
        int s = pos;
        advance();                                  // 'TabularFunction'
        String name = parseIdentifier();
        expect(TokenType.PAREN_OPEN);
        List<Protocol.PDbColumn> columns = new ArrayList<>();
        while (!atEnd() && peek() != TokenType.PAREN_CLOSE) {
            int cS = pos;
            String colName = parseIdentifier();
            Protocol.PDbType type = parseDbType();
            boolean nullable = true;
            while (peek() == TokenType.PRIMARY_KEY
                    || peek() == TokenType.NOT_NULL) {
                nullable = false;
                advance();
            }
            columns.add(new Protocol.PDbColumn(colName, nullable, type,
                    List.of(), List.of(), spanOf(cS, pos - 1)));
            match(TokenType.COMMA);
        }
        expect(TokenType.PAREN_CLOSE);
        return new Protocol.PDbTable(name, columns, List.of(), List.of(),
                List.of(), List.of(), spanOf(s, pos - 1));
    }

    private Protocol.PDbTable parseTable() {
        int s = pos;
        expect(TokenType.TABLE);
        Decorations dec = parseDecorations();
        String name = parseIdentifier();
        expect(TokenType.PAREN_OPEN);
        List<Protocol.PDbColumn> columns = new ArrayList<>();
        List<Protocol.PMilestoning> milestoning = new ArrayList<>();
        List<String> primaryKey = new ArrayList<>();
        while (!atEnd() && peek() != TokenType.PAREN_CLOSE) {
            if (peek() == TokenType.VALID_STRING
                    && "milestoning".equals(text())) {
                advance();
                parseMilestoning(milestoning);
                continue;
            }
            int cS = pos;
            String colName = parseIdentifier();
            // columns take stereotypes AND tagged values before the type
            // (probes column-stereotypes + db-and-column-decorations)
            Decorations colDec = parseDecorations();
            Protocol.PDbType type = parseDbType();
            boolean nullable = true;
            // PRIMARY KEY / NOT NULL lex as single tokens
            while (peek() == TokenType.PRIMARY_KEY
                    || peek() == TokenType.NOT_NULL) {
                if (peek() == TokenType.PRIMARY_KEY) {
                    primaryKey.add(colName);
                }
                nullable = false;
                advance();
            }
            columns.add(new Protocol.PDbColumn(colName, nullable,
                    type, colDec.stereotypes(), colDec.taggedValues(),
                    spanOf(cS, pos - 1)));
            match(TokenType.COMMA);
        }
        expect(TokenType.PAREN_CLOSE);
        return new Protocol.PDbTable(name, columns, milestoning, primaryKey,
                dec.stereotypes(), dec.taggedValues(), spanOf(s, pos - 1));
    }

    /** Datatype spelling &rarr; wire {@code _type} (probe TYPES): sized,
     *  precision/scale and plain kinds; unknown kinds refuse loudly. */
    private Protocol.PDbType parseDbType() {
        String kindWord = parseIdentifier().toUpperCase(java.util.Locale.ROOT);
        Long size = null;
        Long precision = null;
        Long scale = null;
        String kind = switch (kindWord) {
            case "INTEGER", "INT" -> "Integer";
            case "BIGINT" -> "BigInt";
            case "SMALLINT" -> "SmallInt";
            case "TINYINT" -> "TinyInt";
            case "DOUBLE" -> "Double";
            case "FLOAT" -> "Float";
            case "REAL" -> "Real";
            case "BIT" -> "Bit";
            case "DATE" -> "Date";
            case "TIMESTAMP" -> "Timestamp";
            case "SEMISTRUCTURED" -> "SemiStructured";
            case "JSON" -> "Json";
            case "OTHER" -> "Other";
            case "ARRAY" -> "Array";
            case "VARCHAR" -> "Varchar";
            case "CHAR" -> "Char";
            case "BINARY" -> "Binary";
            case "VARBINARY" -> "Varbinary";
            case "DECIMAL" -> "Decimal";
            case "NUMERIC" -> "Numeric";
            default -> throw error("unsupported column datatype: " + kindWord);
        };
        boolean sized = kind.equals("Varchar") || kind.equals("Char")
                || kind.equals("Binary") || kind.equals("Varbinary");
        boolean precise = kind.equals("Decimal") || kind.equals("Numeric");
        if ((sized || precise) && peek() == TokenType.PAREN_OPEN) {
            advance();
            long first = Long.parseLong(text());
            expect(TokenType.INTEGER);
            if (precise) {
                precision = first;
                expect(TokenType.COMMA);
                scale = Long.parseLong(text());
                expect(TokenType.INTEGER);
            } else {
                size = first;
            }
            expect(TokenType.PAREN_CLOSE);
        }
        return new Protocol.PDbType(kind, size, precision, scale);
    }

    /** ENGINE QUIRK (probed + walker-read): each dimension reparses through
     *  an offset anchored at ITSELF, and the engine double-adds the 1-based
     *  anchor column — net {@code +1} on BOTH span columns. Business
     *  (fromThru) spans its ARGS context; processing spans the WHOLE
     *  dimension (MilestoningParseTreeWalker branch difference). */
    private void parseMilestoning(List<Protocol.PMilestoning> out) {
        expect(TokenType.PAREN_OPEN);
        while (!atEnd() && peek() != TokenType.PAREN_CLOSE) {
            int s = pos;
            String kind = parseIdentifier();
            expect(TokenType.PAREN_OPEN);
            int argsStart = pos;
            java.util.Map<String, String> kv = new java.util.LinkedHashMap<>();
            Protocol.PDateTimeLit infinity = null;
            while (!atEnd() && peek() != TokenType.PAREN_CLOSE) {
                String key = parseIdentifier();
                expect(TokenType.EQUAL);
                if (peek() == TokenType.DATE) {
                    int dS = pos;
                    String raw = text();
                    advance();
                    infinity = new Protocol.PDateTimeLit(
                            raw.substring(1), plusOneCols(spanOf(dS, dS)));
                } else {
                    kv.put(key, safeText());
                    advance();
                }
                match(TokenType.COMMA);
            }
            int argsEnd = pos - 1;                  // last token inside ( )
            expect(TokenType.PAREN_CLOSE);
            // business: ARGS span; processing: WHOLE dimension span —
            // both with the +1-column reparse quirk
            boolean snapshot = kv.containsKey("BUS_SNAPSHOT_DATE")
                    || kv.containsKey("PROCESSING_SNAPSHOT_DATE");
            // fromThru-business and BOTH snapshot variants span their ARGS
            // context; plain processing spans the whole dimension
            SourceInfo span = "business".equals(kind) || snapshot
                    ? plusOneCols(spanOf(argsStart, argsEnd))
                    : plusOneCols(spanOf(s, pos - 1));
            match(TokenType.COMMA);
            switch (kind) {
                case "business" -> {
                    if (kv.containsKey("BUS_SNAPSHOT_DATE")) {
                        out.add(new Protocol.PBusinessSnapshotMilestoning(
                                req(kv, "BUS_SNAPSHOT_DATE"), span));
                        continue;
                    }
                    out.add(new Protocol.PBusinessMilestoning(
                        req(kv, "BUS_FROM"), req(kv, "BUS_THRU"),
                        Boolean.parseBoolean(kv.getOrDefault(
                                "THRU_IS_INCLUSIVE", "false")),
                        infinity, span));
                }
                case "processing" -> {
                    if (kv.containsKey("PROCESSING_SNAPSHOT_DATE")) {
                        out.add(new Protocol.PProcessingSnapshotMilestoning(
                                req(kv, "PROCESSING_SNAPSHOT_DATE"), span));
                        continue;
                    }
                    out.add(new Protocol.PProcessingMilestoning(
                        req(kv, "PROCESSING_IN"), req(kv, "PROCESSING_OUT"),
                        Boolean.parseBoolean(kv.getOrDefault(
                                "OUT_IS_INCLUSIVE", "false")),
                        infinity, span));
                }
                default -> throw error("unsupported milestoning kind: " + kind);
            }
        }
        expect(TokenType.PAREN_CLOSE);
    }

    private static SourceInfo plusOneCols(SourceInfo sp) {
        return new SourceInfo("", sp.startLine(), sp.startColumn() + 1,
                sp.endLine(), sp.endColumn() + 1);
    }

    private String req(java.util.Map<String, String> kv, String key) {
        String v = kv.get(key);
        if (v == null) {
            throw error("milestoning needs " + key);
        }
        return v;
    }

    private Protocol.PDbView parseView(String schemaCtx) {
        int s = pos;
        expect(TokenType.VIEW);
        Decorations dec = parseDecorations();
        String name = parseIdentifier();
        expect(TokenType.PAREN_OPEN);
        boolean distinct = false;
        Protocol.PViewFilter filter = null;
        List<Protocol.PRelOp> groupBy = null;
        List<Protocol.PViewColumnMapping> mappings = new ArrayList<>();
        List<String> primaryKey = new ArrayList<>();
        while (!atEnd() && peek() != TokenType.PAREN_CLOSE) {
            if (peek() == TokenType.DISTINCT_CMD) {
                advance();
                distinct = true;
                continue;
            }
            if (peek() == TokenType.FILTER_CMD) {
                int tS = pos;
                advance();
                if (peek() == TokenType.BRACKET_OPEN) {
                    advance();                      // [db] filter pointer
                    parseQualifiedName();
                    expect(TokenType.BRACKET_CLOSE);
                }
                String fn = parseIdentifier();
                filter = new Protocol.PViewFilter(fn, spanOf(tS, pos - 1));
                continue;
            }
            if (peek() == TokenType.GROUP_BY_CMD) {
                advance();
                groupBy = new ArrayList<>();
                expect(TokenType.PAREN_OPEN);
                while (peek() != TokenType.PAREN_CLOSE && !atEnd()) {
                    groupBy.add(parseOperation(schemaCtx));
                    match(TokenType.COMMA);
                }
                expect(TokenType.PAREN_CLOSE);
                continue;
            }
            int mS = pos;
            String colName = parseIdentifier();
            expect(TokenType.COLON);
            Protocol.PRelOp op = parseOperation(schemaCtx);
            if (peek() == TokenType.PRIMARY_KEY) {
                advance();
                primaryKey.add(colName);
            }
            mappings.add(new Protocol.PViewColumnMapping(colName, op,
                    spanOf(mS, pos - 1)));
            match(TokenType.COMMA);
        }
        expect(TokenType.PAREN_CLOSE);
        return new Protocol.PDbView(name, mappings, dec.stereotypes(),
                dec.taggedValues(), distinct, filter, groupBy,
                primaryKey, spanOf(s, pos - 1));
    }

    // ------------------------------------------------------------------
    // Operation expressions: or > and > comparison > atom
    // ------------------------------------------------------------------

    /** OPERATOR-node spans run the OPERATOR TOKEN to the right operand's
     *  end. and/or carry NO precedence (engine booleanOperation is
     *  RIGHT-recursive): {@code x and y or z} is {@code and(x, or(y,z))};
     *  CONSECUTIVE same-ops flatten n-ary with the FIRST operator's
     *  anchor. Parens wrap as a {@code group} dynaFunc. */
    Protocol.PRelOp parseOperation(String schemaCtx) {
        Protocol.PRelOp left = parseComparison(schemaCtx);
        String op = boolOpHere();
        if (op == null) {
            return left;
        }
        int opTok = pos;
        advance();
        Protocol.PRelOp rest = parseOperation(schemaCtx);
        SourceInfo span = spanOf(opTok, pos - 1);
        if (rest instanceof Protocol.PDynaFunc df && df.funcName().equals(op)) {
            List<Protocol.PRelOp> params = new ArrayList<>();
            params.add(left);
            params.addAll(df.parameters());
            return new Protocol.PDynaFunc(op, params, span);
        }
        if (rest instanceof Protocol.PDynaFunc df
                && ("and".equals(df.funcName()) || "or".equals(df.funcName()))) {
            // a DIFFERENT-op boolean right side wraps in group (engine's
            // right-recursion goes through the group rule)
            rest = new Protocol.PDynaFunc("group", List.of(rest),
                    df.sourceInformation());
        }
        return new Protocol.PDynaFunc(op, List.of(left, rest), span);
    }

    private @com.legend.Nullable String boolOpHere() {
        if (peek() == TokenType.RELATIONAL_AND
                || (peek() == TokenType.VALID_STRING && "and".equals(text()))) {
            return "and";
        }
        if (peek() == TokenType.RELATIONAL_OR
                || (peek() == TokenType.VALID_STRING && "or".equals(text()))) {
            return "or";
        }
        return null;
    }

    private Protocol.PRelOp parseComparison(String schemaCtx) {
        Protocol.PRelOp left = parseAtom(schemaCtx);
        // postfix null tests bind tighter than comparisons
        while (peek() == TokenType.IS_NULL || peek() == TokenType.IS_NOT_NULL) {
            String nf = peek() == TokenType.IS_NULL ? "isNull" : "isNotNull";
            int nTok = pos;
            advance();
            SourceInfo opSpan = spanOf(nTok, nTok);
            // the operand's context swallows the postfix operator (probe
            // null-postfix: column 14..24 under 'is null' at 18..24)
            left = new Protocol.PDynaFunc(nf,
                    List.of(withSpanEnd(left, opSpan)), opSpan);
        }
        String fn = switch (peek()) {
            case EQUAL -> "equal";
            case NOT_EQUAL -> "notEqualAnsi";   // <> is the ANSI spelling
            case TEST_NOT_EQUAL -> "notEqual";
            case GREATER_THAN -> "greaterThan";
            case GREATER_OR_EQUAL -> "greaterThanEqual";
            case LESS_THAN -> "lessThan";
            case LESS_OR_EQUAL -> "lessThanEqual";
            default -> null;
        };
        if (fn == null) {
            return left;
        }
        int opTok = pos;
        advance();
        // comparisons CHAIN right-recursively (probe chained-eq:
        // a = b = c is equal(a, equal(b, c)) with the same swallow)
        Protocol.PRelOp right = parseComparison(schemaCtx);
        SourceInfo compSpan = spanOf(opTok, pos - 1);
        // ENGINE QUIRK (probed): the LEFT operand's context swallows the
        // operator and right operand — its span END is the comparison's
        // end; the comparison NODE spans operator..right
        left = withSpanEnd(left, compSpan);
        return new Protocol.PDynaFunc(fn, List.of(left, right), compSpan);
    }

    /** One operation under an active {@code scope(...)} header with a
     *  table segment (probe scope-forms). */
    static Protocol.PRelOp scopedOperationAt(TokenStream ts, int start,
            ScopeCtx scope, int[] posOut) {
        DatabaseProtocolParser p = new DatabaseProtocolParser(ts, start,
                scope.db(), scope);
        Protocol.PRelOp op = p.parseOperation("default");
        posOut[0] = p.pos;
        return op;
    }

    /** {@code null} when parsing OUTSIDE any database (mapping-embedded
     *  bare refs) — the wire then omits both db keys (probe bare-no-db). */
    private @com.legend.Nullable String dbOrNull() {
        return dbFqn.isEmpty() ? null : dbFqn;
    }

    /** An atom resolved under {@code db} — a bracket-nav element inherits
     *  the bracket's db even when the ambient parse has none. */
    private Protocol.PRelOp atomUnder(String db, String schemaCtx) {
        if (db.equals(dbFqn)) {
            return parseAtom(schemaCtx);
        }
        DatabaseProtocolParser p =
                new DatabaseProtocolParser(tokens, pos, db, scope);
        Protocol.PRelOp e = p.parseAtom(schemaCtx);
        this.pos = p.pos;
        return e;
    }

    /** {@code true} when the {@code [} opens a db pointer followed by an
     *  {@code @}-navigation ({@code [db]@J} — association sides). */
    private boolean bracketLeadsToAt() {
        int i = pos + 1;
        int depth = 1;
        while (i < tokens.count() && depth > 0) {
            TokenType t = tokens.type(i);
            if (t == TokenType.BRACKET_OPEN) {
                depth++;
            } else if (t == TokenType.BRACKET_CLOSE) {
                depth--;
            }
            i++;
        }
        return i < tokens.count() && tokens.type(i) == TokenType.AT;
    }

    /** {@code @J [> (TYPE) @K]* [| element]} — a join-only nav
     *  (association sides) has NO element. */
    private Protocol.PRelOp parseJoinNav(String db, String schemaCtx,
            int s) {
        List<Protocol.PJoinPtr> joinPtrs = new ArrayList<>();
        String curDb = db;
        String pendingType = null;
        while (peek() == TokenType.AT) {
            int jS = pos;                           // span INCLUDES the '@'
            advance();
            String jn = parseIdentifier();
            joinPtrs.add(new Protocol.PJoinPtr(
                    curDb.isEmpty() ? null : curDb, pendingType, jn,
                    spanOf(jS, pos - 1)));
            pendingType = null;
            if (peek() == TokenType.GREATER_THAN) {
                advance();
                if (peek() == TokenType.PAREN_OPEN) {
                    // > (INNER) @Next — the TYPE rides the NEXT pointer
                    advance();
                    pendingType = parseIdentifier();
                    expect(TokenType.PAREN_CLOSE);
                }
                if (peek() == TokenType.BRACKET_OPEN) {
                    // > [db2] @Next — re-anchors the NEXT pointer's db;
                    // spans stay AT the '@' (probe nav-chain-db-each-step)
                    advance();
                    curDb = Protocol.unquotePath(parseQualifiedName());
                    expect(TokenType.BRACKET_CLOSE);
                }
            } else {
                break;
            }
        }
        Protocol.PRelOp elem = null;
        if (peek() == TokenType.PIPE) {
            advance();
            // the element resolves under the LAST join's (re-anchored) db
            elem = atomUnder(curDb, schemaCtx);
            if (!"default".equals(schemaCtx)) {
                // engine resolves non-default-schema nav tables against
                // their DECLARATION (probe nav-spacing) — unbuilt
                throw error("join-navigation inside schema '" + schemaCtx
                        + "' needs declaration-site span resolution (unbuilt)");
            }
        }
        return new Protocol.PElemtWithJoins(joinPtrs, elem,
                spanOf(s, pos - 1));
    }

    /** The node with its span's END replaced by {@code end}'s end. */
    private static Protocol.PRelOp withSpanEnd(Protocol.PRelOp op,
            SourceInfo end) {
        return switch (op) {
            case Protocol.PDynaFunc f -> new Protocol.PDynaFunc(f.funcName(),
                    f.parameters(), stretch(f.sourceInformation(), end));
            case Protocol.PColumnRef c -> new Protocol.PColumnRef(c.column(),
                    c.table(), c.tableAlias(),
                    stretch(c.sourceInformation(), end));
            case Protocol.PRelLiteral l -> new Protocol.PRelLiteral(l.value(),
                    stretch(l.sourceInformation(), end));
            case Protocol.PElemtWithJoins ej -> ej;   // nav spans stay put
            case Protocol.PRelLiteralList ll -> new Protocol.PRelLiteralList(
                    ll.values(), stretch(ll.sourceInformation(), end));
        };
    }

    private static SourceInfo stretch(SourceInfo base, SourceInfo end) {
        return new SourceInfo("", base.startLine(), base.startColumn(),
                end.endLine(), end.endColumn());
    }

    /** {@code [} opens either a db-qualifier or a literal list — a literal
     *  or string first token means list. */
    private boolean looksLikeLiteralList() {
        TokenType n = peek(1);
        return n == TokenType.INTEGER || n == TokenType.FLOAT
                || n == TokenType.STRING;
    }

    private Protocol.PRelOp parseAtom(String schemaCtx) {
        int s = pos;
        if (peek() == TokenType.PAREN_OPEN) {
            int gS = pos;
            advance();
            Protocol.PRelOp inner = parseOperation(schemaCtx);
            expect(TokenType.PAREN_CLOSE);
            // parenthesized subexpressions WRAP as a group dynaFunc
            return new Protocol.PDynaFunc("group", List.of(inner),
                    spanOf(gS, pos - 1));
        }
        if (peek() == TokenType.STRING) {
            String quoted = text();
            advance();
            return new Protocol.PRelLiteral(
                    TokenStreamCursor.unquoteAndUnescape(quoted, this),
                    spanOf(s, s));
        }
        if (peek() == TokenType.MINUS) {
            advance();
            int numTok = pos;
            if (peek() == TokenType.INTEGER) {
                long v = -Long.parseLong(text());
                advance();
                return new Protocol.PRelLiteral(v, spanOf(s, numTok));
            }
            double v = -Double.parseDouble(text());
            expect(TokenType.FLOAT);
            return new Protocol.PRelLiteral(v, spanOf(s, numTok));
        }
        if (peek() == TokenType.INTEGER) {
            long v = Long.parseLong(text());
            advance();
            return new Protocol.PRelLiteral(v, spanOf(s, s));
        }
        if (peek() == TokenType.FLOAT) {
            double v = Double.parseDouble(text());
            advance();
            return new Protocol.PRelLiteral(v, spanOf(s, s));
        }
        if (peek() == TokenType.TARGET) {
            // {target} — lexes as ONE token; spells BOTH table and alias
            advance();
            expect(TokenType.DOT);
            String col = parseIdentifier();
            SourceInfo span = spanOf(s, pos - 1);
            return new Protocol.PColumnRef(col, new Protocol.PTablePtr(
                    dbOrNull(), dbOrNull(), schemaCtx, "{target}",
                    spanOf(s, s)), "{target}",
                    span);
        }
        if (peek() == TokenType.BRACKET_OPEN && !looksLikeLiteralList()
                && bracketLeadsToAt()) {
            // [db]@Join — bracket-qualified join nav; span INCLUDES the db
            // pointer (probe assoc-spans E=16 at the '[')
            int bS = pos;
            advance();
            String db = Protocol.unquotePath(parseQualifiedName());
            expect(TokenType.BRACKET_CLOSE);
            return parseJoinNav(db, schemaCtx, bS);
        }
        if (peek() == TokenType.BRACKET_OPEN && !looksLikeLiteralList()) {
            // [db]table.col — bracket-qualified ref: the bracket db becomes
            // the pointer's database; ptr srcInfo = TABLE token only,
            // column span starts at the BRACKET (probe db-qualified)
            advance();
            String db = Protocol.unquotePath(parseQualifiedName());
            expect(TokenType.BRACKET_CLOSE);
            if (peek() == TokenType.VALID_STRING
                    && peek(1) == TokenType.PAREN_OPEN) {
                // [db] add(...) — a dynaFunc under the bracket db; the
                // OUTER op's span starts at the bracket (probe
                // db-dynafunc)
                DatabaseProtocolParser p =
                        new DatabaseProtocolParser(tokens, pos, db, scope);
                Protocol.PDynaFunc f =
                        (Protocol.PDynaFunc) p.parseAtom(schemaCtx);
                this.pos = p.pos;
                SourceInfo br = spanOf(s, s);
                return new Protocol.PDynaFunc(f.funcName(), f.parameters(),
                        new SourceInfo("", br.startLine(),
                                br.startColumn(),
                                f.sourceInformation().endLine(),
                                f.sourceInformation().endColumn()));
            }
            if (scope != null && peek() == TokenType.VALID_STRING
                    && peek(1) != TokenType.DOT) {
                // [db] BARECOL under a scope header — column of the
                // HEADER table; span bracket..ident (probe db-dynafunc)
                String col = parseIdentifier();
                return new Protocol.PColumnRef(col, new Protocol.PTablePtr(
                        db, db, scope.schema(), scope.table(),
                        scope.tableSpan()), scope.table(),
                        spanOf(s, pos - 1));
            }
            int firstTok = pos;                     // FIRST ident anchors
            int tblEndTok = pos;
            String schema = "default";
            String table = parseIdentifier();
            expect(TokenType.DOT);
            String column = parseIdentifier();
            if (peek() == TokenType.DOT) {
                advance();                          // [db]schema.table.col
                schema = table;
                table = column;
                tblEndTok = pos - 2;
                column = parseIdentifier();
            }
            return new Protocol.PColumnRef(column, new Protocol.PTablePtr(db,
                    db, schema, table, spanOf(firstTok, tblEndTok)), table,
                    spanOf(s, pos - 1));
        }
        if (peek() == TokenType.BRACKET_OPEN && looksLikeLiteralList()) {
            int lS = pos;
            advance();
            List<Protocol.PRelLiteral> items = new ArrayList<>();
            while (peek() != TokenType.BRACKET_CLOSE && !atEnd()) {
                int iS = pos;
                Object v;
                if (peek() == TokenType.STRING) {
                    v = TokenStreamCursor.unquoteAndUnescape(text(), this);
                } else if (peek() == TokenType.INTEGER) {
                    v = Long.parseLong(text());
                } else if (peek() == TokenType.FLOAT) {
                    v = Double.parseDouble(text());
                } else {
                    throw error("unsupported literal-list element: "
                            + safeText());
                }
                advance();
                items.add(new Protocol.PRelLiteral(v, spanOf(iS, iS)));
                match(TokenType.COMMA);
            }
            expect(TokenType.BRACKET_CLOSE);
            return new Protocol.PRelLiteralList(items, spanOf(lS, pos - 1));
        }
        if (peek() == TokenType.AT) {
            return parseJoinNav(dbFqn, schemaCtx, pos);
        }
        // identifier: function call, or dotted column ref
        String first = parseIdentifier();
        if (scope != null && peek() != TokenType.PAREN_OPEN
                && peek() != TokenType.DOT) {
            // bare ident under scope = column of the HEADER table; the
            // pointer span points AT THE HEADER tokens (probe scope-forms)
            return new Protocol.PColumnRef(first, new Protocol.PTablePtr(
                    dbFqn, dbFqn, scope.schema(), scope.table(),
                    scope.tableSpan()), scope.table(), spanOf(s, s));
        }
        if (peek() == TokenType.PAREN_OPEN) {
            advance();
            List<Protocol.PRelOp> args = new ArrayList<>();
            while (peek() != TokenType.PAREN_CLOSE && !atEnd()) {
                args.add(parseOperation(schemaCtx));
                match(TokenType.COMMA);
            }
            expect(TokenType.PAREN_CLOSE);
            return new Protocol.PDynaFunc(first, args, spanOf(s, pos - 1));
        }
        // A.col | S.T.col — schema-qualified when two dots; the TABLE
        // pointer's span runs through the TABLE token (probe: 'S.T1' 11-14)

        expect(TokenType.DOT);
        int tblEnd = pos - 2;                       // the first identifier
        String second = parseIdentifier();
        String schema = schemaCtx;
        String table = first;
        String column = second;
        boolean explicitSchema = false;
        if (peek() == TokenType.DOT) {
            advance();
            schema = first;
            table = second;
            tblEnd = pos - 2;                       // through the table ident
            column = parseIdentifier();
            explicitSchema = true;
        }
        if (scope != null && scope.singleSeg() && !explicitSchema) {
            // a SINGLE-segment header is a SCHEMA prefix for dotted refs
            // (probe single-seg-scope-dotted): schema = the header seg,
            // span stretches header..local table
            schema = scope.table();
        }
        SourceInfo span = spanOf(s, pos - 1);
        SourceInfo tblSpan = spanOf(s, tblEnd);
        if (scope != null && !explicitSchema) {
            // under a schema.table scope header, a dotted ref's TABLE
            // pointer span STRETCHES from the header's schema through the
            // LOCAL table token (probe scope-dotted w; chainedJoinsInner)
            tblSpan = new SourceInfo("", scope.tableSpan().startLine(),
                    scope.tableSpan().startColumn(), tblSpan.endLine(),
                    tblSpan.endColumn());
        }
        return new Protocol.PColumnRef(column, new Protocol.PTablePtr(
                dbOrNull(), dbOrNull(), schema, table, tblSpan),
                table, span);
    }
}
