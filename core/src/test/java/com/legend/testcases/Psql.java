// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.testcases;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A psql script run on a JDBC connection as psql runs it (the corpus program's first corpus, 2026-10-17: the
 * PostgreSQL regression suite's {@code sql/*.sql} files). {@link #parse} reads a script into its statements -- each
 * with how it ends ({@code ;}, {@code \g}, {@code \gset}, {@code \gexec}) and the {@code COPY ... FROM stdin} data that
 * follows it -- and its meta-commands, in order; {@link Runner} runs them: each statement with psql's variables
 * interpolated, the meta-commands the suite's scripts depend on ({@code \set}, <code>&#92;unset</code>, {@code \getenv},
 * {@code \gset}, {@code \gexec}, {@code \if}/{@code \elif}/{@code \else}/{@code \endif}, {@code \c}, {@code \q}), and
 * the rest, which only shape psql's display ({@code \d}, {@code \pset}, {@code \x}, {@code \echo}), not at all.
 * Statements end where psql's lexer ends them: a semicolon outside quotes, comments and parentheses, and outside a
 * {@code BEGIN ATOMIC} body.
 */
public final class Psql {

    private Psql() {
    }

    /** A statement or a meta-command, at its line. */
    public sealed interface Item permits Sql, Meta {
        int line();
    }

    /** A statement: its text, the command that ends it ({@code ";"}, {@code "g"}, {@code "gset"}, {@code "gexec"},
     *  {@code "crosstabview"}) with that command's arguments, and the data a {@code COPY ... FROM stdin} reads. */
    public record Sql(String text, String end, String endArgs, @com.legend.base.Nullable String copyData, int line)
            implements Item {
    }

    /** A meta-command: its name (without the backslash) and its arguments as written. */
    public record Meta(String command, String args, int line) implements Item {
    }

    private static final Pattern COPY_FROM_STDIN = Pattern.compile("(?is)^\\s*copy\\b.*\\bfrom\\s+stdin\\b.*");

    /** The meta-commands that send the query buffer, as {@code ;} does. */
    private static final java.util.Set<String> SENDS = java.util.Set.of("g", "gx", "gset", "gexec", "crosstabview",
            "gdesc");

    /** {@code script}'s statements and meta-commands, in order. */
    public static List<Item> parse(String script) {
        return new Reader(script).items();
    }

    /** psql's lexer over one script. */
    private static final class Reader {
        private final String s;
        private final List<Item> items = new ArrayList<>();
        private final StringBuilder buf = new StringBuilder();
        private int i;
        private int line = 1;
        private int bufLine = 1;
        private int parens;
        private int atomic;
        private String lastWord = "";

        Reader(String s) {
            this.s = s;
        }

        List<Item> items() {
            while (i < s.length()) {
                step();
            }
            if (!buf.toString().isBlank()) {
                items.add(new Sql(buf.toString().strip(), ";", "", null, bufLine));
            }
            return items;
        }

        private void step() {
            char c = s.charAt(i);
            if (buf.toString().isBlank()) {
                bufLine = line;
            }
            if (c == '-' && s.startsWith("--", i)) {
                skipTo('\n');
            } else if (c == '/' && s.startsWith("/*", i)) {
                blockComment();
            } else if (c == '\'' || c == '"') {
                quoted(c, c == '\'' && escapeString());
            } else if (c == '$' && dollarQuote()) {
                return;
            } else if (c == '\\') {
                meta();
            } else if (c == '(') {
                parens++;
                take(c);
            } else if (c == ')') {
                parens = Math.max(0, parens - 1);
                take(c);
            } else if (c == ';' && parens == 0 && atomic == 0) {
                i++;
                send(";", "");
            } else if (Character.isLetter(c) || c == '_') {
                word();
            } else {
                take(c);
            }
        }

        private void take(char c) {
            buf.append(c);
            if (c == '\n') {
                line++;
            }
            i++;
        }

        private void skipTo(char end) {
            while (i < s.length() && s.charAt(i) != end) {
                i++;
            }
        }

        private void blockComment() {
            int depth = 0;
            do {
                if (s.startsWith("/*", i)) {
                    depth++;
                    i += 2;
                } else if (s.startsWith("*/", i)) {
                    depth--;
                    i += 2;
                } else {
                    if (s.charAt(i) == '\n') {
                        line++;
                    }
                    i++;
                }
            } while (depth > 0 && i < s.length());
            buf.append(' ');
        }

        /** An {@code E'...'} string, whose backslashes escape. */
        private boolean escapeString() {
            return i > 0 && (s.charAt(i - 1) == 'E' || s.charAt(i - 1) == 'e')
                    && (i < 2 || !Character.isLetterOrDigit(s.charAt(i - 2)) && s.charAt(i - 2) != '_');
        }

        private void quoted(char q, boolean backslashes) {
            take(q);
            while (i < s.length()) {
                char c = s.charAt(i);
                if (backslashes && c == '\\' && i + 1 < s.length()) {
                    take(c);
                    take(s.charAt(i));
                } else if (c == q && i + 1 < s.length() && s.charAt(i + 1) == q) {
                    take(c);
                    take(q);
                } else if (c == q) {
                    take(c);
                    return;
                } else {
                    take(c);
                }
            }
        }

        private boolean dollarQuote() {
            Matcher m = Pattern.compile("\\$([A-Za-z_][A-Za-z_0-9]*)?\\$").matcher(s).region(i, s.length());
            if (!m.lookingAt() || i > 0 && (Character.isLetterOrDigit(s.charAt(i - 1)) || s.charAt(i - 1) == '_')) {
                return false;
            }
            String tag = m.group();
            int end = s.indexOf(tag, i + tag.length());
            int stop = end < 0 ? s.length() : end + tag.length();
            while (i < stop) {
                take(s.charAt(i));
            }
            return true;
        }

        private void word() {
            int start = i;
            while (i < s.length() && (Character.isLetterOrDigit(s.charAt(i)) || s.charAt(i) == '_'
                    || s.charAt(i) == '$')) {
                i++;
            }
            String w = s.substring(start, i).toLowerCase(Locale.ROOT);
            buf.append(s, start, i);
            if (w.equals("atomic") && lastWord.equals("begin") || atomic > 0 && w.equals("case")) {
                atomic++;
            } else if (atomic > 0 && w.equals("end")) {
                atomic--;
            }
            lastWord = w;
        }

        /** A meta-command: its name, then its arguments to the end of the line. */
        private void meta() {
            int start = ++i;
            while (i < s.length() && (Character.isLetter(s.charAt(i)) || s.charAt(i) == '?' || s.charAt(i) == '!')) {
                i++;
            }
            if (i == start && i < s.length()) {
                i++; // \. or another one-character command
            }
            String command = s.substring(start, i);
            int eol = s.indexOf('\n', i);
            String args = (eol < 0 ? s.substring(i) : s.substring(i, eol)).strip();
            i = eol < 0 ? s.length() : eol;
            if (SENDS.contains(command)) {
                send(command, args);
            } else {
                items.add(new Meta(command, args, line));
            }
        }

        /** The buffer as a statement, ended by {@code end}; a {@code COPY ... FROM stdin}'s data follows it. */
        private void send(String end, String args) {
            String text = buf.toString().strip();
            buf.setLength(0);
            parens = 0;
            atomic = 0;
            if (text.isEmpty() && end.equals(";")) {
                return;
            }
            String data = null;
            if (COPY_FROM_STDIN.matcher(text).matches()) {
                data = copyData();
            }
            items.add(new Sql(text, end, args, data, bufLine));
        }

        /** The lines after this one, to a line that is {@code \.}. */
        private String copyData() {
            int eol = s.indexOf('\n', i);
            if (eol < 0) {
                i = s.length();
                return "";
            }
            int from = eol + 1;
            line++;
            StringBuilder data = new StringBuilder();
            int at = from;
            while (at < s.length()) {
                int next = s.indexOf('\n', at);
                String l = next < 0 ? s.substring(at) : s.substring(at, next);
                at = next < 0 ? s.length() : next + 1;
                line++;
                if (l.equals("\\.")) {
                    break;
                }
                data.append(l).append('\n');
            }
            i = at;
            return data.toString();
        }
    }

    // ------------------------------------------------------------------ variables

    /** {@code text} with psql's variables interpolated outside quotes: {@code :name}, {@code :'name'} (a literal),
     *  {@code :"name"} (an identifier) and {@code :{?name}} ({@code TRUE} when set); an unset name is left as written,
     *  as psql leaves it, and {@code ::} is a cast. */
    public static String interpolate(String text, Map<String, String> vars) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '\'' || c == '"') {
                int end = closing(text, i);
                out.append(text, i, end);
                i = end;
            } else if (c == ':' && i + 1 < text.length() && text.charAt(i + 1) == ':') {
                out.append("::");
                i += 2;
            } else if (c == ':' && i + 1 < text.length()) {
                i = variable(text, i, vars, out);
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    private static int closing(String text, int i) {
        char q = text.charAt(i);
        int j = i + 1;
        while (j < text.length()) {
            if (text.charAt(j) == q && j + 1 < text.length() && text.charAt(j + 1) == q) {
                j += 2;
            } else if (text.charAt(j) == q) {
                return j + 1;
            } else {
                j++;
            }
        }
        return text.length();
    }

    private static final Pattern VAR = Pattern.compile(":(?:'([A-Za-z_][A-Za-z0-9_]*)'|\"([A-Za-z_][A-Za-z0-9_]*)\""
            + "|\\{\\?([A-Za-z_][A-Za-z0-9_]*)\\}|([A-Za-z_][A-Za-z0-9_]*))");

    private static int variable(String text, int i, Map<String, String> vars, StringBuilder out) {
        Matcher m = VAR.matcher(text).region(i, text.length());
        if (!m.lookingAt()) {
            out.append(':');
            return i + 1;
        }
        if (m.group(3) != null) {
            out.append(vars.containsKey(m.group(3)) ? "TRUE" : "FALSE");
        } else if (m.group(1) != null && vars.containsKey(m.group(1))) {
            out.append('\'').append(vars.get(m.group(1)).replace("'", "''")).append('\'');
        } else if (m.group(2) != null && vars.containsKey(m.group(2))) {
            out.append('"').append(vars.get(m.group(2)).replace("\"", "\"\"")).append('"');
        } else if (m.group(4) != null && vars.containsKey(m.group(4))) {
            out.append(vars.get(m.group(4)));
        } else {
            out.append(m.group());
        }
        return m.end();
    }

    /** A {@code \set}'s value: its arguments, interpolated and unquoted, joined (psql concatenates them). */
    static String setValue(String args, Map<String, String> vars) {
        StringBuilder v = new StringBuilder();
        int i = 0;
        while (i < args.length()) {
            char c = args.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '\'') {
                int end = closing(args, i);
                v.append(args.substring(i + 1, Math.max(i + 1, end - 1)).replace("''", "'"));
                i = end;
            } else {
                int end = i;
                while (end < args.length() && !Character.isWhitespace(args.charAt(end)) && args.charAt(end) != '\'') {
                    end++;
                }
                v.append(interpolate(args.substring(i, end), vars));
                i = end;
            }
        }
        return v.toString();
    }

    /** psql's booleans: {@code true}, {@code on}, {@code yes}, {@code 1} and their prefixes ({@code t}, {@code y}). */
    static boolean truth(String value) {
        String v = value.strip().toLowerCase(Locale.ROOT);
        return !v.isEmpty() && ("true".startsWith(v) || "yes".startsWith(v) || v.equals("on") || v.equals("1"));
    }

    // ------------------------------------------------------------------ running

    /** What a run of a script tells its caller: each statement as sent, with what the database answered. */
    public interface Listener {
        /** A statement was sent: its text as interpolated, its item, and the database's answer (a statement that
         *  answered no rows answers an empty grid of no columns). */
        void sent(String sql, Sql item, SqlParity.Answer answer);
    }

    /** Runs a script's items on a connection, as psql runs them, telling {@code listener} each statement's answer. */
    public static final class Runner {
        private final Map<String, String> vars;
        private final java.util.function.Supplier<Connection> reconnect;
        private Connection connection;
        private final int timeoutSeconds;

        /** {@code vars} are psql's variables and {@code \getenv}'s environment ({@code env:NAME}); {@code reconnect}
         *  opens the connection a {@code \c} switches to. */
        public Runner(Connection connection, Map<String, String> vars,
                java.util.function.Supplier<Connection> reconnect, int timeoutSeconds) {
            this.connection = connection;
            this.vars = vars;
            this.reconnect = reconnect;
            this.timeoutSeconds = timeoutSeconds;
        }

        /** The connection the script runs on now (a {@code \c} replaces it). */
        public Connection connection() {
            return connection;
        }

        /** Runs {@code items}; returns when the script ends or {@code \q} quits it. */
        public void run(List<Item> items, Listener listener) throws SQLException {
            Deque<boolean[]> conditions = new ArrayDeque<>(); // each \if: {this branch active, a branch was taken}
            for (Item item : items) {
                boolean active = conditions.stream().allMatch(b -> b[0]);
                if (item instanceof Meta m && conditional(m, conditions, active)) {
                    continue;
                }
                if (!active) {
                    continue;
                }
                if (item instanceof Meta m) {
                    if (!meta(m)) {
                        return;
                    }
                } else if (item instanceof Sql q) {
                    statement(q, listener);
                }
            }
        }

        /** An {@code \if}, {@code \elif}, {@code \else} or {@code \endif}: true when {@code m} is one. */
        private boolean conditional(Meta m, Deque<boolean[]> conditions, boolean active) {
            switch (m.command()) {
                case "if" -> {
                    boolean v = active && truth(interpolate(m.args(), vars));
                    conditions.push(new boolean[] {v, v});
                }
                case "elif" -> {
                    boolean[] top = conditions.peek();
                    if (top != null) {
                        boolean v = !top[1] && truth(interpolate(m.args(), vars));
                        top[0] = v;
                        top[1] |= v;
                    }
                }
                case "else" -> {
                    boolean[] top = conditions.peek();
                    if (top != null) {
                        top[0] = !top[1];
                        top[1] = true;
                    }
                }
                case "endif" -> conditions.poll();
                default -> {
                    return false;
                }
            }
            return true;
        }

        /** A meta-command that is not a conditional; false when it quits the script. */
        private boolean meta(Meta m) {
            String[] args = m.args().isEmpty() ? new String[0] : m.args().split("\\s+", 2);
            switch (m.command()) {
                case "set" -> {
                    if (args.length > 0) {
                        vars.put(args[0], args.length > 1 ? setValue(args[1], vars) : "");
                    }
                }
                case "unset" -> {
                    if (args.length > 0) {
                        vars.remove(args[0]);
                    }
                }
                case "getenv" -> {
                    String[] two = m.args().split("\\s+");
                    if (two.length == 2 && vars.containsKey("env:" + two[1])) {
                        vars.put(two[0], vars.get("env:" + two[1]));
                    }
                }
                case "c", "connect" -> connection = reconnect.get();
                case "q", "quit" -> {
                    return false;
                }
                default -> {
                    // display only (\d, \pset, \x, \echo, ...)
                }
            }
            return true;
        }

        private void statement(Sql q, Listener listener) throws SQLException {
            String sql = interpolate(q.text(), vars);
            SqlParity.Answer answer = q.copyData() != null ? copy(sql, q.copyData()) : send(sql);
            listener.sent(sql, q, answer);
            if (answer instanceof SqlParity.Rows rows) {
                if (q.end().equals("gset")) {
                    gset(rows.grid(), q.endArgs());
                } else if (q.end().equals("gexec")) {
                    for (List<Object> row : rows.grid().rows()) {
                        for (Object cell : row) {
                            if (cell != null) {
                                listener.sent(String.valueOf(cell), q, send(String.valueOf(cell)));
                            }
                        }
                    }
                }
            }
        }

        /** {@code \gset [prefix]}: the one row's columns as variables (a NULL unsets its variable). */
        private void gset(SqlParity.Grid grid, String prefix) {
            if (grid.rows().size() != 1) {
                return;
            }
            for (int c = 0; c < grid.columns().size(); c++) {
                Object v = grid.rows().get(0).get(c);
                String name = prefix + grid.columns().get(c);
                if (v == null) {
                    vars.remove(name);
                } else {
                    vars.put(name, v instanceof Boolean b ? (b ? "t" : "f") : String.valueOf(v));
                }
            }
        }

        /** One statement: its first result's rows, an empty grid when it has none, or its error. */
        SqlParity.Answer send(String sql) {
            try (Statement st = connection.createStatement()) {
                st.setQueryTimeout(timeoutSeconds);
                if (st.execute(sql)) {
                    try (ResultSet rs = st.getResultSet()) {
                        return new SqlParity.Rows(SqlParity.grid(rs));
                    }
                }
                return new SqlParity.Rows(new SqlParity.Grid(List.of(), List.of()));
            } catch (SQLException e) {
                return rejected(e);
            }
        }

        /** A {@code COPY ... FROM stdin}: its data written to a file, read by the server ({@code FROM '<file>'}),
         *  which runs on this machine. */
        private SqlParity.Answer copy(String sql, String data) {
            try {
                java.nio.file.Path file = java.nio.file.Files.createTempFile("psql-copy", ".data");
                java.nio.file.Files.writeString(file, data, java.nio.charset.StandardCharsets.UTF_8);
                file.toFile().setReadable(true, false);
                try {
                    return send(FROM_STDIN.matcher(sql).replaceFirst(Matcher.quoteReplacement(
                            "FROM '" + file.toAbsolutePath().toString().replace("'", "''") + "'")));
                } finally {
                    java.nio.file.Files.deleteIfExists(file);
                }
            } catch (java.io.IOException e) {
                return new SqlParity.Rejected("", e.getMessage() == null ? "" : e.getMessage());
            }
        }

        private static final Pattern FROM_STDIN = Pattern.compile("(?i)\\bfrom\\s+stdin\\b");

        static SqlParity.Rejected rejected(SQLException e) {
            String message = e.getMessage() == null ? "" : e.getMessage().split("\n")[0];
            return new SqlParity.Rejected(e.getSQLState() == null ? "" : e.getSQLState(), message);
        }
    }
}
