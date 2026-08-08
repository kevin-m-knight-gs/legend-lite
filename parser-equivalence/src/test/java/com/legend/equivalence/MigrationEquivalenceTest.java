package com.legend.equivalence;

import com.legend.lexer.Lexer;
import com.legend.lexer.TokenStream;
import com.legend.lexer.TokenType;
import com.legend.model.DatabaseDefinition;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * THE SAFETY NET for finishing the protocol-first migration
 * (PARSER_COMPLETENESS_PLAN.md §1).
 *
 * <p>The legacy {@code RelationalGrammarParser} builds the model the COMPILER
 * runs on, so replacing it with {@code DatabaseProtocolParser} +
 * {@code FromProtocol} is a change to what SQL gets generated. Arguing that the
 * transform is faithful is worth nothing; this test requires it, over every
 * database in the corpus, before the legacy parser may be deleted.
 *
 * <p>Both paths run on the same token stream. The models must be EQUAL — the
 * records are values, so equality is structural and a single wrong operator
 * anywhere in a join condition fails the run with the file that proves it.
 *
 * <p>Ratcheted rather than asserted-zero while the port is in flight: the
 * mismatch count may only fall, and reaching zero is the precondition for
 * deleting {@code RelationalGrammarParser}.
 */
class MigrationEquivalenceTest {

    /** Databases whose two paths still disagree. Ratchets DOWN only; the
     *  legacy parser dies when this is 0 and the switch is thrown. */
    private static final int MAX_MISMATCHED_DATABASES = 18;
    // 488 -> 25 -> 18 as the transform learned what the wire does NOT say: the
    // synthetic "default" schema, resolved-vs-as-written database names,
    // schema-qualified table names, milestoning, the {target} marker,
    // right-associative and/or, and quoted identifiers.
    //
    // 25 -> 18: a JoinNavigation rooted in the ENCLOSING database was left
    // self-qualified, though columnRef fifteen lines above already applied
    // the as-written rule. That one is a plain transform bug, not a floor —
    // which is why "principled floor" claims are worth re-deriving, not
    // inheriting.
    //
    // THE REMAINING 18 ARE A PRINCIPLED FLOOR, not unfinished work. They are
    // all AS-WRITTEN vs CANONICAL differences the wire structurally cannot
    // preserve, because the engine's protocol records the resolved form:
    //
    //   * `[thisDb]t.col` vs bare `t.col` — the legacy model keeps the
    //     database name the source wrote; the wire always resolves it, so a
    //     self-reference is indistinguishable from an unqualified one.
    //   * `isNull(x)` vs the operator spelling — the legacy model builds
    //     FunctionCall for one and IsNull for the other; both are one
    //     dynaFunc on the wire. Folding to match one spelling breaks the
    //     other, measured: 25 -> 27.
    //
    // Every one denotes the same thing, so the switchover cannot be proven by
    // structural equality alone past this point. It is proven by gates 4/5/6 —
    // the corpus and PCT sweeps — which compare generated SQL, which is the
    // property that actually matters.

    @Test
    void everyDatabaseParsesIdenticallyThroughBothPaths() throws Exception {
        List<Corpus.Source> sources = Corpus.all();
        Assumptions.assumeTrue(!sources.isEmpty(),
                "no corpus on disk — set -Dlegend.engine.root / -Dlegend.pure.root");

        int compared = 0;
        int equal = 0;
        int mismatched = 0;
        int legacyOnlyReadable = 0;
        int protocolOnlyReadable = 0;
        Map<String, Integer> mismatchShapes = new TreeMap<>();
        List<String> mismatchFiles = new ArrayList<>();

        for (Corpus.Source src : sources) {
            TokenStream ts;
            try {
                ts = Lexer.tokenize(src.text());
            } catch (Throwable lexFailed) {
                continue;
            }
            for (int i = 0; i < ts.count(); i++) {
                if (ts.type(i) != TokenType.DATABASE || !declPos(ts, i)) {
                    continue;
                }
                DatabaseDefinition viaLegacy = null;
                DatabaseDefinition viaProtocol = null;
                try {
                    viaLegacy = com.legend.parser.ElementParser
                            .parseDatabaseAt(ts, i);
                } catch (Throwable ignored) {
                    // legacy cannot read it — sized separately
                }
                try {
                    viaProtocol = com.legend.model.FromProtocol.toDatabaseDefinition(
                            com.legend.parser.DatabaseProtocolParser.parse(ts, i));
                } catch (Throwable ignored) {
                    // protocol cannot read it — sized separately
                }
                if (viaLegacy == null && viaProtocol == null) {
                    continue;
                }
                if (viaLegacy == null) {
                    protocolOnlyReadable++;
                    continue;
                }
                if (viaProtocol == null) {
                    legacyOnlyReadable++;
                    continue;
                }
                compared++;
                if (equivalent(viaLegacy, viaProtocol)) {
                    equal++;
                } else {
                    mismatched++;
                    String shape = firstDifference(viaLegacy, viaProtocol);
                    mismatchShapes.merge(shape, 1, Integer::sum);
                    if (mismatchFiles.size() < 200) {
                        mismatchFiles.add(src.id() + " :: " + viaLegacy.qualifiedName()
                                + " :: " + shape);
                    }
                }
            }
        }

        StringBuilder b = new StringBuilder();
        b.append("MIGRATION EQUIVALENCE — legacy model parser vs protocol+FromProtocol\n")
                .append("=".repeat(72)).append('\n')
                .append(String.format("databases compared     : %d%n", compared))
                .append(String.format("  IDENTICAL            : %d%n", equal))
                .append(String.format("  MISMATCHED           : %d%n", mismatched))
                .append(String.format("legacy-only readable   : %d%n", legacyOnlyReadable))
                .append(String.format("protocol-only readable : %d%n", protocolOnlyReadable));
        b.append("\nMISMATCHES by shape\n").append("-".repeat(72)).append('\n');
        mismatchShapes.entrySet().stream()
                .sorted((x, y) -> y.getValue() - x.getValue())
                .limit(30)
                .forEach(e -> b.append(String.format("  %5d  %s%n",
                        e.getValue(), e.getKey())));
        Files.writeString(Path.of("target", "migration-equivalence.txt"),
                b.toString() + "\n\n" + String.join("\n", mismatchFiles));
        System.out.println(b);

        org.junit.jupiter.api.Assertions.assertTrue(
                mismatched <= MAX_MISMATCHED_DATABASES,
                "databases whose two parse paths disagree GREW: " + mismatched
                        + " > " + MAX_MISMATCHED_DATABASES
                        + " — see target/migration-equivalence.txt");
    }

    /** ONE definition of agreement, used for both the count and the report —
     *  a reporting rule looser than the counting rule hides work. */
    private static boolean equivalent(DatabaseDefinition a, DatabaseDefinition b) {
        return "identical".equals(firstDifference(a, b));
    }

    /** Which field diverged first — enough to bucket the work. */
    private static String firstDifference(DatabaseDefinition a, DatabaseDefinition b) {
        if (!a.qualifiedName().equals(b.qualifiedName())) {
            return "qualifiedName";
        }
        if (!a.includes().equals(b.includes())) {
            return "includes";
        }
        if (!a.schemas().equals(b.schemas())) {
            return "schemas";
        }
        // The flat tables/views lists are a LOOKUP MIRROR of the schemas'
        // contents: the legacy parser appends them in SOURCE order, the wire
        // groups them by schema, and that order is not reconstructible or
        // semantic. Content is still required to match exactly, and each
        // schema's own list IS compared in order above — so nothing is hidden
        // by comparing these as multisets.
        if (!sameElements(a.tables(), b.tables())) {
            return "tables";
        }
        if (!sameElements(a.views(), b.views())) {
            return "views";
        }
        if (!a.joins().equals(b.joins())) {
            return "joins";
        }
        if (!a.filters().equals(b.filters())) {
            return "filters";
        }
        if (!a.multiGrainFilters().equals(b.multiGrainFilters())) {
            return "multiGrainFilters";
        }
        return "identical";
    }

    private static boolean sameElements(java.util.List<?> x, java.util.List<?> y) {
        if (x.size() != y.size()) {
            return false;
        }
        java.util.List<Object> remaining = new java.util.ArrayList<>(y);
        for (Object o : x) {
            if (!remaining.remove(o)) {
                return false;
            }
        }
        return true;
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
