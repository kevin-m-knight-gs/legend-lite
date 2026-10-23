// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.exec;

import com.legend.Compiler;
import com.legend.TypedQuery;
import com.legend.database.Databases;
import com.legend.executionplan.ExecutionPlan;
import com.legend.plan.QueryPlan;
import com.legend.testbackend.TestBackends;
import com.legend.testcases.PlanCases;
import com.legend.testcases.PlanRoundTrip;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A mixed selection's winner as a tag plus typed slots (docs/RESULTS_AND_RENDERERS_2026_10_22.md §5a, 2026-10-23):
 * the plan's column is {@code TAGGED<kind slot, ...>}, the statement projects the tag and one column per slot and
 * spells no value, the typed seam reads the winner in its own kind, and the text outputs print it as that kind.
 * On the platform's own engine.
 */
class TaggedSelectionTest {

    private static final String MODEL = """
            ###Relational
            Database s::DB ( Table T ( ID INTEGER PRIMARY KEY ) )
            ###Connection
            RelationalDatabaseConnection s::Conn
            {
                store: s::DB;
                type: %s;
                specification: LocalH2 { };
                auth: DefaultH2;
            }
            ###Runtime
            Runtime s::RT { mappings: []; connections: [ s::DB: [ c1: s::Conn ] ]; }
            """.formatted(Databases.PLATFORM.name());

    private static TypedQuery query(String q) {
        return Compiler.query(Compiler.compileModel(MODEL), q);
    }

    private static Object value(Connection c, String q) throws Exception {
        QueryPlan plan = query(q).plan("s::RT");
        return JdbcDecodeCases.row(c, Databases.PLATFORM, plan.sql(), plan.columns()).get(0);
    }

    private static String tdsText(Connection c, String q) throws Exception {
        ExecutionPlan plan = query(q).executionPlan("s::RT", TypedQuery.Output.TDS_TEXT);
        PlanRoundTrip.assertRoundTrips(plan, q);
        return PlanCases.run(plan, c);
    }

    @Test
    void thePlanCarriesTheTagAndItsSlots_andSpellsNoValue() {
        QueryPlan plan = query("|[1, 2.5, 3.0D]->max()").plan("s::RT");
        assertEquals(1, plan.columns().size(), plan.sql());
        ExecutionPlan.Column value = plan.columns().get(0);
        assertEquals("Number", value.type());
        assertEquals("TAGGED<Integer BIGINT, Float DOUBLE, Decimal DECIMAL(2,1)>", value.slot());
        String sql = plan.sql();
        for (String column : List.of("value__0", "value__1", "value__2")) {
            assertTrue(sql.contains(column), sql);
        }
        // the spelling carrier's machinery is gone: no text of a candidate, no literal cast
        assertFalse(sql.contains("regexp"), sql);
        assertFalse(sql.contains("VARCHAR"), sql);
        assertTrue(sql.length() < 700, sql.length() + " characters: " + sql);
    }

    @Test
    void theWinnerIsReadInItsOwnKind() throws Exception {
        try (Connection c = TestBackends.platform().open()) {
            assertEquals(new BigDecimal("3.0"), value(c, "|[1, 2.5, 3.0D]->max()"));
            assertEquals(2.5d, value(c, "|[1, 2.5]->max()"));
            assertEquals(3L, value(c, "|[1, 2.5, 3]->max()"));
            assertEquals(1L, value(c, "|[1, 2.5]->min()"));
            assertEquals(2.5d, value(c, "|greatest([1, 2.5])"));
            assertEquals(1L, value(c, "|least([1, 2.5])"));
            // mode keeps the last of the winning run, as Pure's does
            assertEquals(2.5d, value(c, "|[1, 2, 2.5, 2.5]->mode()"));
            // ties keep the first element: Pure's max of [3.0, 3] is the Float
            assertEquals(3.0d, value(c, "|[3.0, 3]->max()"));
        }
    }

    @Test
    void theTextOutputsPrintTheWinnerAsItsKind() throws Exception {
        try (Connection c = TestBackends.platform().open()) {
            assertEquals("value:Number[1]\n3.0D", tdsText(c, "|[1, 2.5, 3.0D]->max()"));
            assertEquals("value:Number[1]\n2.5", tdsText(c, "|[1, 2.5]->max()"));
            assertEquals("value:Number[1]\n3", tdsText(c, "|[1, 2.5, 3]->max()"));
            // a String value's TDS text is quoted whole, as the reader reads a value
            assertEquals("value:String[1]\n'3'", tdsText(c, "|[1, 2.5, 3]->max()->toString()"));
            assertEquals("value:String[1]\n'2.5'", tdsText(c, "|[1, 2.5]->max()->toString()"));
            assertEquals("value:String[1]\n'3.0D'", tdsText(c, "|[1, 2.5, 3.0D]->max()->toString()"));
            // compared on its comparable channel
            assertEquals("value:Boolean[1]\ntrue", tdsText(c, "|[1, 2.5]->max() < 3"));
            assertEquals("value:Boolean[1]\ntrue", tdsText(c, "|[1, 2.5]->max() == 2.5"));
        }
    }
}
