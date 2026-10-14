// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.protocol.sql.iq;

import com.legend.protocol.sql.SqlAst;
import com.legend.protocol.sql.SqlSyntaxException;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/**
 * The name IQ gives a select item written without an alias: SQL Anywhere's own rendering of the expression (IQ's front
 * end names a result column; measured on SQL Anywhere 17, docs/IQ_SQL_STUDY_2026_10_13.md §9.4). It is a pure
 * rendering of the item as written -- a name, never a value -- read again from the item's text by
 * {@link IqParser#written}, which keeps what the tree does not (the parentheses, a simple CASE, an IF). The rules,
 * measured:
 * <ul>
 *   <li>At the top, the item's outer parentheses go, and so do a sign {@code +} and the parentheses inside it; a column
 *       then shows as its last part is written ({@code +a.id}, {@code (a.id)} are {@code id}); a parenthesized query
 *       shows as its one column does.</li>
 *   <li>A column inside an expression is qualified by the correlation name of the FROM item it reads (its alias, else
 *       its name), as declared ({@code id + 1} over {@code t a} is {@code a.id+1}); one written qualified keeps its parts
 *       as written ({@code A.ID+1}). A column of a derived table, of an enclosing query or of no known FROM item is not
 *       rendered (SQL Anywhere renders a derived table's column by its definition).</li>
 *   <li>Constants: an integer's digits; a numeric's value without trailing zeros or a zero before its point
 *       ({@code .5}), a whole one {@code n.0} up to an INT's largest and {@code n} beyond; a string quoted, its quotes
 *       doubled, each control character {@code \xHH}; NULL {@code null}.</li>
 *   <li>Operators: {@code + - * /} written tight ({@code 1+2*3}), a minus before a minus spaced ({@code 1- -2},
 *       {@code - -1}); {@code ||} and the comparisons spaced ({@code a || b}, {@code a = b}; {@code !=} is
 *       {@code <>}, {@code !<} is {@code >=}, {@code !>} is {@code <=}); {@code %} is {@code remainder(a,b)}; a sign
 *       {@code +} is dropped. Every written parenthesis stays (but the outermost).</li>
 *   <li>Words in lower case ({@code and}, {@code or}, {@code not}, {@code is null}, {@code between}, {@code like},
 *       {@code escape}, {@code case}, {@code when}, {@code then}, {@code else}, {@code end}, {@code if}, {@code endif}),
 *       a space after each unless what follows begins with {@code (} or {@code -} ({@code if(a = 1)},
 *       {@code then-1}). {@code END CASE} is {@code end}, {@code END IF} is {@code endif}; a simple CASE stays simple.
 *       {@code x IN (...)} is {@code x in( a,b ) }, its plain constants first; NOT before a LIKE, a BETWEEN, an IN or an
 *       IS NULL is written inside it ({@code x not like p}); {@code IS [NOT] DISTINCT FROM} is SQL Anywhere's
 *       {@code [not] x is not distinct from y} turned round; a truth test's NOT goes in front
 *       ({@code not x = 1 is true}).</li>
 *   <li>A call: its name as written (a reserved word in double quotes, {@code "CHAR"(65)}), its arguments joined by
 *       {@code ,} without spaces; a date part as written, in lower case ({@code DATEPART(mm,...)}); {@code CURRENT DATE}
 *       and {@code CURRENT TIMESTAMP} (or {@code CURRENT_TIMESTAMP}) in lower case, {@code f(*)} as {@code f()}
 *       (IQ island, 2026-10-14, measured). An aggregate's (M1c): {@code COUNT(*)} is {@code COUNT()}, DISTINCT is
 *       {@code distinct } before its argument, ALL is dropped, LIST's ORDER BY follows its arguments as
 *       {@code  order by k asc,j desc}; an aggregate is never in parentheses ({@code (SUM(x)) + 1} is
 *       {@code SUM(t.x)+1}).</li>
 *   <li>In a grouped query (M1c) IQ names a part of an item that is a GROUP BY item, or an aggregate the query
 *       computes, by the rendering it gives that item or aggregate where it first reads it ({@link Substitutes}).</li>
 *   <li>The name is cut to 255 characters.</li>
 * </ul>
 * Anything else -- a subquery inside the expression, a comment or a line break between its tokens (each leaves its
 * mark on SQL Anywhere's name in ways not read here), a name of more than 255 characters
 * outside ASCII in a database whose characters may take two bytes -- has no rendering here (null), and the dialect
 * refuses the item by name.
 */
final class IqNames {

    /** The longest name SQL Anywhere 17 gives a column (measured): a longer rendering is cut to it. */
    static final int LONGEST = 255;

    /** The rendering of a column inside an expression, qualified ({@code t.id}), or null when it is not rendered;
     *  {@code inAggregate}: inside an aggregate's arguments (M1c: a derived table's column is rendered there, measured:
     *  {@code SUM(d.x)}). */
    @FunctionalInterface
    interface Columns {
        @com.legend.base.Nullable String rendering(SqlAst.ColumnRef ref, boolean inAggregate);
    }

    /** What the parts of an item read in a grouped query render as (M1c): a part that is a GROUP BY item, or an
     *  aggregate the query computes, renders as IQ renders that item or aggregate where it first reads it, without the
     *  parentheses written around the part. */
    @FunctionalInterface
    interface Substitutes {
        /** The rendering {@code e}, a part of the item ({@code inAggregate}: inside an aggregate's arguments), takes in
         *  place of its own: null for its own, a {@link Part} of no text when it is not known. Asked of each part once,
         *  in the order the item is written. */
        @com.legend.base.Nullable Part replace(SqlAst.Expr e, boolean inAggregate);
    }

    /** A part's rendering in place of its own; no text when it is not known ({@link #UNKNOWN}). */
    record Part(@com.legend.base.Nullable String text) {
    }

    /** No part is replaced: an item of a query that does not group. */
    static final Substitutes NONE = (e, inAggregate) -> null;

    /** A {@link Substitutes} answer: the part's rendering is not known (the item is refused by name). */
    static final Part UNKNOWN = new Part(null);

    private final IqParser.Written written;
    private final Columns columns;
    private final Substitutes substitutes;
    /** Whether the parts rendered are inside an aggregate's arguments (an aggregate's are rendered by another). */
    private final boolean inAggregate;

    private IqNames(IqParser.Written written, Columns columns, Substitutes substitutes, boolean inAggregate) {
        this.written = written;
        this.columns = columns;
        this.substitutes = substitutes;
        this.inAggregate = inAggregate;
    }

    /** The name IQ gives the unaliased item written {@code text} (its expression's text) under {@code profile}; null
     *  when it is not known (a parenthesized query's is its one item's: the dialect's to give). */
    static @com.legend.base.Nullable String name(@com.legend.base.Nullable String text, IqProfile profile,
            Columns columns) {
        return name(text, profile, columns, NONE);
    }

    /** The name IQ gives the unaliased item written {@code text} under {@code profile}, its parts replaced as
     *  {@code substitutes} says (M1c: an item of a grouped query); null when it is not known. */
    static @com.legend.base.Nullable String name(@com.legend.base.Nullable String text, IqProfile profile,
            Columns columns, Substitutes substitutes) {
        return name(text, profile, columns, substitutes, Chains.KEPT);
    }

    /**
     * IQ island, 2026-10-14 (review 3's M1, measured on SQL Anywhere 17): what SQL Anywhere does with the parentheses
     * inside an item that is a chain of {@code +} and {@code -} whose operands are all non-negative number constants or
     * products of them -- in a query without FROM or over DUMMY it keeps them ({@code 1+(2)}, {@code 1-(2-3)}); over a
     * table, a derived table or {@code sa_rowgenerator} it drops those around the chain's operands and its inner chains
     * ({@code 1+2}, {@code 1-2-3}, {@code 1+2*3}), keeping a factor's ({@code 1+(2)*3}); a chain over anything else
     * (a column, a negative number, NULL, a quotient) keeps them all. A FROM of DUMMY beside another item is not
     * measured: such a chain with a parenthesis is not named.
     */
    enum Chains { KEPT, DROPPED, UNKNOWN }

    /** {@link #name(String, IqProfile, Columns, Substitutes)}, with the item's query's {@link Chains}. */
    static @com.legend.base.Nullable String name(@com.legend.base.Nullable String text, IqProfile profile,
            Columns columns, Substitutes substitutes, Chains chains) {
        if (text == null) {
            return null;
        }
        IqParser.Written w;
        try {
            w = IqParser.written(text, profile);
        } catch (SqlSyntaxException e) {
            return null;
        }
        if (w.commented() || w.brokenLines()) {
            // SQL Anywhere's name keeps a space for a line break in places not read here (measured: "1\n+ 1" is
            // "1 +1", "f(\n1)" is "f( 1)")
            return null;
        }
        SqlAst.Expr e = w.expr();
        while (e instanceof SqlAst.Unary u && u.op() == SqlAst.UnaryOp.PLUS) {
            e = u.operand();
        }
        String name;
        if (e instanceof SqlAst.ColumnRef ref) {
            name = ref.parts().get(ref.parts().size() - 1).text();
        } else if (chains != Chains.KEPT && constantChain(e) && parenthesized(w, e, true)) {
            name = chains == Chains.UNKNOWN ? null : new IqNames(w, columns, substitutes, false).flat(e);
        } else {
            IqNames names = new IqNames(w, columns, substitutes, false);
            Part replaced = substitutes.replace(e, false);
            name = replaced != null ? replaced.text() : names.bare(e);
        }
        if (name == null) {
            return null;
        }
        if (name.length() > LONGEST) {
            for (int i = 0; i < LONGEST && !profile.iqDatabase(); i++) {
                if (name.charAt(i) > 0x7F) {
                    // a UTF-8 database cuts at 255 bytes, maybe inside a character
                    return null;
                }
            }
            name = name.substring(0, LONGEST);
        }
        return name;
    }

    /** Whether {@code e} is a chain of {@code +} and {@code -} (through parentheses) whose operands are all terms
     *  ({@link #term}). */
    private static boolean constantChain(SqlAst.Expr e) {
        return e instanceof SqlAst.Binary b && (b.op() == SqlAst.BinaryOp.PLUS || b.op() == SqlAst.BinaryOp.MINUS)
                && chainOperand(b.left()) && chainOperand(b.right());
    }

    private static boolean chainOperand(SqlAst.Expr e) {
        SqlAst.Expr x = unsigned(e);
        return constantChain(x) || term(x);
    }

    /** A non-negative number constant, or a product of them (a sign {@code +} is no sign). */
    private static boolean term(SqlAst.Expr e) {
        SqlAst.Expr x = unsigned(e);
        return x instanceof SqlAst.IntegerLit || x instanceof SqlAst.NumericLit
                || x instanceof SqlAst.Binary b && b.op() == SqlAst.BinaryOp.TIMES && term(b.left()) && term(b.right());
    }

    private static SqlAst.Expr unsigned(SqlAst.Expr e) {
        SqlAst.Expr x = e;
        while (x instanceof SqlAst.Unary u && u.op() == SqlAst.UnaryOp.PLUS) {
            x = u.operand();
        }
        return x;
    }

    /** Whether a parenthesis is written inside chain {@code e} (but around {@code e} itself, at the top). */
    private static boolean parenthesized(IqParser.Written w, SqlAst.Expr e, boolean top) {
        if (!top && w.parensOf(e) > 0) {
            return true;
        }
        if (e instanceof SqlAst.Unary u) {
            return parenthesized(w, u.operand(), false);
        }
        return e instanceof SqlAst.Binary b && (parenthesized(w, b.left(), false)
                || parenthesized(w, b.right(), false));
    }

    /** A constant chain as SQL Anywhere names it over a table: its operands' and inner chains' parentheses dropped, a
     *  product's factors' kept. */
    private @com.legend.base.Nullable String flat(SqlAst.Expr e) {
        SqlAst.Expr x = unsigned(e);
        if (x instanceof SqlAst.Binary b && (b.op() == SqlAst.BinaryOp.PLUS || b.op() == SqlAst.BinaryOp.MINUS)) {
            String l = flat(b.left());
            String r = flat(b.right());
            return l == null || r == null ? null : l + (b.op() == SqlAst.BinaryOp.PLUS ? "+" : "-") + r;
        }
        return bare(x);
    }

    /** {@code e}'s rendering with its parentheses (none around an aggregate, or a part replaced). */
    private @com.legend.base.Nullable String render(SqlAst.Expr e) {
        Part replaced = substitutes.replace(e, inAggregate);
        if (aggregate(e) && written.parensOf(e) > 0) {
            // SQL Anywhere keeps them around some aggregates and drops them around others (measured: (SUM(x)) + 1 is
            // SUM(t.x)+1, (SUM(x)) * 2 is (SUM(t.x))*2, (COUNT(*)) + 1 is (COUNT())+1): not read
            return null;
        }
        if (replaced != null) {
            return replaced.text();
        }
        if (aggregate(e)) {
            return bare(e);
        }
        String core = bare(e);
        if (core == null) {
            return null;
        }
        StringBuilder b = new StringBuilder();
        for (int i = written.parensOf(e); i > 0; i--) {
            b.append('(');
        }
        b.append(core);
        for (int i = written.parensOf(e); i > 0; i--) {
            b.append(')');
        }
        return b.toString();
    }

    /** {@code e}'s rendering without the parentheses around it. */
    private @com.legend.base.Nullable String bare(SqlAst.Expr e) {
        return switch (e) {
            case SqlAst.ColumnRef ref -> columns.rendering(ref, inAggregate);
            case SqlAst.IntegerLit n -> n.value().toString();
            case SqlAst.NumericLit n -> numeric(n.value());
            case SqlAst.StringLit s -> quoted(s.value());
            case SqlAst.NullLit n -> "null";
            case SqlAst.Unary u -> unary(u);
            case SqlAst.Binary b -> binary(b);
            case SqlAst.IsNull n -> negatable(n, false);
            case SqlAst.Between b -> negatable(b, false);
            case SqlAst.Like l -> negatable(l, false);
            case SqlAst.InList in -> negatable(in, false);
            case SqlAst.IsDistinct d -> negatable(d, false);
            case SqlAst.IsTruth t -> negatable(t, false);
            case SqlAst.Case c -> written.ifs().contains(c) ? ifExpression(c) : caseExpression(c);
            case SqlAst.FunctionCall f -> call(f);
            default -> null;
        };
    }

    private @com.legend.base.Nullable String unary(SqlAst.Unary u) {
        if (u.op() == SqlAst.UnaryOp.PLUS) {
            return render(u.operand());
        }
        if (u.op() == SqlAst.UnaryOp.MINUS) {
            String operand = render(u.operand());
            return operand == null ? null : "-" + (operand.startsWith("-") ? " " : "") + operand;
        }
        // NOT before a predicate that has its own negation is written inside it (measured)
        SqlAst.Expr operand = u.operand();
        if (written.parensOf(operand) == 0 && (operand instanceof SqlAst.IsNull || operand instanceof SqlAst.Between
                || operand instanceof SqlAst.Like || operand instanceof SqlAst.InList
                || operand instanceof SqlAst.IsDistinct || operand instanceof SqlAst.IsTruth)) {
            return negatable(operand, true);
        }
        String inner = render(operand);
        return inner == null ? null : word("not", inner);
    }

    private @com.legend.base.Nullable String binary(SqlAst.Binary b) {
        String l = render(b.left());
        String r = render(b.right());
        if (l == null || r == null) {
            return null;
        }
        return switch (b.op()) {
            case PLUS -> l + "+" + r;
            case MINUS -> l + "-" + (r.startsWith("-") ? " " : "") + r;
            case TIMES -> l + "*" + r;
            case DIVIDE -> l + "/" + r;
            case MODULO -> "remainder(" + l + "," + r + ")";
            case CONCAT -> l + " || " + r;
            case EQ -> l + " = " + r;
            case NE -> l + " <> " + r;
            case LT -> l + " < " + r;
            case LE -> l + " <= " + r;
            case GT -> l + " > " + r;
            case GE -> l + " >= " + r;
            case AND -> word(then(l, "and"), r);
            case OR -> word(then(l, "or"), r);
            case POWER -> null;
        };
    }

    /** A predicate with a negation of its own, {@code negated} once more by a NOT before it. */
    private @com.legend.base.Nullable String negatable(SqlAst.Expr e, boolean negated) {
        return switch (e) {
            case SqlAst.IsNull n -> {
                String x = render(n.operand());
                yield x == null ? null : then(x, n.negated() != negated ? "is not null" : "is null");
            }
            case SqlAst.Between b -> {
                String x = render(b.operand());
                String low = render(b.low());
                String high = render(b.high());
                yield x == null || low == null || high == null ? null
                        : word(then(word(then(x, b.negated() != negated ? "not between" : "between"), low), "and"),
                                high);
            }
            case SqlAst.Like l -> {
                String x = render(l.operand());
                String p = render(l.pattern());
                String esc = l.escape() == null ? "" : render(java.util.Objects.requireNonNull(l.escape()));
                if (x == null || p == null || esc == null) {
                    yield null;
                }
                String like = word(then(x, l.negated() != negated ? "not like" : "like"), p);
                yield l.escape() == null ? like : word(then(like, "escape"), esc);
            }
            case SqlAst.InList in -> {
                String x = render(in.operand());
                List<String> values = inValues(in.values());
                yield x == null || values == null ? null : then(x, in.negated() != negated ? "not in(" : "in(")
                        + " " + String.join(",", values) + " ) ";
            }
            case SqlAst.IsDistinct d -> {
                // SQL Anywhere's predicate is IS NOT DISTINCT FROM, IS DISTINCT FROM its negation (measured)
                String l = render(d.left());
                String r = render(d.right());
                if (l == null || r == null) {
                    yield null;
                }
                String same = word(then(l, "is not distinct from"), r);
                yield d.negated() != negated ? same : word("not", same);
            }
            case SqlAst.IsTruth t -> {
                String x = render(t.operand());
                if (x == null) {
                    yield null;
                }
                String test = then(x, "is " + t.truth().name().toLowerCase(java.util.Locale.ROOT));
                yield t.negated() != negated ? word("not", test) : test;
            }
            default -> throw new IllegalStateException("not a negatable predicate: " + e);
        };
    }

    /** An IN list's values: its plain constants (numbers, strings) first, then the rest, each in written order
     *  (measured). */
    private @com.legend.base.Nullable List<String> inValues(List<SqlAst.Expr> values) {
        List<String> constants = new ArrayList<>();
        List<String> rest = new ArrayList<>();
        for (SqlAst.Expr v : values) {
            String r = render(v);
            if (r == null) {
                return null;
            }
            boolean constant = written.parensOf(v) == 0 && (v instanceof SqlAst.IntegerLit
                    || v instanceof SqlAst.NumericLit || v instanceof SqlAst.StringLit);
            (constant ? constants : rest).add(r);
        }
        constants.addAll(rest);
        return constants;
    }

    /** {@code CASE [x] WHEN ... THEN ... [ELSE ...] END}: a simple CASE's WHEN holds its value (the reader made it
     *  {@code x = v}). */
    private @com.legend.base.Nullable String caseExpression(SqlAst.Case c) {
        boolean simple = written.simpleCases().contains(c);
        String out = "case";
        if (simple) {
            if (!(c.whens().get(0).condition() instanceof SqlAst.Binary first)) {
                return null;
            }
            String operand = render(first.left());
            if (operand == null) {
                return null;
            }
            out = word(out, operand);
        }
        List<SqlAst.When> whens = new ArrayList<>(c.whens());
        // IQ island, 2026-10-14 (review 3's M5): the branches the reader dropped (a NULL's) are named as written
        whens.addAll(written.droppedWhens().getOrDefault(c, List.of()));
        for (SqlAst.When w : whens) {
            String test = simple && w.condition() instanceof SqlAst.Binary b ? render(b.right()) : render(w.condition());
            String result = render(w.result());
            if (test == null || result == null) {
                return null;
            }
            out = word(then(word(then(out, "when"), test), "then"), result);
        }
        // (review 4's m2) the ELSE as written where the reader moved one
        SqlAst.Expr otherwise = written.droppedElse().containsKey(c) ? written.droppedElse().get(c) : c.otherwise();
        if (otherwise != null) {
            String e = render(otherwise);
            if (e == null) {
                return null;
            }
            out = word(then(out, "else"), e);
        }
        return then(out, "end");
    }

    /** {@code IF c THEN a [ELSE b] ENDIF}, which the reader made {@code CASE WHEN c THEN a [WHEN NOT c THEN b] END}. */
    private @com.legend.base.Nullable String ifExpression(SqlAst.Case c) {
        SqlAst.When first = c.whens().get(0);
        String test = render(first.condition());
        String a = render(first.result());
        if (test == null || a == null) {
            return null;
        }
        String out = word(then(word("if", test), "then"), a);
        // IQ island, 2026-10-14 (review 3's M5): an ELSE NULL the reader dropped is named as written
        SqlAst.Expr otherwise = c.whens().size() > 1 ? c.whens().get(1).result() : written.droppedElse().get(c);
        if (otherwise != null) {
            String b = render(otherwise);
            if (b == null) {
                return null;
            }
            out = word(then(out, "else"), b);
        }
        return then(out, "endif");
    }

    /** Whether {@code e} is a call of one of the aggregates read. */
    private static boolean aggregate(SqlAst.Expr e) {
        return e instanceof SqlAst.FunctionCall f && f.name().size() == 1
                && IqParser.READ_AGGREGATES.contains(IqLexer.lower(f.name().get(0).text()));
    }

    /** An aggregate's call (measured): {@code NAME()} for COUNT(*), {@code distinct } before a DISTINCT argument, the
     *  arguments joined by {@code ,}, then LIST's {@code  order by key asc|desc,...}. */
    private @com.legend.base.Nullable String aggregateCall(SqlAst.FunctionCall f) {
        IqNames inside = new IqNames(written, columns, substitutes, true);
        List<String> args = new ArrayList<>();
        for (SqlAst.Expr a : f.args()) {
            String r = inside.render(a);
            if (r == null) {
                return null;
            }
            args.add(r);
        }
        StringBuilder b = new StringBuilder(f.name().get(0).text()).append('(');
        String joined = String.join(",", args);
        // DISTINCT is a word: no space before a parenthesis or a minus (measured: COUNT(distinct(t.a)))
        b.append(f.distinct() ? word("distinct", joined) : joined);
        for (int i = 0; i < f.orderBy().size(); i++) {
            SqlAst.SortItem o = f.orderBy().get(i);
            String key = inside.render(o.expr());
            if (key == null) {
                return null;
            }
            b.append(i == 0 ? " order by " : ",").append(key).append(o.descending() ? " desc" : " asc");
        }
        return b.append(')').toString();
    }

    /** {@code name(argument,...)}: the name as written, a reserved word double-quoted. */
    private @com.legend.base.Nullable String call(SqlAst.FunctionCall f) {
        if (f.name().size() != 1) {
            return null;
        }
        if (aggregate(f)) {
            return aggregateCall(f);
        }
        String name = f.name().get(0).text();
        if (!f.name().get(0).quoted() && f.args().isEmpty()
                && (name.equals("CURRENT DATE") || name.equals("CURRENT TIMESTAMP"))) {
            // IQ island, 2026-10-14 (the merge of M1b and M1d): the reader's call for CURRENT DATE, CURRENT TIMESTAMP
            // and CURRENT_TIMESTAMP, which SQL Anywhere names in lower case (measured: current timestamp)
            return name.toLowerCase(java.util.Locale.ROOT);
        }
        List<String> args = new ArrayList<>();
        for (SqlAst.Expr a : f.args()) {
            // a date part is named as written, in lower case (measured: DATEPART(mm,...), DATENAME(month,...))
            String word = written.partWords().get(a);
            String r = word == null ? render(a) : word.isEmpty() || written.parensOf(a) > 0 ? null : word;
            if (r == null) {
                return null;
            }
            args.add(r);
        }
        return (IqLexer.reserved(name) ? "\"" + name + "\"" : name) + "(" + String.join(",", args) + ")";
    }

    /** {@code keyword} then {@code text}: a space between, unless the text begins with a parenthesis or a minus. */
    private static String word(String keyword, String text) {
        return keyword + (text.startsWith("(") || text.startsWith("-") ? "" : " ") + text;
    }

    /** {@code text} then {@code keyword}: a space between, unless the text ends with one (an IN list's). */
    private static String then(String text, String keyword) {
        return text + (text.endsWith(" ") ? "" : " ") + keyword;
    }

    /** A numeric constant: its value without trailing zeros or a zero before its point; a whole one {@code n.0} up to
     *  an INT's largest, {@code n} beyond (measured). */
    static String numeric(BigDecimal value) {
        BigDecimal v = value.stripTrailingZeros();
        if (v.scale() <= 0) {
            BigInteger whole = v.toBigInteger();
            return whole.abs().compareTo(BigInteger.valueOf(Integer.MAX_VALUE)) <= 0 ? whole + ".0" : whole.toString();
        }
        String s = v.toPlainString();
        return s.startsWith("0.") ? s.substring(1) : s.startsWith("-0.") ? "-" + s.substring(2) : s;
    }

    /** A string constant quoted: its quotes doubled, each control character as {@code \xHH}. */
    static String quoted(String value) {
        StringBuilder b = new StringBuilder("'");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\'') {
                b.append("''");
            } else if (c < 0x20) {
                String h = Integer.toHexString(c).toUpperCase(java.util.Locale.ROOT);
                b.append("\\x").append(h.length() == 1 ? "0" : "").append(h);
            } else {
                b.append(c);
            }
        }
        return b.append('\'').toString();
    }
}
