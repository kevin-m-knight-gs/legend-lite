// CLASSPATH SHADOW of the engine's compilation test base — see
// TestGrammarRoundtrip's header. Compilation fixtures are PARSE-accepted
// models (their errors are COMPILE errors), so they feed accept-parity;
// the expectedError rides along for the future compile-parity story.
package org.finos.legend.engine.language.pure.compiler.test;

import com.legend.equivalence.harvest.FixtureRecorder;
import org.eclipse.collections.api.tuple.Pair;
import org.eclipse.collections.impl.tuple.Tuples;
import org.finos.legend.engine.language.pure.compiler.toPureGraph.PureModel;
import org.finos.legend.engine.protocol.pure.v1.model.context.PureModelContextData;
import org.junit.Test;

import java.util.List;

public class TestCompilationFromGrammar {

    public abstract static class TestCompilationFromGrammarTestSuite {

        protected abstract String getDuplicatedElementTestCode();

        protected abstract String getDuplicatedElementTestExpectedErrorMessage();

        @Test
        public void testDuplicatedElement() {
            test(this.getDuplicatedElementTestCode(),
                    this.getDuplicatedElementTestExpectedErrorMessage());
        }

        public static Pair<PureModelContextData, PureModel> test(String str) {
            return test(str, null);
        }

        public static Pair<PureModelContextData, PureModel> test(String str,
                String expectedErrorMsg) {
            FixtureRecorder.record(str, expectedErrorMsg,
                    expectedErrorMsg == null ? "compile" : "compile-error");
            return Tuples.pair(null, null);
        }

        public static Pair<PureModelContextData, PureModel> test(String str,
                String expectedErrorMsg, List<String> expectedWarnings) {
            FixtureRecorder.record(str, expectedErrorMsg,
                    expectedErrorMsg == null ? "compile" : "compile-error");
            return Tuples.pair(null, null);
        }

        public static Pair<PureModelContextData, PureModel>
                partialCompilationTest(String str) {
            FixtureRecorder.record(str, null, "compile-partial");
            return Tuples.pair(null, null);
        }

        public static Pair<PureModelContextData, PureModel>
                partialCompilationTest(String str,
                        List<String> expectedEngineExceptions) {
            FixtureRecorder.record(str, null, "compile-partial");
            return Tuples.pair(null, null);
        }

        public static Pair<PureModelContextData, PureModel>
                partialCompilationTest(String str,
                        List<String> expectedEngineExceptions,
                        List<String> expectedWarnings) {
            FixtureRecorder.record(str, null, "compile-partial");
            return Tuples.pair(null, null);
        }
    }
}
