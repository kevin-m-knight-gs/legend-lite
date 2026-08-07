// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.compiler.spec;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * THE engine signature-mangle grammar — one implementation (text-surgery
 * audit §1.1 #4: three demanglers with three grammars each fired on lookup
 * miss and could silently redirect {@code compute_Step_2_()} to a different
 * existing {@code compute()}).
 *
 * <p>Shape: {@code base(_Type_mult)+_} — segments of {@code _Type_mult}
 * (mult: digits, {@code MANY}, or {@code $a_b$} ranges) with a MANDATORY
 * trailing underscore; requiring it keeps ordinary snake-case names
 * ({@code transform_step_3}) from falsely demangling. A demangle is only
 * VALID against a candidate whose arity matches the tail's parameter count
 * (segments minus the return) — callers must filter by {@link #tailArity}
 * and treat no-arity-match as no-match, never fall back to the raw strip.
 */
public final class SignatureMangle {

    private SignatureMangle() {
    }

    private static final Pattern MANGLED_TAIL = Pattern
            .compile("(?:_?_[A-Za-z][A-Za-z0-9]*_(?:\\d+|MANY|\\$[^$]*\\$))+_$");

    /** The mangled tail's start index in {@code name}, or -1 when the name
     *  carries no signature tail. */
    public static int tailStart(String name) {
        Matcher m = MANGLED_TAIL.matcher(name);
        return m.find() ? m.start() : -1;
    }

    /** The base name with the signature tail stripped; {@code null} when
     *  there is no tail. */
    public static @com.legend.Nullable String stripTail(String name) {
        int at = tailStart(name);
        return at < 0 ? null : name.substring(0, at);
    }

    /** Parameter count the tail encodes: one {@code _Type_mult} segment per
     *  parameter plus one for the return type. */
    public static int tailArity(String name) {
        int at = tailStart(name);
        if (at < 0) {
            return -1;
        }
        Matcher seg = Pattern.compile("_?_[A-Za-z][A-Za-z0-9]*_(?:\\d+|MANY|\\$[^$]*\\$)")
                .matcher(name.substring(at, name.length() - 1));
        int n = 0;
        while (seg.find()) {
            n++;
        }
        return n - 1;
    }

    /** The RETURN type's simple name from the tail's LAST segment, or null.
     *  The arity filter alone cannot reject every accidental spelling —
     *  {@code compute_Step_2_} parses as a plausible zero-param mangle of
     *  {@code compute} returning {@code Step[2]} — so callers holding typed
     *  candidates must also require the return-type name to round-trip. */
    public static @com.legend.Nullable String tailReturnTypeName(String name) {
        int at = tailStart(name);
        if (at < 0) {
            return null;
        }
        Matcher seg = Pattern.compile("_?_([A-Za-z][A-Za-z0-9]*)_(?:\\d+|MANY|\\$[^$]*\\$)")
                .matcher(name.substring(at, name.length() - 1));
        String last = null;
        while (seg.find()) {
            last = seg.group(1);
        }
        return last;
    }
}
