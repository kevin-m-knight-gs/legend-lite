// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.parser;

import com.legend.lexer.Lexer;
import com.legend.lexer.TokenStream;
import com.legend.lexer.TokenType;
import com.legend.protocol.Protocol;
import com.legend.protocol.ProtocolEmitter;
import com.legend.protocol.SourceInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The WHOLE-DOCUMENT drop-in surface: source text in, the engine's full
 * PMCD JSON out — envelope {@code {"_type":"data","elements":[...]}},
 * elements in SOURCE order, and the {@code __internal__::SectionIndex}
 * appended last, exactly as {@code PureGrammarParser.parseModel} builds it
 * (ZPmcdProbe / ZPmcdProbe2).
 *
 * <p>ENGINE-STRICT throughout: every element parses through the strict
 * surface ({@code ElementParser.at}), and an unregistered section refuses
 * in the engine's own words. Site discovery walks the ONE lexer's token
 * stream per section range — the section list comes from the lexer's own
 * header records, never a second text scan.
 *
 * <p>Section spans reproduce the engine's coordinate system: the engine
 * parses {@code "\n###Pure\n" + code} (PureGrammarParser.DEFAULT_SECTION_
 * BEGIN) and reports section spans in that BUFFER's coordinates, where a
 * SECTION_START token INCLUDES its leading newline. See
 * {@link #sectionSpan}.
 */
public final class PmcdParser {

    private PmcdParser() {
    }

    /** Parsers whose sections are {@code importAware} on the wire — from
     *  the engine's own extension sources (Default vs ImportAware
     *  CodeSection) plus ZPmcdProbe2; everything else emits
     *  {@code default}. */
    private static final Set<String> IMPORT_AWARE = Set.of("Pure", "Mapping",
            "Runtime", "Connection", "Service", "ExternalFormat", "Diagram",
            "GenerationSpecification", "FileGeneration", "HostedService",
            "QueryPostProcessor", "FunctionJar", "Persistence",
            "Deephaven", "Elasticsearch");

    /** Tail sections (site kind 12) — mirror of the registry's
     *  elementwise grammars. */
    private static final List<String> TAIL_SECTIONS = List.of("Text",
            "GenerationSpecification", "FileGeneration", "Deephaven",
            "MongoDB", "DataQualityValidation", "Elasticsearch",
            "ExternalFormat", "ServiceStore", "DataSpace", "Persistence",
            "Service", "QueryPostProcessor");

    private static final Set<String> ACTIVATOR_SECTIONS = Set.of("Snowflake",
            "MemSql", "BigQuery", "HostedService", "FunctionJar");

    private static final Set<String> ACTIVATOR_KINDS = Set.of("SnowflakeApp",
            "SnowflakeM2MUdf", "MemSqlFunction", "BigQueryFunction",
            "HostedService", "FunctionJar", "DeephavenApp");

    private static final com.legend.parser.section.FunctionActivatorSectionGrammar
            ACTIVATOR_GRAMMAR =
            new com.legend.parser.section.FunctionActivatorSectionGrammar(
                    "<activator>", ACTIVATOR_KINDS);

    /** One parsed element: its wire JSON and the path the sectionIndex
     *  lists (functions use their MANGLED path, like the engine). */
    public record DocElement(String path, String json) {
    }

    /** One document section with its parsed elements and imports. */
    public record DocSection(String parserName, List<DocElement> elements,
                             List<String> imports, SourceInfo span) {
    }

    /** Parse {@code source} into the engine's full PMCD JSON. */
    public static String parseDocument(String source) {
        List<DocSection> sections = parseSections(source);
        StringBuilder b = new StringBuilder("{\"_type\":\"data\",\"elements\":[");
        boolean first = true;
        for (DocSection s : sections) {
            for (DocElement e : s.elements()) {
                if (!first) {
                    b.append(',');
                }
                first = false;
                b.append(e.json());
            }
        }
        List<Protocol.PSection> pSections = new ArrayList<>();
        for (DocSection s : sections) {
            List<String> paths = new ArrayList<>();
            for (DocElement e : s.elements()) {
                // the sectionIndex lists each path ONCE even when a source
                // declares it twice (TestSectionRoundtrip duplicates); the
                // envelope keeps every element
                if (!paths.contains(e.path())) {
                    paths.add(e.path());
                }
            }
            pSections.add(new Protocol.PSection(
                    IMPORT_AWARE.contains(s.parserName()), s.parserName(),
                    paths, s.imports(), s.span()));
        }
        if (!first) {
            b.append(',');
        }
        b.append(ProtocolEmitter.emitElement(new Protocol.PSectionIndex(
                "__internal__", "SectionIndex", pSections)));
        return b.append("]}").toString();
    }

    /** The ordered section list with parsed elements — the assembly
     *  {@link #parseDocument} serializes. */
    public static List<DocSection> parseSections(String source) {
        TokenStream ts = Lexer.tokenize(source);

        // section boundaries from the LEXER's records: every ###X header in
        // source order; the implicit leading section is Pure
        record Bounds(String name, int headerStart, int contentStart) {
        }
        List<Bounds> bounds = new ArrayList<>();
        List<TokenStream.SectionHeader> headers = ts.sectionHeaders();
        bounds.add(new Bounds("Pure", -1, 0));
        for (TokenStream.SectionHeader h : headers) {
            bounds.add(new Bounds(h.name(), h.nameOffset() - 3,
                    h.contentStartOffset()));
        }

        List<DocSection> out = new ArrayList<>();
        for (int i = 0; i < bounds.size(); i++) {
            Bounds bd = bounds.get(i);
            // the newline directly before a '###' header belongs to that
            // header's SECTION_START token — content stops BEFORE it
            int contentEnd = i + 1 < bounds.size()
                    ? bounds.get(i + 1).headerStart() - 1 : source.length();
            SourceInfo span = sectionSpan(ts, source, bd.name(),
                    bd.headerStart(), contentEnd);
            long[] range = {bd.contentStart(), contentEnd};
            List<DocElement> elements = sectionElements(ts, source,
                    bd.name(), range, bd.headerStart());
            List<String> imports;
            if ("Diagram".equals(bd.name())) {
                // a RAW section has no tokens — its own parse carries the
                // imports (TestDiagramGrammarRoundtrip)
                imports = diagramImports(source, range);
            } else {
                imports = IMPORT_AWARE.contains(bd.name())
                        ? sectionImports(ts, range) : List.of();
            }
            out.add(new DocSection(bd.name(), elements, imports, span));
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Section spans — the engine's buffer coordinates (see class doc)
    // ------------------------------------------------------------------

    /** The engine reports each section over the buffer
     *  {@code "\n###Pure\n" + source}: bufferLine = userLine + 2; a
     *  section starts at its SECTION_START's leading newline (the
     *  synthetic prefix for the implicit section) and stops at the LAST
     *  content character (the newline before the next header belongs to
     *  the NEXT section's token); an EMPTY section stops at its own
     *  header token's end. */
    private static SourceInfo sectionSpan(TokenStream ts, String source,
            String name, int headerStart, int contentEnd) {
        int startLine;
        int startCol;
        if (headerStart < 0) {
            startLine = 1;                          // synthetic '\n###Pure'
            startCol = 1;
        } else if (headerStart == 0) {
            startLine = 2;                          // '\n' of the synthetic
            startCol = 8;                           // after '###Pure'
        } else {
            int nl = headerStart - 1;               // the header's own '\n'
            startLine = ts.lineOf(nl) + 2;
            startCol = ts.columnOf(nl);
        }
        int lastContent = contentEnd - 1;
        // the newline directly before a following header belongs to that
        // header's token, and CR before it stays content; content begins
        // right AFTER '###'+name — the trailing newline is content
        int firstContent = headerStart < 0 ? 0
                : headerStart + 3 + name.length();
        if (headerStart < 0 && lastContent < 0) {
            // the SYNTHETIC prefix's trailing newline is the implicit
            // section's content — UNLESS the source's first char is a
            // header, whose SECTION_START token consumes it
            // (garmanKlassVolatility vs s3, ZPmcdProbe2)
            return lastContent == -1 ? new SourceInfo("", 1, 1, 2, 8)
                    : new SourceInfo("", 1, 1, 1, 8);
        }
        if (lastContent < firstContent) {
            // EMPTY section: span = its own SECTION_START token
            int len = 4 + name.length();            // '\n###' + name
            if (headerStart == 0) {
                // the leading '\n' is the synthetic prefix's second one
                return new SourceInfo("", 2, 8, 2, 7 + len);
            }
            return new SourceInfo("", startLine, startCol, startLine,
                    startCol + len - 1);
        }
        int endLine = ts.lineOf(lastContent) + 2;
        int endCol = ts.columnOf(lastContent);
        return new SourceInfo("", startLine, startCol, endLine, endCol);
    }

    // ------------------------------------------------------------------
    // Imports
    // ------------------------------------------------------------------

    /** The section's {@code import a::b::*;} PREFIX — the sectionIndex
     *  lists the path text minus the trailing {@code ::*}. Engine section
     *  grammars are {@code imports (element)*}: imports can ONLY precede
     *  the first element, so the scan STOPS at the first non-import
     *  token. (The old depth-0 whole-range scan fabricated an import out
     *  of a keyword-as-identifier declaration like
     *  {@code Class import::Class::…} — caught by the harvested
     *  identifier-inclusion fixtures, DIFF batch 2026-08-11.) */
    private static List<String> sectionImports(TokenStream ts, long[] r) {
        List<String> out = new ArrayList<>();
        int cursor = skipTo(ts, r[0]);
        while (cursor < ts.count() && ts.start(cursor) < r[1]
                && ts.type(cursor) == TokenType.IMPORT) {
            StringBuilder p = new StringBuilder();
            cursor++;
            while (cursor < ts.count()
                    && ts.type(cursor) != TokenType.SEMI_COLON) {
                p.append(ts.text(cursor));
                cursor++;
            }
            cursor++;                               // past the ';'
            String imp = Protocol.unquotePath(
                    stripWildcard(p.toString()));
            // the engine DEDUPES section imports keeping the
            // FIRST occurrence (PmcdEquivalenceTest:
            // storeContract/flatDataToPure)
            if (!out.contains(imp)) {
                out.add(imp);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Per-section element parsing (the drop-in dispatch)
    // ------------------------------------------------------------------

    private static List<DocElement> sectionElements(TokenStream ts,
            String source, String parserName, long[] r, int headerStart) {
        List<long[]> range = List.of(r);
        return switch (parserName) {
            case "Pure" -> parseSites(ts, pureSites(ts, range), -1);
            case "Runtime" -> parseSites(ts, runtimeSites(ts, range), -1);
            case "Connection" -> parseSites(ts, connectionSites(ts, range), -1);
            case "Relational" -> parseSites(ts,
                    markerSites(ts, range, TokenType.DATABASE, 8), -1);
            case "Mapping" -> {
                // the aggLambdaShift anchor is the HEADER line + 1 (the
                // engine sub-parses content whose text starts at the
                // header's own newline)
                int line = ts.lineOf(Math.max(headerStart, 0)) + 1;
                yield parseSites(ts,
                        markerSites(ts, range, TokenType.MAPPING, 9), line);
            }
            case "Data" -> parseSites(ts,
                    textMarkerSites(ts, range, Set.of("Data"), 10), -1);
            case "Snowflake", "MemSql", "BigQuery", "HostedService",
                    "FunctionJar" -> parseSites(ts,
                    textMarkerSites(ts, range, ACTIVATOR_KINDS, 11), -1);
            case "Diagram" -> diagramElements(source, r);
            default -> {
                int idx = TAIL_SECTIONS.indexOf(parserName);
                if (idx < 0) {
                    throw new ParseException("'" + parserName
                            + "' is not a known section parser",
                            ts.lineOf((int) r[0]), 1);
                }
                yield ruleGroup(parserName,
                        parseTail(ts, tailSites(ts, range, idx)));
            }
        };
    }

    /** The engine's walkers collect BY GRAMMAR RULE, not source order,
     *  where a section owns several element kinds: schemaSets before
     *  bindings, services before execution environments
     *  (PmcdEquivalenceTest testGrammar / multiExec-embeddedParam). */
    private static List<DocElement> ruleGroup(String parserName,
            List<DocElement> parsed) {
        String firstKind = switch (parserName) {
            case "ExternalFormat" -> "{\"_type\":\"externalFormatSchemaSet\"";
            case "Service" -> "{\"_type\":\"service\"";
            default -> null;
        };
        if (firstKind == null) {
            return parsed;
        }
        List<DocElement> first = new ArrayList<>();
        List<DocElement> rest = new ArrayList<>();
        for (DocElement e : parsed) {
            (e.json().startsWith(firstKind) ? first : rest).add(e);
        }
        first.addAll(rest);
        return first;
    }

    private static List<String> diagramImports(String source, long[] r) {
        int startLine = 1;
        for (int k = 0; k < r[0]; k++) {
            if (source.charAt(k) == '\n') {
                startLine++;
            }
        }
        var parsed = com.legend.parser.section.DiagramSectionGrammar.INSTANCE
                .parseRaw(new com.legend.spi.SectionSource("Diagram",
                        source.substring((int) r[0], (int) r[1]),
                        (int) r[0], (int) r[1], startLine), true);
        List<String> out = new ArrayList<>();
        for (String imp : parsed.imports()) {
            String path = stripWildcard(imp);
            if (!out.contains(path)) {
                out.add(path);
            }
        }
        return out;
    }

    /** {@code a::b::*} → {@code a::b} — the sectionIndex lists import
     *  paths without the wildcard segment. */
    private static String stripWildcard(String imp) {
        int cut = imp.lastIndexOf("::");
        return cut >= 0 && "*".equals(imp.substring(cut + 2))
                ? imp.substring(0, cut) : imp;
    }

    private static List<DocElement> diagramElements(String source, long[] r) {
        int startLine = 1;
        for (int k = 0; k < r[0]; k++) {
            if (source.charAt(k) == '\n') {
                startLine++;
            }
        }
        var parsed = com.legend.parser.section.DiagramSectionGrammar.INSTANCE
                .parseRaw(new com.legend.spi.SectionSource("Diagram",
                        source.substring((int) r[0], (int) r[1]),
                        (int) r[0], (int) r[1], startLine), true);
        List<DocElement> out = new ArrayList<>();
        for (var pe : parsed.elements()) {
            Protocol.PDiagram d = (Protocol.PDiagram) pe.protocol();
            out.add(new DocElement(d.qualifiedName(),
                    ProtocolEmitter.emitElement(d)));
        }
        return out;
    }

    private static List<DocElement> parseSites(TokenStream ts,
            List<int[]> sites, int mappingSectionLine) {
        List<DocElement> out = new ArrayList<>();
        for (int[] site : sites) {
            Protocol.Element el;
            String path;
            switch (site[1]) {
                case 0 -> {
                    Protocol.PClass c = ElementParser.at(ts, site[0])
                            .parseClassDefinition(false);
                    el = c;
                    path = c.qualifiedName();
                }
                case 1 -> {
                    Protocol.PEnumeration e = ElementParser.at(ts, site[0])
                            .parseEnumDefinition();
                    el = e;
                    path = e.qualifiedName();
                }
                case 2 -> {
                    Protocol.PProfile p = ElementParser.at(ts, site[0])
                            .parseProfileDefinition();
                    el = p;
                    path = p.qualifiedName();
                }
                case 3 -> {
                    Protocol.PAssociation a = ElementParser.at(ts, site[0])
                            .parseAssociationDefinition();
                    el = a;
                    path = a.qualifiedName();
                }
                case 4 -> {
                    Protocol.PFunction f = ElementParser.at(ts, site[0])
                            .parseFunctionProtocol();
                    el = f;
                    path = f.pkg().isEmpty() ? f.mangledName()
                            : f.pkg() + "::" + f.mangledName();
                }
                case 5 -> {
                    Protocol.PMeasure m = ElementParser.at(ts, site[0])
                            .parseMeasureDefinition();
                    el = m;
                    path = m.qualifiedName();
                }
                case 6 -> {
                    Protocol.PRuntime rt = ElementParser.at(ts, site[0])
                            .parseRuntimeProtocol();
                    el = rt;
                    path = rt.qualifiedName();
                }
                case 7 -> {
                    Protocol.PConnection cn = ElementParser.at(ts, site[0])
                            .parseConnectionProtocol();
                    el = cn;
                    path = cn.qualifiedName();
                }
                case 8 -> {
                    Protocol.PDatabase db = DatabaseProtocolParser
                            .parse(ts, site[0]);
                    el = db;
                    path = db.qualifiedName();
                }
                case 9 -> {
                    Protocol.PMapping mp = MappingProtocolParser.parse(ts,
                            site[0], mappingSectionLine, null, true);
                    el = mp;
                    path = mp.qualifiedName();
                }
                case 10 -> {
                    Protocol.PDataElement de = MappingProtocolParser
                            .parseData(ts, site[0], true);
                    el = de;
                    path = de.qualifiedName();
                }
                case 11 -> {
                    Protocol.PFunctionActivator fa = ACTIVATOR_GRAMMAR
                            .parseElement(ElementParser.at(ts, site[0]));
                    el = fa;
                    path = fa.qualifiedName();
                }
                default -> throw new IllegalStateException(
                        "unknown site kind " + site[1]);
            }
            out.add(new DocElement(path, ProtocolEmitter.emitElement(el)));
        }
        return out;
    }

    private static List<DocElement> parseTail(TokenStream ts,
            List<int[]> sites) {
        List<DocElement> out = new ArrayList<>();
        for (int[] site : sites) {
            var g = java.util.Objects.requireNonNull(
                    TAIL_GRAMMARS.get(TAIL_SECTIONS.get(site[2])));
            Protocol.Element el = g.parseOne(ElementParser.at(ts, site[0]));
            out.add(new DocElement(g.qualifiedNameOf(el),
                    ProtocolEmitter.emitElement(el)));
        }
        return out;
    }

    private static final Map<String,
            com.legend.parser.section.ElementwiseSectionGrammar> TAIL_GRAMMARS =
            Map.ofEntries(
                    Map.entry("Text", com.legend.parser.section
                            .TextSectionGrammar.INSTANCE),
                    Map.entry("QueryPostProcessor", com.legend.parser.section
                            .QueryPostProcessorSectionGrammar.INSTANCE),
                    Map.entry("GenerationSpecification",
                            com.legend.parser.section
                                    .GenerationSpecificationSectionGrammar
                                    .INSTANCE),
                    Map.entry("FileGeneration", com.legend.parser.section
                            .FileGenerationSectionGrammar.INSTANCE),
                    Map.entry("Deephaven", com.legend.parser.section
                            .DeephavenSectionGrammar.INSTANCE),
                    Map.entry("MongoDB", com.legend.parser.section
                            .MongoDBSectionGrammar.INSTANCE),
                    Map.entry("DataQualityValidation",
                            com.legend.parser.section
                                    .DataQualityValidationSectionGrammar
                                    .INSTANCE),
                    Map.entry("Elasticsearch", com.legend.parser.section
                            .ElasticsearchSectionGrammar.INSTANCE),
                    Map.entry("ExternalFormat", com.legend.parser.section
                            .ExternalFormatSectionGrammar.INSTANCE),
                    Map.entry("ServiceStore", com.legend.parser.section
                            .ServiceStoreSectionGrammar.INSTANCE),
                    Map.entry("DataSpace", com.legend.parser.section
                            .DataSpaceSectionGrammar.INSTANCE),
                    Map.entry("Persistence", com.legend.parser.section
                            .PersistenceSectionGrammar.INSTANCE),
                    Map.entry("Service", com.legend.parser.section
                            .ServiceSectionGrammar.INSTANCE));

    // ------------------------------------------------------------------
    // Site scanners — ONE depth/decl-position discipline per family
    // ------------------------------------------------------------------

    private static final Map<TokenType, Integer> MARKERS = Map.of(
            TokenType.CLASS, 0,
            TokenType.ENUM, 1,
            TokenType.PROFILE, 2,
            TokenType.ASSOCIATION, 3,
            TokenType.FUNCTION, 4);

    private static int skipTo(TokenStream ts, long offset) {
        int cursor = 0;
        while (cursor < ts.count() && ts.start(cursor) < offset) {
            cursor++;
        }
        return cursor;
    }

    private static List<int[]> pureSites(TokenStream ts, List<long[]> ranges) {
        List<int[]> sites = new ArrayList<>();
        for (long[] r : ranges) {
            int depth = 0;
            boolean declPos = true;
            for (int cursor = skipTo(ts, r[0]);
                    cursor < ts.count() && ts.start(cursor) < r[1]; cursor++) {
                TokenType t = ts.type(cursor);
                switch (t) {
                    case BRACE_OPEN, BRACKET_OPEN, PAREN_OPEN -> depth++;
                    case BRACE_CLOSE, BRACKET_CLOSE, PAREN_CLOSE -> depth--;
                    default -> {
                        Integer kind = MARKERS.get(t);
                        if (depth == 0 && declPos && kind != null
                                && (cursor + 1 >= ts.count()
                                        || ts.type(cursor + 1)
                                                != TokenType.PATH_SEPARATOR)) {
                            sites.add(new int[]{cursor, kind});
                        } else if (depth == 0 && declPos
                                && t == TokenType.VALID_STRING
                                && "Measure".equals(ts.text(cursor))) {
                            sites.add(new int[]{cursor, 5});
                        }
                    }
                }
                declPos = t == TokenType.BRACE_CLOSE
                        || t == TokenType.SEMI_COLON;
            }
        }
        return sites;
    }

    private static List<int[]> markerSites(TokenStream ts, List<long[]> ranges,
            TokenType marker, int kind) {
        List<int[]> sites = new ArrayList<>();
        for (long[] r : ranges) {
            int depth = 0;
            boolean declPos = true;
            for (int cursor = skipTo(ts, r[0]);
                    cursor < ts.count() && ts.start(cursor) < r[1]; cursor++) {
                TokenType t = ts.type(cursor);
                switch (t) {
                    case BRACE_OPEN, BRACKET_OPEN, PAREN_OPEN -> depth++;
                    case BRACE_CLOSE, BRACKET_CLOSE, PAREN_CLOSE -> depth--;
                    default -> {
                        if (depth == 0 && declPos && t == marker) {
                            sites.add(new int[]{cursor, kind});
                        }
                    }
                }
                declPos = t == TokenType.BRACE_CLOSE
                        || t == TokenType.SEMI_COLON
                        || t == TokenType.PAREN_CLOSE;
            }
        }
        return sites;
    }

    private static List<int[]> textMarkerSites(TokenStream ts,
            List<long[]> ranges, Set<String> markers, int kind) {
        List<int[]> sites = new ArrayList<>();
        for (long[] r : ranges) {
            int depth = 0;
            boolean declPos = true;
            for (int cursor = skipTo(ts, r[0]);
                    cursor < ts.count() && ts.start(cursor) < r[1]; cursor++) {
                TokenType t = ts.type(cursor);
                switch (t) {
                    case BRACE_OPEN, BRACKET_OPEN, PAREN_OPEN -> depth++;
                    case BRACE_CLOSE, BRACKET_CLOSE, PAREN_CLOSE -> depth--;
                    default -> {
                        if (depth == 0 && declPos
                                && markers.contains(ts.text(cursor))) {
                            sites.add(new int[]{cursor, kind});
                        }
                    }
                }
                declPos = t == TokenType.BRACE_CLOSE
                        || t == TokenType.SEMI_COLON;
            }
        }
        return sites;
    }

    private static List<int[]> tailSites(TokenStream ts, List<long[]> ranges,
            int sectionIdx) {
        List<int[]> sites = new ArrayList<>();
        for (long[] r : ranges) {
            int depth = 0;
            boolean declPos = true;
            for (int cursor = skipTo(ts, r[0]);
                    cursor < ts.count() && ts.start(cursor) < r[1]; cursor++) {
                TokenType t = ts.type(cursor);
                switch (t) {
                    case BRACE_OPEN, BRACKET_OPEN, PAREN_OPEN -> depth++;
                    case BRACE_CLOSE, BRACKET_CLOSE, PAREN_CLOSE -> depth--;
                    case IMPORT -> {
                        if (depth == 0 && declPos) {
                            while (cursor < ts.count() && ts.type(cursor)
                                    != TokenType.SEMI_COLON) {
                                cursor++;
                            }
                            t = TokenType.SEMI_COLON;
                        }
                    }
                    default -> {
                        if (depth == 0 && declPos
                                && TokenStreamCursor.IDENTIFIER_TOKENS
                                        .contains(t)
                                && (cursor + 1 >= ts.count()
                                        || ts.type(cursor + 1)
                                                != TokenType.PATH_SEPARATOR)) {
                            sites.add(new int[]{cursor, 12, sectionIdx});
                        }
                    }
                }
                declPos = t == TokenType.BRACE_CLOSE
                        || t == TokenType.SEMI_COLON
                        || t == TokenType.PAREN_CLOSE;
            }
        }
        return sites;
    }

    private static final Set<String> CONNECTION_FLAVORS = Set.of(
            "JsonModelConnection", "XmlModelConnection",
            "ModelChainConnection", "RelationalDatabaseConnection",
            "ServiceStoreConnection", "DeephavenConnection",
            "MongoDBConnection");

    private static List<int[]> connectionSites(TokenStream ts,
            List<long[]> ranges) {
        List<int[]> sites = new ArrayList<>();
        for (long[] r : ranges) {
            int depth = 0;
            boolean declPos = true;
            for (int cursor = skipTo(ts, r[0]);
                    cursor < ts.count() && ts.start(cursor) < r[1]; cursor++) {
                TokenType t = ts.type(cursor);
                switch (t) {
                    case BRACE_OPEN, BRACKET_OPEN, PAREN_OPEN -> depth++;
                    case BRACE_CLOSE, BRACKET_CLOSE, PAREN_CLOSE -> depth--;
                    default -> {
                        if (depth == 0 && declPos
                                && CONNECTION_FLAVORS.contains(ts.text(cursor))) {
                            sites.add(new int[]{cursor, 7});
                        }
                    }
                }
                declPos = t == TokenType.BRACE_CLOSE
                        || t == TokenType.SEMI_COLON;
            }
        }
        return sites;
    }

    private static List<int[]> runtimeSites(TokenStream ts,
            List<long[]> ranges) {
        // the engine's runtime walker collects BY GRAMMAR RULE: every
        // `Runtime` first, then every `SingleConnectionRuntime` — NOT
        // source order (PmcdEquivalenceTest join-relational)
        List<int[]> runtimes = new ArrayList<>();
        List<int[]> singles = new ArrayList<>();
        for (long[] r : ranges) {
            int depth = 0;
            boolean declPos = true;
            for (int cursor = skipTo(ts, r[0]);
                    cursor < ts.count() && ts.start(cursor) < r[1]; cursor++) {
                TokenType t = ts.type(cursor);
                switch (t) {
                    case BRACE_OPEN, BRACKET_OPEN, PAREN_OPEN -> depth++;
                    case BRACE_CLOSE, BRACKET_CLOSE, PAREN_CLOSE -> depth--;
                    default -> {
                        if (depth == 0 && declPos
                                && t == TokenType.RUNTIME) {
                            runtimes.add(new int[]{cursor, 6});
                        } else if (depth == 0 && declPos
                                && t == TokenType.SINGLE_CONNECTION_RUNTIME) {
                            singles.add(new int[]{cursor, 6});
                        }
                    }
                }
                declPos = t == TokenType.BRACE_CLOSE
                        || t == TokenType.SEMI_COLON;
            }
        }
        runtimes.addAll(singles);
        return runtimes;
    }
}
