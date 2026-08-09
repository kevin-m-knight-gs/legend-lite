package com.legend.model;

import java.util.Objects;

/**
 * A standalone model-store connection element from a {@code ###Connection}
 * section &mdash; {@code JsonModelConnection} / {@code XmlModelConnection}
 * {@code name { class: ...; url: '...'; }}. Binds a model class to a data
 * URL for M2M execution.
 *
 * <p>Distinct from {@link JsonModelConnection}, which is the ANONYMOUS
 * embedded-island form living inside a {@link RuntimeDefinition}; this one is
 * a named packageable element other elements point at.
 *
 * @param qualifiedName fully qualified connection name
 * @param kind          serialization flavor of the data behind {@code url}
 * @param className     qualified name of the model class the data instantiates
 * @param url           data location ({@code data:...,} URLs in tests)
 */
public record ModelConnectionDefinition(
        String qualifiedName,
        Kind kind,
        String className,
        String url) implements PackageableElement {

    public ModelConnectionDefinition {
        Objects.requireNonNull(qualifiedName, "Qualified name cannot be null");
        Objects.requireNonNull(kind, "Kind cannot be null");
        Objects.requireNonNull(className, "Class name cannot be null");
        Objects.requireNonNull(url, "Url cannot be null");
    }

    /** The two model-connection serializations. */
    public enum Kind {
        JSON,
        XML
    }
}
