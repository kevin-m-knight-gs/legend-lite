// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.protocol.sql.iq;

import com.legend.protocol.sql.SqlAst;
import com.legend.protocol.sql.SqlSyntaxException;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * IQ's SELECT ({@link IqLexer}'s tokens) to the shared syntax tree, {@link SqlAst}, which {@code SqlToPure} reads
 * through {@link IqDialect} (docs/IQ_SQL_STUDY_2026_10_13.md §5.2). The productions read so far are the research
 * GRAMMAR's for M0's first slice:
 * <pre>
 *   SELECT [ALL | DISTINCT] item, ... [FROM item] [WHERE condition] [ORDER BY expression [ASC | DESC], ...]
 *   item:       * | name.* | expression [[AS] alias]
 *   FROM item:  name | name(arguments)  [[AS] alias]
 *   condition:  NOT, AND, OR over predicates; a comparison (= &lt;&gt; != &lt; &gt; &lt;= &gt;= !&lt; !&gt;),
 *               IS [NOT] NULL, or a parenthesized condition
 *   expression: a column (plain, [bracketed], `back-quoted` or "double-quoted" parts), a number (signed), a string,
 *               NULL, a parenthesized expression; || + - * / % parse at IQ's precedence and are the dialect's to read
 * </pre>
 * IQ keeps values and conditions apart (it has no boolean values): a condition where a value is expected, or a value
 * where a condition is, is a syntax error, and so are {@code NOT NOT} and a chained comparison, as in IQ (measured on
 * SQL Anywhere 17, the research's GRAMMAR §2). Everything else IQ reads -- TOP, GROUP BY, joins, subqueries, set
 * operations, function calls, CASE and IF, the T-SQL alias forms, parameters -- is refused here by name, as not
 * supported yet: never read as something else.
 */
public final class IqParser {

    /** The comparison operators, with IQ's spellings of the negated forms. */
    private static final List<String> COMPARISONS = List.of("=", "<>", "!=", "<", ">", "<=", ">=", "!<", "!>");

    /** Keywords that begin a join (M1b). */
    private static final Set<String> JOIN_WORDS = Set.of("join", "inner", "left", "right", "full", "cross",
            "natural", "key", "outer");

    private final String text;
    private final List<IqLexer.Token> tokens;
    /** The expressions written in parentheses: a parenthesized condition in a value's place is IQ's syntax error. */
    private final Set<SqlAst.Expr> parenthesized = Collections.newSetFromMap(new IdentityHashMap<>());
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
        if (peek().is("top") || peek().is("first")) {
            throw SqlSyntaxException.unsupported(peek().text().toUpperCase(java.util.Locale.ROOT)
                    + " (a row limit) is not supported yet", peek().pos());
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
                orderBy.add(sortItem());
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
        return new SqlAst.Select(distinct, items, from, where, List.of(), null, List.of(), orderBy, null, null, pos);
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
        if (first.isName() && peek(1).isOp("=")) {
            throw SqlSyntaxException.unsupported("the T-SQL select item 'alias = expression' is not supported yet",
                    first.pos());
        }
        int start = first.start();
        SqlAst.Expr e = expression();
        value(e, "the select list");
        String written = text.substring(start, tokens.get(at - 1).end());
        SqlAst.Ident alias = alias(false);
        return new SqlAst.SelectExpr(e, alias, first.pos(), written);
    }

    /** {@code [AS] alias}: a plain or delimited name; a reserved word after AS is IQ's syntax error, and a string
     *  alias is refused (T-SQL's, not read yet). {@code fromItem}: the alias of a FROM item. */
    private SqlAst.@com.legend.base.Nullable Ident alias(boolean fromItem) {
        IqLexer.Token t = peek();
        boolean as = t.is("as");
        if (as) {
            take();
            t = peek();
        }
        if (t.kind() == IqLexer.Kind.STRING) {
            throw SqlSyntaxException.unsupported("a string as an alias is not supported yet", t.pos());
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
        if (peek().isOp("(")) {
            take();
            List<SqlAst.Arg> args = new ArrayList<>();
            if (!peek().isOp(")")) {
                do {
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

    private void noTableHints() {
        if (peek().is("with") && peek(1).isOp("(")) {
            throw SqlSyntaxException.unsupported("table hints (WITH (...)) are not supported yet", peek().pos());
        }
        if (peek().is("force") || peek().isWord("noholdlock") || peek().is("holdlock")) {
            throw SqlSyntaxException.unsupported("table hints are not supported yet", peek().pos());
        }
    }

    // ------------------------------------------------------------------ ORDER BY

    private SqlAst.SortItem sortItem() {
        IqLexer.Token first = peek();
        SqlAst.Expr e = expression();
        value(e, "ORDER BY");
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
        return new SqlAst.SortItem(e, descending, null, first.pos());
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

    /** A prefix sign. Read only before a number for now: IQ's minus of a value may overflow its type (the research's
     *  D-N3), which the island does not check yet. */
    private SqlAst.Expr signed() {
        IqLexer.Token t = peek();
        if (t.isOp("-") || t.isOp("+")) {
            if (peek(1).kind() != IqLexer.Kind.NUMBER) {
                throw SqlSyntaxException.unsupported("a sign before anything but a number is not supported yet",
                        t.pos());
            }
            take();
            SqlAst.Expr operand = bitwise();
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
                    parenthesized.add(inner);
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
        return k.equals("case") || k.equals("if") ? "a conditional expression"
                : k.equals("current") ? "a special value" : k.equals("exists") ? "a subquery"
                : "a function or special form";
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
            throw SqlSyntaxException.unsupported("function calls (" + parts.get(parts.size() - 1).text()
                    + "(...)) are not supported yet", first.pos());
        }
        return new SqlAst.ColumnRef(parts, first.pos());
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
        if (plain.precision() > 38) {
            throw SqlSyntaxException.unsupported("a number of more than 38 digits is not supported", t.pos());
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

    private void value(SqlAst.Expr e, String where) {
        if (isCondition(e)) {
            if (parenthesized.contains(e) || !(e instanceof SqlAst.Binary)) {
                throw new SqlSyntaxException("a condition cannot stand in " + where + ": IQ has no boolean values"
                        + IqDialect.syntaxCode(), e.pos());
            }
            // e.g. SELECT a = b: a T-SQL alias in IQ's select list, not read yet; elsewhere IQ's syntax error
            throw SqlSyntaxException.unsupported("a condition in " + where + " is not supported", e.pos());
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
        return tokens.get(Math.min(at + ahead, tokens.size() - 1));
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

    /** A token where the grammar read so far has no place for it: refused as not supported, since IQ may read it. */
    private SqlSyntaxException unexpected(IqLexer.Token t) {
        if (t.kind() == IqLexer.Kind.EOF) {
            return new SqlSyntaxException("the statement ends too soon" + IqDialect.syntaxCode(), t.pos());
        }
        return SqlSyntaxException.unsupported("'" + t.text() + "' here is not supported", t.pos());
    }
}
