// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.protocol.sql;

import com.legend.protocol.spec.CBoolean;
import com.legend.protocol.spec.CDecimal;
import com.legend.protocol.spec.CString;
import com.legend.protocol.sql.SqlToPure.Kind;
import com.legend.protocol.sql.SqlToPure.Tx;

import java.util.ArrayList;
import java.util.List;

/**
 * Postgres as {@link SqlToPure} reads it: the {@code #SQL{...}#} island's dialect, Postgres 16 its spec
 * (docs/LEGEND_SQL_STUDY_2026_10_11.md). Its reader is {@link SqlParser}; its names, NULL order, output names,
 * constants' and columns' types and the wording of its refusals are here; its operators, casts, LIKE, CASE's one type
 * and its function catalog are the translator's own Postgres rules, which read the translator throughout (the
 * {@code postgres...} entries of {@link SqlToPure.At}, {@link SqlToPure#common}, {@link SqlToPure#postgresFunction}).
 */
public final class PostgresDialect implements InputDialect {

    /** The one Postgres dialect: it has no options. */
    public static final PostgresDialect INSTANCE = new PostgresDialect();

    private PostgresDialect() {
    }

    @Override
    public SqlAst.Statement parse(String sql) {
        return SqlParser.parse(sql);
    }

    // ------------------------------------------------------------------ names

    /** Postgres's folding of an unquoted identifier: ASCII letters to lower case, nothing else. */
    @Override
    public String fold(String text) {
        return SqlToPure.fold(text);
    }

    /** THE identifier rule (SqlToPure's class comment): the name of {@code names} that {@code id} means, or null for
     *  none. */
    @Override
    public @com.legend.base.Nullable String resolve(SqlAst.Ident id, Iterable<String> names, String what) {
        if (id.quoted()) {
            for (String n : names) {
                if (n.equals(id.text())) {
                    return n;
                }
            }
            return null;
        }
        String folded = fold(id.text());
        List<String> loose = new ArrayList<>();
        for (String n : names) {
            if (n.equals(folded)) {
                return n;
            }
            if (fold(n).equals(folded) && !loose.contains(n)) {
                loose.add(n);
            }
        }
        if (loose.size() > 1) {
            throw SqlTranslationException.invalid(what + " reference \"" + id.text() + "\" is ambiguous: it matches "
                    + String.join(", ", loose.stream().map(n -> "\"" + n + "\"").toList())
                    + "; quote the name to choose one", id.pos());
        }
        return loose.isEmpty() ? null : loose.get(0);
    }

    /** Postgres's hint for a quoted name that differs only in case from a real one. */
    @Override
    public String hint(SqlAst.Ident name, List<String> names) {
        if (name.quoted()) {
            for (String n : names) {
                if (fold(n).equals(fold(name.text()))) {
                    return "; perhaps you meant \"" + n + "\"";
                }
            }
        }
        return "";
    }

    // ------------------------------------------------------------------ order and output names

    /** Postgres sorts NULL after every value: last ascending, first descending. */
    @Override
    public boolean nullsFirst(boolean descending) {
        return descending;
    }

    @Override
    public String defaultName(SqlAst.SelectExpr item) {
        return defaultName(item.expr());
    }

    /** Postgres's name for an unnamed select item (its FigureColname): a column's name, a function's name,
     *  {@code case} for CASE, else {@code ?column?}. */
    private static String defaultName(SqlAst.Expr e) {
        return switch (e) {
            case SqlAst.ColumnRef ref -> ref.parts().get(ref.parts().size() - 1).text();
            case SqlAst.FunctionCall f -> SqlToPure.functionName(f.name().get(f.name().size() - 1));
            case SqlAst.Window w -> SqlToPure.functionName(w.call().name().get(w.call().name().size() - 1));
            case SqlAst.Case c -> "case";
            // a subscripted value keeps its name (Postgres's FigureColname reads through the indirection)
            case SqlAst.Subscript sub -> defaultName(sub.base());
            case SqlAst.ArrayConstructor a -> "array";
            // the operand's name once: twice a level was 2^n for n nested casts
            case SqlAst.Cast c -> castName(defaultName(c.operand()), c.type().name());
            default -> "?column?";
        };
    }

    /** A cast's default name: its operand's, or its type's when the operand has none. */
    private static String castName(String operand, String type) {
        return operand.equals("?column?") ? type : operand;
    }

    // ------------------------------------------------------------------ types

    /** A column's kind is its Pure type's: Postgres's translation reads no store type. */
    @Override
    public Kind kindOf(String pureType, @com.legend.base.Nullable String storeType) {
        int paren = pureType.indexOf('(');
        String base = paren < 0 ? pureType : pureType.substring(0, paren);
        return switch (base) {
            case "Integer" -> Kind.INTEGER;
            case "Float" -> Kind.FLOAT;
            case "Decimal" -> Kind.DECIMAL;
            case "Number" -> Kind.NUMBER;
            case "String" -> Kind.STRING;
            case "Boolean" -> Kind.BOOLEAN;
            case "StrictDate" -> Kind.DATE;
            case "DateTime" -> Kind.DATETIME;
            // a Variant is Postgres's jsonb (register S49)
            case "Variant", SqlToPure.VARIANT -> Kind.JSON;
            default -> Kind.OTHER;
        };
    }

    /** An integer constant is Postgres's integer (Pure's Integer, whatever its width), a numeric one its exact numeric,
     *  a string constant text, {@code DATE '...'} and the other typed constants their types. */
    @Override
    public Tx literal(SqlAst.Expr constant) {
        return switch (constant) {
            case SqlAst.IntegerLit n -> Tx.value(SqlToPure.integer(n.value()), Kind.INTEGER, false);
            case SqlAst.NumericLit n -> Tx.value(new CDecimal(n.value()), Kind.DECIMAL, false);
            case SqlAst.StringLit str -> Tx.value(new CString(str.value()), Kind.STRING, false);
            case SqlAst.BooleanLit b -> SqlToPure.booleanValue(new CBoolean(b.value()), false);
            case SqlAst.NullLit n -> Tx.value(SqlToPure.empty(), Kind.NULL, true);
            case SqlAst.TypedLit lit -> SqlToPure.typedLiteral(lit);
            default -> throw new IllegalArgumentException("not a constant: " + constant);
        };
    }

    // ------------------------------------------------------------------ meanings: the translator's Postgres rules

    @Override
    public Tx binary(SqlAst.Binary b, SqlToPure.At at) {
        return at.postgresBinary(b);
    }

    /** Postgres's resolution of a CASE's results ("UNION, CASE, and Related Constructs"). */
    @Override
    public SqlToPure.Common unify(String what, List<SqlAst.Expr> exprs, List<Tx> values, SqlAst.Pos pos) {
        return SqlToPure.common(what, exprs, values, pos);
    }

    @Override
    public @com.legend.base.Nullable FunctionRule function(String name) {
        return SqlToPure.postgresFunction(name);
    }

    @Override
    public Tx cast(SqlAst.Cast c, SqlToPure.At at) {
        return at.postgresCast(c);
    }

    @Override
    public Tx like(SqlAst.Like l, SqlToPure.At at) {
        return at.postgresLike(l);
    }

    // ------------------------------------------------------------------ Postgres's wording

    @Override
    public String message(Problem problem, Object... args) {
        return switch (problem) {
            case NO_SUCH_QUALIFIED_COLUMN -> "column " + args[0] + "." + args[1] + " does not exist" + args[2];
            case NO_SUCH_COLUMN -> "column \"" + args[0] + "\" does not exist" + args[1];
            case AMBIGUOUS_COLUMN -> "column reference \"" + args[0] + "\" is ambiguous";
            case AMBIGUOUS_TABLE -> "table reference \"" + args[0] + "\" is ambiguous";
            case NO_SUCH_TABLE -> "missing FROM-clause entry for table \"" + args[0] + "\"";
            case NOT_GROUPED -> "column \"" + args[0] + "\" must appear in the GROUP BY clause or be used in an"
                    + " aggregate function";
            case DUPLICATE_TABLE -> "table name \"" + args[0] + "\" specified more than once";
            case DUPLICATE_USING_COLUMN -> "common column name \"" + args[0] + "\" appears more than once in "
                    + args[1] + " table";
            case NO_SUCH_USING_COLUMN -> "column \"" + args[0] + "\" specified in USING clause does not exist in "
                    + args[1] + " table";
            case NO_SUCH_WINDOW -> "window \"" + args[0] + "\" does not exist";
        };
    }
}
