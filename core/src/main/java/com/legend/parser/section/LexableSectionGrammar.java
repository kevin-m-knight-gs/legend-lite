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
 * <p>{@link #parseSection} emits PROTOCOL elements; the model transform stays
 * on the {@code FromProtocol} side of the seam and is reached via
 * {@link #toModel}, so the parser/model boundary stays where it is for every
 * other element kind.
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

    /** Transform one of {@link #parseSection}'s protocol elements into the
     *  model. Throws {@link UnsupportedElementShape} for shapes the model
     *  cannot represent; the caller positions the failure. */
    com.legend.model.PackageableElement toModel(Protocol.Element element);

    /** One parsed section: protocol elements with their absolute start
     *  offsets (source order), plus the section's own imports. */
    record ParsedSection(List<ParsedElement> elements, List<String> imports) {
    }

    /** One parsed element and the char offset its declaration starts at. */
    record ParsedElement(Protocol.Element protocol, int startOffset) {
    }

    /** A parse product the model cannot represent; carries the refusal
     *  reason for the caller to position. */
    final class UnsupportedElementShape extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private final String reason;

        public UnsupportedElementShape(String reason) {
            super(reason);
            this.reason = reason;
        }

        public String reason() {
            return reason;
        }
    }
}
