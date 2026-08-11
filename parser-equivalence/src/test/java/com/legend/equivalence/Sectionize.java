package com.legend.equivalence;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * The MECHANICAL sectionize transform (sections normalization): given a
 * sectionless Pure snippet, compute where the engine's {@code ###Section}
 * headers belong — one insertion at each element whose kind leaves the
 * current section, positions taken from OUR lenient parse. Shared by the
 * probe ({@link ZSectionizeProbe}), the source rewriter
 * ({@link ZSectionNormalizeRewrite}) and the own-corpus ratchet
 * ({@link OwnCorpusConformanceTest}).
 */
final class Sectionize {

    private Sectionize() {
    }

    /** The engine section that owns an element kind ({@code Pure} = the
     *  default section — no header needed). */
    static String sectionOf(com.legend.model.PackageableElement e) {
        return switch (e) {
            case com.legend.model.DatabaseDefinition d -> "Relational";
            case com.legend.model.MappingDefinition m -> "Mapping";
            case com.legend.model.LegacyMappingDefinition m -> "Mapping";
            case com.legend.model.RuntimeDefinition r -> "Runtime";
            case com.legend.model.ConnectionDefinition c -> "Connection";
            case com.legend.model.ModelConnectionDefinition c -> "Connection";
            case com.legend.model.ModelChainConnectionDefinition c -> "Connection";
            case com.legend.model.ServiceDefinition s -> "Service";
            case com.legend.model.ExecutionEnvironmentDefinition s -> "Service";
            case com.legend.model.DataSpaceDefinition d -> "DataSpace";
            default -> "Pure";
        };
    }

    /** One header insertion: {@code section}'s header belongs at char
     *  {@code offset} of the decoded snippet. */
    record Insertion(int offset, String section) {
    }

    /**
     * The insertion plan for a snippet, in source order. Returns null when
     * the snippet is not sectionizable (already carries {@code ###} headers,
     * does not lenient-parse, or an element has no recorded offset); an
     * EMPTY list means the snippet is Pure-only — already normal.
     */
    static @com.legend.Nullable List<Insertion> plan(String text) {
        if (text.contains("###")) {
            return null;
        }
        com.legend.model.ParsedModel pm;
        try {
            pm = com.legend.parser.ElementParser.parse(text);
        } catch (Throwable t) {
            return null;
        }
        List<com.legend.model.PackageableElement> els =
                new ArrayList<>(pm.elements());
        Map<String, Integer> offs = pm.elementOffsets();
        for (com.legend.model.PackageableElement e : els) {
            if (offs.get(e.qualifiedName()) == null) {
                return null;
            }
        }
        els.sort(Comparator.comparingInt(e -> offs.get(e.qualifiedName())));
        List<Insertion> inserts = new ArrayList<>();
        String current = "Pure";
        for (com.legend.model.PackageableElement e : els) {
            String want = sectionOf(e);
            if (!want.equals(current)) {
                inserts.add(new Insertion(offs.get(e.qualifiedName()), want));
                current = want;
            }
        }
        return inserts;
    }

    /** {@link #plan(String)} applied in decoded space: each header on its
     *  own line ({@code \n###X\n}). Null/identity mirror plan()'s verdicts. */
    static @com.legend.Nullable String apply(String text) {
        List<Insertion> inserts = plan(text);
        if (inserts == null) {
            return null;
        }
        if (inserts.isEmpty()) {
            return text;
        }
        StringBuilder sb = new StringBuilder(text);
        for (int i = inserts.size() - 1; i >= 0; i--) {
            sb.insert(inserts.get(i).offset(),
                    "\n###" + inserts.get(i).section() + "\n");
        }
        return sb.toString();
    }
}
