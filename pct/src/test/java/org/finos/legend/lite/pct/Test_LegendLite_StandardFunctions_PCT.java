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
 * PCT tests for Standard Functions (extended standard library).
 */
public class Test_LegendLite_StandardFunctions_PCT extends PCTReportConfiguration {

    private static final ReportScope reportScope = CoreStandardFunctionsCodeRepositoryProvider.standardFunctions;
    private static final Adapter adapter = LegendLitePCTReportProvider.LegendLiteAdapter;
    private static final String platform = "interpreted";

    // OFFICIAL-PARITY exclusions: every test here is ALSO an expected
    // failure of the reference legend-engine DuckDB adapter (its
    // Test_Relational_DuckDB_*_PCT exclusion set) — mixed-Number /
    // mixed-Date element identity through SQL type promotion, Decimal
    // value surfaces, in() over non-primitives, and friends. The
    // reference LEDGERS these rather than building a carrier; matching
    // its set IS the parity target. Pins are OUR full actual messages
    // (contains-matched), so any regression that changes the failure
    // shape — or a fix that makes one pass — fails loudly.
    private static final MutableList<ExclusionSpecification> expectedFailures = Lists.mutable.with(
            // DESCENDING CONTINUOUS PERCENTILE, last ULP. The window form
            // asks for percentile_cont(0.6) over [1.0,1.5,2.0] descending;
            // exact arithmetic is 1.4 and DuckDB returns 1.4000000000000001.
            // Not our arithmetic and not the negate-then-interpolate spelling
            // in sql.dialect.QuantileOrder — measured against DuckDB 1.4.4.0
            // directly, the SQL the ENGINE renders
            //   percentile_cont(0.6) WITHIN GROUP (ORDER BY v DESC)
            // returns 1.4000000000000001 too. The two forms differ only in
            // operation order: lo + t*(hi-lo) gives 1.4, lo*(1-t) + hi*t
            // gives 1.4000000000000001, and the second is what the backend
            // does. Matching it would mean hand-rolling the interpolation in
            // SQL for every percentile — a real behaviour change to a core
            // aggregate to chase one ULP.
            //
            // OFFICIAL PARITY, per this class's policy: the reference adapter
            // ledgers this SAME test in its DuckDB manifest
            // (pct-manifests/relational-duckdb/StandardFunctions_manifest.json)
            // — and for a WORSE reason, "Catalog Error: Aggregate Function
            // with name percentile_cont does not exist", because DuckDB has no
            // WINDOWED percentile_cont at all. We compute the value; we are
            // one ULP off it.
            one("meta::pure::functions::math::tests::percentile::testPercentile_Relation_Window_Function_1__Boolean_1_", "\"\nexpected: '#TDS\n   id,val,newCol\n   1,1.0,1.8\n   1,2.0,1.8\n   1,3.0,1.8\n   2,1.5,2.3\n   2,2.5,2.3\n   2,3.5,2.3\n   3,1.0,1.4\n   3,1.5,1.4\n   3,2.0,1.4\n#'\nactual:   '#TDS\n   id,val,newCol\n   1,1.0,1.8\n   1,2.0,1.8\n   1,3.0,1.8\n   2,1.5,2.3\n   2,2.5,2.3\n   2,3.5,2.3\n   3,1.0,1.4000000000000001\n   3,1.5,1.4000000000000001\n   3,2.0,1.4000000000000001\n#'\""));

    public static Test suite() {
        // M4 §3.4: the census gate pins this JVM's SqlTypeCensus
        // invariants at suite teardown (PctCensusGate)
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
