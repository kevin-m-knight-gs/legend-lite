// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend;

import com.legend.compiler.element.type.ExprType;
import com.legend.compiler.element.type.Type;
import com.legend.database.Databases;
import com.legend.executionplan.ExecutionPlan;
import com.legend.lowering.WireRender;
import com.legend.model.ConnectionDefinition;
import com.legend.setup.CsvSeed;
import com.legend.setup.RowLoad;
import com.legend.sql.SqlQuery;
import com.legend.sql.dialect.RenderedStatement;
import com.legend.sql.dialect.SqlDialect;

import java.util.ArrayList;
import java.util.List;

/**
 * THE PLANNER MAKES LITE PLANS (docs/EXECUTION_PLAN_BOUNDARY_2026_10_05.md §9, step 2's landing 2): a typed query planned
 * for its runtime as an {@link ExecutionPlan} that a model-free runner executes. Its one node is a text the DATABASE
 * writes — the wire statement ({@link WireRender}), or a graph fetch's JSON — so the runner passes the text on. Every
 * statement is final for its target: written in the dialect of the target's database, with the server versions that
 * spelling is for; and the target is whole — the database (a declared connection, or the platform's own engine), the
 * statements each connection runs first, and the setup that establishes it, written at plan time. The statements are
 * the ones {@link Execution}'s wire and streaming paths render for the same query, so a plan answers as they do.
 */
final class PlanMaker {

    private PlanMaker() {
    }

    /** {@code query}'s plan on {@code runtime}, its text in the form {@code output} asks for, lowered under the
     *  feature flags {@code features}; each declared parameter a value the statement binds where it is used. */
    static ExecutionPlan plan(TypedQuery query, @com.legend.base.Nullable String runtime, TypedQuery.Output output,
            java.util.Set<com.legend.platform.Feature> features) {
        List<QueryParameters.Declared> declared = query.parameters();
        List<com.legend.sql.SqlExpr.PlanParam> slots = new ArrayList<>(declared.size());
        for (QueryParameters.Declared p : declared) {
            // one value, an optional one's absence, or a list as one array (Declared.slot)
            slots.add(p.slot());
        }
        Compiler.LoweredQuery l = query.lower(runtime, output == TypedQuery.Output.STREAMED_JSON, slots, features);
        // the runtime decided: a null one is refused there, by name
        com.legend.database.Target decided = Compiler.executesOn(l.ctx(), runtime);
        SqlDialect dialect = Databases.dialect(decided.type());
        ExecutionPlan.Target target = target(decided, java.util.Objects.requireNonNull(runtime), l, dialect);
        ExprType root = l.root().info();
        // an enumeration parameter compared with a mapped column: through that place's value table
        SqlQuery lowered = com.legend.sql.EnumValueTables.apply(l.plan());
        ExecutionPlan.Node node = switch (com.legend.plan.ResultShape.of(l.root())) {
            case GRAPH -> switch (output) {
                case CSV -> throw new com.legend.error.NotImplementedException("graph results have no CSV wire");
                case TDS_TEXT -> throw new com.legend.error.NotImplementedException("graph results have no TDS text");
                case JSON -> text(ExecutionPlan.Format.JSON, objects(root), lowered, dialect, target);
                case STREAMED_JSON -> text(ExecutionPlan.Format.JSON_PER_ROW, objects(root), lowered, dialect, target);
            };
            case TABULAR -> switch (output) {
                case CSV -> wire(WireRender.Format.CSV, lowered, root, dialect, target);
                case JSON -> wire(WireRender.Format.JSON, lowered, root, dialect, target);
                case STREAMED_JSON -> text(ExecutionPlan.Format.JSON_PER_ROW, relation(WireRender.schema(root), lowered),
                        WireRender.rows(lowered, WireRender.schema(root)), dialect, target);
                case TDS_TEXT -> tdsText(lowered, root, dialect, l.ctx(), target);
            };
            // a value's text is the one-column relation `value`, whole: no row to stream
            case SCALAR, COLLECTION -> switch (output) {
                case CSV -> wire(WireRender.Format.CSV, lowered, root, dialect, target);
                case JSON, STREAMED_JSON -> wire(WireRender.Format.JSON, lowered, root, dialect, target);
                case TDS_TEXT -> tdsText(lowered, root, dialect, l.ctx(), target);
            };
        };
        return new ExecutionPlan(declared.stream().map(p -> p.declaration(l.ctx())).toList(), node);
    }

    /**
     * The whole result as Pure's TDS text (the MongoDB line's homework 2, 2026-10-17: the harness's form on the plan
     * path, docs/MONGODB_PLAN_2026_10_16.md §6): a relation's own columns, typed by the query; a value's the one-column
     * relation {@code value}, its column optional only when the value is. A pivot's concrete columns exist only once
     * the database is asked (the legacy executor's probe, ColumnProbe), so a plan with one is refused by name.
     */
    private static ExecutionPlan.TextResult tdsText(SqlQuery lowered, ExprType root, SqlDialect dialect,
            com.legend.compiler.element.ModelContext ctx,
            ExecutionPlan.Target target) {
        // a dynamic pivot's columns are named by the database in the statement itself (TdsTextWrap, step 4)
        Type.RelationType own = Type.schemaView(root.type());
        Type.RelationType schema;
        if (own != null) {
            schema = own;
        } else {
            var m = root.multiplicity();
            boolean optional = m instanceof com.legend.compiler.element.type.Multiplicity.Bounded b && b.lower() == 0
                    && Integer.valueOf(1).equals(b.upper());
            schema = new Type.RelationType(List.of(new Type.Column("value", root.type(), optional
                    ? com.legend.compiler.element.type.Multiplicity.Bounded.ZERO_ONE
                    : com.legend.compiler.element.type.Multiplicity.Bounded.ONE)));
        }
        SqlQuery wrapped = com.legend.lowering.TdsTextWrap.wrap(lowered, schema, null, sqlType -> {
            throw new IllegalStateException("TDS text: an output column outside the query's typed schema (SQL type "
                    + sqlType + "): the lowering's typed slot is missing");
        }, own == null,   // a value's strings quoted whole: the reader types an unquoted cell by its text
                t -> com.legend.compiler.element.ClassLayouts.layoutOf(ctx, t));
        return text(ExecutionPlan.Format.TDS_TEXT, relation(schema, lowered), wrapped, dialect, target);
    }

    /** The whole result as one text of {@code format}: the lowered query, of {@code root}'s type, wrapped by the wire. */
    private static ExecutionPlan.TextResult wire(WireRender.Format format, SqlQuery lowered, ExprType root,
            SqlDialect dialect, ExecutionPlan.Target target) {
        Type.RelationType schema = WireRender.schema(root);
        ExecutionPlan.Format text = switch (format) {
            case CSV -> ExecutionPlan.Format.CSV;
            case JSON -> ExecutionPlan.Format.JSON;
        };
        return text(text, relation(schema, lowered), WireRender.wrap(lowered, schema, format), dialect, target);
    }

    static ExecutionPlan.TextResult text(ExecutionPlan.Format format, ExecutionPlan.ResultType type,
            SqlQuery statement, SqlDialect dialect, ExecutionPlan.Target target) {
        return new ExecutionPlan.TextResult(format, type, sql(statement, dialect, target));
    }

    /** {@code statement} final for {@code target}: rendered in {@code dialect}, each placeholder's binding as the
     *  dialect decided it, the typed tree kept (the judges' plans take it too, JudgePlans, step 6). */
    static ExecutionPlan.Sql sql(SqlQuery statement, SqlDialect dialect, ExecutionPlan.Target target) {
        RenderedStatement rendered = dialect.renderStatement(statement);
        List<ExecutionPlan.Slot> slots = rendered.binds().stream()
                .map(b -> new ExecutionPlan.Slot(b.parameter(), binding(b.binding()))).toList();
        return new ExecutionPlan.Sql(rendered.sql(), slots, target, statement);
    }

    /** A placeholder's binding, as the dialect decided it, in the plan's own terms. */
    private static ExecutionPlan.Binding binding(RenderedStatement.Binding b) {
        return switch (b) {
            case RenderedStatement.Binding.One one -> {
                RenderedStatement.TypeHole hole = one.hole();
                yield new ExecutionPlan.Binding.One(one.nullType(), hole == null ? null
                        : new ExecutionPlan.TypeHole(hole.at(), spellings(hole.types()), kind(hole.absent())));
            }
            case RenderedStatement.Binding.Array array -> new ExecutionPlan.Binding.Array(array.elementSqlType());
        };
    }

    private static java.util.Map<ExecutionPlan.ValueKind, ExecutionPlan.TypeSpelling> spellings(
            java.util.Map<com.legend.sql.ValueKind, RenderedStatement.TypeSpelling> types) {
        java.util.Map<ExecutionPlan.ValueKind, ExecutionPlan.TypeSpelling> out =
                new java.util.EnumMap<>(ExecutionPlan.ValueKind.class);
        types.forEach((k, t) -> out.put(kind(k), new ExecutionPlan.TypeSpelling(t.name(), switch (t.digits()) {
            case NONE -> ExecutionPlan.Digits.NONE;
            case PRECISION -> ExecutionPlan.Digits.PRECISION;
            case PRECISION_AND_SCALE -> ExecutionPlan.Digits.PRECISION_AND_SCALE;
        }, t.fraction())));
        return out;
    }

    private static ExecutionPlan.ValueKind kind(com.legend.sql.ValueKind k) {
        return switch (k) {
            case INTEGER -> ExecutionPlan.ValueKind.INTEGER;
            case DECIMAL -> ExecutionPlan.ValueKind.DECIMAL;
            case FLOATING -> ExecutionPlan.ValueKind.FLOATING;
            case DATE -> ExecutionPlan.ValueKind.DATE;
            case DATE_TIME -> ExecutionPlan.ValueKind.DATE_TIME;
            case DATE_TIME_NANOS -> ExecutionPlan.ValueKind.DATE_TIME_NANOS;
        };
    }

    /** The relation's columns with their three type facts, read from the lowered query (the plan path program's §3). */
    private static ExecutionPlan.Relation relation(Type.RelationType schema, SqlQuery lowered) {
        return new ExecutionPlan.Relation(com.legend.plan.PlanColumns.of(schema, lowered));
    }

    static ExecutionPlan.Value objects(ExprType root) {
        var m = root.multiplicity().requireBounded("a graph fetch's result");
        return new ExecutionPlan.Value(root.type().typeName(), new ExecutionPlan.Multiplicity(m.lower(), m.upper()));
    }

    /** Where the plan runs: the database {@code decided}, the server versions and session statements of its dialect,
     *  and the setup its runtime's connections declare, every statement final for that database. */
    private static ExecutionPlan.Target target(com.legend.database.Target decided, String runtime,
            Compiler.LoweredQuery l, SqlDialect dialect) {
        ExecutionPlan.Database database = switch (decided) {
            case com.legend.database.Target.Platform p -> new ExecutionPlan.Database.Platform(p.type());
            case com.legend.database.Target.Declared d -> new ExecutionPlan.Database.Declared(oneDefinition(d));
        };
        List<ExecutionPlan.SetupStep> steps = new ArrayList<>(
                setup(CsvSeed.declaredSteps(runtime, l.ctx(), dialect), decided.type(), dialect));
        steps.addAll(metamodelSteps(l, decided.type(), dialect));
        return new ExecutionPlan.Target(database, Databases.servers(decided.type()), dialect.sessionSetup(), steps);
    }

    /**
     * The system metamodel store's tables a query reads ({@code Class.all()}: the typed tree's references to
     * {@link com.legend.builtin.SystemMetamodel#STORE_FQN}), set up on the plan's target as the legacy route seeded
     * them on the graph's own system database ({@code exec.SystemDatabase}; the plan path program's step 9,
     * 2026-10-18): each referenced table created and loaded with the graph's facts ({@link MetamodelSeeds}, written
     * at plan time), so a model-free runner answers the query on the plan's session. A query reading no such table
     * adds no step; a table of no rows is created empty.
     */
    private static List<ExecutionPlan.SetupStep> metamodelSteps(Compiler.LoweredQuery l,
            ConnectionDefinition.DatabaseType type, SqlDialect dialect) {
        java.util.Set<String> tables = new java.util.TreeSet<>();
        collectSystemTables(l.root(), tables);
        if (tables.isEmpty()) {
            return List.of();
        }
        com.legend.model.DatabaseDefinition store = l.ctx()
                .findDatabase(com.legend.builtin.SystemMetamodel.STORE_FQN)
                .orElseThrow(() -> new IllegalStateException("the system metamodel store is not in the model"));
        boolean bulk = Databases.loadsRowsInBulk(type);
        List<ExecutionPlan.SetupStep> out = new ArrayList<>();
        for (com.legend.model.DatabaseDefinition.SchemaDefinition schema : store.schemas()) {
            for (com.legend.model.DatabaseDefinition.TableDefinition def : schema.tables()) {
                // a reference names its table schema-qualified (metamodel.classes)
                if (!tables.contains(schema.name() + "." + def.name()) && !tables.contains(def.name())) {
                    continue;
                }
                for (String statement : com.legend.setup.Ddl.metamodelSeed(def, schema.name(), dialect)) {
                    out.add(new ExecutionPlan.SetupStep.Statement(statement));
                }
                List<List<String>> rows = MetamodelSeeds.rows(def.name(), l.ctx());
                if (!rows.isEmpty()) {
                    RowLoad load = com.legend.setup.Ddl.metamodelRows(def, schema.name(), rows);
                    out.add(bulk ? load.staged(dialect)
                            : new ExecutionPlan.SetupStep.Statement(dialect.render(load.values())));
                }
            }
        }
        return out;
    }

    private static void collectSystemTables(com.legend.compiler.spec.typed.TypedSpec n, java.util.Set<String> out) {
        if (n instanceof com.legend.compiler.spec.typed.TypedTableReference tr
                && tr.store().equals(com.legend.builtin.SystemMetamodel.STORE_FQN)) {
            out.add(tr.table());
        }
        for (com.legend.compiler.spec.typed.TypedSpec c : n.children()) {
            collectSystemTables(c, out);
        }
    }

    /** The one definition a declared target opens: its connections may differ in name, not in database —
     *  a query runs on one session. */
    private static ConnectionDefinition oneDefinition(com.legend.database.Target.Declared d) {
        ConnectionDefinition first = d.connections().get(0);
        for (ConnectionDefinition c : d.connections()) {
            if (c.databaseType() != first.databaseType() || !c.specification().equals(first.specification())
                    || !c.authentication().equals(first.authentication())) {
                throw new com.legend.error.NotImplementedException("the runtime binds "
                        + d.connections().stream().map(ConnectionDefinition::qualifiedName).toList()
                        + ", different connections: a query runs on one session");
            }
        }
        return first;
    }

    /**
     * A target's setup as the plan's steps, final for {@code type}: each statement of a connection's SQL (written in
     * H2's spelling, as legend-engine's test data is) adapted to the database, and each table's rows either for the
     * database's bulk loader ({@link Databases#loadsRowsInBulk}) with the statements that stage them, or as the one
     * INSERT the database runs. A table of no rows is no step.
     */
    private static List<ExecutionPlan.SetupStep> setup(List<CsvSeed.Step> steps, ConnectionDefinition.DatabaseType type,
            SqlDialect dialect) {
        boolean bulk = Databases.loadsRowsInBulk(type);
        List<ExecutionPlan.SetupStep> out = new ArrayList<>(steps.size());
        for (CsvSeed.Step step : steps) {
            switch (step) {
                case CsvSeed.Step.Sql blob -> {
                    for (String statement : com.legend.sql.RawSql.splitStatements(blob.text())) {
                        out.add(new ExecutionPlan.SetupStep.Statement(
                                new CsvSeed.Step.Sql(statement, blob.writtenFor()).runOn(dialect)));
                    }
                }
                case CsvSeed.Step.Rows rows -> {
                    RowLoad load = rows.load();
                    if (!load.rows().isEmpty()) {
                        out.add(bulk ? load.staged(dialect)
                                : new ExecutionPlan.SetupStep.Statement(dialect.render(load.values())));
                    }
                }
            }
        }
        return out;
    }
}
