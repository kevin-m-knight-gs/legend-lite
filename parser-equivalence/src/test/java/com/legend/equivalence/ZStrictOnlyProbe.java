package com.legend.equivalence;

import org.junit.jupiter.api.Test;

/** PROBE: WHY does parseStrict accept rows the document pipeline refuses?
 *  Prints the DOCUMENT refusal for a sample of strict-only allowlist rows. */
class ZStrictOnlyProbe {
    @Test
    void probe() throws Exception {
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (String l : java.nio.file.Files.readAllLines(
                java.nio.file.Path.of("..", "docs", "refusal-allowlist.tsv"))) {
            if (l.startsWith("#")) continue;
            String[] f = l.split("\t", 3);
            if ("strict".equals(f[1])) ids.add(f[0]);
        }
        int shown = 0;
        for (Corpus.Source s : Corpus.all()) {
            if (!ids.contains(s.id()) || shown >= 12) continue;
            String docMsg;
            try {
                com.legend.parser.PmcdParser.parseDocument(s.text());
                docMsg = "<document ACCEPTS?!>";
            } catch (Throwable t) {
                Throwable r = t;
                while (r.getCause() != null && r.getCause() != r) r = r.getCause();
                docMsg = String.valueOf(r.getMessage()).replaceAll("\\s+", " ");
            }
            System.out.println("@@ STRICT-ONLY " + s.id());
            System.out.println("@@   doc-refuses: " + (docMsg.length() > 140 ? docMsg.substring(0, 140) : docMsg));
            shown++;
        }
    }
}
