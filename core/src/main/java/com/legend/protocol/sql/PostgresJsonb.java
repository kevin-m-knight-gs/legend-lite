// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.protocol.sql;

import java.util.ArrayList;
import static com.legend.protocol.sql.SqlToPure.*;
import static com.legend.protocol.sql.PostgresOperators.*;
import static com.legend.protocol.sql.PostgresFunctions.*;

import com.legend.protocol.spec.CString;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Postgres's jsonb constants and functions as the {@code #SQL{...}#} island reads them ({@link PostgresDialect}'s,
 * moved out of {@link SqlToPure} with the dialect program's D11, 2026-10-18).
 */
final class PostgresJsonb {

    private PostgresJsonb() {
    }

    /** {@code '...'::jsonb}: the text parsed (text that is not JSON is Postgres's error, and fromJson's). */
    static Tx jsonConstant(SqlAst.StringLit s) {
        if (duplicateKey(s.value())) {
            // jsonb keeps a duplicate key's last value, DuckDB's JSON reads its first
            throw SqlTranslationException.unsupported("a jsonb constant with a duplicate key is not supported yet",
                    s.pos());
        }
        // jsonb's 'null' is a value, not SQL's NULL; a database may hold it either way
        return Tx.value(call(FROM_JSON, new CString(s.value())), Kind.JSON, s.value().strip().equals("null"));
    }

    /** Whether JSON text has an object with a key twice (text that is not JSON: no; reading it is the error). */
    private static boolean duplicateKey(String json) {
        java.util.ArrayDeque<Set<String>> objects = new java.util.ArrayDeque<>();
        java.util.ArrayDeque<Boolean> isObject = new java.util.ArrayDeque<>();
        int i = 0;
        while (i < json.length()) {
            char c = json.charAt(i);
            if (c == '"') {
                int start = ++i;
                while (i < json.length() && json.charAt(i) != '"') {
                    i += json.charAt(i) == '\\' ? 2 : 1;
                }
                if (i >= json.length()) {
                    return false;
                }
                String raw = json.substring(start, i++);
                int next = i;
                while (next < json.length() && Character.isWhitespace(json.charAt(next))) {
                    next++;
                }
                if (next < json.length() && json.charAt(next) == ':' && Boolean.TRUE.equals(isObject.peek())) {
                    String key = com.legend.json.Json.unescape(raw);
                    if (!java.util.Objects.requireNonNull(objects.peek()).add(key)) {
                        return true;
                    }
                }
                continue;
            }
            if (c == '{' || c == '[') {
                isObject.push(c == '{');
                objects.push(new HashSet<>());
            } else if ((c == '}' || c == ']') && !isObject.isEmpty()) {
                isObject.pop();
                objects.pop();
            }
            i++;
        }
        return false;
    }

    /** {@code json_extract_path(x, VARIADIC keys)}: {@code #>}'s path, its keys constants; the _text form
     *  {@code #>>}'s. */
    static Tx extractPath(SqlAst.FunctionCall f, List<Tx> args, boolean text) {
        if (args.size() < 2) {
            throw SqlTranslationException.unsupported(functionName(f.name().get(0)) + " with no keys is not"
                    + " supported yet", f.pos());
        }
        if (!text) {
            constantNavigation(f.args().get(0), f.pos());
        }
        // an untyped constant is read as the function's json argument
        Tx got = f.args().get(0) instanceof SqlAst.StringLit lit ? jsonConstant(lit)
                : jsonOperand(args.get(0), functionName(f.name().get(0)), f.pos());
        boolean constant = isJsonConstant(f.args().get(0));
        for (int i = 1; i < args.size(); i++) {
            if (!(f.args().get(i) instanceof SqlAst.StringLit key)) {
                throw SqlTranslationException.unsupported("a path key other than a constant is not supported yet",
                        f.args().get(i).pos());
            }
            constantIndex(constant, key.value(), key.pos());
            got = pathElement(got, key.value(), key.pos());
        }
        return text ? jsonText(got) : got;
    }

    /** {@code to_jsonb(x)}: a text, number or boolean as jsonb's string, number or boolean. */
    static Tx toJson(SqlAst.FunctionCall f, List<Tx> args) {
        arity(f, args, 1);
        if (f.args().get(0) instanceof SqlAst.StringLit) {
            throw SqlTranslationException.invalid("could not determine polymorphic type because input has type"
                    + " unknown", f.pos());
        }
        Kind k = args.get(0).kind();
        if (k == Kind.JSON) {
            return args.get(0);
        }
        if (k != Kind.STRING && k != Kind.INTEGER && k != Kind.BOOLEAN && k != Kind.DECIMAL && k != Kind.FLOAT) {
            throw SqlTranslationException.unsupported(functionName(f.name().get(0)) + "(...) of a "
                    + k.name().toLowerCase(Locale.ROOT) + " is not supported yet", f.pos());
        }
        if (args.get(0).vs() instanceof CString text) {
            // a text constant is a jsonb string constant (its JSON spelling, quoted and escaped): legend-lite's
            // Postgres lowering spells toVariant of a string constant with no type, which Postgres cannot resolve
            return Tx.value(call(FROM_JSON, new CString(com.legend.json.Json.toCompact(text.value()))), Kind.JSON,
                    false);
        }
        return strict(Kind.JSON, args, v -> call("toVariant", v.get(0)));
    }

    private static final Set<String> REGEX_OPERATORS = Set.of("~", "~*", "!~", "!~*");

    /** jsonb's navigation operators: {@code ->} a member or an element, {@code ->>} its text, {@code #>} and
     *  {@code #>>} the same along a path. */
    private static final Set<String> JSON_OPERATORS = Set.of("->", "->>", "#>", "#>>");

    /** An operator Postgres's reading reads as no other node ({@link InputDialect#operator}): a jsonb navigation, or a
     *  regular expression match (refused); null for another. */
    static @com.legend.base.Nullable Tx readOperator(SqlAst.Operator o, SqlToPure.At at) {
        if (o.left() != null && JSON_OPERATORS.contains(o.operator())) {
            return jsonOperator(at.translator(), o, at.scope(), at.row());
        }
        if (REGEX_OPERATORS.contains(o.operator())) {
            throw SqlTranslationException.unsupported("regular expression operators (~, ~*, !~, !~*) are not supported"
                    + " yet", o.pos());
        }
        return null;
    }

    private static Tx jsonOperator(SqlToPure translator, SqlAst.Operator o, Scope scope, String row) {
        if (!o.operator().endsWith(">>")) {
            constantNavigation(java.util.Objects.requireNonNull(o.left()), o.pos());
        }
        Tx value = jsonOperand(translator.tx(java.util.Objects.requireNonNull(o.left()), scope, row), o.operator(),
                o.pos());
        Tx got;
        if (o.operator().startsWith("#")) {
            if (!(o.right() instanceof SqlAst.StringLit path)) {
                throw SqlTranslationException.unsupported("a path for " + o.operator() + " other than a constant"
                        + " ('{a,b}') is not supported yet", o.right().pos());
            }
            got = value;
            boolean constant = isJsonConstant(java.util.Objects.requireNonNull(o.left()));
            for (String key : jsonPath(path)) {
                constantIndex(constant, key, path.pos());
                got = pathElement(got, key, path.pos());
            }
        } else {
            got = navigate(value, o.right(), translator.tx(o.right(), scope, row), o.operator());
        }
        return o.operator().endsWith(">>") ? jsonText(got) : got;
    }

    /** {@code base[index]}: jsonb's subscript, a PATH element (Postgres's jsonb_get_element), not {@code ->}: an
     *  integer reads an array's element or an object's member named by its digits; a text reads an object's member or,
     *  if it is an integer's spelling, an array's element. */
    static Tx subscript(SqlToPure translator, SqlAst.Subscript sub, Scope scope, String row) {
        constantNavigation(sub.base(), sub.pos());
        Tx base = translator.tx(sub.base(), scope, row);
        if (base.kind() != Kind.JSON) {
            throw SqlTranslationException.unsupported("subscripts of values other than jsonb (arrays) are not"
                    + " supported yet", sub.pos());
        }
        SqlAst.Expr index = sub.index();
        if (index instanceof SqlAst.StringLit k) {
            return pathElement(base, k.value(), k.pos());
        }
        if (index instanceof SqlAst.IntegerLit n) {
            if (n.value().bitLength() >= 32) {
                throw SqlTranslationException.invalid("subscript type bigint is not supported", n.pos());
            }
            return pathElement(base, n.value().toString(), n.pos());
        }
        Tx key = translator.tx(index, scope, row);
        if (key.kind() == Kind.INTEGER) {
            // an array's element, else an object's member named by the integer's digits
            Tx got = strict(Kind.JSON, List.of(key), k -> call("coalesce", call("get", base.vs(), k.get(0)),
                    call("get", base.vs(), call("toString", k.get(0)))));
            return Tx.value(got.vs(), Kind.JSON, true);
        }
        if (key.kind() == Kind.STRING) {
            throw SqlTranslationException.unsupported("a subscript text other than a constant is not supported yet",
                    index.pos());
        }
        if (key.kind() == Kind.NULL || key.kind() == Kind.OTHER) {
            throw SqlTranslationException.unsupported("a jsonb subscript of this type is not supported yet",
                    index.pos());
        }
        throw SqlTranslationException.invalid("subscript type " + postgresName(key.kind()) + " is not supported",
                index.pos());
    }

    /** One element of a jsonb path ({@code #>}, a subscript, {@code jsonb_extract_path}), as Postgres reads it:
     *  an object's member named by the text; an array's element if the text is an integer's spelling (strtol's:
     *  leading white space, a sign, digits, nothing after, within int4), else none; a scalar's, none. */
    private static Tx pathElement(Tx value, String element, SqlAst.Pos pos) {
        Integer index = pathIndex(element);
        Tx member = member(value, element, pos);
        if (index == null) {
            return member;
        }
        // an object has no element and an array no member: whichever the value is, one of the two reads it
        return Tx.value(call("coalesce", member.vs(), call("get", value.vs(), integer(java.math.BigInteger.valueOf(
                index)))), Kind.JSON, true);
    }

    /** A path element that spells an integer, read out of a jsonb CONSTANT, is refused: whichever of an element or a
     *  member it reads is chosen in the database, and legend-lite's Postgres lowering folds a literal Variant's
     *  navigation (constantNavigation's reason). */
    private static void constantIndex(boolean constant, String element, SqlAst.Pos pos) {
        if (constant && pathIndex(element) != null) {
            throw SqlTranslationException.unsupported("a path element that spells an integer, read out of a jsonb"
                    + " constant, is not supported yet", pos);
        }
    }

    /** The array index a path element's text spells, as Postgres's strtoint reads it, or null. */
    private static @com.legend.base.Nullable Integer pathIndex(String text) {
        int i = 0;
        while (i < text.length() && " \t\n\u000b\f\r".indexOf(text.charAt(i)) >= 0) {
            i++;
        }
        String number = text.substring(i);
        if (!isIntegerText(number)) {
            return null;
        }
        try {
            return Integer.parseInt(number.startsWith("+") ? number.substring(1) : number);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** {@code value -> key}: a constant key's member (an object's, else NULL), or an integer's element (an array's,
     *  from 0; a negative one counts from the end; else NULL). */
    private static Tx navigate(Tx value, SqlAst.Expr keyExpr, Tx key, String operator) {
        if (keyExpr instanceof SqlAst.IntegerLit n && n.value().bitLength() >= 32) {
            throw SqlTranslationException.invalid("operator does not exist: jsonb " + operator + " bigint",
                    keyExpr.pos());
        }
        if (key.kind() == Kind.INTEGER) {
            Tx got = strict(Kind.JSON, List.of(key), k -> call("get", value.vs(), k.get(0)));
            return Tx.value(got.vs(), Kind.JSON, true);
        }
        if (key.kind() == Kind.STRING && keyExpr instanceof SqlAst.StringLit k) {
            return member(value, k.value(), k.pos());
        }
        if (key.kind() == Kind.STRING) {
            // DuckDB's arrow reads a key that starts with $ or / as a path: only a constant one is known not to
            throw SqlTranslationException.unsupported("a member key other than a constant is not supported yet",
                    keyExpr.pos());
        }
        if (key.kind() == Kind.NULL || key.kind() == Kind.OTHER) {
            throw SqlTranslationException.unsupported("a jsonb key of this type is not supported yet", keyExpr.pos());
        }
        throw SqlTranslationException.invalid("operator does not exist: jsonb " + operator + " "
                + key.kind().name().toLowerCase(Locale.ROOT), keyExpr.pos());
    }

    private static Tx member(Tx value, String key, SqlAst.Pos pos) {
        if (key.startsWith("$") || key.startsWith("/")) {
            throw SqlTranslationException.unsupported("a member key that starts with $ or / is read as a path by"
                    + " DuckDB; it is not supported yet", pos);
        }
        return Tx.value(call("get", value.vs(), new CString(key)), Kind.JSON, true);
    }

    /** {@code ->>}'s text: a string's own characters, a number's or a boolean's spelling; NULL for jsonb's null. */
    private static Tx jsonText(Tx got) {
        return Tx.value(call("to", got.vs(), typeArg("String")), Kind.STRING, true);
    }

    /** A text[] constant's elements, the keys of a jsonb path: {@code '{a,b,"c d"}'}. An element that is a number
     *  reads an array's element or an object's member, by the value it meets: it is not supported yet. */
    private static List<String> jsonPath(SqlAst.StringLit lit) {
        String text = lit.value().strip();
        if (!text.startsWith("{") || !text.endsWith("}")) {
            throw SqlTranslationException.unsupported("a path other than a plain array constant ('{a,b}') is not"
                    + " supported yet", lit.pos());
        }
        String body = text.substring(1, text.length() - 1);
        List<String> keys = new ArrayList<>();
        if (body.strip().isEmpty()) {
            return keys;
        }
        int i = 0;
        while (true) {
            while (i < body.length() && Character.isWhitespace(body.charAt(i))) {
                i++;
            }
            StringBuilder key = new StringBuilder();
            if (i < body.length() && body.charAt(i) == '"') {
                i++;
                while (i < body.length() && body.charAt(i) != '"') {
                    if (body.charAt(i) == '\\' && i + 1 < body.length()) {
                        i++;
                    }
                    key.append(body.charAt(i++));
                }
                if (i >= body.length()) {
                    throw SqlTranslationException.unsupported("a path with an unclosed quote is not supported",
                            lit.pos());
                }
                i++;
                while (i < body.length() && Character.isWhitespace(body.charAt(i))) {
                    i++;
                }
            } else {
                int start = i;
                while (i < body.length() && body.charAt(i) != ',') {
                    char c = body.charAt(i);
                    if (c == '"' || c == '{' || c == '}' || c == '\\') {
                        throw SqlTranslationException.unsupported("a path element with " + c + " is not supported yet",
                                lit.pos());
                    }
                    i++;
                }
                String bare = body.substring(start, i).strip();
                if (bare.isEmpty() || bare.equalsIgnoreCase("null")) {
                    throw SqlTranslationException.unsupported("an empty or NULL path element is not supported yet",
                            lit.pos());
                }
                key.append(bare);
            }
            keys.add(key.toString());
            if (i >= body.length()) {
                return keys;
            }
            if (body.charAt(i) != ',') {
                throw SqlTranslationException.unsupported("a malformed path constant is not supported", lit.pos());
            }
            i++;
        }
    }

    /** Whether {@code s} is an integer's digits, signed or not: a path element jsonb reads by the value it meets. */
    private static boolean isIntegerText(String s) {
        int start = !s.isEmpty() && (s.charAt(0) == '+' || s.charAt(0) == '-') ? 1 : 0;
        if (start == s.length()) {
            return false;
        }
        for (int i = start; i < s.length(); i++) {
            if (s.charAt(i) < '0' || s.charAt(i) > '9') {
                return false;
            }
        }
        return true;
    }
}
