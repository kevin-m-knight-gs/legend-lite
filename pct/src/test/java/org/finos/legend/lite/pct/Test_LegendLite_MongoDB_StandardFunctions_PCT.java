// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package org.finos.legend.lite.pct;

import junit.framework.Test;
import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.list.MutableList;
import org.finos.legend.pure.code.core.CoreStandardFunctionsCodeRepositoryProvider;
import org.finos.legend.pure.m3.pct.reports.config.PCTReportConfiguration;
import org.finos.legend.pure.m3.pct.reports.config.exclusion.ExclusionSpecification;
import org.finos.legend.pure.m3.pct.reports.model.Adapter;
import org.finos.legend.pure.m3.pct.shared.model.ReportScope;
import org.finos.legend.pure.runtime.java.interpreted.testHelper.PureTestBuilderInterpreted;
import static org.finos.legend.engine.test.shared.framework.PureTestHelperFramework.wrapSuite;

/**
 * The standard PCT suite on MongoDB (docs/MONGODB_PLAN_2026_10_16.md §6): the SAME tests as the DuckDB lane's, through the
 * same adapter, on the embedded MongoDB 8.0 ({@code LEGENDLITE_PCT_BACKEND=mongodb}, pct/BUILD.bazel), channel A
 * through the plan path (PctPlanRoute: every result crosses as Pure's TDS text), with MongoDB's OWN expected
 * failures -- every one named and pinned by the text it fails with, and a pinned test that starts passing fails the
 * run, so a fix is recorded by deleting its row.
 */
public class Test_LegendLite_MongoDB_StandardFunctions_PCT extends PCTReportConfiguration {

    private static final ReportScope reportScope = CoreStandardFunctionsCodeRepositoryProvider.standardFunctions;
    private static final Adapter adapter = LegendLitePCTReportProvider.LegendLiteAdapter;
    private static final String platform = "interpreted";

    // Pinned by a STABLE part of the message (the runner matches by containment): no per-run id, no source line.
    // First measured 2026-10-17 (MongoDB 8.0.17, the pinned server; channel A through the plan path, PctPlanRoute).
    // Re-measured 2026-10-17 after the burn-down (docs/MONGODB_PLAN_2026_10_16.md §11.4: windows, quantified
    // comparisons, the Variant's JSON text, lateral and as-of joins, the statistics, the long tail; the adapter
    // reading the value relation's cells by the function's return type).
    private static final MutableList<ExclusionSpecification> expectedFailures = Lists.mutable.with(
            // VALUE DIFFERENCES, measured
            one("meta::pure::functions::collection::tests::greatest::testGreatest_DateTime_Function_1__Boolean_1_", "\"\nexpected: %2025-02-10T20:10:20+0000\nactual:   %2025-02-10T20:10:20.000+0000\""),
            one("meta::pure::functions::collection::tests::greatest::testGreatest_Single_Function_1__Boolean_1_", "\"\nexpected: %2025-02-10T20:10:20+0000\nactual:   %2025-02-10T20:10:20.000+0000\""),
            one("meta::pure::functions::collection::tests::least::testLeast_DateTime_Function_1__Boolean_1_", "\"\nexpected: %2025-01-10T15:25:30+0000\nactual:   %2025-01-10T15:25:30.000+0000\""),
            one("meta::pure::functions::collection::tests::least::testLeast_Single_Function_1__Boolean_1_", "\"\nexpected: %2025-02-10T20:10:20+0000\nactual:   %2025-02-10T20:10:20.000+0000\""),
            one("meta::pure::functions::date::tests::max::testMax_DateTimeArray_Function_1__Boolean_1_", "\"\nexpected: %2025-02-10T20:10:20+0000\nactual:   %2025-02-10T20:10:20.000+0000\""),
            one("meta::pure::functions::date::tests::max::testMax_DateTime_Function_1__Boolean_1_", "\"\nexpected: %2025-02-10T20:10:20+0000\nactual:   %2025-02-10T20:10:20.000+0000\""),
            one("meta::pure::functions::date::tests::min::testMin_DateTimeArray_Function_1__Boolean_1_", "\"\nexpected: %2025-02-10T20:10:20+0000\nactual:   %2025-02-10T20:10:20.000+0000\""),
            one("meta::pure::functions::date::tests::min::testMin_DateTime_Function_1__Boolean_1_", "\"\nexpected: %2025-01-10T15:25:30+0000\nactual:   %2025-01-10T15:25:30.000+0000\""),
            one("meta::pure::functions::math::tests::max::testMax_NumbersArray_Function_1__Boolean_1_", "\"\nexpected: 1.0D\nactual:   1.0\""),
            one("meta::pure::functions::math::tests::min::testMin_NumbersArray_Function_1__Boolean_1_", "\"\nexpected: 1.0D\nactual:   1.0\""),
            // MONGODB'S OWN, refused by name
            one("meta::pure::functions::collection::tests::greatest::testGreatest_Number_Function_1__Boolean_1_", "\"a regular-expression replace reached MongoDb, which has no operator for one ($replaceAll replaces text)\""),
            one("meta::pure::functions::collection::tests::least::testLeast_Number_Function_1__Boolean_1_", "\"a regular-expression replace reached MongoDb, which has no operator for one ($replaceAll replaces text)\""),
            one("meta::pure::functions::hashCode::tests::testHashCode_Function_1__Boolean_1_", "\"hashCode reached MongoDb: its hashed index key is not Pure's hash\""),
            one("meta::pure::functions::math::hashCode::tests::testHashCodeAggregate_Function_1__Boolean_1_", "\"hashCode reached MongoDb: its hashed index key is not Pure's hash\""),
            one("meta::pure::functions::math::tests::max::testMax_Numbers_Function_1__Boolean_1_", "\"a regular-expression replace reached MongoDb, which has no operator for one ($replaceAll replaces text)\""),
            one("meta::pure::functions::math::tests::min::testMin_Numbers_Function_1__Boolean_1_", "\"a regular-expression replace reached MongoDb, which has no operator for one ($replaceAll replaces text)\""),
            one("meta::pure::functions::string::generation::tests::generateGuid::testGenerateGuidWithRelation_Function_1__Boolean_1_", "\"generateGuid reached MongoDb, whose pipeline has no uuid operator\""),
            one("meta::pure::functions::string::generation::tests::generateGuid::testGenerateGuid_Function_1__Boolean_1_", "\"generateGuid reached MongoDb, whose pipeline has no uuid operator\"")
    );

    public static Test suite() {
        return PctCensusGate.wrap("Standard", wrapSuite(
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
