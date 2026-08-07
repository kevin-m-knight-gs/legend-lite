package com.legend.equivalence;

import com.legend.equivalence.ParserEquivalence.Kind;
import com.legend.equivalence.ParserEquivalence.Verdict;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The composition pin (implementation audit item 13, &sect;1.1/&sect;1.3):
 * legend-pure's {@code grammar/tests/composition.pure} &mdash; the ONE file
 * upstream wrote to test operator precedence &mdash; with its PCT headers
 * ({@code <Z|y>} type/multiplicity parameters) stripped so the reference
 * parser ACCEPTS it instead of rejecting the whole file
 * (&sect;2.2: as shipped, every function's PCT header makes the engine answer
 * "Type and/or multiplicity parameters are not authorized" and the gate saw
 * {@code REFERENCE_REJECTED}, which is not a failure &mdash; green on the
 * exact corpus written to test precedence). Stripping means two probed
 * substitutions per function, leaving every assertion body VERBATIM:
 * {@code <Z|y>} deleted, and the parameter
 * {@code f:Function<{Function<{->Z[y]}>[1]->Z[y]}>[1]} &rarr;
 * {@code f:Function<Any>[1]}, because the engine rejects EVERY
 * function-type-literal parameter ("The type {...->...} is not supported
 * yet"), not just the PCT one.
 *
 * <p>Byte parity on every element through {@link ParserEquivalence} makes the
 * &sect;1.1 boolean-then-arithmetic shape and the &sect;1.3 interrupted-run
 * shape gate-visible: before the parse-time fix, two of the five precedence
 * assertions DIFF'd here.
 */
class ZCompositionPin {

    @Test
    void compositionPureMatchesEngineByteForByte() throws Exception {
        String text = new String(
                Objects.requireNonNull(
                        ZCompositionPin.class.getResourceAsStream(
                                "/composition-pct-stripped.pure"),
                        "composition-pct-stripped.pure missing from test resources")
                        .readAllBytes(),
                StandardCharsets.UTF_8);

        ParserEquivalence eq = new ParserEquivalence();
        List<Verdict> verdicts = eq.compare(
                new Corpus.Source("pinned/composition-pct-stripped.pure", text, "pinned"));

        long matches = verdicts.stream().filter(v -> v.kind() == Kind.MATCH).count();
        List<Verdict> offenders = verdicts.stream()
                .filter(v -> v.kind() != Kind.MATCH)
                .toList();
        assertTrue(offenders.isEmpty(),
                "composition.pure must be byte-identical on every element:\n"
                        + offenders.stream()
                                .map(v -> "  " + v.kind() + " " + v.element()
                                        + "\n      " + v.detail())
                                .reduce("", (a, b) -> a + b + "\n"));
        // 6 functions — the run must have done work (anti-false-green rule 1)
        assertEquals(6, matches,
                "expected all 6 composition functions compared and matched");
    }
}
