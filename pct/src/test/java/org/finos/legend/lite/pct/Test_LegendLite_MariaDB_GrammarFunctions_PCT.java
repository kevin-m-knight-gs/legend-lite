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
 * The grammar PCT suite on MariaDB (docs/CLOUD_BACKENDS_PCT_2026_10_11.md §13): the SAME tests as
 * {@link Test_LegendLite_GrammarFunctions_PCT}, through the same adapter, on the embedded MariaDB 11.4
 * ({@code LEGENDLITE_PCT_BACKEND=mariadb}, pct/BUILD.bazel), with MariaDB's OWN expected failures -- legend-engine's
 * shape, as {@link Test_LegendLite_Postgres_GrammarFunctions_PCT}: every expected failure named and pinned by the text
 * it fails with, and a pinned test that starts passing fails the run, so a fix is recorded by deleting its row.
 */
public class Test_LegendLite_MariaDB_GrammarFunctions_PCT extends PCTReportConfiguration {

    private static final ReportScope reportScope = PlatformCodeRepositoryProvider.grammarFunctions;
    private static final Adapter adapter = LegendLitePCTReportProvider.LegendLiteAdapter;
    private static final String platform = "interpreted";

    // Pinned by a STABLE part of the message (the runner matches by containment): no per-run id.
    // First measured 2026-10-11 (MariaDB 11.4.13, the pinned server).
    private static final MutableList<ExclusionSpecification> expectedFailures = Lists.mutable.with(
            // INSTANCE IDENTITY (as on DuckDB): the asserts want the interpreter's ORIGINAL instances back, or eq/==
            // by reference; the harness inlines captured instances BY VALUE, so identity is erased before the wire
            one("meta::pure::functions::collection::tests::filter::testFilterInstance_Function_1__Boolean_1_", "instanceOf meta::pure::functions::collection::tests::model::CO_Person"),
            one("meta::pure::functions::collection::tests::first::testFirstComplex_Function_1__Boolean_1_", "instanceOf meta::pure::functions::collection::tests::model::CO_Person"),
            one("meta::pure::functions::collection::tests::map::testMapRelationshipFromManyToMany_Function_1__Boolean_1_", "instanceOf meta::pure::functions::collection::tests::map::model::M_Location"),
            one("meta::pure::functions::collection::tests::map::testMapRelationshipFromManyToOne_Function_1__Boolean_1_", "instanceOf meta::pure::functions::collection::tests::map::model::M_Address"),
            // THE PLATFORM'S OWN (each pinned on the DuckDB lane too, by the same cause): name resolution, lowering
            // and harness limits, the 0-based indexOf, error positions and messages
            one("meta::pure::functions::boolean::tests::equality::eq::testEqNonPrimitive_Function_1__Boolean_1_", "\"Assert failed\""),
            one("meta::pure::functions::boolean::tests::equality::eq::testEqPrimitiveExtension_Function_1__Boolean_1_", "\"unknown type 'meta::pure::functions::boolean::tests::equalitymodel::ExtendedInteger' in @meta::pure::functions::boolean::tests::equalitymodel::ExtendedInteger\""),
            one("meta::pure::functions::boolean::tests::equality::equal::testEqualNonPrimitive_Function_1__Boolean_1_", "\"Assert failed\""),
            one("meta::pure::functions::boolean::tests::equality::equal::testEqualPrimitiveExtension_Function_1__Boolean_1_", "\"unknown type 'meta::pure::functions::boolean::tests::equalitymodel::ExtendedInteger' in @meta::pure::functions::boolean::tests::equalitymodel::ExtendedInteger\""),
            one("meta::pure::functions::collection::tests::map::testMapRelationshipFromOneToOne_Function_1__Boolean_1_", "\"unbound variable '$address'\""),
            // NOTE 2026-10-20 (the parity phase): testDateCompare unpinned -- two dates without a time compare on their
            // day numbers, year*10000 + month*100 + day, in any year (MixedEncoding.dayNumberOrNull)
            // step 9, 2026-10-19, the plan route: a model element referenced as a value crosses by its simple name, and
            // five TestClasses defeat the text carrier's reader; register S82 (as on DuckDB)
            one("meta::pure::functions::lang::tests::letFn::testLetChainedWithAnotherFunction_Function_1__Boolean_1_", "\"the text carrier's cell does not compile: TestClass has not been defined! The system found 5 possible matches:\n    meta::json::tests::TestClass\n    meta::pure::functions::collection::tests::removeAll::TestClass\n    meta::pure::functions::lang::tests::letFn::TestClass\n    meta::pure::metamodel::serialization::grammar::tests::testNew::TestClass\n    meta::pure::tds::toRelation::TestClass -- TestClass\"")
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
