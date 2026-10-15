// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * THE OWNERS: which sources may name a database, and which databases each may name (the dialect program,
 * docs/DIALECT_LEAK_CENSUS_AND_PLAN_2026_10_12.md §4 and decisions D9 to D11, the user's, 2026-10-15). The leak ratchet
 * counts a database named anywhere else ({@link DialectLeakRatchetTest}); the owner guard holds each owner to its own
 * ({@link DialectOwnerExclusivityTest}). Four kinds:
 * <ul>
 *   <li>a <b>dialect</b> (the SQL a database runs, {@code sql/dialect/}) and the DuckDB-only execution sources
 *       ({@code core/src/main/duckdb}): the database its class is named for, and its family's (the class it
 *       extends: {@code MySql} names MariaDB); a file named for none is shared, and names none;</li>
 *   <li>an <b>input language</b> (the SQL a user writes, {@code protocol/sql/}, D11): {@code PostgresDialect} and
 *       {@code PostgresDateTime} Postgres's, the {@code iq} package IQ's (Sybase's); the translator they share is no
 *       owner;</li>
 *   <li>a <b>vocabulary</b> (the model language: the lexer, the parser and its grammars, the protocol, the model, D9):
 *       it may parse and carry any database's name, and decides nothing by one;</li>
 *   <li>the <b>registry</b> ({@code database.Databases}, a declared type to its dialect) and the guards, which name
 *       every database by design.</li>
 * </ul>
 * The dialect package's tests name several databases by design (one test compares them) and are owners of every one.
 */
final class DatabaseOwners {

    private DatabaseOwners() {
    }

    enum Kind { DIALECT, INPUT_LANGUAGE, VOCABULARY, EVERY }

    /** An owner: its kind, and the databases it may name (for a vocabulary or the registry, every one). */
    record Owner(Kind kind, Set<String> databases) {
    }

    /** Every database the census sees, each by the pattern of its name in an identifier: the ratchet's product-name
     *  kind, one database at a time. H2 and DB2 as the ratchet reads them (an identifier's word, never a word's
     *  letters: {@code width2} is no H2). */
    static final Map<String, Pattern> DATABASES = databases();

    private static Map<String, Pattern> databases() {
        Map<String, String> names = new LinkedHashMap<>();
        names.put("DuckDB", "duck\\s?db");
        names.put("H2", "(?-i:(?<=[a-z0-9])H2|(?<![A-Za-z0-9])[Hh]2)(?![0-9])");
        names.put("DB2", "(?-i:(?<=[a-z0-9])D[Bb]2|(?<![A-Za-z0-9])[Dd][Bb]2)(?![0-9])");
        names.put("Postgres", "postgres(?:ql)?");
        names.put("Snowflake", "snowflake");
        names.put("Databricks", "databricks");
        names.put("BigQuery", "big\\s?query");
        names.put("SQLite", "sqlite");
        names.put("MariaDB", "maria\\s?db");
        names.put("MySQL", "mysql");
        names.put("Trino", "trino|presto");
        names.put("ClickHouse", "click\\s?house");
        names.put("Sybase", "sybase");
        names.put("Oracle", "oracle");
        names.put("SqlServer", "sql\\s?server");
        names.put("Redshift", "redshift");
        names.put("Athena", "athena");
        names.put("Spanner", "spanner");
        names.put("Hive", "hive\\b");
        names.put("MemSQL", "memsql|singlestore");
        names.put("MongoDB", "mongo");
        names.put("Elasticsearch", "elastic");
        Map<String, Pattern> out = new LinkedHashMap<>();
        names.forEach((db, p) -> out.put(db, Pattern.compile("(?i)\\b\\w*(" + p + ")\\w*")));
        return out;
    }

    /** The databases {@code code} (a line, or a class name) names. */
    static Set<String> named(String code) {
        Set<String> out = new LinkedHashSet<>();
        DATABASES.forEach((db, p) -> {
            if (p.matcher(code).find()) {
                out.add(db);
            }
        });
        return out;
    }

    private static final String MAIN = "core/src/main/java/com/legend/";

    /** The dialect package's shared files that own a database their name does not say: the engine text's reader owns
     *  the language it reads, legend-engine's H2 (D2). */
    private static final Map<String, Set<String>> NAMED_BY_ROLE = Map.of(
            MAIN + "sql/dialect/RawSqlBoundary.java", Set.of("H2"));

    /** The input languages (D11): each file and the database whose SQL it reads. */
    private static final Map<String, Set<String>> INPUT_LANGUAGES = Map.of(
            MAIN + "protocol/sql/PostgresDialect.java", Set.of("Postgres"),
            MAIN + "protocol/sql/PostgresDateTime.java", Set.of("Postgres"));

    private static final String IQ = MAIN + "protocol/sql/iq/";

    /** The vocabulary (D9): the model language's lexer, parser, protocol and model. {@code protocol/sql/}, the SQL a user
     *  writes, is not vocabulary: its owners are the input languages. */
    private static final List<String> VOCABULARY = List.of(MAIN + "lexer/", MAIN + "parser/", MAIN + "protocol/",
            MAIN + "model/");

    /** Sources that name every database by design: the registry and the guards that count names. */
    private static final List<String> EVERY = List.of(
            MAIN + "database/Databases.java",
            "core/src/test/java/com/legend/sql/dialect/",
            "core/src/test/java/com/legend/DialectBoundaryTest.java",
            "core/src/test/java/com/legend/DialectLeakRatchetTest.java",
            "core/src/test/java/com/legend/DialectOwnerExclusivityTest.java",
            "core/src/test/java/com/legend/DatabaseOwners.java");

    private static final Pattern EXTENDS = Pattern.compile("\\bclass\\s+\\w+\\s+extends\\s+(\\w+)");

    /** The owner {@code rel} (a source's path from the repository root, {@code source} its text) is, or null when it
     *  is no owner and every database it names is a leak. */
    static Owner of(String rel, String source) {
        if (EVERY.stream().anyMatch(rel::startsWith)) {
            return new Owner(Kind.EVERY, DATABASES.keySet());
        }
        if (rel.startsWith(MAIN + "sql/dialect/") || rel.startsWith("core/src/main/duckdb/")) {
            Set<String> own = new LinkedHashSet<>(named(className(rel)));
            own.addAll(NAMED_BY_ROLE.getOrDefault(rel, Set.of()));
            Matcher parent = EXTENDS.matcher(source);
            if (parent.find()) {
                own.addAll(named(parent.group(1)));
            }
            return new Owner(Kind.DIALECT, own);
        }
        if (INPUT_LANGUAGES.containsKey(rel)) {
            return new Owner(Kind.INPUT_LANGUAGE, INPUT_LANGUAGES.get(rel));
        }
        if (rel.startsWith(IQ)) {
            return new Owner(Kind.INPUT_LANGUAGE, Set.of("Sybase"));
        }
        if (rel.startsWith(MAIN + "protocol/sql/")) {
            return null;
        }
        if (VOCABULARY.stream().anyMatch(rel::startsWith)) {
            return new Owner(Kind.VOCABULARY, DATABASES.keySet());
        }
        return null;
    }

    private static String className(String rel) {
        String file = rel.substring(rel.lastIndexOf('/') + 1);
        return file.endsWith(".java") ? file.substring(0, file.length() - ".java".length()) : file;
    }
}
