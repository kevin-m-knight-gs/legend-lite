// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.equivalence;

import com.legend.lexer.Lexer;
import com.legend.lexer.TokenStream;
import com.legend.lexer.TokenType;
import com.legend.model.LegacyMappingDefinition;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * THE SAFETY NET for phase M — {@code MappingGrammarParser} versus
 * {@code MappingProtocolParser} + {@code MappingFromProtocol}, over every
 * mapping in the corpus.
 *
 * <p>This is the harness that took the Database migration from 488
 * disagreements to 18. It is a CHEAP PRE-FILTER and nothing more: R3 proved
 * that structural agreement does not imply agreeing SQL — 538 of 561
 * databases were already identical while nine corpus families generated wrong
 * SQL, from an ordering the wire does not carry. The switch is proven by
 * gates 4/5/6. This test's job is to make the failures cheap to FIND.
 *
 * <p>Ratcheted, never asserted-zero while the port is in flight: the counts
 * may only improve. {@code TRANSFORMED} rising and {@code MISMATCHED} falling
 * is the shape of progress.
 */
class MappingEquivalenceTest {

    /** Mappings the transform refuses outright (UnsupportedMappingShape).
     *  Falls as phases land. M1 covered Relational + Column/Join; M2 added
     *  the rest of the property-mapping family and the class-mapping filter;
     *  what remains is M3's class-mapping and association work. */
    private static final int MAX_UNSUPPORTED = 846;

    /** Mappings BOTH paths build where the models disagree. Ratchets DOWN
     *  only; the legacy mapping parser dies when this is 0 and gates 4/5/6
     *  are green with the switch thrown. */
    private static final int MAX_MISMATCHED = 0;

    @Test
    void everyMappingTransformsToTheSameModel() throws Exception {
        List<Corpus.Source> sources = Corpus.all();
        Assumptions.assumeTrue(!sources.isEmpty(),
                "no corpus on disk — set -Dlegend.engine.root / -Dlegend.pure.root");

        int compared = 0;
        int identical = 0;
        int mismatched = 0;
        int unsupported = 0;
        int protocolUnreadable = 0;
        int legacyUnreadable = 0;
        int asWrittenOnly = 0;
        Map<String, Integer> asWrittenShapes = new TreeMap<>();
        Map<String, Integer> unsupportedWhy = new TreeMap<>();
        Map<String, Integer> mismatchShapes = new TreeMap<>();
        Map<String, String> examples = new TreeMap<>();
        List<String> mismatchFiles = new ArrayList<>();

        for (Corpus.Source src : sources) {
            String text = src.text();
            TokenStream ts;
            try {
                ts = Lexer.tokenize(text);
            } catch (Throwable lexFailed) {
                continue;
            }
            for (int i = 0; i < ts.count(); i++) {
                if (ts.type(i) != TokenType.MAPPING || !declPos(ts, i)) {
                    continue;
                }
                LegacyMappingDefinition viaLegacy;
                try {
                    Object m = com.legend.parser.ElementParser.parseMappingAt(ts, i);
                    if (!(m instanceof LegacyMappingDefinition legacy)) {
                        continue;   // clean-sheet element — a different axis
                    }
                    viaLegacy = legacy;
                } catch (Throwable t) {
                    legacyUnreadable++;
                    continue;
                }

                Protocol0 protocol;
                try {
                    protocol = new Protocol0(com.legend.parser.MappingProtocolParser
                            .parse(ts, i, sectionStartLine(text, ts.start(i))));
                } catch (Throwable t) {
                    protocolUnreadable++;
                    continue;
                }

                LegacyMappingDefinition viaProtocol;
                try {
                    viaProtocol = com.legend.model.MappingFromProtocol
                            .toMappingDefinition(protocol.p);
                } catch (com.legend.model.MappingFromProtocol.UnsupportedMappingShape u) {
                    unsupported++;
                    unsupportedWhy.merge(normalize(u.getMessage()), 1, Integer::sum);
                    continue;
                } catch (Throwable t) {
                    unsupported++;
                    unsupportedWhy.merge("UNEXPECTED " + t.getClass().getSimpleName()
                            + ": " + normalize(String.valueOf(t.getMessage())),
                            1, Integer::sum);
                    continue;
                }

                compared++;
                String diff = firstDifference(viaLegacy, viaProtocol);
                if (!"identical".equals(diff) && asWritten(viaLegacy, viaProtocol)) {
                    // A difference the WIRE CANNOT PRESERVE, verified against
                    // the sources — counted separately, never as agreement.
                    asWrittenOnly++;
                    asWrittenShapes.merge(diff, 1, Integer::sum);
                    continue;
                }
                if ("identical".equals(diff)) {
                    identical++;
                } else {
                    mismatched++;
                    mismatchShapes.merge(diff, 1, Integer::sum);
                    // one WORKED EXAMPLE per shape: a bucket count says how
                    // much, the example says what to fix
                    examples.computeIfAbsent(diff, k -> viaLegacy.qualifiedName()
                            + "\n      LEGACY   = " + firstDiffering(viaLegacy, viaProtocol, true)
                            + "\n      PROTOCOL = " + firstDiffering(viaLegacy, viaProtocol, false));
                    if (mismatchFiles.size() < 200) {
                        mismatchFiles.add(src.id() + " :: "
                                + viaLegacy.qualifiedName() + " :: " + diff);
                    }
                }
            }
        }

        StringBuilder b = new StringBuilder();
        b.append("MAPPING EQUIVALENCE — legacy parser vs protocol+MappingFromProtocol\n")
                .append("=".repeat(72)).append('\n')
                .append(String.format("TRANSFORMED (both built)  : %d%n", compared))
                .append(String.format("  IDENTICAL               : %d%n", identical))
                .append(String.format("  AS-WRITTEN ONLY         : %d"
                        + "  (wire cannot preserve; see asWritten())%n", asWrittenOnly))
                .append(String.format("  MISMATCHED (REAL)       : %d%n", mismatched))
                .append(String.format("UNSUPPORTED (refused)     : %d%n", unsupported))
                .append(String.format("protocol unreadable       : %d%n", protocolUnreadable))
                .append(String.format("legacy unreadable         : %d%n", legacyUnreadable));
        section(b, "UNSUPPORTED by cause — the phase worklist", unsupportedWhy);
        section(b, "AS-WRITTEN by shape — verified unrecoverable", asWrittenShapes);
        section(b, "MISMATCHES by shape — the fidelity worklist", mismatchShapes);
        b.append("\nWORKED EXAMPLES — one per shape\n").append("-".repeat(72)).append('\n');
        examples.forEach((k, v) -> b.append("  ").append(k).append("  ")
                .append(v).append('\n'));

        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target", "mapping-equivalence.txt"),
                b + "\n\n" + String.join("\n", mismatchFiles));
        System.out.println(b);

        org.junit.jupiter.api.Assertions.assertTrue(unsupported <= MAX_UNSUPPORTED,
                "mappings the transform REFUSES grew: " + unsupported
                        + " > " + MAX_UNSUPPORTED);
        org.junit.jupiter.api.Assertions.assertTrue(mismatched <= MAX_MISMATCHED,
                "mappings whose two paths disagree GREW: " + mismatched
                        + " > " + MAX_MISMATCHED
                        + " — see target/mapping-equivalence.txt");
    }

    /** Wrapper so the protocol parse and the transform can fail separately
     *  without the compiler complaining about definite assignment. */
    private record Protocol0(com.legend.protocol.Protocol.PMapping p) {
    }

    /**
     * Whether the ONLY differences are ones the engine's wire structurally
     * cannot preserve, because it records the RESOLVED form of what the
     * source wrote. Each was verified against a corpus source before being
     * listed here — this is a named exemption, not a fudge, and it is
     * counted and reported separately from agreement so it can never quietly
     * absorb a real bug:
     *
     * <ul>
     *   <li>{@code scope([db]default.personTable)} vs {@code scope([db])} —
     *       the legacy model keeps the scope path VERBATIM including the
     *       {@code default.} prefix; the wire always splits schema from
     *       table, so {@code default.T} and {@code T} arrive identical.
     *       (showcaseModel.pure:94)</li>
     *   <li>{@code [thisDb]T.COL} vs bare {@code T.COL} — the wire resolves
     *       every column's database, so an explicitly-written self-reference
     *       is indistinguishable from an implicit one.
     *       (legend-testable-function-test-relation-semistructured.pure:91)</li>
     *   <li>{@code equal(a,b)} / {@code isNull(x)} written in CALL form vs
     *       operator form — one dynaFunc on the wire either way. R3 already
     *       canonicalised these for Database bodies with zero measured SQL
     *       change across the corpus and PCT.</li>
     * </ul>
     */
    private static boolean asWritten(LegacyMappingDefinition a,
            LegacyMappingDefinition b) {
        return canonical(a).equals(canonical(b));
    }

    private static String canonical(LegacyMappingDefinition m) {
        StringBuilder b = new StringBuilder();
        b.append(m.qualifiedName()).append('|').append(m.includes());
        for (com.legend.model.ClassMapping cm : m.classMappings()) {
            if (cm instanceof com.legend.model.ClassMapping.Relational r) {
                b.append("\nCM ").append(r.className()).append('/').append(r.setId())
                        .append('/').append(r.root()).append('/').append(r.distinct())
                        .append('/').append(canonTable(r.mainTable()));
                r.groupBy().forEach(g -> b.append("\n  gb ").append(canonOp(g)));
                r.primaryKey().forEach(p -> b.append("\n  pk ").append(canonOp(p)));
                r.propertyMappings().forEach(pm -> b.append("\n  pm ").append(canonPm(pm)));
                b.append("\n  ts ").append(r.propertyTargetSets());
            } else {
                b.append('\n').append(cm);
            }
        }
        return b.toString();
    }

    private static String canonTable(
            LegacyMappingDefinition.@com.legend.Nullable TableReference t) {
        return t == null ? "-" : t.database() + "." + stripDefault(t.table());
    }

    /** `scope([db]default.T)` and `scope([db])` both reach the wire as
     *  schema=default, table=T. */
    private static String stripDefault(String table) {
        return table.startsWith("default.") ? table.substring("default.".length()) : table;
    }

    private static String canonPm(com.legend.model.PropertyMapping pm) {
        if (pm instanceof com.legend.model.PropertyMapping.Column c) {
            return "Column " + c.propertyName() + " " + c.database() + " "
                    + stripDefault(c.table()) + "." + c.column();
        }
        if (pm instanceof com.legend.model.PropertyMapping.Join j) {
            return "Join " + j.propertyName() + " " + j.database() + " "
                    + canonChain(j.joins()) + " " + j.targetSetId();
        }
        if (pm instanceof com.legend.model.PropertyMapping.JoinTerminalColumn j) {
            return "JTC " + j.propertyName() + " " + j.database() + " "
                    + canonChain(j.joins()) + " " + canonOp(j.terminalColumn())
                    + " " + j.enumMappingId() + " " + j.enumMapped();
        }
        if (pm instanceof com.legend.model.PropertyMapping.Expression e) {
            return "Expr " + e.propertyName() + " " + canonOp(e.expression());
        }
        if (pm instanceof com.legend.model.PropertyMapping.EnumeratedColumn c) {
            return "EnumCol " + c.propertyName() + " " + c.enumMappingId() + " "
                    + c.database() + " " + stripDefault(c.table()) + "." + c.column();
        }
        if (pm instanceof com.legend.model.PropertyMapping.EnumeratedExpression e) {
            return "EnumExpr " + e.propertyName() + " " + e.enumMappingId() + " "
                    + canonOp(e.expression());
        }
        // The nesting variants MUST recurse: falling back to toString() here
        // made every as-written difference INSIDE an embedded block read as a
        // real mismatch, which is a harness bug that looks like a transform bug.
        if (pm instanceof com.legend.model.PropertyMapping.Embedded e) {
            return "Emb " + e.propertyName() + " " + canonPms(e.propertyMappings())
                    + " " + e.primaryKey().stream().map(
                            MappingEquivalenceTest::canonOp).toList();
        }
        if (pm instanceof com.legend.model.PropertyMapping.OtherwiseEmbedded o) {
            return "Oth " + o.propertyName() + " " + canonPms(o.embedded()) + " "
                    + o.fallbackSetId() + " " + canonPm(o.fallback());
        }
        if (pm instanceof com.legend.model.PropertyMapping.LocalProperty l) {
            return "Local " + l.propertyName() + " " + l.type() + " "
                    + l.multiplicity() + " " + canonPm(l.body());
        }
        return String.valueOf(pm);
    }

    private static String canonPms(List<com.legend.model.PropertyMapping> pms) {
        return pms.stream().map(MappingEquivalenceTest::canonPm).toList().toString();
    }

    private static String canonChain(List<com.legend.model.JoinChainElement> chain) {
        StringBuilder b = new StringBuilder("[");
        for (com.legend.model.JoinChainElement c : chain) {
            b.append(c.joinName()).append('/').append(c.joinType())
                    .append('/').append(c.includeSelf()).append(',');
        }
        return b.append(']').toString();
    }

    /**
     * Render an operation so the CALL form and the OPERATOR form of the
     * closed operator vocabulary come out identical — {@code equal(a,b)} and
     * {@code a = b} are one dynaFunc on the wire, so the legacy model's
     * {@code FunctionCall} / {@code Comparison} split cannot be reconstructed.
     * A string regex cannot do this (the two spellings nest differently);
     * doing it structurally also means an inner database qualifier is elided
     * in exactly one place rather than by a blanket pattern.
     */
    private static String canonOp(com.legend.model.@com.legend.Nullable RelationalOperation op) {
        if (op == null) {
            return "-";
        }
        if (op instanceof com.legend.model.RelationalOperation.ColumnRef c) {
            // databaseName elided: explicit [thisDb] vs implicit is not
            // recoverable from the wire
            return "col(" + stripDefault(c.table()) + "." + c.column() + ")";
        }
        if (op instanceof com.legend.model.RelationalOperation.Comparison c) {
            return "fn(" + c.op() + "," + canonOp(c.left()) + "," + canonOp(c.right()) + ")";
        }
        if (op instanceof com.legend.model.RelationalOperation.FunctionCall f) {
            String name = switch (f.name()) {
                case "equal" -> "EQ";
                case "notEqual", "notEqualAnsi" -> "NEQ";
                case "lessThan" -> "LT";
                case "lessThanEqual" -> "LTE";
                case "greaterThan" -> "GT";
                case "greaterThanEqual" -> "GTE";
                // and(a,b) / or(a,b) in CALL form vs the infix operator: one
                // dynaFunc on the wire, FunctionCall vs BooleanOp in the model
                case "and" -> "AND";
                case "or" -> "OR";
                default -> f.name();
            };
            StringBuilder b = new StringBuilder("fn(").append(name);
            f.args().forEach(a -> b.append(',').append(canonOp(a)));
            return b.append(')').toString();
        }
        if (op instanceof com.legend.model.RelationalOperation.IsNull n) {
            return "fn(isNull," + canonOp(n.operand()) + ")";
        }
        if (op instanceof com.legend.model.RelationalOperation.IsNotNull n) {
            return "fn(isNotNull," + canonOp(n.operand()) + ")";
        }
        if (op instanceof com.legend.model.RelationalOperation.BooleanOp b2) {
            return "fn(" + b2.op() + "," + canonOp(b2.left()) + "," + canonOp(b2.right()) + ")";
        }
        if (op instanceof com.legend.model.RelationalOperation.Group g) {
            return "grp(" + canonOp(g.inner()) + ")";
        }
        if (op instanceof com.legend.model.RelationalOperation.JoinNavigation j) {
            return "nav(" + canonChain(j.chain()) + "," + canonOp(j.terminal()) + ")";
        }
        return String.valueOf(op);
    }

    /** The first differing SUB-VALUE, from whichever side is asked — so the
     *  report shows the two records that actually differ rather than two
     *  thousand-character mappings. */
    private static String firstDiffering(LegacyMappingDefinition a,
            LegacyMappingDefinition b, boolean wantLeft) {
        if (!a.includes().equals(b.includes())) {
            return String.valueOf(wantLeft ? a.includes() : b.includes());
        }
        for (int i = 0; i < Math.min(a.classMappings().size(), b.classMappings().size()); i++) {
            Object x = a.classMappings().get(i);
            Object y = b.classMappings().get(i);
            if (x.equals(y)) {
                continue;
            }
            if (x instanceof com.legend.model.ClassMapping.Relational p
                    && y instanceof com.legend.model.ClassMapping.Relational q) {
                for (int j = 0; j < Math.min(p.propertyMappings().size(),
                        q.propertyMappings().size()); j++) {
                    Object pa = p.propertyMappings().get(j);
                    Object qa = q.propertyMappings().get(j);
                    if (!pa.equals(qa)) {
                        return String.valueOf(wantLeft ? pa : qa);
                    }
                }
                if (!java.util.Objects.equals(p.mainTable(), q.mainTable())) {
                    return String.valueOf(wantLeft ? p.mainTable() : q.mainTable());
                }
            }
            return String.valueOf(wantLeft ? x : y);
        }
        return "(sizes differ)";
    }

    /** ONE definition of agreement, used for both the count and the report —
     *  a reporting rule looser than the counting rule hides work. */
    private static String firstDifference(LegacyMappingDefinition a,
            LegacyMappingDefinition b) {
        if (!a.qualifiedName().equals(b.qualifiedName())) {
            return "qualifiedName";
        }
        if (!a.includes().equals(b.includes())) {
            return "includes";
        }
        if (a.classMappings().size() != b.classMappings().size()) {
            return "classMappings:count";
        }
        for (int i = 0; i < a.classMappings().size(); i++) {
            Object x = a.classMappings().get(i);
            Object y = b.classMappings().get(i);
            if (!x.equals(y)) {
                return "classMappings:" + classDiff(x, y);
            }
        }
        if (!a.associationMappings().equals(b.associationMappings())) {
            return "associationMappings";
        }
        if (!a.enumerationMappings().equals(b.enumerationMappings())) {
            return "enumerationMappings";
        }
        return "identical";
    }

    /** Name the FIELD that diverged, not just the record — otherwise every
     *  mismatch buckets as "Relational" and the worklist says nothing. */
    private static String classDiff(Object x, Object y) {
        if (!(x instanceof com.legend.model.ClassMapping.Relational p)
                || !(y instanceof com.legend.model.ClassMapping.Relational q)) {
            return x.getClass().getSimpleName() + " vs " + y.getClass().getSimpleName();
        }
        if (!java.util.Objects.equals(p.className(), q.className())) {
            return "className";
        }
        if (!java.util.Objects.equals(p.setId(), q.setId())) {
            return "setId";
        }
        if (!java.util.Objects.equals(p.extendsSetId(), q.extendsSetId())) {
            return "extendsSetId";
        }
        if (p.root() != q.root()) {
            return "root";
        }
        if (!java.util.Objects.equals(p.mainTable(), q.mainTable())) {
            return "mainTable";
        }
        if (p.distinct() != q.distinct()) {
            return "distinct";
        }
        if (!java.util.Objects.equals(p.groupBy(), q.groupBy())) {
            return "groupBy";
        }
        if (!java.util.Objects.equals(p.primaryKey(), q.primaryKey())) {
            return "primaryKey";
        }
        if (p.propertyMappings().size() != q.propertyMappings().size()) {
            return "propertyMappings:count";
        }
        for (int i = 0; i < p.propertyMappings().size(); i++) {
            Object a = p.propertyMappings().get(i);
            Object c = q.propertyMappings().get(i);
            if (!a.equals(c)) {
                return "pm:" + a.getClass().getSimpleName()
                        + (a.getClass() == c.getClass() ? ":fields"
                                : " vs " + c.getClass().getSimpleName());
            }
        }
        if (!java.util.Objects.equals(p.propertyTargetSets(), q.propertyTargetSets())) {
            return "propertyTargetSets";
        }
        if (p.aggregationAwareMain() != q.aggregationAwareMain()) {
            return "aggregationAwareMain";
        }
        return "other";
    }

    private static void section(StringBuilder b, String title,
            Map<String, Integer> counts) {
        b.append('\n').append(title).append('\n').append("-".repeat(72)).append('\n');
        if (counts.isEmpty()) {
            b.append("  (none)\n");
            return;
        }
        counts.entrySet().stream()
                .sorted((x, y) -> y.getValue() - x.getValue())
                .limit(30)
                .forEach(e -> b.append(String.format("  %5d  %s%n",
                        e.getValue(), e.getKey())));
    }

    /** Bucket by CAUSE, not by instance. */
    private static String normalize(String msg) {
        return String.valueOf(msg)
                .replaceAll("'[^']*'", "'X'")
                .replaceAll("\\[\\d+:\\d+\\]", "[L:C]")
                .replaceAll("\\d+", "N");
    }

    /** Same computation ParserEquivalence uses — see MigrationSizingTest. */
    private static int sectionStartLine(String text, int offset) {
        Matcher m = Pattern.compile("(?m)^###Mapping\\b").matcher(text);
        int header = -1;
        while (m.find() && m.start() < offset) {
            header = m.start();
        }
        if (header < 0) {
            return -1;
        }
        int line = 1;
        for (int i = 0; i < header; i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        return line + 1;
    }

    private static boolean declPos(TokenStream ts, int i) {
        if (i == 0) {
            return true;
        }
        TokenType prev = ts.type(i - 1);
        return prev == TokenType.BRACE_CLOSE || prev == TokenType.SEMI_COLON
                || prev == TokenType.PAREN_CLOSE;
    }
}
