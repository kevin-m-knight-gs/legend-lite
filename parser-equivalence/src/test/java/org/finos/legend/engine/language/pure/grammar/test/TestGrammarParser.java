// CLASSPATH SHADOW of the engine's parser test base — see
// TestGrammarRoundtrip's header. The identifier-inclusion generator is
// REPLICATED (not skipped): its synthesized keyword-as-identifier
// fixtures are real grammar-edge coverage.
package org.finos.legend.engine.language.pure.grammar.test;

import com.legend.equivalence.harvest.FixtureRecorder;
import org.antlr.v4.runtime.Vocabulary;
import org.finos.legend.engine.protocol.pure.v1.model.context.PureModelContextData;
import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

public class TestGrammarParser {

    public abstract static class TestGrammarParserTestSuite {

        public abstract Vocabulary getParserGrammarVocabulary();

        public List<Vocabulary> getDelegatedParserGrammarVocabulary() {
            return new ArrayList<>();
        }

        public abstract String getParserGrammarIdentifierInclusionTestCode(
                List<String> keywords);

        @Test
        public void testParserGrammarIdentifierInclusion() {
            List<Vocabulary> vocabularies = new ArrayList<>();
            vocabularies.add(this.getParserGrammarVocabulary());
            vocabularies.addAll(this.getDelegatedParserGrammarVocabulary());
            LinkedHashSet<String> keywords = new LinkedHashSet<>();
            for (Vocabulary vocabulary : vocabularies) {
                for (int i = 0; i < vocabulary.getMaxTokenType(); ++i) {
                    String literal = vocabulary.getLiteralName(i);
                    if (literal != null && literal.length() > 2
                            && literal.startsWith("'")
                            && literal.endsWith("'")) {
                        String word = literal.substring(1,
                                literal.length() - 1);
                        if (word.matches("[A-Za-z0-9_][A-Za-z0-9_$]*")) {
                            keywords.add(word);
                        }
                    }
                }
            }
            String testCode = this.getParserGrammarIdentifierInclusionTestCode(
                    new ArrayList<>(keywords));
            if (testCode != null) {
                test(testCode);
            }
        }

        protected static PureModelContextData test(String val) {
            FixtureRecorder.record(val, null, "parser");
            return null;
        }

        protected static void test(String val, String expectedErrorMsg) {
            FixtureRecorder.record(val, expectedErrorMsg, "parser-error");
        }
    }

    public static void testFromJson(Class<?> _class, String path,
            String code) {
        FixtureRecorder.record(code, null, "parser-json");
    }

    public static String getJsonString(Class<?> _class, String path) {
        return "";
    }
}
