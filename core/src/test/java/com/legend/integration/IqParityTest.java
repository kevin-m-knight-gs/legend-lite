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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code #IQ{...}#} island's parity cases on DuckDB ({@link IqParity}): every case of
 * {@code core/src/test/resources/iq-parity/lite} under each of its profiles, as an island over the schema's tables in
 * DuckDB, judged against SQL Anywhere 17's snapped answer (or the case's provisional decision), and held to the
 * ledger's {@code duckdb} column. The answers file is checked against the cases first: UNSNAPPED, STALE and GONE fail
 * the lane, as does a case pinning an engine its island does not run in.
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
                IqAnswers.sqlSha("SELECT 2", "iq16-default", schema), List.of(), List.of(), List.of(), null));
        answers.put("lite/x/b@iq16-default", new IqAnswers.Snap("lite/x/b", "iq16-default",
                IqAnswers.sqlSha("SELECT 1", "iq16-default", schema), List.of(), List.of(), List.of(), null));
        List<String> problems = IqAnswers.guards(IqParity.runs(List.of(c)), answers, schema);
        assertEquals(3, problems.size(), String.join("\n", problems));
        assertTrue(problems.get(0).startsWith("STALE: lite/x/a@iq16-default"), problems.get(0));
        assertTrue(problems.get(1).startsWith("UNSNAPPED: lite/x/a@iq16-tds"), problems.get(1));
        assertTrue(problems.get(2).startsWith("GONE: lite/x/b@iq16-default"), problems.get(2));
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
