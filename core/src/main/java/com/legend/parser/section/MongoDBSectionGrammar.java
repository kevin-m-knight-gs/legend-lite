// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.parser.section;

import com.legend.lexer.TokenType;
import com.legend.parser.TokenStreamCursor;
import com.legend.protocol.Protocol;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code ###MongoDB} — {@code Database qn ( Collection name ( ... ) )}
 * (ZTailProbe "mongo-db"/"mongo-rich"/"mongo-empty"): collections carry
 * validation levels and a {@code jsonSchema:} island, captured balanced,
 * read by the platform JSON reader and mapped to typed BSON schema nodes.
 */
public final class MongoDBSectionGrammar implements ElementwiseSectionGrammar {

    public static final MongoDBSectionGrammar INSTANCE =
            new MongoDBSectionGrammar();

    private MongoDBSectionGrammar() {
    }

    @Override
    public String name() {
        return "MongoDB";
    }

    @Override
    public String qualifiedNameOf(Protocol.Element e) {
        return ((Protocol.PMongoDatabase) e).qualifiedName();
    }

    @Override
    public com.legend.model.PackageableElement toModel(Protocol.Element element) {
        Protocol.PMongoDatabase m = (Protocol.PMongoDatabase) element;
        return new com.legend.model.GenericSectionElementDefinition("MongoDB",
                "Database", m.qualifiedName(), java.util.Map.of(), null);
    }

    @Override
    public Protocol.Element parseOne(TokenStreamCursor c) {
        SectionParse.Head h = SectionParse.head(c, "Database");
        c.expect(TokenType.PAREN_OPEN);
        List<Protocol.PMongoDatabase.PMongoCollection> collections =
                new ArrayList<>();
        while (!c.atEnd() && c.peek() != TokenType.PAREN_CLOSE) {
            String ck = c.parseIdentifier();
            if (!"Collection".equals(ck)) {
                throw c.error("expected Collection, got " + ck);
            }
            collections.add(parseCollection(c));
        }
        c.expect(TokenType.PAREN_CLOSE);
        return new Protocol.PMongoDatabase(h.pkg(), h.name(), collections,
                c.spanOf(h.declStart(), c.pos() - 1));
    }

    private static Protocol.PMongoDatabase.PMongoCollection parseCollection(
            TokenStreamCursor c) {
        String collName;
        if (c.peek() == TokenType.QUOTED_STRING) {
            String raw = c.text();
            c.expect(TokenType.QUOTED_STRING);
            collName = raw.length() >= 2 && raw.charAt(0) == '"'
                    && raw.charAt(raw.length() - 1) == '"'
                    ? raw.substring(1, raw.length() - 1) : raw;
        } else {
            collName = c.parseIdentifier();
        }
        c.expect(TokenType.PAREN_OPEN);
        String level = null;
        String action = null;
        Protocol.PBsonSchema schema = null;
        java.util.Set<String> seenKeys = new java.util.HashSet<>();
        while (c.peek() != TokenType.PAREN_CLOSE) {
            String key = c.parseIdentifier();
            TokenStreamCursor.once(seenKeys, key, c);
            c.expect(TokenType.COLON);
            switch (key) {
                case "validationLevel" -> level = c.parseIdentifier();
                case "validationAction" -> action = c.parseIdentifier();
                case "jsonSchema" -> schema = parseSchemaIsland(c);
                default -> throw c.error(
                        "unknown Collection key '" + key + "'");
            }
            c.expect(TokenType.SEMI_COLON);
        }
        c.expect(TokenType.PAREN_CLOSE);
        if (level == null || action == null || schema == null) {
            throw c.error("Collection '" + collName + "' needs "
                    + "validationLevel, validationAction and jsonSchema");
        }
        return new Protocol.PMongoDatabase.PMongoCollection(collName, level,
                action, schema);
    }

    /** The island is already lexed by the shared lexer (double-quoted keys
     *  are QUOTED_STRING tokens), so the JSON walks as TOKENS — the
     *  parser's native medium; no string re-parse, no layer crossing. */
    private static Protocol.PBsonSchema parseSchemaIsland(TokenStreamCursor c) {
        Object parsed = parseJsonValue(c);
        if (!(parsed instanceof java.util.Map<?, ?> m)) {
            throw c.error("jsonSchema is not a JSON object");
        }
        return schemaOf(m, true, c);
    }

    private static Object parseJsonValue(TokenStreamCursor c) {
        switch (c.peek()) {
            case BRACE_OPEN -> {
                c.advance();
                var m = new java.util.LinkedHashMap<String, Object>();
                while (c.peek() != TokenType.BRACE_CLOSE) {
                    String raw = c.text();
                    c.expect(TokenType.QUOTED_STRING);
                    c.expect(TokenType.COLON);
                    m.put(raw.substring(1, raw.length() - 1),
                            parseJsonValue(c));
                    if (!c.match(TokenType.COMMA)) {
                        break;
                    }
                }
                c.expect(TokenType.BRACE_CLOSE);
                return m;
            }
            case BRACKET_OPEN -> {
                c.advance();
                var l = new ArrayList<Object>();
                while (c.peek() != TokenType.BRACKET_CLOSE) {
                    l.add(parseJsonValue(c));
                    if (!c.match(TokenType.COMMA)) {
                        break;
                    }
                }
                c.expect(TokenType.BRACKET_CLOSE);
                return l;
            }
            case QUOTED_STRING -> {
                String raw = c.text();
                c.advance();
                return raw.substring(1, raw.length() - 1);
            }
            case INTEGER -> {
                String t = c.text();
                c.advance();
                return Long.valueOf(t);
            }
            case MINUS -> {
                c.advance();
                String t = c.text();
                c.expect(TokenType.INTEGER);
                return Long.valueOf("-" + t);
            }
            case TRUE -> {
                c.advance();
                return Boolean.TRUE;
            }
            case FALSE -> {
                c.advance();
                return Boolean.FALSE;
            }
            default -> throw c.error("unsupported jsonSchema value: "
                    + c.safeText());
        }
    }

    private static Protocol.PBsonSchema schemaOf(java.util.Map<?, ?> m,
            boolean root, TokenStreamCursor at) {
        String bsonType = (String) m.get("bsonType");
        String wireType = switch (bsonType == null ? "object" : bsonType) {
            case "object" -> root ? "schema" : "objectType";
            case "string" -> "stringType";
            case "long" -> "longType";
            case "int" -> "intType";
            case "bool" -> "boolType";
            case "double" -> "doubleType";
            case "decimal" -> "decimalType";
            case "array" -> "arrayType";
            default -> throw at.error("unmapped bsonType '" + bsonType + "'");
        };
        List<java.util.Map.Entry<String, Protocol.PBsonSchema>> props =
                new ArrayList<>();
        if (m.get("properties") instanceof java.util.Map<?, ?> pm) {
            for (java.util.Map.Entry<?, ?> e : pm.entrySet()) {
                props.add(java.util.Map.entry((String) e.getKey(),
                        schemaOf((java.util.Map<?, ?>) e.getValue(), false,
                                at)));
            }
        }
        List<String> required = new ArrayList<>();
        if (m.get("required") instanceof List<?> rl) {
            for (Object r : rl) {
                required.add((String) r);
            }
        }
        Object addl = m.get("additionalProperties");
        return new Protocol.PBsonSchema(wireType,
                addl == null ? null : (Boolean) addl, props, required,
                (String) m.get("title"), (String) m.get("description"),
                (Long) m.get("minLength"), (Long) m.get("maxLength"));
    }
}
