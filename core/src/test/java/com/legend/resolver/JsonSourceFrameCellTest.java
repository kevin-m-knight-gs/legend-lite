// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.resolver;

import com.legend.compiler.element.type.Type;
import com.legend.error.NotImplementedException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F7.3 pins: the JSON source frame types each cell by the DECLARED
 * column — the old {@code String.valueOf} carrier erased JSON types
 * back to text (a nested object rode as Java's {@code {a=1}}, a JSON
 * string {@code "null"} silently became SQL NULL through the grid's
 * null spelling).
 */
@DisplayName("JsonSourceFrame cell typing (F7.3)")
class JsonSourceFrameCellTest {

    private static final Type VARIANT = new Type.ClassType(
            com.legend.builtin.Pure.VARIANT.qualifiedName());

    @Test
    @DisplayName("a nested object under a Variant column is REAL JSON (quote-wrapped for the grid)")
    void variantCellIsRealJson() {
        String cell = JsonSourceFrame.cellText(
                Map.of("k", 1L, "tags", List.of("x", "y")),
                VARIANT, "m::C", "meta");
        assertTrue(cell.startsWith("\"") && cell.endsWith("\""), cell);
        String json = cell.substring(1, cell.length() - 1);
        assertTrue(json.contains("\"k\":1"), json);
        assertTrue(json.contains("[\"x\",\"y\"]"), json);
    }

    @Test
    @DisplayName("a structured value under a SCALAR column walls loudly (was Java Map.toString)")
    void structuredUnderScalarWalls() {
        var e = assertThrows(NotImplementedException.class, () ->
                JsonSourceFrame.cellText(Map.of("a", 1L),
                        Type.Primitive.STRING, "m::C", "meta"));
        assertTrue(e.getMessage().contains(
                "structured value under scalar property"), e.getMessage());
    }

    @Test
    @DisplayName("a JSON string spelling the grid null token walls (was silent SQL NULL)")
    void nullStringCollisionWalls() {
        var e = assertThrows(NotImplementedException.class, () ->
                JsonSourceFrame.cellText("null",
                        Type.Primitive.STRING, "m::C", "name"));
        assertTrue(e.getMessage().contains("collides with the TDS-grid"),
                e.getMessage());
    }

    @Test
    @DisplayName("scalar values keep their text; JSON null keeps the grid null token")
    void scalarAndNullCells() {
        assertEquals("30", JsonSourceFrame.cellText(30L,
                Type.Primitive.INTEGER, "m::C", "age"));
        assertEquals(com.legend.compiler.element.type.PlatformTypes
                        .TDS_NULL_CELL,
                JsonSourceFrame.cellText(null,
                        Type.Primitive.STRING, "m::C", "name"));
    }
}
