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

    public record Rop(DatabaseDefinition db, ModelContext ctx,
            RelationalOperation op) {
    }

    public record Dt(RelationalDataType type) {
    }

    public record Mm(ModelContext ctx,
            com.legend.model.LegacyMappingDefinition mapping) {
    }

    public record Cm(ModelContext ctx,
            com.legend.model.ClassMapping.Relational cm) {
    }

    public record Pm(ModelContext ctx,
            com.legend.model.PropertyMapping pm) {
    }

    /** A Mapping ELEMENT reference as a metamodel handle, or null. */
    public static Object mapping(ModelContext ctx, String fqn) {
        return ctx.findLegacyMapping(fqn).map(m -> new Mm(ctx, m))
                .orElse(null);
    }

    /** {@code rootClassMappingByClass} — the class's relational set. */
    public static Object rootClassMappingByClass(Object recv,
            String classFqn) {
        if (recv instanceof Mm m) {
            for (var cm : m.mapping().classMappings()) {
                if (cm instanceof com.legend.model.ClassMapping.Relational r
                        && r.className().equals(classFqn)) {
                    return new Cm(m.ctx(), r);
                }
            }
        }
        return null;
    }

    /** {@code propertyMappingsByPropertyName} — declaration order. */
    public static Object propertyMappingsByName(Object recv, String name) {
        if (recv instanceof Cm c) {
            List<Object> out = new ArrayList<>();
            for (var pm : c.cm().propertyMappings()) {
                if (pm.propertyName().equals(name)) {
                    out.add(new Pm(c.ctx(), pm));
                }
            }
            return out;
        }
        return null;
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
                        new Rop(c.db(), null, c.vcm().expression());
                default -> null;
            };
        }
        if (recv instanceof Pm p
                && prop.equals("relationalOperationElement")) {
            return switch (p.pm()) {
                case com.legend.model.PropertyMapping.Expression ex ->
                        new Rop(null, p.ctx(), ex.expression());
                case com.legend.model.PropertyMapping.Column col ->
                        new Rop(null, p.ctx(),
                                new RelationalOperation.ColumnRef(
                                        col.database(), col.table(),
                                        col.column()));
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
        RelationalDataType t = inferOp(rop, rop.op());
        return t == null ? null : new Dt(t);
    }

    private static RelationalDataType inferOp(Rop env,
            RelationalOperation op) {
        return switch (op) {
            case RelationalOperation.ColumnRef c ->
                    columnType(env, c.databaseName(), c.table(), c.column());
            case RelationalOperation.FunctionCall f -> switch (
                    f.name().toLowerCase(java.util.Locale.ROOT)) {
                // aggregates carry their argument's type (engine
                // inferDynaFunctionReturnType: max/min/sum/avg family)
                case "max", "min", "distinct" ->
                        f.args().isEmpty() ? null
                                : inferOp(env, f.args().get(0));
                // sum/avg PROMOTE float-family to DOUBLE (engine
                // inferDynaFunctionReturnType aggregation rule)
                case "sum", "average", "avg" -> {
                    RelationalDataType at = f.args().isEmpty() ? null
                            : inferOp(env, f.args().get(0));
                    yield at instanceof RelationalDataType.Float_
                            || at instanceof RelationalDataType.Real
                            ? new RelationalDataType.Double_() : at;
                }
                case "count" -> new RelationalDataType.Integer_();
                // case(c1, v1, ..., else): the SAFE type over the value
                // branches (engine getSafeType lattice)
                case "case" -> {
                    RelationalDataType acc = null;
                    for (int i = 1; i < f.args().size(); i += 2) {
                        acc = safe(acc, inferOp(env, f.args().get(i)));
                    }
                    if (f.args().size() % 2 == 1) {
                        acc = safe(acc, inferOp(env,
                                f.args().get(f.args().size() - 1)));
                    }
                    yield acc;
                }
                // numeric operators widen positionally (engine math
                // compatibility: DOUBLE beats INT; DECIMAL beats both)
                case "plus", "minus", "times", "divide" -> {
                    RelationalDataType acc = null;
                    for (var arg : f.args()) {
                        acc = safe(acc, inferOp(env, arg));
                    }
                    yield acc;
                }
                default -> null;
            };
            case RelationalOperation.JoinNavigation j ->
                    j.terminal() == null ? null
                            : inferOp(env, j.terminal());
            case RelationalOperation.Group g -> inferOp(env, g.inner());
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

    /** The engine's getSafeType pair rule (typeInference.pure): equal
     * types keep; DECIMAL beats int/double/float as-is; two decimals
     * widen to DECIMAL(maxIntDigits+maxScale, maxScale); DOUBLE beats
     * integers. Null operands pass the other side through. */
    private static RelationalDataType safe(RelationalDataType a,
            RelationalDataType b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        if (a.equals(b)) {
            return a;
        }
        Integer[] da = decimalOf(a);
        Integer[] db2 = decimalOf(b);
        if (da != null && db2 != null) {
            int intDigits = Math.max(da[0] - da[1], db2[0] - db2[1]);
            int scale = Math.max(da[1], db2[1]);
            return new RelationalDataType.Decimal(intDigits + scale, scale);
        }
        if (da != null) {
            return new RelationalDataType.Decimal(da[0], da[1]);
        }
        if (db2 != null) {
            return new RelationalDataType.Decimal(db2[0], db2[1]);
        }
        boolean aFloat = a instanceof RelationalDataType.Double_
                || a instanceof RelationalDataType.Float_
                || a instanceof RelationalDataType.Real;
        boolean bFloat = b instanceof RelationalDataType.Double_
                || b instanceof RelationalDataType.Float_
                || b instanceof RelationalDataType.Real;
        if (aFloat || bFloat) {
            return new RelationalDataType.Double_();
        }
        if (a instanceof RelationalDataType.Varchar va
                && b instanceof RelationalDataType.Varchar vb) {
            return new RelationalDataType.Varchar(
                    Math.max(va.size(), vb.size()));
        }
        return a;
    }

    private static Integer[] decimalOf(RelationalDataType t) {
        if (t instanceof RelationalDataType.Decimal d) {
            return new Integer[]{d.precision(), d.scale()};
        }
        if (t instanceof RelationalDataType.Numeric n) {
            return new Integer[]{n.precision(), n.scale()};
        }
        return null;
    }

    /** The declared type of {@code table.column}: the op's OWN database
     * (mapping expressions carry it) via ctx, else the handle db —
     * searching declared schemas and the top-level default. */
    private static RelationalDataType columnType(Rop env, String opDb,
            String table, String column) {
        DatabaseDefinition db = env.db();
        if (opDb != null && env.ctx() != null) {
            db = env.ctx().findDatabase(opDb).orElse(db);
        }
        if (db == null) {
            return null;
        }
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
