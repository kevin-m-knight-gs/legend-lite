// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.testcases;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The openCypher TCK's value grammar (tck/README.adoc, "Values"), the one language both sides of the Cypher parity
 * judge speak (docs/CYPHER_STUDY_2026_10_15.md §8.3): the TCK's expected tables are written in it, the oracle writes
 * Neo4j's rows in it ({@code tools/neo4j-oracle/snap.py}, {@code fmt}), and a lite cell is read into it here. Values
 * compare structurally: a node by its label set and properties, never by an id (C5); a map by its keys; a list in
 * order, or as a bag where the scenario says so; a float within a tolerance.
 */
public final class CypherValues {

    private CypherValues() {
    }

    /** A value of the grammar. */
    public sealed interface Value permits Null, Bool, Int, Flt, Str, Lst, Mp, Node, Rel, Path, Opaque {
    }

    public record Null() implements Value {
    }

    public record Bool(boolean value) implements Value {
    }

    public record Int(BigInteger value) implements Value {
    }

    public record Flt(double value) implements Value {
    }

    public record Str(String value) implements Value {
    }

    public record Lst(List<Value> items) implements Value {
    }

    public record Mp(Map<String, Value> entries) implements Value {
    }

    /** {@code (:A:B {k: v})}: labels as a sorted set. */
    public record Node(TreeSet<String> labels, Map<String, Value> props) implements Value {
    }

    /** {@code [:T {k: v}]}. */
    public record Rel(String type, Map<String, Value> props) implements Value {
    }

    /** {@code <(a)-[:T]->(b)>}: its text, compared as written (nodes and paths are not values lite returns yet). */
    public record Path(String text) implements Value {
    }

    /** A value of a kind the grammar has no spelling for (a temporal, a point), as the oracle tags it. */
    public record Opaque(String text) implements Value {
    }

    // ------------------------------------------------------------------ reading

    /** The value {@code text} spells. */
    public static Value parse(String text) {
        Reader r = new Reader(text);
        Value v = r.value();
        r.ws();
        if (r.i != text.length()) {
            throw new IllegalArgumentException("value grammar: trailing text in " + text);
        }
        return v;
    }

    private static final class Reader {
        private final String s;
        private int i;

        Reader(String s) {
            this.s = s;
        }

        void ws() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
                i++;
            }
        }

        private char peek() {
            return i < s.length() ? s.charAt(i) : '\0';
        }

        private boolean take(String word) {
            if (s.startsWith(word, i)) {
                i += word.length();
                return true;
            }
            return false;
        }

        private void expect(char c) {
            ws();
            if (peek() != c) {
                throw new IllegalArgumentException("value grammar: expected '" + c + "' at " + i + " in " + s);
            }
            i++;
        }

        Value value() {
            ws();
            char c = peek();
            if (c == '\'') {
                return new Str(string());
            }
            if (c == '[') {
                int start = i;
                i++;
                ws();
                if (peek() == ':') {
                    i = start;
                    return rel();
                }
                i = start;
                return list();
            }
            if (c == '{') {
                return new Mp(map());
            }
            if (c == '(') {
                return node();
            }
            if (c == '<') {
                return path();
            }
            if (take("true")) {
                return new Bool(true);
            }
            if (take("false")) {
                return new Bool(false);
            }
            if (take("null")) {
                return new Null();
            }
            if (take("NaN")) {
                return new Flt(Double.NaN);
            }
            if (take("Inf")) {
                return new Flt(Double.POSITIVE_INFINITY);
            }
            if (take("-Inf")) {
                return new Flt(Double.NEGATIVE_INFINITY);
            }
            int start = i;
            if (peek() == '-' || peek() == '+') {
                i++;
            }
            while (i < s.length() && Character.isDigit(s.charAt(i))) {
                i++;
            }
            boolean flt = false;
            if (peek() == '.' && i + 1 < s.length() && Character.isDigit(s.charAt(i + 1))) {
                flt = true;
                i++;
                while (i < s.length() && Character.isDigit(s.charAt(i))) {
                    i++;
                }
            }
            if (peek() == 'e' || peek() == 'E') {
                int save = i;
                i++;
                if (peek() == '-' || peek() == '+') {
                    i++;
                }
                if (Character.isDigit(peek())) {
                    flt = true;
                    while (i < s.length() && Character.isDigit(s.charAt(i))) {
                        i++;
                    }
                } else {
                    i = save;
                }
            }
            String number = s.substring(start, i);
            if (number.isEmpty() || number.equals("-") || number.equals("+")) {
                // an opaque tagged value: Kind('text')
                i = start;
                return opaque();
            }
            return flt ? new Flt(Double.parseDouble(number)) : new Int(new BigInteger(number));
        }

        private Value opaque() {
            int start = i;
            while (i < s.length() && (Character.isLetterOrDigit(s.charAt(i)) || s.charAt(i) == '_')) {
                i++;
            }
            if (i == start || peek() != '(') {
                throw new IllegalArgumentException("value grammar: unexpected '" + peek() + "' at " + i + " in " + s);
            }
            int depth = 0;
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == '\'') {
                    string();
                    continue;
                }
                if (c == '(') {
                    depth++;
                } else if (c == ')') {
                    depth--;
                    if (depth == 0) {
                        i++;
                        break;
                    }
                }
                i++;
            }
            return new Opaque(s.substring(start, i));
        }

        private String string() {
            expect('\'');
            StringBuilder out = new StringBuilder();
            while (true) {
                if (i >= s.length()) {
                    throw new IllegalArgumentException("value grammar: unterminated string in " + s);
                }
                char c = s.charAt(i++);
                if (c == '\'') {
                    return out.toString();
                }
                if (c == '\\') {
                    char e = s.charAt(i++);
                    out.append(switch (e) {
                        case 'n' -> '\n';
                        case 't' -> '\t';
                        case 'r' -> '\r';
                        default -> e;
                    });
                } else {
                    out.append(c);
                }
            }
        }

        private Value list() {
            expect('[');
            List<Value> items = new ArrayList<>();
            ws();
            if (peek() == ']') {
                i++;
                return new Lst(items);
            }
            while (true) {
                items.add(value());
                ws();
                if (peek() == ',') {
                    i++;
                    continue;
                }
                expect(']');
                return new Lst(items);
            }
        }

        private Map<String, Value> map() {
            expect('{');
            Map<String, Value> out = new TreeMap<>();
            ws();
            if (peek() == '}') {
                i++;
                return out;
            }
            while (true) {
                ws();
                String key = key();
                expect(':');
                out.put(key, value());
                ws();
                if (peek() == ',') {
                    i++;
                    continue;
                }
                expect('}');
                return out;
            }
        }

        private String key() {
            if (peek() == '`') {
                i++;
                int start = i;
                while (i < s.length() && s.charAt(i) != '`') {
                    i++;
                }
                String k = s.substring(start, i);
                i++;
                return k;
            }
            int start = i;
            while (i < s.length() && (Character.isLetterOrDigit(s.charAt(i)) || s.charAt(i) == '_')) {
                i++;
            }
            if (start == i) {
                throw new IllegalArgumentException("value grammar: expected a key at " + i + " in " + s);
            }
            return s.substring(start, i);
        }

        private Value node() {
            expect('(');
            TreeSet<String> labels = new TreeSet<>();
            ws();
            while (peek() == ':') {
                i++;
                labels.add(key());
                ws();
            }
            Map<String, Value> props = peek() == '{' ? map() : Map.of();
            expect(')');
            return new Node(labels, props);
        }

        private Value rel() {
            expect('[');
            expect(':');
            String type = key();
            ws();
            Map<String, Value> props = peek() == '{' ? map() : Map.of();
            expect(']');
            return new Rel(type, props);
        }

        private Value path() {
            int start = i;
            int depth = 0;
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == '\'') {
                    string();
                    continue;
                }
                if (c == '<') {
                    depth++;
                } else if (c == '>') {
                    depth--;
                    if (depth == 0) {
                        i++;
                        break;
                    }
                }
                i++;
            }
            return new Path(s.substring(start, i).replaceAll("\\s+", " "));
        }
    }

    // ------------------------------------------------------------------ lite's cells

    /** A cell of a lite result as a value: integers, floats, strings, booleans and null; another kind as the text
     *  the result spells it. */
    public static Value ofCell(@com.legend.base.Nullable Object cell) {
        if (cell == null) {
            return new Null();
        }
        if (cell instanceof Boolean b) {
            return new Bool(b);
        }
        if (cell instanceof BigInteger n) {
            return new Int(n);
        }
        if (cell instanceof Long || cell instanceof Integer || cell instanceof Short || cell instanceof Byte) {
            return new Int(BigInteger.valueOf(((Number) cell).longValue()));
        }
        if (cell instanceof BigDecimal d) {
            return d.scale() <= 0 && d.stripTrailingZeros().scale() <= 0 && isWhole(d) ? new Int(d.toBigInteger())
                    : new Flt(d.doubleValue());
        }
        if (cell instanceof Double || cell instanceof Float) {
            return new Flt(((Number) cell).doubleValue());
        }
        if (cell instanceof String s) {
            return new Str(s);
        }
        return new Opaque(cell.getClass().getSimpleName() + "(" + new Str(cell.toString()) + ")");
    }

    private static boolean isWhole(BigDecimal d) {
        return d.signum() == 0 || d.stripTrailingZeros().scale() <= 0;
    }

    // ------------------------------------------------------------------ comparing

    /** Two doubles agree within this many units in the last place (an average summed in another order; the host
     *  judge's rule, docs/SEMANTICS_REGISTER.md S15); a signed zero is its own value (Neo4j's -0.0). */
    private static final int ULPS = 2;

    /** Whether {@code a} and {@code b} are the same value; {@code bagLists} compares lists as bags. */
    public static boolean equal(Value a, Value b, boolean bagLists) {
        if (a instanceof Int x && b instanceof Int y) {
            return x.value().equals(y.value());
        }
        if (a instanceof Flt x && b instanceof Flt y) {
            return sameDouble(x.value(), y.value());
        }
        if (a instanceof Int x && b instanceof Flt y || a instanceof Flt x2 && b instanceof Int y2) {
            // an integer and a float are different values in Cypher (1 = 1.0 is true, but a result cell typed one
            // way is not the other): the grammar keeps them apart
            return false;
        }
        if (a instanceof Lst x && b instanceof Lst y) {
            if (x.items().size() != y.items().size()) {
                return false;
            }
            if (!bagLists) {
                for (int i = 0; i < x.items().size(); i++) {
                    if (!equal(x.items().get(i), y.items().get(i), false)) {
                        return false;
                    }
                }
                return true;
            }
            return sameBag(x.items(), y.items(), true);
        }
        if (a instanceof Mp x && b instanceof Mp y) {
            return sameMap(x.entries(), y.entries(), bagLists);
        }
        if (a instanceof Node x && b instanceof Node y) {
            return x.labels().equals(y.labels()) && sameMap(x.props(), y.props(), bagLists);
        }
        if (a instanceof Rel x && b instanceof Rel y) {
            return x.type().equals(y.type()) && sameMap(x.props(), y.props(), bagLists);
        }
        return a.equals(b);
    }

    private static boolean sameMap(Map<String, Value> x, Map<String, Value> y, boolean bagLists) {
        if (!x.keySet().equals(y.keySet())) {
            return false;
        }
        for (Map.Entry<String, Value> e : x.entrySet()) {
            if (!equal(e.getValue(), y.get(e.getKey()), bagLists)) {
                return false;
            }
        }
        return true;
    }

    private static boolean sameDouble(double x, double y) {
        if (Double.isNaN(x) || Double.isNaN(y)) {
            return Double.isNaN(x) && Double.isNaN(y);
        }
        if (x == y) {
            return Double.doubleToLongBits(x) == Double.doubleToLongBits(y);
        }
        if (x == 0 || y == 0 || Double.isInfinite(x) || Double.isInfinite(y)) {
            return false;
        }
        return Math.abs(x - y) <= ULPS * Math.ulp(Math.max(Math.abs(x), Math.abs(y)));
    }

    /** Whether two lists hold the same values, each as many times. */
    public static boolean sameBag(List<Value> x, List<Value> y, boolean bagLists) {
        if (x.size() != y.size()) {
            return false;
        }
        List<Value> left = new ArrayList<>(y);
        for (Value v : x) {
            int found = -1;
            for (int i = 0; i < left.size(); i++) {
                if (equal(v, left.get(i), bagLists)) {
                    found = i;
                    break;
                }
            }
            if (found < 0) {
                return false;
            }
            left.remove(found);
        }
        return true;
    }

    /** A row of values. */
    public static List<Value> row(List<String> cells) {
        List<Value> out = new ArrayList<>();
        for (String c : cells) {
            out.add(parse(c));
        }
        return out;
    }

    /** Whether two tables of rows agree: in order, or as bags of rows. */
    public static boolean sameRows(List<List<Value>> expected, List<List<Value>> actual, boolean ordered,
            boolean bagLists) {
        if (expected.size() != actual.size()) {
            return false;
        }
        if (ordered) {
            for (int i = 0; i < expected.size(); i++) {
                if (!sameRow(expected.get(i), actual.get(i), bagLists)) {
                    return false;
                }
            }
            return true;
        }
        List<List<Value>> left = new ArrayList<>(actual);
        for (List<Value> row : expected) {
            int found = -1;
            for (int i = 0; i < left.size(); i++) {
                if (sameRow(row, left.get(i), bagLists)) {
                    found = i;
                    break;
                }
            }
            if (found < 0) {
                return false;
            }
            left.remove(found);
        }
        return true;
    }

    private static boolean sameRow(List<Value> a, List<Value> b, boolean bagLists) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (!equal(a.get(i), b.get(i), bagLists)) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ writing

    /** A value in the grammar, as the oracle writes it. */
    public static String text(Value v) {
        return switch (v) {
            case Null n -> "null";
            case Bool b -> String.valueOf(b.value());
            case Int i -> i.value().toString();
            case Flt f -> Double.isNaN(f.value()) ? "NaN" : Double.isInfinite(f.value()) ? (f.value() > 0 ? "Inf"
                    : "-Inf") : Double.toString(f.value());
            case Str s -> "'" + s.value().replace("\\", "\\\\").replace("'", "\\'") + "'";
            case Lst l -> "[" + String.join(", ", l.items().stream().map(CypherValues::text).toList()) + "]";
            case Mp m -> mapText(m.entries());
            case Node n -> "(" + String.join("", n.labels().stream().map(l -> ":" + l).toList())
                    + (n.props().isEmpty() ? "" : " " + mapText(n.props())) + ")";
            case Rel r -> "[:" + r.type() + (r.props().isEmpty() ? "" : " " + mapText(r.props())) + "]";
            case Path p -> p.text();
            case Opaque o -> o.text();
        };
    }

    private static String mapText(Map<String, Value> m) {
        List<String> parts = new ArrayList<>();
        new TreeMap<>(m).forEach((k, v) -> parts.add(k + ": " + text(v)));
        return "{" + String.join(", ", parts) + "}";
    }

    /** A row's cells as text, for a report. */
    public static String rowText(List<Value> row) {
        List<String> parts = new ArrayList<>();
        for (Value v : row) {
            parts.add(text(v));
        }
        return "[" + String.join(", ", parts) + "]";
    }

    /** The values of a map literal's text, in key order. */
    public static Map<String, Value> mapOf(String text) {
        Value v = parse(text);
        if (v instanceof Mp m) {
            return new LinkedHashMap<>(m.entries());
        }
        throw new IllegalArgumentException("not a map: " + text);
    }
}
