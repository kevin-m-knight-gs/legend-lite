// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.equivalence;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.finos.legend.engine.language.pure.grammar.from.PureGrammarParser;
import org.finos.legend.engine.language.pure.grammar.from.extension.PureGrammarParserExtensions;
import org.finos.legend.engine.shared.core.ObjectMapperFactory;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The QUOTED-IMPORT byte case no corpus file exercises
 * (HARNESS_SIMPLIFICATION_PLAN 5e): {@code import a::'b c'::*;} must
 * emit {@code ["a::b c"]} through the SPI bridge exactly as vanilla
 * does — the bridge's local import loop had dropped
 * {@code Protocol.unquotePath}. A fixture-level oracle in the
 * {@code ViewFilterParityTest} shape: hand-built input, adjudicated
 * LIVE against the engine.
 */
class QuotedImportParityTest {

    @Test
    void quotedImportSegmentUnquotesOnTheWire() throws Exception {
        String src = "import a::'b c'::*;\nClass x::Y{}\n";
        ObjectMapper mapper = ObjectMapperFactory
                .getNewStandardObjectMapperWithPureProtocolExtensionSupports();
        PureGrammarParser vanilla = PureGrammarParser.newInstance();
        PureGrammarParser spi = PureGrammarParser.newInstance(
                PureGrammarParserExtensions.fromExtensions(
                        List.of(LegendLiteSectionParser.extension())));
        assertEquals(mapper.writeValueAsString(vanilla.parseModel(src)),
                mapper.writeValueAsString(spi.parseModel(src)));
    }
}
