package com.legend.server.serial;

/**
 * The JSON wire format's metadata (array of row objects). E5
 * (JAVA_EVICTION_PLAN): the text itself is PLAN-RENDERED —
 * {@code Render.jsonWire}'s {@code json_object} rows, the DATABASE'S
 * value policy and RFC 8259 escaping; this class carries only the
 * registry surface.
 *
 * GraalVM native-image compatible (no external dependencies).
 */
public final class JsonSerializer implements ResultSerializer {

    public static final JsonSerializer INSTANCE = new JsonSerializer();

    private JsonSerializer() {
    }

    @Override
    public String formatId() {
        return "json";
    }

    @Override
    public String contentType() {
        return "application/json";
    }

    @Override
    public boolean supportsStreaming() {
        return true;
    }
}
