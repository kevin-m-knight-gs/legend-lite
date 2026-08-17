// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.protocol;

/**
 * THE escape table (F3.6) — commons-text {@code unescapeJava}
 * semantics, JDK-only: octal escapes ({@code \101}), unicode escapes
 * ({@code \-u+XXXX}), the control table ({@code \b \n \t \f \r}),
 * the quote/backslash table, and DROP-BACKSLASH for anything else
 * (including a lone trailing backslash, which commons deletes). ONE
 * owner for BOTH decode sites: the parser's token path
 * ({@code TokenStreamCursor.unescapeBody}) and the wire path's quoted
 * FQN segments ({@code Protocol.unquoteSegments}) — the wire's own
 * copy refused octal/{@code \-u} (both fell to drop-backslash), so
 * {@code Class a::'x\101y'} decoded differently in element-name and
 * property-name position (audit §5; the parser side was fixed
 * 2026-08-12, the wire copy was not). Protocol is the BOTTOM parse-
 * product layer (ArchitectureTest 7b: protocol → values + JDK only),
 * so the parser delegates DOWN — never a second table.
 */
public final class Escapes {

    private Escapes() {
    }

    /** THE JSON string-escape WRITE table (F3.1c) — RFC-8259, matching
     *  Jackson's default output: quote/backslash and the named controls
     *  ({@code \b \f \n \r \t}), every other control as {@code \-uXXXX}.
     *  The ONE knob is hex case: Jackson writes UPPERCASE hex, and the
     *  protocol emitter's byte-parity goldens pin that; the server and
     *  result writers historically emit lowercase. Before F3.1c this
     *  table was spelled three times (server/Json.escapeTo,
     *  ProtocolEmitter.str, ResultJson.writeString) differing ONLY in
     *  hex case — all three now delegate here. */
    public static void jsonEscape(Appendable out, String s,
            boolean upperHex) throws java.io.IOException {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (c < 0x20) {
                        String hex = Integer.toHexString(c);
                        out.append("\\u");
                        for (int p = hex.length(); p < 4; p++) {
                            out.append('0');
                        }
                        out.append(upperHex
                                ? hex.toUpperCase(java.util.Locale.ROOT)
                                : hex);
                    } else {
                        out.append(c);
                    }
                }
            }
        }
    }

    /** Decode; throws {@link IllegalArgumentException} on a unicode
     *  escape with fewer than 4 hex digits (commons parity — the
     *  parser wraps it in a located error, the wire path never sees
     *  malformed input because the parser refused it earlier). */
    public static String unescapeJavaLike(String s) {
        if (s.indexOf('\\') < 0) {
            return s;
        }
        StringBuilder sb = new StringBuilder(s.length());
        int i = 0;
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c != '\\' || i + 1 >= n) {
                if (c != '\\') {
                    sb.append(c);
                }                                    // lone trailing '\' drops
                i++;
                continue;
            }
            char esc = s.charAt(i + 1);
            if (esc >= '0' && esc <= '7') {          // octal, 1-3 digits, ≤ \377
                int k = i + 1;
                int val = 0;
                int max = esc <= '3' ? 3 : 2;
                while (k < n && k - i <= max && s.charAt(k) >= '0'
                        && s.charAt(k) <= '7') {
                    val = val * 8 + (s.charAt(k) - '0');
                    k++;
                }
                sb.append((char) val);
                i = k;
                continue;
            }
            if (esc == 'u') {                        // backslash-u+XXXX (extra u's legal)
                int k = i + 2;
                while (k < n && s.charAt(k) == 'u') {
                    k++;
                }
                if (k + 4 <= n) {
                    int val = 0;
                    boolean ok = true;
                    for (int h = 0; h < 4; h++) {
                        int d = Character.digit(s.charAt(k + h), 16);
                        if (d < 0) {
                            ok = false;
                            break;
                        }
                        val = val * 16 + d;
                    }
                    if (ok) {
                        sb.append((char) val);
                        i = k + 4;
                        continue;
                    }
                }
                throw new IllegalArgumentException(
                        "Less than 4 hex digits in unicode escape");
            }
            switch (esc) {
                case 'n' -> sb.append('\n');
                case 't' -> sb.append('\t');
                case 'r' -> sb.append('\r');
                case 'b' -> sb.append('\b');
                case 'f' -> sb.append('\f');
                default -> sb.append(esc);           // \' \" \\ + drop-backslash
            }
            i += 2;
        }
        return sb.toString();
    }
}
