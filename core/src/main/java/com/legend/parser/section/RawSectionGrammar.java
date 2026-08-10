// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.parser.section;

import com.legend.protocol.Protocol;

/**
 * A built-in grammar for a section whose content the SHARED lexer must not
 * touch ({@code lexable() == false} — Diagram color literals are unlexable
 * Pure). The lexer raw-skips the section; {@code ElementParser.parseModel}
 * routes the recorded skip here for TYPED elements — the same claimed-seam
 * routing lexable sections get, over raw text instead of tokens. Distinct
 * from the OVERLAY path, which delivers foreign payloads as the locked
 * opaque carrier.
 */
public interface RawSectionGrammar extends com.legend.spi.SectionGrammar {

    @Override
    default boolean lexable() {
        return false;
    }

    /** Parse one raw section occurrence into protocol elements with
     *  SECTION-RELATIVE start offsets — the PURE-MODE entry (full dialect). */
    LexableSectionGrammar.ParsedSection parseRaw(com.legend.spi.SectionSource src);

    /** As {@link #parseRaw(com.legend.spi.SectionSource)} with the parse
     *  mode ({@code TokenStreamCursor#legendStrict()}): strict refuses
     *  pure-dialect-only forms (m2 diagram views). The default ignores the
     *  mode — for grammars whose section has no dialect split. */
    default LexableSectionGrammar.ParsedSection parseRaw(
            com.legend.spi.SectionSource src, boolean legendStrict) {
        return parseRaw(src);
    }

    /** Transform one parsed element into the model. */
    com.legend.model.PackageableElement toModel(Protocol.Element element);
}
