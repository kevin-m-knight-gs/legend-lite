// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package org.finos.legend.lite.pct;

import junit.framework.Test;
import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.list.MutableList;
import org.finos.legend.pure.code.core.RelationCodeRepositoryProvider;
import org.finos.legend.pure.m3.pct.reports.config.PCTReportConfiguration;
import org.finos.legend.pure.m3.pct.reports.config.exclusion.ExclusionSpecification;
import org.finos.legend.pure.m3.pct.reports.model.Adapter;
import org.finos.legend.pure.m3.pct.shared.model.ReportScope;
import org.finos.legend.pure.runtime.java.interpreted.testHelper.PureTestBuilderInterpreted;
import static org.finos.legend.engine.test.shared.framework.PureTestHelperFramework.wrapSuite;

/**
 * The relation PCT suite on MariaDB (docs/CLOUD_BACKENDS_PCT_2026_10_11.md §13): the SAME tests as
 * {@link Test_LegendLite_RelationFunctions_PCT}, through the same adapter, on the embedded MariaDB 11.4
 * ({@code LEGENDLITE_PCT_BACKEND=mariadb}, pct/BUILD.bazel), with MariaDB's OWN expected failures -- legend-engine's
 * shape, as {@link Test_LegendLite_Postgres_RelationFunctions_PCT}: every expected failure named and pinned by the text
 * it fails with, and a pinned test that starts passing fails the run, so a fix is recorded by deleting its row.
 */
public class Test_LegendLite_MariaDB_RelationFunctions_PCT extends PCTReportConfiguration {

    private static final ReportScope reportScope = RelationCodeRepositoryProvider.relationFunctions;
    private static final Adapter adapter = LegendLitePCTReportProvider.LegendLiteAdapter;
    private static final String platform = "interpreted";

    // Pinned by a STABLE part of the message (the runner matches by containment): no per-run id.
    // First measured 2026-10-11 (MariaDB 11.4.13, the pinned server).
    private static final MutableList<ExclusionSpecification> expectedFailures = Lists.mutable.with(
            // THE PLATFORM'S OWN (each pinned on the DuckDB lane too, by the same cause): name resolution, lowering
            // and harness limits, the 0-based indexOf, error positions and messages
            one("meta::pure::functions::relation::tests::composition::testVariantArrayColumn_joinStrings_Function_1__Boolean_1_", "\"\nexpected: '#TDS\n   id,payload,joined\n   1,\\'[1,2,3]\\',1,2,3\n   2,\\'[4,5,6]\\',4,5,6\n   3,\\'[7,8,9]\\',7,8,9\n   4,\\'null\\',\n#'\nactual:   '#TDS\n   id,payload,joined\n   1,\\'[1,2,3]\\',1,2,3\n   2,\\'[4,5,6]\\',4,5,6\n   3,\\'[7,8,9]\\',7,8,9\n   4,\\'null\\',null\n#'\""),
            // MARIADB'S OWN LIMIT: GROUP_CONCAT, JSON_ARRAYAGG and a mode are no window functions (measured), so a
            // windowed string aggregate or mode has no spelling
            one("meta::pure::functions::relation::tests::composition::testExtendJoinStringOnNull_Function_1__Boolean_1_", "\"a windowed STRING_AGG reached MariaDB, where it is no window function\""),
            one("meta::pure::functions::relation::tests::extend::testOLAPAggStringWithPartitionAndOrderASCUnboundedWindow_Function_1__Boolean_1_", "\"a windowed STRING_AGG reached MariaDB, where it is no window function\""),
            one("meta::pure::functions::relation::tests::extend::testOLAPAggStringWithPartitionAndOrderUnboundedWindow_Function_1__Boolean_1_", "\"a windowed STRING_AGG reached MariaDB, where it is no window function\""),
            one("meta::pure::functions::relation::tests::extend::testOLAPAggStringWithPartitionAndOrderUnboundedWindow_MultipleExpressions_Function_1__Boolean_1_", "\"a windowed STRING_AGG reached MariaDB, where it is no window function\""),
            one("meta::pure::functions::relation::tests::extend::testOLAPAggStringWithPartitionAndOrderWindow_Function_1__Boolean_1_", "\"a windowed STRING_AGG reached MariaDB, where it is no window function\""),
            one("meta::pure::functions::relation::tests::extend::testOLAPAggStringWithPartitionAndOrderWindow_MultipleExpressions_Function_1__Boolean_1_", "\"a windowed STRING_AGG reached MariaDB, where it is no window function\""),
            one("meta::pure::functions::relation::tests::extend::testOLAPAggStringWithPartitionAndUnboundedWindow_Function_1__Boolean_1_", "\"a windowed STRING_AGG reached MariaDB, where it is no window function\""),
            one("meta::pure::functions::relation::tests::extend::testOLAPAggWithPartitionAndOrderUnboundedWindowMultipleColumns_Function_1__Boolean_1_", "\"a windowed STRING_AGG reached MariaDB, where it is no window function\""),
            one("meta::pure::functions::relation::tests::extend::testOLAPAggWithPartitionAndOrderUnboundedWindowMultipleColumns_MultipleExpressions_Function_1__Boolean_1_", "\"a windowed STRING_AGG reached MariaDB, where it is no window function\""),
            one("meta::pure::functions::relation::tests::extend::testOLAPAggWithPartitionAndOrderWindowMultipleColumns_Function_1__Boolean_1_", "\"a windowed STRING_AGG reached MariaDB, where it is no window function\""),
            one("meta::pure::functions::relation::tests::extend::testOLAPAggWithPartitionAndOrderWindowMultipleColumns_MultipleExpressions_Function_1__Boolean_1_", "\"a windowed STRING_AGG reached MariaDB, where it is no window function\""),
            // MARIADB'S OWN LIMIT: a recursive CTE takes no outer reference (measured), so a fold over a row's list
            // is no walk
            one("meta::pure::functions::relation::tests::extend::testVariantColumn_fold_Function_1__Boolean_1_", "\"a fold that reads a row reached MariaDB, whose recursive CTE cannot read an outer column (measured: Unknown column)\""),
            // step 9, 2026-10-19, the plan route: a pivot. A dynamic pivot's text needs the row as JSON, which only DuckDB
            // has; a static pivot has no emulation on the plan route yet (the grouped CASE-aggregate form, the base
            // dialect's, is one shared item owned by the merging session). Pinned by name, not fixed (the brief's §5.4)
            one("meta::pure::functions::relation::tests::composition::test_Extend_Filter_Select_ComplexGroupBy_Pivot_Function_1__Boolean_1_", "\"a pivot reached MariaDB unemulated: it has no PIVOT\""),
            one("meta::pure::functions::relation::tests::composition::test_Extend_Filter_Select_GroupBy_Pivot_Extend_Sort_Limit_Function_1__Boolean_1_", "\"a pivot reached MariaDB unemulated: it has no PIVOT\""),
            one("meta::pure::functions::relation::tests::composition::test_Extend_Filter_Select_Pivot_GroupBy_Extend_Sort_Function_1__Boolean_1_", "\"a pivot reached MariaDB unemulated: it has no PIVOT\""),
            one("meta::pure::functions::relation::tests::composition::test_Pivot_Filter_Function_1__Boolean_1_", "\"a pivot reached MariaDB unemulated: it has no PIVOT\""),
            one("meta::pure::functions::relation::tests::pivot::testPivot_MultipleMultiple_Dynamic_Aggregation_Function_1__Boolean_1_", "\"a row as JSON reached a dialect without a row-to-JSON form\""),
            one("meta::pure::functions::relation::tests::pivot::testPivot_MultipleMultiple_Function_1__Boolean_1_", "\"a row as JSON reached a dialect without a row-to-JSON form\""),
            one("meta::pure::functions::relation::tests::pivot::testPivot_MultipleMultiple_MultipleExpressions_Function_1__Boolean_1_", "\"a row as JSON reached a dialect without a row-to-JSON form\""),
            one("meta::pure::functions::relation::tests::pivot::testPivot_MultipleSingle_Function_1__Boolean_1_", "\"a row as JSON reached a dialect without a row-to-JSON form\""),
            one("meta::pure::functions::relation::tests::pivot::testPivot_MultipleSingle_MultipleExpressions_Function_1__Boolean_1_", "\"a row as JSON reached a dialect without a row-to-JSON form\""),
            one("meta::pure::functions::relation::tests::pivot::testPivot_SingleMultiple_Dynamic_Aggregation_Function_1__Boolean_1_", "\"a row as JSON reached a dialect without a row-to-JSON form\""),
            one("meta::pure::functions::relation::tests::pivot::testPivot_SingleMultiple_Function_1__Boolean_1_", "\"a row as JSON reached a dialect without a row-to-JSON form\""),
            one("meta::pure::functions::relation::tests::pivot::testPivot_SingleMultiple_MultipleExpressions_Function_1__Boolean_1_", "\"a row as JSON reached a dialect without a row-to-JSON form\""),
            one("meta::pure::functions::relation::tests::pivot::testPivot_SingleSingle_Function_1__Boolean_1_", "\"a row as JSON reached a dialect without a row-to-JSON form\""),
            one("meta::pure::functions::relation::tests::pivot::testPivot_SingleSingle_MultipleExpressions_Function_1__Boolean_1_", "\"a row as JSON reached a dialect without a row-to-JSON form\"")
    );

    public static Test suite() {
        return PctCensusGate.wrap("Relation", wrapSuite(
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
