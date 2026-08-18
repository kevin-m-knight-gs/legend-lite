// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * THE JDBC SURFACE CENSUS (ADVERSARIAL_TENET_AUDIT_2026_08_18 fix plan
 * items 6+7): a WALK over every module and BOTH source roots, with an
 * explicit exempt register — the eval ledger's hard-coded paths could
 * not see code that moved or was born outside them (10 of the audit's
 * 15 probes landed green that way; the {@code src/main -> src/test}
 * harness move silently walked 7,564 lines out of seven guards).
 *
 * <p>Three assertions:
 * <ul>
 *   <li><b>Coverage floor</b> — the walk itself reports how many files
 *   it scanned, and that number must not drop: scope rot (a renamed
 *   root, a new module the walk misses) fails loudly instead of
 *   silently shrinking the guarded surface.</li>
 *   <li><b>Production register</b> — the EXACT set of {@code src/main}
 *   files that touch JDBC or a driver API ({@code java.sql},
 *   {@code javax.sql}, {@code org.duckdb}, {@code org.h2},
 *   {@code org.sqlite}, {@code ResultSet}/statement spellings, in
 *   comment-stripped source). A new file fails in BOTH directions:
 *   growth is a conscious registration, shrink deletes the row.</li>
 *   <li><b>Test register</b> — same, for test roots: the harness,
 *   checkers, and probes legitimately EXECUTE queries, so they
 *   register by file; a new test file reaching JDBC is a conscious
 *   decision, not ambient drift.</li>
 * </ul>
 *
 * <p>KNOWN LIMIT (the audit's own §3 lesson, recorded honestly): this
 * census is FILE-grained. New evaluation code added to an
 * already-registered file is invisible here — that residue is what the
 * eval ledger's size/name pins cover, and residue DELETION (the
 * relation-typed fetchDb leg) is the durable fix, not finer guards.
 */
class JdbcSurfaceCensusTest {

    private static final Pattern JDBC = Pattern.compile(
            "java\\.sql|javax\\.sql|org\\.duckdb|org\\.h2\\.|org\\.sqlite"
            + "|ResultSet|prepareStatement|executeQuery");

    /** Every module source root the census walks (repo-relative). A
     * NEW MODULE must be added here — the coverage floor cannot see a
     * root it was never told about, so module creation reviews this
     * file. */
    private static final List<String> ROOTS = List.of(
            "core/src", "pct/src", "nlq/src", "parser-equivalence/src");

    /** Coverage floor: files scanned on 2026-08-18. Shrink needs a
     * written justification (files deleted); growth is free. */
    private static final int FILE_FLOOR = 779;

    private static final Set<String> MAIN_REGISTER = new TreeSet<>(List.of(
            "core/src/main/java/com/legend/Compiler.java",
            "core/src/main/java/com/legend/SeedSqlForms.java",
            "core/src/main/java/com/legend/StatementExecutor.java",
            "core/src/main/java/com/legend/builtin/Pure.java",
            "core/src/main/java/com/legend/exec/DbMetaData.java",
            "core/src/main/java/com/legend/exec/DynamicPivot.java",
            "core/src/main/java/com/legend/exec/Executor.java",
            "core/src/main/java/com/legend/exec/GridReads.java",
            "core/src/main/java/com/legend/exec/PctProbe.java",
            "core/src/main/java/com/legend/server/ConnectionResolver.java",
            "core/src/main/java/com/legend/server/QueryService.java",
            "core/src/main/java/com/legend/testdatagen/TestDataGenerator.java"
    ));

    private static final Set<String> TEST_REGISTER = new TreeSet<>(List.of(
            "core/src/test/java/com/legend/ArchitectureTest.java",
            "core/src/test/java/com/legend/JdbcSurfaceCensusTest.java",
            "core/src/test/java/com/legend/AuditRound3Test.java",
            "core/src/test/java/com/legend/AuditRound5Test.java",
            "core/src/test/java/com/legend/ConstantPlanParityTest.java",
            "core/src/test/java/com/legend/TenetRatchetTest.java",
            "core/src/test/java/com/legend/builtin/NativeFunctionTest.java",
            "core/src/test/java/com/legend/compiler/spec/UserCallInlinerTest.java",
            // Tier-1 audit regression pins (2026-08-18): drive the fixed
            // findings through the real pipeline / real connections —
            // they verify egress spelling and wall behavior, computing
            // no values in Java
            "core/src/test/java/com/legend/exec/AuditTier1PipelineTest.java",
            "core/src/test/java/com/legend/exec/DynamicPivotKeyLiteralTest.java",
            "core/src/test/java/com/legend/testdatagen/PureReprTest.java",
            "core/src/test/java/com/legend/exec/ExecuteFrameTest.java",
            "core/src/test/java/com/legend/exec/ExecuteInDbTest.java",
            "core/src/test/java/com/legend/exec/ExecutorTest.java",
            "core/src/test/java/com/legend/exec/StructValueTest.java",
            "core/src/test/java/com/legend/harness/AssertLoopForm.java",
            "core/src/test/java/com/legend/harness/EngineTestExecutor.java",
            "core/src/test/java/com/legend/harness/EngineTestExecutorTest.java",
            "core/src/test/java/com/legend/harness/ExecCallFinder.java",
            "core/src/test/java/com/legend/harness/H2Verify.java",
            "core/src/test/java/com/legend/harness/PlanAsserts.java",
            "core/src/test/java/com/legend/harness/RuntimeIfForm.java",
            "core/src/test/java/com/legend/harness/TdsEquivalence.java",
            "core/src/test/java/com/legend/harness/TestDataGenForm.java",
            "core/src/test/java/com/legend/integration/AbstractDatabaseTest.java",
            "core/src/test/java/com/legend/integration/AsOfJoinCheckerTest.java",
            "core/src/test/java/com/legend/integration/AssociationIntegrationTest.java",
            "core/src/test/java/com/legend/integration/ComputedProjectIntegrationTest.java",
            "core/src/test/java/com/legend/integration/ConcatenateFlattenCheckerTest.java",
            "core/src/test/java/com/legend/integration/CorpusDifferentialTest.java",
            "core/src/test/java/com/legend/integration/DuckDBIntegrationTest.java",
            "core/src/test/java/com/legend/integration/DuckDBStructSyntaxTest.java",
            "core/src/test/java/com/legend/integration/DynaFunctionIntegrationTest.java",
            "core/src/test/java/com/legend/integration/EnumIntegrationTest.java",
            "core/src/test/java/com/legend/integration/ExecutionResultIntegrationTest.java",
            "core/src/test/java/com/legend/integration/ExtendCheckerTest.java",
            "core/src/test/java/com/legend/integration/ExtendWindowCheckerTest.java",
            "core/src/test/java/com/legend/integration/FilterCheckerTest.java",
            "core/src/test/java/com/legend/integration/FoldCheckerTest.java",
            "core/src/test/java/com/legend/integration/FromCheckerTest.java",
            "core/src/test/java/com/legend/integration/GetCheckerTest.java",
            "core/src/test/java/com/legend/integration/GroupByCheckerTest.java",
            "core/src/test/java/com/legend/integration/InheritanceIntegrationTest.java",
            "core/src/test/java/com/legend/integration/JoinCheckerTest.java",
            "core/src/test/java/com/legend/integration/JsonM2MChainIntegrationTest.java",
            "core/src/test/java/com/legend/integration/JsonM2MIntegrationTest.java",
            "core/src/test/java/com/legend/integration/JsonMappingIntegrationTest.java",
            "core/src/test/java/com/legend/integration/LetCheckerTest.java",
            "core/src/test/java/com/legend/integration/M2M2RTabularTest.java",
            "core/src/test/java/com/legend/integration/M2MChainIntegrationTest.java",
            "core/src/test/java/com/legend/integration/M2MIntegrationTest.java",
            "core/src/test/java/com/legend/integration/PivotCheckerTest.java",
            "core/src/test/java/com/legend/integration/RelationApiIntegrationTest.java",
            "core/src/test/java/com/legend/integration/RelationalMappingCompositionTest.java",
            "core/src/test/java/com/legend/integration/RelationalMappingIntegrationTest.java",
            "core/src/test/java/com/legend/integration/RenameCheckerTest.java",
            "core/src/test/java/com/legend/integration/RenderCsvIntegrationTest.java",
            "core/src/test/java/com/legend/integration/SQLiteIntegrationTest.java",
            "core/src/test/java/com/legend/integration/ScalarFunctionIntegrationTest.java",
            "core/src/test/java/com/legend/integration/SelectDistinctCheckerTest.java",
            "core/src/test/java/com/legend/integration/SlicingCheckerTest.java",
            "core/src/test/java/com/legend/integration/SortCheckerTest.java",
            "core/src/test/java/com/legend/integration/SourceUrlUserCallableTest.java",
            "core/src/test/java/com/legend/integration/StreamingIntegrationTest.java",
            "core/src/test/java/com/legend/integration/StressDomainTest.java",
            "core/src/test/java/com/legend/integration/StressTest.java",
            "core/src/test/java/com/legend/integration/StressTest100K.java",
            "core/src/test/java/com/legend/integration/StressTest10K.java",
            "core/src/test/java/com/legend/integration/StressTestChaotic.java",
            "core/src/test/java/com/legend/integration/StressTestComplexQueries.java",
            "core/src/test/java/com/legend/integration/StressTestDense.java",
            "core/src/test/java/com/legend/integration/StructFilterIntegrationTest.java",
            "core/src/test/java/com/legend/integration/TypeConversionCheckerTest.java",
            "core/src/test/java/com/legend/integration/TypeInferenceIntegrationTest.java",
            "core/src/test/java/com/legend/integration/UserFunctionIntegrationTest.java",
            "core/src/test/java/com/legend/integration/VariantIntegrationTest.java",
            "core/src/test/java/com/legend/integration/WindowFunctionTest.java",
            "core/src/test/java/com/legend/integration/WriteCheckerTest.java",
            "core/src/test/java/com/legend/lowering/GroupByAvgMappingTest.java",
            "core/src/test/java/com/legend/lowering/JoinTortureTest.java",
            "core/src/test/java/com/legend/lowering/LowerRelationTest.java",
            "core/src/test/java/com/legend/lowering/NullSemanticsTest.java",
            "core/src/test/java/com/legend/lowering/ValueSortComparatorTest.java",
            "core/src/test/java/com/legend/normalizer/AssocSimpleNameProbeTest.java",
            "core/src/test/java/com/legend/normalizer/AssociationViewJoinTest.java",
            "core/src/test/java/com/legend/rcorpus/DuckWorkspaces.java",
            "core/src/test/java/com/legend/rcorpus/Runner.java",
            "core/src/test/java/com/legend/resolver/ResolveDeepEmptinessProbeTest.java",
            "core/src/test/java/com/legend/resolver/ResolveDerivedLeafProbeTest.java",
            "core/src/test/java/com/legend/resolver/ResolveFilterDemandTest.java",
            "core/src/test/java/com/legend/resolver/ResolveGraphUnionProbeTest.java",
            "core/src/test/java/com/legend/resolver/ResolveM2mTest.java",
            "core/src/test/java/com/legend/resolver/ResolveNavigationTest.java",
            "core/src/test/java/com/legend/resolver/ResolveNestedNavTest.java",
            "core/src/test/java/com/legend/resolver/ResolveOtherwiseTest.java",
            "core/src/test/java/com/legend/resolver/ResolveOuterDatedNavTest.java",
            "core/src/test/java/com/legend/resolver/ResolveSerializeTest.java",
            "core/src/test/java/com/legend/resolver/ResolveSimpleClassTest.java",
            "core/src/test/java/com/legend/resolver/ResolveTemporalContextTest.java",
            "core/src/test/java/com/legend/resolver/ResolveUnionChainTest.java",
            "core/src/test/java/com/legend/resolver/ResolveUnionJtcProbeTest.java",
            "core/src/test/java/com/legend/resolver/ResolveUnionMultiHopProbeTest.java",
            "core/src/test/java/com/legend/resolver/ResolveUnionOuterDateProbeTest.java",
            "core/src/test/java/com/legend/resolver/ResolveUnionTest.java",
            "core/src/test/java/com/legend/sql/DuckDbValidityTest.java",
            "core/src/test/java/com/legend/sql/dialect/CarrierDifferentialTest.java",
            "pct/src/test/java/org/finos/legend/lite/pct/Test_LegendLite_GrammarFunctions_PCT.java",
            "pct/src/test/java/org/finos/legend/lite/pct/extension/ExecuteLegendLiteQuery.java"
    ));

    @Test
    void jdbcSurfaceIsRegistered() throws IOException {
        List<Path> files = new ArrayList<>();
        for (String r : ROOTS) {
            Path root = Path.of("..", r);
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> s = Files.walk(root)) {
                s.filter(p -> p.toString().endsWith(".java"))
                        .forEach(files::add);
            }
        }
        assertTrue(files.size() >= FILE_FLOOR,
                "JDBC census coverage DROPPED: scanned " + files.size()
                + " files, floor " + FILE_FLOOR + " — a source root moved"
                + " or the walk rotted; re-point the census before"
                + " trusting any guard that scopes by path");

        Set<String> mainHits = new TreeSet<>();
        Set<String> testHits = new TreeSet<>();
        for (Path p : files) {
            String src = Files.readString(p)
                    .replaceAll("//.*", "")
                    .replaceAll("(?s)/\\*.*?\\*/", "");
            if (JDBC.matcher(src).find()) {
                String rel = Path.of("..").toAbsolutePath().normalize()
                        .relativize(p.toAbsolutePath().normalize())
                        .toString().replace('\\', '/');
                (rel.contains("/main/") ? mainHits : testHits).add(rel);
            }
        }
        StringBuilder drift = new StringBuilder();
        diff(drift, "src/main", mainHits, MAIN_REGISTER);
        diff(drift, "test roots", testHits, TEST_REGISTER);
        assertTrue(drift.length() == 0,
                "JDBC surface census drift (tenet #1 — Java orchestrates,"
                + " the DATABASE executes; audit 2026-08-18 items 6+7):"
                + drift);
    }

    private static void diff(StringBuilder drift, String where,
            Set<String> actual, Set<String> register) {
        for (String f : actual) {
            if (!register.contains(f)) {
                drift.append("\n  NEW JDBC surface in ").append(where)
                        .append(": ").append(f)
                        .append(" — register it CONSCIOUSLY (with the")
                        .append(" tenet argument) or route through an")
                        .append(" existing seam");
            }
        }
        for (String f : register) {
            if (!actual.contains(f)) {
                drift.append("\n  ").append(f)
                        .append(" no longer touches JDBC — shrink the ")
                        .append(where).append(" register");
            }
        }
    }
}
