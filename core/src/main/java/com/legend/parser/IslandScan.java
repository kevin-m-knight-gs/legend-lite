// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.parser;

import com.legend.lexer.TokenStream;
import com.legend.protocol.spec.CBoolean;
import com.legend.protocol.spec.CDate;
import com.legend.protocol.spec.CInteger;
import com.legend.protocol.spec.CString;
import com.legend.protocol.spec.EnumValue;
import com.legend.protocol.spec.PureCollection;
import com.legend.protocol.spec.ValueSpecification;
import com.legend.protocol.spec.Variable;

import java.util.ArrayList;
import java.util.List;

/**
 * The CHARACTER-LEVEL island scanner: THE ONE scalar reader for island
 * argument text (audit §2.1) plus the graph-fetch node/argument scans
 * that consume it. Extracted from {@code SpecParser} at the 4.138.2 re-pin
 * (the file-size guardrail's named seam); the token-level graph-fetch
 * entry points ({@code parseGraphFetchTree}/{@code wrapGraphFetch}) stay
 * with the expression parser, which delegates here.
 */
final class IslandScan {

    private final TokenStream tokens;
    private final String spanSourceId;
    /** Error anchor for bad escapes inside island strings. */
    private final TokenStreamCursor errorContext;

    IslandScan(TokenStream tokens, String spanSourceId,
            TokenStreamCursor errorContext) {
        this.tokens = tokens;
        this.spanSourceId = spanSourceId;
        this.errorContext = errorContext;
    }

    private com.legend.protocol.SourceInfo charSpan(int from, int to) {
        return new com.legend.protocol.SourceInfo(spanSourceId,
                tokens.lineOf(from), tokens.columnOf(from),
                tokens.lineOf(to), tokens.columnOf(to));
    }

    /** What {@link #scanScalar} read. {@code a}/{@code b} carry the kind's
     *  strings (STRING: unescaped body; DATE: body sans {@code %} / written
     *  with it; ENUM: type / value; VAR: name); spans are {@code s..e}
     *  inclusive; {@code next} is where the caller resumes. */
    record Scalar(ScalarKind kind, String a, String b, long intValue,
            int s, int e, int next) {
        static Scalar of(ScalarKind k, String a, String b, long v, int s, int e, int n) {
            return new Scalar(k, a, b, v, s, e, n);
        }
    }

    enum ScalarKind { SKIP, LATEST, DATE, INT, NUMERICISH, STRING, BOOL, VAR, ENUM, OTHER }

    /**
     * THE ONE scalar reader for island argument text (audit §2.1): path-literal
     * dated-segment args and graph-fetch property args read the same lexeme the
     * SAME way. Each caller maps the kinds whose wire shapes it has probed and
     * WALLS the rest — an unprobed position refuses loudly instead of reading
     * wrong (a float no longer truncates to its fraction digits; strings
     * unescape on both paths). Structural syntax (collections, commas,
     * closers) stays with the caller.
     */
    Scalar scanScalar(String src, int i, int limit) {
        char c = src.charAt(i);
        if (c == '/' && i + 1 < limit && src.charAt(i + 1) == '/') {
            int k = i;
            while (k < limit && src.charAt(k) != '\n') {
                k++;
            }
            return Scalar.of(ScalarKind.SKIP, "", "", 0, i, k, k);
        }
        if (c == '%') {
            int k = i + 1;
            while (k < limit && (Character.isLetterOrDigit(src.charAt(k))
                    || "-T:.Z+".indexOf(src.charAt(k)) >= 0)) {
                k++;
            }
            String body = src.substring(i + 1, k);
            if (body.isEmpty()) {
                return Scalar.of(ScalarKind.OTHER, "", "", 0, i, i, i + 1);
            }
            return Scalar.of(body.equals("latest") ? ScalarKind.LATEST : ScalarKind.DATE,
                    body, src.substring(i, k), 0, i, k - 1, k);
        }
        if (Character.isDigit(c)) {
            int k = i;
            while (k < limit && Character.isDigit(src.charAt(k))) {
                k++;
            }
            if (k < limit && (src.charAt(k) == '.' || isGraphIdentChar(src.charAt(k)))) {
                // float/decimal/suffixed — one LEXEME, wire unprobed: consume it
                // whole so it cannot re-read as its fraction digits
                while (k < limit && (src.charAt(k) == '.' || isGraphIdentChar(src.charAt(k)))) {
                    k++;
                }
                return Scalar.of(ScalarKind.NUMERICISH, src.substring(i, k), "", 0, i, k - 1, k);
            }
            long intVal;
            try {
                intVal = Long.parseLong(src.substring(i, k));
            } catch (NumberFormatException overflow) {
                throw errorContext.error("integer literal out of range: '"
                        + src.substring(i, k) + "'");
            }
            return Scalar.of(ScalarKind.INT, "", "", intVal, i, k - 1, k);
        }
        if (c == '\'') {
            // ESCAPE-AWARE close scan: 'it\'s' is one literal (adversarial
            // audit F26 — indexOf landed on the escaped quote and refused
            // engine-legal input)
            int close = closingQuote(src, i + 1, limit);
            if (close < 0) {
                return Scalar.of(ScalarKind.OTHER, "", "", 0, i, limit - 1, limit);
            }
            return Scalar.of(ScalarKind.STRING,
                    TokenStreamCursor.unescapeBody(
                            src.substring(i + 1, close), "string literal",
                            errorContext), "", 0, i, close, close + 1);
        }
        if (c == '$') {
            int k = i + 1;
            while (k < limit && isGraphIdentChar(src.charAt(k))) {
                k++;
            }
            if (k == i + 1) {
                return Scalar.of(ScalarKind.OTHER, "", "", 0, i, i, i + 1);
            }
            return Scalar.of(ScalarKind.VAR, src.substring(i + 1, k), "", 0, i + 1, k - 1, k);
        }
        if (Character.isLetter(c) || c == '_') {
            int k = i;
            while (k < limit && (isGraphIdentChar(src.charAt(k)) || src.charAt(k) == ':')) {
                k++;
            }
            String word = src.substring(i, k);
            if (word.equals("true") || word.equals("false")) {
                return Scalar.of(ScalarKind.BOOL, word, "", word.equals("true") ? 1 : 0,
                        i, k - 1, k);
            }
            if (k < limit && src.charAt(k) == '.') {
                int vs = k + 1;
                int ve = vs;
                while (ve < limit && isGraphIdentChar(src.charAt(ve))) {
                    ve++;
                }
                if (ve > vs) {
                    return Scalar.of(ScalarKind.ENUM, word, src.substring(vs, ve), 0,
                            i, ve - 1, ve);
                }
            }
            return Scalar.of(ScalarKind.OTHER, word, "", 0, i, k - 1, k);
        }
        return Scalar.of(ScalarKind.OTHER, "", "", 0, i, i, i + 1);
    }


    private static boolean isGraphIdentChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    /** Index of the closing quote from {@code from}, honouring backslash
     *  escapes; {@code -1} when none before {@code limit}. THE quote scan
     *  for this char-level scanner — every site must use it, never a raw
     *  {@code indexOf} (adversarial audit F26/F27). */
    private static int closingQuote(String src, int from, int limit) {
        for (int k = from; k < limit; k++) {
            char qc = src.charAt(k);
            if (qc == '\\') {
                k++;
            } else if (qc == '\'') {
                return k;
            }
        }
        return -1;
    }

    List<com.legend.protocol.spec.GraphFetchLiteral.Node> scanGraphNodes(
            String src, int[] cursor, int limit, boolean[] unsupported,
            List<com.legend.protocol.spec.GraphFetchLiteral.SubTypeNode> subTypesOut) {
        return scanGraphNodes(src, cursor, limit, unsupported, subTypesOut,
                true);
    }

    private List<com.legend.protocol.spec.GraphFetchLiteral.Node> scanGraphNodes(
            String src, int[] cursor, int limit, boolean[] unsupported,
            List<com.legend.protocol.spec.GraphFetchLiteral.SubTypeNode> subTypesOut,
            boolean root) {
        List<com.legend.protocol.spec.GraphFetchLiteral.Node> nodes = new ArrayList<>();
        int i = cursor[0];
        if (i >= limit || src.charAt(i) != '{') {
            unsupported[0] = true;
            cursor[0] = i;
            return nodes;
        }
        i++;                                        // past '{'
        while (i < limit) {
            char c = src.charAt(i);
            if (c == '}') {
                i++;
                break;
            }
            if (Character.isWhitespace(c) || c == ',') {
                i++;
                continue;
            }
            if (c == '/' && i + 1 < limit && src.charAt(i + 1) == '/') {
                while (i < limit && src.charAt(i) != '\n') {
                    i++;
                }
                continue;
            }
            if (c == '/' && i + 1 < limit && src.charAt(i + 1) == '*') {
                // block comment inside a graph-fetch tree — engine-legal
                // (adversarial audit F28: the fall-through scanned the
                // comment's words as phantom property nodes)
                i += 2;
                while (i + 1 < limit
                        && !(src.charAt(i) == '*' && src.charAt(i + 1) == '/')) {
                    i++;
                }
                i = Math.min(i + 2, limit);
                continue;
            }
            // ->subType(@X) { ... } ENTRY: this level's subTypeTrees (probe "gft root
            // subtype"; span = the class name WITHOUT the '@')
            if (c == '-' && i + 1 < limit && src.charAt(i + 1) == '>') {
                int k = i + 2;
                int ws = k;
                while (k < limit && isGraphIdentChar(src.charAt(k))) {
                    k++;
                }
                if (src.substring(ws, k).equals("subType")
                        && k + 1 < limit && src.charAt(k) == '(' && src.charAt(k + 1) == '@') {
                    if (!root) {
                        // ENGINE-VERBATIM (harvest TestDomainGrammarParser
                        // testGraphFetchTreeWithSubtypeTreeAtPropertyLevel)
                        throw new com.legend.parser.ParseException(
                                "->subType() is supported only at root"
                                        + " level");
                    }
                    int ts = k + 2;
                    int te = src.indexOf(')', ts);
                    if (te < 0 || te >= limit) {
                        unsupported[0] = true;
                        i = k;
                        continue;
                    }
                    int j = te + 1;
                    while (j < limit && Character.isWhitespace(src.charAt(j))) {
                        j++;
                    }
                    List<com.legend.protocol.spec.GraphFetchLiteral.Node> sub = List.of();
                    List<com.legend.protocol.spec.GraphFetchLiteral.SubTypeNode> nestedSts =
                            new ArrayList<>();
                    if (j < limit && src.charAt(j) == '{') {
                        cursor[0] = j;
                        sub = scanGraphNodes(src, cursor, limit,
                                unsupported, nestedSts, false);
                        i = cursor[0];
                    } else {
                        i = te + 1;
                    }
                    if (!nestedSts.isEmpty()) {
                        unsupported[0] = true;      // subType inside subType — unprobed
                    }
                    if (sub.isEmpty()) {
                        // the ENGINE's grammar requires a non-empty subType
                        // body (harvest fixture #18: at the closing '}' it
                        // wants properties or another ->subType)
                        throw new com.legend.parser.ParseException(
                                "a ->subType() body must not be empty");
                    }
                    subTypesOut.add(new com.legend.protocol.spec.GraphFetchLiteral.SubTypeNode(
                            src.substring(ts, te), charSpan(ts, te - 1), sub));
                    continue;
                }
                unsupported[0] = true;
                i += 2;
                continue;
            }
            String alias = null;
            if (c == '\'') {
                // 'nick' : prop — the alias rides the node's "alias" field
                // (escape-aware close scan; the alias VALUE stays raw — the
                // engine's graph-fetch alias quote-strip is naked, verified)
                int qs = i + 1;
                int qe = closingQuote(src, qs, limit);
                if (qe < 0) {
                    unsupported[0] = true;
                    i = limit;
                    break;
                }
                alias = src.substring(qs, qe);
                i = qe + 1;
                while (i < limit && Character.isWhitespace(src.charAt(i))) {
                    i++;
                }
                if (i >= limit || src.charAt(i) != ':') {
                    unsupported[0] = true;
                    continue;
                }
                i++;
                while (i < limit && Character.isWhitespace(src.charAt(i))) {
                    i++;
                }
                if (i >= limit || !isGraphIdentChar(src.charAt(i))) {
                    unsupported[0] = true;
                    continue;
                }
                c = src.charAt(i);
            }
            if (isGraphIdentChar(c)) {
                int s = i;
                while (i < limit && isGraphIdentChar(src.charAt(i))) {
                    i++;
                }
                String prop = src.substring(s, i);
                com.legend.protocol.SourceInfo pos = charSpan(s, i - 1);
                int j = i;
                while (j < limit && Character.isWhitespace(src.charAt(j))) {
                    j++;
                }
                List<ValueSpecification> params = List.of();
                if (j < limit && src.charAt(j) == '(') {
                    cursor[0] = j;
                    params = scanGraphArgs(src, cursor, limit, unsupported);
                    i = cursor[0];
                    j = i;
                    while (j < limit && Character.isWhitespace(src.charAt(j))) {
                        j++;
                    }
                }
                String subType = null;
                if (j + 1 < limit && src.charAt(j) == '-' && src.charAt(j + 1) == '>') {
                    // prop->subType(@X) — the view rides the node's "subType" field;
                    // any other arrow form is unprobed
                    int k = j + 2;
                    int ws = k;
                    while (k < limit && isGraphIdentChar(src.charAt(k))) {
                        k++;
                    }
                    if (src.substring(ws, k).equals("subType")
                            && k + 1 < limit && src.charAt(k) == '(' && src.charAt(k + 1) == '@') {
                        int ts = k + 2;
                        int te = src.indexOf(')', ts);
                        if (te >= 0 && te < limit) {
                            subType = src.substring(ts, te);
                            i = te + 1;
                            j = i;
                            while (j < limit && Character.isWhitespace(src.charAt(j))) {
                                j++;
                            }
                        } else {
                            unsupported[0] = true;
                        }
                    } else {
                        unsupported[0] = true;
                    }
                }
                List<com.legend.protocol.spec.GraphFetchLiteral.Node> sub = List.of();
                List<com.legend.protocol.spec.GraphFetchLiteral.SubTypeNode> nodeSts =
                        new ArrayList<>();
                if (j < limit && src.charAt(j) == '{') {
                    cursor[0] = j;
                    sub = scanGraphNodes(src, cursor, limit, unsupported,
                            nodeSts, false);
                    i = cursor[0];
                }
                nodes.add(new com.legend.protocol.spec.GraphFetchLiteral.Node(
                        prop, pos, params, alias, subType, sub, nodeSts));
                continue;
            }
            // root-level ->subType, quoted names in other positions — wire shapes unprobed
            unsupported[0] = true;
            i++;
        }
        cursor[0] = i;
        return nodes;
    }

    /**
     * Property call arguments inside a graph-fetch island: string (span includes quotes),
     * integer, {@code $var} (span = NAME only, no dollar), {@code %date} (written keeps the
     * {@code %} — the wire emits it verbatim as {@code dateTime}), {@code Enum.VALUE}
     * (whole dotted span). Anything else marks the literal unsupported.
     */
    List<ValueSpecification> scanGraphArgs(
            String src, int[] cursor, int limit, boolean[] unsupported) {
        return scanGraphArgs(src, cursor, limit, unsupported, ')');
    }

    List<ValueSpecification> scanGraphArgs(
            String src, int[] cursor, int limit, boolean[] unsupported, char closer) {
        List<ValueSpecification> out = new ArrayList<>();
        int i = cursor[0] + 1;                      // past '(' or '['
        while (i < limit) {
            char c = src.charAt(i);
            if (c == closer) {
                i++;
                break;
            }
            if (c == '[') {
                // collection argument — elements share the scalar-arg grammar
                cursor[0] = i;
                out.add(new PureCollection(
                        scanGraphArgs(src, cursor, limit, unsupported, ']'), null));
                i = cursor[0];
                continue;
            }
            if (Character.isWhitespace(c) || c == ',') {
                i++;
                continue;
            }
            Scalar sc = scanScalar(src, i, limit);
            switch (sc.kind()) {
                case SKIP -> { }
                case STRING -> out.add(new CString(sc.a(), charSpan(sc.s(), sc.e())));
                case VAR -> out.add(new Variable(sc.a(), null, null,
                        charSpan(sc.s(), sc.e())));
                case DATE -> {
                    try {
                        out.add(new CDate(
                                com.legend.values.PureDateLiteral.parse(sc.a()),
                                sc.b(), charSpan(sc.s(), sc.e())));
                    } catch (IllegalArgumentException e) {
                        unsupported[0] = true;
                    }
                }
                case INT -> out.add(new CInteger(sc.intValue(), charSpan(sc.s(), sc.e())));
                case BOOL -> out.add(new CBoolean(sc.intValue() == 1,
                        charSpan(sc.s(), sc.e())));
                case ENUM -> out.add(new EnumValue(sc.a(), sc.b(), null,
                        charSpan(sc.s(), sc.e())));
                // LATEST / NUMERICISH / OTHER: wire unprobed in graph position
                default -> unsupported[0] = true;
            }
            i = sc.next();
        }
        cursor[0] = i;
        return out;
    }
}
