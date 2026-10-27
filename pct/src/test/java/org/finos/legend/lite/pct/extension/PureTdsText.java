// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package org.finos.legend.lite.pct.extension;

import com.legend.executionplan.ExecutionPlan.Column;
import com.legend.result.CellText;
import com.legend.values.PureDateLiteral;

import java.util.List;

/**
 * Pure's TDS text of a relation's typed rows: the text legend-pure's {@code stringToTDS} reads, which is how the
 * reference interpreter holds a relation (its {@code TDS} instance IS its text), so the PCT adapter hands a relation
 * over as it (docs/RESULT_BOUNDARY_PROGRAM_2026_10_26.md, the end state of 2026-10-27, phase 4). Written here, at the
 * edge, from the cells the plan's typed rows decode -- the database spelled it until phase 4 ({@code Render.tdsText},
 * deleted with it); the rules are the reader's and Pure's print forms, unchanged:
 *
 * <ul>
 *   <li>the header, {@code name:Type[1]} or {@code [0..1]} per column: a name outside an identifier's characters
 *       quoted, a pivot's generated name with its own quotes inside the quoting; the type a path (the reader resolves
 *       none by import), a precision decimal a Decimal (its grammar has no type variables), a generic type its raw
 *       class;</li>
 *   <li>a cell in Pure's print form ({@link CellText#pure}); a DateTime with at least three fraction digits, finer
 *       digits to the microsecond with their trailing zeros dropped (legend-engine's {@code testDayOfWeek_Relation}:
 *       a relation prints {@code 1985-01-12T06:30:00.000+0000});</li>
 *   <li>a String or an enum's name quoted ({@code '...'}, a backslash and a quote escaped by a backslash) only when it
 *       holds a comma, a quote, a backslash or a line break, or is empty (an empty cell is the reader's null); a
 *       Variant's JSON always quoted; a null the bare {@code null}.</li>
 * </ul>
 *
 * <p>A cell of a class, a List, a Pair or a Map has no TDS text the reader reads ("Pure Type: 'X' is not supported
 * yet"): it is refused by name.
 */
final class PureTdsText {

    private PureTdsText() {
    }

    static String of(List<Column> columns, List<List<Object>> rows) {
        if (columns.isEmpty()) {
            throw new IllegalStateException("a relation of no column has no TDS text");
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < columns.size(); i++) {
            Column c = columns.get(i);
            out.append(i == 0 ? "" : ",").append(name(c.name())).append(':').append(typeName(c.type()))
                    .append(c.multiplicity().lower() == 0 ? "[0..1]" : "[1]");
        }
        for (List<Object> row : rows) {
            out.append('\n');
            for (int i = 0; i < columns.size(); i++) {
                out.append(i == 0 ? "" : ",").append(cell(row.get(i), columns.get(i)));
            }
        }
        return out.toString();
    }

    private static String name(String name) {
        if (name.startsWith("'")) {
            return "'" + name.replace("'", "\\'") + "'";
        }
        if (name.contains("__|__")) {
            return "'\\'" + name + "\\''";
        }
        return name.matches("[A-Za-z_][A-Za-z0-9_$]*") ? name : "'" + name + "'";
    }

    private static String typeName(String type) {
        if (type.startsWith("Decimal(")) {
            return "Decimal";
        }
        if (type.equals("Variant")) {
            return VARIANT;
        }
        int generic = type.indexOf('<');
        return generic < 0 ? type : type.substring(0, generic);
    }

    private static final String VARIANT = "meta::pure::metamodel::variant::Variant";

    private static String cell(@com.legend.base.Nullable Object v, Column c) {
        if (v == null) {
            return "null";
        }
        String type = c.type();
        if (type.equals(VARIANT) || type.equals("Variant")) {
            return quoted(String.valueOf(v));
        }
        if (type.contains("<") || v instanceof java.util.Map || v instanceof List) {
            throw new IllegalStateException("column '" + c.name() + "' (" + type + "): a structure has no TDS text"
                    + " the reader reads");
        }
        if (v instanceof String s) {
            boolean structural = s.isEmpty() || s.contains(",") || s.contains("'") || s.contains("\\")
                    || s.contains("\n") || s.contains("\r");
            return structural ? quoted(s) : s;
        }
        if (v instanceof PureDateLiteral d && (type.equals("DateTime") || type.equals("Date"))
                && d.precision().atLeast(PureDateLiteral.Precision.SECOND) && !writtenPrecision(c)) {
            return relationDateTime(d);
        }
        return CellText.pure(v, c);
    }

    /** Whether the column carries a date as the value was written (its own text), not a database timestamp. */
    private static boolean writtenPrecision(Column c) {
        String slot = c.slot() == null ? "" : c.slot();
        return slot.startsWith("TEMPORAL_TEXT") || slot.startsWith("LITERAL");
    }

    private static String relationDateTime(PureDateLiteral d) {
        java.time.LocalDateTime t = d.toInstantFloor();
        String nine = String.format(java.util.Locale.ROOT, "%09d", t.getNano());
        String finer = nine.substring(3, 6).replaceAll("0+$", "");
        return String.format(java.util.Locale.ROOT, "%04d-%02d-%02dT%02d:%02d:%02d", t.getYear(), t.getMonthValue(),
                t.getDayOfMonth(), t.getHour(), t.getMinute(), t.getSecond()) + "." + nine.substring(0, 3) + finer
                + "+0000";
    }

    private static String quoted(String s) {
        return "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }
}
