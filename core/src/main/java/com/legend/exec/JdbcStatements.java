// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.exec;

import com.legend.executionplan.ExecutionPlan;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;

/**
 * A JDBC session's {@link Statements} (2026-10-17, out of {@code PlanRunner}, whose JDBC this was): the statement
 * prepared, each slot bound to its value as the slot says (a date-time in a cast passed as its text), and each row's
 * one text read -- the one {@code getString} of the plan path, carriage (Charter C1.2) -- or, since the plan path
 * program's step 7 (2026-10-18), each row's typed cells read from the driver by the plan's columns
 * ({@link JdbcDecode}: one carrier conversion per cell, nothing computed).
 */
final class JdbcStatements implements Statements {

    private final Connection c;

    JdbcStatements(Connection c) {
        this.c = c;
    }

    @Override
    public void check(ExecutionPlan.Target target) {
        Sessions.check(c, target.database().type());
        switch (target.servers()) {
            case ExecutionPlan.Servers.Every every -> {
                // the spelling depends on no version
            }
            case ExecutionPlan.Servers.Versions versions -> {
                String version = Sessions.version(c);
                if (versions.prefixes().stream().noneMatch(version::startsWith)) {
                    throw new com.legend.error.NotImplementedException("the plan's statements are written for "
                            + target.database().type() + " " + versions.prefixes() + ", and the session is "
                            + target.database().type() + " " + version + ": not run");
                }
            }
        }
    }

    @Override
    public void execute(String statement) {
        try (Statement st = c.createStatement()) {
            StatementOrigin.sent(statement);
            st.execute(statement);
        } catch (SQLException e) {
            throw PlanSessions.dataError(e, c);
        }
    }

    @Override
    public void query(String statement, List<ExecutionPlan.Slot> slots, List<PlanParameters.Checked> values,
            Rows rows) throws IOException {
        try (PreparedStatement st = c.prepareStatement(statement)) {
            for (int i = 0; i < slots.size(); i++) {
                bind(st, i + 1, slots.get(i), values.get(i));
            }
            StatementOrigin.sent(statement);
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    rows.row(rs.getString(1));
                }
            }
        } catch (SQLException e) {
            throw PlanSessions.dataError(e, c);
        }
    }

    /** {@inheritDoc} Each row's cells read from the driver by the plan's columns ({@link JdbcDecode}), the i-th
     *  column from the i-th result column; a statement answering another number of columns than the plan declares
     *  is refused by name before a row is read. */
    @Override
    public void rows(String statement, List<ExecutionPlan.Slot> slots, List<PlanParameters.Checked> values,
            List<ExecutionPlan.Column> columns, Cells cells) throws IOException {
        try (PreparedStatement st = c.prepareStatement(statement)) {
            for (int i = 0; i < slots.size(); i++) {
                bind(st, i + 1, slots.get(i), values.get(i));
            }
            StatementOrigin.sent(statement);
            try (ResultSet rs = st.executeQuery()) {
                int answered = rs.getMetaData().getColumnCount();
                if (answered != columns.size()) {
                    throw new IllegalStateException("the statement answers " + answered + " columns, and the plan"
                            + " declares " + columns.size() + " " + columns.stream().map(ExecutionPlan.Column::name)
                            .toList() + ": a typed-rows statement answers the plan's columns in their order");
                }
                while (rs.next()) {
                    cells.row(JdbcDecode.row(rs, columns));
                }
            }
        } catch (SQLException e) {
            throw PlanSessions.dataError(e, c);
        }
    }

    /** Slot {@code index} bound to its parameter's checked value, as the slot says: one value (an absent one a null of
     *  the slot's type), or a list as one array of the slot's element type (an absent one an empty array). */
    private void bind(PreparedStatement st, int index, ExecutionPlan.Slot slot, PlanParameters.Checked value)
            throws SQLException {
        switch (slot.binding()) {
            case ExecutionPlan.Binding.Array array -> {
                List<Object> items = switch (value) {
                    case PlanParameters.Many many -> many.values();
                    case PlanParameters.None none -> List.of();
                    case PlanParameters.One one -> List.of(one.value());
                };
                st.setArray(index, c.createArrayOf(array.elementSqlType(),
                        items.stream().map(JdbcStatements::arrayElement).toArray()));
            }
            case ExecutionPlan.Binding.One one -> {
                switch (value) {
                    case PlanParameters.One v -> {
                        ExecutionPlan.TypeHole hole = one.hole();
                        if (hole != null && v.value() instanceof LocalDateTime t) {
                            // a date-time in a cast is passed as its text: the drivers pass no digit finer than a
                            // microsecond alike (DuckDB's cuts it, Postgres's rounds it; probes/timestamp-results.txt)
                            st.setString(index, PlanRunner.dateTimeText(t,
                                    PlanRunner.spelling(hole, PlanRunner.kindOf(t, slot), slot).fraction()));
                        } else {
                            st.setObject(index, v.value());
                        }
                    }
                    case PlanParameters.None none -> st.setNull(index, PlanRunner.nullType(one, slot));
                    case PlanParameters.Many many -> throw new IllegalStateException("slot " + index + " binds one"
                            + " value of '" + slot.parameter() + "', given a list");
                }
            }
        }
    }

    /** An array element as the drivers take it: a timestamp as {@code java.sql.Timestamp} (measured,
     *  probes/list-results.txt), every other value as it is. */
    private static Object arrayElement(Object v) {
        return v instanceof LocalDateTime t ? java.sql.Timestamp.valueOf(t) : v;
    }
}
