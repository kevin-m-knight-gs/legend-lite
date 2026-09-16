package com.legend.integration;

import org.junit.jupiter.api.*;

import java.io.*;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.stream.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Domain-based stress test: 20 domains × 10 classes × ~12 properties = ~200 classes, ~2400 properties.
 * 20 databases, 20 mappings, ~200 tables, ~200 joins, cross-domain associations.
 *
 * Tests parsing, typechecking, normalization, and plan generation across
 * a realistic investment banking domain model loaded from .pure resource files.
 */
@DisplayName("Domain Stress Tests")
class StressDomainTest {

    /** Services that lower to SQL, measured 2026-09-16 over 4,735: raise on
     *  improvement, never lower to make a run green. */
    private static final int MIN_LOWERED = 2753;

    /** Runtimes legend-lite cannot bind. Each needs a reason, and removing one must be a
     *  deliberate act rather than a side effect. */
    private static final java.util.Set<String> UNSUPPORTED_RUNTIMES =
            java.util.Set.of("stress::CanonicalRT", "stress::MoneyRT");

    /** Services whose CLASS lives in an excluded file, so legend-lite cannot resolve them
     *  at all. Distinct from an unsupported runtime: the element itself is absent. */
    private static final java.util.Set<String> UNRESOLVABLE =
            java.util.Set.of("MU0_MonetaryTrade");

    private Connection conn;

    @BeforeEach
    void setUp() throws SQLException {
        conn = DriverManager.getConnection("jdbc:duckdb:");
    }

    @AfterEach
    void tearDown() throws SQLException {
        if (conn != null && !conn.isClosed()) conn.close();
    }

    /** The corpus as the lite side loads it: linked projects first, then every stress
     *  file minus the {@link StressCorpus#EXCLUDED} ones (an element legend-lite cannot
     *  build takes the WHOLE model load down, not one service). */
    private String loadStressModel() throws Exception {
        StressCorpus.reportExclusions();
        String model = StressCorpus.model();
        assertFalse(model.isBlank(), "No .pure files found in stress/");
        return model;
    }

    @Test
    @DisplayName("20-domain model: parse + build + normalize + plan generation via Services")
    void testDomainModel() throws Exception {
        // ---- Phase 0: Load all Pure source from resource files (model + runtime + services) ----
        long t0 = System.nanoTime();
        String model = loadStressModel();
        long loadMs = (System.nanoTime() - t0) / 1_000_000;

        System.out.println("=== DOMAIN STRESS TEST: 20 Domains (Service-based) ===");
        System.out.println("Pure source size: " + (model.length() / 1024) + " KB");
        System.out.println("Phase 0 (load files): " + loadMs + " ms");

        // ---- Phase 1: Parse + build model (cold — first parse) ----
        long t1 = System.nanoTime();
        var ctx = com.legend.Compiler.compileModel(model);
        long buildMs = (System.nanoTime() - t1) / 1_000_000;
        System.out.println("Phase 1 (parse + build model, cold): " + buildMs + " ms");

        // ---- Phase 1b: Rebuild from same source (warm — ParseCache hit) ----
        long t1b = System.nanoTime();
        com.legend.Compiler.compileModel(model);
        long rebuildMs = (System.nanoTime() - t1b) / 1_000_000;
        System.out.println("Phase 1b (rebuild same source, cache hit): " + rebuildMs + " ms"
                + " (speedup: " + (buildMs > 0 ? buildMs + "/" + rebuildMs + " = " + (buildMs / Math.max(rebuildMs, 1)) + "x" : "N/A") + ")");

        var dialect = new com.legend.sql.dialect.DuckDb();

        // ---- Phase 3: Discover Services and plan each one (core parse) ----
        var stressServices = com.legend.testing.Own.model(model).elements().stream()
                .filter(el -> el instanceof com.legend.model.ServiceDefinition svc
                        && svc.qualifiedName().startsWith("stress::"))
                .map(el -> (com.legend.model.ServiceDefinition) el)
                .sorted(Comparator.comparing(com.legend.model.ServiceDefinition::qualifiedName))
                .collect(Collectors.toList());

        assertFalse(stressServices.isEmpty(), "No stress:: Services found in model");
        System.out.println("\nDiscovered " + stressServices.size() + " Services in stress:: package");

        System.out.println("\n=== SERVICE EXECUTION ===");
        int passed = 0, failed = 0;
        long parseNsTotal = 0, typeNsTotal = 0, resolveNsTotal = 0, planNsTotal = 0;
        long queryStartAll = System.nanoTime();
        for (var svc : stressServices) {
            String svcName = svc.qualifiedName()
                    .substring(svc.qualifiedName().lastIndexOf(':') + 1);
            String phase = "resolve";
            try {
                // the service body is already a parsed AST on the core record
                long qStart = System.nanoTime();
                var vs = com.legend.compiler.NameResolver.resolveQuery(svc.functionBody());
                long parseNs = System.nanoTime() - qStart;
                long parseUs = parseNs / 1_000;
                parseNsTotal += parseNs;
                phase = "typeCheck";
                long t = System.nanoTime();
                // Honour the service's OWN runtime. Hardcoding "stress::RT" silently compiled
                // every service against one runtime, which worked only while there was one:
                // reporting::FlatTrade is bound by stress::FlatRT and dispatch failed with
                // "runtime 'stress::RT' has 0 mappings binding class 'reporting::FlatTrade'".
                String rt = svc.runtimeRef() != null ? svc.runtimeRef() : "stress::RT";
                // legend-lite has no ModelChainConnection: it cannot bind a runtime whose
                // mappings are M2M fed by another mapping, and reports the runtime as
                // binding 0 mappings for the source class. That is a legend-lite GAP, not
                // a corpus error -- legend-engine runs these services -- so they are
                // reported as UNSUPPORTED rather than failing the suite. Remove the entry
                // when model chains land.
                if (UNRESOLVABLE.contains(svcName)) {
                    System.out.println("  SKIP " + svcName
                            + ": its class is declared in a file legend-lite cannot parse"
                            + " (see StressCorpus.EXCLUDED)");
                    continue;
                }
                if (UNSUPPORTED_RUNTIMES.contains(rt)) {
                    System.out.println("  SKIP " + svcName
                            + ": runtime " + rt + " uses a ModelChainConnection, which"
                            + " legend-lite does not implement");
                    continue;
                }
                var sqlq = com.legend.Compiler.lowerResolved(vs, ctx, rt, false);
                long typeElapsed = System.nanoTime() - t;
                long typeUs = typeElapsed / 1_000;
                typeNsTotal += typeElapsed;
                phase = "resolve";
                t = System.nanoTime();
                // resolve is folded into the lowering above
                long resolveElapsed = System.nanoTime() - t;
                long resolveUs = resolveElapsed / 1_000;
                resolveNsTotal += resolveElapsed;
                phase = "planGen";
                t = System.nanoTime();
                String sql = dialect.render(sqlq);
                long planElapsed = System.nanoTime() - t;
                long planUs = planElapsed / 1_000;
                planNsTotal += planElapsed;
                long totalUs = (System.nanoTime() - qStart) / 1_000;
                assertNotNull(sql, svcName + " produced null SQL");
                assertFalse(sql.isBlank(), svcName + " produced blank SQL");
                System.out.printf("  PASS %s: SQL(%d chars) %,dμs [parse=%,d type=%,d resolveFolded=%,d plan=%,d]%n",
                        svcName, sql.length(), totalUs, parseUs, typeUs, resolveUs, planUs);
                passed++;
            } catch (Exception e) {
                System.out.println("  FAIL " + svcName + " [" + phase + "]: "
                        + e.getClass().getSimpleName() + ": " + e.getMessage());
                var st = e.getStackTrace();
                for (int si = 0; si < Math.min(5, st.length); si++) {
                    System.out.println("      " + st[si]);
                }
                failed++;
            }
        }
        long queryMs = (System.nanoTime() - queryStartAll) / 1_000_000;
        long totalMs = (System.nanoTime() - t0) / 1_000_000;
        System.out.println("\n" + stressServices.size() + " services: " + passed + " passed, " + failed + " failed in " + queryMs + " ms");
        System.out.printf("  Pipeline: parse=%dms  lower=%dms  resolveFolded=%dms  render=%dms%n",
                parseNsTotal / 1_000_000, typeNsTotal / 1_000_000, resolveNsTotal / 1_000_000, planNsTotal / 1_000_000);
        System.out.println("TOTAL: " + totalMs + " ms");
        // THE LOWERING RATCHET (2026-09-16, the corpus at 4,735 services): the
        // count that lowers cleanly may only grow. The VERDICT over each
        // service's answer is StressServiceSuitesTest's; this test is the
        // lowering census and its timing (parse+build ~5 s, lowering ~2.5 s).
        assertTrue(passed >= MIN_LOWERED, passed + " services lowered, below the ratchet "
                + MIN_LOWERED + " (" + failed + " failed of " + stressServices.size() + ")");
    }
}
