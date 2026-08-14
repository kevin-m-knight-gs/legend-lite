// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.parser;

/**
 * The {@code #GQL{ ... }#} island content: a GraphQL document parsed from
 * the RAW island text (GraphQL treats commas as whitespace and {@code $},
 * {@code @}, {@code !}, {@code ...} as its own lexemes, so the Pure token
 * stream is the wrong instrument). Produces the engine's canonical wire
 * JSON directly — fields alphabetical per node, matching the probed
 * serialization (scratchpad gql-wire.txt, 2026-08-14):
 *
 * <ul>
 *   <li>{@code executableDocument} / {@code operationDefinition} (the
 *       {@code type} field is OMITTED for a bare selection document);</li>
 *   <li>{@code field} with {@code alias} carrying the trailing colon
 *       verbatim ({@code "h:"} — the engine keeps it);</li>
 *   <li>variable DEFINITIONS keep the {@code $} prefix in {@code name},
 *       variable USES drop it;</li>
 *   <li>values: int/float/string/boolean/null/enum/list/object;</li>
 *   <li>{@code fragmentDefinition}/{@code fragmentSpread}; INLINE
 *       fragments are refused — the reference refuses them too;</li>
 *   <li>SDL {@code objectTypeDefinition} ({@code type X { f: T }});
 *       other type-system kinds refuse loudly until probed.</li>
 * </ul>
 */
public final class GqlParser {

    private final String src;
    private int pos;
    private final int baseLine;
    private final int baseCol;

    private GqlParser(String src, int baseLine, int baseCol) {
        this.src = src;
        this.baseLine = baseLine;
        this.baseCol = baseCol;
    }

    /** Parse a whole island content into the wire {@code value} JSON. */
    public static String parseDocument(String content, int baseLine,
            int baseCol) {
        GqlParser p = new GqlParser(content, baseLine, baseCol);
        StringBuilder b = new StringBuilder();
        b.append("{\"_type\":\"executableDocument\",\"definitions\":[");
        boolean first = true;
        p.ws();
        while (!p.atEnd()) {
            if (!first) {
                b.append(',');
            }
            first = false;
            p.definition(b);
            p.ws();
        }
        b.append("]}");
        if (first) {
            throw p.fail("empty GraphQL document");
        }
        return b.toString();
    }

    // ------------------------------------------------------------------
    // Definitions
    // ------------------------------------------------------------------

    private void definition(StringBuilder b) {
        if (peekc() == '{') {
            // bare selection set: an operationDefinition with NO type field
            b.append("{\"_type\":\"operationDefinition\",\"directives\":[],"
                    + "\"selectionSet\":[");
            selectionSet(b);
            b.append("],\"variables\":[]}");
            return;
        }
        String kw = name("definition keyword");
        switch (kw) {
            case "query", "mutation", "subscription" -> operation(b, kw);
            case "fragment" -> fragmentDefinition(b);
            case "type" -> objectTypeDefinition(b);
            default -> throw fail("unbuilt GraphQL definition kind '" + kw
                    + "'");
        }
    }

    private void operation(StringBuilder b, String opType) {
        ws();
        String opName = isNameStart(peekc()) ? name("operation name") : null;
        ws();
        String variables = peekc() == '(' ? variableDefinitions() : "[]";
        String directives = directives();
        b.append("{\"_type\":\"operationDefinition\",\"directives\":")
                .append(directives);
        if (opName != null) {
            b.append(",\"name\":").append(json(opName));
        }
        b.append(",\"selectionSet\":[");
        selectionSet(b);
        b.append("],\"type\":").append(json(opType))
                .append(",\"variables\":").append(variables).append('}');
    }

    private void fragmentDefinition(StringBuilder b) {
        ws();
        String fragName = name("fragment name");
        ws();
        if (!"on".equals(name("'on'"))) {
            throw fail("fragment needs 'on <Type>'");
        }
        ws();
        String cond = name("fragment type condition");
        String directives = directives();
        b.append("{\"_type\":\"fragmentDefinition\",\"directives\":")
                .append(directives).append(",\"name\":").append(json(fragName))
                .append(",\"selectionSet\":[");
        selectionSet(b);
        b.append("],\"typeCondition\":").append(json(cond)).append('}');
    }

    private void objectTypeDefinition(StringBuilder b) {
        ws();
        String typeName = name("type name");
        ws();
        if (isNameStart(peekc())) {
            throw fail("unbuilt SDL clause after type name (implements/"
                    + "directives are unprobed)");
        }
        expect('{');
        b.append("{\"_type\":\"objectTypeDefinition\",\"_implements\":[],"
                + "\"directives\":[],\"fields\":[");
        boolean first = true;
        ws();
        while (peekc() != '}') {
            if (!first) {
                b.append(',');
            }
            first = false;
            String fieldName = name("field name");
            ws();
            expect(':');
            b.append("{\"argumentDefinitions\":[],\"directives\":[],"
                    + "\"name\":").append(json(fieldName))
                    .append(",\"type\":").append(typeReference()).append('}');
            ws();
        }
        expect('}');
        b.append("],\"name\":").append(json(typeName)).append('}');
    }

    // ------------------------------------------------------------------
    // Selections
    // ------------------------------------------------------------------

    private void selectionSet(StringBuilder b) {
        expect('{');
        boolean first = true;
        ws();
        while (peekc() != '}') {
            if (!first) {
                b.append(',');
            }
            first = false;
            selection(b);
            ws();
        }
        expect('}');
    }

    private void selection(StringBuilder b) {
        if (peekc() == '.') {
            expect('.');
            expect('.');
            expect('.');
            ws();
            String spread = name("fragment spread name");
            if ("on".equals(spread)) {
                // the reference REFUSES inline fragments (probed: empty-
                // message rejection); verdict parity — so do we
                throw fail("inline fragments are refused by the reference"
                        + " GraphQL grammar");
            }
            b.append("{\"_type\":\"fragmentSpread\",\"directives\":")
                    .append(directives()).append(",\"name\":")
                    .append(json(spread)).append('}');
            return;
        }
        String first = name("field name");
        ws();
        String alias = null;
        String fieldName = first;
        if (peekc() == ':') {
            pos++;
            ws();
            // the engine keeps the COLON inside the alias (probed "h:")
            alias = first + ":";
            fieldName = name("aliased field name");
            ws();
        }
        String arguments = peekc() == '(' ? arguments() : "[]";
        String directives = directives();
        b.append("{\"_type\":\"field\"");
        if (alias != null) {
            b.append(",\"alias\":").append(json(alias));
        }
        b.append(",\"arguments\":").append(arguments)
                .append(",\"directives\":").append(directives)
                .append(",\"name\":").append(json(fieldName))
                .append(",\"selectionSet\":[");
        ws();
        if (peekc() == '{') {
            selectionSet(b);
        }
        b.append("]}");
    }

    // ------------------------------------------------------------------
    // Arguments / directives / variables / types / values
    // ------------------------------------------------------------------

    private String arguments() {
        expect('(');
        StringBuilder b = new StringBuilder("[");
        boolean first = true;
        ws();
        while (peekc() != ')') {
            if (!first) {
                b.append(',');
            }
            first = false;
            String argName = name("argument name");
            ws();
            expect(':');
            b.append("{\"name\":").append(json(argName))
                    .append(",\"value\":").append(value()).append('}');
            ws();
        }
        expect(')');
        return b.append(']').toString();
    }

    private String directives() {
        ws();
        StringBuilder b = new StringBuilder("[");
        boolean first = true;
        while (peekc() == '@') {
            pos++;
            String dirName = name("directive name");
            ws();
            String args = peekc() == '(' ? arguments() : "[]";
            if (!first) {
                b.append(',');
            }
            first = false;
            b.append("{\"arguments\":").append(args).append(",\"name\":")
                    .append(json(dirName)).append('}');
            ws();
        }
        return b.append(']').toString();
    }

    private String variableDefinitions() {
        expect('(');
        StringBuilder b = new StringBuilder("[");
        boolean first = true;
        ws();
        while (peekc() != ')') {
            if (!first) {
                b.append(',');
            }
            first = false;
            expect('$');
            String varName = name("variable name");
            ws();
            expect(':');
            String type = typeReference();
            ws();
            String dflt = null;
            if (peekc() == '=') {
                pos++;
                dflt = value();
                ws();
            }
            // wire order: defaultValue?, directives, name (WITH $), type
            b.append('{');
            if (dflt != null) {
                b.append("\"defaultValue\":").append(dflt).append(',');
            }
            b.append("\"directives\":[],\"name\":").append(json("$" + varName))
                    .append(",\"type\":").append(type).append('}');
            ws();
        }
        expect(')');
        return b.append(']').toString();
    }

    private String typeReference() {
        ws();
        String inner;
        if (peekc() == '[') {
            pos++;
            String item = typeReference();
            ws();
            expect(']');
            inner = "{\"_type\":\"listTypeReference\",\"itemType\":" + item;
        } else {
            inner = "{\"_type\":\"namedTypeReference\",\"name\":"
                    + json(name("type name"));
        }
        ws();
        boolean nullable = true;
        if (peekc() == '!') {
            pos++;
            nullable = false;
        }
        return inner + ",\"nullable\":" + nullable + "}";
    }

    private String value() {
        ws();
        char c = peekc();
        if (c == '$') {
            pos++;
            return "{\"_type\":\"variable\",\"name\":"
                    + json(name("variable name")) + "}";
        }
        if (c == '"') {
            return "{\"_type\":\"stringValue\",\"value\":"
                    + json(stringLiteral()) + "}";
        }
        if (c == '[') {
            pos++;
            StringBuilder b = new StringBuilder(
                    "{\"_type\":\"listValue\",\"values\":[");
            boolean first = true;
            ws();
            while (peekc() != ']') {
                if (!first) {
                    b.append(',');
                }
                first = false;
                b.append(value());
                ws();
            }
            pos++;
            return b.append("]}").toString();
        }
        if (c == '{') {
            pos++;
            StringBuilder b = new StringBuilder(
                    "{\"_type\":\"objectValue\",\"fields\":[");
            boolean first = true;
            ws();
            while (peekc() != '}') {
                if (!first) {
                    b.append(',');
                }
                first = false;
                String f = name("object field name");
                ws();
                expect(':');
                b.append("{\"name\":").append(json(f)).append(",\"value\":")
                        .append(value()).append('}');
                ws();
            }
            pos++;
            return b.append("]}").toString();
        }
        if (c == '-' || (c >= '0' && c <= '9')) {
            int s = pos;
            if (c == '-') {
                pos++;
            }
            while (!atEnd() && Character.isDigit(peekc())) {
                pos++;
            }
            boolean isFloat = false;
            if (!atEnd() && (peekc() == '.' || peekc() == 'e'
                    || peekc() == 'E')) {
                isFloat = true;
                pos++;
                while (!atEnd() && (Character.isDigit(peekc())
                        || peekc() == '+' || peekc() == '-'
                        || peekc() == 'e' || peekc() == 'E'
                        || peekc() == '.')) {
                    pos++;
                }
            }
            String num = src.substring(s, pos);
            return "{\"_type\":\"" + (isFloat ? "floatValue" : "intValue")
                    + "\",\"value\":" + num + "}";
        }
        String word = name("value");
        return switch (word) {
            case "true" -> "{\"_type\":\"booleanValue\",\"value\":true}";
            case "false" -> "{\"_type\":\"booleanValue\",\"value\":false}";
            case "null" -> "{\"_type\":\"nullValue\"}";
            default -> "{\"_type\":\"enumValue\",\"value\":" + json(word)
                    + "}";
        };
    }

    // ------------------------------------------------------------------
    // Scanner
    // ------------------------------------------------------------------

    /** GraphQL insignificants: whitespace, commas and {@code #} comments. */
    private void ws() {
        while (!atEnd()) {
            char c = src.charAt(pos);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == ',') {
                pos++;
            } else if (c == '#') {
                while (!atEnd() && src.charAt(pos) != '\n') {
                    pos++;
                }
            } else {
                return;
            }
        }
    }

    private String name(String what) {
        ws();
        if (atEnd() || !isNameStart(src.charAt(pos))) {
            throw fail("expected " + what);
        }
        int s = pos;
        while (!atEnd() && isNamePart(src.charAt(pos))) {
            pos++;
        }
        return src.substring(s, pos);
    }

    private String stringLiteral() {
        expect('"');
        StringBuilder b = new StringBuilder();
        while (!atEnd() && src.charAt(pos) != '"') {
            char c = src.charAt(pos++);
            if (c == '\\' && !atEnd()) {
                char e = src.charAt(pos++);
                b.append(switch (e) {
                    case 'n' -> '\n';
                    case 't' -> '\t';
                    case 'r' -> '\r';
                    case '"' -> '"';
                    case '\\' -> '\\';
                    case '/' -> '/';
                    default -> throw fail("unbuilt string escape '\\" + e
                            + "'");
                });
            } else {
                b.append(c);
            }
        }
        expect('"');
        return b.toString();
    }

    private static boolean isNameStart(char c) {
        return c == '_' || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    private static boolean isNamePart(char c) {
        return isNameStart(c) || (c >= '0' && c <= '9');
    }

    private void expect(char c) {
        ws();
        if (atEnd() || src.charAt(pos) != c) {
            throw fail("expected '" + c + "'");
        }
        pos++;
    }

    private char peekc() {
        return atEnd() ? '\0' : src.charAt(pos);
    }

    private boolean atEnd() {
        return pos >= src.length();
    }

    private ParseException fail(String message) {
        int line = baseLine;
        int col = baseCol;
        for (int i = 0; i < Math.min(pos, src.length()); i++) {
            if (src.charAt(i) == '\n') {
                line++;
                col = 1;
            } else {
                col++;
            }
        }
        return new ParseException("GQL: " + message, line, col);
    }

    /** Minimal JSON string rendering (quotes/backslash/control chars). */
    private static String json(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
                }
            }
        }
        return b.append('"').toString();
    }
}
