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
 * The essential PCT suite on MongoDB (docs/MONGODB_PLAN_2026_10_16.md §6): the SAME tests as the DuckDB lane's, through the
 * same adapter, on the embedded MongoDB 8.0 ({@code LEGENDLITE_PCT_BACKEND=mongodb}, pct/BUILD.bazel), channel A
 * through the plan path (PctPlanRoute: every result crosses as Pure's TDS text), with MongoDB's OWN expected
 * failures -- every one named and pinned by the text it fails with, and a pinned test that starts passing fails the
 * run, so a fix is recorded by deleting its row.
 */
public class Test_LegendLite_MongoDB_EssentialFunctions_PCT extends PCTReportConfiguration {

    private static final ReportScope reportScope = PlatformCodeRepositoryProvider.essentialFunctions;
    private static final Adapter adapter = LegendLitePCTReportProvider.LegendLiteAdapter;
    private static final String platform = "interpreted";

    // Pinned by a STABLE part of the message (the runner matches by containment): no per-run id, no source line.
    // First measured 2026-10-17 (MongoDB 8.0.17, the pinned server; channel A through the plan path, PctPlanRoute).
    private static final MutableList<ExclusionSpecification> expectedFailures = Lists.mutable.with(
            // THE PLATFORM'S OWN (pinned on the DuckDB lane too, by the same cause)
            one("meta::pure::functions::collection::tests::at::testAtError_Function_1__Boolean_1_", "\"Execution error column mismatch. Actual: 23 where expected: 37\""),
            one("meta::pure::functions::collection::tests::fold::testFoldFiltering_Function_1__Boolean_1_", "\"'otherNames' is not a known class, mapping, runtime, connection, or database — user elements in a query need a fully qualified name\""),
            one("meta::pure::functions::collection::tests::fold::testFoldToMany_Function_1__Boolean_1_", "\"'otherNames' is not a known class, mapping, runtime, connection, or database — user elements in a query need a fully qualified name\""),
            one("meta::pure::functions::collection::tests::fold::testFold_Function_1__Boolean_1_", "\"'lastName' is not a known class, mapping, runtime, connection, or database — user elements in a query need a fully qualified name\""),
            one("meta::pure::functions::date::tests::testDayOfMonthError_Function_1__Boolean_1_", "\"Execution error column mismatch. Actual: 23 where expected: 36\""),
            one("meta::pure::functions::date::tests::testHourError_Function_1__Boolean_1_", "\"Execution error column mismatch. Actual: 23 where expected: 36\""),
            one("meta::pure::functions::date::tests::testMinuteError_Function_1__Boolean_1_", "\"Execution error column mismatch. Actual: 23 where expected: 36\""),
            one("meta::pure::functions::date::tests::testNewDateError_Function_1__Boolean_1_", "\"Execution error column mismatch. Actual: 23 where expected: 29\""),
            one("meta::pure::functions::date::tests::testSecondError_Function_1__Boolean_1_", "\"Execution error column mismatch. Actual: 23 where expected: 36\""),
            one("meta::pure::functions::lang::tests::match::testMatchWithMixedReturnType_Function_1__Boolean_1_", "\"scalar lowering not yet implemented for TypedDeactivate\""),
            one("meta::pure::functions::math::tests::rem::testRemError_Function_1__Boolean_1_", "\"Execution error message mismatch.\nThe actual message was \"Command execution failed on MongoDB server with error 16610 (Location16610): 'Failed to optimize pipeline :: caused by :: can't $mod by zero'"),
            one("meta::pure::functions::math::tests::testSquareRootError_Function_1__Boolean_1_", "\"Execution error message mismatch.\nThe actual message was \"Command execution failed on MongoDB server with error 28714 (Location28714): 'Failed to optimize pipeline :: caused by :: $sqrt's argument must be greater than or equal to 0'"),
            one("meta::pure::functions::math::tests::trigonometry::testArcCosineError_Function_1__Boolean_1_", "\"Execution error message mismatch.\nThe actual message was \"Infinite or NaN\"\nwhere the expected message was:\"Unable to compute acos of 2.0\"\""),
            one("meta::pure::functions::math::tests::trigonometry::testArcSineError_Function_1__Boolean_1_", "\"Execution error message mismatch.\nThe actual message was \"Infinite or NaN\"\nwhere the expected message was:\"Unable to compute asin of 2.0\"\""),
            // THE TDS TEXT ROUTE: a value the text does not carry (an empty string, a class instance, a List, a Map, an Any)
            one("meta::pure::functions::collection::tests::concatenate::testConcatenateMixedType_Function_1__Boolean_1_", "\"ERROR. Pure Type: 'meta::pure::metamodel::type::Any' is not supported yet.\""),
            one("meta::pure::functions::collection::tests::concatenate::testConcatenateTypeInference_Function_1__Boolean_1_", "\"ERROR. Pure Type: 'meta::pure::metamodel::type::Any' is not supported yet.\""),
            one("meta::pure::functions::collection::tests::removeDuplicates::testRemoveDuplicatesPrimitiveNonStandardFunction_Function_1__Boolean_1_", "\"ERROR. Pure Type: 'meta::pure::metamodel::type::Any' is not supported yet.\""),
            one("meta::pure::functions::collection::tests::zip::testZipBothListsEmpty_Function_1__Boolean_1_", "\"Type argument mismatch for the class meta::pure::functions::collection::Pair<U,V> (expected 2, got 0): Pair\""),
            one("meta::pure::functions::collection::tests::zip::testZipFirstListEmpty_Function_1__Boolean_1_", "\"Type argument mismatch for the class meta::pure::functions::collection::Pair<U,V> (expected 2, got 0): Pair\""),
            one("meta::pure::functions::collection::tests::zip::testZipSecondListEmpty_Function_1__Boolean_1_", "\"Type argument mismatch for the class meta::pure::functions::collection::Pair<U,V> (expected 2, got 0): Pair\""),
            one("meta::pure::functions::string::tests::joinStrings::testJoinStringsNoStrings_Function_1__Boolean_1_", "\"Cannot cast a collection of size 0 to multiplicity [1]\""),
            one("meta::pure::functions::string::tests::left::testLeftEmptyString_Function_1__Boolean_1_", "\"Cannot cast a collection of size 0 to multiplicity [1]\""),
            one("meta::pure::functions::string::tests::left::testLeftZeroLength_Function_1__Boolean_1_", "\"Cannot cast a collection of size 0 to multiplicity [1]\""),
            one("meta::pure::functions::string::tests::reverse::testReverseString_Function_1__Boolean_1_", "\"Cannot cast a collection of size 0 to multiplicity [1]\""),
            one("meta::pure::functions::string::tests::right::testRightEmptyString_Function_1__Boolean_1_", "\"Cannot cast a collection of size 0 to multiplicity [1]\""),
            one("meta::pure::functions::string::tests::right::testRightZeroLength_Function_1__Boolean_1_", "\"Cannot cast a collection of size 0 to multiplicity [1]\""),
            one("meta::pure::functions::string::tests::substr::testSubstrEmptyResult_Function_1__Boolean_1_", "\"Cannot cast a collection of size 0 to multiplicity [1]\""),
            one("meta::pure::functions::string::tests::toString::testStringToString_Function_1__Boolean_1_", "\"Cannot cast a collection of size 0 to multiplicity [1]\""),
            // VALUE DIFFERENCES, measured
            one("meta::pure::functions::collection::tests::contains::testContainsWithFunction_Function_1__Boolean_1_", "\"Assert failed\""),
            one("meta::pure::functions::collection::tests::exists::testExistsInSelect_Function_1__Boolean_1_", "\"\nexpected: ['f']\nactual:   []\""),
            one("meta::pure::functions::collection::tests::exists::testExists_Function_1__Boolean_1_", "\"Assert failed\""),
            one("meta::pure::functions::collection::tests::indexof::testIndexOfOneElement_Function_1__Boolean_1_", "\"\nexpected: 0\nactual:   1\""),
            one("meta::pure::functions::collection::tests::sort::testMixedSortNoComparator_Function_1__Boolean_1_", "\"\nexpected: [-2.0, 1, 5.0, 171, 342]\nactual:   [-2.0, 1.0, 5.0, 171.0, 342.0]\""),
            one("meta::pure::functions::date::tests::testAdjustByHours_Function_1__Boolean_1_", "\"\nexpected: %2015-04-15T13:12:11+0000\nactual:   %2015-04-15T13:12:11.000+0000\""),
            one("meta::pure::functions::date::tests::testAdjustByMinutes_Function_1__Boolean_1_", "\"\nexpected: %2015-04-15T13:12:11+0000\nactual:   %2015-04-15T13:12:11.000+0000\""),
            one("meta::pure::functions::date::tests::testAdjustByMonths_Function_1__Boolean_1_", "\"\nexpected: %2015-02-28T13:55:21+0000\nactual:   %2015-02-28T13:55:21.000+0000\""),
            one("meta::pure::functions::date::tests::testAdjustBySecondsBigNumber_Function_1__Boolean_1_", "\"\nexpected: %2407-05-19T19:15:12+0000\nactual:   %2407-05-19T19:15:12.000+0000\""),
            one("meta::pure::functions::date::tests::testAdjustBySeconds_Function_1__Boolean_1_", "\"\nexpected: %2015-04-15T13:11:12+0000\nactual:   %2015-04-15T13:11:12.000+0000\""),
            one("meta::pure::functions::date::tests::testDateFromSecond_Function_1__Boolean_1_", "\"\nexpected: %1973-11-13T23:09:11+0000\nactual:   %1973-11-13T23:09:11.000+0000\""),
            one("meta::pure::functions::date::tests::testDatePartTrivial_Function_1__Boolean_1_", "\"\nexpected: %1973-11-05\nactual:   %1973-11-05T00:00:00.000+0000\""),
            one("meta::pure::functions::date::tests::testDatePart_Function_1__Boolean_1_", "\"\nexpected: %1973-11-05\nactual:   %1973-11-05T00:00:00.000+0000\""),
            one("meta::pure::functions::math::tests::abs::testBigFloatAbs_Function_1__Boolean_1_", "\"\nexpected: 123456789123456789.99\nactual:   123456789123456780.0\""),
            one("meta::pure::functions::math::tests::rem::testRemWithNegativeIntegers_Function_1__Boolean_1_", "\"\nexpected: -2\nactual:   -2.0\""),
            one("meta::pure::functions::math::tests::rem::testRem_Function_1__Boolean_1_", "\"\nexpected: 2\nactual:   2.0\""),
            one("meta::pure::functions::string::tests::indexOf::testFromIndex_Function_1__Boolean_1_", "\"\nexpected: 1\nactual:   2\""),
            one("meta::pure::functions::string::tests::indexOf::testSimple_Function_1__Boolean_1_", "\"\nexpected: 4\nactual:   5\""),
            one("meta::pure::functions::string::tests::toString::testComplexClassToString_Function_1__Boolean_1_", "\"\nexpected: '// Warning: Good for gin -- Sad times no tonic'\nactual:   '@_'\""),
            // MONGODB'S OWN, the server's error
            one("meta::pure::functions::collection::tests::drop::testDropInList_Function_1__Boolean_1_", "\"Command execution failed on MongoDB server with error 241 (ConversionFailure): 'PlanExecutor error during aggregation :: caused by :: Unsupported conversion from array to string in $convert with no onError value'"),
            one("meta::pure::functions::collection::tests::find::testFindInstance_Function_1__Boolean_1_", "\"Command execution failed on MongoDB server with error 34471 (Location34471): 'PlanExecutor error during aggregation :: caused by :: $strLenCP requires a string argument, found: null'"),
            one("meta::pure::functions::collection::tests::find::testFindUsingVarForFunction_Function_1__Boolean_1_", "\"Command execution failed on MongoDB server with error 34471 (Location34471): 'PlanExecutor error during aggregation :: caused by :: $strLenCP requires a string argument, found: null'"),
            one("meta::pure::functions::collection::tests::head::testHeadComplex_Function_1__Boolean_1_", "\"Command execution failed on MongoDB server with error 241 (ConversionFailure): 'PlanExecutor error during aggregation :: caused by :: Unsupported conversion from object to string in $convert with no onError value'"),
            one("meta::pure::functions::collection::tests::put::testPut_emptyMap_Function_1__Boolean_1_", "\"Command execution failed on MongoDB server with error 241 (ConversionFailure): 'PlanExecutor error during aggregation :: caused by :: Unsupported conversion from object to string in $convert with no onError value'"),
            one("meta::pure::functions::collection::tests::slice::testSliceInList_Function_1__Boolean_1_", "\"Command execution failed on MongoDB server with error 241 (ConversionFailure): 'PlanExecutor error during aggregation :: caused by :: Unsupported conversion from array to string in $convert with no onError value'"),
            one("meta::pure::functions::collection::tests::take::testTakeInList_Function_1__Boolean_1_", "\"Command execution failed on MongoDB server with error 241 (ConversionFailure): 'PlanExecutor error during aggregation :: caused by :: Unsupported conversion from array to string in $convert with no onError value'"),
            one("meta::pure::functions::collection::tests::zip::testZipBothListsAreOfPairs_Function_1__Boolean_1_", "\"Command execution failed on MongoDB server with error 241 (ConversionFailure): 'PlanExecutor error during aggregation :: caused by :: Unsupported conversion from object to string in $convert with no onError value'"),
            one("meta::pure::functions::collection::tests::zip::testZipBothListsSameLength_Function_1__Boolean_1_", "\"Command execution failed on MongoDB server with error 241 (ConversionFailure): 'PlanExecutor error during aggregation :: caused by :: Unsupported conversion from object to string in $convert with no onError value'"),
            one("meta::pure::functions::collection::tests::zip::testZipFirstListLonger_Function_1__Boolean_1_", "\"Command execution failed on MongoDB server with error 241 (ConversionFailure): 'PlanExecutor error during aggregation :: caused by :: Unsupported conversion from object to string in $convert with no onError value'"),
            one("meta::pure::functions::collection::tests::zip::testZipFirstListsIsOfPairs_Function_1__Boolean_1_", "\"Command execution failed on MongoDB server with error 241 (ConversionFailure): 'PlanExecutor error during aggregation :: caused by :: Unsupported conversion from object to string in $convert with no onError value'"),
            one("meta::pure::functions::collection::tests::zip::testZipSecondListLonger_Function_1__Boolean_1_", "\"Command execution failed on MongoDB server with error 241 (ConversionFailure): 'PlanExecutor error during aggregation :: caused by :: Unsupported conversion from object to string in $convert with no onError value'"),
            one("meta::pure::functions::collection::tests::zip::testZipSecondListsIsOfPairs_Function_1__Boolean_1_", "\"Command execution failed on MongoDB server with error 241 (ConversionFailure): 'PlanExecutor error during aggregation :: caused by :: Unsupported conversion from object to string in $convert with no onError value'"),
            one("meta::pure::functions::date::tests::testAdjustByDaysBigNumber_Function_1__Boolean_1_", "\"Command execution failed on MongoDB server with error 18537 (Location18537): 'PlanExecutor error during aggregation :: caused by :: Could not convert date to string: date component was outside the supported range of 0-9999: 33803336'"),
            one("meta::pure::functions::date::tests::testAdjustByHoursBigNumber_Function_1__Boolean_1_", "\"Command execution failed on MongoDB server with error 18537 (Location18537): 'PlanExecutor error during aggregation :: caused by :: Could not convert date to string: date component was outside the supported range of 0-9999: 1410404'"),
            one("meta::pure::functions::date::tests::testAdjustByMinutesBigNumber_Function_1__Boolean_1_", "\"Command execution failed on MongoDB server with error 18537 (Location18537): 'PlanExecutor error during aggregation :: caused by :: Could not convert date to string: date component was outside the supported range of 0-9999: 25489'"),
            one("meta::pure::functions::date::tests::testAdjustByMonthsBigNumber_Function_1__Boolean_1_", "\"Command execution failed on MongoDB server with error 5976500 (Location5976500): 'Failed to optimize pipeline :: caused by :: invalid dateAdd 'amount' parameter value: 9600000000 month'"),
            one("meta::pure::functions::date::tests::testAdjustByWeeksBigNumber_Function_1__Boolean_1_", "\"Command execution failed on MongoDB server with error 18537 (Location18537): 'PlanExecutor error during aggregation :: caused by :: Could not convert date to string: date component was outside the supported range of 0-9999: 236611261'"),
            // MONGODB'S OWN, refused by name
            one("meta::pure::functions::collection::tests::get::testGet_Function_1__Boolean_1_", "\"a map from entries reached MongoDb: the entry shape is not mapped yet\""),
            one("meta::pure::functions::collection::tests::keys::testKeys_Function_1__Boolean_1_", "\"a map from entries reached MongoDb: the entry shape is not mapped yet\""),
            one("meta::pure::functions::collection::tests::put::testPut_addsEntry_Function_1__Boolean_1_", "\"a map from entries reached MongoDb: the entry shape is not mapped yet\""),
            one("meta::pure::functions::collection::tests::put::testPut_overridesEntry_Function_1__Boolean_1_", "\"a map from entries reached MongoDb: the entry shape is not mapped yet\""),
            one("meta::pure::functions::collection::tests::putAll::testPutAll_emptyInputMap_Function_1__Boolean_1_", "\"a map from entries reached MongoDb: the entry shape is not mapped yet\""),
            one("meta::pure::functions::collection::tests::putAll::testPutAll_emptyPutEntries_Function_1__Boolean_1_", "\"a map from entries reached MongoDb: the entry shape is not mapped yet\""),
            one("meta::pure::functions::collection::tests::putAll::testPutAll_overridesExistingAndAddNew_Function_1__Boolean_1_", "\"a map from entries reached MongoDb: the entry shape is not mapped yet\""),
            one("meta::pure::functions::collection::tests::values::testValues_Function_1__Boolean_1_", "\"a map from entries reached MongoDb: the entry shape is not mapped yet\""),
            one("meta::pure::functions::date::tests::testAdjustByMicrosecondsBigNumber_Function_1__Boolean_1_", "\"a MICROSECOND unit reached MongoDb, which has no such date unit\""),
            one("meta::pure::functions::date::tests::testAdjustByMicroseconds_Function_1__Boolean_1_", "\"a MICROSECOND unit reached MongoDb, which has no such date unit\""),
            one("meta::pure::functions::string::tests::format::testFormatDate_Function_1__Boolean_1_", "\"a date format with a HOUR12_NOPAD part reached MongoDb, whose $dateToString has no code for it\""),
            one("meta::pure::functions::string::tests::format::testFormatFloatWithRounding_Function_1__Boolean_1_", "\"a format directive other than %s reached MongoDb (the quick brown %s jumps over the lazy %.2f): no printf operator\""),
            one("meta::pure::functions::string::tests::format::testFormatFloatWithTruncation_Function_1__Boolean_1_", "\"a format directive other than %s reached MongoDb (the quick brown %s jumps over the lazy %.1f): no printf operator\""),
            one("meta::pure::functions::string::tests::format::testFormatFloatWithZeroPadding_Function_1__Boolean_1_", "\"a format directive other than %s reached MongoDb (the quick brown %s jumps over the lazy %.4f): no printf operator\""),
            one("meta::pure::functions::string::tests::format::testFormatInteger_Function_1__Boolean_1_", "\"a format directive other than %s reached MongoDb (the quick brown %s jumps over the lazy %d): no printf operator\""),
            one("meta::pure::functions::string::tests::parseDate::testParseDateWithTimezone_Function_1__Boolean_1_", "\"a time zone conversion reached MongoDb: not shaped yet\""),
            one("meta::pure::functions::string::tests::parseDate::testParseDateWithZ_Function_1__Boolean_1_", "\"a time zone conversion reached MongoDb: not shaped yet\""),
            one("meta::pure::functions::string::tests::toString::testPairCollectionToString_Function_1__Boolean_1_", "\"a Variant's JSON text reached MongoDb: no JSON text writer for a nested document yet\""),
            // MONGODB'S OWN: a two-parameter lambda (element, index) has no $map binding yet
            one("meta::pure::functions::collection::tests::removeDuplicates::testRemoveDuplicatesEmptyListExplicit_Function_1__Boolean_1_", "\"lambda [x, _rd_i] bound to [v_x]\""),
            one("meta::pure::functions::collection::tests::removeDuplicates::testRemoveDuplicatesEmptyList_Function_1__Boolean_1_", "\"lambda [_ddx, _ddi] bound to [v__ddx]\""),
            one("meta::pure::functions::collection::tests::removeDuplicates::testRemoveDuplicatesPrimitiveStandardFunctionExplicit_Function_1__Boolean_1_", "\"lambda [_ddx, _ddi] bound to [v__ddx]\""),
            one("meta::pure::functions::collection::tests::removeDuplicates::testRemoveDuplicatesPrimitiveStandardFunctionMixedTypes_Function_1__Boolean_1_", "\"lambda [_ddx, _ddi] bound to [v__ddx]\""),
            one("meta::pure::functions::collection::tests::removeDuplicates::testRemoveDuplicatesPrimitiveStandardFunctionSimple_Function_1__Boolean_1_", "\"lambda [_ddx, _ddi] bound to [v__ddx]\""),
            one("meta::pure::functions::collection::tests::removeDuplicatesBy::testRemoveDuplicatesByPrimitive_Function_1__Boolean_1_", "\"lambda [x, _rd_i] bound to [v_x]\""),
            // THE READER: a cell typed by its text
            one("meta::pure::functions::lang::tests::match::testMatchWithExtraParamsAndFunctionsAsParam_Function_1__Boolean_1_", "\"Not supported data type :'LONG' for Pure type: 'String'\""),
            one("meta::pure::functions::lang::tests::match::testMatchWithFunctionsAsParamManyMatch_Function_1__Boolean_1_", "\"Not supported data type :'LONG' for Pure type: 'String'\""),
            one("meta::pure::functions::lang::tests::match::testMatchWithFunctionsManyMatch_Function_1__Boolean_1_", "\"Not supported data type :'LONG' for Pure type: 'String'\""),
            one("meta::pure::functions::string::tests::right::testRight_Function_1__Boolean_1_", "\"Not supported data type :'LONG' for Pure type: 'String'\""),
            one("meta::pure::functions::string::tests::toString::testBooleanToString_Function_1__Boolean_1_", "\"Not supported data type :'BOOLEAN_AS_BYTE' for Pure type: 'String'\""),
            one("meta::pure::functions::string::tests::toString::testDateTimeToString_Function_1__Boolean_1_", "\"Not supported data type :'DATETIME_AS_LONG' for Pure type: 'String'\""),
            one("meta::pure::functions::string::tests::toString::testDateTimeWithTimezoneToString_Function_1__Boolean_1_", "\"Not supported data type :'DATETIME_AS_LONG' for Pure type: 'String'\""),
            one("meta::pure::functions::string::tests::toString::testDateToString_Function_1__Boolean_1_", "\"Not supported data type :'LONG' for Pure type: 'String'\""),
            one("meta::pure::functions::string::tests::toString::testFloatToStringWithExcessTrailingZeros_Function_1__Boolean_1_", "\"Not supported data type :'DOUBLE' for Pure type: 'String'\""),
            one("meta::pure::functions::string::tests::toString::testFloatToStringWithNegativeExponent_Function_1__Boolean_1_", "\"Not supported data type :'DOUBLE' for Pure type: 'String'\""),
            one("meta::pure::functions::string::tests::toString::testFloatToStringWithNoLeadingZero_Function_1__Boolean_1_", "\"Not supported data type :'DOUBLE' for Pure type: 'String'\""),
            one("meta::pure::functions::string::tests::toString::testFloatToStringWithPositiveExponent_Function_1__Boolean_1_", "\"Not supported data type :'DOUBLE' for Pure type: 'String'\""),
            one("meta::pure::functions::string::tests::toString::testFloatToString_Function_1__Boolean_1_", "\"Not supported data type :'DOUBLE' for Pure type: 'String'\""),
            one("meta::pure::functions::string::tests::toString::testIntegerToString_Function_1__Boolean_1_", "\"Not supported data type :'LONG' for Pure type: 'String'\""),
            one("meta::pure::functions::string::tests::toString::testSimpleDateToString_Function_1__Boolean_1_", "\"Not supported data type :'DATETIME_AS_LONG' for Pure type: 'String'\"")
    );

    public static Test suite() {
        return PctCensusGate.wrap("Essential", wrapSuite(
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
