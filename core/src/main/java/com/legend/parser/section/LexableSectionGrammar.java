// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.parser.section;

import com.legend.parser.TokenStreamCursor;
import com.legend.protocol.Protocol;

import java.util.List;

/**
 * A BUILT-IN section grammar: one {@code ###Name} whose content flows through
 * the SHARED lexer ({@code lexable() == true}) and parses from the shared
 * token stream — the core-internal face of the
 * {@link com.legend.spi.SectionGrammar} seam.
 *
 * <p>Two feeds, ONE grammar. Hosted in core, {@link #parseSection} consumes
 * the section's range of the file's own token stream, so spans stay
 * file-absolute with no re-lex. Hosted through the SPI (an overlay jar, an
 * external engine), the inherited {@code parse(SectionSource, ElementSink)}
 * surface re-lexes the raw section text and drives the SAME parse methods.
 * Divergence between the feeds is impossible because there is nothing on
 * either side but the shared grammar.
 *
 * <p>{@link #parseSection} emits PROTOCOL elements, and only them: the model is made from them on the other side of
 * the door ({@code ModelFromProtocol}), as it is for every element kind (docs/PROTOCOL_PROGRAM_2026_10_05.md leg 8:
 * the parser returns protocol only).
 */
public interface LexableSectionGrammar extends com.legend.spi.SectionGrammar {

    @Override
    default boolean lexable() {
        return true;
    }

    /**
     * Parse ONE whole section occurrence from the shared token stream.
     * {@code host}'s cursor sits at (or before) the section's first token;
     * consume every token whose start offset precedes
     * {@code sectionEndOffset} and return the protocol elements plus any
     * {@code import} statements the section carried (engine's built-in
     * sections are import-aware).
     */
    ParsedSection parseSection(TokenStreamCursor host, int sectionEndOffset);

    /** One parsed section: protocol elements with their absolute start
     *  offsets (source order), plus the section's own imports. */
    record ParsedSection(List<ParsedElement> elements, List<String> imports) {
    }

    /** One parsed element and the char offset its declaration starts at. */
    record ParsedElement(Protocol.Element protocol, int startOffset) {
    }
}
