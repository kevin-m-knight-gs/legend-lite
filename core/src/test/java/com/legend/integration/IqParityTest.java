// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.integration;

import com.legend.protocol.sql.iq.IqProfile;
import com.legend.server.QueryService;
import com.legend.testcases.IqAnswers;
import com.legend.testcases.IqParity;
import com.legend.testcases.SqlParity;
import com.legend.testing.Runfile;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code #IQ{...}#} island's parity cases on DuckDB ({@link IqParity}): every case of
 * {@code core/src/test/resources/iq-parity/lite} under each of its profiles, as an island over the schema's tables in
 * DuckDB, judged against SQL Anywhere 17's snapped answer (or the case's provisional decision), and held to the
 * ledger's {@code duckdb} column. The answers file is checked against the cases first: UNSNAPPED, STALE and GONE fail
 * the lane, as does a case pinning an engine its island does not run in, a case SQL Anywhere judges whose
 * translation the IQ engine's rules change (engine neutrality, the study's §6.3), and a judged case whose WHERE puts
 * an operation that can raise beside AND or OR (its answer depends on IQ's plan: the study's D-E2).
 *
 * <p>Its own target ({@code //core:iq_parity_test}). Run with {@code --jvm_flag=-Diq.parity.update=<the ledger's path
 * in the workspace>} to write the ledger's column instead.
 */
class IqParityTest {

    @Test
    void everyIqCaseHasItsLedgerStatusOnDuckDB() throws Exception {
        Path schemaFile = Runfile.property("iq.parity.schema");
        List<IqParity.Run> runs = IqParity.runs(IqParity.cases(Runfile.property("iq.parity.lite").getParent()));
        List<SqlParity.Table> tables = IqParity.schema(schemaFile);
        Set<String> names = IqParity.tableNames(tables);
        Map<String, IqAnswers.Snap> answers = IqAnswers.read(Runfile.property("iq.parity.answers"));
        List<String> problems = new ArrayList<>(IqAnswers.guards(runs, answers, Files.readAllBytes(schemaFile)));
        String model = IqParity.model(tables, "type: DuckDB;\n    specification: DuckDB { };\n    auth: Test;");
        Map<String, SqlParity.Outcome> outcomes = new LinkedHashMap<>();
        try (Connection duckdb = DriverManager.getConnection("jdbc:duckdb:")) {
            IqParity.seed(duckdb, tables, false);
            QueryService service = new QueryService();
            for (IqParity.Run r : runs) {
                String mismatch = IqParity.engineMismatch(r, names);
                if (mismatch != null) {
                    problems.add(mismatch);
                }
                String neutrality = IqParity.engineNeutrality(r, tables, names);
                if (neutrality != null) {
                    problems.add(neutrality);
                }
                String order = IqParity.evaluationOrder(r);
                if (order != null) {
                    problems.add(order);
                }
                outcomes.put(r.key(), IqParity.outcome(r, answers.get(r.key()), names,
                        island -> service.execute(model, island, IqParity.RUNTIME, duckdb)));
            }
        }
        String update = System.getProperty("iq.parity.update");
        problems.addAll(IqParity.ratchet(runs, outcomes, Runfile.property("iq.parity.ledger"), "duckdb",
                update == null ? null : Path.of(update)));
        assertTrue(problems.isEmpty(), problems.size() + " problems:\n" + String.join("\n", problems));
    }

    @Test
    void theAnswersCannotFallBehindTheCases() {
        IqParity.Case c = new IqParity.Case("lite/x.yaml", "a", "SELECT 1", List.of("iq16-default", "iq16-tds"), null,
                null, null, null);
        byte[] schema = "tables:\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Map<String, IqAnswers.Snap> answers = new LinkedHashMap<>();
        answers.put("lite/x/a@iq16-default", new IqAnswers.Snap("lite/x/a", "iq16-default",
                IqAnswers.sqlSha("SELECT 2", "iq16-default", null, null, schema), List.of(), List.of(), List.of(), null));
        answers.put("lite/x/b@iq16-default", new IqAnswers.Snap("lite/x/b", "iq16-default",
                IqAnswers.sqlSha("SELECT 1", "iq16-default", null, null, schema), List.of(), List.of(), List.of(), null));
        List<String> problems = IqAnswers.guards(IqParity.runs(List.of(c)), answers, schema);
        assertEquals(3, problems.size(), String.join("\n", problems));
        assertTrue(problems.get(0).startsWith("STALE: lite/x/a@iq16-default"), problems.get(0));
        assertTrue(problems.get(1).startsWith("UNSNAPPED: lite/x/a@iq16-tds"), problems.get(1));
        assertTrue(problems.get(2).startsWith("GONE: lite/x/b@iq16-default"), problems.get(2));
    }

    @Test
    void theIslandsProfilesAreTheOraclesProfiles() throws Exception {
        String first = Files.readAllLines(Runfile.property("iq.parity.answers")).get(0);
        com.legend.json.Json.Obj meta = (com.legend.json.Json.Obj) ((com.legend.json.Json.Obj)
                com.legend.json.Json.parse(first)).get("meta");
        com.legend.json.Json.Obj profiles = (com.legend.json.Json.Obj) meta.get("profiles");
        com.legend.json.Json.Obj databases = (com.legend.json.Json.Obj) meta.get("databases");
        for (IqProfile p : IqProfile.values()) {
            com.legend.json.Json.Obj profile = (com.legend.json.Json.Obj) profiles.get(p.spelling());
            String db = ((com.legend.json.Json.Str) profile.get("db")).value();
            com.legend.json.Json.Obj database = (com.legend.json.Json.Obj) databases.get(db);
            com.legend.json.Json.Obj props = (com.legend.json.Json.Obj) database.get("properties");
            com.legend.json.Json.Obj pub = (com.legend.json.Json.Obj) database.get("public");
            com.legend.json.Json.Obj options = (com.legend.json.Json.Obj) profile.get("options");
            String where = p.spelling() + ": ";
            assertEquals(db.equals("lite_iq"), p.iqDatabase(), where + "database");
            assertEquals(text(props, "CaseSensitive", "").equals("On"), p.caseSensitive(), where + "case");
            assertEquals(text(props, "BlankPadding", "").equals("On"), p.blankPadding(), where + "blank padding");
            assertEquals(text(props, "CharSet", "").startsWith("ISO_8859-1"), p.latin1(), where + "charset");
            assertEquals(Integer.parseInt(text(pub, "precision", "30")), p.precision(), where + "precision");
            assertEquals(Integer.parseInt(text(pub, "scale", "6")), p.scale(), where + "scale");
            assertEquals(!text(options, "ansinull", "On").equalsIgnoreCase("Off"), p.ansinull(), where + "ansinull");
            assertEquals(!text(options, "quoted_identifier", "On").equalsIgnoreCase("Off"), p.quotedIdentifier(),
                    where + "quoted_identifier");
            assertEquals(!text(options, "escape_character", "On").equalsIgnoreCase("Off"), p.escapeCharacter(),
                    where + "escape_character");
            assertTrue(text(options, "date_order", "YMD").equalsIgnoreCase(p.dateOrder()), where + "date_order");
        }
    }

    private static String text(com.legend.json.Json.Obj o, String key, String otherwise) {
        return o.getOr(key, null) instanceof com.legend.json.Json.Str s ? s.value() : otherwise;
    }

    @Test
    void editingADecidedCasesRowsMakesItsAnswerStale() {
        byte[] schema = "tables:\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        IqParity.Case decided = new IqParity.Case("lite/x.yaml", "a", "SELECT 1", List.of("iq16-default"), null, null,
                "D-S1", "[[\"1\"]]");
        Map<String, IqAnswers.Snap> answers = new LinkedHashMap<>();
        answers.put("lite/x/a@iq16-default", new IqAnswers.Snap("lite/x/a", "iq16-default",
                IqAnswers.sqlSha("SELECT 1", "iq16-default", "D-S1", "[[\"2\"]]", schema), List.of(), List.of(),
                List.of(), null));
        List<String> problems = IqAnswers.guards(IqParity.runs(List.of(decided)), answers, schema);
        assertEquals(1, problems.size(), String.join("\n", problems));
        assertTrue(problems.get(0).startsWith("STALE: lite/x/a@iq16-default"), problems.get(0));
    }

    @Test
    void theJudgeComparesExactlyBySqlAnywheresType() {
        // integers exactly, never through a double
        assertFalse(IqParity.cellEquals(new java.math.BigInteger("9223372036854775807"), 9223372036854775806L,
                "bigint"));
        assertTrue(IqParity.cellEquals(new java.math.BigInteger("9223372036854775807"), Long.MAX_VALUE, "bigint"));
        assertFalse(IqParity.cellEquals(java.math.BigInteger.ONE, new java.math.BigDecimal("1.0"), "integer"));
        // numerics by value, scale aside, never against a double
        assertTrue(IqParity.cellEquals(new java.math.BigDecimal(".50"), new java.math.BigDecimal("0.5"), "numeric"));
        assertFalse(IqParity.cellEquals(new java.math.BigDecimal("0.1"), 0.1d, "numeric"));
        // doubles within a few units in the last place, no absolute slack
        assertFalse(IqParity.cellEquals(1e-300, 0.0d, "double"));
        assertTrue(IqParity.cellEquals(0.30000000000000004, 0.30000000000000004, "double"));
        // text exactly
        assertFalse(IqParity.cellEquals("ab", "ab   ", "char"));
        assertFalse(IqParity.cellEquals("1", 1L, "varchar"));
    }

    @Test
    void aCaseSqlAnywhereJudgesMustTranslateAlikeUnderBothEnginesRules() {
        List<SqlParity.Table> tables = List.of(new SqlParity.Table("t", List.of(new SqlParity.Column("id", "integer"),
                new SqlParity.Column("ch", "char(5)")), List.of()));
        Set<String> names = Set.of("t");
        IqParity.Case padded = new IqParity.Case("lite/x.yaml", "a", "SELECT ch FROM t", List.of("iq16-default"),
                null, null, null, null);
        // the IQ engine pads ch (D-S1), SQL Anywhere's does not: the case must name its decision
        assertTrue(IqParity.engineNeutrality(IqParity.runs(List.of(padded)).get(0), tables, names) != null);
        IqParity.Case decided = new IqParity.Case("lite/x.yaml", "a", "SELECT ch FROM t", List.of("iq16-default"),
                null, null, "D-S1", "[]");
        assertEquals(null, IqParity.engineNeutrality(IqParity.runs(List.of(decided)).get(0), tables, names));
        // ch unread: nothing differs
        IqParity.Case neutral = new IqParity.Case("lite/x.yaml", "a", "SELECT id FROM t", List.of("iq16-default"),
                null, null, null, null);
        assertEquals(null, IqParity.engineNeutrality(IqParity.runs(List.of(neutral)).get(0), tables, names));
    }

    @Test
    void anEnginePinToIqNeedsADecision() {
        List<SqlParity.Table> tables = List.of(new SqlParity.Table("t", List.of(new SqlParity.Column("id", "integer")),
                List.of()));
        IqParity.Case pinned = new IqParity.Case("lite/x.yaml", "a", "SELECT id FROM t", List.of("iq16-default"),
                "iq", null, null, null);
        String problem = IqParity.engineNeutrality(IqParity.runs(List.of(pinned)).get(0), tables, Set.of("t"));
        assertTrue(problem != null && problem.contains("pins engine=iq"), problem);
        IqParity.Case decided = new IqParity.Case("lite/x.yaml", "a", "SELECT id FROM t", List.of("iq16-default"),
                "iq", null, "D-S1", "[]");
        assertEquals(null, IqParity.engineNeutrality(IqParity.runs(List.of(decided)).get(0), tables, Set.of("t")));
    }

    @Test
    void anOperationThatCanRaiseBesideAndOrInWhereIsNotJudged() {
        java.util.function.BiFunction<String, String, String> order = (sql, skip) -> IqParity.evaluationOrder(
                IqParity.runs(List.of(new IqParity.Case("lite/x.yaml", "a", sql, List.of("iq16-default"), null, skip,
                        null, null))).get(0));
        assertTrue(order.apply("SELECT id FROM t WHERE id > 1 AND it + 1 > 0", null) != null);
        assertTrue(order.apply("SELECT id FROM t WHERE NOT (sm = 0 OR 100 / sm > 1)", null) != null);
        assertTrue(order.apply("SELECT id FROM t WHERE id = 1 OR NULLIF(sm, it) > 0", null) != null);
        // skipped as the decision says, or with nothing beside the operation: judged as usual
        assertEquals(null, order.apply("SELECT id FROM t WHERE id > 1 AND it + 1 > 0", IqParity.PLAN_DEPENDENT));
        assertEquals(null, order.apply("SELECT id FROM t WHERE it + 1 > 0", null));
        assertEquals(null, order.apply("SELECT id FROM t WHERE sm <> 0 AND sm != 1", null));
        // arithmetic of constants cannot raise per row
        assertEquals(null, order.apply("SELECT id FROM t WHERE id > 1 AND id = 2 + 3", null));
        // AND inside a CASE's condition is lazy in IQ too
        assertEquals(null, order.apply("SELECT id FROM t WHERE CASE WHEN id > 1 AND id = 2 THEN 1 END = 1", null));
    }

    @Test
    void aBothErrorWhoseIqCodeIsNotSqlAnywheresFails() {
        SqlParity.Rejected sa = new SqlParity.Rejected("", "SQLCODE -158, SQLSTATE 22003: Value 32768 out of range");
        RuntimeException wrong = new RuntimeException("#IQ{...}# at line 1: no column 'x' (IQ: SQLCODE -143,"
                + " SQLSTATE 52003)");
        assertEquals(SqlParity.Status.FAIL, IqParity.bothError(wrong, sa).status());
        RuntimeException right = new RuntimeException("#IQ{...}#: out of range for its type, SMALLINT (IQ: SQLCODE"
                + " -158, SQLSTATE 22003)");
        assertEquals(SqlParity.Status.BOTH_ERROR, IqParity.bothError(right, sa).status());
        // an error without IQ's code (a refusal, the database's own) is judged by being one
        assertEquals(SqlParity.Status.BOTH_ERROR, IqParity.bothError(new RuntimeException("Cannot divide 1 by zero"),
                sa).status());
    }

    @Test
    void aNumericCellCarriesSqlAnywheresScale() {
        SqlParity.Grid lite = new SqlParity.Grid(List.of("v"), List.of(List.of(new java.math.BigDecimal("1.50"))));
        assertTrue(IqParity.scale("numeric", 1, lite, 0) != null);
        assertEquals(null, IqParity.scale("numeric", 2, lite, 0));
        SqlParity.Grid whole = new SqlParity.Grid(List.of("v"), List.of(List.of(15L)));
        assertEquals(null, IqParity.scale("numeric", 0, whole, 0));
        assertEquals(null, IqParity.scale("integer", 0, lite, 0));
    }

    @Test
    void aTableNamedInFromIsItsLegendSource_asWritten() {
        Set<String> tables = Set.of("t", "u");
        assertEquals("SELECT [t].id FROM tb('e2e::DB.t') AS [t] WHERE id = 1",
                IqParity.rewrite("SELECT [t].id FROM [t] WHERE id = 1", tables, IqProfile.IQ16_DEFAULT));
        assertEquals("SELECT a.id FROM tb('e2e::DB.t') a, tb('e2e::DB.u') AS U",
                IqParity.rewrite("SELECT a.id FROM t a, U", tables, IqProfile.IQ16_DEFAULT));
        // under a TDS login "t" is a string, no table
        assertEquals("SELECT 1 FROM \"t\"", IqParity.rewrite("SELECT 1 FROM \"t\"", tables, IqProfile.IQ16_TDS));
    }
}
