package com.legend.sql.dialect;

import java.util.Set;

/**
 * Dialect LEXICON &mdash; pure DATA, the first extraction of the
 * rewrite-then-render migration (remediation T3.2): what a backend quotes
 * and how, with no behavior attached. A backend that differs ONLY here is
 * not a dialect subclass &mdash; it is a {@code Lexicon} row handed to the
 * base renderer (SQLite was exactly that and its class is gone).
 *
 * @param quoteChar     the identifier quote character
 * @param reservedWords lower-cased words that force quoting of a
 *                      plain-looking identifier
 */
public record Lexicon(char quoteChar, Set<String> reservedWords) {

    public Lexicon {
        reservedWords = Set.copyOf(reservedWords);
    }

    /** DuckDB (the execution backend). */
    public static final Lexicon DUCKDB = new Lexicon('"', Set.of(
            "all", "and", "as", "asc", "between", "by", "case", "cast", "create", "cross",
            "default", "delete", "desc", "distinct", "drop", "else", "end", "except", "exists",
            "false", "from", "full", "group", "having", "in", "inner", "insert", "intersect",
            "into", "is", "join", "left", "like", "limit", "not", "null", "offset", "on", "or",
            "order", "outer", "pivot", "qualify", "right", "select", "table", "then", "true",
            "union", "update", "using", "values", "when", "where", "window", "with"));

    /**
     * SQLite (<a href="https://sqlite.org/lang_keywords.html">lang_keywords</a>)
     * &mdash; the ANSI baseline rendering with SQLite's reserved-word list.
     * SQLite accepts the relational core the base renderer emits (SELECT/
     * JOIN/WHERE/GROUP BY/ORDER BY/LIMIT-OFFSET, TRUE/FALSE since 3.23).
     * KNOWN GAPS that fail LOUD at execution, pending SQLite-specific
     * passes: aliased VALUES sources (TDS literals &mdash; SQLite lacks
     * {@code (VALUES ...) AS t(c1, c2)}), typed DATE/TIMESTAMP literals,
     * and everything with no ANSI encoding at all (list lambdas, QUALIFY,
     * ASOF, native PIVOT) on the base renderer's loud defaults.
     */
    public static final Lexicon SQLITE = new Lexicon('"', Set.of(
            "abort", "action", "add", "after", "all", "alter", "analyze", "and", "as",
            "asc", "attach", "autoincrement", "before", "begin", "between", "by",
            "cascade", "case", "cast", "check", "collate", "column", "commit",
            "conflict", "constraint", "create", "cross", "current_date",
            "current_time", "current_timestamp", "database", "default", "deferrable",
            "deferred", "delete", "desc", "detach", "distinct", "drop", "each",
            "else", "end", "escape", "except", "exclusive", "exists", "explain",
            "fail", "for", "foreign", "from", "full", "glob", "group", "having",
            "if", "ignore", "immediate", "in", "index", "indexed", "initially",
            "inner", "insert", "instead", "intersect", "into", "is", "isnull",
            "join", "key", "left", "like", "limit", "match", "natural", "no", "not",
            "notnull", "null", "of", "offset", "on", "or", "order", "outer", "plan",
            "pragma", "primary", "query", "raise", "recursive", "references",
            "regexp", "reindex", "release", "rename", "replace", "restrict", "right",
            "rollback", "row", "savepoint", "select", "set", "table", "temp",
            "temporary", "then", "to", "transaction", "trigger", "union", "unique",
            "update", "using", "vacuum", "values", "view", "virtual", "when",
            "where", "with", "without"));

    /** Engine-text lowering (H2/DB2 golden style): minimal quoting. */
    public static final Lexicon ENGINE_STYLE = new Lexicon('"', Set.of());
}
