// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.protocol.sql.iq;

import com.legend.protocol.sql.SqlAst;
import com.legend.protocol.sql.SqlSyntaxException;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * IQ's SELECT ({@link IqLexer}'s tokens) to the shared syntax tree, {@link SqlAst}, which {@code SqlToPure} reads
 * through {@link IqDialect} (docs/IQ_SQL_STUDY_2026_10_13.md §5.2). The productions read so far are the research
 * GRAMMAR's for M0:
 * <pre>
 *   SELECT [ALL | DISTINCT] [row limit] item, ... [FROM item] [WHERE condition] [ORDER BY expression [ASC | DESC], ...]
 *   row limit:  FIRST | TOP (ALL | count) [START AT count]
 *   count:      an unsigned integer up to 2147483647, or ( + - * over such integers and parentheses )
 *   item:       * | name.* | expression [[AS] alias] | alias = expression     (T-SQL's, never a comparison)
 *   alias:      a name ([bracketed], `back-quoted`, "double-quoted") or a 'string'
 *   FROM item:  name | name(arguments)  [[AS] alias]
 *   condition:  NOT, AND, OR over predicates; a comparison (= &lt;&gt; != &lt; &gt; &lt;= &gt;= !&lt; !&gt;),
 *               IS [NOT] NULL, or a parenthesized condition
 *   expression: a column (plain, [bracketed], `back-quoted` or "double-quoted" parts), a number, a string, NULL, a
 *               parenthesized expression, a function call name(value, ...), CASE [value] WHEN ... THEN ... [ELSE ...]
 *               END [CASE], IF condition THEN value [ELSE value] (ENDIF | END IF), a sign (- or +) before a value;
 *               || + - * / % parse at IQ's precedence and are the dialect's to read
 * </pre>
 * The row limit becomes the query's limit and offset: {@code FIRST} is {@code TOP 1}, {@code START AT m} skips m - 1
 * rows (so the offset is the expression {@code m - 1}, which the dialect checks and the planner folds). {@code IF c
 * THEN a ELSE b ENDIF} becomes {@code CASE WHEN c THEN a WHEN NOT c THEN b END}, so an UNKNOWN condition gives NULL
 * even with an ELSE (the research's SEMANTICS §3.2); a simple CASE ({@code CASE x WHEN v ...}) becomes the searched
 * one ({@code CASE WHEN x = v ...}), so its comparisons are the dialect's.
 *
 * <p>IQ keeps values and conditions apart (it has no boolean values): a condition where a value is expected, or a
 * value where a condition is, is a syntax error, and so are {@code NOT NOT} and a chained comparison, as in IQ
 * (measured on SQL Anywhere 17, the research's GRAMMAR §2). Everything else IQ reads -- GROUP BY, joins, subqueries,
 * set operations, aggregates, the bitwise operators, CAST and the other special forms, parameters and variables -- is
 * refused here by name, as not supported yet: never read as something else.
 */
public final class IqParser {

    /** The comparison operators, with IQ's spellings of the negated forms. */
    private static final List<String> COMPARISONS = List.of("=", "<>", "!=", "<", ">", "<=", ">=", "!<", "!>");

    /** IQ's aggregate and OLAP functions (and the translator's own aggregate names, which it would read as one): each
     *  refused by name until its slice (M1c, M3). */
    private static final Set<String> AGGREGATES = Set.of("count", "sum", "avg", "min", "max", "list", "median",
            "stddev", "stddev_pop", "stddev_samp", "variance", "var_pop", "var_samp", "first_value", "last_value",
            "nth_value", "row_number", "rank", "dense_rank", "percent_rank", "cume_dist", "ntile", "lag", "lead",
            "grouping", "corr", "covar_pop", "covar_samp", "regr_avgx", "regr_avgy", "regr_count", "regr_intercept",
            "regr_r2", "regr_slope", "regr_sxx", "regr_sxy", "regr_syy", "percentile_cont", "percentile_disc",
            "xmlagg", "string_agg", "bool_and", "bool_or", "every", "bit_and", "bit_or", "bit_xor", "set_bits",
            "count_big", "count_distinct", "approx_count_distinct");

    /** The largest count of a row limit (TOP's, START AT's): an INT's. */
    private static final BigInteger MAX_COUNT = BigInteger.valueOf(Integer.MAX_VALUE);

    /** Keywords that begin a join (M1b). */
    private static final Set<String> JOIN_WORDS = Set.of("join", "inner", "left", "right", "full", "cross",
            "natural", "key", "outer");

    private final String text;
    private final List<IqLexer.Token> tokens;
    private int at;

    private IqParser(String text, List<IqLexer.Token> tokens) {
        this.text = text;
        this.tokens = tokens;
    }

    /** The query {@code sql} is, read under {@code profile}'s options. */
    public static SqlAst.Statement parse(String sql, IqProfile profile) {
        IqParser p = new IqParser(sql, IqLexer.tokenize(sql, profile));
        if (p.peek().kind() == IqLexer.Kind.EOF) {
            throw new SqlSyntaxException("no statement" + IqDialect.syntaxCode(), p.peek().pos());
        }
        SqlAst.Statement statement = p.statement();
        IqLexer.Token end = p.peek();
        if (end.kind() != IqLexer.Kind.EOF) {
            throw p.unexpected(end);
        }
        return statement;
    }

    private SqlAst.Statement statement() {
        IqLexer.Token first = peek();
        if (first.isOp("(")) {
            throw SqlSyntaxException.unsupported("a parenthesized query is not supported yet", first.pos());
        }
        if (first.is("with")) {
            throw SqlSyntaxException.unsupported("WITH (common table expressions) is not supported yet", first.pos());
        }
        if (!first.is("select")) {
            throw SqlSyntaxException.unsupported("only a SELECT is read in an #IQ{...}# island, not '" + first.text()
                    + "'", first.pos());
        }
        return select();
    }

    private SqlAst.Select select() {
        SqlAst.Pos pos = take().pos();
        boolean distinct = false;
        if (peek().is("distinct")) {
            take();
            distinct = true;
        } else if (peek().is("all")) {
            take();
        }
        RowLimit limit = rowLimit();
        if (limit != null && (peek().is("distinct") || peek().is("all") || peek().is("top") || peek().is("first")
                || peek().is("start"))) {
            throw new SqlSyntaxException("'" + peek().text() + "' cannot follow the row limit (TOP, FIRST)"
                    + IqDialect.syntaxCode(), peek().pos());
        }
        List<SqlAst.SelectItem> items = new ArrayList<>();
        do {
            items.add(selectItem());
        } while (takeOp(","));
        if (peek().is("into")) {
            throw SqlSyntaxException.unsupported("INTO (a query that stores its rows) is not supported in an #IQ{...}#"
                    + " island: it is a value, not a statement", peek().pos());
        }
        List<SqlAst.FromItem> from = new ArrayList<>();
        if (peek().is("from")) {
            take();
            from.add(fromItem());
            if (peek().isOp(",")) {
                throw SqlSyntaxException.unsupported("a FROM list of several items (a comma join) is not supported yet",
                        peek().pos());
            }
            if (JOIN_WORDS.contains(IqLexer.lower(peek().text())) && peek().kind() == IqLexer.Kind.KEYWORD) {
                throw SqlSyntaxException.unsupported("joins (" + peek().text() + ") are not supported yet",
                        peek().pos());
            }
        }
        SqlAst.Expr where = null;
        if (peek().is("where")) {
            take();
            where = condition("WHERE");
        }
        refuseClause("group", "GROUP BY is not supported yet");
        refuseClause("having", "HAVING is not supported yet");
        refuseClause("window", "the WINDOW clause is not supported yet");
        for (String op : List.of("union", "except", "intersect")) {
            refuseClause(op, op.toUpperCase(java.util.Locale.ROOT) + " is not supported yet");
        }
        List<SqlAst.SortItem> orderBy = new ArrayList<>();
        if (peek().is("order")) {
            take();
            expectKeyword("by");
            do {
                SqlAst.SortItem item = sortItem();
                if (item != null) {
                    orderBy.add(item);
                }
            } while (takeOp(","));
        }
        refuseClause("for", "FOR READ ONLY, FOR UPDATE and FOR XML are not supported yet");
        refuseClause("option", "the OPTION clause is not supported yet");
        if (peek().isWord("limit") || peek().isWord("offset")) {
            throw SqlSyntaxException.unsupported(peek().text() + " is not supported yet", peek().pos());
        }
        if (peek().isOp(";")) {
            throw SqlSyntaxException.unsupported("a statement separator (;) is not read in an #IQ{...}# island",
                    peek().pos());
        }
        return new SqlAst.Select(distinct, items, from, where, List.of(), null, List.of(), orderBy,
                limit == null ? null : limit.count(), limit == null ? null : limit.offset(), pos);
    }

    // ------------------------------------------------------------------ row limits

    /** A row limit: the count of rows (null for all), and the rows skipped first (null for none). */
    private record RowLimit(SqlAst.@com.legend.base.Nullable Expr count, SqlAst.@com.legend.base.Nullable Expr offset) {
    }

    /** {@code FIRST} or {@code TOP (ALL | count) [START AT count]} (the research's GRAMMAR S2-S5), or null when the
     *  select list follows. {@code START AT m} is the offset {@code m - 1}: the dialect checks it, the planner folds
     *  it. */
    private @com.legend.base.Nullable RowLimit rowLimit() {
        IqLexer.Token t = peek();
        if (t.is("first")) {
            take();
            return new RowLimit(new SqlAst.IntegerLit(BigInteger.ONE, t.pos()), null);
        }
        if (!t.is("top")) {
            if (t.is("start") && peek(1).isWord("at")) {
                throw new SqlSyntaxException("START AT needs a TOP before it" + IqDialect.syntaxCode(), t.pos());
            }
            return null;
        }
        take();
        SqlAst.Expr count = null;
        if (peek().is("all")) {
            take();
        } else {
            count = limitValue("TOP");
        }
        if (peek().isWord("percent")) {
            // IQ has no PERCENT: it reads TOP n, then a column named PERCENT (the research's GRAMMAR S2, a parse trap)
            throw SqlSyntaxException.unsupported("TOP n PERCENT is not read: IQ has no PERCENT keyword, it reads TOP n"
                    + " and then a column named PERCENT", peek().pos());
        }
        SqlAst.Expr offset = null;
        if (peek().is("start")) {
            IqLexer.Token start = take();
            if (!peek().isWord("at")) {
                throw new SqlSyntaxException("START needs AT" + IqDialect.syntaxCode(), peek().pos());
            }
            take();
            SqlAst.Expr m = limitValue("START AT");
            offset = new SqlAst.Binary(SqlAst.BinaryOp.MINUS, m, new SqlAst.IntegerLit(BigInteger.ONE, start.pos()),
                    start.pos());
        }
        if (peek().is("with")) {
            throw new SqlSyntaxException("IQ's TOP has no WITH TIES" + IqDialect.syntaxCode(), peek().pos());
        }
        return new RowLimit(count, offset);
    }

    /** A row limit's count: an unsigned integer of an INT's range, or a parenthesized + - * of such integers. A
     *  variable or a parameter (which IQ reads too) is refused by name; anything else is IQ's syntax error. */
    private SqlAst.Expr limitValue(String clause) {
        IqLexer.Token t = peek();
        if (t.kind() == IqLexer.Kind.NUMBER) {
            take();
            return limitInteger(t, clause);
        }
        if (t.isOp("(")) {
            take();
            SqlAst.Expr sum = limitSum(clause);
            if (!peek().isOp(")")) {
                throw new SqlSyntaxException(clause + "'s count is an integer or + - * of integers"
                        + IqDialect.syntaxCode(), peek().pos());
            }
            take();
            return sum;
        }
        if (t.isOp("?") || t.isOp(":") || t.kind() == IqLexer.Kind.WORD && t.text().startsWith("@")) {
            throw SqlSyntaxException.unsupported("a variable or a parameter as " + clause + "'s count is not supported"
                    + " yet", t.pos());
        }
        throw new SqlSyntaxException(clause + " needs a count: an unsigned integer or a parenthesized + - * of"
                + " integers" + IqDialect.syntaxCode(), t.pos());
    }

    private SqlAst.Expr limitSum(String clause) {
        SqlAst.Expr left = limitProduct(clause);
        while (peek().isOp("+") || peek().isOp("-")) {
            IqLexer.Token op = take();
            left = new SqlAst.Binary(op.isOp("+") ? SqlAst.BinaryOp.PLUS : SqlAst.BinaryOp.MINUS, left,
                    limitProduct(clause), op.pos());
        }
        return left;
    }

    private SqlAst.Expr limitProduct(String clause) {
        SqlAst.Expr left = limitValue(clause);
        while (peek().isOp("*")) {
            IqLexer.Token op = take();
            left = new SqlAst.Binary(SqlAst.BinaryOp.TIMES, left, limitValue(clause), op.pos());
        }
        return left;
    }

    /** An unsigned integer of a row limit: digits only, at most an INT's largest (IQ's syntax error otherwise). */
    private static SqlAst.IntegerLit limitInteger(IqLexer.Token t, String clause) {
        String s = t.text();
        boolean digits = true;
        for (int i = 0; i < s.length(); i++) {
            digits &= s.charAt(i) >= '0' && s.charAt(i) <= '9';
        }
        if (!digits || new BigInteger(s).compareTo(MAX_COUNT) > 0) {
            throw new SqlSyntaxException(clause + "'s count is an integer from 0 to 2147483647, not " + s
                    + IqDialect.syntaxCode(), t.pos());
        }
        return new SqlAst.IntegerLit(new BigInteger(s), t.pos());
    }

    private void refuseClause(String keyword, String message) {
        if (peek().is(keyword)) {
            throw SqlSyntaxException.unsupported(message, peek().pos());
        }
    }

    // ------------------------------------------------------------------ select items

    private SqlAst.SelectItem selectItem() {
        IqLexer.Token first = peek();
        if (first.isOp("*")) {
            take();
            return new SqlAst.AllColumns(null, first.pos());
        }
        if (first.isName() && peek(1).isOp(".") && peek(2).isOp("*")) {
            SqlAst.Ident qualifier = name(take());
            take();
            take();
            return new SqlAst.AllColumns(qualifier, first.pos());
        }
        if ((first.isName() || first.kind() == IqLexer.Kind.STRING) && peek(1).isOp("=")) {
            return namedItem();
        }
        int start = first.start();
        SqlAst.Expr e = expression();
        value(e, "the select list");
        String written = text.substring(start, tokens.get(at - 1).end());
        SqlAst.Ident alias = alias(false);
        return new SqlAst.SelectExpr(e, alias, first.pos(), written);
    }

    /** T-SQL's {@code alias = expression} (the research's GRAMMAR S10): the item named {@code alias}, never a
     *  comparison. A condition after the {@code =} ({@code x = a = 1}) and an alias after the expression
     *  ({@code x = a AS y}) are IQ's syntax errors; a variable on the left is an assignment, a statement's, refused. */
    private SqlAst.SelectItem namedItem() {
        IqLexer.Token first = take();
        take();
        if (first.kind() == IqLexer.Kind.WORD && first.text().startsWith("@")) {
            throw SqlSyntaxException.unsupported("a T-SQL assignment (" + first.text() + " = expression) is not"
                    + " supported in an #IQ{...}# island: it is a statement, not a value", first.pos());
        }
        SqlAst.Ident alias = first.kind() == IqLexer.Kind.STRING ? stringAlias(first) : name(first);
        int start = peek().start();
        SqlAst.Expr e = expression();
        if (isCondition(e)) {
            throw new SqlSyntaxException("T-SQL's alias = expression names a value, not a condition"
                    + IqDialect.syntaxCode(), e.pos());
        }
        String written = text.substring(start, tokens.get(at - 1).end());
        IqLexer.Token after = peek();
        if (after.is("as") || after.isName() || after.kind() == IqLexer.Kind.STRING) {
            throw new SqlSyntaxException("an item named by alias = expression takes no second alias"
                    + IqDialect.syntaxCode(), after.pos());
        }
        return new SqlAst.SelectExpr(e, alias, first.pos(), written);
    }

    /** A string as an alias ({@code expr 'alias'}, {@code 'alias' = expr}): its value is the name. One holding a
     *  quote, a backslash, a bracket, a back quote or a control character is IQ's syntax error; an empty one is
     *  refused (no Pure column has an empty name). */
    private static SqlAst.Ident stringAlias(IqLexer.Token t) {
        String v = t.text();
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c == '"' || c == '\\' || c == '[' || c == ']' || c == '`' || c < 0x20) {
                throw new SqlSyntaxException("a string alias cannot hold the character U+" + hex4(c)
                        + IqDialect.syntaxCode(), t.pos());
            }
        }
        if (v.isEmpty()) {
            throw SqlSyntaxException.unsupported("an empty string as an alias is not supported: a Pure column needs a"
                    + " name", t.pos());
        }
        return new SqlAst.Ident(v, true, t.pos());
    }

    private static String hex4(char c) {
        String h = Integer.toHexString(c).toUpperCase(java.util.Locale.ROOT);
        return "0000".substring(h.length()) + h;
    }

    /** {@code [AS] alias}: a plain or delimited name, or (in the select list) a string; a reserved word after AS is
     *  IQ's syntax error. {@code fromItem}: the alias of a FROM item, where a string is refused (not read yet). */
    private SqlAst.@com.legend.base.Nullable Ident alias(boolean fromItem) {
        IqLexer.Token t = peek();
        boolean as = t.is("as");
        if (as) {
            take();
            t = peek();
        }
        if (t.kind() == IqLexer.Kind.STRING) {
            if (fromItem) {
                throw SqlSyntaxException.unsupported("a string as a FROM item's alias is not supported yet", t.pos());
            }
            take();
            return stringAlias(t);
        }
        if (t.isName()) {
            take();
            if (fromItem && peek().isOp("(")) {
                throw SqlSyntaxException.unsupported("a FROM item's column alias list is not supported yet",
                        peek().pos());
            }
            return name(t);
        }
        if (as) {
            throw new SqlSyntaxException("AS needs a name, not '" + t.text() + "'" + IqDialect.syntaxCode(), t.pos());
        }
        return null;
    }

    // ------------------------------------------------------------------ FROM

    private SqlAst.FromItem fromItem() {
        IqLexer.Token first = peek();
        if (first.isOp("(")) {
            throw SqlSyntaxException.unsupported("a derived table or a parenthesized FROM item is not supported yet",
                    first.pos());
        }
        if (first.is("lateral") || first.isWord("apply") || first.is("openstring") || first.is("openxml")) {
            throw SqlSyntaxException.unsupported(first.text() + " in FROM is not supported yet", first.pos());
        }
        if (!first.isName()) {
            throw unexpected(first);
        }
        List<SqlAst.Ident> name = new ArrayList<>(List.of(name(take())));
        while (peek().isOp(".")) {
            take();
            name.add(namePart());
        }
        if (name.size() > 4) {
            // server.database.owner.table at most (measured on SQL Anywhere 17)
            throw new SqlSyntaxException("a table name has at most four parts" + IqDialect.syntaxCode(),
                    first.pos());
        }
        if (peek().isOp("(")) {
            take();
            List<SqlAst.Arg> args = new ArrayList<>();
            if (!peek().isOp(")")) {
                do {
                    noNamedArgument();
                    SqlAst.Expr a = expression();
                    value(a, "a FROM item's arguments");
                    args.add(new SqlAst.Arg(null, a));
                } while (takeOp(","));
            }
            expectOp(")");
            SqlAst.Ident alias = alias(true);
            noTableHints();
            return new SqlAst.TableFunction(name, args, alias, List.of(), first.pos());
        }
        SqlAst.Ident alias = alias(true);
        noTableHints();
        return new SqlAst.TableName(name, alias, List.of(), first.pos());
    }

    /** A named argument ({@code name = value}, which IQ reads in a call): refused by name, never a comparison. */
    private void noNamedArgument() {
        if (peek().isName() && peek(1).isOp("=")) {
            throw SqlSyntaxException.unsupported("named arguments (" + peek().text() + " = ...) are not supported yet",
                    peek().pos());
        }
    }

    private void noTableHints() {
        if (peek().is("with") && peek(1).isOp("(")) {
            throw SqlSyntaxException.unsupported("table hints (WITH (...)) are not supported yet", peek().pos());
        }
        if (peek().is("force") || peek().isWord("noholdlock") || peek().is("holdlock")) {
            throw SqlSyntaxException.unsupported("table hints are not supported yet", peek().pos());
        }
    }

    // ------------------------------------------------------------------ ORDER BY

    /** {@code expression [ASC | DESC]}: an unsigned or {@code +}-signed integer is a select-list position, a
     *  parenthesized one ({@code (1)}) a constant, which orders nothing and is dropped (measured on SQL Anywhere 17: the
     *  research's GRAMMAR S13); null for a dropped key. */
    private SqlAst.@com.legend.base.Nullable SortItem sortItem() {
        IqLexer.Token first = peek();
        SqlAst.Expr e = expression();
        value(e, "ORDER BY");
        boolean constant = false;
        if (e instanceof SqlAst.Unary u && u.op() == SqlAst.UnaryOp.PLUS && u.operand() instanceof SqlAst.IntegerLit n) {
            if (peek(-1).kind() != IqLexer.Kind.NUMBER || tokens.get(at - 2) != first) {
                throw SqlSyntaxException.unsupported("a signed parenthesized position in ORDER BY is not supported",
                        first.pos());
            }
            e = n;
        } else if (e instanceof SqlAst.IntegerLit && first.isOp("(")) {
            constant = true;
        }
        boolean descending = false;
        if (peek().is("desc")) {
            take();
            descending = true;
        } else if (peek().is("asc")) {
            take();
        }
        if (peek().isWord("nulls")) {
            // IQ 16.2 has no NULLS FIRST / LAST (the research's D-W1; SQL Anywhere 17 rejects it too)
            throw new SqlSyntaxException("IQ has no NULLS FIRST or NULLS LAST" + IqDialect.syntaxCode(),
                    peek().pos());
        }
        return constant ? null : new SqlAst.SortItem(e, descending, null, first.pos());
    }

    // ------------------------------------------------------------------ conditions

    /** A search condition (WHERE's): an expression that is one. */
    private SqlAst.Expr condition(String clause) {
        SqlAst.Expr e = expression();
        if (!isCondition(e)) {
            throw new SqlSyntaxException(clause + " needs a condition, and IQ has no boolean values"
                    + IqDialect.syntaxCode(), e.pos());
        }
        return e;
    }

    /** An expression of IQ's whole precedence ladder (the research's GRAMMAR §2): OR, AND, NOT, the predicates, then
     *  the value operators. Whether it is a condition or a value is checked where it stands. */
    private SqlAst.Expr expression() {
        SqlAst.Expr left = conjunction();
        while (peek().is("or")) {
            IqLexer.Token op = take();
            SqlAst.Expr right = conjunction();
            left = logical(SqlAst.BinaryOp.OR, left, right, op);
        }
        return left;
    }

    private SqlAst.Expr conjunction() {
        SqlAst.Expr left = negation();
        while (peek().is("and")) {
            IqLexer.Token op = take();
            SqlAst.Expr right = negation();
            left = logical(SqlAst.BinaryOp.AND, left, right, op);
        }
        return left;
    }

    private SqlAst.Expr logical(SqlAst.BinaryOp op, SqlAst.Expr left, SqlAst.Expr right, IqLexer.Token at) {
        condition(left, op.name());
        condition(right, op.name());
        return new SqlAst.Binary(op, left, right, at.pos());
    }

    private SqlAst.Expr negation() {
        if (peek().is("not")) {
            IqLexer.Token not = take();
            if (peek().is("not")) {
                throw new SqlSyntaxException("NOT NOT: IQ reads NOT (NOT x) only" + IqDialect.syntaxCode(),
                        peek().pos());
            }
            SqlAst.Expr operand = negation();
            condition(operand, "NOT");
            return new SqlAst.Unary(SqlAst.UnaryOp.NOT, operand, not.pos());
        }
        return predicate();
    }

    private SqlAst.Expr predicate() {
        SqlAst.Expr left = concatenation();
        IqLexer.Token t = peek();
        if (t.kind() == IqLexer.Kind.OP && COMPARISONS.contains(t.text())) {
            take();
            SqlAst.Expr right = concatenation();
            value(left, "a comparison");
            value(right, "a comparison");
            IqLexer.Token after = peek();
            if (after.kind() == IqLexer.Kind.OP && (COMPARISONS.contains(after.text()) || after.isOp("*=")
                    || after.isOp("=*"))) {
                throw new SqlSyntaxException("comparisons do not chain" + IqDialect.syntaxCode(), after.pos());
            }
            return new SqlAst.Binary(comparison(t.text()), left, right, t.pos());
        }
        if (t.isOp("*=") || t.isOp("=*")) {
            throw SqlSyntaxException.unsupported("the T-SQL outer join operators (*=, =*) are not supported yet",
                    t.pos());
        }
        if (t.is("is")) {
            take();
            boolean negated = false;
            if (peek().is("not")) {
                take();
                negated = true;
            }
            if (peek().is("null")) {
                take();
                value(left, "IS NULL");
                return new SqlAst.IsNull(left, negated, t.pos());
            }
            throw SqlSyntaxException.unsupported("IS " + (negated ? "NOT " : "") + peek().text()
                    + " is not supported yet", peek().pos());
        }
        IqLexer.Token predicate = t.is("not") ? peek(1) : t;
        if (predicate.is("between") || predicate.is("like") || predicate.is("in") || predicate.isWord("similar")
                || predicate.isWord("regexp") || predicate.is("contains")) {
            throw SqlSyntaxException.unsupported(predicate.text().toUpperCase(java.util.Locale.ROOT)
                    + " is not supported yet", predicate.pos());
        }
        return left;
    }

    private static SqlAst.BinaryOp comparison(String op) {
        return switch (op) {
            case "=" -> SqlAst.BinaryOp.EQ;
            case "<>", "!=" -> SqlAst.BinaryOp.NE;
            case "<" -> SqlAst.BinaryOp.LT;
            case ">" -> SqlAst.BinaryOp.GT;
            case "<=", "!>" -> SqlAst.BinaryOp.LE;
            case ">=", "!<" -> SqlAst.BinaryOp.GE;
            default -> throw new IllegalArgumentException("not a comparison: " + op);
        };
    }

    // ------------------------------------------------------------------ values

    /** {@code ||}, IQ's loosest value operator. */
    private SqlAst.Expr concatenation() {
        SqlAst.Expr left = additive();
        while (peek().isOp("||")) {
            IqLexer.Token op = take();
            left = arithmetic(SqlAst.BinaryOp.CONCAT, left, additive(), op);
        }
        return left;
    }

    private SqlAst.Expr additive() {
        SqlAst.Expr left = multiplicative();
        while (peek().isOp("+") || peek().isOp("-")) {
            IqLexer.Token op = take();
            left = arithmetic(op.isOp("+") ? SqlAst.BinaryOp.PLUS : SqlAst.BinaryOp.MINUS, left, multiplicative(),
                    op);
        }
        return left;
    }

    private SqlAst.Expr multiplicative() {
        SqlAst.Expr left = signed();
        while (peek().isOp("*") || peek().isOp("/") || peek().isOp("%")) {
            IqLexer.Token op = take();
            SqlAst.BinaryOp b = op.isOp("*") ? SqlAst.BinaryOp.TIMES : op.isOp("/") ? SqlAst.BinaryOp.DIVIDE
                    : SqlAst.BinaryOp.MODULO;
            left = arithmetic(b, left, signed(), op);
        }
        return left;
    }

    private SqlAst.Expr arithmetic(SqlAst.BinaryOp op, SqlAst.Expr left, SqlAst.Expr right, IqLexer.Token at) {
        value(left, "an arithmetic operator");
        value(right, "an arithmetic operator");
        return new SqlAst.Binary(op, left, right, at.pos());
    }

    /** A prefix sign (the research's GRAMMAR E5: {@code (- | +) signed | bitwise}): it binds looser than the bitwise
     *  operators, and signs repeat ({@code - -1}). */
    private SqlAst.Expr signed() {
        IqLexer.Token t = peek();
        if (t.isOp("-") || t.isOp("+")) {
            take();
            SqlAst.Expr operand = signed();
            value(operand, "a sign");
            return new SqlAst.Unary(t.isOp("-") ? SqlAst.UnaryOp.MINUS : SqlAst.UnaryOp.PLUS, operand, t.pos());
        }
        return bitwise();
    }

    /** IQ's bitwise operators ({@code & | ^}, one level, tighter than the sign; {@code ^} is XOR, not a power) and
     *  prefix {@code ~}: refused by name. */
    private SqlAst.Expr bitwise() {
        if (peek().isOp("~")) {
            throw SqlSyntaxException.unsupported("the bitwise operator ~ is not supported yet", peek().pos());
        }
        SqlAst.Expr operand = primary();
        IqLexer.Token t = peek();
        if (t.isOp("&") || t.isOp("|") || t.isOp("^")) {
            throw SqlSyntaxException.unsupported("the bitwise operator " + t.text() + " is not supported yet",
                    t.pos());
        }
        return operand;
    }

    private SqlAst.Expr primary() {
        IqLexer.Token t = peek();
        switch (t.kind()) {
            case NUMBER -> {
                take();
                return number(t);
            }
            case STRING -> {
                take();
                return new SqlAst.StringLit(t.text(), t.pos());
            }
            case WORD, QUOTED -> {
                return reference();
            }
            case KEYWORD -> {
                if (t.is("null")) {
                    take();
                    return new SqlAst.NullLit(t.pos());
                }
                if (t.is("if")) {
                    return ifExpression();
                }
                if (t.is("case")) {
                    return caseExpression();
                }
                throw SqlSyntaxException.unsupported("'" + t.text() + "' here (" + keywordForm(t)
                        + ") is not supported yet", t.pos());
            }
            case OP -> {
                if (t.isOp("(")) {
                    take();
                    if (peek().is("select") || peek().is("with")) {
                        throw SqlSyntaxException.unsupported("subqueries are not supported yet", peek().pos());
                    }
                    SqlAst.Expr inner = expression();
                    expectOp(")");
                    return inner;
                }
                if (t.isOp("?") || t.isOp(":")) {
                    throw SqlSyntaxException.unsupported("parameters (? and :name) are not supported yet", t.pos());
                }
                if (t.isOp("{")) {
                    throw SqlSyntaxException.unsupported("ODBC escapes ({...}) are not supported yet", t.pos());
                }
                throw unexpected(t);
            }
            case EOF -> throw unexpected(t);
        }
        throw new IllegalStateException("unreachable: " + t.kind());
    }

    /** What a keyword heading a value would begin, for the refusal's message. */
    private static String keywordForm(IqLexer.Token t) {
        String k = IqLexer.lower(t.text());
        return k.equals("current") ? "a special value" : k.equals("exists") ? "a subquery"
                : "a function or special form";
    }

    /** {@code IF condition THEN value [ELSE value] (ENDIF | END IF)} (the research's GRAMMAR E14), as the CASE it
     *  means: {@code CASE WHEN c THEN a WHEN NOT c THEN b END}, so an UNKNOWN condition gives NULL, ELSE or not. IQ
     *  has no ELSEIF, and a bare END does not close an IF: each IQ's syntax error. */
    private SqlAst.Expr ifExpression() {
        IqLexer.Token t = take();
        SqlAst.Expr c = condition("IF");
        expectSyntax("then", "IF needs THEN");
        SqlAst.Expr a = expression();
        value(a, "IF's THEN");
        SqlAst.Expr b = null;
        if (peek().is("else")) {
            take();
            b = expression();
            value(b, "IF's ELSE");
        }
        if (peek().is("endif")) {
            take();
        } else if (peek().is("end") && peek(1).is("if")) {
            take();
            take();
        } else {
            throw new SqlSyntaxException("an IF expression ends with ENDIF or END IF" + (peek().is("elseif")
                    ? " (IQ's IF expression has no ELSEIF)" : "") + IqDialect.syntaxCode(), peek().pos());
        }
        List<SqlAst.When> whens = new ArrayList<>(List.of(new SqlAst.When(c, a)));
        if (b != null) {
            whens.add(new SqlAst.When(new SqlAst.Unary(SqlAst.UnaryOp.NOT, c, c.pos()), b));
        }
        return new SqlAst.Case(null, whens, null, t.pos());
    }

    /** {@code CASE [value] WHEN ... THEN value ... [ELSE value] END [CASE]} (the research's GRAMMAR E12-E13): a simple
     *  CASE's {@code WHEN v} is the condition {@code value = v}, so the dialect compares as {@code =} does. */
    private SqlAst.Expr caseExpression() {
        IqLexer.Token t = take();
        SqlAst.Expr operand = null;
        if (!peek().is("when")) {
            operand = expression();
            value(operand, "CASE's operand");
        }
        if (!peek().is("when")) {
            throw new SqlSyntaxException("CASE needs WHEN" + IqDialect.syntaxCode(), peek().pos());
        }
        List<SqlAst.When> whens = new ArrayList<>();
        while (peek().is("when")) {
            IqLexer.Token when = take();
            SqlAst.Expr test;
            if (operand == null) {
                test = condition("WHEN");
            } else {
                SqlAst.Expr v = expression();
                value(v, "WHEN");
                test = new SqlAst.Binary(SqlAst.BinaryOp.EQ, operand, v, when.pos());
            }
            expectSyntax("then", "WHEN needs THEN");
            SqlAst.Expr result = expression();
            value(result, "THEN");
            whens.add(new SqlAst.When(test, result));
        }
        SqlAst.Expr otherwise = null;
        if (peek().is("else")) {
            take();
            otherwise = expression();
            value(otherwise, "ELSE");
        }
        expectSyntax("end", "CASE ends with END");
        if (peek().is("case")) {
            take();
        }
        return new SqlAst.Case(null, whens, otherwise, t.pos());
    }

    /** A column reference: one name, or names joined by dots (a function call refused by name). */
    private SqlAst.Expr reference() {
        IqLexer.Token first = peek();
        if (first.kind() == IqLexer.Kind.WORD && first.text().startsWith("@@")) {
            throw SqlSyntaxException.unsupported("global variables (" + first.text() + ") are not supported yet",
                    first.pos());
        }
        List<SqlAst.Ident> parts = new ArrayList<>(List.of(name(take())));
        while (peek().isOp(".")) {
            take();
            parts.add(namePart());
        }
        if (peek().isOp("(")) {
            return call(parts, first);
        }
        if (peek().isOp("[[")) {
            throw SqlSyntaxException.unsupported("array subscripts ([[n]]) are not supported yet", peek().pos());
        }
        return new SqlAst.ColumnRef(parts, first.pos());
    }

    /** {@code name(value, ...)}: a scalar function's call, which the dialect's catalog reads (or refuses by name). An
     *  owner-qualified name, an aggregate, {@code DISTINCT} or {@code *} in the call, and a window ({@code OVER}) are
     *  refused by name. */
    private SqlAst.Expr call(List<SqlAst.Ident> parts, IqLexer.Token first) {
        String name = parts.get(parts.size() - 1).text();
        if (parts.size() > 1) {
            throw SqlSyntaxException.unsupported("owner-qualified function names (" + String.join(".",
                    parts.stream().map(SqlAst.Ident::text).toList()) + "(...)) are not supported yet", first.pos());
        }
        if (AGGREGATES.contains(IqLexer.lower(name))) {
            throw SqlSyntaxException.unsupported("the aggregate or OLAP function " + name + "(...) is not supported"
                    + " yet", first.pos());
        }
        take();
        if (peek().is("distinct") || peek().is("all") || peek().isOp("*")) {
            throw SqlSyntaxException.unsupported("'" + peek().text() + "' in a call (an aggregate's form) is not"
                    + " supported yet", peek().pos());
        }
        List<SqlAst.Expr> args = new ArrayList<>();
        if (!peek().isOp(")")) {
            do {
                noNamedArgument();
                SqlAst.Expr a = expression();
                value(a, "a function's arguments");
                args.add(a);
            } while (takeOp(","));
        }
        expectOp(")");
        if (peek().is("over") || peek().is("within")) {
            throw SqlSyntaxException.unsupported(peek().text().toUpperCase(java.util.Locale.ROOT) + " after a call is"
                    + " not supported yet", peek().pos());
        }
        return new SqlAst.FunctionCall(parts, args, false, false, first.pos());
    }

    /** A name after a dot: a reserved word there is IQ's syntax error unless delimited. */
    private SqlAst.Ident namePart() {
        IqLexer.Token t = peek();
        if (t.kind() == IqLexer.Kind.KEYWORD) {
            throw new SqlSyntaxException("the reserved word '" + t.text() + "' after a dot must be delimited"
                    + IqDialect.syntaxCode(), t.pos());
        }
        if (!t.isName()) {
            throw unexpected(t);
        }
        return name(take());
    }

    private static SqlAst.Ident name(IqLexer.Token t) {
        return new SqlAst.Ident(t.text(), t.kind() == IqLexer.Kind.QUOTED, t.pos());
    }

    /** A number: an integer when written with digits only, else a numeric (IQ types a point or an exponent as
     *  NUMERIC, the research's D-N5). Beyond lite's 38 digits, or a double's range, refused by name. */
    private static SqlAst.Expr number(IqLexer.Token t) {
        String s = t.text();
        boolean integer = true;
        for (int i = 0; i < s.length(); i++) {
            integer &= s.charAt(i) >= '0' && s.charAt(i) <= '9';
        }
        if (integer) {
            BigInteger v = new BigInteger(s);
            if (v.toString().length() > 38) {
                throw SqlSyntaxException.unsupported("a number of more than 38 digits is not supported", t.pos());
            }
            return new SqlAst.IntegerLit(v, t.pos());
        }
        BigDecimal v = new BigDecimal(s);
        if (v.signum() != 0 && (v.precision() - v.scale() > 300 || v.scale() > 300)) {
            throw SqlSyntaxException.unsupported("a number beyond 1e300 or below 1e-300 is not supported", t.pos());
        }
        BigDecimal plain = v.scale() < 0 ? v.setScale(0) : v;
        if (plain.precision() > 38 || plain.scale() > 38) {
            // IQ keeps such a constant exactly (a NUMERIC of up to 126 digits); lite's decimals hold 38
            throw SqlSyntaxException.unsupported("a number of more than 38 digits, or of more than 38 after the point,"
                    + " is not supported", t.pos());
        }
        return new SqlAst.NumericLit(plain, t.pos());
    }

    // ------------------------------------------------------------------ values and conditions apart

    private static boolean isCondition(SqlAst.Expr e) {
        return switch (e) {
            case SqlAst.Binary b -> switch (b.op()) {
                case AND, OR, EQ, NE, LT, LE, GT, GE -> true;
                default -> false;
            };
            case SqlAst.Unary u -> u.op() == SqlAst.UnaryOp.NOT;
            case SqlAst.IsNull n -> true;
            default -> false;
        };
    }

    /** A value where one is expected: a condition there is IQ's syntax error (IQ has no boolean values; in the select
     *  list, {@code a = b} is T-SQL's alias form, read before an expression is). */
    private void value(SqlAst.Expr e, String where) {
        if (isCondition(e)) {
            throw new SqlSyntaxException("a condition cannot stand in " + where + ": IQ has no boolean values"
                    + IqDialect.syntaxCode(), e.pos());
        }
    }

    private void condition(SqlAst.Expr e, String operator) {
        if (!isCondition(e)) {
            throw new SqlSyntaxException(operator + " needs conditions, and IQ has no boolean values"
                    + IqDialect.syntaxCode(), e.pos());
        }
    }

    // ------------------------------------------------------------------ tokens

    private IqLexer.Token peek() {
        return tokens.get(at);
    }

    private IqLexer.Token peek(int ahead) {
        return tokens.get(Math.max(0, Math.min(at + ahead, tokens.size() - 1)));
    }

    private IqLexer.Token take() {
        IqLexer.Token t = tokens.get(at);
        if (t.kind() != IqLexer.Kind.EOF) {
            at++;
        }
        return t;
    }

    private boolean takeOp(String op) {
        if (peek().isOp(op)) {
            take();
            return true;
        }
        return false;
    }

    private void expectOp(String op) {
        if (!takeOp(op)) {
            throw unexpected(peek());
        }
    }

    private void expectKeyword(String keyword) {
        if (!peek().is(keyword)) {
            throw unexpected(peek());
        }
        take();
    }

    /** The reserved word {@code keyword}, which IQ's grammar requires here: its absence is IQ's syntax error. */
    private void expectSyntax(String keyword, String message) {
        if (!peek().is(keyword)) {
            throw new SqlSyntaxException(message + ", not '" + peek().text() + "'" + IqDialect.syntaxCode(),
                    peek().pos());
        }
        take();
    }

    /** A token where the grammar read so far has no place for it: refused as not supported, since IQ may read it. */
    private SqlSyntaxException unexpected(IqLexer.Token t) {
        if (t.kind() == IqLexer.Kind.EOF) {
            return new SqlSyntaxException("the statement ends too soon" + IqDialect.syntaxCode(), t.pos());
        }
        return SqlSyntaxException.unsupported("'" + t.text() + "' here is not supported", t.pos());
    }
}
