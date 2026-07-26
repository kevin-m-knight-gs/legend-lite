// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.exec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** THE minimal JSON reader (one for the platform): the harness's graph
 * result envelope AND the resolver's JsonModelConnection source frames
 * parse through it. Integers read as Long, decimals as BigDecimal
 * (audit 18: double rounding made distinct Decimals compare equal). */
public final class Json {
    private final String s;
    private int i;

    private Json(String s) {
        this.s = s;
    }

    public static Object parse(String json) {
        Json p = new Json(json);
        p.ws();
        Object v = p.value();
        p.ws();
        if (p.i < p.s.length()) {
            throw new IllegalStateException("trailing JSON at " + p.i);
        }
        return v;
    }

    /** CONCATENATED top-level values ({@code {..}{..}...} — the engine's
     * JsonModelConnection row stream: one object per row). Strict trailing-
     * garbage still throws; a single value returns a one-element list. */
    public static List<Object> parseAll(String json) {
        Json p = new Json(json);
        List<Object> out = new ArrayList<>();
        p.ws();
        while (p.i < p.s.length()) {
            out.add(p.value());
            p.ws();
        }
        return out;
    }

    private Object value() {
        char c = s.charAt(i);
        return switch (c) {
            case '{' -> obj();
            case '[' -> arr();
            case '"' -> str();
            case 't' -> { i += 4; yield Boolean.TRUE; }
            case 'f' -> { i += 5; yield Boolean.FALSE; }
            case 'n' -> { i += 4; yield null; }
            default -> num();
        };
    }

    private Map<String, Object> obj() {
        Map<String, Object> out = new LinkedHashMap<>();
        i++;
        ws();
        if (s.charAt(i) == '}') {
            i++;
            return out;
        }
        while (true) {
            ws();
            String k = str();
            ws();
            i++;    // ':'
            ws();
            out.put(k, value());
            ws();
            if (s.charAt(i) == ',') {
                i++;
                continue;
            }
            i++;    // '}'
            return out;
        }
    }

    private List<Object> arr() {
        List<Object> out = new ArrayList<>();
        i++;
        ws();
        if (s.charAt(i) == ']') {
            i++;
            return out;
        }
        while (true) {
            ws();
            out.add(value());
            ws();
            if (s.charAt(i) == ',') {
                i++;
                continue;
            }
            i++;    // ']'
            return out;
        }
    }

    private String str() {
        StringBuilder b = new StringBuilder();
        i++;
        while (s.charAt(i) != '"') {
            char c = s.charAt(i);
            if (c == '\\') {
                i++;
                char e = s.charAt(i);
                b.append(switch (e) {
                    case 'n' -> '\n';
                    case 't' -> '\t';
                    case 'r' -> '\r';
                    case 'b' -> '\b';
                    case 'f' -> '\f';
                    case 'u' -> {
                        char u = (char) Integer.parseInt(
                                s.substring(i + 1, i + 5), 16);
                        i += 4;
                        yield u;
                    }
                    default -> e;
                });
            } else {
                b.append(c);
            }
            i++;
        }
        i++;
        return b.toString();
    }

    private Object num() {
        int start = i;
        while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) {
            i++;
        }
        String t = s.substring(start, i);
        // Decimal tokens parse as BigDecimal, NOT double (audit 18):
        // two distinct Decimals beyond 17 significant digits round to
        // the SAME double, so a wrong Decimal wire value would compare
        // equal — the JSON bridge must stay as strict as wireEquals.
        return t.contains(".") || t.contains("e") || t.contains("E")
                ? (Object) new java.math.BigDecimal(t)
                : (Object) Long.parseLong(t);
    }

    private void ws() {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
    }
}
