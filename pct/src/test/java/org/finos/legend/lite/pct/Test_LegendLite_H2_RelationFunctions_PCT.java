// Copyright 2026 Legend Lite Contributors
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
 * The relation PCT suite on H2 2.4.240 — gate 7. The SAME tests as
 * {@link Test_LegendLite_RelationFunctions_PCT}, through the same adapter; the
 * database is chosen by the target ({@code LEGENDLITE_PCT_BACKEND=h2},
 * pct/BUILD.bazel), and this class carries H2's OWN expected failures.
 *
 * <p>The shape is legend-engine's: one configuration per database per suite,
 * every expected failure named and pinned by the text it fails with, and a
 * pinned test that starts PASSING fails the run ("expected to fail ... but now
 * passes") — so a fix is recorded by deleting its row. It replaces a count
 * ratchet (at most 1 failure and 26 errors) that could not say which tests
 * failed, nor notice one failure swapping for another.
 *
 * <p>Measured 2026-09-23 against legend-engine's own H2 manifest at the pinned
 * release (pct-manifests/relational-h2/RelationFunctions_manifest.json): 21 of
 * these 27 fail there too — H2 has no list type, no UNNEST, no LATERAL — and 6
 * fail only here (marked). The engine fails 37 relation tests on H2 that lite
 * passes (pivots, as-of joins, flatten).
 */
public class Test_LegendLite_H2_RelationFunctions_PCT extends PCTReportConfiguration {

    private static final ReportScope reportScope = RelationCodeRepositoryProvider.relationFunctions;
    private static final Adapter adapter = LegendLitePCTReportProvider.LegendLiteAdapter;
    private static final String platform = "interpreted";

    // Pinned by a STABLE part of the message: several carry a per-run
    // execution trace id, and the runner matches by containment.
    private static final MutableList<ExclusionSpecification> expectedFailures = Lists.mutable.with(
            // H2 has no list type: the list functions have no encoding on this dialect
            one("meta::pure::functions::relation::variant::tests::flatten::testFlatten_LateralJoin_Nested_Extend_Function_1__Boolean_1_", "LIST_FILTER reached a dialect without a list encoding"),
            one("meta::pure::functions::relation::tests::extend::testVariantColumn_filter_Function_1__Boolean_1_", "LIST_FILTER reached a dialect without a list encoding"),
            one("meta::pure::functions::relation::tests::filter::testVariantColumn_filterOutputFromLambda_Function_1__Boolean_1_", "LIST_FILTER reached a dialect without a list encoding"),
            one("meta::pure::functions::relation::tests::composition::testVariantColumn_functionComposition_Function_1__Boolean_1_", "LIST_FILTER reached a dialect without a list encoding"),
            one("meta::pure::functions::relation::tests::composition::testCoalesceInPreFilter_Function_1__Boolean_1_", "LIST_FILTER reached a dialect without a list encoding"),   // lite only: the engine passes it on H2
            one("meta::pure::functions::relation::tests::composition::testVariantColumn_distinct_removeDuplicates_Function_1__Boolean_1_", "LIST_SORT reached a dialect without a list encoding"),
            one("meta::pure::functions::relation::tests::composition::testVariantArrayColumn_sort_Function_1__Boolean_1_", "LIST_SORT reached a dialect without a list encoding"),
            one("meta::pure::functions::relation::tests::composition::testVariantColumn_slice_Function_1__Boolean_1_", "LIST_SLICE reached a dialect without a list encoding"),
            one("meta::pure::functions::relation::tests::extend::testVariantColumn_map_Function_1__Boolean_1_", "LIST_TRANSFORM reached a dialect without a list encoding"),
            one("meta::pure::functions::relation::tests::composition::testVariantArrayColumn_reverse_Function_1__Boolean_1_", "LIST_REVERSE reached a dialect without a list encoding"),
            one("meta::pure::functions::relation::tests::composition::testVariantColumn_indexOf_Function_1__Boolean_1_", "LIST_POSITION reached a dialect without a list encoding"),
            one("meta::pure::functions::relation::tests::composition::testVariantColumn_contains_Function_1__Boolean_1_", "collection membership reached a dialect without a list encoding"),
            one("meta::pure::functions::relation::tests::composition::testVariantArrayColumn_joinStrings_Function_1__Boolean_1_", "collection reduction 'STRING_AGG' reached a dialect without a list encoding"),
            one("meta::pure::functions::relation::tests::extend::testVariantColumn_fold_Function_1__Boolean_1_", "fold reached a dialect without a fold encoding"),
            // H2 has no UNNEST placement for a flatten
            one("meta::pure::functions::relation::variant::tests::flatten::testFlatten_LateralJoin_Function_1__Boolean_1_", "UNNEST reached a dialect without an unnest placement"),
            one("meta::pure::functions::relation::variant::tests::flatten::testFlatten_LateralJoin_Nested_Function_1__Boolean_1_", "UNNEST reached a dialect without an unnest placement"),
            one("meta::pure::functions::relation::tests::composition::testVariantMapColumn_keys_LateralFlatten_Function_1__Boolean_1_", "UNNEST reached a dialect without an unnest placement"),
            one("meta::pure::functions::relation::tests::composition::testVariantMapColumn_values_LateralFlatten_Function_1__Boolean_1_", "UNNEST reached a dialect without an unnest placement"),
            one("meta::pure::functions::relation::tests::project::testSimpleProjectWithEmpty_Function_1__Boolean_1_", "UNNEST reached a dialect without an unnest placement"),   // lite only: the engine passes it on H2
            // H2 has no LATERAL: the SQL lite renders is refused by the database
            one("meta::pure::functions::relation::tests::lateral::testLateralJoin_Function_1__Boolean_1_", "Function \"LATERAL\" not found"),
            one("meta::pure::functions::relation::tests::lateral::testLateralJoin_Chained_Function_1__Boolean_1_", "Function \"LATERAL\" not found"),
            one("meta::pure::functions::relation::tests::lateral::testLateralJoinAreInnerJoins_Function_1__Boolean_1_", "Function \"LATERAL\" not found"),
            one("meta::pure::functions::relation::tests::lateral::testLateralJoin_CorrelatedColumnNameAlsoOnInner_Function_1__Boolean_1_", "Function \"LATERAL\" not found"),
            one("meta::pure::functions::relation::tests::project::testSimpleProject_Function_1__Boolean_1_", "Function \"LATERAL\" not found"),   // lite only: the engine passes it on H2
            one("meta::pure::functions::relation::tests::project::testSimpleProjectList_Function_1__Boolean_1_", "Function \"LATERAL\" not found"),   // lite only: the engine passes it on H2
            one("meta::pure::functions::relation::tests::composition::testFilterPostProject_Function_1__Boolean_1_", "Function \"LATERAL\" not found"),   // lite only: the engine passes it on H2
            // PIVOTS ON THE PLAN ROUTE (step 9, 2026-10-19, the H2 lane's first run on the plan route: 455 of 469,
            // these fourteen): pinned by name, not fixed here (one shared item, owned by the session that merges the
            // lanes); the engine refuses every pivot on H2 (register S80). A static pivot has no emulation on the plan
            // route yet ...
            one("meta::pure::functions::relation::tests::composition::test_Extend_Filter_Select_ComplexGroupBy_Pivot_Function_1__Boolean_1_", "\"pivot reached a dialect without a PIVOT strategy\""),
            one("meta::pure::functions::relation::tests::composition::test_Extend_Filter_Select_GroupBy_Pivot_Extend_Sort_Limit_Function_1__Boolean_1_", "\"pivot reached a dialect without a PIVOT strategy\""),
            one("meta::pure::functions::relation::tests::composition::test_Extend_Filter_Select_Pivot_GroupBy_Extend_Sort_Function_1__Boolean_1_", "\"pivot reached a dialect without a PIVOT strategy\""),
            one("meta::pure::functions::relation::tests::composition::test_Pivot_Filter_Function_1__Boolean_1_", "\"pivot reached a dialect without a PIVOT strategy\""),
            // ... and a dynamic pivot's text reads each row's JSON, which H2's dialect has no form for
            one("meta::pure::functions::relation::tests::pivot::testPivot_MultipleMultiple_Dynamic_Aggregation_Function_1__Boolean_1_", "\"variant navigation reached a dialect without JSON support\""),
            one("meta::pure::functions::relation::tests::pivot::testPivot_MultipleMultiple_Function_1__Boolean_1_", "\"variant navigation reached a dialect without JSON support\""),
            one("meta::pure::functions::relation::tests::pivot::testPivot_MultipleMultiple_MultipleExpressions_Function_1__Boolean_1_", "\"variant navigation reached a dialect without JSON support\""),
            one("meta::pure::functions::relation::tests::pivot::testPivot_MultipleSingle_Function_1__Boolean_1_", "\"variant navigation reached a dialect without JSON support\""),
            one("meta::pure::functions::relation::tests::pivot::testPivot_MultipleSingle_MultipleExpressions_Function_1__Boolean_1_", "\"variant navigation reached a dialect without JSON support\""),
            one("meta::pure::functions::relation::tests::pivot::testPivot_SingleMultiple_Dynamic_Aggregation_Function_1__Boolean_1_", "\"variant navigation reached a dialect without JSON support\""),
            one("meta::pure::functions::relation::tests::pivot::testPivot_SingleMultiple_Function_1__Boolean_1_", "\"variant navigation reached a dialect without JSON support\""),
            one("meta::pure::functions::relation::tests::pivot::testPivot_SingleMultiple_MultipleExpressions_Function_1__Boolean_1_", "\"variant navigation reached a dialect without JSON support\""),
            one("meta::pure::functions::relation::tests::pivot::testPivot_SingleSingle_Function_1__Boolean_1_", "\"variant navigation reached a dialect without JSON support\""),
            one("meta::pure::functions::relation::tests::pivot::testPivot_SingleSingle_MultipleExpressions_Function_1__Boolean_1_", "\"variant navigation reached a dialect without JSON support\"")
    );

    public static Test suite() {
        // the census gate keys its H2 pins on the backend (PctCensusGate)
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
