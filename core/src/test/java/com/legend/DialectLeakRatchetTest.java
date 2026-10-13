// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend;

import com.legend.testing.SourceFiles;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * THE DIALECT-LEAK RATCHET (the dialect program's Phase 0, 2026-10-13; docs/DIALECT_LEAK_CENSUS_AND_PLAN_2026_10_12.md):
 * the census's mechanical part, kept. Every database decision belongs to its dialect (or, for execution, its
 * connector); a line that names a database anywhere else is a leak the program moves home. Counted per file and per
 * kind, by line, in code (a comment never counts; a string only for a JDBC URL):
 * <ul>
 *   <li>{@code database-type}: {@code DatabaseType.X};</li>
 *   <li>{@code dialect-class}: a dialect class constructed, tested, cast to or named by its package;</li>
 *   <li>{@code driver}: a JDBC driver's own package;</li>
 *   <li>{@code product-name}: a database's name in an identifier ({@code isPostgres}, {@code DuckDbCells});</li>
 *   <li>{@code jdbc-url}: a {@code jdbc:} URL written inline;</li>
 *   <li>{@code product-call}: {@code getDatabaseProductName}, a decision by what the driver says it is.</li>
 * </ul>
 * The owners are not counted: the dialect package and its tests, the DuckDB-only sources, and
 * {@code com.legend.database.Databases}, which maps a declared type to its dialect. Every other file's count is
 * pinned in {@code core/src/test/resources/dialect-leaks/ledger.tsv}, shrink-only: a count above its pin fails, and a
 * count below it prints the pin to lower (the run writes the whole table to its outputs, {@code dialect-leaks.tsv}).
 * When the program ends every pin is zero and this becomes a ban.
 */
@Tag("census")
class DialectLeakRatchetTest {

    private static final String LEDGER = "core/src/test/resources/dialect-leaks/ledger.tsv";

    /** The Java sources the census target declares (core, and the modules that test it). */
    private static final List<String> ROOTS = List.of("core/src", "pct/src", "spec/src", "parser-equivalence/src",
            "warehouse/src/test/java");

    /** Where a database is decided by design, so a name there is no leak. */
    private static final List<String> OWNERS = List.of(
            "core/src/main/java/com/legend/sql/dialect/",
            "core/src/test/java/com/legend/sql/dialect/",
            "core/src/main/duckdb/",
            "core/src/main/java/com/legend/database/Databases.java",
            // the guards that name every database to count them
            "core/src/test/java/com/legend/DialectBoundaryTest.java",
            "core/src/test/java/com/legend/DialectLeakRatchetTest.java");

    private static final String DIALECTS = "AnsiSqlRenderer|DuckDb|H2Modern|H2|Postgres|Snowflake|Databricks|BigQuery"
            + "|SQLite|MariaDb|MySql|Trino|ClickHouse|EngineStyleH2|EngineStyleDB2|EngineStyleComposite";

    private static final String PRODUCTS = "duck\\s?db|postgres(?:ql)?|snowflake|databricks|big\\s?query|sqlite"
            + "|maria\\s?db|mysql|trino|presto|click\\s?house|\\bh2\\b|\\bdb2\\b|sybase|oracle|sql\\s?server|redshift"
            + "|athena|spanner|hive\\b|memsql|singlestore|mongo|elastic";

    /** The kinds counted in code, each by the census's own pattern (scanner/scan.py). */
    private static final Map<String, Pattern> IN_CODE = Map.of(
            "database-type", Pattern.compile("\\bDatabaseType\\s*\\.\\s*\\w+"),
            "dialect-class", Pattern.compile("\\b(new\\s+|instanceof\\s+|\\(\\s*)(?:com\\.legend\\.sql\\.dialect\\.)?("
                    + DIALECTS + ")\\b(\\s*\\(|\\s+\\w|\\s*\\)|\\.class)|\\bcom\\.legend\\.sql\\.dialect\\.(" + DIALECTS
                    + ")\\b"),
            "driver", Pattern.compile("\\b(org\\.duckdb|org\\.h2\\.|org\\.postgresql|net\\.snowflake|com\\.databricks"
                    + "|com\\.simba|com\\.google\\.cloud\\.bigquery|org\\.sqlite|org\\.mariadb|com\\.mysql|io\\.trino"
                    + "|com\\.clickhouse)"),
            "product-name", Pattern.compile("(?i)\\b\\w*(" + PRODUCTS + ")\\w*"),
            "product-call", Pattern.compile("\\bgetDatabaseProductName\\b"));

    private static final Pattern JDBC_URL = Pattern.compile("jdbc:\\w+");

    @Test
    void databaseNamesOutsideTheirOwnersOnlyShrink() throws IOException {
        TreeMap<String, Integer> counts = new TreeMap<>();
        for (Path f : SourceFiles.under(ROOTS.get(0), ROOTS.subList(1, ROOTS.size()).toArray(String[]::new))) {
            String rel = SourceFiles.path(f);
            if (!rel.endsWith(".java") || OWNERS.stream().anyMatch(rel::startsWith)) {
                continue;
            }
            Lines lines = lex(Files.readString(f));
            for (var kind : IN_CODE.entrySet()) {
                int n = (int) lines.code().stream().filter(l -> kind.getValue().matcher(l).find()).count();
                if (n > 0) {
                    counts.put(rel + "\t" + kind.getKey(), n);
                }
            }
            int urls = (int) lines.strings().stream().filter(l -> JDBC_URL.matcher(l).find()).count();
            if (urls > 0) {
                counts.put(rel + "\tjdbc-url", urls);
            }
        }
        List<String> table = new ArrayList<>();
        counts.forEach((k, v) -> table.add(k + "\t" + v));
        Files.createDirectories(com.legend.testing.TestOutputs.dir());
        Files.write(com.legend.testing.TestOutputs.file("dialect-leaks.tsv"), table);

        Map<String, Integer> pins = new TreeMap<>();
        for (String line : Files.readAllLines(SourceFiles.file(LEDGER))) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            int cut = line.lastIndexOf('\t');
            pins.put(line.substring(0, cut), Integer.parseInt(line.substring(cut + 1)));
        }
        List<String> grew = new ArrayList<>();
        List<String> shrank = new ArrayList<>();
        for (var e : counts.entrySet()) {
            int pin = pins.getOrDefault(e.getKey(), 0);
            if (e.getValue() > pin) {
                grew.add(e.getKey().replace('\t', ' ') + ": " + pin + " -> " + e.getValue());
            }
        }
        for (var e : pins.entrySet()) {
            int now = counts.getOrDefault(e.getKey(), 0);
            if (now < e.getValue()) {
                shrank.add(e.getKey().replace('\t', ' ') + ": " + e.getValue() + " -> " + now);
            }
        }
        if (!shrank.isEmpty()) {
            System.out.println("[dialect-leaks] lower these pins in " + LEDGER + ": " + shrank);
        }
        System.out.println("[dialect-leaks] " + counts.size() + " file-kinds, "
                + counts.values().stream().mapToInt(Integer::intValue).sum() + " lines outside the owners");
        assertTrue(grew.isEmpty(), "a database named outside its owners (dialect, connector, Databases) — move the"
                + " decision into its dialect; a pin rises only with a dated reason in " + LEDGER + ": " + grew
                + " (the whole table is the run's dialect-leaks.tsv)");
    }

    /** A source's lines as code (comments and string literals blanked) and as its string literals, line by line. */
    record Lines(List<String> code, List<String> strings) {
    }

    static Lines lex(String src) {
        StringBuilder code = new StringBuilder(src.length());
        StringBuilder strings = new StringBuilder();
        int i = 0;
        int n = src.length();
        while (i < n) {
            char c = src.charAt(i);
            if (src.startsWith("//", i)) {
                while (i < n && src.charAt(i) != '\n') {
                    i++;
                }
            } else if (src.startsWith("/*", i)) {
                int end = src.indexOf("*/", i + 2);
                end = end < 0 ? n : end + 2;
                blank(src, i, end, code, strings);
                i = end;
            } else if (src.startsWith("\"\"\"", i)) {
                int end = src.indexOf("\"\"\"", i + 3);
                end = end < 0 ? n : end + 3;
                literal(src, i, end, code, strings);
                i = end;
            } else if (c == '"' || c == '\'') {
                int j = i + 1;
                while (j < n && src.charAt(j) != c && src.charAt(j) != '\n') {
                    j += src.charAt(j) == '\\' ? 2 : 1;
                }
                int end = Math.min(j + 1, n);
                if (c == '"') {
                    literal(src, i, end, code, strings);
                } else {
                    blank(src, i, end, code, strings);
                }
                i = end;
            } else {
                code.append(c);
                strings.append(c == '\n' ? '\n' : ' ');
                i++;
            }
        }
        return new Lines(List.of(code.toString().split("\n", -1)), List.of(strings.toString().split("\n", -1)));
    }

    /** {@code src[from, to)} as neither code nor string: newlines kept, so both stay line for line. */
    private static void blank(String src, int from, int to, StringBuilder code, StringBuilder strings) {
        for (int k = from; k < to; k++) {
            char ch = src.charAt(k) == '\n' ? '\n' : ' ';
            code.append(ch);
            strings.append(ch);
        }
    }

    /** {@code src[from, to)}, a string literal: in the strings, blanked in the code. */
    private static void literal(String src, int from, int to, StringBuilder code, StringBuilder strings) {
        for (int k = from; k < to; k++) {
            char ch = src.charAt(k);
            code.append(ch == '\n' ? '\n' : ' ');
            strings.append(ch);
        }
    }
}
