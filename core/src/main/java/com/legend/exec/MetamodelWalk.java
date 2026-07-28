// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.exec;

import com.legend.compiler.element.ModelContext;
import com.legend.error.NotImplementedException;
import com.legend.model.DatabaseDefinition;
import com.legend.model.RelationalDataType;
import com.legend.model.RelationalOperation;

import java.util.ArrayList;
import java.util.List;

/**
 * HOST-side evaluation of store-METAMODEL navigations — the engine's
 * {@code db.schemas->view(...)->columnMappings...inferRelationalType()}
 * test surface (typeInference family). The handles wrap legend-lite's
 * OWN compiled model ({@link DatabaseDefinition}); {@code
 * inferRelationalType} resolves the column expression's declared SQL
 * type; {@code dataTypeToSqlText} spells it in the engine's text form.
 * Every unrecognized shape returns null (the caller's walk falls
 * through to its own walls) — never a silent wrong answer.
 */
public final class MetamodelWalk {

    private MetamodelWalk() {
    }

    public record Db(DatabaseDefinition db) {
    }

    public record Sch(DatabaseDefinition db,
            DatabaseDefinition.SchemaDefinition schema) {
    }

    public record Vw(DatabaseDefinition db,
            DatabaseDefinition.ViewDefinition view) {
    }

    public record Vcm(DatabaseDefinition db,
            DatabaseDefinition.ViewDefinition.ViewColumnMapping vcm) {
    }

    public record Rop(DatabaseDefinition db, RelationalOperation op) {
    }

    public record Dt(RelationalDataType type) {
    }

    /** A Database ELEMENT reference as a metamodel handle, or null. */
    public static Object database(ModelContext ctx, String fqn) {
        return ctx.findDatabase(fqn).map(Db::new).orElse(null);
    }

    /** Property step over a handle; null = not a metamodel property. */
    public static Object prop(Object recv, String prop) {
        if (recv instanceof Db d && prop.equals("schemas")) {
            List<Object> out = new ArrayList<>();
            for (var s : d.db().schemas()) {
                out.add(new Sch(d.db(), s));
            }
            // top-level tables/views ARE the default schema (the parser
            // stores them on the Database; the engine models a 'default'
            // Schema instance)
            if (!d.db().tables().isEmpty() || !d.db().views().isEmpty()) {
                out.add(new Sch(d.db(),
                        new DatabaseDefinition.SchemaDefinition("default",
                                d.db().tables(), d.db().views())));
            }
            return out;
        }
        if (recv instanceof Vw v && prop.equals("columnMappings")) {
            List<Object> out = new ArrayList<>();
            for (var cm : v.view().columnMappings()) {
                out.add(new Vcm(v.db(), cm));
            }
            return out;
        }
        if (recv instanceof Vcm c) {
            return switch (prop) {
                case "columnName" -> c.vcm().name();
                case "relationalOperationElement" ->
                        new Rop(c.db(), c.vcm().expression());
                default -> null;
            };
        }
        return null;
    }

    /** {@code schema->view('name')} navigation; null = not applicable. */
    public static Object view(Object recv, String name) {
        if (recv instanceof Sch s) {
            for (var v : s.schema().views()) {
                if (v.name().equals(name)) {
                    return new Vw(s.db(), v);
                }
            }
        }
        return null;
    }

    /** {@code inferRelationalType} over a column expression handle —
     * the DECLARED SQL type the expression carries. Single-element
     * lists unwrap (pure toOne semantics ride the walk). */
    public static Object infer(Object recv) {
        Object r = recv instanceof List<?> l && l.size() == 1
                ? l.get(0) : recv;
        if (!(r instanceof Rop rop)) {
            return null;
        }
        RelationalDataType t = inferOp(rop.db(), rop.op());
        return t == null ? null : new Dt(t);
    }

    private static RelationalDataType inferOp(DatabaseDefinition db,
            RelationalOperation op) {
        return switch (op) {
            case RelationalOperation.ColumnRef c ->
                    columnType(db, c.table(), c.column());
            case RelationalOperation.FunctionCall f -> switch (
                    f.name().toLowerCase(java.util.Locale.ROOT)) {
                // aggregates carry their argument's type (engine
                // inferDynaFunctionReturnType: max/min/sum/avg family)
                case "max", "min", "sum", "distinct", "average", "avg" ->
                        f.args().isEmpty() ? null
                                : inferOp(db, f.args().get(0));
                case "count" -> new RelationalDataType.Integer_();
                default -> null;
            };
            case RelationalOperation.JoinNavigation j ->
                    j.terminal() == null ? null : inferOp(db, j.terminal());
            case RelationalOperation.Group g -> inferOp(db, g.inner());
            // boolean expressions spell BIT (engine H2 boolean SQL type)
            case RelationalOperation.Comparison ignored ->
                    new RelationalDataType.Bit();
            case RelationalOperation.BooleanOp ignored ->
                    new RelationalDataType.Bit();
            case RelationalOperation.IsNull ignored ->
                    new RelationalDataType.Bit();
            case RelationalOperation.IsNotNull ignored ->
                    new RelationalDataType.Bit();
            default -> null;
        };
    }

    /** The declared type of {@code table.column}, searching every
     * schema (view expressions spell bare table names). */
    private static RelationalDataType columnType(DatabaseDefinition db,
            String table, String column) {
        List<DatabaseDefinition.TableDefinition> all = new ArrayList<>(
                db.tables());
        for (var s : db.schemas()) {
            all.addAll(s.tables());
        }
        for (var t : all) {
            if (t.name().equalsIgnoreCase(table)) {
                for (var c : t.columns()) {
                    if (c.name().equalsIgnoreCase(column)) {
                        return c.dataType();
                    }
                }
            }
        }
        return null;
    }

    /** {@code dataTypeToSqlText} — the ENGINE's spelling: {@code
     * DECIMAL(p, s)} with a space, {@code BIT} for booleans (distinct
     * from the plan-tuple spelling in PlanText.spell). */
    public static Object sqlText(Object recv) {
        Object r = recv instanceof List<?> l && l.size() == 1
                ? l.get(0) : recv;
        if (!(r instanceof Dt d)) {
            return null;
        }
        return switch (d.type()) {
            case RelationalDataType.Integer_ ignored -> "INT";
            case RelationalDataType.BigInt ignored -> "BIGINT";
            case RelationalDataType.SmallInt ignored -> "SMALLINT";
            case RelationalDataType.TinyInt ignored -> "TINYINT";
            case RelationalDataType.Varchar v -> "VARCHAR(" + v.size() + ")";
            case RelationalDataType.Char_ c -> "CHAR(" + c.size() + ")";
            case RelationalDataType.Double_ ignored -> "DOUBLE";
            case RelationalDataType.Float_ ignored -> "FLOAT";
            case RelationalDataType.Real ignored -> "REAL";
            case RelationalDataType.Decimal dc ->
                    "DECIMAL(" + dc.precision() + ", " + dc.scale() + ")";
            case RelationalDataType.Numeric n ->
                    "NUMERIC(" + n.precision() + ", " + n.scale() + ")";
            case RelationalDataType.Timestamp ignored -> "TIMESTAMP";
            case RelationalDataType.Date_ ignored -> "DATE";
            case RelationalDataType.Bit ignored -> "BIT";
            default -> throw new NotImplementedException(
                    "dataTypeToSqlText spelling for " + d.type()
                    + " pending");
        };
    }
}
