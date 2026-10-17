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
    private static final MutableList<ExclusionSpecification> expectedFailures = Lists.mutable.with(
            // VALUE DIFFERENCES, measured
            one("meta::pure::functions::collection::tests::greatest::testGreatest_DateTime_Function_1__Boolean_1_", "\"\nexpected: %2025-02-10T20:10:20+0000\nactual:   %2025-02-10T20:10:20.000+0000\""),
            one("meta::pure::functions::collection::tests::greatest::testGreatest_Number_Function_1__Boolean_1_", "\"\nexpected: 2\nactual:   2.0\""),
            one("meta::pure::functions::collection::tests::greatest::testGreatest_Single_Function_1__Boolean_1_", "\"\nexpected: %2025-02-10T20:10:20+0000\nactual:   %2025-02-10T20:10:20.000+0000\""),
            one("meta::pure::functions::collection::tests::least::testLeast_DateTime_Function_1__Boolean_1_", "\"\nexpected: %2025-01-10T15:25:30+0000\nactual:   %2025-01-10T15:25:30.000+0000\""),
            one("meta::pure::functions::collection::tests::least::testLeast_Single_Function_1__Boolean_1_", "\"\nexpected: %2025-02-10T20:10:20+0000\nactual:   %2025-02-10T20:10:20.000+0000\""),
            one("meta::pure::functions::date::tests::max::testMax_DateTimeArray_Function_1__Boolean_1_", "\"\nexpected: %2025-02-10T20:10:20+0000\nactual:   %2025-02-10T20:10:20.000+0000\""),
            one("meta::pure::functions::date::tests::max::testMax_DateTime_Function_1__Boolean_1_", "\"\nexpected: %2025-02-10T20:10:20+0000\nactual:   %2025-02-10T20:10:20.000+0000\""),
            one("meta::pure::functions::date::tests::min::testMin_DateTimeArray_Function_1__Boolean_1_", "\"\nexpected: %2025-02-10T20:10:20+0000\nactual:   %2025-02-10T20:10:20.000+0000\""),
            one("meta::pure::functions::date::tests::min::testMin_DateTime_Function_1__Boolean_1_", "\"\nexpected: %2025-01-10T15:25:30+0000\nactual:   %2025-01-10T15:25:30.000+0000\""),
            one("meta::pure::functions::math::tests::max::testMax_Numbers_Function_1__Boolean_1_", "\"\nexpected: 2\nactual:   2.0\""),
            // THE READER: a cell typed by its text
            one("meta::pure::functions::collection::tests::greatest::testGreatest_Empty_Function_1__Boolean_1_", "\"Not supported data type :'STRING' for Pure type: 'Float'\""),
            one("meta::pure::functions::collection::tests::least::testLeast_Empty_Function_1__Boolean_1_", "\"Not supported data type :'STRING' for Pure type: 'Float'\""),
            one("meta::pure::functions::math::tests::max::testMax_FloatsArray_Function_1__Boolean_1_", "\"Not supported data type :'STRING' for Pure type: 'Float'\""),
            one("meta::pure::functions::math::tests::max::testMax_NumbersArray_Function_1__Boolean_1_", "\"Not supported data type :'STRING' for Pure type: 'Number'\""),
            one("meta::pure::functions::math::tests::min::testMin_FloatsArray_Function_1__Boolean_1_", "\"Not supported data type :'STRING' for Pure type: 'Float'\""),
            one("meta::pure::functions::math::tests::min::testMin_NumbersArray_Function_1__Boolean_1_", "\"Not supported data type :'STRING' for Pure type: 'Number'\""),
            // MONGODB'S OWN, refused by name
            one("meta::pure::functions::collection::tests::least::testLeast_Number_Function_1__Boolean_1_", "\"a regular-expression replace reached MongoDb, which has no operator for one ($replaceAll replaces text)\""),
            one("meta::pure::functions::date::tests::max::testExtendMaxDate_Function_1__Boolean_1_", "\"a window function reached MongoDb: $setWindowFields is not shaped yet\""),
            one("meta::pure::functions::date::tests::min::testExtendMinDate_Function_1__Boolean_1_", "\"a window function reached MongoDb: $setWindowFields is not shaped yet\""),
            one("meta::pure::functions::date::tests::timeBucket::dateTime::testTimeBucketDays_Function_1__Boolean_1_", "\"timeBucket reached MongoDb: $dateTrunc's bin size is not mapped to the engine's bucket origin yet\""),
            one("meta::pure::functions::date::tests::timeBucket::dateTime::testTimeBucketHours_Function_1__Boolean_1_", "\"timeBucket reached MongoDb: $dateTrunc's bin size is not mapped to the engine's bucket origin yet\""),
            one("meta::pure::functions::date::tests::timeBucket::dateTime::testTimeBucketMinutes_Function_1__Boolean_1_", "\"timeBucket reached MongoDb: $dateTrunc's bin size is not mapped to the engine's bucket origin yet\""),
            one("meta::pure::functions::date::tests::timeBucket::dateTime::testTimeBucketMonths_Function_1__Boolean_1_", "\"timeBucket reached MongoDb: $dateTrunc's bin size is not mapped to the engine's bucket origin yet\""),
            one("meta::pure::functions::date::tests::timeBucket::dateTime::testTimeBucketSeconds_Function_1__Boolean_1_", "\"timeBucket reached MongoDb: $dateTrunc's bin size is not mapped to the engine's bucket origin yet\""),
            one("meta::pure::functions::date::tests::timeBucket::dateTime::testTimeBucketWeeks_Function_1__Boolean_1_", "\"timeBucket reached MongoDb: $dateTrunc's bin size is not mapped to the engine's bucket origin yet\""),
            one("meta::pure::functions::date::tests::timeBucket::dateTime::testTimeBucketYears_Function_1__Boolean_1_", "\"timeBucket reached MongoDb: $dateTrunc's bin size is not mapped to the engine's bucket origin yet\""),
            one("meta::pure::functions::date::tests::timeBucket::strictDate::testTimeBucketDays_Function_1__Boolean_1_", "\"timeBucket reached MongoDb: $dateTrunc's bin size is not mapped to the engine's bucket origin yet\""),
            one("meta::pure::functions::date::tests::timeBucket::strictDate::testTimeBucketMonths_Function_1__Boolean_1_", "\"timeBucket reached MongoDb: $dateTrunc's bin size is not mapped to the engine's bucket origin yet\""),
            one("meta::pure::functions::date::tests::timeBucket::strictDate::testTimeBucketWeeks_Function_1__Boolean_1_", "\"timeBucket reached MongoDb: $dateTrunc's bin size is not mapped to the engine's bucket origin yet\""),
            one("meta::pure::functions::date::tests::timeBucket::strictDate::testTimeBucketYears_Function_1__Boolean_1_", "\"timeBucket reached MongoDb: $dateTrunc's bin size is not mapped to the engine's bucket origin yet\""),
            one("meta::pure::functions::hashCode::tests::testHashCode_Function_1__Boolean_1_", "\"hashCode reached MongoDb: its hashed index key is not Pure's hash\""),
            one("meta::pure::functions::math::hashCode::tests::testHashCodeAggregate_Function_1__Boolean_1_", "\"hashCode reached MongoDb: its hashed index key is not Pure's hash\""),
            one("meta::pure::functions::math::tests::average::testAverage_Floats_Relation_Window_Function_1__Boolean_1_", "\"a window function reached MongoDb: $setWindowFields is not shaped yet\""),
            one("meta::pure::functions::math::tests::average::testAverage_Integers_Relation_Window_Function_1__Boolean_1_", "\"a window function reached MongoDb: $setWindowFields is not shaped yet\""),
            one("meta::pure::functions::math::tests::average::testAverage_Numbers_Relation_Window_Function_1__Boolean_1_", "\"a window function reached MongoDb: $setWindowFields is not shaped yet\""),
            one("meta::pure::functions::math::tests::average::testAverage_Round_Integers_Relation_Window_Function_1__Boolean_1_", "\"a window function reached MongoDb: $setWindowFields is not shaped yet\""),
            one("meta::pure::functions::math::tests::bitShiftLeft::testBitShiftLeft_UpTo62Bits_Function_1__Boolean_1_", "\"BIT_SHIFT_LEFT reached MongoDb, which has no bit-shift operator\""),
            one("meta::pure::functions::math::tests::bitShiftRight::testBitShiftRight_UpTo62Bits_Function_1__Boolean_1_", "\"BIT_SHIFT_RIGHT reached MongoDb, which has no bit-shift operator\""),
            one("meta::pure::functions::math::tests::corr::testCorr_Function_1__Boolean_1_", "\"CORR reached MongoDb: a two-column statistic is not shaped yet\""),
            one("meta::pure::functions::math::tests::corr::testSimpleWindowCorr_Function_1__Boolean_1_", "\"a window function reached MongoDb: $setWindowFields is not shaped yet\""),
            one("meta::pure::functions::math::tests::covarPopulation::testCovarPopulation_Function_1__Boolean_1_", "\"COVAR_POP reached MongoDb: a two-column statistic is not shaped yet\""),
            one("meta::pure::functions::math::tests::covarPopulation::testSimpleWindowCovarPopulation_Function_1__Boolean_1_", "\"a window function reached MongoDb: $setWindowFields is not shaped yet\""),
            one("meta::pure::functions::math::tests::covarSample::testCovarSample_Function_1__Boolean_1_", "\"COVAR_SAMP reached MongoDb: a two-column statistic is not shaped yet\""),
            one("meta::pure::functions::math::tests::covarSample::testSimpleWindowCovarSample_Function_1__Boolean_1_", "\"a window function reached MongoDb: $setWindowFields is not shaped yet\""),
            one("meta::pure::functions::math::tests::max::testMax_Floats_Relation_Window_Function_1__Boolean_1_", "\"a window function reached MongoDb: $setWindowFields is not shaped yet\""),
            one("meta::pure::functions::math::tests::max::testMax_Integers_Relation_Window_Function_1__Boolean_1_", "\"a window function reached MongoDb: $setWindowFields is not shaped yet\""),
            one("meta::pure::functions::math::tests::max::testMax_Numbers_Relation_Window_Function_1__Boolean_1_", "\"a window function reached MongoDb: $setWindowFields is not shaped yet\""),
            one("meta::pure::functions::math::tests::maxBy::testSimpleGroupByMaxBy_Function_1__Boolean_1_", "\"ARG_MAX reached MongoDb: not shaped yet\""),
            one("meta::pure::functions::math::tests::median::testMedian_Floats_Relation_Window_Function_1__Boolean_1_", "\"a window function reached MongoDb: $setWindowFields is not shaped yet\""),
            one("meta::pure::functions::math::tests::median::testMedian_Integers_Relation_Window_Function_1__Boolean_1_", "\"a window function reached MongoDb: $setWindowFields is not shaped yet\""),
            one("meta::pure::functions::math::tests::median::testMedian_Numbers_Relation_Window_Function_1__Boolean_1_", "\"a window function reached MongoDb: $setWindowFields is not shaped yet\""),
            one("meta::pure::functions::math::tests::min::testMin_Floats_Relation_Window_Function_1__Boolean_1_", "\"a window function reached MongoDb: $setWindowFields is not shaped yet\""),
            one("meta::pure::functions::math::tests::min::testMin_Integers_Relation_Window_Function_1__Boolean_1_", "\"a window function reached MongoDb: $setWindowFields is not shaped yet\""),
            one("meta::pure::functions::math::tests::min::testMin_Numbers_Function_1__Boolean_1_", "\"a regular-expression replace reached MongoDb, which has no operator for one ($replaceAll replaces text)\""),
            one("meta::pure::functions::math::tests::min::testMin_Numbers_Relation_Window_Function_1__Boolean_1_", "\"a window function reached MongoDb: $setWindowFields is not shaped yet\""),
            one("meta::pure::functions::math::tests::minBy::testSimpleGroupByMinBy_Function_1__Boolean_1_", "\"ARG_MIN reached MongoDb: not shaped yet\""),
            one("meta::pure::functions::math::tests::mode::testMode_Float_Function_1__Boolean_1_", "\"a list's mode reached MongoDb: not shaped yet\""),
            one("meta::pure::functions::math::tests::mode::testMode_Floats_Relation_Aggregate_Function_1__Boolean_1_", "\"mode reached MongoDb: not shaped yet\""),
            one("meta::pure::functions::math::tests::mode::testMode_Floats_Relation_Window_Function_1__Boolean_1_", "\"a window function reached MongoDb: $setWindowFields is not shaped yet\""),
            one("meta::pure::functions::math::tests::mode::testMode_Integer_Function_1__Boolean_1_", "\"a list's mode reached MongoDb: not shaped yet\""),
            one("meta::pure::functions::math::tests::mode::testMode_Integer_Relation_Aggregate_Function_1__Boolean_1_", "\"mode reached MongoDb: not shaped yet\""),
            one("meta::pure::functions::math::tests::mode::testMode_Integer_Relation_Window_Function_1__Boolean_1_", "\"a window function reached MongoDb: $setWindowFields is not shaped yet\""),
            one("meta::pure::functions::math::tests::mode::testMode_Number_Function_1__Boolean_1_", "\"a list's mode reached MongoDb: not shaped yet\""),
            one("meta::pure::functions::math::tests::mode::testMode_Number_Relation_Aggregate_Function_1__Boolean_1_", "\"mode reached MongoDb: not shaped yet\""),
            one("meta::pure::functions::math::tests::mode::testMode_Number_Relation_Window_Function_1__Boolean_1_", "\"a window function reached MongoDb: $setWindowFields is not shaped yet\""),
            one("meta::pure::functions::math::tests::percentile::testPercentile_Relation_Window_Function_1__Boolean_1_", "\"a window function reached MongoDb: $setWindowFields is not shaped yet\""),
            one("meta::pure::functions::math::tests::stdDev::testSimpleWindowStandardDeviationPopulation_Function_1__Boolean_1_", "\"a window function reached MongoDb: $setWindowFields is not shaped yet\""),
            one("meta::pure::functions::math::tests::stdDev::testSimpleWindowStandardDeviationSample_Function_1__Boolean_1_", "\"a window function reached MongoDb: $setWindowFields is not shaped yet\""),
            one("meta::pure::functions::math::tests::sum::testSum_Floats_Relation_Window_Function_1__Boolean_1_", "\"a window function reached MongoDb: $setWindowFields is not shaped yet\""),
            one("meta::pure::functions::math::tests::sum::testSum_Integers_Relation_Window_Function_1__Boolean_1_", "\"a window function reached MongoDb: $setWindowFields is not shaped yet\""),
            one("meta::pure::functions::math::tests::sum::testSum_Numbers_Relation_Window_Function_1__Boolean_1_", "\"a window function reached MongoDb: $setWindowFields is not shaped yet\""),
            one("meta::pure::functions::math::tests::variance::testSimpleWindowVariancePopulation_Function_1__Boolean_1_", "\"a window function reached MongoDb: $setWindowFields is not shaped yet\""),
            one("meta::pure::functions::math::tests::variance::testSimpleWindowVarianceSample_Function_1__Boolean_1_", "\"a window function reached MongoDb: $setWindowFields is not shaped yet\""),
            one("meta::pure::functions::math::tests::zScore::testZScore_FloatValues_Function_1__Boolean_1_", "\"a window function reached MongoDb: $setWindowFields is not shaped yet\""),
            one("meta::pure::functions::math::tests::zScore::testZScore_Function_1__Boolean_1_", "\"a window function reached MongoDb: $setWindowFields is not shaped yet\""),
            one("meta::pure::functions::math::tests::zScore::testZScore_SingleValuePartition_Function_1__Boolean_1_", "\"a window function reached MongoDb: $setWindowFields is not shaped yet\""),
            one("meta::pure::functions::string::generation::tests::generateGuid::testGenerateGuidWithRelation_Function_1__Boolean_1_", "\"generateGuid reached MongoDb, whose pipeline has no uuid operator\""),
            one("meta::pure::functions::string::generation::tests::generateGuid::testGenerateGuid_Function_1__Boolean_1_", "\"generateGuid reached MongoDb, whose pipeline has no uuid operator\""),
            one("meta::pure::functions::tests::date::testDayOfWeek_Function_1__Boolean_1_", "\"a date format with a WEEKDAY_NAME part reached MongoDb, whose $dateToString has no code for it\""),
            one("meta::pure::functions::tests::date::testDayOfWeek_Relation_Function_1__Boolean_1_", "\"a date format with a WEEKDAY_NAME part reached MongoDb, whose $dateToString has no code for it\""),
            // MONGODB'S OWN, the server's error
            one("meta::pure::functions::math::tests::percentile::testPercentile_Function_1__Boolean_1_", "\"Command execution failed on MongoDB server with error 28667 (Location28667): 'Expression $range takes at least 2 arguments, and at most 3, but 1 were passed in.'")
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
