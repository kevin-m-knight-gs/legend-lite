package com.legend.protocol.spec;

import java.util.Objects;

/**
 * A SQL expression island: {@code #SQL{ select ... }#} (the sql-expression extension) or {@code #IQ{ select ... }#}
 * and {@code #IQ:<profile>{ select ... }#} (legend-lite's SAP IQ island, docs/IQ_SQL_STUDY_2026_10_13.md §4). Wire
 * shape (ZTailProbe "sql-island"): a {@code classInstance} of type {@code SQL} whose value is
 * {@code {"sql": <content>}}, or of type {@code IQ} whose value is {@code {"sql": <content>}} with
 * {@code "profile": <name>} when the island names one; the span covers the whole literal.
 *
 * <p>The SQL island's text is Postgres SQL; the IQ island's is SAP IQ 16.2's, read under the named profile
 * ({@code iq16-default} when none is named). The typer translates it into the Pure Relation expression it means
 * ({@code com.legend.protocol.sql.SqlToPure}, through the language's dialect) and types that, so the island runs
 * through the same typed pipeline as any query ({@code compiler/spec/SqlIslands}).
 *
 * @param language {@link #SQL} or {@link #IQ} (as the parser and the wire reader make it; the typer refuses any other)
 * @param profile  the profile an {@code #IQ:<profile>{...}#} island names, as written; null when it names none (and
 *                 always for {@code #SQL{...}#})
 */
public record SqlIsland(String sql,
        @com.legend.base.Nullable com.legend.protocol.SourceInfo pos,
        String language,
        @com.legend.base.Nullable String profile)
        implements ValueSpecification {

    /** The Postgres island's language. */
    public static final String SQL = "SQL";
    /** The SAP IQ island's language. */
    public static final String IQ = "IQ";

    public SqlIsland {
        Objects.requireNonNull(sql, "sql");
        Objects.requireNonNull(language, "language");
    }

    /** A {@code #SQL{...}#} island. */
    public SqlIsland(String sql, @com.legend.base.Nullable com.legend.protocol.SourceInfo pos) {
        this(sql, pos, SQL, null);
    }

    /** The island's opening as written, up to its first brace: #SQL, #IQ or #IQ:profile, then the brace. */
    public String opening() {
        return "#" + language + (profile == null ? "" : ":" + profile) + "{";
    }
}
