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
 * The relation PCT suite on a Databricks SQL warehouse (docs/CLOUD_BACKENDS_PCT_2026_10_11.md): the SAME tests as
 * {@link Test_LegendLite_RelationFunctions_PCT}, through the same adapter, on a real account
 * ({@code LEGENDLITE_PCT_BACKEND=databricks}, pct/BUILD.bazel; its coordinates and credentials from
 * {@code LEGENDLITE_PCT_CLOUD_PROPERTIES}), with Databricks's OWN expected failures -- legend-engine's shape, as
 * {@link Test_LegendLite_Postgres_RelationFunctions_PCT}: every expected failure named and pinned by the text it
 * fails with, and a pinned test that starts passing fails the run, so a fix is recorded by deleting its row.
 */
public class Test_LegendLite_Databricks_RelationFunctions_PCT extends PCTReportConfiguration {

    private static final ReportScope reportScope = RelationCodeRepositoryProvider.relationFunctions;
    private static final Adapter adapter = LegendLitePCTReportProvider.LegendLiteAdapter;
    private static final String platform = "interpreted";

    // Pinned by a STABLE part of the message (the runner matches by containment): no per-run id.
    // First measured 2026-10-11 on the account (Databricks SQL warehouse); burned down the same day: every row is
    // the platform's own (pinned on the DuckDB lane too) or a measured limit of Databricks.
    private static final MutableList<ExclusionSpecification> expectedFailures = Lists.mutable.with(
            // THE PLATFORM'S OWN (each pinned on the DuckDB lane too, by the same cause): name resolution, lowering
            // and harness limits, the 0-based indexOf, error positions and messages
            one("meta::pure::functions::relation::tests::composition::testVariantArrayColumn_joinStrings_Function_1__Boolean_1_", "\"\nexpected: '#TDS\n   id,payload,joined\n   1,\\'[1,2,3]\\',1,2,3\n   2,\\'[4,5,6]\\',4,5,6\n   3,\\'[7,8,9]\\',7,8,9\n   4,\\'null\\',\n#'\nactual:   '#TDS\n   id,payload,joined\n   1,\\'[1,2,3]\\',1,2,3\n   2,\\'[4,5,6]\\',4,5,6\n   3,\\'[7,8,9]\\',7,8,9\n   4,\\'null\\',null\n#'\""),
            // 2026-10-19, step 9, the Databricks lane on the plan route: PIVOTS, pinned by name, not fixed (the
            // brief, docs/STEP9_LANE_BRIEF_2026_10_19.md §5.4: the emulation is one shared item, owned by the session
            // that merges the lanes). A dynamic pivot's text on the plan route needs the row as JSON, which only
            // DuckDB has (the four composition pivots over a cast pass since the merge of bccf473, register S83)
            one("meta::pure::functions::relation::tests::pivot::testPivot_MultipleMultiple_Dynamic_Aggregation_Function_1__Boolean_1_", "a row as JSON reached a dialect without a row-to-JSON form"),
            one("meta::pure::functions::relation::tests::pivot::testPivot_MultipleMultiple_Function_1__Boolean_1_", "a row as JSON reached a dialect without a row-to-JSON form"),
            one("meta::pure::functions::relation::tests::pivot::testPivot_MultipleMultiple_MultipleExpressions_Function_1__Boolean_1_", "a row as JSON reached a dialect without a row-to-JSON form"),
            one("meta::pure::functions::relation::tests::pivot::testPivot_MultipleSingle_Function_1__Boolean_1_", "a row as JSON reached a dialect without a row-to-JSON form"),
            one("meta::pure::functions::relation::tests::pivot::testPivot_MultipleSingle_MultipleExpressions_Function_1__Boolean_1_", "a row as JSON reached a dialect without a row-to-JSON form"),
            one("meta::pure::functions::relation::tests::pivot::testPivot_SingleMultiple_Dynamic_Aggregation_Function_1__Boolean_1_", "a row as JSON reached a dialect without a row-to-JSON form"),
            one("meta::pure::functions::relation::tests::pivot::testPivot_SingleMultiple_Function_1__Boolean_1_", "a row as JSON reached a dialect without a row-to-JSON form"),
            one("meta::pure::functions::relation::tests::pivot::testPivot_SingleMultiple_MultipleExpressions_Function_1__Boolean_1_", "a row as JSON reached a dialect without a row-to-JSON form"),
            one("meta::pure::functions::relation::tests::pivot::testPivot_SingleSingle_Function_1__Boolean_1_", "a row as JSON reached a dialect without a row-to-JSON form"),
            one("meta::pure::functions::relation::tests::pivot::testPivot_SingleSingle_MultipleExpressions_Function_1__Boolean_1_", "a row as JSON reached a dialect without a row-to-JSON form")
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
