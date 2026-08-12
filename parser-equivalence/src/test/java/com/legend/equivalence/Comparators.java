// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.equivalence;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.finos.legend.engine.protocol.pure.v1.model.context.PureModelContextData;

/**
 * THE load-bearing comparisons of the equivalence harness, in ONE place so
 * {@code ComparatorSelfTest} can prove they are capable of reporting a
 * difference (HARNESS_SIMPLIFICATION_PLAN Phase 0: with every comparator
 * hardwired to "equal" the whole chain reported green — nothing tested
 * that the testers can fail).
 *
 * <p>Every byte verdict in gate 8 must flow through {@link #sameBytes};
 * the engine-JSON-asymmetry bucket's membership proof through
 * {@link #engineSerializeOnlyDelta}. Diagnostics (first-divergence
 * printers) stay local to their tests — they are not load-bearing.
 */
final class Comparators {

    private Comparators() {
    }

    /** The byte claim. */
    static boolean sameBytes(String a, String b) {
        return a.equals(b);
    }

    /** Membership proof for the engine serialize-only bucket: the delta is
     *  attributable to the ENGINE's serializer iff the engine's own JSON
     *  round-trip (readTree -> protocol -> serialize) of ITS OWN output
     *  produces OUR bytes. */
    static boolean engineSerializeOnlyDelta(ObjectMapper mapper,
            String engineJson, String ourJson) {
        try {
            return ourJson.equals(mapper.writeValueAsString(
                    mapper.readTree(engineJson).traverse(mapper)
                            .readValueAs(PureModelContextData.class)));
        } catch (java.io.IOException e) {
            return false;
        }
    }
}
