// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.resolver;

/**
 * THE ASOR store-object-reference protocol — ONE owner (F3.4; audit
 * A11 found the format spelled three times: GraphEmission's encode,
 * Substitution's resolve-time decode via a {@code lastIndexOf(":{")}
 * heuristic, and the harness's full codec with its own copy of the
 * prefix). Layout, decoded from the engine goldens:
 * {@code 001:010:} then len10-prefixed segments
 * ({@code %010d:value:}): store kind, the DEFINING (include-aware)
 * mapping, the root set id, the set id, the canonical test-H2
 * connection protocol JSON, and the per-row pk-map JSON. References
 * travel base64-encoded with an {@code ASOR:} prefix.
 */
public final class AsorRef {

    private AsorRef() {
    }

    public static final String KIND = "Relational";

    /** The engine protocol's canonical test-H2 connection segment —
     * part of the WIRE FORMAT (both producer and consumer are ours;
     * the goldens pin these exact bytes), not an execution choice. */
    public static final String CANONICAL_H2_CONNECTION =
            "{\"_type\":\"RelationalDatabaseConnection\","
            + "\"authenticationStrategy\":{\"_type\":\"h2Default\"},"
            + "\"datasourceSpecification\":{\"_type\":\"h2Local\"},"
            + "\"element\":\"\",\"postProcessorWithParameter\":[],"
            + "\"postProcessors\":[],\"timeZone\":\"GMT\","
            + "\"type\":\"H2\"}";

    public static String seg(String v) {
        return String.format("%010d", v.length()) + ":" + v + ":";
    }

    /** The STATIC prefix — everything before the per-row pk segment. */
    public static String prefix(String definingMapping, String rootSetId,
            String setId) {
        return "001:010:" + seg(KIND) + seg(definingMapping)
                + seg(rootSetId) + seg(setId) + seg(CANONICAL_H2_CONNECTION);
    }

    /** A decoded reference: the segments a consumer reads. */
    public record Ref(String mapping, String rootSetId, String setId,
            String pkJson) {
    }

    /** Decode an {@code ASOR:}-prefixed (or bare) base64 reference by
     * the REAL segment walk — never a substring heuristic. Null when
     * the text is not a well-formed reference (callers keep their loud
     * walls). */
    public static @com.legend.Nullable Ref decode(String ref) {
        try {
            String b64 = ref.startsWith("ASOR:") ? ref.substring(5) : ref;
            String d = new String(java.util.Base64.getDecoder().decode(
                    b64 + "=".repeat((4 - b64.length() % 4) % 4)),
                    java.nio.charset.StandardCharsets.UTF_8);
            java.util.List<String> segs = new java.util.ArrayList<>();
            int i = "001:010:".length();
            while (i < d.length() && segs.size() < 6) {
                int len = Integer.parseInt(d.substring(i, i + 10));
                segs.add(d.substring(i + 11, i + 11 + len));
                i += 11 + len + 1;
            }
            if (segs.size() < 6) {
                return null;
            }
            return new Ref(segs.get(1), segs.get(2), segs.get(3),
                    segs.get(5));
        } catch (IllegalArgumentException | IndexOutOfBoundsException e) {
            // bad base64 / bad length digits / short segment — not a
            // reference: the caller's wall owns it
            return null;
        }
    }
}
