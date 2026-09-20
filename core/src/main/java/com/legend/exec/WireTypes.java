// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.exec;

import com.legend.compiler.element.type.ExprType;
import com.legend.compiler.element.type.PlatformTypes;
import com.legend.compiler.element.type.Type;
import com.legend.sql.OutputCol;
import com.legend.sql.SqlExpr;
import com.legend.sql.SqlQuery;
import com.legend.sql.SqlSelect;
import com.legend.sql.SqlType;
import com.legend.sql.TypeFact;

import java.sql.Connection;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** THE WIRE KIND OF A WIRE-DECIDED COLUMN IS THE DATABASE'S (leg 3.3,
 * docs/DATABASE_MODE_HOMEWORK_2026_09_18.md §4t): the engine types a
 * result cell by the result set's metadata, never by the store's
 * declaration — {@code Interaction.id : String[1]} over a store column
 * declared {@code ID INT} that the fixture created {@code VARCHAR(200)}
 * delivers the String {@code '7'}. A declaration that leaves the kind to
 * the wire (String, unrefined Number, Any — {@code dataTypeTransformer}'s
 * identity arm) therefore takes the type the database REPORTS for the
 * planned statement: one prepare (parse and bind, no row fetched — the
 * driver's {@code getMetaData} on a prepared statement), memoized per
 * connection and statement text. A projection whose stamped kind differs
 * from the reported one is cast to the reported type — a cast to a
 * column's own type, so the value is untouched and the canon spells the
 * wire's kind. Numeric, Boolean and temporal declarations convert the
 * wire cell (the transformer's other arms) and are never touched; nor is
 * a label carrier (LITERAL / TEMPORAL_TEXT / DECIMAL_TEXT / JSON), whose
 * VARCHAR wire is the label's contract. */
public final class WireTypes {

    private WireTypes() {
    }

    /** {@code plan} with its wire-decided outputs reconciled to the
     * database's reported types, or {@code plan} itself when nothing is
     * wire-decided or the plan is not a projection frame. */
    public static SqlQuery reconcile(SqlQuery plan, ExprType shapeInfo,
            com.legend.sql.dialect.SqlDialect dialect, Connection conn,
            Map<String, List<SqlType>> memo) {
        if (!(plan instanceof SqlSelect ps) || ps.projections().isEmpty()
                || ps.projections().size() != plan.outputs().size()) {
            return plan;
        }
        List<Type> declared = declaredKinds(shapeInfo, plan.outputs().size());
        if (declared == null || declared.stream().noneMatch(WireTypes::wireDecided)) {
            return plan;
        }
        String sql = dialect.render(plan);
        List<SqlType> reported = memo.computeIfAbsent(sql, s -> reported(s, conn));
        if (reported.size() != plan.outputs().size()) {
            return plan;
        }
        List<SqlSelect.Projection> projections = new ArrayList<>(ps.projections());
        List<OutputCol> outputs = new ArrayList<>(plan.outputs());
        boolean changed = false;
        for (int i = 0; i < outputs.size(); i++) {
            SqlType wire = reported.get(i);
            if (wire == null || !wireDecided(declared.get(i))) {
                continue;
            }
            SqlSelect.Projection p = projections.get(i);
            OutputCol col = outputs.get(i);
            // a BARE store-column reference only: its stamp is the store's
            // declaration, the one fact the fixture can contradict. A
            // computed expression keeps the compiler's own type — and is
            // never cast: H2 reports a computed DECIMAL at scale 0 while
            // the cell carries the value's real scale (the calendar rows)
            if (!(p.expr() instanceof SqlExpr.Column ref
                    && ref.type() instanceof TypeFact.Typed tf)
                    || labelCarrier(tf.type()) || Type.kindOfSqlType(wire) == null
                    || Type.kindOfSqlType(wire) == Type.kindOfSqlType(tf.type())) {
                continue;
            }
            if (System.getenv("LEGEND_LITE_DUMP_SQL") != null) {
                System.err.println("[wire] " + col.name() + ": stamped " + tf.type()
                        + ", the database reports " + wire);
            }
            // the reference re-typed, the value untouched (no cast)
            SqlExpr.Column retyped = SqlExpr.Column.of(ref.table(), ref.name(), wire,
                    tf.nullable(), java.util.Objects.requireNonNullElse(ref.origin(),
                            OutputCol.Origin.DERIVED));
            projections.set(i, new SqlSelect.Projection(retyped, p.alias(),
                    p.out() == null ? null : withType(p.out(), wire)));
            outputs.set(i, withType(col, wire));
            changed = true;
        }
        return changed ? ps.withProjections(projections, outputs) : plan;
    }

    private static OutputCol withType(OutputCol c, SqlType t) {
        return new OutputCol(c.name(), t, c.nullable(), c.tolerated(), c.origin());
    }

    /** The declared kind per output: the relation's columns for a grid,
     * the root type for a one-column value; null when the shape does not
     * name one kind per output. */
    private static @com.legend.Nullable List<Type> declaredKinds(ExprType shapeInfo, int width) {
        Type.RelationType schema = Type.schemaView(shapeInfo.type());
        if (schema != null) {
            if (schema.isLateBound() || schema.columns().size() != width) {
                return null;
            }
            List<Type> kinds = new ArrayList<>(width);
            for (Type.Column c : schema.columns()) {
                kinds.add(c.type());
            }
            return kinds;
        }
        return width == 1 ? List.of(shapeInfo.type()) : null;
    }

    /** {@code dataTypeTransformer}'s identity arm: the declaration leaves
     * the cell's kind to the wire. */
    static boolean wireDecided(Type declared) {
        return declared == Type.Primitive.STRING || declared == Type.Primitive.NUMBER
                || (declared instanceof Type.ClassType ct && PlatformTypes.isAny(ct));
    }

    private static boolean labelCarrier(SqlType t) {
        return t == SqlType.Scalar.LITERAL || t == SqlType.Scalar.TEMPORAL_TEXT
                || t == SqlType.Scalar.DECIMAL_TEXT || t == SqlType.Scalar.JSON;
    }

    /** The database's reported type per column — a prepare, no execution;
     * a column outside the vocabulary reports null; a statement the
     * database cannot prepare raises the data error the verdict would have. */
    private static List<SqlType> reported(String sql, Connection conn) {
        try (var st = conn.prepareStatement(sql)) {
            ResultSetMetaData md = st.getMetaData();
            if (md == null) {
                return Collections.emptyList();
            }
            List<SqlType> out = new ArrayList<>(md.getColumnCount());
            for (int i = 1; i <= md.getColumnCount(); i++) {
                out.add(sqlTypeOf(md.getColumnType(i), md.getPrecision(i), md.getScale(i)));
            }
            return out;
        } catch (SQLException e) {
            // the seam: java.sql stops at this probe's boundary — a
            // statement the database cannot prepare cannot judge either
            throw new com.legend.error.DataError(String.valueOf(e.getMessage()), e);
        }
    }

    /** JDBC's type code to the SQL vocabulary (the kinds
     * {@link Type#kindOfSqlType} names). */
    static @com.legend.Nullable SqlType sqlTypeOf(int jdbcType, int precision, int scale) {
        return switch (jdbcType) {
            case Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR, Types.NCHAR, Types.NVARCHAR,
                    Types.LONGNVARCHAR -> SqlType.Scalar.VARCHAR;
            case Types.TINYINT, Types.SMALLINT, Types.INTEGER -> SqlType.Scalar.INTEGER;
            case Types.BIGINT -> SqlType.Scalar.BIGINT;
            case Types.FLOAT, Types.REAL, Types.DOUBLE -> SqlType.Scalar.DOUBLE;
            case Types.DECIMAL, Types.NUMERIC -> precision > 0
                    ? new SqlType.Decimal(precision, Math.max(scale, 0)) : null;
            case Types.BIT, Types.BOOLEAN -> SqlType.Scalar.BOOLEAN;
            case Types.DATE -> SqlType.Scalar.DATE;
            case Types.TIMESTAMP -> SqlType.Scalar.TIMESTAMP;
            case Types.TIMESTAMP_WITH_TIMEZONE -> SqlType.Scalar.TIMESTAMPTZ;
            default -> null;
        };
    }
}
