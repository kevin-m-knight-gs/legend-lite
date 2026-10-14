// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.protocol.sql;

import java.util.List;

/**
 * A SQL dialect as {@link SqlToPure} reads one: its parse, and the rules the dialects differ on -- which name an
 * identifier means, where NULLs sort when the query does not say, what an unnamed column is called, what a source
 * column's and a constant's types are, what an operator, a function, a cast and LIKE mean, how CASE's results take one
 * type, and the wording of the refusals that are the dialect's own. The rest of the translation is every dialect's:
 * the scopes, the query's shape (FROM, WHERE, grouping, windows, set operations, WITH), three-valued logic,
 * subqueries.
 *
 * <p>{@link PostgresDialect} is the {@code #SQL{...}#} island's, and {@link SqlToPure#translate(String,
 * SqlToPure.Sources)}'s: Postgres is that island's spec. A dialect read under options (an engine's profile) is one
 * instance per profile, so no method here takes one.
 */
public interface InputDialect {

    /** The query {@code sql} is, as the dialect's reader parses it: {@link SqlSyntaxException} when it is no query
     *  the reader accepts (its {@code unsupported()} when the syntax may be fine but is not read yet). */
    SqlAst.Statement parse(String sql);

    // ------------------------------------------------------------------ names

    /** The name an unquoted identifier declares ({@code AS x}, a WITH query's name): Postgres folds it to lower
     *  case. */
    String fold(String text);

    /** THE identifier rule: the name of {@code names} that {@code id} means, or null for none; a reference that means
     *  more than one is the dialect's error. {@code what} says what is named: {@code column}, {@code table} or
     *  {@code window}. */
    @com.legend.base.Nullable String resolve(SqlAst.Ident id, Iterable<String> names, String what);

    /** What to add to "does not exist" for {@code name} among {@code names}: a hint at the name meant, or nothing. */
    String hint(SqlAst.Ident name, List<String> names);

    // ------------------------------------------------------------------ order and output names

    /** Whether NULLs sort first where the query does not say ({@code NULLS FIRST} or {@code NULLS LAST} unwritten). */
    boolean nullsFirst(boolean descending);

    /** The output name of a select item written without an alias. */
    String defaultName(SqlAst.SelectExpr item);

    // ------------------------------------------------------------------ types

    /** What the translator knows of a FROM source column's type, from its Pure type's name ({@code Integer},
     *  {@code Decimal(10,2)}, an enumeration's path, ...). */
    SqlToPure.Kind kindOf(String pureType);

    /** A constant's translation: an {@link SqlAst.IntegerLit}, {@link SqlAst.NumericLit}, {@link SqlAst.StringLit},
     *  {@link SqlAst.BooleanLit}, {@link SqlAst.NullLit} or {@link SqlAst.TypedLit}. */
    SqlToPure.Tx literal(SqlAst.Expr constant);

    // ------------------------------------------------------------------ meanings

    /** {@code b}'s translation: its operands are translated {@code at} its place. */
    SqlToPure.Tx binary(SqlAst.Binary b, SqlToPure.At at);

    /** Values that are one value's alternatives, as one type -- a CASE's results ({@code what} names the construct,
     *  for the dialect's errors): each value, read as that type where it is read otherwise ({@code exprs[i]} is
     *  {@code values[i]}'s expression), and the type's kind. */
    SqlToPure.Common unify(String what, List<SqlAst.Expr> exprs, List<SqlToPure.Tx> values, SqlAst.Pos pos);

    /** The catalog: the scalar function named {@code name} (as {@code SqlToPure} reads a function's name: folded when
     *  unquoted), or null when the dialect has none (refused as not supported). */
    @com.legend.base.Nullable FunctionRule function(String name);

    /** {@code CAST(x AS type)}'s translation (and the dialect's other spellings of one). */
    SqlToPure.Tx cast(SqlAst.Cast c, SqlToPure.At at);

    /** {@code x [NOT] LIKE pattern}'s translation. */
    SqlToPure.Tx like(SqlAst.Like l, SqlToPure.At at);

    // ------------------------------------------------------------------ the dialect's own refusals

    /** A refusal's message, in the dialect's wording: {@link Problem} says what {@code args} are. */
    String message(Problem problem, Object... args);

    /** A function of a dialect's catalog: the call, its arguments' translations, and the place it is translated. */
    @FunctionalInterface
    interface FunctionRule {
        SqlToPure.Tx apply(SqlAst.FunctionCall f, List<SqlToPure.Tx> args, SqlToPure.At at);
    }

    /** What a dialect knows of a value's type beyond its {@link SqlToPure.Kind} (a width, a scale, a length): opaque
     *  to the translator, which carries it in {@link SqlToPure.Tx#detail()}. Postgres's translation has none. */
    interface TypeDetail {
    }

    /** The refusals whose wording is the dialect's own, each with the arguments its message reads, in order. */
    enum Problem {
        /** A qualified column the qualifier's FROM item does not have: the qualifier and the column as written, and
         *  the {@link InputDialect#hint}. */
        NO_SUCH_QUALIFIED_COLUMN,
        /** A column no FROM item has: the column as written, and the {@link InputDialect#hint} (or nothing). */
        NO_SUCH_COLUMN,
        /** A column more than one FROM item has: the column as written. */
        AMBIGUOUS_COLUMN,
        /** A qualifier more than one FROM item has: the qualifier as written. */
        AMBIGUOUS_TABLE,
        /** A qualifier no FROM item has: the qualifier as written. */
        NO_SUCH_TABLE,
        /** A column of a grouped query that is no group key: the reference as written. */
        NOT_GROUPED,
        /** Two FROM items of one name: the name as written. */
        DUPLICATE_TABLE,
        /** A USING column one side has twice: the column as written, and the side ({@code left} or {@code right}). */
        DUPLICATE_USING_COLUMN,
        /** A USING column one side does not have: the column as written, and the side. */
        NO_SUCH_USING_COLUMN,
        /** A window reference to no named window: the name as written. */
        NO_SUCH_WINDOW
    }
}
