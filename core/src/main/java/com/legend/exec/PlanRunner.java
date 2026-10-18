// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.exec;

import com.legend.executionplan.ExecutionPlan;

import java.io.IOException;
import java.math.BigDecimal;
import java.io.Writer;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * THE RUNNER (docs/EXECUTION_PLAN_BOUNDARY_2026_10_05.md §9, step 3): an {@link ExecutionPlan} run with a caller's
 * parameter values, with no model, no compiler and no dialect — what runs is the plan's own statement, reviewed when the
 * plan was made. It checks and converts the caller's values ({@link PlanParameters}), takes the session for the
 * statement's target ({@link PlanSessions}: shared, set up once, by the target's content), checks it is the database and
 * server version the statement is written for, runs the target's session statements, binds each slot to its
 * parameter's value as the slot says (writing a value's type where the statement leaves it to the value, H2's), and
 * passes on the text the database writes.
 */
public final class PlanRunner {

    private PlanRunner() {
    }

    /**
     * Runs {@code plan} with {@code values} (by parameter name, each as legend-engine's execute API makes it from a
     * request's value), on a session of {@code sessions}, writing the result's text to {@code out}, which is flushed
     * and never closed.
     */
    public static void run(ExecutionPlan plan, Map<String, ?> values, PlanSessions.Source sessions, Writer out)
            throws IOException {
        Map<String, PlanParameters.Checked> checked = PlanParameters.check(plan.parameters(), values);
        switch (plan.root()) {
            case ExecutionPlan.TextResult text -> text(text, checked, sessions, out);
            case ExecutionPlan.TdsResult tds -> throw new IllegalArgumentException("a plan whose answer is typed rows"
                    + " (a tabular result) is run by rows(...), which decodes them; run(...) writes a text");
            case ExecutionPlan.Sequence sequence -> throw new com.legend.error.NotImplementedException("a plan of"
                    + " several steps is not run yet: phase 2 of the execution plan program");
        }
    }

    /** A plan's typed rows: the columns, with their three type facts, and each row's cells decoded by them. */
    public record Rows(List<ExecutionPlan.Column> columns, List<List<Object>> rows) {
        public Rows {
            columns = List.copyOf(columns);
            rows = List.copyOf(rows);
        }
    }

    /**
     * Runs {@code plan}, whose answer is typed rows ({@link ExecutionPlan.TdsResult}; the plan path program's step 6,
     * 2026-10-18), with {@code values}, on a session of {@code sessions}: each row the database writes, one JSON
     * object, decoded into cells by the plan's columns ({@link RowDecode}).
     */
    public static Rows rows(ExecutionPlan plan, Map<String, ?> values, PlanSessions.Source sessions)
            throws IOException {
        Map<String, PlanParameters.Checked> checked = PlanParameters.check(plan.parameters(), values);
        return switch (plan.root()) {
            case ExecutionPlan.TdsResult tds -> {
                List<List<Object>> out = new java.util.ArrayList<>();
                send(tds.sql(), checked, sessions, row -> {
                    if (row == null) {
                        throw new IllegalStateException("the plan's statement wrote no object for a row");
                    }
                    out.add(RowDecode.cells(row, tds.columns()));
                });
                yield new Rows(tds.columns(), out);
            }
            case ExecutionPlan.TextResult text -> throw new IllegalArgumentException("a plan whose answer is a text"
                    + " is run by run(...), which writes it; rows(...) decodes typed rows");
            case ExecutionPlan.Sequence sequence -> throw new com.legend.error.NotImplementedException("a plan of"
                    + " several steps is not run yet: phase 2 of the execution plan program");
        };
    }

    private static void text(ExecutionPlan.TextResult text, Map<String, PlanParameters.Checked> checked,
            PlanSessions.Source sessions, Writer out) throws IOException {
        switch (text.format()) {
            case CSV, JSON, TDS_TEXT -> {
                boolean[] written = {false};
                send(text.sql(), checked, sessions, row -> {
                    if (!written[0]) {
                        written[0] = true;
                        out.write(row == null ? "" : row);
                    }
                });
                if (!written[0]) {
                    throw new IllegalStateException("the plan's statement wrote no text: no row");
                }
            }
            case JSON_PER_ROW -> {
                // one JSON object per row: the array's brackets and commas are the runner's
                out.write('[');
                boolean[] first = {true};
                send(text.sql(), checked, sessions, row -> {
                    if (!first[0]) {
                        out.write(',');
                    }
                    first[0] = false;
                    out.write(row != null ? row : "null");
                    out.flush();
                });
                out.write(']');
            }
        }
        out.flush();
    }

    /** {@code sql} sent on a session of {@code sessions}: the slots bound to the checked values, the type holes
     *  filled, each row's text handed to {@code rows}. */
    private static void send(ExecutionPlan.Sql sql, Map<String, PlanParameters.Checked> checked,
            PlanSessions.Source sessions, Statements.Rows rows) throws IOException {
        // every null type checked before any session is opened: a plan whose null is of no type is refused whole
        for (ExecutionPlan.Slot slot : sql.slots()) {
            if (slot.binding() instanceof ExecutionPlan.Binding.One one) {
                nullType(one, slot);
            }
        }
        List<PlanParameters.Checked> values = new java.util.ArrayList<>(sql.slots().size());
        for (int i = 0; i < sql.slots().size(); i++) {
            values.add(value(checked, sql.slots().get(i), i + 1));
        }
        // the session's own statements (JDBC's, or a connector's: Statements), 2026-10-17, docs/MONGODB_PLAN_2026_10_16.md
        // D12: the runner holds the plan and the punctuation; the session carries text in and text out
        try (Sessions.Session session = sessions.open(sql.target())) {
            Statements st = session.statements();
            st.check(sql.target());
            try (var origin = StatementOrigin.enter(StatementOrigin.SESSION)) {
                for (String s : sql.target().session()) {
                    StatementOrigin.sent(s);
                    st.execute(s);
                }
            }
            String statement = statement(sql, checked);
            StatementOrigin.sent(statement);
            st.query(statement, sql.slots(), values, rows);
        }
    }

    /** {@code slot}'s parameter's checked value. */
    private static PlanParameters.Checked value(Map<String, PlanParameters.Checked> checked, ExecutionPlan.Slot slot,
            int index) {
        PlanParameters.Checked value = checked.get(slot.parameter());
        if (value == null) {
            throw new IllegalStateException("slot " + index + " names parameter '" + slot.parameter()
                    + "', which the plan does not declare");
        }
        return value;
    }

    /** The statement to prepare: the plan's, with each type hole filled with its value's type (a statement with none is
     *  the plan's as it is). */
    private static String statement(ExecutionPlan.Sql sql, Map<String, PlanParameters.Checked> checked) {
        StringBuilder out = new StringBuilder();
        int from = 0;
        List<ExecutionPlan.Slot> slots = sql.slots();
        for (int i = 0; i < slots.size(); i++) {
            ExecutionPlan.Slot slot = slots.get(i);
            if (slot.binding() instanceof ExecutionPlan.Binding.One one && one.hole() != null) {
                ExecutionPlan.TypeHole hole = one.hole();
                out.append(sql.statement(), from, hole.at()).append(typeOf(hole, value(checked, slot, i + 1), slot));
                from = hole.at();
            }
        }
        return out.append(sql.statement().substring(from)).toString();
    }

    /** The type a hole takes for {@code value}: the spelling of its kind, with its own digits where the spelling takes
     *  them; an absent value's, its type's name alone (a null has no digits to keep). */
    private static String typeOf(ExecutionPlan.TypeHole hole, PlanParameters.Checked value, ExecutionPlan.Slot slot) {
        Object v = switch (value) {
            case PlanParameters.One one -> one.value();
            case PlanParameters.None none -> null;
            case PlanParameters.Many many -> throw new IllegalStateException("slot of '" + slot.parameter()
                    + "' binds one value, given a list");
        };
        ExecutionPlan.TypeSpelling spelling = spelling(hole, v == null ? hole.absent() : kindOf(v, slot), slot);
        if (v == null) {
            return spelling.name();
        }
        BigDecimal digits = v instanceof Double d ? BigDecimal.valueOf(d) : v instanceof BigDecimal bd ? bd : null;
        return switch (spelling.digits()) {
            case NONE -> spelling.name();
            case PRECISION -> spelling.name() + "(" + digitsOf(digits, slot).precision() + ")";
            case PRECISION_AND_SCALE -> spelling.name() + "(" + digitsOf(digits, slot).precision() + ","
                    + digitsOf(digits, slot).scale() + ")";
        };
    }

    static ExecutionPlan.TypeSpelling spelling(ExecutionPlan.TypeHole hole, ExecutionPlan.ValueKind kind,
            ExecutionPlan.Slot slot) {
        ExecutionPlan.TypeSpelling spelling = hole.types().get(kind);
        if (spelling == null) {
            throw new IllegalStateException("slot of '" + slot.parameter() + "' is given a value of kind " + kind
                    + ", and its type hole spells only " + hole.types().keySet());
        }
        return spelling;
    }

    private static final java.time.format.DateTimeFormatter DATE_TIME_TEXT =
            java.time.format.DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss.SSSSSSSSS", java.util.Locale.ROOT);

    /** {@code t} as the text its cast reads: to the nanosecond, or to {@code fraction} digits of a second, the finer ones
     *  cut, as its literal's are. */
    static String dateTimeText(LocalDateTime t, @com.legend.base.Nullable Integer fraction) {
        int unit = 1;
        for (int digits = fraction == null ? 9 : fraction; digits < 9; digits++) {
            unit *= 10;
        }
        return DATE_TIME_TEXT.format(t.withNano(t.getNano() - t.getNano() % unit));
    }

    private static BigDecimal digitsOf(@com.legend.base.Nullable BigDecimal digits, ExecutionPlan.Slot slot) {
        if (digits == null) {
            throw new IllegalStateException("slot of '" + slot.parameter() + "' spells a type with a value's digits,"
                    + " given a value with none");
        }
        return digits;
    }

    /** The JDBC type number of {@code one}'s null: the plan's null types are {@code java.sql.JDBCType} names (the
     *  plan format's own vocabulary, which every session reads), or the plan is refused by name. */
    static int nullType(ExecutionPlan.Binding.One one, ExecutionPlan.Slot slot) {
        try {
            return java.sql.JDBCType.valueOf(one.nullType()).getVendorTypeNumber();
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("slot of '" + slot.parameter() + "': its null type '" + one.nullType()
                    + "' is no JDBC type", e);
        }
    }

    /** The kind of a checked value (one {@link PlanParameters} makes). */
    static ExecutionPlan.ValueKind kindOf(Object v, ExecutionPlan.Slot slot) {
        return switch (v) {
            case Long l -> ExecutionPlan.ValueKind.INTEGER;
            case BigDecimal d -> ExecutionPlan.ValueKind.DECIMAL;
            case Double d -> ExecutionPlan.ValueKind.FLOATING;
            case LocalDate d -> ExecutionPlan.ValueKind.DATE;
            case LocalDateTime t -> t.getNano() % 1000 == 0 ? ExecutionPlan.ValueKind.DATE_TIME
                    : ExecutionPlan.ValueKind.DATE_TIME_NANOS;
            default -> throw new IllegalStateException("slot of '" + slot.parameter() + "' is typed by its value,"
                    + " given " + v.getClass().getSimpleName() + ", which is of no value kind");
        };
    }
}
