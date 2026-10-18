// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.exec;

import com.legend.executionplan.ExecutionPlan;
import com.legend.json.Json;
import com.legend.values.LiteralText;
import com.legend.values.PureDateLiteral;
import com.legend.values.PureTimeLiteral;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * THE ROW DECODE (the plan path program's step 6, docs/PLAN_PATH_PROGRAM_2026_10_17.md §6, 2026-10-18): a tabular
 * result's row, written by the database as one JSON object ({@link ExecutionPlan.TdsResult}), read into cells by the
 * plan's own columns -- each cell by its column's declared Pure type and, where the type alone does not say the
 * carrier (a {@code Number}, a literal-spelled or text-carried cell), by its slot. No model: the plan's three type
 * facts are the whole instruction. The cells are the carriers the host adjudication layer reads (the Z2 ruling's
 * carrier-decode bridge, docs/TENET_CHARTER.md Clause 2c): a date kind as {@link PureDateLiteral}, a time as
 * {@link PureTimeLiteral}, a Decimal as its exact {@link BigDecimal}, an Integer as a {@code long}, a Float as a
 * {@code double}, a class instance as the structure its JSON object spells. Nothing is computed; a token no arm reads
 * is refused by name, never guessed.
 */
public final class RowDecode {

    private RowDecode() {
    }

    /** The variant class's path (the plan's type vocabulary names a class by its path; no model here). */
    private static final String VARIANT = "meta::pure::metamodel::variant::Variant";

    /** One row's cells, in {@code columns}' order: each column read from the object's field of its name, or, when no
     *  field is named after any column and the counts agree, by position (a value root's one column, which the wire
     *  names after its output). */
    public static List<Object> cells(String rowJson, List<ExecutionPlan.Column> columns) {
        Json.Node parsed = Json.parse(rowJson);
        if (!(parsed instanceof Json.Obj row)) {
            throw new IllegalStateException("the statement wrote a row that is no JSON object: "
                    + excerpt(rowJson));
        }
        boolean anyByName = columns.stream().anyMatch(c -> row.fields().containsKey(c.name()));
        boolean positional = !anyByName && row.fields().size() == columns.size();
        List<Json.Node> byPosition = positional ? new ArrayList<>(row.fields().values()) : List.of();
        List<Object> out = new ArrayList<>(columns.size());
        for (int i = 0; i < columns.size(); i++) {
            ExecutionPlan.Column c = columns.get(i);
            Json.Node node = positional ? byPosition.get(i) : row.fields().get(c.name());
            if (node == null) {
                throw new IllegalStateException("the row's object has no field for the plan's column '" + c.name()
                        + "' (it has " + row.fields().keySet() + "): the statement does not write the plan's columns");
            }
            out.add(cell(node, c));
        }
        return out;
    }

    /** {@code node} as the cell of {@code c}: by the SLOT first -- its kind is the carrier's, as the legacy executor's
     *  label-driven unwrap read a driver's cell by the output's SQL type -- and by the declared Pure type where the
     *  slot is none, a structure's or JSON. A string token under a numeric slot is the TEXT the database holds unless
     *  the Pure type is itself numeric (the legacy executor kept a driver's String under an integral label: the
     *  engine's test store declares {@code interactionTable.ID INT} over a column its DDL creates VARCHAR, and the
     *  String-typed {@code Interaction.id} reads {@code '7'}; measured on the corpus, 2026-10-18); a numeric Pure type
     *  parses the token's digits, for a wire that quotes its numbers. */
    static @com.legend.base.Nullable Object cell(Json.Node node, ExecutionPlan.Column c) {
        if (node instanceof Json.Null) {
            return null;
        }
        String type = c.type();
        if (type.equals(VARIANT)) {
            // a variant keeps its JSON text (the legacy executor's contract for a variant root)
            return Json.toCompact(node);
        }
        String slot = slotWord(c.slot());
        switch (slot) {
            // the slot labels that ARE the decode instruction: a literal-spelled cell carries its own kind; a
            // text-carried temporal or decimal parses
            case "LITERAL" -> {
                return LiteralText.parse(text(node, c));
            }
            case "DECIMAL_TEXT" -> {
                String s = text(node, c);
                return new BigDecimal(s.endsWith("D") || s.endsWith("d") ? s.substring(0, s.length() - 1) : s);
            }
            case "TEMPORAL_TEXT", "DATE", "TIMESTAMP", "TIMESTAMPTZ" -> {
                return PureDateLiteral.parse(text(node, c).trim().replace(' ', 'T'));
            }
            case "TIME" -> {
                return PureTimeLiteral.parse(text(node, c).trim());
            }
            // the SQL kinds: the carrier is the slot's -- but a string token is the column's own text (above)
            case "INTEGER", "BIGINT", "SMALLINT", "TINYINT", "HUGEINT" -> {
                return node instanceof Json.Str s && !numericPure(type) ? s.value() : integer(node, c);
            }
            case "DOUBLE", "FLOAT", "REAL" -> {
                return node instanceof Json.Str s && !numericPure(type) ? s.value() : floating(node, c);
            }
            case "DECIMAL", "NUMERIC" -> {
                return node instanceof Json.Str s && !numericPure(type) ? s.value() : decimal(node, c);
            }
            case "BOOLEAN" -> {
                if (node instanceof Json.Bool b) {
                    return b.value();
                }
                throw refused(node, c);
            }
            case "VARCHAR", "CHAR", "TEXT" -> {
                return text(node, c);
            }
            default -> {
                // no slot, or a JSON or structured one: the Pure type decides
            }
        }
        if (type.equals("Integer")) {
            return integer(node, c);
        }
        if (type.equals("Float")) {
            return floating(node, c);
        }
        if (type.equals("Decimal") || type.startsWith("Decimal(")) {
            return decimal(node, c);
        }
        if (type.equals("Number")) {
            return switch (node) {
                case Json.Num n when n.isInteger() -> (Object) n.longValue();
                case Json.Num n -> exactDecimal(n);
                default -> throw refused(node, c);
            };
        }
        if (type.equals("String")) {
            return text(node, c);
        }
        if (type.equals("Boolean")) {
            if (node instanceof Json.Bool b) {
                return b.value();
            }
            throw refused(node, c);
        }
        if (type.equals("StrictDate") || type.equals("DateTime") || type.equals("Date")) {
            return PureDateLiteral.parse(text(node, c).trim().replace(' ', 'T'));
        }
        if (type.equals("Time")) {
            return PureTimeLiteral.parse(text(node, c).trim());
        }
        // a class instance, an enumeration's value, Any: the token's own kind, as the legacy executor's Any lane
        // reads a JSON cell (a string, a boolean, a whole number as a long, any other number as a double, a
        // structure as its map or list)
        return switch (node) {
            case Json.Str s -> s.value();
            case Json.Bool b -> b.value();
            case Json.Num n -> n.isInteger() ? (Object) n.longValue() : (Object) n.doubleValue();
            case Json.Obj o -> plain(o);
            case Json.Arr a -> plain(a);
            case Json.Null nil -> null;
        };
    }

    /** Whether the Pure type {@code type} is a number's: Integer, Float, Decimal (with or without its digits) or
     *  Number. */
    private static boolean numericPure(String type) {
        return type.equals("Integer") || type.equals("Float") || type.equals("Decimal") || type.startsWith("Decimal(")
                || type.equals("Number");
    }

    /** A structure as plain Java: an object an ordered map, an array a list, a whole number a long, any other
     *  number its exact decimal (the SQL layer's JSON reader's rule, which the legacy executor's structures took). */
    private static @com.legend.base.Nullable Object plain(Json.Node node) {
        return switch (node) {
            case Json.Obj o -> {
                Map<String, Object> m = new LinkedHashMap<>();
                o.fields().forEach((k, v) -> m.put(k, plain(v)));
                yield m;
            }
            case Json.Arr a -> {
                List<Object> l = new ArrayList<>(a.items().size());
                for (Json.Node item : a.items()) {
                    l.add(plain(item));
                }
                yield l;
            }
            case Json.Str s -> s.value();
            case Json.Bool b -> b.value();
            case Json.Num n -> n.isInteger() ? (Object) n.longValue() : (Object) exactDecimal(n);
            case Json.Null nil -> null;
        };
    }

    private static Object integer(Json.Node node, ExecutionPlan.Column c) {
        BigInteger whole = switch (node) {
            case Json.Num n when n.isInteger() -> BigInteger.valueOf(n.longValue());
            case Json.Num n when n.decimalValue() != null && n.decimalValue().stripTrailingZeros().scale() <= 0 ->
                    n.decimalValue().toBigIntegerExact();
            case Json.Str s -> new BigInteger(s.value().trim());
            default -> throw refused(node, c);
        };
        return whole.bitLength() < 63 ? (Object) whole.longValue() : whole;
    }

    private static Object floating(Json.Node node, ExecutionPlan.Column c) {
        return switch (node) {
            case Json.Num n -> n.doubleValue();
            case Json.Str s -> Double.valueOf(s.value().trim());   // a Float the wire spells as text (NaN, Infinity)
            default -> throw refused(node, c);
        };
    }

    private static BigDecimal decimal(Json.Node node, ExecutionPlan.Column c) {
        return switch (node) {
            case Json.Num n -> exactDecimal(n);
            case Json.Str s -> new BigDecimal(s.value().trim());
            default -> throw refused(node, c);
        };
    }

    /** The number's exact decimal: its token's digits (the scale as written), never the double's. */
    private static BigDecimal exactDecimal(Json.Num n) {
        if (n.decimalValue() != null) {
            return n.decimalValue();
        }
        if (n.token() != null) {
            return new BigDecimal(n.token());
        }
        return n.isInteger() ? BigDecimal.valueOf(n.longValue()) : BigDecimal.valueOf(n.doubleValue());
    }

    private static String text(Json.Node node, ExecutionPlan.Column c) {
        if (node instanceof Json.Str s) {
            return s.value();
        }
        // a Decimal's token is written bare; a text-typed cell the database wrote as a number keeps its digits
        if (node instanceof Json.Num n && n.token() != null) {
            return n.token();
        }
        if (node instanceof Json.Num n) {
            return n.isInteger() ? Long.toString(n.longValue()) : exactDecimal(n).toPlainString();
        }
        throw refused(node, c);
    }

    /** The slot's leading word: {@code DECIMAL} of {@code DECIMAL(10,2)}, {@code ARRAY} of {@code ARRAY<...>}. */
    private static String slotWord(@com.legend.base.Nullable String slot) {
        if (slot == null) {
            return "";
        }
        int end = slot.length();
        for (int i = 0; i < slot.length(); i++) {
            char ch = slot.charAt(i);
            if (ch == '(' || ch == '<' || ch == ' ') {
                end = i;
                break;
            }
        }
        return slot.substring(0, end);
    }

    private static IllegalStateException refused(Json.Node node, ExecutionPlan.Column c) {
        return new IllegalStateException("column '" + c.name() + "' is declared " + c.type()
                + (c.slot() == null ? "" : " in slot " + c.slot()) + ", and its cell's token " + excerpt(node)
                + " is not of that kind");
    }

    private static String excerpt(Json.Node node) {
        return excerpt(Json.toCompact(node));
    }

    private static String excerpt(String s) {
        return s.length() > 120 ? s.substring(0, 120) + "..." : s;
    }
}
