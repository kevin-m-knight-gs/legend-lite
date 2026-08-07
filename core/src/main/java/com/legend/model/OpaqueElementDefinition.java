// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.model;

/**
 * An OVERLAY section grammar's element (GRAMMAR_EXTENSIBILITY.md step 3 —
 * the opaque carrier): produced through {@code com.legend.spi.ElementSink},
 * indexed, named and routed by core like any element, but NEVER opened —
 * the payload is the extension's protocol JSON and compiling it is the
 * extension's job (the plug-in unit is a language MODULE, not a grammar).
 * Compiler phase dispatches fall through their {@code default} arms for
 * this variant by design.
 */
public record OpaqueElementDefinition(String qualifiedName, String sectionName,
        String protocolJson) implements PackageableElement {
}
