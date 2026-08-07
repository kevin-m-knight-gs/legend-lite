// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.spi;

/**
 * THE overlay seam (GRAMMAR_EXTENSIBILITY.md step 2): one {@code ###} section
 * grammar, discovered via {@link java.util.ServiceLoader}. An internal
 * closed-source jar adds sections legend-lite does not know by shipping an
 * implementation — raw text in (foreign grammars never adopt our lexer),
 * protocol elements out, mirroring the engine's
 * {@code PureGrammarParserExtension.getExtraSectionParsers()} contract.
 *
 * <p>Extensions WIN over built-ins — the same shadowing rule that lets the
 * engine's SPI host legend-lite itself for {@code ###Pure}; tests, not
 * policy, catch abuse.
 *
 * <p>This package is the future {@code legend-lite-spi} artifact: keep it
 * small and stable, and never let an implementation reach into core
 * internals — the contract is these three types only.
 */
public interface SectionGrammar {

    /** The section name this grammar owns ({@code ###Name}). */
    String name();

    /** Whether the SHARED lexer may tokenize the section's content. An
     *  opaque DSL (color literals, foreign syntax) must return false and
     *  receives its raw text via {@link #parse}. */
    default boolean lexable() {
        return false;
    }

    /** Parse one section occurrence. {@code src} carries the raw text and
     *  its absolute offsets; emit elements through {@code out}. Throw to
     *  reject — a positioned failure beats silence. */
    void parse(SectionSource src, ElementSink out);
}
