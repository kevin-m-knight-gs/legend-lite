// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.model;

import com.legend.protocol.Protocol;

import java.util.Objects;

/**
 * A {@code ###Data} element: a named body of embedded test data — a
 * {@code Relational #{ schema.table: 'csv'; }#} block, a {@code ModelStore},
 * an {@code ExternalFormat} payload, or the resolver form — that service,
 * mapping and function test suites reference by name
 * ({@code Reference #{ my::Data }#}).
 *
 * <p>The model carries the PROTOCOL body as-is (the model is a transform on
 * the protocol; the data kinds are already fully typed there and nothing
 * needs a second record shape). A test runner resolves the reference, reads
 * the body and provisions the store it names — the element is data, never
 * compiled.
 *
 * <p>Retires the {@code ###Data} exemption of the opaque overlay carrier
 * (ArchitectureTest {@code opaqueCarrierIsLockedToTheOverlaySeam},
 * PARSER_COMPLETENESS_PLAN.md §3.1): a data element is now visible to the
 * model like every other built-in element.
 *
 * @param qualifiedName the element's fully qualified name
 * @param element       the parsed protocol element (its body holds the data)
 */
public record DataDefinition(String qualifiedName, Protocol.PDataElement element)
        implements PackageableElement {

    public DataDefinition {
        Objects.requireNonNull(qualifiedName, "Qualified name cannot be null");
        Objects.requireNonNull(element, "Data element cannot be null");
    }

    /** The data body: an optional embedded value and optional resolvers. */
    public Protocol.PDataBody body() {
        return element.body();
    }
}
