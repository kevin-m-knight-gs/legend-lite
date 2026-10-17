// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.protocol.sql.iq;

import com.legend.protocol.sql.SqlAst;
import com.legend.protocol.sql.SqlSyntaxException;
import com.legend.protocol.sql.SqlTranslationException;

import java.util.List;
import java.util.Map;

/**
 * IQ's SET statements before an {@code #IQ{...}#} island's SELECT (docs/IQ_SQL_STUDY_2026_10_13.md §9.17; IQ island,
 * 2026-10-14, the options framework): a run of them, each ended by {@code ;}, then exactly one SELECT, so that the SET
 * lines of an IQ script read as written. Each statement is read in turn, under the settings the ones before it made (a
 * {@code quoted_identifier} or {@code escape_character} it sets changes how the next is lexed, as in IQ), and changes
 * the settings ({@link IqSettings#set}); the SELECT is read under the last. Measured on SQL Anywhere 17:
 * <ul>
 *   <li>{@code SET [EXISTING] [TEMPORARY] OPTION [PUBLIC.]name = [value]}: the name plain, {@code "quoted"} or
 *       {@code [bracketed]}; the value a word ({@code On}, {@code DMY}), a number, a string, {@code NULL} or nothing
 *       (both reset); a sign, a parenthesis or a second value is IQ's syntax error -131; a variable is refused by
 *       name, and so is an owner other than {@code PUBLIC};</li>
 *   <li>T-SQL's forms SQL Anywhere reads as options: {@code SET ANSINULL ON|OFF} (and {@code ANSI_NULLS}),
 *       {@code SET QUOTED_IDENTIFIER ON|OFF}, {@code SET STRING_RTRUNCATION ON|OFF}, {@code SET DATEFIRST n} (1-7:
 *       {@code first_day_of_week}; else -201), {@code SET DATEFORMAT ymd|mdy|dmy} ({@code date_order}; another word
 *       -201), {@code SET ROWCOUNT n} ({@code row_count}, a temporary value each; n an integer up to 2147483647); and
 *       the ones that change no SELECT, accepted and ignored: {@code SET CHAINED}, {@code ANSI_PERMISSIONS},
 *       {@code SELF_RECURSION}, {@code PREFETCH}, {@code NOCOUNT}, {@code ARITHABORT}, {@code ARITHIGNORE} (each
 *       {@code ON|OFF}), {@code SET TEXTSIZE n}, {@code SET TRANSACTION ISOLATION LEVEL ...}, {@code SET STATISTICS IO
 *       ON}, {@code SET SHOWPLAN ON};</li>
 *   <li>a T-SQL form SQL Anywhere 17 does not read ({@code SET CLOSE_ON_ENDTRANS}, {@code SET ESCAPE_CHARACTER},
 *       {@code SET DATE_ORDER}, SQL Server's {@code SET XACT_ABORT} and the like) is its syntax error -131,
 *       {@code SET LANGUAGE} its -611; any other SET (a variable's) is refused by name.</li>
 * </ul>
 * The SELECT's text is the island's with the statements before it blanked (their line breaks kept), so every position
 * in it is the island's own.
 */
public final class IqSetPrefix {

    private IqSetPrefix() {
    }

    /** The SET statements' settings and the island's text with them blanked: the SELECT's. */
    public record Prefix(IqSettings settings, String select) {
    }

    /** The T-SQL forms of no effect on a SELECT, each {@code ON} or {@code OFF} (measured accepted). */
    private static final java.util.Set<String> SWITCHES = java.util.Set.of("chained", "ansi_permissions",
            "self_recursion", "prefetch", "nocount", "arithabort", "arithignore");

    /** The T-SQL forms SQL Anywhere 17 does not read: its syntax error (measured). */
    private static final java.util.Set<String> NOT_READ = java.util.Set.of("close_on_endtrans", "escape_character",
            "date_order", "fmtonly", "xact_abort", "lock_timeout", "concat_null_yields_null", "ansi_warnings",
            "ansi_padding", "implicit_transactions", "deadlock_priority", "forceplan");

    /** The T-SQL forms read by their word, each its own reading ({@link #tsql}). */
    private enum Form { ROWCOUNT, TEXTSIZE, DATEFIRST, DATEFORMAT, TRANSACTION, STATISTICS, SHOWPLAN, LANGUAGE }

    private static final Map<String, Form> FORMS = Map.of("rowcount", Form.ROWCOUNT, "textsize", Form.TEXTSIZE,
            "datefirst", Form.DATEFIRST, "dateformat", Form.DATEFORMAT, "transaction", Form.TRANSACTION, "statistics",
            Form.STATISTICS, "showplan", Form.SHOWPLAN, "language", Form.LANGUAGE);

    /** The T-SQL forms that set a Tier 1 option, {@code ON} or {@code OFF}. */
    private static final Map<String, IqOption> TSQL_OPTIONS = Map.of("ansinull", IqOption.ANSINULL, "ansi_nulls",
            IqOption.ANSINULL, "quoted_identifier", IqOption.QUOTED_IDENTIFIER, "string_rtruncation",
            IqOption.STRING_RTRUNCATION);

    /**
     * The SET statements {@code sql} begins with, read under {@code settings}: null when it begins with none (then
     * nothing is read here, and the text is the SELECT's as it is).
     */
    public static @com.legend.base.Nullable Prefix read(String sql, IqSettings settings) {
        if (!startsWithSet(sql, 0)) {
            return null;
        }
        IqSettings s = settings;
        int at = 0;
        String masked = sql;
        while (startsWithSet(masked, at)) {
            int end = statementEnd(sql, at);
            if (end < 0) {
                List<IqLexer.Token> tokens = IqLexer.tokenize(masked, s);
                throw SqlSyntaxException.unsupported("a SET statement before an #IQ{...}# island's SELECT ends with"
                        + " ';'", tokens.get(0).pos());
            }
            List<IqLexer.Token> tokens = IqLexer.tokenize(masked.substring(0, end + 1), s);
            s = statement(tokens, s);
            at = end + 1;
            masked = blank(sql, at);
        }
        return new Prefix(s, masked);
    }

    /** {@code sql} with its characters before {@code at} blanked, their line breaks kept. */
    private static String blank(String sql, int at) {
        StringBuilder b = new StringBuilder(sql.length());
        for (int i = 0; i < at; i++) {
            char c = sql.charAt(i);
            b.append(c == '\n' || c == '\r' ? c : ' ');
        }
        return b.append(sql, at, sql.length()).toString();
    }

    /** Whether the text from {@code from}, past blanks and comments, begins with the word SET. */
    private static boolean startsWithSet(String sql, int from) {
        int i = skip(sql, from);
        return i + 3 <= sql.length() && IqLexer.lower(sql.substring(i, i + 3)).equals("set")
                && (i + 3 == sql.length() || !namePart(sql.charAt(i + 3)));
    }

    /** A character that continues a plain name (IQ's lexer's, for ASCII; any letter past it). */
    private static boolean namePart(char c) {
        return c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '_' || c == '@' || c == '#'
                || c == '$' || c > 0x7F && Character.isLetter(c);
    }

    /** Past the blanks and comments from {@code i} ({@code --} and {@code //} to the line's end, {@code /* *}{@code /}
     *  nested). */
    private static int skip(String sql, int i) {
        int n = sql.length();
        while (i < n) {
            char c = sql.charAt(i);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f' || c == '\u000B') {
                i++;
            } else if (sql.startsWith("--", i) || sql.startsWith("//", i)) {
                while (i < n && sql.charAt(i) != '\n' && sql.charAt(i) != '\r') {
                    i++;
                }
            } else if (sql.startsWith("/*", i)) {
                int depth = 1;
                i += 2;
                while (i < n && depth > 0) {
                    if (sql.startsWith("/*", i)) {
                        depth++;
                        i += 2;
                    } else if (sql.startsWith("*/", i)) {
                        depth--;
                        i += 2;
                    } else {
                        i++;
                    }
                }
            } else {
                return i;
            }
        }
        return i;
    }

    /** The index of the {@code ;} that ends the statement starting at {@code from} (outside strings, delimited
     *  identifiers and comments); -1 for none. */
    private static int statementEnd(String sql, int from) {
        int n = sql.length();
        int i = from;
        while (i < n) {
            int j = skip(sql, i);
            if (j != i) {
                i = j;
                continue;
            }
            char c = sql.charAt(i);
            if (c == '\'' || c == '"' || c == '`' || c == '[') {
                char close = c == '[' ? ']' : c;
                i++;
                while (i < n) {
                    if (sql.charAt(i) == close) {
                        if ((close == '\'' || close == '"') && i + 1 < n && sql.charAt(i + 1) == close) {
                            i += 2;
                            continue;
                        }
                        break;
                    }
                    i++;
                }
                i++;
                continue;
            }
            if (c == ';') {
                return i;
            }
            i++;
        }
        return -1;
    }

    /** One SET statement, its tokens up to its {@code ;} and EOF: the settings after it. */
    private static IqSettings statement(List<IqLexer.Token> tokens, IqSettings s) {
        Cursor t = new Cursor(tokens);
        IqLexer.Token set = t.take();
        IqLexer.Token first = t.peek();
        boolean temporary = false;
        boolean option = false;
        boolean existing = false;
        if (first.is("existing") || first.is("temporary") || first.is("option")) {
            if (t.peek().is("existing")) {
                t.take();
                existing = true;
            }
            if (t.peek().is("temporary")) {
                t.take();
                temporary = true;
            }
            t.expectKeyword("option");
            option = true;
        }
        if (option) {
            return setOption(t, s, temporary, existing);
        }
        return tsql(t, s, set);
    }

    /** {@code SET [EXISTING] [TEMPORARY] OPTION [owner.]name = [value]}, after OPTION; {@code existing} for
     *  EXISTING. */
    private static IqSettings setOption(Cursor t, IqSettings s, boolean temporary, boolean existing) {
        IqLexer.Token first = t.name();
        IqLexer.Token name = first;
        boolean publicOwner = false;
        if (t.peek().isOp(".")) {
            t.take();
            if (!(first.kind() == IqLexer.Kind.WORD && first.text().equalsIgnoreCase("public"))) {
                throw SqlSyntaxException.unsupported("an option set for the user " + first.text() + " is not"
                        + " supported: an #IQ{...}# island sets the connection's and PUBLIC's", first.pos());
            }
            publicOwner = true;
            name = t.name();
        }
        if (!t.peek().isOp("=")) {
            throw t.syntaxError();
        }
        t.take();
        IqLexer.Token v = t.peek();
        IqSettings.Value value;
        if (v.isOp(";")) {
            value = new IqSettings.Value(IqSettings.Value.Form.NONE, "");
        } else {
            t.take();
            value = switch (v.kind()) {
                case STRING -> new IqSettings.Value(IqSettings.Value.Form.STRING, v.text());
                case NUMBER -> {
                    // a number beyond a DOUBLE's range is IQ's syntax error (-131, IQ 17.1 and SQL Anywhere 17)
                    if (Double.isInfinite(Double.parseDouble(v.text()))) {
                        throw t.syntaxErrorAt(v);
                    }
                    yield new IqSettings.Value(IqSettings.Value.Form.NUMBER, v.text());
                }
                case KEYWORD -> new IqSettings.Value(v.is("null") ? IqSettings.Value.Form.NULL
                        : IqSettings.Value.Form.WORD, v.text());
                case QUOTED -> new IqSettings.Value(IqSettings.Value.Form.WORD, v.text());
                case WORD -> {
                    if (v.text().startsWith("@")) {
                        throw SqlSyntaxException.unsupported("a variable as an option's value is not supported",
                                v.pos());
                    }
                    yield new IqSettings.Value(IqSettings.Value.Form.WORD, v.text());
                }
                default -> throw t.syntaxErrorAt(v);
            };
        }
        t.end();
        IqSettings.Scope scope = publicOwner
                ? temporary ? IqSettings.Scope.PUBLIC_TEMPORARY : IqSettings.Scope.PUBLIC
                : temporary ? IqSettings.Scope.TEMPORARY : IqSettings.Scope.USER;
        return s.set(scope, name.text(), value, name.pos(), existing);
    }

    /** A T-SQL SET statement, after SET. */
    private static IqSettings tsql(Cursor t, IqSettings s, IqLexer.Token set) {
        IqLexer.Token word = t.peek();
        if (word.kind() == IqLexer.Kind.EOF || word.isOp(";")) {
            throw t.syntaxError();
        }
        String w = IqLexer.lower(word.text());
        if (word.kind() != IqLexer.Kind.WORD && word.kind() != IqLexer.Kind.KEYWORD) {
            throw t.syntaxErrorAt(word);
        }
        t.take();
        IqOption option = TSQL_OPTIONS.get(w);
        if (option != null || SWITCHES.contains(w)) {
            IqLexer.Token v = t.take();
            if (!v.is("on") && !v.is("off")) {
                throw t.syntaxErrorAt(v);
            }
            t.end();
            String change = "SET " + word.text() + " " + v.text();
            return option == null ? s.note(change) : s.setTemporary(option, v.is("on") ? "On" : "Off", change);
        }
        Form form = FORMS.get(w);
        if (form == null) {
            if (NOT_READ.contains(w)) {
                throw t.syntaxErrorAt(word);
            }
            throw SqlSyntaxException.unsupported("the statement SET " + word.text() + " is not supported: an"
                    + " #IQ{...}# island reads SET OPTION and T-SQL's SET statements that set options before its"
                    + " SELECT", set.pos());
        }
        switch (form) {
            case ROWCOUNT -> {
                IqLexer.Token n = t.take();
                long count = count(n, t);
                t.end();
                return s.setTemporary(IqOption.ROW_COUNT, Long.toString(count), "SET ROWCOUNT " + n.text());
            }
            case TEXTSIZE -> {
                count(t.take(), t);
                t.end();
                return s.note("SET TEXTSIZE");
            }
            case DATEFIRST -> {
                IqLexer.Token n = t.take();
                long day = count(n, t);
                t.end();
                if (day < 1 || day > 7) {
                    throw IqSettings.invalidSetting("DATEFIRST", n.text(), n.pos());
                }
                return s.setTemporary(IqOption.FIRST_DAY_OF_WEEK, Long.toString(day), "SET DATEFIRST " + n.text());
            }
            case DATEFORMAT -> {
                IqLexer.Token f = t.take();
                if (f.kind() != IqLexer.Kind.WORD) {
                    throw t.syntaxErrorAt(f);
                }
                t.end();
                String order = IqLexer.lower(f.text()).toUpperCase(java.util.Locale.ROOT);
                if (!order.equals("YMD") && !order.equals("MDY") && !order.equals("DMY")) {
                    throw IqSettings.invalidSetting("DATEFORMAT", f.text(), f.pos());
                }
                return s.setTemporary(IqOption.DATE_ORDER, order, "SET DATEFORMAT " + f.text());
            }
            case TRANSACTION -> {
                if (!t.take().isWord("isolation") || !t.take().isWord("level")) {
                    throw t.syntaxError();
                }
                while (!t.peek().isOp(";")) {
                    IqLexer.Token x = t.take();
                    if (x.kind() != IqLexer.Kind.WORD && x.kind() != IqLexer.Kind.KEYWORD
                            && x.kind() != IqLexer.Kind.NUMBER) {
                        throw t.syntaxErrorAt(x);
                    }
                }
                t.end();
                return s.note("SET TRANSACTION ISOLATION LEVEL");
            }
            case STATISTICS, SHOWPLAN -> {
                if (form == Form.STATISTICS && !t.take().isWord("io")) {
                    throw SqlSyntaxException.unsupported("this SET STATISTICS statement is not supported",
                            word.pos());
                }
                if (!t.take().is("on")) {
                    throw SqlSyntaxException.unsupported("this SET " + word.text() + " statement is not supported",
                            word.pos());
                }
                t.end();
                return s.note("SET " + word.text().toUpperCase(java.util.Locale.ROOT));
            }
            case LANGUAGE -> throw SqlTranslationException.invalid("SET LANGUAGE is a Transact-SQL feature IQ does"
                    + " not support" + IqDialect.code(-611, "0AW02"), word.pos());
        }
        throw new IllegalStateException("no T-SQL form " + form);
    }

    /** A count: an integer literal up to 2147483647 (else IQ's syntax error). */
    private static long count(IqLexer.Token n, Cursor t) {
        if (n.kind() != IqLexer.Kind.NUMBER || !digits(n.text()) || n.text().length() > 10
                || Long.parseLong(n.text()) > Integer.MAX_VALUE) {
            throw t.syntaxErrorAt(n);
        }
        return Long.parseLong(n.text());
    }

    private static boolean digits(String s) {
        if (s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) < '0' || s.charAt(i) > '9') {
                return false;
            }
        }
        return true;
    }

    /** The tokens of one statement, read in turn: the ones left, ending with EOF, which is never taken. */
    private static final class Cursor {
        private final java.util.ArrayDeque<IqLexer.Token> left;

        Cursor(List<IqLexer.Token> tokens) {
            this.left = new java.util.ArrayDeque<>(tokens);
        }

        IqLexer.Token peek() {
            return java.util.Objects.requireNonNull(left.peekFirst());
        }

        IqLexer.Token take() {
            IqLexer.Token t = peek();
            if (t.kind() != IqLexer.Kind.EOF) {
                left.pollFirst();
            }
            return t;
        }

        void expectKeyword(String word) {
            IqLexer.Token t = take();
            if (!t.is(word)) {
                throw syntaxErrorAt(t);
            }
        }

        /** A name: plain, quoted or a reserved word (an option may be named like one: {@code precision}). */
        IqLexer.Token name() {
            IqLexer.Token t = take();
            if (t.kind() != IqLexer.Kind.WORD && t.kind() != IqLexer.Kind.QUOTED && t.kind() != IqLexer.Kind.KEYWORD) {
                throw syntaxErrorAt(t);
            }
            return t;
        }

        /** The statement's {@code ;}, then nothing. */
        void end() {
            IqLexer.Token t = take();
            if (!t.isOp(";")) {
                throw syntaxErrorAt(t);
            }
        }

        SqlSyntaxException syntaxError() {
            return syntaxErrorAt(peek());
        }

        SqlSyntaxException syntaxErrorAt(IqLexer.Token t) {
            return new SqlSyntaxException("syntax error near " + (t.kind() == IqLexer.Kind.EOF ? "the end"
                    : "'" + t.text() + "'") + " in a SET statement" + IqDialect.syntaxCode(), t.pos());
        }
    }
}
