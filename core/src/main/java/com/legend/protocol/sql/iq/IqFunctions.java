// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.protocol.sql.iq;

import com.legend.protocol.spec.AppliedFunction;
import com.legend.protocol.spec.CDate;
import com.legend.protocol.spec.CDecimal;
import com.legend.protocol.spec.CFloat;
import com.legend.protocol.spec.CInteger;
import com.legend.protocol.spec.CString;
import com.legend.protocol.spec.EnumValue;
import com.legend.protocol.spec.ValueSpecification;
import com.legend.protocol.sql.InputDialect;
import com.legend.protocol.sql.SqlAst;
import com.legend.protocol.sql.SqlToPure;
import com.legend.protocol.sql.SqlToPure.Kind;
import com.legend.protocol.sql.SqlToPure.Tx;
import com.legend.protocol.sql.SqlTranslationException;
import com.legend.values.PureDateLiteral;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * IQ's scalar functions beyond the NULL family (M1d, docs/IQ_SQL_STUDY_2026_10_13.md §9.6), and CAST and a style-less
 * CONVERT, as {@link IqDialect} reads them: each translated exactly with Pure's existing natives, or refused by name --
 * with the native it would need, or the research's decision it waits for. Java never computes a value here: a rule
 * reads its arguments' types and constants' spellings, and writes Pure the database computes. Measured on SQL
 * Anywhere 17 (the oracle of the island's {@code engine=sa}; M1d's probes, kept with the private research), and per
 * engine where IQ's documentation differs (the research's SEMANTICS §2.2, §5):
 * <ul>
 *   <li><b>Strings.</b> {@code LENGTH}, {@code CHAR_LENGTH} and {@code LEN} count characters, trailing blanks too (a
 *       CHAR(n) column under the IQ engine is padded already: D-S1, D-S6); {@code SUBSTRING} and {@code SUBSTR} by
 *       ANSI_SUBSTRING's virtual positions (a start below 1 counts the positions before the string; a negative
 *       length is IQ's error -1090); {@code LEFT} and {@code RIGHT} (a negative count is ''); {@code TRIM},
 *       {@code LTRIM} and {@code RTRIM} of what an ISO-8859-1 database trims (the space, tab, line feed, vertical tab,
 *       form feed, carriage return), or of a constant set; {@code REPLACE}, {@code CHARINDEX}, {@code LOCATE} (a
 *       negative start finds the last occurrence) and {@code PATINDEX} (of a constant pattern of one literal
 *       between %s) case-sensitively, as an IQ database matches, a search of 255 characters or more NULL;
 *       {@code UPPER}, {@code LOWER}, {@code UCASE}, {@code LCASE} by IQ's ASCII-only fold, and a {@code sqla17}
 *       profile's matching without regard to the ASCII letters' case, both composed of {@code replace} (IQ island,
 *       2026-10-14: the natives' slice);
 *       {@code REPEAT}, {@code REPLICATE} and {@code SPACE} (a negative count is ''); {@code STUFF} (SQL Anywhere's
 *       front end only: D-F3); {@code ASCII}, {@code CHAR}; {@code STRING} (a NULL is '', all NULL is NULL). An integer
 *       argument is read as its digits; any other value IQ would write as text by its own format is refused.</li>
 *   <li><b>Numbers.</b> {@code ABS} (SQL Anywhere's unsigned and NUMERIC(20,0) results; under the IQ engine the
 *       argument's type, its smallest value IQ's error -158: D-F5); {@code SIGN}; {@code CEILING}, {@code CEIL} and
 *       {@code FLOOR} (a DOUBLE, in SQL Anywhere's front end: D-F2); {@code ROUND} (half away from zero, in SQL
 *       Anywhere's front end: D-F1) and {@code TRUNCNUM} of integers and NUMERICs, keeping the scale; {@code MOD} and
 *       {@code REMAINDER} as {@code %}; {@code SQRT}, {@code EXP}, {@code LN}, {@code LOG} (natural), {@code LOG10}
 *       (NULL outside their domain in SQL Anywhere's front end, IQ's error under the IQ engine: D-F9); {@code PI}.</li>
 *   <li><b>Dates.</b> {@code GETDATE}, {@code NOW}, {@code CURRENT TIMESTAMP}, {@code TODAY}, {@code CURRENT DATE}:
 *       the statement's time, one value per statement; {@code YEAR}, {@code MONTH}, {@code DAY}, {@code HOUR},
 *       {@code MINUTE}, {@code SECOND}, {@code QUARTER}, {@code DOW}, {@code DATE}; {@code DATEPART} and
 *       {@code DATENAME} (SQL Anywhere's Sunday-based week, weekday 1 on Sunday at the default first day of the week;
 *       the ISO parts in SQL Anywhere's front end only: D-T10); {@code DATEADD} and {@code DATEDIFF} in SQL
 *       Anywhere's front end only (D-T2, D-T12, D-T11), DATEDIFF by SQL Anywhere's mixed boundary rules.</li>
 *   <li><b>NUMERIC division</b> (IQ island, 2026-10-14): SQL Anywhere's exact quotient and remainder
 *       ({@link #quotient}, {@link #remainder}), composed of decimal arithmetic, Pure's {@code rem}, and double
 *       estimates that decimal arithmetic checks; {@link IqDialect} applies them to {@code /} and {@code %}, and an
 *       AVG of a NUMERIC is the same quotient of its SUM by its COUNT at AVG's type.</li>
 *   <li><b>CAST and CONVERT</b> (without a style) to SMALLINT, INTEGER, BIGINT (truncating toward zero, a string by
 *       IQ's strict reading), NUMERIC(p, s) (rounding half away from zero), DOUBLE, CHAR(n), VARCHAR(n) and LONG
 *       VARCHAR (a longer string IQ's error -638 in SQL Anywhere's front end; under the IQ engine D-C2), DATE,
 *       TIMESTAMP, BIT.</li>
 * </ul>
 */
final class IqFunctions {

    private final IqDialect dialect;
    private final IqProfile profile;
    private final IqDialect.@com.legend.base.Nullable Engine engine;

    IqFunctions(IqDialect dialect) {
        this.dialect = dialect;
        this.profile = dialect.profile();
        this.engine = dialect.engine();
    }

    // ------------------------------------------------------------------ the catalog

    /** IQ's functions this class reads, each with how many arguments it takes (else IQ's error -154). */
    private enum Fn {
        LENGTH(1, 1), LEN(1, 1), CHAR_LENGTH(1, 1), SUBSTRING(2, 3), SUBSTR(2, 3), LEFT(2, 2), RIGHT(2, 2),
        TRIM(1, 2), LTRIM(1, 2), RTRIM(1, 2), REPLACE(3, 3), REPEAT(2, 2), REPLICATE(2, 2), SPACE(1, 1),
        CHARINDEX(2, 2), LOCATE(2, 3), PATINDEX(2, 2), STUFF(4, 4), ASCII(1, 1), CHAR(1, 1),
        STRING(1, Integer.MAX_VALUE), UPPER(1, 1), LOWER(1, 1), UCASE(1, 1), LCASE(1, 1),
        ABS(1, 1), SIGN(1, 1), CEILING(1, 1), CEIL(1, 1), FLOOR(1, 1), ROUND(2, 2), TRUNCNUM(2, 2), MOD(2, 2),
        REMAINDER(2, 2), SQRT(1, 1), EXP(1, 1), LN(1, 1), LOG(1, 1), LOG10(1, 1), POWER(2, 2), PI(0, 0),
        GETDATE(0, 0), NOW(0, 0), TODAY(0, 0), CURRENT_DATE(0, 0, "current date"),
        CURRENT_TIMESTAMP(0, 0, "current timestamp"), YEAR(1, 1), MONTH(1, 1), DAY(1, 1), HOUR(1, 1),
        MINUTE(1, 1), SECOND(1, 1), QUARTER(1, 1), DOW(1, 1), DATE(1, 1), DATEPART(2, 2), DATENAME(2, 2),
        DATEADD(3, 3), DATEDIFF(3, 3);

        final int min;
        final int max;
        final String spelling;

        Fn(int min, int max) {
            this(min, max, null);
        }

        Fn(int min, int max, @com.legend.base.Nullable String spelling) {
            this.min = min;
            this.max = max;
            this.spelling = spelling != null ? spelling : name().toLowerCase(Locale.ROOT);
        }

        /** The function {@code name} names, as IQ matches names (ASCII case folded); null for none. */
        static @com.legend.base.Nullable Fn named(String name) {
            String key = IqLexer.lower(name);
            for (Fn f : values()) {
                if (f.spelling.equals(key)) {
                    return f;
                }
            }
            return null;
        }
    }

    /** How many arguments {@code f} takes, {min, max}, for a function of this catalog ({@link IqDialect}'s parse checks
     *  the count). Refused by name before translation: a delimited name with a space (only the reader writes
     *  {@code CURRENT DATE}); other dialects' forms IQ raises -154 for (CHARINDEX's start, ROUND's third argument, LOG's
     *  base); a constant negative SUBSTRING length (IQ's -1090 wherever computed). */
    static int[] arity(SqlAst.FunctionCall f) {
        SqlAst.Ident name = f.name().get(f.name().size() - 1);
        Fn fn = Fn.named(name.text());
        if (fn == null || name.quoted() && name.text().indexOf(' ') >= 0) {
            throw SqlTranslationException.unsupported("function " + name.text() + "(...) is not supported yet",
                    f.pos());
        }
        int n = f.args().size();
        String foreign = fn == Fn.CHARINDEX && n == 3 ? "CHARINDEX's start (other dialects'; IQ's is LOCATE(s, search,"
                + " start))" : fn == Fn.ROUND && n == 3 ? "ROUND's third argument (other dialects'; IQ's is TRUNCNUM)"
                : fn == Fn.LOG && n == 2 ? "LOG's base (other dialects'; IQ's LOG is the natural one, LOG10 the"
                + " decimal)" : null;
        if (foreign != null) {
            throw SqlTranslationException.unsupported(foreign + " is not supported: IQ raises its error -154", f.pos());
        }
        if ((fn == Fn.SUBSTRING || fn == Fn.SUBSTR) && n == 3 && signedInteger(f.args().get(2)) instanceof BigInteger
                len && len.signum() < 0) {
            throw SqlTranslationException.unsupported(name.text() + " of a negative length is IQ's error -1090 wherever"
                    + " it is computed: not supported", f.args().get(2).pos());
        }
        return new int[] {fn.min, fn.max};
    }

    /**
     * Refuses by name, before translation, a CAST or CONVERT of a constant IQ cannot convert -- its error raised
     * wherever the CAST is computed (SQL Anywhere computes it for each row, and so for none of no rows): a string that
     * is no integer (-157) or a number out of an integer type's range (-158); a constant with more whole digits than a
     * NUMERIC(p, s) holds (-158); a string to BIT, or a number other than 0 and 1 (-158); a string that is no ISO date
     * to DATE or TIMESTAMP (-157, or IQ's other spellings, not read); an integer longer than a CHAR or VARCHAR (-157), a
     * string longer than it but for blanks (-638).
     */
    static void constantCast(SqlAst.Expr operand, SqlAst.TypeName type, String what, IqProfile profile) {
        String target = type.name();
        List<Integer> sizes = type.modifiers();
        String text = operand instanceof SqlAst.StringLit lit ? lit.value() : null;
        BigDecimal number = numberConstant(operand);
        String problem = null;
        switch (target) {
            case "SMALLINT", "INTEGER", "BIGINT" -> {
                BigInteger v = text != null ? integerText(text) : number == null ? null
                        : number.setScale(0, java.math.RoundingMode.DOWN).toBigIntegerExact();
                IqType t = new IqType(target);
                if (text != null && v == null) {
                    problem = "the string '" + text + "' is no integer: IQ's error -157";
                } else if (v != null && (v.compareTo(t.min()) < 0 || v.compareTo(t.max()) > 0)) {
                    problem = v + " is out of " + target + "'s range: IQ's error -158";
                }
            }
            case "NUMERIC" -> {
                BigDecimal v = text != null ? IqDialect.numericText(text) : number;
                if (v != null && !sizes.isEmpty()) {
                    int p = sizes.get(0);
                    int sc = sizes.size() > 1 ? sizes.get(1) : 0;
                    BigDecimal rounded = v.setScale(sc, java.math.RoundingMode.HALF_UP);
                    if (rounded.abs().compareTo(new BigDecimal(BigInteger.TEN.pow(p - sc))) >= 0) {
                        problem = v + " has more whole digits than a NUMERIC(" + p + "," + sc + ") holds: IQ's error"
                                + " -158";
                    }
                }
            }
            case "BIT" -> {
                if (text != null) {
                    problem = "a string to BIT is not read yet";
                } else if (number != null && number.compareTo(BigDecimal.ZERO) != 0
                        && number.compareTo(BigDecimal.ONE) != 0) {
                    problem = number + " is no BIT: IQ's error -158";
                }
            }
            case "DATE", "TIMESTAMP" -> {
                if (text != null) {
                    // IQ island, 2026-10-14 (M1e): IQ's spellings by the profile's DATE_ORDER (IqDateTime); one
                    // before the year 100 refused here as the reader's
                    String[] parts;
                    try {
                        parts = IqDateTime.read(text, profile, operand.pos());
                    } catch (SqlTranslationException e) {
                        throw com.legend.protocol.sql.SqlSyntaxException.unsupported(String.valueOf(e.getMessage()), operand.pos());
                    }
                    if (parts == null) {
                        problem = "the string '" + text + "' is no date the island reads (IQ's error -157, or a"
                                + " spelling of IQ's date order)";
                    }
                }
            }
            case "CHAR", "VARCHAR" -> {
                if (!sizes.isEmpty()) {
                    int n = sizes.get(0);
                    if (number != null && number.toPlainString().length() > n) {
                        problem = number.toPlainString() + " is longer than " + target + "(" + n + "): IQ's error -157";
                    } else if (text != null && text.length() > n && !text.substring(n).replace(" ", "").isEmpty()) {
                        problem = "'" + text + "' is longer than " + target + "(" + n + "): IQ's error -638";
                    }
                }
            }
            default -> {
            }
        }
        if (problem != null) {
            throw com.legend.protocol.sql.SqlSyntaxException.unsupported(what + " of a constant IQ cannot convert is"
                    + " not supported: " + problem + " wherever it is computed", operand.pos());
        }
    }

    /** A number constant's value, signs applied; null for any other expression. */
    private static @com.legend.base.Nullable BigDecimal numberConstant(SqlAst.Expr e) {
        return switch (e) {
            case SqlAst.IntegerLit n -> new BigDecimal(n.value());
            case SqlAst.NumericLit n -> n.value();
            case SqlAst.Unary u when u.op() == SqlAst.UnaryOp.MINUS -> {
                BigDecimal v = numberConstant(u.operand());
                yield v == null ? null : v.negate();
            }
            case SqlAst.Unary u when u.op() == SqlAst.UnaryOp.PLUS -> numberConstant(u.operand());
            default -> null;
        };
    }

    /** The rule of the function {@code name} names, or null when this catalog has none. */
    static InputDialect.@com.legend.base.Nullable FunctionRule rule(IqDialect dialect, String name) {
        Fn fn = Fn.named(name);
        if (fn == null) {
            return null;
        }
        return (f, args, at) -> IqDialect.bounded(new IqFunctions(dialect).apply(fn, f, args, at), f.pos());
    }

    private Tx apply(Fn fn, SqlAst.FunctionCall f, List<Tx> args, SqlToPure.At at) {
        String name = f.name().get(f.name().size() - 1).text().toUpperCase(Locale.ROOT);
        if (args.size() < fn.min || args.size() > fn.max) {
            throw SqlTranslationException.invalid("wrong number of arguments to " + name + code(-154, "37505"),
                    f.pos());
        }
        return switch (fn) {
            case LENGTH, LEN, CHAR_LENGTH -> length(name, f, args);
            case SUBSTRING, SUBSTR -> substring(name, f, args);
            case LEFT, RIGHT -> leftRight(fn == Fn.LEFT, name, f, args);
            case TRIM, LTRIM, RTRIM -> trim(fn, name, f, args);
            case REPLACE -> replace(name, f, args);
            case REPEAT, REPLICATE -> repeat(name, f, args.get(0), f.args().get(0), args.get(1), f.args().get(1));
            case SPACE -> repeat(name, f, Tx.value(new CString(" "), Kind.STRING, false, new IqType("CHAR(1)")),
                    f, args.get(0), f.args().get(0));
            case CHARINDEX -> locate(name, f, args.get(1), f.args().get(1), args.get(0), f.args().get(0), null, null);
            case LOCATE -> locate(name, f, args.get(0), f.args().get(0), args.get(1), f.args().get(1),
                    args.size() == 3 ? args.get(2) : null, args.size() == 3 ? f.args().get(2) : null);
            case PATINDEX -> patindex(name, f, args);
            case STUFF -> stuff(name, f, args);
            case ASCII -> ascii(name, f, args);
            case CHAR -> chr(name, f, args);
            case STRING -> string(f, args);
            // IQ island, 2026-10-14 (natives): the ASCII-only fold, composed of replace
            case UPPER, LOWER, UCASE, LCASE -> foldCase(fn == Fn.UPPER || fn == Fn.UCASE, name, f, args);
            case ABS -> abs(f, args);
            case SIGN -> sign(f, args);
            case CEILING, CEIL, FLOOR -> ceilingFloor(fn == Fn.FLOOR, name, f, args);
            case ROUND, TRUNCNUM -> roundTruncate(fn == Fn.ROUND, name, f, args);
            case MOD, REMAINDER -> mod(name, f, at);
            case SQRT, EXP, LN, LOG, LOG10 -> mathematical(fn, name, f, args);
            case POWER -> throw SqlTranslationException.unsupported("POWER(...) is not supported yet: SQL Anywhere"
                    + " answers +Infinity (an overflow, a zero to a negative power) or NULL (a negative base to a"
                    + " fraction) where lite's databases raise or answer NaN, which needs a native for its domain",
                    f.pos());
            case PI -> Tx.value(SqlToPure.call("pi"), Kind.FLOAT, false, new IqType("DOUBLE"));
            case GETDATE, NOW, CURRENT_TIMESTAMP -> Tx.value(SqlToPure.call("now"), Kind.DATETIME, false,
                    new IqType("TIMESTAMP"));
            case TODAY, CURRENT_DATE -> Tx.value(SqlToPure.call("today"), Kind.DATE, false, new IqType("DATE"));
            case YEAR, MONTH, DAY, HOUR, MINUTE, SECOND, QUARTER, DOW -> datePartOf(Part.of(fn.spelling), name, f,
                    args.get(0), f.args().get(0), true);
            case DATE -> dateOf(name, f, args.get(0), f.args().get(0));
            case DATEPART, DATENAME -> datePartCall(fn == Fn.DATENAME, name, f, args);
            case DATEADD -> dateAdd(name, f, args);
            case DATEDIFF -> dateDiff(name, f, args);
        };
    }

    // ------------------------------------------------------------------ strings

    /** {@code LENGTH(s)}, {@code CHAR_LENGTH(s)}, {@code LEN(s)}: its characters, trailing blanks counted (LEN is
     *  LENGTH in IQ: the research's D-S6), an INTEGER; an integer's digits (and sign). */
    private Tx length(String name, SqlAst.FunctionCall f, List<Tx> args) {
        Tx s = text(args.get(0), f.args().get(0), name);
        return strict(Kind.INTEGER, nonNegative(), List.of(s), v -> SqlToPure.call("length", v.get(0)));
    }

    /**
     * {@code SUBSTRING(s, start [, length])} (and {@code SUBSTR}) under ANSI_SUBSTRING On (every profile's): the
     * characters at positions start .. start + length - 1 that the string has, where positions below 1 are virtual
     * (measured: {@code ('mnopqrst', 0, 5)} is 'mnop', {@code (-3, 5)} 'm'); without a length, the length is the
     * largest INTEGER (measured: a start of -2147483641 keeps 'mnopq'). A negative length is IQ's error -1090 (for a
     * string that is not NULL); a NULL argument is NULL.
     */
    private Tx substring(String name, SqlAst.FunctionCall f, List<Tx> args) {
        Tx s = text(args.get(0), f.args().get(0), name);
        Tx start = count(args.get(1), f.args().get(1), name);
        // without a length, SQL Anywhere's is the largest INTEGER (measured: from -2147483641 it is 'abcde')
        Tx len = args.size() == 2 ? Tx.value(integer(Integer.MAX_VALUE), Kind.INTEGER, false,
                IqType.integer("INTEGER", IqType.INTEGER_MAX, IqType.INTEGER_MAX, false))
                : count(args.get(2), f.args().get(2), name);
        boolean negative = len.detail() instanceof IqType t && t.lo().signum() < 0;
        if (negative) {
            raisesHere(f, "-1090 (a negative length)");
        }
        return strict(Kind.STRING, varchar(), List.of(s, start, len), v -> {
            ValueSpecification p = v.get(1);
            ValueSpecification n = v.get(2);
            // a start of 1 or more: substring's own; below 1: the positions from 1 to start + length - 1
            ValueSpecification below = ifThen(lt(AppliedFunction.infixRun("plus", List.of(p, n)), integer(2)),
                    new CString(""), SqlToPure.call("substring", v.get(0), integer(1),
                            AppliedFunction.infixRun("minus", List.of(AppliedFunction.infixRun("plus", List.of(p, n)),
                                    integer(1)))));
            ValueSpecification value = ifThen(lt(p, integer(1)), below, SqlToPure.call("substring", v.get(0), p, n));
            return negative ? ifThen(lt(n, integer(0)), raise("String"), value) : value;
        });
    }

    /** {@code x}, or 1 when it is below 1. */
    private static ValueSpecification atLeastOne(ValueSpecification x) {
        return ifThen(lt(x, integer(1)), integer(1), x);
    }

    /** {@code UPPER(s)}, {@code UCASE(s)}, {@code LOWER(s)}, {@code LCASE(s)}: s with its ASCII letters A-Z (a-z) in
     *  the other case and every other character as it is (measured in both kinds of database: UPPER('é') is 'é',
     *  and a Greek or Cyrillic letter stays as it is), of the argument's type ({@link #asciiFold}). */
    private Tx foldCase(boolean upper, String name, SqlAst.FunctionCall f, List<Tx> args) {
        Tx s = text(args.get(0), f.args().get(0), name);
        IqType type = s.detail() instanceof IqType t && (t.family().equals("CHAR") || t.family().equals("VARCHAR"))
                ? t : varchar();
        return strict(Kind.STRING, type, List.of(s), v -> asciiFold(v.get(0), upper));
    }

    /**
     * IQ's case fold: {@code s} with the 26 ASCII letters in upper case ({@code upper}) or lower case, every other
     * character as it is -- IQ folds A-Z only, in an ISO-8859-1 and a UTF-8 database alike (the research's SEMANTICS
     * §3.3; measured). Composed of 26 of Pure's {@code replace}, which both databases compute character for character
     * (measured over Latin-1 and UTF-8 text): Pure's {@code toUpper} and {@code toLower} are the databases' own
     * {@code upper} and {@code lower}, and DuckDB's fold every letter. A constant with no letter to fold is itself.
     */
    static ValueSpecification asciiFold(ValueSpecification s, boolean upper) {
        char first = upper ? 'a' : 'A';
        if (s instanceof CString c) {
            boolean any = false;
            for (int i = 0; i < c.value().length() && !any; i++) {
                char ch = c.value().charAt(i);
                any = ch >= first && ch <= first + 25;
            }
            if (!any) {
                return s;
            }
        }
        ValueSpecification x = s;
        for (int i = 0; i < 26; i++) {
            x = SqlToPure.call("replace", x, new CString(String.valueOf((char) (first + i))),
                    new CString(String.valueOf((char) (first + i + (upper ? -32 : 32)))));
        }
        return x;
    }

    /** IQ's blank padding where it orders strings: {@code s} without its trailing spaces -- U+0020 only (measured: a
     *  tab or a no-break space at the end is a character, and IQ compares as if the trailing spaces were not there,
     *  not as if the shorter string were padded: {@code 'a' < 'a\t'}). One anchored {@code regexpReplace}, which both
     *  databases compute alike, where Pure's {@code rtrim} is DuckDB's (every Unicode space) and Postgres's. */
    static ValueSpecification trailingSpacesDropped(ValueSpecification s) {
        return SqlToPure.call("regexpReplace", s, new CString(" +$"), new CString(""),
                new com.legend.protocol.spec.CBoolean(false));
    }

    /** {@code LEFT(s, n)}, {@code RIGHT(s, n)}: the first or last n characters, all of them when n is more; '' for a
     *  negative n (measured: IQ's, where Pure's left leaves that many off the other end). */
    private Tx leftRight(boolean left, String name, SqlAst.FunctionCall f, List<Tx> args) {
        Tx s = text(args.get(0), f.args().get(0), name);
        Tx n = count(args.get(1), f.args().get(1), name);
        return strict(Kind.STRING, varchar(), List.of(s, n), v -> SqlToPure.call(left ? "left" : "right", v.get(0),
                ifThen(lt(v.get(1), integer(0)), integer(0), v.get(1))));
    }

    /** The characters an ISO-8859-1 database's TRIM drops (measured over the 255: the space, and tab through carriage
     *  return), as a regular expression's alternatives: the line feed apart, since Postgres's lowering reads a bracket
     *  expression newline-sensitively. */
    private static final String BLANKS = "(\\n|[\\t-\\r ])";

    /**
     * {@code TRIM(s)}, {@code LTRIM(s)}, {@code RTRIM(s)}: the leading and/or trailing characters an ISO-8859-1
     * database trims -- the space, the tab, the line feed, the vertical tab, the form feed, the carriage return, and no
     * other (measured; DuckDB's trim drops Unicode spaces and no tab, Postgres's the space alone, so neither is used);
     * a {@code sqla17} profile's UTF-8 database trims the same six and no Unicode space (IQ island, 2026-10-14
     * (natives): measured over U+0085, U+00A0, U+1680, U+180E, U+2000-U+200B, U+2028, U+2029, U+202F, U+205F,
     * U+3000, U+FEFF). {@code TRIM(s, set)}: the characters of a constant set, case-sensitively in an IQ database
     * and without regard to the ASCII letters' case in a {@code sqla17} profile's (the research's SEMANTICS §3.3).
     * Refused by name: a set that is not a constant.
     */
    private Tx trim(Fn fn, String name, SqlAst.FunctionCall f, List<Tx> args) {
        Tx s = text(args.get(0), f.args().get(0), name);
        String chars;
        if (args.size() == 2) {
            if (args.get(1).kind() == Kind.NULL) {
                return nullString();
            }
            if (!(f.args().get(1) instanceof SqlAst.StringLit set)) {
                throw SqlTranslationException.unsupported(name + " with a set of characters that is not a constant"
                        + " string is not supported yet", f.args().get(1).pos());
            }
            if (set.value().isEmpty()) {
                return strict(Kind.STRING, varchar(), List.of(s), v -> v.get(0));
            }
            chars = setClass(set.value(), !profile.caseSensitive());
        } else {
            chars = BLANKS;
        }
        boolean leading = fn != Fn.RTRIM;
        boolean trailing = fn != Fn.LTRIM;
        String run = chars + "+";
        return strict(Kind.STRING, varchar(), List.of(s), v -> {
            ValueSpecification x = v.get(0);
            if (trailing) {
                x = SqlToPure.call("regexpReplace", x, new CString(run + "$"), new CString(""),
                        new com.legend.protocol.spec.CBoolean(false));
            }
            if (leading) {
                x = SqlToPure.call("regexpReplace", x, new CString("^" + run), new CString(""),
                        new com.legend.protocol.spec.CBoolean(false));
            }
            return x;
        });
    }

    /** A set of characters as a regular expression's alternatives: each character itself, escaped, and with
     *  {@code bothCases} an ASCII letter's other case too; a line feed apart (above). */
    private static String setClass(String set, boolean bothCases) {
        StringBuilder b = new StringBuilder("([");
        boolean newline = false;
        for (int i = 0; i < set.length(); i++) {
            char c = set.charAt(i);
            if (bothCases && asciiLetter(c)) {
                b.append(c).append((char) (c ^ 0x20));
            } else if (c == '\n') {
                newline = true;
            } else if (c < 0x20 || c == 0x7F) {
                String h = Integer.toHexString(c);
                b.append("\\x").append(h.length() == 1 ? "0" : "").append(h);
            } else {
                if ("\\^]-[".indexOf(c) >= 0) {
                    b.append('\\');
                }
                b.append(c);
            }
        }
        b.append("])");
        if (newline) {
            return b.length() == 4 ? "(\\n)" : "(\\n|" + b.substring(1);
        }
        return b.toString();
    }

    /** The longest search string REPLACE, CHARINDEX and LOCATE take: with one of 255 characters or more SQL Anywhere
     *  answers NULL (measured; IQ's manual says above 255). */
    private static final int LONGEST_SEARCH = 254;

    /** {@code REPLACE(s, search, replacement)}: every occurrence, left to right, case-sensitively (an IQ database's);
     *  '' searched is no change; a search of 255 characters or more NULL; a NULL argument NULL. In a {@code sqla17}
     *  profile's database the search matches without regard to the ASCII letters' case (measured: REPLACE('aAbBa',
     *  'a', 'x') is 'xxbBx', REPLACE('éÉe', 'é', 'x') is 'xÉe'): a global {@code regexpReplace} whose pattern spells
     *  each letter of a constant search as a class of its two cases (IQ island, 2026-10-14, natives); refused by name
     *  there: a search with a letter that is no constant, a replacement that is no constant. */
    private Tx replace(String name, SqlAst.FunctionCall f, List<Tx> args) {
        Tx s = text(args.get(0), f.args().get(0), name);
        Tx search = text(args.get(1), f.args().get(1), name);
        Tx replacement = text(args.get(2), f.args().get(2), name);
        if (!profile.caseSensitive() && !caseFree(f.args().get(1)) && search.kind() != Kind.NULL
                && replacement.kind() != Kind.NULL) {
            if (!(f.args().get(1) instanceof SqlAst.StringLit sought) || sought.value().length() > LONGEST_SEARCH) {
                throw SqlTranslationException.unsupported(name + " in profile " + profile.spelling() + "'s"
                        + " case-insensitive database with a search that is no constant string (of at most "
                        + LONGEST_SEARCH + " characters) is not supported yet", f.args().get(1).pos());
            }
            String with = constantText(f.args().get(2));
            if (with == null) {
                throw SqlTranslationException.unsupported(name + " in profile " + profile.spelling() + "'s"
                        + " case-insensitive database with a replacement that is no constant is not supported yet",
                        f.args().get(2).pos());
            }
            String pattern = caselessPattern(sought.value());
            String rewrite = with.replace("\\", "\\\\");
            return strict(Kind.STRING, varchar(), List.of(s, search, replacement), v -> SqlToPure.call(
                    "regexpReplace", v.get(0), new CString(pattern), new CString(rewrite),
                    new com.legend.protocol.spec.CBoolean(true)));
        }
        boolean guard = !shortConstant(f.args().get(1));
        Tx r = strict(Kind.STRING, varchar(), List.of(s, search, replacement), v -> {
            ValueSpecification value = SqlToPure.call("replace", v.get(0), v.get(1), v.get(2));
            return guard ? ifThen(tooLong(v.get(1)), SqlToPure.empty(), value) : value;
        });
        return guard ? Tx.value(r.vs(), Kind.STRING, true, r.detail()) : r;
    }

    /** Whether {@code c} is one of the 26 ASCII letters, either case. */
    private static boolean asciiLetter(char c) {
        return c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z';
    }

    /** Whether {@code e} is a constant whose matching no case can change: a number, or a string without an ASCII
     *  letter. */
    private static boolean caseFree(SqlAst.Expr e) {
        if (e instanceof SqlAst.StringLit lit) {
            for (int i = 0; i < lit.value().length(); i++) {
                if (asciiLetter(lit.value().charAt(i))) {
                    return false;
                }
            }
            return true;
        }
        return e instanceof SqlAst.IntegerLit || e instanceof SqlAst.NullLit;
    }

    /** A constant's text as IQ reads it as a string: a string's own, an integer's digits; null for anything else. */
    private static @com.legend.base.Nullable String constantText(SqlAst.Expr e) {
        return e instanceof SqlAst.StringLit lit ? lit.value() : e instanceof SqlAst.IntegerLit n
                ? n.value().toString() : null;
    }

    /** A constant string as a regular expression matching it without regard to its ASCII letters' case: each letter a
     *  class of its two cases, every other character itself (escaped where a pattern reads it otherwise; a character
     *  outside ASCII is itself, which IQ does not fold either). */
    static String caselessPattern(String text) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (asciiLetter(c)) {
                b.append('[').append((char) (c | 0x20)).append((char) (c & ~0x20)).append(']');
            } else {
                if ("\\.[]{}()*+?^$|".indexOf(c) >= 0) {
                    b.append('\\');
                }
                b.append(c);
            }
        }
        return b.toString();
    }

    /** Whether {@code e} is a constant string IQ searches for (at most {@link #LONGEST_SEARCH} characters). */
    private static boolean shortConstant(SqlAst.Expr e) {
        return e instanceof SqlAst.StringLit s && s.value().length() <= LONGEST_SEARCH;
    }

    private static ValueSpecification tooLong(ValueSpecification search) {
        return SqlToPure.call("greaterThan", SqlToPure.call("length", search), integer(LONGEST_SEARCH));
    }

    /** {@code REPEAT(s, n)}, {@code REPLICATE(s, n)}, {@code SPACE(n)}: n copies; '' for n of 0 or less (measured: not
     *  NULL, as T-SQL's REPLICATE). */
    private Tx repeat(String name, SqlAst.FunctionCall f, Tx s, SqlAst.Expr se, Tx n, SqlAst.Expr ne) {
        Tx text = text(s, se, name);
        Tx times = count(n, ne, name);
        // repeatString is of a String[0..1]: here of a value, so one
        return strict(Kind.STRING, varchar(), List.of(text, times), v -> SqlToPure.call("toOne",
                SqlToPure.call("repeatString", v.get(0), v.get(1))));
    }

    /**
     * {@code CHARINDEX(search, s)} and {@code LOCATE(s, search [, start])}: where search first occurs in s, from 1, or
     * 0; '' is found at 1 (wherever the start); a search of 255 characters or more is NULL. LOCATE's start: from there
     * on when it is positive (0 reads as 1); when negative, the last occurrence in s without its last -start - 1
     * characters (measured). Case-sensitively in an IQ database; without regard to the ASCII letters' case in a
     * {@code sqla17} profile's ({@link #asciiFold}).
     */
    private Tx locate(String name, SqlAst.FunctionCall f, Tx s, SqlAst.Expr se, Tx search, SqlAst.Expr searchExpr,
            @com.legend.base.Nullable Tx start, SqlAst.@com.legend.base.Nullable Expr startExpr) {
        Tx text = text(s, se, name);
        Tx sought = text(search, searchExpr, name);
        boolean guard = !shortConstant(searchExpr);
        List<Tx> operands = new ArrayList<>(List.of(text, sought));
        Tx from = start == null ? null : count(start, java.util.Objects.requireNonNull(startExpr), name);
        if (from != null) {
            operands.add(from);
        }
        // IQ island, 2026-10-14 (natives): a sqla17 profile's database finds the search without regard to the ASCII
        // letters' case (measured: LOCATE('xAbc', 'abc') is 2): both folded, which moves no position
        boolean fold = !profile.caseSensitive();
        Tx r = strict(Kind.INTEGER, nonNegative(), operands, v -> {
            ValueSpecification str = fold ? asciiFold(v.get(0), false) : v.get(0);
            ValueSpecification find = fold ? asciiFold(v.get(1), false) : v.get(1);
            ValueSpecification value;
            if (from == null) {
                value = SqlToPure.call("indexOf", str, find);
            } else {
                ValueSpecification p = v.get(2);
                // from start on: where it occurs in the rest, as a position of s
                ValueSpecification at = atLeastOne(p);
                ValueSpecification rest = SqlToPure.call("indexOf", SqlToPure.call("substring", str, at), find);
                ValueSpecification forward = ifThen(SqlToPure.call("equal", rest, integer(0)), integer(0),
                        AppliedFunction.infixRun("minus", List.of(AppliedFunction.infixRun("plus", List.of(rest, at)),
                                integer(1))));
                // the last occurrence in s less its last -start - 1 characters: the first of the reversed search in
                // the reversed prefix, counted back
                ValueSpecification keep = AppliedFunction.infixRun("plus", List.of(SqlToPure.call("length", str), p,
                        integer(1)));
                ValueSpecification prefix = SqlToPure.call("left", str, ifThen(lt(keep, integer(0)), integer(0),
                        keep));
                ValueSpecification j = SqlToPure.call("indexOf", SqlToPure.call("reverseString", prefix),
                        SqlToPure.call("reverseString", find));
                ValueSpecification backward = ifThen(SqlToPure.call("equal", j, integer(0)), integer(0),
                        AppliedFunction.infixRun("plus", List.of(AppliedFunction.infixRun("minus", List.of(
                                SqlToPure.call("length", prefix), j, SqlToPure.call("length", find))), integer(2))));
                ValueSpecification found = ifThen(lt(p, integer(0)), backward, forward);
                value = ifThen(SqlToPure.call("equal", find, new CString("")), integer(1), found);
            }
            return guard ? ifThen(tooLong(find), SqlToPure.empty(), value) : value;
        });
        return guard ? Tx.value(r.vs(), Kind.INTEGER, true, r.detail()) : r;
    }

    /**
     * {@code PATINDEX(pattern, s)} of a constant pattern of one literal run between optional %s: where the run first
     * occurs ({@code '%lit%'}), 1 or 0 for {@code 'lit%'}, where it ends s for {@code '%lit'}, 1 or 0 for {@code 'lit'};
     * 0 when either argument is NULL (measured: not NULL). A pattern of %s alone (or '') is 0 in SQL Anywhere and 1 in
     * IQ's manual: the IQ engine's is the research's D-F6, refused. Refused by name: a pattern that is no constant, one
     * with {@code _}, a {@code [set]} or a % inside its run, one over 126 characters (SQL Anywhere's -606, IQ's NULL:
     * D-F7). A {@code sqla17} profile's database matches without regard to the ASCII letters' case
     * ({@link #asciiFold}).
     */
    private Tx patindex(String name, SqlAst.FunctionCall f, List<Tx> args) {
        SqlAst.Expr pe = f.args().get(0);
        Tx s = args.get(1);
        IqType type = IqType.integer("INTEGER", BigInteger.ZERO, IqType.INTEGER_MAX, false);
        if (pe instanceof SqlAst.NullLit) {
            return Tx.value(integer(0), Kind.INTEGER, false, type);
        }
        if (!(pe instanceof SqlAst.StringLit lit)) {
            throw SqlTranslationException.unsupported(name + " with a pattern that is not a constant string is not"
                    + " supported yet", pe.pos());
        }
        String p = lit.value();
        if (p.length() > 126) {
            throw SqlTranslationException.unsupported(name + " with a pattern of more than 126 characters is not"
                    + " supported (SQL Anywhere raises -606, IQ's manual answers NULL: the research's D-F7)", pe.pos());
        }
        int from = 0;
        while (from < p.length() && p.charAt(from) == '%') {
            from++;
        }
        int to = p.length();
        while (to > from && p.charAt(to - 1) == '%') {
            to--;
        }
        if (from == to) {
            if (engine == IqDialect.Engine.IQ) {
                throw SqlTranslationException.unsupported(name + " with a pattern of %s alone (or '') is not supported"
                        + " under the IQ engine: SQL Anywhere answers 0, IQ's manual 1 (the research's D-F6, not yet"
                        + " verified)", pe.pos());
            }
            text(s, f.args().get(1), name);
            return Tx.value(integer(0), Kind.INTEGER, false, type);
        }
        String run = p.substring(from, to);
        if (run.indexOf('%') >= 0 || run.indexOf('_') >= 0 || run.indexOf('[') >= 0) {
            throw SqlTranslationException.unsupported(name + " with a pattern other than one literal between %s (a _, a"
                    + " [set], a % inside) is not supported yet", pe.pos());
        }
        boolean anchoredStart = from == 0;
        boolean anchoredEnd = to == p.length();
        Tx str = text(s, f.args().get(1), name);
        if (str.kind() == Kind.NULL) {
            return Tx.value(integer(0), Kind.INTEGER, false, type);
        }
        ValueSpecification value = SqlToPure.valueOf(str);
        ValueSpecification x = str.nullable() ? SqlToPure.call("toOne", value) : value;
        ValueSpecification literal = new CString(run);
        if (!profile.caseSensitive()) {
            // IQ island, 2026-10-14 (natives): without regard to the ASCII letters' case in a sqla17 profile's
            // database (measured: PATINDEX('%BC%', 'abc') is 2), both folded
            x = asciiFold(x, false);
            literal = asciiFold(literal, false);
        }
        ValueSpecification found;
        if (!anchoredStart && !anchoredEnd) {
            found = SqlToPure.call("indexOf", x, literal);
        } else if (anchoredStart && !anchoredEnd) {
            found = ifThen(SqlToPure.call("startsWith", x, literal), integer(1), integer(0));
        } else if (!anchoredStart) {
            found = ifThen(SqlToPure.call("endsWith", x, literal), AppliedFunction.infixRun("plus", List.of(
                    AppliedFunction.infixRun("minus", List.of(SqlToPure.call("length", x), SqlToPure.call("length",
                            literal))), integer(1))), integer(0));
        } else {
            found = ifThen(SqlToPure.call("equal", x, literal), integer(1), integer(0));
        }
        ValueSpecification result = str.nullable() ? ifThen(SqlToPure.call("isEmpty", value), integer(0), found)
                : found;
        return Tx.value(result, Kind.INTEGER, false, type);
    }

    /**
     * {@code STUFF(s, start, length, replacement)}: s with its length characters from start replaced; a NULL
     * replacement deletes them. NULL when s, start or length is NULL, start is below 1 or beyond the string's length
     * plus 1, or length is negative (measured: start = length + 1 appends). IQ's manual answers NULL for start = length
     * + 1 (the research's D-F3, not yet verified): refused under the IQ engine.
     */
    private Tx stuff(String name, SqlAst.FunctionCall f, List<Tx> args) {
        if (engine == IqDialect.Engine.IQ) {
            throw SqlTranslationException.unsupported(name + " is not supported under the IQ engine yet: at a start"
                    + " just past the string SQL Anywhere appends, IQ's manual answers NULL (the research's D-F3, not"
                    + " yet verified)", f.pos());
        }
        Tx s = text(args.get(0), f.args().get(0), name);
        Tx start = count(args.get(1), f.args().get(1), name);
        Tx len = count(args.get(2), f.args().get(2), name);
        Tx rep = args.get(3).kind() == Kind.NULL ? Tx.value(new CString(""), Kind.STRING, false, varchar())
                : text(args.get(3), f.args().get(3), name);
        Tx r = strict(Kind.STRING, varchar(), List.of(s, start, len), v -> {
            ValueSpecification str = v.get(0);
            ValueSpecification p = v.get(1);
            ValueSpecification n = v.get(2);
            ValueSpecification replacement = textValue(rep);
            ValueSpecification valid = SqlToPure.call("and", SqlToPure.call("and",
                    SqlToPure.call("greaterThanEqual", p, integer(1)), SqlToPure.call("greaterThanEqual", n,
                            integer(0))), SqlToPure.call("lessThanEqual", p, AppliedFunction.infixRun("plus",
                    List.of(SqlToPure.call("length", str), integer(1)))));
            ValueSpecification value = AppliedFunction.infixRun("plus", List.of(SqlToPure.call("left", str,
                    AppliedFunction.infixRun("minus", List.of(p, integer(1)))), replacement,
                    SqlToPure.call("substring", str, AppliedFunction.infixRun("plus", List.of(p, n)))));
            return ifThen(valid, value, SqlToPure.empty());
        });
        return Tx.value(r.vs(), Kind.STRING, true, r.detail());
    }

    /** A string's value, '' for a NULL. */
    private static ValueSpecification textValue(Tx t) {
        ValueSpecification v = SqlToPure.valueOf(t);
        return t.nullable() ? SqlToPure.call("coalesce", v, new CString("")) : v;
    }

    /** {@code ASCII(s)}: its first character's code (an ISO-8859-1 database's byte), 0 for '', a SMALLINT. In a
     *  {@code sqla17} profile's UTF-8 database a character above 127 is NULL (measured), refused by name. */
    private Tx ascii(String name, SqlAst.FunctionCall f, List<Tx> args) {
        if (!profile.latin1()) {
            throw SqlTranslationException.unsupported(name + " in profile " + profile.spelling() + "'s UTF-8 database"
                    + " is not supported yet: a character above 127 is NULL there", f.pos());
        }
        Tx s = text(args.get(0), f.args().get(0), name);
        return strict(Kind.INTEGER, IqType.integer("SMALLINT", BigInteger.ZERO, BigInteger.valueOf(255), false),
                List.of(s), v -> SqlToPure.call("ascii", v.get(0)));
    }

    /** {@code CHAR(n)}: the character of code n (an ISO-8859-1 database's), NULL outside 0 .. 255. Refused by name: a
     *  code that may be 0 (a NUL, which lite's databases cannot hold), and above 127 in a {@code sqla17} profile's UTF-8
     *  database (SQL Anywhere answers a lone byte there). */
    private Tx chr(String name, SqlAst.FunctionCall f, List<Tx> args) {
        SqlAst.Expr e = f.args().get(0);
        if (!(e instanceof SqlAst.ColumnRef) && signedInteger(e) == null && !(e instanceof SqlAst.NullLit)) {
            throw SqlTranslationException.unsupported(name + " of a computed code is not supported yet: DuckDB's chr"
                    + " takes a 32-bit integer, which lite's lowering does not cast to yet (a column or a constant is"
                    + " read)", e.pos());
        }
        Tx n = count(args.get(0), e, name);
        if (n.kind() == Kind.NULL) {
            return nullString();
        }
        IqType t = (IqType) java.util.Objects.requireNonNull(n.detail());
        if (t.lo().signum() <= 0 && t.hi().signum() >= 0) {
            throw SqlTranslationException.unsupported(name + " of a code that may be 0 is not supported: CHAR(0) is a"
                    + " NUL character, which lite's databases cannot hold", f.pos());
        }
        if (!profile.latin1() && t.hi().compareTo(BigInteger.valueOf(127)) > 0) {
            throw SqlTranslationException.unsupported(name + " of a code above 127 is not supported in profile "
                    + profile.spelling() + "'s UTF-8 database: SQL Anywhere answers a lone byte there", f.pos());
        }
        boolean outside = t.lo().signum() < 0 || t.hi().compareTo(BigInteger.valueOf(255)) > 0;
        Tx r = strict(Kind.STRING, varchar(), List.of(n), v -> outside ? ifThen(SqlToPure.call("or", lt(v.get(0),
                integer(1)), SqlToPure.call("greaterThan", v.get(0), integer(255))), SqlToPure.empty(),
                SqlToPure.call("char", v.get(0))) : SqlToPure.call("char", v.get(0)));
        return outside ? Tx.value(r.vs(), Kind.STRING, true, r.detail()) : r;
    }

    /** {@code STRING(a, ...)}: the values as text, joined; a NULL among them is '', all NULL is NULL (measured). An
     *  integer joins as its digits; a value IQ writes by its own format (a NUMERIC, a DOUBLE, a date) is refused. */
    private Tx string(SqlAst.FunctionCall f, List<Tx> args) {
        List<Tx> present = new ArrayList<>();
        for (int i = 0; i < args.size(); i++) {
            Tx a = args.get(i);
            if (a.kind() == Kind.NULL) {
                continue;
            }
            present.add(text(a, f.args().get(i), "STRING"));
        }
        if (present.isEmpty()) {
            return nullString();
        }
        boolean nullable = true;
        List<ValueSpecification> texts = new ArrayList<>();
        ValueSpecification allNull = null;
        for (Tx p : present) {
            nullable &= p.nullable();
            texts.add(textValue(p));
            ValueSpecification isNull = SqlToPure.call("isEmpty", SqlToPure.valueOf(p));
            allNull = allNull == null ? isNull : SqlToPure.call("and", allNull, isNull);
        }
        ValueSpecification joined = texts.size() == 1 ? texts.get(0) : AppliedFunction.infixRun("plus", texts);
        if (!nullable) {
            return Tx.value(joined, Kind.STRING, false, varchar());
        }
        return Tx.value(ifThen(java.util.Objects.requireNonNull(allNull), SqlToPure.empty(), joined), Kind.STRING, true,
                varchar());
    }

    // ------------------------------------------------------------------ numbers

    /**
     * {@code ABS(x)}: in SQL Anywhere's front end, a SMALLINT's an unsigned SMALLINT and an INTEGER's an unsigned
     * INTEGER (no overflow at the smallest value), a BIGINT's a NUMERIC(20,0), a NUMERIC's and a DOUBLE's their own
     * type, a BIT itself (measured); under the IQ engine an integer's is its own type, so the type's smallest value is
     * IQ's error -158 (the research's D-F5, the manual's: never a wrong value, only an error). A string (IQ's NUMERIC
     * conversion) is refused.
     */
    private Tx abs(SqlAst.FunctionCall f, List<Tx> args) {
        Tx x = args.get(0);
        SqlAst.Expr e = f.args().get(0);
        if (x.kind() == Kind.NULL) {
            return Tx.value(SqlToPure.nullOf(Kind.INTEGER).vs(), Kind.INTEGER, true, new IqType("UNSIGNED SMALLINT"));
        }
        if (x.kind() == Kind.FLOAT) {
            doubleOnly(x, e, "ABS");
            return strict(Kind.FLOAT, new IqType("DOUBLE"), List.of(x), v -> SqlToPure.call("abs", v.get(0)));
        }
        if (decimal(x)) {
            // an integer constant beyond an INTEGER is IQ's NUMERIC (-2147483648): as a decimal, never Postgres's int4
            boolean integer = x.kind() == Kind.INTEGER;
            scaleOf(x, e, "ABS");
            IqType type = (IqType) java.util.Objects.requireNonNull(x.detail());
            return strict(Kind.DECIMAL, type, List.of(x), v -> SqlToPure.call("abs", integer
                    ? SqlToPure.call("toDecimal", v.get(0)) : v.get(0)));
        }
        IqType t = integerType(x, e, "ABS");
        BigInteger top = t.lo().abs().max(t.hi().abs());
        BigInteger bottom = t.lo().signum() <= 0 && t.hi().signum() >= 0 ? BigInteger.ZERO
                : t.lo().abs().min(t.hi().abs());
        if (t.family().equals("BIT")) {
            return x;
        }
        if (engine == IqDialect.Engine.IQ) {
            boolean overflows = top.compareTo(t.max()) > 0;
            if (overflows) {
                raisesHere(f, "-158 (the absolute value of " + t.family() + "'s smallest value)");
            }
            // a BIGINT's overflow is the database's own error, but over a value it may compute wider (IQ island,
            // 2026-10-14, review 5's B1: a host variable's)
            boolean checked = !t.family().equals("BIGINT") || t.wider();
            boolean widen = !t.wide() && !t.exact();
            Tx r = SqlToPure.strict(Kind.INTEGER, List.of(x), v -> {
                ValueSpecification a = SqlToPure.call("abs", widen ? SqlToPure.call("floor", v.get(0)) : v.get(0));
                return overflows && checked ? ifThen(SqlToPure.call("greaterThan", a, SqlToPure.integer(t.max())),
                        raise("Integer"), a) : a;
            });
            return Tx.value(r.vs(), Kind.INTEGER, r.nullable(), IqType.integer(t.family(), bottom, top.min(t.max()),
                    widen || t.wide(), t.wider()));
        }
        if (t.family().equals("BIGINT")) {
            return strict(Kind.DECIMAL, new IqType("NUMERIC(20,0)"), List.of(x), v -> SqlToPure.call("abs",
                    SqlToPure.call("toDecimal", v.get(0))));
        }
        boolean widen = !t.wide() && !t.exact();
        Tx r = SqlToPure.strict(Kind.INTEGER, List.of(x), v -> SqlToPure.call("abs", widen ? SqlToPure.call("floor",
                v.get(0)) : v.get(0)));
        return Tx.value(r.vs(), Kind.INTEGER, r.nullable(), IqType.integer("UNSIGNED " + t.family(), bottom, top,
                true));
    }

    /** {@code SIGN(x)}: -1, 0 or 1, a SMALLINT (of -0.0, 0). */
    private Tx sign(SqlAst.FunctionCall f, List<Tx> args) {
        Tx x = args.get(0);
        if (x.kind() != Kind.NULL) {
            number(x, f.args().get(0), "SIGN");
        }
        return strict(Kind.INTEGER, IqType.integer("SMALLINT", BigInteger.ONE.negate(), BigInteger.ONE, false),
                List.of(x), v -> SqlToPure.call("sign", v.get(0)));
    }

    /** 2^52: a DOUBLE of this magnitude or more is a whole number. */
    private static final double WHOLE = 4503599627370496.0;

    /**
     * {@code CEILING(x)}, {@code CEIL(x)}, {@code FLOOR(x)} in SQL Anywhere's front end: the argument as a DOUBLE first
     * (measured: FLOOR of NUMERIC 0.99999999999999999999 is 1), then its whole number, a DOUBLE. Under the IQ engine
     * the result is the argument's own type (the research's D-F2, not yet verified): refused.
     */
    private Tx ceilingFloor(boolean floor, String name, SqlAst.FunctionCall f, List<Tx> args) {
        if (engine == IqDialect.Engine.IQ) {
            throw SqlTranslationException.unsupported(name + " is not supported under the IQ engine yet: its result"
                    + " type there is the argument's (the research's D-F2, not yet verified), SQL Anywhere's a DOUBLE",
                    f.pos());
        }
        Tx x = asDouble(args.get(0), f.args().get(0), name);
        return strict(Kind.FLOAT, new IqType("DOUBLE"), List.of(x), v -> {
            ValueSpecification d = v.get(0);
            return ifThen(SqlToPure.call("greaterThanEqual", SqlToPure.call("abs", d), new CFloat(WHOLE)), d,
                    SqlToPure.call("toFloat", SqlToPure.call(floor ? "floor" : "ceiling", d)));
        });
    }

    /**
     * {@code ROUND(x, n)} (half away from zero) and {@code TRUNCNUM(x, n)} (toward zero) to n places, n a constant (of
     * at least -18): an integer's a NUMERIC(10,0) (a BIGINT's NUMERIC(20,0)), a NUMERIC's its own type -- a result its
     * precision cannot hold is IQ's error -158, but for a constant (SQL Anywhere types a folded one a digit wider).
     * Refused by name: ROUND under the IQ engine (its type and ROUND_TO_EVEN are the research's D-F1), a DOUBLE (SQL
     * Anywhere rounds its decimal digits, measured: ROUND(2.675e0, 2) is 2.68: a native), a NUMERIC whose scaled value
     * may exceed 18 digits.
     */
    private Tx roundTruncate(boolean round, String name, SqlAst.FunctionCall f, List<Tx> args) {
        if (round && engine == IqDialect.Engine.IQ) {
            throw SqlTranslationException.unsupported(name + " is not supported under the IQ engine yet: its result"
                    + " type there and the ROUND_TO_EVEN option are the research's D-F1, not yet verified", f.pos());
        }
        Tx x = args.get(0);
        SqlAst.Expr e = f.args().get(0);
        BigInteger places = signedInteger(f.args().get(1));
        if (args.get(1).kind() == Kind.NULL) {
            places = null;
        }
        if (places == null && args.get(1).kind() != Kind.NULL) {
            throw SqlTranslationException.unsupported(name + " with a number of places other than an integer constant"
                    + " is not supported yet", f.args().get(1).pos());
        }
        if (x.kind() == Kind.NULL) {
            return Tx.value(SqlToPure.nullOf(Kind.FLOAT).vs(), Kind.FLOAT, true, new IqType("DOUBLE"));
        }
        if (x.kind() == Kind.FLOAT) {
            throw SqlTranslationException.unsupported(name + " of a DOUBLE is not supported yet: SQL Anywhere rounds"
                    + " its decimal digits (ROUND(2.675e0, 2) is 2.68), which needs a native", f.pos());
        }
        int p;
        int s;
        int digits;
        boolean whole;
        if (decimal(x)) {
            int[] ps = scaleOf(x, e, name);
            p = ps[0];
            s = ps[1];
            digits = p - s;
            whole = false;
        } else {
            IqType t = integerType(x, e, name);
            p = t.family().equals("BIGINT") ? 20 : 10;
            s = 0;
            digits = Math.max(t.lo().abs().toString().length(), t.hi().abs().toString().length());
            whole = true;
        }
        IqType type = new IqType("NUMERIC(" + p + "," + s + ")");
        if (places == null) {
            return Tx.value(SqlToPure.nullOf(Kind.DECIMAL).vs(), Kind.DECIMAL, true, type);
        }
        if (places.compareTo(BigInteger.valueOf(-18)) < 0) {
            throw SqlTranslationException.unsupported(name + " to fewer than -18 places is not supported yet",
                    f.args().get(1).pos());
        }
        int n = places.min(BigInteger.valueOf(s)).intValue();
        if (n >= s) {
            // no place to drop: the value itself, at its scale
            return strict(Kind.DECIMAL, type, List.of(x), v -> whole ? SqlToPure.call("toDecimal", v.get(0)) : v.get(0));
        }
        if (digits + n > 18) {
            throw SqlTranslationException.unsupported(name + " of a NUMERIC(" + p + "," + s + ") to " + n + " places is"
                    + " not supported yet: its scaled value may exceed 18 digits", f.pos());
        }
        // a constant (no column in it) SQL Anywhere folds and types a digit wider (measured): no check
        boolean check = round && readsColumn(e) && !whole;
        if (check) {
            raisesHere(f, "-158 (a rounded value its precision cannot hold)");
        }
        return strict(Kind.DECIMAL, type, List.of(x), v -> {
            ValueSpecification value = whole ? SqlToPure.call("toDecimal", v.get(0)) : v.get(0);
            ValueSpecification r = places(value, n, round);
            if (s > Math.max(n, 0)) {
                r = atScale(r, s);
            }
            if (!check) {
                return r;
            }
            return ifThen(SqlToPure.call("greaterThanEqual", SqlToPure.call("abs", r), new CDecimal(pow10(p - s))),
                    raise("Decimal"), r);
        });
    }

    /** {@code x} (a decimal) to {@code n} places, half away from zero or toward zero, at scale max(n, 0): exact
     *  decimal arithmetic, its scaled value within 18 digits. */
    private static ValueSpecification places(ValueSpecification x, int n, boolean round) {
        ValueSpecification scaled = n == 0 ? x : AppliedFunction.infixRun("times", List.of(x, new CDecimal(pow10(n))));
        ValueSpecification positive = SqlToPure.call("greaterThanEqual", x, integer(0));
        ValueSpecification half = new CDecimal(new BigDecimal("0.5"));
        ValueSpecification whole = round
                ? ifThen(positive, SqlToPure.call("floor", AppliedFunction.infixRun("plus", List.of(scaled, half))),
                        SqlToPure.call("ceiling", AppliedFunction.infixRun("minus", List.of(scaled, half))))
                : ifThen(positive, SqlToPure.call("floor", scaled), SqlToPure.call("ceiling", scaled));
        ValueSpecification back = SqlToPure.call("toDecimal", whole);
        return n == 0 ? back : AppliedFunction.infixRun("times", List.of(back, new CDecimal(pow10(-n))));
    }

    /** 10 to the {@code k}, written with no exponent: 100 for 2, 0.01 for -2. */
    private static BigDecimal pow10(int k) {
        return k >= 0 ? new BigDecimal(BigInteger.TEN.pow(k)) : BigDecimal.ONE.movePointLeft(-k);
    }

    /** {@code x} at scale {@code s} (no fewer places than it has): a 38-digit zero, then a zero of that scale added.
     *  IQ island, 2026-10-14 (natives): the 38-digit zero first -- DuckDB adds two decimals of at most 18 digits in
     *  18 ({@code 756929791864553.5 + 0.00000} overflows there), where Postgres's numeric has no limit. */
    private static ValueSpecification atScale(ValueSpecification x, int s) {
        return AppliedFunction.infixRun("plus", List.of(x, SqlToPure.call("toDecimal", new CInteger(0L)),
                new CDecimal(BigDecimal.ZERO.setScale(s))));
    }

    // ------------------------------------------------------------------ exact NUMERIC division (IQ island, 2026-10-14)

    /**
     * SQL Anywhere's NUMERIC quotient {@code x / y}, of IQ's type NUMERIC(p, s) (the research's TYPING §4: p1 + p2 + 1
     * and s1 + p2 - s2 + 1, then the profile's cap; for AVG, AVG's type), where x is a decimal of scale {@code s1} with
     * at most {@code whole1} digits before its point, y one of scale {@code s2} with at most {@code whole2}, neither NULL
     * and y not 0 (the caller raises IQ's -628 first), and |x / y| below 10^(p - s), as IQ's type promises.
     * <p>
     * The value, measured on SQL Anywhere 17 over 3,400 quotients of every shape in both kinds of database (and 129
     * AVGs): the exact quotient cut to ceil((p + 1) / 2) base-100 digits from its first nonzero one, then rounded half
     * away from zero at scale s. That is the quotient rounded half away from zero at scale s -- but truncated there when
     * p is odd, s even and the quotient has all its p - s whole digits (two NUMERIC(1,0)s: 5/3 is 1.66, 2/3 is .67;
     * NUMERIC(2,1) 8.5 / NUMERIC(1,0) 8 is 1.063).
     * <p>
     * Composed of existing natives, exactly: n = |x| * 10^s and d = |y|, so that n / d = |x / y| * 10^s;
     * the rounded quotient is the whole quotient of 2n + d by 2d, the truncated one that of n by d ({@link #whole}); the
     * sign is x's and y's. Every step is a decimal of at most 38 digits, its scale counted (DuckDB's limit): the
     * caller refuses what {@link #quotientFits} says would need more. x and y are 38-digit decimals already (DuckDB
     * multiplies two narrower ones in 64 bits).
     * <p>
     * The value is the one SQL Anywhere shows; as an operand, a CAST, a comparison or a key it reads the quotient cut
     * to base-100 digits, unrounded ({@code (10.0/3)*3} is 10.00), so a caller uses this only where the quotient is
     * shown. AVG of a NUMERIC (measured: 129 AVGs, every one SUM / COUNT by this rule at AVG's NUMERIC(p+19, s+11)):
     * {@code quotient(sum, s, wholeOfSum, count, 0, 19, pAvg, sAvg)}, shown only, as {@code /}.
     */
    static ValueSpecification quotient(ValueSpecification x, int s1, int whole1, ValueSpecification y, int s2,
            int whole2, int p, int s) {
        if (!quotientFits(s1, whole1, s2, whole2, p, s)) {
            throw new IllegalStateException("a quotient of more than 38 digits");
        }
        ValueSpecification ax = SqlToPure.call("abs", x);
        ValueSpecification ay = SqlToPure.call("abs", y);
        ValueSpecification n = s > 0 ? times(ax, new CDecimal(pow10(s))) : ax;
        ValueSpecification d = ay;
        BigDecimal bound = pow10(p);
        ValueSpecification two = new CDecimal(BigDecimal.valueOf(2));
        ValueSpecification m = whole(AppliedFunction.infixRun("plus", List.of(times(two, n), d)), times(two, d), bound);
        if (p % 2 == 1 && s % 2 == 0) {
            // the quotient has all its whole digits when |x| >= |y| * 10^(p - 1 - s)
            int k = p - 1 - s;
            ValueSpecification full = SqlToPure.call("greaterThanEqual", k < 0 ? times(ax, new CDecimal(pow10(-k)))
                    : ax, k > 0 ? times(ay, new CDecimal(pow10(k))) : ay);
            m = ifThen(full, whole(n, d, bound), m);
        }
        ValueSpecification negative = SqlToPure.call("or", SqlToPure.call("and", lt(x, integer(0)),
                SqlToPure.call("greaterThan", y, integer(0))), SqlToPure.call("and",
                SqlToPure.call("greaterThan", x, integer(0)), lt(y, integer(0))));
        BigDecimal unit = pow10(-s);
        return times(m, ifThen(negative, new CDecimal(unit.negate()), new CDecimal(unit)));
    }

    /** Whether {@link #quotient}'s steps fit lite's 38 digits, scales counted (n = |x| * 10^s keeps x's scale, and
     *  2n + d, a - q * b the larger one). */
    static boolean quotientFits(int s1, int whole1, int s2, int whole2, int p, int s) {
        int k = p - 1 - s;
        return p <= 38 && Math.max(whole1 + s, whole2) + 1 + Math.max(s1, s2) <= 38
                && (p % 2 == 0 || s % 2 == 1 || whole2 + Math.max(k, 0) + s2 <= 38
                && whole1 + Math.max(-k, 0) + s1 <= 38);
    }

    /** Whether {@link #remainder}'s operands at one scale fit lite's 38 digits. */
    static boolean remainderFits(int s1, int whole1, int s2, int whole2) {
        int scale = Math.max(s1, s2);
        return whole1 + scale <= 38 && whole2 + scale <= 38;
    }

    /** How far below the true quotient a biased double estimate may fall, relatively: the bias ({@link #BELOW}) and
     *  the databases' own rounding (a decimal to a double, a double division: a few units in the last place). */
    private static final BigDecimal SLACK = new BigDecimal("6e-14");

    /** The factor each double estimate of a quotient is scaled by before it is cut: 1 - 2^-44, so that no estimate
     *  exceeds the true quotient (the databases' rounding is fifty times smaller). */
    private static final double BELOW = 1 - 0x1p-44;

    /** The largest whole number Pure's {@code floor} of a double gives exactly (a BIGINT): 10^18. */
    private static final int FLOOR_DIGITS = 18;

    /**
     * The whole quotient of {@code a} by {@code b} (decimals, a >= 0, b > 0), below {@code bound}: exactly, from double
     * estimates. An estimate of the rest's quotient, (a - q * b) / b computed in doubles, scaled by {@link #BELOW} and
     * cut to a whole number (to a multiple of 10^c while it may exceed a BIGINT), never exceeds the true one, so q stays
     * at most the quotient and a - q * b at least 0; each estimate leaves at most {@link #SLACK} of the rest plus 10^c
     * unfound, so a quotient of 38 digits takes three, one of 13 or fewer one; the last leaves the quotient or one less,
     * which a comparison of the rest with b settles. The bounds are worked out from the types alone.
     */
    private static ValueSpecification whole(ValueSpecification a, ValueSpecification b, BigDecimal bound) {
        ValueSpecification q = null;
        BigDecimal left = bound;
        while (true) {
            ValueSpecification rest = q == null ? a : AppliedFunction.infixRun("minus", List.of(a, times(q, b)));
            BigDecimal most = left.add(BigDecimal.ONE);
            boolean last = most.multiply(SLACK).compareTo(BigDecimal.ONE) < 0;
            int c = last ? 0 : Math.max(0, most.toBigInteger().toString().length() - FLOOR_DIGITS);
            ValueSpecification estimate = times(SqlToPure.call("divide", rest, b), new CFloat(BELOW));
            if (c > 0) {
                estimate = SqlToPure.call("divide", estimate, new CFloat(Math.pow(10, c)));
            }
            ValueSpecification found = SqlToPure.call("toDecimal", SqlToPure.call("floor", estimate));
            if (c > 0) {
                found = times(found, new CDecimal(pow10(c)));
            }
            q = q == null ? found : AppliedFunction.infixRun("plus", List.of(q, found));
            if (last) {
                break;
            }
            left = most.multiply(SLACK).add(pow10(c)).setScale(0, java.math.RoundingMode.CEILING);
        }
        ValueSpecification rest = AppliedFunction.infixRun("minus", List.of(a, times(q, b)));
        return AppliedFunction.infixRun("plus", List.of(q, ifThen(SqlToPure.call("greaterThanEqual", rest, b),
                new CDecimal(BigDecimal.ONE), new CDecimal(BigDecimal.ZERO))));
    }

    /**
     * SQL Anywhere's NUMERIC remainder {@code x % y}: x - y * the quotient cut toward zero, exactly (the dividend's
     * sign; measured on 274 remainders), of scale max(s1, s2) -- x of scale {@code s1} with at most {@code whole1}
     * digits before its point, y of scale {@code s2} and {@code whole2}, neither NULL, y not 0. Pure's {@code rem} of the
     * two at one scale (both databases' mod is exact for two decimals of one scale; DuckDB's of two scales is a
     * double's); the caller refuses what {@link #remainderFits} says would need more than 38 digits.
     */
    static ValueSpecification remainder(ValueSpecification x, int s1, int whole1, ValueSpecification y, int s2,
            int whole2) {
        if (!remainderFits(s1, whole1, s2, whole2)) {
            throw new IllegalStateException("a remainder of more than 38 digits");
        }
        int scale = Math.max(s1, s2);
        return SqlToPure.call("rem", s1 < scale ? atScale(x, scale) : x, s2 < scale ? atScale(y, scale) : y);
    }

    private static ValueSpecification times(ValueSpecification a, ValueSpecification b) {
        return AppliedFunction.infixRun("times", List.of(a, b));
    }

    /** {@code MOD(a, b)} and {@code REMAINDER(a, b)}: IQ's {@code a % b} (the same function: the dividend's sign, a
     *  zero divisor IQ's error -628, IQ's types). */
    private Tx mod(String name, SqlAst.FunctionCall f, SqlToPure.At at) {
        SqlAst.Expr divisor = f.args().get(1);
        boolean safe = divisor instanceof SqlAst.IntegerLit d && d.value().signum() != 0 && constantNumber(f.args()
                .get(0));
        if (!safe) {
            raisesHere(f, "-628 (a zero divisor) or -158 (out of range)");
        }
        return dialect.binary(new SqlAst.Binary(SqlAst.BinaryOp.MODULO, f.args().get(0), divisor, f.pos()), at);
    }

    /** The largest argument {@code EXP} gives a finite DOUBLE for (exp's of the next DOUBLE overflows). */
    private static final double EXP_LARGEST = 709.782712893384;

    /** The largest argument {@code EXP} gives 0 for (measured: SQL Anywhere's 0.0, where Postgres raises an
     *  underflow). */
    private static final double EXP_ZERO = -745.1332191019412;

    /**
     * {@code SQRT}, {@code EXP}, {@code LN}, {@code LOG} (natural: the research's SEMANTICS §3.6.2) and {@code LOG10}:
     * of the argument as a DOUBLE, a DOUBLE. Outside the domain (a negative root, the logarithm of 0 or less, an
     * exponential past the largest DOUBLE) SQL Anywhere's front end answers NULL (measured) and the IQ engine raises
     * an error (the research's D-F9): never a wrong value either way.
     */
    private Tx mathematical(Fn fn, String name, SqlAst.FunctionCall f, List<Tx> args) {
        Tx x = asDouble(args.get(0), f.args().get(0), name);
        boolean iq = engine == IqDialect.Engine.IQ;
        if (iq) {
            raisesHere(f, "an error outside its domain (the research's D-F9)");
        }
        Tx r = strict(Kind.FLOAT, new IqType("DOUBLE"), List.of(x), v -> {
            ValueSpecification d = v.get(0);
            ValueSpecification outside = iq ? raise("Float") : SqlToPure.empty();
            return switch (fn) {
                case SQRT -> ifThen(lt(d, new CFloat(0.0)), outside, SqlToPure.call("sqrt", d));
                case LN, LOG -> ifThen(SqlToPure.call("lessThanEqual", d, new CFloat(0.0)), outside,
                        SqlToPure.call("log", d));
                case LOG10 -> ifThen(SqlToPure.call("lessThanEqual", d, new CFloat(0.0)), outside,
                        SqlToPure.call("log10", d));
                case EXP -> ifThen(SqlToPure.call("greaterThan", d, new CFloat(EXP_LARGEST)), outside,
                        ifThen(SqlToPure.call("lessThanEqual", d, new CFloat(EXP_ZERO)), new CFloat(0.0),
                                SqlToPure.call("exp", d)));
                default -> throw new IllegalStateException("not a function of a DOUBLE: " + fn);
            };
        });
        return Tx.value(r.vs(), Kind.FLOAT, true, r.detail());
    }

    // ------------------------------------------------------------------ dates

    /** The months' and the weekdays' names, as DATENAME writes them. */
    private static final String[] MONTHS = {"January", "February", "March", "April", "May", "June", "July", "August",
        "September", "October", "November", "December"};
    private static final String[] WEEKDAYS = {"Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday",
        "Saturday"};

    private static final String DURATION_UNIT = "meta::pure::functions::date::DurationUnit";
    private static final String DAY_OF_WEEK = "meta::pure::functions::date::DayOfWeek";

    /** A date argument: a DATE or a TIMESTAMP, or a constant string in ISO spelling (IQ reads many more spellings,
     *  by the profile's date order: refused by name); null for a NULL. */
    private @com.legend.base.Nullable Tx dateArgument(Tx x, SqlAst.Expr e, String name) {
        if (x.kind() == Kind.NULL) {
            return null;
        }
        if (x.kind() == Kind.DATE || x.kind() == Kind.DATETIME) {
            return x;
        }
        if (x.kind() == Kind.STRING && e instanceof SqlAst.StringLit s) {
            String[] parts = isoParts(s.value(), e);
            if (parts != null) {
                String body = parts[0] + "T" + parts[1];
                return Tx.value(new CDate(PureDateLiteral.parse(body), body, null), Kind.DATETIME, false,
                        dated("TIMESTAMP", Integer.parseInt(parts[0].substring(0, 4))));
            }
        }
        throw SqlTranslationException.unsupported(name + " of " + kindName(x.kind()) + " is not supported yet: IQ"
                + " converts it to a date by its own rules (a constant string in a spelling of IQ's the island reads is"
                + " read)", e.pos());
    }

    /** {@code YEAR}, {@code MONTH}, {@code DAY}, {@code HOUR}, {@code MINUTE}, {@code SECOND}, {@code QUARTER},
     *  {@code DOW} (Sunday 1: SQL Anywhere's never moves; IQ's moves with DATE_FIRST_DAY_OF_WEEK, no profile's), and
     *  the DATEPART parts below: a SMALLINT (QUARTER an INTEGER). */
    private Tx datePartOf(Part part, String name, SqlAst.FunctionCall f, Tx x, SqlAst.Expr e, boolean small) {
        Tx d = dateArgument(x, e, name);
        String family = small && part != Part.QUARTER ? "SMALLINT" : "INTEGER";
        if (d == null) {
            return Tx.value(SqlToPure.nullOf(Kind.INTEGER).vs(), Kind.INTEGER, true, new IqType(family));
        }
        boolean date = d.kind() == Kind.DATE;
        IqType type = IqType.integer(family, BigInteger.valueOf(part.low), BigInteger.valueOf(part.high), true);
        return strict(Kind.INTEGER, type, List.of(d), v -> partValue(part, v.get(0), date));
    }

    /**
     * The date parts, as the reader names them (its lowercase full name: {@code yy} is {@code year}), with the values
     * each takes. The CAL parts are ISO 8601's, DOW is Sunday 1; TZOFFSET only reaches a refusal.
     */
    private enum Part {
        YEAR(1, 9999), QUARTER(1, 4), MONTH(1, 12), WEEK(1, 54), DAY(1, 31), DAYOFYEAR(1, 366), WEEKDAY(1, 7),
        HOUR(0, 23), MINUTE(0, 59), SECOND(0, 59), MILLISECOND(0, 999), MICROSECOND(0, 999_999),
        CALYEAROFWEEK(1, 9999), CALWEEKOFYEAR(1, 54), CALDAYOFWEEK(1, 7), DOW(1, 7), TZOFFSET(0, 0);

        final long low;
        final long high;

        Part(long low, long high) {
            this.low = low;
            this.high = high;
        }

        /** The part the reader's lowercase {@code name} names. */
        static Part of(String name) {
            for (Part p : values()) {
                if (p.name().toLowerCase(Locale.ROOT).equals(name)) {
                    return p;
                }
            }
            throw new IllegalStateException("no date part " + name);
        }

        boolean calendar() {
            return this == CALYEAROFWEEK || this == CALWEEKOFYEAR || this == CALDAYOFWEEK;
        }
    }

    /** A date part's value of {@code d} (a DATE when {@code date}, its time midnight). */
    private static ValueSpecification partValue(Part part, ValueSpecification d, boolean date) {
        return switch (part) {
            case YEAR -> SqlToPure.call("year", d);
            case QUARTER -> SqlToPure.call("quarterNumber", d);
            case MONTH -> SqlToPure.call("monthNumber", d);
            case DAY -> SqlToPure.call("dayOfMonth", d);
            case DAYOFYEAR -> SqlToPure.call("dayOfYear", d);
            case WEEKDAY, DOW -> SqlToPure.call("dayOfWeekNumber", d, new EnumValue(DAY_OF_WEEK, "Sunday"));
            // weeks from Sunday, week 1 the one holding 1 January: (day of the year - 1 + Sunday-based weekday of
            // 1 January - 1) / 7 + 1
            case WEEK -> AppliedFunction.infixRun("plus", List.of(wholeQuotient(
                    AppliedFunction.infixRun("plus", List.of(SqlToPure.call("dayOfYear", d), SqlToPure.call(
                            "dayOfWeekNumber", SqlToPure.call("firstDayOfYear", d), new EnumValue(DAY_OF_WEEK,
                                    "Sunday")), integer(-2))), 7), integer(1)));
            case HOUR -> date ? integer(0) : SqlToPure.call("hour", d);
            case MINUTE -> date ? integer(0) : SqlToPure.call("minute", d);
            case SECOND -> date ? integer(0) : SqlToPure.call("second", d);
            case MILLISECOND -> date ? integer(0) : wholeQuotient(microseconds(d), 1000);
            case MICROSECOND -> date ? integer(0) : microseconds(d);
            case CALDAYOFWEEK -> SqlToPure.call("dayOfWeekNumber", d);
            case CALWEEKOFYEAR -> SqlToPure.call("weekOfYear", d);
            // the ISO year: the year of the week's Thursday
            case CALYEAROFWEEK -> SqlToPure.call("year", SqlToPure.call("adjust", d, AppliedFunction.infixRun(
                    "minus", List.of(integer(4), SqlToPure.call("dayOfWeekNumber", d))), new EnumValue(DURATION_UNIT,
                    "DAYS")));
            case TZOFFSET -> throw new IllegalStateException("no value of TZOFFSET");
        };
    }

    /** The microseconds of a timestamp's second: its epoch microseconds modulo a million, from 0. */
    private static ValueSpecification microseconds(ValueSpecification d) {
        ValueSpecification epoch = SqlToPure.call("toEpochValue", d, new EnumValue(DURATION_UNIT, "MICROSECONDS"));
        CInteger million = integer(1_000_000);
        return SqlToPure.call("mod", AppliedFunction.infixRun("plus", List.of(SqlToPure.call("mod", epoch, million),
                million)), million);
    }

    /** {@code DATEPART(part, x)}, an INTEGER, and {@code DATENAME(part, x)}, a VARCHAR: the month's and the weekday's
     *  names, any other part's digits. The ISO parts (cdw, cwk, cyr) are SQL Anywhere's; IQ's manual's are
     *  Sunday-based (the research's D-T10): refused under the IQ engine. TZOFFSET is refused. */
    private Tx datePartCall(boolean name, String fnName, SqlAst.FunctionCall f, List<Tx> args) {
        Part part = Part.of(((SqlAst.StringLit) f.args().get(0)).value());
        if (part == Part.TZOFFSET) {
            throw SqlTranslationException.unsupported(fnName + "(TZOFFSET, ...) is not supported: lite reads no time"
                    + " zones", f.pos());
        }
        if (part.calendar() && engine == IqDialect.Engine.IQ) {
            throw SqlTranslationException.unsupported(fnName + "(" + part + ", ...) is not"
                    + " supported under the IQ engine yet: SQL Anywhere's is ISO 8601, IQ's manual's Sunday-based (the"
                    + " research's D-T10, not yet verified)", f.pos());
        }
        Tx value = datePartOf(part, fnName, f, args.get(1), f.args().get(1), false);
        if (!name) {
            return value;
        }
        if (value.kind() == Kind.NULL || SqlToPure.isNullConstant(value)) {
            return nullString();
        }
        boolean month = part == Part.MONTH;
        boolean weekday = part == Part.WEEKDAY;
        Tx r = SqlToPure.strict(Kind.STRING, List.of(value), v -> month ? names(v.get(0), MONTHS)
                : weekday ? names(v.get(0), WEEKDAYS) : SqlToPure.call("toString", v.get(0)));
        return Tx.value(r.vs(), Kind.STRING, r.nullable(), varchar());
    }

    /** The name of number {@code n} (from 1) among {@code names}. */
    private static ValueSpecification names(ValueSpecification n, String[] names) {
        ValueSpecification v = new CString(names[names.length - 1]);
        for (int i = names.length - 2; i >= 0; i--) {
            v = ifThen(SqlToPure.call("equal", n, integer(i + 1)), new CString(names[i]), v);
        }
        return v;
    }

    /** {@code DATE(x)}: a timestamp's date, a date itself. */
    private Tx dateOf(String name, SqlAst.FunctionCall f, Tx x, SqlAst.Expr e) {
        Tx d = dateArgument(x, e, name);
        if (d == null) {
            return Tx.value(SqlToPure.nullOf(Kind.DATE).vs(), Kind.DATE, true, new IqType("DATE"));
        }
        if (d.kind() == Kind.DATE) {
            return d;
        }
        return strict(Kind.DATE, dated("DATE", latestYear(d)), List.of(d), v -> typed(SqlToPure.call("datePart",
                v.get(0)), "StrictDate"));
    }

    /** A timestamp of {@code d} (a DATE: its midnight). */
    private static ValueSpecification timestamp(ValueSpecification d, boolean date) {
        return date ? SqlToPure.call("date", SqlToPure.call("year", d), SqlToPure.call("monthNumber", d),
                SqlToPure.call("dayOfMonth", d), integer(0), integer(0), integer(0)) : d;
    }

    /**
     * {@code DATEADD(part, n, x)} in SQL Anywhere's front end: a TIMESTAMP, x moved by n parts (a month's end clamped:
     * '2026-01-31' plus a month is 2026-02-28; a quarter is three months, a week seven days, a day of the year or a
     * weekday a day). Refused by name: under the IQ engine (its result keeps a DATE: the research's D-T2, D-T12), a
     * MILLISECOND (SQL Anywhere cuts the timestamp to milliseconds first: its defect, D-T3), the CAL parts (no-ops in
     * SQL Anywhere), TZOFFSET, a count that is no integer.
     */
    private Tx dateAdd(String name, SqlAst.FunctionCall f, List<Tx> args) {
        if (engine == IqDialect.Engine.IQ) {
            throw SqlTranslationException.unsupported(name + " is not supported under the IQ engine yet: its result"
                    + " keeps a DATE there (the research's D-T2 and D-T12, not yet verified), SQL Anywhere's is a"
                    + " TIMESTAMP", f.pos());
        }
        Part part = Part.of(((SqlAst.StringLit) f.args().get(0)).value());
        String unit;
        long times = 1;
        switch (part) {
            case YEAR -> unit = "YEARS";
            case QUARTER -> {
                unit = "MONTHS";
                times = 3;
            }
            case MONTH -> unit = "MONTHS";
            case WEEK -> {
                unit = "DAYS";
                times = 7;
            }
            case DAY, DAYOFYEAR, WEEKDAY -> unit = "DAYS";
            case HOUR -> unit = "HOURS";
            case MINUTE -> unit = "MINUTES";
            case SECOND -> unit = "SECONDS";
            case MICROSECOND -> unit = "MICROSECONDS";
            case MILLISECOND -> throw SqlTranslationException.unsupported(name + "(MILLISECOND, ...) is not supported:"
                    + " SQL Anywhere cuts the timestamp to milliseconds first (its defect; the research's D-T3)",
                    f.pos());
            default -> throw SqlTranslationException.unsupported(name + "(" + part + ", ...) is not supported",
                    f.pos());
        }
        Tx n = args.get(1);
        IqType count = null;
        if (n.kind() != Kind.NULL) {
            count = integerType(n, f.args().get(1), name);
            // IQ island, 2026-10-14 (review 3's B4, measured): SQL Anywhere wraps some counts past IQ's range
            // instead of raising -158 (DAY 1,500,000,000 is 2598-05-12, WEEK 200,000,000 is 2408-03-25, QUARTER
            // 2,000,000,000 is 6036-09-01, YEAR -2,147,483,648 leaves the date as it is): its defect, refused by name
            BigInteger bound = WRAPS.get(part);
            if (bound != null && (count.hi().compareTo(bound) > 0 || count.lo().compareTo(bound.negate()) < 0)) {
                throw SqlTranslationException.unsupported(name + "(" + part + ", n, ...) with a count that may pass "
                        + bound + " is not supported: SQL Anywhere wraps such a count rather than raising its -158"
                        + " (its defect)", f.args().get(1).pos());
            }
        }
        Tx d = dateArgument(args.get(2), f.args().get(2), name);
        IqType type = new IqType("TIMESTAMP");
        if (d == null || n.kind() == Kind.NULL) {
            return Tx.value(SqlToPure.nullOf(Kind.DATETIME).vs(), Kind.DATETIME, true, type);
        }
        // IQ island, 2026-10-14 (review 4's M2, measured): from the year 7911 on SQL Anywhere 17 drops a timestamp's
        // hours and minutes (9999-12-31 22:30 plus an hour is 9999-12-31 00:00, 2026-01-01 10:30 plus 6000 years
        // 8026-01-01 00:00; and past 9999-12-31 it answers rather than raising): its defect, the study's §2.5. A result
        // that may reach those years with a time of day -- a part below a day, or a timestamp that may not be midnight
        // -- is refused by name; the latest year a date may reach is carried in its type (IqType.high)
        Integer latest = latestYear(d);
        int end = latest == null ? 9999 : latest + forwardYears(part, times, java.util.Objects.requireNonNull(count));
        boolean subDay = part == Part.HOUR || part == Part.MINUTE || part == Part.SECOND
                || part == Part.MICROSECOND;
        // a column's date of no known year is a stored one (a computed date carries its year): the parity schema holds
        // none past 7910 (the study's §2.5), and lite's answer there is the calendar's
        boolean stored = latest == null && f.args().get(2) instanceof SqlAst.ColumnRef;
        if (!stored && end > IqDialect.LAST_TIMED_YEAR && (subDay || !midnight(d))) {
            throw SqlTranslationException.unsupported(name + "(" + part + ", ...) that may reach a timestamp after the"
                    + " year " + IqDialect.LAST_TIMED_YEAR + " is not supported: SQL Anywhere 17 drops the hours and"
                    + " minutes of such a timestamp (its defect), so no answer of it judges lite's", f.pos());
        }
        type = dated("TIMESTAMP", Math.min(end, 9999));
        boolean date = d.kind() == Kind.DATE;
        long by = times;
        // adjust is of a Date: here of a DateTime, so one; a result outside IQ's years 1 to 9999 is IQ's -158
        // (IQ island, 2026-10-14, review 3's B4, measured: DATEADD(DAY, 1, '9999-12-31'), DATEADD(YEAR, -2026,
        // '2026-01-01'))
        return strict(Kind.DATETIME, type, List.of(n, d), v -> {
            ValueSpecification moved = typed(SqlToPure.call("adjust", timestamp(v.get(1), date), by == 1 ? v.get(0)
                    : AppliedFunction.infixRun("times", List.of(v.get(0), integer(by))), new EnumValue(DURATION_UNIT,
                    unit)), "DateTime");
            ValueSpecification year = SqlToPure.call("year", moved);
            return ifThen(SqlToPure.call("or", SqlToPure.call("greaterThan", year, integer(9999)), lt(year,
                    integer(1))), raise("DateTime"), moved);
        });
    }

    /** The latest year {@code d} (a DATE or a TIMESTAMP) may be in: a constant's own, else the one its type carries
     *  ({@link #dated}); null when it is not known. IQ island, 2026-10-14 (review 4's M2). */
    static @com.legend.base.Nullable Integer latestYear(Tx d) {
        if (SqlToPure.isNullConstant(d)) {
            // no value: no year
            return 1;
        }
        if (d.vs() instanceof CDate c && c.written() != null && java.util.Objects.requireNonNull(c.written()).length()
                >= 4) {
            return Integer.parseInt(java.util.Objects.requireNonNull(c.written()).substring(0, 4));
        }
        if (d.detail() instanceof IqType t && (t.family().equals("DATE") || t.family().equals("TIMESTAMP"))
                && t.high() != null) {
            return java.util.Objects.requireNonNull(t.high()).intValue();
        }
        return null;
    }

    /** A DATE or TIMESTAMP type whose values are in the year {@code latest} at the latest (null: not known). */
    static IqType dated(String spelling, @com.legend.base.Nullable Integer latest) {
        return new IqType(spelling, false, null, latest == null ? null : BigInteger.valueOf(latest));
    }

    /** Whether {@code d}'s time of day is midnight: a DATE's, or a timestamp constant's written so. */
    private static boolean midnight(Tx d) {
        if (d.kind() == Kind.DATE) {
            return true;
        }
        if (!(d.vs() instanceof CDate c) || c.written() == null) {
            return false;
        }
        String w = java.util.Objects.requireNonNull(c.written());
        for (int i = 10; i < w.length(); i++) {
            char ch = w.charAt(i);
            if (ch != 'T' && ch != ':' && ch != '.' && ch != '0') {
                return false;
            }
        }
        return true;
    }

    /** How many years at most {@code count} (of {@code times} {@code part}s each) moves a date forward. */
    private static int forwardYears(Part part, long times, IqType count) {
        BigInteger hi = count.hi().multiply(BigInteger.valueOf(times));
        if (hi.signum() <= 0) {
            return 0;
        }
        // the parts of a common year (a count of days of the shortest year moves the furthest)
        long perYear = switch (part) {
            case YEAR -> 1;
            case QUARTER, MONTH -> 12;
            case WEEK, DAY, DAYOFYEAR, WEEKDAY -> 365;
            case HOUR -> 8_760;
            case MINUTE -> 525_600;
            case SECOND -> 31_536_000;
            case MICROSECOND -> 31_536_000_000_000L;
            default -> throw new IllegalStateException("DATEADD reads no part " + part);
        };
        BigInteger[] qr = hi.divideAndRemainder(BigInteger.valueOf(perYear));
        BigInteger years = qr[1].signum() == 0 ? qr[0] : qr[0].add(BigInteger.ONE);
        return years.min(BigInteger.valueOf(10_000)).intValue();
    }

    /** The counts DATEADD's parts take before SQL Anywhere wraps one (measured; review 3's B4): a count beyond is
     *  refused. A MONTH, an HOUR, a MINUTE, a SECOND and a MICROSECOND of any INTEGER raise or answer rightly. */
    private static final java.util.Map<Part, BigInteger> WRAPS = java.util.Map.of(
            Part.DAY, BigInteger.valueOf(1_000_000_000L), Part.DAYOFYEAR, BigInteger.valueOf(1_000_000_000L),
            Part.WEEKDAY, BigInteger.valueOf(1_000_000_000L), Part.WEEK, BigInteger.valueOf(100_000_000L),
            Part.QUARTER, BigInteger.valueOf(1_500_000_000L), Part.YEAR, BigInteger.valueOf(2_147_483_647L));

    /**
     * {@code DATEDIFF(part, a, b)} in SQL Anywhere's front end (the research's SEMANTICS §2.2, measured): b - a in
     * boundaries crossed for YEAR, QUARTER, MONTH, WEEK (Sundays), DAY (midnights), MINUTE, MILLISECOND and
     * MICROSECOND; HOUR is the minute boundaries over 60 and SECOND the millisecond boundaries over 1000, truncated;
     * DAYOFYEAR is the difference of the two days of the year (2025-12-31 to 2026-01-01 is -364).
     * An INTEGER from YEAR to DAY, a BIGINT from HOUR down. Refused by name: under the IQ engine (whole elapsed units
     * and INTEGER there: the research's D-T11, D-T5), the weekday and CAL parts (SQL Anywhere answers 0), TZOFFSET.
     */
    private Tx dateDiff(String name, SqlAst.FunctionCall f, List<Tx> args) {
        if (engine == IqDialect.Engine.IQ) {
            throw SqlTranslationException.unsupported(name + " is not supported under the IQ engine yet: it counts"
                    + " whole elapsed time units there, typed INTEGER (the research's D-T11, not yet verified)",
                    f.pos());
        }
        Part part = Part.of(((SqlAst.StringLit) f.args().get(0)).value());
        boolean big = switch (part) {
            case YEAR, QUARTER, MONTH, WEEK, DAY, DAYOFYEAR -> false;
            case HOUR, MINUTE, SECOND, MILLISECOND, MICROSECOND -> true;
            default -> throw SqlTranslationException.unsupported(name + "(" + part + ", ...) is not supported (SQL"
                    + " Anywhere answers 0 for the weekday and CAL parts)", f.pos());
        };
        Tx a = dateArgument(args.get(1), f.args().get(1), name);
        Tx b = dateArgument(args.get(2), f.args().get(2), name);
        IqType type = big ? IqType.integer("BIGINT", IqType.BIGINT_MIN, IqType.BIGINT_MAX, true)
                : IqType.integer("INTEGER", IqType.INTEGER_MIN, IqType.INTEGER_MAX, true);
        if (a == null || b == null) {
            return Tx.value(SqlToPure.nullOf(Kind.INTEGER).vs(), Kind.INTEGER, true, type);
        }
        boolean aDate = a.kind() == Kind.DATE;
        boolean bDate = b.kind() == Kind.DATE;
        return strict(Kind.INTEGER, type, List.of(a, b), v -> {
            ValueSpecification x = v.get(0);
            ValueSpecification y = v.get(1);
            return switch (part) {
                case YEAR -> minus(SqlToPure.call("year", y), SqlToPure.call("year", x));
                case QUARTER -> minus(months(y, 4), months(x, 4));
                case MONTH -> minus(months(y, 12), months(x, 12));
                case DAY -> SqlToPure.call("dateDiff", day(x, aDate), day(y, bDate), new EnumValue(DURATION_UNIT,
                        "DAYS"));
                // measured: the days of the year's difference, not the days'
                case DAYOFYEAR -> minus(SqlToPure.call("dayOfYear", y), SqlToPure.call("dayOfYear", x));
                // Sundays crossed: the days between the Sundays on or before each, in weeks
                case WEEK -> exactQuotient(AppliedFunction.infixRun("plus", List.of(minus(SqlToPure.call("dateDiff",
                        day(x, aDate), day(y, bDate), new EnumValue(DURATION_UNIT, "DAYS")), sunday(y)), sunday(x))),
                        7);
                case MINUTE -> boundaries(x, aDate, y, bDate, 60_000_000L);
                case HOUR -> truncatedQuotient(boundaries(x, aDate, y, bDate, 60_000_000L), 60);
                case MILLISECOND -> boundaries(x, aDate, y, bDate, 1000L);
                case SECOND -> truncatedQuotient(boundaries(x, aDate, y, bDate, 1000L), 1000);
                case MICROSECOND -> minus(epochMicros(y, bDate), epochMicros(x, aDate));
                default -> throw new IllegalStateException("no DATEDIFF part " + part);
            };
        });
    }

    private static ValueSpecification minus(ValueSpecification a, ValueSpecification b) {
        return AppliedFunction.infixRun("minus", List.of(a, b));
    }

    /** year * 12 + month (per = 12), or year * 4 + quarter (per = 4). */
    private static ValueSpecification months(ValueSpecification d, int per) {
        return AppliedFunction.infixRun("plus", List.of(AppliedFunction.infixRun("times", List.of(
                SqlToPure.call("year", d), integer(per))), SqlToPure.call(per == 12 ? "monthNumber" : "quarterNumber",
                d)));
    }

    /** A date's day, as a DATE. */
    private static ValueSpecification day(ValueSpecification d, boolean date) {
        return date ? d : typed(SqlToPure.call("datePart", d), "StrictDate");
    }

    /** {@code d}'s weekday counted from Sunday, 0 to 6: the days since the Sunday on or before it. */
    private static ValueSpecification sunday(ValueSpecification d) {
        return minus(SqlToPure.call("dayOfWeekNumber", d, new EnumValue(DAY_OF_WEEK, "Sunday")), integer(1));
    }

    /** {@code d}'s microseconds from the epoch. */
    private static ValueSpecification epochMicros(ValueSpecification d, boolean date) {
        return SqlToPure.call("toEpochValue", timestamp(d, date), new EnumValue(DURATION_UNIT, "MICROSECONDS"));
    }

    /** The boundaries of {@code unit} microseconds crossed from a to b: (b - a) less the two remainders' difference,
     *  a multiple of unit, divided by it. */
    private static ValueSpecification boundaries(ValueSpecification a, boolean aDate, ValueSpecification b,
            boolean bDate, long unit) {
        ValueSpecification ea = epochMicros(a, aDate);
        ValueSpecification eb = epochMicros(b, bDate);
        return exactQuotient(minus(minus(eb, ea), minus(remainder(eb, unit), remainder(ea, unit))), unit);
    }

    /** {@code x} modulo {@code n}, from 0 to n - 1 whatever x's sign. */
    private static ValueSpecification remainder(ValueSpecification x, long n) {
        CInteger by = integer(n);
        return SqlToPure.call("mod", AppliedFunction.infixRun("plus", List.of(SqlToPure.call("mod", x, by), by)), by);
    }

    /**
     * {@code x / n} for an {@code x} that is a multiple of {@code n} whose quotient is below 2^52: the double quotient's
     * nearest integer, exact (x's double is within 2^-53 of x relatively, so the quotient within far less than a half
     * of its integer).
     */
    private static ValueSpecification exactQuotient(ValueSpecification x, long n) {
        return SqlToPure.call("round", SqlToPure.call("divide", x, integer(n)));
    }

    /** {@code x / n} rounded down, for a non-negative {@code x} below 2^52 (the double quotient's floor: exact, a
     *  quotient that is no integer lying at least 1/n from one). */
    private static ValueSpecification wholeQuotient(ValueSpecification x, long n) {
        return SqlToPure.call("floor", SqlToPure.call("divide", x, integer(n)));
    }

    /** {@code x / n} truncated toward zero, for an {@code x} below 2^52 in magnitude (as {@link #wholeQuotient}). */
    private static ValueSpecification truncatedQuotient(ValueSpecification x, long n) {
        ValueSpecification q = SqlToPure.call("divide", x, integer(n));
        return ifThen(SqlToPure.call("greaterThanEqual", x, integer(0)), SqlToPure.call("floor", q),
                SqlToPure.call("ceiling", q));
    }

    // ------------------------------------------------------------------ CAST and CONVERT

    /**
     * {@code CAST(x AS type)}, and {@code CONVERT(type, x)} without a style (they differ only where a date becomes
     * text: CONVERT writes style 0, refused; under the IQ engine the research's D-C6). Measured on SQL Anywhere 17:
     * <ul>
     *   <li>to SMALLINT, INTEGER, BIGINT: an integer as itself, a NUMERIC or a DOUBLE truncated toward zero, a string
     *       by IQ's strict reading (blanks around it, an optional sign, digits; nothing but blanks 0; anything else IQ's
     *       error -157); out of the type IQ's error -158;</li>
     *   <li>to NUMERIC(p, s) (DECIMAL; NUMERIC(p); an integer's NUMERIC(10,0) or (20,0) and a NUMERIC's own without a
     *       precision): rounded half away from zero to s places, more than p - s whole digits IQ's error -158;</li>
     *   <li>to DOUBLE: an integer, a NUMERIC of at most 15 digits, a constant numeric string;</li>
     *   <li>to CHAR(n), VARCHAR(n), LONG VARCHAR: a string as itself -- longer than n IQ's error -638 in SQL Anywhere's
     *       front end, unless the rest is blanks (measured), and under the IQ engine cut (the research's D-C2,
     *       refused); under the IQ engine a CHAR(n) is padded to n (D-S1); an integer its digits (too long: -157, in
     *       both engines); a NUMERIC IQ's text ({@code -.50}); a DATE yyyy-mm-dd;</li>
     *   <li>to DATE, TIMESTAMP (DATETIME): a date, a timestamp, a constant string in ISO spelling;</li>
     *   <li>to BIT, in SQL Anywhere's front end: 0 or 1 (a number truncated first), anything else -158.</li>
     * </ul>
     * Refused by name: what IQ converts by rules not read yet (a non-constant string to a number or a date, a DOUBLE to
     * a NUMERIC -- SQL Anywhere rounds its decimal digits, the IQ engine truncates: D-N6 --, a DOUBLE or a timestamp to
     * text), and the types the island does not read (REAL, TINYINT, unsigned, TIME, binary, national).
     */
    Tx cast(SqlAst.Cast c, SqlToPure.At at) {
        String typeName = c.type().name();
        boolean convert = typeName.startsWith(IqParser.CONVERT);
        String target = convert ? typeName.substring(IqParser.CONVERT.length()) : typeName;
        String what = convert ? "CONVERT" : "CAST";
        List<Integer> sizes = c.type().modifiers();
        Tx x = at.tx(c.operand());
        Tx result = switch (target) {
            case "SMALLINT", "INTEGER", "BIGINT" -> toInteger(target, x, c, what);
            case "NUMERIC" -> toNumeric(sizes, x, c, what);
            case "DOUBLE" -> toDouble(x, c, what);
            case "CHAR", "VARCHAR", "LONG VARCHAR" -> toText(target, sizes, x, c, what, convert);
            case "DATE", "TIMESTAMP" -> toDate(target.equals("DATE"), x, c, what);
            case "BIT" -> toBit(x, c, what);
            default -> throw SqlTranslationException.unsupported(what + " to " + target + " is not supported yet"
                    + (target.equals("REAL") ? " (a single-precision float)" : target.contains("INT")
                    ? " (an unsigned or TINYINT type)" : ""), c.pos());
        };
        return IqDialect.bounded(result, c.pos());
    }

    private static boolean isNull(Tx x) {
        return x.kind() == Kind.NULL || SqlToPure.isNullConstant(x);
    }

    /** CAST to an integer type: see {@link #cast}. */
    private Tx toInteger(String family, Tx x, SqlAst.Cast c, String what) {
        IqType target = new IqType(family);
        if (isNull(x)) {
            return Tx.value(SqlToPure.nullOf(Kind.INTEGER).vs(), Kind.INTEGER, true, target);
        }
        boolean bigint = family.equals("BIGINT");
        switch (x.kind()) {
            case INTEGER -> {
                IqType t = integerType(x, c.operand(), what);
                boolean inside = t.lo().compareTo(target.min()) >= 0 && t.hi().compareTo(target.max()) <= 0;
                if (inside) {
                    return Tx.value(x.vs(), Kind.INTEGER, x.nullable(), IqType.integer(family, t.lo(), t.hi(),
                            t.wide(), t.wider()));
                }
                raisesHere(c, "-158 (out of range)");
                return checkedRange(family, x, false, v -> v);
            }
            case DECIMAL, FLOAT -> {
                if (x.kind() == Kind.FLOAT) {
                    doubleOnly(x, c.operand(), what);
                } else {
                    scaleOf(x, c.operand(), what);
                }
                raisesHere(c, "-158 (out of range)");
                // toward zero; a value beyond a BIGINT is the database's own error
                return checkedRange(family, x, true, v -> ifThen(SqlToPure.call("greaterThanEqual", v,
                        integer(0)), SqlToPure.call("floor", v), SqlToPure.call("ceiling", v)));
            }
            case STRING -> {
                raisesHere(c, "-157 (not an integer) or -158 (out of range)");
                if (c.operand() instanceof SqlAst.StringLit s) {
                    BigInteger v = integerText(s.value());
                    if (v != null && v.bitLength() < Long.SIZE) {
                        return checkedRange(family, Tx.value(SqlToPure.integer(v), Kind.INTEGER, false,
                                IqType.integer("BIGINT", v, v, true)), false, w -> w);
                    }
                    if (v != null) {
                        throw SqlTranslationException.unsupported(what + " of a string beyond a BIGINT's range is not"
                                + " supported", c.operand().pos());
                    }
                    // IQ's error -157 where it is computed
                    Tx r = SqlToPure.strict(Kind.INTEGER, List.of(x), w -> raise("Integer"));
                    return Tx.value(r.vs(), Kind.INTEGER, true, target);
                }
                // IQ's reading (measured): a string that begins or ends with a space loses its white space at both
                // ends; what is left is an optional sign and digits (none: 0)
                Tx parsed = SqlToPure.strict(Kind.INTEGER, List.of(x), v -> {
                    ValueSpecification str = v.get(0);
                    ValueSpecification valid = SqlToPure.call("or", SqlToPure.call("or",
                            SqlToPure.call("matches", str, new CString("^ " + BLANKS + "*" + SIGNED + BLANKS + "*$")),
                            SqlToPure.call("matches", str, new CString("^" + BLANKS + "*" + SIGNED + BLANKS + "* $"))),
                            SqlToPure.call("matches", str, new CString("^" + SIGNED + "$")));
                    ValueSpecification bare = SqlToPure.call("regexpReplace", str, new CString(BLANKS + "+"),
                            new CString(""), new com.legend.protocol.spec.CBoolean(true));
                    return ifThen(valid, ifThen(SqlToPure.call("matches", bare, new CString("^[+-]?$")), integer(0),
                            SqlToPure.call("parseInteger", bare)), raise("Integer"));
                });
                Tx wide = Tx.value(parsed.vs(), Kind.INTEGER, parsed.nullable(), IqType.integer("BIGINT",
                        IqType.BIGINT_MIN, IqType.BIGINT_MAX, true));
                return bigint ? Tx.value(wide.vs(), Kind.INTEGER, wide.nullable(), IqType.integer(family,
                        IqType.BIGINT_MIN, IqType.BIGINT_MAX, true)) : checkedRange(family, wide, false, v -> v);
            }
            default -> throw SqlTranslationException.unsupported(what + " of " + kindName(x.kind()) + " to " + family
                    + " is not supported yet (IQ reads a date as the integer yyyymmdd)", c.pos());
        }
    }

    /** {@code build} of x's value, an integer, checked against {@code family}'s range (IQ's -158) unless a BIGINT
     *  (the database raises its own). */
    private Tx checkedRange(String family, Tx x, boolean widened, Function<ValueSpecification, ValueSpecification>
            build) {
        IqType target = new IqType(family);
        boolean check = !family.equals("BIGINT");
        boolean narrow = x.detail() instanceof IqType t && t.isInteger() && !t.wide() && !t.exact();
        Tx r = SqlToPure.strict(Kind.INTEGER, List.of(x), v -> {
            ValueSpecification value = build.apply(narrow ? SqlToPure.call("floor", v.get(0)) : v.get(0));
            return check ? ifThen(SqlToPure.call("or", lt(value, SqlToPure.integer(target.min())),
                    SqlToPure.call("greaterThan", value, SqlToPure.integer(target.max()))), raise("Integer"), value)
                    : value;
        });
        BigInteger lo = target.min();
        BigInteger hi = target.max();
        if (x.detail() instanceof IqType t && t.isInteger()) {
            lo = lo.max(t.lo());
            hi = hi.min(t.hi());
            if (lo.compareTo(hi) > 0) {
                lo = target.min();
                hi = target.max();
            }
        }
        return Tx.value(r.vs(), Kind.INTEGER, r.nullable(), IqType.integer(family, lo, hi, true,
                x.detail() instanceof IqType t && t.wider()));
    }

    /** An optional sign and digits, as a regular expression. */
    private static final String SIGNED = "[+-]?[0-9]*";

    /** IQ's reading of a string as an integer (measured): a string that begins or ends with a space loses the white
     *  space (the space, tab through carriage return) at both ends, any other keeps it; what is left is an optional
     *  sign and digits, no digit 0; null for any other spelling (a point, an exponent, a sign apart from its digits). */
    static @com.legend.base.Nullable BigInteger integerText(String text) {
        int start = 0;
        int end = text.length();
        if (!text.isEmpty() && (text.charAt(0) == ' ' || text.charAt(end - 1) == ' ')) {
            while (start < end && blank(text.charAt(start))) {
                start++;
            }
            while (end > start && blank(text.charAt(end - 1))) {
                end--;
            }
        }
        int i = start;
        if (i < end && (text.charAt(i) == '+' || text.charAt(i) == '-')) {
            i++;
        }
        if (i == end) {
            return BigInteger.ZERO;
        }
        for (int k = i; k < end; k++) {
            if (text.charAt(k) < '0' || text.charAt(k) > '9') {
                return null;
            }
        }
        String digits = text.substring(i, end);
        BigInteger v = new BigInteger(digits);
        return text.charAt(start) == '-' ? v.negate() : v;
    }

    private static boolean blank(char c) {
        return c == ' ' || c >= '\t' && c <= '\r';
    }

    /** CAST to NUMERIC: see {@link #cast}. */
    private Tx toNumeric(List<Integer> sizes, Tx x, SqlAst.Cast c, String what) {
        if (x.kind() == Kind.FLOAT) {
            throw SqlTranslationException.unsupported(what + " of a DOUBLE to NUMERIC is not supported yet: SQL Anywhere"
                    + " rounds its decimal digits (2.675e0 is 2.68 at two places), the IQ engine truncates (the"
                    + " research's D-N6)", c.pos());
        }
        // the source's own NUMERIC
        int sp;
        int ss;
        Tx value = x;
        boolean whole = false;
        if (isNull(x)) {
            sp = 1;
            ss = 0;
        } else if (x.kind() == Kind.STRING) {
            if (!(c.operand() instanceof SqlAst.StringLit s)) {
                throw SqlTranslationException.unsupported(what + " of a string that is not a constant to NUMERIC is not"
                        + " supported yet: IQ reads it by its own rules", c.operand().pos());
            }
            BigDecimal d = IqDialect.numericText(s.value());
            if (d == null) {
                throw SqlTranslationException.unsupported(what + " of the string '" + s.value() + "' to NUMERIC is not"
                        + " supported: IQ reads it as a NUMERIC by rules not read for this spelling yet", c.pos());
            }
            BigDecimal exact = IqTypes.atScale(d);
            if (exact.precision() > 38 || exact.scale() > 38) {
                throw SqlTranslationException.unsupported(what + " of a string of more than 38 digits is not"
                        + " supported", c.pos());
            }
            IqType t = IqTypes.ofNumericConstant(exact);
            value = Tx.value(new CDecimal(exact), Kind.DECIMAL, false, t);
            sp = t.size();
            ss = scaleOf(value, c.operand(), what)[1];
        } else if (decimal(x)) {
            int[] ps = scaleOf(x, c.operand(), what);
            sp = ps[0];
            ss = ps[1];
        } else if (x.kind() == Kind.INTEGER) {
            IqType t = integerType(x, c.operand(), what);
            sp = t.family().equals("BIGINT") ? 20 : 10;
            ss = 0;
            whole = true;
        } else {
            throw SqlTranslationException.unsupported(what + " of " + kindName(x.kind()) + " to NUMERIC is not supported"
                    + " yet", c.pos());
        }
        // without a precision: an integer's NUMERIC(10,0) or (20,0), a NUMERIC's own, a string's the profile's
        // PRECISION and SCALE (measured: CAST('1' AS DECIMAL) is a NUMERIC(126,38) in an IQ database)
        boolean fromString = x.kind() == Kind.STRING;
        int p = sizes.isEmpty() ? fromString ? profile.precision() : sp : sizes.get(0);
        int s = sizes.isEmpty() ? fromString ? profile.scale() : ss : sizes.size() > 1 ? sizes.get(1) : 0;
        if (p > 38) {
            throw SqlTranslationException.unsupported(what + " to a NUMERIC of more than 38 digits is not supported:"
                    + " lite's decimals hold 38", c.pos());
        }
        IqType type = new IqType("NUMERIC(" + p + "," + s + ")");
        if (isNull(x)) {
            return Tx.value(SqlToPure.nullOf(Kind.DECIMAL).vs(), Kind.DECIMAL, true, type);
        }
        int n = s;
        boolean round = n < ss;
        if (round && sp - ss + n > 18) {
            throw SqlTranslationException.unsupported(what + " of a NUMERIC(" + sp + "," + ss + ") to " + n + " places"
                    + " is not supported yet: its scaled value may exceed 18 digits", c.pos());
        }
        // can the value need more whole digits than p - s? (rounding up may add one)
        boolean check = sp - ss + (round ? 1 : 0) > p - s;
        if (check) {
            raisesHere(c, "-158 (out of range)");
        }
        boolean fromWhole = whole;
        return strict(Kind.DECIMAL, type, List.of(value), v -> {
            ValueSpecification d = fromWhole ? SqlToPure.call("toDecimal", v.get(0)) : v.get(0);
            ValueSpecification r = round ? places(d, n, true) : s == ss ? d : atScale(d, s);
            if (!check) {
                return r;
            }
            return ifThen(SqlToPure.call("greaterThanEqual", SqlToPure.call("abs", r), new CDecimal(pow10(p - s))),
                    raise("Decimal"), r);
        });
    }

    /** CAST to DOUBLE: see {@link #cast}. */
    private Tx toDouble(Tx x, SqlAst.Cast c, String what) {
        IqType type = new IqType("DOUBLE");
        if (isNull(x)) {
            return Tx.value(SqlToPure.nullOf(Kind.FLOAT).vs(), Kind.FLOAT, true, type);
        }
        if (x.kind() == Kind.STRING) {
            if (!(c.operand() instanceof SqlAst.StringLit s) || IqDialect.numericText(s.value()) == null) {
                throw SqlTranslationException.unsupported(what + " of a string that is not a constant number to DOUBLE"
                        + " is not supported yet: IQ reads it by its own rules", c.operand().pos());
            }
            BigDecimal d = IqTypes.atScale(java.util.Objects.requireNonNull(IqDialect.numericText(s.value())));
            if (d.precision() > 15 || d.scale() > 38) {
                throw SqlTranslationException.unsupported(what + " of a string of more than 15 significant digits to"
                        + " DOUBLE is not supported yet", c.operand().pos());
            }
            return Tx.value(SqlToPure.call("toFloat", new CDecimal(d)), Kind.FLOAT, false, type);
        }
        Tx d = asDouble(x, c.operand(), what);
        return Tx.value(d.vs(), Kind.FLOAT, d.nullable(), type);
    }

    /** CAST to a character type: see {@link #cast}. */
    private Tx toText(String target, List<Integer> sizes, Tx x, SqlAst.Cast c, String what, boolean convert) {
        int n = target.equals("LONG VARCHAR") || sizes.isEmpty() ? -1 : sizes.get(0);
        boolean pad = target.equals("CHAR") && n > 0 && engine == IqDialect.Engine.IQ;
        IqType type = new IqType(n < 0 ? (target.equals("LONG VARCHAR") ? "VARCHAR" : target) : target + "(" + n + ")");
        if (isNull(x)) {
            return Tx.value(SqlToPure.nullOf(Kind.STRING).vs(), Kind.STRING, true, type);
        }
        Tx text;
        switch (x.kind()) {
            case STRING -> {
                // a UTF-8 database's lengths are bytes (unless the type counts characters: CHAR(n CHAR))
                boolean bytes = !profile.latin1() && sizes.size() < 2;
                int longest = bytes && c.operand() instanceof SqlAst.StringLit lit
                        ? lit.value().getBytes(java.nio.charset.StandardCharsets.UTF_8).length : longest(x, c.operand());
                if (n < 0 || longest >= 0 && longest <= n) {
                    text = x;
                } else {
                    if (engine == IqDialect.Engine.IQ) {
                        throw SqlTranslationException.unsupported(what + " of a string that may be longer than " + n
                                + " characters to " + target + "(" + n + ") is not supported under the IQ engine yet:"
                                + " IQ's manual cuts it, SQL Anywhere raises -638 (the research's D-C2, not yet"
                                + " verified)", c.pos());
                    }
                    if (!profile.latin1()) {
                        throw SqlTranslationException.unsupported(what + " of a string that may be longer than " + n
                                + " to " + target + "(" + n + ") is not supported in profile " + profile.spelling()
                                + "'s UTF-8 database yet: its lengths are bytes", c.pos());
                    }
                    raisesHere(c, "-638 (right truncation)");
                    Tx r = SqlToPure.strict(Kind.STRING, List.of(x), v -> {
                        ValueSpecification s = v.get(0);
                        ValueSpecification rest = SqlToPure.call("substring", s, integer(n + 1));
                        return ifThen(SqlToPure.call("lessThanEqual", SqlToPure.call("length", s), integer(n)), s,
                                ifThen(SqlToPure.call("equal", SqlToPure.call("replace", rest, new CString(" "),
                                        new CString("")), new CString("")), SqlToPure.call("left", s, integer(n)),
                                        raise("String")));
                    });
                    text = r;
                }
            }
            case INTEGER -> {
                IqType t = integerType(x, c.operand(), what);
                int digits = Math.max(t.lo().toString().length(), t.hi().toString().length());
                if (n < 0 || digits <= n) {
                    text = SqlToPure.strict(Kind.STRING, List.of(x), v -> SqlToPure.call("toString", v.get(0)));
                } else {
                    raisesHere(c, "-157 (too long for its type)");
                    text = SqlToPure.strict(Kind.STRING, List.of(x), v -> ifThen(SqlToPure.call("greaterThan",
                            SqlToPure.call("length", SqlToPure.call("toString", v.get(0))), integer(n)),
                            raise("String"), SqlToPure.call("toString", v.get(0))));
                }
            }
            case DECIMAL -> {
                int[] ps = scaleOf(x, c.operand(), what);
                int longest = ps[0] + (ps[1] > 0 ? 2 : 1);
                if (n >= 0 && n < longest) {
                    throw SqlTranslationException.unsupported(what + " of a NUMERIC(" + ps[0] + "," + ps[1] + ") to "
                            + target + "(" + n + ") is not supported yet: SQL Anywhere cuts its text, the IQ engine"
                            + " raises (the research's D-C1)", c.pos());
                }
                // IQ's text: as the database writes it, but no 0 before the point of a value between -1 and 1
                text = SqlToPure.strict(Kind.STRING, List.of(x), v -> ifThen(SqlToPure.call("and", SqlToPure.call("and",
                        SqlToPure.call("greaterThan", v.get(0), integer(-1)), lt(v.get(0), integer(1))),
                        SqlToPure.call("not", SqlToPure.call("equal", v.get(0), integer(0)))),
                        SqlToPure.call("replace", SqlToPure.call("toString", v.get(0)), new CString("0."),
                                new CString(".")), SqlToPure.call("toString", v.get(0))));
            }
            case DATE -> {
                if (convert) {
                    throw SqlTranslationException.unsupported("CONVERT of a date to text without a style is not"
                            + " supported yet: " + (engine == IqDialect.Engine.IQ ? "under the IQ engine it writes the"
                            + " DATE_FORMAT option's form (the research's D-C6, not yet verified)" : "SQL Anywhere writes"
                            + " style 0 (Mar 04 2026 12:00AM), a format not read yet"), c.pos());
                }
                if (n >= 0 && n < 10) {
                    throw SqlTranslationException.unsupported(what + " of a DATE to " + target + "(" + n + ") is not"
                            + " supported yet: SQL Anywhere cuts its text, the IQ engine raises (the research's D-C1)",
                            c.pos());
                }
                // IQ island, 2026-10-14 (review 5's M4): in the profile's DATE_FORMAT, each part with its zeros (a year
                // below 1000 too), as || and STRING write it (IqDateTime, measured)
                text = IqDateTime.text(x, profile, what, c.pos());
            }
            // IQ island, 2026-10-14 (M1e): a timestamp in the profile's TIMESTAMP_FORMAT (IqDateTime, measured)
            case DATETIME -> {
                if (convert) {
                    throw SqlTranslationException.unsupported("CONVERT of a timestamp to text without a style is not"
                            + " supported yet: " + (engine == IqDialect.Engine.IQ ? "under the IQ engine it writes the"
                            + " TIMESTAMP_FORMAT option's form (the research's D-C6, not yet verified)" : "SQL Anywhere"
                            + " writes style 0 (Mar 04 2026 05:06AM), a format not read yet"), c.pos());
                }
                int length = IqDateTime.length(true, profile);
                if (n >= 0 && n < length) {
                    throw SqlTranslationException.unsupported(what + " of a TIMESTAMP to " + target + "(" + n + ") is not"
                            + " supported yet: SQL Anywhere cuts its text, the IQ engine raises (the research's D-C1)",
                            c.pos());
                }
                text = IqDateTime.text(x, profile, what, c.pos());
            }
            default -> throw SqlTranslationException.unsupported(what + " of " + kindName(x.kind()) + " to text is not"
                    + " supported yet: IQ writes it by its own format", c.pos());
        }
        if (pad) {
            Tx padded = SqlToPure.strict(Kind.STRING, List.of(text), v -> SqlToPure.call("rpad", v.get(0),
                    integer(n), new CString(" ")));
            return Tx.value(padded.vs(), Kind.STRING, padded.nullable(), type);
        }
        return Tx.value(text.vs(), Kind.STRING, text.nullable(), type);
    }

    /** The most characters a string value may have, by its type: a CHAR(n)'s or VARCHAR(n)'s n, a constant's length;
     *  -1 when not known. */
    private static int longest(Tx x, SqlAst.Expr e) {
        if (e instanceof SqlAst.StringLit s) {
            return s.value().length();
        }
        if (x.detail() instanceof IqType t && (t.family().equals("CHAR") || t.family().equals("VARCHAR"))) {
            return t.size();
        }
        return -1;
    }

    /** CAST to DATE or TIMESTAMP: see {@link #cast}. */
    private Tx toDate(boolean toDate, Tx x, SqlAst.Cast c, String what) {
        Kind kind = toDate ? Kind.DATE : Kind.DATETIME;
        // IQ island, 2026-10-14 (review 4's M2): a date's latest year carried through
        IqType type = dated(toDate ? "DATE" : "TIMESTAMP", x.kind() == Kind.DATE || x.kind() == Kind.DATETIME
                ? latestYear(x) : null);
        if (isNull(x)) {
            return Tx.value(SqlToPure.nullOf(kind).vs(), kind, true, type);
        }
        if (x.kind() == Kind.STRING && c.operand() instanceof SqlAst.StringLit s) {
            String[] parts = isoParts(s.value(), c.operand());
            if (parts == null) {
                if (s.value().isEmpty() || looksIso(s.value())) {
                    // '' and an ISO spelling of no day: IQ's error -157 where it is computed
                    raisesHere(c, "-157 (not a date)");
                    return Tx.value(SqlToPure.call("toOne", SqlToPure.call("cast", SqlToPure.empty(),
                            SqlToPure.typeArg(toDate ? "StrictDate" : "DateTime"))), kind, false, type);
                }
                throw SqlTranslationException.unsupported(what + " of the string '" + s.value() + "' to a date is not"
                        + " supported: it is no date spelling the island reads yet (IqDateTime)", c.pos());
            }
            type = dated(toDate ? "DATE" : "TIMESTAMP", Integer.parseInt(parts[0].substring(0, 4)));
            if (toDate) {
                return Tx.value(new CDate(PureDateLiteral.parse(parts[0]), parts[0], null), Kind.DATE, false, type);
            }
            String body = parts[0] + "T" + parts[1];
            return Tx.value(new CDate(PureDateLiteral.parse(body), body, null), Kind.DATETIME, false, type);
        }
        if (x.kind() == Kind.DATE) {
            if (toDate) {
                return Tx.value(x.vs(), Kind.DATE, x.nullable(), type);
            }
            return strict(Kind.DATETIME, type, List.of(x), v -> timestamp(v.get(0), true));
        }
        if (x.kind() == Kind.DATETIME) {
            if (!toDate) {
                return Tx.value(x.vs(), Kind.DATETIME, x.nullable(), type);
            }
            return strict(Kind.DATE, type, List.of(x), v -> typed(SqlToPure.call("datePart", v.get(0)),
                    "StrictDate"));
        }
        throw SqlTranslationException.unsupported(what + " of " + kindName(x.kind()) + " to a date is not supported"
                + " yet: IQ converts it by its own rules (an integer as yyyymmdd, a string by its date order)",
                c.pos());
    }

    /** A constant's date and time as IQ reads it ({@link IqDateTime#read}: a year below 100 refused by name; IQ
     *  island, 2026-10-14: one helper with M1a's comparisons, review 3's B3; M1e: by the profile's DATE_ORDER). */
    private String @com.legend.base.Nullable [] isoParts(String text, SqlAst.Expr e) {
        return IqDateTime.read(text, profile, e.pos());
    }

    /** Whether {@code s} is spelled yyyy-mm-dd... (digits and dashes where ISO has them), whatever its values. */
    private static boolean looksIso(String s) {
        if (s.length() < 10 || s.charAt(4) != '-' || s.charAt(7) != '-') {
            return false;
        }
        for (int i : new int[] {0, 1, 2, 3, 5, 6, 8, 9}) {
            if (s.charAt(i) < '0' || s.charAt(i) > '9') {
                return false;
            }
        }
        return s.length() == 10;
    }

    /** CAST of an integer to BIT in SQL Anywhere's front end: 0 or 1, anything else IQ's -158. Under the IQ engine any
     *  nonzero integer is 1 (the research's SEMANTICS §2.2, not yet verified): refused unless the value is 0 or 1. A
     *  number with a fraction is refused (SQL Anywhere raises -158 for -0.5). */
    private Tx toBit(Tx x, SqlAst.Cast c, String what) {
        IqType type = IqType.integer("BIT", BigInteger.ZERO, BigInteger.ONE, false);
        if (isNull(x)) {
            return Tx.value(SqlToPure.nullOf(Kind.INTEGER).vs(), Kind.INTEGER, true, type);
        }
        if (x.kind() != Kind.INTEGER) {
            // measured: a fraction is no BIT (-0.5 is -158), by rules not read yet
            throw SqlTranslationException.unsupported(what + " of " + kindName(x.kind()) + " to BIT is not supported"
                    + " yet", c.pos());
        }
        IqType t = integerType(x, c.operand(), what);
        if (t.lo().signum() >= 0 && t.hi().compareTo(BigInteger.ONE) <= 0) {
            return Tx.value(x.vs(), Kind.INTEGER, x.nullable(), type);
        }
        if (engine == IqDialect.Engine.IQ) {
            throw SqlTranslationException.unsupported(what + " to BIT of a value other than 0 or 1 is not supported"
                    + " under the IQ engine yet: IQ's manual makes any nonzero integer 1, SQL Anywhere raises -158",
                    c.pos());
        }
        raisesHere(c, "-158 (out of range)");
        Tx r = SqlToPure.strict(Kind.INTEGER, List.of(x), v -> ifThen(SqlToPure.call("or", lt(v.get(0), integer(0)),
                SqlToPure.call("greaterThan", v.get(0), integer(1))), raise("Integer"), v.get(0)));
        return Tx.value(r.vs(), Kind.INTEGER, r.nullable(), type);
    }

    // ------------------------------------------------------------------ arguments

    /** A string argument: a string as it is, an integer as its digits (and sign); a NULL. Any other
     *  value (IQ writes it as text by its own format) is refused by name. */
    private Tx text(Tx t, SqlAst.Expr e, String fn) {
        if (t.kind() == Kind.NULL || t.kind() == Kind.STRING) {
            return t;
        }
        if (t.kind() == Kind.INTEGER && t.detail() instanceof IqType type && (type.isInteger()
                || type.family().startsWith("UNSIGNED"))) {
            Tx digits = SqlToPure.strict(Kind.STRING, List.of(t), v -> SqlToPure.call("toString", v.get(0)));
            return Tx.value(digits.vs(), Kind.STRING, digits.nullable(), varchar());
        }
        // IQ island, 2026-10-14 (M1e): a date or a timestamp in the profile's DATE_FORMAT or TIMESTAMP_FORMAT
        if (t.kind() == Kind.DATE || t.kind() == Kind.DATETIME) {
            return IqDateTime.text(t, profile, fn, e.pos());
        }
        throw SqlTranslationException.unsupported(fn + " of " + kindName(t.kind()) + " is not supported yet: IQ writes"
                + " it as text by its own format", e.pos());
    }

    /**
     * A count or a position argument: an integer that lite's SQL computes in 32 bits (a SMALLINT, an INTEGER, a BIT, a
     * constant), or a NULL. A BIGINT, or an integer the translation computes in 64 bits, is refused by name: Postgres's
     * substr, left, right, repeat and chr take a 32-bit integer, and lite's lowering does not cast one to it yet. A
     * value IQ converts (a NUMERIC truncated, a string read) is refused too.
     */
    private Tx count(Tx t, SqlAst.Expr e, String fn) {
        if (t.kind() == Kind.NULL) {
            return t;
        }
        if (t.kind() == Kind.INTEGER && t.detail() instanceof IqType type && type.isInteger()) {
            if (type.family().equals("BIGINT") || type.wide() && !type.exact()) {
                throw SqlTranslationException.unsupported(fn + " with a count or position that is a BIGINT, or"
                        + " computed in 64 bits, is not supported yet: Postgres's string functions take a 32-bit"
                        + " integer, which lite's lowering does not cast to yet", e.pos());
            }
            return t;
        }
        throw SqlTranslationException.unsupported(fn + " with a count or position of " + kindName(t.kind()) + " is not"
                + " supported yet (IQ converts it to an integer by its own rules)", e.pos());
    }

    /** The IQ integer type of {@code t}: one of the island's integer types, or an unsigned result whose range is
     *  known; anything else refused by name. */
    private static IqType integerType(Tx t, SqlAst.Expr e, String fn) {
        if (t.kind() == Kind.INTEGER && t.detail() instanceof IqType type && (type.isInteger()
                || type.family().startsWith("UNSIGNED") && type.low() != null)) {
            return type;
        }
        throw SqlTranslationException.unsupported(fn + " of " + (t.kind() == Kind.INTEGER && t.detail() instanceof
                IqType type ? "a " + type.spelling() : kindName(t.kind())) + " is not supported yet", e.pos());
    }

    /** Checks a number argument: an integer, a NUMERIC, a DOUBLE; a string (IQ's NUMERIC conversion) refused. */
    private static void number(Tx t, SqlAst.Expr e, String fn) {
        if (t.kind() == Kind.FLOAT) {
            doubleOnly(t, e, fn);
        } else if (!decimal(t)) {
            integerType(t, e, fn);
        }
    }

    /** A DOUBLE, never a REAL (its table is refused, but a REAL could arrive otherwise). */
    private static void doubleOnly(Tx t, SqlAst.Expr e, String fn) {
        if (t.detail() instanceof IqType type && !type.family().equals("DOUBLE")) {
            throw SqlTranslationException.unsupported(fn + " of a " + type.spelling() + " is not supported yet",
                    e.pos());
        }
    }

    private static boolean decimal(Tx t) {
        if (t.detail() instanceof IqType type && (type.family().equals("NUMERIC") || type.family().equals("DECIMAL"))) {
            return true;
        }
        return t.kind() == Kind.DECIMAL || t.kind() == Kind.NUMBER;
    }

    /** A NUMERIC's precision and scale, by its IQ type; refused when not known. */
    private static int[] scaleOf(Tx t, SqlAst.Expr e, String fn) {
        if (t.detail() instanceof IqType type && (type.family().equals("NUMERIC") || type.family().equals("DECIMAL"))
                && type.size() >= 0) {
            String sp = type.spelling();
            int comma = sp.indexOf(',');
            int scale = comma < 0 ? 0 : Integer.parseInt(sp.substring(comma + 1, sp.indexOf(')')).strip());
            return new int[] {type.size(), scale};
        }
        throw SqlTranslationException.unsupported(fn + " of a NUMERIC whose precision and scale are not known is not"
                + " supported", e.pos());
    }

    /** A number argument as a DOUBLE: an integer converted, a NUMERIC of at most 15 digits converted (exact in every
     *  database, as SQL Anywhere's conversion is), a DOUBLE itself, a NULL. */
    private Tx asDouble(Tx x, SqlAst.Expr e, String fn) {
        if (isNull(x)) {
            return Tx.value(SqlToPure.nullOf(Kind.FLOAT).vs(), Kind.FLOAT, true, new IqType("DOUBLE"));
        }
        if (x.kind() == Kind.FLOAT) {
            doubleOnly(x, e, fn);
            return x;
        }
        if (decimal(x)) {
            int[] ps = scaleOf(x, e, fn);
            if (ps[0] > 15) {
                throw SqlTranslationException.unsupported(fn + " of a NUMERIC of more than 15 digits is not supported"
                        + " yet: its DOUBLE may round otherwise in lite's databases", e.pos());
            }
        } else {
            integerType(x, e, fn);
        }
        Tx r = SqlToPure.strict(Kind.FLOAT, List.of(x), v -> SqlToPure.call("toFloat", v.get(0)));
        return Tx.value(r.vs(), Kind.FLOAT, r.nullable(), new IqType("DOUBLE"));
    }

    /** An integer constant's value, signs applied; null for any other expression. */
    private static @com.legend.base.Nullable BigInteger signedInteger(SqlAst.Expr e) {
        return switch (e) {
            case SqlAst.IntegerLit n -> n.value();
            case SqlAst.Unary u when u.op() == SqlAst.UnaryOp.MINUS -> {
                BigInteger v = signedInteger(u.operand());
                yield v == null ? null : v.negate();
            }
            case SqlAst.Unary u when u.op() == SqlAst.UnaryOp.PLUS -> signedInteger(u.operand());
            default -> null;
        };
    }

    /** Whether {@code e} may read a column (every form but constants, signs, arithmetic and calls of them). */
    private static boolean readsColumn(SqlAst.Expr e) {
        return switch (e) {
            case SqlAst.IntegerLit n -> false;
            case SqlAst.NumericLit n -> false;
            case SqlAst.StringLit n -> false;
            case SqlAst.NullLit n -> false;
            case SqlAst.Unary u -> readsColumn(u.operand());
            case SqlAst.Binary b -> readsColumn(b.left()) || readsColumn(b.right());
            case SqlAst.Cast c -> readsColumn(c.operand());
            default -> true;
        };
    }

    /** Whether {@code e} is a number constant: a literal, signed or not. */
    private static boolean constantNumber(SqlAst.Expr e) {
        return switch (e) {
            case SqlAst.IntegerLit n -> true;
            case SqlAst.NumericLit n -> true;
            case SqlAst.Unary u -> u.op() != SqlAst.UnaryOp.NOT && constantNumber(u.operand());
            default -> false;
        };
    }

    /** {@code node} can raise IQ's error {@code error}: refused where it stands in the select list or ORDER BY of a
     *  statement with a row limit (IQ computes them only for the rows it keeps; lite's databases may compute them for
     *  the rows the limit drops). */
    private void raisesHere(SqlAst.Expr node, String error) {
        if (dialect.limited(node)) {
            throw SqlTranslationException.unsupported("this can raise IQ's error " + error + " in the select list or"
                    + " ORDER BY of a statement with TOP, FIRST or START AT, which is not supported yet: IQ computes"
                    + " those items only for the rows it keeps, lite's databases may compute them for the rows the"
                    + " limit drops", node.pos());
        }
    }

    // ------------------------------------------------------------------ Pure

    /** A strict function (NULL when any argument is), its string arguments' integers written as their digits. */
    private static Tx strict(Kind kind, IqType type, List<Tx> args, Function<List<ValueSpecification>,
            ValueSpecification> build) {
        Tx r = SqlToPure.strict(kind, args, v -> {
            List<ValueSpecification> values = new ArrayList<>(v);
            return build.apply(values);
        });
        return Tx.value(r.vs(), kind, r.nullable(), type);
    }

    private static IqType nonNegative() {
        return IqType.integer("INTEGER", BigInteger.ZERO, IqType.INTEGER_MAX, false);
    }

    private static IqType varchar() {
        return new IqType("VARCHAR");
    }

    private static Tx nullString() {
        return Tx.value(SqlToPure.nullOf(Kind.STRING).vs(), Kind.STRING, true, varchar());
    }

    private static CInteger integer(long v) {
        return new CInteger(v);
    }

    private static ValueSpecification lt(ValueSpecification a, ValueSpecification b) {
        return SqlToPure.call("lessThan", a, b);
    }

    private static ValueSpecification ifThen(ValueSpecification test, ValueSpecification then,
            ValueSpecification otherwise) {
        return SqlToPure.call("if", test, SqlToPure.thunk(then), SqlToPure.thunk(otherwise));
    }

    /** {@code x} (a Date) as the date type {@code type} it is (Pure's datePart and adjust are of a Date). */
    private static ValueSpecification typed(ValueSpecification x, String type) {
        return SqlToPure.call("cast", x, SqlToPure.typeArg(type));
    }

    /** A value of Pure type {@code type} that raises when computed: an empty value read as one. */
    private static ValueSpecification raise(String type) {
        return SqlToPure.call("toOne", SqlToPure.call("cast", SqlToPure.empty(), SqlToPure.typeArg(type)));
    }

    private static String code(int sqlcode, String sqlstate) {
        return " (IQ: SQLCODE " + sqlcode + ", SQLSTATE " + sqlstate + ")";
    }

    private static String kindName(Kind k) {
        return switch (k) {
            case BOOLEAN -> "a condition";
            case STRING -> "a string";
            case DATE -> "a date";
            case DATETIME, ZONED -> "a timestamp";
            case TIME -> "a time";
            case DURATION -> "a duration";
            case NULL -> "NULL";
            case INTEGER, WHOLE, FLOAT, DECIMAL, NUMBER, INEXACT -> "a number";
            case JSON, OTHER -> "a value of this type";
        };
    }
}
