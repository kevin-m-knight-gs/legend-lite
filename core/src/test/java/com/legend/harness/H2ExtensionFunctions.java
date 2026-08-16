// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.harness;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * JDK-only mirrors of the engine's {@code LegendH2Extensions} SQL functions,
 * registered as H2 aliases on the golden-replay connection (correctness lane
 * C1: golden SQL calling {@code legend_h2_extension_*} previously DECLINED
 * verification — "Function not found"). Semantics copied from the engine
 * class verbatim; null-in-null-out like the originals.
 */
public final class H2ExtensionFunctions {

    private H2ExtensionFunctions() {
    }

    public static @com.legend.Nullable String legend_h2_extension_base64_encode(
            @com.legend.Nullable String s) {
        return s == null ? null
                : Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    public static @com.legend.Nullable String legend_h2_extension_base64_decode(
            @com.legend.Nullable String s) {
        return s == null ? null
                : new String(Base64.getDecoder().decode(s), StandardCharsets.UTF_8);
    }

    public static @com.legend.Nullable String legend_h2_extension_reverse_string(
            @com.legend.Nullable String s) {
        return s == null ? null : new StringBuilder(s).reverse().toString();
    }

    public static @com.legend.Nullable String legend_h2_extension_hash_md5(
            @com.legend.Nullable String s) {
        if (s == null) {
            return null;
        }
        try {
            byte[] d = java.security.MessageDigest.getInstance("MD5")
                    .digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(d.length * 2);
            for (byte b : d) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16))
                        .append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The alias DDL for the replay connection — engine spellings, lite
     *  implementations. */
    public static java.util.List<String> aliases() {
        String cls = H2ExtensionFunctions.class.getName();
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String fn : new String[]{"base64_encode", "base64_decode",
                "reverse_string", "hash_md5"}) {
            out.add("CREATE ALIAS IF NOT EXISTS legend_h2_extension_" + fn
                    + " FOR \"" + cls + ".legend_h2_extension_" + fn + "\"");
        }
        return out;
    }
}
