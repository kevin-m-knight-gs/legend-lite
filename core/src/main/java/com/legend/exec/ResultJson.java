package com.legend.exec;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;

/**
 * JSON serialization of an {@link ExecutionResult} — the array-of-row-objects
 * wire (each row is one object keyed by column name). Ported from the engine
 * module's {@code ExecutionResult.toJsonArray}/{@code writeJsonValue} with the
 * same value policy: integral numbers emit as JSON integers, other numbers as
 * doubles, booleans as booleans, everything else as an RFC 8259-escaped string
 * of its {@code toString()}. A {@link ExecutionResult.Graph} result's json IS
 * a well-formed array (built by the database) — returned verbatim, never
 * re-wrapped.
 *
 * <p>Composite cells are core-specific (the engine's rows were raw JDBC):
 * {@link Executor}'s unwrap hands back {@code List} (array cells) and ordered
 * {@code Map} (struct cells) — emitted as JSON arrays/objects, since their
 * {@code toString()} forms are not JSON.
 *
 * <p>The row primitives are package-private so {@link Executor}'s streaming
 * path can emit rows one at a time without materializing a result.
 */
public final class ResultJson {

    private ResultJson() {
    }

    /** The whole result as one JSON array string. */
    public static String toJsonArray(ExecutionResult result) {
        if (result instanceof ExecutionResult.Graph g) {
            return g.json() != null ? g.json() : "[]";
        }
        StringBuilder sb = new StringBuilder();
        try {
            write(result, sb);
        } catch (IOException e) {
            throw new UncheckedIOException(e); // StringBuilder never throws
        }
        return sb.toString();
    }

    /** Serialize {@code result} to {@code out} without an intermediate String. */
    public static void write(ExecutionResult result, Appendable out) throws IOException {
        if (result instanceof ExecutionResult.Graph g) {
            out.append(g.json() != null ? g.json() : "[]");
            return;
        }
        List<Column> columns = result.columns();
        out.append('[');
        boolean first = true;
        for (Row row : result.rows()) {
            if (!first) {
                out.append(',');
            }
            first = false;
            writeRow(out, columns, row.values());
        }
        out.append(']');
    }

    /** One row as a JSON object keyed by column name. */
    static void writeRow(Appendable out, List<Column> columns, List<Object> cells)
            throws IOException {
        out.append('{');
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            writeString(out, columns.get(i).name());
            out.append(':');
            writeValue(out, cells.get(i));
        }
        out.append('}');
    }

    static void writeValue(Appendable out, @com.legend.Nullable Object v) throws IOException {
        switch (v) {
            case null -> out.append("null");
            case Boolean b -> out.append(b.toString());
            case Long l -> out.append(l.toString());
            case Integer i -> out.append(i.toString());
            case Short s -> out.append(s.toString());
            case Byte b -> out.append(b.toString());
            case Number n -> out.append(Double.toString(n.doubleValue()));
            case List<?> list -> {
                out.append('[');
                for (int i = 0; i < list.size(); i++) {
                    if (i > 0) {
                        out.append(',');
                    }
                    writeValue(out, list.get(i));
                }
                out.append(']');
            }
            case Map<?, ?> map -> {
                out.append('{');
                boolean first = true;
                for (var e : map.entrySet()) {
                    if (!first) {
                        out.append(',');
                    }
                    first = false;
                    writeString(out, String.valueOf(e.getKey()));
                    out.append(':');
                    writeValue(out, e.getValue());
                }
                out.append('}');
            }
            default -> writeString(out, v.toString());
        }
    }

    /** RFC 8259 string emission — the ONE table
     *  ({@link com.legend.protocol.Escapes#jsonEscape}, F3.1c),
     *  lowercase hex. */
    static void writeString(Appendable out, String s) throws IOException {
        out.append('"');
        com.legend.protocol.Escapes.jsonEscape(out, s, false);
        out.append('"');
    }
}
