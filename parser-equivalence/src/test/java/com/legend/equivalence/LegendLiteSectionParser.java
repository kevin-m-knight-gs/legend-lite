package com.legend.equivalence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.legend.lexer.Lexer;
import com.legend.lexer.TokenStream;
import com.legend.lexer.TokenType;
import com.legend.parser.ElementParser;
import org.finos.legend.engine.language.pure.grammar.from.SectionSourceCode;
import org.finos.legend.engine.language.pure.grammar.from.extension.PureGrammarParserExtension;
import org.finos.legend.engine.language.pure.grammar.from.extension.SectionParser;
import org.finos.legend.engine.protocol.pure.m3.PackageableElement;
import org.finos.legend.engine.protocol.pure.v1.model.packageableElement.section.ImportAwareCodeSection;
import org.finos.legend.engine.shared.core.ObjectMapperFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * THE SEAM PROOF: legend-lite parsing behind the engine's own {@code SectionParser}
 * extension point. The bridge is deliberately dumb — parse with the STRICT drop-in
 * surface, emit the byte-parity JSON, shift spans by the section's walker offsets, and
 * let the ENGINE'S ObjectMapper build its protocol objects. Correctness rides entirely
 * on the proven byte parity plus the engine's own JSON-first architecture (its SDLC flow
 * compiles from JSON-deserialized protocol as a matter of course).
 */
final class LegendLiteSectionParser {

    private LegendLiteSectionParser() {
    }

    /** Exact-decimal tree reading: floats become BigDecimal-backed nodes WITHOUT the
     *  default node factory's stripTrailingZeros normalization, so a source {@code 10.10}
     *  survives readTree → treeToValue byte-exactly (engine's CDecimal deserializes via
     *  {@code new BigDecimal(node.asText())}). */
    private static final ObjectMapper MAPPER =
            ObjectMapperFactory.getNewStandardObjectMapperWithPureProtocolExtensionSupports()
                    .enable(com.fasterxml.jackson.databind.DeserializationFeature
                            .USE_BIG_DECIMAL_FOR_FLOATS)
                    .setNodeFactory(com.fasterxml.jackson.databind.node.JsonNodeFactory
                            .withExactBigDecimals(true));

    /** The extension: replaces the engine's DomainParser for {@code ###Pure}. */
    static PureGrammarParserExtension extension() {
        return new PureGrammarParserExtension() {
            @Override
            public Iterable<? extends SectionParser> getExtraSectionParsers() {
                return List.of(SectionParser.newParser("Pure",
                        LegendLiteSectionParser::parseSection));
            }
        };
    }

    private static ImportAwareCodeSection parseSection(SectionSourceCode section,
            java.util.function.Consumer<PackageableElement> elementConsumer,
            org.finos.legend.engine.language.pure.grammar.from.PureGrammarParserContext ctx) {
        ImportAwareCodeSection out = new ImportAwareCodeSection();
        out.parserName = "Pure";
        out.sourceInformation = section.sourceInformation;

        String sourceId = section.walkerSourceInformation.getSourceId();
        int lineOffset = section.walkerSourceInformation.getLineOffset();

        TokenStream ts = Lexer.tokenize(section.code);

        // leading imports: import a::b::*;
        int pos = 0;
        while (pos < ts.count() && ts.type(pos) == TokenType.IMPORT) {
            StringBuilder path = new StringBuilder();
            pos++;
            while (pos < ts.count() && ts.type(pos) != TokenType.SEMI_COLON) {
                if (ts.type(pos) != TokenType.STAR
                        || !path.toString().endsWith("::")) {
                    path.append(ts.text(pos));
                }
                pos++;
            }
            pos++;                                   // past ';'
            String p = path.toString();
            if (p.endsWith("::*")) {
                p = p.substring(0, p.length() - 3);
            } else if (p.endsWith("::")) {
                p = p.substring(0, p.length() - 2);
            }
            out.imports.add(p);
        }

        // ENGINE PARITY: the Legend flavor refuses native functions ('Unsupported
        // syntax') — legend-lite's own dialect keeps them, so the drop-in surface
        // rejects here at the seam
        for (int i : ElementParser.topLevelIndexes(ts, TokenType.NATIVE)) {
            throw new RuntimeException(
                    "Unsupported syntax: native declarations are not supported in Legend"
                            + " (token " + i + ")");
        }
        // ENGINE 4.133.0 PARITY: the pinned vanilla jar predates the
        // ''' documentation sugar (#5008) — accepting these files would
        // silently DROP the doc block (the site scan skips junk); reject
        // as a unit until the sugar is implemented against updated jars
        for (int i = 0; i < ts.count(); i++) {
            if (ts.type(i) == TokenType.DOC_STRING) {
                throw new RuntimeException("Unsupported syntax:"
                        + " documentation literal (''' ... ''') — doc.doc"
                        + " sugar pending (token " + i + ")");
            }
        }

        // element sites in SOURCE order — the engine emits interleaved kinds as written
        record Site(int tok, TokenType kind) {
        }
        List<Site> sites = new ArrayList<>();
        for (TokenType marker : new TokenType[]{TokenType.CLASS, TokenType.ENUM,
                TokenType.PROFILE, TokenType.ASSOCIATION, TokenType.FUNCTION}) {
            for (int i : ElementParser.topLevelIndexes(ts, marker)) {
                sites.add(new Site(i, marker));
            }
        }
        for (int i : ElementParser.measureSites(ts)) {
            sites.add(new Site(i, TokenType.VALID_STRING));
        }
        sites.sort(java.util.Comparator.comparingInt(Site::tok));

        for (Site site : sites) {
            ElementParser p = ElementParser.at(ts, site.tok());
            com.legend.protocol.Protocol.Element el;
            String path;
            switch (site.kind()) {
                case CLASS -> {
                    var c = p.parseClassDefinition(false);
                    el = c;
                    path = c.qualifiedName();
                }
                case ENUM -> {
                    var e = p.parseEnumDefinition();
                    el = e;
                    path = e.qualifiedName();
                }
                case PROFILE -> {
                    var pr = p.parseProfileDefinition();
                    el = pr;
                    path = pr.qualifiedName();
                }
                case ASSOCIATION -> {
                    var a = p.parseAssociationDefinition();
                    el = a;
                    path = a.qualifiedName();
                }
                case VALID_STRING -> {
                    var me = p.parseMeasureDefinition();
                    el = me;
                    path = me.qualifiedName();
                }
                default -> {
                    var f = p.parseFunctionProtocol();
                    el = f;
                    path = f.pkg().isEmpty() ? f.mangledName()
                            : f.pkg() + "::" + f.mangledName();
                }
            }
            try {
                String json = com.legend.protocol.ProtocolEmitter.emitElement(el);
                JsonNode tree = MAPPER.readTree(json);
                shiftSpans(tree, sourceId, lineOffset);
                elementConsumer.accept(MAPPER.treeToValue(tree, PackageableElement.class));
                out.elements.add(path);
            } catch (Exception e) {
                throw new RuntimeException("SPI bridge failed on " + path, e);
            }
        }
        return out;
    }

    /** Our spans are 1-based within the section text with sourceId "" — the section's
     *  walker carries the file line offset and real sourceId (section columnOffset is 0
     *  by the engine's own construction). */
    private static void shiftSpans(JsonNode node, String sourceId, int lineOffset) {
        if (node instanceof ObjectNode obj) {
            obj.fields().forEachRemaining(e -> {
                // every span key: sourceInformation, profileSourceInformation, ...
                if (e.getKey().endsWith("ourceInformation")
                        && e.getValue() instanceof ObjectNode s
                        && s.has("startLine")) {
                    s.put("sourceId", sourceId);
                    s.put("startLine", s.get("startLine").asInt() + lineOffset);
                    s.put("endLine", s.get("endLine").asInt() + lineOffset);
                } else {
                    shiftSpans(e.getValue(), sourceId, lineOffset);
                }
            });
        } else if (node.isArray()) {
            node.forEach(c -> shiftSpans(c, sourceId, lineOffset));
        }
    }
}
