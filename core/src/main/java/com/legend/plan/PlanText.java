// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.plan;

import com.legend.compiler.element.ModelContext;
import com.legend.error.NotImplementedException;
import com.legend.lineage.ScanRelations;
import com.legend.model.DatabaseDefinition;
import com.legend.model.RelationalDataType;
import com.legend.sql.SqlExpr;
import com.legend.sql.SqlQuery;
import com.legend.sql.SqlSelect;
import com.legend.sql.SqlSource;

/**
 * The engine's {@code planToString} text for SINGLE-RELATIONAL plans
 * (#47 pilot): the literal plan printout the executionPlan corpus pins —
 * a text envelope around the ENGINE-STYLE SQL the toSQLString pipeline
 * already renders literally, plus the set-implementation identity and
 * the result columns read STRUCTURALLY from the SQL IR (alias + the
 * store column's engine type spelling). Plan text compares LITERALLY —
 * the toSQLString doctrine. Anything beyond the single-node vocabulary
 * (unions, computed projections, multi-node sequences) is a named wall.
 */
public final class PlanText {

    private PlanText() {
    }

    public static String single(ModelContext ctx, String rootClassFqn,
            String mappingFqn, SqlQuery plan, String sql,
            java.util.List<com.legend.compiler.spec.typed.TypedSpec> body) {
        String[] impl = ScanRelations.rootImpl(ctx, mappingFqn,
                rootClassFqn);
        com.legend.compiler.spec.typed.TypedSpec last =
                body.get(body.size() - 1);
        String typeLine;
        if (last.info().type()
                instanceof com.legend.compiler.element.type.Type.RelationType rt) {
            // TDS plans: per-column (name, PureType, DBTYPE, "doc")
            // tuples and NO resultSizeRange line; the engine quotes the
            // column name exactly when a documentation string rides it
            typeLine = "  type = TDS[" + tdsTuples(ctx, impl[2], plan, rt,
                    docsOf(last)) + "]\n";
        } else {
            typeLine = "  type = Class[impls=(" + rootClassFqn + " | "
                    + impl[0] + "." + impl[1] + ")]\n"
                    + "         as " + rootClassFqn + "\n"
                    + "  resultSizeRange = *\n";
        }
        return "Relational\n(\n"
                + typeLine
                + "  resultColumns = [" + resultColumns(ctx, impl[2], plan)
                + "]\n"
                + "  sql = " + sql + "\n"
                + "  connection = TestDatabaseConnection(type = \"H2\")\n"
                + ")\n";
    }

    private static java.util.Map<String, String> docsOf(
            com.legend.compiler.spec.typed.TypedSpec last) {
        java.util.Map<String, String> docs = new java.util.LinkedHashMap<>();
        java.util.ArrayDeque<com.legend.compiler.spec.typed.TypedSpec> work =
                new java.util.ArrayDeque<>();
        work.add(last);
        while (!work.isEmpty()) {
            com.legend.compiler.spec.typed.TypedSpec t = work.poll();
            if (t instanceof com.legend.compiler.spec.typed.TypedProject tp) {
                for (var fc : tp.columns()) {
                    if (fc.documentation() != null) {
                        docs.put(strip(fc.name()), fc.documentation());
                    }
                }
                return docs;
            }
            work.addAll(t.children());
        }
        return docs;
    }

    private static String tdsTuples(ModelContext ctx, String dbFqn,
            SqlQuery plan,
            com.legend.compiler.element.type.Type.RelationType rt,
            java.util.Map<String, String> docs) {
        if (!(plan instanceof SqlSelect s)) {
            throw new NotImplementedException(
                    "plan: non-select TDS top query pending");
        }
        StringBuilder sb = new StringBuilder();
        var cols = rt.columns();
        for (int i = 0; i < cols.size(); i++) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            String name = strip(cols.get(i).name());
            String doc = docs.getOrDefault(name, "");
            SqlSelect.Projection p = s.projections().get(i);
            String db;
            if (p.expr() instanceof SqlExpr.Column c) {
                String table = tableOf(s.from(), c.table());
                var td = ctx.findTableDefinition(dbFqn, table).orElseThrow();
                db = spell(td.columns().stream()
                        .filter(x -> x.name().equalsIgnoreCase(c.name()))
                        .findFirst().orElseThrow().dataType());
            } else {
                throw new NotImplementedException("plan: computed TDS"
                        + " column '" + name + "' type spelling pending");
            }
            sb.append('(')
                    .append(doc.isEmpty() ? name : "\"" + name + "\"")
                    .append(", ").append(pureName(cols.get(i).type()))
                    .append(", ").append(db)
                    .append(", \"").append(doc).append("\"").append(')');
        }
        return sb.toString();
    }

    private static String pureName(
            com.legend.compiler.element.type.Type t) {
        if (t == com.legend.compiler.element.type.Type.Primitive.STRING) {
            return "String";
        }
        if (t == com.legend.compiler.element.type.Type.Primitive.INTEGER) {
            return "Integer";
        }
        if (t == com.legend.compiler.element.type.Type.Primitive.FLOAT) {
            return "Float";
        }
        if (t == com.legend.compiler.element.type.Type.Primitive.BOOLEAN) {
            return "Boolean";
        }
        if (t == com.legend.compiler.element.type.Type.Primitive.STRICT_DATE) {
            return "StrictDate";
        }
        if (t == com.legend.compiler.element.type.Type.Primitive.DATE_TIME) {
            return "DateTime";
        }
        if (t == com.legend.compiler.element.type.Type.Primitive.DATE) {
            return "Date";
        }
        if (t == com.legend.compiler.element.type.Type.Primitive.DECIMAL) {
            return "Decimal";
        }
        if (t == com.legend.compiler.element.type.Type.Primitive.NUMBER) {
            return "Number";
        }
        throw new NotImplementedException("plan: pure type name for " + t);
    }

    private static String strip(String name) {
        return name.length() > 1 && name.startsWith("\"")
                && name.endsWith("\"")
                ? name.substring(1, name.length() - 1) : name;
    }

    private static String resultColumns(ModelContext ctx, String dbFqn,
            SqlQuery plan) {
        if (!(plan instanceof SqlSelect s)) {
            throw new NotImplementedException(
                    "plan: non-select top query (union) pending");
        }
        StringBuilder sb = new StringBuilder();
        for (SqlSelect.Projection p : s.projections()) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            if (!(p.expr() instanceof SqlExpr.Column c)) {
                throw new NotImplementedException("plan: computed"
                        + " projection '" + p.outputName()
                        + "' type spelling pending");
            }
            String table = tableOf(s.from(), c.table());
            var td = ctx.findTableDefinition(dbFqn, table).orElseThrow(
                    () -> new NotImplementedException("plan: table '"
                            + table + "' not in '" + dbFqn + "'"));
            DatabaseDefinition.ColumnDefinition cd = td.columns().stream()
                    .filter(x -> x.name().equalsIgnoreCase(c.name()))
                    .findFirst().orElseThrow(
                            () -> new NotImplementedException("plan:"
                                    + " column '" + c.name() + "' not on '"
                                    + table + "'"));
            sb.append("(\"").append(strip(p.outputName()))
                    .append("\", ").append(spell(cd.dataType())).append(')');
        }
        return sb.toString();
    }

    /** The physical table behind a FROM-tree alias. */
    private static String tableOf(SqlSource src, String alias) {
        return switch (src) {
            case SqlSource.Table t when t.alias().equals(alias) -> t.name();
            case SqlSource.Join j -> {
                try {
                    yield tableOf(j.left(), alias);
                } catch (NotImplementedException e) {
                    yield tableOf(j.right(), alias);
                }
            }
            default -> throw new NotImplementedException(
                    "plan: alias '" + alias + "' not resolvable to a table"
                    + " (" + src.getClass().getSimpleName() + ")");
        };
    }

    /** The engine's resultColumns type spelling (dataTypeToSqlText):
     * INT (not INTEGER), sized VARCHAR/CHAR, etc. */
    private static String spell(RelationalDataType t) {
        return switch (t) {
            case RelationalDataType.Integer_ ignored -> "INT";
            case RelationalDataType.BigInt ignored -> "BIGINT";
            case RelationalDataType.SmallInt ignored -> "SMALLINT";
            case RelationalDataType.TinyInt ignored -> "TINYINT";
            case RelationalDataType.Varchar v -> "VARCHAR(" + v.size() + ")";
            case RelationalDataType.Char_ c -> "CHAR(" + c.size() + ")";
            case RelationalDataType.Double_ ignored -> "DOUBLE";
            case RelationalDataType.Float_ ignored -> "FLOAT";
            case RelationalDataType.Real ignored -> "REAL";
            case RelationalDataType.Decimal d ->
                    "DECIMAL(" + d.precision() + "," + d.scale() + ")";
            case RelationalDataType.Numeric n ->
                    "NUMERIC(" + n.precision() + "," + n.scale() + ")";
            case RelationalDataType.Timestamp ignored -> "TIMESTAMP";
            case RelationalDataType.Date_ ignored -> "DATE";
            case RelationalDataType.Bit ignored -> "BIT";
            case RelationalDataType.Bool ignored -> "BOOLEAN";
            default -> throw new NotImplementedException(
                    "plan: type spelling for " + t + " pending");
        };
    }
}
