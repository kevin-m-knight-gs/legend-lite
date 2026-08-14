// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.parser.section;

import com.legend.lexer.TokenType;
import com.legend.parser.TokenStreamCursor;
import com.legend.protocol.Protocol;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The SHARED grammar for the FUNCTION-ACTIVATOR sections — Snowflake
 * (SnowflakeApp / SnowflakeM2MUdf), MemSql (MemSqlFunction), BigQuery
 * (BigQueryFunction), HostedService, FunctionJar. One uniform body shape:
 * typed scalar/boolean keys, a {@code function:} pointer keeping its full
 * signature text and span, {@code ownership: Deployment { identifier }},
 * and an optional {@code activationConfiguration:} connection pointer.
 * Wire shapes probed byte-for-byte (ZTailProbe "activatorShapes" /
 * "activatorShapes2").
 */
public final class FunctionActivatorSectionGrammar
        implements LexableSectionGrammar {

    private static final Set<String> STRING_KEYS = Set.of("applicationName",
            "udfName", "functionName", "description", "documentation",
            "pattern", "deploymentSchema", "deploymentStage", "usageRole");
    private static final Set<String> BOOLEAN_KEYS = Set.of(
            "autoActivateUpdates", "generateLineage", "storeModel");

    private final String section;
    private final Set<String> kinds;

    public FunctionActivatorSectionGrammar(String section, Set<String> kinds) {
        this.section = section;
        this.kinds = kinds;
    }

    /** The censused activator kinds this section admits — read by
     *  PmcdParser's registry-routed dispatch (SectionCatalog step: the
     *  document surface previously merged ALL kind sets, accepting
     *  MemSqlFunction inside ###Snowflake — adversarial audit #18). */
    public Set<String> kinds() {
        return kinds;
    }

    @Override
    public String name() {
        return section;
    }

    @Override
    public void parse(com.legend.spi.SectionSource src,
            com.legend.spi.ElementSink out) {
        var c = new SliceCursor(com.legend.lexer.Lexer.tokenize(src.text()),
                // SPI overlay hosting = the drop-in seam: engine grammar
                com.legend.parser.Dialect.LEGEND_ENGINE);
        while (!c.atEnd()) {
            if (c.peek() == TokenType.IMPORT) {
                SectionImports.parseImport(c);
                continue;
            }
            Protocol.PFunctionActivator a = parseElement(c);
            out.accept(a.qualifiedName(),
                    com.legend.protocol.ProtocolEmitter.emitElement(a));
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
        Protocol.PFunctionActivator a = (Protocol.PFunctionActivator) element;
        java.util.Map<String, String> fields = new java.util.LinkedHashMap<>(
                a.scalars());
        fields.put("function", a.functionPath());
        if (a.ownerId() != null) {
            fields.put("ownership", "Deployment " + a.ownerId());
        }
        if (a.userListUsers() != null) {
            fields.put("ownership",
                    "UserList " + String.join(",", a.userListUsers()));
        }
        if (a.activationConnection() != null) {
            fields.put("activationConfiguration", a.activationConnection());
        }
        return new com.legend.model.SnowflakeActivatorDefinition(
                a.qualifiedName(), a.kind(), fields);
    }

    /** One {@code Kind <<dec>> qn { key: value; ... }} activator. */
    public Protocol.PFunctionActivator parseElement(TokenStreamCursor c) {
        int declStart = c.pos();
        if (!c.isIdentifierToken(c.peek())) {
            throw c.error("unsupported ###" + section + " element: "
                    + c.safeText());
        }
        String kind = c.safeText();
        if (!kinds.contains(kind)) {
            throw c.error("unsupported ###" + section + " element: " + kind);
        }
        c.advance();
        TokenStreamCursor.Decorations dec = c.parseDecorations();
        String qn = Protocol.unquotePath(c.parseQualifiedName());
        int cut = qn.lastIndexOf("::");
        String pkg = cut < 0 ? "" : qn.substring(0, cut);
        String name = cut < 0 ? qn : qn.substring(cut + 2);
        c.expect(TokenType.BRACE_OPEN);

        Map<String, String> scalars = new java.util.LinkedHashMap<>();
        Map<String, Boolean> booleans = new java.util.LinkedHashMap<>();
        String functionPath = null;
        com.legend.protocol.SourceInfo functionSpan = null;
        String ownerId = null;
        List<String> userListUsers = null;
        String actConn = null;
        com.legend.protocol.SourceInfo actConnSpan = null;

        java.util.Set<String> seenKeys = new java.util.HashSet<>();
        while (!c.atEnd() && c.peek() != TokenType.BRACE_CLOSE) {
            String key = c.parseIdentifier();
            TokenStreamCursor.once(seenKeys, key, c);
            c.expect(TokenType.COLON);
            if (STRING_KEYS.contains(key)) {
                scalars.put(key, SectionParse.stringValue(c));
            } else if (BOOLEAN_KEYS.contains(key)) {
                booleans.put(key, SectionParse.booleanValue(c));
            } else if ("function".equals(key)) {
                // the SHARED pointer-site scan (TokenStreamCursor
                // #parseFunctionDescriptor); this site renders the raw
                // SPELLING (reconstructText), unquoting only the fqn
                var fd = c.parseFunctionDescriptor();
                functionPath = Protocol.unquotePath(
                        c.reconstructText(fd.start(), fd.nameEnd()))
                        + c.reconstructText(fd.nameEnd(), fd.end());
                functionSpan = c.spanOf(fd.start(), fd.end() - 1);
            } else if ("ownership".equals(key)) {
                String oKind = c.parseIdentifier();
                c.expect(TokenType.BRACE_OPEN);
                String ik = c.parseIdentifier();
                c.expect(TokenType.COLON);
                if ("Deployment".equals(oKind)) {
                    if (!"identifier".equals(ik)) {
                        throw c.error("unknown Deployment key: " + ik);
                    }
                    ownerId = SectionParse.stringValue(c);
                } else if ("UserList".equals(oKind)) {
                    if (!"users".equals(ik)) {
                        throw c.error("unknown UserList key: " + ik);
                    }
                    userListUsers = new ArrayList<>();
                    c.expect(TokenType.BRACKET_OPEN);
                    while (c.peek() != TokenType.BRACKET_CLOSE) {
                        userListUsers.add(SectionParse.stringValue(c));
                        if (!c.match(TokenType.COMMA)) {
                            break;
                        }
                    }
                    c.expect(TokenType.BRACKET_CLOSE);
                } else {
                    throw c.error("unsupported ownership kind: " + oKind);
                }
                c.match(TokenType.SEMI_COLON);
                c.expect(TokenType.BRACE_CLOSE);
            } else if ("permissionScheme".equals(key)) {
                // an ENUM-shaped value — engine-parity validation against
                // SnowflakePermissionScheme (SnowflakeTreeWalker:113)
                String scheme = c.parseIdentifier();
                if (!"DEFAULT".equals(scheme)
                        && !"SEQUESTERED".equals(scheme)) {
                    throw c.error("Unknown permission scheme '" + scheme
                            + "'");
                }
                scalars.put(key, scheme);
            } else if ("activationConfiguration".equals(key)) {
                int aS = c.pos();
                actConn = Protocol.unquotePath(c.parseQualifiedName());
                actConnSpan = c.spanOf(aS, c.pos() - 1);
            } else {
                throw c.error("unknown key '" + key + "' inside " + kind
                        + " '" + qn + "'");
            }
            c.expect(TokenType.SEMI_COLON);
        }
        c.expect(TokenType.BRACE_CLOSE);
        if (functionPath == null || functionSpan == null) {
            throw com.legend.parser.TokenStreamCursor.throwAt(
                    c.tokens(), declStart,
                    kind + " '" + qn + "' needs a function");
        }
        // engine-required fields (leniency audit: the engine deserializer
        // refuses these when absent — structured parity refusals).
        // BigQueryFunction is EXEMPT from ownership: accepted corpus
        // fixtures omit it (the corpus adjudicates).
        if (ownerId == null && userListUsers == null
                && !"BigQueryFunction".equals(kind)) {
            throw com.legend.parser.TokenStreamCursor.throwAt(
                    c.tokens(), declStart, "Field 'ownership' is required");
        }
        if ("MemSqlFunction".equals(kind)
                && !scalars.containsKey("functionName")) {
            throw com.legend.parser.TokenStreamCursor.throwAt(
                    c.tokens(), declStart,
                    "Field 'functionName' is required");
        }
        if ("SnowflakeM2MUdf".equals(kind)) {
            if (!scalars.containsKey("deploymentSchema")) {
                throw com.legend.parser.TokenStreamCursor.throwAt(
                        c.tokens(), declStart,
                        "Field 'deploymentSchema' is required");
            }
            if (!scalars.containsKey("deploymentStage")) {
                throw com.legend.parser.TokenStreamCursor.throwAt(
                        c.tokens(), declStart,
                        "Field 'deploymentStage' is required");
            }
        }
        return new Protocol.PFunctionActivator(pkg, name, kind,
                dec.stereotypes(), dec.taggedValues(), scalars, booleans,
                functionPath, functionSpan, ownerId, userListUsers, actConn,
                actConnSpan, c.spanOf(declStart, c.pos() - 1));
    }



    /** A minimal cursor over a re-lexed slice for the SPI feed. */
    private static final class SliceCursor implements TokenStreamCursor {

        private final com.legend.lexer.TokenStream tokens;
        private final com.legend.parser.Dialect dialect;
        private int pos;

        SliceCursor(com.legend.lexer.TokenStream tokens, com.legend.parser.Dialect dialect) {
            this.tokens = tokens;
            this.dialect = dialect;
        }

        @Override
        public com.legend.parser.Dialect dialect() {
            return dialect;
        }

        @Override
        public com.legend.lexer.TokenStream tokens() {
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
    }
}
