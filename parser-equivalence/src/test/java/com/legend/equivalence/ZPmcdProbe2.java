package com.legend.equivalence;

import org.finos.legend.engine.language.pure.grammar.from.PureGrammarParser;
import org.junit.jupiter.api.Test;

/** PROBE (PMCD build): section SPAN formula + importAware/default per
 *  parser. Diagnostic only. */
class ZPmcdProbe2 {

    private void sections(String label, String src) throws Exception {
        var mapper = org.finos.legend.engine.shared.core.ObjectMapperFactory
                .getNewStandardObjectMapperWithPureProtocolExtensionSupports();
        try {
            var pmcd = PureGrammarParser.newInstance().parseModel(src);
            for (var e : pmcd.getElements()) {
                if (!"SectionIndex".equals(e.name)) {
                    continue;
                }
                var json = mapper.readTree(mapper.writeValueAsString(e));
                StringBuilder out = new StringBuilder();
                for (var s : json.get("sections")) {
                    var si = s.get("sourceInformation");
                    out.append(String.format(" %s/%s[%d:%d-%d:%d]",
                            s.get("parserName").asText(),
                            s.get("_type").asText(),
                            si.get("startLine").asInt(),
                            si.get("startColumn").asInt(),
                            si.get("endLine").asInt(),
                            si.get("endColumn").asInt()));
                }
                System.out.println("@@ " + label + " ::" + out);
            }
        } catch (Throwable t) {
            System.out.println("@@ " + label + " REJECTED: "
                    + String.valueOf(t.getMessage()).replaceAll("\\s+", " "));
        }
    }

    @Test
    void spanFormula() throws Exception {
        // no trailing newline after last element
        sections("s1", "Class a::A\n{\n}");
        // trailing newline
        sections("s2", "Class a::A\n{\n}\n");
        // header at line 1, content, EOF
        sections("s3", "###Text\nText t::T\n{\n  type: STRING;\n  content: 'x';\n}\n");
        // two headers back to back, different lengths
        sections("s4", "###Text\nText t::T\n{\n  type: STRING;\n  content: 'x';\n}\n"
                + "###Pure\nClass a::A\n{\n}\n");
        // blank lines between sections
        sections("s5", "Class a::A\n{\n}\n\n\n###Pure\n\nClass b::B\n{\n}\n");
        // trailing spaces after last brace, no newline
        sections("s6", "###Pure\nClass a::A\n{\n}   ");
    }

    @Test
    void importAwareByParser2() throws Exception {
        sections("persistence", "###Persistence\nPersistence p::P\n{\n"
                + "  trigger: Manual;\n  service: s::S;\n  serviceOutputTargets: [];\n}\n");
        sections("dataspace", "###DataSpace\nDataSpace d::D\n{\n"
                + "  executionContexts: [];\n  defaultExecutionContext: 'x';\n}\n");
        sections("servicestore", "###ServiceStore\nServiceStore s::S\n(\n)\n");
        sections("filegen", "###FileGeneration\nAvro f::F\n{\n}\n");
        sections("snowflake", "###Snowflake\nSnowflakeApp a::A\n{\n"
                + "  applicationName: 'x';\n  function: f::F():String[1];\n"
                + "  ownership: Deployment { identifier: 'i' };\n}\n");
        sections("hosted", "###HostedService\nHostedService h::H\n{\n"
                + "  pattern: '/x';\n  ownership: Deployment { identifier: 'i' };\n"
                + "  function: f::F():String[1];\n  documentation: 'd';\n"
                + "  autoActivateUpdates: true;\n}\n");
        sections("text2", "###Text\nText t::T\n{\n  type: STRING;\n"
                + "  content: 'x';\n}\n###Text\nText t::U\n{\n  type: STRING;\n"
                + "  content: 'y';\n}\n");
        sections("qpp", "###QueryPostProcessor\n");
    }

    @Test
    void importAwareByParser() throws Exception {
        sections("runtime", "###Runtime\nRuntime r::R\n{\n  mappings: [];\n}\n");
        sections("connection", "###Connection\nJsonModelConnection c::C\n{\n"
                + "  class: my::A;\n  url: 'x';\n}\n");
        sections("service", "###Service\nService s::S\n{\n  pattern: '/x';\n"
                + "  documentation: 'd';\n  execution: Single\n  {\n"
                + "    query: |1;\n    mapping: m::M;\n    runtime: r::R;\n  }\n}\n");
        sections("externalFormat", "###ExternalFormat\nBinding b::B\n{\n"
                + "  contentType: 'application/json';\n  modelIncludes: [ my::A ];\n}\n");
        sections("data", "###Data\nData d::D\n{\n  ExternalFormat\n  #{\n"
                + "    contentType: 'application/json';\n    data: '[]';\n  }#\n}\n");
        sections("diagram", "###Diagram\nDiagram d::D\n{\n}\n");
        sections("genspec", "###GenerationSpecification\n"
                + "GenerationSpecification g::G\n{\n}\n");
    }
}
