// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.equivalence;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.finos.legend.engine.language.pure.grammar.from.PureGrammarParser;
import org.finos.legend.engine.shared.core.ObjectMapperFactory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * THE SELF-TEST (HARNESS_SIMPLIFICATION_PLAN Phase 0): a known-different
 * pair MUST report a difference. The audit hardwired every comparator to
 * "equal" and the whole chain stayed green — this test is what goes red.
 * Replacing any {@link Comparators} body with {@code return true} fails
 * here.
 */
class ComparatorSelfTest {

    @Test
    void aOneByteDeltaReportsDifferent() {
        assertTrue(Comparators.sameBytes("{\"a\":1}", "{\"a\":1}"));
        assertFalse(Comparators.sameBytes("{\"a\":1}", "{\"a\":2}"),
                "the byte comparator called a one-byte delta equal");
        assertFalse(Comparators.sameBytes("{\"a\":1}", "{\"a\":1} "),
                "the byte comparator ignored a length delta");
    }

    @Test
    void membershipProofRejectsARealDelta() throws Exception {
        ObjectMapper mapper = ObjectMapperFactory
                .getNewStandardObjectMapperWithPureProtocolExtensionSupports();
        PureGrammarParser oracle = PureGrammarParser.newInstance();
        String a = mapper.writeValueAsString(
                oracle.parseModel("Class a::A{}\n"));
        String b = mapper.writeValueAsString(
                oracle.parseModel("Class a::B{}\n"));
        // the engine's round-trip of its own bytes IS its own fixed point
        assertTrue(Comparators.engineSerializeOnlyDelta(mapper, a,
                        mapper.writeValueAsString(mapper.readTree(a)
                                .traverse(mapper).readValueAs(
                                        org.finos.legend.engine.protocol.pure
                                                .v1.model.context
                                                .PureModelContextData.class))),
                "the membership proof refused the engine's own fixed point");
        // a SEMANTIC delta must never pass the membership proof
        assertFalse(Comparators.engineSerializeOnlyDelta(mapper, a, b),
                "the membership proof laundered a semantic delta into the"
                        + " serialize-only bucket");
        // garbage input must classify as NOT-member, never throw-through
        assertFalse(Comparators.engineSerializeOnlyDelta(mapper,
                "not json", b));
    }
}
