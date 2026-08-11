// CLASSPATH SHADOW of the engine's roundtrip test base (same FQN wins
// over the tests-jar): every fixture the engine's suites assemble flows
// through these statics — we RECORD instead of asserting. The fixtures
// are adjudicated downstream by the production oracle (corpus tier C6).
package org.finos.legend.engine.language.pure.grammar.test;

import com.legend.equivalence.harvest.FixtureRecorder;
import org.finos.legend.engine.shared.core.api.grammar.RenderStyle;

public class TestGrammarRoundtrip {

    public abstract static class TestGrammarRoundtripTestSuite {

        protected static final com.fasterxml.jackson.databind.ObjectMapper
                objectMapper = org.finos.legend.engine.shared.core
                .ObjectMapperFactory
                .getNewStandardObjectMapperWithPureProtocolExtensionSupports();

        public static void test(String code) {
            FixtureRecorder.record(code, null, "roundtrip");
        }

        public static void test(String code, RenderStyle renderStyle) {
            FixtureRecorder.record(code, null, "roundtrip");
        }

        public static void testWithSectionInfoPreserved(String code) {
            FixtureRecorder.record(code, null, "roundtrip");
        }

        public static void testSectionWithPureGrammar(String code,
                boolean isPureGrammar) {
            FixtureRecorder.record(code, null, "roundtrip");
        }

        public void testTo(String protocolResource, String expectedCode) {
            FixtureRecorder.record(expectedCode, null, "roundtrip-to");
        }

        protected void testComposedGrammar(String protocol, String expected) {
            FixtureRecorder.record(expected, null, "roundtrip-composed");
        }
    }
}
