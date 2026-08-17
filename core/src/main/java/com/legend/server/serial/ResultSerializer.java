package com.legend.server.serial;

/**
 * A wire FORMAT's metadata surface — id, MIME type, streaming
 * capability. E5 (JAVA_EVICTION_PLAN): the result TEXT itself is
 * plan-rendered ({@code Compiler.executeWire} — the database composes
 * the bytes); no serializer composes result values in Java anymore.
 *
 * GraalVM native-image compatible.
 */
public interface ResultSerializer {

    /**
     * Returns the format identifier (e.g., "json", "csv").
     */
    String formatId();

    /**
     * Returns the MIME content type for HTTP responses.
     */
    String contentType();

    /**
     * Returns true if this format supports true streaming
     * (rows can flow incrementally without buffering the entire result).
     */
    default boolean supportsStreaming() {
        return false;
    }
}
