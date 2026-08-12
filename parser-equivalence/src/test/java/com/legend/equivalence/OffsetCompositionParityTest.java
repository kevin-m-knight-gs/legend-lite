// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.equivalence;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.finos.legend.engine.language.pure.grammar.from.PureGrammarParser;
import org.finos.legend.engine.shared.core.ObjectMapperFactory;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * FIXTURE-LEVEL ORACLES for span/offset COMPOSITION
 * (HARNESS_SIMPLIFICATION_PLAN "not in scope" note, adopted): the audit
 * proved one island-offset mutation survives the ENTIRE corpus because
 * no corpus file reaches the branch — more corpus will never find that
 * fault class. These are hand-built inputs chosen to stress island
 * nesting, mid-line island opens, and walker-offset composition, each
 * adjudicated LIVE against the engine byte-for-byte (the
 * ViewFilterParityTest shape).
 */
class OffsetCompositionParityTest {

    private static final Map<String, String> FIXTURES = new LinkedHashMap<>();

    static {
        // island opening MID-LINE, content sharing the opener's line
        FIXTURES.put("midline-island", """
                ###Pure
                Class a::A { x: String[1]; }
                ###Mapping
                Mapping a::M
                (
                  a::A: Pure { ~src a::A x: $src.x }
                )
                """);
        // EMBEDDED runtime island inside a service, connection island
        // NESTED inside it — offsets compose across two island layers
        FIXTURES.put("nested-islands", """
                ###Service
                Service a::S
                {
                  pattern: '/x';
                  documentation: 'd';
                  execution: Single
                  {
                    query: |1;
                    mapping: a::M;
                    runtime:
                    #{
                      connections:
                      [
                        ModelStore:
                        [
                          id1:
                          #{
                            JsonModelConnection
                            {
                              class: a::A;
                              url: 'u';
                            }
                          }#
                        ]
                      ];
                    }#;
                  }
                }
                """);
        // island content whose LAST line is the '}#' line — the
        // end-column path where an offset can silently drop
        FIXTURES.put("island-end-on-close", """
                ###Persistence
                PersistenceContext a::PC
                {
                  persistence: a::P;
                  platform: Default;
                }
                Persistence a::P
                {
                  doc: 'd';
                  trigger: Manual;
                  service: a::S;
                  persister: Streaming { sink: Relational { database: a::DB; } }
                }
                """);
        // section content beginning on the SAME line count as a
        // multi-section file with blank-line skew before headers
        FIXTURES.put("blankline-skew", """


                ###Relational
                Database a::DB ( Table T ( ID INTEGER PRIMARY KEY ) )


                ###Pure

                Class a::A { x: String[1]; }
                """);
        // enumeration-mapping island values at high columns
        FIXTURES.put("enum-mapping-columns", """
                ###Pure
                Enum a::E { X, Y }
                ###Mapping
                Mapping a::M
                (
                  a::E: EnumerationMapping em { X: ['x1', 'x2'], Y: ['y'] }
                )
                """);
    }

    @Test
    void handBuiltOffsetFixturesByteMatchTheLiveOracle() throws Exception {
        ObjectMapper mapper = ObjectMapperFactory
                .getNewStandardObjectMapperWithPureProtocolExtensionSupports();
        PureGrammarParser oracle = PureGrammarParser.newInstance();
        for (Map.Entry<String, String> f : FIXTURES.entrySet()) {
            String expected;
            try {
                expected = mapper.writeValueAsString(
                        oracle.parseModel(f.getValue()));
            } catch (Throwable t) {
                throw new AssertionError("fixture '" + f.getKey()
                        + "' must be ORACLE-ACCEPTED (fix the fixture): "
                        + t.getMessage(), t);
            }
            String actual = com.legend.parser.PmcdParser
                    .parseDocument(f.getValue());
            assertEquals(expected, actual,
                    "offset-composition fixture '" + f.getKey() + "'");
        }
    }
}
