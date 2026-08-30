// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.resolver;

import com.legend.Compiler;
import com.legend.compiler.NameResolver;
import com.legend.compiler.spec.SpecCompiler;
import com.legend.compiler.spec.typed.TypedSpec;
import com.legend.lowering.Lowerer;
import com.legend.sql.SqlQuery;
import com.legend.sql.dialect.DuckDb;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * §4AD P1 (NAV_ROUTING_PLACEMENT_ADDENDUM_4AD §6): VALUE-position
 * filtered navigations take the ROW-DROPPING placement — bare fanned
 * LEFT join, qualifier predicate hoisted to the frame's top WHERE
 * (the measured batch-0 cell; engine witnesses
 * testQualifierWithOperation / testTwoQualifiersWithOperation).
 * These are the R4 distinguishing witnesses: under the old in-target
 * (row-preserving) placement every no-match parent survived as a NULL
 * row and pure's null-skipping plus MINTED phantom values — each test
 * here FAILS on that emission and PASSES on this one.
 */
class ValueMapPlacementTest {

    private static final String MODEL = """
            Class m::Org { name: String[1]; nick: String[0..1];
              nick2: String[0..1]; children: m::Org[*]; }
            ###Relational
            Database s::DB (
              Table ORG (ID INTEGER PRIMARY KEY, PARENT_ID INTEGER,
                NAME VARCHAR(50), NICK VARCHAR(50), NICK2 VARCHAR(50))
              Join OrgChildren (ORG.ID = {target}.PARENT_ID)
            )
            ###Mapping
            Mapping m::M (
              *m::Org: Relational { ~mainTable [s::DB] ORG
                name: [s::DB] ORG.NAME,
                nick: [s::DB] ORG.NICK,
                nick2: [s::DB] ORG.NICK2,
                children: [s::DB] @OrgChildren }
            )
            ###Runtime
            Runtime m::RT { mappings: [m::M]; }
            """;

    private static Connection conn;

    @BeforeAll
    static void setUp() throws SQLException {
        conn = DriverManager.getConnection("jdbc:duckdb:");
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE ORG (ID INTEGER, PARENT_ID INTEGER,"
                    + " NAME VARCHAR, NICK VARCHAR, NICK2 VARCHAR)");
            // Alpha has children Beta (nick pair differs) and Gamma
            // (nick pair both NULL); Beta and Gamma are childless.
            st.execute("INSERT INTO ORG VALUES"
                    + " (1, NULL, 'Alpha', NULL, NULL),"
                    + " (2, 1, 'Beta', 'b', 'x'),"
                    + " (3, 1, 'Gamma', NULL, NULL)");
        }
    }

    @AfterAll
    static void tearDown() throws SQLException {
        conn.close();
    }

    private static String sqlOf(String query) {
        var ctx = Compiler.compileModel(MODEL);
        SpecCompiler specs = new SpecCompiler(ctx);
        List<TypedSpec> body = specs.typeQueryBody(
                NameResolver.resolveQuery(com.legend.testing.Own.spec(query)));
        List<TypedSpec> resolved = new StoreResolver(ctx, specs).resolve(body, null);
        SqlQuery plan = new Lowerer().lower(resolved);
        return new DuckDb().render(plan);
    }

    private List<String> exec(String sql) throws SQLException {
        List<String> rows = new ArrayList<>();
        try (Statement st = conn.createStatement();
                ResultSet rs = st.executeQuery(sql)) {
            int n = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                StringBuilder b = new StringBuilder();
                for (int i = 1; i <= n; i++) {
                    if (i > 1) {
                        b.append("|");
                    }
                    b.append(rs.getObject(i));
                }
                rows.add(b.toString());
            }
        }
        return rows;
    }

    private static int count(String sql, String kw) {
        int c = 0;
        for (int i = sql.indexOf(kw); i >= 0; i = sql.indexOf(kw, i + kw.length())) {
            c++;
        }
        return c;
    }

    @Test
    @DisplayName("value read + null-skipping plus: pred drops rows, no phantom mints")
    void valuePredDropsRows() throws SQLException {
        String sql = sqlOf("m::Org.all()->map(o|"
                + "$o.children->filter(c|$c.name == 'Beta')->toOne().name + 'T')"
                + "->from(m::M, m::RT)");
        // Alpha's Beta child survives; Beta/Gamma (childless) DROP at
        // the WHERE — under in-target parking they survived as NULL
        // rows and concat minted a phantom 'T' each (['BetaT','T','T']).
        assertEquals(List.of("BetaT"), exec(sql), sql);
        // INNER renders as bare JOIN (SqlSource.Kind)
        assertEquals(0, count(sql, "LEFT OUTER JOIN"), sql);
        assertEquals(1, count(sql, "JOIN"), sql);
    }

    @Test
    @DisplayName("multi-occurrence, different preds: join copies fork, preds AND one WHERE")
    void multiOccurrenceForksCopies() throws SQLException {
        String sql = sqlOf("m::Org.all()->map(o|"
                + "$o.children->filter(c|$c.name == 'Beta')->toOne().name"
                + " + $o.children->filter(c|$c.name == 'Gamma')->toOne().name)"
                + "->from(m::M, m::RT)");
        // the measured multi-occurrence cell (engine golden
        // testTwoQualifiersWithOperation): per-occurrence join copies,
        // ALL predicates conjoined in the ONE top WHERE
        assertEquals(List.of("BetaGamma"), exec(sql), sql);
        assertEquals(0, count(sql, "LEFT OUTER JOIN"), sql);
        assertEquals(2, count(sql, "JOIN"), sql);
        assertTrue(sql.contains("'Beta'") && sql.contains("'Gamma'"), sql);
    }

    @Test
    @DisplayName("multi-occurrence, EQUAL preds: one shared join identity")
    void equalPredsShareOneJoin() throws SQLException {
        String sql = sqlOf("m::Org.all()->map(o|"
                + "$o.children->filter(c|$c.name == 'Beta')->toOne().name"
                + " + $o.children->filter(c|$c.name == 'Beta')->toOne().name)"
                + "->from(m::M, m::RT)");
        assertEquals(List.of("BetaBeta"), exec(sql), sql);
        assertEquals(0, count(sql, "LEFT OUTER JOIN"), sql);
        assertEquals(1, count(sql, "JOIN"), sql);
    }

    @Test
    @DisplayName("injected conjunct keeps the filter-position equality rule (double-NULL)")
    void doubleNullConjunctRuleParity() throws SQLException {
        // risk-6 pin (addendum §6): the hoisted predicate lowers under
        // the SAME NullSemantics filter arm as any user filter — the
        // engine's own position-blind rule (nullSafeEqualsOperation
        // case 5: both operands nullable). Gamma's both-NULL nick pair
        // therefore MATCHES; Beta's ('b','x') does not.
        String sql = sqlOf("m::Org.all()->map(o|"
                + "$o.children->filter(c|$c.nick == $c.nick2)->toOne().name + 'T')"
                + "->from(m::M, m::RT)");
        assertEquals(List.of("GammaT"), exec(sql), sql);
    }
}
