// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * THE SECOND-SQL-PRODUCER RATCHET (ADVERSARIAL_TENET_AUDIT_2026_08_18
 * §8, fix-plan item 10): AGENTS.md invariants 2/3/3a say there is ONE
 * path from HIR to SQL — Lowerer &rarr; typed MIR &rarr;
 * {@code dialect.render()}. Production code outside {@code sql/dialect/}
 * that builds SQL as STRING TEXT is a shadow producer the dialect cannot
 * retarget, {@code SqlPostProcessors} cannot rewrite, and no backend
 * swap reaches.
 *
 * <p>The target is NOT zero — DDL, {@code information_schema} catalog
 * queries, and plan-envelope engine text have no MIR representation and
 * are the decision rule's permitted classes. The target is ENUMERATED
 * AND ARGUED: every site below is registered per file, shrink-only.
 * Growth is a new shadow SQL producer and needs either a route through
 * the compiler or a conscious pin bump with a written justification.
 * The registered residue's durable fix is DELETION — the relation-typed
 * {@code fetchDb} leg retires the GridReads/DbMetaData rows wholesale.
 *
 * <p>Counting rule (comment-stripped source, string literals only):
 * a literal is a SQL-construction site when it matches a
 * select-from / leading-DDL-keyword / catalog / union-all / limit-n /
 * lateral shape and carries no Pure {@code ::} path. The rule is
 * deliberately high-precision over high-recall — a missed spelling is
 * drift the next audit catches; a noisy pin rots trust in the register.
 */
class SqlTextRatchetTest {

    private static final Pattern LITERAL =
            Pattern.compile("\"(?:[^\"\\\\]|\\\\.)*\"");

    private static final Pattern SQL_SHAPE = Pattern.compile(
            "(?i)(\\bselect\\s+\\S.*\\s+from\\b"
            + "|^\"\\s*(select|create table|insert into|drop table"
            + "|delete from|alter table)\\s+\\S"
            + "|information_schema|\\bunion all\\s|\\blimit \\d"
            + "|\\bleft lateral\\s)");

    /** The register: file &rarr; exact SQL-text site count, measured
     * 2026-08-18 (audit §8 seeded 33 with a narrower rule; this rule
     * also counts Ddl — the one registered DDL owner — and
     * StatementExecutor's engine-text sites). */
    private static final Map<String, Integer> REGISTER =
            new LinkedHashMap<>();

    static {
        REGISTER.put("StatementExecutor.java", 2);
        REGISTER.put("exec/CsvSeed.java", 4);
        // Phase 1c: DbMetaData's 9 catalog-SQL sites moved verbatim to
        // compiler/spec/CatalogGrids (the Typer's fetchDb retype; pure
        // text composition, no JDBC)
        REGISTER.put("compiler/spec/CatalogGrids.java", 9);
        // 3 -> 4 (metamodel-store leg 2026-08-28): metamodelSeed's
        // registry-extent INSERT joins the one DDL owner — system
        // setup text beside the create/drop it already renders
        REGISTER.put("exec/Ddl.java", 4);
        REGISTER.put("plan/InProtocol.java", 1);
        REGISTER.put("plan/PlanText.java", 2);
        // 17 -> 16 (documented-debts 2026-08-18): the LL_TMP_DEBUG
        // COUNT(*) round-trip died
        REGISTER.put("testdatagen/TestDataGenerator.java", 16);
    }

    @Test
    void sqlTextOutsideTheDialectLayerOnlyShrinks() throws IOException {
        Path root = Path.of("..", "core/src/main/java/com/legend");
        Map<String, Integer> actual = new TreeMap<>();
        int scanned = 0;
        try (Stream<Path> s = Files.walk(root)) {
            for (Path p : s.filter(f -> f.toString().endsWith(".java"))
                    .toList()) {
                String rel = root.toAbsolutePath().normalize()
                        .relativize(p.toAbsolutePath().normalize())
                        .toString().replace('\\', '/');
                if (rel.startsWith("sql/dialect/")) {
                    continue;
                }
                scanned++;
                String src = Files.readString(p)
                        .replaceAll("//.*", "")
                        .replaceAll("(?s)/\\*.*?\\*/", "");
                int n = 0;
                Matcher m = LITERAL.matcher(src);
                while (m.find()) {
                    String lit = m.group();
                    if (!lit.contains("::") && SQL_SHAPE.matcher(lit)
                            .find()) {
                        n++;
                    }
                }
                if (n > 0) {
                    actual.put(rel, n);
                }
            }
        }
        // coverage self-assertion (audit item 7): the walk must keep
        // seeing the production tree it guards
        assertTrue(scanned >= 250, "SQL-text ratchet coverage DROPPED:"
                + " scanned " + scanned + " production files (floor 250)"
                + " — the walk root moved; re-point before trusting it");

        StringBuilder drift = new StringBuilder();
        for (var e : actual.entrySet()) {
            Integer pinned = REGISTER.get(e.getKey());
            if (pinned == null) {
                drift.append("\n  NEW shadow SQL producer: ")
                        .append(e.getKey()).append(" (").append(
                                e.getValue())
                        .append(" sites) — route through the compiler"
                                + " (Lowerer -> MIR -> dialect.render)"
                                + " or register with a justification");
            } else if (!pinned.equals(e.getValue())) {
                drift.append("\n  ").append(e.getKey()).append(": ")
                        .append(e.getValue())
                        .append(e.getValue() > pinned ? " > " : " < ")
                        .append(pinned)
                        .append(e.getValue() > pinned
                                ? " — SQL text GREW outside the dialect"
                                        + " layer"
                                : " — sites died: shrink this pin");
            }
        }
        for (String f : REGISTER.keySet()) {
            if (!actual.containsKey(f)) {
                drift.append("\n  ").append(f)
                        .append(" builds no SQL text anymore — delete"
                                + " its register row");
            }
        }
        assertTrue(drift.length() == 0,
                "shadow-SQL-producer drift (AGENTS.md invariants 2/3/3a;"
                + " audit 2026-08-18 §8):" + drift);
    }
}
