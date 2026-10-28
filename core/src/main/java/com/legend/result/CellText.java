// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.result;

import com.legend.executionplan.ExecutionPlan;
import com.legend.json.Json;
import com.legend.values.PureDateLiteral;
import com.legend.values.PureTimeLiteral;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;

/**
 * A typed cell's TEXT at the edge (docs/RESULT_BOUNDARY_PROGRAM_2026_10_26.md R3, R5; 2026-10-27): the spellings the
 * writers use, each a function of the session's carrier and the plan's column facts alone. A spelling is
 * serialization, never computation: it chooses digits for a value it holds whole (a Float's shortest digits, a
 * Decimal's own scale, a DateTime's fraction), converts no kind, shifts no zone and defaults no null. Two vocabularies:
 * Pure's print forms ({@link #pure}), lite's own outputs' and interpreted Pure's; and legend-engine's JSON tokens
 * ({@link #engine}), its {@code pure/v1} execute's. What the database spelled for these until the result boundary
 * program's phase 3 (the lowering's CSV and JSON wires, legend-engine's Float and DateTime text in SQL) is the same
 * rule, written where it belongs; those spellings are deleted.
 */
public final class CellText {

    private CellText() {
    }

    /** Pure's text of a non-null cell of column {@code c}: an Integer's digits; a Float's shortest digits laid out as
     *  Pure prints them (a point always, never an exponent); a Decimal at its own scale; a boolean; a text as it is;
     *  a StrictDate's day; a DateTime at its precision with {@code +0000}; a structure or a text-carried value as its
     *  own text. */
    public static String pure(Object cell, ExecutionPlan.Column c) {
        return pure(cell, c.type(), c);
    }

    /** {@link #pure} of a cell, or a structure's element, of Pure type {@code type} ({@code c} the column it is in). */
    private static String pure(Object cell, String type, ExecutionPlan.Column c) {
        String collection = collectionClass(type);
        return switch (cell) {
            case Long l -> Long.toString(l);
            case BigInteger b -> b.toString();
            case Double d -> pureFloat(d);
            case BigDecimal d -> type.equals("Float") ? pureFloat(d) : d.toPlainString();
            case Boolean b -> b.toString();
            case String s -> s;
            case PureDateLiteral d -> pureDate(d, type, c);
            case PureTimeLiteral t -> t.toEngineString();
            // a List prints its values' texts joined (the prelude's List.toString(): joinStrings('[', ', ', ']'))
            case List<?> l when collection.equals("List") -> joined(l, typeArguments(type).get(0), c, "[", "]");
            // a Pair prints '<first, second>' (the prelude's Pair.toString())
            case Map<?, ?> m when collection.equals("Pair") -> {
                List<String> args = typeArguments(type);
                yield "<" + element(m.get("first"), args.get(0), c) + ", " + element(m.get("second"), args.get(1), c)
                        + ">";
            }
            // a Map prints its entries as 'key=value' in braces, comma-joined: legend-pure's map's own text (its Java
            // map's), the form every output wrote it in until now
            case Map<?, ?> m when collection.equals("Map") -> {
                List<String> args = typeArguments(type);
                StringBuilder out = new StringBuilder("{");
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    if (out.length() > 1) {
                        out.append(", ");
                    }
                    out.append(element(e.getKey(), args.get(0), c)).append('=')
                            .append(element(e.getValue(), args.get(1), c));
                }
                yield out.append('}').toString();
            }
            // a user class's instance prints its repository id, interpreted Pure's anonymous instance name (the
            // lowering's own rule, Scalars.pureToString: "@_" and the instance's identity)
            case Map<?, ?> m when m.get(SYNTHETIC_ID) instanceof String id -> "@_" + id;
            default -> throw refused(cell, c, "Pure's text");
        };
    }

    /** A structure's element as Pure prints it inside its parent's text. A Pure collection holds no null, so a null
     *  element is a decode fault, refused by name rather than printed as some text. */
    private static String element(@com.legend.base.Nullable Object v, String type, ExecutionPlan.Column c) {
        if (v == null) {
            throw new IllegalArgumentException("column '" + c.name() + "' (" + c.type() + "): a null " + type
                    + " inside a structure has no Pure text");
        }
        return pure(v, type, c);
    }

    private static String joined(List<?> l, String elementType, ExecutionPlan.Column c, String open, String close) {
        StringBuilder out = new StringBuilder(open);
        for (int i = 0; i < l.size(); i++) {
            if (i > 0) {
                out.append(", ");
            }
            out.append(element(l.get(i), elementType, c));
        }
        return out.append(close).toString();
    }

    /** The platform collection class a Pure type names ({@code List}, {@code Pair}, {@code Map}), else the empty
     *  text: the class's name with or without its package, before its type arguments. */
    private static String collectionClass(String type) {
        int lt = type.indexOf('<');
        if (lt < 0) {
            return "";
        }
        String name = type.substring(0, lt).trim();
        if (name.startsWith(COLLECTION_PACKAGE)) {
            name = name.substring(COLLECTION_PACKAGE.length());
        }
        return name.equals("List") || name.equals("Pair") || name.equals("Map") ? name : "";
    }

    private static final String COLLECTION_PACKAGE = "meta::pure::functions::collection::";

    /** The type arguments of a generic type's text, split at its top-level commas ({@code Pair<Pair<Integer, String>,
     *  Integer>} is {@code Pair<Integer, String>} and {@code Integer}). */
    static List<String> typeArguments(String type) {
        int lt = type.indexOf('<');
        if (lt < 0 || !type.endsWith(">")) {
            throw new IllegalArgumentException("'" + type + "' has no type arguments");
        }
        List<String> out = new java.util.ArrayList<>();
        int depth = 0;
        int start = lt + 1;
        for (int i = start; i < type.length() - 1; i++) {
            char ch = type.charAt(i);
            if (ch == '<') {
                depth++;
            } else if (ch == '>') {
                depth--;
            } else if (ch == ',' && depth == 0) {
                out.add(type.substring(start, i).trim());
                start = i + 1;
            }
        }
        out.add(type.substring(start, type.length() - 1).trim());
        return out;
    }

    /** legend-engine's JSON token of a cell of column {@code c} ({@code pure/v1} execute, measured 4.145.0): null as
     *  {@code null}; an Integer's digits; a Float as Java's {@code Double.toString} writes the double; a Decimal at its
     *  own scale (register S104); a boolean; a text as a string; a StrictDate's day, and a DateTime's nine fraction
     *  digits with {@code +0000}, as strings; a Variant as a string holding its JSON (measured, 4.145.0, 2026-10-28:
     *  PARK-34's probe; the engine re-spells the JSON's numbers through its JSON library, lite keeps the database's
     *  digits: register S112); a structure as its own JSON. */
    public static Json.Node engine(@com.legend.base.Nullable Object cell, ExecutionPlan.Column c) {
        if (cell == null) {
            return Json.nil();
        }
        return switch (cell) {
            case Long l -> Json.num(l);
            case BigInteger b -> token(b.toString(), new BigDecimal(b));
            case Double d -> engineFloat(d);
            case BigDecimal d -> isFloat(c) ? engineFloat(d.doubleValue()) : token(d.toPlainString(), d);
            case Boolean b -> Json.bool(b);
            case String s -> Json.str(s);
            case PureDateLiteral d -> Json.str(engineDay(d, c) ? day(d) : d.toEngineJson());
            case PureTimeLiteral t -> Json.str(t.toEngineString());
            case Map<?, ?> m -> structure(declared(m), false, c);
            case List<?> l -> structure(declared(l), false, c);
            default -> throw refused(cell, c, "legend-engine's JSON token");
        };
    }

    /** A structure's JSON (an object's declared properties, a List's elements, a Pair's, a Map's entries), each value
     *  by the rules of a cell of its own kind -- a float, a date, a time spelled as one is, never as the JSON library
     *  would spell a Java value: legend-engine's tokens, or Pure's where {@code pure}. A nested value has no column of
     *  its own, so a date is its day where it has no time of day, else a timestamp's text. */
    private static Json.Node structure(@com.legend.base.Nullable Object v, boolean pure, ExecutionPlan.Column c) {
        return switch (v) {
            case null -> Json.nil();
            case Map<?, ?> m -> {
                java.util.LinkedHashMap<String, Json.Node> fields = new java.util.LinkedHashMap<>();
                m.forEach((k, x) -> fields.put(String.valueOf(k), structure(x, pure, c)));
                yield new Json.Obj(fields);
            }
            case List<?> l -> new Json.Arr(l.stream().map(x -> structure(x, pure, c)).toList());
            case Long n -> Json.num(n);
            case Integer n -> Json.num(n);
            case BigInteger b -> token(b.toString(), new BigDecimal(b));
            case Double d -> !pure ? engineFloat(d)
                    : Double.isFinite(d) ? token(pureFloat(d), BigDecimal.valueOf(d)) : Json.str(pureFloat(d));
            case BigDecimal d -> token(d.toPlainString(), d);
            case Boolean b -> Json.bool(b);
            case String s -> Json.str(s);
            case PureDateLiteral d -> Json.str(!d.precision().atLeast(PureDateLiteral.Precision.HOUR) ? d.toEngineString()
                    : pure ? d.trimmed().toEngineString() + "+0000" : d.toEngineJson());
            case PureTimeLiteral t -> Json.str(t.toEngineString());
            default -> throw refused(v, c, pure ? "Pure's JSON token" : "legend-engine's JSON token");
        };
    }

    /** Lite's own JSON token of a cell (its CSV's and JSON's values are Pure's): a Float as Pure's text, a Decimal at
     *  its scale, a date kind as a string of Pure's text, the rest as {@link #engine} writes it. */
    public static Json.Node pureJson(@com.legend.base.Nullable Object cell, ExecutionPlan.Column c) {
        if (cell == null) {
            return Json.nil();
        }
        return switch (cell) {
            case Double d -> Double.isFinite(d) ? token(pureFloat(d), BigDecimal.valueOf(d)) : Json.str(pureFloat(d));
            case BigDecimal d -> token(isFloat(c) ? pureFloat(d) : d.toPlainString(), d);
            case PureDateLiteral d -> Json.str(pureDate(d, c.type(), c));
            case Map<?, ?> m -> structure(declared(m), true, c);
            case List<?> l -> structure(declared(l), true, c);
            default -> engine(cell, c);
        };
    }

    /** {@code name} as legend-engine labels a column (its {@code mayQuotePivotColNames}, pureToSQLQuery.pure:2985;
     *  the compiler's {@code Type.RelationType.presentPivotName}): a name holding the pivot separator {@code __|__}
     *  that is not already quote-wrapped carries literal single quotes as part of the NAME. */
    public static String presentedName(String name) {
        return name.contains("__|__") && !(name.startsWith("'") && name.endsWith("'")) ? "'" + name + "'" : name;
    }

    // ---- Float ----------------------------------------------------------------------------------------------------

    /** Pure's layout of a double: its shortest digits ({@code Double.toString}'s, which read back as the same double),
     *  an exponent laid out positionally with a point always ({@code 1500000000000000.0}, {@code 0.00000025}); NaN and
     *  the infinities as Java names them. A negative zero keeps its sign, {@code -0.0}: it is another double (the
     *  user, 2026-10-28; Pure's own {@code toString} writes {@code 0.0} for either, as lite's SQL does: register
     *  S114). */
    static String pureFloat(double d) {
        String shortest = Double.toString(d);
        if (shortest.indexOf('E') < 0) {
            return shortest;
        }
        String plain = new BigDecimal(shortest).toPlainString();
        return plain.contains(".") ? plain : plain + ".0";
    }

    /** Pure's text of a Float the database holds exactly (a decimal slot under a Float column): its digits without
     *  trailing zeros, a point always. */
    static String pureFloat(BigDecimal d) {
        String plain = d.stripTrailingZeros().toPlainString();
        return plain.contains(".") ? plain : plain + ".0";
    }

    /** legend-engine's token of a double: Java's {@code Double.toString}; a non-finite double, which JSON has no token
     *  for, as its name in a string (the engine's serializer writes it so). */
    private static Json.Node engineFloat(double d) {
        return Double.isFinite(d) ? token(Double.toString(d), BigDecimal.valueOf(d)) : Json.str(Double.toString(d));
    }

    private static boolean isFloat(ExecutionPlan.Column c) {
        return c.type().equals("Float");
    }

    // ---- dates ----------------------------------------------------------------------------------------------------

    /** Pure's text of a date: a value carried as its own text at the precision it was written (a year, a month, a
     *  day, an hour ... a fraction), {@code +0000} from an hour on; a day as it is; a database timestamp at its
     *  precision, the fraction's trailing zeros dropped; an abstract Date's midnight its day. */
    private static String pureDate(PureDateLiteral d, String type, ExecutionPlan.Column c) {
        if (writtenPrecision(c)) {
            return d.precision().atLeast(PureDateLiteral.Precision.HOUR) ? d.toEngineString() + "+0000"
                    : d.toEngineString();
        }
        if (dateOnly(d, type, c)) {
            return day(d);
        }
        return d.trimmed().toEngineString() + "+0000";
    }

    /** Whether the column carries a date at the precision it was written: as its own text, or a literal's spelling. */
    private static boolean writtenPrecision(ExecutionPlan.Column c) {
        String slot = slotWord(c.slot());
        return slot.equals("TEMPORAL_TEXT") || slot.equals("LITERAL");
    }

    /** Whether a date is written as its day: a StrictDate column's, a DATE slot's, a value with no time of day, or an
     *  abstract Date at midnight (legend-engine's formatDateTime rule for a Date: by its time of day). */
    private static boolean dateOnly(PureDateLiteral d, String type, ExecutionPlan.Column c) {
        if (!d.precision().atLeast(PureDateLiteral.Precision.HOUR)) {
            return true;
        }
        if (type.equals("StrictDate") || (type.equals(c.type()) && slotWord(c.slot()).equals("DATE"))) {
            return true;
        }
        return type.equals("Date") && midnight(d);
    }

    /** Whether legend-engine writes a date as its day: by what the database answered, never by the time of day --
     *  its serializer writes a {@code java.sql.Timestamp} in full and only a {@code java.sql.Date} as its day
     *  ({@code ValueTransformer}, legend-engine's relational execution), so a day without a time, a StrictDate's, a
     *  DATE slot's. A Date at midnight in a TIMESTAMP column is a date-time like the column's others (2026-10-27, the
     *  result boundary program: found by the PCT read as the engine's adapter reads it, which a column of both
     *  spellings failed, testExtendMaxDate; the day at midnight is Pure's print form, {@link #dateOnly}). */
    private static boolean engineDay(PureDateLiteral d, ExecutionPlan.Column c) {
        return !d.precision().atLeast(PureDateLiteral.Precision.HOUR) || c.type().equals("StrictDate")
                || slotWord(c.slot()).equals("DATE");
    }

    private static boolean midnight(PureDateLiteral d) {
        java.time.LocalDateTime t = d.toInstantFloor();
        return t.getHour() == 0 && t.getMinute() == 0 && t.getSecond() == 0 && t.getNano() == 0;
    }

    private static String day(PureDateLiteral d) {
        java.time.LocalDateTime t = d.toInstantFloor();
        return d.precision().atLeast(PureDateLiteral.Precision.DAY)
                ? String.format(java.util.Locale.ROOT, "%d-%02d-%02d", t.getYear(), t.getMonthValue(),
                        t.getDayOfMonth())
                : d.toEngineString();
    }

    // ---- structures -----------------------------------------------------------------------------------------------

    /** The identity fields an object carries through the database (docs/OBJECT_IDENTITY_2026_10_20.md §3.4; the
     *  compiler's {@code ClassLayouts.SYNTHETIC_ID}, {@code SYNTHETIC_TYPE}, {@code SYNTHETIC_CANON}). */
    static final String SYNTHETIC_ID = "__id";
    private static final String SYNTHETIC_TYPE = "__type";
    private static final String SYNTHETIC_CANON = "__canon";

    /** A structure as a reader sees it (the lowering's {@code MixedEncoding.declaredOnly}, the same rule): an object
     *  its declared properties, its identity fields dropped -- its class alone where it declares none -- and every
     *  nested value the same. */
    static Object declared(Object v) {
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> out = new java.util.LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                String k = String.valueOf(e.getKey());
                if (!k.equals(SYNTHETIC_ID) && !k.equals(SYNTHETIC_TYPE) && !k.equals(SYNTHETIC_CANON)) {
                    out.put(k, e.getValue() == null ? null : declared(e.getValue()));
                }
            }
            if (out.isEmpty() && m.containsKey(SYNTHETIC_TYPE)) {
                out.put(SYNTHETIC_TYPE, m.get(SYNTHETIC_TYPE));
            }
            return out;
        }
        if (v instanceof List<?> l) {
            List<Object> out = new java.util.ArrayList<>(l.size());
            for (Object x : l) {
                out.add(x == null ? null : declared(x));
            }
            return out;
        }
        return v;
    }

    // ---- the rest -------------------------------------------------------------------------------------------------

    /** A number written as {@code token}, its exact value {@code value} beside it. The JSON writer writes an integer
     *  number's {@code long} and any other number's token, so a whole number past a {@code long} (a HUGEINT's
     *  {@code 18446744073709551614}) is marked a non-integer and written by its token, never wrapped. */
    private static Json.Num token(String token, BigDecimal value) {
        boolean integral = token.chars().allMatch(ch -> ch == '-' || Character.isDigit(ch))
                && value.toBigInteger().bitLength() < 64;
        return new Json.Num(value.longValue(), value.doubleValue(), integral, value, token);
    }

    static String slotWord(@com.legend.base.Nullable String slot) {
        if (slot == null) {
            return "";
        }
        for (int i = 0; i < slot.length(); i++) {
            char ch = slot.charAt(i);
            if (ch == '(' || ch == '<' || ch == ' ') {
                return slot.substring(0, i);
            }
        }
        return slot;
    }

    private static IllegalArgumentException refused(Object cell, ExecutionPlan.Column c, String what) {
        return new IllegalArgumentException("column '" + c.name() + "' (" + c.type() + ", slot " + c.slot() + "): "
                + what + " of a " + cell.getClass().getSimpleName() + " cell is not defined (" + cell + ")");
    }
}
