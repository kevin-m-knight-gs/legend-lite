// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.parser;

import com.legend.protocol.spec.Gql;

import java.util.ArrayList;
import java.util.List;

/**
 * The {@code #GQL{ ... }#} island content: a GraphQL document parsed from
 * the RAW island text (GraphQL treats commas as whitespace and {@code $},
 * {@code @}, {@code !}, {@code ...} as its own lexemes, so the Pure token
 * stream is the wrong instrument — token reconstruction also merges
 * {@code query Hero} into one word). Scannerless by design: the lexical
 * grammar is trivial and needs no lookahead, so a separate token stream
 * would be ceremony.
 *
 * <p>Produces typed {@link Gql} nodes; {@code GqlEmitter} owns the
 * byte-exact wire (probe gql-wire 2026-08-14; the gql-islands battery in
 * AdversarialParityTest pins every shape). Grammar facts probed live:
 * INLINE fragments are refused by the reference, and SDL kinds beyond
 * {@code type} are unprobed and refuse loudly.
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

    /** Parse a whole island content into its typed document. */
    public static Gql.Document parseDocument(String content, int baseLine,
            int baseCol) {
        GqlParser p = new GqlParser(content, baseLine, baseCol);
        List<Gql.Definition> defs = new ArrayList<>();
        p.ws();
        while (!p.atEnd()) {
            defs.add(p.definition());
            p.ws();
        }
        if (defs.isEmpty()) {
            throw p.fail("empty GraphQL document");
        }
        return new Gql.Document(defs);
    }

    // ------------------------------------------------------------------
    // Definitions
    // ------------------------------------------------------------------

    private Gql.Definition definition() {
        if (peekc() == '{') {
            // bare selection set: an operation whose TYPE the wire omits
            return new Gql.Operation(null, null, List.of(), List.of(),
                    selectionSet());
        }
        String kw = name("definition keyword");
        return switch (kw) {
            case "query", "mutation", "subscription" -> operation(kw);
            case "fragment" -> fragmentDefinition();
            case "type" -> objectTypeDefinition();
            default -> throw fail("unbuilt GraphQL definition kind '" + kw
                    + "'");
        };
    }

    private Gql.Operation operation(String opType) {
        ws();
        String opName = isNameStart(peekc()) ? ident("operation name") : null;
        ws();
        List<Gql.VariableDef> variables = peekc() == '('
                ? variableDefinitions() : List.of();
        List<Gql.Directive> directives = directives();
        return new Gql.Operation(opType, opName, variables, directives,
                selectionSet());
    }

    private Gql.Fragment fragmentDefinition() {
        ws();
        String fragName = ident("fragment name");
        ws();
        if (!"on".equals(name("'on'"))) {
            throw fail("fragment needs 'on <Type>'");
        }
        ws();
        String cond = ident("fragment type condition");
        // the engine WALKER never reads fragment-level directives — parse
        // and DROP to match its wire (deep audit #2)
        directives();
        return new Gql.Fragment(fragName, cond, List.of(), selectionSet());
    }

    private Gql.ObjectType objectTypeDefinition() {
        ws();
        String typeName = name("type name");
        ws();
        if (isNameStart(peekc())) {
            throw fail("unbuilt SDL clause after type name (implements/"
                    + "directives are unprobed)");
        }
        expect('{');
        List<Gql.FieldDef> fields = new ArrayList<>();
        ws();
        while (peekc() != '}') {
            String fieldName = name("field name");
            ws();
            expect(':');
            fields.add(new Gql.FieldDef(fieldName, typeReference()));
            ws();
        }
        expect('}');
        if (fields.isEmpty()) {
            throw fail("empty type body");
        }
        return new Gql.ObjectType(typeName, fields);
    }

    // ------------------------------------------------------------------
    // Selections
    // ------------------------------------------------------------------

    private List<Gql.Selection> selectionSet() {
        expect('{');
        List<Gql.Selection> out = new ArrayList<>();
        ws();
        while (peekc() != '}') {
            out.add(selection());
            ws();
        }
        expect('}');
        if (out.isEmpty()) {
            // the .g4 selection rule is ONE-OR-MORE (deep audit #2 1a-ter)
            throw fail("empty selection set");
        }
        return out;
    }

    private Gql.Selection selection() {
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
            // spread directives: parsed, dropped by the engine walker
            directives();
            return new Gql.FragmentSpread(spread, List.of());
        }
        String first = ident("field name");
        ws();
        String alias = null;
        String fieldName = first;
        if (peekc() == ':') {
            pos++;
            ws();
            // the engine keeps the COLON inside the alias (probed "h:")
            alias = first + ":";
            fieldName = ident("aliased field name");
            ws();
        }
        List<Gql.Argument> arguments = peekc() == '(' ? arguments()
                : List.of();
        List<Gql.Directive> directives = directives();
        ws();
        List<Gql.Selection> nested = peekc() == '{' ? selectionSet()
                : List.of();
        return new Gql.Field(alias, fieldName, arguments, directives, nested);
    }

    // ------------------------------------------------------------------
    // Arguments / directives / variables / types / values
    // ------------------------------------------------------------------

    private List<Gql.Argument> arguments() {
        expect('(');
        List<Gql.Argument> out = new ArrayList<>();
        ws();
        while (peekc() != ')') {
            String argName = name("argument name");
            ws();
            expect(':');
            out.add(new Gql.Argument(argName, value()));
            ws();
        }
        expect(')');
        if (out.isEmpty()) {
            throw fail("empty argument list");
        }
        return out;
    }

    private List<Gql.Directive> directives() {
        ws();
        List<Gql.Directive> out = new ArrayList<>();
        while (peekc() == '@') {
            pos++;
            String dirName = name("directive name");
            ws();
            out.add(new Gql.Directive(dirName,
                    peekc() == '(' ? arguments() : List.of()));
            ws();
        }
        return out;
    }

    private List<Gql.VariableDef> variableDefinitions() {
        expect('(');
        List<Gql.VariableDef> out = new ArrayList<>();
        ws();
        while (peekc() != ')') {
            expect('$');
            String varName = name("variable name");
            ws();
            expect(':');
            Gql.TypeRef type = typeReference();
            ws();
            Gql.Value dflt = null;
            if (peekc() == '=') {
                pos++;
                dflt = value();
                ws();
            }
            out.add(new Gql.VariableDef("$" + varName, type, dflt));
            ws();
        }
        expect(')');
        if (out.isEmpty()) {
            throw fail("empty variable definitions");
        }
        return out;
    }

    private Gql.TypeRef typeReference() {
        ws();
        if (peekc() == '[') {
            pos++;
            Gql.TypeRef item = typeReference();
            ws();
            expect(']');
            return new Gql.ListType(item, !bang());
        }
        String typeName = name("type name");
        return new Gql.NamedType(typeName, !bang());
    }

    private boolean bang() {
        ws();
        if (peekc() == '!') {
            pos++;
            return true;
        }
        return false;
    }

    private Gql.Value value() {
        ws();
        char c = peekc();
        if (c == '$') {
            pos++;
            return new Gql.VariableRef(name("variable name"));
        }
        if (c == '"') {
            return new Gql.StringValue(stringLiteral());
        }
        if (c == '[') {
            pos++;
            List<Gql.Value> values = new ArrayList<>();
            ws();
            while (peekc() != ']') {
                values.add(value());
                ws();
            }
            pos++;
            return new Gql.ListValue(values);
        }
        if (c == '{') {
            pos++;
            List<Gql.Argument> fields = new ArrayList<>();
            ws();
            while (peekc() != '}') {
                String f = name("object field name");
                ws();
                expect(':');
                fields.add(new Gql.Argument(f, value()));
                ws();
            }
            pos++;
            return new Gql.ObjectValue(fields);
        }
        if (c == '-' || (c >= '0' && c <= '9')) {
            // the .g4 INT/FLOAT shape, enforced (deep audit #2 1a-bis:
            // '1.', '-', '1e', '007', '1.2.3' must REFUSE like the engine)
            int s = pos;
            if (c == '-') {
                pos++;
            }
            int digitsStart = pos;
            while (!atEnd() && Character.isDigit(peekc())) {
                pos++;
            }
            int intDigits = pos - digitsStart;
            if (intDigits == 0) {
                throw fail("malformed number");
            }
            if (intDigits > 1 && src.charAt(digitsStart) == '0') {
                throw fail("malformed number (leading zero)");
            }
            boolean isFloat = false;
            if (!atEnd() && peekc() == '.') {
                isFloat = true;
                pos++;
                int fs = pos;
                while (!atEnd() && Character.isDigit(peekc())) {
                    pos++;
                }
                if (pos == fs) {
                    throw fail("malformed number (bare fraction point)");
                }
            }
            if (!atEnd() && (peekc() == 'e' || peekc() == 'E')) {
                isFloat = true;
                pos++;
                if (!atEnd() && (peekc() == '+' || peekc() == '-')) {
                    pos++;
                }
                int es = pos;
                while (!atEnd() && Character.isDigit(peekc())) {
                    pos++;
                }
                if (pos == es) {
                    throw fail("malformed number (empty exponent)");
                }
            }
            String num = src.substring(s, pos);
            try {
                return isFloat
                        ? new Gql.FloatValue(Double.parseDouble(num))
                        : new Gql.IntValue(Long.parseLong(num));
            } catch (NumberFormatException e) {
                throw fail("number out of range: " + num);
            }
        }
        String word = name("value");
        return switch (word) {
            case "true" -> new Gql.BooleanValue(true);
            case "false" -> new Gql.BooleanValue(false);
            case "null" -> new Gql.NullValue();
            default -> new Gql.EnumValue(word);
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

    /** The .g4 name rule EXCLUDES its implicit keyword literals (deep
     *  audit #2: null/on/implements/directive confirmed refused). */
    private String ident(String what) {
        String n = name(what);
        if ("null".equals(n) || "on".equals(n) || "implements".equals(n)
                || "directive".equals(n)) {
            throw fail("reserved word '" + n + "' cannot be a " + what);
        }
        return n;
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
        if (pos + 1 < src.length() && src.charAt(pos) == '"'
                && src.charAt(pos + 1) == '"') {
            // a BLOCK string (triple-quoted) — the engine grammar
            // has no rule for it; refuse instead of mis-lexing into
            // three values (deep audit #2 1a-ter)
            throw fail("block strings are not in the reference"
                    + " grammar");
        }
        StringBuilder b = new StringBuilder();
        while (!atEnd() && src.charAt(pos) != '"') {
            char c = src.charAt(pos++);
            b.append(c);
            if (c == '\\') {
                // the engine strips the QUOTES and nothing else —
                // escapes ride the wire RAW (deep audit #2 1a);
                // validate the .g4 ESC set, keep the bytes
                if (atEnd()) {
                    throw fail("dangling escape");
                }
                char e = src.charAt(pos++);
                if ("\"\\/bfnrt".indexOf(e) < 0 && e != 'u') {
                    throw fail("unbuilt string escape '\\" + e
                            + "'");
                }
                b.append(e);
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
}
