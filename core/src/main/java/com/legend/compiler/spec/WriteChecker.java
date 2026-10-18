package com.legend.compiler.spec;

import com.legend.compiler.element.type.ExprType;
import com.legend.compiler.element.type.Type;
import com.legend.compiler.spec.typed.TypedSpec;
import com.legend.compiler.spec.typed.TypedTableReference;
import com.legend.compiler.spec.typed.TypedWrite;
import com.legend.protocol.spec.AppliedFunction;

import java.util.List;
import java.util.Optional;

/**
 * Relation {@code write} (engine {@code WriteChecker}) &mdash; fully generic
 * ({@code write<T>(Relation<T>[1] [, target:Any[1]]):Integer[1]}); the optional
 * destination reference rides the node for the back-end.
 *
 * <p>A write INTO A STORE TABLE ({@code rel->write(#>{db.t}#)}) is typed by the
 * table's columns BY NAME (docs/WRITES_DESIGN_2026_10_18.md §4, L1; upstream
 * aligns them by position): each of the relation's columns names a column of the
 * table, of the same type or both numeric; the table's other columns take their
 * defaults. A column's multiplicity is not compared: a null into a NOT NULL
 * column is the database's error, as is a number it cannot hold
 * (SEMANTICS_REGISTER S89). The relation rule is applied here, never by loosening
 * the generic {@code T}-binding, as {@link ConcatenateChecker} applies its own.
 */
final class WriteChecker {

    private WriteChecker() {
    }

    static TypedSpec check(Typer t, AppliedFunction af, Env env) {
        if (af.parameters().size() == 2
                && af.parameters().stream().noneMatch(DeferredArgs::deferredArg)) {
            // each argument types exactly ONCE (a second synth would re-register typer state)
            TypedSpec rows = t.synth(af.parameters().get(0), env);
            TypedSpec destination = t.synth(af.parameters().get(1), env);
            Type.RelationType schema = Type.relationSchema(rows.info().type());
            if (destination instanceof TypedTableReference table && schema != null) {
                byName(schema, table);
                return new TypedWrite(rows, Optional.of(destination), ExprType.one(Type.Primitive.INTEGER));
            }
            Application a = t.checkGenericTyped(af, List.of(rows, destination));
            return new TypedWrite(a.args().get(0), Optional.of(a.args().get(1)), a.out());
        }
        Application a = t.checkGeneric(af, env);
        Optional<TypedSpec> destination = a.args().size() > 1
                ? Optional.of(a.args().get(1)) : Optional.empty();
        return new TypedWrite(a.args().get(0), destination, a.out());
    }

    /** Each of the rows' columns is a column of {@code table} by name, of its type or both numeric. */
    private static void byName(Type.RelationType rows, TypedTableReference table) {
        Type.RelationType columns = Type.schemaView(table.info().type());
        if (columns == null) {
            throw new TypeInferenceException("write: the destination " + table.store() + "." + table.table()
                    + " has no columns (" + table.info().type().typeName() + ")");
        }
        java.util.Map<String, Type.Column> byName = new java.util.LinkedHashMap<>();
        columns.columns().forEach(c -> byName.put(c.name(), c));
        for (Type.Column c : rows.columns()) {
            Type.Column into = byName.get(c.name());
            if (into == null) {
                throw new TypeInferenceException("write: the relation's column '" + c.name() + "' is not a column"
                        + " of " + table.store() + "." + table.table() + " " + byName.keySet()
                        + " (a write names the table's columns)");
            }
            if (!c.type().equals(into.type()) && !(numeric(c.type()) && numeric(into.type()))) {
                throw new TypeInferenceException("write: the relation's column '" + c.name() + "' "
                        + c.type().typeName() + " cannot be written into " + table.store() + "." + table.table()
                        + "'s column '" + into.name() + "' " + into.type().typeName());
            }
        }
    }

    private static boolean numeric(Type t) {
        return t instanceof Type.PrecisionDecimal || (t instanceof Type.Primitive p && p.isNumeric());
    }
}
