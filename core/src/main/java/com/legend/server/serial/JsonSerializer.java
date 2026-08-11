package com.legend.server.serial;

import com.legend.exec.ExecutionResult;
import com.legend.exec.ResultJson;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;

/**
 * JSON serializer for query results — the {@link ResultJson} wire (array of
 * row objects; a Graph result's database-built JSON array passes through
 * verbatim). Value policy and RFC 8259 escaping live in {@link ResultJson};
 * this class only adapts it to the {@link ResultSerializer} registry.
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

    @Override
    public void serialize(ExecutionResult result, OutputStream out) throws IOException {
        try (Writer writer = new OutputStreamWriter(out, StandardCharsets.UTF_8)) {
            // Rows flow to the OutputStream as they are emitted — no
            // intermediate String of the whole result.
            ResultJson.write(result, writer);
        }
    }
}
