// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.parser.section;

import com.legend.protocol.Protocol;
import com.legend.protocol.SourceInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * THE {@code ###Diagram} grammar — the first RAW built-in: diagram content
 * (color literals like {@code #FFFFCC}) is unlexable Pure, so the shared
 * lexer skips the section and this grammar walks the CHARACTERS. Elements
 * parse to their envelope (name, optional {@code (width=..,height=..)}
 * attributes) with the body carried as written — a diagram is presentation
 * metadata; nothing in lite opens it, but it is now a NAMED, indexed,
 * typed element instead of lexer silence.
 */
public final class DiagramSectionGrammar implements RawSectionGrammar {

    /** The one stateless instance the registry hands out. */
    public static final DiagramSectionGrammar INSTANCE =
            new DiagramSectionGrammar();

    private DiagramSectionGrammar() {
    }

    @Override
    public String name() {
        return "Diagram";
    }

    /** The SPI feed — same raw parse, protocol JSON out (walls: no wire
     *  shape is claimed for diagrams). */
    @Override
    public void parse(com.legend.spi.SectionSource src,
            com.legend.spi.ElementSink out) {
        for (var pe : parseRaw(src).elements()) {
            Protocol.PDiagram d = (Protocol.PDiagram) pe.protocol();
            out.accept(d.qualifiedName(),
                    com.legend.protocol.ProtocolEmitter.emitElement(d));
        }
    }

    @Override
    public LexableSectionGrammar.ParsedSection parseRaw(
            com.legend.spi.SectionSource src) {
        String t = src.text();
        List<LexableSectionGrammar.ParsedElement> elements = new ArrayList<>();
        List<String> imports = new ArrayList<>();
        int i = 0;
        int n = t.length();
        while (true) {
            i = skipWsAndComments(t, i);
            if (i >= n) {
                break;
            }
            if (t.startsWith("import ", i)) {
                int semi = t.indexOf(';', i);
                if (semi < 0) {
                    throw fail(src, i, "unterminated import");
                }
                imports.add(t.substring(i + 7, semi).trim());
                i = semi + 1;
                continue;
            }
            int elStart = i;
            if (!t.startsWith("Diagram", i)) {
                throw fail(src, i, "unsupported ###Diagram element");
            }
            i += "Diagram".length();
            i = skipWsAndComments(t, i);
            int nameStart = i;
            while (i < n && (Character.isLetterOrDigit(t.charAt(i))
                    || t.charAt(i) == '_' || t.charAt(i) == '$'
                    || t.startsWith("::", i))) {
                i += t.startsWith("::", i) ? 2 : 1;
            }
            if (i == nameStart) {
                throw fail(src, i, "Diagram needs a qualified name");
            }
            String qn = t.substring(nameStart, i);
            i = skipWsAndComments(t, i);
            if (i < n && t.charAt(i) == '(') {
                i = skipBalanced(src, t, i, '(', ')');
                i = skipWsAndComments(t, i);
            }
            if (i >= n || t.charAt(i) != '{') {
                throw fail(src, i, "Diagram '" + qn + "' needs a body");
            }
            int bodyStart = i;
            i = skipBalanced(src, t, i, '{', '}');
            String body = t.substring(bodyStart, i);
            int cut = qn.lastIndexOf("::");
            elements.add(new LexableSectionGrammar.ParsedElement(
                    new Protocol.PDiagram(
                            cut < 0 ? "" : qn.substring(0, cut),
                            cut < 0 ? qn : qn.substring(cut + 2),
                            body, spanOf(t, elStart, i)),
                    elStart));
        }
        return new LexableSectionGrammar.ParsedSection(elements, imports);
    }

    @Override
    public com.legend.model.PackageableElement toModel(Protocol.Element element) {
        Protocol.PDiagram d = (Protocol.PDiagram) element;
        return new com.legend.model.GenericSectionElementDefinition("Diagram",
                "Diagram", d.qualifiedName(), java.util.Map.of(),
                d.bodySource());
    }

    private static int skipWsAndComments(String t, int i) {
        int n = t.length();
        while (i < n) {
            char c = t.charAt(i);
            if (c == ' ' || c == '\t' || c == '\r' || c == '\n') {
                i++;
            } else if (t.startsWith("//", i)) {
                int nl = t.indexOf('\n', i);
                i = nl < 0 ? n : nl + 1;
            } else if (t.startsWith("/*", i)) {
                int end = t.indexOf("*/", i + 2);
                i = end < 0 ? n : end + 2;
            } else {
                break;
            }
        }
        return i;
    }

    /** Balanced skip from the opener at {@code i}; returns the index PAST
     *  the matching closer. Diagram bodies carry no string literals that
     *  could hide braces (geometry, colors, names). */
    private static int skipBalanced(com.legend.spi.SectionSource src,
            String t, int i, char open, char close) {
        int depth = 0;
        int n = t.length();
        do {
            char c = t.charAt(i);
            if (c == open) {
                depth++;
            } else if (c == close) {
                depth--;
            }
            i++;
        } while (i < n && depth > 0);
        if (depth > 0) {
            throw fail(src, i, "unbalanced '" + open + "'");
        }
        return i;
    }

    /** Section-relative line/column span for a carried element. */
    private static SourceInfo spanOf(String t, int from, int toExclusive) {
        int line = 1;
        int col = 1;
        int endLine = 1;
        int endCol = 1;
        for (int i = 0; i < toExclusive && i < t.length(); i++) {
            if (i == from) {
                line = endLine;
                col = endCol;
            }
            if (t.charAt(i) == '\n') {
                endLine++;
                endCol = 1;
            } else {
                endCol++;
            }
        }
        return new SourceInfo("", line, col, endLine, Math.max(1, endCol - 1));
    }

    private static RuntimeException fail(com.legend.spi.SectionSource src,
            int at, String message) {
        int line = 1;
        int col = 1;
        String t = src.text();
        for (int i = 0; i < at && i < t.length(); i++) {
            if (t.charAt(i) == '\n') {
                line++;
                col = 1;
            } else {
                col++;
            }
        }
        return new com.legend.parser.ParseException(message, line, col);
    }
}
