// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.exec;

import com.legend.compiler.spec.typed.TypedSpec;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F1.5 — the host-channel dispatch predicate, PINNED (Charter clause 3:
 * forbid the PROVENANCE, not the arm). {@code wantsHostEval} gates an
 * 894-line interpreter and has collapsed the corpus sweep twice by two
 * different mechanisms (2096→408 routing-on-containment; 2091→2013).
 * These pins hold the predicate's admission surface exactly where the
 * F0.3 census measured it: chains BOTTOMING at executeInDb / store-nav,
 * the five curated host constructions, and fetchDb containment — and
 * NOTHING else. The negative pins are the load-bearing half: a query
 * chain, a literal, and an executeInDb buried in a NON-bottom position
 * must never route host — re-wiring the executeInDb READ path into
 * chainBottom (audit A9's mechanism) flips the buried-call pin red.
 */
class HostChannelPredicateTest {

    private static final String MODEL = "Class model::Person { name: "
            + "meta::pure::metamodel::type::String[1]; }\n";

    private static final String CONN =
            "^meta::external::store::relational::runtime"
            + "::TestDatabaseConnection(type="
            + "meta::relational::runtime::DatabaseType.H2)";

    private static TypedSpec typed(String query) {
        return com.legend.Compiler.compileQuery(MODEL, query);
    }

    // ---- admitted shapes (must route host) ------------------------------

    @Test
    void executeInDbBottomRoutesHost() {
        TypedSpec n = typed("meta::relational::metamodel::execute"
                + "::executeInDb('select 1', " + CONN + ", 0, 100)"
                + ".rows->size()");
        assertTrue(HostEval.wantsHostEval(n),
                "a chain BOTTOMING at executeInDb is the host channel's"
                        + " charter case");
    }

    @Test
    void fetchDbContainmentRoutesHost() {
        TypedSpec n = typed("meta::relational::metamodel::execute"
                + "::fetchDbColumnsMetaData(" + CONN + ", 'S', 'T', '%')"
                + ".rows->size()");
        assertTrue(HostEval.wantsHostEval(n),
                "fetchDb anywhere routes: its only corpus shapes are"
                        + " grid reads");
    }

    @Test
    void curatedHostConstructionRoutesHost() {
        TypedSpec n = typed(
                "^meta::relational::metamodel::Literal(value='x')");
        assertTrue(HostEval.wantsHostEval(n),
                "the five curated metamodel constructions are the host"
                        + " channel's typeInference vocabulary");
    }

    @Test
    void curatedConstructionSetIsPinned() {
        // F1.11: the ADMISSION SET itself is pinned — widening it routes
        // new shapes host-side with no behavioral pin tripping (the
        // "any native class stole 21 constructions" incident). Growing
        // this set is a Charter clause-3/4 decision, not an edit.
        org.junit.jupiter.api.Assertions.assertEquals(
                java.util.Set.of(
                        "meta::relational::metamodel::DynaFunction",
                        "meta::relational::metamodel::Literal",
                        "meta::relational::metamodel::Alias",
                        "meta::relational::functions::pureToSqlQuery"
                                + "::metamodel::FreeMarkerOperationHolder",
                        "meta::relational::functions::pureToSqlQuery"
                                + "::metamodel::VarPlaceHolder"),
                HostEval.HOST_CONSTRUCTION_CLASSES);
    }

    // ---- refused shapes (must NEVER route host) -------------------------

    @Test
    void bareLiteralDoesNotRouteHost() {
        assertFalse(HostEval.wantsHostEval(typed("'hi'")));
        assertFalse(HostEval.wantsHostEval(typed("1 + 2")));
    }

    @Test
    void queryChainDoesNotRouteHost() {
        assertFalse(HostEval.wantsHostEval(
                typed("model::Person.all()->filter(p|$p.name == 'x')")),
                "query values are the SQL pipeline's — the tenet's line");
    }

    @Test
    void uncuratedConstructionDoesNotRouteHost() {
        // an uncurated ^Class(...) stays on the compile-to-SQL path —
        // "any native class" once stole 21 sqlDialectTranslation
        // constructions from the K path (the gate caught it)
        TypedSpec n = typed("^model::Person(name='x')");
        assertFalse(HostEval.wantsHostEval(n),
                "constructions outside the curated set stay on the"
                        + " compile-to-SQL path");
    }

    @Test
    void buriedExecuteInDbDoesNotRouteHost() {
        // executeInDb in a NON-bottom position (an argument of the
        // chain's bottom) must not route: "routing on containment
        // collapsed modelJoin/testDataGeneration" — and audit A9's
        // re-wire (the executeInDb READ path into chainBottom) would
        // flip exactly this pin.
        TypedSpec n = typed("'prefix' + meta::relational::metamodel"
                + "::execute::executeInDb('select 1', " + CONN
                + ", 0, 100).rows->size()->toString()");
        assertFalse(HostEval.wantsHostEval(n),
                "executeInDb inside an argument tree is the SQL"
                        + " pipeline's business — containment must not"
                        + " route (the 2096->408 collapse)");
    }
}
