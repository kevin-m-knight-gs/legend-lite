// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.protocol.sql.iq;

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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * SAP IQ 16.2 as {@code SqlToPure} reads it: the {@code #IQ{...}#} island's dialect (docs/IQ_SQL_STUDY_2026_10_13.md
 * §4-§5), under one of the closed set of {@link IqProfile}s and one of IQ's two {@link Engine}s. Its reader is
 * {@link IqParser}; its rules, so far (M0's first slice):
 * <ul>
 *   <li><b>Names</b> match without regard to ASCII case, quoted or not (IQ's identifiers are case-insensitive, and fold
 *       A-Z only); a name two candidates match is an ambiguity. An unquoted alias keeps its case. A column shows as
 *       its reference is written ({@code SELECT ID} is a column {@code ID}); any other unaliased item as IQ names it,
 *       its own rendering of the item ({@code 1.50} shows as {@code 1.5}, {@code 'a'} as {@code 'a'}), or the item is
 *       refused where that rendering is not known. A select-list alias is visible to WHERE and to the select list in
 *       IQ, before a column of its name: a reference that would read one is refused, not yet read.</li>
 *   <li><b>NULL</b> is the lowest value: first ascending, last descending. A bare NULL is a SMALLINT NULL. Under
 *       {@code ansinull} Off (a TDS login), {@code x = NULL} is {@code x IS NULL} and {@code x != NULL} is
 *       {@code x IS NOT NULL} in every search condition (measured on SQL Anywhere 17, and the research's D-L1 for the
 *       IQ engine); any other comparison with NULL is UNKNOWN.</li>
 *   <li><b>Strings</b> compare case-sensitively in an IQ profile's database, ignoring trailing blanks (BLANK PADDING
 *       ON): {@code =} holds when one value starts with the other and the rest is spaces. Ordering
 *       strings ({@code <}, ORDER BY) and DISTINCT over them need an ASCII-only right trim (IQ ignores the blanks
 *       before comparing, which no padding reproduces for characters below the space), and every string comparison
 *       of a {@code sqla17} profile's case-insensitive database needs an ASCII-only case fold: natives not built yet
 *       (the study's O1), so each is refused by name. Under the IQ engine a CHAR(n) column's value is padded with
 *       blanks to n (the research's D-S1), which the source read computes once, so every reference sees it.</li>
 *   <li><b>Numbers</b>: an integer constant is an Integer (a NUMERIC beyond a BIGINT), a constant with a point or an
 *       exponent a Decimal (IQ's NUMERIC, the research's D-N5). Comparisons of numbers, of dates, of timestamps are
 *       Pure's; IQ's conversions between kinds (a string compared with a number or a date, a date with a timestamp, a
 *       BIT with an integer) and its arithmetic (typing, overflow, integer division: the research's D-N3) are the next
 *       slices', refused by name until then.</li>
 * </ul>
 * Refusals that IQ makes too carry IQ's SQLCODE and SQLSTATE beside the message, which is this dialect's own wording.
 */
public final class IqDialect implements InputDialect {

    /** Which rules a statement follows (the research's SEMANTICS §2): IQ's engine for a query over IQ tables, SQL
     *  Anywhere's front end (IQ's own) for a query without FROM. Only the typing and function layer reads it. */
    public enum Engine { IQ, SA }

    /** The store types a column is read as stored in: every other refuses its table by name (CHAR is padded, below). */
    private static final Set<String> READ_AS_STORED = Set.of("SMALLINT", "INTEGER", "BIGINT", "TINYINT", "DECIMAL",
            "NUMERIC", "DOUBLE", "FLOAT", "REAL", "VARCHAR", "DATE", "TIMESTAMP", "BIT");

    /** A {@code TypeDetail}: a FROM source column's declared store type, as the store spells it ({@code CHAR(5)}). */
    public record IqType(String spelling) implements InputDialect.TypeDetail {

        /** The type's name without its size: {@code CHAR}, {@code NUMERIC}, ... */
        public String family() {
            int paren = spelling.indexOf('(');
            return (paren < 0 ? spelling : spelling.substring(0, paren)).strip().toUpperCase(java.util.Locale.ROOT);
        }

        /** A sized type's first size: CHAR(n)'s n, NUMERIC(p,s)'s p. */
        public int size() {
            int open = spelling.indexOf('(');
            int end = spelling.indexOf(',', open);
            return Integer.parseInt(spelling.substring(open + 1, end < 0 ? spelling.indexOf(')') : end).strip());
        }
    }

    private final IqProfile profile;
    private final @com.legend.base.Nullable Engine engine;

    private IqDialect(IqProfile profile, @com.legend.base.Nullable Engine engine) {
        this.profile = profile;
        this.engine = engine;
    }

    /** The dialect of an island written for {@code profile}: each statement's engine chosen by its FROM clause
     *  ({@link #engineOf}, the study's I3: {@code engine=auto}). */
    public static IqDialect of(IqProfile profile) {
        return new IqDialect(profile, null);
    }

    /** The dialect under {@code profile} with {@code engine}'s rules whatever the statement. */
    public static IqDialect of(IqProfile profile, Engine engine) {
        return new IqDialect(profile, engine);
    }

    public IqProfile profile() {
        return profile;
    }

    /** The engine this dialect reads by; null while it is chosen per statement. */
    public @com.legend.base.Nullable Engine engine() {
        return engine;
    }

    /** The engine IQ runs {@code statement} in: SQL Anywhere's front end when it has no FROM, IQ's otherwise (a table
     *  of the island's is an IQ table; the research's D-E1 gives a mix to IQ). */
    public static Engine engineOf(SqlAst.Statement statement) {
        return statement instanceof SqlAst.Select s && s.from().isEmpty() ? Engine.SA : Engine.IQ;
    }

    /** IQ's code for a syntax error, as this dialect's messages carry it. */
    static String syntaxCode() {
        return code(-131, "42W04");
    }

    private static String code(int sqlcode, String sqlstate) {
        return " (IQ: SQLCODE " + sqlcode + ", SQLSTATE " + sqlstate + ")";
    }

    @Override
    public SqlAst.Statement parse(String sql) {
        SqlAst.Statement statement = IqParser.parse(sql, profile);
        if (statement instanceof SqlAst.Select s) {
            noAliasRead(s);
        }
        return statement;
    }

    @Override
    public InputDialect reading(SqlAst.Statement statement) {
        return engine != null ? this : new IqDialect(profile, engineOf(statement));
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

    /** A select-list alias read in WHERE or in the select list: IQ reads the alias's expression there (a reference
     *  names an alias before a column), which is not read yet. An alias of the column of its own name reads the same
     *  either way. */
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
        for (SqlAst.ColumnRef ref : refs) {
            if (ref.parts().size() != 1) {
                continue;
            }
            String name = IqLexer.lower(ref.parts().get(0).text());
            SqlAst.SelectExpr aliased = aliases.get(name);
            if (aliased != null && !(aliased.expr() instanceof SqlAst.ColumnRef c
                    && IqLexer.lower(c.parts().get(c.parts().size() - 1).text()).equals(name))) {
                throw SqlTranslationException.unsupported("'" + ref.parts().get(0).text() + "' names the select"
                        + " list's alias, which IQ reads before a column of that name: reading an alias outside ORDER"
                        + " BY is not supported yet", ref.pos());
            }
        }
    }

    private static void columnRefs(SqlAst.Expr e, List<SqlAst.ColumnRef> out) {
        switch (e) {
            case SqlAst.ColumnRef ref -> out.add(ref);
            case SqlAst.Binary b -> {
                columnRefs(b.left(), out);
                columnRefs(b.right(), out);
            }
            case SqlAst.Unary u -> columnRefs(u.operand(), out);
            case SqlAst.IsNull n -> columnRefs(n.operand(), out);
            default -> {
            }
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
     *  the TPC-H reports run on IQ show it). A store type the island does not read yet refuses the source by name. */
    @Override
    public SqlToPure.@com.legend.base.Nullable Tx read(String column, Tx stored, SqlAst.Pos pos) {
        if (!(stored.detail() instanceof IqType type)) {
            return null;
        }
        String family = type.family();
        if (family.equals("CHAR")) {
            if (engine != Engine.IQ) {
                return null;
            }
            int length = type.size();
            Tx padded = SqlToPure.strict(Kind.STRING, List.of(stored), v -> SqlToPure.call("rpad", v.get(0),
                    new CInteger((long) length), new CString(" ")));
            return Tx.value(padded.vs(), Kind.STRING, padded.nullable(), type);
        }
        if (!READ_AS_STORED.contains(family)) {
            throw SqlTranslationException.unsupported("the column '" + column + "' is of store type " + type.spelling()
                    + ", which the #IQ{...}# island does not read yet", pos);
        }
        return null;
    }

    /** An integer constant is an Integer (a NUMERIC, Pure's Decimal, beyond a BIGINT); a numeric one a Decimal; a
     *  string a String; a bare NULL IQ's SMALLINT NULL. */
    @Override
    public Tx literal(SqlAst.Expr constant) {
        return switch (constant) {
            case SqlAst.IntegerLit n -> n.value().bitLength() < Long.SIZE
                    ? Tx.value(SqlToPure.integer(n.value()), Kind.INTEGER, false)
                    : Tx.value(new CDecimal(new BigDecimal(n.value())), Kind.DECIMAL, false);
            case SqlAst.NumericLit n -> Tx.value(new CDecimal(n.value()), Kind.DECIMAL, false);
            case SqlAst.StringLit s -> Tx.value(new CString(s.value()), Kind.STRING, false);
            case SqlAst.NullLit n -> Tx.value(SqlToPure.nullOf(Kind.INTEGER).vs(), Kind.NULL, true);
            default -> throw SqlTranslationException.unsupported("this constant is not IQ's", constant.pos());
        };
    }

    // ------------------------------------------------------------------ meanings

    @Override
    public Tx binary(SqlAst.Binary b, SqlToPure.At at) {
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
            case EQ, NE, LT, LE, GT, GE -> comparison(b, at);
            case PLUS -> throw SqlTranslationException.unsupported("IQ's + (a sum, or two strings joined) is not"
                    + " supported yet", b.pos());
            case CONCAT -> throw SqlTranslationException.unsupported("|| is not supported yet", b.pos());
            case MINUS, TIMES, DIVIDE, MODULO, POWER -> throw SqlTranslationException.unsupported("arithmetic is not"
                    + " supported yet: IQ's result types and overflow (the research's D-N3) are not built", b.pos());
        };
    }

    /** A comparison: with a NULL constant UNKNOWN (IS [NOT] NULL for = and != under ansinull Off), else of two values
     *  of one kind. */
    private Tx comparison(SqlAst.Binary b, SqlToPure.At at) {
        Tx l = at.tx(b.left());
        Tx r = at.tx(b.right());
        boolean leftNull = b.left() instanceof SqlAst.NullLit;
        boolean rightNull = b.right() instanceof SqlAst.NullLit;
        if (leftNull || rightNull) {
            if (!profile.ansinull() && (b.op() == SqlAst.BinaryOp.EQ || b.op() == SqlAst.BinaryOp.NE)) {
                ValueSpecification v = SqlToPure.valueOf(leftNull ? r : l);
                Tx isNull = Tx.condition(SqlToPure.call("isEmpty", v), SqlToPure.call("isNotEmpty", v), false);
                return b.op() == SqlAst.BinaryOp.EQ ? isNull : SqlToPure.not(isNull);
            }
            return Tx.condition(new CBoolean(false), new CBoolean(false), true);
        }
        Kind lk = l.kind();
        Kind rk = r.kind();
        if (lk == Kind.STRING && rk == Kind.STRING) {
            return strings(b, l, r);
        }
        boolean comparable = lk.numeric() && rk.numeric() && lk != Kind.NUMBER && rk != Kind.NUMBER
                || lk == Kind.DATE && rk == Kind.DATE || lk == Kind.DATETIME && rk == Kind.DATETIME;
        if (!comparable) {
            throw SqlTranslationException.unsupported("comparing " + kindName(lk) + " with " + kindName(rk)
                    + " is not supported yet (IQ's conversions between kinds are a later slice's)", b.pos());
        }
        return SqlToPure.compare(b.op(), l, r);
    }

    /** Two strings: = and != ignore trailing blanks (BLANK PADDING ON): equal when one starts with the other and the
     *  two are equal with their spaces taken out (so the longer one's rest is spaces only, exactly); the rest refused
     *  by name (above). */
    private Tx strings(SqlAst.Binary b, Tx l, Tx r) {
        if (!profile.caseSensitive()) {
            throw SqlTranslationException.unsupported("comparing strings in profile " + profile.spelling()
                    + "'s case-insensitive database needs an ASCII-only case fold, which is not built yet", b.pos());
        }
        if (b.op() != SqlAst.BinaryOp.EQ && b.op() != SqlAst.BinaryOp.NE) {
            throw SqlTranslationException.unsupported("ordering strings is not supported yet: IQ ignores trailing"
                    + " blanks first, which needs an ASCII-only right trim, not built yet", b.pos());
        }
        if (!profile.blankPadding()) {
            return SqlToPure.compare(b.op(), l, r);
        }
        Tx equal = SqlToPure.strictTest(List.of(l, r), v -> {
            ValueSpecification prefix = SqlToPure.call("or", SqlToPure.call("startsWith", v.get(0), v.get(1)),
                    SqlToPure.call("startsWith", v.get(1), v.get(0)));
            ValueSpecification spaceless = SqlToPure.call("equal",
                    SqlToPure.call("replace", v.get(0), new CString(" "), new CString("")),
                    SqlToPure.call("replace", v.get(1), new CString(" "), new CString("")));
            return SqlToPure.call("and", prefix, spaceless);
        });
        return b.op() == SqlAst.BinaryOp.EQ ? equal : SqlToPure.not(equal);
    }

    private static String kindName(Kind k) {
        return switch (k) {
            case BOOLEAN -> "a BIT";
            case STRING -> "a string";
            case DATE -> "a date";
            case DATETIME -> "a timestamp";
            case NULL -> "NULL";
            case INTEGER, WHOLE, FLOAT, DECIMAL, NUMBER, INEXACT -> "a number";
            case JSON, OTHER -> "a value of this type";
        };
    }

    @Override
    public SqlToPure.Common unify(String what, List<SqlAst.Expr> exprs, List<Tx> values, SqlAst.Pos pos) {
        throw SqlTranslationException.unsupported(what + " is not supported yet", pos);
    }

    /** IQ's function catalog: none is read yet. */
    @Override
    public InputDialect.@com.legend.base.Nullable FunctionRule function(String name) {
        return null;
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
