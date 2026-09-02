// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.compiler.element;

import com.legend.model.DatabaseDefinition;
import com.legend.model.RelationalDataType;
import com.legend.model.RelationalOperation;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Relational-operation TREES as metamodel-store rows (group F burn,
 * 2026-09-02): one {@code relational_ops} row per node (parent + ordinal,
 * the engine's node kinds), each stamped with the compiler's inferred SQL
 * type as a {@code data_types} row ({@link RelationalTypeInference}). ONE
 * builder serves both row sources: the model's mapping / view expressions
 * (the metamodel seeds) and a query's CONSTRUCTED instances
 * ({@code ^DynaFunction(...)}, the resolver's side-output rows) — the same
 * fact shape whoever authored the tree.
 */
public final class RelationalOpRows {

    public final List<List<String>> ops = new ArrayList<>();
    public final List<List<String>> dataTypes = new ArrayList<>();
    private final ModelContext ctx;
    private final Set<String> typeIds = new LinkedHashSet<>();

    public RelationalOpRows(ModelContext ctx) {
        this.ctx = ctx;
    }

    /** One data-type row per id (a column's declared type, a node's
     * inferred type): the m3 subclass simple name + its size/precision/
     * scale. */
    public void dataType(String id, RelationalDataType t) {
        if (!typeIds.add(id)) {
            return;
        }
        String size = null;
        String precision = null;
        String scale = null;
        switch (t) {
            case RelationalDataType.Varchar v -> size = Integer.toString(v.size());
            case RelationalDataType.Char_ c -> size = Integer.toString(c.size());
            case RelationalDataType.Binary b -> size = Integer.toString(b.size());
            case RelationalDataType.Varbinary v -> size = Integer.toString(v.size());
            case RelationalDataType.Decimal d -> {
                precision = Integer.toString(d.precision());
                scale = Integer.toString(d.scale());
            }
            case RelationalDataType.Numeric n -> {
                precision = Integer.toString(n.precision());
                scale = Integer.toString(n.scale());
            }
            default -> {
            }
        }
        dataTypes.add(java.util.Arrays.asList(id, kindOf(t), size, precision, scale));
    }

    /** The m3 datatype class simple name of a store type (the store
     * mapping's per-kind filter reads it). */
    public static String kindOf(RelationalDataType t) {
        return switch (t) {
            case RelationalDataType.BigInt ignored -> "BigInt";
            case RelationalDataType.SmallInt ignored -> "SmallInt";
            case RelationalDataType.TinyInt ignored -> "TinyInt";
            case RelationalDataType.Integer_ ignored -> "Integer";
            case RelationalDataType.Float_ ignored -> "Float";
            case RelationalDataType.Double_ ignored -> "Double";
            case RelationalDataType.Real ignored -> "Real";
            case RelationalDataType.Bit ignored -> "Bit";
            case RelationalDataType.Timestamp ignored -> "Timestamp";
            case RelationalDataType.Date_ ignored -> "Date";
            case RelationalDataType.Distinct ignored -> "Distinct";
            case RelationalDataType.Other ignored -> "Other";
            case RelationalDataType.SemiStructured ignored -> "SemiStructured";
            case RelationalDataType.Varchar ignored -> "Varchar";
            case RelationalDataType.Char_ ignored -> "Char";
            case RelationalDataType.Binary ignored -> "Binary";
            case RelationalDataType.Varbinary ignored -> "Varbinary";
            case RelationalDataType.Decimal ignored -> "Decimal";
            case RelationalDataType.Numeric ignored -> "Numeric";
            case RelationalDataType.Array ignored -> "Array";
            case RelationalDataType.Object_ ignored -> "Object";
        };
    }

    /** One node row (+ its inferred-type row) and, recursively, its
     * children. {@code scopeDb}/{@code scopeTable} resolve bare column
     * references (a view reads its own store; a set's expressions its
     * main table). A parenthesized group is transparent (no engine node). */
    public void node(RelationalOperation op, String id, @com.legend.Nullable String parent,
            @com.legend.Nullable Integer ordinal, @com.legend.Nullable String scopeDbFqn,
            @com.legend.Nullable DatabaseDefinition scopeDb, @com.legend.Nullable String scopeTable) {
        node(op, id, parent, ordinal, scopeDbFqn, scopeDb, scopeTable, null, null);
    }

    public void node(RelationalOperation op, String id, @com.legend.Nullable String parent,
            @com.legend.Nullable Integer ordinal, @com.legend.Nullable String scopeDbFqn,
            @com.legend.Nullable DatabaseDefinition scopeDb, @com.legend.Nullable String scopeTable,
            @com.legend.Nullable String pkMapping, @com.legend.Nullable String pkSet) {
        if (op instanceof RelationalOperation.Group g) {
            node(g.inner(), id, parent, ordinal, scopeDbFqn, scopeDb, scopeTable, pkMapping, pkSet);
            return;
        }
        String kind;
        String dynaName = null;
        String literal = null;
        String colDb = null;
        String colSchema = null;
        String colTable = null;
        String colName = null;
        List<RelationalOperation> children = List.of();
        String childDbFqn = scopeDbFqn;
        DatabaseDefinition childDb = scopeDb;
        switch (op) {
            case RelationalOperation.ColumnRef c -> {
                kind = "TableAliasColumn";
                colDb = c.databaseName() != null ? c.databaseName() : scopeDbFqn;
                DatabaseDefinition db = colDb == null ? null
                        : ctx.findDatabase(colDb).orElse(scopeDb);
                String[] st = splitTable(db, c.table());
                colSchema = st[0];
                colTable = st[1];
                colName = c.column();
            }
            case RelationalOperation.TargetColumnRef t -> {
                kind = "TableAliasColumn";
                colDb = scopeDbFqn;
                if (scopeTable != null) {
                    String[] st = splitTable(scopeDb, scopeTable);
                    colSchema = st[0];
                    colTable = st[1];
                }
                colName = t.column();
            }
            case RelationalOperation.Literal l -> {
                kind = "Literal";
                literal = String.valueOf(l.value());
            }
            case RelationalOperation.FunctionCall f -> {
                kind = "DynaFunction";
                dynaName = f.name();
                children = f.args();
            }
            case RelationalOperation.Comparison c -> {
                kind = "DynaFunction";
                dynaName = switch (c.op()) {
                    case EQ -> "equal";
                    case NEQ -> "notEqual";
                    case LT -> "lessThan";
                    case LTE -> "lessThanEqual";
                    case GT -> "greaterThan";
                    case GTE -> "greaterThanEqual";
                };
                children = List.of(c.left(), c.right());
            }
            case RelationalOperation.BooleanOp b -> {
                kind = "DynaFunction";
                dynaName = b.op().keyword();
                children = List.of(b.left(), b.right());
            }
            case RelationalOperation.IsNull n -> {
                kind = "DynaFunction";
                dynaName = "isNull";
                children = List.of(n.operand());
            }
            case RelationalOperation.IsNotNull n -> {
                kind = "DynaFunction";
                dynaName = "isNotNull";
                children = List.of(n.operand());
            }
            case RelationalOperation.ArrayLiteral a -> {
                kind = "LiteralList";
                children = a.elements();
            }
            case RelationalOperation.JoinNavigation j -> {
                kind = "RelationalOperationElementWithJoin";
                if (j.databaseName() != null) {
                    childDbFqn = j.databaseName();
                    childDb = ctx.findDatabase(j.databaseName()).orElse(scopeDb);
                }
                children = j.terminal() == null ? List.of() : List.of(j.terminal());
            }
            case RelationalOperation.Group ignored -> throw new IllegalStateException("unreachable");
        }
        RelationalDataType t = RelationalTypeInference.infer(op, scopeDb, ctx);
        String typeId = null;
        if (t != null) {
            typeId = "op:" + id;
            dataType(typeId, t);
        }
        ops.add(java.util.Arrays.asList(id, kind, parent,
                ordinal == null ? null : Integer.toString(ordinal), dynaName, literal,
                colDb, colSchema, colTable, colName, typeId, pkMapping, pkSet));
        for (int i = 0; i < children.size(); i++) {
            node(children.get(i), id + "/" + i, id, i, childDbFqn, childDb, scopeTable);
        }
    }

    /** {schema, table} of a table spelling: {@code schema.table} as
     * written; a bare name is the top-level ({@code default}) table or
     * view when one exists, else the declared schema holding it. */
    private static String[] splitTable(@com.legend.Nullable DatabaseDefinition db, String spelling) {
        int dot = spelling.indexOf('.');
        if (dot >= 0) {
            return new String[] {spelling.substring(0, dot), spelling.substring(dot + 1)};
        }
        // the parser lists a schema's tables in the flat list too, so a
        // declared schema holding the name wins over the flat entry
        if (db != null) {
            for (DatabaseDefinition.SchemaDefinition s : db.schemas()) {
                if (s.tables().stream().anyMatch(t -> t.name().equals(spelling))
                        || s.views().stream().anyMatch(v -> v.name().equals(spelling))) {
                    return new String[] {s.name(), spelling};
                }
            }
        }
        return new String[] {"default", spelling};
    }

}
