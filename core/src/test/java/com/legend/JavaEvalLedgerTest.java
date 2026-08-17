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
 * THE TENET RATCHET (JAVA_EVICTION_PLAN E0): tenet #1 — "Java
 * orchestrates, the DATABASE executes" — enforced as a shrink-only
 * ledger instead of prose. Every EVICT row below is a Java surface that
 * COMPUTES a value or COMPOSES text a test assertion observes as the
 * result of executing a Pure expression. A NEW evaluator method on a
 * ledgered file fails this test; an evicted one forces its row to
 * SHRINK. The program is done when every EVICT row is zero and deleted.
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

    /** SIZE rows (whole-file E4 surfaces): pinned MAX line count,
     * measured 2026-08-17 — the file may only SHRINK or hold; growth is
     * a new Java-evaluation site and needs a deliberate pin bump with a
     * justification (the code-shape-guard convention). */
    private static final Map<String, Integer> EVICT_SIZE = Map.of(
            // E1 — the WHOLE PCT extension is size-pinned (deep-audit
            // follow-up: the name row alone under-covered it — print
            // decisions also live in the value bridge), shrink-only
            // until root mode reduces it to the adapter contract
            "pct/src/test/java/org/finos/legend/lite/pct/extension/ExecuteLegendLiteQuery.java", 1101,
            "core/src/main/java/com/legend/exec/HostEval.java", 928,
            "core/src/main/java/com/legend/exec/MetamodelWalk.java", 1603,
            "core/src/main/java/com/legend/MetamodelSteps.java", 234,
            "core/src/main/java/com/legend/plan/PlanText.java", 888,
            "core/src/main/java/com/legend/AggAwareActivities.java", 265);
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
        // E4 — engine-TEXT generators in Ddl
        EVICT_NAMES.put("core/src/main/java/com/legend/exec/Ddl.java",
                new Object[]{"(dropTableStatementText|createTableStatementText|setUpDataSqlsText|setUpDataSqlsTextFromRecords|engineSpell)\\(",
                        15});
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

    @Test
    void javaEvaluationSurfaceOnlyShrinks() throws IOException {
        StringBuilder drift = new StringBuilder();
        for (var e : EVICT_SIZE.entrySet()) {
            Path p = Path.of("..", e.getKey());
            if (!Files.exists(p)) {
                continue;   // evicted whole — delete the row when seen
            }
            long lines = Files.readAllLines(p).size();
            if (lines > e.getValue()) {
                drift.append("\n  ").append(e.getKey()).append(": ")
                        .append(lines).append(" > ").append(e.getValue())
                        .append(" lines — the evaluator GREW (tenet #1:"
                                + " the database executes; evict, or bump"
                                + " the pin with a written justification)");
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
