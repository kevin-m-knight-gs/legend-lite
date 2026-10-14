// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.protocol.sql.iq;

import com.legend.protocol.sql.SqlAst;
import com.legend.protocol.sql.SqlSyntaxException;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * IQ's SELECT ({@link IqLexer}'s tokens) to the shared syntax tree, {@link SqlAst}, which {@code SqlToPure} reads
 * through {@link IqDialect} (docs/IQ_SQL_STUDY_2026_10_13.md §5.2). The productions read so far are the research
 * GRAMMAR's for M0, M1a and M1b:
 * <pre>
 *   statement:  query [ORDER BY key, ...]        (the ORDER BY is the whole query's, a set operation's included)
 *   query:      term {(UNION | EXCEPT) [ALL | DISTINCT] term};  term: primary {INTERSECT [ALL | DISTINCT] primary}
 *   primary:    select | ( query [ORDER BY key, ...] )       (INTERSECT binds tighter; a branch never begins with WITH)
 *   select:     SELECT [ALL | DISTINCT] [row limit] item, ... [FROM table expression, ...] [WHERE condition]
 *   row limit:  FIRST | TOP (ALL | count) [START AT count]
 *   count:      an unsigned integer up to 2147483647, or ( + - * over such integers and parentheses )
 *   item:       * | name.* | expression [[AS] alias] | alias = expression     (T-SQL's, never a comparison)
 *   alias:      a name ([bracketed], `back-quoted`, "double-quoted") or a 'string'
 *   table expression: factor {[NATURAL] [INNER | LEFT [OUTER] | RIGHT [OUTER] | FULL [OUTER]] JOIN factor [ON condition]
 *               | CROSS JOIN factor}                         (a comma binds loosest; a join without ON is IQ's KEY JOIN)
 *   factor:     name | name(arguments)  [[AS] alias] | ( query ) [AS] alias [(column, ...)] | ( table expression, ... )
 *   condition:  NOT, AND, OR over predicates, each optionally tested: IS [NOT] (TRUE | FALSE | UNKNOWN)
 *   predicate:  a comparison (= &lt;&gt; != &lt; &gt; &lt;= &gt;= !&lt; !&gt;), IS [NOT] NULL, IS [NOT] DISTINCT FROM,
 *               [NOT] BETWEEN value AND value, [NOT] LIKE pattern [ESCAPE char], [NOT] IN (value, ...),
 *               [NOT] IN (query), a comparison with ANY | SOME | ALL (query), EXISTS (query), or a parenthesized
 *               condition
 *   expression: a column (plain, [bracketed], `back-quoted` or "double-quoted" parts), a number, a string, NULL, a
 *               parenthesized expression, a parenthesized query (its one value), a function call name(value, ...),
 *               CASE [value] WHEN ... THEN ... [ELSE ...] END [CASE], IF condition THEN value [ELSE value]
 *               (ENDIF | END IF), a sign (- or +) before a value; || + - * / % parse at IQ's precedence and are the
 *               dialect's to read
 * </pre>
 * The row limit becomes the query's limit and offset: {@code FIRST} is {@code TOP 1}, {@code START AT m} skips m - 1
 * rows (so the offset is the expression {@code m - 1}, which the dialect checks and the planner folds). {@code IF c
 * THEN a ELSE b ENDIF} becomes {@code CASE WHEN c THEN a WHEN NOT c THEN b END}, so an UNKNOWN condition gives NULL
 * even with an ELSE (the research's SEMANTICS §3.2); a simple CASE ({@code CASE x WHEN v ...}) becomes the searched
 * one ({@code CASE WHEN x = v ...}), so its comparisons are the dialect's. A parenthesis that may open a query or an
 * expression ({@code ((SELECT 1) + 1)}) is read as a query first, once per position. What the tree does not keep of
 * how an item was written (its parentheses, a simple CASE, an IF), which IQ's name for an unaliased item shows, is
 * read again from the item's text by {@link #written}.
 *
 * <p>IQ keeps values and conditions apart (it has no boolean values): a condition where a value is expected, or a
 * value where a condition is, is a syntax error, and so are {@code NOT NOT} and a chained comparison, as in IQ
 * (measured on SQL Anywhere 17, the research's GRAMMAR §2). So are USING, a second ON in a row, CROSS JOIN ... ON, a
 * derived table without a name and a parenthesized join with one. Everything else IQ reads -- GROUP BY, KEY JOIN,
 * APPLY, LATERAL, WITH, aggregates, the bitwise operators, CAST and the other special forms, parameters and variables
 * -- is refused here by name, as not supported yet: never read as something else.
 */
public final class IqParser {

    /** The comparison operators, with IQ's spellings of the negated forms. */
    private static final List<String> COMPARISONS = List.of("=", "<>", "!=", "<", ">", "<=", ">=", "!<", "!>");

    /** IQ's aggregate and OLAP functions (and the translator's own aggregate names, which it would read as one): each
     *  refused by name until its slice (M3), but for the ones read ({@link #READ_AGGREGATES}). */
    private static final Set<String> AGGREGATES = Set.of("count", "sum", "avg", "min", "max", "list", "median",
            "stddev", "stddev_pop", "stddev_samp", "variance", "var_pop", "var_samp", "first_value", "last_value",
            "nth_value", "row_number", "rank", "dense_rank", "percent_rank", "cume_dist", "ntile", "lag", "lead",
            "grouping", "corr", "covar_pop", "covar_samp", "regr_avgx", "regr_avgy", "regr_count", "regr_intercept",
            "regr_r2", "regr_slope", "regr_sxx", "regr_sxy", "regr_syy", "percentile_cont", "percentile_disc",
            "xmlagg", "string_agg", "bool_and", "bool_or", "every", "bit_and", "bit_or", "bit_xor", "set_bits",
            "count_big", "count_distinct", "approx_count_distinct");

    /** The aggregates read (M1c, the research's GRAMMAR F7 and F10): {@code [ALL | DISTINCT] value}, COUNT's
     *  {@code *} (or nothing), LIST's delimiter, and an ORDER BY inside the call (IQ's syntax in any call; the dialect
     *  takes it in LIST's only). */
    static final Set<String> READ_AGGREGATES = Set.of("count", "sum", "avg", "min", "max", "median", "list");

    /** IQ's statistical aggregates: SQL Anywhere computes them by a formula that loses precision (the research's
     *  TYPING §7: three equal values of 9999999999999999.9999 give a STDDEV of 134217728), which lite's databases do
     *  not reproduce; refused by name. */
    private static final Set<String> STATISTICS = Set.of("stddev", "stddev_pop", "stddev_samp", "variance", "var_pop",
            "var_samp");

    /** IQ island, 2026-10-14 (M1d): the functions {@code f(*)} calls without arguments (the research's GRAMMAR F4). */
    private static final Set<String> STAR_CALLS = Set.of("getdate", "now", "today", "pi");

    /** IQ island, 2026-10-14 (M1d): the functions whose first argument is a date part, a keyword of its own (GRAMMAR
     *  F19), read as the constant string of its full name ({@link #datePart}). */
    private static final Set<String> DATE_PART_CALLS = Set.of("datepart", "datename", "dateadd", "datediff",
            "dateround", "datefloor", "dateceiling");

    /** IQ island, 2026-10-14 (the third landing): how many arguments each of them IQ reads takes (else its -154). */
    private static final Map<String, Integer> PART_CALL_ARGUMENTS = Map.of("datepart", 2, "datename", 2, "dateadd", 3,
            "datediff", 3);

    /** IQ island, 2026-10-14 (M1d): the date parts (GRAMMAR F20), each abbreviation to its full name. */
    private static final java.util.Map<String, String> DATE_PARTS = datePartNames();

    /** The type name a style-less CONVERT's {@code Cast} carries before the type's own, so that the dialect can tell
     *  it from a CAST (they differ only in how a date becomes text). */
    static final String CONVERT = "CONVERT ";

    /** The largest count of a row limit (TOP's, START AT's): an INT's. */
    private static final BigInteger MAX_COUNT = BigInteger.valueOf(Integer.MAX_VALUE);

    private final String text;
    private final List<IqLexer.Token> tokens;
    private int at;
    /** What the tree does not keep of how an expression was written, for the name IQ gives an unaliased one
     *  ({@link #written}): the parentheses around each expression (how many), the CASEs written simple
     *  ({@code CASE x WHEN v ...}), the CASEs that are IF expressions. */
    private final Map<SqlAst.Expr, Integer> parens = new IdentityHashMap<>();
    private final Set<SqlAst.Expr> simpleCases = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<SqlAst.Expr> ifs = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
    /** IQ island, 2026-10-14 (the merge of M1b and M1d): each date part as written, in lower case (the constant the
     *  tree holds is the part's full name; SQL Anywhere names a call by the spelling, measured: {@code DATEPART(mm,
     *  ...)}), or the empty string for a delimited one (its name not measured). */
    private final Map<SqlAst.Expr, String> partWords = new IdentityHashMap<>();
    /** IQ island, 2026-10-14 (review 3's M5): the trailing branches whose value is NULL each CASE and IF was read
     *  without (an ELSE NULL; a last WHEN ... THEN NULL whose condition cannot raise), by identity: the value is the
     *  same, and the compiler's multiplicity stamp of a CASE whose last branches are all NULL is wrong (the compiler
     *  line's; recorded). Kept for the name IQ gives the item ({@link IqNames}). */
    private final Map<SqlAst.Expr, List<SqlAst.When>> droppedWhens = new IdentityHashMap<>();
    private final Map<SqlAst.Expr, SqlAst.Expr> droppedElse = new IdentityHashMap<>();
    /** The token positions where a parenthesis was read as a query and is not one: never tried twice, so that nested
     *  parentheses cost one try each. */
    private final Set<Integer> notQuery = new java.util.HashSet<>();
    /** The text of each GROUP BY item and each aggregate's call, as written: IQ names an unaliased item over them by
     *  their own rendering ({@link Parsed}). */
    private final Map<SqlAst.Expr, String> spans = new IdentityHashMap<>();
    /** IQ island, 2026-10-14 (M1e): the profile the text is read under (a constant's date order), and the index of
     *  each named parameter read so far, one per name ({@link #parameter}). */
    private final IqSettings settings;
    private final Map<String, Integer> parameters = new java.util.HashMap<>();

    private IqParser(String text, List<IqLexer.Token> tokens, IqSettings settings) {
        this.text = text;
        this.tokens = tokens;
        this.settings = settings;
    }

    /** The query {@code sql} is, read under {@code settings}'s options. */
    public static SqlAst.Statement parse(String sql, IqSettings settings) {
        return parsed(sql, settings).statement();
    }

    /** A statement as read ({@link #parsed}): its tree, and the text of each GROUP BY item and each aggregate's call
     *  in it, as written (by identity). */
    public record Parsed(SqlAst.Statement statement, Map<SqlAst.Expr, String> spans) {
    }

    /** The query {@code sql} is, read under {@code settings}'s options, with the texts the tree does not keep. */
    public static Parsed parsed(String sql, IqSettings settings) {
        IqParser p = new IqParser(sql, IqLexer.tokenize(sql, settings), settings);
        if (p.peek().kind() == IqLexer.Kind.EOF) {
            throw new SqlSyntaxException("no statement" + IqDialect.syntaxCode(), p.peek().pos());
        }
        SqlAst.Statement statement = p.statement();
        IqLexer.Token end = p.peek();
        if (end.kind() != IqLexer.Kind.EOF) {
            throw p.unexpected(end);
        }
        return new Parsed(statement, java.util.Collections.unmodifiableMap(p.spans));
    }

    /** An expression as it was written ({@link #written}): its tree, and what the tree does not keep -- the parentheses
     *  around each part, the CASEs written simple, the IF expressions, the date parts' spellings -- and whether a
     *  comment or a line break stands between its tokens (each changes the name IQ gives it, measured). */
    public record Written(SqlAst.Expr expr, Map<SqlAst.Expr, Integer> parens, Set<SqlAst.Expr> simpleCases,
            Set<SqlAst.Expr> ifs, boolean commented, boolean brokenLines, Map<SqlAst.Expr, String> partWords,
            Map<SqlAst.Expr, List<SqlAst.When>> droppedWhens, Map<SqlAst.Expr, SqlAst.Expr> droppedElse,
            Map<SqlAst.Pos, int[]> lines, List<IqLexer.Token> tokens) {

        /** The parentheses written around {@code e}. */
        public int parensOf(SqlAst.Expr e) {
            return parens.getOrDefault(e, 0);
        }

        /** IQ island, 2026-10-14 (the third landing): the lines (1-based; a line feed, a carriage return and the two
         *  together each end one, in a comment too) the token at {@code pos} starts and ends on, or null for none. */
        public int @com.legend.base.Nullable [] linesAt(SqlAst.Pos pos) {
            return lines.get(pos);
        }
    }

    /** {@code text}, one expression (a select item's, as {@link SqlAst.SelectExpr#text} keeps it), read under
     *  {@code settings}'s options with what its tree does not keep ({@link Written}); {@link SqlSyntaxException} when it
     *  is no expression. */
    public static Written written(String text, IqSettings settings) {
        List<IqLexer.Token> tokens = IqLexer.tokenize(text, settings);
        IqParser p = new IqParser(text, tokens, settings);
        SqlAst.Expr e = p.expression();
        if (p.peek().kind() != IqLexer.Kind.EOF) {
            throw p.unexpected(p.peek());
        }
        boolean commented = false;
        boolean brokenLines = false;
        for (int i = 0; i + 1 < tokens.size() && tokens.get(i + 1).kind() != IqLexer.Kind.EOF; i++) {
            String gap = text.substring(tokens.get(i).end(), tokens.get(i + 1).start());
            commented |= !gap.strip().isEmpty();
            brokenLines |= gap.indexOf('\n') >= 0 || gap.indexOf('\r') >= 0;
        }
        // IQ island, 2026-10-14 (the third landing): each token's lines, counted as SQL Anywhere counts them (measured:
        // a carriage return alone breaks a line too)
        Map<SqlAst.Pos, int[]> lines = new java.util.HashMap<>();
        int line = 1;
        int scanned = 0;
        for (IqLexer.Token t : tokens) {
            if (t.kind() == IqLexer.Kind.EOF) {
                break;
            }
            line += breaks(text, scanned, t.start());
            int first = line;
            line += breaks(text, t.start(), t.end());
            scanned = t.end();
            lines.put(t.pos(), new int[] {first, line});
        }
        return new Written(e, p.parens, p.simpleCases, p.ifs, commented, brokenLines, p.partWords, p.droppedWhens,
                p.droppedElse, lines, tokens);
    }

    /** The line breaks in {@code text} from {@code from} to {@code to}: a line feed, a carriage return, and the two
     *  together, one each. */
    private static int breaks(String text, int from, int to) {
        int n = 0;
        for (int i = from; i < to; i++) {
            char c = text.charAt(i);
            if (c == '\n' && !(i > from && text.charAt(i - 1) == '\r') || c == '\r') {
                n++;
            }
        }
        return n;
    }

    /** A statement: a query expression ({@link #queryBlock}), with the tail clauses IQ reads after it refused. */
    private SqlAst.Statement statement() {
        IqLexer.Token first = peek();
        if (first.is("with")) {
            throw SqlSyntaxException.unsupported("WITH (common table expressions) is not supported yet", first.pos());
        }
        if (!first.is("select") && !first.isOp("(")) {
            throw SqlSyntaxException.unsupported("only a SELECT is read in an #IQ{...}# island, not '" + first.text()
                    + "'", first.pos());
        }
        SqlAst.Statement query = queryBlock();
        refuseClause("for", "FOR READ ONLY, FOR UPDATE and FOR XML are not supported yet");
        refuseClause("option", "the OPTION clause is not supported yet");
        if (peek().isWord("limit") || peek().isWord("offset")) {
            throw SqlSyntaxException.unsupported(peek().text() + " is not supported yet", peek().pos());
        }
        if (peek().isOp(";")) {
            throw SqlSyntaxException.unsupported("a statement separator (;) is not read in an #IQ{...}# island",
                    peek().pos());
        }
        return query;
    }

    // ------------------------------------------------------------------ query expressions

    /** {@code query_expression [ORDER BY ...]} (the research's GRAMMAR Q1, Q5): the ORDER BY is the whole query's, a
     *  set operation's included; after it, a set operation is IQ's syntax error (a branch with its own ORDER BY is
     *  written in parentheses). */
    private SqlAst.Statement queryBlock() {
        SqlAst.Statement query = queryExpression();
        if (!peek().is("order")) {
            return query;
        }
        take();
        expectKeyword("by");
        boolean set = query instanceof SqlAst.SetOperation;
        List<SqlAst.SortItem> orderBy = new ArrayList<>();
        do {
            SqlAst.SortItem item = set ? setSortItem() : sortItem();
            if (item != null) {
                orderBy.add(item);
            }
        } while (takeOp(","));
        if (peek().is("union") || peek().is("except") || peek().is("intersect")) {
            throw new SqlSyntaxException("ORDER BY ends the query: a set operation after it needs the branch before it"
                    + " in parentheses" + IqDialect.syntaxCode(), peek().pos());
        }
        if (orderBy.isEmpty()) {
            return query;
        }
        return switch (query) {
            case SqlAst.Select s -> {
                if (!s.orderBy().isEmpty()) {
                    throw SqlSyntaxException.unsupported("a second ORDER BY around a parenthesized query is not"
                            + " supported", orderBy.get(0).pos());
                }
                yield new SqlAst.Select(s.distinct(), s.items(), s.from(), s.where(), s.groupBy(), s.having(),
                        s.windows(), orderBy, s.limit(), s.offset(), s.pos());
            }
            case SqlAst.SetOperation o -> {
                if (!o.orderBy().isEmpty()) {
                    throw SqlSyntaxException.unsupported("a second ORDER BY around a parenthesized query is not"
                            + " supported", orderBy.get(0).pos());
                }
                yield new SqlAst.SetOperation(o.op(), o.all(), o.left(), o.right(), orderBy, null, null, o.pos());
            }
            case SqlAst.With w -> throw new IllegalStateException("IQ's reader makes no WITH");
        };
    }

    /** {@code query_term {(UNION | EXCEPT) [ALL | DISTINCT] query_term}}: left to right (the research's GRAMMAR Q2). */
    private SqlAst.Statement queryExpression() {
        SqlAst.Statement left = queryTerm();
        while (peek().is("union") || peek().is("except")) {
            IqLexer.Token op = take();
            boolean all = setQuantifier();
            SqlAst.Statement right = queryTerm();
            left = setOperation(op.is("union") ? SqlAst.SetOp.UNION : SqlAst.SetOp.EXCEPT, all, left, right, op);
        }
        return left;
    }

    /** {@code query_primary {INTERSECT [ALL | DISTINCT] query_primary}}: INTERSECT binds tighter than UNION and
     *  EXCEPT (the research's GRAMMAR Q3, measured). */
    private SqlAst.Statement queryTerm() {
        SqlAst.Statement left = queryPrimary();
        while (peek().is("intersect")) {
            IqLexer.Token op = take();
            boolean all = setQuantifier();
            SqlAst.Statement right = queryPrimary();
            left = setOperation(SqlAst.SetOp.INTERSECT, all, left, right, op);
        }
        return left;
    }

    /** A set operation of two branches: a branch with an ORDER BY of its own (parenthesized) is refused by name (IQ's
     *  manual forbids it; SQL Anywhere reads it: the research's GRAMMAR Q5, flag). */
    private static SqlAst.Statement setOperation(SqlAst.SetOp op, boolean all, SqlAst.Statement left,
            SqlAst.Statement right, IqLexer.Token at) {
        for (SqlAst.Statement branch : List.of(left, right)) {
            boolean ordered = branch instanceof SqlAst.Select s && !s.orderBy().isEmpty()
                    || branch instanceof SqlAst.SetOperation o && !o.orderBy().isEmpty();
            if (ordered) {
                throw SqlSyntaxException.unsupported("a set operation's branch with its own ORDER BY is not supported:"
                        + " IQ's manual forbids it", at.pos());
            }
        }
        return new SqlAst.SetOperation(op, all, left, right, List.of(), null, null, at.pos());
    }

    /** {@code [ALL | DISTINCT]} after a set operator: true for ALL. A second one is IQ's syntax error. */
    private boolean setQuantifier() {
        boolean all = false;
        if (peek().is("all")) {
            take();
            all = true;
        } else if (peek().is("distinct")) {
            take();
        } else {
            return false;
        }
        if (peek().is("all") || peek().is("distinct")) {
            throw new SqlSyntaxException("a set operator takes one of ALL and DISTINCT" + IqDialect.syntaxCode(),
                    peek().pos());
        }
        return all;
    }

    /** {@code select_block | ( query_block )} (the research's GRAMMAR Q4): a parenthesized query never begins with WITH
     *  (Q9: IQ's syntax error). */
    private SqlAst.Statement queryPrimary() {
        IqLexer.Token t = peek();
        if (t.is("select")) {
            return select();
        }
        if (t.isOp("(")) {
            take();
            if (peek().is("with")) {
                throw new SqlSyntaxException("a parenthesized query, a set operation's branch or a subquery cannot"
                        + " begin with WITH" + IqDialect.syntaxCode(), peek().pos());
            }
            SqlAst.Statement inner = queryBlock();
            expectOp(")");
            return inner;
        }
        if (t.is("with")) {
            throw new SqlSyntaxException("a set operation's branch cannot begin with WITH" + IqDialect.syntaxCode(),
                    t.pos());
        }
        throw new SqlSyntaxException("a query begins with SELECT, not '" + t.text() + "'" + IqDialect.syntaxCode(),
                t.pos());
    }

    /** Whether the tokens from {@code from} on are a parenthesis opening a query: {@code (} then, after any more
     *  parentheses, SELECT. */
    private boolean opensQuery(int from) {
        int i = from;
        while (i < tokens.size() && tokens.get(i).isOp("(")) {
            i++;
        }
        return i > from && i < tokens.size() && tokens.get(i).is("select");
    }

    /** A parenthesized query ({@code ( query_block )}) at the parenthesis here, when it is one; else null, with
     *  nothing read. Only a parenthesis followed by more parentheses is in doubt ({@code ((SELECT 1) + 1)} is a value,
     *  {@code ((SELECT 1) UNION (SELECT 2))} a query): it is read as a query first, once. {@code onFailure} keeps the
     *  query's own error, which stands when the other reading fails too. */
    private SqlAst.@com.legend.base.Nullable Statement parenthesizedQuery(SqlSyntaxException[] onFailure) {
        int start = at;
        if (!opensQuery(start) || notQuery.contains(start)) {
            return null;
        }
        if (peek(1).is("select")) {
            take();
            SqlAst.Statement q = queryBlock();
            expectOp(")");
            return q;
        }
        try {
            take();
            SqlAst.Statement q = queryBlock();
            if (peek().isOp(")")) {
                take();
                return q;
            }
        } catch (SqlSyntaxException e) {
            onFailure[0] = e;
        }
        notQuery.add(start);
        at = start;
        return null;
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
            do {
                from.add(tableExpression());
            } while (takeOp(","));
        }
        SqlAst.Expr where = null;
        if (peek().is("where")) {
            take();
            where = condition("WHERE");
        }
        List<SqlAst.Expr> groupBy = new ArrayList<>();
        if (peek().is("group")) {
            take();
            expectKeyword("by");
            groupBy = groupBy();
        }
        SqlAst.Expr having = null;
        if (peek().is("having")) {
            take();
            having = condition("HAVING");
            if (peek().is("group")) {
                throw new SqlSyntaxException("GROUP BY comes before HAVING" + IqDialect.syntaxCode(), peek().pos());
            }
        }
        refuseClause("window", "the WINDOW clause is not supported yet");
        return new SqlAst.Select(distinct, items, from, where, groupBy, having, List.of(), List.of(),
                limit == null ? null : limit.count(), limit == null ? null : limit.offset(), pos);
    }

    /**
     * {@code GROUP BY item, ...} (the research's GRAMMAR G1-G6): each item an expression, kept with its text. Read: an
     * expression, an integer constant (a constant to IQ, never a position: the dialect's to read). Refused by name:
     * ROLLUP, CUBE, WITH ROLLUP, WITH CUBE and GROUPING SETS (IQ's engine restricts them, and SQL Anywhere answers some
     * wrongly: the study's §9.5), an empty {@code ( )}, a composite {@code (a, b)}, a subquery. {@code GROUP BY ALL}
     * and an empty GROUP BY are IQ's syntax errors.
     */
    private List<SqlAst.Expr> groupBy() {
        List<SqlAst.Expr> keys = new ArrayList<>();
        do {
            IqLexer.Token t = peek();
            if (t.is("rollup") || t.is("cube") || t.isWord("grouping") && peek(1).isWord("sets")) {
                throw SqlSyntaxException.unsupported(t.text().toUpperCase(java.util.Locale.ROOT) + (t.isWord("grouping")
                        ? " SETS" : "(...)") + " is not supported yet: IQ's engine restricts it, and SQL Anywhere"
                        + " answers some of it wrongly", t.pos());
            }
            if (t.is("all") || t.kind() == IqLexer.Kind.EOF || t.isOp(",")) {
                throw new SqlSyntaxException("GROUP BY needs an expression here, not '" + t.text() + "'"
                        + IqDialect.syntaxCode(), t.pos());
            }
            if (t.isOp("(")) {
                if (peek(1).isOp(")")) {
                    throw SqlSyntaxException.unsupported("an empty grouping, GROUP BY ( ), is not supported yet",
                            t.pos());
                }
                if (peek(1).is("select")) {
                    throw SqlSyntaxException.unsupported("a subquery in GROUP BY is not supported (IQ's engine"
                            + " restricts it)", t.pos());
                }
                if (composite(at)) {
                    throw SqlSyntaxException.unsupported("a composite GROUP BY item, (a, b), is not supported yet",
                            t.pos());
                }
            }
            int first = at;
            SqlAst.Expr key = expression();
            value(key, "GROUP BY");
            spans.put(key, text.substring(tokens.get(first).start(), tokens.get(at - 1).end()));
            keys.add(key);
        } while (takeOp(","));
        if (peek().is("with") && (peek(1).is("rollup") || peek(1).is("cube"))) {
            throw SqlSyntaxException.unsupported("WITH " + peek(1).text().toUpperCase(java.util.Locale.ROOT) + " is not"
                    + " supported yet: IQ's engine restricts it, and SQL Anywhere answers some of it wrongly",
                    peek().pos());
        }
        return keys;
    }

    /** Whether the parenthesis at token {@code from} holds a comma at its own depth: a composite GROUP BY item. */
    private boolean composite(int from) {
        int depth = 0;
        for (int i = from; i < tokens.size() && tokens.get(i).kind() != IqLexer.Kind.EOF; i++) {
            IqLexer.Token t = tokens.get(i);
            if (t.isOp("(")) {
                depth++;
            } else if (t.isOp(")")) {
                if (--depth == 0) {
                    return false;
                }
            } else if (t.isOp(",") && depth == 1) {
                return true;
            }
        }
        return false;
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
        if (first.isName() && peek(1).isOp(".") && peek(2).isName() && peek(3).isOp(".") && peek(4).isOp("*")) {
            // IQ island, 2026-10-14 (M1e): owner.table.*
            throw SqlSyntaxException.unsupported("an owner-qualified star (" + first.text() + "." + peek(2).text()
                    + ".*) is not supported yet: write the table's correlation name, " + peek(2).text() + ".*",
                    first.pos());
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

    /**
     * {@code table_factor {join}} (the research's GRAMMAR R1-R3): joins associate left, each right operand a table
     * factor (a nested join is parenthesized, so a second ON in a row is IQ's syntax error).
     * <pre>
     *   join:  [NATURAL] [INNER | LEFT [OUTER] | RIGHT [OUTER] | FULL [OUTER]] JOIN factor [ON condition]
     *        | CROSS JOIN factor
     * </pre>
     * A join with neither ON nor NATURAL (or written KEY JOIN) is IQ's KEY JOIN, made of the tables' foreign keys: it
     * reads, and the dialect refuses it by name (its ON is null and its kind not CROSS). USING is IQ's syntax error;
     * APPLY and the T-SQL outer joins are refused by name.
     */
    private SqlAst.FromItem tableExpression() {
        SqlAst.FromItem left = tableFactor();
        while (true) {
            IqLexer.Token t = peek();
            if (t.is("on")) {
                // ON belongs to the JOIN just before it: a second one in a row (a nested join unparenthesized) is IQ's
                // syntax error
                throw new SqlSyntaxException("this ON has no JOIN of its own: a nested join is written in parentheses"
                        + IqDialect.syntaxCode(), t.pos());
            }
            if ((t.is("cross") || t.is("outer")) && peek(1).isWord("apply")) {
                throw SqlSyntaxException.unsupported(t.text() + " APPLY is not supported yet", t.pos());
            }
            boolean natural = false;
            if (t.is("natural") || t.is("key")) {
                if (t.is("key")) {
                    throw SqlSyntaxException.unsupported("KEY JOIN (a join made of the tables' foreign keys) is not"
                            + " supported yet", t.pos());
                }
                take();
                natural = true;
                if (peek().is("key") || peek().is("natural") || peek().is("cross")) {
                    throw new SqlSyntaxException("NATURAL cannot be followed by " + peek().text()
                            + IqDialect.syntaxCode(), peek().pos());
                }
            }
            if (peek().is("cross")) {
                IqLexer.Token cross = take();
                if (!peek().is("join")) {
                    throw new SqlSyntaxException("CROSS needs JOIN" + IqDialect.syntaxCode(), peek().pos());
                }
                take();
                SqlAst.FromItem right = tableFactor();
                if (peek().is("on")) {
                    throw new SqlSyntaxException("a CROSS JOIN takes no ON" + IqDialect.syntaxCode(), peek().pos());
                }
                left = new SqlAst.Join(SqlAst.JoinType.CROSS, false, left, right, null, List.of(), cross.pos());
                continue;
            }
            SqlAst.JoinType type = null;
            IqLexer.Token kind = peek();
            if (kind.is("inner")) {
                take();
                type = SqlAst.JoinType.INNER;
            } else if (kind.is("left") || kind.is("right") || kind.is("full")) {
                take();
                type = kind.is("left") ? SqlAst.JoinType.LEFT : kind.is("right") ? SqlAst.JoinType.RIGHT
                        : SqlAst.JoinType.FULL;
                if (peek().is("outer")) {
                    take();
                }
            } else if (kind.is("outer")) {
                throw new SqlSyntaxException("OUTER JOIN needs LEFT, RIGHT or FULL before it" + IqDialect.syntaxCode(),
                        kind.pos());
            }
            if (!peek().is("join")) {
                if (natural || type != null) {
                    throw new SqlSyntaxException("a join needs JOIN here, not '" + peek().text() + "'"
                            + IqDialect.syntaxCode(), peek().pos());
                }
                return left;
            }
            IqLexer.Token join = take();
            SqlAst.FromItem right = tableFactor();
            if (peek().is("using")) {
                throw new SqlSyntaxException("IQ's joins have no USING: write ON" + IqDialect.syntaxCode(),
                        peek().pos());
            }
            SqlAst.Expr on = null;
            if (peek().is("on")) {
                take();
                on = condition("ON");
            }
            left = new SqlAst.Join(type == null ? SqlAst.JoinType.INNER : type, natural, left, right, on, List.of(),
                    (natural ? t : kind.is("join") ? join : kind).pos());
        }
    }

    /** A table factor (the research's GRAMMAR R4): a table, a FROM function, a derived table
     *  {@code ( query ) [AS] name [(columns)]} (its name mandatory), or a parenthesized table expression, which takes no
     *  name of its own; a comma inside the parentheses is a cross join there. */
    private SqlAst.FromItem tableFactor() {
        IqLexer.Token first = peek();
        if (!first.isOp("(")) {
            return fromItem();
        }
        SqlSyntaxException[] queryError = new SqlSyntaxException[1];
        SqlAst.Statement query = parenthesizedQuery(queryError);
        if (query != null) {
            return derivedTable(query, first);
        }
        take();
        if (peek().is("with")) {
            throw SqlSyntaxException.unsupported("WITH in a derived table is not supported yet", peek().pos());
        }
        try {
            SqlAst.FromItem inner = tableExpression();
            while (peek().isOp(",")) {
                IqLexer.Token comma = take();
                inner = new SqlAst.Join(SqlAst.JoinType.CROSS, false, inner, tableExpression(), null, List.of(),
                        comma.pos());
            }
            expectOp(")");
            IqLexer.Token after = peek();
            if (after.is("as") || after.isName() || after.kind() == IqLexer.Kind.STRING) {
                throw new SqlSyntaxException("a parenthesized join takes no correlation name" + IqDialect.syntaxCode(),
                        after.pos());
            }
            return inner;
        } catch (SqlSyntaxException e) {
            throw queryError[0] != null ? queryError[0] : e;
        }
    }

    /** {@code ( query ) [AS] name [(column, ...)]}: the name is mandatory (IQ's syntax error without it). */
    private SqlAst.FromItem derivedTable(SqlAst.Statement query, IqLexer.Token open) {
        IqLexer.Token t = peek();
        if (t.is("as")) {
            take();
            t = peek();
        }
        if (!t.isName()) {
            throw new SqlSyntaxException("a derived table needs a correlation name" + IqDialect.syntaxCode(), t.pos());
        }
        SqlAst.Ident alias = name(take());
        List<SqlAst.Ident> columns = new ArrayList<>();
        if (peek().isOp("(")) {
            take();
            do {
                IqLexer.Token c = peek();
                if (!c.isName()) {
                    throw new SqlSyntaxException("a derived table's column list holds names" + IqDialect.syntaxCode(),
                            c.pos());
                }
                columns.add(name(take()));
            } while (takeOp(","));
            expectOp(")");
        }
        noTableHints();
        return new SqlAst.SubqueryFrom(query, alias, columns, false, open.pos());
    }

    private SqlAst.FromItem fromItem() {
        IqLexer.Token first = peek();
        if (first.is("lateral") || first.isWord("apply") || first.is("openstring") || first.is("openxml")) {
            throw SqlSyntaxException.unsupported(first.text() + " in FROM is not supported yet", first.pos());
        }
        if (!first.isName()) {
            throw unexpected(first);
        }
        List<SqlAst.Ident> name = new ArrayList<>(List.of(name(take())));
        // IQ island, 2026-10-14 (M1e): T-SQL's database..table, its owner omitted (the research's GRAMMAR L15)
        boolean omitted = ownerOmitted(name);
        while (peek().isOp(".")) {
            if (omitted || peek(1).isOp(".")) {
                throw new SqlSyntaxException("'..' stands only after a name's first part" + IqDialect.syntaxCode(),
                        peek().pos());
            }
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

    /** A set operation's ORDER BY key: a position (unsigned or {@code +}-signed) or a name, which the translator
     *  reads; a parenthesized constant ({@code (1)}) is no position there but an expression, IQ's error -152 (measured),
     *  so it is kept as one ({@code +(1)}) for the dialect to reject. */
    private SqlAst.SortItem setSortItem() {
        IqLexer.Token first = peek();
        SqlAst.Expr e = expression();
        value(e, "ORDER BY");
        if (e instanceof SqlAst.Unary u && u.op() == SqlAst.UnaryOp.PLUS && u.operand() instanceof SqlAst.IntegerLit n
                && peek(-1).kind() == IqLexer.Kind.NUMBER && tokens.get(at - 2) == first) {
            e = n;
        } else if (e instanceof SqlAst.IntegerLit && first.isOp("(")) {
            e = new SqlAst.Unary(SqlAst.UnaryOp.PLUS, e, first.pos());
        }
        boolean descending = false;
        if (peek().is("desc")) {
            take();
            descending = true;
        } else if (peek().is("asc")) {
            take();
        }
        if (peek().isWord("nulls")) {
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
                // IQ island, 2026-10-14 (M1c): IQ's syntax error (measured), refused by name: the research's syntax
                // cases call the form accept in one case and reject in another (s1_lexical-1015, a1_sa_lang-690)
                throw SqlSyntaxException.unsupported("NOT NOT: IQ reads NOT (NOT x) only, IQ's syntax error, refused"
                        + " before translating" + IqDialect.syntaxCode(), peek().pos());
            }
            SqlAst.Expr operand = negation();
            condition(operand, "NOT");
            return new SqlAst.Unary(SqlAst.UnaryOp.NOT, operand, not.pos());
        }
        return predicate();
    }

    /** A predicate (the research's GRAMMAR C4-C17): a value, or a value and one predicate operator -- a comparison,
     *  {@code IS [NOT] NULL}, {@code IS [NOT] DISTINCT FROM}, {@code [NOT] BETWEEN}, {@code [NOT] LIKE},
     *  {@code [NOT] IN (list)} -- then, after a predicate or a parenthesized condition, at most one truth test
     *  {@code IS [NOT] TRUE | FALSE | UNKNOWN} (a truth test of a bare value, or a second one, is IQ's syntax error,
     *  measured on SQL Anywhere 17). */
    private SqlAst.Expr predicate() {
        SqlAst.Expr left = concatenation();
        SqlAst.Expr operated = predicateOperator(left);
        SqlAst.Expr result = operated != null ? operated : left;
        IqLexer.Token after = peek();
        if (operated != null && after.kind() == IqLexer.Kind.OP && (COMPARISONS.contains(after.text())
                || after.isOp("*=") || after.isOp("=*"))) {
            throw new SqlSyntaxException("comparisons do not chain" + IqDialect.syntaxCode(), after.pos());
        }
        if (after.is("is") && isCondition(result)) {
            IqLexer.Token is = take();
            boolean negated = false;
            if (peek().is("not")) {
                take();
                negated = true;
            }
            SqlAst.Truth truth = truthValue(peek());
            if (truth == null) {
                // a condition IS NULL, IS DISTINCT FROM, ...: IQ tests a value there, never a condition
                throw new SqlSyntaxException("IS after a condition is a truth test: TRUE, FALSE or UNKNOWN, not '"
                        + peek().text() + "'" + IqDialect.syntaxCode(), peek().pos());
            }
            take();
            result = new SqlAst.IsTruth(result, truth, negated, is.pos());
            if (peek().is("is")) {
                throw new SqlSyntaxException("a truth test takes no second IS" + IqDialect.syntaxCode(),
                        peek().pos());
            }
        }
        return result;
    }

    /** The truth value a truth test names ({@code TRUE}, {@code FALSE}, {@code UNKNOWN}), or null for none. */
    private static SqlAst.@com.legend.base.Nullable Truth truthValue(IqLexer.Token t) {
        return t.isWord("true") ? SqlAst.Truth.TRUE : t.isWord("false") ? SqlAst.Truth.FALSE
                : t.isWord("unknown") ? SqlAst.Truth.UNKNOWN : null;
    }

    /** The predicate operator after {@code left} and its operands, or null when none follows (or only a truth test,
     *  which {@link #predicate} reads). */
    private SqlAst.@com.legend.base.Nullable Expr predicateOperator(SqlAst.Expr left) {
        IqLexer.Token t = peek();
        if (t.kind() == IqLexer.Kind.OP && COMPARISONS.contains(t.text())) {
            take();
            value(left, "a comparison");
            if ((peek().is("any") || peek().is("some") || peek().is("all")) && peek(1).isOp("(")) {
                // x op ANY | SOME | ALL (query) (the research's GRAMMAR C8): a subquery, never a list
                boolean all = take().is("all");
                take();
                SqlAst.Statement query = subquery("ANY, SOME and ALL");
                return new SqlAst.Quantified(comparison(t.text()), all, left, query, t.pos());
            }
            SqlAst.Expr right = concatenation();
            value(right, "a comparison");
            return new SqlAst.Binary(comparison(t.text()), left, right, t.pos());
        }
        if (t.isOp("*=") || t.isOp("=*")) {
            throw SqlSyntaxException.unsupported("the T-SQL outer join operators (*=, =*) are not supported yet",
                    t.pos());
        }
        if (t.is("is")) {
            boolean negated = peek(1).is("not");
            IqLexer.Token what = peek(negated ? 2 : 1);
            if (truthValue(what) != null) {
                // a truth test: predicate() reads it after a condition, and refuses it after a value
                if (!isCondition(left)) {
                    throw new SqlSyntaxException("IS " + what.text().toUpperCase(java.util.Locale.ROOT) + " tests a"
                            + " condition, not a value" + IqDialect.syntaxCode(), what.pos());
                }
                return null;
            }
            take();
            if (negated) {
                take();
            }
            if (what.is("null")) {
                take();
                value(left, "IS NULL");
                return new SqlAst.IsNull(left, negated, t.pos());
            }
            if (what.is("distinct")) {
                take();
                if (!peek().is("from")) {
                    throw new SqlSyntaxException("IS DISTINCT needs FROM" + IqDialect.syntaxCode(), peek().pos());
                }
                take();
                SqlAst.Expr right = concatenation();
                value(left, "IS DISTINCT FROM");
                value(right, "IS DISTINCT FROM");
                return new SqlAst.IsDistinct(left, right, negated, t.pos());
            }
            throw SqlSyntaxException.unsupported("IS " + (negated ? "NOT " : "") + what.text()
                    + " is not supported yet", what.pos());
        }
        boolean negated = t.is("not");
        IqLexer.Token predicate = negated ? peek(1) : t;
        if (predicate.is("between")) {
            take();
            if (negated) {
                take();
            }
            value(left, "BETWEEN");
            if (peek().isWord("symmetric") || peek().isWord("asymmetric")) {
                throw new SqlSyntaxException("IQ's BETWEEN has no " + peek().text().toUpperCase(java.util.Locale.ROOT)
                        + IqDialect.syntaxCode(), peek().pos());
            }
            SqlAst.Expr low = concatenation();
            value(low, "BETWEEN");
            if (!peek().is("and")) {
                throw new SqlSyntaxException("BETWEEN needs AND" + IqDialect.syntaxCode(), peek().pos());
            }
            take();
            SqlAst.Expr high = concatenation();
            value(high, "BETWEEN");
            return new SqlAst.Between(left, low, high, negated, predicate.pos());
        }
        if (predicate.is("like")) {
            take();
            if (negated) {
                take();
            }
            value(left, "LIKE");
            SqlAst.Expr pattern = concatenation();
            value(pattern, "LIKE");
            SqlAst.Expr escape = null;
            if (peek().is("escape")) {
                take();
                escape = concatenation();
                value(escape, "ESCAPE");
            }
            return new SqlAst.Like(left, pattern, escape, negated, false, predicate.pos());
        }
        if (predicate.is("in")) {
            take();
            if (negated) {
                take();
            }
            value(left, "IN");
            if (!peek().isOp("(")) {
                throw new SqlSyntaxException("IN needs a parenthesized list" + IqDialect.syntaxCode(), peek().pos());
            }
            if (peek(1).is("select") || peek(1).is("with")) {
                take();
                SqlAst.Statement query = subquery("IN");
                return new SqlAst.InSubquery(left, query, negated, predicate.pos());
            }
            SqlSyntaxException[] queryError = new SqlSyntaxException[1];
            SqlAst.Statement query = parenthesizedQuery(queryError);
            if (query != null) {
                // IN ((query) UNION (query)): the parenthesis is the subquery's
                return new SqlAst.InSubquery(left, query, negated, predicate.pos());
            }
            take();
            if (peek().isOp(")")) {
                throw new SqlSyntaxException("IN needs at least one value" + IqDialect.syntaxCode(), peek().pos());
            }
            List<SqlAst.Expr> values = new ArrayList<>();
            do {
                SqlAst.Expr v = expression();
                value(v, "an IN list");
                values.add(v);
            } while (takeOp(","));
            expectOp(")");
            return new SqlAst.InList(left, values, negated, predicate.pos());
        }
        if (predicate.isWord("similar") || predicate.isWord("regexp") || predicate.is("contains")) {
            throw SqlSyntaxException.unsupported(predicate.text().toUpperCase(java.util.Locale.ROOT)
                    + " is not supported yet", predicate.pos());
        }
        return null;
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
                if (t.is("exists") && peek(1).isOp("(")) {
                    // EXISTS (query): a condition (the research's GRAMMAR C15), never a value
                    take();
                    take();
                    return new SqlAst.Exists(subquery("EXISTS"), t.pos());
                }
                // IQ island, 2026-10-14 (M1d): CAST, CONVERT, the special values, the calls a reserved word heads
                SqlAst.Expr special = special(t);
                if (special != null) {
                    return special;
                }
                throw SqlSyntaxException.unsupported("'" + t.text() + "' here (" + keywordForm(t)
                        + ") is not supported yet", t.pos());
            }
            case OP -> {
                if (t.isOp("(")) {
                    if (peek(1).is("with")) {
                        throw new SqlSyntaxException("a subquery cannot begin with WITH" + IqDialect.syntaxCode(),
                                peek(1).pos());
                    }
                    SqlSyntaxException[] queryError = new SqlSyntaxException[1];
                    SqlAst.Statement query = parenthesizedQuery(queryError);
                    if (query != null) {
                        // (query) as a value: its one column of its one row (the research's GRAMMAR E15)
                        return new SqlAst.ScalarSubquery(query, t.pos());
                    }
                    take();
                    try {
                        SqlAst.Expr inner = expression();
                        expectOp(")");
                        parens.merge(inner, 1, Integer::sum);
                        return inner;
                    } catch (SqlSyntaxException e) {
                        throw queryError[0] != null ? queryError[0] : e;
                    }
                }
                // IQ island, 2026-10-14 (M1e): a host variable, :name (the research's GRAMMAR L28)
                if (t.isOp(":")) {
                    return hostVariable();
                }
                if (t.isOp("?")) {
                    throw SqlSyntaxException.unsupported("a positional parameter (?) is not supported: it names no"
                            + " variable of the enclosing Pure query to read (an #IQ{...}# island reads :name and @name"
                            + " as one)", t.pos());
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

    /** A subquery's {@code query_block )} after its opening parenthesis ({@code what} names the construct): WITH there
     *  is IQ's syntax error (the research's GRAMMAR Q9). */
    private SqlAst.Statement subquery(String what) {
        if (peek().is("with")) {
            throw new SqlSyntaxException("the subquery of " + what + " cannot begin with WITH" + IqDialect.syntaxCode(),
                    peek().pos());
        }
        SqlAst.Statement query = queryBlock();
        expectOp(")");
        return query;
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
        if (b != null && !nullValue(b)) {
            whens.add(new SqlAst.When(new SqlAst.Unary(SqlAst.UnaryOp.NOT, c, c.pos()), b));
        }
        // IQ island, 2026-10-14 (review 3's M5): ELSE NULL is no ELSE (the same value); (review 4's m2) a NULL of a
        // type -- CAST(NULL AS VARCHAR(3)) -- is the CASE's ELSE, the same value, its type kept for the CASE's
        SqlAst.Case result = new SqlAst.Case(null, whens, b != null && nullValue(b) && !(b instanceof SqlAst.NullLit)
                ? b : null, t.pos());
        if (b != null && nullValue(b)) {
            droppedElse.put(result, b);
        }
        ifs.add(result);
        return result;
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
        // IQ island, 2026-10-14 (review 3's M5): ELSE NULL, and then each last WHEN whose value is NULL and whose
        // condition cannot raise, are no branches (the same value); one WHEN stays. (Review 4's m2) a NULL of a type
        // (CAST(NULL AS VARCHAR(3))) as well; the last such typed NULL is the CASE's ELSE then (the same value), so
        // that its type is the CASE's still -- two of different types are left as written
        SqlAst.Expr nullElse = otherwise != null && nullValue(otherwise) ? otherwise : null;
        List<SqlAst.When> dropped = new ArrayList<>();
        if (otherwise == null || nullElse != null) {
            while (whens.size() > 1 && nullValue(whens.get(whens.size() - 1).result())
                    && cannotRaise(whens.get(whens.size() - 1).condition())) {
                dropped.add(0, whens.remove(whens.size() - 1));
            }
        }
        List<SqlAst.Expr> typed = new ArrayList<>();
        dropped.forEach(w -> typed.add(w.result()));
        if (nullElse != null) {
            typed.add(nullElse);
        }
        typed.removeIf(x -> x instanceof SqlAst.NullLit);
        if (typed.stream().map(IqParser::nullType).distinct().count() > 1) {
            whens.addAll(dropped);
            dropped.clear();
            nullElse = null;
        }
        SqlAst.Expr newElse = nullElse == null && dropped.isEmpty() ? otherwise
                : typed.isEmpty() ? null : typed.get(typed.size() - 1);
        SqlAst.Case result = new SqlAst.Case(null, whens, newElse, t.pos());
        if (newElse != otherwise) {
            // the ELSE as written (none: null), for the item's name
            droppedElse.put(result, otherwise);
        }
        if (!dropped.isEmpty()) {
            droppedWhens.put(result, dropped);
        }
        if (operand != null) {
            simpleCases.add(result);
        }
        return result;
    }

    /** Whether {@code e} is a NULL of any type: NULL, or a CAST (CONVERT) of one (IQ island, 2026-10-14, review 4's
     *  m2). */
    static boolean nullValue(SqlAst.Expr e) {
        return e instanceof SqlAst.NullLit || e instanceof SqlAst.Cast c && nullValue(c.operand());
    }

    /** A typed NULL's types, outermost first, as written ({@link #nullValue}). */
    private static String nullType(SqlAst.Expr e) {
        StringBuilder b = new StringBuilder();
        for (SqlAst.Expr x = e; x instanceof SqlAst.Cast c; x = c.operand()) {
            b.append(IqLexer.lower(c.type().name())).append(c.type().modifiers()).append(';');
        }
        return b.toString();
    }

    /** Whether evaluating {@code e} cannot raise an error: columns, constants, IS NULL and truth tests and the logic of
     *  them -- no comparison (IQ may convert its sides), arithmetic, call, conversion or subquery. */
    private static boolean cannotRaise(SqlAst.Expr e) {
        boolean leaf = e instanceof SqlAst.ColumnRef || e instanceof SqlAst.IntegerLit || e instanceof SqlAst.NumericLit
                || e instanceof SqlAst.StringLit || e instanceof SqlAst.NullLit;
        if (leaf) {
            return true;
        }
        boolean logic = switch (e) {
            case SqlAst.Binary b -> b.op() == SqlAst.BinaryOp.AND || b.op() == SqlAst.BinaryOp.OR;
            case SqlAst.Unary u -> u.op() == SqlAst.UnaryOp.NOT;
            case SqlAst.IsNull n -> true;
            case SqlAst.IsTruth n -> true;
            default -> false;
        };
        return logic && IqDialect.children(e).stream().allMatch(IqParser::cannotRaise);
    }

    /** A column reference: one name, or names joined by dots (a function call refused by name). IQ island,
     *  2026-10-14 (M1e): a lone {@code @name} is a named parameter ({@link #parameter}): a variable, unless a column
     *  of its name is in scope (the research's GRAMMAR L27: a column before a variable), which the dialect decides. */
    private SqlAst.Expr reference() {
        IqLexer.Token first = peek();
        if (first.kind() == IqLexer.Kind.WORD && first.text().startsWith("@@")) {
            throw SqlSyntaxException.unsupported("global variables (" + first.text() + ") are not supported yet",
                    first.pos());
        }
        if (first.kind() == IqLexer.Kind.WORD && first.text().startsWith("@") && first.text().length() > 1
                && !peek(1).isOp(".") && !peek(1).isOp("(")) {
            take();
            return parameter(first.text(), first);
        }
        List<SqlAst.Ident> parts = new ArrayList<>(List.of(name(take())));
        // IQ island, 2026-10-14 (M1e): database..table.column (the research's GRAMMAR L16)
        ownerOmitted(parts);
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
        String lower = IqLexer.lower(name);
        if (READ_AGGREGATES.contains(lower)) {
            return aggregate(parts, first, lower);
        }
        if (STATISTICS.contains(lower)) {
            throw SqlSyntaxException.unsupported(name + "(...) is not supported: SQL Anywhere computes it by a formula"
                    + " that loses precision for large values, which lite's databases do not reproduce", first.pos());
        }
        if (AGGREGATES.contains(lower)) {
            throw SqlSyntaxException.unsupported("the aggregate or OLAP function " + name + "(...) is not supported"
                    + " yet", first.pos());
        }
        take();
        // IQ island, 2026-10-14 (M1d): f(*) is f() for a function that has a form without arguments (GETDATE(*),
        // NOW(*), TODAY(*), PI(*): the research's GRAMMAR F4)
        boolean empty = peek().isOp("*") && peek(1).isOp(")") && STAR_CALLS.contains(IqLexer.lower(name));
        if (empty) {
            take();
        } else if (peek().is("distinct") || peek().is("all") || peek().isOp("*")) {
            throw SqlSyntaxException.unsupported("'" + peek().text() + "' in a call (an aggregate's form) is not"
                    + " supported yet", peek().pos());
        }
        List<SqlAst.Expr> args = new ArrayList<>();
        if (!empty && DATE_PART_CALLS.contains(IqLexer.lower(name)) && !peek().isOp(")")) {
            // IQ island, 2026-10-14 (the third landing, measured): IQ counts the arguments before it reads the part
            // (DATEDIFF(x, y) is its -154, not a part's syntax error)
            Integer wanted = PART_CALL_ARGUMENTS.get(IqLexer.lower(name));
            if (wanted != null && arguments() != wanted) {
                throw new SqlSyntaxException("wrong number of arguments to " + name.toUpperCase(
                        java.util.Locale.ROOT) + IqDialect.code(-154, "37505"), first.pos());
            }
            // the date part is a keyword of its own, not an expression (GRAMMAR F19, F43); TZOFFSET only in DATEPART
            IqLexer.Token partToken = peek();
            SqlAst.Expr part = datePart();
            if (((SqlAst.StringLit) part).value().equals("tzoffset") && (IqLexer.lower(name).equals("dateadd")
                    || IqLexer.lower(name).equals("datediff"))) {
                throw new SqlSyntaxException("TZOFFSET is no part " + name + " takes" + IqDialect.syntaxCode(),
                        partToken.pos());
            }
            args.add(part);
            if (!takeOp(",")) {
                expectOp(")");
                return new SqlAst.FunctionCall(parts, args, false, false, first.pos());
            }
        }
        if (!empty && !peek().isOp(")")) {
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

    /**
     * An aggregate's call ({@link #READ_AGGREGATES}; the research's GRAMMAR F7, F10, F42): {@code name([ALL | DISTINCT]
     * value)}, COUNT's {@code (*)} or {@code ()} (COUNT(*) both), LIST's {@code (value [, delimiter] [ORDER BY key,
     * ...])}, an ORDER BY in any of them (the dialect refuses it outside LIST, as IQ does after parsing). The call's
     * text is kept. A window ({@code OVER}) is refused by name; {@code DISTINCT *} and NULLS FIRST/LAST are IQ's syntax
     * errors. The arguments are counted by the dialect (IQ's -154 is raised after parsing).
     */
    private SqlAst.Expr aggregate(List<SqlAst.Ident> parts, IqLexer.Token first, String lower) {
        int start = at;
        take();
        boolean distinct = false;
        boolean star = false;
        List<SqlAst.Expr> args = new ArrayList<>();
        List<SqlAst.SortItem> orderBy = new ArrayList<>();
        if (peek().isOp("*") || peek().isOp(")")) {
            star = lower.equals("count");
            if (peek().isOp("*")) {
                take();
            }
        } else {
            if (peek().is("distinct")) {
                take();
                distinct = true;
                if (peek().isOp("*")) {
                    throw new SqlSyntaxException("DISTINCT * in an aggregate" + IqDialect.syntaxCode(), peek().pos());
                }
            } else if (peek().is("all")) {
                take();
            }
            do {
                noNamedArgument();
                SqlAst.Expr a = expression();
                value(a, "an aggregate's argument");
                args.add(a);
            } while (takeOp(","));
            if (peek().is("order")) {
                take();
                expectKeyword("by");
                do {
                    IqLexer.Token key = peek();
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
                        throw new SqlSyntaxException("IQ has no NULLS FIRST or NULLS LAST" + IqDialect.syntaxCode(),
                                peek().pos());
                    }
                    orderBy.add(new SqlAst.SortItem(e, descending, null, key.pos()));
                } while (takeOp(","));
            }
        }
        expectOp(")");
        if (peek().is("over") || peek().is("within")) {
            throw SqlSyntaxException.unsupported(peek().text().toUpperCase(java.util.Locale.ROOT) + " after a call is"
                    + " not supported yet", peek().pos());
        }
        SqlAst.FunctionCall call = new SqlAst.FunctionCall(parts, args, distinct, star, orderBy, List.of(), null,
                first.pos());
        spans.put(call, text.substring(tokens.get(start - 1).start(), tokens.get(at - 1).end()));
        return call;
    }

    // ------------------------------------------------------------------ M1d: CAST, CONVERT and the special forms

    /**
     * IQ island, 2026-10-14 (M1d). The value a reserved word {@code t} heads, of the forms the function slice reads
     * (the research's GRAMMAR F14, F15, L26): {@code CAST(x AS type)}; {@code CONVERT(type, x)} (with a style refused
     * by name: the styles' formats are a later slice's); {@code CURRENT DATE}, {@code CURRENT TIMESTAMP} and
     * {@code CURRENT_TIMESTAMP} (no parentheses: IQ's syntax error), calls without arguments of the catalog's
     * names {@code CURRENT DATE} and {@code CURRENT TIMESTAMP}; and the calls a reserved word heads, {@code CHAR(n)},
     * {@code LEFT(s, n)}, {@code RIGHT(s, n)}, {@code DATE(x)}. Null for any other.
     */
    private SqlAst.@com.legend.base.Nullable Expr special(IqLexer.Token t) {
        if (t.is("cast") && peek(1).isOp("(")) {
            return castCall();
        }
        if (t.is("convert") && peek(1).isOp("(")) {
            return convertCall();
        }
        if (t.is("current")) {
            IqLexer.Token next = peek(1);
            if (next.isWord("date") || next.isWord("timestamp")) {
                take();
                take();
                String value = "CURRENT " + next.text().toUpperCase(java.util.Locale.ROOT);
                return new SqlAst.FunctionCall(List.of(new SqlAst.Ident(value, false, t.pos())), List.of(), false,
                        false, t.pos());
            }
            if (next.isWord("time")) {
                throw SqlSyntaxException.unsupported("CURRENT TIME (a TIME) is not supported: the #IQ{...}# island"
                        + " reads no TIME values", t.pos());
            }
            return null;
        }
        if (t.is("current_timestamp")) {
            if (peek(1).isOp("(")) {
                throw new SqlSyntaxException("CURRENT_TIMESTAMP takes no parentheses" + IqDialect.syntaxCode(),
                        peek(1).pos());
            }
            take();
            return new SqlAst.FunctionCall(List.of(new SqlAst.Ident("CURRENT TIMESTAMP", false, t.pos())), List.of(),
                    false, false, t.pos());
        }
        if ((t.is("char") || t.is("left") || t.is("right") || t.is("date")) && peek(1).isOp("(")) {
            take();
            return call(List.of(new SqlAst.Ident(t.text(), false, t.pos())), t);
        }
        return null;
    }

    /** {@code CAST(expression AS data_type)} (GRAMMAR F14). */
    private SqlAst.Expr castCall() {
        IqLexer.Token t = take();
        expectOp("(");
        SqlAst.Expr operand = expression();
        value(operand, "CAST");
        expectSyntax("as", "CAST needs AS");
        SqlAst.TypeName type = dataType();
        expectOp(")");
        IqFunctions.constantCast(operand, type, "CAST", settings);
        return new SqlAst.Cast(operand, type, t.pos());
    }

    /** {@code CONVERT(data_type, expression [, style])} (GRAMMAR F15): without a style, a {@code Cast} whose type name
     *  begins with {@link #CONVERT}; a style is refused by name, after the call reads. */
    private SqlAst.Expr convertCall() {
        IqLexer.Token t = take();
        expectOp("(");
        SqlAst.TypeName type = dataType();
        if (!peek().isOp(",")) {
            throw new SqlSyntaxException("CONVERT needs a value after its type" + IqDialect.syntaxCode(),
                    peek().pos());
        }
        take();
        SqlAst.Expr operand = expression();
        value(operand, "CONVERT");
        if (takeOp(",")) {
            if (peek().is("default")) {
                throw new SqlSyntaxException("CONVERT's style cannot be DEFAULT" + IqDialect.syntaxCode(),
                        peek().pos());
            }
            SqlAst.Expr style = expression();
            value(style, "CONVERT's style");
            expectOp(")");
            throw SqlSyntaxException.unsupported("CONVERT with a style is not supported yet: IQ's style formats are a"
                    + " later slice's", style.pos());
        }
        expectOp(")");
        IqFunctions.constantCast(operand, type, "CONVERT", settings);
        return new SqlAst.Cast(operand, new SqlAst.TypeName(CONVERT + type.name(), type.modifiers(), type.pos()),
                t.pos());
    }

    /**
     * A data type (GRAMMAR T1-T11), as the name the dialect reads and its sizes: {@code CHAR}, {@code VARCHAR} (with a
     * length, and 1 after it for a CHAR length unit), {@code LONG VARCHAR} ({@code TEXT}), {@code SMALLINT},
     * {@code INTEGER}, {@code BIGINT}, {@code TINYINT} and their {@code UNSIGNED} forms, {@code NUMERIC} (DECIMAL,
     * DEC; MONEY and SMALLMONEY as their NUMERICs), {@code DOUBLE}, {@code REAL} (FLOAT up to 24 bits, or without a
     * precision), {@code DATE}, {@code TIME}, {@code TIMESTAMP} (DATETIME, SMALLDATETIME), {@code BIT}, and the
     * binary, national and other types by their own names (refused when translated). A size outside its type's range
     * is IQ's syntax error; any other name (a domain) is refused by name.
     */
    private SqlAst.TypeName dataType() {
        IqLexer.Token first = peek();
        if (first.kind() != IqLexer.Kind.KEYWORD && first.kind() != IqLexer.Kind.WORD) {
            throw new SqlSyntaxException("a data type is expected here, not '" + first.text() + "'"
                    + IqDialect.syntaxCode(), first.pos());
        }
        take();
        String w = IqLexer.lower(first.text());
        SqlAst.Pos pos = first.pos();
        switch (w.toUpperCase(java.util.Locale.ROOT)) {
            case "CHAR", "CHARACTER" -> {
                boolean varying = peek().isWord("varying");
                if (varying) {
                    take();
                }
                return new SqlAst.TypeName(varying ? "VARCHAR" : "CHAR", charLength(), pos);
            }
            case "VARCHAR" -> {
                return new SqlAst.TypeName("VARCHAR", charLength(), pos);
            }
            case "LONG" -> {
                IqLexer.Token next = peek();
                if (next.isWord("varchar")) {
                    take();
                    return new SqlAst.TypeName("LONG VARCHAR", List.of(), pos);
                }
                if (next.isWord("binary") || next.isWord("nvarchar") || next.isWord("varbit")) {
                    take();
                    throw notRead("LONG " + next.text().toUpperCase(java.util.Locale.ROOT), pos);
                }
                throw new SqlSyntaxException("LONG needs VARCHAR, BINARY, NVARCHAR or VARBIT" + IqDialect.syntaxCode(),
                        next.pos());
            }
            case "TEXT" -> {
                return new SqlAst.TypeName("LONG VARCHAR", List.of(), pos);
            }
            case "INT", "INTEGER", "SMALLINT", "BIGINT", "TINYINT" -> {
                String name = w.equals("int") ? "INTEGER" : w.toUpperCase(java.util.Locale.ROOT);
                if (peek().is("unsigned")) {
                    take();
                    throw notRead("UNSIGNED " + name, pos);
                }
                if (name.equals("TINYINT")) {
                    throw notRead(name, pos);
                }
                return new SqlAst.TypeName(name, List.of(), pos);
            }
            case "UNSIGNED" -> {
                IqLexer.Token next = peek();
                String n = IqLexer.lower(next.text());
                if (next.kind() == IqLexer.Kind.KEYWORD && Set.of("int", "integer", "smallint", "bigint", "tinyint")
                        .contains(n)) {
                    take();
                    throw notRead("UNSIGNED " + n.toUpperCase(java.util.Locale.ROOT), pos);
                }
                throw new SqlSyntaxException("UNSIGNED needs an integer type" + IqDialect.syntaxCode(), next.pos());
            }
            case "DECIMAL", "DEC", "NUMERIC" -> {
                List<Integer> sizes = new ArrayList<>();
                if (takeOp("(")) {
                    int precision = typeSize(1, 127, "a NUMERIC's precision");
                    sizes.add(precision);
                    if (takeOp(",")) {
                        sizes.add(typeSize(0, precision, "a NUMERIC's scale"));
                    }
                    expectOp(")");
                }
                return new SqlAst.TypeName("NUMERIC", sizes, pos);
            }
            case "MONEY", "SMALLMONEY" -> {
                return new SqlAst.TypeName("NUMERIC", w.equals("money") ? List.of(19, 4) : List.of(10, 4), pos);
            }
            case "DOUBLE" -> {
                if (peek().is("precision")) {
                    take();
                }
                return new SqlAst.TypeName("DOUBLE", List.of(), pos);
            }
            case "FLOAT" -> {
                if (takeOp("(")) {
                    // SQL Anywhere takes FLOAT(0) and FLOAT(200) too (GRAMMAR T6): a precision outside 1 .. 53 is
                    // refused, not an error
                    int bits = typeSize(0, 999999, "a FLOAT's precision");
                    expectOp(")");
                    if (bits >= 25 && bits <= 53) {
                        return new SqlAst.TypeName("DOUBLE", List.of(), pos);
                    }
                }
                throw notRead("REAL", pos);
            }
            case "DATE", "BIT" -> {
                return new SqlAst.TypeName(w.toUpperCase(java.util.Locale.ROOT), List.of(), pos);
            }
            case "REAL", "TIME", "UNIQUEIDENTIFIER", "XML", "VARBIT", "UNIQUEIDENTIFIERSTR" -> {
                throw notRead(w.toUpperCase(java.util.Locale.ROOT), pos);
            }
            case "TIMESTAMP", "DATETIME", "SMALLDATETIME" -> {
                if (peek().is("with")) {
                    throw SqlSyntaxException.unsupported("TIMESTAMP WITH TIME ZONE is not supported: IQ has no time"
                            + " zones", peek().pos());
                }
                return new SqlAst.TypeName("TIMESTAMP", List.of(), pos);
            }
            case "BINARY", "VARBINARY", "NCHAR", "NVARCHAR" -> {
                // a national type takes no length unit (GRAMMAR T4: IQ's syntax error)
                List<Integer> length = charLength();
                if (length.size() > 1 && (w.equals("nchar") || w.equals("nvarchar"))) {
                    throw new SqlSyntaxException("a national character type takes no length unit"
                            + IqDialect.syntaxCode(), pos);
                }
                throw notRead(w.toUpperCase(java.util.Locale.ROOT), pos);
            }
            default -> throw SqlSyntaxException.unsupported("the data type '" + first.text() + "' is not supported"
                    + " (a user-defined domain, or a type the #IQ{...}# island does not read)", pos);
        }
    }

    /** A data type the island reads no value of (a single-precision float, an unsigned or TINYINT integer, TIME, a binary
     *  or national type): refused by name. */
    private static SqlSyntaxException notRead(String type, SqlAst.Pos pos) {
        return SqlSyntaxException.unsupported("the data type " + type + " is not supported yet: the #IQ{...}# island"
                + " reads no value of it", pos);
    }

    /** A character type's optional {@code (length [CHAR | CHARACTER | BYTE])}: the length, then 1 for a CHAR unit. */
    private List<Integer> charLength() {
        if (!takeOp("(")) {
            return List.of();
        }
        int length = typeSize(1, 32767, "a length");
        boolean chars = false;
        if (peek().is("char") || peek().is("character")) {
            take();
            chars = true;
        } else if (peek().isWord("byte")) {
            take();
        }
        expectOp(")");
        return chars ? List.of(length, 1) : List.of(length);
    }

    /** An unsigned integer literal from {@code min} to {@code max}: anything else is IQ's syntax error. */
    private int typeSize(int min, int max, String what) {
        IqLexer.Token t = peek();
        boolean digits = t.kind() == IqLexer.Kind.NUMBER && !t.text().isEmpty();
        for (int i = 0; i < t.text().length() && digits; i++) {
            digits = t.text().charAt(i) >= '0' && t.text().charAt(i) <= '9';
        }
        if (!digits || t.text().length() > 6 || Integer.parseInt(t.text()) < min || Integer.parseInt(t.text()) > max) {
            throw new SqlSyntaxException(what + " is an integer from " + min + " to " + max + ", not '" + t.text()
                    + "'" + IqDialect.syntaxCode(), t.pos());
        }
        take();
        return Integer.parseInt(t.text());
    }

    /** How many arguments the call whose parenthesis was just read has: its commas outside inner parentheses, plus
     *  one (the tokens are not consumed). */
    private int arguments() {
        int depth = 0;
        int commas = 0;
        for (int i = at; i < tokens.size() && tokens.get(i).kind() != IqLexer.Kind.EOF; i++) {
            IqLexer.Token t = tokens.get(i);
            if (t.isOp("(")) {
                depth++;
            } else if (t.isOp(")")) {
                if (depth == 0) {
                    return commas + 1;
                }
                depth--;
            } else if (t.isOp(",") && depth == 0) {
                commas++;
            }
        }
        return commas + 1;
    }

    /** A date part (GRAMMAR F20, F43): a name, plain or delimited, of IQ's parts or their abbreviations, as the
     *  constant string of the part's full name; a string or any other name is IQ's syntax error. */
    private SqlAst.Expr datePart() {
        IqLexer.Token t = peek();
        String full = t.kind() == IqLexer.Kind.WORD || t.kind() == IqLexer.Kind.QUOTED
                || t.kind() == IqLexer.Kind.KEYWORD ? DATE_PARTS.get(IqLexer.lower(t.text())) : null;
        if (full == null) {
            throw new SqlSyntaxException("'" + t.text() + "' is no date part" + IqDialect.syntaxCode(), t.pos());
        }
        take();
        SqlAst.StringLit part = new SqlAst.StringLit(full, t.pos());
        partWords.put(part, t.kind() == IqLexer.Kind.QUOTED ? "" : IqLexer.lower(t.text()));
        return part;
    }

    private static java.util.Map<String, String> datePartNames() {
        java.util.Map<String, String> m = new java.util.HashMap<>();
        String[][] parts = {{"year", "yy"}, {"quarter", "qq"}, {"month", "mm"}, {"week", "wk"}, {"day", "dd"},
                {"dayofyear", "dy"}, {"weekday", "dw"}, {"hour", "hh"}, {"minute", "mi"}, {"second", "ss"},
                {"millisecond", "ms"}, {"microsecond", "mcs", "us"}, {"calyearofweek", "cyr"},
                {"calweekofyear", "cwk"}, {"caldayofweek", "cdw"}, {"tzoffset", "tz"}};
        for (String[] p : parts) {
            for (String spelling : p) {
                m.put(spelling, p[0]);
            }
        }
        return java.util.Collections.unmodifiableMap(m);
    }

    /** IQ island, 2026-10-14 (M1e): after a name's first part, {@code ..} and a name -- T-SQL's database..table, its
     *  owner omitted -- read into {@code parts} with an empty name for the owner; whether it was. */
    private boolean ownerOmitted(List<SqlAst.Ident> parts) {
        if (!peek().isOp(".") || !peek(1).isOp(".") || peek(1).start() != peek().end()) {
            return false;
        }
        take();
        IqLexer.Token second = take();
        parts.add(new SqlAst.Ident("", false, second.pos()));
        parts.add(namePart());
        return true;
    }

    /** IQ island, 2026-10-14 (M1e): {@code :name}, a host variable -- a regular identifier right after the colon --
     *  as a named parameter ({@link #parameter}). IQ's syntax errors (the research's GRAMMAR L28, L31, measured): a
     *  space after the colon, {@code ::a}, {@code :1}, a delimited name, an indicator variable ({@code :a:b}).
     *  {@code :?} (positional) and {@code :a.b} (a part of one) are refused by name. */
    private SqlAst.Expr hostVariable() {
        IqLexer.Token colon = take();
        IqLexer.Token t = peek();
        if (t.isOp("?") && t.start() == colon.end()) {
            throw SqlSyntaxException.unsupported("a positional parameter (:?) is not supported: it names no variable of"
                    + " the enclosing Pure query to read", colon.pos());
        }
        if (t.kind() != IqLexer.Kind.WORD || t.start() != colon.end() || t.text().startsWith("@")) {
            throw new SqlSyntaxException("a host variable is a colon and a name, with nothing between"
                    + IqDialect.syntaxCode(), colon.pos());
        }
        take();
        if (peek().isOp(":") && peek().start() == t.end()) {
            throw new SqlSyntaxException("an indicator variable (:a:b) is Embedded SQL's, which IQ does not read"
                    + IqDialect.syntaxCode(), peek().pos());
        }
        if (peek().isOp(".")) {
            throw SqlSyntaxException.unsupported("a part of a host variable (:" + t.text() + ".name) is not supported",
                    peek().pos());
        }
        return parameter(":" + t.text(), colon);
    }

    /** IQ island, 2026-10-14 (M1e): a named parameter, {@code @name} or {@code :name} as written: one index per name
     *  as IQ matches names (ASCII case-insensitively), so that two references to one variable are one expression. */
    private SqlAst.Param parameter(String written, IqLexer.Token at) {
        String key = IqLexer.lower(written.substring(1));
        Integer index = parameters.get(key);
        if (index == null) {
            index = parameters.size() + 1;
            parameters.put(key, index);
        }
        return new SqlAst.Param(index, written, at.pos());
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
            case SqlAst.IsTruth n -> true;
            case SqlAst.IsDistinct n -> true;
            case SqlAst.Between n -> true;
            case SqlAst.Like n -> true;
            case SqlAst.InList n -> true;
            case SqlAst.Exists n -> true;
            case SqlAst.InSubquery n -> true;
            case SqlAst.Quantified n -> true;
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
        if (t.is("into")) {
            // IQ island, 2026-10-14 (the third landing): INTO after FROM (IQ reads it before FROM only: SQL Anywhere's
            // syntax error -131, measured) is the same statement, refused as INTO is where IQ reads it
            return SqlSyntaxException.unsupported("INTO (a query that stores its rows) is not supported in an"
                    + " #IQ{...}# island: it is a value, not a statement (and IQ reads INTO before FROM only)",
                    t.pos());
        }
        return SqlSyntaxException.unsupported("'" + t.text() + "' here is not supported", t.pos());
    }
}
