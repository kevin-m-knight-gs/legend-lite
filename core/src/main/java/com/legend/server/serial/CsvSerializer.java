package com.legend.server.serial;

/**
 * The CSV wire format's metadata (RFC 4180, CRLF line endings, header
 * row). E5 (JAVA_EVICTION_PLAN): the text itself is PLAN-RENDERED —
 * {@code Render.csvWire} composes it in SQL through the one RFC 4180
 * escape owner; this class carries only the registry surface.
 *
 * GraalVM native-image compatible (no external dependencies).
 */
public final class CsvSerializer implements ResultSerializer {

    public static final CsvSerializer INSTANCE = new CsvSerializer();

    private CsvSerializer() {
    }

    @Override
    public String formatId() {
        return "csv";
    }

    @Override
    public String contentType() {
        return "text/csv";
    }

    @Override
    public boolean supportsStreaming() {
        return true;
    }
}
