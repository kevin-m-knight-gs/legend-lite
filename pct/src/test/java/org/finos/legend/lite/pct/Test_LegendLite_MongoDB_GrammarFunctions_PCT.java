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
    private static final MutableList<ExclusionSpecification> expectedFailures = Lists.mutable.with(
            // VALUE DIFFERENCES, measured
            one("meta::pure::functions::boolean::tests::equality::eq::testEqNonPrimitive_Function_1__Boolean_1_", "\"Assert failed\""),
            one("meta::pure::functions::boolean::tests::equality::equal::testEqualNonPrimitive_Function_1__Boolean_1_", "\"Assert failed\""),
            one("meta::pure::functions::math::tests::times::testDecimalTimes_Function_1__Boolean_1_", "\"\nexpected: 353791.470D\nactual:   353791.47D\""),
            one("meta::pure::functions::string::tests::plus::testPlusInCollect_Function_1__Boolean_1_", "\"\nexpected: ['Doe, Pierre', 'RoeDoe, Kevin']\nactual:   [', ', ', ']\""),
            one("meta::pure::functions::string::tests::plus::testPlusInIterate_Function_1__Boolean_1_", "\"\nexpected: 'names; Doe, Pierre; RoeDoe, Kevin; Some_LName, Andrew'\nactual:   'names; , ; , ; , '\""),
            // THE PLATFORM'S OWN (pinned on the DuckDB lane too, by the same cause)
            one("meta::pure::functions::boolean::tests::equality::eq::testEqPrimitiveExtension_Function_1__Boolean_1_", "\"unknown type 'meta::pure::functions::boolean::tests::equalitymodel::ExtendedInteger' in @meta::pure::functions::boolean::tests::equalitymodel::ExtendedInteger\""),
            one("meta::pure::functions::boolean::tests::equality::equal::testEqualPrimitiveExtension_Function_1__Boolean_1_", "\"unknown type 'meta::pure::functions::boolean::tests::equalitymodel::ExtendedInteger' in @meta::pure::functions::boolean::tests::equalitymodel::ExtendedInteger\""),
            one("meta::pure::functions::collection::tests::map::testMapRelationshipFromOneToOne_Function_1__Boolean_1_", "\"unbound variable '$address'\""),
            one("meta::pure::functions::collection::tests::range::testRangeStepError_Function_1__Boolean_1_", "\"Execution error message mismatch.\nThe actual message was \"Command execution failed on MongoDB server with error 34449 (Location34449): 'Failed to optimize pipeline :: caused by :: $range requires a non-zero step value'"),
            // MONGODB'S OWN, the server's error
            one("meta::pure::functions::collection::tests::filter::testFilterInstance_Function_1__Boolean_1_", "\"Command execution failed on MongoDB server with error 34471 (Location34471): 'PlanExecutor error during aggregation :: caused by :: $strLenCP requires a string argument, found: null'"),
            one("meta::pure::functions::collection::tests::first::testFirstComplex_Function_1__Boolean_1_", "\"Command execution failed on MongoDB server with error 241 (ConversionFailure): 'PlanExecutor error during aggregation :: caused by :: Unsupported conversion from object to string in $convert with no onError value'"),
            one("meta::pure::functions::collection::tests::map::testMapRelationshipFromManyToMany_Function_1__Boolean_1_", "\"Command execution failed on MongoDB server with error 241 (ConversionFailure): 'PlanExecutor error during aggregation :: caused by :: Unsupported conversion from object to string in $convert with no onError value'"),
            one("meta::pure::functions::collection::tests::map::testMapRelationshipFromManyToOne_Function_1__Boolean_1_", "\"Command execution failed on MongoDB server with error 241 (ConversionFailure): 'PlanExecutor error during aggregation :: caused by :: Unsupported conversion from object to string in $convert with no onError value'"),
            one("meta::pure::functions::collection::tests::range::testRange_Function_1__Boolean_1_", "\"Command execution failed on MongoDB server with error 28667 (Location28667): 'Expression $range takes at least 2 arguments, and at most 3, but 1 were passed in.'"),
            one("meta::pure::functions::lang::tests::compare::testDateCompare_Function_1__Boolean_1_", "\"Command execution failed on MongoDB server with error 40523 (Location40523): 'PlanExecutor error during aggregation :: caused by :: 'year' must evaluate to an integer in the range 1 to 9999, found 10999'"),
            one("meta::pure::functions::lang::tests::letFn::testAssignNewInstance_Function_1__Boolean_1_", "\"Command execution failed on MongoDB server with error 241 (ConversionFailure): 'PlanExecutor error during aggregation :: caused by :: Unsupported conversion from object to string in $convert with no onError value'"),
            // MONGODB'S OWN, refused by name
            one("meta::pure::functions::collection::tests::getAll::testBasic_Function_1__Boolean_1_", "\"a schema-qualified table (metamodel.classes) reached MongoDb: a collection has no schema (its database is the connection's)\""),
            // THE TDS TEXT ROUTE: a value the text does not carry (an empty string, a class instance, a List, a Map, an Any)
            one("meta::pure::functions::collection::tests::map::testMapInstance_Function_1__Boolean_1_", "\"Cannot cast a collection of size 0 to multiplicity [3]\""),
            one("meta::pure::functions::lang::tests::letFn::testLetChainedWithAnotherFunction_Function_1__Boolean_1_", "\"ERROR. Pure Type: 'meta::pure::metamodel::ModelElement' is not supported yet.\""),
            one("meta::pure::functions::math::tests::minus::testSingleMinusType_Function_1__Boolean_1_", "\"ERROR. Pure Type: 'meta::pure::metamodel::type::Type' is not supported yet.\""),
            one("meta::pure::functions::math::tests::plus::testSinglePlusType_Function_1__Boolean_1_", "\"ERROR. Pure Type: 'meta::pure::metamodel::type::Type' is not supported yet.\""),
            // THE READER: a cell typed by its text
            one("meta::pure::functions::lang::tests::letFn::testLetInsideIf_Function_1__Boolean_1_", "\"Not supported data type :'BOOLEAN_AS_BYTE' for Pure type: 'String'\""),
            one("meta::pure::functions::math::tests::plus::testLargePlus_Function_1__Boolean_1_", "\"Not supported data type :'DOUBLE' for Pure type: 'Integer'\""),
            one("meta::pure::functions::math::tests::times::testLargeTimes_Function_1__Boolean_1_", "\"Not supported data type :'DOUBLE' for Pure type: 'Integer'\"")
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
