package com.legend.equivalence;

import org.finos.legend.engine.language.pure.grammar.from.PureGrammarParser;
import org.junit.jupiter.api.Test;

/** Wire-shape probe for ###Diagram — the last OUT_OF_SCOPE bucket.
 *
 * <p>THE FINDING these probes record: there are TWO diagram grammars in the
 * corpus, and the pinned oracle only speaks one of them.
 *
 * <ul>
 *   <li><b>Legacy</b> — {@code TypeView cview_1(type=..., color=#FFFFCC,
 *       lineWidth=1.0)}. 55 real {@code .pure} files use it. The pinned
 *       vanilla parser REJECTS it ("Unexpected token '('"), so no element
 *       in those files can ever be compared — see {@link #realCorpusDiagram}.
 *       This is the form whose {@code #FFFFCC} literal Lexer.LEXABLE_SECTIONS
 *       cites as unlexable Pure.</li>
 *   <li><b>Current</b> — {@code classView <uuid> { class: X; position: (x,y);
 *       rectangle: (w,h); }} plus {@code propertyView { property: C.p;
 *       source:; target:; points: []; }}. NO color literals. All 10
 *       comparable elements are this form, and every one of them comes from
 *       an inline snippet in the engine's own diagram test modules.</li>
 * </ul>
 *
 * <p>Measured: 70 corpus sources carry a ###Diagram; the reference accepts 9
 * and rejects 61, and those rejections also cost ~112 non-diagram elements
 * (Classes and friends in the same files) that never enter the denominator.
 * The current form is not cleanly lexable either — a classView id is a UUID,
 * whose hyphens lex as minus under Pure rules — so this leg belongs on the
 * registry's opaque-grammar path rather than Lexer.LEXABLE_SECTIONS. */
class ZDiagramProbe {

    private void probe(String label, String src) throws Exception {
        var mapper = org.finos.legend.engine.shared.core.ObjectMapperFactory
                .getNewStandardObjectMapperWithPureProtocolExtensionSupports();
        try {
            var pmcd = PureGrammarParser.newInstance().parseModel(src);
            for (var e : pmcd.getElements()) {
                String json = mapper.writeValueAsString(e);
                if (json.contains("SectionIndex")) {
                    continue;
                }
                System.out.println("== " + label + " :: " + e.getPath());
                System.out.println(json);
            }
        } catch (Throwable t) {
            System.out.println("== " + label + " REJECTED: "
                    + String.valueOf(t.getMessage()).replaceAll("\\s+", " "));
        }
    }

    /** A REAL corpus {@code .pure} diagram (legacy TypeView form): the
     *  pinned oracle REJECTS it, which is why 61 of 70 diagram files are
     *  REFERENCE_REJECTED and none of their elements are comparable. */
    @Test
    void realCorpusDiagram() throws Exception {
        java.nio.file.Path f = Corpus.engineRoot().resolve(
                "legend-engine-core/legend-engine-core-pure/"
                + "legend-engine-pure-code-compiled-core/src/main/resources/"
                + "core/pure/binding/schemaSet/metamodel_diagram.pure");
        probe("diagram-real", java.nio.file.Files.readString(f));
    }

    /** The CURRENT form — what all 10 comparable elements look like, and
     *  therefore the only diagram grammar this leg has to implement. */
    @Test
    void currentForm() throws Exception {
        probe("diagram-current", """
                ###Diagram
                Diagram model::Diag
                {
                  classView 24ec35ba-8656-4561-93c5-c77a84ba5f4f
                  {
                    class: model::C1;
                    position: (342.0,136.0);
                    rectangle: (68.30224609375,44.0);
                  }
                  propertyView
                  {
                    property: model::A.c1;
                    source: 24ec35ba-8656-4561-93c5-c77a84ba5f4f;
                    target: 24ec35ba-8656-4561-93c5-c77a84ba5f4f;
                    points: [(549.669921875,252.0),(376.151123046875,158.0)];
                  }
                }

                ###Pure
                Class model::C1 { }
                Class model::A { c1: model::C1[1]; }
                """);
    }

    /** The empty body — the degenerate envelope. */
    @Test
    void bareHeader() throws Exception {
        probe("diagram-bare", """
                ###Diagram
                Diagram my::D
                {
                }
                """);
    }
}
