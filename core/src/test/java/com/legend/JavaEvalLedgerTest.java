// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * THE TENET RATCHET (JAVA_EVICTION_PLAN E0; program CLOSED 2026-08-18;
 * claim recalibrated by docs/ADVERSARIAL_TENET_AUDIT_2026_08_18.md):
 * tenet #1 — "Java orchestrates, the DATABASE executes" — enforced as a
 * shrink-only ledger instead of prose. The honest, measurable claim: NO
 * host interpreter remains; the QUERY COMPILER executes no values; the
 * egress boundary is a small set of irreducible carriage sites plus the
 * NAMED, SHRINKING residue registered below (PCT wire, product
 * CSV/JSON, corpus rows, metadata grids, JSON-source frames, testdatagen
 * text, grid-read chains are all DATABASE-PRODUCED). Growth is a new
 * Java-evaluation site and needs a deliberate pin bump with a written
 * justification.
 *
 * <p>THE METAMODEL CHANNEL (ratified adjudication, JAVA_EVICTION_PLAN):
 * HostEval/MetamodelWalk/MetamodelSteps/PlanText/AggAwareActivities
 * evaluate MODEL CONSTANTS (instance construction from {@code ^Class}
 * literals), replicate engine metamodel TRANSFORMATIONS under test
 * (convertElement, wrapH2Boolean — node-to-node assertions, no text),
 * and compose engine-parity TEXT through single-owner spellings (the
 * Ddl ENGINE_TEXT flavor, dataTypeToSqlText, the plan-text envelope).
 * NO DATABASE VALUE can enter the channel: grid chains COMPILE to SQL
 * at the exec seam ({@code GridReads.tryLower} — chartered grid egress,
 * scheduled for deletion by the relation-typed {@code fetchDb} leg) and
 * {@code ArchitectureTest.theInterpreterPerformsNoJdbc} makes the
 * boundary mechanical (the channel cannot reach a connection).
 *
 * <p>PERMANENT-ALLOWED (the registered residue — justified, not
 * counted): the egress decode cluster ({@code Executor.fetch/unwrap/
 * latticeKind/decodeAny} — decoding carriers the DATABASE produced, by
 * declared contract), {@code LiteralFold} (the engine's own
 * ConstantExecutionNode, differential-pinned), the harness COMPARISON
 * layer (verification consumes two sides, never produces a result),
 * and {@code JsonAssertCanon.sortByKey} (re-creates the TEST'S OWN
 * canonicalization over a metamodel that never executes through SQL).
 */
class JavaEvalLedgerTest {

    /** SIZE rows — the METAMODEL-CHANNEL register: pinned MAX count of
     * COMMENT-STRIPPED NON-BLANK lines, shrink-only (growth needs a pin
     * bump with a written justification — the code-shape-guard
     * convention). Stripped counting is the Tier-2 audit's answer to
     * the ADVERSARIAL_TENET_AUDIT §3.1 probe: with raw line counts,
     * deleting comments funded new evaluation code under a green pin —
     * stripped, only CODE moves the number. The PCT extension row is
     * the E1 adapter-contract residue (ingress splicing, the scalar
     * bridge, the H4 message remap). */
    private static final Map<String, Integer> EVICT_SIZE = Map.ofEntries(
            Map.entry("pct/src/test/java/org/finos/legend/lite/pct/extension/ExecuteLegendLiteQuery.java", 844),
            // the INTERPRETER IS DELETED (oracle-not-runtime principle,
            // user-ratified 2026-08-18): HostEval is the routing
            // predicate only — grid chains compile into SQL (GridReads),
            // store nav resolves against the compiled model (StoreNav),
            // everything else walls with the principle's name
            Map.entry("core/src/main/java/com/legend/exec/HostEval.java", 132),
            Map.entry("core/src/main/java/com/legend/exec/MetamodelWalk.java", 1307),
            Map.entry("core/src/main/java/com/legend/MetamodelSteps.java", 195),
            // raw-line history: 888 -> 943 -> 957 (burn batches 1-2:
            // temp-table IN envelope emitters + PureExp let-allocation —
            // engine-parity plan TEXT, the register's own class);
            // re-seeded stripped 2026-08-18
            Map.entry("core/src/main/java/com/legend/plan/PlanText.java", 749),
            Map.entry("core/src/main/java/com/legend/AggAwareActivities.java", 225),
            // ADVERSARIAL_TENET_AUDIT_2026_08_18 §5: the grid egress was
            // "the sixth class the JDBC guard doesn't name" — these four
            // rows pin it until the relation-typed fetchDb leg DELETES
            // GridReads + DbMetaData's carrier wholesale (delete the
            // rows with the files, never bump them)
            Map.entry("core/src/main/java/com/legend/exec/GridReads.java", 386),
            Map.entry("core/src/main/java/com/legend/exec/StoreNav.java", 110),
            Map.entry("core/src/main/java/com/legend/exec/DynamicPivot.java", 118),
            Map.entry("core/src/main/java/com/legend/exec/DbMetaData.java", 235));
    // E4.b LANDED (2026-08-17): DbMetaData's row is RETIRED — the
    // shadow-H2 replay is DELETED and every metadata VALUE is now
    // database-produced (catalog queries over the AMBIENT session's
    // information_schema, F6.6's rule; identifier columns upper()'d in
    // SQL for the H2 engine-parity spelling). The residual file is
    // catalog-query ORCHESTRATION + egress decode by contract — the
    // decision rule's permitted classes. The grid VALUES still flow
    // into interpreter arms (fold/at chains) — that residue is E4.e's,
    // pinned by the HostEval rows above.
    // E5 wire rows LANDED (2026-08-17): the product wire is
    // PLAN-RENDERED (Compiler.executeWire → WireRender → Render
    // csvWire/jsonWire — the DB composes the bytes through the ONE
    // RFC-4180 owner and its own json_object policy). ResultJson is
    // DELETED (the Java JSON value policy died with it; streaming
    // writes plan-rendered row texts plus array punctuation only);
    // CsvSerializer/JsonSerializer shrank to format METADATA
    // (id/contentType/streaming capability — no serialize method
    // exists on the registry surface anymore), so their size rows
    // are retired rather than pinned.

    /** NAME rows (surgical surfaces inside shared files): explicit
     * name-family regex, EXACT pinned occurrence count (definitions and
     * call sites both — a stable proxy; any drift is a conscious
     * decision). */
    private static final Map<String, Object[]> EVICT_NAMES =
            new LinkedHashMap<>();

    static {
        // E4 — StatementExecutor's walk family
        EVICT_NAMES.put("core/src/main/java/com/legend/StatementExecutor.java",
                new Object[]{"(planWalk|walkProp|walkFilter|walkResult|planModel|planConnOf|constructNode|constructOp|nodeValue|typeRefSimple|activityEnvelopeRead|connectionStoreElementOf)\\(",
                        42});
        // E4.d batch 1 LANDED (2026-08-17, user-ratified "engine-exact
        // text is a lower TARGET"): the second DDL speller is DEAD —
        // dropTableStatementText/createTableStatementText/engineSpell
        // merged into the ONE generator (Ddl.createTable + Flavor
        // {H2_EXEC, DUCK_EXEC, ENGINE_TEXT}; the flavored type spelling
        // is the only per-target delta). This row pins the dead names
        // at zero. setUpDataSqlsText* remain as the engine-text
        // setUpDataSQLs walkers, now composing THROUGH the one
        // generator — engine-golden text of the model's own seed data
        // (compilation-class; asserted against engine goldens).
        EVICT_NAMES.put("core/src/main/java/com/legend/exec/Ddl.java",
                new Object[]{"(dropTableStatementText|createTableStatementText|engineSpell)\\(",
                        0});
        // E2 LANDED (2026-08-17): the host-side row explosion is DEAD
        // — the scalar-stream projection explodes IN SQL (LEFT LATERAL
        // UNNEST at project lowering; probe: ZERO firings on the full
        // sweep). This row pins the deletion: a list cell in a scalar
        // slot is a loud lowering-defect wall now, never a repair.
        EVICT_NAMES.put("core/src/main/java/com/legend/exec/Executor.java",
                new Object[]{"two many-valued TDS cells", 0});
        // E3 LANDED (2026-08-17): the frame is a one-Variant-column
        // VALUES relation — each cell an object's RAW JSON TEXT, every
        // property a typed variant extraction IN SQL (get + to-cast +
        // toOne); the DATABASE does all value interpretation. This row
        // PINS the deletion of the Java realization (classSource /
        // cellText / Json.parseAll — the lossy string grid) at zero.
        // objectTexts residue is SCISSORS: a lexical string-aware brace
        // scan cutting the model-text payload into row spans at plan
        // build; no JSON value ever materializes in Java.
        EVICT_NAMES.put("core/src/main/java/com/legend/resolver/JsonSourceFrame.java",
                new Object[]{"(classSource|cellText)\\(", 0});
        // E5 LANDED (2026-08-17): the testdatagen ROW TEXT is SQL — the
        // cell display casts, the '---null---' token, and the comma
        // joins all ride the projection; Java (csvEnvelope) assembles
        // only the ENVELOPE from catalog metadata (schema/table/header
        // lines, table separators) and appends DB-produced lines. This
        // row pins the deleted value composition at zero. headerCase is
        // re-registered PERMANENT: identifier-DISPLAY casing over
        // catalog names (the engine's H2 uppercase parity rule) — no
        // value ever flows through it (decision rule: metadata text,
        // census-classified).
        EVICT_NAMES.put("core/src/main/java/com/legend/testdatagen/TestDataGenerator.java",
                new Object[]{"renderCsv\\(", 0});
        // E1 LANDED (2026-08-17): the composition family is DEAD — the
        // PLAN emits the PCT wire text (Lowerer/Render pctTds via
        // PctRender at the execution seam; PCT 1110/1110). This row now
        // PINS the deletion at zero. The adapter-contract RESIDUE moved
        // to the PERMANENT register: createTDSResult (wraps the DB text
        // into the TDSResult CoreInstance), multText (model-source
        // extraction, ingress), stripTrailingZeros (scalar-bridge date
        // instance precision decode), remapErrorMessage (error-text
        // adapter, H4 known weakness documented), reEscapeStringLiterals
        // (interpreter-artifact ingress).
        EVICT_NAMES.put("pct/src/test/java/org/finos/legend/lite/pct/extension/ExecuteLegendLiteQuery.java",
                new Object[]{"(formatAsTds|formatValue|formatDate|purePctName)\\(",
                        0});
    }

    /** The EXEC PACKAGE is a CLOSED REGISTER (Tier-2 audit 2026-08-18;
     * ADVERSARIAL_TENET_AUDIT §3 probe: "new class in com.legend.exec
     * hashing a live cell" landed GREEN — exec had no class-list pin).
     * The egress boundary lives here; a NEW class is a new egress
     * surface and registers consciously. Exact in both directions. */
    private static final java.util.Set<String> EXEC_CLASSES =
            java.util.Set.of(
                    "Column.java", "CsvSeed.java", "DbMetaData.java",
                    "Ddl.java", "DynamicPivot.java",
                    "ExecutionResult.java", "Executor.java",
                    "GridReads.java", "H2Settings.java", "HostEval.java",
                    "MetamodelWalk.java", "PctProbe.java",
                    "PctRenderOption.java", "PostProcessBoundary.java",
                    "QueryPlan.java", "RawSqlBoundary.java",
                    "ResultShape.java", "Row.java", "StoreNav.java",
                    "TimingLedger.java", "package-info.java");

    @Test
    void theExecPackageIsAClosedRegister() throws IOException {
        Path dir = Path.of("..",
                "core/src/main/java/com/legend/exec");
        java.util.Set<String> actual = new java.util.TreeSet<>();
        try (var s = Files.list(dir)) {
            s.map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(".java"))
                    .forEach(actual::add);
        }
        StringBuilder drift = new StringBuilder();
        for (String f : actual) {
            if (!EXEC_CLASSES.contains(f)) {
                drift.append("\n  NEW exec class: ").append(f)
                        .append(" — a new egress surface registers"
                                + " consciously with its tenet argument");
            }
        }
        for (String f : EXEC_CLASSES) {
            if (!actual.contains(f)) {
                drift.append("\n  ").append(f)
                        .append(" is GONE — delete its register row");
            }
        }
        assertTrue(drift.length() == 0,
                "exec class-register drift (Tier-2 audit):" + drift);
    }

    @Test
    void javaEvaluationSurfaceOnlyShrinks() throws IOException {
        StringBuilder drift = new StringBuilder();
        for (var e : EVICT_SIZE.entrySet()) {
            Path p = Path.of("..", e.getKey());
            if (!Files.exists(p)) {
                continue;   // evicted whole — delete the row when seen
            }
            long lines = Files.readString(p)
                    .replaceAll("(?s)/\\*.*?\\*/", "")
                    .replaceAll("//.*", "")
                    .lines().filter(l -> !l.isBlank()).count();
            if (lines > e.getValue()) {
                drift.append("\n  ").append(e.getKey()).append(": ")
                        .append(lines).append(" > ").append(e.getValue())
                        .append(" stripped code lines — the evaluator"
                                + " GREW (tenet #1: the database"
                                + " executes; evict, or bump the pin"
                                + " with a written justification)");
            }
        }
        for (var e : EVICT_NAMES.entrySet()) {
            Path p = Path.of("..", e.getKey());
            int pinned = (Integer) e.getValue()[1];
            if (!Files.exists(p)) {
                if (pinned != 0) {
                    drift.append("\n  ").append(e.getKey())
                            .append(": file GONE — delete its ledger row");
                }
                continue;
            }
            String src = Files.readString(p)
                    .replaceAll("//.*", "")
                    .replaceAll("(?s)/\\*.*?\\*/", "");
            Matcher m = Pattern.compile((String) e.getValue()[0]).matcher(src);
            int n = 0;
            while (m.find()) {
                n++;
            }
            if (n != pinned) {
                drift.append("\n  ").append(e.getKey()).append(": ")
                        .append(n).append(n > pinned ? " > " : " < ")
                        .append(pinned)
                        .append(n > pinned
                                ? " — a NEW Java-evaluation site (tenet #1;"
                                        + " evict or register PERMANENT with"
                                        + " a justification)"
                                : " — an EVICTION landed: shrink this pin");
            }
        }
        assertTrue(drift.length() == 0,
                "Java-evaluation ledger drift (JAVA_EVICTION_PLAN E0):"
                + drift);
    }
}
