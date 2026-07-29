// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.lowering;

import com.legend.sql.DateFmt;

import java.util.ArrayList;
import java.util.List;

/** Date-format pattern translation for the scalar lowering rules. */
final class DateFormats {

    private DateFormats() {
    }

    /**
     * Java SimpleDateFormat pattern -> TYPED parts, longest token first
     * (remediation T3.2: dialects spell parts; nobody re-parses). Values
     * are UTC throughout, so the ZONE directives are literals: Z prints the
     * +0000 offset, X the ISO 'Z'.
     */
    static List<DateFmt> javaDateToParts(String pattern) {
        List<DateFmt> out = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        int i = 0;
        while (i < pattern.length()) {
            if (pattern.charAt(i) == '"') {
                int close = pattern.indexOf('"', i + 1);
                if (close < 0) {
                    throw new IllegalStateException("unterminated quote in date pattern: " + pattern);
                }
                text.append(pattern, i + 1, close);
                i = close + 1;
                continue;
            }
            String rest = pattern.substring(i);
            Object[][] tokens = {
                    {"yyyy", DateFmt.Part.YEAR4}, {"SSS", DateFmt.Part.SUBSEC_MIN},
                    {"MM", DateFmt.Part.MONTH2}, {"dd", DateFmt.Part.DAY2},
                    {"HH", DateFmt.Part.HOUR2}, {"hh", DateFmt.Part.HOUR12},
                    {"mm", DateFmt.Part.MIN2}, {"ss", DateFmt.Part.SEC2},
                    {"h", DateFmt.Part.HOUR12_NOPAD}, {"a", DateFmt.Part.AMPM},
            };
            String[][] literals = {{"Z", "+0000"}, {"X", "Z"}};
            boolean matched = false;
            for (Object[] t : tokens) {
                if (rest.startsWith((String) t[0])) {
                    if (text.length() > 0) {
                        out.add(new DateFmt.Text(text.toString()));
                        text.setLength(0);
                    }
                    out.add((DateFmt.Part) t[1]);
                    i += ((String) t[0]).length();
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                for (String[] t : literals) {
                    if (rest.startsWith(t[0])) {
                        text.append(t[1]);
                        i += t[0].length();
                        matched = true;
                        break;
                    }
                }
            }
            if (!matched) {
                char ch = pattern.charAt(i);
                // A pattern LETTER outside the token table would silently
                // pass through as literal text ('MMM' -> '03M'; audit) —
                // loud instead; punctuation/separators pass.
                if (Character.isLetter(ch)) {
                    throw new IllegalStateException("unsupported date-format"
                            + " token '" + ch + "' in pattern '" + pattern + "'");
                }
                text.append(ch);
                i++;
            }
        }
        if (text.length() > 0) {
            out.add(new DateFmt.Text(text.toString()));
        }
        return out;
    }

    /**
     * Longest-first token map: engine format spellings (SimpleDateFormat +
     * the Oracle-style TO_CHAR tokens the corpus mixes in) to DuckDB
     * strptime. Case is load-bearing (MM month vs mm minutes). Two entries
     * are pinned by ENGINE-PRODUCED expected values rather than a spec:
     * '.mmm' and '.FF' both read as fractional seconds — the sqlFunction
     * corpus asserts %2016-06-23T15:03:00.000 for 'yyyy-MM-dd hh:mm:ss.mmm'
     * over '2016-06-23 15:03:00.000000000', i.e. the engine result treats
     * the tail as millis, not minutes (SimpleDateFormat's reading would
     * shift the minute field). SSS is SimpleDateFormat millis proper.
     */
    private static final String[][] FORMAT_TOKENS = {
            {"HH24", "%H"}, {"SSS", "%g"}, {"MMM", "%b"}, {"MON", "%b"},
            {"mmm", "%g"},
            {"yyyy", "%Y"}, {"YYYY", "%Y"}, {"MM", "%m"}, {"dd", "%d"},
            {"DD", "%d"}, {"MI", "%M"}, {"mm", "%M"}, {"hh", "%H"},
            {"HH", "%H"}, {"ss", "%S"}, {"SS", "%S"}, {"FF", "%g"},
    };

    /** Pure's format vocabulary parses ONCE, here, into TYPED parts —
     * no dialect ever re-parses a format string (remediation T3.2). */
    static List<DateFmt> pureToParts(String src) {
        List<DateFmt> out = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        int i = 0;
        outer:
        while (i < src.length()) {
            for (String[] tok : FORMAT_TOKENS) {
                if (src.startsWith(tok[0], i)) {
                    if (text.length() > 0) {
                        out.add(new DateFmt.Text(text.toString()));
                        text.setLength(0);
                    }
                    out.add(partOf(tok[1]));
                    i += tok[0].length();
                    continue outer;
                }
            }
            text.append(src.charAt(i));
            i++;
        }
        if (text.length() > 0) {
            out.add(new DateFmt.Text(text.toString()));
        }
        return out;
    }

    private static DateFmt.Part partOf(String code) {
        return switch (code) {
            case "%Y" -> DateFmt.Part.YEAR4;
            case "%m" -> DateFmt.Part.MONTH2;
            case "%d" -> DateFmt.Part.DAY2;
            case "%H" -> DateFmt.Part.HOUR2;
            case "%M" -> DateFmt.Part.MIN2;
            case "%S" -> DateFmt.Part.SEC2;
            case "%g" -> DateFmt.Part.SUBSEC_MIN;
            case "%b" -> DateFmt.Part.MONTH_ABBREV;
            default -> throw new IllegalStateException(
                    "unmapped format token: " + code);
        };
    }
}
