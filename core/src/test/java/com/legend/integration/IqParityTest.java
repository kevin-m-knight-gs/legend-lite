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
import static org.junit.jupiter.api.Assertions.assertThrows;
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
 *
 * <p>It also pins the showcase's SQL (docs/IQ_SQL_SHOWCASE.md): {@link #theShowcasePlansItsPinnedSql}.
 */
class IqParityTest {

    /** The showcase's query: {@code lite/showcase.yaml}'s main case, judged by SQL Anywhere above like every case. */
    private static final String SHOWCASE = "lite/showcase/showcase_main@iq16-default";

    /** The connections the showcase's SQL is pinned for, each a file of {@code iq-parity/showcase/}: Snowflake's is
     *  rendered only (no Snowflake account is reached; planning never connects), DuckDB's is the SQL the lane runs. */
    private static final String[][] SHOWCASE_LANES = {
        {"snowflake", "type: Snowflake;\n    specification: Snowflake { name: 'IQ_SHOWCASE'; account: 'acct';"
                + " warehouse: 'wh'; region: 'us-east-1'; };\n    auth: SnowflakePublic { publicUserName: 'u';"
                + " privateKeyVaultReference: 'vault/k'; passPhraseVaultReference: 'vault/pp'; };"},
        {"duckdb", "type: DuckDB;\n    specification: DuckDB { };\n    auth: Test;"},
    };

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

    /**
     * IQ island, 2026-10-14: the showcase (docs/IQ_SQL_SHOWCASE.md). Its island -- {@link #SHOWCASE}, with the
     * variable it binds let before it, as the lane runs it -- compiled with a Snowflake connection and with a DuckDB
     * one plans the SQL pinned in {@code iq-parity/showcase/snowflake.sql} and {@code duckdb.sql}, byte for byte. Run
     * with {@code --jvm_flag=-Diq.showcase.update=<core/src/test/resources/iq-parity/showcase in the workspace>} to
     * rewrite them.
     */
    @Test
    void theShowcasePlansItsPinnedSql() throws Exception {
        List<SqlParity.Table> tables = IqParity.schema(Runfile.property("iq.parity.schema"));
        IqParity.Run main = IqParity.runs(IqParity.cases(Runfile.property("iq.parity.lite").getParent())).stream()
                .filter(r -> r.key().equals(SHOWCASE)).findFirst()
                .orElseThrow(() -> new AssertionError("no case " + SHOWCASE));
        String island = IqParity.island(main, IqParity.tableNames(tables));
        Path pinned = Runfile.property("iq.parity.showcase").getParent();
        String update = System.getProperty("iq.showcase.update");
        List<String> problems = new ArrayList<>();
        for (String[] lane : SHOWCASE_LANES) {
            String sql = com.legend.Compiler.query(com.legend.Compiler.compileModel(IqParity.model(tables, lane[1])),
                    island).plan(IqParity.RUNTIME).sql() + "\n";
            String file = lane[0] + ".sql";
            if (update != null) {
                Files.writeString(Path.of(update).resolve(file), sql, java.nio.charset.StandardCharsets.UTF_8);
                continue;
            }
            String expected = Files.readString(pinned.resolve(file), java.nio.charset.StandardCharsets.UTF_8);
            if (!expected.equals(sql)) {
                List<String> was = expected.lines().toList();
                List<String> now = sql.lines().toList();
                int line = 0;
                while (line < Math.min(was.size(), now.size()) && was.get(line).equals(now.get(line))) {
                    line++;
                }
                problems.add(file + " differs from line " + (line + 1) + ":\n  pinned: "
                        + (line < was.size() ? was.get(line) : "<end>") + "\n  now:    "
                        + (line < now.size() ? now.get(line) : "<end>"));
            }
        }
        assertTrue(problems.isEmpty(), "the showcase's SQL moved (if on purpose, rewrite it with"
                + " -Diq.showcase.update=<core/src/test/resources/iq-parity/showcase in the workspace> and update"
                + " docs/IQ_SQL_SHOWCASE.md):\n" + String.join("\n", problems));
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
        // IQ island, 2026-10-14 (review 3's M4): HAVING is a search condition too, and a SUM or an AVG can raise
        assertTrue(order.apply("SELECT k FROM g GROUP BY k HAVING MAX(v) <> 2147483647 AND MAX(v) + 1 > 0", null)
                != null);
        assertTrue(order.apply("SELECT k FROM g GROUP BY k HAVING COUNT(*) > 2 AND SUM(v) > 0", null) != null);
        assertEquals(null, order.apply("SELECT k FROM g GROUP BY k HAVING COUNT(*) > 2 AND MAX(v) > 0", null));
    }

    @Test
    void aTieAmongRoundedSortKeysIsNoTie() {
        // IQ island, 2026-10-14 (review 4's B2): .9990 and .9990 are shown alike, but SQL Anywhere sorts 997/998
        // before 998/999 -- the tie rule may not hide lite's other order where a select item is (or reads) a quotient
        // or an AVG
        java.util.function.BiFunction<String, String, List<String>> judge = (sql, type) -> {
            IqParity.Run run = IqParity.runs(List.of(new IqParity.Case("lite/x.yaml", "a", sql, List.of("iq16-default"),
                    null, null, null, null))).get(0);
            IqAnswers.Snap snap = new IqAnswers.Snap("lite/x/a", "iq16-default", "", List.of("id", "q"),
                    List.of("integer", type), List.of(List.of("2", "1"), List.of("1", "1"), List.of("3", "3")), null,
                    List.of(0, 0));
            SqlParity.Grid lite = new SqlParity.Grid(List.of("id", "q"), List.of(List.of(1L, 1L), List.of(2L, 1L),
                    List.of(3L, 3L)));
            SqlParity.Grid sa = ((SqlParity.Rows) IqAnswers.expected(run, snap)).grid();
            return IqParity.compare(run, sa, snap, lite, List.of("Integer", "Integer"));
        };
        assertFalse(judge.apply("SELECT id, v / w AS q FROM d ORDER BY q", "numeric").isEmpty());
        assertFalse(judge.apply("SELECT id, AVG(v) AS q FROM d GROUP BY id ORDER BY q", "numeric").isEmpty());
        assertFalse(judge.apply("SELECT id, q FROM (SELECT id, v / w AS q FROM d) e ORDER BY q", "numeric").isEmpty());
        // ties of values shown as they are sorted stay the plan's order
        assertEquals(List.of(), judge.apply("SELECT id, k AS q FROM d ORDER BY q", "integer"));
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

    /** IQ island, 2026-10-14 (M1e): a case's params are its host variables' values -- each a let before the island,
     *  each a SQL Anywhere variable of its type for the oracle (tools/iq-oracle/snap.py reads the same spelling; review
     *  5's M5: a real variable, no longer a constant written into the SQL), {@code null:<type>} a NULL -- and part of its
     *  answer's sqlSha, so editing them makes the answer STALE; an owner-qualified name is left as written. */
    @Test
    void aCaseBindsItsParamsAsLetsBeforeItsIsland() {
        List<IqParity.Param> params = IqParity.params("lo = 3; name = 'x'; amt = 0.50D; day = %2026-03-04;"
                + " stamp = %2026-03-04T05:06:07.5; rate = -1.5; n = null:Integer; neg = -0.50D");
        assertEquals(List.of("Integer", "String", "Decimal(38,2)", "StrictDate", "DateTime", "Float", "Integer",
                "Decimal"), params.stream().map(IqParity.Param::type).toList());
        assertEquals(List.of("BIGINT", "LONG VARCHAR", "NUMERIC(38,2)", "DATE", "TIMESTAMP", "DOUBLE", "BIGINT",
                "NUMERIC(38,2)"), params.stream().map(IqParity.Param::saType).toList());
        assertEquals(java.util.Arrays.asList("3", "'x'", "0.50", "CAST('2026-03-04' AS DATE)",
                "CAST('2026-03-04 05:06:07.5' AS TIMESTAMP)", "CAST(-1.5 AS DOUBLE)", null, "-0.50"),
                params.stream().map(IqParity.Param::saValue).toList());
        assertEquals("[]->cast(@Integer)", params.get(6).pure());
        // the two readers would read a backslash otherwise; names are SQL Anywhere's, distinct without case
        assertThrows(IllegalStateException.class, () -> IqParity.params("s = 'a\\x41b'"));
        assertThrows(IllegalStateException.class, () -> IqParity.params("lo = 1; LO = 2"));
        IqParity.Case c = new IqParity.Case("lite/x.yaml", "a", "SELECT id FROM t, DBA.u WHERE id > @lo",
                List.of("iq16-default"), null, null, null, null, "lo = 3");
        assertEquals("|let lo = 3; #IQ{SELECT id FROM tb('e2e::DB.t') AS t, DBA.u WHERE id > @lo}#;",
                IqParity.island(IqParity.runs(List.of(c)).get(0), Set.of("t", "u")));
        byte[] schema = "tables:\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Map<String, IqAnswers.Snap> answers = new LinkedHashMap<>();
        answers.put("lite/x/a@iq16-default", new IqAnswers.Snap("lite/x/a", "iq16-default",
                IqAnswers.sqlSha(c.sql(), "iq16-default", null, null, "lo = 4", schema), List.of(), List.of(),
                List.of(), null));
        List<String> problems = IqAnswers.guards(IqParity.runs(List.of(c)), answers, schema);
        assertEquals(1, problems.size(), String.join("\n", problems));
        assertTrue(problems.get(0).startsWith("STALE: lite/x/a@iq16-default"), problems.get(0));
    }
}
