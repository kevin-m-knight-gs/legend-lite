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
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * THE OWNER GUARD (the dialect program, 2026-10-15; docs/DIALECT_LEAK_CENSUS_AND_PLAN_2026_10_12.md D9 to D11): the leak
 * ratchet keeps a database's name inside its owners, and this keeps each owner to its own. Without it,
 * {@code Postgres.java} could decide something for DuckDB and every count would still pass.
 * <ul>
 *   <li>A dialect, the DuckDB-only sources and an input language name only their own database and their family's
 *       ({@link DatabaseOwners}); a shared file in the dialect package names none. A crossing that is deliberate is
 *       registered below with its reason, shrink-only.</li>
 *   <li>A vocabulary owner (the lexer, the parser, the protocol, the model) carries a database's name and decides
 *       nothing by it: it names a database only as its own identifiers ({@code PSnowflakeSpec}, {@code parseTrinoSpec},
 *       the {@code DatabaseType} constants it declares), never as a specific {@code DatabaseType} constant, a dialect
 *       class, a driver, a JDBC URL or the driver's product name. Its dependency allowlists already keep it from
 *       reaching a dialect, a connection or {@code Databases} ({@code ArchitectureTest}: 6j, 7a, 7b, 7c,
 *       {@code compileSideLayersAreDialectBlind}).</li>
 * </ul>
 * Code only, as the ratchet reads it: a comment or a string never counts (a refusal's message names its database).
 */
@Tag("census")
class DialectOwnerExclusivityTest {

    /** A deliberate crossing: an owner that names another database, on {@code lines} lines, and why. Shrink-only. */
    private record Crossing(String file, String database, int lines, String reason) {
    }

    private static final List<Crossing> CROSSINGS = List.of(
            // 2026-10-15 (registered as measured): the engine-H2 text spells ten functions legend-engine's H2 text
            // has no spelling for (MAP_EMPTY, TIME_BUCKET, ...) with DuckDB's own (DuckDbSpellings), because the corpus
            // reads this text on DuckDB too; refusing them broke 14 passing corpus tests (c7cd917; the plan's Phase 1b
            // step 2, the engine-H2 row). Whether the engine text should spell them at all is the engine-text work's
            // question, not this guard's.
            new Crossing("core/src/main/java/com/legend/sql/dialect/EngineStyleH2.java", "DuckDB", 9,
                    "the corpus reads the engine-H2 text on DuckDB: ten DuckDB-only functions spelled DuckDB's way"));

    /** {@code DatabaseType.valueOf} and {@code .values()}: the vocabulary reading a name, deciding nothing. */
    private static final Pattern ANY_TYPE = Pattern.compile("\\bDatabaseType\\s*\\.\\s*(valueOf|values)\\b");

    private static final List<String> MAIN_ROOTS = List.of("core/src/main/java", "core/src/main/duckdb");

    @Test
    void eachOwnerNamesOnlyItsOwnDatabase() throws IOException {
        Map<String, Integer> crossings = new TreeMap<>();
        for (Path f : SourceFiles.under(MAIN_ROOTS.get(0), MAIN_ROOTS.subList(1, MAIN_ROOTS.size())
                .toArray(String[]::new))) {
            String rel = SourceFiles.path(f);
            if (!rel.endsWith(".java")) {
                continue;
            }
            String src = Files.readString(f);
            DatabaseOwners.Owner owner = DatabaseOwners.of(rel, src);
            if (owner == null || owner.kind() == DatabaseOwners.Kind.EVERY
                    || owner.kind() == DatabaseOwners.Kind.VOCABULARY) {
                continue;
            }
            for (String line : DialectLeakRatchetTest.lex(src).code()) {
                for (String db : DatabaseOwners.named(line)) {
                    if (!owner.databases().contains(db)) {
                        crossings.merge(rel + "\t" + db, 1, Integer::sum);
                    }
                }
            }
        }
        List<String> failures = new ArrayList<>();
        Map<String, Crossing> pins = new TreeMap<>();
        CROSSINGS.forEach(c -> pins.put(c.file() + "\t" + c.database(), c));
        crossings.forEach((k, n) -> {
            Crossing pin = pins.get(k);
            if (pin == null || n > pin.lines()) {
                failures.add(k.replace("\t", " names ") + " on " + n + " lines"
                        + (pin == null ? "" : " (registered: " + pin.lines() + ")"));
            }
        });
        pins.forEach((k, pin) -> {
            if (crossings.getOrDefault(k, 0) < pin.lines()) {
                System.out.println("[owner-guard] lower this crossing in DialectOwnerExclusivityTest: "
                        + k.replace('\t', ' ') + " " + pin.lines() + " -> " + crossings.getOrDefault(k, 0));
            }
        });
        assertTrue(failures.isEmpty(), "an owner names a database not its own: move the decision into that database's"
                + " owner, or pass what differs in from it (a crossing is registered here only with a dated reason): "
                + failures);
    }

    @Test
    void aVocabularyOwnerCarriesADatabasesNameAndDecidesNothingByIt() throws IOException {
        List<String> failures = new ArrayList<>();
        for (Path f : SourceFiles.under(MAIN_ROOTS.get(0))) {
            String rel = SourceFiles.path(f);
            if (!rel.endsWith(".java")) {
                continue;
            }
            String src = Files.readString(f);
            DatabaseOwners.Owner owner = DatabaseOwners.of(rel, src);
            if (owner == null || owner.kind() != DatabaseOwners.Kind.VOCABULARY) {
                continue;
            }
            DialectLeakRatchetTest.Lines lines = DialectLeakRatchetTest.lex(src);
            for (int i = 0; i < lines.code().size(); i++) {
                String code = ANY_TYPE.matcher(lines.code().get(i)).replaceAll("");
                for (var kind : DialectLeakRatchetTest.IN_CODE.entrySet()) {
                    if (!kind.getKey().equals("product-name") && kind.getValue().matcher(code).find()) {
                        failures.add(rel + ":" + (i + 1) + " " + kind.getKey());
                    }
                }
                if (DialectLeakRatchetTest.JDBC_URL.matcher(lines.strings().get(i)).find()) {
                    failures.add(rel + ":" + (i + 1) + " jdbc-url");
                }
            }
        }
        assertTrue(failures.isEmpty(), "the model language carries a database's name and decides nothing by it (D9):"
                + " a specific DatabaseType, a dialect, a driver, a JDBC URL or a product-name call belongs to the"
                + " database's owner: " + failures);
    }

    /** The register reads the owners the plan names, each with its own database. */
    @Test
    void theRegisterNamesEachOwnersDatabase() {
        String dialect = "core/src/main/java/com/legend/sql/dialect/";
        assertEquals(Set.of("DuckDB"), DatabaseOwners.of(dialect + "DuckDb.java", "class DuckDb extends AnsiSqlRenderer")
                .databases());
        assertEquals(Set.of("MySQL", "MariaDB"),
                DatabaseOwners.of(dialect + "MySql.java", "class MySql extends MariaDb").databases());
        assertEquals(Set.of("DB2", "H2"),
                DatabaseOwners.of(dialect + "EngineStyleDB2.java", "class EngineStyleDB2 extends EngineStyleH2")
                        .databases());
        assertEquals(Set.of(), DatabaseOwners.of(dialect + "Stars.java", "final class Stars {").databases());
        // only the file's own class's parent, in code: not a comment's, not a nested class's
        assertEquals(Set.of(), DatabaseOwners.of(dialect + "Stars.java",
                "/** like class Stars extends Postgres */ final class Stars { static class In extends DuckDb {} }")
                .databases());
        assertEquals(Set.of("H2"), DatabaseOwners.of(dialect + "RawSqlBoundary.java", "").databases());
        assertEquals(DatabaseOwners.Kind.VOCABULARY,
                DatabaseOwners.of("core/src/main/java/com/legend/parser/section/ConnectionSectionGrammar.java", "")
                        .kind());
        assertEquals(Set.of("Postgres"),
                DatabaseOwners.of("core/src/main/java/com/legend/protocol/sql/PostgresDialect.java", "").databases());
        assertEquals(null, DatabaseOwners.of("core/src/main/java/com/legend/protocol/sql/SqlToPure.java", ""));
        assertEquals(null, DatabaseOwners.of("core/src/main/java/com/legend/exec/Sessions.java", ""));
    }

    /** Every name the ratchet counts as a database is one the register knows: the two read the same names. */
    @Test
    void theRegisterSeesEveryNameTheRatchetCounts() {
        Pattern products = DialectLeakRatchetTest.IN_CODE.get("product-name");
        for (String named : List.of("isH2", "EngineStyleDB2", "DuckDbCells", "postgresName", "PSnowflakeSpec",
                "parseTrinoSpec", "PBigQuerySpec", "SybaseIQ", "PMongoDbConnection", "isSqlServer", "MariaDb",
                "MySql", "ClickHouse", "presto", "oracleSpec", "redshiftSpec", "athenaEndpoint", "PSpannerSpec",
                "MemSql", "Elasticsearch7", "Hive")) {
            assertTrue(products.matcher(named).find(), named);
            assertEquals(1, DatabaseOwners.named(named).size(), named + ": " + DatabaseOwners.named(named));
        }
    }
}
