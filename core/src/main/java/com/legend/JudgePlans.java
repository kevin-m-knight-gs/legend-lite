// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend;

import com.legend.compiler.element.type.ExprType;
import com.legend.compiler.element.type.PlatformTypes;
import com.legend.compiler.element.type.Type;
import com.legend.compiler.spec.SpecCompiler;
import com.legend.compiler.spec.typed.TypedSpec;
import com.legend.exec.Column;
import com.legend.exec.ExecutionResult;
import com.legend.exec.PlanRunner;
import com.legend.exec.PlanSessions;
import com.legend.exec.Row;
import com.legend.executionplan.ExecutionPlan;
import com.legend.lowering.TypedRows;
import com.legend.lowering.VerdictSql;
import com.legend.plan.PlanColumns;
import com.legend.sql.OutputCol;
import com.legend.sql.SqlQuery;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * THE JUDGES' PLANS (the plan path program's step 6, docs/PLAN_PATH_PROGRAM_2026_10_17.md §6, 2026-10-18): every
 * statement a judge sends is an {@link ExecutionPlan} the model-free runner executes on the body's own session.
 * The DATABASE judge's verdict statement (the lowering's {@link VerdictSql}, composed over both sides' plans) is a
 * typed-rows plan whose one row is the verdict; the HOST judge receives each side as a plan's result -- the side's
 * lowered query as a typed-rows plan, or a graph fetch's JSON text -- its cells read from the driver by the plan's
 * declared types ({@code exec.JdbcDecode}, the typed seam of step 7: the Z2 ruling's carrier-decode bridge at the
 * plan boundary), then compares. The legacy
 * executor still PLANS a side ({@link StatementExecutor#planHostSide}: the let prefix, the inliner's splice, the
 * natives' staging, the store resolution, the wire reconciliation) -- the one road both judges' sides take, until the
 * test runners emit plan sequences (step 8) and it is deleted (step 11).
 */
final class JudgePlans {

    private JudgePlans() {
    }

    /**
     * The target a judge's plan names: the body's runtime's database (its first declared connection: every
     * connection of a runtime is of one database type, the one fact a given session is checked against) with the
     * server versions narrowed to the body's session's ({@code PlanMaker.serversFor}: the env's dialect is the one
     * the legacy seam chose for that session, {@code SqlDialect.forServer}, so the plan is written for the session
     * it runs on; found 2026-10-19 by channel B's H2 judge, every case refused "written for H2 [2.1, 2.2], and the
     * session is H2 2.4"); no session statements and no setup -- the body's session was established by the runner
     * that opened it ({@link Execution#dialectOf}), and the judge's plan runs within it ({@link PlanSessions#given}).
     */
    static ExecutionPlan.Target target(StatementExecutor.ExecEnv env) {
        com.legend.database.Target decided = Compiler.executesOn(env.ctx(), env.runtimeFqn());
        ExecutionPlan.Database database = switch (decided) {
            case com.legend.database.Target.Platform p -> new ExecutionPlan.Database.Platform(p.type());
            case com.legend.database.Target.Declared d ->
                    new ExecutionPlan.Database.Declared(d.connections().get(0));
        };
        return new ExecutionPlan.Target(database,
                PlanMaker.serversFor(decided.type(), com.legend.exec.Sessions.version(env.connection())),
                List.of(), List.of());
    }

    /** {@code statement}'s typed-rows plan: the statement itself, its columns {@code schema}'s with their three
     *  type facts, each row's cells read by the session's typed method (the plan path program's step 7,
     *  docs/PLAN_AND_RESULT_MODEL_2026_10_18.md §3, 2026-10-18; until then the JSON rows wire wrapped the statement
     *  and the runner parsed each row back). A typed-rows statement answers the plan's columns in their order, and
     *  that is checked here where the statement's outputs are known: a column out of its output's place is a
     *  planner defect, refused by name. A sort key that is no output is carried as a hidden column
     *  ({@link TypedRows#statement}: H2's FULL OUTER JOIN emulation addresses its sort key among the outputs). */
    static ExecutionPlan.TdsResult rowsPlan(SqlQuery statement, Type.RelationType schema,
            StatementExecutor.ExecEnv env) {
        List<ExecutionPlan.Column> columns = PlanColumns.of(schema, statement);
        List<OutputCol> outs = statement.outputs();
        boolean anyByName = columns.stream().anyMatch(c -> outs.stream().anyMatch(o -> o.name().equals(c.name())));
        if (anyByName && columns.size() == outs.size()) {
            for (int i = 0; i < columns.size(); i++) {
                if (!columns.get(i).name().equals(outs.get(i).name())) {
                    throw new IllegalStateException("the plan's columns " + columns.stream()
                            .map(ExecutionPlan.Column::name).toList() + " are not the statement's outputs "
                            + outs.stream().map(OutputCol::name).toList() + " in order");
                }
            }
        }
        return new ExecutionPlan.TdsResult(ExecutionPlan.TdsResult.Shape.RELATION, columns,
                PlanMaker.sql(TypedRows.statement(statement), env.dialect(), target(env)));
    }

    /** {@code plan} with the definitions of the frames it references at its head -- the verdict batch's, in
     *  database mode, where a let's relation is one CTE every statement of the body reads (leg 3.4 step 2); the
     *  legacy executor attached them at its render ({@code StatementExecutor.renderFor}), and a side's plan needs
     *  them the same way (the plan path program's step 6, 2026-10-18: `//spec:corpus_h2`'s database pass found
     *  660 row legs reading a frame that was not there). In host mode a frame is pasted, and the plan is its own. */
    private static SqlQuery withFrames(SqlQuery plan, StatementExecutor.ExecEnv env) {
        var batch = env.verdictBatch();
        return batch == null ? plan : com.legend.sql.FrameCtes.attach(plan, batch.frames());
    }

    /** {@code node} run on a session of {@code on} -- the body's own, given ({@link PlanSessions#given}): checked
     *  against the plan's target, never closed. */
    static PlanRunner.Rows run(ExecutionPlan.TdsResult node, PlanSessions.Source on) {
        try {
            return PlanRunner.rows(new ExecutionPlan(List.of(), node), Map.of(), on);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /** The database judge's statements as plans ({@link com.legend.exec.VerdictBatch.Runner}): a verdict statement's
     *  one row, or a fused batch's rows, decoded by the verdict schema. */
    static com.legend.exec.VerdictBatch.Runner runner(StatementExecutor.ExecEnv env) {
        return new com.legend.exec.VerdictBatch.Runner() {
            @Override
            public List<List<Object>> rows(SqlQuery statement, PlanSessions.Source on, boolean fused) {
                Type.RelationType schema = fused ? VerdictSql.batchSchema() : VerdictSql.schema();
                return run(rowsPlan(statement, schema, env), on).rows();
            }

            @Override
            public int chars(SqlQuery statement) {
                return env.dialect().render(statement).length();
            }
        };
    }

    /**
     * A side's value as a plan's result (the host judge's sides, §6): the side planned as the legacy executor plans a
     * verdict side, with no canon rider ({@link StatementExecutor#planHostSide}); a side the pipeline answers without
     * a plan (a literal-only side the compiler folds, a host constant) is that answer, nothing sent. {@code identity}:
     * the identity lane (an assert-condition side whose in-SQL equality needs instance identity).
     * A side whose plan answers a DYNAMIC PIVOT's columns unnamed runs as a keyed plan (its keys read first,
     * {@link #tabular}); a cast over the pivot names them, and such a side is an ordinary relation (step 7).
     */
    static ExecutionResult evalSide(TypedSpec value, List<TypedSpec> letPrefix, SpecCompiler specs,
            StatementExecutor.ExecEnv env, boolean identity, boolean fold,
            java.util.function.@com.legend.base.Nullable BiFunction<TypedSpec, java.util.Set<String>,
                    TypedSpec> hook) {
        StatementExecutor.PlannedValue pv = StatementExecutor.planHostSide(value, letPrefix, specs, env, identity,
                fold, hook);
        StatementExecutor.WrappedSide side = pv.side();
        if (side == null) {
            return java.util.Objects.requireNonNull(pv.answered(), "a side neither planned nor answered");
        }
        ExecutionResult result = sideResult(side, env);
        // the engine's reader semantics for toOne() over a relation: exactly one row (the legacy executor's check)
        TypedSpec root = pv.root();
        if (root instanceof com.legend.compiler.spec.typed.TypedNativeCall tw
                && com.legend.builtin.Pure.isToOneCall(tw.callee().qualifiedName())
                && !tw.args().isEmpty() && Type.isRelation(tw.args().get(0).info().type())
                && result instanceof ExecutionResult.Tabular tab && tab.rows().size() != 1) {
            throw new IllegalStateException("toOne() over a relation returned " + tab.rows().size()
                    + " row(s) — the exactly-one contract (engine reader semantics)");
        }
        return result;
    }

    /** A planned side's result: its plan run by the runner, its rows decoded by the plan's types and shaped as the
     *  side's declared shape (the legacy executor's own shaping rules, read from the plan's facts instead of JDBC). */
    static ExecutionResult sideResult(StatementExecutor.WrappedSide w, StatementExecutor.ExecEnv env) {
        ExprType info = w.shapeInfo();
        ExecutionResult result = sideResult0(w, env);
        if (com.legend.diagnostics.Diagnostics.dumpSql()) {
            // the SQL dump's companion: the side's plan columns and the cells the decode gave, with their carriers
            StringBuilder sb = new StringBuilder("[side] " + w.shape() + " " + info.type().typeName() + " slots=");
            for (com.legend.sql.OutputCol oc : w.plan().outputs()) {
                sb.append(oc.name()).append(':').append(oc.type()).append(' ');
            }
            sb.append("cells=");
            for (Row r : result.rows()) {
                for (Object v : r.values()) {
                    sb.append(v == null ? "null" : v + "<" + v.getClass().getSimpleName() + ">").append(' ');
                }
            }
            System.err.println(sb);
        }
        return result;
    }

    private static ExecutionResult sideResult0(StatementExecutor.WrappedSide w, StatementExecutor.ExecEnv env) {
        ExprType info = w.shapeInfo();
        return switch (w.shape()) {
            case GRAPH -> new ExecutionResult.Graph(objects(w, env), info.type());
            case TABULAR -> tabular(w, env);
            case SCALAR, COLLECTION -> values(w, env);
        };
    }

    /** A graph fetch side's objects: its statement's one cell, the objects' JSON array as the database built it (the
     *  side's graph root), read as a typed value of one text column -- the plan asks the database for no output text
     *  (docs/RESULT_BOUNDARY_PROGRAM_2026_10_26.md, the end state of 2026-10-27). */
    private static String objects(StatementExecutor.WrappedSide w, StatementExecutor.ExecEnv env) {
        SqlQuery plan = withFrames(w.plan(), env);
        List<OutputCol> outs = plan.outputs();
        if (outs.size() != 1) {
            throw new IllegalStateException("a graph fetch side's statement answers " + outs.size() + " columns "
                    + outs.stream().map(OutputCol::name).toList() + ": its objects' array is one cell");
        }
        ExecutionPlan.TdsResult node = new ExecutionPlan.TdsResult(ExecutionPlan.TdsResult.Shape.VALUE,
                List.of(new ExecutionPlan.Column(outs.get(0).name(), "String", new ExecutionPlan.Multiplicity(0, 1),
                        com.legend.executionplan.ColumnFacts.slot(outs.get(0).type()), null)),
                PlanMaker.sql(plan, env.dialect(), target(env)));
        PlanRunner.Rows rows = run(node, PlanSessions.given(w.connection()));
        if (rows.rows().size() != 1) {
            throw new IllegalStateException("a graph fetch side's statement answered " + rows.rows().size()
                    + " rows: its objects' array is one row");
        }
        return switch (rows.rows().get(0).get(0)) {
            case null -> "";
            case String objects -> objects;
            case Object other -> throw new IllegalStateException("a graph fetch side's objects' array is no text: "
                    + other.getClass().getSimpleName());
        };
    }

    private static ExecutionResult tabular(StatementExecutor.WrappedSide w, StatementExecutor.ExecEnv env) {
        ExprType info = w.shapeInfo();
        SqlQuery plan = w.plan();
        Type.RelationType schema = Type.schemaView(info.type());
        boolean lateBound = plan.outputs().isEmpty() && (schema == null || schema.isLateBound());
        if (lateBound) {
            // a raw grid (executeInDbToTDS) has no typed outputs: the database's reported columns frame it
            plan = com.legend.exec.WireTypes.staticized(plan, env.dialect(), w.connection(),
                    StatementExecutor.wireMemo(w.connection()));
            schema = com.legend.compiler.spec.VerdictQueries.wireSchema(plan.outputs());
        }
        if (schema == null) {
            throw new IllegalStateException("TABULAR result without a relation root type: " + info.type().typeName());
        }
        if (!lateBound && TypedRows.dynamicPivotUnnamed(plan, schema)) {
            return keyed(plan, schema, info, w, env);
        }
        if (!lateBound) {
            // ASSERT-BOUNDARY determinism (user design, 2026-08-29; the rule of the legacy TDS canon wrap this side
            // no longer rides, kept on the plan path): a grid under assertion is deterministically ordered even when
            // the test forgot to sort -- a FEATURE of the assert surface, always on; ScanOrder is the one key owner,
            // and the corpus lane's test-only scan-order pass then finds the order in place, as it did under the
            // wrap (measured 2026-10-18: without it that pass counted the order it added on eight inheritance
            // bodies, the lane's engine-order register moving for an accounting change).
            plan = com.legend.sql.ScanOrder.stabilize(plan);
        }
        plan = withFrames(plan, env);
        // a row-struct column is typed nesting over a flat physical reality: the prefixed columns the join emitted
        schema = flattenStructColumns(schema);
        // a grid's cells typed from the driver: a temporal cell by the database's own kind (a DATE a day)
        PlanRunner.Rows rows = run(rowsPlan(plan, schema, env), PlanSessions.given(w.connection()));
        List<Column> columns = new ArrayList<>(schema.columns().size());
        for (Type.Column c : schema.columns()) {
            columns.add(new Column(c.name(), c.type(), c.multiplicity()));
        }
        List<Row> out = new ArrayList<>(rows.rows().size());
        for (List<Object> r : rows.rows()) {
            out.add(new Row(r));
        }
        return new ExecutionResult.Tabular(columns, out, info.type());
    }

    /** A side over a DYNAMIC PIVOT nothing names: its keyed plan ({@link PlanMaker#keyed}, the probe plan the server
     *  runs), its keys read first and its body written for them; each answered column typed by the schema -- a fixed
     *  one by its name, a per-key one by its aggregate's template -- and named as the plan names it (a generated
     *  name presented in its quotes, as the engine labels it). */
    private static ExecutionResult keyed(SqlQuery plan, Type.RelationType schema, ExprType info,
            StatementExecutor.WrappedSide w, StatementExecutor.ExecEnv env) {
        SqlQuery framed = withFrames(plan, env);
        com.legend.sql.SqlSource.Pivot pivot = java.util.Objects.requireNonNull(
                com.legend.lowering.KeyedPivot.dynamic(framed), "a dynamic pivot side whose plan holds no such pivot");
        ExecutionPlan.Keyed keyed = PlanMaker.keyed(framed, pivot, schema, env.dialect(), target(env));
        PlanRunner.Rows rows;
        try {
            rows = PlanRunner.rows(new ExecutionPlan(List.of(), keyed), Map.of(), PlanSessions.given(w.connection()));
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        List<Type> perKey = new ArrayList<>();
        int fixed = 0;
        for (ExecutionPlan.Keyed.Output o : keyed.outputs()) {
            if (o instanceof ExecutionPlan.Keyed.PerKey p) {
                perKey.add(schema.dynamicColumns().stream().filter(c -> c.name().equals(p.column().name()))
                        .findFirst().orElseThrow(() -> new IllegalStateException("a per-key column '"
                                + p.column().name() + "' with no template in " + schema.dynamicColumns()))
                        .type());
            } else {
                fixed++;
            }
        }
        List<Column> columns = new ArrayList<>(rows.columns().size());
        for (int i = 0; i < rows.columns().size(); i++) {
            ExecutionPlan.Column c = rows.columns().get(i);
            if (i < fixed) {
                Type.Column sc = schema.columns().stream().filter(x -> x.name().equals(c.name())).findFirst()
                        .orElseThrow(() -> new IllegalStateException("a fixed column '" + c.name()
                                + "' outside the schema " + schema.columns()));
                columns.add(new Column(sc.name(), sc.type(), sc.multiplicity()));
            } else {
                columns.add(new Column(Type.RelationType.presentPivotName(c.name()),
                        perKey.get((i - fixed) % perKey.size())));
            }
        }
        List<Row> out = new ArrayList<>(rows.rows().size());
        for (List<Object> r : rows.rows()) {
            out.add(new Row(r));
        }
        return new ExecutionResult.Tabular(columns, out, info.type());
    }

    /** A value root's rows in the VALUE lane's carrier: a timestamp slot's cell with its digits trimmed
     *  ({@link com.legend.values.PureDateLiteral#trimmed}) -- the database judge's value-lane canon spells a
     *  timestamp so ({@code dateTimeText}: a whole second none, {@code .123} three), and so did the JSON rows
     *  wire's carrier spelling of a value root (step 6), where a grid's cell keeps the nine digits of the engine's
     *  relational read (its GRID_FETCH canon; step 7, 2026-10-18). A text-carried temporal (a literal's own digits)
     *  is read as written and stays. */
    private static PlanRunner.Rows valueLane(PlanRunner.Rows rows) {
        List<ExecutionPlan.Column> columns = rows.columns();
        boolean any = columns.stream().anyMatch(c -> c.slot() != null && c.slot().startsWith("TIMESTAMP"));
        if (!any) {
            return rows;
        }
        List<List<Object>> out = new ArrayList<>(rows.rows().size());
        for (List<Object> r : rows.rows()) {
            List<Object> cells = new ArrayList<>(r);
            for (int i = 0; i < columns.size(); i++) {
                String slot = columns.get(i).slot();
                if (slot != null && slot.startsWith("TIMESTAMP")
                        && cells.get(i) instanceof com.legend.values.PureDateLiteral d) {
                    cells.set(i, d.trimmed());
                }
            }
            out.add(cells);
        }
        return new PlanRunner.Rows(columns, out);
    }

    /** A value root's cells: the one-column relation {@code value}, shaped as a scalar or a collection with the
     *  legacy executor's rules (a required root of no rows refuses; a scalar of two rows refuses; a null cell in a
     *  collection is the TDSNull value under a TDSNull root, a null slot on the Any and variant lanes, a lowering
     *  defect otherwise). */
    private static ExecutionResult values(StatementExecutor.WrappedSide w, StatementExecutor.ExecEnv env) {
        ExprType info = w.shapeInfo();
        Type type = info.type();
        PlanRunner.Rows rows = valueLane(run(rowsPlan(withFrames(w.plan(), env), TypedRows.schema(info), env),
                PlanSessions.given(w.connection())));
        long lower = info.multiplicity().requireBounded("result shape").lower();
        boolean tdsNullRoot = type.equals(new Type.ClassType(PlatformTypes.TDS_NULL_FQN));
        boolean anyRoot = PlatformTypes.isAny(type) || tdsNullRoot;
        boolean variantRoot = type instanceof Type.ClassType ct && PlatformTypes.isVariant(ct);
        if (w.shape() == com.legend.plan.ResultShape.SCALAR) {
            if (rows.rows().isEmpty() && lower >= 1) {
                throw new IllegalStateException("Cannot cast a collection of size 0 to multiplicity "
                        + info.multiplicity().text());
            }
            if (rows.rows().size() > 1) {
                throw new IllegalStateException("scalar-shaped result returned more than one row — the to-one"
                        + " contract was not enforced upstream");
            }
            Object v = rows.rows().isEmpty() ? null : rows.rows().get(0).get(0);
            if (v instanceof List<?> l) {
                // the list wire arriving as one cell under a scalar-shaped root: the collection is the value
                return new ExecutionResult.Collection(new ArrayList<>(l), type);
            }
            return new ExecutionResult.Scalar(v, type);
        }
        List<Object> values = new ArrayList<>(rows.rows().size());
        for (List<Object> r : rows.rows()) {
            Object v = r.get(0);
            if (v == null) {
                if (tdsNullRoot) {
                    values.add(PlatformTypes.TDS_NULL_CELL);
                } else if (anyRoot || variantRoot) {
                    values.add(null);
                } else {
                    throw new IllegalStateException("NULL cell reached COLLECTION egress — the lowerer owns the"
                            + " null-drop (COMPILER_SHORTCUT_AUDIT §5); a NULL here is a lowering defect, never an"
                            + " empty");
                }
            } else {
                values.add(v);
            }
        }
        if (rows.rows().isEmpty() && lower >= 1) {
            throw new IllegalStateException("Cannot cast a collection of size 0 to multiplicity "
                    + info.multiplicity().text());
        }
        if (values.size() < lower) {
            throw new IllegalStateException("collection-shaped result holds " + values.size()
                    + " values, below its declared lower bound " + lower
                    + " — NULL cells were dropped past the type's own contract");
        }
        return new ExecutionResult.Collection(values, type);
    }

    /** A row-struct column (a navigate slot) expanded to its prefixed flat columns, as the join emitted them. */
    private static Type.RelationType flattenStructColumns(Type.RelationType schema) {
        if (schema.columns().stream().noneMatch(c -> c.type() instanceof Type.RelationType)) {
            return schema;
        }
        List<Type.Column> flat = new ArrayList<>();
        for (Type.Column c : schema.columns()) {
            if (c.type() instanceof Type.RelationType sub) {
                for (Type.Column sc : sub.columns()) {
                    flat.add(new Type.Column(c.name() + "_" + sc.name(), sc.type(), sc.multiplicity()));
                }
            } else {
                flat.add(c);
            }
        }
        return new Type.RelationType(flat);
    }
}
