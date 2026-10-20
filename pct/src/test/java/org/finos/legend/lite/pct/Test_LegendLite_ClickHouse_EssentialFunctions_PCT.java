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
 * The essential PCT suite on ClickHouse (docs/CLOUD_BACKENDS_PCT_2026_10_11.md §19): the SAME tests as
 * {@link Test_LegendLite_EssentialFunctions_PCT}, through the same adapter, on the embedded ClickHouse 25.8
 * ({@code LEGENDLITE_PCT_BACKEND=clickhouse}, pct/BUILD.bazel), with ClickHouse's OWN expected failures -- legend-engine's
 * shape, as {@link Test_LegendLite_Postgres_EssentialFunctions_PCT}: every expected failure named and pinned by the text
 * it fails with, and a pinned test that starts passing fails the run, so a fix is recorded by deleting its row.
 */
public class Test_LegendLite_ClickHouse_EssentialFunctions_PCT extends PCTReportConfiguration {

    private static final ReportScope reportScope = PlatformCodeRepositoryProvider.essentialFunctions;
    private static final Adapter adapter = LegendLitePCTReportProvider.LegendLiteAdapter;
    private static final String platform = "interpreted";

    // Pinned by a STABLE part of the message (the runner matches by containment): no per-run id.
    // First measured 2026-10-12 (ClickHouse 25.8, the pinned server).
    private static final MutableList<ExclusionSpecification> expectedFailures = Lists.mutable.with(
            // testFormatFloat passes through the harness's connection, not the dialect: its statement is past 256 KiB,
            // and EmbeddedClickHouse.jdbcUrl raises max_query_size and the AST limits; a server on its defaults refuses
            // it ("Max query size exceeded", loudly). The ClickHouse connector carries them (the dialect plan's P5;
            // PR #34's review, 2026-10-13)
            // INSTANCE IDENTITY (as on DuckDB): the asserts want the interpreter's ORIGINAL instances back, or eq/==
            // by reference; the harness inlines captured instances BY VALUE, so identity is erased before the wire
            one("meta::pure::functions::collection::tests::find::testFindInstance_Function_1__Boolean_1_", "instanceOf meta::pure::functions::collection::tests::model::CO_Person"),
            one("meta::pure::functions::collection::tests::find::testFindUsingVarForFunction_Function_1__Boolean_1_", "instanceOf meta::pure::functions::collection::tests::model::CO_Person"),
            one("meta::pure::functions::collection::tests::head::testHeadComplex_Function_1__Boolean_1_", "instanceOf meta::pure::functions::collection::tests::model::CO_Person"),
            // THE PLATFORM'S OWN (each pinned on the DuckDB lane too, by the same cause): name resolution, lowering
            // and harness limits, the 0-based indexOf, error positions and messages
            one("meta::pure::functions::collection::tests::at::testAtError_Function_1__Boolean_1_", "\"Execution error column mismatch. Actual: 23 where expected: 37\""),
            one("meta::pure::functions::collection::tests::fold::testFoldFiltering_Function_1__Boolean_1_", "\"'otherNames' is not a known class, mapping, runtime, connection, or database — user elements in a query need a fully qualified name\""),
            one("meta::pure::functions::collection::tests::fold::testFoldToMany_Function_1__Boolean_1_", "\"'otherNames' is not a known class, mapping, runtime, connection, or database — user elements in a query need a fully qualified name\""),
            one("meta::pure::functions::collection::tests::fold::testFold_Function_1__Boolean_1_", "\"'lastName' is not a known class, mapping, runtime, connection, or database — user elements in a query need a fully qualified name\""),
            one("meta::pure::functions::collection::tests::indexof::testIndexOfOneElement_Function_1__Boolean_1_", "\"\nexpected: 0\nactual:   1\""),
            // step 9, 2026-10-19, the plan route: ClickHouse's dates are 1900 to 2299 -- the database now writes the
            // date's text itself, and its formatDateTime saturates at 2299-12-31 (the legacy route's Java read the raw
            // value: a DateTime64's ticks past 2299 exact, a Date32's days wrapped). A silent wrong answer, the
            // database's own; open: a refusal by name past 2299, as MariaDB's "a date past the year 9999"
            one("meta::pure::functions::date::tests::testAdjustByDaysBigNumber_Function_1__Boolean_1_", "\"\nexpected: %33803336-12-17\nactual:   %2299-12-31\""),
            one("meta::pure::functions::date::tests::testAdjustByHoursBigNumber_Function_1__Boolean_1_", "\"\nexpected: %1410404-07-12T00:00:00+0000\nactual:   %2299-12-31T23:56:20.896768+0000\""),
            one("meta::pure::functions::date::tests::testAdjustByMonthsBigNumber_Function_1__Boolean_1_", "\"\nexpected: %800002016-02-29\nactual:   %2299-12-31\""),
            one("meta::pure::functions::date::tests::testAdjustByWeeksBigNumber_Function_1__Boolean_1_", "\"\nexpected: %236611261-10-03\nactual:   %2299-12-31\""),
            one("meta::pure::functions::date::tests::testAdjustByMinutesBigNumber_Function_1__Boolean_1_", "\"\nexpected: %25489-04-20T03:12:00+0000\nactual:   %2299-12-31T23:12:00+0000\""),
            one("meta::pure::functions::date::tests::testAdjustBySecondsBigNumber_Function_1__Boolean_1_", "\"\nexpected: %2407-05-19T19:15:12+0000\nactual:   %2299-12-31T23:15:12+0000\""),
            one("meta::pure::functions::date::tests::testDayOfMonthError_Function_1__Boolean_1_", "\"Execution error column mismatch. Actual: 23 where expected: 36\""),
            one("meta::pure::functions::date::tests::testHourError_Function_1__Boolean_1_", "\"Execution error column mismatch. Actual: 23 where expected: 36\""),
            one("meta::pure::functions::date::tests::testMinuteError_Function_1__Boolean_1_", "\"Execution error column mismatch. Actual: 23 where expected: 36\""),
            one("meta::pure::functions::date::tests::testNewDateError_Function_1__Boolean_1_", "\"Execution error column mismatch. Actual: 23 where expected: 29\""),
            one("meta::pure::functions::date::tests::testSecondError_Function_1__Boolean_1_", "\"Execution error column mismatch. Actual: 23 where expected: 36\""),
            one("meta::pure::functions::lang::tests::match::testMatchWithMixedReturnType_Function_1__Boolean_1_", "\"scalar lowering not yet implemented for TypedDeactivate\""),
            // NOTE 2026-10-20 (the parity phase): testBigFloatAbs unpinned -- a Float root computed from exact-digit literals alone keeps its DECIMAL value (LiteralSpelling.exactFloatConstant).
            // step 9, 2026-10-19, the plan route (as DuckDB's, 2026-10-18): the text carrier spells the NaN cell NaN, and
            // Pure's parseFloat refuses the character (the legacy route's Java BigDecimal said "Infinite or NaN")
            one("meta::pure::functions::math::tests::trigonometry::testArcCosineError_Function_1__Boolean_1_", "\"Execution error message mismatch.\nThe actual message was \"Character N is neither a decimal digit number, decimal point, nor \"e\" notation exponential mark.\"\nwhere the expected message was:\"Unable to compute acos of 2.0\"\""),
            one("meta::pure::functions::math::tests::trigonometry::testArcSineError_Function_1__Boolean_1_", "\"Execution error message mismatch.\nThe actual message was \"Character N is neither a decimal digit number, decimal point, nor \"e\" notation exponential mark.\"\nwhere the expected message was:\"Unable to compute asin of 2.0\"\""),
            one("meta::pure::functions::string::tests::indexOf::testFromIndex_Function_1__Boolean_1_", "\"\nexpected: 1\nactual:   2\""),
            one("meta::pure::functions::string::tests::indexOf::testSimple_Function_1__Boolean_1_", "\"\nexpected: 4\nactual:   5\""),
            one("meta::pure::functions::string::tests::toString::testComplexClassToString_Function_1__Boolean_1_", "\"Code: 10. DB::Exception: Tuple doesn't have element with name '__id'"),
            // step 9, 2026-10-19, the plan route: a heterogeneous class collection meeting at an abstract ancestor crosses
            // as JSON without its class, which the text carrier has no form for; register S81 (as on DuckDB)
            one("meta::pure::functions::collection::tests::concatenate::testConcatenateTypeInference_Function_1__Boolean_1_", "\"the text carrier: an instance of meta::pure::functions::collection::tests::model::CO_GeographicEntity nested past the layout's depth has no form\"")
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
