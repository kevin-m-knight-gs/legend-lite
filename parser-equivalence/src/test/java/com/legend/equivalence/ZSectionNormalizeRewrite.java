package com.legend.equivalence;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * SECTIONS NORMALIZATION — the source rewriter. Finds every sectionless
 * Pure snippet embedded in OUR test sources (core/pct/nlq) whose lenient
 * parse contains non-Pure elements, and inserts the engine's
 * {@code ###Section} headers into the Java literals at the positions
 * {@link Sectionize} computes. Handles BOTH literal forms: text blocks
 * (headers become their own source lines at the block's indent) and
 * {@code +}-joined ordinary literals (headers become {@code \n###X\n}
 * escapes inside the literal).
 *
 * <p>REPORT-ONLY by default; {@code -Dsectionize.apply=true} writes the
 * files. Every rewritten file is verified before writing: the INDEPENDENT
 * {@link InlineSnippets#literalRuns} scanner re-extracts the new snippets,
 * and each changed snippet minus its {@code ###} lines must lex to the
 * IDENTICAL token stream as the original (type + text, token by token) —
 * so a mapping bug cannot silently corrupt a fixture.
 *
 * <p>IMPORTS: the engine scopes imports PER SECTION, so a snippet's
 * leading imports are REPLICATED under every inserted header — the
 * engine-conformant spelling (each section declares what it uses;
 * redundant imports are legal) that leaves lite's lenient scoping
 * observably unchanged (same import set in force at every element).
 * ONLY into import-aware sections: the engine's Relational and DataSpace
 * grammars are {@code definition: (element)* EOF} — no imports rule — and
 * refuse an import line, so those sections get the bare header.
 * Snippets with imports that are NOT all-leading are skipped and listed —
 * those need eyes, not mechanics.
 */
class ZSectionNormalizeRewrite {

    /** WIDER than InlineSnippets' corpus heuristic ON PURPOSE: a snippet
     *  that STARTS with a store/mapping element (no domain decl at all)
     *  is exactly the sectionless shape this tool exists to fix — but the
     *  corpus universe (ledger totals) is pinned on the narrow pattern,
     *  so the widening lives here, not in InlineSnippets. */
    private static final Pattern PURE_DECL = Pattern.compile(
            "(?m)^\\s*(Class|Enum|Association|Profile|Measure|function|native\\s+function|import"
                    + "|Database|Mapping|Runtime|RelationalDatabaseConnection|Service)\\s");

    /** A decoded literal run with per-char source coordinates. */
    private record PosRun(String text, int[] srcPos, BitSet inTextBlock) {
    }

    /** One source edit: insert {@code insert} at {@code srcOffset}. */
    private record Edit(int srcOffset, String insert) {
    }

    @Test
    void normalizeSectionlessSnippets() throws Exception {
        boolean apply = Boolean.getBoolean("sectionize.apply");
        Path repo = Path.of(System.getProperty("user.dir")).getParent();
        int filesChanged = 0;
        int runsChanged = 0;
        int headersInserted = 0;
        List<String> importSkips = new ArrayList<>();
        List<String> verifyFailures = new ArrayList<>();
        for (String module : new String[]{"core", "pct", "nlq"}) {
            Path root = repo.resolve(module);
            if (!Files.isDirectory(root)) {
                continue;
            }
            List<Path> javaFiles = new ArrayList<>();
            try (Stream<Path> s = Files.walk(root)) {
                s.filter(p -> p.toString().endsWith(".java"))
                        .filter(p -> p.toString().contains("/src/test/"))
                        .filter(p -> !p.toString().contains("/target/"))
                        .sorted()
                        .forEach(javaFiles::add);
            }
            for (Path p : javaFiles) {
                String source;
                try {
                    source = Files.readString(p);
                } catch (Exception e) {
                    continue;                    // non-UTF8, same as extractor
                }
                List<PosRun> runs = scanRuns(source);
                List<Edit> edits = new ArrayList<>();
                List<Integer> changedRunIdx = new ArrayList<>();
                List<String> expectedRuns = new ArrayList<>();
                for (int r = 0; r < runs.size(); r++) {
                    PosRun run = runs.get(r);
                    String text = run.text();
                    if (text.length() <= 20 || !PURE_DECL.matcher(text).find()) {
                        continue;
                    }
                    List<Sectionize.Insertion> plan = Sectionize.plan(text);
                    if (plan == null || plan.isEmpty()) {
                        continue;
                    }
                    List<String> imports = leadingImports(text);
                    if (imports == null) {
                        importSkips.add(p + "#run" + r);
                        continue;
                    }
                    changedRunIdx.add(r);
                    expectedRuns.add(expected(text, plan, imports));
                    for (Sectionize.Insertion ins : plan) {
                        edits.add(edit(source, run, ins, imports));
                    }
                }
                if (edits.isEmpty()) {
                    continue;
                }
                edits.sort(java.util.Comparator
                        .comparingInt(Edit::srcOffset).reversed());
                StringBuilder sb = new StringBuilder(source);
                for (Edit e : edits) {
                    sb.insert(e.srcOffset(), e.insert());
                }
                String rewritten = sb.toString();
                String verdict = verify(source, rewritten, changedRunIdx,
                        expectedRuns);
                if (verdict != null) {
                    verifyFailures.add(p + " :: " + verdict);
                    continue;
                }
                filesChanged++;
                runsChanged += changedRunIdx.size();
                headersInserted += edits.size();
                if (apply) {
                    Files.writeString(p, rewritten);
                }
                System.out.println("@@ " + (apply ? "REWROTE " : "WOULD ")
                        + repo.relativize(p) + " runs=" + changedRunIdx.size()
                        + " headers=" + edits.size());
            }
        }
        System.out.println("@@ TOTAL files=" + filesChanged + " runs="
                + runsChanged + " headers=" + headersInserted
                + " importSkips=" + importSkips.size()
                + " verifyFailures=" + verifyFailures.size()
                + (apply ? " (APPLIED)" : " (report only)"));
        importSkips.forEach(s -> System.out.println("@@ IMPORT-SKIP " + s));
        verifyFailures.forEach(s -> System.out.println("@@ VERIFY-FAIL " + s));
        org.junit.jupiter.api.Assertions.assertTrue(verifyFailures.isEmpty(),
                () -> "sectionize rewrite verification failed:\n  "
                        + String.join("\n  ", verifyFailures));
    }

    /** The snippet's leading import statements (decoded text, one per
     *  statement) — or null when an import appears AFTER the first
     *  non-import token (manual territory). Empty list = no imports. */
    private static @com.legend.Nullable List<String> leadingImports(
            String text) {
        com.legend.lexer.TokenStream ts;
        try {
            ts = com.legend.lexer.Lexer.tokenize(text);
        } catch (Throwable t) {
            return null;
        }
        List<String> out = new ArrayList<>();
        int i = 0;
        while (i < ts.count()
                && ts.type(i) == com.legend.lexer.TokenType.IMPORT) {
            int j = i;
            while (j < ts.count()
                    && ts.type(j) != com.legend.lexer.TokenType.SEMI_COLON) {
                j++;
            }
            if (j >= ts.count()) {
                return null;
            }
            out.add(text.substring(ts.start(i), ts.end(j)));
            i = j + 1;
        }
        for (int k = i; k < ts.count(); k++) {
            if (ts.type(k) == com.legend.lexer.TokenType.IMPORT) {
                return null;
            }
        }
        return out;
    }

    /** Engine sections whose grammar has an {@code imports} rule; the
     *  others ({@code definition: (element)* EOF}) refuse import lines. */
    private static final java.util.Set<String> IMPORT_AWARE =
            java.util.Set.of("Pure", "Mapping", "Runtime", "Connection",
                    "Service");

    /** One inserted block in decoded space: the header line plus the
     *  replicated leading imports (import-aware sections only). */
    private static String block(String section, List<String> imports) {
        StringBuilder b = new StringBuilder("\n###").append(section)
                .append('\n');
        if (IMPORT_AWARE.contains(section)) {
            for (String imp : imports) {
                b.append(imp).append('\n');
            }
        }
        return b.toString();
    }

    /** The decoded-space expectation for a rewritten run. */
    private static String expected(String text,
            List<Sectionize.Insertion> plan, List<String> imports) {
        StringBuilder sb = new StringBuilder(text);
        for (int i = plan.size() - 1; i >= 0; i--) {
            sb.insert(plan.get(i).offset(),
                    block(plan.get(i).section(), imports));
        }
        return sb.toString();
    }

    /** The source edit realizing one decoded-space insertion. */
    private static Edit edit(String source, PosRun run,
            Sectionize.Insertion ins, List<String> imports) {
        int p = run.srcPos()[ins.offset()];
        boolean withImports = IMPORT_AWARE.contains(ins.section());
        if (!run.inTextBlock().get(ins.offset())) {
            // ordinary literal: real newlines as escapes, inside the literal
            StringBuilder b = new StringBuilder("\\n###")
                    .append(ins.section()).append("\\n");
            if (withImports) {
                for (String imp : imports) {
                    b.append(imp).append("\\n");
                }
            }
            return new Edit(p, b.toString());
        }
        int ls = source.lastIndexOf('\n', p - 1) + 1;
        int firstNonWs = ls;
        while (firstNonWs < source.length()
                && (source.charAt(firstNonWs) == ' '
                        || source.charAt(firstNonWs) == '\t')) {
            firstNonWs++;
        }
        String indent = source.substring(ls, firstNonWs);
        StringBuilder lines = new StringBuilder();
        lines.append(indent).append("###").append(ins.section()).append('\n');
        if (withImports) {
            for (String imp : imports) {
                lines.append(indent).append(imp).append('\n');
            }
        }
        if (source.substring(ls, p).isBlank()) {
            // element starts its line: the block becomes the lines above
            return new Edit(ls, lines.toString());
        }
        // mid-line: break the line, block on its own lines, element resumes
        return new Edit(p, "\n" + lines + indent);
    }

    /** Null when the rewrite checks out; otherwise the failure story. The
     *  INDEPENDENT extractor re-reads each changed run, which must lex to
     *  the same tokens AND section headers as the decoded-space
     *  expectation. */
    private static @com.legend.Nullable String verify(String oldSource,
            String newSource, List<Integer> changedRunIdx,
            List<String> expectedRuns) {
        List<String> oldRuns = InlineSnippets.literalRuns(oldSource);
        List<String> newRuns = InlineSnippets.literalRuns(newSource);
        if (oldRuns.size() != newRuns.size()) {
            return "run count changed " + oldRuns.size() + " -> "
                    + newRuns.size();
        }
        for (int i = 0; i < changedRunIdx.size(); i++) {
            int r = changedRunIdx.get(i);
            String tok = tokenDiff(expectedRuns.get(i), newRuns.get(r));
            if (tok != null) {
                return "run " + r + " " + tok;
            }
        }
        return null;
    }

    /** Null when both texts lex identically (type+text per token, plus the
     *  section-header names in order). */
    private static @com.legend.Nullable String tokenDiff(String a, String b) {
        com.legend.lexer.TokenStream ta;
        com.legend.lexer.TokenStream tb;
        try {
            ta = com.legend.lexer.Lexer.tokenize(a);
            tb = com.legend.lexer.Lexer.tokenize(b);
        } catch (Throwable t) {
            return "lex failed post-rewrite: " + t.getMessage();
        }
        List<String> ha = ta.sectionHeaders().stream()
                .map(com.legend.lexer.TokenStream.SectionHeader::name).toList();
        List<String> hb = tb.sectionHeaders().stream()
                .map(com.legend.lexer.TokenStream.SectionHeader::name).toList();
        if (!ha.equals(hb)) {
            return "section headers " + ha + " -> " + hb;
        }
        if (ta.count() != tb.count()) {
            return "token count " + ta.count() + " -> " + tb.count();
        }
        for (int i = 0; i < ta.count(); i++) {
            if (ta.type(i) != tb.type(i)
                    || !ta.text(i).equals(tb.text(i))) {
                return "token " + i + " " + ta.type(i) + "('" + ta.text(i)
                        + "') -> " + tb.type(i) + "('" + tb.text(i) + "')";
            }
        }
        return null;
    }

    // ================================================================
    // Position-aware literal-run scanner — the same walk as
    // InlineSnippets.literalRuns, carrying source coordinates per
    // decoded char (verified against that scanner by verify()).
    // ================================================================

    private static List<PosRun> scanRuns(String s) {
        List<PosRun> out = new ArrayList<>();
        int i = 0;
        int n = s.length();
        StringBuilder run = null;
        List<Integer> pos = null;
        BitSet tb = null;
        while (i < n) {
            char c = s.charAt(i);
            if (c == '/' && i + 1 < n && s.charAt(i + 1) == '/') {
                while (i < n && s.charAt(i) != '\n') {
                    i++;
                }
                continue;
            }
            if (c == '/' && i + 1 < n && s.charAt(i + 1) == '*') {
                int end = s.indexOf("*/", i + 2);
                i = end < 0 ? n : end + 2;
                continue;
            }
            if (c == '\'') {
                i++;
                while (i < n && s.charAt(i) != '\'') {
                    i += s.charAt(i) == '\\' ? 2 : 1;
                }
                i++;
                continue;
            }
            if (c == '"') {
                if (run == null) {
                    run = new StringBuilder();
                    pos = new ArrayList<>();
                    tb = new BitSet();
                }
                int[] cursor = {i};
                if (s.startsWith("\"\"\"", i)) {
                    scanTextBlockPos(s, cursor, run, pos, tb);
                } else {
                    scanOrdinaryPos(s, cursor, run, pos, tb);
                }
                i = cursor[0];
                int j = skipWsAndComments(s, i);
                if (j < n && s.charAt(j) == '+') {
                    j = skipWsAndComments(s, j + 1);
                    if (j < n && s.charAt(j) == '"') {
                        i = j;
                        continue;
                    }
                }
                out.add(finish(run, pos, tb));
                run = null;
                pos = null;
                tb = null;
                continue;
            }
            i++;
        }
        if (run != null) {
            out.add(finish(run, pos, tb));
        }
        return out;
    }

    private static PosRun finish(StringBuilder run, List<Integer> pos,
            BitSet tb) {
        int[] arr = new int[pos.size()];
        for (int k = 0; k < arr.length; k++) {
            arr[k] = pos.get(k);
        }
        return new PosRun(run.toString(), arr, tb);
    }

    private static int skipWsAndComments(String s, int i) {
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '/' && i + 1 < n && s.charAt(i + 1) == '/') {
                while (i < n && s.charAt(i) != '\n') {
                    i++;
                }
            } else if (c == '/' && i + 1 < n && s.charAt(i + 1) == '*') {
                int end = s.indexOf("*/", i + 2);
                i = end < 0 ? n : end + 2;
            } else {
                break;
            }
        }
        return i;
    }

    private static void scanOrdinaryPos(String s, int[] cursor,
            StringBuilder b, List<Integer> pos, BitSet tb) {
        int i = cursor[0] + 1;
        int n = s.length();
        while (i < n && s.charAt(i) != '"') {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < n) {
                int before = b.length();
                int consumed = decodeEscapePos(s, i, b);
                for (int q = before; q < b.length(); q++) {
                    pos.add(i);
                }
                i += consumed;
            } else {
                b.append(c);
                pos.add(i);
                i++;
            }
        }
        cursor[0] = Math.min(i + 1, n);
    }

    private static void scanTextBlockPos(String s, int[] cursor,
            StringBuilder b, List<Integer> pos, BitSet tb) {
        int start = s.indexOf('\n', cursor[0] + 3);
        int end = s.indexOf("\"\"\"", start < 0 ? cursor[0] + 3 : start);
        if (start < 0 || end < 0) {
            cursor[0] = s.length();
            return;
        }
        String body = s.substring(start + 1, end);
        cursor[0] = end + 3;
        String[] lines = body.split("\n", -1);
        int minIndent = Integer.MAX_VALUE;
        for (int k = 0; k < lines.length; k++) {
            String line = lines[k];
            boolean lastLine = k == lines.length - 1;
            if (line.isBlank() && !lastLine) {
                continue;
            }
            int indent = 0;
            while (indent < line.length()
                    && Character.isWhitespace(line.charAt(indent))) {
                indent++;
            }
            minIndent = Math.min(minIndent, indent);
        }
        int lineSrc = start + 1;
        for (int k = 0; k < lines.length; k++) {
            String line = lines[k];
            int lineStartSrc = lineSrc;
            lineSrc += line.length() + 1;
            if (k == lines.length - 1 && line.isBlank()) {
                break;
            }
            String stripped;
            int base;
            if (line.length() > minIndent) {
                stripped = line.substring(minIndent);
                base = lineStartSrc + minIndent;
            } else {
                stripped = line.strip();
                int lead = 0;
                while (lead < line.length()
                        && Character.isWhitespace(line.charAt(lead))) {
                    lead++;
                }
                base = lineStartSrc + lead;
            }
            for (int i = 0; i < stripped.length(); i++) {
                char c = stripped.charAt(i);
                if (c == '\\' && i + 1 < stripped.length()) {
                    int before = b.length();
                    int consumed = decodeEscapePos(stripped, i, b);
                    for (int q = before; q < b.length(); q++) {
                        pos.add(base + i);
                        tb.set(q);
                    }
                    i += consumed - 1;
                } else {
                    tb.set(b.length());
                    b.append(c);
                    pos.add(base + i);
                }
            }
            tb.set(b.length());
            b.append('\n');
            pos.add(lineStartSrc + line.length());
        }
    }

    /** InlineSnippets.decodeEscape, verbatim semantics. */
    private static int decodeEscapePos(String s, int i, StringBuilder b) {
        char e = s.charAt(i + 1);
        switch (e) {
            case 'n' -> b.append('\n');
            case 't' -> b.append('\t');
            case 'r' -> b.append('\r');
            case 'b' -> b.append('\b');
            case 'f' -> b.append('\f');
            case 's' -> b.append(' ');
            case '"' -> b.append('"');
            case '\'' -> b.append('\'');
            case '\\' -> b.append('\\');
            case 'u' -> {
                if (i + 5 < s.length()) {
                    try {
                        b.append((char) Integer
                                .parseInt(s.substring(i + 2, i + 6), 16));
                        return 6;
                    } catch (NumberFormatException ignored) {
                        // fall through to raw
                    }
                }
                b.append('\\').append(e);
            }
            default -> b.append('\\').append(e);
        }
        return 2;
    }
}
