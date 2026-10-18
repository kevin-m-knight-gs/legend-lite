// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package org.finos.legend.lite.pct;

import junit.framework.Test;
import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.list.MutableList;
import org.finos.legend.pure.m3.PlatformCodeRepositoryProvider;
import org.finos.legend.pure.m3.pct.reports.config.PCTReportConfiguration;
import org.finos.legend.pure.m3.pct.reports.config.exclusion.ExclusionSpecification;
import org.finos.legend.pure.m3.pct.reports.model.Adapter;
import org.finos.legend.pure.m3.pct.shared.model.ReportScope;
import org.finos.legend.pure.runtime.java.interpreted.testHelper.PureTestBuilderInterpreted;
import static org.finos.legend.engine.test.shared.framework.PureTestHelperFramework.wrapSuite;

/**
 * The grammar PCT suite on MongoDB (docs/MONGODB_PLAN_2026_10_16.md §6): the SAME tests as the DuckDB lane's, through the
 * same adapter, on the embedded MongoDB 8.0 ({@code LEGENDLITE_PCT_BACKEND=mongodb}, pct/BUILD.bazel), channel A
 * through the plan path (PctPlanRoute: every result crosses as Pure's TDS text), with MongoDB's OWN expected
 * failures -- every one named and pinned by the text it fails with, and a pinned test that starts passing fails the
 * run, so a fix is recorded by deleting its row.
 */
public class Test_LegendLite_MongoDB_GrammarFunctions_PCT extends PCTReportConfiguration {

    private static final ReportScope reportScope = PlatformCodeRepositoryProvider.grammarFunctions;
    private static final Adapter adapter = LegendLitePCTReportProvider.LegendLiteAdapter;
    private static final String platform = "interpreted";

    // Pinned by a STABLE part of the message (the runner matches by containment): no per-run id, no source line.
    // First measured 2026-10-17 (MongoDB 8.0.17, the pinned server; channel A through the plan path, PctPlanRoute).
    // Re-measured 2026-10-17 after the burn-down (docs/MONGODB_PLAN_2026_10_16.md §11.4: windows, quantified
    // comparisons, the Variant's JSON text, lateral and as-of joins, the statistics, the long tail; the adapter
    // reading the value relation's cells by the function's return type).
    // Re-measured 2026-10-17 after the cross-lane pass (§11.4: base64 and the string distances composed, a value
    // root's date precision, the day truncation typed DATE).
    // Re-measured 2026-10-18 after the text carrier (docs/PLAN_PATH_PROGRAM_2026_10_17.md §4, step 3): a class
    // instance, a List, a Pair, a Map, an Any value and a Type cross as Pure's own literal grammar; what stays
    // pinned is the identity tests (instances compared by reference, as on the DuckDB lane), a user element
    // referenced by its simple name, and an instance nested past the layout's depth.
    private static final MutableList<ExclusionSpecification> expectedFailures = Lists.mutable.with(
            // VALUE DIFFERENCES, measured
            one("meta::pure::functions::boolean::tests::equality::eq::testEqNonPrimitive_Function_1__Boolean_1_", "\"Assert failed\""),
            one("meta::pure::functions::boolean::tests::equality::equal::testEqualNonPrimitive_Function_1__Boolean_1_", "\"Assert failed\""),
            one("meta::pure::functions::math::tests::divide::testDecimalDivide_Function_1__Boolean_1_", "\"\nexpected: 0.5238095238095238\nactual:   0.5238095238095238095238095238095238\""),
            one("meta::pure::functions::math::tests::minus::testDecimalMinus_Function_1__Boolean_1_", "\"\nexpected: -4.0D\nactual:   -4.0\""),
            one("meta::pure::functions::math::tests::plus::testDecimalPlus_Function_1__Boolean_1_", "\"\nexpected: 6.0D\nactual:   6.0\""),
            one("meta::pure::functions::math::tests::times::testDecimalTimes_Function_1__Boolean_1_", "\"\nexpected: 353791.470D\nactual:   353791.47\""),
            // THE PLATFORM'S OWN (pinned on the DuckDB lane too, by the same cause)
            one("meta::pure::functions::boolean::tests::equality::eq::testEqPrimitiveExtension_Function_1__Boolean_1_", "\"unknown type 'meta::pure::functions::boolean::tests::equalitymodel::ExtendedInteger' in @meta::pure::functions::boolean::tests::equalitymodel::ExtendedInteger\""),
            one("meta::pure::functions::boolean::tests::equality::equal::testEqualPrimitiveExtension_Function_1__Boolean_1_", "\"unknown type 'meta::pure::functions::boolean::tests::equalitymodel::ExtendedInteger' in @meta::pure::functions::boolean::tests::equalitymodel::ExtendedInteger\""),
            one("meta::pure::functions::collection::tests::map::testMapRelationshipFromOneToOne_Function_1__Boolean_1_", "\"unbound variable '$address'\""),
            one("meta::pure::functions::collection::tests::range::testRangeStepError_Function_1__Boolean_1_", "\"Execution error message mismatch.\nThe actual message was \"Command execution failed on MongoDB server with error 34449 (Location34449): 'Failed to optimize pipeline :: caused by :: $range requires a non-zero step value'"),
            // THE IDENTITY TESTS: instances compared by reference, which no wire carries (the program's §4)
            one("meta::pure::functions::collection::tests::filter::testFilterInstance_Function_1__Boolean_1_", "instanceOf meta::pure::functions::collection::tests::model::CO_Person"),
            one("meta::pure::functions::collection::tests::first::testFirstComplex_Function_1__Boolean_1_", "instanceOf meta::pure::functions::collection::tests::model::CO_Person"),
            one("meta::pure::functions::collection::tests::map::testMapRelationshipFromManyToMany_Function_1__Boolean_1_", "instanceOf meta::pure::functions::collection::tests::map::model::M_Location"),
            one("meta::pure::functions::collection::tests::map::testMapRelationshipFromManyToOne_Function_1__Boolean_1_", "instanceOf meta::pure::functions::collection::tests::map::model::M_Address"),
            one("meta::pure::functions::lang::tests::compare::testDateCompare_Function_1__Boolean_1_", "\"Command execution failed on MongoDB server with error 40523 (Location40523): 'PlanExecutor error during aggregation :: caused by :: 'year' must evaluate to an integer in the range 1 to 9999, found 10999'"),
            // MONGODB'S OWN, refused by name
            one("meta::pure::functions::collection::tests::getAll::testBasic_Function_1__Boolean_1_", "\"a schema-qualified table (metamodel.classes) reached MongoDb: a collection has no schema (its database is the connection's)\""),
            // THE TDS TEXT ROUTE: a value the text does not carry (an empty string, a class instance, a List, a Map, an Any)
            one("meta::pure::functions::lang::tests::letFn::testLetChainedWithAnotherFunction_Function_1__Boolean_1_", "the text carrier's cell does not compile: TestClass has not been defined!")
    );

    public static Test suite() {
        return PctCensusGate.wrap("Grammar", wrapSuite(
                () -> true,
                () -> PureTestBuilderInterpreted.buildPCTTestSuite(reportScope, expectedFailures, adapter),
                () -> false,
                Lists.mutable.empty()));
    }

    @Override
    public MutableList<ExclusionSpecification> expectedFailures() {
        return expectedFailures;
    }

    @Override
    public ReportScope getReportScope() {
        return reportScope;
    }

    @Override
    public Adapter getAdapter() {
        return adapter;
    }

    @Override
    public String getPlatform() {
        return platform;
    }
}
