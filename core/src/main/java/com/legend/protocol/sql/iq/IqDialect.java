// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.protocol.sql.iq;

import com.legend.protocol.spec.AppliedFunction;
import com.legend.protocol.spec.CBoolean;
import com.legend.protocol.spec.CDecimal;
import com.legend.protocol.spec.CInteger;
import com.legend.protocol.spec.CString;
import com.legend.protocol.spec.ValueSpecification;
import com.legend.protocol.sql.InputDialect;
import com.legend.protocol.sql.SqlAst;
import com.legend.protocol.sql.SqlToPure;
import com.legend.protocol.sql.SqlToPure.Kind;
import com.legend.protocol.sql.SqlToPure.Tx;
import com.legend.protocol.sql.SqlTranslationException;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * SAP IQ 16.2 as {@code SqlToPure} reads it: the {@code #IQ{...}#} island's dialect (docs/IQ_SQL_STUDY_2026_10_13.md
 * §4-§5), under one of the closed set of {@link IqProfile}s and one of IQ's two {@link Engine}s. Its reader is
 * {@link IqParser}, its types {@link IqType} and {@link IqTypes}; its rules, so far (M0):
 * <ul>
 *   <li><b>Names</b> match without regard to ASCII case, quoted or not (IQ's identifiers are case-insensitive, and fold
 *       A-Z only); a name two candidates match is an ambiguity. An unquoted alias keeps its case. A column shows as
 *       its reference is written ({@code SELECT ID} is a column {@code ID}); any other unaliased item as IQ names it,
 *       its own rendering of the item ({@code 1.50} shows as {@code 1.5}, {@code 'a'} as {@code 'a'}), or the item is
 *       refused where that rendering is not known. A select-list alias is visible to WHERE, to the select list and
 *       inside an ORDER BY expression in IQ, before a column of its name: a reference that would read one is refused,
 *       not yet read (a bare ORDER BY name reads the select list first, as IQ does).</li>
 *   <li><b>NULL</b> is the lowest value: first ascending, last descending. A bare NULL is a SMALLINT NULL. Under
 *       {@code ansinull} Off (a TDS login), {@code x = NULL} is {@code x IS NULL} and {@code x != NULL} is
 *       {@code x IS NOT NULL} in every search condition, but SQL Anywhere's front end leaves the conditions of a CASE
 *       or an IF UNKNOWN (measured) where the IQ engine rewrites them too (the research's D-L1); any other comparison
 *       with NULL is UNKNOWN.</li>
 *   <li><b>Strings</b> compare case-sensitively in an IQ profile's database, ignoring trailing blanks (BLANK PADDING
 *       ON): {@code =} holds when one value starts with the other and the rest is spaces. Ordering
 *       strings ({@code <}, ORDER BY) and DISTINCT over them need an ASCII-only right trim (IQ ignores the blanks
 *       before comparing, which no padding reproduces for characters below the space), and every string comparison
 *       of a {@code sqla17} profile's case-insensitive database needs an ASCII-only case fold: natives not built yet
 *       (the study's O1), so each is refused by name. Under the IQ engine a CHAR(n) column's value is padded with
 *       blanks to n (the research's D-S1), which the source read computes once, so every reference sees it.
 *       {@code ||} joins two values as text, a single NULL among them read as '' and all NULL giving NULL (the
 *       research's D-S3); {@code +} of two strings does the same, while a string and a number (or an untyped NULL)
 *       is IQ's NUMERIC addition, refused until the conversions land.</li>
 *   <li><b>BIT</b> is an integer, 0 or 1, in IQ, not a truth value: a BIT column is read as one, so it compares with
 *       integers, sorts, and computes as IQ's BIT does.</li>
 *   <li><b>Integers</b> compute as IQ's do ({@link IqTypes}): each constant and result typed SMALLINT, INTEGER or
 *       BIGINT, a result that leaves its type's range IQ's error -158 (checked with Pure's own natives: an empty value
 *       read as one raises, as {@code SqlToPure}'s range checks do), {@code /} truncating toward zero, {@code %}
 *       keeping the dividend's sign, a zero divisor IQ's error -628 (DIVIDE_BY_ZERO_ERROR's default). The database
 *       computes each operation in 64 bits ({@code floor}, Pure's integer of a number, before each operand
 *       that might be narrower), so only IQ's own ranges bound a result. Arithmetic on a NUMERIC (IQ-engine
 *       decimal types are the research's D-N1), a DOUBLE, a string or a date is refused by name, and so are the
 *       results IQ types NUMERIC or unsigned. A constant result out of its type is IQ's error at translation only
 *       when every operand is a constant IQ folds; a BIGINT result is the database's own overflow; an expression
 *       whose checks would write out more Pure than {@link #LARGEST} nodes is refused.</li>
 *   <li><b>CASE, IF, ISNULL, COALESCE, IFNULL, NULLIF</b>: one type for their values by IQ's unification
 *       ({@link #unify}), where it is one the island can prove; {@code IFNULL(a, b)} is NULL when {@code a} is not
 *       (IQ's, not COALESCE's); {@code NULLIF(a, b)} reads {@code b} as {@code a}'s type.</li>
 *   <li><b>Row limits</b>: {@code TOP n} and {@code START AT m}, each a constant IQ types and checks (a literal TOP 0,
 *       a negative count or a START AT below 1 is IQ's error -674, the research's D-Q1), which the planner folds. A
 *       row limit over a FROM clause needs an ORDER BY, and its select list and ORDER BY no operation that can raise
 *       (IQ computes them only for the rows it keeps): both refused by name.</li>
 * </ul>
 * Refusals that IQ makes too carry IQ's SQLCODE and SQLSTATE beside the message, which is this dialect's own wording.
 */
public final class IqDialect implements InputDialect {

    /** Which rules a statement follows (the research's SEMANTICS §2): IQ's engine for a query over IQ tables, SQL
     *  Anywhere's front end (IQ's own) for a query without FROM. Only the typing and function layer reads it. */
    public enum Engine { IQ, SA }

    /** The store types a column is read as stored in: every other refuses its table by name (CHAR is padded and BIT
     *  read as an integer, below). */
    private static final Set<String> READ_AS_STORED = Set.of("SMALLINT", "INTEGER", "BIGINT", "TINYINT", "DECIMAL",
            "NUMERIC", "DOUBLE", "VARCHAR", "DATE", "TIMESTAMP");

    private final IqProfile profile;
    private final @com.legend.base.Nullable Engine engine;
    /** The comparisons that are (part of) a CASE's or an IF's condition, in the statement being read: SQL Anywhere's
     *  front end does not rewrite {@code = NULL} there under ansinull Off. */
    private final Set<SqlAst.Expr> caseConditions;
    /** The names (ASCII lower case) the statement being read refers to a column by, or null when it reads every
     *  column (a star): a source column read otherwise ({@link #read}) is read so only where it is referred to. */
    private final @com.legend.base.Nullable Set<String> referenced;
    /** The expressions (and every expression inside them) of the select list and ORDER BY of a statement with a row
     *  limit: IQ computes the select list only for the rows the limit keeps, where lite's databases may compute it for
     *  every row, so an operation there that can raise IQ's error is refused ({@link #checkedInteger}). */
    private final Set<SqlAst.Expr> limited;
    /** Whether the statement reads a FROM clause and has no ORDER BY: its row limit's rows are IQ's choice. */
    private final boolean unordered;
    /** Whether the statement has a TOP count (its LIMIT), beside or instead of a START AT (its OFFSET). */
    private final boolean counted;

    private IqDialect(IqProfile profile, @com.legend.base.Nullable Engine engine, Set<SqlAst.Expr> caseConditions,
            @com.legend.base.Nullable Set<String> referenced, Set<SqlAst.Expr> limited, boolean unordered,
            boolean counted) {
        this.profile = profile;
        this.engine = engine;
        this.caseConditions = caseConditions;
        this.referenced = referenced;
        this.limited = limited;
        this.unordered = unordered;
        this.counted = counted;
    }

    /** The dialect of an island written for {@code profile}: each statement's engine chosen by its FROM clause
     *  ({@link #engineOf}, the study's I3: {@code engine=auto}). */
    public static IqDialect of(IqProfile profile) {
        return new IqDialect(profile, null, Set.of(), null, Set.of(), false, false);
    }

    /** The dialect under {@code profile} with {@code engine}'s rules whatever the statement. */
    public static IqDialect of(IqProfile profile, Engine engine) {
        return new IqDialect(profile, engine, Set.of(), null, Set.of(), false, false);
    }

    public IqProfile profile() {
        return profile;
    }

    /** The engine this dialect reads by; null while it is chosen per statement. */
    public @com.legend.base.Nullable Engine engine() {
        return engine;
    }

    /** The engine IQ runs {@code statement} in under {@code profile}: SQL Anywhere's front end when it has no FROM,
     *  IQ's otherwise (a table of the island's is an IQ table; the research's D-E1 gives a mix to IQ). A
     *  {@code sqla17-*} profile is a SQL Anywhere database: no IQ engine there, whatever the FROM. */
    public static Engine engineOf(SqlAst.Statement statement, IqProfile profile) {
        return !profile.iqDatabase() || statement instanceof SqlAst.Select s && s.from().isEmpty() ? Engine.SA
                : Engine.IQ;
    }

    /** IQ's code for a syntax error, as this dialect's messages carry it. */
    static String syntaxCode() {
        return code(-131, "42W04");
    }

    private static String code(int sqlcode, String sqlstate) {
        return " (IQ: SQLCODE " + sqlcode + ", SQLSTATE " + sqlstate + ")";
    }

    /** IQ's code for a value outside its type's range. */
    private static String overflowCode() {
        return code(-158, "22003");
    }

    /** IQ's query: {@link IqParser}'s, with what the island refuses before translating it -- a FROM function other
     *  than the table source ({@code tb}), a function the catalog lacks (so no other dialect's reading of its name
     *  applies), two select-list aliases IQ cannot tell apart (its error -830), an alias read anywhere but as a bare
     *  ORDER BY key. */
    @Override
    public SqlAst.Statement parse(String sql) {
        SqlAst.Statement statement = IqParser.parse(sql, profile);
        if (statement instanceof SqlAst.Select s) {
            for (SqlAst.FromItem item : s.from()) {
                if (item instanceof SqlAst.TableFunction f && !(f.name().size() == 1
                        && IqLexer.lower(f.name().get(0).text()).equals("tb"))) {
                    throw SqlTranslationException.unsupported("the FROM function " + f.name().get(f.name().size() - 1)
                            .text() + "(...) is not supported yet: an #IQ{...}# island reads tables, tb('...')",
                            f.pos());
                }
            }
            for (SqlAst.Expr e : expressions(s)) {
                knownFunctions(e);
            }
            uniqueAliases(s);
            noAliasRead(s);
        }
        return statement;
    }

    /** Every call under {@code e} names a function of the catalog ({@link #function}), with as many arguments as
     *  it takes (else IQ's error -154). */
    private void knownFunctions(SqlAst.Expr e) {
        if (e instanceof SqlAst.FunctionCall f) {
            String name = f.name().get(f.name().size() - 1).text();
            if (function(name) == null) {
                throw SqlTranslationException.unsupported("function " + name + "(...) is not supported yet", f.pos());
            }
            int n = f.args().size();
            Catalog fn = java.util.Objects.requireNonNull(Catalog.named(name));
            if (n < fn.min || n > fn.max) {
                throw SqlTranslationException.invalid("wrong number of arguments to " + name.toUpperCase(
                        java.util.Locale.ROOT) + code(-154, "37505"), f.pos());
            }
        }
        for (SqlAst.Expr child : children(e)) {
            knownFunctions(child);
        }
    }

    /** Two select-list aliases equal without regard to ASCII case are IQ's error, read or not (measured). */
    private static void uniqueAliases(SqlAst.Select s) {
        Set<String> seen = new java.util.HashSet<>();
        for (SqlAst.SelectItem item : s.items()) {
            if (item instanceof SqlAst.SelectExpr x && x.alias() != null) {
                SqlAst.Ident alias = java.util.Objects.requireNonNull(x.alias());
                if (!seen.add(IqLexer.lower(alias.text()))) {
                    throw SqlTranslationException.invalid("the alias '" + alias.text() + "' is not unique"
                            + code(-830, "42W60"), alias.pos());
                }
            }
        }
    }

    @Override
    public InputDialect reading(SqlAst.Statement statement) {
        Set<SqlAst.Expr> inCase = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<SqlAst.Expr> limitedHere = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<String> names = new java.util.HashSet<>();
        boolean star = !(statement instanceof SqlAst.Select);
        boolean unorderedHere = false;
        boolean countedHere = false;
        if (statement instanceof SqlAst.Select s) {
            for (SqlAst.Expr e : expressions(s)) {
                caseConditions(e, inCase);
                List<SqlAst.ColumnRef> refs = new ArrayList<>();
                columnRefs(e, refs);
                refs.forEach(r -> names.add(IqLexer.lower(r.parts().get(r.parts().size() - 1).text())));
            }
            star = s.items().stream().anyMatch(i -> i instanceof SqlAst.AllColumns);
            if (s.limit() != null || s.offset() != null) {
                for (SqlAst.SelectItem item : s.items()) {
                    if (item instanceof SqlAst.SelectExpr x) {
                        within(x.expr(), limitedHere);
                    }
                }
                s.orderBy().forEach(o -> within(o.expr(), limitedHere));
                unorderedHere = s.orderBy().isEmpty() && !s.from().isEmpty();
                countedHere = s.limit() != null;
            }
        }
        return new IqDialect(profile, engine != null ? engine : engineOf(statement, profile), inCase,
                star ? null : names, limitedHere, unorderedHere, countedHere);
    }

    /** {@code e} and every expression inside it, into {@code out}. */
    private static void within(SqlAst.Expr e, Set<SqlAst.Expr> out) {
        out.add(e);
        for (SqlAst.Expr child : children(e)) {
            within(child, out);
        }
    }

    // ------------------------------------------------------------------ the statement's expressions

    /** A select's expressions: its items', WHERE's, ORDER BY's. */
    private static List<SqlAst.Expr> expressions(SqlAst.Select s) {
        List<SqlAst.Expr> all = new ArrayList<>();
        for (SqlAst.SelectItem item : s.items()) {
            if (item instanceof SqlAst.SelectExpr x) {
                all.add(x.expr());
            }
        }
        if (s.where() != null) {
            all.add(java.util.Objects.requireNonNull(s.where()));
        }
        s.orderBy().forEach(o -> all.add(o.expr()));
        return all;
    }

    /** The expressions directly inside {@code e}, of the forms {@link IqParser} reads. */
    private static List<SqlAst.Expr> children(SqlAst.Expr e) {
        return switch (e) {
            case SqlAst.Binary b -> List.of(b.left(), b.right());
            case SqlAst.Unary u -> List.of(u.operand());
            case SqlAst.IsNull n -> List.of(n.operand());
            case SqlAst.FunctionCall f -> f.args();
            case SqlAst.Case c -> {
                List<SqlAst.Expr> all = new ArrayList<>();
                if (c.operand() != null) {
                    all.add(java.util.Objects.requireNonNull(c.operand()));
                }
                for (SqlAst.When w : c.whens()) {
                    all.add(w.condition());
                    all.add(w.result());
                }
                if (c.otherwise() != null) {
                    all.add(java.util.Objects.requireNonNull(c.otherwise()));
                }
                yield all;
            }
            case SqlAst.ColumnRef r -> List.of();
            case SqlAst.IntegerLit n -> List.of();
            case SqlAst.NumericLit n -> List.of();
            case SqlAst.StringLit n -> List.of();
            case SqlAst.NullLit n -> List.of();
            // exhaustive over IqParser's forms: a new form is read here before a walk can miss what is inside it
            default -> throw new IllegalStateException("the #IQ{...}# dialect does not walk " + e.getClass()
                    .getSimpleName() + " yet");
        };
    }

    /** Each comparison of a CASE's (an IF's) condition under {@code e} into {@code out}. */
    private static void caseConditions(SqlAst.Expr e, Set<SqlAst.Expr> out) {
        if (e instanceof SqlAst.Case c) {
            for (SqlAst.When w : c.whens()) {
                comparisons(w.condition(), out);
            }
        }
        for (SqlAst.Expr child : children(e)) {
            caseConditions(child, out);
        }
    }

    /** The comparisons a condition is made of (through AND, OR and NOT). */
    private static void comparisons(SqlAst.Expr condition, Set<SqlAst.Expr> out) {
        switch (condition) {
            case SqlAst.Binary b when b.op() == SqlAst.BinaryOp.AND || b.op() == SqlAst.BinaryOp.OR -> {
                comparisons(b.left(), out);
                comparisons(b.right(), out);
            }
            case SqlAst.Unary u when u.op() == SqlAst.UnaryOp.NOT -> comparisons(u.operand(), out);
            case SqlAst.Binary b -> out.add(b);
            default -> {
            }
        }
    }

    // ------------------------------------------------------------------ names

    /** IQ keeps an alias as written. */
    @Override
    public String fold(String text) {
        return text;
    }

    /** IQ's identifier rule: the one name equal to {@code id} without regard to ASCII case, quoted or not. */
    @Override
    public @com.legend.base.Nullable String resolve(SqlAst.Ident id, Iterable<String> names, String what) {
        String key = IqLexer.lower(id.text());
        List<String> found = new ArrayList<>();
        for (String n : names) {
            if (IqLexer.lower(n).equals(key) && !found.contains(n)) {
                found.add(n);
            }
        }
        if (found.size() > 1) {
            throw SqlTranslationException.invalid(what + " '" + id.text() + "' names " + String.join(" and ",
                    found.stream().map(n -> "'" + n + "'").toList()) + ", which IQ cannot tell apart: its names"
                    + " match without regard to case", id.pos());
        }
        return found.isEmpty() ? null : found.get(0);
    }

    @Override
    public String hint(SqlAst.Ident name, List<String> names) {
        return "";
    }

    /** A select-list alias read in WHERE, in the select list, or inside an ORDER BY expression: IQ reads the alias's
     *  expression there (a reference names an alias before a column, everywhere), which is not read yet. ORDER BY's
     *  bare name (an output column first, as IQ reads it) and its positions read the select list already. An alias of
     *  the column of its own name reads the same either way. */
    private static void noAliasRead(SqlAst.Select s) {
        Map<String, SqlAst.SelectExpr> aliases = new HashMap<>();
        for (SqlAst.SelectItem item : s.items()) {
            if (item instanceof SqlAst.SelectExpr x && x.alias() != null) {
                aliases.putIfAbsent(IqLexer.lower(java.util.Objects.requireNonNull(x.alias()).text()), x);
            }
        }
        if (aliases.isEmpty()) {
            return;
        }
        List<SqlAst.ColumnRef> refs = new ArrayList<>();
        for (SqlAst.SelectItem item : s.items()) {
            if (item instanceof SqlAst.SelectExpr x) {
                columnRefs(x.expr(), refs);
            }
        }
        if (s.where() != null) {
            columnRefs(java.util.Objects.requireNonNull(s.where()), refs);
        }
        int outside = refs.size();
        for (SqlAst.SortItem o : s.orderBy()) {
            if (!(o.expr() instanceof SqlAst.ColumnRef) && !(o.expr() instanceof SqlAst.IntegerLit)) {
                columnRefs(o.expr(), refs);
            }
        }
        for (int i = 0; i < refs.size(); i++) {
            SqlAst.ColumnRef ref = refs.get(i);
            if (ref.parts().size() != 1) {
                continue;
            }
            String name = IqLexer.lower(ref.parts().get(0).text());
            SqlAst.SelectExpr aliased = aliases.get(name);
            if (aliased != null && !(aliased.expr() instanceof SqlAst.ColumnRef c
                    && IqLexer.lower(c.parts().get(c.parts().size() - 1).text()).equals(name))) {
                throw SqlTranslationException.unsupported("'" + ref.parts().get(0).text() + "' names the select"
                        + " list's alias, which IQ reads before a column of that name: reading an alias "
                        + (i < outside ? "in WHERE or the select list" : "inside an ORDER BY expression (only a bare"
                        + " alias or a position is read there)") + " is not supported yet", ref.pos());
            }
        }
    }

    private static void columnRefs(SqlAst.Expr e, List<SqlAst.ColumnRef> out) {
        if (e instanceof SqlAst.ColumnRef ref) {
            out.add(ref);
        }
        for (SqlAst.Expr child : children(e)) {
            columnRefs(child, out);
        }
    }

    // ------------------------------------------------------------------ order and output names

    /** IQ's NULL is the lowest value. */
    @Override
    public boolean nullsFirst(boolean descending) {
        return !descending;
    }

    /** A column shows as its reference is written. */
    @Override
    public String columnName(SqlAst.Ident written, String label) {
        return written.text();
    }

    /** IQ's name for an unnamed item: its rendering of the item (measured on SQL Anywhere 17, which IQ's front end
     *  is), where it is known. */
    @Override
    public String defaultName(SqlAst.SelectExpr item) {
        String name = rendering(item.expr());
        if (name == null) {
            throw SqlTranslationException.unsupported("this select item needs an alias (AS name): the name IQ gives an"
                    + " unaliased " + (item.text() == null ? "item" : "'" + item.text() + "'") + " is not known yet",
                    item.pos());
        }
        return name;
    }

    /** IQ's rendering of a constant or a column: an integer's digits, a numeric's value without trailing zeros (and
     *  without a zero before the point; {@code .0} after a whole one that fits an INT, nothing after one of eleven
     *  digits or more), a string quoted with its quotes doubled and its control characters as {@code \xHH}, NULL as
     *  {@code null}; null where IQ's rendering is not known. */
    private static @com.legend.base.Nullable String rendering(SqlAst.Expr e) {
        return switch (e) {
            case SqlAst.ColumnRef ref -> ref.parts().get(ref.parts().size() - 1).text();
            case SqlAst.IntegerLit n -> n.value().toString();
            case SqlAst.NumericLit n -> numeric(n.value());
            case SqlAst.StringLit s -> quoted(s.value());
            case SqlAst.NullLit n -> "null";
            case SqlAst.Unary u when u.op() == SqlAst.UnaryOp.MINUS && constantNumber(u.operand()) -> {
                String operand = rendering(u.operand());
                yield operand == null ? null : "-" + operand;
            }
            case SqlAst.Unary u when u.op() == SqlAst.UnaryOp.PLUS && constantNumber(u.operand()) ->
                    rendering(u.operand());
            default -> null;
        };
    }

    private static boolean constantNumber(SqlAst.Expr e) {
        return e instanceof SqlAst.IntegerLit || e instanceof SqlAst.NumericLit;
    }

    private static @com.legend.base.Nullable String numeric(BigDecimal value) {
        BigDecimal v = value.stripTrailingZeros();
        if (v.scale() <= 0) {
            BigInteger whole = v.toBigInteger();
            if (whole.abs().compareTo(BigInteger.valueOf(Integer.MAX_VALUE)) <= 0) {
                return whole + ".0";
            }
            return whole.abs().compareTo(BigInteger.TEN.pow(10)) >= 0 ? whole.toString() : null;
        }
        String s = v.toPlainString();
        return s.startsWith("0.") ? s.substring(1) : s;
    }

    private static String quoted(String value) {
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

    // ------------------------------------------------------------------ types

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
            default -> Kind.OTHER;
        };
    }

    /** The column's store type, when it has one. */
    @Override
    public InputDialect.@com.legend.base.Nullable TypeDetail detailOf(String pureType,
            @com.legend.base.Nullable String storeType) {
        return storeType == null ? null : new IqType(storeType);
    }

    /** Under the IQ engine a CHAR(n) value is blank padded to n (the research's D-S1: the IQ manual's own examples and
     *  the TPC-H reports run on IQ show it). A BIT is IQ's integer 0 or 1 (the store holds Pure's Boolean): read as
     *  {@code if(b, 1, 0)}, NULL staying NULL. A store type the island does not read yet refuses the source by name. */
    @Override
    public SqlToPure.@com.legend.base.Nullable Tx read(String column, Tx stored, SqlAst.Pos pos) {
        if (!(stored.detail() instanceof IqType type)) {
            return null;
        }
        String family = type.family();
        if (referenced != null && !referenced.contains(IqLexer.lower(column))
                && (family.equals("CHAR") && type.size() >= 0 || family.equals("BIT"))) {
            // never seen: read as stored (the CHAR's pad and the BIT's integer change nothing the query sees)
            return null;
        }
        if (family.equals("CHAR")) {
            int length = type.size();
            if (length < 0) {
                throw SqlTranslationException.unsupported("the column '" + column + "' is of store type CHAR with no"
                        + " length, which the #IQ{...}# island does not read", pos);
            }
            if (engine != Engine.IQ) {
                return null;
            }
            Tx padded = SqlToPure.strict(Kind.STRING, List.of(stored), v -> SqlToPure.call("rpad", v.get(0),
                    new CInteger((long) length), new CString(" ")));
            return Tx.value(padded.vs(), Kind.STRING, padded.nullable(), type);
        }
        if (family.equals("BIT") && stored.kind() == Kind.BOOLEAN) {
            ValueSpecification bit = ifThen(stored.vs(), new CInteger(1L), stored.nullable()
                    ? ifThen(stored.falseWhen(), new CInteger(0L), SqlToPure.empty()) : new CInteger(0L));
            return Tx.value(bit, Kind.INTEGER, stored.nullable(), new IqType("BIT"));
        }
        if (!READ_AS_STORED.contains(family)) {
            throw SqlTranslationException.unsupported("the column '" + column + "' is of store type " + type.spelling()
                    + ", which the #IQ{...}# island does not read yet", pos);
        }
        return null;
    }

    /** An integer constant is an Integer of IQ's type by its value (a NUMERIC, Pure's Decimal, beyond a BIGINT); a
     *  numeric one a Decimal; a string a String (a CHAR of its length); a bare NULL IQ's SMALLINT NULL. */
    @Override
    public Tx literal(SqlAst.Expr constant) {
        BigDecimal number = constant instanceof SqlAst.IntegerLit n ? new BigDecimal(n.value())
                : constant instanceof SqlAst.NumericLit n ? n.value() : null;
        if (number != null && number.signum() != 0 && number.stripTrailingZeros().precision() > profile.precision()) {
            // the profile's database keeps only its PRECISION's leading digits of such a constant (measured)
            throw SqlTranslationException.unsupported("a number of more than " + profile.precision() + " significant"
                    + " digits is not supported under profile " + profile.spelling() + ": its database keeps only the"
                    + " leading ones", constant.pos());
        }
        return switch (constant) {
            case SqlAst.IntegerLit n -> n.value().bitLength() < Long.SIZE
                    ? Tx.value(SqlToPure.integer(n.value()), Kind.INTEGER, false, IqTypes.ofIntegerConstant(n.value()))
                    : Tx.value(new CDecimal(new BigDecimal(n.value())), Kind.DECIMAL, false,
                            IqTypes.ofIntegerConstant(n.value()));
            // at IQ's scale for it: its digits without trailing zeros (1.50 is NUMERIC(2,1) 1.5, 1.50E1 is 15)
            case SqlAst.NumericLit n -> Tx.value(new CDecimal(IqTypes.atScale(n.value())), Kind.DECIMAL, false,
                    IqTypes.ofNumericConstant(n.value()));
            case SqlAst.StringLit s -> Tx.value(new CString(s.value()), Kind.STRING, false, IqTypes.ofString(s.value()));
            case SqlAst.NullLit n -> Tx.value(SqlToPure.nullOf(Kind.INTEGER).vs(), Kind.NULL, true);
            default -> throw SqlTranslationException.unsupported("this constant is not IQ's", constant.pos());
        };
    }

    // ------------------------------------------------------------------ meanings

    @Override
    public Tx binary(SqlAst.Binary b, SqlToPure.At at) {
        return bounded(meaning(b, at), b.pos());
    }

    private Tx meaning(SqlAst.Binary b, SqlToPure.At at) {
        return switch (b.op()) {
            case AND, OR -> {
                Tx l = SqlToPure.truth(at.tx(b.left()), b.op().name(), b.pos());
                Tx r = SqlToPure.truth(at.tx(b.right()), b.op().name(), b.pos());
                boolean nullable = l.nullable() || r.nullable();
                // Kleene: AND is TRUE when both are, FALSE when either is; OR the other way round
                yield b.op() == SqlAst.BinaryOp.AND
                        ? Tx.condition(SqlToPure.call("and", l.vs(), r.vs()),
                                SqlToPure.call("or", l.falseWhen(), r.falseWhen()), nullable)
                        : Tx.condition(SqlToPure.call("or", l.vs(), r.vs()),
                                SqlToPure.call("and", l.falseWhen(), r.falseWhen()), nullable);
            }
            case EQ, NE, LT, LE, GT, GE -> comparison(b.op(), at.tx(b.left()), at.tx(b.right()),
                    b.left() instanceof SqlAst.NullLit, b.right() instanceof SqlAst.NullLit, b, b.pos());
            case PLUS -> plus(b, at);
            case CONCAT -> {
                List<Tx> parts = new ArrayList<>();
                for (SqlAst.Expr e : operands(b, SqlAst.BinaryOp.CONCAT)) {
                    parts.add(at.tx(e));
                }
                yield concatenation(parts, b, "||");
            }
            case MINUS, TIMES, DIVIDE, MODULO -> integerArithmetic(b, at.tx(b.left()), at.tx(b.right()));
            case POWER -> throw SqlTranslationException.unsupported("IQ has no power operator", b.pos());
        };
    }

    /** {@code -x}, {@code +x}, {@code NOT c}: a minus before an integer constant is a constant typed by its value; one
     *  before any other integer negates it in its type (a BIT's is a SMALLINT), checked; a minus before a numeric
     *  constant negates it exactly; a plus changes no number. */
    @Override
    public Tx unary(SqlAst.Unary u, SqlToPure.At at) {
        Tx x = at.tx(u.operand());
        if (u.op() == SqlAst.UnaryOp.NOT) {
            return SqlToPure.not(SqlToPure.truth(x, "NOT", u.pos()));
        }
        if (x.kind() == Kind.NULL) {
            return typedNull("SMALLINT");
        }
        if (u.op() == SqlAst.UnaryOp.PLUS) {
            if (x.kind().numeric()) {
                return x;
            }
            throw SqlTranslationException.unsupported("a sign before " + kindName(x.kind()) + " (IQ converts it to"
                    + " NUMERIC) is not supported yet", u.pos());
        }
        if (u.operand() instanceof SqlAst.IntegerLit n) {
            Tx negated = SqlToPure.strict(x.kind(), List.of(x), v -> SqlToPure.call("minus", v.get(0)));
            return Tx.value(negated.vs(), x.kind(), false, IqTypes.ofNegatedConstant(n.value()));
        }
        if (u.operand() instanceof SqlAst.NumericLit) {
            Tx negated = SqlToPure.strict(x.kind(), List.of(x), v -> SqlToPure.call("minus", v.get(0)));
            return Tx.value(negated.vs(), x.kind(), false, x.detail());
        }
        IqType t = integerOperand(x, u.operand(), "-");
        IqType result = new IqType(IqTypes.negated(t.family()));
        BigInteger lo = t.hi().negate();
        BigInteger hi = t.lo().negate();
        return bounded(checkedInteger(u, "-", result, lo, hi, List.of(x), List.of(t), constants(List.of(u.operand()),
                List.of(x)), false, v -> SqlToPure.call("minus", v.get(0))), u.pos());
    }

    /** {@code +}: two strings join (as {@code ||} does), two integers add; a string with anything else is IQ's NUMERIC
     *  addition of the string, refused until the conversions land. A run of {@code +} over strings is one join. */
    private Tx plus(SqlAst.Binary b, SqlToPure.At at) {
        List<SqlAst.Expr> leaves = operands(b, SqlAst.BinaryOp.PLUS);
        List<Tx> parts = new ArrayList<>();
        boolean strings = true;
        boolean anyString = false;
        for (SqlAst.Expr e : leaves) {
            Tx t = at.tx(e);
            parts.add(t);
            strings &= t.kind() == Kind.STRING;
            anyString |= t.kind() == Kind.STRING;
        }
        if (strings) {
            return concatenation(parts, b, "+");
        }
        if (anyString) {
            throw SqlTranslationException.unsupported("+ of a string and a value that is no string is IQ's NUMERIC"
                    + " addition (the string converted, an untyped NULL included): not supported yet", b.pos());
        }
        return integerArithmetic(b, at.tx(b.left()), at.tx(b.right()));
    }

    /** The operands of a run of {@code op} (both sides: {@code ||} and string {@code +} are associative). */
    private static List<SqlAst.Expr> operands(SqlAst.Expr e, SqlAst.BinaryOp op) {
        if (e instanceof SqlAst.Binary b && b.op() == op) {
            List<SqlAst.Expr> all = new ArrayList<>(operands(b.left(), op));
            all.addAll(operands(b.right(), op));
            return all;
        }
        return List.of(e);
    }

    /** IQ's {@code ||} (and {@code +} of strings): the values as text, joined; a NULL among them is read as '', and the
     *  result is NULL only when every one is (the research's D-S3). An integer joins as its digits; any other value
     *  ({@code ||} writes numerics, doubles and dates by IQ's formats) is refused by name. */
    private Tx concatenation(List<Tx> parts, SqlAst.Binary b, String operator) {
        List<Tx> present = new ArrayList<>();
        for (Tx p : parts) {
            if (p.kind() == Kind.NULL) {
                continue;
            }
            boolean integer = p.kind() == Kind.INTEGER && p.detail() instanceof IqType t && t.isInteger();
            if (p.kind() != Kind.STRING && !integer) {
                throw SqlTranslationException.unsupported(operator + " of " + kindName(p.kind()) + " (IQ writes it as"
                        + " text by its own format) is not supported yet", b.pos());
            }
            present.add(p);
        }
        IqType text = new IqType("VARCHAR");
        if (present.isEmpty()) {
            return Tx.value(SqlToPure.nullOf(Kind.STRING).vs(), Kind.STRING, true, text);
        }
        boolean nullable = true;
        List<ValueSpecification> texts = new ArrayList<>();
        ValueSpecification allNull = null;
        for (Tx p : present) {
            nullable &= p.nullable();
            ValueSpecification v = SqlToPure.valueOf(p);
            texts.add(text(p, v));
            ValueSpecification isNull = SqlToPure.call("isEmpty", v);
            allNull = allNull == null ? isNull : SqlToPure.call("and", allNull, isNull);
        }
        ValueSpecification joined = texts.size() == 1 ? texts.get(0) : AppliedFunction.infixRun("plus", texts);
        if (!nullable) {
            return Tx.value(joined, Kind.STRING, false, text);
        }
        return Tx.value(ifThen(java.util.Objects.requireNonNull(allNull), SqlToPure.empty(), joined), Kind.STRING,
                true, text);
    }

    /** A part of a join as text: a string as it is, an integer as its digits, a NULL as ''. */
    private static ValueSpecification text(Tx p, ValueSpecification v) {
        if (p.kind() == Kind.STRING) {
            return p.nullable() ? SqlToPure.call("coalesce", v, new CString("")) : v;
        }
        return p.nullable() ? ifThen(SqlToPure.call("isEmpty", v), new CString(""),
                SqlToPure.call("toString", SqlToPure.call("toOne", v))) : SqlToPure.call("toString", v);
    }

    /** {@code l op r} for {@code + - * / %} of two integers, IQ's way ({@link IqTypes}): the result's type, a check
     *  where the result may leave it, each operand widened to 64 bits first unless it is a constant or already wide; a
     *  NULL constant gives a NULL of the type IQ gives the other operand with itself. */
    private Tx integerArithmetic(SqlAst.Binary b, Tx l, Tx r) {
        String operator = switch (b.op()) {
            case PLUS -> "+";
            case MINUS -> "-";
            case TIMES -> "*";
            case DIVIDE -> "/";
            case MODULO -> "%";
            default -> throw new IllegalArgumentException("not integer arithmetic: " + b.op());
        };
        boolean leftNull = l.kind() == Kind.NULL;
        boolean rightNull = r.kind() == Kind.NULL;
        if (leftNull && rightNull) {
            return typedNull(b.op() == SqlAst.BinaryOp.TIMES ? "INTEGER" : "SMALLINT");
        }
        if (leftNull || rightNull) {
            IqType other = integerOperand(leftNull ? r : l, leftNull ? b.right() : b.left(), operator);
            String family = IqTypes.arithmetic(b.op(), other.family(), other.family());
            if (family == null) {
                throw resultRefused(operator, other.family(), other.family(), b.pos());
            }
            return typedNull(family);
        }
        IqType lt = integerOperand(l, b.left(), operator);
        IqType rt = integerOperand(r, b.right(), operator);
        String family = IqTypes.arithmetic(b.op(), lt.family(), rt.family());
        if (family == null) {
            throw resultRefused(operator, lt.family(), rt.family(), b.pos());
        }
        BigInteger[] range = IqTypes.range(b.op(), lt, rt);
        SqlAst.BinaryOp op = b.op();
        // a divisor that may be zero raises IQ's -628 (the lowering's guarded error)
        boolean zeroDivisor = (op == SqlAst.BinaryOp.DIVIDE || op == SqlAst.BinaryOp.MODULO)
                && rt.lo().signum() <= 0 && rt.hi().signum() >= 0;
        return checkedInteger(b, operator, new IqType(family), range[0], range[1], List.of(l, r),
                List.of(lt, rt), constants(List.of(b.left(), b.right()), List.of(l, r)), zeroDivisor, v -> switch (op) {
                    case PLUS -> AppliedFunction.infixRun("plus", v);
                    case MINUS -> AppliedFunction.infixRun("minus", v);
                    case TIMES -> AppliedFunction.infixRun("times", v);
                    case DIVIDE -> SqlToPure.integerQuotient(v.get(0), v.get(1));
                    // Pure's rem is a Number: the integer it is, as one
                    default -> SqlToPure.call("cast", SqlToPure.call("rem", v.get(0), v.get(1)),
                            SqlToPure.typeArg("Integer"));
                });
    }

    /**
     * An integer operation's translation: strict in its operands (each widened to 64 bits unless it is a constant or
     * already wide), checked against {@code type}'s range unless {@code lo..hi} (the range its operands allow) lies in
     * it. A BIGINT result is never checked: the database computes it in 64 bits and raises its own overflow first.
     * <ul>
     *   <li>A result that can only leave the range is IQ's error now when every operand is a constant
     *       ({@code constant}: literals, signs and arithmetic of literals, never NULL), as IQ folds one; otherwise
     *       the check is emitted and raises only where the operation is computed (never on a NULL).</li>
     *   <li>An operation that can raise (a result that may leave its type, a {@code zeroDivisor}) in the select list
     *       or ORDER BY of a statement with a row limit is refused: IQ computes the select list only for the rows it
     *       keeps, lite's databases may compute it for every row first.</li>
     * </ul>
     */
    private Tx checkedInteger(SqlAst.Expr node, String operator, IqType type, BigInteger lo, BigInteger hi,
            List<Tx> operands, List<IqType> types, boolean constant, boolean zeroDivisor,
            java.util.function.Function<List<ValueSpecification>, ValueSpecification> build) {
        SqlAst.Pos pos = node.pos();
        boolean certainlyOut = lo.compareTo(type.max()) > 0 || hi.compareTo(type.min()) < 0;
        if (certainlyOut && constant) {
            throw SqlTranslationException.invalid("the value of this " + operator + " is out of range for its type, "
                    + type.family() + overflowCode(), pos);
        }
        boolean leaves = lo.compareTo(type.min()) < 0 || hi.compareTo(type.max()) > 0;
        if ((leaves || zeroDivisor) && limited.contains(node)) {
            throw SqlTranslationException.unsupported("this " + operator + " can raise IQ's error" + (leaves
                    ? " -158 (out of range)" : " -628 (division by zero)") + " in the select list or ORDER BY of a"
                    + " statement with TOP, FIRST or START AT, which is not supported yet: IQ computes the select list"
                    + " only for the rows it keeps, lite's databases may compute it for the rows the limit drops", pos);
        }
        boolean check = leaves && !type.family().equals("BIGINT");
        boolean wide = false;
        List<Boolean> widen = new ArrayList<>();
        for (int i = 0; i < types.size(); i++) {
            IqType t = types.get(i);
            // a constant (one value, never NULL) is written as itself; anything else is widened unless it is wide
            boolean w = !t.wide() && !(t.exact() && !operands.get(i).nullable());
            widen.add(w);
            wide |= w || t.wide();
        }
        Tx result = SqlToPure.strict(Kind.INTEGER, operands, v -> {
            List<ValueSpecification> args = new ArrayList<>();
            for (int i = 0; i < v.size(); i++) {
                // floor: Pure's integer of a number, the database's 64-bit integer (exact below 2^53)
                args.add(widen.get(i) ? SqlToPure.call("floor", v.get(i)) : v.get(i));
            }
            ValueSpecification core = build.apply(args);
            return check ? inRange(core, type.min(), type.max()) : core;
        });
        BigInteger low = lo.max(type.min());
        BigInteger high = hi.min(type.max());
        if (low.compareTo(high) > 0) {
            // every value it computes raises: NULL is all it answers
            low = type.min();
            high = type.max();
        }
        return Tx.value(result.vs(), Kind.INTEGER, result.nullable(), IqType.integer(type.family(), low, high, wide));
    }

    /** Whether every one of {@code exprs} is a constant IQ folds (a literal, a sign or arithmetic of literals) whose
     *  translation is never NULL. */
    private static boolean constants(List<SqlAst.Expr> exprs, List<Tx> values) {
        for (int i = 0; i < exprs.size(); i++) {
            if (values.get(i).nullable() || !folded(exprs.get(i))) {
                return false;
            }
        }
        return true;
    }

    /** Whether {@code e} is a number IQ folds: a literal, a sign or arithmetic of literals. */
    private static boolean folded(SqlAst.Expr e) {
        return switch (e) {
            case SqlAst.IntegerLit n -> true;
            case SqlAst.NumericLit n -> true;
            case SqlAst.Unary u -> u.op() != SqlAst.UnaryOp.NOT && folded(u.operand());
            case SqlAst.Binary b -> switch (b.op()) {
                case PLUS, MINUS, TIMES, DIVIDE, MODULO -> folded(b.left()) && folded(b.right());
                default -> false;
            };
            default -> false;
        };
    }

    // ------------------------------------------------------------------ the size of a translation

    /** The most nodes one value's Pure may have written out (each repeated operand counted at each use): a
     *  checked operation repeats its operands (its NULL test, its range test, its value), so nested ones grow
     *  fourfold per level and a quotient twentyfold; past this, the translation is refused rather than built. A sum of
     *  six SMALLINT columns (about 17,000 nodes) is about 120 KB of SQL; a quotient's SQL is the densest, about 20
     *  bytes a node. */
    static final long LARGEST = 20_000;

    /** {@code t}, unless its Pure written out exceeds {@link #LARGEST} nodes: refused by name. */
    private static Tx bounded(Tx t, SqlAst.Pos pos) {
        long size = size(t.vs(), new IdentityHashMap<>());
        if (t.whenFalse() != null) {
            size = Math.max(size, size(t.falseWhen(), new IdentityHashMap<>()));
        }
        if (size > LARGEST) {
            throw SqlTranslationException.unsupported("this expression nests too many operations IQ checks for"
                    + " overflow or a zero divisor to be translated: each repeats its operands in lite's SQL, which"
                    + " would hold more than " + LARGEST + " nodes; split it into fewer operations (or compute it over"
                    + " BIGINT values, which need no check)", pos);
        }
        return t;
    }

    /** The nodes of {@code vs} written out: a node shared by several parents counts once for each. */
    private static long size(ValueSpecification vs, Map<ValueSpecification, Long> memo) {
        Long known = memo.get(vs);
        if (known != null) {
            return known;
        }
        long n = 1;
        List<ValueSpecification> children = switch (vs) {
            case AppliedFunction f -> f.parameters();
            case com.legend.protocol.spec.AppliedProperty p -> List.of(p.receiver());
            case com.legend.protocol.spec.LambdaFunction l -> l.body();
            case com.legend.protocol.spec.PureCollection c -> c.values();
            default -> List.of();
        };
        for (ValueSpecification c : children) {
            n = Math.min(Long.MAX_VALUE / 2, n + size(c, memo));
        }
        memo.put(vs, n);
        return n;
    }

    /** {@code x}, or IQ's error -158 when it lies outside {@code min..max}: an empty value read as one raises, as
     *  {@code SqlToPure}'s narrow integer casts do. */
    private static ValueSpecification inRange(ValueSpecification x, BigInteger min, BigInteger max) {
        return ifThen(SqlToPure.call("or", SqlToPure.call("lessThan", x, SqlToPure.integer(min)),
                SqlToPure.call("greaterThan", x, SqlToPure.integer(max))), raise(), x);
    }

    /** A value that raises when computed: an empty value read as one, typed Integer. */
    private static ValueSpecification raise() {
        return SqlToPure.call("toOne", SqlToPure.call("cast", SqlToPure.empty(), SqlToPure.typeArg("Integer")));
    }

    /** {@code if(test, |then, |otherwise)}. */
    private static ValueSpecification ifThen(ValueSpecification test, ValueSpecification then,
            ValueSpecification otherwise) {
        return SqlToPure.call("if", test, SqlToPure.thunk(then), SqlToPure.thunk(otherwise));
    }

    /** A NULL of the integer type {@code family}. */
    private static Tx typedNull(String family) {
        return Tx.value(SqlToPure.nullOf(Kind.INTEGER).vs(), Kind.INTEGER, true, new IqType(family));
    }

    /** The IQ integer type of an operand of {@code operator}: anything else is refused by name. */
    private static IqType integerOperand(Tx t, SqlAst.Expr e, String operator) {
        if (t.kind() == Kind.INTEGER && t.detail() instanceof IqType type) {
            if (type.isInteger()) {
                return type;
            }
            if (type.family().equals("NUMERIC") || type.family().equals("DECIMAL")) {
                throw numericRefused(operator, e.pos());
            }
            throw SqlTranslationException.unsupported(operator + " of a " + type.spelling() + " is not supported yet"
                    + " (IQ's result is an unsigned or NUMERIC type)", e.pos());
        }
        if (t.kind() == Kind.DECIMAL || t.kind() == Kind.NUMBER) {
            throw numericRefused(operator, e.pos());
        }
        if (t.kind() == Kind.FLOAT) {
            throw SqlTranslationException.unsupported(operator + " of a DOUBLE is not supported yet", e.pos());
        }
        if (t.kind() == Kind.INTEGER) {
            throw SqlTranslationException.unsupported(operator + " of an integer whose IQ type is not known is not"
                    + " supported", e.pos());
        }
        throw SqlTranslationException.unsupported(operator + " of " + kindName(t.kind()) + " is not supported yet (IQ"
                + " converts " + (t.kind() == Kind.STRING ? "a string to NUMERIC" : "it by its own rules") + ")",
                e.pos());
    }

    private static SqlTranslationException numericRefused(String operator, SqlAst.Pos pos) {
        return SqlTranslationException.unsupported(operator + " of a NUMERIC is not supported yet: IQ's NUMERIC result"
                + " types are the research's D-N1, not yet verified", pos);
    }

    private static SqlTranslationException resultRefused(String operator, String l, String r, SqlAst.Pos pos) {
        return SqlTranslationException.unsupported(l + " " + operator + " " + r + " is not supported yet: IQ types it"
                + " NUMERIC or unsigned", pos);
    }

    /** A comparison: with a NULL constant UNKNOWN (IS [NOT] NULL for = and != under ansinull Off, but for a CASE's or
     *  an IF's condition in SQL Anywhere's front end), else of two values of one kind. */
    private Tx comparison(SqlAst.BinaryOp op, Tx l, Tx r, boolean leftNull, boolean rightNull,
            SqlAst.@com.legend.base.Nullable Expr at, SqlAst.Pos pos) {
        if (leftNull || rightNull) {
            boolean rewritten = !profile.ansinull() && (op == SqlAst.BinaryOp.EQ || op == SqlAst.BinaryOp.NE)
                    && !(engine == Engine.SA && at != null && caseConditions.contains(at));
            if (rewritten) {
                ValueSpecification v = SqlToPure.valueOf(leftNull ? r : l);
                Tx isNull = Tx.condition(SqlToPure.call("isEmpty", v), SqlToPure.call("isNotEmpty", v), false);
                return op == SqlAst.BinaryOp.EQ ? isNull : SqlToPure.not(isNull);
            }
            return Tx.condition(new CBoolean(false), new CBoolean(false), true);
        }
        Kind lk = l.kind();
        Kind rk = r.kind();
        if (lk == Kind.STRING && rk == Kind.STRING) {
            return strings(op, l, r, pos);
        }
        boolean comparable = lk.numeric() && rk.numeric() && lk != Kind.NUMBER && rk != Kind.NUMBER
                || lk == Kind.DATE && rk == Kind.DATE || lk == Kind.DATETIME && rk == Kind.DATETIME;
        if (!comparable) {
            throw SqlTranslationException.unsupported("comparing " + kindName(lk) + " with " + kindName(rk)
                    + " is not supported yet (IQ's conversions between kinds are a later slice's)", pos);
        }
        int[] left = digits(l);
        int[] right = digits(r);
        if (left != null && right != null && Math.max(left[0], right[0]) + Math.max(left[1], right[1]) > 38) {
            // the database compares the two as one decimal holding both, which lite's 38 digits cannot
            throw SqlTranslationException.unsupported("comparing these two numbers needs a decimal of more than 38"
                    + " digits, which lite does not hold", pos);
        }
        return SqlToPure.compare(op, l, r);
    }

    /** An exact number's digits before and after its point, by its IQ type; null for any other value. */
    private static int @com.legend.base.Nullable [] digits(Tx t) {
        if (!(t.detail() instanceof IqType type)) {
            return null;
        }
        if (type.isInteger()) {
            return new int[] {switch (type.family()) {
                case "BIT" -> 1;
                case "SMALLINT" -> 5;
                case "INTEGER" -> 10;
                case "BIGINT" -> 19;
                default -> throw new IllegalStateException("not an integer type: " + type.family());
            }, 0};
        }
        String f = type.family();
        if ((f.equals("NUMERIC") || f.equals("DECIMAL")) && type.size() >= 0) {
            String sp = type.spelling();
            int comma = sp.indexOf(',');
            int scale = comma < 0 ? 0 : Integer.parseInt(sp.substring(comma + 1, sp.indexOf(')')).strip());
            return new int[] {type.size() - scale, scale};
        }
        return null;
    }

    /** Two strings: = and != ignore trailing blanks (BLANK PADDING ON): equal when one starts with the other and the
     *  two are equal with their spaces taken out (so the longer one's rest is spaces only, exactly); the rest refused
     *  by name (above). */
    private Tx strings(SqlAst.BinaryOp op, Tx l, Tx r, SqlAst.Pos pos) {
        if (!profile.caseSensitive()) {
            throw SqlTranslationException.unsupported("comparing strings in profile " + profile.spelling()
                    + "'s case-insensitive database needs an ASCII-only case fold, which is not built yet", pos);
        }
        if (op != SqlAst.BinaryOp.EQ && op != SqlAst.BinaryOp.NE) {
            throw SqlTranslationException.unsupported("ordering strings is not supported yet: IQ ignores trailing"
                    + " blanks first, which needs an ASCII-only right trim, not built yet", pos);
        }
        if (!profile.blankPadding()) {
            return SqlToPure.compare(op, l, r);
        }
        Tx equal = SqlToPure.strictTest(List.of(l, r), v -> {
            ValueSpecification prefix = SqlToPure.call("or", SqlToPure.call("startsWith", v.get(0), v.get(1)),
                    SqlToPure.call("startsWith", v.get(1), v.get(0)));
            ValueSpecification spaceless = SqlToPure.call("equal",
                    SqlToPure.call("replace", v.get(0), new CString(" "), new CString("")),
                    SqlToPure.call("replace", v.get(1), new CString(" "), new CString("")));
            return SqlToPure.call("and", prefix, spaceless);
        });
        return op == SqlAst.BinaryOp.EQ ? equal : SqlToPure.not(equal);
    }

    private static String kindName(Kind k) {
        return switch (k) {
            case BOOLEAN -> "a condition";
            case STRING -> "a string";
            case DATE -> "a date";
            case DATETIME -> "a timestamp";
            case NULL -> "NULL";
            case INTEGER, WHOLE, FLOAT, DECIMAL, NUMBER, INEXACT -> "a number";
            case JSON, OTHER -> "a value of this type";
        };
    }

    // ------------------------------------------------------------------ one type for alternatives

    /**
     * IQ's unification (the research's TYPING §8: CASE, IF, ISNULL and COALESCE share it), for the pairs the island
     * proves: integers take the smallest type holding them all (BIT with another gives the other); strings a VARCHAR
     * when one is, else a CHAR of the longest; dates with dates, timestamps with timestamps, doubles with doubles; an
     * untyped NULL takes the others' type, and all NULL is a SMALLINT. Under the IQ engine a CHAR result whose values
     * differ in length is refused: whether IQ pads such a result as it pads a CHAR(n) column (the research's D-S1) is
     * not decided. A NUMERIC (the research's D-N1) and every mix of kinds (IQ converts: a string with a number to
     * NUMERIC, with a date to TIMESTAMP) are refused by name.
     */
    @Override
    public SqlToPure.Common unify(String what, List<SqlAst.Expr> exprs, List<Tx> values, SqlAst.Pos pos) {
        values.forEach(v -> bounded(v, pos));
        Kind kind = null;
        String family = null;
        List<Integer> charLengths = new ArrayList<>();
        BigInteger lo = null;
        BigInteger hi = null;
        boolean wide = true;
        for (Tx v : values) {
            if (v.kind() == Kind.NULL) {
                continue;
            }
            if (!(v.detail() instanceof IqType t)) {
                throw SqlTranslationException.unsupported(what + " of a value whose IQ type is not known is not"
                        + " supported", pos);
            }
            if (kind != null && kind != v.kind()) {
                throw SqlTranslationException.unsupported(what + " of " + kindName(kind) + " and " + kindName(v.kind())
                        + " is not supported yet (IQ converts one to the other's type by its own rules)", pos);
            }
            kind = v.kind();
            switch (v.kind()) {
                case INTEGER -> {
                    if (!t.isInteger()) {
                        throw t.family().equals("NUMERIC") ? numericRefused(what, pos)
                                : SqlTranslationException.unsupported(what + " of a " + t.spelling() + " is not"
                                        + " supported yet", pos);
                    }
                    family = family == null ? t.family() : IqTypes.unified(family, t.family());
                    lo = lo == null ? t.lo() : lo.min(t.lo());
                    hi = hi == null ? t.hi() : hi.max(t.hi());
                    wide &= t.wide();
                }
                case STRING -> {
                    String f = t.family();
                    if (f.equals("CHAR")) {
                        charLengths.add(t.size());
                        family = family == null ? "CHAR" : family;
                    } else if (f.equals("VARCHAR")) {
                        family = "VARCHAR";
                    } else {
                        throw SqlTranslationException.unsupported(what + " of a " + t.spelling() + " is not supported"
                                + " yet", pos);
                    }
                }
                case DATE, DATETIME -> family = v.kind() == Kind.DATE ? "DATE" : "TIMESTAMP";
                case FLOAT -> {
                    if (!t.family().equals("DOUBLE")) {
                        throw SqlTranslationException.unsupported(what + " of a " + t.spelling() + " is not supported"
                                + " yet", pos);
                    }
                    family = "DOUBLE";
                }
                case DECIMAL, NUMBER -> throw numericRefused(what, pos);
                default -> throw SqlTranslationException.unsupported(what + " of " + kindName(v.kind()) + " is not"
                        + " supported yet", pos);
            }
        }
        if (kind == null) {
            // every value an untyped NULL: IQ's SMALLINT NULL
            kind = Kind.INTEGER;
            family = "SMALLINT";
            lo = IqType.SMALLINT_MIN;
            hi = IqType.SMALLINT_MAX;
            wide = false;
        }
        IqType type;
        if (kind == Kind.STRING && "CHAR".equals(family)) {
            int longest = Collections.max(charLengths);
            if (engine == Engine.IQ && charLengths.stream().anyMatch(n -> n != longest)) {
                throw SqlTranslationException.unsupported(what + " of strings of different lengths is IQ's CHAR("
                        + longest + "): whether the IQ engine pads it, as the research's D-S1 pads a CHAR(n) column, is"
                        + " not decided, so it is not supported yet", pos);
            }
            type = new IqType("CHAR(" + longest + ")");
        } else if (kind == Kind.INTEGER) {
            type = IqType.integer(java.util.Objects.requireNonNull(family), java.util.Objects.requireNonNull(lo),
                    java.util.Objects.requireNonNull(hi), wide);
        } else {
            type = new IqType(java.util.Objects.requireNonNull(family));
        }
        List<Tx> unified = new ArrayList<>();
        for (Tx v : values) {
            unified.add(v.kind() == Kind.NULL ? Tx.value(SqlToPure.nullOf(kind).vs(), kind, true, type) : v);
        }
        return new SqlToPure.Common(unified, kind, type);
    }

    // ------------------------------------------------------------------ the catalog

    /** IQ's scalar functions the catalog holds, each with how many arguments it takes. */
    private enum Catalog {
        ISNULL(2, Integer.MAX_VALUE), COALESCE(2, Integer.MAX_VALUE), IFNULL(2, 3), NULLIF(2, 2);

        final int min;
        final int max;

        Catalog(int min, int max) {
            this.min = min;
            this.max = max;
        }

        /** The function {@code name} names, matched as IQ matches names (ASCII case folded); null for none. */
        static @com.legend.base.Nullable Catalog named(String name) {
            String key = IqLexer.lower(name);
            for (Catalog c : values()) {
                if (IqLexer.lower(c.name()).equals(key)) {
                    return c;
                }
            }
            return null;
        }
    }

    /** IQ's scalar functions read so far: ISNULL and COALESCE (one function in IQ), IFNULL, NULLIF. Any other name is
     *  refused by name (by the translator: "function f(...) is not supported yet"). */
    @Override
    public InputDialect.@com.legend.base.Nullable FunctionRule function(String name) {
        Catalog fn = Catalog.named(name);
        if (fn == null) {
            return null;
        }
        return switch (fn) {
            case ISNULL -> (f, args, at) -> bounded(coalesce("ISNULL", f, args), f.pos());
            case COALESCE -> (f, args, at) -> bounded(coalesce("COALESCE", f, args), f.pos());
            case IFNULL -> (f, args, at) -> bounded(ifnull(f, args), f.pos());
            case NULLIF -> (f, args, at) -> bounded(nullif(f, args), f.pos());
        };
    }

    private static void arguments(String fn, SqlAst.FunctionCall f, List<Tx> args, int min, int max) {
        if (args.size() < min || args.size() > max) {
            throw SqlTranslationException.invalid("wrong number of arguments to " + fn + code(-154, "37505"),
                    f.pos());
        }
    }

    /** {@code ISNULL(a, b, ...)} and {@code COALESCE(a, b, ...)}: the first that is not NULL, as their unified type.
     *  The arguments after the first that is never NULL are never read. */
    private Tx coalesce(String fn, SqlAst.FunctionCall f, List<Tx> arguments) {
        arguments(fn, f, arguments, 2, Integer.MAX_VALUE);
        SqlToPure.Common common = unify(fn, f.args(), arguments, f.pos());
        List<Tx> args = new ArrayList<>();
        for (int i = 0; i < common.values().size(); i++) {
            Tx a = common.values().get(i);
            if (arguments.get(i).kind() == Kind.NULL) {
                continue;
            }
            args.add(a);
            if (!a.nullable()) {
                break;
            }
        }
        if (args.isEmpty()) {
            return Tx.value(SqlToPure.nullOf(common.kind()).vs(), common.kind(), true, common.detail());
        }
        if (args.size() == 1) {
            Tx only = args.get(0);
            return Tx.value(SqlToPure.valueOf(only), common.kind(), only.nullable(), common.detail());
        }
        boolean nullable = true;
        for (Tx a : args) {
            nullable &= a.nullable();
        }
        ValueSpecification tail = SqlToPure.valueOf(args.get(args.size() - 1));
        int i = args.size() - 1;
        while (i > 0) {
            int take = Math.min(3, i);
            List<ValueSpecification> group = new ArrayList<>();
            for (int k = i - take; k < i; k++) {
                group.add(SqlToPure.valueOf(args.get(k)));
            }
            group.add(tail);
            tail = SqlToPure.call("coalesce", group.toArray(new ValueSpecification[0]));
            i -= take;
        }
        return Tx.value(tail, common.kind(), nullable, common.detail());
    }

    /** IQ's {@code IFNULL(a, b)}: {@code b} when {@code a} is NULL, else NULL (not COALESCE), typed as {@code b};
     *  {@code IFNULL(a, b, c)}: {@code b} when {@code a} is NULL, else {@code c}, as their unified type. */
    private Tx ifnull(SqlAst.FunctionCall f, List<Tx> args) {
        arguments("IFNULL", f, args, 2, 3);
        Tx a = args.get(0);
        SqlToPure.Common common = args.size() == 3
                ? unify("IFNULL", f.args().subList(1, 3), args.subList(1, 3), f.pos())
                : unify("IFNULL", f.args().subList(1, 2), List.of(args.get(1)), f.pos());
        Tx then = common.values().get(0);
        Tx otherwise = args.size() == 3 ? common.values().get(1)
                : Tx.value(SqlToPure.nullOf(common.kind()).vs(), common.kind(), true, common.detail());
        if (a.kind() == Kind.NULL) {
            return Tx.value(SqlToPure.valueOf(then), common.kind(), then.nullable(), common.detail());
        }
        if (!a.nullable()) {
            return Tx.value(SqlToPure.valueOf(otherwise), common.kind(), otherwise.nullable(), common.detail());
        }
        ValueSpecification vs = ifThen(SqlToPure.call("isEmpty", SqlToPure.valueOf(a)), SqlToPure.valueOf(then),
                SqlToPure.valueOf(otherwise));
        return Tx.value(vs, common.kind(), then.nullable() || otherwise.nullable(), common.detail());
    }

    /** IQ's {@code NULLIF(a, b)}: NULL when {@code a = b}, else {@code a}, typed as {@code a}; {@code b} is read as
     *  {@code a}'s type (an integer outside its range is IQ's error -158). A CHAR {@code a} (the conversion may cut
     *  {@code b}) and every pair of kinds IQ converts are refused by name. */
    private Tx nullif(SqlAst.FunctionCall f, List<Tx> args) {
        arguments("NULLIF", f, args, 2, 2);
        Tx a = args.get(0);
        Tx b = args.get(1);
        if (a.kind() == Kind.NULL) {
            return typedNull("SMALLINT");
        }
        if (!(a.detail() instanceof IqType at)) {
            throw SqlTranslationException.unsupported("NULLIF of a value whose IQ type is not known is not supported",
                    f.pos());
        }
        if (b.kind() == Kind.NULL) {
            return a;
        }
        if (a.kind() == Kind.STRING && at.family().equals("CHAR")) {
            throw SqlTranslationException.unsupported("NULLIF of a CHAR is not supported yet: IQ reads the second"
                    + " argument as the CHAR, which may cut it", f.pos());
        }
        if (a.kind() == Kind.INTEGER) {
            IqType bt = integerOperand(b, f.args().get(1), "NULLIF");
            if (!at.isInteger()) {
                throw numericRefused("NULLIF", f.pos());
            }
            if (bt.lo().compareTo(at.min()) < 0 || bt.hi().compareTo(at.max()) > 0) {
                // the second argument read as the first's type: a value outside its range is IQ's -158
                b = checkedInteger(f, "NULLIF", new IqType(at.family()), bt.lo(), bt.hi(), List.of(b), List.of(bt),
                        constants(List.of(f.args().get(1)), List.of(b)), false, v -> v.get(0));
            }
        } else if (a.kind() != b.kind() || a.kind() == Kind.DECIMAL || a.kind() == Kind.NUMBER) {
            throw SqlTranslationException.unsupported("NULLIF of " + kindName(a.kind()) + " and " + kindName(b.kind())
                    + " is not supported yet", f.pos());
        }
        Tx equal = comparison(SqlAst.BinaryOp.EQ, a, b, false, false, null, f.pos());
        ValueSpecification vs = ifThen(equal.vs(), SqlToPure.nullOf(a.kind()).vs(), SqlToPure.valueOf(a));
        return Tx.value(vs, a.kind(), true, a.detail());
    }

    @Override
    public Tx cast(SqlAst.Cast c, SqlToPure.At at) {
        throw SqlTranslationException.unsupported("CAST is not supported yet", c.pos());
    }

    @Override
    public Tx like(SqlAst.Like l, SqlToPure.At at) {
        throw SqlTranslationException.unsupported("LIKE is not supported yet", l.pos());
    }

    /** Strings are no key yet: IQ orders and tells them apart ignoring trailing blanks (and, in a {@code sqla17}
     *  profile, case), which needs the natives not built yet. */
    @Override
    public void key(String clause, Kind kind, SqlAst.Pos pos) {
        if (kind == Kind.STRING) {
            throw SqlTranslationException.unsupported(clause + " over strings is not supported yet: IQ ignores trailing"
                    + " blanks" + (profile.caseSensitive() ? "" : " and case") + " there, which needs natives not built"
                    + " yet", pos);
        }
        if (kind == Kind.OTHER || kind == Kind.JSON) {
            throw SqlTranslationException.unsupported(clause + " over a value of this type is not supported yet", pos);
        }
    }

    // ------------------------------------------------------------------ row limits

    /**
     * A row limit's count, as {@link IqParser} reads one: TOP's ({@code LIMIT}), or START AT's less one
     * ({@code OFFSET}). It is a constant: integers and {@code + - *}, which IQ types and checks as it does any constant
     * arithmetic (a result out of its type's range is IQ's error -158), and which the planner folds. A literal
     * {@code TOP 0}, a negative TOP and a START AT below 1 are IQ's error -674 (the research's D-Q1: a TOP expression
     * worth 0 gives no rows).
     */
    @Override
    public ValueSpecification rowCount(SqlAst.Expr count, String clause) {
        boolean offset = clause.equals("OFFSET");
        String iq = offset ? "START AT" : "TOP";
        if (!offset && count instanceof SqlAst.IntegerLit n && n.value().signum() == 0) {
            throw SqlTranslationException.invalid("a literal TOP 0 is IQ's error" + code(-674, "09W07"),
                    count.pos());
        }
        IqType type = constant(count);
        if (type.hi().signum() < 0) {
            throw SqlTranslationException.invalid(iq + (offset ? " counts from 1" : " cannot be negative")
                    + code(-674, "09W07"), count.pos());
        }
        if (unordered && (!offset || !counted)) {
            // after IQ's own errors in either count (START AT's is read first): the rows kept are IQ's choice
            throw SqlTranslationException.unsupported("TOP, FIRST or START AT without ORDER BY is not supported: which"
                    + " rows IQ keeps is its plan's choice; order the query", count.pos());
        }
        return pure(count);
    }

    /** A row limit's constant, typed and checked as IQ types constant arithmetic: its type, whose range is its
     *  value. */
    private static IqType constant(SqlAst.Expr e) {
        return switch (e) {
            case SqlAst.IntegerLit n -> IqTypes.ofIntegerConstant(n.value());
            case SqlAst.Binary b -> {
                IqType l = constant(b.left());
                IqType r = constant(b.right());
                String family = java.util.Objects.requireNonNull(IqTypes.arithmetic(b.op(), l.family(), r.family()));
                IqType type = new IqType(family);
                BigInteger[] range = IqTypes.range(b.op(), l, r);
                if (range[0].compareTo(type.min()) < 0 || range[1].compareTo(type.max()) > 0) {
                    throw SqlTranslationException.invalid("the row limit's arithmetic is out of range for "
                            + family + overflowCode(), b.pos());
                }
                yield IqType.integer(family, range[0], range[1], false);
            }
            default -> throw new IllegalStateException("not a row limit's count: " + e);
        };
    }

    /** A row limit's constant as Pure arithmetic, for the planner to fold. */
    private static ValueSpecification pure(SqlAst.Expr e) {
        return switch (e) {
            case SqlAst.IntegerLit n -> SqlToPure.integer(n.value());
            case SqlAst.Binary b -> AppliedFunction.infixRun(b.op() == SqlAst.BinaryOp.PLUS ? "plus"
                    : b.op() == SqlAst.BinaryOp.MINUS ? "minus" : "times", List.of(pure(b.left()), pure(b.right())));
            default -> throw new IllegalStateException("not a row limit's count: " + e);
        };
    }

    // ------------------------------------------------------------------ IQ's refusals, in this dialect's words

    @Override
    public String message(Problem problem, Object... args) {
        return switch (problem) {
            case NO_SUCH_QUALIFIED_COLUMN -> "no column '" + args[1] + "' in '" + args[0] + "'" + code(-143, "52003");
            case NO_SUCH_COLUMN -> "no column '" + args[0] + "' in the query's tables" + code(-143, "52003");
            case AMBIGUOUS_COLUMN -> "the column '" + args[0] + "' is in more than one of the query's tables: qualify"
                    + " it" + code(-144, "52002");
            case AMBIGUOUS_TABLE, DUPLICATE_TABLE -> "two of the query's tables are named '" + args[0] + "'"
                    + code(-139, "52012");
            case NO_SUCH_TABLE -> "no table or correlation name '" + args[0] + "' in the query" + code(-142, "52W02");
            case NOT_GROUPED -> "'" + args[0] + "' is neither grouped nor aggregated";
            case DUPLICATE_USING_COLUMN -> "the column '" + args[0] + "' is twice in the join's " + args[1] + " side";
            case NO_SUCH_USING_COLUMN -> "the join's " + args[1] + " side has no column '" + args[0] + "'";
            case NO_SUCH_WINDOW -> "no window named '" + args[0] + "'";
        };
    }
}
